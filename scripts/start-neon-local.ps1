$ErrorActionPreference = "Stop"

$backendRoot = Split-Path -Parent $PSScriptRoot
Set-Location $backendRoot

if (-not (Test-Path ".env")) {
    throw "Missing .env in $backendRoot. Copy .env.example and add the Neon credentials first."
}

Get-Content ".env" | ForEach-Object {
    if ($_ -match '^\s*([^#=]+)=(.*)$') {
        Set-Item -Path ("Env:" + $matches[1]) -Value $matches[2]
    }
}

$requiredVariables = @("NEON_JDBC_URL", "NEON_DB_USERNAME", "NEON_DB_PASSWORD", "APP_JWT_SECRET")
$missingVariables = @($requiredVariables | Where-Object {
    $value = [Environment]::GetEnvironmentVariable($_)
    [string]::IsNullOrWhiteSpace($value) -or $value -match '<[^>]+>|replace-with'
})
if ($missingVariables.Count -gt 0) {
    throw "Missing required Neon runtime variable(s) in .env: $($missingVariables -join ', '). Run scripts/setup-shared-neon.ps1 or fill them locally."
}

docker compose -f docker-compose.desktop.yml --profile local --profile cloud stop backend-local backend-cloud s3mock postgres-local
if ($LASTEXITCODE -ne 0) {
    throw "Failed to stop Docker backend runtimes before starting the source runtime."
}

$env:SPRING_PROFILES_ACTIVE = "neon"
Write-Output "Starting the backend from source with the Neon profile. Credentials are not displayed."
& .\mvnw.cmd spring-boot:run
exit $LASTEXITCODE
