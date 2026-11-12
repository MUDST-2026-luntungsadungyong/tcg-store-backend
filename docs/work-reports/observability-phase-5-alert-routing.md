# Observability Phase 5 — AKS demo alert routing

## Objective

Provide a repeatable and reviewable way to route Azure Managed Prometheus alerts from `aks-tcg-store-demo` to the approved engineering recipients, then request a test notification without hard-coding recipient addresses in the repository.

## Implementation

- Added `infra/observability/azure/aks-demo-alert-action-group.bicep`, which creates one enabled Azure Monitor Action Group with Common Alert Schema email receivers.
- Added `scripts/reliability/deploy-aks-demo-alert-routing.ps1`. It validates recipient input, deploys the Action Group, resolves the Azure Monitor Workspace and AKS resource IDs, and re-deploys the Phase 4 Prometheus rule groups with `actionGroupResourceId` set.
- The script can optionally submit an Azure Monitor sample notification with `-SendTestNotification`.
- Updated the observability runbook with the supported deployment and verification flow.

## Approved recipients

The deployment operator supplies the three approved recipients as `-AlertEmailAddress` values. They are deliberately not committed to source control so that a future recipient change is an Azure configuration change, not an exposed code change.

## Deployment

```powershell
pwsh -NoProfile -File scripts/reliability/deploy-aks-demo-alert-routing.ps1 `
  -AlertEmailAddress '<recipient-1>','<recipient-2>','<recipient-3>' `
  -SendTestNotification
```

The command requires an authenticated Azure CLI identity with permission to create/update `Microsoft.Insights/actionGroups`, deploy resource-group templates, read the Azure Monitor Workspace and AKS resource IDs, and update the Azure Prometheus rule groups.

## Acceptance checks

1. The Action Group exists in `rg-tcg-store-aks-demo` and is enabled.
2. All three configured email receivers are visible and enabled in Azure Monitor.
3. `tcg-store-aks-demo-alerts` has the Action Group attached after the rule-group redeployment.
4. Every recipient receives the Azure Monitor test email and confirms receipt.
5. The Grafana dashboard still loads with `environment=aks-demo`; panels that lack a data source continue to display `UNKNOWN` rather than a healthy value.

## Storage telemetry fixture

Phase 4 correctly identified that no `tcg_storage_operations_total` or `tcg_storage_operation_duration_seconds_bucket` samples exist yet. Run an approved seller product image upload using a disposable, valid PNG/JPG/WebP fixture, then delete that test listing through the normal seller API/UI. This exercises the same managed object-storage path as the application and records bounded upload/delete metrics without adding object keys or customer data to Prometheus labels. Confirm the two metric families arrive before accepting storage telemetry.

Do not create a special public fixture endpoint and do not expose `/actuator/prometheus` through ingress merely to test storage metrics.
