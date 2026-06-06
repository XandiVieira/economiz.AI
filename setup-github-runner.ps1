# Install a self-hosted GitHub Actions runner on this machine so pushes to
# `development` auto-deploy the dev server.
#
# Run in a NORMAL (non-admin) PowerShell:
#   powershell -ExecutionPolicy Bypass -File .\setup-github-runner.ps1 -Token "RUNNER_TOKEN"
#
# Get RUNNER_TOKEN from:
#   GitHub repo -> Settings -> Actions -> Runners -> New self-hosted runner
#   -> Windows x64. Copy the token shown in the `./config.cmd ... --token XXXX`
#   line (it expires in ~1 hour, so grab it right before running this).
# ---------------------------------------------------------------------------
param(
    [Parameter(Mandatory = $true)][string]$Token,
    [string]$RepoUrl = "https://github.com/XandiVieira/economiz.AI",
    [string]$Label   = "economizai-dev"
)

$ErrorActionPreference = "Stop"
$runnerDir = "C:\actions-runner"
$version   = "2.321.0"   # bump if GitHub requires newer; config will tell you
$zip       = "actions-runner-win-x64-$version.zip"
$url       = "https://github.com/actions/runner/releases/download/v$version/$zip"

New-Item -ItemType Directory -Force -Path $runnerDir | Out-Null
Set-Location $runnerDir

if (-not (Test-Path (Join-Path $runnerDir "config.cmd"))) {
    Write-Host "Downloading runner $version ..." -ForegroundColor Cyan
    Invoke-WebRequest -Uri $url -OutFile $zip
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    [System.IO.Compression.ZipFile]::ExtractToDirectory((Join-Path $runnerDir $zip), $runnerDir)
    Remove-Item $zip
}

Write-Host "Configuring runner for $RepoUrl (label: $Label) ..." -ForegroundColor Cyan
# --unattended + --replace makes re-runs idempotent. Runs as a Windows SERVICE
# so it starts on boot WITHOUT needing a logged-in session.
& "$runnerDir\config.cmd" --url $RepoUrl --token $Token --name "economizai-dev-box" --labels $Label --unattended --replace --runasservice

Write-Host "`nInstalling + starting the runner service ..." -ForegroundColor Cyan
& "$runnerDir\svc.cmd" install
& "$runnerDir\svc.cmd" start

Write-Host "`nDone. Runner registered and running as a service." -ForegroundColor Green
Write-Host "Verify at: $RepoUrl/settings/actions/runners (should show 'Idle')" -ForegroundColor Green
