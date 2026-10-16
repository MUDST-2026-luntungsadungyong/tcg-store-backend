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

$env:SPRING_PROFILES_ACTIVE = "neon"
& .\mvnw.cmd compile spring-boot:run
exit $LASTEXITCODE
