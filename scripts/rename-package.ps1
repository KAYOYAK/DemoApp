<#
.SYNOPSIS
Renames the application id and the Kotlin package, together, in one run.

.DESCRIPTION
The id this project shipped until 2026-10-16, `com.example.demoapp`, was a placeholder:
what a project generator writes before anyone owns a domain, and what Google Play refuses
as a reserved example domain. The rename to `com.kayoyak.demoapp` is recorded in
`docs/ROADMAP.md`. The old name is read out of `app/build.gradle.kts` rather than assumed,
so this script renames again if the id ever moves. It is not a search-and-replace -- the
package name decides where the sources live, which classes the manifest resolves, and what
the device considers a different app -- so it does every part in one order and refuses to
run halfway.

What it changes:

  * `app/build.gradle.kts`: `namespace` and `applicationId`.
  * The source directories: `app/src/{main,test,androidTest}/java/<old path>` move to
    the new path. Done with `git mv` when the directory is tracked, so history follows.
  * Every occurrence of the old name in `.kt`, `.kts`, `.xml`, `.pro`, `.ps1`, `.yml`
    and `.json` files outside build output -- in **both** spellings it appears in: the
    dotted one the code and the manifest use (`com.example.demoapp`) and the slashed
    one paths use (`com/example/demoapp`). Knowing only the dotted one was this
    script's first real defect: files that named a source path by its slashed form
    were never listed, so they kept pointing at a directory that no longer existed and
    the dry run still read as complete. A gate that compares against a path like that
    does not fail loudly -- it stops finding the file, or goes on excusing a path that
    is gone -- which is why the scan at the end checks for either spelling.

What it deliberately leaves alone:

  * Markdown. The documents quote paths as they were when they were written, and
    rewriting them would make the record wrong rather than current. The files that
    mention the old name are listed at the end so a person can decide.
  * Anything under `build/`, `.gradle/` or `.kotlin/`.

The result installs as a different app: the device keeps the old one, or replaces it
only if the id is unchanged, so expect an uninstall or a second icon.

.PARAMETER NewName
The new package, e.g. `com.kayoyak.aichat`. It is used for both `namespace` and
`applicationId`; change `applicationId` afterwards if they should differ.

.PARAMETER Apply
Write the changes. Without it this prints the plan and touches nothing.

.EXAMPLE
pwsh -NoProfile -File scripts/rename-package.ps1 -NewName com.kayoyak.aichat
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$NewName,
    [switch]$Apply
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$buildFile = Join-Path $root 'app/build.gradle.kts'

if ($NewName -notmatch '^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$') {
    throw "'$NewName' is not a package name: lower-case letters, digits and underscores, at least two segments, no leading digit in a segment."
}
if ($NewName -match '^com\.example($|\.)') {
    throw "'$NewName' is still under the reserved example domain; Google Play refuses those."
}

$buildText = Get-Content -LiteralPath $buildFile -Raw
if ($buildText -notmatch 'namespace\s*=\s*"([^"]+)"') {
    throw "$buildFile has no namespace to rename."
}
$oldName = $Matches[1]
if ($oldName -eq $NewName) {
    throw "The package is already '$NewName'."
}

$oldPath = $oldName.Replace('.', [IO.Path]::DirectorySeparatorChar)
$newPath = $NewName.Replace('.', [IO.Path]::DirectorySeparatorChar)
# The name in the two spellings that reach text: dotted where it is a name, slashed
# where it is part of a path. Both have to be replaced; see the .DESCRIPTION note.
$oldSlashed = $oldName.Replace('.', '/')
$newSlashed = $NewName.Replace('.', '/')

# True when a file's text still names the old package in either spelling.
function Test-StillNamesOld([string] $Text) {
    return ($Text.Contains($oldName) -or $Text.Contains($oldSlashed))
}
$sourceSets = @('main', 'test', 'androidTest')
$extensions = @('.kt', '.kts', '.xml', '.pro', '.ps1', '.yml', '.yaml', '.json')
$skip = @('build', '.gradle', '.kotlin')

$moves = @()
foreach ($set in $sourceSets) {
    $from = Join-Path $root "app/src/$set/java/$oldPath"
    if (Test-Path -LiteralPath $from) {
        $moves += @{ From = $from; To = Join-Path $root "app/src/$set/java/$newPath" }
    }
}

$rewrites = @()
$markdown = @()
foreach ($file in Get-ChildItem -LiteralPath $root -Recurse -File) {
    $relative = $file.FullName.Substring($root.Length + 1)
    if ($relative -split '[\\/]' | Where-Object { $skip -contains $_ }) { continue }
    $extension = $file.Extension.ToLowerInvariant()
    if ($markdown -notcontains $file.FullName -and $extension -eq '.md') {
        if (Test-StillNamesOld ([IO.File]::ReadAllText($file.FullName))) {
            $markdown += $relative
        }
        continue
    }
    if ($extensions -notcontains $extension) { continue }
    if (Test-StillNamesOld ([IO.File]::ReadAllText($file.FullName))) {
        $rewrites += $relative
    }
}

Write-Host "package:   $oldName -> $NewName"
Write-Host "directories:"
foreach ($move in $moves) {
    Write-Host ("  {0} -> {1}" -f $move.From.Substring($root.Length + 1), $move.To.Substring($root.Length + 1))
}
Write-Host ("files rewritten: {0}" -f $rewrites.Count)
$rewrites | Sort-Object | ForEach-Object { Write-Host "  $_" }
if ($markdown.Count -gt 0) {
    Write-Host "documents that mention the old name and are NOT rewritten (they quote it as it was):"
    $markdown | Sort-Object | ForEach-Object { Write-Host "  $_" }
}

if (-not $Apply) {
    Write-Host ''
    Write-Host 'Dry run: nothing was changed. Add -Apply to write.'
    exit 0
}

# A rename that lands beside other edits cannot be read or reverted on its own, so
# writing it is what needs the clean tree, not looking at it. Both halves of that
# check need a repository: without one `git status` fails, its output is empty, and a
# tree that is not under version control at all would read as clean.
$insideRepo = (& git -C $root rev-parse --is-inside-work-tree 2>$null)
if ($insideRepo -notmatch '^true$') {
    throw "$root is not a git working tree, so the rename could not be reverted on its own. Use -Apply in a checkout."
}
$dirty = & git -C $root status --porcelain -- app/src app/build.gradle.kts
if ($dirty) {
    throw "app/src or app/build.gradle.kts has uncommitted changes. Commit them first."
}

# Contents first, then the directories. The rewrite list is paths as they are now,
# and moving the directories up front would leave every entry pointing at a file
# that is no longer there.
foreach ($relative in $rewrites | Sort-Object) {
    $path = Join-Path $root $relative
    $text = Get-Content -LiteralPath $path -Raw
    Set-Content -LiteralPath $path -Value ($text.Replace($oldName, $NewName).Replace($oldSlashed, $newSlashed)) -NoNewline
}

foreach ($move in $moves) {
    $tracked = & git -C $root ls-files -- $move.From
    $parent = Split-Path -Parent $move.To
    if (-not (Test-Path -LiteralPath $parent)) { New-Item -ItemType Directory -Path $parent -Force | Out-Null }
    if ($tracked) { & git -C $root mv $move.From $move.To } else { Move-Item -LiteralPath $move.From -Destination $move.To }
}

# A move leaves the old `com/example` behind, empty. Walk up from the deepest
# directory the old name still implies -- the package directory itself is gone by
# now -- and remove what is empty, stopping at the first one that is not.
foreach ($set in $sourceSets) {
    $java = Join-Path $root "app/src/$set/java"
    $parts = $oldName.Split('.')
    for ($i = $parts.Count - 1; $i -ge 1; $i--) {
        $candidate = Join-Path $java ($parts[0..($i - 1)] -join [IO.Path]::DirectorySeparatorChar)
        if (-not (Test-Path -LiteralPath $candidate)) { continue }
        if (Get-ChildItem -LiteralPath $candidate -Force) { break }
        Remove-Item -LiteralPath $candidate
    }
}

$buildText = $buildText.Replace("namespace = `"$oldName`"", "namespace = `"$NewName`"")
$buildText = $buildText.Replace("applicationId = `"$oldName`"", "applicationId = `"$NewName`"")
Set-Content -LiteralPath $buildFile -Value $buildText -NoNewline

# The check that would have caught the first version's defect: read the same file set
# back and fail if the old name survives in either spelling. Markdown is exempt by
# design (it quotes the old name as a record) and is reported rather than fatal.
$leftovers = @()
foreach ($set in $sourceSets) {
    $java = Join-Path $root "app/src/$set/java"
    foreach ($file in Get-ChildItem -LiteralPath $java -Recurse -File) {
        if (Test-StillNamesOld ([IO.File]::ReadAllText($file.FullName))) {
            $leftovers += $file.FullName.Substring($root.Length + 1)
        }
    }
}
foreach ($file in Get-ChildItem -LiteralPath $root -Recurse -File) {
    $relative = $file.FullName.Substring($root.Length + 1)
    if ($relative -split '[\\/]' | Where-Object { $skip -contains $_ }) { continue }
    if ($extensions -notcontains $file.Extension.ToLowerInvariant()) { continue }
    if (Test-StillNamesOld ([IO.File]::ReadAllText($file.FullName))) {
        $leftovers += $relative
    }
}
if ($leftovers.Count -gt 0) {
    # Sorted into one string first: `Sort-Object -join` is not a thing, and a check
    # that dies of its own syntax tells the reader nothing about the tree.
    $listing = ($leftovers | Sort-Object -Unique) -join "`n  "
    throw ("The old name is still in {0} file(s) after the rename:`n  {1}`nRewrite them, or say in the script why they are exempt." -f $leftovers.Count, $listing)
}
Write-Host 'No file under app/ or scripts/ still names the old package.'

Write-Host ''
Write-Host "Renamed to $NewName. Next:"
Write-Host '  .\gradlew :app:testDebugUnitTest'
Write-Host '  scripts\check-*.ps1            # all nine; they read the package from app/build.gradle.kts'
Write-Host '  scripts\mutation-sweep.ps1     # clean tree only'
Write-Host '  .\gradlew :app:installDebug    # this is a different app on the device; the old one stays'
