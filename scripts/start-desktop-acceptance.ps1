[CmdletBinding()]
param(
    [switch]$Build,
    [ValidateSet('cloud', 'local')]
    [string]$Runtime = 'cloud',
    [string]$Context = 'desktop-linux'
)
$ErrorActionPreference = 'Stop'
$backendRoot = Split-Path -Parent $PSScriptRoot
$composeFile = Join-Path $backendRoot 'docker-compose.desktop.yml'
$environmentFile = Join-Path $backendRoot '.env'
if (-not (Test-Path -LiteralPath $environmentFile)) {
    throw 'Missing .env. Run scripts/initialize-desktop-env.py with Python first; generated values are never printed.'
}
$dockerArguments = @('--context', $Context, 'compose', '--env-file', $environmentFile, '-f', $composeFile, '--profile', 'local', '--profile', 'cloud')
& docker @dockerArguments config --quiet
if ($LASTEXITCODE -ne 0) { throw 'Desktop Compose configuration is incomplete.' }
if ($Build) {
    & docker @dockerArguments build "backend-$Runtime" frontend
    if ($LASTEXITCODE -ne 0) { throw 'Desktop source build failed.' }
}
# Both profiles appear in Desktop, but their backends share one upstream alias.
& docker @dockerArguments create --no-build --pull missing
if ($LASTEXITCODE -ne 0) { throw 'Could not create Desktop profile containers.' }
if ($Runtime -eq 'cloud') {
    & docker @dockerArguments stop backend-local migrations-local seed-local postgres-local s3mock
} else {
    & docker @dockerArguments stop backend-cloud migrations-cloud
}
if ($LASTEXITCODE -ne 0) { throw 'Could not stop the inactive Desktop profile.' }
& docker @dockerArguments up -d --no-build --pull never --wait --wait-timeout 300 "backend-$Runtime"
if ($LASTEXITCODE -ne 0) { throw 'Selected backend/migration gate did not become healthy; frontend was not started.' }
& docker @dockerArguments up -d --no-build --pull never --wait --wait-timeout 90 frontend
if ($LASTEXITCODE -ne 0) { throw 'Desktop frontend did not become healthy.' }
& docker @dockerArguments ps -a
if ($LASTEXITCODE -ne 0) { throw 'Could not read Desktop stack status.' }
