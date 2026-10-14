<#
.SYNOPSIS
  Runs one test class repeatedly until it fails, and keeps the evidence.

.DESCRIPTION
  The intermittent failure recorded in docs/OPEN-WIRING.md is a disagreement
  between what the screen shows and what the disk holds. It moves between test
  classes and does not reproduce on a single forced run, so the only way to
  study it is to run one class many times and keep what happened.

  Two things this does that a shell loop does not:

    * It reads the failure out of the JUnit XML rather than off the console.
      Gradle's console output for a failing test is a wall of frames; the XML
      carries the message and the assertion the test wrote, which is where this
      project's diagnostics are.
    * It stops a concurrent Gradle. Two Gradles on one project cannot both run
      `processDebugResources` -- the second fails deleting `R.jar` and reports
      `initializationError` in tests that pass alone. Earlier rounds of this
      investigation produced usable numbers only from the runs where the build
      succeeded; this refuses to start rather than add to that pile.

.PARAMETER Runs
  How many times to run the class. Default 30.

.PARAMETER TestClass
  Fully-qualified class to filter on. Default the whole UI chat package, since
  the failure has moved between classes in it.

.PARAMETER TimeoutSeconds
  Per-run bound. A run that exceeds it is recorded as a timeout, not a pass.

.PARAMETER OutDir
  Where the per-run XML and the summary land.

.EXAMPLE
  ./scripts/stress-flake.ps1 -Runs 50 -TestClass '*ChatViewModelTest'

.NOTES
  Local investigation tool. Not a gate: it fails when the code is wrong, which
  is what the opposite of a gate is, and it takes minutes.
#>
[CmdletBinding()]
param(
    [int] $Runs = 30,
    [string] $TestClass = '*ui.chat.*Test',
    [int] $TimeoutSeconds = 300,
    [string] $OutDir = 'build/flake'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$ProjectDir = if ($env:DEMOAPP_DIR) { $env:DEMOAPP_DIR } else { Split-Path -Parent $scriptDir }
Push-Location $ProjectDir
try {
    # A second Gradle on this project cannot delete R.jar and fails every test
    # with initializationError. Counting those as failures would be counting the
    # harness.
    $gradle = @(Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -match 'GradleDaemon|GradleWrapperMain' })
    if ($gradle.Count -gt 1) {
        throw ("$($gradle.Count) Gradle processes are running. Stop them first (./gradlew --stop): two " +
            'Gradles on one project produce initializationError, which is a build failure dressed as a test failure.')
    }

    $outRoot = Join-Path $ProjectDir $OutDir
    if (Test-Path $outRoot) { Remove-Item $outRoot -Recurse -Force }
    New-Item -ItemType Directory -Path $outRoot | Out-Null

    # A class name Gradle can use. `-TestClass '*ui.chat.*Test'` matches both
    # ChatViewModelTest and ReplySegmentsTest.
    $filter = '--tests'
    $filterArg = $TestClass

    Write-Host "Running $TestClass up to $Runs times; failures land in $outRoot"

    $failures = @()
    for ($i = 1; $i -le $Runs; $i++) {
        $resultsDir = Join-Path $ProjectDir 'app/build/test-results/testDebugUnitTest'
        if (Test-Path $resultsDir) { Remove-Item $resultsDir -Recurse -Force }

        $sw = [System.Diagnostics.Stopwatch]::StartNew()
        $job = Start-Job -ScriptBlock {
            param($dir, $filter, $cls)
            Set-Location $dir
            $env:JAVA_HOME = 'D:\Program Files\Eclipse Adoptium\jdk-25.0.4.101-hotspot'
            $out = & .\gradlew.bat -q :app:testDebugUnitTest --console=plain $filter $cls 2>&1
            [pscustomobject]@{ ExitCode = $LASTEXITCODE; Output = ($out | Out-String) }
        } -ArgumentList $ProjectDir, $filter, $filterArg

        $done = Wait-Job $job -Timeout $TimeoutSeconds
        if (-not $done) {
            Stop-Job $job
            Remove-Job $job -Force
            $failures += [pscustomobject]@{ Run = $i; Kind = 'timeout'; Test = '(whole run)'; Detail = "exceeded ${TimeoutSeconds}s" }
            Write-Host ("  run {0,3} TIMEOUT after {1}s" -f $i, $TimeoutSeconds)
            continue
        }
        $r = Receive-Job $job
        Remove-Job $job -Force
        $secs = [int]$sw.Elapsed.TotalSeconds

        if ($r.ExitCode -eq 0) {
            Write-Host ("  run {0,3} pass {1,4}s" -f $i, $secs)
            continue
        }

        # Read the XML, not the console: the message the test wrote lives there.
        $sawFailure = $false
        Get-ChildItem $resultsDir -Filter '*.xml' -ErrorAction SilentlyContinue | ForEach-Object {
            $xml = [xml](Get-Content $_.FullName -Raw)
            foreach ($case in $xml.testsuite.testcase) {
                if ($case.failure -or $case.error) {
                    $sawFailure = $true
                    $node = if ($case.failure) { $case.failure } else { $case.error }
                    $detail = ($node.'#text' -split "`n" | Where-Object { $_ -match 'AssertionError|expected:|java\.' } | Select-Object -First 4) -join ' | '
                    $failures += [pscustomobject]@{
                        Run    = $i
                        Kind   = if ($case.error) { 'error' } else { 'failure' }
                        Test   = $case.name
                        Detail = $detail
                    }
                    Write-Host ("  run {0,3} {1} {2,4}s  {3}" -f $i, $case.name, $secs, $detail)
                }
            }
        }
        if (-not $sawFailure) {
            $tail = ($r.Output -split "`n" | Where-Object { $_.Trim() } | Select-Object -Last 6) -join ' | '
            $failures += [pscustomobject]@{ Run = $i; Kind = 'build'; Test = '(no test XML)'; Detail = $tail }
            Write-Host ("  run {0,3} BUILD  {1,4}s  {2}" -f $i, $secs, $tail)
        }

        # Keep the run's raw output next to the summary: the detail above is a
        # projection, and the projection is what was wrong often enough that the
        # summary alone should not be trusted.
        $r.Output | Out-File -Encoding utf8 (Join-Path $outRoot ("run-{0:d3}.log" -f $i))
    }

    Write-Host ''
    Write-Host ("{0} runs, {1} did not pass" -f $Runs, $failures.Count)
    if ($failures.Count) {
        $summary = Join-Path $outRoot 'summary.txt'
        $failures | Format-Table -AutoSize | Out-String -Width 400 | Tee-Object -FilePath $summary
        # A grouped view, because the whole question is whether the failure moves.
        $failures | Group-Object Test | ForEach-Object {
            "  {0,3}x  {1}" -f $_.Count, $_.Name
        }
        Write-Host "summary: $summary"
        exit 1
    }
    Write-Host 'No failure in this batch. That is a result about this machine, not about the code.'
    exit 0
}
finally {
    Pop-Location
}
