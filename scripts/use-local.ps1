param(
    [switch]$Build
)

$ErrorActionPreference = "Stop"
$backendRoot = Split-Path -Parent $PSScriptRoot
Set-Location $backendRoot

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    throw "Docker CLI was not found. Start Docker Desktop and install the Docker CLI before switching runtimes."
}

Write-Output "Stopping other backend runtimes..."
docker compose -f docker-compose.desktop.yml --profile local --profile cloud stop backend-local backend-cloud s3mock
if ($LASTEXITCODE -ne 0) {
    throw "Failed to stop the other backend runtimes."
}

$services = @("postgres-local", "backend-local", "frontend")
$profileArguments = @("--profile", "local")

$arguments = @("compose", "-f", "docker-compose.desktop.yml") + $profileArguments + @("up", "-d")
if ($Build) {
    $arguments += "--build"
}
$arguments += $services

Write-Output "Starting local PostgreSQL + S3Mock + backend + frontend..."
docker @arguments
if ($LASTEXITCODE -ne 0) {
    throw "Failed to start the local runtime."
}

Write-Output "Local S3 runtime is active: PostgreSQL + S3Mock + backend + frontend."
