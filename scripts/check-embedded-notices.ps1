<#
.SYNOPSIS
    Checks the third-party notices packed inside the AARs this app ships.

.DESCRIPTION
    The dependency licence check reads each artifact's POM. That is not enough.
    An AAR's native libraries can statically link code whose licence appears
    nowhere in the POM, and the only record of it is a notice file inside the AAR.

    This walks the release classpath, finds a notice or licence file inside each
    artifact, and reports any copyleft licence named there. Findings recorded in
    compliance/embedded-notices.json are printed with their status; anything else
    fails the check.

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

$isWindowsHost = $IsWindows -or ($env:OS -eq 'Windows_NT')
$gradlew = Join-Path $ProjectDir $(if ($isWindowsHost) { 'gradlew.bat' } else { 'gradlew' })
if (-not (Test-Path $gradlew)) { throw "gradle wrapper not found at $gradlew" }

# Under a timeout, and against the hang this machine produces: see gradle-query.ps1.
# A direct `& $gradlew` here meant a wedged daemon turned this gate, and the pipeline
# behind it, into a process that waited with no output and no way to tell the
# difference between a slow dependency graph and a dead one.
try {
    $graph = Invoke-GradleQuery -Root $ProjectDir -Configuration 'releaseRuntimeClasspath'
} catch {
    Write-Host ("  FAILED to resolve the release classpath: {0}" -f $_.Exception.Message) -ForegroundColor Red
    Write-Host '  nothing can be checked.' -ForegroundColor Red
    exit 1
}

# Coordinates as they appear in the dependency tree.
$coordinates = [regex]::Matches($graph, '(?m)^[|+\\\-\s]*([a-zA-Z0-9_.\-]+):([a-zA-Z0-9_.\-]+):([0-9][a-zA-Z0-9_.\-]*)') |
    ForEach-Object { "$($_.Groups[1].Value):$($_.Groups[2].Value):$($_.Groups[3].Value)" } |
    Sort-Object -Unique

Write-Host ("  release classpath: {0} artifact(s)" -f $coordinates.Count) -ForegroundColor DarkGray

$cacheRoot = if ($env:GRADLE_USER_HOME) { Join-Path $env:GRADLE_USER_HOME 'caches/modules-2/files-2.1' }
             else {
                 # `$HOME` is read-only in PowerShell and assigning to it throws; the
                 # environment variable is the way to read it.
                 $userHome = if ($IsWindows -or ($env:OS -eq 'Windows_NT')) { $env:USERPROFILE } else { $env:HOME }
                 Join-Path $userHome '.gradle/caches/modules-2/files-2.1'
             }

Add-Type -AssemblyName System.IO.Compression.FileSystem

$inspected = 0
$withNotice = 0
$findings = [System.Collections.Generic.List[object]]::new()

foreach ($coord in $coordinates) {
    $parts = $coord.Split(':')
    if ($parts.Count -ne 3) { continue }
    $group, $name, $version = $parts
    $moduleDir = Join-Path $cacheRoot (Join-Path $group (Join-Path $name $version))
    if (-not (Test-Path $moduleDir)) { continue }

    $aars = Get-ChildItem $moduleDir -Recurse -File -Filter "$name-$version.aar" -ErrorAction SilentlyContinue
    foreach ($aar in $aars) {
        $inspected++
        $zip = $null
        try {
            $zip = [System.IO.Compression.ZipFile]::OpenRead($aar.FullName)
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
            Write-Host ("      could not read {0}: {1}" -f $aar.Name, $_.Exception.Message) -ForegroundColor Yellow
        } finally {
            if ($zip) { $zip.Dispose() }
        }
    }
}

Write-Host ("  inspected {0} AAR(s); {1} carried a notice file" -f $inspected, $withNotice) -ForegroundColor DarkGray

# ------------------------------------------------------------- reporting ---

Write-Host ''
Write-Host ('-' * 78)

$unacknowledged = @($findings | Where-Object {
    $f = $_
    -not ($acknowledged | Where-Object { $_.artifact -eq $f.Artifact -and $_.notice -eq $f.Notice })
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
    $ack = $acknowledged | Where-Object { $_.artifact -eq $f.Artifact -and $_.notice -eq $f.Notice } | Select-Object -First 1
    if ($ack) {
        [pscustomobject]@{
            Artifact = $f.Artifact
            Notice   = $f.Notice
            Term     = $f.Term
            Hits     = $f.Hits
            status   = $ack.status
            scope    = $ack.scope
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
