[CmdletBinding()]
param(
    [string]$ResourceGroup = 'rg-tcg-store-aks-demo',
    [string]$ActionGroupName = 'tcg-store-aks-demo-alerts',
    [string]$AzureMonitorWorkspaceName = 'amw-tcg-store-aks-demo',
    [string]$AksClusterName = 'aks-tcg-store-demo',
    [Parameter(Mandatory)]
    [ValidateNotNullOrEmpty()]
    [string[]]$AlertEmailAddress,
    [switch]$SendTestNotification
)

$ErrorActionPreference = 'Stop'

function Require-AzureCli {
    if (-not (Get-Command az -ErrorAction SilentlyContinue)) {
        throw 'Azure CLI (az) is required. Install it, then run az login before deploying alert routing.'
    }
}

function New-TemporaryParametersFile {
    param([hashtable]$Parameters)

    $path = Join-Path $env:TEMP ("tcg-store-observability-{0}.parameters.json" -f [guid]::NewGuid())
    @{
        '$schema' = 'https://schema.management.azure.com/schemas/2019-04-01/deploymentParameters.json#'
        contentVersion = '1.0.0.0'
        parameters = $Parameters
    } | ConvertTo-Json -Depth 8 | Set-Content -Path $path -Encoding utf8
    return $path
}

Require-AzureCli

$distinctEmails = @($AlertEmailAddress | ForEach-Object { $_.Trim().ToLowerInvariant() } | Where-Object { $_ } | Select-Object -Unique)
if ($distinctEmails.Count -eq 0) {
    throw 'At least one non-empty -AlertEmailAddress value is required.'
}

foreach ($emailAddress in $distinctEmails) {
    if ($emailAddress -notmatch '^[^\s@]+@[^\s@]+\.[^\s@]+$') {
        throw "Invalid alert email address: $emailAddress"
    }
}

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$actionGroupTemplate = Join-Path $repositoryRoot 'infra/observability/azure/aks-demo-alert-action-group.bicep'
$ruleGroupsTemplate = Join-Path $repositoryRoot 'infra/observability/azure/aks-demo-prometheus-rules.bicep'
$actionGroupParametersFile = $null

try {
    $actionGroupParametersFile = New-TemporaryParametersFile -Parameters @{
        actionGroupName = @{ value = $ActionGroupName }
        alertEmailAddresses = @{ value = $distinctEmails }
    }

    az deployment group create `
        --name 'tcg-store-observability-phase5-action-group' `
        --resource-group $ResourceGroup `
        --template-file $actionGroupTemplate `
        --parameters "@$actionGroupParametersFile" `
        --only-show-errors | Out-Null

    $actionGroupResourceId = az monitor action-group show `
        --name $ActionGroupName `
        --resource-group $ResourceGroup `
        --query id -o tsv
    if ([string]::IsNullOrWhiteSpace($actionGroupResourceId)) {
        throw 'Azure returned no Action Group resource ID after deployment.'
    }

    $azureMonitorWorkspaceResourceId = az monitor account show `
        --name $AzureMonitorWorkspaceName `
        --resource-group $ResourceGroup `
        --query id -o tsv
    $aksClusterResourceId = az aks show `
        --name $AksClusterName `
        --resource-group $ResourceGroup `
        --query id -o tsv

    if ([string]::IsNullOrWhiteSpace($azureMonitorWorkspaceResourceId) -or [string]::IsNullOrWhiteSpace($aksClusterResourceId)) {
        throw 'Could not resolve the Azure Monitor Workspace or AKS cluster resource ID.'
    }

    az deployment group create `
        --name 'tcg-store-observability-phase5-rule-routing' `
        --resource-group $ResourceGroup `
        --template-file $ruleGroupsTemplate `
        --parameters azureMonitorWorkspaceResourceId=$azureMonitorWorkspaceResourceId aksClusterResourceId=$aksClusterResourceId actionGroupResourceId=$actionGroupResourceId `
        --only-show-errors | Out-Null

    if ($SendTestNotification) {
        az monitor action-group test-notifications create `
            --action-group $ActionGroupName `
            --resource-group $ResourceGroup `
            --alert-type metricstaticthreshold `
            --only-show-errors | Out-Null
    }

    Write-Host "Configured Action Group '$ActionGroupName' and attached it to AKS demo Prometheus alerts."
    if ($SendTestNotification) {
        Write-Host 'Azure Monitor accepted a test-notification request. Each recipient must confirm delivery in their inbox.'
    }
}
finally {
    if ($actionGroupParametersFile -and (Test-Path $actionGroupParametersFile)) {
        Remove-Item -LiteralPath $actionGroupParametersFile -Force
    }
}
