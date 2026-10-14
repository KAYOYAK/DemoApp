#requires -Version 7
<#
    Kotlin source extraction shared by the gates that have to answer a question
    about a declaration rather than about a file.

    ## Why this is its own file

    `check-callback-reachability.ps1` spent its whole life finding out how to read a
    Kotlin declaration correctly, and seven separate mistakes had to be measured out
    of it -- a body read as everything after the parameter list, a match tested
    instead of the source at its offset, `-and` clobbering `$Matches`, a predicate
    whose own `Write-Output` became part of its return value, and so on. Each one
    looked correct while answering a different question.

    A second gate needs the same reading. Copying it would copy the seven mistakes
    back in, because the copies would be written by the same hand that had to be
    corrected seven times. Hence one file, sourced by both.

    ## What it does not do

    It does not know what a gate is checking. Nothing here reports, exits, or has an
    opinion about defects; every function returns a value.

    ## The one thing every consumer has to remember

    **Stop yourself before putting `Write-Output` inside anything that returns a
    value.** In PowerShell everything a function writes to the output stream becomes
    part of its return value, so a debug print turns `if (Test-Foo ...)` into a test
    of a non-empty array, which is always true. That is not a style rule; it silently
    discarded every finding of one gate for one whole run, and it looked like the
    rule working.
#>

# The files a caller wants as source, in a stable order.
function Get-KotlinFiles([string] $sourceRoot) {
    if (-not (Test-Path $sourceRoot)) { return @() }
    return @(Get-ChildItem $sourceRoot -Recurse -Filter '*.kt' -File | Sort-Object FullName)
}

# File text with line endings normalised, so every offset arithmetic below is
# against the same byte sequence the line index was built from.
function Get-Text([string] $path) {
    return ([System.IO.File]::ReadAllText($path) -replace "`r`n", "`n")
}

# The offset of the '}' closing the block that starts at or after $from, or $from
# itself when the declaration has no block (an expression body).
#
# Not optional: a body read as "everything after the parameter list" contains the
# declarations further down the same file, so an invocation inside one of them is
# attributed to this one. That made a callback nothing forwards to answer "invoked".
function Get-BodyEnd([string] $text, [int] $from) {
    $start = $text.IndexOf('{', $from)
    if ($start -lt 0) { return $from }
    $depth = 0
    for ($c = $start; $c -lt $text.Length; $c++) {
        $ch = $text[$c]
        if ($ch -eq '{' -or $ch -eq '(') { $depth++ }
        elseif ($ch -eq '}' -or $ch -eq ')') {
            $depth--
            if ($depth -eq 0) { return $c }
        }
    }
    return $text.Length - 1
}

# The offset of the ')' matching the '(' at $open, or $null.
function Get-CloseOf([string] $text, [int] $open) {
    $depth = 0
    for ($c = $open; $c -lt $text.Length; $c++) {
        $ch = $text[$c]
        if ($ch -eq '(') { $depth++ }
        elseif ($ch -eq ')') {
            $depth--
            if ($depth -eq 0) { return $c }
        }
    }
    return $null
}

# The length of the balanced `{...}` or `(...)` run that starts this text.
function Get-BalancedLength([string] $text) {
    $open = $text[0]
    $close = if ($open -eq '{') { '}' } else { ')' }
    $depth = 0
    for ($i = 0; $i -lt $text.Length; $i++) {
        $ch = $text[$i]
        if ($ch -eq $open) { $depth++ }
        elseif ($ch -eq $close) { $depth--; if ($depth -eq 0) { return $i + 1 } }
    }
    return $text.Length
}

# Parameter names out of a declaration's parameter text, at top-level commas only.
#
# Angle brackets are NOT counted as depth. They were, and it broke the parser: the
# `>` in the function type `(String) -> Unit` took the depth to -1, so every comma
# after the second parameter stopped being a separator and a seventeen-parameter
# composable was read as having two. Generics never contain a top-level parameter
# comma, so not counting them costs nothing.
function Get-ParameterNames([string] $paramText) {
    $names = [System.Collections.Generic.List[string]]::new()
    foreach ($part in (Split-Parameters $paramText)) {
        $trimmed = ($part -replace "`n", ' ').Trim()
        if ($trimmed -match '^(?:@\w+\s+)?(\w+)\s*:\s*(.+)$') {
            # The two groups are copied out before either is tested. Testing them
            # inside one `-and` chain lets each `-match` overwrite `$Matches`, so the
            # second test reads a group from its own match rather than from this one.
            # That made every parameter list come back empty and the gate report that
            # no screen declares a callback.
            $pn = $Matches[1]
            $pt = $Matches[2]
            if ($pn -match '^on[A-Z]' -and $pt -match '->\s*Unit') { $names.Add($pn) }
        }
    }
    return $names
}

# Split a parameter text at top-level commas. Shared by the two readers above and
# by any caller that wants the raw parts rather than names.
function Split-Parameters([string] $paramText) {
    $depth = 0
    $current = [System.Text.StringBuilder]::new()
    $parts = [System.Collections.Generic.List[string]]::new()
    foreach ($ch in $paramText.ToCharArray()) {
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

# The same parameter list, with each parameter's declared name and type, for gates
# that have to ask what a parameter is rather than only what it is called.
function Get-Parameters([string] $paramText) {
    $out = @()
    foreach ($part in (Split-Parameters $paramText)) {
        $trimmed = ($part -replace "`n", ' ').Trim()
        if ($trimmed -match '^(?:@\w+\s+)?(\w+)\s*:\s*(.+)$') {
            $pn = $Matches[1]
            $pt = $Matches[2]
            $out += [pscustomobject]@{ Name = $pn; Type = $pt.Trim() }
        }
    }
    return $out
}

# Every declaration in the tree, with its parameter text and its body.
#
# Two passes, and the order is forced: a body has to be scanned for calls to
# declarations further down the same file, so every name has to be known before any
# body is read.
function Get-Declarations([string] $sourceRoot) {
    $files = Get-KotlinFiles $sourceRoot
    $declared = [System.Collections.Generic.HashSet[string]]::new()
    $pass1 = @()
    foreach ($file in $files) {
        $text = Get-Text $file.FullName
        $lines = $text.Split("`n")
        for ($i = 0; $i -lt $lines.Count; $i++) {
            if ($lines[$i] -notmatch '^\s*(?:@\w+\s+)*(?:internal\s+|private\s+|public\s+)?fun\s+([A-Z]\w*)\s*\(') { continue }
            $name = $Matches[1]
            [void]$declared.Add($name)

            $open = $lines[$i].IndexOf('(', $lines[$i].IndexOf($name))
            if ($open -lt 0) { continue }
            $absoluteOpen = 0
            for ($n = 0; $n -lt $i; $n++) { $absoluteOpen += $lines[$n].Length + 1 }
            $absoluteOpen += $open
            $close = Get-CloseOf $text $absoluteOpen
            if ($null -eq $close) { continue }

            $pass1 += [pscustomobject]@{
                Name = $name; Path = $file.FullName; Line = $i
                Open = $absoluteOpen; Close = $close
            }
        }
    }

    # A call site is a declared name followed by '('. The name list is the whole
    # point: matching any identifier would read `Column(` as a call to a screen.
    $callPattern = '(?<![\w.])(' + (($declared | ForEach-Object { [regex]::Escape($_) }) -join '|') + ')\s*\('

    $nodes = @()
    foreach ($d in $pass1) {
        $text = Get-Text $d.Path
        $paramText = $text.Substring($d.Open + 1, $d.Close - $d.Open - 1)
        $bodyEnd = Get-BodyEnd $text $d.Close
        $body = $text.Substring($d.Close + 1, [Math]::Max(0, $bodyEnd - $d.Close - 1))

        $calls = @()
        foreach ($m in [regex]::Matches($body, $callPattern)) {
            $argOpen = $m.Index + $m.Length - 1
            $argClose = Get-CloseOf $body $argOpen
            if ($null -eq $argClose) { continue }
            $spread = $argClose - $argOpen - 1
            $calls += [pscustomobject]@{
                Name      = $m.Groups[1].Value
                Text      = if ($spread -gt 0) { $body.Substring($argOpen + 1, $spread) } else { '' }
                SpanStart = $argOpen
                SpanEnd   = $argClose
            }
        }

        $nodes += [pscustomobject]@{
            Name    = $d.Name
            Label   = $d.Path.Replace("$sourceRoot/", '').Replace('\', '/')
            Path    = $d.Path
            Line    = $d.Line + 1
            Params  = @(Get-ParameterNames $paramText)
            Typed   = @(Get-Parameters $paramText)
            Body    = $body
            Calls   = $calls
            Callees = @($calls | ForEach-Object { $_.Name } | Sort-Object -Unique)
        }
    }
    return $nodes
}

# The declarations reachable from $roots through the call graph, roots included.
# Same-name declarations in other files are all reached, because the call site does
# not say which one it means.
function Get-ReachableDeclarations($nodes, $roots) {
    $scope = @()
    $seen = [System.Collections.Generic.HashSet[string]]::new()
    $queue = [System.Collections.Queue]::new()
    foreach ($r in $roots) { $queue.Enqueue($r) }
    while ($queue.Count -gt 0) {
        $node = $queue.Dequeue()
        if (-not $seen.Add("$($node.Label)|$($node.Line)")) { continue }
        $scope += $node
        foreach ($calleeName in $node.Callees) {
            foreach ($child in $nodes) {
                if ($child.Name -eq $calleeName) { $queue.Enqueue($child) }
            }
        }
    }
    return $scope
}

# Every mention of a name in a text, with the text that follows the mention.
#
# Consumers must read `After` rather than the match text. A regex match is the
# identifier alone -- it never contains the `(` or `=` that follows it -- so a test
# written against the match answers the same way for every mention, including the
# broken one. That single mistake made the first version of the callback gate return
# true for a parameter nothing invokes.
function Get-Mentions([string] $text, [string] $name) {
    $escapd = [regex]::Escape($name)
    foreach ($m in [regex]::Matches($text, '(?<![\w$])' + $escapd + '(?!\w)')) {
        [pscustomobject]@{ Index = $m.Index; After = $text.Substring($m.Index + $name.Length) }
    }
}

# The offset of the first `name =` in a text, or -1. `[^=]` after the `=` keeps
# `==` and `!=` out, so a comparison is not read as a named argument.
function Get-NamedArgumentOffset([string] $text, [string] $name) {
    $m = [regex]::Match($text, '(?<![\w$])' + [regex]::Escape($name) + '\s*=[^=]')
    if ($m.Success) { return $m.Index }
    return -1
}
