# Mutation sweep over the features added most recently.
#
# Each entry breaks one specific behaviour in the product code. A mutation that
# no test notices means no test asserts that behaviour, and the comment next to
# the code claiming it does is the only thing holding it up.
#
# Run: pwsh -File scripts/mutation-sweep.ps1

param(
    # Sweep a tree that carries uncommitted edits anyway. See the guard below for
    # what that costs.
    [switch]$AllowDirty
)

$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
. "$PSScriptRoot/app-package.ps1"
$appPkg = Get-AppPackage $root
# The JDK is named here only as a fallback. Hardcoding one machine's install path meant
# this script did not run anywhere else -- including CI, which is the one place a sweep
# is worth having, because a survivor list nobody looks at is a comment.
if (-not $env:JAVA_HOME -or -not (Test-Path $env:JAVA_HOME)) {
    foreach ($candidate in @('D:\Program Files\Eclipse Adoptium\jdk-25.0.4.101-hotspot')) {
        if (Test-Path $candidate) { $env:JAVA_HOME = $candidate; break }
    }
}
Set-Location $root

# The tree has to be clean, and this is not a formality.
#
# The baseline is measured, a mutation is planted on top of whatever is in the
# working tree, and a build that fails is counted as "caught". So uncommitted edits
# do not merely add noise: they can fail the build themselves, which the sweep reads
# as proof that a test asserts the mutated behaviour. That happened -- a rename in
# progress turned eight anchors into compile errors that had nothing to do with the
# planted mutation, and the run still ended `CLEAN: 45 caught, 0 survived`, which is
# the most confident possible way to say something untrue.
#
# Untracked files are ignored: build output lands in one, and a stray file cannot
# change what the compiler does with the mutated source.
if (-not $AllowDirty) {
    # A mode-only change is not an uncommitted edit. `chmod +x ./gradlew`, which the CI
    # workflow runs before anything else, shows up as ` M gradlew` and changes nothing
    # the compiler reads -- and this guard refused the sweep on exactly that, in CI, on
    # every run, which turned a check into an obstacle.
    #
    # git reports a mode-only change in --numstat as `0	0	<path>`, so the counts are
    # the test. HEAD is the left side because an edit that is already staged shows
    # nothing in the unstaged diff, and this guard has to see those too.
    $dirty = [System.Collections.Generic.List[string]]::new()
    foreach ($line in @(git status --porcelain --untracked-files=no)) {
        if ($line.Length -lt 4) { $dirty.Add($line); continue }
        $path = $line.Substring(3).Trim('"')
        $edited = $false
        foreach ($stat in @(git diff --numstat HEAD -- $path)) {
            $fields = $stat -split "`t"
            if ($fields.Count -lt 3) { continue }
            if ($fields[0] -ne '0' -or $fields[1] -ne '0') { $edited = $true }
        }
        if ($edited) { $dirty.Add($line) }
    }
    if ($dirty.Count -gt 0) {
        Write-Host "REFUSED - the working tree carries uncommitted changes:" -ForegroundColor Red
        $dirty | ForEach-Object { Write-Host "  $_" }
        Write-Host ''
        Write-Host 'Commit or stash them first. A sweep over a dirty tree counts build errors as coverage.' -ForegroundColor Red
        Write-Host 'Pass -AllowDirty to sweep anyway and read the doubtful list it prints.' -ForegroundColor Red
        exit 2
    }
}

function Get-Failures {
    $dir = 'app/build/test-results/testDebugUnitTest'
    $names = @()
    Get-ChildItem $dir -Filter '*.xml' -ErrorAction SilentlyContinue | ForEach-Object {
        [xml]$d = Get-Content $_.FullName
        $d.testsuite.testcase | Where-Object { $_.failure } | ForEach-Object { $names += $_.name }
    }
    return $names
}

<#
    A failure that is not about behaviour.

    Writing a source file and rebuilding it, thirteen times in a row, leaves the
    incremental compiler's output damaged often enough to matter: the next run
    fails with NoClassDefFoundError on test classes that have nothing to do with
    the mutation. Counting that as "the mutation was caught" makes this whole
    sweep worthless, because it reports coverage where there is none.

    A run that shows this signature is discarded, not counted either way.
#>
function Get-InfrastructureFailures {
    $dir = 'app/build/test-results/testDebugUnitTest'
    $names = @()
    Get-ChildItem $dir -Filter '*.xml' -ErrorAction SilentlyContinue | ForEach-Object {
        [xml]$d = Get-Content $_.FullName
        $d.testsuite.testcase | Where-Object { $_.failure } | ForEach-Object {
            if ($_.failure.'#text' -match 'NoClassDefFoundError|NoSuchMethodError|ClassNotFoundException|IncompatibleClassChangeError') {
                $names += $_.name
            }
        }
    }
    return $names
}

function Get-Baseline {
    & cmd.exe /c 'gradlew.bat :app:testDebugUnitTest --console=plain -q 2>&1' | Out-Null
    return (Get-Failures)
}

# The baseline, with a retry.
#
# The rule is real -- sweeping against a failing baseline reports every mutation as
# caught, because the failure is already there -- but a single red run is not the
# same as a broken baseline, and treating them alike made this script unrunnable.
# This was written for the intermittent failure these runs used to hit (roughly one
# run in five under load, a rename the file system refused with nothing retrying
# it). That cause was found and fixed on 2026-10-15 -- `FileChatStore` now retries
# the move, and `docs/OPEN-WIRING.md` has the reasoning -- so what the three
# attempts remain for is ordinary local load: a machine running this sweep is a
# machine running thirteen Gradle builds in a row.
#
# Three attempts, and the result is reported either way. A tree that is genuinely
# broken fails all three.
$baselineAttempts = 3
$baseline = @()

for ($attempt = 1; $attempt -le $baselineAttempts; $attempt++) {
    $baseline = Get-Baseline
    if ($baseline.Count -eq 0) { break }

    Write-Host "Baseline attempt $attempt`: $($baseline.Count) failing — $($baseline -join ', ')" -ForegroundColor Yellow
    if ($attempt -lt $baselineAttempts) {
        Write-Host '  Retrying: one red run does not mean a broken tree, and this suite carries its own load.'
    }
}

if ($baseline.Count -gt 0) {
    Write-Host "Baseline is not green after $baselineAttempts attempts ($($baseline.Count) failing). Fix that first." -ForegroundColor Red
    exit 1
}
Write-Host 'Baseline green.' -ForegroundColor Green

$mutations = @(
    @{ file = 'ui/chat/MarkdownText.kt'
       name = 'an unterminated fence is shown as code'
       from = 'if (contents.isNotEmpty()) segments += ReplySegment.Prose(contents)'
       to   = 'segments += ReplySegment.Code(contents, language)' }

    @{ file = 'ui/chat/MarkdownText.kt'
       name = 'any fence-prefixed line closes a block'
       from = 'isFence && marker.isEmpty() ->'
       to   = 'isFence ->' }

    @{ file = 'ui/chat/MarkdownText.kt'
       name = 'an empty fence becomes an empty code box'
       from = 'if (contents.isNotEmpty()) segments += ReplySegment.Code(contents, language)'
       to   = 'segments += ReplySegment.Code(contents, language)' }

    @{ file = 'data/chat/ConversationExport.kt'
       name = 'interrupted marker omitted from the export'
       from = 'builder.appendLine(labels.stoppedHere)'
       to   = 'builder.appendLine("")' }

    @{ file = 'data/chat/ConversationExport.kt'
       name = 'export file name has no fallback'
       from = '.ifBlank { "conversation" }'
       to   = '.ifBlank { "x" }' }

    @{ file = 'data/settings/SettingsStore.kt'
       name = 'blank system prompt no longer falls back to the default'
       from = 'val effectiveSystemPrompt: String get() = systemPrompt.ifBlank { DEFAULT_SYSTEM_PROMPT }'
       to   = 'val effectiveSystemPrompt: String get() = systemPrompt' }

    @{ file = 'data/settings/SettingsStore.kt'
       name = 'temperature divided by the wrong factor'
       from = 'val temperature: Double get() = temperatureTenths / 10.0'
       to   = 'val temperature: Double get() = temperatureTenths / 100.0' }

    @{ file = 'model/ModelImporter.kt'
       name = 'free-space check removed'
       from = 'if (expectedBytes > 0L && expectedBytes > available) {'
       to   = 'if (false) {' }

    @{ file = 'model/ModelImporter.kt'
       name = 'empty file accepted'
       from = 'if (written == 0L) throw IOException("That file is empty")'
       to   = 'if (false) throw IOException("That file is empty")' }

    @{ file = 'model/ModelRegistry.kt'
       name = 'imported model appended instead of replacing'
       from = 'val next = this.imported.value.filterNot { it.id == imported.id } + imported'
       to   = 'val next = this.imported.value + imported' }

    @{ file = 'ui/chat/ChatConversation.kt'
       name = 'deleting a question leaves its answer behind'
       from = '.takeWhile { it.role != MessageRole.USER }'
       to   = '.takeWhile { false }' }

    @{ file = 'ui/history/HistoryUiState.kt'
       name = 'search no longer matches message text'
       from = 'row.conversation.messages.any { it.text.contains(needle, ignoreCase = true) }'
       to   = 'false' }

    @{ file = 'ui/history/HistoryUiState.kt'
       name = 'search ignores the query entirely'
       from = 'if (needle.isEmpty()) return rows'
       to   = 'return rows' }

    @{ file = 'ui/models/ModelManagerViewModel.kt'
       name = 'import file name sanitisation bypassed'
       from = 'return cleaned.ifBlank { "imported-model.litertlm" }'
       to   = 'return name' }

    @{ file = 'ui/chat/ChatGeneration.kt'
       name = 'a model nothing can be asked of still passes the guard'
       from = '_state.value.modelId != null && engine.state.value is EngineState.Ready'
       to   = '_state.value.modelId != null' }

    @{ file = 'ui/chat/ChatGeneration.kt'
       name = 'asking again seeds the question it is about to send'
       from = 'reseedRuntimeWith(trimmed, messages = upto.dropLast(1))'
       to   = 'reseedRuntimeWith(trimmed, messages = upto)' }

    @{ file = 'ui/chat/ChatModels.kt'
       name = 'a model load empties the runtime instead of seeding it'
       from = '            reseedRuntimeWith(conversation)'
       to   = '            engine.resetConversation()' }

    @{ file = 'ui/chat/ChatSaving.kt'
       name = 'a save stops deferring to the title on disk'
       from = 'if (stored.messages.isEmpty()) return withPin'
       to   = 'return withPin' }

    @{ file = 'data/settings/SettingsStore.kt'
       name = 'a stored settings value is no longer brought into range'
       from = '.let { json.decodeFromString<Settings>(it).sanitised() }'
       to   = '.let { json.decodeFromString<Settings>(it) }' }

    # The sampler the runtime is handed is only reachable on a device, and is
    # asserted there by app/src/androidTest/.../LiteRtLlmEngineTest. What can be
    # checked here is the half that runs in a JVM: the setting is read when the
    # model is loaded, and it is the value the engine is given. A screen offering
    # five choices that never leave the file is worse than no screen.
    @{ file = 'ui/chat/ChatModels.kt'
       name = 'the creativity setting never reaches the engine'
       from = 'settings.samplingFor(descriptor.id),'
       to   = 'GenerationConfig(),' }

    @{ file = 'ui/chat/ChatGeneration.kt'
       name = 'the reply length never reaches the generation request'
       from = 'maxTokens = settings.maxReplyTokens,'
       to   = 'maxTokens = 512,' }

    # ---------------------------------------------------------------------------
    # Behaviours a later session changed: a defect fixed, or a feature wired up.
    #
    # The question is the same one -- does any test notice this going away -- and the
    # answer matters more here than for a new feature, because a fix carries a comment
    # explaining what was wrong, and a comment is what holds a fix up when nothing
    # asserts it. Each name below describes the code the fix removed, so a survivor
    # reads as "this fix is back".
    # ---------------------------------------------------------------------------

    @{ file = 'ui/history/HistoryViewModel.kt'
       name = 'a row stops carrying the space its conversation takes'
       from = 'rows = conversations.map { HistoryRow(it, bytes = sizes[it.id]) },'
       to   = 'rows = conversations.map { HistoryRow(it) },' }

    @{ file = 'ui/chat/ChatViewModel.kt'
       name = 'a reply is stamped with the raw clock, losing the backwards-clock guard'
       from = 'maxOf(now, updatedAt + 1)'
       to   = 'now' }

    @{ file = 'ui/models/ModelManagerScreen.kt'
       name = 'the benchmark prompt is dropped at the call site'
       from = 'benchmarkPrompt = stringResource(R.string.models_benchmark_prompt),'
       to   = '' }

    @{ file = 'model/ModelStore.kt'
       name = 'available space ignores what the platform could reclaim'
       from = 'return runCatching { provider() }.getOrNull() ?: root.usableSpace'
       to   = 'return root.usableSpace' }

    @{ file = 'model/ModelDownloader.kt'
       name = 'progress is throttled to nanoseconds rather than milliseconds'
       from = 'const val PROGRESS_INTERVAL_NANOS = 200_000_000L'
       to   = 'const val PROGRESS_INTERVAL_NANOS = 200L' }

    @{ file = 'ui/about/AboutViewModel.kt'
       name = 'a wipe that wrote nothing reports zero rather than nothing'
       from = 'wipedBytes = written?.takeIf { it > 0L },'
       to   = 'wipedBytes = written ?: 0L,' }

    @{ file = 'ui/chat/MarkdownText.kt'
       name = 'the flanking rule is dropped, so 2*3*4 becomes emphasis'
       from = 'if (!opening || i + runLength >= text.length || text[i + runLength].isWhitespace()) {'
       to   = 'if (i + runLength >= text.length) {' }

    @{ file = 'ui/about/AboutScreen.kt'
       name = 'the diagnostics report stops naming the loaded model'
       from = 'modelId = state.runningModelId,'
       to   = '' }

    @{ file = 'data/chat/ChatStore.kt'
       name = 'a late save resurrects a deleted conversation'
       from = 'if (removedAt != null && conversation.updatedAt <= removedAt) {'
       to   = 'if (false) {' }

    # The four counters in `ChatStore.saveAudit`. They were dead once -- declared,
    # documented, and never incremented -- and nothing noticed, because their only
    # reader runs when a test is already failing. One mutation per counter, so a
    # counter that stops moving is attributable to the counter rather than to the
    # block it sits in.
    @{ file = 'data/chat/ChatStore.kt'
       name = 'the save audit stops counting attempts'
       from = '            savesAttempted++'
       to   = '            savesAttempted += 2' }

    @{ file = 'data/chat/ChatStore.kt'
       name = 'the save audit stops counting writes'
       from = '            savesWritten++'
       to   = '            savesWritten += 2' }

    @{ file = 'data/chat/ChatStore.kt'
       name = 'the save audit stops counting the newest-wins refusal'
       from = '                savesRefusedAsOlder++'
       to   = '                savesRefusedAsOlder += 2' }

    @{ file = 'data/chat/ChatStore.kt'
       name = 'the save audit stops counting the tombstone refusal'
       from = '                savesRefusedAsDeleted++'
       to   = '                savesRefusedAsDeleted += 2' }

    # The report is the only channel this app has, and it is read by whoever is
    # asked to fix the bug rather than by a user. Both of these were true of the
    # code before anything tested it.
    @{ file = 'diagnostics/DiagnosticsReport.kt'
       name = 'the report prints none for a model it was given'
       from = 'appendLine("${labels.model}: ${facts.modelId ?: labels.none}")'
       to   = 'appendLine("${labels.model}: ${labels.none}")' }

    @{ file = 'diagnostics/DiagnosticsReport.kt'
       name = 'the report stops redacting what it carries'
       from = 'return DiagnosticsRedactor.redact(raw)'
       to   = 'return raw' }

    # The retry added for the pin/archive tap. A tap is not a reply: the store keeps
    # the newer of two versions, which is right for a reply racing a save and wrong
    # for a tap, because a refusal is not an exception and the row comes back
    # unpinned with nothing on screen to say why. Three anchors, one per way the
    # retry can be dropped: the early return that decides the first write was enough,
    # the second write itself, and the read-back that turns a double refusal into
    # something the user is told about.
    @{ file = 'ui/history/HistoryViewModel.kt'
       name = 'a first write that lost the race is treated as done'
       from = 'if (stamp(chatStore.load(id) ?: return) == wanted) return'
       to   = 'return' }

    @{ file = 'ui/history/HistoryViewModel.kt'
       name = 'a tap refused once is not tried again'
       from = 'chatStore.save(change(fresh, wanted).copy(updatedAt = maxOf(clock(), fresh.updatedAt + 1)))'
       to   = 'Unit' }

    @{ file = 'ui/history/HistoryViewModel.kt'
       name = 'a store that refuses twice loses the tap in silence'
       from = 'if (stamp(chatStore.load(id) ?: return) != wanted) throw StoreRefusedTwice()'
       to   = 'if (false) throw StoreRefusedTwice()' }

    # The list's stamp read the device locale while the export it feeds wrote
    # `Locale.US`, so a device set to Thai dated the same conversation 2568 in the
    # list and 2025 in the file. Digits a file carries anywhere, or no digits.
    @{ file = 'ui/history/HistoryViewModel.kt'
       name = 'the export stamp follows the device locale again'
       from = 'java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)'
       to   = 'java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())' }

    # Reading a picked file. The limit is checked as the bytes arrive; without it a
    # file picked by mistake is copied into memory whole before anything looks at it.
    @{ file = 'data/chat/BackupTransfer.kt'
       name = 'an oversized import is read to the end before being refused'
       from = 'if (total > limitBytes) return null'
       to   = 'if (false) return null' }

    # A Binder transaction carries about a megabyte and a String costs two bytes per
    # character inside one: this bound is the difference between an export that is
    # refused and a crash on the main thread.
    @{ file = 'data/chat/BackupTransfer.kt'
       name = 'the share limit is raised past what a transaction can carry'
       from = 'const val MAX_SHARE_CHARS = 300_000'
       to   = 'const val MAX_SHARE_CHARS = 3_000_000' }

    # The retry around a refused move. A rename is refused for as long as something
    # that is not this app holds the target -- a scanner, an indexer -- and one more
    # attempt is the difference between a save that lands and the user being told the
    # conversation could not be saved while nothing was wrong with it.
    #
    # The anchor is the guard, not the throw: the throw moved inside a block when the
    # refusal's own account started being recorded, and `if (true)` is the same
    # mutation as removing the guard -- the first refusal is reported instead of tried
    # again.
    @{ file = 'data/chat/ChatStore.kt'
       name = 'a refused move is reported without being tried again'
       from = 'if (attempt >= MOVE_ATTEMPTS) {'
       to   = 'if (true) {' }

    # The account the refusal leaves behind. Three widenings of the retry schedule were
    # decided without it -- `FileSystemException.reason` is what separates a sharing
    # violation from a read-only target from a full disk, and the message the caller
    # carries does not always include it. One mutation per part the test asserts, so a
    # part that stops being filled in is attributable to that part.
    @{ file = 'data/chat/ChatStore.kt'
       name = 'the refusal account stops saying what the system said'
       from = 'val reason = (refused as? java.nio.file.FileSystemException)?.reason'
       to   = 'val reason: String? = null' }

    @{ file = 'data/chat/ChatStore.kt'
       name = 'the refusal account reports a window that was not waited'
       from = 'val waited = FileChatStore.MOVE_RETRY_MILLIS.take(attempts - 1).sum()'
       to   = 'val waited = 0L' }
)

$survived = @()
$discarded = @()
$doubtful = @()
$caught = 0

# Every anchor has to resolve before the sweep starts.
#
# A stale anchor is not a survivor and it is not a caught mutation; it is a mutation
# that did not run, and counting it either way is wrong. The previous version counted
# it as a survivor -- `$survived += "... (anchor not found)"` -- which is the loud
# direction, and five anchors had gone stale anyway without anyone reading the line,
# because the sweep takes half an hour to produce output that nobody diffs against
# the previous run. Checking them all up front costs a second and makes the failure
# impossible to miss: the sweep does not start.
$stale = @()
foreach ($m in $mutations) {
    $path = Join-Path (Join-Path "$root/app/src/main/java" $appPkg) $m.file
    if (-not (Test-Path $path)) {
        $stale += "$($m.name)  —  file not found: $($m.file)"
    } elseif (-not (Get-Content $path -Raw).Contains($m.from)) {
        $stale += "$($m.name)  —  anchor not found in $($m.file)`n        $($m.from)"
    }
}

if ($stale.Count -gt 0) {
    Write-Host "STALE MUTATIONS ($($stale.Count) of $($mutations.Count)) — the sweep did not run." -ForegroundColor Red
    Write-Host ''
    foreach ($s in $stale) { Write-Host "  - $s" -ForegroundColor Red }
    Write-Host ''
    Write-Host 'An anchor is a claim about where a behaviour lives. When the code moves,' -ForegroundColor Yellow
    Write-Host 'the claim is wrong, and a mutation that cannot be planted tests nothing.' -ForegroundColor Yellow
    Write-Host 'Fix each anchor against the current source.' -ForegroundColor Yellow
    exit 1
}

Write-Host "All $($mutations.Count) anchors resolve." -ForegroundColor Green

foreach ($m in $mutations) {
    $path = Join-Path (Join-Path "$root/app/src/main/java" $appPkg) $m.file
    $original = Get-Content $path -Raw
    # Emptied before the run, so that "no failing test" can only mean what it says.
    # Reading the directory as it stands reads the previous run's results whenever
    # this one never reached the tests, which is exactly when the answer matters.
    Remove-Item 'app/build/test-results/testDebugUnitTest' -Recurse -Force -ErrorAction SilentlyContinue
    # In a `finally`: an exception thrown anywhere between here and the next
    # `Set-Content` would otherwise leave the product source holding the mutation, and
    # the next gate to read that file would be reading a tree nobody edited.
    try {
        Set-Content $path -Value $original.Replace($m.from, $m.to) -Encoding UTF8 -NoNewline

        $log = & cmd.exe /c 'gradlew.bat :app:testDebugUnitTest --console=plain -q 2>&1'
        $exit = $LASTEXITCODE
        $failures = Get-Failures
        $bogus = Get-InfrastructureFailures
    } finally {
        Set-Content $path -Value $original -Encoding UTF8 -NoNewline
    }

    if ($bogus.Count -gt 0) {
        Write-Host ("  DISCARDED     {0}  (build artifacts were stale, not a result)" -f $m.name) -ForegroundColor Yellow
        $discarded += $m.name
        # Rebuild cleanly so the next mutation starts from a sound state.
        & cmd.exe /c 'gradlew.bat :app:clean --console=plain -q 2>&1' | Out-Null
        continue
    }

    # A mutation the compiler rejects is not a survivor, and a build that failed for
    # some other reason is not coverage.
    #
    # Most of these mutations delete a line, and a deleted argument is only a defect
    # while the parameter it fills has a default. Where the default has been removed
    # the program cannot be written that way at all, the build stops before any test
    # runs, and there is no failing test to find -- so the old code printed SURVIVED,
    # which says "no test asserts this behaviour" about a mutation no test could ever
    # see. The distinction matters the other way too: this machine's Gradle daemon
    # wedges, and a run that never reached the tests must not be reported as a
    # mutation that was caught.
    if ($failures.Count -eq 0 -and $exit -ne 0) {
        $compileErrors = @($log | Where-Object { $_ -match '^e: ' })
        # A build error has to be in the file the mutation edited to say anything
        # about that mutation. The sweep is often run over a tree that is not
        # pristine -- and without the guard above, always was -- so an error
        # somewhere else is a report about the tree, not about the anchor, and
        # counting it as a catch would manufacture the coverage the sweep exists to
        # measure.
        $leaf = Split-Path $m.file -Leaf
        $attributable = @($compileErrors | Where-Object { $_ -match [regex]::Escape($leaf) })
        if ($compileErrors.Count -gt 0 -and $attributable.Count -gt 0) {
            Write-Host ("  caught        {0}  (does not compile: {1})" -f $m.name, $attributable[0].Trim()) -ForegroundColor Green
            $caught++
        } elseif ($compileErrors.Count -gt 0) {
            Write-Host ("  DOUBTFUL      {0}  (the build failed in another file: {1})" -f $m.name, $compileErrors[0].Trim()) -ForegroundColor Yellow
            $doubtful += "$($m.name)  [$($m.file)]  <- $($compileErrors[0].Trim())"
        } else {
            $first = @($log | Where-Object { $_ -match '\S' } | Select-Object -Last 1)
            Write-Host ("  DISCARDED     {0}  (the build failed and no test ran)" -f $m.name) -ForegroundColor Yellow
            if ($first.Count -gt 0) { Write-Host ("                {0}" -f $first[0].Trim()) -ForegroundColor Yellow }
            $discarded += $m.name
        }
        continue
    }

    if ($failures.Count -eq 0) {
        Write-Host ("  SURVIVED      {0}" -f $m.name) -ForegroundColor Red
        $survived += "$($m.name)  [$($m.file)]"
    } else {
        Write-Host ("  caught        {0}  ({1} test(s))" -f $m.name, $failures.Count)
        $caught++
    }
}

Write-Host ''
Write-Host ("  {0} caught, {1} survived, {2} discarded, {3} doubtful" -f $caught, $survived.Count, $discarded.Count, $doubtful.Count)
if ($survived.Count -gt 0) {
    Write-Host ''
    Write-Host 'Survivors, meaning no test asserts this behaviour:' -ForegroundColor Red
    $survived | ForEach-Object { Write-Host "  - $_" -ForegroundColor Red }
}
if ($doubtful.Count -gt 0) {
    Write-Host ''
    Write-Host 'Doubtful, meaning the build failed somewhere the mutation does not touch:' -ForegroundColor Yellow
    Write-Host 'Nothing here was proved either way. Commit the tree and run the sweep again.' -ForegroundColor Yellow
    $doubtful | ForEach-Object { Write-Host "  - $_" -ForegroundColor Yellow }
}

Get-Baseline | Out-Null
Write-Host ''
Write-Host 'Source restored.'

# The exit code is the whole point of running this in a pipeline.
#
# This script ran by hand for its entire life and ended with an `Out-Null`, so it
# exited 0 whatever it found. A survivor list printed at the end of a half-hour run
# that nothing reads and nothing fails on is a comment, and this project has a row in
# its own history for every one of those.
if ($survived.Count -gt 0) {
    Write-Host ''
    Write-Host "FAILED - $($survived.Count) mutation(s) survived: no test asserts those behaviours." -ForegroundColor Red
    exit 1
}

if ($doubtful.Count -gt 0) {
    Write-Host ''
    Write-Host "INCONCLUSIVE - $($doubtful.Count) mutation(s) could not be judged: the build failed in a file they do not touch." -ForegroundColor Yellow
    exit 3
}

Write-Host "CLEAN - all $caught mutations were caught, none survived, $($discarded.Count) discarded." -ForegroundColor Green
exit 0
