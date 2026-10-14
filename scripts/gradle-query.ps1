#requires -Version 7
<#
    Runs a Gradle dependency query for the gates, under a timeout, and repairs a
    daemon that stops answering.

    ## The defect this exists for

    Three gates ask Gradle for the release dependency graph --
    `check-licenses.ps1`, `check-privacy.ps1`, `check-embedded-notices.ps1` -- and
    each of them did it with a direct `& $gradlew -q ':app:dependencies' ...`.

    On this machine the Gradle daemon periodically stops responding while holding
    the registry: `~/.gradle/daemon/9.1.0/daemon-<pid>.out.log` is created at zero
    bytes and never written to, and the daemon accumulates about twelve seconds of
    CPU over ten minutes and then nothing. It has happened four times in one
    working day. When it does, `& $gradlew` never returns, so the gate never
    returns, so the pipeline never returns -- and there is nothing in the output to
    say whether the dependency graph was fine, or a licence was found, or the
    machine had quietly stopped working on it.

    That is the same shape as every other defect this repository's gates exist for:
    something that appears to be running and is not producing an answer. A check
    that cannot finish is not a check, and the failure mode is indistinguishable
    from patience.

    ## What this does instead

    Runs the wrapper in a child process, polls while it runs, and gives up after
    `-TimeoutSeconds`. On a timeout it stops the daemon, deletes the poisoned
    registry, starts fresh, and asks once more. If the second attempt also times
    out it throws with the log paths, so the pipeline fails with a sentence that
    names the machine state rather than hanging until someone notices.

    The daemon is stopped with `gradlew --stop`, which is the wrapper's own way to
    end a daemon. It is deliberately not a process kill: a machine running this
    suite may have other JVMs on it, and a gate is not entitled to end them.

    ## What this is not

    A general-purpose Gradle runner. It answers exactly the question the three
    gates ask -- resolve a configuration and print it -- and it assumes the
    dependency report is the only thing on standard output.
#>

Set-StrictMode -Version Latest

$script:gradleQueryDefaultTimeout = 90

function Get-DaemonLogDir {
    <#
        The daemon directory for the wrapper's own version. Derived from
        gradle/wrapper/gradle-wrapper.properties rather than hardcoded, because the
        hardcoded path is right up until the wrapper is upgraded and then it is a
        silent path to a string comparison that never matches.
    #>
    param([string] $Root)

    $props = Join-Path $Root 'gradle/wrapper/gradle-wrapper.properties'
    if (-not (Test-Path $props)) { return $null }
    $m = [regex]::Match((Get-Content $props -Raw), 'distributionUrl=.*?gradle-([0-9][^-]*)-')
    if (-not $m.Success) { return $null }
    # `$HOME` is a read-only automatic variable in PowerShell and assigning to it
    # throws, which is how this line failed the first time it ran. The user profile
    # directory has to be read from the environment.
    # The `.gradle` segment is added when GRADLE_USER_HOME is unset: that variable, when
    # it is set, is already the gradle home itself, and when it is not, the home is
    # `<user profile>/.gradle`. Omitting it pointed the message at
    # `C:\Users\XSZ20\daemon\9.1.0`, which does not exist -- a diagnostic that sends
    # the reader somewhere there is nothing to read.
    $userHome = $env:GRADLE_USER_HOME
    if (-not $userHome) {
        $profileDir = if ($IsWindows -or ($env:OS -eq 'Windows_NT')) { $env:USERPROFILE } else { $env:HOME }
        if (-not $profileDir) { return $null }
        $userHome = Join-Path $profileDir '.gradle'
    }
    return (Join-Path $userHome ("daemon/{0}" -f $m.Groups[1].Value))
}

function Stop-GradleDaemon {
    <#
        Ends the daemon, with a bound on how long that is allowed to take.

        `gradlew --stop` is the wrapper's own way to do this, and against a daemon
        that has stopped answering it does not return either -- it starts a client
        JVM, asks the daemon to stop, and waits for the daemon it is trying to stop.
        Measure it: the first version of the retry path called `--stop` in the
        foreground and the whole thing hung for twelve minutes on a machine where
        the timeout was two seconds, which is a retry that can hang the pipeline it
        exists to protect.

        So `--stop` runs under its own timeout, and if it does not finish, the daemon
        process is ended directly. That is a last resort and it is narrow: only a
        `java` process whose command line is a Gradle daemon, never a JVM running
        something else on this machine.
    #>
    param([string] $Root, [int] $TimeoutSeconds = 15)

    $isWindowsHost = $IsWindows -or ($env:OS -eq 'Windows_NT')
    $gradlew = Join-Path $Root $(if ($isWindowsHost) { 'gradlew.bat' } else { 'gradlew' })

    $stopped = $false
    if (Test-Path $gradlew) {
        # `Start-Process -PassThru` is assigned before it is polled. Piping it
        # straight into a loop hides the ordering: the loop body only runs when the
        # object arrives, and when it arrives after the process has exited there is
        # nothing to poll and the call has already waited for the thing the timeout
        # exists to bound.
        $stopJob = Start-Process -FilePath 'pwsh' -PassThru -NoNewWindow -WorkingDirectory $Root -ArgumentList @(
            '-NoProfile', '-ExecutionPolicy', 'Bypass', '-Command', ('& ''{0}'' --stop *> $null; exit 0' -f $gradlew)
        ) -ErrorAction SilentlyContinue
        if ($stopJob) {
            $sw = [System.Diagnostics.Stopwatch]::StartNew()
            while (-not $stopJob.HasExited -and $sw.Elapsed.TotalSeconds -lt $TimeoutSeconds) {
                Start-Sleep -Milliseconds 250
                $stopJob.Refresh()
            }
            if (-not $stopJob.HasExited) { try { $stopJob.Kill() } catch { } }
            else { $stopped = $true }
        }
    }
    if ($stopped) { return $true }

    Write-Host ("  gradlew --stop did not return in {0}s; ending gradle daemons directly" -f $TimeoutSeconds) -ForegroundColor DarkYellow
    $killed = 0
    try {
        foreach ($p in (Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue)) {
            # The wrapper is a `java` process too, and so is a Gradle client JVM: a
            # filter on the executable name alone would end JVMs this check does not
            # own. Only the daemon matches, by its main class.
            if ([string]$p.CommandLine -notmatch 'org\.gradle\.launcher\.daemon\.GradleDaemon') { continue }
            try { Stop-Process -Id $p.ProcessId -Force -ErrorAction Stop; $killed++ } catch { }
        }
    } catch { }
    return ($killed -gt 0)
}

function Clear-GradleDaemonState {
    <#
        Ends the daemon and deletes the registry. Both steps are needed: deleting the
        registry while a daemon still holds it is the state that produces the next
        hang, and stopping the daemon while the registry is intact is what `--stop`
        already does.
    #>
    param([string] $Root)

    $null = Stop-GradleDaemon $Root

    $dir = Get-DaemonLogDir $Root
    if ($null -eq $dir) { return }
    foreach ($name in 'registry.bin', 'registry.bin.lock') {
        $p = Join-Path $dir $name
        if (Test-Path $p) { Remove-Item $p -Force -ErrorAction SilentlyContinue }
    }
}

function Invoke-GradleQuery {
    <#
        .SYNOPSIS
        Resolves a Gradle configuration, or throws with the reason it could not.

        .PARAMETER Root
        Project root that holds `gradlew`.

        .PARAMETER Configuration
        Configuration to resolve, for example `releaseRuntimeClasspath`.

        .PARAMETER TimeoutSeconds
        Wall-clock budget per attempt. Defaults to 180, which is roughly ten times
        what a warm daemon needs on this machine.

        .OUTPUTS
        The dependency report as a single string.
    #>
    param(
        [Parameter(Mandatory)][string] $Root,
        [Parameter(Mandatory)][string] $Configuration,
        [int] $TimeoutSeconds = 0
    )

    if ($TimeoutSeconds -le 0) { $TimeoutSeconds = $script:gradleQueryDefaultTimeout }

    $isWindowsHost = $IsWindows -or ($env:OS -eq 'Windows_NT')
    $gradlew = Join-Path $Root $(if ($isWindowsHost) { 'gradlew.bat' } else { 'gradlew' })
    if (-not (Test-Path $gradlew)) { throw "Gradle wrapper not found: $gradlew" }

    $logDir = Get-DaemonLogDir $Root
    $outFile = Join-Path ([System.IO.Path]::GetTempPath()) ("gradle-query-{0}.txt" -f [guid]::NewGuid().ToString('N'))
    $errFile = "$outFile.err"

    for ($attempt = 1; $attempt -le 2; $attempt++) {
        # A daemon that never answered cannot answer faster the second time. Both
        # attempts are the same call; the difference is the daemon state in front of
        # it, and the second one is the last.
        if ($attempt -eq 2) {
            Write-Host '  the Gradle daemon did not answer; restarting it and asking once more ...' -ForegroundColor DarkYellow
            Clear-GradleDaemonState $Root
        }

        # Both streams redirected, to two different files: `Start-Process` refuses
        # to send two streams to one path. The redirection is not cosmetic either --
        # a child that inherits the console is not reliably killed by `Kill()`,
        # because the console keeps the process tree alive.
        $child = Start-Process -FilePath 'pwsh' -PassThru -NoNewWindow -WorkingDirectory $Root `
            -RedirectStandardOutput $outFile -RedirectStandardError $errFile `
            -ArgumentList @(
                '-NoProfile', '-ExecutionPolicy', 'Bypass', '-Command',
                ('& ''{0}'' -q '':app:dependencies'' --configuration {1} --console=plain; exit $LASTEXITCODE' -f $gradlew, $Configuration)
            ) -ErrorAction Stop

        $sw = [System.Diagnostics.Stopwatch]::StartNew()
        $timedOut = $false
        while (-not $child.HasExited) {
            if ($sw.Elapsed.TotalSeconds -ge $TimeoutSeconds) {
                $timedOut = $true
                # The tree, not the process: the wrapper starts a JVM, and killing
                # only the pwsh that launched it leaves that JVM holding the temp
                # files this function is about to read.
                try { & taskkill /PID $child.Id /T /F 2>&1 | Out-Null } catch { }
                try { $child.Kill() } catch { }
                break
            }
            Start-Sleep -Milliseconds 500
            $child.Refresh()
        }

        $raw = ''
        if (Test-Path $outFile) { $raw = [string](Get-Content $outFile -Raw -ErrorAction SilentlyContinue) }
        $errText = ''
        if (Test-Path $errFile) { $errText = [string](Get-Content $errFile -Raw -ErrorAction SilentlyContinue) }

        if ($timedOut) {
            # The child wrote into $outFile while it ran, and the file size is the
            # best evidence available for whether it was working or wedged. It is
            # reported rather than interpreted.
            $size = if (Test-Path $outFile) { (Get-Item $outFile).Length } else { 0 }
            Write-Host ("  attempt {0}: no answer after {1}s ({2} bytes written)" -f $attempt, $TimeoutSeconds, $size) -ForegroundColor DarkYellow
            continue
        }

        # `ExitCode` is read defensively: after a kill the process handle can be gone,
        # and a GET on it throws. That throw would escape this function and end the
        # gate it was called from, which is the failure this whole exercise is about.
        $code = -1
        try { $code = $child.ExitCode } catch { $code = -1 }

        Remove-Item $outFile -Force -ErrorAction SilentlyContinue
        Remove-Item $errFile -Force -ErrorAction SilentlyContinue

        # A non-zero exit from an empty report is not a licence finding or a privacy
        # finding; it is Gradle saying it could not resolve, and the message is in the
        # report. Either way the caller gets the text and the exit code.
        if ($code -ne 0 -or $raw -notmatch '\S') {
            $snippet = $raw
            if ($errText -match '\S') { $snippet = "$snippet`n$errText" }
            if ($snippet.Length -gt 2000) { $snippet = $snippet.Substring($snippet.Length - 2000) }
            throw "Gradle failed while resolving $Configuration (exit $code):`n$snippet"
        }
        return $raw
    }

    Remove-Item $outFile -Force -ErrorAction SilentlyContinue
    Remove-Item $errFile -Force -ErrorAction SilentlyContinue
    $where = if ($logDir) { $logDir } else { '<gradle user home>/daemon/<version>' }
    throw ("Gradle did not answer twice for $Configuration (${TimeoutSeconds}s each time). " +
           "The daemon is not responding on this machine. It has been stopped and its registry " +
           "cleared; check $where for a daemon-*.out.log that is zero bytes and rerun.")
}
