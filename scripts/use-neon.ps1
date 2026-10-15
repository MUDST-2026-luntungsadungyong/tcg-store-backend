param(
    [switch]$Build,
    [switch]$SkipEnvRefresh
)

$ErrorActionPreference = "Stop"
$backendRoot = Split-Path -Parent $PSScriptRoot
Set-Location $backendRoot

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    throw "Docker CLI was not found. Start Docker Desktop and install the Docker CLI before switching runtimes."
}

Write-Output "Stopping other backend runtimes..."
docker compose -f docker-compose.desktop.yml --profile local --profile cloud stop backend-local backend-cloud s3mock postgres-local
if ($LASTEXITCODE -ne 0) {
    throw "Failed to stop the other backend runtimes."
}

if (-not $SkipEnvRefresh) {
    & "$PSScriptRoot\setup-shared-neon.ps1"
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to prepare the shared Neon environment."
    }
}

$arguments = @("compose", "-f", "docker-compose.desktop.yml", "--profile", "cloud", "up", "-d")
if ($Build) {
    $arguments += "--build"
}
$arguments += @("backend-cloud", "frontend")

Write-Output "Starting the shared Neon runtime..."
docker @arguments
if ($LASTEXITCODE -ne 0) {
    throw "Failed to start the Neon runtime."
}

Write-Output "Cloud runtime is active: Neon + backend + frontend."
