#requires -Version 7
<#
    Checks that every string in strings.xml is one something asks for.

    ## Why this exists

    `lint` reports unused resources, and it reported forty-eight of them: a wording
    revision that left the previous sentence behind, an errored form that was
    replaced by a shorter one, an earlier draft of the About screen's principles.
    None of it is broken. All of it is text somebody wrote on purpose, kept where
    the app's copy lives, and now describes nothing the app does.

    That matters more here than in an app where copy is decoration. This project's
    whole claim is that its promises are checked by scripts rather than stated --
    the About screen says so in as many words. A strings file that still holds
    sentences for features that were reworded away is a file where a promise can
    sit unread, and the next person to look will not be able to tell an abandoned
    sentence from a load-bearing one.

    ## What it checks

    Each `<string name="...">` must be named somewhere: in main Kotlin, in the
    manifest, or in an XML resource (`@string/...`). Anything named nowhere is
    reported.

    ## What it deliberately does not do

    It does not look at `androidTest` or `test`. A string used only by a test is a
    string no user reads, and letting tests excuse one would defeat the check --
    except that a test which asserts on user-visible copy is asserting on the
    resource, not on its own private copy, so there is nothing there to excuse.

    It does not try to decide whether a string is *reachable* from a screen, only
    whether anything names it. Reachability is `check-ui-wiring.ps1`'s job for
    callbacks and was never attempted for text.

    ## Adding an exemption

    Named by string, with the reason. Each one is a decision, in the same spirit as
    check-ui-copy's developer-facing list.
#>

$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$stringsFile = Join-Path $root 'app/src/main/res/values/strings.xml'

$problems = [System.Collections.Generic.List[string]]::new()

if (-not (Test-Path $stringsFile)) {
    Write-Host '  FAILED strings.xml not found' -ForegroundColor Red
    exit 1
}

# Strings kept on purpose although nothing names them yet.
#
# Empty today. When something lands here, say why: "planned for the next release"
# is exactly the excuse this gate exists to make visible rather than silent.
$exempt = @()

# The format-string checker, compiled on demand.
#
# Written in Java rather than in PowerShell because this is the one place where
# the answer has to match the runtime exactly: `Resources.getString` calls
# `java.util.Formatter`, and no reimplementation in another language is going to
# agree with it on the edges. `java` is not a new dependency -- `gradlew` requires
# it -- and the whole check is one compile and one run.
#
# It supplies a dummy argument per slot, typed from the conversion character. That
# detail matters: a version that handed every slot a String reported the perfectly
# correct `%1$d` as broken, which is a worse failure than the one being looked for.
$FMT_CHECK_JAVA = @'
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

public class FmtCheck {
    public static void main(String[] args) throws Exception {
        for (String line : Files.readAllLines(Paths.get(args[0]))) {
            String[] fields = line.split("\t", -1);
            if (fields.length < 2) continue;
            String name = fields[0];
            String body = fields[1];

            // What the positional indices ask for. The regex stops at a letter or a
            // `%` -- never at a digit -- so an index is only recorded when a real
            // conversion character follows it.
            //
            // A dangling `%` (`%2`, `%1。`, a trailing `%`) matches nothing at all
            // here, and that is deliberate: the *absence* of a match is the failure,
            // and `String.format` reports it as `UnknownFormatConversionException`.
            // Trying to detect it by re-parsing was worse -- two attempts at that
            // both reported correct strings as broken.
            Map<Integer, Character> wanted = new TreeMap<>();
            Matcher m = Pattern.compile("%(?:(\\d+)\\$)?[-#+ 0,(]*\\d*(?:\\.\\d+)?([a-zA-Z%])").matcher(body);
            int positional = 0;
            while (m.find()) {
                char conversion = m.group(2).charAt(0);
                if (conversion == '%' || conversion == 'n') continue;
                int index = m.group(1) != null ? Integer.parseInt(m.group(1)) : ++positional;
                wanted.put(index, conversion);
            }

            int highest = wanted.isEmpty() ? 0 : Collections.max(wanted.keySet());
            List<Object> supplied = new ArrayList<>();
            for (int i = 1; i <= highest; i++) {
                Character conversion = wanted.get(i);
                if (conversion == null) { supplied.add("x"); continue; }
                switch (Character.toLowerCase(conversion)) {
                    case 'd': case 'o': case 'x': supplied.add(1); break;
                    case 'e': case 'f': case 'g': case 'a': supplied.add(1.0d); break;
                    case 'c': supplied.add('x'); break;
                    default: supplied.add("x");
                }
            }

            try {
                String.format(body, supplied.toArray());
            } catch (Throwable thrown) {
                System.out.println(name + "\t" + body + "\t"
                    + thrown.getClass().getSimpleName() + ": " + thrown.getMessage());
                continue;
            }

            // ---------------------------------------------------------------
            // Whether the call sites pass enough arguments.
            //
            // The check above proves the *template* is valid. It cannot prove a
            // caller gives it enough, and both failures are the same failure at
            // runtime: `Resources.getString(id, args...)` raises, and it raises
            // inside composition, so the screen closes. A string that needs two
            // arguments and is formatted with one is the same crash as `%1。`.
            //
            // `chat_rate` was that case until this was written: the template is
            // `%1$s tok/s`, and the count at the call site is one, which is why
            // nothing was wrong. The gap was that nothing checked -- a period
            // added to that string, or a second model named in it, would have
            // shipped.
            //
            // A third field carries the largest argument count seen at any call
            // site; -1 means no call site formats it at all, which is a different
            // problem and not this one.
            // ---------------------------------------------------------------
            int declared = 0;
            if (fields.length > 2) {
                try { declared = Integer.parseInt(fields[2].trim()); } catch (NumberFormatException ignored) { declared = 0; }
            }
            if (declared > 0 && declared < highest) {
                System.out.println(name + "\t" + body + "\t"
                    + "needs " + highest + " argument(s) and every call site supplies "
                    + declared + " -- MissingFormatArgumentException");
            }
        }
    }
}
'@

# ---------------------------------------------------------------- reading ---

$xml = Get-Content $stringsFile -Raw
$declared = [regex]::Matches($xml, '<string\s+name="([^"]+)"') |
    ForEach-Object { $_.Groups[1].Value } |
    Sort-Object -Unique

if ($declared.Count -eq 0) {
    Write-Host '  FAILED no strings were found, which means this check did not run' -ForegroundColor Red
    exit 1
}

# Everything that can name a string: the app's Kotlin, its manifest, and any other
# XML resource that could reference one with @string/.
$haystack = [System.Text.StringBuilder]::new()
$searched = 0

foreach ($dir in @('app/src/main/java', 'app/src/main/res')) {
    $full = Join-Path $root $dir
    if (-not (Test-Path $full)) { continue }
    foreach ($file in Get-ChildItem $full -Recurse -File -Include *.kt, *.xml) {
        # The strings file itself would match every name it declares.
        if ($file.FullName -eq $stringsFile) { continue }
        [void]$haystack.AppendLine((Get-Content $file.FullName -Raw))
        $searched++
    }
}

$manifest = Join-Path $root 'app/src/main/AndroidManifest.xml'
if (Test-Path $manifest) {
    [void]$haystack.AppendLine((Get-Content $manifest -Raw))
    $searched++
}

if ($searched -eq 0) {
    Write-Host '  FAILED nothing was searched, which means this check did not run' -ForegroundColor Red
    exit 1
}

$text = $haystack.ToString()

if ($text -notmatch 'R\.string\.') {
    Write-Host '  FAILED no R.string reference was found anywhere, which means this check did not run' -ForegroundColor Red
    exit 1
}

# --------------------------------------------------------------- checking ---

$unused = [System.Collections.Generic.List[string]]::new()

foreach ($name in $declared) {
    if ($exempt -contains $name) { continue }

    # Either the Kotlin form or the XML form counts. Anchored so `foo` does not
    # match `foo_bar`.
    $kotlin = [regex]::IsMatch($text, "R\.string\.$([regex]::Escape($name))\b")
    $resource = [regex]::IsMatch($text, "@string/$([regex]::Escape($name))\b")

    if (-not ($kotlin -or $resource)) {
        $unused.Add($name)
    }
}

if ($unused.Count -gt 0) {
    foreach ($name in $unused) {
        $line = ($xml -split "`n" | Where-Object { $_ -match "name=`"$([regex]::Escape($name))`"" } | Select-Object -First 1)
        $problems.Add("${name} is declared and nothing names it: $($line.Trim())")
    }
}

# ------------------------------------------------------- format strings ---

# A string that carries a format directive has to be one a printf can read.
#
# This exists because `about_wipe_done` was `写了 %1。…`: `%` and `1`, where the
# `1` is in the width position and no conversion character follows. `Resources
# .getString(id, args)` throws `UnknownFormatConversionException` for that, inside
# composition, so it is a crash and not a wrong label. Nothing caught it -- `lint`
# does not read format strings, this gate only asked whether the string was named
# (it was), and the screen never reached the line because the value it formats was
# never set. Repairing that separate defect would have turned a dead branch into a
# crash on the first tap, which is how the two were found together.
#
# Checked with the JDK rather than in PowerShell, because PowerShell's `-f` is
# .NET's `{0}` syntax and does not read `%` at all: it returns `写了 %1。`
# unchanged, so a check written that way would pass the very string it was added
# for. `java` is not a dependency this adds -- `gradlew` needs it, and the format
# engine is the same `java.util.Formatter` on both sides.
# Bodies, keyed by name, taken from the parsed document rather than by re-scanning
# lines: a string's body can wrap, and a regex over `-split "`n"` would then match
# the opening tag and capture nothing.
[xml] $doc = $xml
$bodies = @{}
foreach ($entry in $doc.resources.string) {
    $bodies[$entry.name] = $entry.'#text'
}

$formatted = @($declared | Where-Object { $bodies[$_] -and $bodies[$_] -match '%' })

<#
    How many arguments a call site gives, per string.

    A third field per row, and the reason it exists is the gap the Java side names:
    proving a template is valid does not prove a caller supplies enough for it. Both
    raise the same exception from `Resources.getString`, inside composition.

    Scanned over the same sources the unused check searches, so a call site that counts
    for one counts for the other.

    The minimum across call sites, not the maximum. A string formatted with two
    arguments in one place and one in another crashes in the second place whatever the
    first does.

    `-1` means no call site formats the string at all -- only its id is passed, which
    is what `stringResource(R.string.x)` without arguments looks like. That is not this
    check's business, and passing zero for it would make every such string look like a
    missing-argument failure.
#>
$argCounts = @{}
$sourceRoot = Join-Path $root 'app/src/main/java'

<#
    One pass over every source file, reading call sites rather than resource names.

    The first version of this looked for `R.string.<name>` and expected a `(` straight
    after it. Almost no call site does that, because the two ways a formatted string is
    reached are:

        stringResource(R.string.chat_rate, rate)      -- name, then a comma
        context.getString(R.string.about_wipe_done, text)

    so the parenthesis never follows the name and the scan found nothing at all. It
    reported `-1`, which this check treats as "no call site formats it" and skips --
    silently, which is the failure this whole file exists to prevent.

    Reading from the call instead: find `stringResource(` or `getString(`, take the
    first argument as the name, and count the rest.
#>
foreach ($file in (Get-ChildItem $sourceRoot -Recurse -Filter *.kt -File)) {
    # Comments are stripped first, because `R.string.chat_rate` appears inside KDoc and
    # ordinary comments in this project -- more often than once, in fact -- and a name
    # read out of prose would be counted as a call site.
    $text = [System.IO.File]::ReadAllText($file.FullName)
    $text = [regex]::Replace($text, '(?s)/\*.*?\*/', ' ')
    $text = [regex]::Replace($text, '(?m)//.*$', ' ')

    foreach ($fn in @('stringResource', 'getString')) {
        $at = $text.IndexOf("$fn(")
        while ($at -ge 0) {
            # Not a call if it is the tail of a longer identifier.
            $before = if ($at -gt 0) { $text[$at - 1] } else { ' ' }
            if ([char]::IsLetterOrDigit($before) -or $before -eq '_' -or $before -eq '.') {
                $at = $text.IndexOf("$fn(", $at + 1)
                continue
            }

            # Character literals and string literals may hold parentheses or commas,
            # but not backslash escapes that matter here; the common case is a string
            # with neither.
            $depth = 1
            $count = 1
            $inString = $false
            $inChar = $false
            $j = $at + $fn.Length + 1
            $closed = -1
            $nameAt = -1
            while ($j -lt $text.Length) {
                $c = $text[$j]
                if ($inString) {
                    if ($c -eq '\') { $j += 2; continue }
                    if ($c -eq '"') { $inString = $false }
                } elseif ($inChar) {
                    if ($c -eq '\') { $j += 2; continue }
                    if ($c -eq "'") { $inChar = $false }
                } elseif ($c -eq '"') {
                    $inString = $true
                } elseif ($c -eq "'") {
                    $inChar = $true
                } elseif ($c -eq '(') {
                    $depth++
                } elseif ($c -eq ')') {
                    $depth--
                    if ($depth -eq 0) { $closed = $j; break }
                } elseif ($c -eq ',' -and $depth -eq 1) {
                    $count++
                }
                $j++
            }
            if ($closed -ge 0) {
                $nameAt = $text.IndexOf('R.string.', $at, $closed - $at)
                if ($nameAt -ge 0) {
                    $start = $nameAt + 'R.string.'.Length
                    $end = $start
                    while ($end -lt $text.Length -and ([char]::IsLetterOrDigit($text[$end]) -or $text[$end] -eq '_')) { $end++ }
                    $resolved = $text.Substring($start, $end - $start)
                    # `stringResource(id, a, b)` passes two arguments; the count above
                    # includes the id, so the arguments after it are one fewer.
                    $args = $count - 1
                    if ($argCounts.ContainsKey($resolved)) {
                        if ($args -lt $argCounts[$resolved]) { $argCounts[$resolved] = $args }
                    } else {
                        $argCounts[$resolved] = $args
                    }
                }
            }
            $at = $text.IndexOf("$fn(", $at + 1)
        }
    }
}

if ($formatted.Count -gt 0) {
    $java = Get-Command java -ErrorAction SilentlyContinue
    if (-not $java) {
        Write-Host '  WARNING - java is not on PATH, so format strings were not checked.' -ForegroundColor Yellow
    } else {
        $work = Join-Path ([System.IO.Path]::GetTempPath()) ("fmtcheck-" + [guid]::NewGuid().ToString('N'))
        New-Item -ItemType Directory -Force -Path $work | Out-Null
        try {
            [System.IO.File]::WriteAllText((Join-Path $work 'FmtCheck.java'), $FMT_CHECK_JAVA)

            $input = [System.Collections.Generic.List[string]]::new()
            foreach ($name in $formatted) {
                # `-1` when no call site names it as a formatted string. The Java side
                # reads that as "not this check's business" rather than as zero, so a
                # string reached only as an id does not look like a missing argument.
                $supplied = if ($argCounts.ContainsKey($name)) { $argCounts[$name] } else { -1 }
                $input.Add("$name`t$($bodies[$name])`t$supplied")
            }
            [System.IO.File]::WriteAllLines((Join-Path $work 'in.tsv'), $input)

            $javac = Join-Path (Split-Path -Parent $java.Source) 'javac'
            if ($IsWindows) { $javac = "$javac.exe" }

            if (Test-Path $javac) {
                & $javac -d $work (Join-Path $work 'FmtCheck.java') *> $null
                if ($LASTEXITCODE -eq 0) {
                    $out = & java -cp $work FmtCheck (Join-Path $work 'in.tsv') 2>&1
                    foreach ($row in $out) {
                        if ("$row" -match "^(?<name>\w+)\t(?<body>.*)\t(?<why>.*)$") {
                            # Says "crash" rather than "throw": this is shown to whoever
                            # broke the build, and the consequence is what matters --
                            # `Resources.getString` raises inside composition, so the
                            # user sees the app close.
                            $problems.Add("$($Matches['name']) would crash the screen when formatted  ($($Matches['why']))")
                        }
                    }
                } else {
                    Write-Host '  WARNING - the format-string checker did not compile.' -ForegroundColor Yellow
                }
            } else {
                Write-Host '  WARNING - javac is not available, so format strings were not checked.' -ForegroundColor Yellow
            }
        } finally {
            Remove-Item $work -Recurse -Force -ErrorAction SilentlyContinue
        }
    }
}

# ------------------------------------------------ every gate is run by one ---

# A gate that no pipeline runs is a comment.
#
# This is the same defect shape as a string nothing names, which is why it lives in
# this file rather than in a gate of its own. `check-embedded-notices.ps1` and
# `check-ui-wiring.ps1` were both in this repository, both correct, and both run by
# nothing: CI ran four gates, the pre-commit hook ran two, and the two that fell
# through the gap were the ones nobody had thought to add. They worked whenever
# someone ran them by hand, which is exactly why it went unnoticed.
#
# Checked against the two places a gate can be invoked -- the workflow and the hook --
# and the check is "at least one", not "both", because the hook deliberately runs a
# subset for speed.
$pipelines = @(
    (Join-Path $root '.github/workflows/compliance.yml'),
    (Join-Path $root '.githooks/pre-commit')
)

$gateScripts = Get-ChildItem (Join-Path $root 'scripts') -Filter 'check-*.ps1' -File |
    ForEach-Object { $_.Name } |
    Sort-Object

if ($gateScripts.Count -eq 0) {
    $problems.Add('no gate scripts were found, which means this check did not run')
} else {
    $pipelineText = ''
    foreach ($pipeline in $pipelines) {
        if (Test-Path $pipeline) { $pipelineText += (Get-Content $pipeline -Raw) + "`n" }
    }

    # What counts is an invocation, not a mention.
    #
    # The first version of this checked whether the file name appeared anywhere in
    # the pipelines, and its own negative test passed when it should have failed: the
    # workflow lists the gates in a comment block at the top, so deleting the
    # `run:` line left the comment behind and the name still matched. A gate that
    # looks for a name is satisfied by a comment about that name, which is the same
    # defect this check exists to catch, one level up.
    #
    # Two shapes, because there are two ways a gate is invoked here: `run:` in a
    # workflow step, and `-File scripts/<name>` in the hook.
    $invocations = [regex]::Matches($pipelineText, '(?:run:\s*|[-/]File\s+)(?:\./)?scripts/(check-[\w-]+\.ps1)') |
        ForEach-Object { $_.Groups[1].Value }

    if ($invocations.Count -eq 0) {
        $problems.Add('no gate is invoked by any pipeline, which means this check did not run')
    } else {
        foreach ($gate in $gateScripts) {
            if ($invocations -notcontains $gate) {
                $problems.Add("scripts/$gate is invoked by nothing: neither the workflow nor the pre-commit hook runs it")
            }
        }
    }
}

# ------------------------------------------------- every script compiles ---

# A gate that does not parse is a gate that does not run.
#
# This exists because one did not, and the failure was invisible in exactly the way
# this file keeps finding. Teaching `check-ui-copy.ps1` to see Chinese meant adding
# a list of allowed literals; the list was written with trailing commas after every
# element, and PowerShell does not permit one after the *last* element of an array.
# The script then failed to parse, so every run reported a ParserError and no copy
# was checked at all -- while the file sat there looking correct.
#
# `Parser::ParseFile` answers this in milliseconds, without running anything, and it
# names the line and column. Every step spent guessing at an encoding, a BOM, a line
# ending and a `-File` invocation was a step that this call would have replaced.
$scriptsDir = Join-Path $root 'scripts'
foreach ($script in (Get-ChildItem $scriptsDir -Filter '*.ps1' -File)) {
    $parseErrors = $null
    $null = [System.Management.Automation.Language.Parser]::ParseFile($script.FullName, [ref]$null, [ref]$parseErrors)
    foreach ($e in $parseErrors) {
        $problems.Add("scripts/$($script.Name):$($e.Extent.StartLineNumber) does not parse: $($e.Message)")
    }
}

# ----------------------------------------------------------------- report ---

if ($problems.Count -gt 0) {
    Write-Host ''
    Write-Host '  VIOLATED - strings.xml or scripts/ holds something nothing asks for:' -ForegroundColor Red
    foreach ($problem in $problems) {
        Write-Host "      $problem" -ForegroundColor Red
    }
    Write-Host ''
    Write-Host '  Either name it from the code that should use it, or delete it. A thing' -ForegroundColor Yellow
    Write-Host '  that stays is one a reader has to re-derive the status of.' -ForegroundColor Yellow
    exit 1
}

Write-Host "  CLEAN - all $($declared.Count) strings are named by something ($searched files searched), and all $($gateScripts.Count) gates are run by a pipeline."
exit 0
