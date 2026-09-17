[CmdletBinding()]
param(
    [string]$RuntimeDirectory
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($RuntimeDirectory)) {
    $repositoryRoot = Split-Path -Parent $PSScriptRoot
    $RuntimeDirectory = Join-Path $repositoryRoot "vendor\mpv\runtime"
}

$archiveUrl = "https://github.com/shinchiro/mpv-winbuild-cmake/releases/download/20260610/mpv-x86_64-20260610-git-304426c.7z"
$archiveSha256 = "facac536baa73c7b925771af5e39a3c9cb16b8d75b59a6e9800de89799dffca7"
$expectedFiles = [ordered]@{
    "mpv.exe" = "b0bb2dc1928e6d86cc26d950815c80c977440081e814c6a46e93f6e9e99c276d"
    "d3dcompiler_43.dll" = "4b074a3976399dc735484f5d43d04b519b7bdee8ac719d9ab8ed6bd4e6be0345"
    "mpv/fonts.conf" = "f141c1b89b172d22f213531646c21e288f0ebf3ec46484698896e1b33c626756"
}

function Get-Sha256([string]$Path) {
    (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Test-VerifiedRuntime([string]$Root) {
    foreach ($entry in $expectedFiles.GetEnumerator()) {
        $path = Join-Path $Root $entry.Key
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
            return $false
        }
        if ((Get-Sha256 $path) -ne $entry.Value) {
            return $false
        }
    }
    return $true
}

$runtimeRoot = [System.IO.Path]::GetFullPath($RuntimeDirectory)
if (Test-VerifiedRuntime $runtimeRoot) {
    Write-Host "Verified mpv runtime is already available at $runtimeRoot."
    exit 0
}

New-Item -ItemType Directory -Path $runtimeRoot -Force | Out-Null
$temporaryRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("screenpilot-mpv-" + [guid]::NewGuid())

try {
    New-Item -ItemType Directory -Path $temporaryRoot -Force | Out-Null
    $archivePath = Join-Path $temporaryRoot "mpv-runtime.7z"
    $extractRoot = Join-Path $temporaryRoot "extract"

    Write-Host "Downloading the pinned mpv runtime archive."
    Invoke-WebRequest -Uri $archiveUrl -OutFile $archivePath -MaximumRedirection 5
    if ((Get-Sha256 $archivePath) -ne $archiveSha256) {
        throw "The downloaded mpv archive does not match the pinned SHA-256."
    }

    $sevenZip = Get-Command "7z.exe" -ErrorAction SilentlyContinue
    if ($null -eq $sevenZip) {
        $sevenZip = Get-Command "7z" -ErrorAction SilentlyContinue
    }
    if ($null -eq $sevenZip) {
        throw "7-Zip is required to unpack the mpv archive. Install the 7zip package or add 7z.exe to PATH."
    }

    & $sevenZip.Source x "-o$extractRoot" "-y" $archivePath
    if ($LASTEXITCODE -ne 0) {
        throw "7-Zip could not unpack the verified mpv archive (exit code $LASTEXITCODE)."
    }

    foreach ($entry in $expectedFiles.GetEnumerator()) {
        $sourcePath = Join-Path $extractRoot $entry.Key
        if (-not (Test-Path -LiteralPath $sourcePath -PathType Leaf)) {
            throw "The verified mpv archive is missing $($entry.Key)."
        }
        if ((Get-Sha256 $sourcePath) -ne $entry.Value) {
            throw "The extracted mpv file $($entry.Key) does not match its pinned SHA-256."
        }

        $destinationPath = Join-Path $runtimeRoot $entry.Key
        New-Item -ItemType Directory -Path (Split-Path -Parent $destinationPath) -Force | Out-Null
        Copy-Item -LiteralPath $sourcePath -Destination $destinationPath -Force
    }

    if (-not (Test-VerifiedRuntime $runtimeRoot)) {
        throw "The restored mpv runtime did not pass final verification."
    }
    Write-Host "Restored and verified the pinned mpv runtime at $runtimeRoot."
}
finally {
    if (Test-Path -LiteralPath $temporaryRoot) {
        Remove-Item -LiteralPath $temporaryRoot -Recurse -Force
    }
}
