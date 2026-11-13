# Observability Phase 6 — Container Insights and backend log handoff

Date: 13 November 2026  
Scope: `aks-tcg-store-demo`, Log Analytics workspace `law-tcg-store-aks-demo`, and the backend runtime log contract.

## Objective

Phase 6 makes backend container logs usable for incident investigation without exposing credentials, payment data, or unnecessary personal data. The target path is:

```text
Spring Boot structured console log (stdout/stderr)
  -> AKS Container Insights
  -> Log Analytics workspace law-tcg-store-aks-demo
  -> ContainerLogV2 KQL query
```

## Completed platform validation

1. Opened **AKS → Monitor (Insights)** for `aks-tcg-store-demo` and confirmed Container Insights is active. The portal displayed cluster events and the Container Insights navigation surface; this is the Azure-managed agent path for workload `stdout`/`stderr` collection.
2. Opened **Log Analytics workspace → Logs** for `law-tcg-store-aks-demo` and used KQL mode.
3. Executed the following read-only query against the latest hour:

   ```kusto
   ContainerLogV2
   | where TimeGenerated > ago(1h)
   | where PodNamespace == "tcg-store-aks-demo" and ContainerName == "backend"
   | project TimeGenerated, PodName, LogSource, LogMessage
   | order by TimeGenerated desc
   | take 50
   ```

4. Azure returned **50 records** for backend pods. This confirms the complete AKS-to-Log-Analytics collection path and confirms that the `ContainerLogV2` schema is available in this workspace.

The query is read-only. It does not create a diagnostic setting, modify retention, change RBAC, or export log data.

## Application logging contract

The existing application configuration already satisfies the Phase 6 field contract:

| Required information | Implementation / source |
| --- | --- |
| Timestamp | Spring Boot structured JSON `@timestamp` |
| Level | Structured JSON `level` |
| Service | `application=tcg-store-backend` |
| Environment | `APP_ENVIRONMENT`; the AKS demo overlay sets `aks-demo` |
| Pod | Container Insights enrichment: `PodName` |
| Request ID | `X-Request-ID` or generated UUID stored in MDC as `requestId` |
| Message | Structured JSON `message` |
| Exception | Spring Boot structured exception output for error events |

`RequestCorrelationFilter` emits a safe `http_access` record after request completion and records only `requestId`, route template, method, response status, and duration. The filter removes MDC values when the request completes, including asynchronous/error paths, so values cannot leak into the next request on a reused thread.

## Search scenarios

The following queries are documented in [`docs/runbooks/observability.md`](../runbooks/observability.md) and are the accepted first-line procedures for the requested search cases:

| Scenario | Query/result expectation |
| --- | --- |
| Backend error in the latest hour | Filter `ContainerLogV2` for the backend pod and structured `ERROR`/`stderr` records. A no-row result is a valid outcome when no error occurred. |
| Checkout or order exception | Filter backend logs using `checkout`, `order`, `Exception`, and `ERROR`; investigate the returned request ID, pod, and timestamp. |
| Logs around a restart | Select the pod and restart time from Container Insights/Prometheus, then query the two-hour window around it. Absence of a log line alone is not proof of restart. |
| Logs by request ID | Filter only by the `X-Request-ID` value returned to the caller; never add user identity or an access token to the query. |
| Errors grouped by environment | Extract the structured `environment` field and summarize error count/pod count. |

## Data-protection controls

The following data is excluded from the application access log contract:

- Passwords, JWT/access tokens, API keys, SMTP credentials, and JDBC credentials
- Payment details and payment proof content
- Request bodies, query strings, cookies, raw URLs, and multipart data
- Email addresses, phone numbers, LINE IDs, and other unnecessary personal data

Additional safeguards:

- AKS overlays retain `JPA_SHOW_SQL=false`.
- Route templates are used instead of client-provided URL values.
- `requestId` and identifiers are not used as Prometheus metric labels.
- Log Analytics access and export must remain controlled through Azure RBAC; no public log endpoint is introduced.

## Automated verification

Executed with the installed JDK 21 at `C:\Program Files\Java\jdk-21`:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\mvnw.cmd --batch-mode "-Dtest=StructuredLoggingTests,RequestCorrelationFilterTests" test
```

Result:

- `StructuredLoggingTests`: 1 passed
- `RequestCorrelationFilterTests`: 5 passed
- Total: **6 passed, 0 failed, 0 errors, 0 skipped**

The structured-log test parses the actual JSON access record and verifies that test sentinels from query strings, Authorization, cookies, request bodies, JDBC URLs, and the generated-password warning do not appear in captured logs. The correlation tests cover accepted/generated request IDs, error dispatch, async completion, and MDC cleanup.

## Operational notes

- Phase 6 does not alter Log Analytics retention, RBAC, export destinations, or diagnostic settings. Those are subscription-level decisions and require a platform owner.
- The portal's temporary Observability Agent conversation was intentionally discarded before using KQL mode. This removed only the temporary portal conversation; it did not remove any Azure resource, container log, dashboard, or alert rule.
- The alert-routing work from Phase 5 remains separate on `feat/observability-phase-5-alert-routing` and is not included in this Phase 6 branch.

## Acceptance conclusion

Phase 6 is complete for the AKS demo scope: backend stdout/stderr collection is live, the application supplies the required structured/correlated fields, sensitive request data is covered by tests and configuration, and the team has reproducible KQL procedures for the required investigations.
