<#
.SYNOPSIS
  Runs one test class repeatedly until it fails, and keeps the evidence.

.DESCRIPTION
  Written for the intermittent failure recorded in docs/OPEN-WIRING.md -- roughly
  one run in five under load, a save that never reached the disk, in a family of
  tests whose location moved between runs. It was found on 2026-10-15 (a rename
  the file system refused, with nothing retrying it, fixed in `FileChatStore`)
  and this script stays for two reasons: a fix is only as good as a batch of runs
  that no longer fail, and a failure that moves between tests is not ruled out by
  one green run.

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
    * It refuses to count a run that executed nothing. Gradle marks the task
      UP-TO-DATE when its inputs are unchanged, so a loop without `--rerun`
      reports the same green result forty times over without running a test --
      which is what the first version did. A run with no test XML is recorded as
      `no-tests` and fails the batch, because it is not evidence either way.

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
            # `-Pdemoapp.refusalWatch` is this batch's instrument, not a convenience. A move
            # refusal that the retry window recovers from is not a failure -- the save lands
            # -- so without it this script can only report that nothing outlived the window,
            # which is what three widenings of the window were justified with and what left
            # the file system's own reason unread. With it, an absorbed refusal fails the
            # test that saw it and the reason comes out in the message, which is what this
            # run's summary then carries. The property is forwarded into the test JVM by
            # `app/build.gradle.kts`.
            # The property is quoted because PowerShell splits it otherwise: Gradle sees
            # `-Pdemoapp` and `.refusalWatch=1` as two arguments and fails with "Task
            # '.refusalWatch=1' not found". The quotes are load-bearing.
            $out = & .\gradlew.bat -q :app:testDebugUnitTest --console=plain --rerun "-Pdemoapp.refusalWatch=1" $filter $cls 2>&1
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
        # A job that lost `$LASTEXITCODE` must not be read as "not zero, so a test
        # failed": the number is printed with every outcome so it can be read.
        $exit = if ($null -eq $r.ExitCode) { -1 } else { [int]$r.ExitCode }

        # What the run actually executed, before believing its exit code.
        #
        # `--rerun` is what makes this a real run. Without it Gradle marks the task
        # UP-TO-DATE after the first pass, the results directory stays empty, and a
        # batch of forty "passes" is a batch of forty non-events -- which is exactly
        # what the first version of this script reported: run one took 10s, runs two
        # to forty took 5s each and executed nothing.
        $ran = 0
        Get-ChildItem $resultsDir -Filter '*.xml' -ErrorAction SilentlyContinue | ForEach-Object {
            $xml = [xml](Get-Content $_.FullName -Raw)
            foreach ($suite in $xml.SelectNodes('//testsuite')) {
                $ran += [int]$suite.GetAttribute('tests')
            }
        }

        if ($ran -eq 0) {
            $failures += [pscustomobject]@{
                Run    = $i
                Kind   = 'no-tests'
                Test   = '(nothing ran)'
                Detail = "exit $($r.ExitCode), no test XML: this run says nothing about the code"
            }
            Write-Host ("  run {0,3} NO-TESTS {1,4}s  exit {2}" -f $i, $secs, $exit)
            # The stamp is the run's end, not its start: a batch that takes twenty minutes
            # cannot otherwise be read against anything else that happened on the machine,
            # and "which run was this, and when" is the first question a flake asks.
            $logHeader = "run {0,3} ended {1} ({2}s, exit {3}, {4} tests) -- the console output follows" -f $i, (Get-Date -Format 'yyyy-MM-dd HH:mm:ss zzz'), $secs, $exit, $ran
            @($logHeader) + $r.Output | Out-File -Encoding utf8 (Join-Path $outRoot ("run-{0:d3}.log" -f $i))
            continue
        }

        if ($exit -eq 0) {
            Write-Host ("  run {0,3} pass {1,4}s  ({2} tests, exit 0)" -f $i, $secs, $ran)
            continue
        }

        # Read the XML, not the console: the message the test wrote lives there.
        #
        # `SelectSingleNode`, not `$case.failure`: under `Set-StrictMode -Version
        # Latest` (line 59) a missing child element is a terminating error, so the
        # property form threw the first time this path was reached and took the
        # whole batch with it -- the one moment the script exists for.
        $sawFailure = $false
        Get-ChildItem $resultsDir -Filter '*.xml' -ErrorAction SilentlyContinue | ForEach-Object {
            $xml = [xml](Get-Content $_.FullName -Raw)
            foreach ($case in $xml.SelectNodes('//testcase')) {
                $failed = $case.SelectSingleNode('failure')
                $errored = $case.SelectSingleNode('error')
                if ($failed -or $errored) {
                    $sawFailure = $true
                    $node = if ($failed) { $failed } else { $errored }
                    $detail = ($node.InnerText -split "`n" | Where-Object { $_ -match 'AssertionError|expected:|java\.' } | Select-Object -First 4) -join ' | '
                    $failures += [pscustomobject]@{
                        Run    = $i
                        Kind   = if ($errored) { 'error' } else { 'failure' }
                        Test   = $case.GetAttribute('name')
                        Detail = $detail
                    }
                    Write-Host ("  run {0,3} {1} {2,4}s  {3}" -f $i, $case.GetAttribute('name'), $secs, $detail)
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
        $logHeader = "run {0,3} ended {1} ({2}s, exit {3}, {4} tests) -- the console output follows" -f $i, (Get-Date -Format 'yyyy-MM-dd HH:mm:ss zzz'), $secs, $exit, $ran
        @($logHeader) + $r.Output | Out-File -Encoding utf8 (Join-Path $outRoot ("run-{0:d3}.log" -f $i))
    }

    Write-Host ''
    $passed = $Runs - $failures.Count
    Write-Host ("{0} runs: {1} executed and passed, {2} did not pass" -f $Runs, $passed, $failures.Count)

    # Written whether or not anything failed. The first version wrote the summary only
    # on failure, so a clean batch left an empty directory -- and an empty directory is
    # the same evidence as a batch that never ran, which is the defect this script was
    # built to find in other tools. The per-run logs stay failure-only on purpose: a
    # passing run's console output is not worth a hundred and fifty files.
    $summary = Join-Path $outRoot 'summary.txt'
    $lines = @(
        ("{0} runs: {1} executed and passed, {2} did not pass" -f $Runs, $passed, $failures.Count)
        ("class filter: {0}" -f $TestClass)
        ('refusal watch: on -- a move the file system refused and the retries recovered from fails a test and names its reason')
        ("finished: {0}" -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss zzz'))
        ("machine: {0}" -f $env:COMPUTERNAME)
    )
    if ($failures.Count) {
        $lines += ($failures | Format-Table -AutoSize | Out-String -Width 400).TrimEnd()
    }
    else {
        # The caveat is not the same sentence as it was before the watch existed. A clean
        # batch now says two things: every save landed, and through the tests this class
        # runs, no move was refused at all. It is still bounded by the tests that reached
        # a save -- a refusal the store absorbed while no test was watching is not reported
        # -- and it is still a statement about this machine.
        $lines += 'No failure in this batch, and the refusal watch saw no refused move either.'
        $lines += 'Every save landed and nothing had to be retried: about this machine, through the saves these tests make.'
    }
    ($lines -join "`n") | Out-File -Encoding utf8 $summary
    Write-Host "summary: $summary"

    if ($failures.Count) {
        # A grouped view, because the whole question is whether the failure moves.
        $failures | Group-Object Test | ForEach-Object {
            "  {0,3}x  {1}" -f $_.Count, $_.Name
        }
        exit 1
    }
    Write-Host 'No failure in this batch, and the refusal watch saw no refused move either.'
    Write-Host 'Every save landed and nothing had to be retried: about this machine, through the saves these tests make.'
    exit 0
}
finally {
    Pop-Location
}
