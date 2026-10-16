[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$Image = 'prom/prometheus:v3.15.0'
$Root = (Resolve-Path -LiteralPath $PSScriptRoot).Path
$RepositoryRoot = (Resolve-Path -LiteralPath (Join-Path $Root '..\..')).Path
$Promtool = Join-Path $RepositoryRoot 'target\observability\prometheus-3.15.0.windows-amd64\promtool.exe'
# Official v3.15.0 windows-amd64 archive SHA256: 5d333b385557d9adc2ff015d13da9809baccc52fb800a82d1e5c94b79258f87e
$PromtoolSha256 = 'b02a6d8726533fbf59d40fad83646105429b6ed2f1b928f9d44659c67a83659b'
$Docker = Get-Command docker -ErrorAction SilentlyContinue
if (-not $Docker) {
    $DockerExe = 'C:\Program Files\Docker\Docker\resources\bin\docker.exe'
    if (Test-Path -LiteralPath $DockerExe) {
        $Docker = Get-Item -LiteralPath $DockerExe
    }
}
$Mount = "${Root}:/etc/observability:ro"
$PrometheusConfig = '/etc/observability/prometheus.yml'
$Rules = '/etc/observability/rules'
$TestFile = '/etc/observability/tests/alerts.test.yml'

if (Test-Path -LiteralPath $Promtool) {
    $ActualSha256 = (Get-FileHash -LiteralPath $Promtool -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($ActualSha256 -ne $PromtoolSha256) {
        throw "Local promtool SHA256 mismatch: expected $PromtoolSha256, got $ActualSha256."
    }

    & $Promtool check config (Join-Path $Root 'prometheus.yml')
    if ($LASTEXITCODE -ne 0) { throw 'promtool check config failed.' }

    & $Promtool check rules (Join-Path $Root 'rules\recording.yml') (Join-Path $Root 'rules\alerts.yml')
    if ($LASTEXITCODE -ne 0) { throw 'promtool check rules failed.' }

    & $Promtool test rules (Join-Path $Root 'tests\alerts.test.yml')
    if ($LASTEXITCODE -ne 0) { throw 'promtool rule tests failed.' }

    Write-Output 'Observability validation passed with the checksum-pinned Prometheus 3.15.0 Windows promtool binary (offline).'
    exit 0
}

if (-not $Docker) {
    throw "Checksum-pinned local promtool was not found at $Promtool and Docker CLI is unavailable."
}

$DockerPath = if ($Docker.Source) { $Docker.Source } else { $Docker.FullName }
& $DockerPath image inspect $Image *> $null
if ($LASTEXITCODE -ne 0) {
    throw "Pinned image $Image is not present locally and the optional local promtool is missing. This validator is offline and will not pull it."
}

& $DockerPath run --rm --network none --volume $Mount --entrypoint /bin/promtool $Image check config $PrometheusConfig
if ($LASTEXITCODE -ne 0) { throw 'promtool check config failed.' }

& $DockerPath run --rm --network none --volume $Mount --entrypoint /bin/promtool $Image check rules "$Rules/recording.yml" "$Rules/alerts.yml"
if ($LASTEXITCODE -ne 0) { throw 'promtool check rules failed.' }

& $DockerPath run --rm --network none --volume $Mount --entrypoint /bin/promtool $Image test rules $TestFile
if ($LASTEXITCODE -ne 0) { throw 'promtool rule tests failed.' }

Write-Output "Observability validation passed with $Image (offline; container networking disabled)."
