#requires -Version 7
<#
    Checks that copy people read is in the app's language and lives in one place.

    The rule this enforces is not "write Chinese in Kotlin" -- it is stricter and
    simpler: no prose in Kotlin at all. Every word a person reads comes from
    app/src/main/res/values/strings.xml, and everything in Kotlin that reaches a
    screen goes through UiMessage, whose type makes a literal impossible.

    Why a gate, when the type already stops most of it: the type covers messages
    that flow through state. It does not cover a Text("Settings") written directly
    in a composable, nor a formatter that returns a sentence. Those are how this
    goes wrong -- one English label on one screen, which nobody notices until a
    user does.

    Exemptions are listed rather than matched by a rule, because each one is a
    decision. Adding to the list should be deliberate.
#>

$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$sourceRoot = Join-Path $root 'app/src/main/java'
$problems = [System.Collections.Generic.List[string]]::new()

# Files whose remaining strings are for developers rather than users. Named by
# full path with the reason, rather than skipped by a rule: a blanket "skip this
# directory" would also skip the user-facing part of it, which is the mistake this
# gate exists to catch.
$developerFacing = @(
    'com/example/demoapp/data/chat/ChatStore.kt',
    'com/example/demoapp/model/ModelStore.kt',
    'com/example/demoapp/model/ModelDownloader.kt',
    'com/example/demoapp/model/ModelImporter.kt',
    'com/example/demoapp/AppContainer.kt',
    # Matching against an internal message to choose which resource to show. Read
    # by this app's own code, not by a person.
    'com/example/demoapp/model/ModelDownloadCoordinator.kt',
    # The same thing one layer down: the runtime reports failures as classes and
    # messages, and the classification reads those to choose which of the
    # load_fail_* resources a person sees. The patterns are never rendered; the
    # matched text is kept as detail for a bug report and shown only if asked for.
    'com/example/demoapp/inference/LoadFailure.kt',
    # A lookup table of language keywords, matched against a code block. The one
    # entry that reads as an English word is `None`, which is Python. Nothing here
    # is rendered; the table decides what colour a word is.
    'com/example/demoapp/ui/chat/SyntaxHighlighter.kt',
    # The validate() problem list is thrown at build time and never rendered.
    'com/example/demoapp/model/ModelCatalogue.kt',
    # The runtime's own status codes and library names, kept verbatim so that
    # searching for them finds something.
    'com/example/demoapp/inference/LiteRtLlmEngine.kt'
)

# A whole quoted literal. The whole thing, not the first two words of it: matching
# a prefix let `"yyyy-MM-dd HH:mm"` through as prose because the colon it should
# have been judged on was past the end of the match.
$literal = [regex]'"([^"\r\n]*)"'

# Two or more ASCII words in a row, which is what prose looks like.
$prose = [regex]'^[A-Za-z][A-Za-z''-]*(?: [A-Za-z][A-Za-z''-]*)+$'

# Or one capitalised word. "Appearance" is a label somebody forgot to move; "CPU"
# and "GB" are not words and never will be. The distinction is the shape: a
# capital followed by lowercase letters, at least four of them.
$singleWord = [regex]'^[A-Z][a-z]{3,}$'

# A sequence of CJK, which is what this app's copy actually looks like.
#
# The two shapes above are ASCII, and that was the whole of this gate's reach until
# now: `$prose` is "two or more ASCII words" and `$singleWord` is "a capital and three
# lowercase letters". This app is written entirely in Chinese, so the gate could catch
# an English label somebody forgot to move and could not see a Chinese one at all --
# and the Chinese one is the copy that actually reaches a screen.
#
# Found by trying to break it: a Chinese sentence was inserted into `Formatting.kt` as
# a plain string literal, the gate reported CLEAN, and the check stayed green while
# user-visible copy sat in a Kotlin file. Every other gate in this repository has been
# observed to fail; this one had only been observed to pass.
#
# Written with escapes rather than the characters themselves, and that is a decision
# rather than a style: this file is PowerShell, and a PowerShell script's own text is
# read by whatever encoding the host guesses. Putting Chinese in it made the parser
# fail on the line containing it -- two lines of identical length, identical code
# units and identical structure, one accepted and one not, which is what a source file
# being decoded wrongly looks like from the inside. Escapes keep the file ASCII and the
# characters unambiguous however it is read.
$cjk = [regex]'[\u3040-\u30ff\u4e00-\u9fff\uf900-\ufaff]'


# Literals that are Chinese and are not copy, as UTF-8 bytes in hex.
#
# Bytes rather than the characters. This file is PowerShell, whose parser reads its
# own text in whatever encoding the host guesses, and putting the characters into a
# list is what produced the parse failures that led here. Hex digits are ASCII
# whatever happens, and comparing bytes is exact.
#
# Generated from the source rather than typed, after four hand-written entries
# turned out wrong -- a truncated UTF-8 sequence for one character, a missing byte
# for a $\ that begins an interpolation. A list that has to be exactly right cannot
# be maintained by hand.
#
# ``ModelCatalogue.kt`` is not here because its whole file is in ``$developerFacing``:
# its Chinese literals are model display names, and the file also holds the
# ``validate()`` problem list, which is thrown at build time and never rendered.
# Literals that are Chinese and are not copy, as UTF-8 bytes in hex.
#
# Bytes rather than the characters, and generated from the source rather than typed.
# Both halves of that are load-bearing. This file is PowerShell, whose parser reads
# its own text in whatever encoding the host guesses, so putting the characters into a
# list is what produced the parse failures that led here -- and four hand-written hex
# entries turned out wrong, in the shape of a truncated UTF-8 sequence and a dropped
# byte. A list that has to be exactly right cannot be maintained by hand.
#
# To change it: run the extraction again and replace this block. Do not edit an entry.
$cjkAllowedHex = @(

    # The initial title of a conversation, and the fallback when a blank first message
    # is derived into one. The one entry here that is a compromise rather than a
    # decision: the value is persisted into the conversation file, so replacing it
    # with a resource id means either a stored id that no longer resolves or a
    # migration. It is user-visible, it is Chinese, and moving it is a change to the
    # on-disk format -- worth doing deliberately, not as a side effect of teaching a
    # gate to see.
    'E696B0E5AFB9E8AF9D'

    # The default system prompt, in SettingsStore and again in LiteRtLlmEngine. Sent to
    # the model, never rendered: they are fields of the request.
    'E4BDA0E5AE8CE585A8E8BF90E8A18CE59CA8E794A8E688B7E887AAE5B7B1E79A84E6898BE69CBAE4B88AEFBC8CE6B2A1E69C89E88194E7BD91E38082'
    'E4BDA0E698AFE4B880E4B8AAE4B990E4BA8EE58AA9E4BABAE79A84E58AA9E6898BE38082'
    'E5A682E69E9CE4B88DE79FA5E98193E7AD94E6A188EFBC8CE5B0B1E79BB4E8AFB4E4B88DE79FA5E98193EFBC8CE4B88DE8A681E7BC96E980A0E38082'
    'E8AFB7E79BB4E68EA5E38081E7AE80E79FADE59CB0E59B9EE7AD94E794A8E688B7E79A84E997AEE9A298E38082'

    # Redaction markers. They stand where a value was in a diagnostic report the user
    # chose to send. Not sentences to translate but a convention: the marker says a
    # value was removed, and translating it would make one report read differently per
    # language.
    '2431203CE5B7B2E99A90E58EBB3E'
    '24313A2F2F3CE5B7B2E99A90E58EBB3E'
    '2A2A2A403CE5B7B2E99A90E58EBB3E'
    '3CE59CB0E59D80E5B7B2E99A90E58EBB3E'
    '3CE5B7B2E99A90E58EBB3E'
    '3CE8B7AFE5BE84E5B7B2E99A90E58EBB3E'

    # The two lines a crash log is written with. The log is a file, and the function
    # that writes it has no Context to resolve a string with.
    'E5BC82E5B8B820247B6572726F723A3A636C6173732E6A6176612E6E616D657D'
    'E697B6E997B420247374616D70'

    # Preview sample data. @Preview is read by the IDE and never executes on a device,
    # so none of this is rendered by the app -- and the point of a preview is to show a
    # screen that looks real, which a placeholder cannot do. Listed rather than skipped
    # by a rule so the next reader can disagree with the call.
    'E4B889E58E9FE889B2'
    'E585B3E4BA8EE982A3E4BBB6E4BA8BE79A84E4B880E4B8AAE59B9EE7AD94'
    'E585B3E4BA8EE982A3E4BBB6E4BA8BE79A84E4B880E4B8AAE997AEE9A298'
    'E5868DE8A7A3E9878AE4B880E4B88BE5A4A9E7A9BAE4B8BAE4BB80E4B988E698AFE8939DE79A84E38082'
    'E59BA0E4B8BAE6B3A2E995BFE8BE83E79FADE79A84'
    'E794A8204B6F746C696E20E7BB99E58897E8A1A8E68E92E5BA8F'
    'E8AFB4E4B880E4B8AAE9A29CE889B2EFBC8CE4B880E4B8AAE8AF8DE5B0B1E8A18CE38082'
)

# The UTF-8 bytes of a string, as an uppercase hex string, for the comparison above.
function HexOf([string]$s) {
    -join ([System.Text.Encoding]::UTF8.GetBytes($s) | ForEach-Object { '{0:X2}' -f $_ })
}

# A shape rather than a sentence: a colon or a percent inside the literal means a
# date format or a printf template, and there is nothing in it to translate.
#
# Except when there is. `"%.1f tok/s"` was skipped by this rule for as long as the
# rule existed -- "tok/s" is a word a person reads, it was written in
# `Formatting.kt`, in `ChatScreen` and in `ConversationBackup`, and this gate waved
# all three through. The distinction the rule was reaching for is not "is there a
# percent" but "is there a word beside it".
$formatPattern = [regex]'[:%]'

# Only a literal that actually carries a printf directive is judged this way. A
# colon means a diagnostic or a date, and an earlier attempt to judge those too
# reported twenty-six false positives -- `${labels.model}: ${facts.modelId}` and
# `\b(https?)://\S+` among them -- so the narrow reading is the one that works.
$printfTemplate = [regex]'%\d*\.?\d*[a-zA-Z%]'

# Unit symbols, which are the same word in every language this app could ever be
# written in. A list rather than a shape, because "is it a unit" is a fact about the
# string and not about how it is spelled. A new entry is a decision, in the same
# spirit as the exemption list above.
$unitSymbols = @('B', 'KB', 'MB', 'GB', 'TB', 'KiB', 'MiB', 'GiB', 'kB', 's', 'ms', 'ns', 'min', 'h')

$files = Get-ChildItem $sourceRoot -Recurse -Filter '*.kt' -File
if ($files.Count -eq 0) {
    Write-Output 'No Kotlin sources found, so this check did not run.'
    exit 2
}

$referenced = [System.Collections.Generic.HashSet[string]]::new()

foreach ($file in $files) {
    $relative = $file.FullName.Substring($sourceRoot.Length + 1).Replace('\', '/')
    $text = Get-Content $file.FullName -Raw

    foreach ($m in [regex]::Matches($text, 'R\.string\.(\w+)')) {
        [void]$referenced.Add($m.Groups[1].Value)
    }

    if ($developerFacing -contains $relative) { continue }

    $lines = Get-Content $file.FullName
    for ($i = 0; $i -lt $lines.Count; $i++) {
        $line = $lines[$i]
        # Comments explain the code to a developer, and are English on purpose.
        if ($line -match '^\s*(//|\*|/\*)') { continue }

        foreach ($found in $literal.Matches($line)) {
            $value = $found.Groups[1].Value

            # A colon means a diagnostic or a date: not copy.
            if ($formatPattern.IsMatch($value) -and -not $printfTemplate.IsMatch($value)) { continue }

            if ($printfTemplate.IsMatch($value)) {
                # Whatever is left once the directives are gone. Judge the words in
                # it by the shape already used for the rest of the file: two or more
                # ASCII words in a row, or one capitalised word of four letters or
                # more. `%.0f MB` is a number and a unit symbol; `%.1f tok/s` is a
                # number and a word, and "tok/s" is neither of those shapes -- so the
                # symbol that is not a unit is named explicitly.
                $rest = $printfTemplate.Replace($value, ' ').Trim()
                $words = [regex]::Matches($rest, '[A-Za-z]{2,}') |
                    ForEach-Object { $_.Value } |
                    Where-Object { $unitSymbols -notcontains $_ } |
                    Where-Object { $_.Length -ge 2 }

                if ($words.Count -eq 0) { continue }
                $problems.Add("${relative}:$($i + 1) has a word inside a format string: $value")
                continue
            }

            if (-not ($prose.IsMatch($value) -or $singleWord.IsMatch($value) -or $cjk.IsMatch($value))) { continue }
            if ($cjkAllowedHex -contains (HexOf $value)) { continue }
            $problems.Add("${relative}:$($i + 1) has prose that will reach a screen: $value")
        }
    }
}

if ($referenced.Count -eq 0) {
    $problems.Add('no R.string reference was found anywhere, which means this check did not run')
}

$stringsFile = Join-Path $root 'app/src/main/res/values/strings.xml'
if (-not (Test-Path $stringsFile)) {
    $problems.Add('strings.xml is missing, so no copy exists at all')
}

if ($problems.Count -gt 0) {
    Write-Output 'UI COPY IS NOT WHERE IT SHOULD BE'
    Write-Output ''
    foreach ($problem in $problems) { Write-Output "  - $problem" }
    Write-Output ''
    Write-Output 'Every word a person reads belongs in app/src/main/res/values/strings.xml,'
    Write-Output 'and every message that reaches a screen goes through UiMessage.'
    exit 1
}

Write-Output 'CLEAN - no prose in Kotlin, and every message people read comes from a resource.'
exit 0
