[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$ReleaseDirectory,

    [Parameter(Mandatory = $true)]
    [ValidatePattern('^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$')]
    [string]$Version,

    [string]$AppId = 'c4cc60ea-a3e8-4ff1-8d94-e79f3b791df4',

    [string]$RepositoryUrl = 'https://github.com/Pasha-404/ScreenPilot'
)

$ErrorActionPreference = 'Stop'

$resolvedDirectory = (Resolve-Path -LiteralPath $ReleaseDirectory).Path
$installerName = "ScreenPilot-Setup-$Version-x64.exe"
$checksumName = "$installerName.sha256"
$requiredNames = @($installerName, $checksumName, 'appfleet-manifest.json')

$assets = @(Get-ChildItem -LiteralPath $resolvedDirectory -Recurse -File)
$actualNames = @($assets | ForEach-Object { $_.Name } | Sort-Object -Unique)
if ($assets.Count -ne 3 -or (Compare-Object $requiredNames $actualNames)) {
    throw "Expected exactly these three release assets: $($requiredNames -join ', '). Found: $($actualNames -join ', ')."
}

foreach ($name in $requiredNames) {
    $asset = Join-Path $resolvedDirectory $name
    if (-not (Test-Path -LiteralPath $asset -PathType Leaf) -or (Get-Item -LiteralPath $asset).Length -le 0) {
        throw "Release asset is missing or empty: $name"
    }
}

$installer = Join-Path $resolvedDirectory $installerName
$checksum = Join-Path $resolvedDirectory $checksumName
$actualHash = (Get-FileHash -LiteralPath $installer -Algorithm SHA256).Hash.ToLowerInvariant()
$expectedChecksumLine = "$actualHash  $installerName"
$checksumLine = (Get-Content -LiteralPath $checksum -Raw).Trim()
if ($checksumLine -cne $expectedChecksumLine) {
    throw "Checksum file does not exactly describe the installer."
}

$manifestPath = Join-Path $resolvedDirectory 'appfleet-manifest.json'
$manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
if ($manifest.schemaVersion -ne 1 -or
    $manifest.appId -cne $AppId -or
    $manifest.name -cne 'ScreenPilot' -or
    $manifest.technicalName -cne 'ScreenPilot' -or
    $manifest.version -cne $Version -or
    $manifest.repositoryUrl -cne $RepositoryUrl -or
    $manifest.platform -cne 'windows' -or
    $manifest.architecture -cne 'x64' -or
    $manifest.installer.type -cne 'inno' -or
    $manifest.installer.assetName -cne $installerName -or
    $manifest.installer.sha256AssetName -cne $checksumName -or
    $manifest.installer.desktopShortcutTask -cne 'desktopicon' -or
    $manifest.minimumAppFleetVersion -cne '2.0.0') {
    throw "The AppFleet manifest does not match the release contract."
}

$expectedSilentArgs = @('/VERYSILENT', '/SUPPRESSMSGBOXES', '/NORESTART', '/CLOSEAPPLICATIONS')
if ((Compare-Object $expectedSilentArgs @($manifest.installer.silentArgs))) {
    throw "The AppFleet manifest has an unexpected silentArgs contract."
}

Write-Host "[PASS] Release assets match the ScreenPilot $Version AppFleet contract."
