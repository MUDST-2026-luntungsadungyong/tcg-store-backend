# Observability Phase 4 — AKS demo handoff

Date: 13 November 2026  
Scope: `aks-tcg-store-demo` in `Malaysia West`, Azure Monitor workspace `amw-tcg-store-aks-demo`, Grafana workspace `tcg-store-grafana`.

## Completed

- Merged [PR #88](https://github.com/MUDST-2026-luntungsadungyong/tcg-store-backend/pull/88) at merge commit `40df9b5c96e2ffe2d5eef6070e04a9e48b878721`. The AKS demo deployment completed successfully; the backend rollout and migration passed.
- Confirmed the live `ServiceMonitor` in namespace `tcg-store-aks-demo`, scraping `tcg-store-backend:http` at `/actuator/prometheus` every 30 seconds.
- Queried Azure Managed Prometheus. `up{job="tcg-store-backend",environment="aks-demo"}` returned `1`; HTTP request count, HTTP histograms, JVM memory, Hikari metrics, deployment availability, and pod restart metrics were present.
- Imported `infra/observability/dashboard/tcg-store-backend.json` into Azure Managed Grafana as dashboard UID `tcg-store-backend`, using datasource UID `amw-tcg-store-aks-demo` and default environment `aks-demo`.
- Deployed `tcg-store-aks-demo-recording` and `tcg-store-aks-demo-alerts` Azure Prometheus rule groups from `infra/observability/azure/aks-demo-prometheus-rules.bicep`, scoped to the Azure Monitor workspace and AKS cluster. Both deployments succeeded.
- Added recording and alert rules for scrape availability, backend availability, restarts/OOM, HTTP 5xx ratio, read latency, Hikari pressure, and missing storage telemetry.
- Updated the shared dashboard contract test to include `aks-demo` and recording rule filters to match the observed lowercase HTTP method labels.

## Validation evidence

- PR #88 CI passed, including Java tests, container build and supply-chain checks, Kubernetes validation for local/staging/production.
- Local `MetricsExposureIntegrationTests`: 3 passed.
- AKS demo Kustomize render: passed; live `ServiceMonitor` present after deployment.
- `python -m unittest discover -s infra/observability/tests -p 'test_*.py' -v`: 4 passed.
- `python -m unittest discover -s scripts/kubernetes -p 'test_*.py' -v`: 35 passed, 1 optional kubeconform integration test skipped because local schema inputs were not configured.
- `az bicep build` succeeded; `az deployment group validate` succeeded; Azure deployment of both rule groups succeeded.
- Grafana dashboard import succeeded and returned URL `/d/tcg-store-backend/tcg-store-backend`.
- Storage metrics currently have no samples. The dashboard and alert treat them as `UNKNOWN`, pending an approved known-fixture storage operation.
- Neon quota exporter metrics are absent; the existing dashboard keeps this signal `UNKNOWN`.

## Follow-up for the next developer

1. Confirm rule-group health in Azure Monitor → `amw-tcg-store-aks-demo` → Rule groups. If any rule is degraded, inspect the group health details and revise the PromQL in the Bicep template.
2. Open the Grafana endpoint and confirm that the dashboard loads with environment `aks-demo`. Verify panels for scrape, JVM, Hikari, and Kubernetes state. Storage and Neon quota are expected to display `UNKNOWN` until their sources exist.
3. Choose the alert notification owner and destination. No Action Group was created because no recipient or escalation owner was provided. Once agreed, create the Azure Monitor Action Group, pass its resource ID as `actionGroupResourceId` when deploying the Bicep template, then test delivery and document evidence.
4. Run an approved storage fixture operation and confirm `tcg_storage_operations_total` and `tcg_storage_operation_duration_seconds_bucket` arrive before treating storage telemetry as accepted.

## Re-deploy rule groups

```powershell
$amw = az monitor account show --name amw-tcg-store-aks-demo --resource-group rg-tcg-store-aks-demo --query id -o tsv
$aks = az aks show --name aks-tcg-store-demo --resource-group rg-tcg-store-aks-demo --query id -o tsv
az deployment group create --name tcg-store-observability-phase4 --resource-group rg-tcg-store-aks-demo --template-file infra/observability/azure/aks-demo-prometheus-rules.bicep --parameters azureMonitorWorkspaceResourceId=$amw aksClusterResourceId=$aks
```

To attach a notification action group after its owner is decided, add `actionGroupResourceId=<resource-id>` to the deployment parameters.

## Dashboard endpoint

`https://tcg-store-grafana-anerbwdscghmbva5.myw.grafana.azure.com/d/tcg-store-backend/tcg-store-backend`
