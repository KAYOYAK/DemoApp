#requires -Version 7.0
<#
.SYNOPSIS
    Checks the legal documents that a disclaimer and an infringement policy
    depend on, and the two places they can silently stop being true.

.DESCRIPTION
    Three things are checked, and each one is a way this can rot without anyone
    noticing:

      1. The documents exist and are reachable. A disclaimer nothing links to is
         a disclaimer no user will read.
      2. Every model publisher named in the catalogue appears in the trademark
         table. Adding a model for a new company, and forgetting the attribution
         that goes with it, is the most likely way this project infringes
         something by accident.
      3. The app itself shows a disclaimer, offline, and says the things that
         matter. It runs with no network and no server; a document on the web is
         not reachable from the device it is about.

    Exits 1 with the specific reasons when any of these fails.

.EXAMPLE
    pwsh -File scripts/check-legal-docs.ps1
#>
[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot

$problems = [System.Collections.Generic.List[string]]::new()

function Read-Text([string] $relative) {
    $path = Join-Path $root $relative
    if (-not (Test-Path $path)) { return $null }
    return Get-Content $path -Raw
}

# --------------------------------------------------------------- 1. documents ---

$disclaimer = Read-Text 'DISCLAIMER.md'
$trademarks = Read-Text 'docs/legal/TRADEMARKS-AND-CLAIMS.md'
$readme = Read-Text 'README.md'

if (-not $disclaimer) {
    $problems.Add('DISCLAIMER.md is missing')
} else {
    # Each of these states something a reader has to be told for the document to
    # do its job. A disclaimer without them is a sentence saying "not our fault".
    $required = @{
        'no warranty'          = '(?i)no warranty'
        'professional advice'  = '(?i)professional advice'
        'the output is not ours' = '(?i)not written, reviewed, edited, or approved'
        'no moderation'        = '(?i)cannot moderate'
        'liability'            = '(?i)liability'
    }
    foreach ($entry in $required.GetEnumerator()) {
        if ($disclaimer -notmatch $entry.Value) {
            $problems.Add("DISCLAIMER.md does not state $($entry.Key)")
        }
    }
}

if (-not $trademarks) {
    $problems.Add('docs/legal/TRADEMARKS-AND-CLAIMS.md is missing')
}

if ($readme -and $readme -notmatch 'DISCLAIMER\.md') {
    $problems.Add('README.md does not link DISCLAIMER.md')
}
if ($readme -and $readme -notmatch 'TRADEMARKS-AND-CLAIMS\.md') {
    $problems.Add('README.md does not link the trademark and claims document')
}

# ------------------------------------------------- 2. publishers are credited ---

$catalogue = Read-Text 'app/src/main/java/com/example/demoapp/model/ModelCatalogue.kt'
if (-not $catalogue) {
    $problems.Add('the model catalogue could not be read, so publishers cannot be checked against it')
} elseif ($trademarks) {
    # The catalogue names its publishers by resource id, so the words come from
    # strings.xml. Read through both: a check that silently stops finding anything
    # is one that passes while the attribution is missing.
    $stringsXml = Read-Text 'app/src/main/res/values/strings.xml'
    $publisherResources = [regex]::Matches($catalogue, 'publisherRes\s*=\s*R\.string\.(\w+)') |
        ForEach-Object { $_.Groups[1].Value } |
        Sort-Object -Unique
    $publishers = @(
        foreach ($resourceName in $publisherResources) {
            $entry = [regex]::Match($stringsXml, "<string name=`"$resourceName`"[^>]*>([^<]+)</string>")
            if ($entry.Success) { $entry.Groups[1].Value } else { $resourceName }
        }
    ) | Sort-Object -Unique

    foreach ($publisher in $publishers) {
        # A publisher may appear under a different legal name in the table -- the
        # catalogue says "Alibaba Qwen" and the table says "Alibaba Group". Any
        # word of the publisher appearing is enough for a human to find the row;
        # requiring the whole string would fail on the honest differences.
        $words = $publisher -split '\s+' | Where-Object { $_.Length -ge 3 }
        $found = $false
        foreach ($word in $words) {
            if ($trademarks -match [regex]::Escape($word)) { $found = $true; break }
        }
        if (-not $found) {
            $problems.Add("model publisher '$publisher' is not named in the trademark table")
        }
    }

    if ($publishers.Count -eq 0) {
        $problems.Add('the catalogue yielded no publishers, which means this check did not run')
    }
}

# ------------------------------------------- 3. the app shows a disclaimer ---

# The words live in the string resources now, not in the screen. Checked there,
# because that is where a person's copy is and where a search for it will land.
$about = Read-Text 'app/src/main/res/values/strings.xml'
if (-not $about) {
    $problems.Add('strings.xml is missing, so the app has no copy at all')
} else {
    $shown = @{
        # Chinese, because the app is. A check for the English wording would pass
        # on a file that no longer says any of this to the person reading it.
        'as-is / no warranty'    = '不附带任何担保'
        'not professional advice'= '不是专业建议'
        # Specific on purpose. A looser pattern would match any sentence that
        # happens to mention a model, and pass while the claim that matters --
        # that the words are the model's and not the app author's -- is absent.
        'output is the model''s' = '你选择的模型在这台设备上生成的|没有任何参与开发的人写过'
        # Two separate claims, not one either-or. A combined pattern passed while
        # one half of the sentence had been deleted, because the other half still
        # matched -- a check that reports the notice is present when half of it is
        # gone.
        'trademarks are named'   = '商标'
        'no affiliation stated'  = '没有关联'
        'no moderation'          = '没有人能审核'
    }
    foreach ($entry in $shown.GetEnumerator()) {
        if ($about -notmatch $entry.Value) {
            $problems.Add("the app's copy does not state $($entry.Key)")
        }
    }
}

# ------------------------------------------------------------------- report ---

if ($problems.Count -gt 0) {
    Write-Host 'LEGAL DOCUMENTS INCOMPLETE' -ForegroundColor Red
    foreach ($problem in $problems) { Write-Host "  - $problem" -ForegroundColor Red }
    Write-Host ''
    Write-Host 'A disclaimer or an attribution that is missing is one a user cannot rely on,'
    Write-Host 'and one a claimant is entitled to treat as absent.'
    exit 1
}

Write-Host 'CLEAN - disclaimer present and linked, every publisher credited, app shows a disclaimer.' -ForegroundColor Green
exit 0
