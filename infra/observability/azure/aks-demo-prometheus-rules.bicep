@description('Azure region shared by the Monitor workspace and rule groups.')
param location string = 'malaysiawest'

@description('Resource ID of the Azure Monitor workspace that stores Prometheus metrics.')
param azureMonitorWorkspaceResourceId string

@description('Resource ID of the AKS cluster that scopes rule evaluation.')
param aksClusterResourceId string

@description('Value of the cluster label attached to metrics from this AKS cluster.')
param clusterName string = 'aks-tcg-store-demo'

@description('Optional Azure Monitor action group resource ID. Leave empty until a notification owner is agreed.')
param actionGroupResourceId string = ''

var scopes = [
  azureMonitorWorkspaceResourceId
  aksClusterResourceId
]

var alertActions = empty(actionGroupResourceId) ? [] : [
  {
    actionGroupId: actionGroupResourceId
  }
]

resource recordingRules 'Microsoft.AlertsManagement/prometheusRuleGroups@2023-03-01' = {
  name: 'tcg-store-aks-demo-recording'
  location: location
  properties: {
    description: 'TCG Store backend recording rules for the AKS demo environment.'
    scopes: scopes
    clusterName: clusterName
    enabled: true
    interval: 'PT1M'
    rules: [
      {
        record: 'tcg:http_requests:rate5m'
        expression: 'sum by (environment) (rate(http_server_requests_seconds_count{job="tcg-store-backend",environment="aks-demo"}[5m]))'
        enabled: true
      }
      {
        record: 'tcg:http_5xx:rate5m'
        expression: 'sum by (environment) (rate(http_server_requests_seconds_count{job="tcg-store-backend",environment="aks-demo",status=~"5.."}[5m]))'
        enabled: true
      }
      {
        record: 'tcg:http_5xx:ratio5m'
        expression: 'tcg:http_5xx:rate5m{environment="aks-demo"} / tcg:http_requests:rate5m{environment="aks-demo"}'
        enabled: true
      }
      {
        record: 'tcg:http_read:p95_latency_seconds'
        expression: 'histogram_quantile(0.95, sum by (le, environment) (rate(http_server_requests_seconds_bucket{job="tcg-store-backend",environment="aks-demo",method=~"GET|get",uri=~"/api/(products|cards)(/.*)?"}[10m])))'
        enabled: true
      }
      {
        record: 'tcg:hikari:connections_in_use_ratio'
        expression: 'sum by (environment) (hikaricp_connections_active{job="tcg-store-backend",environment="aks-demo"}) / sum by (environment) (hikaricp_connections_max{job="tcg-store-backend",environment="aks-demo"})'
        enabled: true
      }
      {
        record: 'tcg:s3:operation_errors:rate5m'
        expression: 'sum by (environment, operation) (rate(tcg_storage_operations_total{job="tcg-store-backend",environment="aks-demo",outcome="error"}[5m]))'
        enabled: true
      }
      {
        record: 'tcg:s3:operation:p95_latency_seconds'
        expression: 'histogram_quantile(0.95, sum by (le, environment, operation) (rate(tcg_storage_operation_duration_seconds_bucket{job="tcg-store-backend",environment="aks-demo"}[5m])))'
        enabled: true
      }
    ]
  }
}

resource alertRules 'Microsoft.AlertsManagement/prometheusRuleGroups@2023-03-01' = {
  name: 'tcg-store-aks-demo-alerts'
  location: location
  properties: {
    description: 'TCG Store backend availability, error, latency, database, storage, and telemetry alerts for AKS demo.'
    scopes: scopes
    clusterName: clusterName
    enabled: true
    interval: 'PT1M'
    rules: [
      {
        alert: 'TCGStoreBackendScrapeMissingAksDemo'
        expression: 'absent(up{job="tcg-store-backend",environment="aks-demo"})'
        for: 'PT2M'
        labels: { app: 'tcg-store-backend', environment: 'aks-demo', severity: 'critical' }
        annotations: { summary: 'No AKS demo backend scrape target is visible', description: 'Managed Prometheus has no discovered backend endpoint for AKS demo. Missing data is not a healthy result.' }
        enabled: true
        severity: 0
        resolveConfiguration: { autoResolved: true, timeToResolve: 'PT5M' }
        actions: alertActions
      }
      {
        alert: 'TCGStoreBackendScrapeDownAksDemo'
        expression: 'up{job="tcg-store-backend",environment="aks-demo"} == 0'
        for: 'PT2M'
        labels: { app: 'tcg-store-backend', environment: 'aks-demo', severity: 'warning' }
        annotations: { summary: 'The AKS demo backend scrape endpoint is unavailable', description: 'Managed Prometheus discovered the backend endpoint but cannot scrape it.' }
        enabled: true
        severity: 2
        resolveConfiguration: { autoResolved: true, timeToResolve: 'PT5M' }
        actions: alertActions
      }
      {
        alert: 'TCGStoreBackendClusterMetricsUnknownAksDemo'
        expression: 'absent(kube_deployment_status_replicas_available{namespace="tcg-store-aks-demo",deployment="tcg-store-backend"})'
        for: 'PT5M'
        labels: { app: 'tcg-store-backend', environment: 'aks-demo', severity: 'warning' }
        annotations: { summary: 'AKS demo workload telemetry is unknown', description: 'No kube-state-metrics deployment availability series is present.' }
        enabled: true
        severity: 2
        resolveConfiguration: { autoResolved: true, timeToResolve: 'PT5M' }
        actions: alertActions
      }
      {
        alert: 'TCGStoreBackendUnavailableAksDemo'
        expression: 'kube_deployment_status_replicas_available{namespace="tcg-store-aks-demo",deployment="tcg-store-backend"} < 1'
        for: 'PT2M'
        labels: { app: 'tcg-store-backend', environment: 'aks-demo', severity: 'critical' }
        annotations: { summary: 'No AKS demo backend replicas are available', description: 'kube-state-metrics reports zero available backend replicas for two minutes.' }
        enabled: true
        severity: 0
        resolveConfiguration: { autoResolved: true, timeToResolve: 'PT5M' }
        actions: alertActions
      }
      {
        alert: 'TCGStoreBackendContainerRestartsAksDemo'
        expression: 'sum by (namespace, pod) (increase(kube_pod_container_status_restarts_total{namespace="tcg-store-aks-demo",container="backend",pod=~"tcg-store-backend-.*"}[10m])) >= 3'
        for: 'PT1M'
        labels: { app: 'tcg-store-backend', environment: 'aks-demo', severity: 'warning' }
        annotations: { summary: 'AKS demo backend container restarted at least three times', description: 'The backend container restarted at least three times in ten minutes.' }
        enabled: true
        severity: 2
        resolveConfiguration: { autoResolved: true, timeToResolve: 'PT5M' }
        actions: alertActions
      }
      {
        alert: 'TCGStoreBackendOOMKilledAksDemo'
        expression: 'kube_pod_container_status_last_terminated_reason{namespace="tcg-store-aks-demo",container="backend",pod=~"tcg-store-backend-.*",reason="OOMKilled"} == 1'
        labels: { app: 'tcg-store-backend', environment: 'aks-demo', severity: 'critical' }
        annotations: { summary: 'AKS demo backend container was OOMKilled', description: 'kube-state-metrics reports an OOMKilled last termination reason.' }
        enabled: true
        severity: 0
        resolveConfiguration: { autoResolved: true, timeToResolve: 'PT5M' }
        actions: alertActions
      }
      {
        alert: 'TCGStoreBackendHigh5xxRatioAksDemo'
        expression: '(tcg:http_5xx:ratio5m{environment="aks-demo"} > 0.01) and on (environment) (sum by (environment) (increase(http_server_requests_seconds_count{job="tcg-store-backend",environment="aks-demo"}[5m])) >= 100)'
        for: 'PT5M'
        labels: { app: 'tcg-store-backend', environment: 'aks-demo', severity: 'warning' }
        annotations: { summary: 'AKS demo backend 5xx ratio is above one percent', description: 'More than one percent of at least 100 requests failed with HTTP 5xx during five minutes.' }
        enabled: true
        severity: 2
        resolveConfiguration: { autoResolved: true, timeToResolve: 'PT5M' }
        actions: alertActions
      }
      {
        alert: 'TCGStoreBackendHighReadLatencyAksDemo'
        expression: 'tcg:http_read:p95_latency_seconds{environment="aks-demo"} > 1'
        for: 'PT10M'
        labels: { app: 'tcg-store-backend', environment: 'aks-demo', severity: 'warning' }
        annotations: { summary: 'AKS demo product or card read p95 is above one second', description: 'The ten-minute p95 for product or card reads is above the initial demo threshold.' }
        enabled: true
        severity: 2
        resolveConfiguration: { autoResolved: true, timeToResolve: 'PT5M' }
        actions: alertActions
      }
      {
        alert: 'TCGStoreBackendHikariPressureAksDemo'
        expression: 'tcg:hikari:connections_in_use_ratio{environment="aks-demo"} > 0.8 or hikaricp_connections_pending{job="tcg-store-backend",environment="aks-demo"} > 0 or increase(hikaricp_connections_timeout_total{job="tcg-store-backend",environment="aks-demo"}[5m]) > 0'
        for: 'PT5M'
        labels: { app: 'tcg-store-backend', environment: 'aks-demo', severity: 'warning' }
        annotations: { summary: 'AKS demo database pool is under pressure', description: 'Hikari usage exceeds 80 percent, connections are pending, or a timeout occurred.' }
        enabled: true
        severity: 2
        resolveConfiguration: { autoResolved: true, timeToResolve: 'PT5M' }
        actions: alertActions
      }
      {
        alert: 'TCGStoreBackendHikariMetricsUnknownAksDemo'
        expression: 'absent(hikaricp_connections_max{job="tcg-store-backend",environment="aks-demo"})'
        for: 'PT5M'
        labels: { app: 'tcg-store-backend', environment: 'aks-demo', severity: 'warning' }
        annotations: { summary: 'AKS demo database pool telemetry is unknown', description: 'No Hikari maximum-connection series is present.' }
        enabled: true
        severity: 2
        resolveConfiguration: { autoResolved: true, timeToResolve: 'PT5M' }
        actions: alertActions
      }
      {
        alert: 'TCGStoreBackendStorageErrorsAksDemo'
        expression: 'tcg:s3:operation_errors:rate5m{environment="aks-demo"} > 0'
        for: 'PT5M'
        labels: { app: 'tcg-store-backend', environment: 'aks-demo', severity: 'warning' }
        annotations: { summary: 'AKS demo object storage operations are failing', description: 'One or more bounded storage operations report errors.' }
        enabled: true
        severity: 2
        resolveConfiguration: { autoResolved: true, timeToResolve: 'PT5M' }
        actions: alertActions
      }
      {
        alert: 'TCGStoreBackendStorageMetricsUnknownAksDemo'
        expression: 'absent(tcg_storage_operations_total{job="tcg-store-backend",environment="aks-demo"}) or absent(tcg_storage_operation_duration_seconds_bucket{job="tcg-store-backend",environment="aks-demo"})'
        for: 'PT5M'
        labels: { app: 'tcg-store-backend', environment: 'aks-demo', severity: 'warning' }
        annotations: { summary: 'AKS demo object storage telemetry is unknown', description: 'No storage counter or histogram has been observed. Run an approved known-fixture operation; missing data is not healthy.' }
        enabled: true
        severity: 2
        resolveConfiguration: { autoResolved: true, timeToResolve: 'PT5M' }
        actions: alertActions
      }
    ]
  }
}

output recordingRuleGroupId string = recordingRules.id
output alertRuleGroupId string = alertRules.id
