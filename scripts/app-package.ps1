# The application's own package directory, read from the build file that owns it.
#
# Gates name application sources relative to `app/src/main/java`; the rest of that
# path is the package, and `app/build.gradle.kts` is where the id is decided. Six
# gates spelled it out until 2026-10-16, when the id changed and they did not. The
# ones that open a named file stopped finding it; the ones that keep an exemption
# list went on matching paths that no longer exist, so they reported prose that had
# been excused and could not open the file they excused it by name. Reading the
# package from the build file means a rename needs no gate edits and cannot leave a
# gate comparing against a tree that is gone.
#
# Usage: `. "$PSScriptRoot/app-package.ps1"` then `$appPkg = Get-AppPackage $root`,
# where `$root` is the checkout root.
function Get-AppPackage {
    param([Parameter(Mandatory)][string] $Root)

    $buildFile = Join-Path $Root 'app/build.gradle.kts'
    if (-not (Test-Path -LiteralPath $buildFile)) {
        throw "Get-AppPackage: no app/build.gradle.kts under $Root"
    }
    $text = [System.IO.File]::ReadAllText($buildFile)
    $match = [regex]::Match($text, '(?m)^\s*namespace\s*=\s*"([^"]+)"\s*$')
    if (-not $match.Success) {
        throw 'Get-AppPackage: app/build.gradle.kts has no namespace = "..." line'
    }
    return ($match.Groups[1].Value -replace '\.', '/')
}
