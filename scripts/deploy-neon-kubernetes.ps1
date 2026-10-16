[CmdletBinding()]
param(
    [string]$Namespace = "tcg-store",
    [string]$MigrationOverlay = "infra/kubernetes/overlays/neon/migration",
    [string]$ApplicationOverlay = "infra/kubernetes/overlays/neon",
    [string]$MigrationJob = "tcg-store-backend-migration",
    [string]$Deployment = "tcg-store-backend",
    [string]$Timeout = "5m"
)

$ErrorActionPreference = "Stop"
$backendRoot = Split-Path -Parent $PSScriptRoot
Set-Location $backendRoot

if (-not (Get-Command kubectl -ErrorAction SilentlyContinue)) {
    throw "kubectl was not found."
}

if ($Namespace -ne "tcg-store") {
    throw "The current Kustomize overlays target namespace tcg-store. Update both overlays before using a different namespace."
}

Write-Output "Applying shared namespace, configuration and service account..."
kubectl apply -f "$ApplicationOverlay/namespace.yaml"
if ($LASTEXITCODE -ne 0) {
    throw "Failed to apply namespace configuration."
}

kubectl apply -f "$ApplicationOverlay/configmap.yaml" --namespace $Namespace
if ($LASTEXITCODE -ne 0) {
    throw "Failed to apply backend configuration."
}

kubectl apply -f "infra/kubernetes/base/serviceaccount.yaml" --namespace $Namespace
if ($LASTEXITCODE -ne 0) {
    throw "Failed to apply the backend service account."
}

kubectl get secret tcg-store-backend-secrets --namespace $Namespace | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw "Required secret tcg-store-backend-secrets is missing from namespace $Namespace."
}

Write-Output "Replacing the previous migration Job..."
kubectl delete job $MigrationJob --namespace $Namespace --ignore-not-found=true
if ($LASTEXITCODE -ne 0) {
    throw "Failed to remove the previous migration Job."
}

kubectl apply --kustomize $MigrationOverlay
if ($LASTEXITCODE -ne 0) {
    throw "Failed to create the migration Job."
}

kubectl wait --for=condition=complete "job/$MigrationJob" --namespace $Namespace --timeout $Timeout
if ($LASTEXITCODE -ne 0) {
    kubectl logs "job/$MigrationJob" --namespace $Namespace --all-containers=true
    throw "Database migration failed; application rollout was not started."
}

Write-Output "Migration completed. Applying the application overlay..."
kubectl apply --kustomize $ApplicationOverlay
if ($LASTEXITCODE -ne 0) {
    throw "Application manifests could not be applied."
}

kubectl rollout status "deployment/$Deployment" --namespace $Namespace --timeout $Timeout
if ($LASTEXITCODE -ne 0) {
    throw "Application rollout did not complete successfully."
}

Write-Output "Migration gate and application rollout completed successfully."
