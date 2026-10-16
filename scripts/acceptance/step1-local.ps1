param(
    [string]$PythonExe,
    [string]$Image = 'tcg-accept-20261016-backend:current',
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$ExpectedImageId,
    [string]$EvidencePath = (Join-Path $PSScriptRoot '../../docs/evidence/step1-local-2026-10-16.md')
)

$ErrorActionPreference = 'Stop'
$taskPrefix = 'tcg-accept-20261016-step1'
$network = "$taskPrefix-net"
$postgres = "$taskPrefix-postgres"
$s3mock = "$taskPrefix-s3mock"
$backendA = "$taskPrefix-backend-a"
$backendB = "$taskPrefix-backend-b"
$s3Volume = "$taskPrefix-s3data"
$image = $Image
$pgImage = 'postgres:16-alpine'
$s3Image = 'adobe/s3mock:5.1.0'
$pgPort = 15432
$s3Port = 19090
$backendAPort = 18081
$backendBPort = 18082
$database = 'tcg_acceptance'
$dbUser = 'tcg_acceptance'
$dbPassword = 'tcg-acceptance-local-only'
$bucket = 'tcg-accept-20261016-images'
$accessKey = 'tcg-accept-local-access'
$secretKey = 'tcg-accept-local-secret'
$jwtSecret = 'tcg-acceptance-local-jwt-secret-at-least-32-bytes'
$publicBase = "http://127.0.0.1:$s3Port/$bucket"
$repoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$evidenceRoot = [System.IO.Path]::GetFullPath((Join-Path $repoRoot 'docs/evidence'))
$evidencePath = [System.IO.Path]::GetFullPath($EvidencePath)
$evidenceRootPrefix = $evidenceRoot.TrimEnd([System.IO.Path]::DirectorySeparatorChar) + [System.IO.Path]::DirectorySeparatorChar
if (-not $evidencePath.StartsWith($evidenceRootPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "EvidencePath must be inside '$evidenceRoot'."
}
$actualImageId = $null
$runtimeRevision = $null
$classPath = Join-Path $PSScriptRoot 'step1-local-classpath.tmp'

function Assert-PortUnused([int]$Port) {
    $listener = Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue
    if ($listener) { throw "Required isolated acceptance port $Port is already in use." }
}

function Assert-ResourceAbsent([string]$Kind, [string]$Name) {
    $found = docker $Kind inspect $Name 2>$null
    if ($LASTEXITCODE -eq 0) { throw "Refusing to reuse existing Docker $Kind '$Name'." }
}

function Wait-HttpReady([string]$Url, [int]$TimeoutSeconds = 180) {
    $until = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $until) {
        try {
            $response = Invoke-WebRequest -Uri $Url -TimeoutSec 3 -UseBasicParsing
            if ($response.StatusCode -eq 200) { return }
        } catch { }
        Start-Sleep -Seconds 2
    }
    throw "Readiness timed out for isolated endpoint $Url."
}

function Save-Evidence([string]$StatusLine) {
    $elapsed = [int]((Get-Date) - $startedAt).TotalSeconds
    $body = @(
        '# Step 1 local S3 acceptance — 2026-10-16',
        '',
        "Run $StatusLine at $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss K') (elapsed ${elapsed}s).",
        "Image: ``$image``; Docker image ID ``$actualImageId``; runtime source revision ``$runtimeRevision``.",
        '',
        'All resources were isolated under the ``tcg-accept-20261016-step1`` prefix. Existing local cloud backend and frontend were not targeted.',
        '',
        '## Results',
        ''
    ) + @($results | ForEach-Object { "- $_" }) + @('', 'No environment values, credentials, JDBC URLs, tokens, or response/log bodies are recorded.')
    $evidenceDir = Split-Path -Parent $evidencePath
    New-Item -ItemType Directory -Force -Path $evidenceDir | Out-Null
    Set-Content -LiteralPath $evidencePath -Value $body -Encoding utf8
}

if (-not $PythonExe) {
    $candidates = @(
        (Join-Path (Get-Location) 'target/acceptance-tools/Scripts/python.exe'),
        (Join-Path (Get-Location) 'target/acceptance-tools/.venv/Scripts/python.exe')
    )
    $PythonExe = $candidates | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
}
if (-not $PythonExe -or -not (Test-Path -LiteralPath $PythonExe)) {
    throw 'The acceptance Python runtime is not present yet; pass its python.exe path after setup completes.'
}

foreach ($port in @($pgPort, $s3Port, $backendAPort, $backendBPort)) { Assert-PortUnused $port }
foreach ($kindAndName in @(
    @('container', $postgres), @('container', $s3mock),
    @('container', $backendA), @('container', $backendB),
    @('network', $network), @('volume', $s3Volume)
)) { Assert-ResourceAbsent $kindAndName[0] $kindAndName[1] }

docker image inspect $image *> $null
if ($LASTEXITCODE -ne 0) { throw "Current-source image '$image' is not available locally." }
$actualImageId = [string](docker image inspect --format '{{.Id}}' $image)
if ($LASTEXITCODE -ne 0 -or -not $actualImageId) { throw "Could not determine Docker image ID for '$image'." }
if ($actualImageId.Trim() -ine $ExpectedImageId.Trim()) {
    throw "Image ID mismatch for '$image': expected '$ExpectedImageId', found '$($actualImageId.Trim())'."
}
$actualImageId = $actualImageId.Trim()
$runtimeRevision = [string](docker image inspect --format '{{index .Config.Labels "org.opencontainers.image.revision"}}' $image)
if ($LASTEXITCODE -ne 0) { throw "Could not determine runtime source revision for '$image'." }
$runtimeRevision = $runtimeRevision.Trim()
if (-not $runtimeRevision -or $runtimeRevision -eq '<no value>' -or $runtimeRevision -eq 'unknown') {
    throw "Image '$image' does not report an actual OCI source revision."
}

$createdNetwork = $false
$createdVolume = $false
$createdContainers = [System.Collections.Generic.List[string]]::new()
$results = [System.Collections.Generic.List[string]]::new()
$startedAt = Get-Date
try {
    docker network create $network *> $null
    if ($LASTEXITCODE -ne 0) { throw 'Could not create isolated acceptance network.' }
    $createdNetwork = $true
    docker volume create $s3Volume *> $null
    if ($LASTEXITCODE -ne 0) { throw 'Could not create isolated S3 acceptance volume.' }
    $createdVolume = $true

    docker run -d --name $postgres --network $network --publish "127.0.0.1:${pgPort}:5432" `
        --tmpfs '/var/lib/postgresql/data:rw,size=1g' `
        --env "POSTGRES_DB=$database" --env "POSTGRES_USER=$dbUser" --env "POSTGRES_PASSWORD=$dbPassword" $pgImage *> $null
    if ($LASTEXITCODE -ne 0) { throw 'Could not start isolated Postgres.' }
    $createdContainers.Add($postgres)

    docker run -d --name $s3mock --network $network --publish "127.0.0.1:${s3Port}:9090" `
        --env "COM_ADOBE_TESTING_S3MOCK_STORE_INITIAL_BUCKETS=$bucket" `
        --env 'COM_ADOBE_TESTING_S3MOCK_STORE_ROOT=/s3mockroot' `
        --env 'COM_ADOBE_TESTING_S3MOCK_STORE_RETAIN_FILES_ON_EXIT=true' `
        --volume "${s3Volume}:/s3mockroot" $s3Image *> $null
    if ($LASTEXITCODE -ne 0) { throw 'Could not start isolated S3Mock.' }
    $createdContainers.Add($s3mock)
    Wait-HttpReady "http://127.0.0.1:$s3Port/favicon.ico"
    $results.Add('PASS: isolated S3Mock started with a task-scoped bucket and persistent task-scoped volume.')

    $pgUntil = (Get-Date).AddSeconds(120)
    do {
        docker exec $postgres pg_isready -U $dbUser -d $database *> $null
        if ($LASTEXITCODE -eq 0) { break }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $pgUntil)
    if ($LASTEXITCODE -ne 0) { throw 'Isolated Postgres did not become ready.' }

    $migrationOutput = & docker run --rm --network $network --env 'SPRING_PROFILES_ACTIVE=migration' `
        --env 'FLYWAY_ENABLED=true' --env "DB_URL=jdbc:postgresql://${postgres}:5432/${database}" `
        --env "DB_USERNAME=$dbUser" --env "DB_PASSWORD=$dbPassword" `
        --entrypoint java $image '-Dloader.main=com.mythix.tcg.DatabaseMigrationApplication' `
        '-cp' '/app/app.jar' 'org.springframework.boot.loader.launch.PropertiesLauncher' 2>&1
    $migrationExit = $LASTEXITCODE
    if ($migrationExit -ne 0) {
        $migrationText = $migrationOutput -join "`n"
        $failureCategory = if ($migrationText -match 'Could not find or load main class\s+([^\s]+)') {
            "class launch ($($Matches[1]))"
        } elseif ($migrationText -match 'ClassNotFoundException:\s*([^\s]+)') {
            "missing runtime class ($($Matches[1]))"
        } elseif ($migrationText -match 'NoClassDefFoundError:\s*([^\s]+)') {
            "missing runtime dependency ($($Matches[1]))"
        } elseif ($migrationText -match 'UnknownHost|Connection refused|Connect timed out|password authentication failed') {
            'database connection or authentication'
        } elseif ($migrationText -match 'FlywayValidateException|Validate failed|Migration.*failed|FlywayException') {
            'Flyway migration or validation'
        } elseif ($migrationText -match 'Schema-validation|SchemaManagementException|ddl-auto') {
            'Hibernate schema validation'
        } else { 'unclassified runner failure' }
        $results.Add("FAIL: Flyway migration and Hibernate validation gate exited $migrationExit (category: $failureCategory; raw output suppressed).")
        throw 'Required Flyway migration runner failed; API replicas were not started.'
    }
    $results.Add('PASS: current-source migration runner completed against isolated Postgres.')

    $historyResult = [string](docker exec $postgres psql -U $dbUser -d $database -At -F '|' -v ON_ERROR_STOP=1 `
        -c "SELECT count(*) FILTER (WHERE success), count(*) FILTER (WHERE version = '10' AND success) FROM flyway_schema_history;")
    if ($LASTEXITCODE -ne 0) { throw 'Could not query Flyway history after migration.' }
    if ($historyResult.Trim() -ne '10|1') { throw "Flyway history expected 10 successful migrations including exactly one version 10; observed '$($historyResult.Trim())'." }
    $results.Add('PASS: isolated PostgreSQL Flyway history records all 10 successful migrations, including version 10.')

    $backendEnv = @(
        '--env', 'SPRING_PROFILES_ACTIVE=dev',
        '--env', 'SPRING_JPA_HIBERNATE_DDL_AUTO=validate',
        '--env', "DB_URL=jdbc:postgresql://${postgres}:5432/${database}",
        '--env', "DB_USERNAME=$dbUser", '--env', "DB_PASSWORD=$dbPassword",
        '--env', "APP_JWT_SECRET=$jwtSecret", '--env', 'FLYWAY_ENABLED=false',
        '--env', "OBJECT_STORAGE_ENDPOINT=http://${s3mock}:9090",
        '--env', 'OBJECT_STORAGE_REGION=us-east-1', '--env', "OBJECT_STORAGE_BUCKET=$bucket",
        '--env', "OBJECT_STORAGE_PUBLIC_BASE_URL=$publicBase", '--env', 'OBJECT_STORAGE_PATH_STYLE=true',
        '--env', "OBJECT_STORAGE_ACCESS_KEY=$accessKey", '--env', "OBJECT_STORAGE_SECRET_KEY=$secretKey"
    )
    docker run -d --name $backendA --network $network --publish "127.0.0.1:${backendAPort}:8080" @backendEnv $image *> $null
    if ($LASTEXITCODE -ne 0) { throw 'Could not start isolated backend replica A.' }
    $createdContainers.Add($backendA)
    Wait-HttpReady "http://127.0.0.1:$backendAPort/actuator/health/readiness"
    $results.Add('PASS: replica A became ready with Hibernate schema validation against the Flyway-created schema.')
    docker run -d --name $backendB --network $network --publish "127.0.0.1:${backendBPort}:8080" @backendEnv $image *> $null
    if ($LASTEXITCODE -ne 0) { throw 'Could not start isolated backend replica B.' }
    $createdContainers.Add($backendB)
    Wait-HttpReady "http://127.0.0.1:$backendBPort/actuator/health/readiness"
    $results.Add('PASS: both backend replicas became ready with Hibernate schema validation against the same Flyway-created Postgres schema and S3Mock.')

    $pythonArgs = @(
        (Join-Path $PSScriptRoot 'step1-local-api-acceptance.py'),
        '--backend-a', "http://127.0.0.1:$backendAPort",
        '--backend-b', "http://127.0.0.1:$backendBPort",
        '--db-host', '127.0.0.1', '--db-port', "$pgPort", '--db-name', $database,
        '--db-user', $dbUser, '--bucket', $bucket, '--s3-endpoint', "http://127.0.0.1:$s3Port",
        '--public-base', $publicBase
    )
    $env:TCG_ACCEPT_DB_PASSWORD = $dbPassword
    $env:TCG_ACCEPT_S3_ACCESS_KEY = $accessKey
    $env:TCG_ACCEPT_S3_SECRET_KEY = $secretKey
    $env:TCG_ACCEPT_JWT_SECRET = $jwtSecret
    $pythonOutput = & $PythonExe @pythonArgs
    if ($LASTEXITCODE -ne 0) { throw 'Local API/S3 acceptance assertions failed (response and log bodies suppressed).' }
    foreach ($line in $pythonOutput) { $results.Add([string]$line) }
    Remove-Item Env:TCG_ACCEPT_DB_PASSWORD, Env:TCG_ACCEPT_S3_ACCESS_KEY, Env:TCG_ACCEPT_S3_SECRET_KEY, Env:TCG_ACCEPT_JWT_SECRET -ErrorAction SilentlyContinue

    docker restart $s3mock *> $null
    if ($LASTEXITCODE -ne 0) { throw 'Task-scoped S3Mock restart failed.' }
    Wait-HttpReady "http://127.0.0.1:$s3Port/favicon.ico"
    foreach ($backend in @($backendA, $backendB)) {
        docker restart $backend *> $null
        if ($LASTEXITCODE -ne 0) { throw "Task-scoped backend restart failed for $backend." }
    }
    Wait-HttpReady "http://127.0.0.1:$backendAPort/actuator/health/readiness"
    Wait-HttpReady "http://127.0.0.1:$backendBPort/actuator/health/readiness"
    $results.Add('PASS: both current-source backend replicas restarted after S3Mock; readiness recovered before the retained-object check.')
    $restartArgs = @(
        (Join-Path $PSScriptRoot 'step1-local-api-acceptance.py'), '--restart-check',
        '--backend-a', "http://127.0.0.1:$backendAPort", '--backend-b', "http://127.0.0.1:$backendBPort",
        '--db-host', '127.0.0.1', '--db-port', "$pgPort", '--db-name', $database,
        '--db-user', $dbUser, '--bucket', $bucket, '--s3-endpoint', "http://127.0.0.1:$s3Port",
        '--public-base', $publicBase
    )
    $env:TCG_ACCEPT_DB_PASSWORD = $dbPassword
    $env:TCG_ACCEPT_S3_ACCESS_KEY = $accessKey
    $env:TCG_ACCEPT_S3_SECRET_KEY = $secretKey
    $env:TCG_ACCEPT_JWT_SECRET = $jwtSecret
    $restartOutput = & $PythonExe @restartArgs
    if ($LASTEXITCODE -ne 0) { throw 'S3Mock restart persistence assertion failed (response and log bodies suppressed).' }
    foreach ($line in $restartOutput) { $results.Add([string]$line) }
    Remove-Item Env:TCG_ACCEPT_DB_PASSWORD, Env:TCG_ACCEPT_S3_ACCESS_KEY, Env:TCG_ACCEPT_S3_SECRET_KEY, Env:TCG_ACCEPT_JWT_SECRET -ErrorAction SilentlyContinue

    $results.Add('PASS: retained fixture was read after restarting S3Mock and both backend replicas; the persisted bytes matched the original fixture.')
    $results.Add('PENDING: Kubernetes/cloud replica parity is outside this isolated local acceptance run.')
    $gateFailures = @($results | Where-Object { $_.StartsWith('FAIL:') })
    $completion = if ($gateFailures.Count -gt 0) { 'completed with failed gate(s)' } else { 'completed' }
    Save-Evidence $completion
    Write-Output "Evidence written: $evidencePath"
    $results | ForEach-Object { Write-Output $_ }
}
catch {
    $results.Add('FAIL: ' + $_.Exception.Message)
    Save-Evidence 'failed'
    throw
}
finally {
    Remove-Item Env:TCG_ACCEPT_DB_PASSWORD, Env:TCG_ACCEPT_S3_ACCESS_KEY, Env:TCG_ACCEPT_S3_SECRET_KEY, Env:TCG_ACCEPT_JWT_SECRET -ErrorAction SilentlyContinue
    foreach ($container in $createdContainers) {
        docker rm -v --force $container *> $null
        if ($LASTEXITCODE -ne 0) { $results.Add("FAIL: cleanup could not remove task-created container '$container'.") }
    }
    if ($createdVolume) {
        docker volume rm $s3Volume *> $null
        if ($LASTEXITCODE -ne 0) { $results.Add("FAIL: cleanup could not remove task-created volume '$s3Volume'.") }
    }
    if ($createdNetwork) {
        docker network rm $network *> $null
        if ($LASTEXITCODE -ne 0) { $results.Add("FAIL: cleanup could not remove task-created network '$network'.") }
    }
    Remove-Item -LiteralPath $classPath -ErrorAction SilentlyContinue
    if ($results | Where-Object { $_.StartsWith('FAIL:') }) { Save-Evidence 'failed' }
}

if (@($results | Where-Object { $_.StartsWith('FAIL:') }).Count -gt 0) { exit 1 }
