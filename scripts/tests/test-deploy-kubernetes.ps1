$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$deployScript = Join-Path $repoRoot 'scripts/deploy-kubernetes.ps1'
$testRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("tcg-k8s-deploy-test-" + [guid]::NewGuid().ToString('N'))
$bundleRoot = Join-Path $testRoot 'bundle'
$bundlePath = Join-Path $bundleRoot 'staging'
$secretSentinel = 'DO_NOT_LOG_SECRET_VALUE'

New-Item -ItemType Directory -Path $bundlePath -Force | Out-Null
foreach ($file in @('prerequisites.yaml', 'migration.yaml', 'application.yaml', 'ci-prerequisites.yaml', 'ci-application.yaml', 'bootstrap.yaml')) {
    Set-Content -LiteralPath (Join-Path $bundlePath $file) -Value "fixture for $file" -NoNewline
}

$global:FakeMetadataMode = 'success'
$global:FakeMigrationFails = $false
$global:FakeLeaseMode = 'valid'
$global:FakeLoseLeaseDuringMigration = $false
$global:KubectlCalls = [System.Collections.Generic.List[object]]::new()

function global:python {
    param(
        [Parameter(Position = 0)][string]$ScriptPath,
        [Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments
    )

    $digestA = 'a' * 64
    $digestB = 'b' * 64
    $metadata = [ordered]@{
        schemaVersion = 2
        fixture = $false
        environment = 'staging'
        namespace = 'tcg-store-staging'
        applicationImage = "ghcr.io/example/tcg-store-backend@sha256:$digestA"
        migrationImage = "ghcr.io/example/tcg-store-backend@sha256:$digestA"
        migrationJobName = 'tcg-store-backend-migration'
        applicationDeploymentName = 'tcg-store-backend'
        placeholders = @()
        hasLiteralSecretResources = $false
        cloudConfigValidated = $true
        externalSecretContract = @{
            migrationSslMode = 'verify-full'
            migrationEndpointType = 'direct'
            valuesInspected = $false
        }
        secretName = 'tcg-store-backend-secrets'
    }
    if ($global:FakeMetadataMode -eq 'fixture') { $metadata.fixture = $true }
    if ($global:FakeMetadataMode -eq 'mismatch') { $metadata.migrationImage = "ghcr.io/example/tcg-store-backend@sha256:$digestB" }
    $global:LASTEXITCODE = 0
    ConvertTo-Json -InputObject $metadata -Depth 10 -Compress
}

function global:kubectl {
    [CmdletBinding(PositionalBinding = $false)]
    param(
        [Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments
    )
    process {
        $capturedArguments = $Arguments
        $fileInput = ''
        for ($index = 0; $index -lt $capturedArguments.Count - 1; $index++) {
            if ($capturedArguments[$index] -eq '-f' -and (Test-Path -LiteralPath $capturedArguments[$index + 1] -PathType Leaf)) {
                if ([System.IO.Path]::GetExtension($capturedArguments[$index + 1]) -eq '.tmp') {
                    $fileInput = Get-Content -LiteralPath $capturedArguments[$index + 1] -Raw
                }
            }
        }
        $global:KubectlCalls.Add([pscustomobject]@{
            Arguments = $capturedArguments
            FileInput = $fileInput
        })
        $global:LASTEXITCODE = 0
        $joined = $capturedArguments -join ' '
        if ($joined -match 'get lease tcg-release-lock') {
            $holder = if ($global:FakeLeaseMode -eq 'stolen') { 'other-owner' } else { 'test-lease-holder' }
            $renewed = if ($global:FakeLeaseMode -eq 'expired') { [DateTimeOffset]::UtcNow.AddMinutes(-5) } else { [DateTimeOffset]::UtcNow }
            return ConvertTo-Json -Depth 5 -Compress -InputObject @{
                apiVersion = 'coordination.k8s.io/v1'; kind = 'Lease'
                metadata = @{ name = 'tcg-release-lock'; namespace = 'tcg-store-staging'; resourceVersion = '42' }
                spec = @{ holderIdentity = $holder; leaseDurationSeconds = 90; renewTime = $renewed.ToString("yyyy-MM-ddTHH:mm:ss.fffffff'Z'") }
            }
        }
        if ($joined -match 'get secret tcg-store-backend-secrets') {
            return @(
                'neon-jdbc-url', 'neon-db-username', 'neon-db-password',
                'neon-migration-jdbc-url', 'neon-migration-db-username', 'neon-migration-db-password',
                'app-jwt-secret', 'object-storage-access-key', 'object-storage-secret-key'
            )
        }
        if ($joined -match 'create --dry-run=client') {
            $objects = @(
                @{ apiVersion = 'v1'; kind = 'Namespace'; metadata = @{ name = 'tcg-store-staging' } },
                @{ apiVersion = 'v1'; kind = 'ConfigMap'; metadata = @{ name = 'tcg-store-backend-config' } },
                @{ apiVersion = 'v1'; kind = 'ServiceAccount'; metadata = @{ name = 'tcg-store-backend' } },
                @{ apiVersion = 'networking.k8s.io/v1'; kind = 'NetworkPolicy'; metadata = @{ name = 'default-deny' } },
                @{
                    apiVersion = 'batch/v1'
                    kind = 'Job'
                    metadata = @{ name = 'tcg-store-backend-migration'; labels = @{ safe = 'label' } }
                    spec = @{ template = @{ spec = @{ containers = @(@{ name = 'migration'; image = 'ghcr.io/example/backend@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa' }) } } }
                }
            )
            return ConvertTo-Json -InputObject @{ apiVersion = 'v1'; kind = 'List'; items = $objects } -Depth 20 -Compress
        }
        if ($joined -match ' wait --for=condition=complete ' -and $global:FakeMigrationFails) {
            $global:LASTEXITCODE = 1
            return
        }
        if ($joined -match ' wait --for=condition=complete ' -and $global:FakeLoseLeaseDuringMigration) {
            $global:FakeLeaseMode = 'stolen'
        }
        if ($joined -match 'get job/') { return '' }
    }
}

function Assert-True {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw "Test failed: $Message" }
}

function Reset-Scenario {
    param([string]$Mode = 'success', [bool]$MigrationFails = $false, [bool]$BundleFixture = $false)
    $global:FakeMetadataMode = $Mode
    $global:FakeMigrationFails = $MigrationFails
    $global:FakeLeaseMode = 'valid'
    $global:FakeLoseLeaseDuringMigration = $false
    $global:KubectlCalls.Clear()
    $bundleJson = @{
        environment = 'staging'
        namespace = 'tcg-store-staging'
        fixture = $BundleFixture
        externalSecretContract = @{
            migrationSslMode = 'verify-full'
            migrationEndpointType = 'direct'
            valuesInspected = $false
        }
        resourceCounts = @{ application = 1; migration = 1; prerequisites = 3 }
    } | ConvertTo-Json -Depth 5
    Set-Content -LiteralPath (Join-Path $bundlePath 'bundle.json') -Value $bundleJson -NoNewline
}

function Invoke-DeployScenario {
    param([switch]$CI, [string]$ReadinessPath, [string]$ProgressPath)
    try {
        & $deployScript `
            -Context 'test-context' `
            -Environment staging `
            -ManifestDirectory $bundleRoot `
            -ReleaseId 'release-20261016' `
            -Timeout '5m' `
            -Python 'python' -CIMode:$CI -SecretReadinessFile $ReadinessPath -ProgressFile $ProgressPath `
            -LeaseName 'tcg-release-lock' -LeaseHolderIdentity 'test-lease-holder' *> $null
        return ''
    } catch {
        return $_.Exception.Message
    }
}

try {
    # Fixture marker is enforced from bundle.json before invoking kubectl.
    Reset-Scenario -BundleFixture $true
    $result = Invoke-DeployScenario
    Assert-True ($result -match 'Fixture bundles are never deployable') 'fixture bundle must be rejected'
    Assert-True ($global:KubectlCalls.Count -eq 0) 'fixture rejection must precede every kubectl call'

    # Image mismatch is independently rejected before the first cluster call.
    Reset-Scenario -Mode mismatch
    $result = Invoke-DeployScenario
    Assert-True ($result -match 'same immutable digest') 'app/migration image mismatch must fail'
    Assert-True ($global:KubectlCalls.Count -eq 0) 'image mismatch must precede every kubectl call'

    # A successful migration permits the application only after prerequisite, SA, Job and wait.
    Reset-Scenario
    $result = Invoke-DeployScenario
    Assert-True (-not $result) "successful deployment should complete; received: $result"
    $callStrings = @($global:KubectlCalls | ForEach-Object { $_.Arguments -join ' ' })
    $prereqIndex = [array]::FindIndex($callStrings, [Predicate[string]]{ param($x) $x -match 'apply -f .*prerequisites.yaml' })
    $serviceAccountIndex = [array]::FindIndex($callStrings, [Predicate[string]]{ param($x) $x -match 'apply -f .*serviceaccount.yaml' })
    $jobApplyIndex = [array]::FindIndex($callStrings, [Predicate[string]]{ param($x) $x -match 'apply -f .*\.tmp' })
    $waitIndex = [array]::FindIndex($callStrings, [Predicate[string]]{ param($x) $x -match 'wait --for=condition=complete' })
    $applicationIndex = [array]::FindIndex($callStrings, [Predicate[string]]{ param($x) $x -match 'apply -f .*application.yaml' })
    Assert-True ($prereqIndex -ge 0 -and $prereqIndex -lt $serviceAccountIndex -and $serviceAccountIndex -lt $jobApplyIndex -and $jobApplyIndex -lt $waitIndex -and $waitIndex -lt $applicationIndex) 'deployment sequence must gate application on completed migration'
    foreach ($call in $global:KubectlCalls) {
        Assert-True ($call.Arguments -contains '--context') "every kubectl invocation must set an explicit context (actual: $($call.Arguments -join '|'))"
        $contextIndex = [array]::IndexOf($call.Arguments, '--context')
        Assert-True ($call.Arguments[$contextIndex + 1] -eq 'test-context') 'every kubectl invocation must use the requested context'
    }
    $releaseJobCall = $global:KubectlCalls | Where-Object { $_.Arguments -join ' ' -match 'apply -f .*\.tmp' } | Select-Object -First 1
    Assert-True ($releaseJobCall.FileInput -match 'tcg-store-backend-migration-release-20261016') 'release Job should get a unique release-specific identity'

    # A failed migration must never reach the application apply.
    Reset-Scenario -MigrationFails $true
    $result = Invoke-DeployScenario
    Assert-True ($result -match 'kubectl command failed') 'failed migration wait should stop deployment'
    Assert-True (-not (@($global:KubectlCalls | ForEach-Object { $_.Arguments -join ' ' }) -match 'apply -f .*application.yaml')) 'application must not be applied after a failed migration'

    # The Secret query uses a names-only Go template; fake values are never requested or logged.
    $secretCall = $global:KubectlCalls | Where-Object { $_.Arguments -join ' ' -match 'get secret tcg-store-backend-secrets' } | Select-Object -First 1
    Assert-True ($null -ne $secretCall) 'Secret key names should be preflighted'
    Assert-True (($secretCall.Arguments -join ' ') -match 'go-template=') 'Secret query must use the names-only Go template'
    Assert-True (($global:KubectlCalls | ConvertTo-Json -Depth 10) -notmatch [regex]::Escape($secretSentinel)) 'Secret values must never appear in captured calls or output'

    # CI consumes only a protected platform-readiness assertion, never Secret data.
    $readinessPath = Join-Path $testRoot 'platform-readiness.json'
    $progressPath = Join-Path $testRoot 'deploy-progress.json'
    $readiness = @{
        schemaVersion = 1; issuer = 'platform-operator'; ready = $true
        environment = 'staging'; namespace = 'tcg-store-staging'
        secretName = 'tcg-store-backend-secrets'; checkedAt = [DateTimeOffset]::UtcNow.ToString('o')
        keyNames = @('neon-jdbc-url','neon-db-username','neon-db-password',
            'neon-migration-jdbc-url','neon-migration-db-username','neon-migration-db-password',
            'app-jwt-secret','object-storage-access-key','object-storage-secret-key')
    }
    $readiness | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $readinessPath
    Reset-Scenario
    $result = Invoke-DeployScenario -CI -ReadinessPath $readinessPath -ProgressPath $progressPath
    Assert-True (-not $result) "CI deployment should complete: $result"
    $ciCalls = @($global:KubectlCalls | ForEach-Object { $_.Arguments -join ' ' })
    Assert-True (-not ($ciCalls -match 'get secret|serviceaccount.yaml|bootstrap.yaml')) 'CI must not read Secrets or apply bootstrap resources'
    Assert-True (@($ciCalls -match 'apply -f .*ci-prerequisites.yaml').Count -eq 1) 'CI must apply its namespaced prerequisite bundle'
    Assert-True (@($ciCalls -match 'apply -f .*ci-application.yaml').Count -eq 1) 'CI must apply its namespaced application bundle'
    $progress = Get-Content -LiteralPath $progressPath -Raw | ConvertFrom-Json
    Assert-True ($progress.phase -eq 'rollout-complete' -and $progress.migrationCompleted) 'progress must report completed migration and rollout'
    $progressText = Get-Content -LiteralPath $progressPath -Raw
    Assert-True ($progressText -match '"startedAt":"[^"]+Z"' -and $progressText -match '"rolloutCompletedAt":"[^"]+Z"') 'progress must preserve actual UTC start and rollout completion for observation'
    Assert-True ([DateTimeOffset]$progress.startedAt -le [DateTimeOffset]$progress.rolloutCompletedAt) 'rollout must not predate deployment start'
    Assert-True (-not (Test-Path -LiteralPath ($progressPath + '.tmp'))) 'progress receipt should be atomically published, without a leftover temporary file'

    Reset-Scenario -MigrationFails $true
    $result = Invoke-DeployScenario -CI -ReadinessPath $readinessPath -ProgressPath $progressPath
    Assert-True ($result -match 'kubectl command failed') 'CI migration failure should stop rollout'
    $progress = Get-Content -LiteralPath $progressPath -Raw | ConvertFrom-Json
    Assert-True ($progress.phase -eq 'migration' -and -not $progress.migrationCompleted) 'progress must preserve the failing migration phase'
    Assert-True (-not (@($global:KubectlCalls | ForEach-Object { $_.Arguments -join ' ' }) -match 'apply -f .*ci-application.yaml')) 'CI migration failure must never apply the API'

    foreach ($leaseMode in @('stolen', 'expired')) {
        Reset-Scenario
        $global:FakeLeaseMode = $leaseMode
        $result = Invoke-DeployScenario -CI -ReadinessPath $readinessPath -ProgressPath $progressPath
        Assert-True ($result -match 'Lease') "$leaseMode Lease must fail closed"
        Assert-True (-not (@($global:KubectlCalls | ForEach-Object { $_.Arguments -join ' ' }) -match ' apply ')) 'invalid Lease must block every cluster write'
    }

    Reset-Scenario
    $global:FakeLoseLeaseDuringMigration = $true
    $result = Invoke-DeployScenario -CI -ReadinessPath $readinessPath -ProgressPath $progressPath
    Assert-True ($result -match 'Lease ownership changed') 'Lease lost during migration must stop deployment'
    Assert-True (-not (@($global:KubectlCalls | ForEach-Object { $_.Arguments -join ' ' }) -match 'apply -f .*ci-application.yaml')) 'Lease lost during migration must block API apply'
    $progress = Get-Content -LiteralPath $progressPath -Raw | ConvertFrom-Json
    Assert-True ($progress.phase -eq 'rollout' -and $progress.migrationCompleted) 'Lease loss after migration must retain truthful recovery phase'

    $readiness.checkedAt = [DateTimeOffset]::UtcNow.AddMinutes(-16).ToString('o')
    $readiness | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $readinessPath
    Reset-Scenario
    $result = Invoke-DeployScenario -CI -ReadinessPath $readinessPath
    Assert-True ($result -match 'stale') 'stale platform readiness must fail closed'
    Assert-True ($global:KubectlCalls.Count -eq 0) 'stale readiness must fail before any cluster call'

    # Exercise the real renderer/validator with the checked-in local dev image.
    $realPython = Join-Path $repoRoot 'target/component-c-tools/Scripts/python.exe'
    if (Test-Path -LiteralPath $realPython -PathType Leaf) {
        $realBundleRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("tcg-k8s-local-bundle-" + [guid]::NewGuid().ToString('N'))
        try {
            & $realPython (Join-Path $repoRoot 'scripts/kubernetes/render.py') `
                --environment local --output-dir $realBundleRoot --validate
            Assert-True ($LASTEXITCODE -eq 0) 'real local rendering and strict validation should pass'
            $realMetadataJson = @(& $realPython (Join-Path $repoRoot 'scripts/kubernetes/render.py') `
                validate-bundle --manifest-directory (Join-Path $realBundleRoot 'local') `
                --environment local --release-id local-offline-check --format json)
            Assert-True ($LASTEXITCODE -eq 0) 'real rendered local bundle preflight should pass'
            $realMetadata = ($realMetadataJson -join "`n") | ConvertFrom-Json
            Assert-True ($realMetadata.fixture -eq $false) 'real local bundle must be marked deployable, not a fixture'
            Assert-True ($realMetadata.applicationImage -eq $realMetadata.migrationImage) 'real local bundle must preserve matching app and migration images'
            Assert-True ($realMetadata.applicationImage -notmatch '@sha256:') 'local dev image may use its checked-in non-digest reference'

            $global:KubectlCalls.Clear()
            $global:FakeMigrationFails = $false
            try {
                & $deployScript `
                    -Context 'test-context' `
                    -Environment local `
                    -ManifestDirectory $realBundleRoot `
                    -ReleaseId 'local-offline-check' `
                    -Python $realPython *> $null
            } catch {
                $called = @($global:KubectlCalls | ForEach-Object { $_.Arguments -join ' ' }) -join "`n"
                throw "Real local adapter preflight/deploy failed: $($_.Exception.Message)`n$called"
            }
            Assert-True ($global:KubectlCalls.Count -gt 0) 'real validator metadata should pass deploy preflight before mocked kubectl calls'
            $localCalls = @($global:KubectlCalls | ForEach-Object { $_.Arguments -join ' ' })
            Assert-True (@($localCalls | Where-Object { $_ -match 'apply -f .*application.yaml' }).Count -eq 1) 'real local bundle should reach application apply after the mocked migration wait'
        } finally {
            $resolvedTempRoot = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath()).TrimEnd([System.IO.Path]::DirectorySeparatorChar, [System.IO.Path]::AltDirectorySeparatorChar)
            $resolvedBundleRoot = [System.IO.Path]::GetFullPath($realBundleRoot)
            $safeParentPrefix = $resolvedTempRoot + [System.IO.Path]::DirectorySeparatorChar
            if ($resolvedBundleRoot.StartsWith($safeParentPrefix, [System.StringComparison]::OrdinalIgnoreCase) -and
                [System.IO.Path]::GetFileName($resolvedBundleRoot) -match '^tcg-k8s-local-bundle-[0-9a-f]{32}$' -and
                (Test-Path -LiteralPath $resolvedBundleRoot -PathType Container)) {
                Remove-Item -LiteralPath $resolvedBundleRoot -Recurse -Force
            }
        }
    }

    Write-Output 'PASS: fixture and image mismatch reject before cluster access; successful ordering and explicit contexts; failed migration blocks application; Secret query exposes names only; real local render, validate-bundle, and mocked local deploy passed when the pinned Python environment is available.'
} finally {
    $resolvedTempRoot = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath()).TrimEnd([System.IO.Path]::DirectorySeparatorChar, [System.IO.Path]::AltDirectorySeparatorChar)
    $resolvedTestRoot = [System.IO.Path]::GetFullPath($testRoot)
    $expectedParent = $resolvedTempRoot + [System.IO.Path]::DirectorySeparatorChar
    $testFolderName = [System.IO.Path]::GetFileName($resolvedTestRoot)
    if ($resolvedTestRoot.StartsWith($expectedParent, [System.StringComparison]::OrdinalIgnoreCase) -and
        $testFolderName -match '^tcg-k8s-deploy-test-[0-9a-f]{32}$' -and
        (Test-Path -LiteralPath $resolvedTestRoot -PathType Container)) {
        Remove-Item -LiteralPath $resolvedTestRoot -Recurse -Force -ErrorAction SilentlyContinue
    }
    Remove-Item Function:\global:python -ErrorAction SilentlyContinue
    Remove-Item Function:\global:kubectl -ErrorAction SilentlyContinue
}
