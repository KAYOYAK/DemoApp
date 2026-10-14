<#
.SYNOPSIS
    Enforces the dependency licence allow-list for this Android project.

.DESCRIPTION
    A licence problem found after release cannot be fixed by editing a file: the
    obligation attaches to every copy already distributed. So this runs as a
    gate rather than as advice.

    Two modes:

      -Update   Resolve the release runtime classpath, fetch each artifact's POM,
                and write compliance/licenses.lock.json. Run this when you
                intentionally change dependencies, and commit the result.

      (default) Resolve the classpath and verify it against the committed lock
                file and the allow-list. Exits non-zero on any mismatch.

    Why a lock file rather than a live check every time: the verification must
    work offline and must not depend on a registry being reachable. The lock
    records what was actually verified, and any dependency change shows up as a
    diff in review instead of silently passing.

.PARAMETER Update
    Regenerate the lock file instead of verifying against it.

.PARAMETER ProjectDir
    Android project root. Defaults to the parent of this script's directory.

.PARAMETER Configuration
    Gradle configuration to inspect. Defaults to releaseRuntimeClasspath, which
    is what actually ships in the APK. Test-only dependencies are deliberately
    excluded: they are not distributed.

.EXAMPLE
    .\check-licenses.ps1
    Verify the current dependency tree against the committed lock file.

.EXAMPLE
    .\check-licenses.ps1 -Update
    Regenerate the lock after a deliberate dependency change.
#>
[CmdletBinding()]
param(
    [switch] $Update,
    [string] $ProjectDir,
    [string] $Configuration = 'releaseRuntimeClasspath'
)

$ErrorActionPreference = 'Stop'
$ProgressPreference    = 'SilentlyContinue'

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
if (-not $ProjectDir) { $ProjectDir = Split-Path -Parent $scriptDir }

$complianceDir = Join-Path $ProjectDir 'compliance'
$allowListPath = Join-Path $complianceDir 'allowed-licenses.json'
$lockPath      = Join-Path $complianceDir 'licenses.lock.json'
$cacheDir      = Join-Path $complianceDir '.pomcache'

. (Join-Path $scriptDir 'gradle-query.ps1')

if (-not (Test-Path $allowListPath)) { throw "Allow-list not found: $allowListPath" }
$allow = Get-Content $allowListPath -Raw | ConvertFrom-Json

# ------------------------------------------------------------------ helpers ---

function Get-GradleClasspath {
    param([string] $Root, [string] $Config)

    Write-Host '  resolving dependency graph ...' -ForegroundColor DarkGray

    # Under a timeout, and against the class of hang this machine produces: see
    # gradle-query.ps1. A direct `& $gradlew` here meant a wedged daemon turned a
    # licence gate into a pipeline that never finished and never said why.
    $out = Invoke-GradleQuery -Root $Root -Configuration $Config

    # "group:artifact:version", with conflict resolutions shown as "a:b:v1 -> v2".
    $re = [regex] '([A-Za-z0-9_.\-]+):([A-Za-z0-9_.\-]+):([0-9][A-Za-z0-9_.\-+]*)(?:\s*->\s*([0-9][A-Za-z0-9_.\-+]*))?'
    $seen = [System.Collections.Generic.HashSet[string]]::new()
    foreach ($m in $re.Matches($out)) {
        $g = $m.Groups[1].Value
        $a = $m.Groups[2].Value
        $v = if ($m.Groups[4].Success) { $m.Groups[4].Value } else { $m.Groups[3].Value }
        # `xxx-bom` are dependency constraints, not shipped artifacts.
        if ($a -like '*-bom') { continue }
        [void] $seen.Add("$g`:$a`:$v")
    }
    return @($seen | Sort-Object)
}

# Invoke-WebRequest returns .Content as byte[] for some content types in
# PowerShell 7. Writing that straight to disk stores a list of integers instead
# of the XML, and every licence then parses as UNKNOWN.
function ConvertTo-Text {
    param($Response)
    if ($null -eq $Response) { return $null }
    $c = $Response.Content
    if ($c -is [byte[]]) { return [System.Text.Encoding]::UTF8.GetString($c) }
    if ($c -is [System.Array]) { return [System.Text.Encoding]::UTF8.GetString([byte[]]$c) }
    return [string]$c
}

function Get-PomText {
    param([string] $Coordinate)

    $parts = $Coordinate -split ':'
    $g, $a, $v = $parts[0], $parts[1], $parts[2]
    $gpath = $g -replace '\.', '/'

    $cacheFile = Join-Path $cacheDir ("{0}__{1}__{2}.pom" -f $g, $a, $v)
    if (Test-Path $cacheFile) { return (Get-Content $cacheFile -Raw) }

    # Google's Maven repository hosts androidx / com.google.android; Maven Central
    # hosts everything else. Try both rather than guessing from the group prefix,
    # because some groups are published to both.
    $urls = @(
        "https://dl.google.com/dl/android/maven2/$gpath/$a/$v/$a-$v.pom"
        "https://repo1.maven.org/maven2/$gpath/$a/$v/$a-$v.pom"
    )
    foreach ($u in $urls) {
        try {
            $r = Invoke-WebRequest -Uri $u -TimeoutSec 25 -UseBasicParsing -ErrorAction Stop
            $body = ConvertTo-Text -Response $r
            New-Item -ItemType Directory -Force -Path $cacheDir | Out-Null
            Set-Content -Path $cacheFile -Value $body -Encoding UTF8
            return $body
        } catch {
            # Fall through to the next repository. A 404 here is expected: only
            # one of the two hosts serves any given artifact.
        }
    }
    return $null
}

# Normalise the many ways POMs spell the same licence into one identifier.
function Resolve-LicenceId {
    param([string] $Raw)

    if ([string]::IsNullOrWhiteSpace($Raw)) { return 'UNKNOWN' }
    $t = $Raw.Trim()

    $map = @(
        @{ p = 'apache';            id = 'Apache-2.0' }
        @{ p = 'mit';               id = 'MIT' }
        @{ p = 'bsd 3|bsd-3|new bsd|three clause'; id = 'BSD-3-Clause' }
        @{ p = 'bsd 2|bsd-2|simplified bsd|two clause'; id = 'BSD-2-Clause' }
        @{ p = '\bbsd\b';           id = 'BSD-3-Clause' }   # bare "BSD" is almost always 3-clause
        @{ p = 'cc0|creative commons zero|public domain'; id = 'CC0-1.0' }
        @{ p = 'unicode|icu';       id = 'Unicode-3.0' }
        @{ p = 'gpl';               id = 'GPL' }
        @{ p = 'lgpl';              id = 'LGPL' }
        @{ p = 'agpl';              id = 'AGPL-3.0' }
        @{ p = 'eclipse public';    id = 'EPL-2.0' }
        @{ p = 'mozilla public';    id = 'MPL-2.0' }
    )
    foreach ($e in $map) {
        if ($t -match "(?i)$($e.p)") { return $e.id }
    }
    return $t
}

# ---------------------------------------------------------------- collect ---

Write-Host ''
Write-Host 'Dependency licence check' -ForegroundColor Cyan
Write-Host "  project       : $ProjectDir" -ForegroundColor DarkGray
Write-Host "  configuration : $Configuration" -ForegroundColor DarkGray
Write-Host ''

$coords = Get-GradleClasspath -Root $ProjectDir -Config $Configuration
Write-Host ("  {0} artifacts resolved" -f $coords.Count) -ForegroundColor DarkGray
Write-Host ''

# Fetch POMs in parallel; the cache makes re-runs cheap.
if (-not (Test-Path $cacheDir)) { New-Item -ItemType Directory -Force -Path $cacheDir | Out-Null }
$missing = @($coords | Where-Object {
    $p = $_ -split ':'
    -not (Test-Path (Join-Path $cacheDir ("{0}__{1}__{2}.pom" -f $p[0], $p[1], $p[2])))
})

if ($missing.Count -gt 0) {
    Write-Host "  fetching $($missing.Count) POM(s) ..." -ForegroundColor DarkGray
    if ($PSVersionTable.PSVersion.Major -ge 7) {
        $missing | ForEach-Object -ThrottleLimit 12 -Parallel {
            $c = $_
            $p = $c -split ':'
            $gpath = $p[0] -replace '\.', '/'
            $cache = $using:cacheDir
            $file  = Join-Path $cache ("{0}__{1}__{2}.pom" -f $p[0], $p[1], $p[2])
            foreach ($u in @(
                "https://dl.google.com/dl/android/maven2/$gpath/$($p[1])/$($p[2])/$($p[1])-$($p[2]).pom",
                "https://repo1.maven.org/maven2/$gpath/$($p[1])/$($p[2])/$($p[1])-$($p[2]).pom"
            )) {
                try {
                    $r = Invoke-WebRequest -Uri $u -TimeoutSec 25 -UseBasicParsing -ErrorAction Stop
                    $c = $r.Content
                    $body = if ($c -is [byte[]]) { [System.Text.Encoding]::UTF8.GetString($c) } else { [string]$c }
                    Set-Content -Path $file -Value $body -Encoding UTF8
                    break
                } catch { }
            }
        } | Out-Null
    } else {
        foreach ($c in $missing) { [void](Get-PomText -Coordinate $c) }
    }
}

# ------------------------------------------------------------------ analyse ---

$records = [System.Collections.Generic.List[object]]::new()
foreach ($c in $coords) {
    $p = $c -split ':'
    $key = "$($p[0]):$($p[1])"
    $pom = Get-PomText -Coordinate $c

    $rawName = $null
    if ($pom) {
        $m = [regex]::Match($pom, '(?is)<licenses?>(.*?)</licenses?>')
        if ($m.Success) {
            $n = [regex]::Match($m.Groups[1].Value, '(?is)<name>(.*?)</name>')
            if ($n.Success) { $rawName = ($n.Groups[1].Value -replace '\s+', ' ').Trim() }
        }
    }

    $id     = Resolve-LicenceId -Raw $rawName
    $source = 'pom'

    if ($id -eq 'UNKNOWN') {
        $ov = $allow.manualOverrides.PSObject.Properties | Where-Object {
            $_.Name -ne '_comment' -and ($c -like "$($_.Name):*" -or $key -eq $_.Name)
        } | Select-Object -First 1
        if ($ov) {
            $id     = $ov.Value.licence
            $source = "manual: $($ov.Value.source)"
        }
    }

    $records.Add([pscustomobject]@{
        coordinate = $c
        licenceId  = $id
        licenceRaw = $rawName
        source     = $source
    })
}

$sorted = $records | Sort-Object coordinate

# ------------------------------------------------------------------- report ---

Write-Host ''
Write-Host ('-' * 78)
$byLicence = $sorted | Group-Object licenceId | Sort-Object Count -Descending
foreach ($g in $byLicence) {
    $mark = if ($allow.allowed.PSObject.Properties.Name -contains $g.Name) { 'OK  ' }
            elseif ($allow.denied.PSObject.Properties.Name -contains $g.Name) { 'DENY' }
            else { '????' }
    $colour = switch ($mark) { 'OK  ' { 'Gray' } 'DENY' { 'Red' } default { 'Yellow' } }
    Write-Host ("  [{0}] {1,-34} x{2}" -f $mark, $g.Name, $g.Count) -ForegroundColor $colour
}
Write-Host ('-' * 78)

$violations = @($sorted | Where-Object {
    $allow.allowed.PSObject.Properties.Name -notcontains $_.licenceId
})

if ($violations.Count -gt 0) {
    Write-Host ''
    Write-Host '  LICENCE VIOLATIONS' -ForegroundColor Red
    foreach ($v in $violations) {
        $why = if ($allow.denied.PSObject.Properties.Name -contains $v.licenceId) {
                   $allow.denied.($v.licenceId)
               } else { 'Not on the allow-list. Read its licence before adding it.' }
        Write-Host ("    {0}" -f $v.coordinate) -ForegroundColor Red
        Write-Host ("      licence : {0}    (raw: {1})" -f $v.licenceId, $(if($v.licenceRaw){$v.licenceRaw}else{'<none declared>'}))
        Write-Host ("      verdict : {0}" -f $why) -ForegroundColor DarkGray
    }
    Write-Host ''
    Write-Host '  Fix by one of:' -ForegroundColor Yellow
    Write-Host '    1. Remove or replace the dependency.' -ForegroundColor DarkGray
    Write-Host '    2. Add its licence to compliance/allowed-licenses.json, after reading it.' -ForegroundColor DarkGray
    Write-Host '    3. If the POM simply lacks a licence block, add a verified' -ForegroundColor DarkGray
    Write-Host '       manualOverrides entry with a source URL and a date.' -ForegroundColor DarkGray
    Write-Host ''
    exit 1
}

# ------------------------------------------------------------- lock compare ---

$newLock = [pscustomobject]@{
    generatedWith = "check-licenses.ps1 -Update"
    configuration = $Configuration
    artifactCount = $sorted.Count
    artifacts     = $sorted
}

# Write with LF regardless of platform.
#
# Set-Content uses the platform's line ending, so a file generated on Windows
# would differ from the same file generated on Linux. Git normalises on commit
# via .gitattributes, which means the working tree would then disagree with the
# repository and every run would report the file as modified. Writing LF
# explicitly makes the output byte-identical everywhere.
function Write-TextFile {
    param([string] $Path, [string] $Text)
    $normalised = ($Text -replace "`r`n", "`n") -replace "`r", "`n"
    [System.IO.File]::WriteAllText($Path, $normalised, [System.Text.UTF8Encoding]::new($false))
}

if ($Update) {
    Write-TextFile -Path $lockPath -Text ($newLock | ConvertTo-Json -Depth 6)
    Write-Host ''
    Write-Host "  lock written: $lockPath  ($($sorted.Count) artifacts)" -ForegroundColor Green

    # Regenerate the attribution file from the same data, so the document a
    # licence obliges us to ship can never drift from what actually ships.
    $noticePath = Join-Path $ProjectDir 'THIRD_PARTY_LICENSES.md'
    $byLicence = $sorted | Group-Object licenceId | Sort-Object Name
    $sb = [System.Text.StringBuilder]::new()
    [void]$sb.AppendLine('# Third-party licences')
    [void]$sb.AppendLine()
    [void]$sb.AppendLine('<!--')
    [void]$sb.AppendLine('  GENERATED FILE - do not edit by hand.')
    [void]$sb.AppendLine('  Regenerate with:  scripts/check-licenses.ps1 -Update')
    [void]$sb.AppendLine()
    [void]$sb.AppendLine('  This file is a licence obligation, not documentation. Apache-2.0')
    [void]$sb.AppendLine('  section 4 requires these notices to travel with every copy.')
    [void]$sb.AppendLine('-->')
    [void]$sb.AppendLine()
    [void]$sb.AppendLine(("Generated {0} from the `{1}` configuration ({2} artifacts)." -f (Get-Date -Format 'yyyy-MM-dd'), $Configuration, $sorted.Count))
    [void]$sb.AppendLine()
    [void]$sb.AppendLine('## Summary')
    [void]$sb.AppendLine()
    [void]$sb.AppendLine('| Licence | Artifacts |')
    [void]$sb.AppendLine('| --- | --- |')
    foreach ($g in $byLicence) { [void]$sb.AppendLine(("| {0} | {1} |" -f $g.Name, $g.Count)) }
    [void]$sb.AppendLine()
    foreach ($g in $byLicence) {
        [void]$sb.AppendLine(("## {0}" -f $g.Name))
        [void]$sb.AppendLine()
        foreach ($r in ($g.Group | Sort-Object coordinate)) {
            $note = if ($r.source -like 'manual*') { "  <!-- licence established manually: $($r.source) -->" } else { '' }
            [void]$sb.AppendLine(("- ``{0}``{1}" -f $r.coordinate, $(if ($note) { " $note" } else { '' })))
        }
        [void]$sb.AppendLine()
    }
    Write-TextFile -Path $noticePath -Text $sb.ToString()
    Write-Host "  notices written: $noticePath" -ForegroundColor Green
    Write-Host ''
    Write-Host '  Commit both files together with the dependency change.' -ForegroundColor DarkGray
    Write-Host ''
    exit 0
}

if (-not (Test-Path $lockPath)) {
    Write-Host ''
    Write-Host '  No lock file. Run with -Update once and commit the result.' -ForegroundColor Red
    Write-Host ''
    exit 1
}

$lock = Get-Content $lockPath -Raw | ConvertFrom-Json
$locked = @{}
foreach ($a in $lock.artifacts) { $locked[$a.coordinate] = $a.licenceId }

$added   = @($sorted | Where-Object { -not $locked.ContainsKey($_.coordinate) })
$removed = @($lock.artifacts | Where-Object { -not ($sorted.coordinate -contains $_.coordinate) })
$changed = @($sorted | Where-Object { $locked.ContainsKey($_.coordinate) -and $locked[$_.coordinate] -ne $_.licenceId })

if ($added.Count -or $removed.Count -or $changed.Count) {
    Write-Host ''
    Write-Host '  DEPENDENCY SET DIFFERS FROM THE COMMITTED LOCK' -ForegroundColor Red
    foreach ($a in $added)   { Write-Host ("    + {0}  ({1})" -f $a.coordinate, $a.licenceId) -ForegroundColor Yellow }
    foreach ($r in $removed) { Write-Host ("    - {0}" -f $r.coordinate) -ForegroundColor Yellow }
    foreach ($c in $changed) { Write-Host ("    ~ {0}  {1} -> {2}" -f $c.coordinate, $locked[$c.coordinate], $c.licenceId) -ForegroundColor Yellow }
    Write-Host ''
    Write-Host '  If the change is intended, re-run with -Update and commit the new lock.' -ForegroundColor DarkGray
    Write-Host ''
    exit 1
}

Write-Host ''
Write-Host "  CLEAN - $($sorted.Count) artifacts, all licences on the allow-list, lock in sync." -ForegroundColor Green
Write-Host ''
exit 0
