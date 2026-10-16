<#
.SYNOPSIS
    Runs the instrumented tests without losing the test model.

.DESCRIPTION
    `gradlew connectedAndroidTest` uninstalls the application when it finishes.
    That matters here only because the tests need a 356 MB model file in the
    app's private storage: the uninstall takes the model with it, so a plain
    `connectedAndroidTest` can never run twice in a row.

    This script drives the same instrumentation directly, which keeps the app
    installed and lets the model be copied once and reused.

    Sequence:
      1. build the app and the test APK
      2. install both, without uninstalling
      3. launch the app once, because filesDir is created lazily and is missing
         immediately after a fresh install
      4. place the model in the app's filesDir if it is not there already,
         sourcing it from /data/local/tmp if that copy exists, otherwise from
         -ModelPath on this machine
      5. run the requested test class through `am instrument`

.PARAMETER ModelPath
    Path to the model on this machine, used when it is not already on the
    device. Defaults to D:\litert-test\SmolLM2_360M_instruct.litertlm.

.PARAMETER Class
    Fully qualified test class. Defaults to the inference engine test.

.PARAMETER Serial
    Device serial. Defaults to emulator-5554.

.EXAMPLE
    .\run-instrumented-test.ps1
    Install, place the model, run the inference engine tests.

.EXAMPLE
    .\run-instrumented-test.ps1 -Class com.example.demoapp.ui.main.MainScreenTest
    Run a different test class against the same install.
#>
[CmdletBinding()]
param(
    [string] $ModelPath = 'D:\litert-test\SmolLM2_360M_instruct.litertlm',
    [string] $Class     = 'com.example.demoapp.inference.LiteRtLlmEngineTest',
    [string] $Serial    = 'emulator-5554'
)

$ErrorActionPreference = 'Stop'

$projectDir = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$adb        = 'D:\Android\Sdk\platform-tools\adb.exe'
$package    = 'com.example.demoapp'
$testPackage = "$package.test"
$runner     = 'androidx.test.runner.AndroidJUnitRunner'
$modelName  = Split-Path -Leaf $ModelPath

# Resolve a JDK rather than assuming the caller exported one.
#
# Gradle needs JAVA_HOME, and a script that only works in a shell where someone
# remembered to set it is a script that fails the first time it is run from
# somewhere else.
if (-not $env:JAVA_HOME -or -not (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
    $candidate = Get-ChildItem 'D:\Program Files\Eclipse Adoptium' -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -like 'jdk-2*' } |
        Sort-Object Name -Descending |
        Select-Object -First 1
    if (-not $candidate) { throw 'No JDK found. Set JAVA_HOME or install one under D:\Program Files\Eclipse Adoptium.' }
    $env:JAVA_HOME = $candidate.FullName
    Write-Host "  java   : $($env:JAVA_HOME)" -ForegroundColor DarkGray
}

function Invoke-Adb {
    param([string[]] $Arguments)
    $out = & $adb -s $Serial @Arguments 2>&1 | Out-String
    return @{ Output = $out; Code = $LASTEXITCODE }
}

Write-Host ''
Write-Host 'Instrumented test run' -ForegroundColor Cyan
Write-Host "  device : $Serial" -ForegroundColor DarkGray
Write-Host "  class  : $Class" -ForegroundColor DarkGray

# ---------------------------------------------------------------- build ---
Write-Host ''
Write-Host '  [1/5] building ...' -ForegroundColor DarkGray
Push-Location $projectDir
try {
    $build = & (Join-Path $projectDir 'gradlew.bat') ':app:assembleDebug' ':app:assembleDebugAndroidTest' '--console=plain' '-q' 2>&1 | Out-String
    if ($LASTEXITCODE -ne 0) { Write-Host $build; throw 'Build failed' }
} finally { Pop-Location }

$appApk  = Join-Path $projectDir 'app\build\outputs\apk\debug\app-debug.apk'
$testApk = Join-Path $projectDir 'app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk'
foreach ($apk in @($appApk, $testApk)) {
    if (-not (Test-Path $apk)) { throw "APK not found: $apk" }
}

# -------------------------------------------------------------- install ---
Write-Host '  [2/5] installing (no uninstall) ...' -ForegroundColor DarkGray
foreach ($apk in @($appApk, $testApk)) {
    $r = Invoke-Adb @('install', '-r', '-t', $apk)
    if ($r.Code -ne 0) { Write-Host $r.Output -ForegroundColor Red; throw "install failed: $apk" }
}

# Launch once, then create filesDir explicitly.
#
# filesDir is created lazily by the platform and is absent right after a fresh
# install. Launching the app is what normally creates it, but that depends on
# the app actually reaching the point of touching it, which is not something
# this script should rely on. mkdir -p makes the directory exist either way.
Write-Host '  [3/5] preparing the app data directory ...' -ForegroundColor DarkGray
[void](Invoke-Adb @('shell', 'am', 'start', '-n', "$package/.MainActivity"))
Start-Sleep -Seconds 4
[void](Invoke-Adb @('shell', 'am', 'force-stop', $package))

$mk = Invoke-Adb @('shell', 'run-as', $package, 'mkdir', '-p', 'files')
if ($mk.Code -ne 0) { Write-Host $mk.Output -ForegroundColor Yellow }
$listing = (Invoke-Adb @('shell', 'run-as', $package, 'ls', '-la')).Output
# The name is the last field of a long-format line, and the empty field is what the
# line's own line ending leaves behind: the first version of this printed
# "app data dir contains: , , , , " for five directories it had listed correctly.
$names = $listing -split "`n" |
    Where-Object { $_ -match '^d' } |
    ForEach-Object { ($_ -split '\s+' | Where-Object { $_ })[-1] }
Write-Host ("        app data dir contains: " + ($names -join ', ')) -ForegroundColor DarkGray

# ---------------------------------------------------------------- model ---
Write-Host '  [4/5] ensuring the model is in place ...' -ForegroundColor DarkGray
# files/models/ is where the application's own downloader writes, so a model
# pulled through the app is already in the right place and nothing is copied.
$present = (Invoke-Adb @('shell', 'run-as', $package, 'ls', 'files/models/')).Output
if ($present -match [regex]::Escape($modelName)) {
    # It got there through the app's downloader or through this script, and this
    # script cannot tell which -- so the line says where it is, not how.
    Write-Host '        already in files/models/, skipping the copy' -ForegroundColor DarkGray
} else {
    $root = (Invoke-Adb @('shell', 'run-as', $package, 'ls', 'files/')).Output
    if ($root -match [regex]::Escape($modelName)) {
        Write-Host '        present in files/, moving it where the app expects it' -ForegroundColor DarkGray
        [void](Invoke-Adb @('shell', 'run-as', $package, 'mkdir', '-p', 'files/models'))
        [void](Invoke-Adb @('shell', 'run-as', $package, 'mv', "files/$modelName", "files/models/$modelName"))
    } else {
        $staged = (Invoke-Adb @('shell', 'ls', '/data/local/tmp/')).Output
        if ($staged -notmatch [regex]::Escape($modelName)) {
            if (-not (Test-Path $ModelPath)) { throw "Model not on device and not at $ModelPath" }
            Write-Host '        pushing to /data/local/tmp ...' -ForegroundColor DarkGray
            $r = Invoke-Adb @('push', $ModelPath, '/data/local/tmp/')
            if ($r.Code -ne 0) { Write-Host $r.Output -ForegroundColor Red; throw 'push failed' }
        }
        [void](Invoke-Adb @('shell', 'run-as', $package, 'mkdir', '-p', 'files/models'))
        # An explicit destination filename, not "files/". Android's toybox cp
        # rejects a bare directory argument here with "Is a directory".
        $r = Invoke-Adb @('shell', 'run-as', $package, 'cp', "/data/local/tmp/$modelName", "files/models/$modelName")
        if ($r.Code -ne 0) { Write-Host $r.Output -ForegroundColor Red; throw 'model copy failed' }
        Write-Host '        copied into files/models/' -ForegroundColor DarkGray
    }
}

# ------------------------------------------------------------------ run ---
Write-Host '  [5/5] running ...' -ForegroundColor DarkGray
Write-Host ''
# The tests print where their numbers go, and that is not the stream below:
# `println` from an instrumented test lands in logcat under `System.out`, which
# is why a run that measured something could still leave nothing behind. The
# buffer is cleared here so what is read back afterwards belongs to this run.
[void](Invoke-Adb @('shell', 'logcat', '-c'))
$result = & $adb -s $Serial shell am instrument -w -e class $Class "$testPackage/$runner" 2>&1 | Out-String
Write-Host $result

# The instrumented output is the evidence, and a passing run was the one that hid
# it: the sampler comparison prints its measurements and nothing else, and
# capturing this script's stdout lost them, so a green run's numbers were gone
# while the same run's failure text survived. Two sources are written to one
# file: the stream above, and the lines the tests printed, read back from logcat.
# Those follow one convention (`SAMPLER:`, `LITERT-ENGINE:`, `DEVICE-AUTH:`,
# `SPEECH:`), so the rule is a prefixed word rather than a list of names.
$logcat = & $adb -s $Serial logcat -d -v brief 2>&1 | Out-String
$printed = $logcat -split "`r?`n" |
    ForEach-Object {
        # `-v brief` writes `I/System.out( 1234): message`; the tag is stripped so
        # the line kept is the one the test wrote.
        $m = [regex]::Match($_, '^[VDIWEF]/(\S+)\s*\(\s*\d+\):\s(.*)$')
        if ($m.Success) { $m.Groups[2].Value } else { $_ }
    } |
    Where-Object { $_ -match '^[A-Z][A-Z0-9_-]*: ' -and $_ -notmatch '^(INSTRUMENTATION_|Time:)' }
$instrumentedLog = Join-Path $projectDir 'build-logs\instrumented-output.txt'
New-Item -ItemType Directory -Force -Path (Split-Path $instrumentedLog) | Out-Null
$parts = @($result.TrimEnd())
if ($printed) { $parts += @('', '--- what the test printed (logcat) ---') + $printed }
$parts -join "`n" | Set-Content -Encoding utf8 $instrumentedLog
if ($printed) {
    Write-Host '--- what the test printed ---' -ForegroundColor DarkGray
    $printed | ForEach-Object { Write-Host "    $_" }
}
Write-Host ("        full instrumented output: {0}" -f $instrumentedLog) -ForegroundColor DarkGray

if ($result -match 'FAILURES!!!|INSTRUMENTATION_FAILED') {
    Write-Host 'RESULT: FAILED' -ForegroundColor Red
    exit 1
}
if ($result -match 'OK \(\d+ test') {
    Write-Host 'RESULT: PASSED' -ForegroundColor Green
    exit 0
}
Write-Host 'RESULT: unclear - inspect the output above.' -ForegroundColor Yellow
exit 2
