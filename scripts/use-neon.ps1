param(
    [switch]$Build,
    [switch]$SkipEnvRefresh
)

$ErrorActionPreference = "Stop"

Write-Output "Stopping the local PostgreSQL runtime..."
docker compose down
if ($LASTEXITCODE -ne 0) {
    throw "Failed to stop the local runtime."
}

if (-not $SkipEnvRefresh) {
    & "$PSScriptRoot\setup-shared-neon.ps1"
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to prepare the shared Neon environment."
    }
}

$arguments = @("compose", "-f", "docker-compose.neon.yml", "up", "-d")
if ($Build) {
    $arguments += "--build"
}

Write-Output "Starting the shared Neon runtime..."
docker @arguments
if ($LASTEXITCODE -ne 0) {
    throw "Failed to start the Neon runtime."
}

Write-Output "Neon runtime is active: Neon database + backend + frontend."
