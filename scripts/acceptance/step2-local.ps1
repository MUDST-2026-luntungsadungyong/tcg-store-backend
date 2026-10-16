param(
    [string]$Image = 'tcg-accept-20261016-backend:current',
    [Parameter(Mandatory = $true)][string]$ExpectedImageId,
    [string]$EvidencePath = 'docs/evidence/step2-local-2026-10-16.md'
)

$ErrorActionPreference = 'Stop'
$baseName = 'tcg-accept-20261016'
$networkName = "$baseName-db"
$containerName = "$baseName-postgres"
$results = [System.Collections.Generic.List[object]]::new()
$networkCreated = $false
$containerCreated = $false
$script:gateOwned = $false
$startedAt = [DateTimeOffset]::UtcNow

function Invoke-Docker {
    param([string[]]$DockerArgs, [string]$FailureLabel = 'Docker operation')

    $null = & docker @DockerArgs 2>&1
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0) {
        throw "$FailureLabel failed (exit $exitCode). Command output was suppressed."
    }
}

function Get-Scalar {
    param([string]$Database, [string]$Sql)

    $output = & docker exec $containerName psql -v ON_ERROR_STOP=1 -U postgres -d $Database -tA -c $Sql 2>&1
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0) {
        throw 'A PostgreSQL assertion query failed. Database output was suppressed.'
    }
    return (($output | Out-String).Trim())
}

function Add-Result {
    param([string]$Scenario, [string]$Status, [string]$Evidence)
    $results.Add([pscustomobject]@{ Scenario = $Scenario; Status = $Status; Evidence = $Evidence })
}

function Invoke-Migration {
    param([string]$Database, [switch]$CaptureFailure)

    $url = "jdbc:postgresql://$containerName`:5432/$Database"
    $runnerArgs = @(
        'run', '--rm', '--network', $networkName, '--entrypoint', 'java',
        '--env', "DB_URL=$url", '--env', 'DB_USERNAME=postgres', '--env', 'DB_PASSWORD=tcg-local-acceptance-only',
        '--env', 'FLYWAY_ENABLED=true', '--env', 'SPRING_PROFILES_ACTIVE=migration',
        $Image, '-Dloader.main=com.mythix.tcg.DatabaseMigrationApplication',
        '-cp', '/app/app.jar', 'org.springframework.boot.loader.launch.PropertiesLauncher'
    )
    $runnerOutput = & docker @runnerArgs 2>&1
    $code = $LASTEXITCODE
    if ($code -ne 0) {
        $joined = $runnerOutput -join "`n"
        $failureClass = if ($joined -match 'UnknownHost|Connection refused|Connect timed out|password authentication failed') {
            'database connection or authentication'
        } elseif ($joined -match 'FlywayValidateException|Validate failed|Migration.*failed|FlywayException') {
            'Flyway migration or validation'
        } elseif ($joined -match 'SchemaManagementException') {
            $match = [regex]::Match($joined, 'SchemaManagementException:\s*([^\r\n]+)')
            if (-not $match.Success) { $match = [regex]::Match($joined, 'Schema-validation:\s*([^\r\n"}]+)') }
            if ($match.Success) {
                $detail = $match.Groups[1].Value -replace '\\n.*$', '' -replace '\\t.*$', ''
                $detail = $detail -replace '","(?:logger_name|thread_name|level|level_value|tags|application|environment)".*$', ''
                $detail = $detail.Trim().TrimEnd('"', ',')
                "Hibernate schema validation: $($detail.Trim())"
            } else {
                'Hibernate SchemaManagementException; structured output omitted the mismatch text'
            }
        } elseif ($joined -match 'ClassNotFoundException:\s*([A-Za-z0-9_.$]+)') {
            "missing runtime class $($Matches[1])"
        } elseif ($joined -match 'NoClassDefFoundError:\s*([A-Za-z0-9_.$/]+)') {
            "missing runtime dependency $($Matches[1])"
        } elseif ($joined -match 'Could not find or load main class') {
            'migration runner main class could not be loaded'
        } elseif ($joined -match 'unrecognized option|could not create the java virtual machine') {
            'Java launcher argument parsing'
        } else {
            'unclassified runner failure'
        }
        $script:lastMigrationFailure = $failureClass
        if (-not $CaptureFailure) {
            throw "Migration runner failed for isolated scenario $Database (exit $code; category: $failureClass). Raw output suppressed."
        }
    } else {
        $script:lastMigrationFailure = ''
    }
    return $code
}

function Set-Status {
    param([string]$Scenario, [scriptblock]$Action)
    try {
        $evidence = & $Action
        if ([string]$evidence -match '^FAIL: (.*)$') {
            Add-Result $Scenario 'FAIL' $Matches[1]
            Write-Output "FAIL $Scenario — $($Matches[1])"
        } else {
            Add-Result $Scenario 'PASS' ([string]$evidence)
            Write-Output "PASS $Scenario — $evidence"
        }
    }
    catch {
        Add-Result $Scenario 'FAIL' $_.Exception.Message
        Write-Output "FAIL $Scenario — $($_.Exception.Message)"
    }
}

try {
    $actualImageId = (& docker image inspect $Image --format '{{.Id}}' 2>$null | Out-String).Trim()
    if ($LASTEXITCODE -ne 0 -or $actualImageId -ne $ExpectedImageId) {
        throw 'The current-source backend image is absent or its image ID does not match the supplied build receipt.'
    }

    $null = & docker container inspect $containerName 2>$null
    if ($LASTEXITCODE -eq 0) { throw 'The isolated container name is already in use; refusing to inspect or alter it.' }
    $null = & docker network inspect $networkName 2>$null
    if ($LASTEXITCODE -eq 0) { throw 'The isolated network name is already in use; refusing to inspect or alter it.' }

    Invoke-Docker -DockerArgs @('network', 'create', $networkName) -FailureLabel 'Creating isolated network'
    $networkCreated = $true
    Invoke-Docker -DockerArgs @(
        'run', '-d', '--name', $containerName, '--network', $networkName,
        '--tmpfs', '/var/lib/postgresql/data:rw,noexec,nosuid,size=768m',
        '--env', 'POSTGRES_DB=tcg_acceptance', '--env', 'POSTGRES_USER=postgres',
        '--env', 'POSTGRES_PASSWORD=tcg-local-acceptance-only',
        'postgres:16-alpine'
    ) -FailureLabel 'Starting isolated PostgreSQL'
    $containerCreated = $true

    $ready = $false
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        $null = & docker exec $containerName pg_isready -U postgres -d tcg_acceptance 2>&1
        if ($LASTEXITCODE -eq 0) { $ready = $true; break }
        Start-Sleep -Seconds 2
    }
    if (-not $ready) { throw 'Isolated PostgreSQL did not become ready within 120 seconds.' }
    Add-Result 'Isolated PostgreSQL startup' 'PASS' 'PostgreSQL 16 container ready; tmpfs storage; no named or pre-existing volume.'

    Set-Status 'Fresh database and Hibernate validation' {
        $runnerCode = Invoke-Migration 'tcg_acceptance' -CaptureFailure
        $historyExists = Get-Scalar 'tcg_acceptance' "SELECT to_regclass('public.flyway_schema_history') IS NOT NULL;"
        $tableCount = Get-Scalar 'tcg_acceptance' "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='public';"
        if ($runnerCode -ne 0) {
            throw "runner exit=$runnerCode; $($script:lastMigrationFailure); Flyway history table exists=$historyExists; public table count=$tableCount."
        }
        $count = Get-Scalar 'tcg_acceptance' 'SELECT COUNT(*) FROM flyway_schema_history WHERE success;'
        if ($count -ne '10') { throw "Expected 10 successful migration records; observed $count." }
        "Flyway V1–V10 recorded successfully; migration application exited zero."
    }

    Set-Status 'Seed separation and repeat migration' {
        $seedStatus = 'PASS'
        try {
            foreach ($seed in @('02-data.sql', '03-profile-avatar.sql')) {
                Invoke-Docker -DockerArgs @('cp', (Join-Path (Get-Location) "infra/postgres/init/$seed"), "${containerName}:/tmp/$seed") -FailureLabel 'Copying isolated development seed'
                Invoke-Docker -DockerArgs @('exec', $containerName, 'psql', '-v', 'ON_ERROR_STOP=1', '-U', 'postgres', '-d', 'tcg_acceptance', '-f', "/tmp/$seed") -FailureLabel 'Applying development seed'
            }
        } catch { $seedStatus = 'FAIL (seed SQL could not apply to observed schema)' }
        $repeatCode = Invoke-Migration 'tcg_acceptance' -CaptureFailure
        $historyExists = Get-Scalar 'tcg_acceptance' "SELECT to_regclass('public.flyway_schema_history') IS NOT NULL;"
        if ($historyExists -eq 't') { $repeatCount = Get-Scalar 'tcg_acceptance' 'SELECT COUNT(*) FROM flyway_schema_history WHERE success;' }
        else { $repeatCount = 'absent' }
        if ($seedStatus -ne 'PASS' -or $repeatCode -ne 0) {
            "FAIL: seed=$seedStatus; repeat runner exit=$repeatCode; category=$($script:lastMigrationFailure); Flyway history exists=$historyExists; successful migrations=$repeatCount."
        } else {
        $cardCount = Get-Scalar 'tcg_acceptance' 'SELECT COUNT(*) FROM global_cards;'
        $userCount = Get-Scalar 'tcg_acceptance' 'SELECT COUNT(*) FROM users;'
        if ($repeatCount -ne '9' -or [int]$cardCount -lt 2000 -or [int]$userCount -lt 9) {
            throw "Repeat/seed assertions failed: migrations=$repeatCount, card rows=$cardCount, user rows=$userCount."
        }
        "Repeat retained 9 migrations; seeded row checks passed (cards=$cardCount, users=$userCount)."
        }
    }

    Set-Status 'Legacy V8 baseline adoption and V9 upgrade' {
        Invoke-Docker -DockerArgs @('exec', $containerName, 'createdb', '-U', 'postgres', 'tcg_upgrade') -FailureLabel 'Creating legacy database'
        $migrationFiles = Get-ChildItem 'src/main/resources/db/migration/V*.sql' | Sort-Object { [int]($_.Name -replace '^V(\d+).*', '$1') }
        foreach ($migration in $migrationFiles) {
            if ($migration.Name -match '^V9__') { break }
            $dest = "/tmp/$($migration.Name)"
            Invoke-Docker -DockerArgs @('cp', $migration.FullName, "${containerName}:$dest") -FailureLabel 'Copying legacy migration'
            Invoke-Docker -DockerArgs @('exec', $containerName, 'psql', '-v', 'ON_ERROR_STOP=1', '-U', 'postgres', '-d', 'tcg_upgrade', '-f', $dest) -FailureLabel 'Applying legacy migration'
        }
        $legacySql = "INSERT INTO users (email,password,full_name,role,seller_status) VALUES ('legacy-seller@example.test','not-a-real-password','Legacy Seller','USER','APPROVED'); INSERT INTO global_cards (id,name,supertype,set_name,card_number,image_small,image_large,market_price) VALUES ('legacy-card','Legacy Card','Pokémon','Legacy Set','1','small','large',10.00); INSERT INTO local_cards (seller_id,global_card_id,custom_image_url,condition,language,price,cost,on_hand,reserved,status) SELECT id,'legacy-card','https://example.test/legacy.png','NM','EN',20.00,10.00,2,0,'SHOW' FROM users WHERE email='legacy-seller@example.test'; ALTER TABLE global_cards RENAME TO cards; ALTER TABLE local_cards RENAME TO products; ALTER TABLE products RENAME COLUMN global_card_id TO card_id; ALTER TABLE cart_items RENAME COLUMN local_card_id TO product_id; ALTER TABLE collection_items RENAME COLUMN global_card_id TO card_id; ALTER TABLE order_items RENAME COLUMN local_card_id TO product_id; ALTER TABLE order_items RENAME COLUMN global_card_id TO card_id; ALTER TABLE order_items RENAME COLUMN card_name_snapshot TO product_name_snapshot;"
        Invoke-Docker -DockerArgs @('exec', $containerName, 'psql', '-v', 'ON_ERROR_STOP=1', '-U', 'postgres', '-d', 'tcg_upgrade', '-c', $legacySql) -FailureLabel 'Creating legacy V8 fixture'
        $before = Get-Scalar 'tcg_upgrade' 'SELECT COUNT(*) FROM products;'
        $upgradeCode = Invoke-Migration 'tcg_upgrade' -CaptureFailure
        $historyExists = Get-Scalar 'tcg_upgrade' "SELECT to_regclass('public.flyway_schema_history') IS NOT NULL;"
        if ($historyExists -eq 't') {
            $baseline = Get-Scalar 'tcg_upgrade' "SELECT COUNT(*) FROM flyway_schema_history WHERE type='BASELINE' AND version='8' AND success;"
            $v9 = Get-Scalar 'tcg_upgrade' "SELECT COUNT(*) FROM flyway_schema_history WHERE type='SQL' AND version='9' AND success;"
        } else { $baseline = 'unavailable'; $v9 = 'unavailable' }
        $canonicalExists = Get-Scalar 'tcg_upgrade' "SELECT to_regclass('public.local_cards') IS NOT NULL;"
        if ($canonicalExists -eq 't') { $after = Get-Scalar 'tcg_upgrade' 'SELECT COUNT(*) FROM local_cards;' }
        else { $after = 'unavailable' }
        $oldNames = Get-Scalar 'tcg_upgrade' "SELECT COUNT(*) FROM pg_class WHERE relnamespace='public'::regnamespace AND relname IN ('cards','products');"
        if ($upgradeCode -ne 0) {
            "FAIL: legacy fixture prepared from committed V1–V8 setup SQL; runner exit=$upgradeCode, category=$($script:lastMigrationFailure); Flyway history table exists=$historyExists, V8 baseline=$baseline, V9=$v9, listing rows before=$before after=$after, legacy table names=$oldNames."
        } elseif ($baseline -ne '1' -or $v9 -ne '1' -or $before -ne $after -or $oldNames -ne '0') {
            throw "Legacy upgrade assertions failed: baseline=$baseline, V9=$v9, before=$before, after=$after, legacy names=$oldNames."
        } else {
            'V8 baseline and V9 succeeded; the legacy listing count was preserved and old table names were removed.'
        }
    }

    Set-Status 'pg_dump / pg_restore and restored database validation' {
        $sourceDatabase = 'tcg_upgrade'
        $sourceHistory = Get-Scalar $sourceDatabase 'SELECT COUNT(*) FROM flyway_schema_history WHERE success;'
        $sourceTable = Get-Scalar $sourceDatabase "SELECT CASE WHEN to_regclass('public.global_cards') IS NOT NULL THEN 'global_cards' ELSE 'cards' END;"
        Invoke-Docker -DockerArgs @('exec', $containerName, 'pg_dump', '-U', 'postgres', '-Fc', '-f', '/tmp/tcg_acceptance.dump', $sourceDatabase) -FailureLabel 'Creating custom-format PostgreSQL backup'
        Invoke-Docker -DockerArgs @('exec', $containerName, 'createdb', '-U', 'postgres', 'tcg_restore') -FailureLabel 'Creating isolated restore target'
        Invoke-Docker -DockerArgs @('exec', $containerName, 'pg_restore', '--exit-on-error', '--no-owner', '-U', 'postgres', '-d', 'tcg_restore', '/tmp/tcg_acceptance.dump') -FailureLabel 'Restoring PostgreSQL backup'
        $sourceCount = Get-Scalar $sourceDatabase "SELECT COUNT(*) FROM $sourceTable;"
        $restoredCount = Get-Scalar 'tcg_restore' "SELECT COUNT(*) FROM $sourceTable;"
        if ($sourceCount -ne $restoredCount) { throw "Restore row count differed: source=$sourceCount, restored=$restoredCount." }
        $restoreRunnerCode = Invoke-Migration 'tcg_restore' -CaptureFailure
        if ($restoreRunnerCode -ne 0) {
            "FAIL: custom-format dump restored; $sourceTable row count matched ($restoredCount); restored-target runner exit=$restoreRunnerCode, category=$($script:lastMigrationFailure)."
        } else {
            $history = Get-Scalar 'tcg_restore' 'SELECT COUNT(*) FROM flyway_schema_history WHERE success;'
            if ($history -ne $sourceHistory) { throw "Restored database migration history count differed: restored=$history, source=$sourceHistory." }
            "Custom-format dump restored; $sourceTable count matched ($restoredCount); restored-target migration runner exited zero."
        }
    }

    Set-Status 'Checksum drift is rejected on an existing schema' {
        Invoke-Docker -DockerArgs @('exec', $containerName, 'createdb', '-U', 'postgres', '-T', 'tcg_acceptance', 'tcg_checksum') -FailureLabel 'Cloning isolated checksum fixture'
        Invoke-Docker -DockerArgs @('exec', $containerName, 'psql', '-v', 'ON_ERROR_STOP=1', '-U', 'postgres', '-d', 'tcg_checksum', '-c', "UPDATE flyway_schema_history SET checksum = checksum + 1 WHERE version = '10' AND type = 'SQL';") -FailureLabel 'Injecting isolated checksum drift'
        $driftCode = Invoke-Migration 'tcg_checksum' -CaptureFailure
        if ($driftCode -eq 0 -or $script:lastMigrationFailure -ne 'Flyway migration or validation') {
            throw 'Migration runner did not reject checksum drift through Flyway validation.'
        }
        'Runner exited nonzero with Flyway validation failure on checksum drift; unchanged schema alone cannot pass the release gate.'
    }

    Set-Status 'Compose migration failure blocks dependent backend API container' {
        $composeFile = Join-Path (Get-Location) 'scripts/acceptance/step2-local-gate.compose.yml'
        $project = 'tcg-accept-20261016-gate'
        foreach ($name in @(
            'tcg-accept-20261016-gate-postgres', 'tcg-accept-20261016-gate-prepare',
            'tcg-accept-20261016-gate-migration', 'tcg-accept-20261016-gate-api'
        )) {
            $null = & docker container inspect $name 2>$null
            if ($LASTEXITCODE -eq 0) { throw "Compose gate container name $name is already occupied; refusing to reuse it." }
        }
        $null = & docker network inspect "$project`_default" 2>$null
        if ($LASTEXITCODE -eq 0) { throw 'Compose gate network already exists; refusing to reuse it.' }

        $script:gateOwned = $true
        $savedGateImage = $env:TCG_ACCEPT_BACKEND_IMAGE
        $env:TCG_ACCEPT_BACKEND_IMAGE = $Image
        try {
            $composeOutput = & docker compose --project-name $project --file $composeFile up api 2>&1
            $composeExit = $LASTEXITCODE
        } finally {
            if ($null -eq $savedGateImage) { Remove-Item Env:TCG_ACCEPT_BACKEND_IMAGE -ErrorAction SilentlyContinue }
            else { $env:TCG_ACCEPT_BACKEND_IMAGE = $savedGateImage }
        }
        $migrationState = (& docker inspect --format '{{.State.Status}}:{{.State.ExitCode}}' 'tcg-accept-20261016-gate-migration' 2>$null | Out-String).Trim()
        if ($LASTEXITCODE -ne 0) { throw 'Compose did not leave an inspectable migration result.' }
        $apiState = (& docker inspect --format '{{.State.Status}}:{{.State.StartedAt}}' 'tcg-accept-20261016-gate-api' 2>$null | Out-String).Trim()
        $apiInspectExit = $LASTEXITCODE
        if ($migrationState -notmatch '^exited:([1-9][0-9]*)$') {
            throw 'The deliberately failing migration service did not exit nonzero.'
        }
        if ($apiInspectExit -eq 0 -and $apiState -notmatch '^created:0001-01-01') {
            throw "The dependent API container started or changed state ($apiState)."
        }
        if ($composeExit -eq 0) { throw 'Compose unexpectedly reported success for the failing migration chain.' }
        "Compose returned nonzero ($composeExit); migration state=$migrationState; dependent API was never started."
    }

    $scenarioResults = @($results | Where-Object { $_.Scenario -in @(
        'Fresh database and Hibernate validation', 'Seed separation and repeat migration',
        'Legacy V8 baseline adoption and V9 upgrade', 'pg_dump / pg_restore and restored database validation',
        'Checksum drift is rejected on an existing schema',
        'Compose migration failure blocks dependent backend API container'
    ) })
    $failedScenarios = @($scenarioResults | Where-Object Status -eq 'FAIL').Count
    $passedScenarios = @($scenarioResults | Where-Object Status -eq 'PASS').Count
    if ($failedScenarios -gt 0) {
        Add-Result 'Result' 'FAIL' "$failedScenarios of $($scenarioResults.Count) acceptance scenarios failed; $passedScenarios passed."
    } else {
        Add-Result 'Result' 'PASS' "All $($scenarioResults.Count) local database acceptance scenarios passed."
    }
}
catch {
    if (-not ($results | Where-Object { $_.Status -eq 'FAIL' })) {
        Add-Result 'Run' 'FAIL' $_.Exception.Message
    }
}
finally {
    if ($containerCreated) {
        $null = & docker rm -f -v $containerName 2>&1
        if ($LASTEXITCODE -ne 0) { Add-Result 'Cleanup isolated container' 'FAIL' 'Could not remove the task-owned container.' }
        else { Add-Result 'Cleanup isolated container' 'PASS' 'Removed task-owned disposable PostgreSQL container and anonymous storage, if any.' }
    }
    if ($networkCreated) {
        $null = & docker network rm $networkName 2>&1
        if ($LASTEXITCODE -ne 0) { Add-Result 'Cleanup isolated network' 'FAIL' 'Could not remove the task-owned network.' }
        else { Add-Result 'Cleanup isolated network' 'PASS' 'Removed task-owned Docker network.' }
    }
    if ($script:gateOwned) {
        $gateComposeFile = Join-Path (Get-Location) 'scripts/acceptance/step2-local-gate.compose.yml'
        $gateProject = 'tcg-accept-20261016-gate'
        $null = & docker compose --project-name $gateProject --file $gateComposeFile down --remove-orphans 2>&1
        if ($LASTEXITCODE -eq 0) { Add-Result 'Cleanup Compose gate stack' 'PASS' 'Removed only resources belonging to the task-prefixed failure-gate project.' }
        else { Add-Result 'Cleanup Compose gate stack' 'FAIL' 'Could not remove task-owned Compose gate resources.' }
    }
    $finishedAt = [DateTimeOffset]::UtcNow
    $report = @(
        '# Step 2 local PostgreSQL acceptance — 2026-10-16',
        '',
        "Run window: $($startedAt.ToString('u')) to $($finishedAt.ToString('u'))",
        'Target: isolated PostgreSQL 16 in tmpfs on a task-prefixed Docker network; no application or database volume was reused.',
        'Runner: current-source backend image, invoked with DatabaseMigrationApplication / PropertiesLauncher. Runtime output is suppressed.',
        '',
        '| Scenario | Result | Observed evidence |',
        '| --- | --- | --- |'
    )
    foreach ($result in $results) {
        $safeEvidence = ([string]$result.Evidence) -replace '\|', '\|' -replace '[\r\n]+', ' '
        $report += "| $($result.Scenario) | $($result.Status) | $safeEvidence |"
    }
    $report += @('', 'No cloud database, existing container, or pre-existing volume was used. No credentials, connection strings, or raw runner logs are retained.')
    $fullEvidencePath = [System.IO.Path]::GetFullPath($EvidencePath)
    $evidenceRoot = [System.IO.Path]::GetFullPath((Join-Path (Get-Location) 'docs/evidence'))
    if (-not $fullEvidencePath.StartsWith($evidenceRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw 'Evidence path must remain within docs/evidence.'
    }
    New-Item -ItemType Directory -Path (Split-Path -Parent $fullEvidencePath) -Force | Out-Null
    [System.IO.File]::WriteAllLines($fullEvidencePath, $report, [System.Text.UTF8Encoding]::new($false))
    Write-Output "Evidence written to $EvidencePath"
}

if ($results | Where-Object { $_.Status -eq 'FAIL' }) { exit 1 }
