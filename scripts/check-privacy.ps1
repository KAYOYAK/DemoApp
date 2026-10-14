<#
.SYNOPSIS
    Enforces the privacy contract of this application.

.DESCRIPTION
    The product promise is: the network is used to download models, and for
    nothing else. A promise that lives only in a README is not a promise, so
    this checks the things that would break it and fails the build.

    What it verifies:

      1. Declared permissions are a subset of the approved list -- checked in the
         MERGED manifest, which is what ships, not only the source manifest.
         A library contributes permissions during the merge, and those never
         appear in app/src/main/AndroidManifest.xml.

      2. No telemetry or analytics SDK is present in the dependency graph.
         These are transitive far more often than they are direct, which is
         exactly why the check reads the resolved graph and not the build file.

      3. The application's own source makes no network calls outside the one
         class allowed to make them. This is the promise itself; without this
         check the promise is enforced only by remembering it.

      4. Cleartext HTTP is not enabled, and no network security config silently
         re-enables it.

      5. Android backup is off. Backing up a chat log to a cloud service
         contradicts the sentence the app shows the user.

.PARAMETER ProjectDir
    Android project root. Defaults to the parent of this script's directory.

.EXAMPLE
    .\check-privacy.ps1
    Verify the privacy contract. Exits non-zero when it is broken.
#>
[CmdletBinding()]
param([string] $ProjectDir)

$ErrorActionPreference = 'Stop'

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
if (-not $ProjectDir) { $ProjectDir = Split-Path -Parent $scriptDir }

$sourceManifest = Join-Path $ProjectDir 'app/src/main/AndroidManifest.xml'
if (-not (Test-Path $sourceManifest)) { throw "AndroidManifest.xml not found under $ProjectDir" }

. (Join-Path $scriptDir 'gradle-query.ps1')

# ------------------------------------------------------------------ contract ---

# Every entry needs a reason. A permission without a reason is one nobody can
# defend in review.
$approvedPermissions = @{
    'android.permission.INTERNET' = 'Required to download model weights. Never used for anything else.'

    # The app lock, declared by androidx.biometric.
    #
    # They look alarming on a manifest and grant nothing: neither can read a
    # fingerprint, and neither returns data to the app. They permit asking the
    # system to perform an authentication whose only result is pass or fail; all
    # matching happens in the secure element, outside this process.
    #
    # USE_FINGERPRINT is the pre-Android-9 spelling of the same capability, added
    # for the devices this app still supports.
    'android.permission.USE_BIOMETRIC' = 'Lets the user unlock the app with the device biometric prompt. No biometric data reaches the app; the result is pass or fail.'
    'android.permission.USE_FINGERPRINT' = 'The pre-Android-9 name for the same capability, for the devices this app still supports.'
}

# Permissions Android itself or an AndroidX library adds for its own housekeeping.
# They are not capabilities the app asks for, and they are all process-local.
$ignoredPermissionPatterns = @(
    '^[a-z0-9_.]+\.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION$'  # AndroidX: receivers are not exported
)

# Substrings matched against the resolved dependency graph. These libraries
# exist to report usage somewhere, which contradicts the offline contract.
$forbiddenDependencies = @(
    @{ p = 'firebase-analytics';          why = 'Google Analytics for Firebase' }
    @{ p = 'firebase-crashlytics';        why = 'Firebase Crashlytics' }
    @{ p = 'firebase-perf';               why = 'Firebase Performance' }
    @{ p = 'play-services-analytics';     why = 'Google Analytics' }
    @{ p = 'play-services-measurement';   why = 'Google Analytics measurement' }
    @{ p = 'io.sentry';                   why = 'Sentry error reporting' }
    @{ p = 'com.amplitude';               why = 'Amplitude analytics' }
    @{ p = 'com.mixpanel';                why = 'Mixpanel analytics' }
    @{ p = 'com.segment.analytics';       why = 'Segment analytics' }
    @{ p = 'com.appsflyer';               why = 'AppsFlyer attribution' }
    @{ p = 'com.adjust.sdk';              why = 'Adjust attribution' }
    @{ p = 'com.flurry';                  why = 'Flurry analytics' }
    @{ p = 'com.bugsnag';                 why = 'Bugsnag crash reporting' }
    @{ p = 'com.newrelic';                why = 'New Relic telemetry' }
    @{ p = 'io.branch';                   why = 'Branch deep-link tracking' }
    @{ p = 'com.onesignal';               why = 'OneSignal push/analytics' }
    @{ p = 'com.google.android.gms:play-services-ads'; why = 'Google Ads' }
    @{ p = 'com.facebook';                why = 'Facebook SDK' }
    @{ p = 'com.datadog';                 why = 'Datadog telemetry' }
    @{ p = 'com.microsoft.appcenter';     why = 'App Center telemetry' }
    @{ p = 'io.braze';                    why = 'Braze messaging analytics' }
    @{ p = 'com.instabug';                why = 'Instabug telemetry' }
)

# Files permitted to reference a network API, relative to app/src/main, with the
# reason. Anything else touching the network breaks the promise, and this is the
# only check that looks at the application's own code.
$networkApiAllowed = @{
    'java/com/example/demoapp/model/ModelDownloader.kt' = 'the only class that performs requests'
    'java/com/example/demoapp/AppContainer.kt'          = 'constructs the single shared HTTP client'
}

# Substrings that indicate a network capability. Deliberately broad: a false
# positive costs one conversation, a false negative costs the promise.
$networkApiPatterns = @(
    'okhttp3',
    'java\.net\.URL',
    'java\.net\.HttpURLConnection',
    'java\.net\.Socket',
    'android\.webkit\.WebView',
    '\.newCall\('
)

$violations = [System.Collections.Generic.List[object]]::new()
function Add-Violation($what, $where, $why) {
    $violations.Add([pscustomobject]@{ What = $what; Where = $where; Why = $why })
}

Write-Host ''
Write-Host 'Privacy contract check' -ForegroundColor Cyan
Write-Host "  project : $ProjectDir" -ForegroundColor DarkGray

# --------------------------------------------------------- 1. permissions ---

# Both manifests, and the union of what they declare.
#
# The source manifest is what a developer edits, and it is always current. The
# merged manifest is what ships, and it is the only place a library's own
# permissions appear -- but it is regenerated by a build, so on its own it can be
# stale. Checking the merged one alone is worse than checking the source: editing
# the source and running the gate without building would report clean against the
# previous build's output.
$mergedCandidates = Get-ChildItem (Join-Path $ProjectDir 'app/build/intermediates') -Recurse `
    -Filter 'AndroidManifest.xml' -ErrorAction SilentlyContinue |
    Where-Object { $_.FullName -match 'merged_manifest' } |
    Sort-Object LastWriteTime -Descending

$manifestsToCheck = [System.Collections.Generic.List[string]]::new()
$manifestsToCheck.Add($sourceManifest)
$mergedNote = 'not built yet -- library-added permissions are not visible'
if ($mergedCandidates) {
    $manifestsToCheck.Add($mergedCandidates[0].FullName)
    $mergedNote = 'merged manifest included'
}

Write-Host ''
Write-Host '  [1] declared permissions' -ForegroundColor DarkGray
Write-Host ("      checked: source manifest + {0}" -f $mergedNote) -ForegroundColor DarkGray

$ns = 'http://schemas.android.com/apk/res/android'
$declared = [System.Collections.Generic.HashSet[string]]::new()
$appsChecked = [System.Collections.Generic.List[object]]::new()

foreach ($mp in $manifestsToCheck) {
    [xml] $doc = Get-Content $mp -Raw
    $doc.SelectNodes('//uses-permission | //uses-permission-sdk-23 | //uses-permission-sdk-m') |
        ForEach-Object {
            $name = $_.GetAttribute('name', $ns)
            if ($name) { [void]$declared.Add($name) }
        }
    $appNode = $doc.SelectSingleNode('//application')
    if ($appNode) { $appsChecked.Add([pscustomobject]@{ path = $mp; node = $appNode }) }
}

$ordered = @($declared | Sort-Object)
if ($ordered.Count -eq 0) {
    Write-Host '      (none)' -ForegroundColor DarkGray
}
foreach ($d in $ordered) {
    $ignored = $ignoredPermissionPatterns | Where-Object { $d -match $_ }
    if ($ignored) {
        Write-Host ("      OK    {0}  (added by AndroidX, process-local)" -f $d) -ForegroundColor Gray
    } elseif ($approvedPermissions.ContainsKey($d)) {
        Write-Host ("      OK    {0}" -f $d) -ForegroundColor Gray
    } else {
        Write-Host ("      EXTRA {0}" -f $d) -ForegroundColor Red
        Add-Violation 'Unapproved permission' $d 'Every permission must be justified. Add it to $approvedPermissions with a reason, or remove it.'
    }
}

# 4. cleartext, and the backup setting
foreach ($entry in $appsChecked) {
    $path = $entry.path
    $node = $entry.node

    $cleartext = $node.GetAttribute('usesCleartextTraffic', $ns)
    if ($cleartext -and $cleartext.ToLowerInvariant() -eq 'true') {
        Add-Violation 'usesCleartextTraffic is true' $path 'Model downloads must use HTTPS.'
    }
    if ($node.GetAttribute('networkSecurityConfig', $ns)) {
        Add-Violation 'networkSecurityConfig is set' $path 'Review it: a config can silently re-enable cleartext.'
    }

    # The app tells the user "Nothing you type leaves it". Android backup copies
    # app-private files to a cloud service, so leaving it on makes that sentence
    # false, whatever the intention was.
    $backup = $node.GetAttribute('allowBackup', $ns)
    if ($backup -and $backup.ToLowerInvariant() -eq 'true') {
        Add-Violation 'allowBackup is true' $path 'Chat history would be copied off the device, contradicting what the app tells the user.'
    }

    # `allowBackup` is not the whole answer, and on Android 12 and higher it is
    # not the answer at all. The platform deprecated it there: it now applies to
    # cloud backup only, and device-to-device transfer is governed by
    # `dataExtractionRules` instead. A mode with no rule in that file is *fully
    # enabled*, so a manifest with `allowBackup="false"` and no rules file copies
    # the conversations and the settings onto the next phone during setup -- which
    # is not a backup to a server, so the sentence above does not even describe
    # it, and the user has no way to tell that it happened.
    #
    # The rule file therefore has to exist and has to exclude everything, in both
    # modes. Checking only that the attribute is present would pass a file that
    # excludes nothing.
    $rulesAttr = $node.GetAttribute('dataExtractionRules', $ns)
    if ([string]::IsNullOrWhiteSpace($rulesAttr)) {
        Add-Violation 'dataExtractionRules is not set' $path 'On Android 12 and higher this is what governs backup and device transfer; without it both are on by default, whatever allowBackup says.'
    } else {
        $rulesName = ($rulesAttr -replace '^@xml/', '') -replace '^.*/', ''
        $rulesFile = Join-Path $ProjectDir "app/src/main/res/xml/$rulesName.xml"

        if (-not (Test-Path $rulesFile)) {
            Add-Violation 'dataExtractionRules points at a file that is not there' "$path -> $rulesAttr" 'The platform would fall back to backing up everything.'
        } else {
            [xml] $rulesDoc = Get-Content $rulesFile -Raw
            $root = $rulesDoc.DocumentElement

            if ($root.Name -ne 'data-extraction-rules') {
                Add-Violation 'the extraction rules file is not a data-extraction-rules document' $rulesFile "Found <$($root.Name)>."
            } else {
                foreach ($mode in @('cloud-backup', 'device-transfer')) {
                    $section = $root.SelectSingleNode($mode)
                    if (-not $section) {
                        Add-Violation "$mode has no section in the extraction rules" $rulesFile 'A mode with no rule is fully enabled, so this copies everything.'
                        continue
                    }
                    # An `<include>` anywhere means the platform backs up what it
                    # names instead of nothing, so the check is not "excludes
                    # exist" but "nothing is included".
                    if ($section.SelectNodes('include').Count -gt 0) {
                        Add-Violation "$mode includes files" $rulesFile 'This app backs up nothing; an include re-enables it for what it names.'
                    }
                    if ($section.SelectNodes('exclude').Count -eq 0) {
                        Add-Violation "$mode excludes nothing" $rulesFile 'A section with no exclusion is the same as no section.'
                    }
                }
            }
        }
    }
}

# ------------------------------------------------- 2. forbidden dependencies ---

Write-Host ''
Write-Host '  [2] telemetry dependencies' -ForegroundColor DarkGray
$isWindowsHost = $IsWindows -or ($env:OS -eq 'Windows_NT')
$gradlew = Join-Path $ProjectDir $(if ($isWindowsHost) { 'gradlew.bat' } else { 'gradlew' })

if (Test-Path $gradlew) {
    # Under a timeout, and against the hang this machine produces: see
    # gradle-query.ps1. A wedged daemon used to leave this gate, and the pipeline
    # behind it, waiting with no output.
    $graph = $null
    try {
        $graph = Invoke-GradleQuery -Root $ProjectDir -Configuration 'releaseRuntimeClasspath'
    } catch {
        Write-Host ("      FAILED to resolve the dependency graph: {0}" -f $_.Exception.Message) -ForegroundColor Red
        Add-Violation 'Dependency graph could not be resolved' 'releaseRuntimeClasspath' 'The check cannot see what ships, so it cannot pass.'
    }

    # A failed resolution prints error text, which contains none of the forbidden
    # names -- so without this the gate reports "none present" precisely when it
    # has no idea what the dependency graph contains.
    if ($null -eq $graph) {
        Write-Host '      (nothing was resolved)' -ForegroundColor Red
    } else {
        $found = 0
        foreach ($f in $forbiddenDependencies) {
            if ($graph -match [regex]::Escape($f.p)) {
                Write-Host ("      FOUND {0}  ({1})" -f $f.p, $f.why) -ForegroundColor Red
                Add-Violation "Forbidden dependency: $($f.p)" 'releaseRuntimeClasspath' "$($f.why). Remove it, or it will report usage off-device."
                $found++
            }
        }
        if ($found -eq 0) { Write-Host '      none present' -ForegroundColor Gray }
    }
} else {
    Write-Host '      (gradle wrapper not found, skipped)' -ForegroundColor Yellow
    Add-Violation 'Dependency graph not checked' 'gradlew missing' 'Without a wrapper the forbidden-dependency check cannot run.'
}

# ---------------------------------------------------- 3. our own network use ---

Write-Host ''
Write-Host '  [3] network calls in application source' -ForegroundColor DarkGray
$mainSrc = Join-Path $ProjectDir 'app/src/main'
$offenders = @()
Get-ChildItem $mainSrc -Recurse -File -Include *.kt, *.java -ErrorAction SilentlyContinue | ForEach-Object {
    $relative = $_.FullName.Substring((Resolve-Path $mainSrc).Path.Length).TrimStart('\', '/') -replace '\\', '/'
    $hits = @()
    foreach ($pattern in $networkApiPatterns) {
        $m = Select-String -Path $_.FullName -Pattern $pattern -ErrorAction SilentlyContinue
        if ($m) { $hits += ($m | ForEach-Object { $_.LineNumber }) }
    }
    if ($hits.Count -gt 0) {
        $allowed = @($networkApiAllowed.Keys | Where-Object { $relative -like "*$_" })
        if ($allowed.Count -gt 0) {
            Write-Host ("      OK    {0}  ({1})" -f $relative, $networkApiAllowed[$allowed[0]]) -ForegroundColor Gray
        } else {
            Write-Host ("      FOUND {0}  at line(s) {1}" -f $relative, ($hits -join ', ')) -ForegroundColor Red
            $offenders += $relative
        }
    }
}
if ($offenders.Count -eq 0) {
    Write-Host '      no unexpected network access' -ForegroundColor Gray
} else {
    Add-Violation "Network access outside the downloader: $($offenders -join ', ')" $mainSrc 'The network is used to download models and for nothing else. Move it, or change the contract deliberately.'
}

# ------------------------------------------------------------- report ---

Write-Host ''
Write-Host ('-' * 78)

if ($violations.Count -eq 0) {
    Write-Host '  CLEAN - permissions minimal, no telemetry, no cleartext, no stray network use.' -ForegroundColor Green
    Write-Host ''
    Write-Host '  Remaining manual check before release:' -ForegroundColor DarkGray
    Write-Host '    Install, download a model, then enable airplane mode and chat.' -ForegroundColor DarkGray
    Write-Host '    That is the only way to prove the offline path end to end.' -ForegroundColor DarkGray
    Write-Host ''
    exit 0
}

Write-Host '  PRIVACY CONTRACT VIOLATED' -ForegroundColor Red
foreach ($v in $violations) {
    Write-Host ("    {0}" -f $v.What) -ForegroundColor Red
    Write-Host ("      at  : {0}" -f $v.Where) -ForegroundColor DarkGray
    Write-Host ("      why : {0}" -f $v.Why) -ForegroundColor DarkGray
}
Write-Host ''
exit 1
