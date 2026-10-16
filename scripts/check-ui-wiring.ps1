#requires -Version 7
<#
    Checks that every screen the navigation graph opens is actually wired to it.

    The rule this enforces is not "pass every parameter". It is: a screen callback
    that nothing can reach is a feature that does not exist, and a default value is
    exactly what hides that.

    Why a gate, when the code looks connected: three features in this project were
    written, tested, and unreachable at the same time.

      * Sharing one reply had a working Activity-side implementation and a
        `chat_share_subject` resource; the argument was deleted from the call site
        and nothing failed.
      * The empty state that explains the app runs the model on the phone existed as
        an alternate branch with an identical condition, so it could never be taken.
      * The tamper-detection block read the signature, decided the verdict, and
        never rendered either.

    All three compiled, and all 477 unit tests passed, because the screen callbacks
    carry `= {}` defaults. Those defaults exist so a Preview can render a screen with
    no engine and no device, which is worth keeping. The cost is that forgetting one
    argument is silent, and silence is what this gate removes.

    Two rules, because the first alone would have missed all three cases:

      1. A parameter with no default is a precondition. Every call must pass it.
         This one the compiler already enforces; it is checked here so the two rules
         are read together and so a future default cannot quietly weaken a
         precondition.

      2. A callback that has a default must still be passed. `on*` naming plus
         `-> Unit` is the shape of "the screen asks the app to do something", and a
         screen that asks a question nobody answers is the failure above.

    A third rule, added after the first two let six of the same defect through:

      3. A parameter whose default is empty or zero must still be passed. `= ""` and
         `= 0` are the shape of "not wired": an empty value is not a legitimate
         state the feature knows how to be in, it is the state it is in when nobody
         ever filled it in. The count of things this hid, all found by reading:
         the benchmark asked every model an empty prompt; `ContextBudget` carried a
         `reservedTokens` nothing set; `FileCipher` was constructed with an
         `onUnreadable` that was never called, so conversations that could not be
         decrypted vanished silently; the row that shows how much space a
         conversation takes read a `bytes` nothing assigned; and the erase screen
         read a `wiping` flag nothing wrote.

      Not checked, and the distinction is the whole of rule 3: `= null` on a
      nullable parameter. "No value" is a state these screens mean, and a Preview or
      a first run is entitled to be in it. It is only empty *non-null* values that
      have no meaning to express, and therefore only they that are always a wiring
      mistake when absent.

    What is deliberately not checked: plain values with defaults that are neither
    empty nor zero, and anything whose name does not look like a callback. Those are
    Preview affordances, and treating them as wiring would make the exemption list
    the interesting part of this file.

    Exemptions are listed rather than matched by a rule, because each one is a
    decision. Adding to the list should be deliberate.
#>

[CmdletBinding()]
param([string] $ProjectDir)

$ErrorActionPreference = 'Stop'

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
if (-not $ProjectDir) { $ProjectDir = Split-Path -Parent $scriptDir }
$root = $ProjectDir
$sourceRoot = Join-Path $root 'app/src/main/java'
. "$PSScriptRoot/app-package.ps1"
$appPkg = Get-AppPackage $root
$navigationFile = Join-Path $sourceRoot "$appPkg/Navigation.kt"

# Callbacks a call site is allowed to leave to its default, with the reason.
# The key is the screen's function name, the value is the callback's name.
$exempt = @{}

# ------------------------------------------------------------------ parsing ---

# Reads a file and hands back its lines, so a report can name the line a problem
# is on. Line endings are normalised because this repository's files are LF and a
# here-string written on Windows is not.
function Get-SourceLines([string] $path) {
    return ([System.IO.File]::ReadAllText($path) -replace "`r`n", "`n").Split("`n")
}

# Collects the argument list of a call whose opening parenthesis is at $start.
# Depth-aware rather than a regex: an argument is routinely another call, and a
# pattern that stops at the first ')' would read `NeedsAModel(onOpenModels)` as
# ending the outer call.
function Get-ArgumentText([string[]] $lines, [int] $startLine, [int] $startColumn) {
    $text = [System.Text.StringBuilder]::new()
    $depth = 0
    $started = $false
    $lineComment = $false
    $blockComment = $false
    # Whether the reader is inside a string literal. Without this, a `/*` that
    # appears *inside* a string opens a block comment that never closes in the
    # right place, and the reader swallows the rest of the file: `"*/*"` in an
    # arrayOf was doing exactly that, so the argument list of `HistoryContent`
    # came back as eighteen thousand characters and every parameter after that
    # point was reported missing.
    $inString = $false
    for ($i = $startLine; $i -lt $lines.Count; $i++) {
        $line = $lines[$i]
        $lineComment = $false
        $from = if ($i -eq $startLine) { $startColumn } else { 0 }
        for ($c = $from; $c -lt $line.Length; $c++) {
            $ch = $line[$c]
            $next = if (($c + 1) -lt $line.Length) { $line[$c + 1] } else { [char]0 }

            # A string literal is opaque: its contents are not code, and treating
            # them as code is the bug this variable exists to prevent.
            if ($inString) {
                if ($ch -eq '\') { $c++; continue }
                if ($ch -eq '"') { $inString = $false }
                [void]$text.Append($ch)
                continue
            }

            # Comments are dropped as the list is read, not stripped afterwards.
            # Prose punctuation inside a comment was being parsed as an argument
            # separator, which cut `sharedText = sharedText,` in half and dropped
            # `onSpeak` entirely -- a gate that silently forgets an argument would
            # report clean on exactly the bugs it exists to find.
            #
            # A block comment's opening is replaced rather than removed, so every
            # later column still lines up with the file on disk and a reported
            # column means something to a reader.
            if ($blockComment) {
                if ($ch -eq '*' -and $next -eq '/') { $blockComment = $false; $c++ }
                continue
            }
            if ($lineComment) { continue }

            if ($ch -eq '"') { $inString = $true; [void]$text.Append($ch); continue }
            if ($ch -eq '/' -and $next -eq '*') {
                $blockComment = $true
                [void]$text.Append(' ')
                $c++
                continue
            }
            if ($ch -eq '/' -and $next -eq '/') { $lineComment = $true; $c++; continue }

            if ($ch -eq '(') {
                $depth++
                if (-not $started) { $started = $true; continue }
            } elseif ($ch -eq ')') {
                # The outer closing parenthesis ends the list and is not captured.
                # Guarded on $started so a ')' that arrives before the call is not
                # counted, which would close the parenthesis early.
                if ($started) {
                    $depth--
                    if ($depth -eq 0) { return $text.ToString() }
                }
            }
            if ($started) { [void]$text.Append($ch) }
        }
        [void]$text.Append("`n")
    }
    return $text.ToString()
}

# The offset just past the `)` that closes the parenthesis at $open, or -1. Bracket
# depth comes from a single forward pass over the file's text rather than from the
# line index, because a parameter list that ends mid-line makes the line index
# useless for anything that follows it.
function Get-BodyStart([string] $text, [int] $open) {
    $depth = 0
    for ($c = $open; $c -lt $text.Length; $c++) {
        $ch = $text[$c]
        if ($ch -eq '(') { $depth++ }
        elseif ($ch -eq ')') {
            $depth--
            if ($depth -eq 0) { return $c + 1 }
        }
    }
    return -1
}

# Splits an argument list on top-level commas only, so `onSpeak = { a, b -> ... }`
# survives intact. Comments have already been dropped by the reader, which is what
# keeps prose punctuation from being read as argument separators.
function Split-Arguments([string] $text) {
    $parts = [System.Collections.Generic.List[string]]::new()
    $current = [System.Text.StringBuilder]::new()
    $depth = 0
    foreach ($ch in $text.ToCharArray()) {
        switch ($ch) {
            '(' { $depth++ }
            ')' { $depth-- }
            '{' { $depth++ }
            '}' { $depth-- }
            '[' { $depth++ }
            ']' { $depth-- }
        }
        if ($ch -eq ',' -and $depth -eq 0) {
            $parts.Add($current.ToString())
            [void]$current.Clear()
            continue
        }
        [void]$current.Append($ch)
    }
    if ($current.Length -gt 0) { $parts.Add($current.ToString()) }
    return $parts
}

# The parameter name an argument is being passed to. Covers `name = value`
# through several lines, a trailing `::reference` with no name, and `{ ... }`.
function Get-ArgumentName([string] $argument) {
    $trimmed = $argument.Trim()
    if ($trimmed -match '^(\w+)\s*=[^=]') { return $Matches[1] }
    $head = $trimmed.Split('=')[0].Trim()
    if ($head -match '^(?:[\w\.]+\.)?(\w+)\s*::') { return $Matches[1] }
    if ($head -match '^(\w+)\s*$') { return $head }
    return $null
}

# ------------------------------------------------------------------- source ---

$screenFiles = Get-ChildItem $sourceRoot -Recurse -Filter '*.kt' -File
$problems = [System.Collections.Generic.List[string]]::new()

# ------------------------------------------------------------------ rule 1+2 ---

if (-not (Test-Path $navigationFile)) {
    Write-Output 'Navigation.kt is missing, so this check did not run.'
    exit 2
}
# Every composable declared in this project, with the parameters that decide
# whether a call site is complete.
$screens = @{}
foreach ($file in $screenFiles) {
    $lines = Get-SourceLines $file.FullName
    for ($i = 0; $i -lt $lines.Count; $i++) {
        if ($lines[$i] -notmatch '^\s*(internal\s+)?fun\s+([A-Z]\w*)\s*\(') { continue }
        $fun = $Matches[2]
        # Only screens: the navigation graph calls these, and the name says so.
        if ($fun -notmatch 'Screen$' -and $fun -notmatch 'Content$') { continue }
        $open = $lines[$i].IndexOf('(', $lines[$i].IndexOf($fun))
        $paramText = Get-ArgumentText $lines $i $open
        # Where this declaration's body starts, in the file's own text. Rule 4 needs
        # it; the rules above do not, which is why it is computed here and read later.
        $text = [System.IO.File]::ReadAllText($file.FullName)
        $absoluteOpen = 0
        for ($n = 0; $n -lt $i; $n++) { $absoluteOpen += $lines[$n].Length + 1 }
        $absoluteOpen += $open
        $bodyStart = Get-BodyStart $text $absoluteOpen
        $declared = [System.Collections.Generic.List[object]]::new()
        foreach ($arg in (Split-Arguments $paramText)) {
            $trimmed = ($arg -replace "`n", ' ').Trim()
            if ($trimmed -notmatch '^(?:@\w+\s+)?(\w+)\s*:\s*(.+)$') { continue }
            $name = $Matches[1]
            $type = $Matches[2].Trim()
            $hasDefault = $type -match '='
            $declared.Add([pscustomobject]@{
                Name       = $name
                Type       = $type
                HasDefault = $hasDefault
                # "The screen asks the app to do something." The `on` prefix plus a
                # function type is what separates a callback from a value that a
                # Preview legitimately leaves alone.
                IsCallback = ($name -match '^on[A-Z]') -and ($type -match '->\s*Unit')
                # Rule 3. Empty and zero only: see the header for why `null` is not
                # in this list.
                IsEmptyDefault = ($type -match '=\s*(""|0|0L|0f|0\.0|0\.0f|emptyList\(\)|emptySet\(\)|emptyMap\(\))\s*$')
            })
        }
        if ($declared.Count -gt 0) {
            $screens[$fun] = [pscustomobject]@{
                Params    = $declared
                BodyStart = $bodyStart
                Path      = $file.FullName
                Label     = $file.FullName.Replace("$sourceRoot/", '').Replace('\', '/')
            }
        }
    }
}

if ($screens.Count -eq 0) {
    $problems.Add('no screen composable was found, which means this check did not run')
}

$navLines = Get-SourceLines $navigationFile
$navText = [System.IO.File]::ReadAllText($navigationFile)

# Which call sites are checked.
#
# The navigation graph is the obvious one, and it is not the only one: a screen is
# routinely split into a stateful `XScreen(viewModel, ...)` and a stateless
# `XContent(state, ...)`, and the two are wired to each other *inside the same
# file*. That call site is where `onSpeak` was dropped -- the argument reached
# `ChatScreen` from the graph, `ChatScreen` never handed it to `ChatContent`, and
# `ChatContent`'s default meant nothing failed.
#
# So the same two rules run over every source file, not just Navigation.kt. What
# keeps that from drowning the report is the exemption below: a `@Preview` is
# exactly the caller that is supposed to lean on the defaults.
$callSites = @()
foreach ($file in $screenFiles) {
    $callSites += [pscustomobject]@{
        Path   = $file.FullName
        Label  = $file.FullName.Replace("$sourceRoot/", '').Replace('\', '/')
        Lines  = Get-SourceLines $file.FullName
        Text   = [System.IO.File]::ReadAllText($file.FullName)
    }
}

# Reads the name of the enclosing declaration, so a call inside a `@Preview`
# composable can be told from a call inside the real one.
function Get-EnclosingCallable([string[]] $lines, [int] $lineIndex) {
    for ($i = $lineIndex; $i -ge 0; $i--) {
        if ($lines[$i] -match '^\s*(?:@\w+\s+)*(?:internal\s+|private\s+|public\s+)?fun\s+(\w+)\s*\(') {
            return $Matches[1]
        }
    }
    return ''
}

# Each call to a screen, with the arguments it passes.
$calls = 0
foreach ($site in $callSites) {
    foreach ($screenName in $screens.Keys) {
        $pattern = "(?<![\w\.])$screenName\s*\("
        foreach ($match in [regex]::Matches($site.Text, $pattern)) {
            $upTo = $site.Text.Substring(0, $match.Index)
            $lineIndex = ($upTo -split "`n").Count - 1
            if ($lineIndex -ge $site.Lines.Count) { continue }
            # A declaration, not a call.
            if ($site.Lines[$lineIndex] -match "fun\s+$screenName\s*\(") { continue }

            # A preview has no engine, no device and no clipboard. Supplying the
            # callbacks would mean inventing behaviour that exists to render a
            # preview, which is worse than the default it is allowed to use.
            $enclosing = Get-EnclosingCallable $site.Lines $lineIndex
            if ($enclosing -match '^Preview') { continue }

            $openColumn = $match.Index - ($upTo.LastIndexOf("`n") + 1) + $screenName.Length
            $argText = Get-ArgumentText $site.Lines $lineIndex $openColumn
            $passed = @()
            foreach ($arg in (Split-Arguments $argText)) {
                $name = Get-ArgumentName $arg
                if ($name -and -not $passed.Contains($name)) { $passed += $name }
            }
            $calls++

            foreach ($param in $screens[$screenName].Params) {
                if ($param.Name -eq 'modifier' -or $param.Name -eq 'viewModel' -or $param.Name -eq 'state') { continue }
                if ($passed -contains $param.Name) { continue }

                $key = "$screenName.$($param.Name)"
                if ($exempt.ContainsKey($key)) { continue }

                if (-not $param.HasDefault) {
                    $problems.Add(
                        "$($site.Label):$($lineIndex + 1): $screenName omits '$($param.Name)' and it has no default. " +
                        "The screen cannot be constructed as written.")
                } elseif ($param.IsCallback) {
                    $problems.Add(
                        "$($site.Label):$($lineIndex + 1): $screenName omits '$($param.Name)'. " +
                        "It is a callback with a default, so the screen renders and the action does nothing.")
                } elseif ($param.IsEmptyDefault) {
                    $problems.Add(
                        "$($site.Label):$($lineIndex + 1): $screenName omits '$($param.Name)'. " +
                        "Its default is empty, so the feature it belongs to is off and nothing says so.")
                }
            }
        }
    }
}

if ($calls -eq 0) {
    $problems.Add('no screen call was found anywhere, which means this check did not run')
}

# -------------------------------------------------------------------- rule 4 ---
#
# A callback a composable accepts and never invokes.
#
# Rules 1 to 3 catch a callback that never reaches a screen. They cannot catch the
# other half, and the other half happened: `HistoryContent` was handed `onToggleArchive`
# at every call site, the parameter list was complete, and the body never called it —
# so the row had five actions and archiving was not among them. The evidence was three
# string resources with zero references, found by hand.
#
# The rule is narrow on purpose: the parameter must be *invoked*, not merely mentioned.
# Handing a callback on to a child composable is plumbing, and a file full of plumbing
# with no invocation anywhere is exactly what this is looking for. A parameter that is
# mentioned and never called is still counted as unused, because appearing in an
# argument list is not a caller.
#
# No exemption list. A composable that accepts a callback and cannot call it is a
# declaration that is wrong, not a default that is being leaned on -- the fix is to
# delete the parameter, which is a better outcome than an entry here.

# How many times $name is read as an identifier in $body. `$name`, `name(`, and
# `name,` all count; `onOpenAll` does not count as a use of `onOpen`.
function Get-NameUses([string] $body, [string] $name) {
    $pattern = '(?<![\w$])' + [regex]::Escape($name) + '(?!\w)'
    return ([regex]::Matches($body, $pattern)).Count
}

$callbacksChecked = 0
foreach ($screenName in $screens.Keys) {
    $screen = $screens[$screenName]
    if ($screen.BodyStart -lt 0) { continue }
    # A declaration with no body is a declaration this rule cannot judge, and guessing
    # past the end of a file is how a check starts reporting on the wrong function.
    if ($screen.Label -match '/(?:Preview|Sample)') { continue }

    $fileText = [System.IO.File]::ReadAllText($screen.Path)
    $body = $fileText.Substring($screen.BodyStart)

    foreach ($param in $screen.Params) {
        if (-not $param.IsCallback) { continue }
        $callbacksChecked++
        if ((Get-NameUses $body $param.Name) -gt 0) { continue }
        $problems.Add(
            "$($screen.Label): $screenName declares '$($param.Name)' and its body never " +
            "invokes it. The screen accepts the action and has no way to ask for it, so " +
            "whatever offers it is missing.")
    }
}

if ($callbacksChecked -eq 0) {
    $problems.Add('no callback parameter was read anywhere, which means this rule did not run')
}

# ------------------------------------------------------------------- report ---

if ($problems.Count -gt 0) {
    Write-Output 'UI WIRING IS INCOMPLETE'
    Write-Output ''
    foreach ($problem in $problems) { Write-Output "  - $problem" }
    Write-Output ''
    Write-Output 'A screen parameter nothing passes is a feature nobody can use, or one'
    Write-Output 'that is silently off. Pass it at the call site, or delete the parameter'
    Write-Output 'and the copy that needs it.'
    Write-Output ''
    Write-Output 'If a default is genuinely intended, add the screen and parameter to'
    Write-Output '$exempt with the reason.'
    exit 1
}

Write-Output "CLEAN - every screen call passes each callback and every non-empty default it declares."
exit 0
