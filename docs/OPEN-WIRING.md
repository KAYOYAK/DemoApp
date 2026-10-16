# Open wiring issues

**Both issues this file was written for are fixed.** Kept because the bottom
section is still open, and because deleting the record of a bug and then meeting
the same bug again is how a project relearns it.

| Was | Now |
| --- | --- |
| The benchmark sent an empty prompt | Fixed — the prompt is passed from `ModelManagerScreen`, which is where `stringResource` is available. Passing it from `Navigation.kt` would also work; this is one layer closer to the text. |
| The first-run guidance was unreachable | Fixed — `NoModelYet` is the live branch for `needsAModel`, and the shorter `NeedsAModel` is gone. |

## Closed: a recovery command deleted the work it was recovering

A negative test of the privacy gate injects `READ_CONTACTS` into
`app/src/main/AndroidManifest.xml`, runs the gate, and removes it. That run hung (see
the next section), the process was killed between the inject and the remove, and the
manifest was left with the permission in it. The obvious recovery is
`git checkout -- app/src/main/AndroidManifest.xml`, and it was run.

**It restored the file from the index, not from the working tree — and the two differed.**
The index held the pre-edit version of everything, because at the time every edit in this
repository lived in the working tree and nothing had been committed: the first commit,
`defc96e`, was not made until 00:00 on 2026-10-15. The command therefore removed the injected permission, as
intended, and also removed `android:dataExtractionRules="@xml/data_extraction_rules"`, which
had been added to that file earlier and never staged. Nothing on screen said so. The gate
caught it on the next run — `dataExtractionRules is not set` — which is the only reason it
was recovered rather than shipped.

An audit showed one file affected: the manifest's status went from `AM` to `A`, and
every other modified file still showed `AM`. The attribute was re-applied by hand and
the gate returned to 0.

**Two facts worth keeping:**

1. `git checkout -- <file>` restores from the index, never from the working tree, so on a
   file with staged *and* unstaged edits it keeps the staged version and discards everything
   since. What that costs depends on what the repository has to recover from: normally an
   unstaged edit is in the reflog or in `git stash`. On the day of this incident there was no
   history at all — `git stash` had no `HEAD` to stash against and `git worktree` was
   unavailable — so whatever the index did not have was gone. The first commit (`defc96e`)
   came later; the rule below is the one to follow either way, because it does not depend on
   what history happens to hold.
2. The correct recovery from a partially-applied injected edit is to **edit the file**,
   not to ask git for it. The injected change is a known one-line addition; deleting
   that line by hand cannot remove anything else.

The general form of this is the same family as the rest of this file, with the sign
flipped: every other entry is a check that was not covering what it claimed. This one
is a *tool* that did exactly what it says and destroyed something, because its
documented behaviour is unsafe in this repository's particular state.

### Why the run hung, and what actually fixes it

Three times in one session the Gradle daemon registry went bad with the same
signature: `~/.gradle/daemon/9.1.0/daemon-<pid>.out.log` is 0 bytes, and a process
that should take ten seconds shows roughly twelve seconds of CPU over ten minutes. A
build started in that state never finishes and never fails.

What works, in order: kill every `java.exe`, delete `registry.bin` and
`registry.bin.lock` from `~/.gradle/daemon/9.1.0/`, then warm the daemon with
`gradlew.bat -q :app:dependencies --configuration debugRuntimeClasspath`, which takes
about ten seconds. Deleting the registry alone is not enough, and killing the build
without killing the daemon leaves the next one poisoned.

## Closed: the copy gate could not read the language the app is written in

The gate that enforces "no prose in Kotlin" looked for two shapes, and both were ASCII:

```powershell
$prose      = '^[A-Za-z][A-Za-z''-]*(?: [A-Za-z][A-Za-z''-]*)+$'   # two or more English words
$singleWord = '^[A-Z][a-z]{3,}$'                                   # a capitalised word
```

This app is written entirely in Chinese. So the gate could catch an English label somebody
forgot to move, and could not see `你完全运行在用户自己的手机上，没有联网。` sitting in
`SettingsStore.kt` at all -- which is user-facing copy in a Kotlin file, the exact thing the
gate exists to prevent. Its own header says "no prose in Kotlin at all".

Found by trying to break it rather than by reading it: a Chinese sentence was inserted into
`Formatting.kt` as a plain string literal, and the gate reported `CLEAN`. Every other gate in
this repository had been observed to fail; this one had only ever been observed to pass,
which is the condition `PRINCIPLES.md` says is indistinguishable from a disabled gate.

### What it found once it could see

Four literals had been invisible to the gate and are all Chinese:

- `Conversation.UNNAMED` -- the initial title of a conversation, shown in the history list
  until the first message names it. **Left in place, and recorded as a compromise:** the
  value is persisted into the conversation file, so moving it to a resource means either a
  stored id that no longer resolves or a migration. Worth doing deliberately, as a change to
  the on-disk format, not as a side effect of teaching a gate to see.
- `CrashLog`'s two prefixes (`时间`/`异常`), in a function that has no `Context`.
- The redaction markers in `DiagnosticsRedactor` -- `<已隐去>`, `<地址已隐去>`, and the rest.
- Preview sample data in `ChatScreen` and `HistoryScreen`. `@Preview` is read by the IDE and
  never executes on a device, and the point of a preview is to show a screen that looks real.

All of them are listed in the gate with their reason, generated from the source rather than
typed. The list is UTF-8 bytes in hex, because the file is PowerShell and its parser reads
its own text in whatever encoding the host guesses -- the four hand-written entries that
preceded it contained a truncated UTF-8 sequence and a dropped byte, and that is the argument
for generating it.

### The four wrong turns, because they are the interesting part

Getting the gate to see Chinese took far longer than the change deserved, and the reason is
worth recording: **every diagnosis before the last one was wrong.** In order:

1. **Encoding.** The file was UTF-8 without a BOM, so a parser reading it as ANSI would mangle
   the CJK bytes. Adding a BOM did not help.
2. **Line endings.** Pure LF, while other scripts that work are also pure LF. Rewriting as CRLF
   did not help.
3. **The invocation.** `pwsh -File` versus `-Command` versus dot-sourcing -- the same error from
   all three.
4. **The characters themselves.** Replacing them with `[char]` calls did not help, and neither
   did replacing those with hex strings.

What actually found it was `[System.Management.Automation.Language.Parser]::ParseFile`, which
answers in milliseconds, runs nothing, and names the line and column:

```
L140:22  ',' 后缺少表达式。
```

**PowerShell does not permit a trailing comma after the last element of an array.** Four exact
checks -- `@(1,2,)` fails, `@(1,2)` parses, both forms of the multiline case match -- confirmed
it in one command. The `$cjkAllowedHex` list had been written entry-per-line with a comma after
every one, which is valid in Kotlin and JavaScript and not in PowerShell.

The lesson is not about commas. It is that a parser exists for the language you are editing,
it is free, and it is the first thing to reach for. Four rounds of reasoning about encodings
and terminals were replaced by one call that said which line was wrong.

### And the gate that would have caught it

`check-unused-strings.ps1` now parses every `scripts/*.ps1` and reports the ones that do not
compile:

```
scripts/check-legal-docs.ps1:165 does not parse: ',' 后缺少表达式。
```

Checked by introducing that exact defect into another gate and watching it report, which is the
same standard the rest of this file is held to. A gate that does not parse never runs, and
nothing else in the repository notices -- which is what happened here, twice: once to this
file's own auxiliary scripts during the work, and once to the list above.

---

## Closed: the format check proved the template and never counted the arguments

The string gate has two halves, and until now only one of them could fail.

The first half is real and works: `FmtCheck.java` compiles each `strings.xml` body that
contains a `%`, supplies a correctly-typed dummy for every slot, and reports what
`String.format` throws. It was checked the way this document keeps insisting -- a body
was set to `写了 %1。` and the gate reported `chat_rate would crash the screen when
formatted (UnknownFormatConversionException)`. That is the earlier defect in this file
being caught by its own gate, which is the point of writing it down.

The second half did not exist. A format string can be perfectly valid and still crash
at runtime, because `Resources.getString(id, args...)` raises when the *caller* supplies
too few:

```xml
<string name="chat_rate">%1$s 和 %2$s</string>      <!-- needs two -->
```
```kotlin
stringResource(R.string.chat_rate, rate)             <!-- supplies one -->
```

`MissingFormatArgumentException`, raised inside composition, so the screen closes --
the same visible failure as `%1。`, from a template that passes the checker.

### Checked before it was fixed

Sixty-eight strings carry a `%`. Every call site supplies enough for the one it names,
so there was no live defect. That was verified two ways: by an ad-hoc scan, and then by
the check built below being run against the tree.

### What the check is, and the mistake in its first version

`FmtCheck` now takes a third field per row: the argument count at the call site, the
**minimum** across call sites rather than the maximum, because a string formatted with
two arguments in one place and one in another crashes in the second place whatever the
first does. `-1` means no call site formats it at all -- only its id is passed -- and
the Java side reads that as "not this check's business" rather than as zero.

The first version of the collector looked for `R.string.<name>` and expected a `(`
immediately after it. Almost no call site is shaped that way, because both ways a
formatted string is reached put something else there:

```kotlin
stringResource(R.string.chat_rate, rate)         // a comma
context.getString(R.string.about_wipe_done, t)   // a comma
```

So the scan found nothing, recorded `-1`, and the check skipped every string --
silently, which is exactly the failure mode this whole file is about. It reported
`0 failures` on a tree where it had examined nothing.

That is the third time in this file that a check was correct in principle and inert in
practice because its anchor did not match the code, and the second time in this session
(the other being the mutation sweep's five stale anchors). The pattern is consistent
enough to name: **the check and the thing it checks drift apart, the check does not
fail, and nothing else notices.** The rewrite reads from the call -- `stringResource(`
or `getString(`, the first argument as the name, the rest counted -- and it strips
comments first, because `R.string.chat_rate` appears inside KDoc in this project and a
name read out of prose would be counted as a call site.

### Verified the same way as the half that already worked

| tree | result |
| --- | --- |
| as committed | exit 0 |
| `chat_rate` set to `%1$s 和 %2$s` (two slots, one argument) | exit 1, `needs 2 argument(s) and every call site supplies 1` |
| `chat_rate` set to `%1$d tok/s` (the false-positive trap) | exit 0 |

The third row is there because `-keepattributes`-style mistakes are how a checker earns
a reputation for lying, and this one has two attempts at false detection recorded in its
own comments already.

---

## Closed: the copy gate could not see a word inside a format string

A defect of the third kind: not a feature that was never wired, but **a gate that
could not see the thing it existed to find.**

`check-ui-copy.ps1` skipped any literal containing `:` or `%`, on the stated
grounds that a colon or a percent means a date format or a printf template "and
there is nothing in it to translate". That is true of `"%.0f MB"` and of
`"HH:mm"`. It was not true of `"%.1f tok/s"`, which was written in three files —
`Formatting.kt`, `ChatScreen.kt` and `ConversationBackup.kt` — and shipped English
inside a Chinese app for as long as the rule existed. Two of the three reached a
person: one under every finished reply, one in the Markdown export.

Fixed in two parts:

* **The word moved to the resource.** `chat_rate` is now `%1$s tok/s` and
  `export_rate` is `%1$s 词元/秒`, so the unit is translated with the sentence
  around it while the number keeps a locale-independent decimal separator. The
  Markdown export takes its labels the way the plain-text export already did, via
  a `MarkdownLabels` argument, and a test proves the injection by passing labels
  that could not be mistaken for the old literals.
* **The gate learned the difference.** A literal with a printf directive is now
  judged on what remains once the directives are removed: `MB` is a unit symbol
  and passes, `tok/s` is a word and does not.

**The first attempt at the second part was wrong and worth recording.** Judging
*every* literal with a colon or a percent reported twenty-six false positives —
`${labels.model}: ${facts.modelId}`, `\b(https?)://\S+`, `"yyyy-MM-dd HH:mm"`,
`"异常 ${error::class.java.name}"`. The narrow reading (only a real printf
directive) is the one that works, and the wider one is not a stricter check, it
is a broken one.

Two literal shapes remain outside this rule and are correct to leave there: SI
unit symbols, which are the same in every language this app could be written in,
and the `@Tool(description = ...)` strings in `LocalTools.kt`, which are read by
the model rather than by a person.

A third of the same shape was found after this file was written, and is also
fixed: **the archive action had no button.** `HistoryViewModel.toggleArchive`
existed, the card received `onToggleArchive`, and the card's `FlowRow` listed five
actions without it — so `history_archive`, `history_unarchive` and
`history_archived_marker` had zero references, which is how it was found.

## What none of this was caught by

`check-ui-wiring.ps1` catches a callback that is **never passed**. It does not
catch one that is passed and then **never invoked**, which is what the archive
button was: the whole chain was complete and the last link was simply absent. The
evidence was three string resources nothing could resolve, found by hand.

Two mechanical rules were proposed to close that gap. The first is written; the second is evaluated below and not adopted:

1. ~~A `@Composable` that declares an `on*` parameter and never mentions it again
   past the parameter list is accepting a callback it does not use.~~ — written,
   and wrong as stated. See the section below.
2. ~~A feature whose strings are all unreferenced is a feature with no way in.~~ —
   implemented far enough to measure, 41 false positives on a clean tree, and
   dropped. See the section below.

**A rule that has not been observed to fail is indistinguishable from a disabled
one**, so either one should be written, pointed at the archive case in this
repository's history, and seen to report it before it is trusted. Both were, which
is how the second was found to be wrong.

---

## Closed: the callback that reaches every declaration and no invocation

The proposal above was written in five lines. What it became is 512, and the
distance between them is the whole point of this section.

The rule as first imagined — *a `@Composable` that declares an `on*` parameter and
never mentions it again is accepting a callback it does not use* — **passes the
archive defect.** The injected parameter was still mentioned eight times inside
`HistoryContent`: it was handed to `HistoryCard` as a named argument. A rule that
asks "is it mentioned" answers yes for the good and the bad case alike. So does
"is it inside an argument list" — `onRenameCancelled` has zero uses outside
argument ranges and is genuinely invoked, because its invocation sits inside a
lambda that is itself an argument.

What the rule has to ask is whether the value **arrives at something that calls
it**, and that is a graph question with two directions:

* **downward** — the body calls the parameter, or hands it to a declaration that
  calls it;
* **upward** — the value the declaration is handed at each of its call sites is
  itself live, or the declaration is a screen root (whose own callers are the
  navigation graph, outside the files the check reads).

A defect is a broken link anywhere in that chain, and the archive case breaks the
topmost one: every declaration below the screen is intact, and the value arriving
from the screen is `{}`.

Seven things had to be measured before it worked, and every one of them was a case
of the check looking correct while answering the wrong question:

* **A declaration's "body" was the rest of the file.** `Substring($close + 1)` ran
  from the end of the parameter list to the end of the file, so `HistoryContent`'s
  body contained `HistoryCard`'s parameter list and the previews' `onToggleArchive
  = {},`. Fixed with a brace-depth scan (`Get-BodyEnd`).
* **A match's text never contains what follows it.** `$m.Value` is only the
  identifier — the regex ends at the last letter — so `$m.Value -match 'name\s*='`
  is false for every mention, including the ones that plainly are hand-offs. The
  test has to read the source at `$m.Index`.
* **PowerShell does not permit a trailing comma** after the last element of an
  array. (`@(1,2,)` fails to parse; `@(1,2)` is fine.)
* **Foreground writes are part of a function's return value.** A `Write-Output`
  left inside the predicate turned `if (Test-Invoked ...)` into a test of a
  non-empty array — always true — and discarded every finding for a whole run
  while the check still reported `CLEAN`.
* **`-and` chains clobber `$Matches`.** Each `-match` in `a -match x -and $Matches[1]
  -match y` overwrites it, so the second test reads a group from the first regex's
  subject.
* **A provisional `false` cached before recursion finishes becomes permanent.** The
  cycle guard has to hold "on the current path" and "settled" separately.
* **A hand-off is not a call.** `onToggleArchive = {}` and `onToggleArchive = {
  onToggleArchive(id) }` are both `name =` heads, so a forwarding rule that accepts
  the head alone makes the two indistinguishable and the chain never breaks.

It is verified the only way that counts: four separate injections of the real
defect into `HistoryScreen.kt`, each one reporting, and a clean tree reporting
nothing.

| injection | reported |
| --- | --- |
| `onToggleArchive = { onToggleArchive(row.conversation.id) },` → `{},` at both forward sites | `HistoryScreen.kt:189 HistoryContent - onToggleArchive`, `:481 HistoryCard - onToggleArchive` |
| `TextButton(onClick = onToggleArchive) {` → `TextButton(onClick = {}) {` | both of the above |
| `onToggleArchive = viewModel::toggleArchive,` (the screen's own argument) → `{},` | `HistoryScreen.kt:189 HistoryContent - onToggleArchive` |
| the card's invocation replaced by an unrelated assignment | `HistoryScreen.kt:481 HistoryCard - onExport` |

Scope on a clean tree: 141 screen callbacks across 67 composables in the graph.

**The lesson that generalises**: the first four attempts at this gate reported
*clean* on a defective tree, and three of those four were verified to work by a
negative test that was itself broken in the same way as the gate. A negative test
is only evidence when you have watched it fail, and watched it fail for the right
reason.

## Closed: a gate that reported a failure with no verdict in it

This one was found by running the suite, not by reading it, and it is the same
family as everything above: **`check-embedded-notices.ps1` failed without ever
deciding anything.**

Near the end it printed "Unresolved findings above still block a release build"
and the condition behind that sentence was
`if ($known.status -contains 'unresolved')`. `$known` is an array of findings —
each one an object with `Artifact`, `Notice`, `Term` and `Hits`. Neither the array
nor a finding has a `status`. Under `Set-StrictMode -Version Latest` that is not
`$null`, it is a `PropertyNotFoundException`, so the script died with a PowerShell
error and exit 1.

The consequence is precise and worth stating in full:

* The gate has **never** decided whether to block a release. Every run ended in an
  exception at the same line.
* The exit code was 1 and the last line on screen was about unresolved notices, so
  the output looked exactly like a considered refusal.
* `.scope` was read off findings the same way, and was wrong for the same reason.
* A fourth thing was wrong on a neighbouring line: `Join-Path $HOME
  '.gradle/caches/modules-2/files-2.1'`. `$HOME` is a read-only automatic variable
  in PowerShell; assigning to it throws. It was being read, not assigned, so this
  one was latent — but it is the same mistake that cost a real rewrite in
  `check-embedded-notices.ps1`'s copy of the same idea.

Fixed by projecting the pairing the gate actually means — each finding together
with its matching acknowledgement, carrying `status` and `scope` from the
acknowledgement — and branching on
`$blocking = @($known.Where({ $_.status -eq 'unresolved' }))`. It now prints
`UNRESOLVED - the notices above still block a release build.` followed by
`Development and testing are unaffected; publishing is.` and exits 1, which is the
verdict the file's own prose had been claiming all along.

`compliance/embedded-notices.json` holds three acknowledgements for
`com.google.ai.edge.litertlm:litertlm-android:0.18.0`: GPL-2.0 unresolved, MPL-2.0
accepted, EPL-1.0 unresolved. The two unresolved ones are what block publishing.
That is the intended state, and now it is the gate's *finding* rather than the
gate's *crash*.

## Closed: three gates asked Gradle a question that could never be answered

The suite was hanging. Not failing — hanging, for twenty minutes and then for
five, with no output, and `^C` as the only way out.

The cause is outside this repository. The Gradle daemon on this machine
intermittently wedges: `~/.gradle/daemon/9.1.0/daemon-<pid>.out.log` is created at
**0 bytes** and never written to, the process accumulates roughly twelve seconds of
CPU over ten minutes, it holds the registry, and it never answers anything again.
It happened four times in one day.

What made that a defect in *this* repository is how the gates asked:

```powershell
$graph = & $gradlew -q ':app:dependencies' --configuration releaseRuntimeClasspath
```

Three gates did this — `check-licenses.ps1`, `check-privacy.ps1`,
`check-embedded-notices.ps1` — and **none of them passed a timeout.** Against a
wedged daemon the call blocks forever, so the gate blocks forever, and a pipeline
containing it blocks forever. From outside, a hung gate and a slow gate look
identical, which is the same blindness as everything else in this file: something
that exists, appears to be working, and cannot report.

The machine-level repair, which is not part of the repository:

1. `Get-Process java | Stop-Process -Force`
2. delete `registry.bin` and `registry.bin.lock` from
   `~/.gradle/daemon/9.1.0/`
3. warm it with `gradlew.bat -q ':app:dependencies' --configuration
   releaseRuntimeClasspath` — about six seconds when healthy

**A zero-byte `daemon-<pid>.out.log` is not by itself the symptom.** A healthy
warm-up leaves one too. The reliable symptom is CPU accumulating with no answer.

The repository-level fix is `scripts/gradle-query.ps1`, sourced by the three gates,
exposing `Invoke-GradleQuery -Root -Configuration [-TimeoutSeconds]`: two attempts
at 90 seconds, and between them a daemon restart and registry clear. It throws a
sentence that names the machine state instead of returning an empty graph. Three
attempts at that helper failed before it worked, and each failure is the same
lesson in a different costume:

* `Start-Process -PassThru` piped straight into a loop **hides the wait**. The
  loop body only runs once the process object arrives, so the script had already
  waited for the thing the timeout existed to bound. The timeout has to be armed
  before the child is allowed to run.
* `-RedirectStandardOutput` and `-RedirectStandardError` may not name the same
  file, and a child that inherits the console is not reliably killed. Two temp
  files, or the timeout tears nothing down.
* Reading `ExitCode` after the kill throws, and the exception **escapes the
  function and ends the calling gate** — a timeout turning into an unrelated crash
  in whoever asked. It is read in a `try`/`catch`.

Timeouts on the three gates after the fix, run twice end to end:
`check-licenses` 4 s, `check-privacy` 4 s, `check-embedded-notices` 5 s, whole
suite **35 s and 34 s** — against a gate that had just spent twenty minutes
producing no output at all.

`gradlew --stop` against a wedged daemon **hangs as well**, so the stop has its own
bound (15 s) and the direct kill is the fallback rather than a courtesy.

## Evaluated and not adopted: text that no screen can reach

The second proposed rule was **"a feature whose strings are all unreferenced is a
feature with no way in."** It was implemented far enough to measure, and then
dropped, and the measurement is the reason worth keeping.

The first half of it is already covered: a string nothing names at all is reported
by `check-unused-strings.ps1`. What this rule adds is the case where the strings are
named — but only from code no screen can reach. That needs a reachability walk over
more than composables, so the walk `check-callback-reachability.ps1` already
maintains was pointed at string references.

**Result on a clean tree: 41 findings, all of them wrong.**

Every one of the 41 came from a declaration the walk cannot see into:

| string | where it is actually named |
| --- | --- |
| `err_history_archive` | `HistoryViewModel.kt` |
| `err_erase_failed` | `AboutViewModel.kt` |
| `err_generation_failed`, `chat_speak_unavailable` | `ChatViewModel.kt` |
| `err_no_gpu_fell_back` | `inference/LiteRtLlmEngine.kt` |
| `err_download_failed` | `model/ModelDownloadCoordinator.kt` |
| `import_name_taken` | `ModelManagerViewModel.kt` |

and the seeded model names (`model_qwen3_0_6b_int4`, `model_minicpm5_2b_int4`, …)
come from `model/ModelCatalogue.kt`. Every one of those is live code a person reads
on screen. The walk follows composable calls, and a ViewModel method, an inference
engine and a catalogue are none of those, so the graph reports them as unreachable
while the app is displaying their text.

**A rule that reports 41 live strings out of 374 is not a stricter check, it is a
broken one** — the same conclusion the copy gate reached about its own wider
variant, 26 false positives from judging every colon.

What text reachability would actually require, recorded so the next attempt starts
from the measurement rather than from the proposal:

1. class and object declarations, not only `fun`;
2. an edge from a constructor call, and from the `viewModel()`-style factory that
   composables use to obtain one;
3. method calls on a *typed receiver*, which means tracking the type of a local
   variable — inference, not pattern matching;
4. the framework entry points — `onClick` lambdas, `LaunchedEffect` bodies,
   collectors — treated as roots in their own right.

That is a whole-program type resolver, and the finding it would catch is one this
repository already has two mechanical rules for. The callback walk was built instead
because it needs none of items 1 through 4: a callback parameter is named in the
signature it is declared in, so the graph never has to work out what a variable is.

The honest statement of the gap, then, is not that the rule is unwritten. It is
that **the rule as proposed cannot be written at acceptable precision in this
codebase**, and the case that motivated it — three string resources with zero
references — is caught by the rule that already exists.

## Closed: a parameter whose default makes the feature quietly do nothing

`benchmarkPrompt: String = ""` was one instance of a shape the gate did not cover:
**a parameter whose default makes the feature silently do nothing.** The rule first
written here as a proposal is now rule 3 in `check-ui-wiring.ps1`:

> A parameter that has an empty or zero default (`= ""`, `= 0`, `= emptyList()`)
> must still be passed explicitly at every call site.

Implemented as written, with one narrowing that the proposal did not have and that
the trial run justified: **`= null` on a nullable parameter is not in the rule.**
"No value" is a state these screens mean — `sharedText: String? = null` is "nothing
was shared into the app", which is the normal case — and a first run or a Preview
is entitled to be in it. Empty *non-null* values are the ones with no meaning to
express, and therefore the ones that are always a wiring mistake when absent. The
wider version reported three parameters; the narrower one reports one, and the one
is the real one.

**Six defects of this shape, all found by reading rather than by a gate**, which is
why the rule was worth building:

| What was empty | What it hid |
| --- | --- |
| `benchmarkPrompt = ""` | the benchmark asked every model an empty prompt |
| `ContextBudget.reservedTokens` | a reserve nothing ever set, so the budget was the window |
| `FileCipher.onUnreadable` | conversations that could not be decrypted vanished, silently |
| `HistoryRow.bytes` | the row never showed how much space a conversation takes |
| `AboutUiState.wiping` / `.wipedBytes` | the erase screen never showed progress or result |
| `ModelDownloader.PROGRESS_INTERVAL_MS` | not this shape, but found in the same pass |

Verified the way the other gate changes were: the rule passes on the current tree,
and deleting `benchmarkPrompt = stringResource(...)` from the call site reproduces
the original defect and fails the gate with a message naming the parameter and the
consequence.

No exemptions were needed, so the `$exempt` map is still empty. That is the outcome
the proposal was unsure about — it expected "a few" to be justified, and the
narrowing to non-null empties is what avoided them.

## Evaluated and not adopted: `com.adamglin.compose.markdown`

Found while investigating a quadratic parse in `MarkdownText.parseInline`, and it
is a close fit on paper: a Kotlin Multiplatform Markdown parser and Compose
renderer **built specifically for append-only streaming output**, with presets
named `ChatFast`, `CommonMarkCore` and `GfmCompat`. MIT, so the licence gate would
accept it without a new allow-list entry.

Not adopted, for three reasons that each stand alone:

1. **It is version 0.1.5 with 61 commits**, and its own README says "This library
   is under development". A parser is not where a pre-1.0 API belongs.
2. **Its stated out-of-scope list includes "full CommonMark parity"** — and
   `ReplySegmentsTest` is, in effect, a list of CommonMark boundary decisions this
   app made on purpose: `2*3*4` is not emphasis, `这是*重点*内容` is, `**kwargs`
   inside backticks is literal. Adopting the library means re-establishing all of
   those against a different implementation, which is not "deleting owned code and
   tests" — it is moving them.
3. The rule this project wrote for itself is to prefer a dependency **when it
   genuinely deletes owned code and tests**. Here it would delete 836 lines of
   implementation and 546 lines of test, and then require writing a comparable
   test suite against unknown behaviour. The trade is only favourable once the
   library is stable enough that its own tests can be trusted for the cases above.

The thing that made it worth looking at is real, though: **streaming is the case
this app is in, and the hand-written renderer was quadratic in it** (2.2 s for one
42,000-character line, on the frame thread). That is fixed — see
`MarkdownText.nextMarker`. If the parser needs to grow much past what it does now,
this is the first place to look, and the licence question is already answered.

---

## A class found while auditing: support that was built and never connected

Five separate things in this project were **designed to tell the user something
and never got wired to anything.** They are listed together because the shape is
the same, and because it is the shape worth looking for:

| What | State |
| --- | --- |
| `Speech.ready` | Deleted. Declared, assigned once, read never. |
| `EngineState.Failed` | Replaced. The state was published four times and read nowhere; the sentence it carried now travels on the failure, so the classification survives. |
| `ContextBudget.reservedTokens` | **Removed.** The real reserve is the caller's `window / 2`; the function was documented as load-bearing and exercised by thirteen tests, and no production caller ever gave it a value. The tests now express the same cases through `budgetTokens`, which is what a reserve always was — arithmetic the caller can do. Kept-and-explained was the earlier call, and it was the wrong way round: what went away with the parameter was a second way to say one thing. |
| `Calculator.Refusal` | **Removed.** Its own doc said "a code is also the thing a caller can branch on"; no caller branched. Every refusal reaches the model as one sentence, which is the right behaviour — the codes described a decision nobody made. |
| `FileCipher.onUnreadable` | **Fixed.** `ChatStore` reads without it, so a conversation whose key is gone disappeared from the list with no message — the exact outcome the parameter's own documentation says it exists to prevent. The count now reaches the history screen. |
| `CrashLog.clear` | **Answered, not wired.** Its doc said "called once the crash has been included in a report" and nothing called it, so the last crash is re-sent with every report until the next one overwrites it. The question that left open — should a report consume what it carried — is now answered **no**, because sharing is something the user asked for and an answer to it should not destroy data: a user asked twice would have to reproduce an intermittent crash to send the second one. Nothing is ambiguous without it either, since the report labels the section "最近一次崩溃" and the trace carries its own timestamp. The reason is recorded at the function. |
| `ConversationBackup.ReadResult.Ok.fromNewerVersion` | **Replaced.** Declared, always constructed as `null`, read nowhere — and it looked like it was doing a job, because `decode` answers `TooNew` for a newer file, so the field was dead on arrival and a reader could reasonably have built a warning on it. It is now `emptyDropped: Int`, a count the screen actually displays, and the thing it replaced had been standing in for a real gap: a conversation with no messages is filtered out while the **file** is read, so it never reaches `ChatStore.restore` and that layer's skipped/failed reporting. The restore brought back fewer conversations than the file held, with nothing on screen to explain the difference — against the filter's own comment, "as long as what was dropped is reported". |

The generalisation, which is what to carry forward:

> **A parameter, callback or enum that exists in order to report something, and is
> never given a value or read in production code, is not a feature — it is a
> capability the code was built to have and does not.**

None of them was caught by a gate. Every one compiles, is exercised by tests, and
reads as finished. The check that would find them is not "is it used"
but "**is it ever used for its stated purpose**", which is harder to write and was
not attempted here.

## And one more, found the same way

`AboutViewModel.confirmErase` — the button the About screen describes as removing
everything — covered what the stores own: conversations, models, settings, the
imported-model registry. It did not cover **`diagnostics/`** (the last crash, a
stack trace with this device's paths and package name in it) or
**`inference-cache/`** (the runtime's scratch directory). Both were written by the
app and deleted by nobody, while `erase_done` says "已经全部从这台设备上清除".

Fixed: the ViewModel now takes the app's files directory and deletes both, and
`erasing removes the files no store owns` fails if either comes back.

Worth noting how it was found, because it is not how the others were: by listing
every path the app writes under `filesDir` and checking each one against the
erase. That is a mechanical check, and it is the closest thing to a rule that
would have caught the whole group — **the rows a screen claims to have cleared are
the rows something must be seen to delete.**

`FileCipher.onUnreadable` is the one of the five that should be fixed rather than
documented, and it needs a sentence and a place to put it — a banner on the
history screen, in the app's own words, saying that some conversations could not
be read and why. That is a decision about the screen, not about the code.

**Done** in the same change as this paragraph: the count is carried out of the
store, the history screen shows it above the error line, and the notice says the
files are still there and were not deleted.

---

## Closed: the release build had never been run, and could not be

The debug variant was built many times. `assembleRelease` was built for the first time
in this session, and the first thing found was that it worked only by accident.

`app/build.gradle.kts` named a file that did not exist:

```kotlin
release {
    isMinifyEnabled = false
    proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
}
```

`app/proguard-rules.pro` was absent -- it is the only reference to that name anywhere
in the repository. That was invisible while minification was off, because R8 never ran
and never read the file. Setting `isMinifyEnabled = true` failed immediately:

```
Execution failed for task ':app:minifyReleaseWithR8'.
> Supplied proguard configuration does not exist: D:\...\app\proguard-rules.pro
```

So the first thing this needed was the file. **A first reading of the finding was
wrong and is worth recording**: the guess was "a missing rules file is harmless, R8
just applies none" -- which would have made the honest report "keep the file absent,
because a file of shrinking directives does nothing when shrinking is off". Gradle
validates the path and fails loudly, so that reading was refuted by one command, and
the actual state was the opposite: turning on minification was impossible.

### What the rules file had to cover

Written against the app's real reflection surfaces rather than copied from a template.
Four, in order of how quietly each fails:

1. **`LoadFailure.of` classifies by exception class name.** It builds a string from
   `Throwable::class.java.simpleName` and matches `"outofmemory"`, `"no space"`,
   `"enospc"` against it to choose between telling the user their device is out of
   memory and telling them the file is broken. Renamed, `OutOfMemoryError` arrives as
   something like `a.b`, nothing matches, and every failure falls through to
   `Kind.UNKNOWN` -- which tells the user to retry and send the details instead of the
   one thing they could have acted on. This looks like message formatting and is a
   decision. `CrashLog` writes the same name into the report the user is asked to send.
2. **Tool calling.** `LocalTools` is registered through `tool(activeTools)`; the
   runtime finds callable methods by reflecting over `@Tool` annotations. R8 cannot see
   that, because the reader is in the library's own `ReflectionTool`. And
   `litertlm-android-0.18.0.aar` ships no consumer rules -- its contents are the
   manifest, `classes.jar`, `res`, `R.txt`, the two `.so` files, `LICENSE` and
   `THIRD_PARTY_NOTICE.txt`, and no `proguard.txt`.
3. **Serialization.** Twelve `@Serializable` types, including everything the app keeps
   on disk. `kotlinx-serialization-json` ships its own consumer rules; repeated rather
   than relied on.
4. **`StorageManager::class.java`**, passed to `getSystemService` and to the
   allocatable-bytes API.

### What was measured, and what could not be

Each candidate rule was tried alone and measured against the minified dex by counting
references to the annotation type:

| rules | `litertlm/Tool` in the dex |
| --- | --- |
| none (fully obfuscated) | 0 |
| `-keepattributes RuntimeVisibleAnnotations` alone | 0 |
| `-keep class com.google.ai.edge.litertlm.Tool` | 2 |
| both of the above | 2 |

Two, either way, is the annotation *class definition*. The unminified build returns
**102** hits for the same search, and in the minified one every hit that is not the
class definition sits inside the library's `ReflectionTool` -- the code that looks for
the annotation, not a method carrying it:

```
1603c4: instance-of v6, v6, Lcom/google/ai/edge/litertlm/Tool;
```

**So the tool-calling question is open.** Keeping the annotation class is not enough to
keep the annotation on a method, and none of the combinations tried was shown to do
it. `-keepattributes` alone does nothing here, which is worth knowing by itself: it
only applies to classes that some rule also keeps.

Byte inspection can show whether an annotation is in the dex. It cannot show whether
the runtime finds it -- the annotation is read only when the model calls a tool. That
needs a device: load a model with `LocalTools` registered, ask something that needs the
clock or a calculation, and see whether the tool runs.

### The decision

**`isMinifyEnabled` stays false, now with a reason instead of by default.**

R8 would save about 7.6 MB of a 57 MB APK whose other 48 MB is two `.so` files -- R8
takes it from 57.34 MB / 3 dex / 29 MB of dex down to 49.75 MB / 1 dex / 4.4 MB. In
exchange it introduces a failure mode that is silent, reachable only on a device, and
breaks a feature no unit test can cover, because there is no engine to reflect over
without one. That is not a trade worth taking blind, and the rules file records what
would have to be confirmed first.

What changed for the better regardless: `assembleRelease` has been run, it works, and
the rules file exists so that minification is a decision rather than an error.

## Closed: the CI staleness check named one file and the command writes two

`compliance.yml` ended with a guard that regenerates the attribution and fails if the
file changed:

```powershell
$before = Get-FileHash THIRD_PARTY_LICENSES.md -Algorithm SHA256
./scripts/check-licenses.ps1 -Update | Out-Null
$after  = Get-FileHash THIRD_PARTY_LICENSES.md -Algorithm SHA256
```

`-Update` writes **two** files. The other is `compliance/licenses.lock.json`, the
resolved dependency set with its licences. A change that alters the lock without
altering the rendered notice -- a version bump that keeps the same licence, an artifact
the notice does not list -- would have passed, and the step would have looked like it
covered the case it names.

There is a second thing worth knowing about this guard, which is why it is written the
way it is: `check-licenses.ps1` **without** `-Update` verifies the lock against the
resolved classpath and writes nothing, so running the gate twice in a row always
passes. Comparing files before and after `-Update` is the only version that can fail,
and it has to name every file the command writes.

Both files are now tracked, and it was checked the way this document keeps insisting:
the lock was edited to be stale, and the guard reported it.

---

## Closed: four flows written to, never combined, so the screen never saw them

Found by `scripts/mutation-sweep.ps1`, which is worth saying plainly: the sweep had
never been run in this repository. It exists, it is documented, and the two survivors
from its first run were pointing at real holes.

`ModelManagerViewModel.state` is a `combine` of five flows. `buildState` read four
more values off the instance:

```
verifying = verifying.value,               // MutableStateFlow
verification = verification.value,         // MutableStateFlow
benchmarking = benchmarking.value,         // MutableStateFlow
benchmark = benchmark.value,               // MutableStateFlow
```

None of the four was in the `combine`. Writing to one changed nothing that the screen
observes -- the value reached the state only when something *else* happened to
recompute it. This is the project's most-repeated defect shape, one layer down from
where it usually appears: not "a callback nobody calls" but "a flow nobody combines".

What each one looked like in use:

- **`dismissVerification` and `dismissBenchmark` write a flow and nothing else**, so
  the card they are meant to take off the screen stayed on it. There is no way for
  the user to close a verification or benchmark result.
- **A benchmark's result appeared only by accident.** Generating a reply moves the
  engine's state, which recomputes the state, which picks up `benchmark.value`. A
  benchmark against an already-loaded model that produced no state change would have
  run to completion and shown nothing at all.
- **`benchmarking` never reached the screen**, so the running indicator was dead. A
  user could start a benchmark and see no sign of it.

The fix is `Tooling`: the four flows get their own typed `combine`, and the state's
`combine` folds that in as its sixth input. Six is past `combine`'s typed arity -- it
is overloaded for one through five, and past five the lambda receives an
`Array<Any?>` -- so the five existing inputs were grouped into `Inputs` first and the
two combined below. Every lambda in the chain is typed; no casts, no index
arithmetic.

`buildState` now takes `Inputs` and `Tooling` and reads nothing off the instance.
That is the substance of the fix rather than a refactor: a value read off the instance
inside `buildState` is not part of the decision about when to recompute, so it cannot
be trusted to be current.

**Seven tests cover it**, and they were checked the way this document keeps saying
things should be: the `Tooling` line was mutated back out and six of them failed.
The one that failed first is the one that found the bug -- `a reported figure can be
dismissed` -- with `expected null, but was:<ModelBenchmark(...)>`, which is the card
staying on screen in an assertion.

## Closed: the sweep had five mutations that could not run, and counted them as survivors

`scripts/mutation-sweep.ps1` plants a change that breaks one behaviour and asks
whether any test notices. Five of its twenty-two anchors no longer existed:

| Mutation | Why the anchor was stale |
| --- | --- |
| `any fence-prefixed line closes a block` | the fence condition was simplified to `isFence && marker.isEmpty()`, the redundant `code != null` dropped |
| `interrupted marker omitted from the export` | the export was localised, so the literal became `labels.stoppedHere` |
| `the creativity setting never reaches the engine` | `load` now takes a `GenerationConfig`, so the anchor is `settings.samplingFor(descriptor.id)` |
| `a stored settings value is no longer brought into range` | the read moved behind `FileCipher.read(file)?.decodeToString()` |
| `a save stops deferring to the title on disk` | the local is `withPin`, not `conversation` |

The script did not skip them. It did this:

```powershell
if (-not $original.Contains($m.from)) {
    Write-Host "  PLANT FAILED  $($m.name)"
    $survived += "$($m.name)  (anchor not found)"    # counted as a survivor
    continue
}
```

So a mutation that never ran was reported in the same list as one that ran and was
missed -- and since the sweep takes half an hour and nobody diffs its output against
the previous run, five dead checks went unnoticed. An anchor is a claim about where a
behaviour lives; when the code moves, the claim is wrong, and a mutation that cannot be
planted tests nothing.

All five were re-anchored against the current source, ten more were added for the
fixes from this session, and the script now validates every anchor **before** it
starts and refuses to run if any is stale. That is the same rule the sweep exists to
apply, one level up: a check that has never been observed to fail is indistinguishable
from a disabled one, and five of these had been disabled by the drift.

The first run of the repaired sweep: **28 caught, 2 survived** out of 30 -- and both
survivors led somewhere. One was the `Tooling` defect above. The other was the
benchmark prompt, which had no test at all because `FakeEngine.generate` returned
`emptyFlow()`: a valid engine and a useless one, since a benchmark against it collects
nothing. Both are now covered.

## Closed: an empty-fence guard, dropped while rewriting the parser around it

`splitReply` had this, with its own comment:

```kotlin
// An empty fence is two markers and nothing between them. A
// grey panel with no text is worse than the backticks.
if (contents.isNotEmpty()) segments += ReplySegment.Code(contents, language)
```

The guard was gone when the mutation sweep refused to start on a red baseline, and
`ReplySegmentsTest` failed on three consecutive runs:

```
AssertionError: No empty code box: [Prose(text=before), Code(text=, language=null), Prose(text=after)]
```

The comment had survived the guard it explained. That is the exact arrangement the
sweep's own header warns about -- "the comment next to the code claiming it does is
the only thing holding it up" -- except here the comment was holding up an absence.
The guard is restored.

The reason it took a mutation sweep to find: every run of the full suite in this
session reported `0 failures` except when the intermittent one appeared, and this was
a *stable* failure that those runs did not report. A sweep cannot be trusted against a
red baseline, so it was the sweep that noticed the baseline was red rather than the
suite being read carefully enough.

---

## Closed: the suite failed intermittently, and the failure moved

Measured on 2026-10-13, Windows, JDK 25, `--rerun-tasks` throughout:

| Run | Result |
| --- | --- |
| 6 consecutive full runs, one Gradle at a time | `a version change is written to disk` failed every time |
| The same, while another Gradle ran concurrently | failures spread across `sending records the question before the reply arrives`, `a completed reply is persisted`, `uninstalling the last model disables the composer` |
| 5 runs of one class alone | 1 failure in 5 |
| **The same tests against the pristine tree, no local changes at all** | **fails identically** |

That last row is the one that matters: **this is not caused by a change in the
working tree.** It reproduces on the code exactly as it was staged.

What is known about the mechanism:

- Every failing test waits on `awaitSaved`, which polls `chatStore.list()` in a
  loop with `delay(5)` under a 15-second timeout. It gives up having seen a
  conversation with *stale* content — so the write it was waiting for did not
  land, rather than landing wrongly.
- The failures are load-sensitive and their location moves between runs, which is
  the signature of a race rather than a wrong expectation.
- The production path was read line by line and is correct: `Mutex` serialises
  writes, the temp name is unique per write, `fd.sync()` precedes the rename,
  `ATOMIC_MOVE` has a documented fallback, and the "newest wins" check compares
  against what is actually stored.

Three attempts at a fix were made and all were reverted, which is worth recording
so they are not repeated:

1. `StandardTestDispatcher` as the test's Main — queues work that these tests
   never advance, so seven tests stopped happening at all.
2. A dedicated single-thread executor — a different thread, so a test blocking on
   a coroutine it dispatched deadlocks instead.
3. `Unconfined` set once per class with no reset — removes the
   `Dispatchers.Main is used concurrently` error, which was real, and does not
   touch this one.

## Closed: the intermittent failure was one test class, and two more tries did not fix it

Investigated further on 2026-10-14. Three things were established, one of which
narrows it a lot and two of which close off explanations.

**It is not spread across the suite.** Every failure observed in this session — the
twelve distinct test names, across roughly thirty full runs — is in
`ChatViewModelTest`. Nothing else in 509 tests failed once. A machine-load story
would not be that tidy, and the earlier "load-sensitive, the location moves" reading
was describing which *test inside that class* failed, not which class.

**The teardown drain was not a barrier, and fixing it did not help.** `tearDown`
drained by queueing one empty task on `Dispatchers.IO` and one on `Default` and
waiting for them. On a pooled dispatcher that proves only that *those* tasks
finished, so it was replaced with an exact wait: every ViewModel built by the
`tracked` helper has its `viewModelScope` inspected until no child job is active for
two consecutive polls. That is a real improvement to the teardown and it is kept —
but the failure rate did not change (2 in 5 runs before, 2 in 5 runs after), so the
teardown was not the cause either.

**Polling starvation is ruled out, by experiment rather than by argument.** The next
thing this file said to measure was whether the 5 ms poll loop starves the save it is
waiting for, since `chatStore.list()` is `withContext(Dispatchers.IO)` decrypting
every conversation file. Geometric backoff to a 50 ms ceiling was implemented and
measured over 8 full runs: **8 failures, against 2 in the 5 runs before it** — and
every run slower. If the write were merely slow, polling less often would have helped
it. That is the shape of a refuted hypothesis, so the backoff was reverted.

What that experiment left behind is better than the theory: a **poll counter in the
timeout message**. Failing runs reported 195, 195, 230, 230, 231, 231 polls — the
loop ran its entire fifteen-second budget and the write never landed. So the write is
not late. It does not happen.

### Where it stands now: the instrument is live, and the next failure is a measurement

Two things changed after the section below this one, and both matter to whoever picks
this up:

- **The counters that were supposed to answer the question now move.** They were
  declared, documented, and never incremented, so `attempted=0` was printed on every
  failing run whether or not the store had been entered, and the "diagnosis" that
  `throughLock=3 attempted=0` proved the interruption was at the hop was read off an
  instrument that could not report anything else. Two tests in `FileChatStoreTest` now
  pin the four counters, and a fourth mutation anchor in the sweep covers them. The
  next failure prints `savesAttempted` with the store's own answer in it: **greater than
  zero means the store ran and decided** (a refusal counts as an answer, and a refusal
  is a bug in a save that should have won), **zero means the call never got past
  `withContext(Dispatchers.IO)` and `writeLock`** — cancelled, or never resumed.
- **The failure has now been observed in a plain full-suite run**, not only under a
  rerun or on a loaded machine: the mutation sweep's baseline attempt 1 was red with
  `opening a conversation that is gone leaves the screen alone`
  (`app/src/test/java/com/kayoyak/demoapp/ui/chat/ChatViewModelTest.kt:1101`), a
  thirteenth distinct name, in the same class, with the same shape — `runBlocking` plus
  `vm.await { … }`. Attempt 2 of the same baseline was green. The sweep tolerates this
  on purpose (`$baselineAttempts = 3`, with the reason written at the call); `ci.yml`
  does not, and it runs `assembleDebug testDebugUnitTest lintDebug` on every push and
  pull request, so this flake is the one thing in this repository that can turn the
  pipeline red for a reason nobody can act on. That is the cost of leaving it open — and
  it was found and fixed on 2026-10-15, in the last section of this file.

Also measured, and worth not repeating: **100 consecutive green runs of
`ChatViewModelTest` alone** (40 then 60, `scripts/stress-flake.ps1`), so whatever this
is needs either the whole suite in one process or a machine that is busy with something
else. A batch of the class by itself is not a way to reproduce it.

## Closed: the instrumented answer was read off counters that never moved

The step this file said to take — "log whether `save` is entered at all on a failing
run, not to reason about it further" — was taken. Three counters were added, all on the
production path, all `internal`:

- `FileChatStore.saveAudit()`: `attempted`, `refusedAsDeleted`, `refusedAsOlder`,
  `written`, and `lastMoveRefusal`. **Attempted is incremented after the
  `withContext(Dispatchers.IO)` hop**, which turns out to matter.
- `ChatViewModel.saveNowCalls` and `saveNowThroughLock`: how many times `saveNow` was
  entered, and how many times it got through `saveLock` to the `chatStore.save` call.

A failing run reported all five:

```
save: attempted=0 written=0 refusedAsOlder=0 refusedAsDeleted=0
viewModel: saveNowCalls=3 throughLock=3
```

And the conclusion drawn from that pair — **"`throughLock=3` with `attempted=0` is not a
contradiction, and it is the finding: the call was interrupted crossing into
`Dispatchers.IO`, before the first line of the body"** — was wrong. It was wrong because
the counter it rests on had never moved:

```
$ git grep -nE 'savesAttempted|savesWritten|savesRefused' -- '*.kt'
ChatStore.kt:168:    private var savesAttempted = 0
ChatStore.kt:169:    private var savesRefusedAsDeleted = 0
ChatStore.kt:170:    private var savesRefusedAsOlder = 0
ChatStore.kt:171:    private var savesWritten = 0
ChatStore.kt:174:        SaveAudit(...)
```

Four declarations and one accessor. No increment site, anywhere, for the whole life of
the store. `saveAudit()` returned `SaveAudit(0, 0, 0, 0)` on every call ever made, so
`attempted=0` was certain regardless of what the store did, and it could not distinguish
"never attempted" from "attempted and refused" — which is the exact distinction the
counters were added for. The same is true of the poll counts above (195, 195, 230, 230,
231, 231): they are real, they come from the loop, and they say nothing about the store.

**Three attempts to explain the timeout were made and refuted before anyone checked that
the instrument moved.** That is the finding worth keeping: an instrument whose output is
plausible gets read as a measurement, and a counter that always returns zero produces the
most plausible number of all — zero, which is what a missing write looks like.

The counters are now incremented (`savesAttempted` past the lock, before the guards;
`savesRefusedAsDeleted` and `savesRefusedAsOlder` inside their respective guards;
`savesWritten` after the rename, so it means the file was replaced rather than that a
write was started), their only reader is the failure diagnostic in `ChatViewModelTest`,
and nothing asserted them — which is why they survived. Two tests now do:
`the save audit counts what save decided` and `the save audit tells a tombstone refusal
from a write`. Four mutations in `scripts/mutation-sweep.ps1` break them one at a time,
in keeping with a counter whose failure mode was "silently stopped counting".

What actually causes the timeout is still **open**, and the honest state of the evidence
is narrower than the paragraph above claimed: no reproduction since the counters were
repaired, so no reading exists. 40 consecutive runs of `ChatViewModelTest` and then 60
more produced zero failures — a statement about this machine under this load, not about
the code. The device work is what makes the next reproduction cheaper.

Two corrections from the earlier round, which stand on their own and are unaffected by
the dead counter:

1. **It is not one test class.** A later batch of twelve runs produced
   `an accepted import is copied, recorded and reported`, which is in
   `ModelManagerViewModelTest`. The "every failure is in `ChatViewModelTest`" row was
   true of the runs that had happened, and stated as though it were a property. Two
   test classes, then, and possibly a third — the shape is "a `runBlocking` test that
   waits for IO work", not "one class".
2. **Some earlier rounds of that batch are unusable.** They report
   `initializationError` and failures in tests that pass alone, because a second
   Gradle was running concurrently and `processDebugResources` could not delete
   `R.jar`. Two incomplete runs of the instrumented batch are in that state. The
   numbers above come from the rounds where the build succeeded, which is why there
   are three of them and not twelve.

Also worth knowing for whoever picks this up: the failure is not a flaky assertion in
the test. It is a real disagreement between what the screen shows and what the disk
holds, and the tests are the only thing that ever notices it.

## Closed: nothing had ever been built from what a checkout carries

**When this section was written, this repository had no commits.** The first one, `defc96e`,
was made at 00:00 on 2026-10-15. `git log` answered `your
current branch 'main' does not have any commits yet`, and that had been true for the whole
life of the project: work accumulated as staged and unstaged edits, and `HEAD` did not exist
as an object. Everything else in this file had been verified against **the working tree**,
which is a different tree from the one any second copy would receive — and that is the gap
this section closes.

The repository has one commit now, `defc96e`: the first, 167 files, made after this section
was written. The question has not changed, and a commit does not answer it by itself — what a
second copy receives is still the question, so the check below is still the check: materialise
what a checkout would carry and run the pipeline against that rather than against the working
tree.

The gap that opens is narrow and easy to miss. `git status --short` calls a file `AD`
when its addition is staged and the file is then deleted on disk — and that is also
what it calls a file whose deletion is staged. Reading it as "the deletion is handled"
leaves the file in the index, so it survives into a commit. The distinction only
becomes visible when something asks the index a direct question:

```
$ git ls-files -- app/src/main/res/xml/backup_rules.xml      # in the index or not?
```

`git diff --cached --name-status --diff-filter=D` is the other way, and it answered
zero rows while thirteen paths were in that state: the twelve launcher icon files that
the move from `mipmap-anydpi-v26/` to `mipmap-anydpi/` and the lint pass had left
behind, and `backup_rules.xml`. All thirteen were things the working tree had already
stopped using, which is exactly what made them easy to miss — a deletion that nothing
depends on produces no failure to notice.

A trickier version, because the two halves look like one rename: the launcher icons
moved from `mipmap-anydpi-v26/` to `mipmap-anydpi/` when `minSdk` made the `-v26`
qualifier redundant, and the legacy `mipmap-*dpi/` PNGs were dropped for the same
reason. On disk that is four directories gone and two files gained, and it reads as a
move. In the index it is not a move at all — `git` records no renames, so a path that
loses its last file is a deletion and a new path is an addition, and if only the
additions are staged the deletions are simply still there. `backup_rules.xml` was the
same shape from the other direction: the manifest line that named it was replaced with
`android:fullBackupContent="false"`, and the file it used to name stayed behind.

**The check that closes this is to stop asking git and start asking the tree.** The
index is a complete record of every file a checkout would place, so materialise it
somewhere else and run the whole pipeline against that:

```sh
git checkout-index -a -f --prefix=/tmp/checkout-check/
cd /tmp/checkout-check
```

163 files. No `build/`, no `.gradle/`, no `.kotlin/`, no git history — exactly what a
fresh CI runner gets. Against that tree:

| Step | Result |
| --- | --- |
| all 8 gates | 7 CLEAN, `check-embedded-notices` 1 as intended, 41 s |
| `:app:assembleRelease` | exit 0, 45 s, `app-release-unsigned.apk` |
| APK bytes | 60,129,127 — the same count as the working tree's build |
| `:app:testDebugUnitTest` | exit 0, 519 tests, 0 failures |

The byte-identical APK is the part worth keeping. It says the index does not merely
carry *a* buildable set of files; it carries the same input the working tree does, so
the working tree is hiding nothing from it.

Two things follow from this that are easy to state and were not obvious before:

1. **Every earlier "verified" claim in this file is a claim about the working tree.**
   They are all still true of the working tree. None of them were evidence about what
   another copy of this repository would do, because no other copy existed to ask.
2. **The byte count of the release APK is a usable checksum here.** Not because the
   build is reproducible in general — two different trees could agree on a size by
   coincidence — but because it is the cheapest total signal that the input set did not
   change, and 60,129,127 has now been observed on the working tree (twice, once after
   `--rerun-tasks`) and on a cold checkout.

The build's own warning at this step is worth recording too, because it points at the
next real gap and not at a defect: the release APK is **unsigned**, which is why it is
named `app-release-unsigned.apk`. That is deliberate and documented in
`README.md` — signing is supplied per-build with
`-Pandroid.injected.signing.store.file=...` rather than a committed keystore — so
`assembleRelease` succeeding is evidence that the app compiles and packages, not that
it is publishable. `compliance/embedded-notices.json`'s two unresolved litertlm
licences are the other half of that, and they are the gate's finding rather than the
gate's crash only as of the section above.

### Re-measured after everything else in this document was fixed

The index was materialised again once the callback gate, the notices gate, the Gradle
timeout, the model pins, the instrumented tests, the sweep and the report tests had all
landed, because that check is only as good as the tree it is run against and the tree
had moved a long way. Same procedure, fresh empty directory, **167 files** materialised,
no `build/`, no `.gradle/`, no `.kotlin/`, no history. Against it:

- `gradlew :app:testDebugUnitTest :app:assembleRelease` → exit 0 in 61 s (cold, both
  tasks), **531 tests, 0 failures, 36 classes**.
- The release APK came out at **60,129,127 bytes — the same count as the working
  tree's**, which is the part of this that says the index carries the same input and
  not merely a buildable one.
- The eight gates were run against the working tree at the end of the same session:
  34 s, seven `CLEAN` lines and `check-embedded-notices` exit 1 as intended. On the
  first, smaller checkout they took 41 s rather than 34 s, because a checkout has no
  build directory and the three Gradle-backed gates have to resolve from scratch. They
  were not run against this second copy; what this copy was asked was the question the
  first one could not answer after the tree moved — does it build and pass.

So the answer to "does what a checkout carries build and pass" is now a measurement
taken twice, on two different sizes of the same tree.

## Closed: the model download was dead, and the digest was the reason

The catalogue pins a SHA-256 per model, and `ModelDownloader.verify` refuses anything
else — correctly. On **2026-10-13T15:53:23Z** the publisher of
`litert-community/Qwen3-0.6B` replaced both files this app pins, under their existing
names. Every model in the ladder was pinned against bytes that a provider could change
whenever it liked, and nothing in the project could tell.

Both entries were affected, and both are on the app's first screen:

| Entry | Pinned | Published on 2026-10-14 | What a user would see |
| --- | --- | --- | --- |
| `qwen3-0.6b-int4` (first rung, 344 MB) | `03e7da1eb1108b50dffaa9b54cc7bcbad2eb0c66ca990267f480c1e545d2856` | `03e7da1eb1108b50dffaa9bb52cc7bcbad2eb0c66ca990267f480c1e545d2856` | same length, so a full download before the refusal |
| `qwen3-0.6b` (614 MB) | `346f9b2e…` (614,230,168 B) | `c5f39b41…` (613,722,264 B) | length differs, so the refusal is immediate |

The `int4` case is the one that matters. It is the first entry
(`ModelCatalogue.kt:190`, "a phone is not a server, and the first entry is what someone
who has not chosen yet ends up with"), the file kept its name *and* its length, and
`ModelDownloader.verify` checks length before digest. So the user transferred all
344,671,744 bytes, was told
`Checksum mismatch for Qwen3 0.6B 量化版. The file was deleted; download it again.`,
and would have been told the same thing on every retry, because the digest in the
catalogue pointed at bytes that no longer existed anywhere.

The evidence for the replacement is the provider's own LFS pointer, which publishes the
digest of the content — the check reads it rather than downloading gigabytes:

```
$ curl -s https://hf-mirror.com/litert-community/Qwen3-0.6B/raw/main/Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm
version https://git-lfs.github.com/spec/v1
oid sha256:03e7da1eb1108b50dffaa9bb52cc7bcbad2eb0c66ca990267f480c1e545d2856
size 344671744
```

`hf-mirror.com` and `huggingface.co` publish the same digest and the same ETag for both
files, so this is the publisher and not the mirror. The download was also confirmed
byte-for-byte against the live server over three 2 MB ranges (head, middle, tail:
2,097,152 bytes each, zero differing bytes), which rules out a corrupted transfer as the
reason the local digest disagreed with the pin.

Both entries are re-pinned to what the provider is serving now, each with the reason
next to it. The lesson is not "the digest was wrong" — it was right, and so was the
check that refused the file. The lesson is that a digest is a claim about bytes that
live somewhere else, and the project had no way to test that claim except by asking a
user to spend 344 MB.

`scripts/check-model-pins.ps1` is that way. It reads every `ModelDescriptor` out of the
catalogue, asks each provider for its pointer, and compares. Seven pointers, about seven
seconds, no artifacts downloaded:

```
  qwen3-0.6b-int4            ok
  qwen3-0.6b                 ok
  minicpm5-1b                ok
  qwen35-0.8b                ok
  minicpm5-2b-int4           ok
  deepseek-r1-qwen-1.5b      ok
  qwen3-1.7b                 ok

CLEAN - all 7 reachable model pins match what their provider publishes (7 in the catalogue).
```

It is deliberately not in `compliance.yml`. It is the one check here that needs the
network, and those gates have to keep working on a machine that has none — so it lives
in `.github/workflows/model-pins.yml`, which runs on changes to the catalogue or the
check, on `workflow_dispatch`, and **on a schedule**. The schedule is the part that
matches the defect: these pins rot on the publisher's clock, not on this repository's,
so a tree nobody has touched can be broken by somebody else's upload. It also exits 0
when no provider answers, because "the provider was down" is not a finding about this
project — but it prints how many it could not reach, so a silent total failure is still
visible in the log.

## Closed: the mutation sweep printed its verdict and exited 0

`scripts/mutation-sweep.ps1` breaks one behaviour at a time in the product source and
asks whether any test notices. It ends with a survivor list — mutations no test caught,
which is exactly the class of defect this file is mostly about — and it ended with:

```powershell
Get-Baseline | Out-Null
Write-Host ''
Write-Host 'Source restored.'
```

No `exit`. So it exited 0 on every run of its life, whatever it found, and a survivor
list printed at the end of a run that takes a while is advisory text in a log. Five
anchors had gone stale underneath it in the same way and for the same reason: a stale
anchor meant a mutation that could not be planted, and that failure was also invisible
from outside.

Three repairs, then:

1. **It exits non-zero when anything survives**, and says how many. A clean run prints
   `CLEAN - all N mutations were caught, none survived, M discarded.` and exits 0.
2. **`JAVA_HOME` is a fallback rather than an assignment.** The script set it to one
   machine's install path unconditionally, so it could not run anywhere else — including
   CI, which is the only place a sweep is worth running, because a survivor list nobody
   reads and nothing fails on is a comment.
3. **The source restore moved into a `finally`.** It sat between the mutation run and the
   classification, so any exception thrown in that window left the product source holding
   the mutation, and the next gate to read that file would have been reading a tree
   nobody edited.

It is now a job in `.github/workflows/compliance.yml` (job `sweep`, 30-minute budget)
rather than its own workflow, because it enforces the same kind of obligation the other
gates do: a comment claiming a behaviour is not a test asserting it. It is the only step
in that file that writes to the product source while it runs.

## Closed: the sweep's three survivors, and the default each one was hiding

The first run after those repairs reported `Baseline green.`, `All 34 anchors resolve.`,
**31 caught, 3 survived, 0 discarded**, and `SWEEP EXIT=1`. The exit code is the point of the
whole exercise: this list had been printed for months with nothing reading it. All four of
the save-counter mutations added for `ChatStore` were caught. The three survivors, exactly as
printed:

| Survivor | File | The defect it names |
| --- | --- | --- |
| `progress is throttled to nanoseconds rather than milliseconds` | `model/ModelDownloader.kt` | the throttle never fires, so every 256 KB of a multi-gigabyte download reports |
| `the benchmark prompt is dropped at the call site` | `ui/models/ModelManagerScreen.kt` | the benchmark asks the model nothing and the numbers still come back looking like measurements |
| `the diagnostics report stops naming the loaded model` | `ui/about/AboutScreen.kt` | the report a user sends after "it got slow" says `model: none` |

### The throttle, closed by counting

`PROGRESS_INTERVAL_NANOS = 200_000_000L` is compared against a `System.nanoTime()`
difference, which is 200 milliseconds and correct. It was `PROGRESS_INTERVAL_MS = 200L`
compared against the same difference for most of its life, which is 200 *nanoseconds*, and a
loop that has just read and written a 256 KB buffer cannot come back inside that — so the
throttle never fired once. The constant's own doc comment had recorded the defect; what was
missing was anything that noticed a repeat.

A duration is only observable by counting, so the test counts. `a local transfer reports a
few times rather than once per buffer` downloads a 16 MB body, which is 64 reads of 256 KB,
and asserts `progress.size * 4 <= reads`: the correct implementation reports one or two
times, and the mutation reports about sixty-four. Measured at 0.093 s for the whole 16 MB
transfer, and mutation-verified by hand — swapping the constant back to `200L` turns the
class red with one failure, and the file was restored byte-identically.

### The two screen sites, closed by the compiler

Both were deletions (`to = ''`), and a deletion only compiles while the parameter it removes
has a default. `benchmarkPrompt: String = ""` on `ModelManagerContent` and eight defaults on
`DiagnosticsReport.Facts` were what let an omission compile, and in both cases the omission
produced text that looked complete and was wrong — the one failure a diagnostics report
cannot have, because it is the only channel this app has. The defaults are gone, so the same
mutation now cannot be written at all:

```
caught        the diagnostics report stops naming the loaded model  (does not compile: e: … No value passed for parameter 'modelId')
```

**And the compiler answered four seconds later.** With the `Facts` defaults gone the build
failed at a line nobody was looking at:

```
e: file:///D:/AndroidProjects/DemoApp/app/src/main/java/com/example/demoapp/ui/about/AboutScreen.kt:482:30 No value passed for parameter 'contextTokens'.
```

`AboutScreen` was still omitting `contextTokens` after the repair that had fixed the other
four, and nothing in `AboutUiState` can ever supply it — this screen holds no conversation.
The report's context line prints `none` whatever the facts say, on every device, including
one with a model loaded and half its window full. The absence is deliberate and recorded
(`AboutViewModel`: "a number invented for a diagnostic report is worse than a gap"); what was
missing is the *call site* saying so, which is what it does now instead of defaulting the
question away. Whether the report should instead print the window it does have, on its own
line, is a change to what the report says and is the owner's call.

### A default is where a mistake hides

The three survivors are one finding in three costumes. A parameter with a default makes the
caller's omission compile, and the resulting behaviour is not a crash or a blank — it is the
honest-looking empty state, `none` and `0` and `""`, which is exactly what a device with no
model loaded prints. Nothing downstream can tell the two apart, and no test can be written
against the difference, because the difference is not in the behaviour: it is in the source.
Making the parameter required moves the question to the compiler, and the compiler found a
live defect on its first run. The earlier section on this page about a parameter whose
default made a feature quietly do nothing is the same finding about a *feature*; this one is
about *evidence*, and the sweep is what produced it — a mutation that survives is a
measurement of where the code can still be wrong, not only of where the tests are thin.

### Two more mutations, and the file that had no test at all

`DiagnosticsReport` is a pure object whose own doc says "it takes what it is given, so a test
can pin the format down without a device", and no test had ever taken it up: a grep of
`app/src/test` and `app/src/androidTest` for the name returned nothing. It is the only
channel this app has — no telemetry by design — so its nine tests are now the thing that
keeps the format honest: the model, the backend and the context reading reach the text, a
missing model says `none` rather than printing nothing, the platform's own `unknown` SoC
sentinel never reaches the page, the failure and the crash are two sections, the text is
redacted before it is returned, and every word on it is one it was handed rather than a
literal that ships in the wrong language. Two mutations in `DiagnosticsReport.kt` prove they
bite (`the report prints none for a model it was given`, caught by the first of those;
`the report stops redacting what it carries`, caught by the fifth), each run by hand and
restored byte-identically.

### The third classification the sweep needed

`caught` and `SURVIVED` were not enough once the mutations could stop compiling:

1. **`caught … (does not compile: <first e: line>)`** — the mutation cannot be expressed in
   this program. That is a stronger statement than a failing test, and it is not a survivor.
2. **`DISCARDED … (the build failed and no test ran)`** — a build that failed for any other
   reason. This machine's Gradle daemon wedges, and a run that never reached the tests must
   not be reported as coverage in either direction.
3. **The results directory is emptied before each run.** `app/build/test-results/…` holds the
   previous run's XML whenever this one never reached the tests, and reading it as it stands
   answers a question about the past — which is how the throttle mutation could have been
   called caught by a test suite that had not run.

## Closed: the intermittent failure was a rename the file system refused

Found on 2026-10-15, in the first full-suite run after the instrument the two sections above
left behind was extended to print it — found by the instrument, not by a guess.

The failing run printed this beside a fifteen-second timeout:

```
error=string#2131624131 detail='java.nio.file.FileSystemException:
  ...\chats\<id>.<uuid>.tmp -> ...\chats\<id>.json: ...'
```

`string#2131624131` is `err_conversation_save` — 没能保存这段对话。 The ViewModel had been
setting that message, with the exception's own text in `detail`, on every one of these
failures. Nothing printed it. What the diagnostic *did* print, `attempted=3 written=2`, is
the same fact seen from the other side: the store ran and decided, and the write threw.
The earlier reading of that pair as "a save that took the lock and never finished" was the
one thing the counters could not distinguish.

The mechanism, with everything else already ruled out by the rounds above: `writeNow` writes
the ciphertext to a unique temporary name, flushes, `fd.sync()`s, and renames it over the
conversation file. That rename can be refused by another process holding the target — on
Windows a virus scanner that has just seen the file closed, or the search indexer — and the
refusal is transient, because the same rename succeeds moments later. The store had no
retry: it caught the exception only to delete the temporary file and rethrow, so the save was
lost and the user was told a conversation could not be saved, for a reason they could do
nothing about. The test's fifteen seconds had nothing to do with it; the write had already
failed.

The fix, in `data/chat/ChatStore.kt`: the rename is retried on a widening schedule —
`MOVE_RETRY_MILLIS = [10, 25, 50, 100, 200, 400, 800, 1600]`, so `MOVE_ATTEMPTS` is nine and
the window runs to just over three seconds — and a refusal that survives all of them is thrown
to the caller, which reports it as it always did. (It started at three attempts 20 ms apart,
which a batch below then caught failing; six attempts then failed the same way in one run of
sixty; seven failed once in a hundred and fifty.) The retry
goes through a constructor parameter (`moveFile: (File, File) -> Unit = ::replaceFile`), and
that is what makes it testable: `FileChatStoreTest` passes a move that refuses the first
attempt and asserts the save still lands, and a move that refuses every attempt and asserts
the caller hears about it with the temporary file cleaned up. A mutation anchor breaks the
retry (the guard `if (attempt >= MOVE_ATTEMPTS) {` → `if (true) {`, which reports the first
refusal instead of trying again), so the behaviour is held up by a test rather than by this
paragraph.

**The account the refusal leaves behind, and why it is worth more than the fourth widening.**
Every widening above was decided without knowing what refused the move: `Files.move` throws a
`FileSystemException` whose message is `source -> target` plus a reason, and the reason is what
separates a sharing violation from a read-only target from a full disk. `saveAudit()` carries
`lastMoveRefusal` now, written by `describeRefusal` as the exception's own class, its reason or
the fact that none was given, and the attempts with the waits summed from the same schedule the
loop read — so a report cannot describe a window other than the one that was waited.
`FileChatStoreTest` asserts all three parts against a move that refuses with `read-only` every
time, and two mutation anchors break the reason and the sum. The next occurrence answers the
question instead of becoming a fourth widening — and if the answer turns out to be permanent
(a read-only target, a full disk), the schedule would be three seconds spent to report
something it could report at once, which is the question the line is there to settle.

Three consequences worth recording:

- **The "real disagreement between what the screen shows and what the disk holds"** was this,
  and it was never a disagreement about the data: the disk was right, the save had failed,
  and the screen had been told to keep a reply the disk never received.
- **The exposure in CI is gone.** `ci.yml` runs the suite with no retry, and the only
  intermittent failure it could hit was this rename, which no longer reaches an assertion.
  No retry was added there: with the cause fixed, a retry would hide a regression rather
  than a platform.
- **The test harness was never the problem.** Three attempts at fixing it — dispatchers, a
  dedicated executor, the teardown drain — were made and reverted while the cause was
  unknown. The sections above are the record of why each was wrong, and they are why the fix
  was made in the store rather than in the tests.

The batch that was supposed to hold this up proved nothing until its tool was fixed. The
first attempt, forty runs of `*ui.chat.*Test`, reported forty passes — five seconds each,
against ten for the first — because `scripts/stress-flake.ps1` did not pass `--rerun`: Gradle
marks `testDebugUnitTest` UP-TO-DATE once its inputs are unchanged, writes no result XML for
the second run onwards, and the script's pass branch trusted the exit code without asking
what ran. A green batch that never executes a test is the same shape of failure as the
diagnostic that never printed its error, one level up: the instrument was wrong, and the
result looked like the answer.

Three changes to `scripts/stress-flake.ps1`, all of them aimed at that shape:

- every run passes `--rerun`, so the task really re-executes;
- a run that produced no test XML is recorded as `no-tests` and fails the batch, printed as
  `0 executed and passed, 1 did not pass` rather than as a pass (verified with a filter that
  matches no class, which is now a red batch and used to be a green one);
- the XML reader uses `SelectSingleNode` rather than `$case.failure`, because line 59 sets
  `Set-StrictMode -Version Latest` and a missing child element is a terminating error there —
  the first time a real failure reached that path it killed the batch, which is the one
  moment the script exists for.

With the tool fixed, the batch ran for real — 40 runs of `*ui.chat.*Test`, 130 tests each, all
of them executing — and **it found the failure again**, in 3 of the 40 runs. That is the
result the batch existed to produce, and it says the section above was one cause and not the
whole story:

- **2 runs re-threw the same refused rename.** The saved error is the same
  `err_conversation_save` with a `.tmp -> .json` path, and `attempted=3 written=2` — three
  attempts, all refused, 20 ms apart. A scanner holding the target for longer than 60 ms
  defeats that schedule, and the fix as first written only narrowed the window rather than
  closing it. The waits then became `[10, 25, 50, 100, 200]` ms — and the second batch below
  caught one refusal that outlived all six, so they now run to 400 ms more. What a save costs
  in the ordinary case is 10 ms, once, and only when the file system refuses.
- **2 runs hit `IllegalStateException: Dispatchers.Main is used concurrently with setting
  it`**, in two tests each. This is the teardown hole the class's `awaitQuiescence` was
  written for, and it is still open: the class waits for the ViewModels it tracks to go quiet
  before `Dispatchers.resetMain()`, and something still dispatches to Main after that. It is a
  property of the test harness, not of the app — the same code path passes 74 tests in every
  other run — and the reported test is not the cause: the throw names the *other* `setMain`
  call site, so it is attributed to whichever test happened to be starting when a previous
  class's work came back to Main.

The second one was then fixed, and the shape of the miss is the point: `onScreenClosed` started
its closing save on a scope the test did not track, so the write could reach Main after
`resetMain()`. That write now registers in `closingWrites`, `awaitQuiescence` waits while it is
not completed, and `leaving mid-reply writes the part that arrived` covers the path. The rate
it is measured against is 2 in 40, and the batch that followed hit the harness race not at all
— consistent with a fix, not proof of one.

`ci.yml` retries the suite **once**, with the reason written beside the retry, so a run that
hits a platform race is retried rather than reported as a broken tree — and the local stress
batch stays the honest counter, because it never retries.

A second batch of sixty runs, after both fixes: `60 runs: 59 executed and passed, 1 did not
pass`. The failure was the rename again, not the harness race — `Gave up waiting for the saved
history after 964 polls … attempted=3 written=2 … error=string#2131624131 detail='…tmp ->
….json'`, three attempts every one refused, so six was still narrower than this machine's worst
case. Widening the schedule rather than changing what the last attempt does is the choice here
because `Files.move` after a sync is idempotent and the source file is intact, so waiting costs
only the rare case; copying over the target can leave a torn file that reads back as an
unreadable conversation, and deleting the target first gives up the atomic replace.

## A third batch of a hundred and fifty: two shapes, and the second one was the test

`150 runs: 148 executed and passed, 2 did not pass`, one per shape.

**Shape one, once in a hundred and fifty, the rename again** — run 12, `switching back to an
earlier version changes what is shown`: `Gave up waiting for the saved history after 965 polls.
save: attempted=5 written=4 … viewModel: saveNowCalls=5 throughLock=5 error=string#2131624131
detail='…\chats\c80b15cb-7d24-4ce5-8f74-a36fd1c56ddb.5fcd7b8f….tmp -> …\chats\c80b15cb….json'`.
Five saves were decided and four landed, so the fifth used up seven attempts and was still
refused: 785 ms was not the worst case either. The rate is what matters and it moved as the
window grew — **3 in 40, then 1 in 60, then 1 in 150** — which is the shape of a refuser that
lets go after a while rather than one that never does. The schedule now ends `…, 400, 800, 1600`
ms, nine attempts, just over three seconds.

Two things are worth more than the third widening:

- **The failure the caller sees carries no reason.** `Files.move` throws a
  `FileSystemException` whose message is `source -> target` plus a reason, and the reason is
  what distinguishes a sharing violation from a permissions failure from a full disk. Three
  widenings were decided without it. `FileChatStore.saveAudit()` now carries
  `lastMoveRefusal`, written by `describeRefusal`, naming the exception class, the reason or
  the fact that none was given, and the attempts and total wait — so the next occurrence
  answers the question instead of becoming a fourth widening. `FileChatStoreTest` asserts all
  three parts against a move that refuses with `read-only` every time.
- **The retry is still a guess in one direction**: it can only help if the refuser lets go. If
  the reason turns out to be permanent — a read-only target, a full disk — the schedule would
  be waiting three seconds to report something it could report at once. The audit line is what
  will tell the two apart, and the batch that follows is where it will show up.

**Shape two, once in a hundred and fifty, was not a lost write at all.** Run 104, `the question
is on disk before the answer exists`: `java.util.NoSuchElementException: List is empty`. The
test waited with

```kotlin
val saved = awaitSaved { it.isNotEmpty() }.first()
assertEquals("What is a haiku?", saved.messages.first().text)
```

and `isNotEmpty()` asks whether *a conversation* is on disk, not whether *the question* is. A
successful model load persists the current conversation — `ChatModels`, on `result.onSuccess ->
persist(updated)` — and at that moment it has no messages, so the file that satisfied the wait
could be that empty version. `messages.first()` on it throws exactly that exception. The write
was never missing; the assertion looked at the wrong conversation.

Four waits had this shape (`ChatViewModelTest` at the question-is-on-disk test, the
new-chat-keeps-the-old-one test, the regenerate-after-unload test, and the search-does-not-write
test — the last of which compared zero with zero and would have passed while measuring
nothing). All four now name the content they are waiting for. This is the same lesson as the
counter that never moved, one level down: **a wait whose predicate can be satisfied by the
wrong thing is not a wait**, and it took a hundred and fifty runs and a stack trace to see it.

**A fourth batch, at the widened window, caught nothing.** `150 runs: 150 executed and passed, 0 did not
pass` — and it left no file that said so: `build-logs\stress-b1e` holds neither a summary nor a single
per-run log, because the script wrote its summary only when something failed, so a clean batch and a
batch that never started left behind the same evidence. That is the same defect this script was built to
find in other tools — a pass over nothing is not a pass — and it is fixed: the summary is written either
way, and `build-logs\stress-b1f` is the batch that leaves one — `150 runs: 150 executed and passed, 0 did
not pass`, above the class filter, the finish time and the machine tag, the same four lines whether the
batch is clean or not. The rate kept falling as the window grew, 385 ms to 785 ms to 3,185 ms, from
`3 in 40` to `1 in 60` to `1 in 150` and then to none in the two batches after that, and every one of
those batches ended with the same caveat: *No failure in this batch. That is a result about this machine,
not about the code.* Zero in three hundred runs bounds the *failure* rate at about one percent (three over
n) rather than at zero — and zero failures turned out not to be zero refusals. The caveat is what a batch
says when it has nothing else to say; the script now also reports whether the refusal watch saw a refused
move, because the number that was missing all along is the one the next section measured: seven refusals
in one hundred and fifty runs, none of which outlived the window, with `lastMoveRefusal` empty through all
of them — it is written only when the retries run out. What those batches could not show was the reason,
and what they bought was a window wide enough that no user met one.

## What the refusal watch found, and what it found in itself

**The watch is one system property.** `demoapp.refusalWatch`, forwarded by `app/build.gradle.kts` into the
forked test JVM and passed by `scripts/stress-flake.ps1`, makes a move the file system refused and a later
attempt made fail a test and name its reason —
`absorbed: AccessDeniedException reason=none given after 2 attempts in 10ms`. With the property unset,
which is what CI runs, the same refusal is counted in `SaveAudit.absorbedMoveRefusals` with its account in
`SaveAudit.lastAbsorbedMoveRefusal` and changes nothing: a save that landed is not a failure. The window it
is measuring is `FileChatStore.MOVE_RETRY_MILLIS`, `[10, 25, 50, 100, 200, 400, 800, 1600]` — nine
attempts, 3,185 ms.

**The first watched batch reported seven absorbed refusals and no failures** —
`build-logs\stress-watch1`, `150 runs: 143 executed and passed, 7 did not pass` (the seven are the watch
doing its job), finished 2026-10-15 22:19:43. That is one refusal in about every twenty-one runs, against
zero that outlived the window, and it is the sentence the three batches above could not write. Two of the
seven kept their account: `absorbed: AccessDeniedException reason=none given after 2 attempts in 10ms` in
`a version change is written to disk` (round 10) and `… after 3 attempts in 35ms` in `opening a
conversation from history seeds the model with it` (round 15). So the reason the three widenings were
spent looking for is **`AccessDeniedException` with no reason given, gone by the first or second retry** —
not a read-only target, not a full disk, and not something a wider window answers: the window is what made
it invisible. The other five landed in `switching back to an earlier version changes what is shown`
(rounds 103, 105, 111, 130) and `opening the conversation that is already open changes nothing` (116),
which is the shape a refusal that arrives per unit of time would have inside a suite of seventy-five
tests that rename files at different rates. Each round's own output is kept under
`build-logs\stress-watch1\`.

**Five of the seven accounts were missing, and that was the watch's own defect.** Those five read
`1 time(s) in this test: no account`: the count and the account were two fields, the count written on the
thread that makes the move and read on the test thread, so the count's store could become visible while
the account's had not. `FileChatStore` holds them in one immutable `AbsorbedRefusals` behind a `@Volatile`
field, and `saveAudit()` reads it once. The second half was the same defect one level up: the check ran
first in `tearDown`, before the test had waited for the ViewModels it created, so it could read a count
while the save it was asking about was still retrying — it runs last now, after the class has waited. A
watch that can be wrong about the number it reports is the class of defect it was built to find, which is
why both are written down here instead of fixed quietly.

**The batch after the fix reported eleven more, and every one kept its account.** `build-logs\stress-watch2`
— `150 runs: 139 executed and passed, 11 did not pass`, finished 2026-10-15 22:40:42 — and not one occurrence
read `no account`: the count and the account are published as one value now, and eleven of eleven kept them
together. Across the two watched batches that is eighteen refusals in three hundred runs, about one every
seventeen, and all eighteen say the same thing: `absorbed: AccessDeniedException reason=none given`,
recovered at the first retry (10 ms) or the second (35 ms). Nothing in either batch needed more than 35 ms
of the 3,185 ms the window allows, which is the number the three widenings were guessing at. One round (40)
refused two moves in two different tests, which is the shape of a condition of the machine rather than of
one file: `switching back to an earlier version changes what is shown` absorbed seven, `the first message
still names a conversation that has no name yet` three, `sending a message does not undo a pin` one. What
this still does not measure is the rate a user meets — the suite renames files far more often than the app
does — but it does say that a refusal which outlives the window is not the shape of the ones this machine
produces.

**What the refusal is not.** The obvious suspect is a reader: `save` replaces a file that `list` or `load`
may be reading at that instant, and a Windows reader that opened without `FILE_SHARE_DELETE` would make
the replace fail every time the two overlap — which would also explain why the window absorbs it, since
the read lasts milliseconds. Measured on 2026-10-16 with a throwaway Java program on this machine (Java 25,
Windows 11): a `FileInputStream` and a `Files.newInputStream` were each held open on the target, and a
`Files.newByteChannel` on it as well, while `Files.move(tmp, target, REPLACE_EXISTING)` was attempted; all
three moves succeeded and the file was replaced. So a reader in this process does not hold the file against
the rename, the write path is not racing its own reader, and the refusal comes from outside the process —
the scanner or the indexer the retry's own comment names. That is worth having measured rather than
assumed: it retires the one cause that would have been this app's own bug, and it is why the retry stayed
the right fix instead of a wider window.

## What the lock and the read-aloud path answer on a device

Two seams cannot be reached from a JVM test: what `BiometricManager.canAuthenticate` says on the
device the user holds, and whether the platform's text-to-speech engine has a Chinese voice.
Neither is decidable from source — a constant `true` or `false` in either place passes every other
test in this project while locking a user out permanently, or never locking at all — so both were
given an instrumented test and both were run on `emulator-5554` through
`scripts/run-instrumented-test.ps1`.

`DeviceAuthTest` finished `OK (1 test)`. Its `println` goes to the instrumented process's stdout
rather than to the instrumentation stream, so the value is read from logcat after `adb logcat -c`:

```
I/System.out( 6323): DEVICE-AUTH: canAuthenticate=false
```

with `adb shell locksettings get-disabled` → `true` and `dumpsys fingerprint` → `Fps state: 0`.
This emulator has no lock-screen credential, so `canAuthenticate` is false and the fifteen-minute
lock correctly never engages here; the test's point is not the value but that the value is the
device's own answer, which is what `theLockFollowsTheDevicesOwnAnswer` asserts against
`AppLock.isLocked(..., authenticatedAt = null, ...)`.

`SpeechTest` finished `OK (2 tests)`. Logcat shows `Sucessfully bound to com.google.android.tts`
(googletts 20241125.02) and then

```
I/System.out( 6142): SPEECH: available=true queued=true finished=true
```

so on this image `TextToSpeech` **does** report a Chinese voice — an earlier guess from
`adb shell settings get secure tts_default_synth` returning `null` said the opposite — the utterance
was queued, the completion callback fired, and `isSpeaking` was false at the end. The second test
has no no-voice branch to exercise here and says so: `SPEECH: this device has a Chinese voice, so
the no-voice path is not exercised here`.

Neither test proves anything was audible, and neither claims to. The engine's path is asserted; the
sound is judged by listening. That distinction is the test's own KDoc, because a voice with no data
reports an error and the app clears its state exactly as designed — a green test over a silent
phone is the failure mode a listener catches and a runner does not.

## What the app's own downloader does on a device

The download was another of the four paths in `docs/ROADMAP.md` that only a device can answer for,
and the one the app cannot work without: the engine tests load weights somebody pushed, while a
user's first run goes through `ModelDownloader`. On 2026-10-16 it ran on `emulator-5554` twice, in
the app's own UI rather than under test.

The first run was in the **release install** of v1.0.1 — the published artefact, put on the device
with `adb install -r` over the v1.0 install, then driven by hand: the list offered Qwen3 0.6B 量化版
(329 MB), the tap downloaded it at 15–18 MB/s, and the row ended at `已安装 · 329 MB`, with
设置 → 这台设备上的占用 reading `模型 329 MB`. That install was then removed to make room for the
debug pair the instrumented tests need, and its app data went with it.

The second run was in the **debug install**, and this is the one the rest of this document depends
on: it is what put the catalogue's own file where `TestModel.orNull` looks for it.

Package ids in this section are the ones the runs used — `com.example.demoapp`, which became
`com.kayoyak.demoapp` on 2026-10-16 (see the rename row in `docs/ROADMAP.md`). Paths and traces
inside quoted output are left exactly as the tools printed them.

```
$ adb shell run-as com.example.demoapp ls -l files/models/
-rw------- 1 u0_a230 u0_a230 344671744 2026-10-16 04:09 Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm
```

344,671,744 bytes, the catalogue's file name, in the directory the application keeps it in. The
instrumented runs that follow therefore exercise the file a user would have, not a variant pushed by
hand — which is the difference the fallback in `TestModel` exists to keep visible in its log.

What a device run cannot show is the network the user is on: these two were an emulator's NAT
through a desktop connection, and the failure the downloader was built around — a digest that did
not match, in the section above — is a server-side fact rather than a device one.

## What the sampler comparison measured, and what it could not

`docs/ROADMAP.md` asked whether the `topK` default of 20 is any good. It had been lowered from 40 on
the publisher's recommendation, and nothing in this project had checked it.
`SamplerComparisonTest` (`app/src/androidTest/java/com/kayoyak/demoapp/inference/SamplerComparisonTest.kt`)
is that check: the same prompt the app's own benchmark uses (「用一句话说明什么是光合作用。」), the engine
loaded once per setting, `temperature = 0.8` and three seeds per setting, both settings in one run so
they share a machine and a warmed cache. The benchmark cannot answer the question itself — it runs at
`temperature = 0.0`, where decoding is greedy and neither `topK` nor `topP` participates, deliberately
and correctly for what it measures — so this test runs the sampler the chat actually uses. Each sample
records the answer, the reasoning channel, the token count, the time and an eight-character repetition
ratio, and the test asserts that the samples within a setting are not all identical: a seed that never
reached the decoder fails rather than reading as a result.

**The first run measured nothing.** It asked for the catalogue's 4-bit Qwen3 — the model a first-run
user downloads — and got six samples of zero tokens, in about a minute, with the reasoning channel
empty as well. The engine's own test fails the same way on the same file on this machine:

```
java.lang.AssertionError: generation produced no tokens …
  at com.example.demoapp.inference.LiteRtLlmEngineTest.loadsModelGeneratesAndStreams(LiteRtLlmEngineTest.kt:142)
```

That message now carries the model id, the file name and the backend, because the first version read
`generation produced no tokens` and nothing else, which leaves a reader guessing which model was
silent. What the silence means is not established here: the file is the one the downloader fetches and
verifies, `files/models/Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm`, 344,671,744 bytes, and the engine
reports no error — it loads it and generates nothing. This is the state of knowledge at the time of the
run, and the comparison below names its model explicitly instead of taking the catalogue's for exactly
that reason. **The meaning was found later the same day, and it was not the model**: the runtime was
returning the reply on a named channel the engine did not read, and thinking at the model's default
spent the entire reply budget before reaching an answer. This prompt now comes back as
`The result is: 1 2 3 4 5 6 7 8 9 10.` through the app's own engine; see *The catalogue's own model was
never silent* below.

**The second run had a model that answers.** `SAMPLER: NOTE — using the named model
SmolLM2_360M_instruct.litertlm (373,719,040 bytes, from /data/user/0/com.example.demoapp/files/models);
the catalogue entry 'qwen3-0.6b-int4' is not what this run measures.` Six samples, 304.6 s:

| setting | seed | tokens | time (ms) | tok/s | repeat ratio | characters |
| --- | --- | --- | --- | --- | --- | --- |
| `topK=40` | 1 | 128 | 18,983 | 6.7 | 0.118 | 269 |
| `topK=40` | 2 | 128 | 16,296 | 7.9 | 0.036 | 145 |
| `topK=40` | 3 | 92 | 13,843 | 6.6 | 0.047 | 113 |
| `topK=20` | 1 | 128 | 22,530 | 5.7 | 0.000 | 151 |
| `topK=20` | 2 | 128 | 21,322 | 6.0 | 0.021 | 153 |
| `topK=20` | 3 | 128 | 17,849 | 7.2 | 0.000 | 154 |

Medians: `topK=40` 0.047 repeat, 145 characters, 6.74 tok/s; `topK=20` 0.000 repeat, 153 characters,
6.00 tok/s. Three distinct texts in both settings. So the tighter cut repeated less on this model and
its speed is inside the spread — the direction the publisher's recommendation claims, measured on
three samples of one prompt.

Two things about the second run are worth keeping. The answers came back partly as byte-fallback
mojibake (`在 Python 中，å¦Ĥ果…`) — a 360M English-first model being asked a Chinese question, which is a
property of the model and of the prompt, not of the sampler, and is the visible reason the app's
catalogue narrowed to models that handle the language it is written in. And the first version of this
test recorded only the answer, so a reasoning-heavy model that emits its thoughts in the reasoning
channel would have measured as an empty string; the sample now carries both channels and the printed
line says how many characters each held.

**The runner lost a passing run's numbers, which is how the instrument was found to be the problem.**
`scripts/run-instrumented-test.ps1` kept the instrumented output only when a run failed, and the tests
print with `println`, which does not travel on the instrumented stream at all — it lands in logcat
under `System.out`. A green measurement therefore printed its numbers into logcat, where a caller
capturing this script's stdout could not see them, while the same run's failure text was the thing
that survived. The numbers above were recovered by hand from `adb logcat`. The script now clears the
buffer before the run and writes both sources to `build-logs\instrumented-output.txt`, so the evidence
of a passing run is a file rather than a memory of one.

**What this does not establish, and what the value became anyway.** The machine is `emulator-5554` on a
desktop CPU, not a phone; the model is a 360M one the catalogue no longer carries; three samples of one
prompt is a sign, not a default. The default therefore stays where the publisher's recommendation already
put it — `topK = 20` — which is the value no number above argues against: the tighter cut repeated less,
lost nothing in variety, and was not slower outside the spread. The owner was asked to choose between 20
and going back to 40 and left the choice to the project, so the project took it on that evidence rather
than on preference. What the measurement changed is not the value but its standing: 20 had an argument
from a model card and now also has a reading. The row that asks whether the app's own default is right
still exists, and now says what is missing rather than that nothing had been tried: the same comparison,
on a phone, on the model the app ships.

## What a rename taught the gates, and the id that had two spellings

The application id changed on 2026-10-16, from `com.example.demoapp` to `com.kayoyak.demoapp`. The
owner had been asked for a domain and answered 「随便你吧」, so the project chose: `KAYOYAK` is this
project's own namespace (the GitHub account, and the `CN` of the certificate the release is signed
with) and `demoapp` is the label the release already carries, so nothing in the id pre-empts the
product name, which stays a one-line change in `strings.xml`. `scripts/rename-package.ps1` did the
work: 113 files and 3 directories, reading the old name **out of the build file** rather than assuming
one, `git mv` for the source tree so history follows, markdown deliberately untouched because the
documents quote paths as they were written, and a refusal to write unless `app/src` and
`app/build.gradle.kts` are committed, so the rename is readable and revertable on its own.

The rename had been rehearsed twice, so a build failure was the expected shape of trouble. The gates
were what broke — four of the nine — and not because they compared a wrong value. They did not read
the id at all. Six gates had `com/example/demoapp` inside them, as a path, and the id appears in two
spellings: a package path (`com/example/demoapp`, in file paths) and a Java name (`com.example.demoapp`,
in class names and `-Class` arguments). The gates held the path one, and they failed in the two ways a
stale path fails: the ones that open a named file stopped finding it (`check-model-pins.ps1`,
`check-privacy.ps1`, `check-callback-reachability.ps1`, `check-legal-docs.ps1`), and the ones that keep
an exemption list went on matching paths that no longer exist — so they reported prose that had been
excused while unable to open the file they excused it by name (`check-ui-copy.ps1`,
`check-ui-wiring.ps1`, `mutation-sweep.ps1`).

The fix is one reader instead of six copies: `scripts/app-package.ps1` exposes `Get-AppPackage $root`,
which reads the `namespace = "…"` line from `app/build.gradle.kts` and returns it in path form. Every
gate that names application sources dot-sources it now, the sweep's anchor paths are built from it,
`.github/workflows/model-pins.yml` matches `app/src/main/java/**/model/ModelCatalogue.kt` instead of
the package that used to be there, and the two places that must still spell the id literally are
`run-instrumented-test.ps1`'s defaults — a test class's name is a Java name, not a path, and a gate
cannot read it out of a namespace line.

**The tool that rewrites names now checks what it rewrote.** A path that no longer exists is not the
only way a rename can miss: a file whose extension the rewrite does not cover can still mention the old
package. `rename-package.ps1` therefore scans `app/` and `scripts/` after writing and throws listing
what is left. Both halves were proven on scratch clones of the real tree rather than argued: a clone
with nothing left behind prints **No file under app/ or scripts/ still names the old package**, and a
clone with one file planted where the rewrite cannot reach it —
`app/src/test/java/<old package>/notes.txt`, a `.txt` the extension list skips, holding a path with the
old name in it — throws and names that file. The first version of that message died of its own syntax
(`Sort-Object -Unique -join` is not a thing) and printed a parameter-binding error where the list of
files should have been: a check that fires is worth nothing if its message does not say what it found,
which is the same defect this repository has already recorded in the notices gate and in the refusal
watch.

Verified after the gate fixes rather than around them: `:app:testDebugUnitTest` exits 0, all nine gates
report CLEAN (the two that read the network were run on their own), and the instrumented suite passes on
the device under the new id — which is the part that would have caught a manifest entry or a `run-as`
path that no longer resolves. What a user sees is in the roadmap row: an installed copy is not upgraded
but joined, the old app and the new one side by side, which is why the change was made while the only
install anywhere was this machine's emulator.

## Four gates that could not tell a pass from a check that never ran

The rename exposed one way a gate can point at nothing — a path that no longer exists — and the fix left
the quieter half in place: a gate can also *name* a file, or excuse one, without anything checking that the
name reaches anything. Checked across all ten gates on 2026-10-16 by planting each defect in a scratch copy
of the tree and reading the exit code, four behaved the same way, and a fifth instance — inside one of the
four — turned up when the gate was mis-invoked, at the end of this section. Each now fails loudly, and each
guard is proven rather than argued — the planted defect on the left of every row, the exit code on the
right.

| Gate | Defect planted in a scratch clone | Before | After |
| --- | --- | --- | --- |
| `scripts/check-ui-copy.ps1` | one of the eleven names in `$developerFacing` renamed away | `exit 0`, `CLEAN` | `exit 2`, naming the file that is missing |
| `scripts/check-privacy.ps1` | the package directory deleted | `exit 0`, `CLEAN` | `exit 2`, `no Kotlin or Java sources under app/src/main` |
| `scripts/check-ui-wiring.ps1` | an `$exempt` key naming a screen that does not exist | nothing said so — the list is empty, so no key is read | `exit 1`, naming the key and the entry it does not reach |
| `scripts/check-embedded-notices.ps1` | an acknowledgement for an artifact the build does not produce | `exit 0`, `No new embedded notices` | `exit 1`, `STALE RECORD` |

Two of the four were live at the moment they were found. `check-ui-copy.ps1`'s eleven names and
`check-privacy.ps1`'s source paths both aimed at `com/example/demoapp`, which no longer existed, and both
reported `CLEAN` over an empty population: the first read no file for the copy it checks, the second read
no file for the permissions it checks. The other two were future defects: `check-ui-wiring.ps1` reads its
exemption keys only when a key exists, and `check-embedded-notices.ps1` compares its acknowledgements
against the build's findings, so an acknowledgement for an artifact that is gone was reported as
`No new embedded notices`.

The convention all four follow now is the one the other six already had: a gate that cannot see what it was
pointed at does not say `CLEAN`. That needs an exit code of its own — `2` for "this check did not run"
against `1` for "this check ran and found something" — so a caller can tell a broken gate from a red tree.

Six gates did not need the fix, which is the part worth recording rather than the four that did:
`check-model-pins.ps1` throws when its pattern stops matching (`the pattern has gone stale, which is worse
than a failing check`); `mutation-sweep.ps1` reports `STALE MUTATIONS` when a mutation no longer applies;
`check-licenses.ps1` fails on a lock-file dependency the licence report no longer has; the callback gate
exits `2` with `SOURCE NOT FOUND`; `check-unused-strings.ps1` fails when no `R.string` reference is found
anywhere, calling that out as a check that did not run; and `check-legal-docs.ps1` and the `Navigation.kt`
branch of `check-ui-wiring.ps1` say the same when the file they parse is absent.

Both directions were checked on the real tree, not only on clones: with each defect planted the gate exits
non-zero and names what it could not find, and on the real tree `check-ui-copy.ps1`, `check-privacy.ps1`
and `check-ui-wiring.ps1` report `CLEAN` while `:app:testDebugUnitTest` still exits 0. The notices gate
resolves the release classpath rather than reading a built APK — on the real tree it reports `78 AARs, 50
with notices, four findings accepted` — so its planted proof is a scratch clone and its real-tree run is
the one the pre-commit hook performs when a build file changes.

**The fifth instance was found by mis-invoking a gate.** `check-embedded-notices.ps1` declared a
`-BuildDir` parameter, documented as "Directory of a built APK to cross-check against. Optional", and never
read it: no caller passes it, and the gate's answer is identical when one is. Pointed at an APK file
instead of the directory holding it, it printed `No new embedded notices` and exited 0 — a cross-check it
promised and nothing performed. The parameter is removed rather than implemented, because resolving the
classpath is the check this gate exists to be; a caller that still passes `-BuildDir` now gets PowerShell's
parameter-binding error and a non-zero exit, which is the loud refusal the rest of this section is about.

## What the model the app ships was doing instead of answering

The catalogue's own entry — `qwen3-0.6b-int4`, 344,671,744 bytes, the file a first-run user downloads — loaded
cleanly and then answered nothing. `SamplerComparisonTest` recorded six samples of zero tokens in about 31
seconds each, with the reasoning channel empty as well, and `LiteRtLlmEngineTest` failed on the same file with
`generation produced no tokens`. The reading taken at the time was that the model was silent. The model was
talking the whole time, on a channel the app does not read.

Three things were ruled out before the file was blamed. The engine works: the same classes answer on
`SmolLM2_360M_instruct.litertlm` on the same emulator and `LiteRtLlmEngineTest` passes there. The backend is
probed by *running* it — one token through a throwaway conversation, which is what makes an unusable GPU
delegate fail at load instead of on the user's first message — and this file reports CPU with a successful
probe. And the file loads: the runtime maps it, reports it, and streams from it.

What made the difference visible is `ThinkingChannelTest`
(`app/src/androidTest/java/com/kayoyak/demoapp/inference/ThinkingChannelTest.kt`), which exists because the
harness had no way to name a model on the command line. `TestModel` now reads an instrumentation argument
(`demoapp.modelFile`), `scripts/run-instrumented-test.ps1` gained `-ModelFile` and passes it, so the same class
can be pointed at either Qwen build or at SmolLM2 without editing a test and rebuilding. Its first test runs
through the app's own engine and prints what reaches the assembler; its second prints what the runtime hands
back per message, with thinking at the model's default and with it off.

Measured 2026-10-16 on `emulator-5554`, both Qwen builds, one prompt (count from one to ten, separated by
spaces):

| Model file | Setting | Messages | Answer | Channel text | Time |
| --- | --- | --- | --- | --- | --- |
| `Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm` (344 MB, the catalogue's) | model default | 254 | 0 characters | 951 characters of `thought` | 57.4 s |
| the same | thinking off | 39 | 71 characters, correct | none | 9.0 s |
| `Qwen3-0.6B.litertlm` (614 MB, full precision) | model default | 253 | 19 characters | 833 characters of `thought` | 34.6 s |
| the same | thinking off | 20 | 58 characters | none | — |

Two defects came out of that, and they are different defects.

**The app read half of what the runtime returns.** A `Message` carries the reply in `contents` and the rest in
`channels`; `LiteRtLlmEngine.textContent()` read only `contents`, so every character the runtime routed into a
channel was dropped before anything could see it. Qwen3's template declares a `thought` channel, and with
thinking on the answer follows the thought — so a whole reply, 254 messages of it, was discarded, which is
exactly what "the model generates nothing" looks like from outside. `ReplyAssembler.addChannelText` records that
text in the reasoning trace instead: not the answer, because the runtime said it is not, and not lost either.
`ReasoningSplitter.record` appends it verbatim rather than scanning it, because text the runtime has already
separated cannot contain a marker, and a channel ending on the start of one would otherwise be held back until
the stream ended. The screen already knows how to show this: the trace renders above the answer, closed by
default, and `ChatGeneration.commitReply` already answers a reply that is *only* a trace with
`chat_reasoned_only` rather than a stopped spinner.

**Thinking was left at the model's default, and the default spends the whole budget.** Given 256 tokens, the
catalogue's model used every one of them thinking and never reached an answer; the app's own configuration
allows 512, and `LiteRtLlmEngineTest` on the same file got nothing out of that either. Asked not to think, the
same model answered the same prompt correctly in a sixth of the tokens. `LiteRtLlmEngine.configFor` now passes
`ThinkingConfig(enableThinking = false)` for every conversation the app creates, the load probe included.
Nothing about the trace UI changes: a model that thinks anyway, or a user's own model whose template carries a
reasoning block, still reaches the screen through `addChannelText` and the splitter.

**What this does not establish.** One model family, one emulator, one prompt. Thinking off is a measurement
against the app's own models rather than a claim about thinking models in general — a model that needs to think
is worse off for it — and the runtime's other route, a bounded `thinkingTokenBudget`, has not been tried. The
emulator's CPU is not a phone's, so 9 s and 57 s are ratios and not timings.

**And the model answered the question the catalogue was asked to answer.** The comparison the silent file could
not take part in has now run on it: `SamplerComparisonTest`, its usual prompt (explain photosynthesis in one
sentence, in Chinese), three seeds per setting at `temperature = 0.8`, `emulator-5554`, 2026-10-16. `topK=40`
produced three distinct answers of 48, 56 and 38 characters — median repeat ratio 0.000, 2.88 tok/s — and
`topK=20` three distinct answers of 28, 56 and 35 characters (0.000, 2.69 tok/s). All six are fluent Chinese
sentences about photosynthesis, which is the part that matters: the model this app ships produces text through
the app's own engine, where before it produced samples of zero tokens and a failing test. What the numbers say
about the sampler is narrower than the earlier run's: on this model the two settings are indistinguishable on
repetition, and the tighter cut writes shorter answers, so the value stays where the publisher's own
recommendation and the 360M reading put it. A phone is still what would settle which of the two reads better.
