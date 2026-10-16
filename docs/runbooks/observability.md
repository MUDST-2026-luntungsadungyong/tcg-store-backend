# Observability runbook

This is a standalone, private monitoring configuration. It is not included in the application Kustomize bundle. The API scrape uses the standard Prometheus Kubernetes Endpoints discovery role against the `tcg-store-backend` Service in `tcg-store-staging` and `tcg-store-production`; endpoint discovery resolves the Service's named `http` port to pod TCP 8080. The scrape path is `/actuator/prometheus` over in-cluster HTTP.

## Platform prerequisites

- Install a Prometheus-compatible server and Grafana in a private monitoring environment. Keep both behind platform access control; this repository defines no public dashboard ingress.
- Configure Prometheus Kubernetes API credentials through its dedicated monitoring identity, with only the discovery permissions it needs: get/list/watch Services, Endpoints (or EndpointSlices if discovery is adapted), and Pods in the two application namespaces. Do not reuse the backend runtime ServiceAccount.
- Apply the separately owned `tcg-store-backend-monitoring-ingress` NetworkPolicy to each application namespace. It allows only TCP 8080 from pods labeled `app.kubernetes.io/name=prometheus` in the namespace labeled `kubernetes.io/metadata.name=monitoring`. Confirm the CNI enforces NetworkPolicy. The Component C owner must add this file to its resource inventory and preserve its name for schema 1 compatibility.
- Ensure the app exposes `/actuator/prometheus` internally and that the Service has the `http` target port. These are application release prerequisites; this standalone configuration does not enable Actuator endpoints.
- Install kube-state-metrics for Deployment availability, pod restart and OOMKilled panels/alerts. Install and scrape cAdvisor or an equivalent container metrics source if container CPU/memory panels are required. Metrics-server remains a separate HPA prerequisite.
- Arrange platform log collection from container stdout/stderr to an access-controlled searchable destination, with retention and ownership. This repository does not choose or configure a provider.
- Configure Alertmanager receiver routing, credentials, owners, escalation policy and retention through the platform's secret-management path. No receiver, credential, or destination is embedded here.
- Configure Prometheus retention, persistent storage, resource requests/limits, backup, and monitoring availability budgets for the actual cluster before installation. No storage or resource quota is assumed here.

## Signals and interpretation

The application supplies common `application=tcg-store-backend` and `environment=staging|production` tags. Kubernetes discovery independently derives the same `environment` value from the namespace (so target identity remains authoritative) and adds the dashboard/alert selector `app=tcg-store-backend`. The scrape is constrained to the two app namespaces, Service name, and named `http` endpoint port. It does not expose a public route.

Recording rules and dashboard panels expect Spring Boot/Micrometer metrics `http_server_requests_seconds_*`, `jvm_memory_used_bytes`, and `hikaricp_connections_*`; confirm the names and labels against the built app's real scrape output before enabling alerts. The storage rules expect `tcg_storage_operations_total` with finite `operation` and `outcome` labels (`outcome="error"` for failures), and `tcg_storage_operation_duration_seconds_bucket` with finite `operation`/`outcome` labels. The application instrumentation registers storage observations when an operation runs, so absent counter or histogram series are reported as unknown. Establish freshness with an approved known-fixture read (and staging write/read checks where permitted). Object keys, URLs, bucket credentials, customer identifiers, and request IDs must never be metric labels.

Neon project quota is not available from the app's Hikari pool. An approved external exporter must provide `tcg_neon_connections_used` and `tcg_neon_connections_limit`, labeled by `environment`. Until both are present, the dashboard shows `UNKNOWN` and `TCGNeonQuotaUnknown*` alerts fire; this is not healthy or zero usage. The exporter endpoint and credentials are platform inputs and are intentionally not guessed here.

Kubernetes replica/restart/OOM panels depend on kube-state-metrics. Missing deployment availability series raises an explicit per-environment unknown alert; replica, restart, and OOM signals must not be interpreted as healthy without that source. No cAdvisor job is provided because its endpoint, authentication and platform policy are cluster-specific. Hikari pressure similarly raises an unknown alert when its pool limit series is absent.

Alert thresholds are initial staging calibration values: two minutes with no API target, fewer than two available replicas for five minutes, over one percent 5xx with at least 100 requests, product/card read p95 above one second for ten minutes, Hikari pressure, storage errors, and Neon quota above 80 percent. Review live traffic, metric labels, SLOs, and alert delivery with service owners before production acceptance.

## Validate offline

The validator prefers an optional local Prometheus `v3.15.0` Windows `promtool.exe` at `target/observability/prometheus-3.15.0.windows-amd64/promtool.exe`, pinned by executable SHA256 `b02a6d8726533fbf59d40fad83646105429b6ed2f1b928f9d44659c67a83659b`. The official release archive SHA256 is `5d333b385557d9adc2ff015d13da9809baccc52fb800a82d1e5c94b79258f87e` ([official Prometheus downloads](https://prometheus.io/download/)). The validator runs the binary directly without network access. If it is absent, it uses the official `prom/prometheus:v3.15.0` image only when already present locally, with container networking disabled. It never pulls an image or contacts a cluster. Prometheus documents [`promtool` rule testing](https://prometheus.io/docs/prometheus/latest/configuration/unit_testing_rules/) and [recording rule validation](https://prometheus.io/docs/prometheus/latest/configuration/recording_rules/).

```powershell
pwsh -NoProfile -File infra/observability/validate.ps1
```

The rule tests cover missing scrape targets, cluster and storage telemetry unknown/recovery, Neon quota unknown, and sustained 5xx firing/recovery. They do not prove Kubernetes RBAC, endpoint discovery against a live cluster, CNI enforcement, exporter availability, Grafana rendering, log delivery, or Alertmanager delivery.

## Triage

1. If API targets are absent, inspect Prometheus target discovery, namespace names, Service name/port, and monitoring identity permissions. Do not treat missing `up` data as API health.
2. If a target is down, check the backend Pod readiness, private TCP 8080 path, NetworkPolicy/CNI enforcement, and Actuator scrape exposure. Keep `/actuator/prometheus` off public Ingress routes.
3. If HTTP/JVM/Hikari series are absent, compare `/actuator/prometheus` output from an authorized in-cluster check with the names expected here, then update the recording rules and dashboard with the instrumentation owner.
4. If S3 series are absent or errors rise, check the storage instrumentation/exporter contract and platform storage health separately; application readiness does not prove object storage availability.
5. If Neon quota is unknown, contact the platform owner for the approved quota exporter and its `used`/`limit` signal. Do not infer project quota from Hikari.
6. If restart/OOM panels are empty, check kube-state-metrics discovery and labels. If container resource panels are needed, confirm the platform cAdvisor-equivalent source and access contract first.
7. Confirm notification delivery through the platform's approved Alertmanager test procedure and escalation owner; no live receiver is configured by these files.
