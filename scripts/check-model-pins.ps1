# Compares every digest in the model catalogue with the digest its provider is
# publishing right now.
#
# Why this exists: the catalogue pins a SHA-256 per model, and `ModelDownloader`
# refuses to install a file whose digest does not match -- correctly. What was
# missing is anything on the other side of that promise. A publisher can replace an
# artifact under the same name and the same length, and the only party who finds out
# is a user, 344 MB into a download, being told to download it again.
#
# That is not hypothetical. It happened to `qwen3-0.6b-int4`, the first entry in the
# ladder and the one someone who has not chosen yet ends up with: the pinned digest
# was correct for the bytes it was computed over and wrong for the bytes being served
# in its place.
#
# The check reads the LFS pointer rather than the artifact: Hugging Face stores large
# files through git-lfs, and the pointer's `oid sha256:` is the digest of the content,
# published with the file. Seven pointers are a few kilobytes, so this is cheap enough
# to run on every change.
#
# This is a network check, and it is the only gate here that needs one. It is NOT in
# `compliance.yml`, which must keep working on a machine with no network at all; see
# `.github/workflows/model-pins.yml`. When the providers cannot be reached this script
# says so and exits 0, because "the provider was down" is not a finding about this
# project -- but it prints the count it could not reach, so a silent total failure is
# still visible in the log.

[CmdletBinding()]
param(
    # Report the digests the providers are publishing without comparing them.
    [switch] $Dump
)

$ErrorActionPreference = 'Stop'

$ProjectDir = if ($env:DEMOAPP_DIR) { $env:DEMOAPP_DIR } else { Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path) }
$cataloguePath = Join-Path $ProjectDir 'app/src/main/java/com/example/demoapp/model/ModelCatalogue.kt'

if (-not (Test-Path $cataloguePath)) {
    throw "Cannot find $cataloguePath"
}

$text = [System.IO.File]::ReadAllText($cataloguePath)

# Each descriptor is read as a block rather than line by line, because the fields are
# not in a fixed order across the seven entries and `url` can be a template.
$entries = [regex]::Matches(
    $text,
    'ModelDescriptor\((?<body>.*?)^        \),',
    [System.Text.RegularExpressions.RegexOptions]::Singleline -bor [System.Text.RegularExpressions.RegexOptions]::Multiline
)

if ($entries.Count -eq 0) {
    throw "No ModelDescriptor blocks matched in $cataloguePath -- the pattern has gone stale, which is worse than a failing check."
}

$streams = @()
foreach ($m in $entries) {
    $body = $m.Groups['body'].Value
    $id = [regex]::Match($body, 'id = "([^"]+)"').Groups[1].Value
    $sha = [regex]::Match($body, 'sha256 = "([0-9a-f]{64})"').Groups[1].Value
    $size = [regex]::Match($body, 'sizeBytes = ([\d_]+)L').Groups[1].Value -replace '_', ''
    $file = [regex]::Match($body, 'fileName = "([^"]+)"').Groups[1].Value
    $url = [regex]::Match($body, 'url = "([^"]+)"').Groups[1].Value
    if ($id -and $sha -and $file -and $url) {
        $streams += [pscustomobject]@{
            Id       = $id
            Sha256   = $sha
            Size     = [long]$size
            FileName = $file
            Url      = $url -replace '\$HF_MIRROR', 'https://hf-mirror.com' -replace '\$HF/', 'https://huggingface.co/'
        }
    }
}

# `RAW_BASE` is the mirror plus the repository path with `/resolve/` swapped for
# `/raw/`, which serves the pointer text instead of the content.
function Get-Pointer([string] $resolveUrl) {
    $raw = $resolveUrl -replace '/resolve/', '/raw/'
    $request = [System.Net.HttpWebRequest]::Create($raw)
    $request.Timeout = 45000
    $request.ReadWriteTimeout = 45000
    $request.UserAgent = 'DemoApp-model-pin-check'
    $response = $request.GetResponse()
    try {
        $reader = New-Object System.IO.StreamReader($response.GetResponseStream())
        return $reader.ReadToEnd()
    } finally {
        $response.Close()
    }
}

$checked = 0
$unreachable = 0
$mismatched = @()

foreach ($s in $streams) {
    $publishedSha = $null
    $publishedSize = $null
    try {
        $pointer = Get-Pointer $s.Url
        $publishedSha = [regex]::Match($pointer, 'oid sha256:([0-9a-f]{64})').Groups[1].Value
        $publishedSize = [regex]::Match($pointer, 'size (\d+)').Groups[1].Value
    } catch {
        $unreachable++
        Write-Host ("  {0,-26} could not ask the provider: {1}" -f $s.Id, $_.Exception.Message)
        continue
    }

    if (-not $publishedSha) {
        # A file small enough to sit in git rather than git-lfs has no pointer. That is
        # not a finding about this project, but it does mean the digest cannot be
        # verified from here.
        $unreachable++
        Write-Host ("  {0,-26} publishes no pointer, so the digest cannot be checked from here" -f $s.Id)
        continue
    }

    $checked++
    if ($Dump) {
        Write-Host ("  {0,-26} {1}" -f $s.Id, $publishedSha)
        continue
    }

    if ($publishedSha -ne $s.Sha256) {
        $mismatched += [pscustomobject]@{
            Id        = $s.Id
            Pinned    = $s.Sha256
            Published = $publishedSha
            PinnedLen = $s.Size
            PubLen    = if ($publishedSize) { [long]$publishedSize } else { 0 }
        }
        Write-Host ("  {0,-26} MISMATCH" -f $s.Id) -ForegroundColor Red
        Write-Host ("      pinned    {0}" -f $s.Sha256)
        Write-Host ("      published {0}" -f $publishedSha)
        if ($publishedSize -and [long]$publishedSize -ne $s.Size) {
            Write-Host ("      and the length differs too: pinned {0:N0}, published {1:N0}" -f $s.Size, [long]$publishedSize)
        } else {
            Write-Host ("      same length ({0:N0} bytes), so a download will complete before it is refused" -f $s.Size)
        }
        continue
    }

    Write-Host ("  {0,-26} ok" -f $s.Id)
}

Write-Host ""

if ($Dump) {
    Write-Host "DUMP - $checked of $($streams.Count) digests read from the providers."
    exit 0
}

if ($mismatched.Count -gt 0) {
    Write-Host "FAILED - $($mismatched.Count) of $($streams.Count) pinned digests no longer match what is published."
    Write-Host "A model whose digest is stale cannot be installed: ModelDownloader deletes the file and"
    Write-Host "asks the user to download it again, which will fail the same way. Update the entry in"
    Write-Host "$cataloguePath with the published digest above, and re-verify on a device."
    exit 1
}

if ($checked -eq 0) {
    Write-Host "CANNOT CHECK - none of the $($streams.Count) providers could be reached."
    Write-Host "This is not a finding about the catalogue, and it is not a pass either."
    exit 0
}

Write-Host "CLEAN - all $checked reachable model pins match what their provider publishes ($($streams.Count) in the catalogue)."
if ($unreachable -gt 0) {
    Write-Host "         $unreachable could not be checked from here."
}
exit 0
