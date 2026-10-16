[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$Context,

    [Parameter(Mandatory = $true)]
    [ValidateSet('local', 'staging', 'production')]
    [string]$Environment,

    [Parameter(Mandatory = $true)]
    [string]$ManifestDirectory,

    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[a-z0-9](?:[a-z0-9-]{0,38}[a-z0-9])?$')]
    [ValidateLength(1, 40)]
    [string]$ReleaseId,

    [ValidatePattern('^[1-9][0-9]*[smh]$')]
    [string]$Timeout = '5m',

    [string]$Python = 'python',

    [switch]$CIMode,
    [string]$SecretReadinessFile,
    [string]$ProgressFile,
    [ValidatePattern('^[a-z0-9](?:[a-z0-9.-]{0,61}[a-z0-9])?$')]
    [string]$LeaseName = 'tcg-release-lock',
    [string]$LeaseHolderIdentity
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$namespace = "tcg-store-$Environment"
$bundleRoot = [System.IO.Path]::GetFullPath($ManifestDirectory)
$bundlePath = if (Test-Path -LiteralPath (Join-Path $bundleRoot 'bundle.json') -PathType Leaf) {
    $bundleRoot
} else {
    Join-Path $bundleRoot $Environment
}
$validatorPath = Join-Path $repoRoot 'scripts/kubernetes/render.py'
$serviceAccountPath = Join-Path $repoRoot 'infra/kubernetes/base/serviceaccount.yaml'
$requiredFiles = @('prerequisites.yaml', 'migration.yaml', 'application.yaml')
$deploymentStartedAt = [DateTimeOffset]::UtcNow.UtcDateTime.ToString('o')

function Invoke-Kubectl {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)

    if ($CIMode -and $Arguments -contains 'apply') {
        Assert-DeploymentLease
    }
    $output = @(& kubectl @Arguments 2>$null)
    if ($LASTEXITCODE -ne 0) {
        throw 'kubectl command failed; command output was suppressed to prevent manifest or credential leakage.'
    }
    return ,$output
}

function Assert-Condition {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw $Message }
}

function Assert-DeploymentLease {
    # The orchestrator renews the Lease. Re-check immediately before each write,
    # not only outside this process: renewal can fail during a migration wait.
    $leaseOutput = Invoke-Kubectl -Arguments @(
        '--context', $Context, '--namespace', $namespace,
        'get', 'lease', $LeaseName, '-o', 'json'
    )
    try {
        $lease = ($leaseOutput -join "`n") | ConvertFrom-Json -ErrorAction Stop
        # PowerShell 7.5+ automatically parses JSON date strings. Preserve their
        # UTC kind instead of stringifying in the runner's local timezone.
        $renewalValue = $lease.spec.renewTime
        if ($renewalValue -is [DateTime] -and $renewalValue.Kind -eq [DateTimeKind]::Utc) {
            $renewed = [DateTimeOffset]::new($renewalValue)
        } elseif ($renewalValue -is [string] -and $renewalValue -cmatch '^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z$') {
            $renewed = [DateTimeOffset]::Parse($renewalValue, [Globalization.CultureInfo]::InvariantCulture)
        } else {
            throw 'timestamp'
        }
        $duration = $lease.spec.leaseDurationSeconds
        if ($duration -isnot [long] -and $duration -isnot [int]) { throw 'duration' }
        if ($duration -lt 1 -or $duration -gt 600) { throw 'duration' }
    } catch {
        throw 'Deployment Lease is missing or malformed; refusing cluster writes.'
    }
    Assert-Condition ($lease.apiVersion -ceq 'coordination.k8s.io/v1' -and $lease.kind -ceq 'Lease') 'Deployment Lease type is invalid.'
    Assert-Condition ($lease.metadata.name -ceq $LeaseName -and $lease.metadata.namespace -ceq $namespace) 'Deployment Lease target does not match.'
    Assert-Condition (-not [string]::IsNullOrWhiteSpace([string]$lease.metadata.resourceVersion)) 'Deployment Lease has no resourceVersion.'
    Assert-Condition ($lease.spec.holderIdentity -ceq $LeaseHolderIdentity) 'Deployment Lease ownership changed; refusing cluster writes.'
    $now = [DateTimeOffset]::UtcNow
    Assert-Condition ($renewed -le $now.AddSeconds(30) -and $renewed.AddSeconds($duration) -gt $now) 'Deployment Lease expired or is future-dated; refusing cluster writes.'
}

function Write-DeploymentProgress {
    param([string]$Phase, [bool]$MigrationCompleted = $false)
    if ($ProgressFile) {
        $progressPath = [System.IO.Path]::GetFullPath($ProgressFile)
        $progressDirectory = Split-Path -Parent $progressPath
        [System.IO.Directory]::CreateDirectory($progressDirectory) | Out-Null
        $timestamp = [DateTimeOffset]::UtcNow.UtcDateTime.ToString('o')
        $progressData = [ordered]@{
            phase = $Phase; migrationCompleted = $MigrationCompleted
            environment = $Environment; releaseId = $ReleaseId
            timestamp = $timestamp; startedAt = $deploymentStartedAt
        }
        if ($Phase -ceq 'rollout-complete') { $progressData.rolloutCompletedAt = $timestamp }
        $progress = $progressData | ConvertTo-Json -Compress
        $temporaryProgress = $progressPath + '.tmp'
        [System.IO.File]::WriteAllText($temporaryProgress, $progress, [System.Text.UTF8Encoding]::new($false))
        [System.IO.File]::Move($temporaryProgress, $progressPath, $true)
    }
}

Assert-Condition (-not [string]::IsNullOrWhiteSpace($Context)) 'Context must name the Kubernetes context explicitly.'
Assert-Condition (Test-Path -LiteralPath $bundlePath -PathType Container) 'ManifestDirectory must be the environment bundle directory or its parent.'
Assert-Condition (Test-Path -LiteralPath $validatorPath -PathType Leaf) "The bundle validator is missing: $validatorPath"
Assert-Condition (Test-Path -LiteralPath $serviceAccountPath -PathType Leaf) 'The repository ServiceAccount manifest is missing.'
foreach ($file in $requiredFiles) {
    Assert-Condition (Test-Path -LiteralPath (Join-Path $bundlePath $file) -PathType Leaf) "The bundle is missing $file."
}
if (-not (Get-Command kubectl -ErrorAction SilentlyContinue)) { throw 'kubectl was not found.' }
if (-not (Get-Command $Python -ErrorAction SilentlyContinue)) { throw "Python executable '$Python' was not found. Use -Python to select the pinned validator environment." }

$bundleMetadataPath = Join-Path $bundlePath 'bundle.json'
Assert-Condition (Test-Path -LiteralPath $bundleMetadataPath -PathType Leaf) 'Rendered bundle is missing bundle.json.'
try {
    $bundleMetadata = Get-Content -LiteralPath $bundleMetadataPath -Raw | ConvertFrom-Json -ErrorAction Stop
} catch {
    throw 'bundle.json is invalid; no Kubernetes resources were changed.'
}
Assert-Condition ($bundleMetadata.environment -ceq $Environment) 'bundle.json environment does not match -Environment.'
Assert-Condition ($bundleMetadata.namespace -ceq $namespace) "bundle.json namespace must be $namespace."
Assert-Condition ($null -ne $bundleMetadata.PSObject.Properties['fixture']) 'bundle.json must include an explicit fixture boolean.'
Assert-Condition ($bundleMetadata.fixture -eq $false) 'Fixture bundles are never deployable.'

# Validator CLI contract owned by the rendering/validation workstream:
#   python scripts/kubernetes/render.py validate-bundle --manifest-directory DIR
#     --environment ENV --release-id ID --format json
# stdout is one JSON object describing those exact YAML files, including
# fixture, namespace, applicationImage, migrationImage, migrationJobName,
# applicationDeploymentName, placeholders, hasLiteralSecretResources,
# cloudConfigValidated, externalSecretContract, and secretName. It must never
# emit Secret values. externalSecretContract is declarative and must state
# valuesInspected=false; actual endpoint/TLS acceptance is an external prerequisite.
$validatorOutput = @(& $Python $validatorPath validate-bundle `
    --manifest-directory $bundlePath `
    --environment $Environment `
    --release-id $ReleaseId `
    --format json 2>$null)
if ($LASTEXITCODE -ne 0) {
    throw 'Rendered bundle validation failed; no Kubernetes resources were changed.'
}
try {
    $metadata = (($validatorOutput -join "`n") | ConvertFrom-Json -ErrorAction Stop)
} catch {
    throw 'Bundle validator returned invalid metadata; no Kubernetes resources were changed.'
}

Assert-Condition ($metadata.environment -ceq $Environment) 'Validator metadata environment does not match -Environment.'
Assert-Condition ($null -ne $metadata.PSObject.Properties['fixture']) 'Validator metadata must include an explicit fixture boolean.'
Assert-Condition ($metadata.fixture -eq $false) 'Fixture bundles are never deployable.'
Assert-Condition ($metadata.namespace -ceq $namespace) "Bundle namespace must be $namespace."
Assert-Condition ($metadata.hasLiteralSecretResources -eq $false) 'Bundle contains a literal Kubernetes Secret resource.'
Assert-Condition ($null -ne $metadata.PSObject.Properties['placeholders']) 'Validator metadata must report placeholder findings.'
Assert-Condition (@($metadata.placeholders).Count -eq 0) 'Bundle contains unresolved placeholders.'
Assert-Condition (-not [string]::IsNullOrWhiteSpace([string]$metadata.applicationImage)) 'Validator did not report the application image.'
Assert-Condition (-not [string]::IsNullOrWhiteSpace([string]$metadata.migrationImage)) 'Validator did not report the migration image.'
Assert-Condition (-not [string]::IsNullOrWhiteSpace([string]$metadata.migrationJobName)) 'Validator did not report the migration Job name.'
Assert-Condition (-not [string]::IsNullOrWhiteSpace([string]$metadata.applicationDeploymentName)) 'Validator did not report the application Deployment name.'
Assert-Condition ($metadata.secretName -ceq 'tcg-store-backend-secrets') 'Bundle must reference the tcg-store-backend-secrets contract.'

if ($CIMode) {
    Assert-Condition (-not [string]::IsNullOrWhiteSpace($LeaseHolderIdentity) -and $LeaseHolderIdentity.Length -le 128) 'CI deploy requires the orchestrator Lease holder identity.'
    Assert-Condition ($metadata.schemaVersion -eq 2) 'CI deploy requires bundle schema version 2.'
    foreach ($ciFile in @('ci-prerequisites.yaml', 'ci-application.yaml', 'bootstrap.yaml')) {
        Assert-Condition (Test-Path -LiteralPath (Join-Path $bundlePath $ciFile) -PathType Leaf) "CI bundle is missing $ciFile."
    }
}

if ($Environment -ne 'local') {
    Assert-Condition ($null -ne $bundleMetadata.PSObject.Properties['externalSecretContract']) 'Cloud bundle metadata must include the declarative external Secret contract.'
    Assert-Condition ($bundleMetadata.externalSecretContract.migrationSslMode -ceq 'verify-full') 'Cloud bundle must declare migration sslmode=verify-full.'
    Assert-Condition ($bundleMetadata.externalSecretContract.migrationEndpointType -ceq 'direct') 'Cloud bundle must declare a direct migration endpoint.'
    Assert-Condition ($bundleMetadata.externalSecretContract.valuesInspected -eq $false) 'Cloud bundle must state that Secret values were not inspected.'
    $digestPattern = '^.+@sha256:[0-9a-f]{64}$'
    Assert-Condition ($metadata.applicationImage -cmatch $digestPattern) 'Cloud application image must use an immutable sha256 digest.'
    Assert-Condition ($metadata.migrationImage -cmatch $digestPattern) 'Cloud migration image must use an immutable sha256 digest.'
    Assert-Condition ($metadata.applicationImage -ceq $metadata.migrationImage) 'Application and migration images must be the same immutable digest.'
    Assert-Condition ($metadata.cloudConfigValidated -eq $true) 'Cloud configuration was not validated by the bundle validator.'
    Assert-Condition ($metadata.externalSecretContract.migrationSslMode -ceq 'verify-full') 'Cloud migration Secret contract must declare sslmode=verify-full.'
    Assert-Condition ($metadata.externalSecretContract.migrationEndpointType -ceq 'direct') 'Cloud migration Secret contract must declare the direct database endpoint.'
    Assert-Condition ($metadata.externalSecretContract.valuesInspected -eq $false) 'Secret values must not be inspected by this deploy adapter.'
    Assert-Condition ($metadata.externalSecretContract.migrationSslMode -ceq $bundleMetadata.externalSecretContract.migrationSslMode) 'Validator and bundle Secret contracts disagree.'
    Assert-Condition ($metadata.externalSecretContract.migrationEndpointType -ceq $bundleMetadata.externalSecretContract.migrationEndpointType) 'Validator and bundle Secret contracts disagree.'
}

$jobPrefix = [string]$metadata.migrationJobName
$maximumPrefixLength = 63 - $ReleaseId.Length - 1
if ($jobPrefix.Length -gt $maximumPrefixLength) {
    $jobPrefix = $jobPrefix.Substring(0, $maximumPrefixLength).TrimEnd('-')
}
$jobName = "$jobPrefix-$ReleaseId"
Assert-Condition ($jobName.Length -le 63 -and $jobName -cmatch '^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?$') 'Release-specific migration Job name is not a valid Kubernetes name.'

$requiredSecretKeys = @(
    'neon-jdbc-url', 'neon-db-username', 'neon-db-password',
    'neon-migration-jdbc-url', 'neon-migration-db-username', 'neon-migration-db-password',
    'app-jwt-secret', 'object-storage-access-key', 'object-storage-secret-key'
)
if ($CIMode) {
    # This file is produced by the platform owner in a protected runner directory.
    # It is not an artifact or a user-supplied assertion that can grant readiness.
    Assert-Condition (-not [string]::IsNullOrWhiteSpace($SecretReadinessFile)) 'CI deploy requires a protected platform Secret readiness file.'
    try {
        $readiness = Get-Content -LiteralPath $SecretReadinessFile -Raw | ConvertFrom-Json -ErrorAction Stop
        if ($readiness.checkedAt -is [DateTime] -and $readiness.checkedAt.Kind -ne [DateTimeKind]::Unspecified) {
            $checkedAt = [DateTimeOffset]::new($readiness.checkedAt).ToUniversalTime()
        } elseif ($readiness.checkedAt -is [string] -and $readiness.checkedAt -cmatch '(?:Z|[+-]\d{2}:\d{2})$') {
            $checkedAt = [DateTimeOffset]::Parse($readiness.checkedAt, [Globalization.CultureInfo]::InvariantCulture)
        } else { throw 'timestamp' }
    } catch {
        throw 'Platform Secret readiness file is missing or invalid.'
    }
    Assert-Condition ($readiness.schemaVersion -eq 1 -and $readiness.issuer -ceq 'platform-operator') 'Secret readiness issuer/schema is invalid.'
    Assert-Condition ($readiness.environment -ceq $Environment -and $readiness.namespace -ceq $namespace) 'Secret readiness environment does not match the target.'
    Assert-Condition ($readiness.secretName -ceq 'tcg-store-backend-secrets' -and $readiness.ready -is [bool] -and $readiness.ready -eq $true) 'Platform Secret readiness is not ready.'
    $readinessAge = [DateTimeOffset]::UtcNow - $checkedAt
    Assert-Condition ($readinessAge.TotalSeconds -ge -30 -and $readinessAge.TotalMinutes -le 15) 'Platform Secret readiness is stale or future-dated.'
    $availableSecretKeys = @($readiness.keyNames)
} else {
    # Manual local/operator flow requires Secret get permission. CI never uses it.
    # Avoid embedded quotes: Windows PowerShell strips them in native arguments.
    $secretKeysTemplate = '{{range $key, $value := .data}}{{$key}} {{end}}'
    $secretKeyOutput = Invoke-Kubectl -Arguments @(
        '--context', $Context, '--namespace', $namespace,
        'get', 'secret', 'tcg-store-backend-secrets',
        '-o', "go-template=$secretKeysTemplate"
    )
    $availableSecretKeys = @((($secretKeyOutput -join ' ') -split '\s+') | Where-Object { $_ })
}
$missingSecretKeys = @($requiredSecretKeys | Where-Object { $_ -notin $availableSecretKeys })
Assert-Condition ($missingSecretKeys.Count -eq 0) "Required Secret key names are missing: $($missingSecretKeys -join ', '). Secret values were not read."

$existingJob = Invoke-Kubectl -Arguments @(
    '--context', $Context, '--namespace', $namespace,
    'get', "job/$jobName", '--ignore-not-found=true', '-o', 'name'
)
Assert-Condition (@($existingJob | Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_) }).Count -eq 0) "Migration Job $jobName already exists; release IDs are immutable and existing Jobs are never deleted."

# Build the release-specific Job locally before the first write. The bundle has
# already passed the complete Python validation contract above.
$migrationJson = Invoke-Kubectl -Arguments @(
    '--context', $Context, '--namespace', $namespace,
    'create', '--dry-run=client', '-f', (Join-Path $bundlePath 'migration.yaml'), '-o', 'json'
)
try {
$migrationResult = (($migrationJson -join "`n") | ConvertFrom-Json -ErrorAction Stop)
} catch {
    throw 'Migration manifest could not be prepared locally; no Kubernetes resources were changed.'
}
$migrationObjects = if ($migrationResult.kind -ceq 'List') { @($migrationResult.items) } else { @($migrationResult) }
$migrationJobs = @($migrationObjects | Where-Object { $_.kind -ceq 'Job' })
Assert-Condition ($migrationJobs.Count -eq 1) 'migration.yaml must contain exactly one Kubernetes Job.'
$migrationObject = $migrationJobs[0]
Assert-Condition ($migrationObject.metadata.name -ceq [string]$metadata.migrationJobName) 'Migration Job identity disagrees with validator metadata.'
$migrationObject.metadata.name = $jobName
$releaseJobJson = $migrationObject | ConvertTo-Json -Depth 100 -Compress
$releaseJobFile = [System.IO.Path]::GetTempFileName()
[System.IO.File]::WriteAllText($releaseJobFile, $releaseJobJson, [System.Text.UTF8Encoding]::new($false))

# First mutation occurs only after full bundle, environment, image and Secret preflight.
try {
    Write-DeploymentProgress -Phase 'prerequisites'
    $prerequisitesFile = if ($CIMode) { 'ci-prerequisites.yaml' } else { 'prerequisites.yaml' }
    $applicationFile = if ($CIMode) { 'ci-application.yaml' } else { 'application.yaml' }
    $null = Invoke-Kubectl -Arguments @(
        '--context', $Context, '--namespace', $namespace,
        'apply', '-f', (Join-Path $bundlePath $prerequisitesFile)
    )
    if (-not $CIMode) {
        $null = Invoke-Kubectl -Arguments @(
            '--context', $Context, '--namespace', $namespace,
            'apply', '-f', $serviceAccountPath
        )
    }
    Write-DeploymentProgress -Phase 'migration'
    $null = Invoke-Kubectl -Arguments @(
        '--context', $Context, '--namespace', $namespace, 'apply', '-f', $releaseJobFile
    )

    $null = Invoke-Kubectl -Arguments @(
        '--context', $Context, '--namespace', $namespace,
        'wait', '--for=condition=complete', "job/$jobName", "--timeout=$Timeout"
    )

    Write-DeploymentProgress -Phase 'rollout' -MigrationCompleted $true
    $null = Invoke-Kubectl -Arguments @(
        '--context', $Context, '--namespace', $namespace,
        'apply', '-f', (Join-Path $bundlePath $applicationFile)
    )
    $null = Invoke-Kubectl -Arguments @(
        '--context', $Context, '--namespace', $namespace,
        'rollout', 'status', "deployment/$($metadata.applicationDeploymentName)", "--timeout=$Timeout"
    )
    Write-DeploymentProgress -Phase 'rollout-complete' -MigrationCompleted $true
} finally {
    Remove-Item -LiteralPath $releaseJobFile -Force -ErrorAction SilentlyContinue
}

Write-Output "Kubernetes release $ReleaseId completed in namespace $namespace."
