param(
    [string]$ProjectId = "hidden-block-50177803",
    [string]$Branch = "development",
    [string]$NeonEnvFile = ".env",
    [string]$OutputFile = ".env"
)

$ErrorActionPreference = "Stop"

$neon = Get-Command neon -ErrorAction SilentlyContinue
if (-not $neon) {
    throw "Neon CLI is not installed. Run: npm install --global neon@latest"
}

Write-Output "Linking Neon project $ProjectId to shared branch $Branch..."
& $neon.Source link `
    --project-id $ProjectId `
    --branch $Branch `
    --no-env-pull `
    --no-config `
    --yes

if ($LASTEXITCODE -ne 0) {
    throw "Neon link failed. Run 'neon login' and confirm that your account can access the project."
}

& $neon.Source env pull `
    --project-id $ProjectId `
    --branch $Branch `
    --file $NeonEnvFile `
    --service postgres

if ($LASTEXITCODE -ne 0) {
    throw "Neon env pull failed."
}

& "$PSScriptRoot\prepare-neon-env.ps1" `
    -InputFile $NeonEnvFile `
    -OutputFile $OutputFile

Write-Output "Shared Neon development environment is ready."
Write-Output "Start it with: docker compose -f docker-compose.neon.yml up -d --build"
