<#
.SYNOPSIS
    Checks the third-party notices packed inside the AARs this app ships.

.DESCRIPTION
    The dependency licence check reads each artifact's POM. That is not enough.
    An AAR's native libraries can statically link code whose licence appears
    nowhere in the POM, and the only record of it is a notice file inside the AAR.

    This resolves the release classpath's artifacts -- which fetches them -- opens the
    notice or licence files inside each AAR, and reports any copyleft licence named
    there. Findings recorded in compliance/embedded-notices.json are printed with their
    status; anything else fails the check.

    The artifacts are resolved rather than read out of the Gradle cache, and this is
    not a detail. The first version of this check asked for the dependency report and
    then walked the cache, so on a runner where nothing had been downloaded yet it
    inspected zero artifacts and reported CLEAN -- which is how a check that exists to
    stop a licence obligation from shipping unnoticed was green in CI on every run.

    The distinction matters. Silently passing an unresolved copyleft notice is how
    a project ships a licence obligation it never noticed, and a licence
    obligation attaches to every copy already distributed.

.PARAMETER ProjectDir
    Android project root. Defaults to the parent of this script's directory.

.PARAMETER BuildDir
    Directory of a built APK to cross-check against. Optional.

.EXAMPLE
    .\check-embedded-notices.ps1
#>
[CmdletBinding()]
param(
    [string] $ProjectDir,
    [string] $BuildDir
)

$ErrorActionPreference = 'Stop'

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
if (-not $ProjectDir) { $ProjectDir = Split-Path -Parent $scriptDir }

$ackPath = Join-Path $ProjectDir 'compliance/embedded-notices.json'
if (-not (Test-Path $ackPath)) { throw "compliance/embedded-notices.json not found under $ProjectDir" }
$acknowledged = @((Get-Content $ackPath -Raw | ConvertFrom-Json).acknowledged)

# A finding is an artifact, a notice file and a term. An acknowledgement names the
# licence, so the term is what the two have in common. Matching on artifact and notice
# alone made every finding on an artifact inherit the status of whichever entry
# happened to come first in the file: the accepted MPL finding printed as unresolved
# because the unresolved GPL entry above it was read first, and the same confusion ran
# the other way in the blocking count.
$licenceTerms = @{
    'GPL'  = 'GNU GENERAL PUBLIC LICENSE'
    'LGPL' = 'GNU LESSER GENERAL PUBLIC LICENSE'
    'AGPL' = 'GNU AFFERO GENERAL PUBLIC LICENSE'
    'EPL'  = 'Eclipse Public License'
    'MPL'  = 'Mozilla Public License'
    'CDDL' = 'Common Development and Distribution License'
    'SSPL' = 'Server Side Public License'
}

function Get-AcknowledgedTerm {
    param([string] $Licence)

    if (-not $Licence) { return $null }
    $id = ($Licence -split '-')[0].ToUpperInvariant()
    if ($licenceTerms.ContainsKey($id)) { return $licenceTerms[$id] }
    return $null
}

function Get-AcknowledgementFor {
    param([object] $Finding)

    # The finding's term carries the count the notice printed ("Eclipse Public License
    # x1"); the acknowledgement names the licence. The count is the notice's own
    # arithmetic, not part of the licence name.
    $term = $Finding.Term -replace '\s+x\d+$', ''

    $acknowledged | Where-Object {
        $_.artifact -eq $Finding.Artifact -and
        $_.notice -eq $Finding.Notice -and
        (Get-AcknowledgedTerm $_.licence) -eq $term
    } | Select-Object -First 1
}

. (Join-Path $scriptDir 'gradle-query.ps1')

# Terms that indicate an obligation beyond attribution. Matched case-sensitively
# against notice text, so a casual mention inside another licence's boilerplate is
# still reported -- this check is deliberately noisy and the acknowledgement file
# is how noise is turned into a decision.
$copyleftTerms = @(
    'GNU GENERAL PUBLIC LICENSE',
    'GNU LESSER GENERAL PUBLIC LICENSE',
    'GNU AFFERO GENERAL PUBLIC LICENSE',
    'Eclipse Public License',
    'Mozilla Public License',
    'Common Development and Distribution License',
    'Server Side Public License'
)

Write-Host ''
Write-Host 'Embedded notice check' -ForegroundColor Cyan
Write-Host "  project : $ProjectDir" -ForegroundColor DarkGray

# ------------------------------------------------------------- which AARs ---

# The artifacts, as Gradle resolves them -- not the coordinate list from the
# dependency report. `:app:dependencies` names the graph and downloads nothing, so a
# walk of the Gradle cache after it read whatever happened to be cached: on a fresh
# CI runner that was nothing, and this gate printed CLEAN over zero artifacts on every
# CI run while the same command on a machine that had built the app found the
# findings. Resolving the configuration's artifacts names them and fetches them, so
# what is inspected is what the app would ship.
try {
    $artifacts = Invoke-GradleArtifactList -Root $ProjectDir -Configuration 'releaseRuntimeClasspath'
} catch {
    Write-Host ("  FAILED to resolve the release classpath: {0}" -f $_.Exception.Message) -ForegroundColor Red
    Write-Host '  nothing can be checked.' -ForegroundColor Red
    exit 1
}

$aars = @($artifacts | Where-Object { $_.Path -like '*.aar' })
Write-Host ("  release classpath: {0} artifact(s), {1} of them AAR" -f $artifacts.Count, $aars.Count) -ForegroundColor DarkGray

if ($aars.Count -eq 0) {
    # Not a pass. Either the app ships no AAR, or the resolution saw none to read, and
    # a check that cannot tell those apart must not report success -- that is the shape
    # of defect this whole file exists for.
    Write-Host ''
    Write-Host '  FAILED - the release classpath resolved to no AAR at all.' -ForegroundColor Red
    Write-Host '  Either this app ships no AAR, or the resolution saw none. A pass over' -ForegroundColor Red
    Write-Host '  nothing is not a pass.' -ForegroundColor Red
    exit 1
}

Add-Type -AssemblyName System.IO.Compression.FileSystem

$inspected = 0
$withNotice = 0
$findings = [System.Collections.Generic.List[object]]::new()

foreach ($artifact in $aars) {
    $coord = $artifact.Coordinates
    $inspected++
    $zip = $null
    try {
        $zip = [System.IO.Compression.ZipFile]::OpenRead($artifact.Path)
        $noticeEntries = $zip.Entries | Where-Object {
            $_.FullName -match '(?i)(NOTICE|LICENSE|COPYING|THIRD_PARTY)' -and $_.Length -gt 0
        }
        if (-not $noticeEntries) { continue }
        $withNotice++

        foreach ($entry in $noticeEntries) {
            $text = $null
            try {
                $reader = New-Object System.IO.StreamReader($entry.Open())
                $text = $reader.ReadToEnd()
                $reader.Close()
            } catch { continue }   # binary licence file; nothing to scan as text
            if (-not $text) { continue }

            foreach ($term in $copyleftTerms) {
                $hits = [regex]::Matches($text, [regex]::Escape($term))
                if ($hits.Count -eq 0) { continue }

                $findings.Add([pscustomobject]@{
                    Artifact = $coord
                    Notice   = $entry.FullName
                    Term     = $term
                    Hits     = $hits.Count
                })
            }
        }
    } catch {
        Write-Host ("      could not read {0}: {1}" -f $artifact.Path, $_.Exception.Message) -ForegroundColor Yellow
    } finally {
        if ($zip) { $zip.Dispose() }
    }
}

Write-Host ("  inspected {0} AAR(s); {1} carried a notice file" -f $inspected, $withNotice) -ForegroundColor DarkGray

# ------------------------------------------------------------- reporting ---

Write-Host ''
Write-Host ('-' * 78)

$unacknowledged = @($findings | Where-Object {
    $f = $_
    -not (Get-AcknowledgementFor -Finding $f)
})

if ($findings.Count -eq 0) {
    Write-Host '  CLEAN - no copyleft licence named in any embedded notice.' -ForegroundColor Green
    Write-Host ''
    exit 0
}

# Each acknowledged finding paired with the acknowledgement that covers it, so the
# decision travels with the finding. The projection is needed rather than the finding
# alone: `.status` and `.scope` live on the acknowledgement, and reading them off a
# finding is a property that does not exist.
$known = @($findings | ForEach-Object {
    $f = $_
    $ack = Get-AcknowledgementFor -Finding $f
    if ($ack) {
        [pscustomobject]@{
            Artifact = $f.Artifact
            Notice   = $f.Notice
            Term     = $f.Term
            Hits     = $f.Hits
            status   = $ack.status
            # An accepted entry carries no scope: there is nothing to warn about. Read it
            # through the property bag rather than directly, because StrictMode 3.0 turns
            # a missing property into a terminating error.
            scope    = if ($ack.PSObject.Properties['scope']) { $ack.scope } else { $null }
        }
    }
})

if ($known.Count -gt 0) {
    Write-Host '  Recorded findings (see compliance/embedded-notices.json)' -ForegroundColor Yellow
    foreach ($f in $known) {
        Write-Host ("    {0}" -f $f.Artifact) -ForegroundColor Yellow
        Write-Host ("      notice : {0}" -f $f.Notice) -ForegroundColor DarkGray
        Write-Host ("      term   : {0} x{1}" -f $f.Term, $f.Hits) -ForegroundColor DarkGray
        Write-Host ("      status : {0}" -f $f.status) -ForegroundColor $(if ($f.status -eq 'unresolved') { 'Red' } else { 'DarkGray' })
        if ($f.scope) { Write-Host ("      scope  : {0}" -f $f.scope) -ForegroundColor Red }
    }
    Write-Host ''
}

if ($unacknowledged.Count -eq 0) {
    Write-Host '  No new embedded notices.' -ForegroundColor Green

    # The question is whether any finding THIS RUN reported is unresolved, and the
    # first version of this line asked the wrong list: `$known.status` is the
    # `.status` of an array, which does not exist. Under StrictMode 3.0 that is a
    # PropertyNotFoundException, so the gate died with a PowerShell error and exit
    # 1 -- reporting a failure with no verdict in it, on every run where the
    # acknowledged list happened to be non-empty. `$blocking` is computed from the
    # findings actually seen, off the acknowledgement that matched each one.
    $blocking = @($known.Where({ $_.status -eq 'unresolved' }))
    if ($blocking.Count -gt 0) {
        Write-Host '  UNRESOLVED - the notices above still block a release build.' -ForegroundColor Red
        Write-Host '  Development and testing are unaffected; publishing is.' -ForegroundColor Red
        Write-Host ''
        exit 1
    }
    Write-Host ''
    exit 0
}

Write-Host '  UNACKNOWLEDGED COPYLEFT NOTICE' -ForegroundColor Red
foreach ($f in $unacknowledged) {
    Write-Host ("    {0}" -f $f.Artifact) -ForegroundColor Red
    Write-Host ("      notice : {0}" -f $f.Notice) -ForegroundColor DarkGray
    Write-Host ("      term   : {0} x{1}" -f $f.Term, $f.Hits) -ForegroundColor DarkGray
}
Write-Host ''
Write-Host '  Read the notice, decide, and record the decision in' -ForegroundColor Red
Write-Host '  compliance/embedded-notices.json. Do not delete the check.' -ForegroundColor Red
Write-Host ''
exit 1
