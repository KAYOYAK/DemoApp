<#
    Checks that every screen callback is invoked somewhere in the chain it travels.

    ## Why this exists

    The archive action on the history screen had no button. `HistoryViewModel.toggleArchive`
    existed, `HistoryScreen` declared `onToggleArchive`, `HistoryContent` forwarded it to
    `HistoryCard`, and `HistoryCard`'s `FlowRow` listed five actions without it. Every link
    was present except the one that would call it, and the app shipped a conversation list
    that could not be archived.

    `check-ui-wiring.ps1` catches a callback that is never *passed*. It passed all of this
    happily, because the callback was passed everywhere. The defect is one step further
    along, and nothing looked there.

    ## The rule

    A callback parameter is live when, in both directions:

    * **downward** -- the declaration either calls it in its body, or hands it to a
      declaration that is itself live for it, and every hand-off along the way is live; and
    * **upward** -- every call site that supplies this declaration with a value supplies a
      live one, unless this declaration is a screen root, whose own callers are the
      navigation graph and live outside the files this check reads.

    Both are required, and the upward half is not a refinement. A walk that only goes down
    from the declaration answers yes for the archive defect, because the declaration's own
    body does contain `onToggleArchive(row.conversation.id)`. What changed in the defect was
    the value arriving from the screen: `onToggleArchive = {}`.

    ## What counts as an invocation, and what does not

    An invocation of a callback is the name *not* followed by `=`, in a body: `TextButton(onClick = onToggleArchive)`,
    `onClick = { onToggleArchive(id) }`, `onToggleArchive(id)`. A hand-off is `name = <value>`
    and is judged by its value, never by the name appearing.

    The first version of this rule was "a `@Composable` that declares an `on*` parameter and
    never mentions it again". It is wrong, and the injected defect disproves it on the first
    run: the broken `HistoryContent` still mentioned `onToggleArchive` eight times, because
    it was handing it to `HistoryCard` as a named argument. A mention test answers yes for
    the intact and the broken case alike.

    ## What it deliberately does not do

    It does not read the navigation graph, so a screen that is itself unreachable is out of
    scope. It does not check that a callback does anything when called -- only that a control
    asks for it.

    ## Verification

    Four injections of the real defect into `ui/history/HistoryScreen.kt` (under
    `app/src/main/java`, with the package directory left out because it moves), each one
    expected to report, and a clean tree expected to report nothing:

    | injection | reported |
    | --- | --- |
    | `onToggleArchive = { onToggleArchive(row.conversation.id) },` -> `{},` at both forward sites | `HistoryScreen.kt:189 HistoryContent - onToggleArchive`, `:481 HistoryCard - onToggleArchive` |
    | `TextButton(onClick = onToggleArchive) {` -> `TextButton(onClick = {}) {` | both of the above |
    | `onToggleArchive = viewModel::toggleArchive,` (the screen's own argument) -> `{},` | `HistoryScreen.kt:189 HistoryContent - onToggleArchive` |
    | the card's invocation replaced by an unrelated assignment | `HistoryScreen.kt:481 HistoryCard - onExport` |

    Seven separate mistakes had to be measured out of this gate before it reported anything,
    and the two worth carrying forward are in `scripts/compose-graph.ps1` -- `$Matches` being
    clobbered by an `-and` chain, and a predicate's own `Write-Output` becoming part of its
    return value. **A rule that has not been observed to fail is indistinguishable from a
    disabled one**, which is why the table above is the evidence and the prose is not.
#>

$ErrorActionPreference = 'Stop'
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
. (Join-Path $scriptDir 'compose-graph.ps1')

$ProjectDir = if ($env:DEMOAPP_DIR) { $env:DEMOAPP_DIR } else { Split-Path -Parent $scriptDir }
$root = $ProjectDir
$sourceRoot = Join-Path $root 'app/src/main/java'

$problems = [System.Collections.Generic.List[string]]::new()

$nodes = @(Get-Declarations $sourceRoot)
if ($nodes.Count -eq 0) {
    Write-Output "SOURCE NOT FOUND under $sourceRoot"
    exit 2
}

# A screen with no callback parameters is a root too. It is the call site that decides
# what each screen below it is handed, so leaving those out skipped the shortest
# defect of all: the screen itself passing `{}`.
$roots = @($nodes | Where-Object { $_.Name -match 'Screen$' })
$callbackRoots = @($roots | Where-Object { $_.Params.Count -gt 0 })
if ($callbackRoots.Count -eq 0) {
    Write-Output 'no screen composable declares a callback, which means this check did not run'
    exit 1
}

$inScope = @(Get-ReachableDeclarations $nodes $roots)

# ------------------------------------------------------------------ the rule ---

# Whether this text invokes the name: a mention that is not a named-argument head.
#
# The mention's own match text is never consulted. A regex match is the identifier
# alone -- it does not contain the `(` or the `=` that follows -- so a test written
# against the match answers the same way for `onToggleArchive(row.id)` and for
# `onToggleArchive = {}`. Reading the source at the offset is the whole difference.
function Test-Invoked([string] $text, [string] $name) {
    foreach ($m in (Get-Mentions $text $name)) {
        if ($m.After -match '^\s*=[^=]') { continue }
        return $true
    }
    return $false
}

# What a value handed in as `name = <value>` will do: an empty lambda or a literal
# calls nothing, the bare name means "whatever this declaration does with its own
# parameter", and anything else -- a lambda with a body in it, `viewModel::method` --
# is an implementation and counts.
function Test-ValueLive($holder, [string] $text, [string] $name) {
    $offset = Get-NamedArgumentOffset $text $name
    if ($offset -lt 0) { return $false }
    $eq = $text.IndexOf('=', $offset)
    $rest = $text.Substring($eq + 1).TrimStart()

    if ($rest.StartsWith('{')) {
        $value = $rest.Substring(0, (Get-BalancedLength $rest))
    }
    else {
        $value = ($rest -replace "`n.*$", '').TrimEnd(' ', ',')
    }

    if ($value -eq '') { return $false }
    if ($value -match '^\{[\s{}]*\}$') { return $false }
    if ($value -eq $name) { return (Test-Invoked $holder.Body $name) }
    return $true
}

# What this declaration hands the value on to: the (declaration, parameter) pairs it
# passes it to, kept only when the value handed on itself calls the callback.
#
# `onToggleArchive = {}` hands on nothing. `onToggleArchive = { onToggleArchive(id) }`
# hands on a lambda that calls it. `onOpenHistory = onOpenHistory` hands on the value
# itself, and so counts exactly when this body calls its own parameter.
function Get-ForwardTargets($node, [string] $name) {
    $targets = @()
    foreach ($call in $node.Calls) {
        if ((Get-NamedArgumentOffset $call.Text $name) -lt 0) { continue }
        if (-not (Test-ValueLive $node $call.Text $name)) { continue }
        foreach ($child in $nodes) {
            # A declaration of the same name in another file is a different target to
            # look at, so both are kept.
            if ($child.Name -eq $call.Name) { $targets += [pscustomobject]@{ Node = $child; Name = $name } }
        }
    }
    return $targets
}

# The call sites that hand this declaration a value for this parameter: a file-local
# call whose named argument is the parameter name.
function Get-SourceCallSites($node, [string] $name) {
    $out = @()
    foreach ($other in $inScope) {
        if ($other.Label -ne $node.Label) { continue }
        if ($other.Name -eq $node.Name) { continue }
        foreach ($call in $other.Calls) {
            if ($call.Name -ne $node.Name) { continue }
            if ((Get-NamedArgumentOffset $call.Text $name) -lt 0) { continue }
            $out += [pscustomobject]@{ Node = $other; Name = $name; Text = $call.Text }
        }
    }
    return $out
}

# Live means: the declaration that receives this value calls it, every hand-off along
# the way carries something that calls it, and what arrives from above is live too.
#
# `$script:reaching` holds settled answers and `$script:reachingWalk` the keys being
# asked right now, so a cycle cannot recurse forever. The answer is recorded only once
# it has settled: caching the provisional value from before the recursion finished
# would make a temporary false permanent, and one path would poison the node for every
# later caller.
$script:reaching = @{}
$script:reachingWalk = [System.Collections.Generic.HashSet[string]]::new()

function Test-Reachable($node, [string] $name) {
    $key = "$($node.Label)|$($node.Line)|$name"
    if ($script:reaching.ContainsKey($key)) { return $script:reaching[$key] }
    if ($script:reachingWalk.Contains($key)) { return $false }
    [void]$script:reachingWalk.Add($key)

    $calls = Test-Invoked $node.Body $name
    $forwards = @(Get-ForwardTargets $node $name)

    # One dead hand-off makes the whole path dead, because that is the path the
    # screen's action has to travel.
    $through = $true
    foreach ($target in $forwards) {
        if (-not (Test-Reachable $target.Node $target.Name)) { $through = $false; break }
    }

    # A screen root's own callers are the navigation graph, outside these files.
    $incoming = $true
    if ($node.Name -notmatch 'Screen$') {
        foreach ($src in (Get-SourceCallSites $node $name)) {
            if (-not (Test-ValueLive $src.Node $src.Text $name)) { $incoming = $false; break }
        }
    }

    $result = ($calls -or $forwards.Count -gt 0) -and $through -and $incoming

    [void]$script:reachingWalk.Remove($key)
    $script:reaching[$key] = $result
    return $result
}

# ------------------------------------------------------------------- report ---

$checked = 0
foreach ($node in $inScope) {
    foreach ($name in $node.Params) {
        $checked++
        if (Test-Reachable $node $name) { continue }
        $problems.Add(
            "$($node.Label):$($node.Line): $($node.Name) accepts '$name' and nothing in the " +
            "chain invokes it. The screen is handed the action and no control asks for it, " +
            "so the feature it belongs to has no way in.")
    }
}

if ($checked -eq 0) {
    $problems.Add('no callback parameter was read anywhere, which means this rule did not run')
}

if ($problems.Count -gt 0) {
    Write-Output 'CALLBACKS THAT CANNOT BE INVOKED'
    Write-Output ''
    foreach ($problem in $problems) { Write-Output "  - $problem" }
    Write-Output ''
    Write-Output 'Either call it somewhere in the body, or delete the parameter and the'
    Write-Output 'copy the control would have needed.'
    exit 1
}

Write-Output "CLEAN - all $checked screen callbacks reach an invocation through the graph ($($inScope.Count) composables in scope)."
exit 0
