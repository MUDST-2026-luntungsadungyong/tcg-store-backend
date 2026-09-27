param(
    [switch]$Build
)

$ErrorActionPreference = "Stop"

Write-Output "Stopping the Neon runtime..."
docker compose -f docker-compose.neon.yml down
if ($LASTEXITCODE -ne 0) {
    throw "Failed to stop the Neon runtime."
}

$arguments = @("compose", "up", "-d")
if ($Build) {
    $arguments += "--build"
}

Write-Output "Starting the local PostgreSQL runtime..."
docker @arguments
if ($LASTEXITCODE -ne 0) {
    throw "Failed to start the local runtime."
}

Write-Output "Local runtime is active: PostgreSQL container + backend + frontend."
