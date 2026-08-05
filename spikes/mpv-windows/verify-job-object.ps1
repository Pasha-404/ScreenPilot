[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'

$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
Set-Location -LiteralPath $projectRoot

$existingMpvIds = @(Get-Process -Name mpv -ErrorAction SilentlyContinue | ForEach-Object Id)

Write-Host '[INFO] Starting the crash-containment probe. A non-zero Gradle exit code is expected.'
& .\gradlew.bat --no-daemon :screenpilot-app:run --args='mpv-spike --crash-after-job-object'
$gradleExitCode = $LASTEXITCODE

Start-Sleep -Milliseconds 1500
$newMpv = @(Get-Process -Name mpv -ErrorAction SilentlyContinue |
    Where-Object { $_.Id -notin $existingMpvIds })

if ($gradleExitCode -eq 0) {
    throw 'FAIL: the crash-containment probe unexpectedly returned success.'
}

if ($newMpv.Count -ne 0) {
    throw ('FAIL: mpv.exe remained after Java halted. Process IDs: ' + ($newMpv.Id -join ', '))
}

Write-Host '[PASS] Java halted and no new mpv.exe process remained. Job Object containment works.'
