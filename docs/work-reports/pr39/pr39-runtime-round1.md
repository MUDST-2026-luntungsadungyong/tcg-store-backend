# PR #39 runtime/storage/migration test audit — round 1

Date: 2026-10-16

Checkout: `backend`, branch `feat/kubernetes`, commit `b859aa1` (clean before testing)

## Outcome

Ran 14 unique selected Maven test methods under JDK 21: 14 passed, 0 failures, 0 errors, 0 skipped. Selection covered a little over half of the directly targeted automated cases in each runnable lane. No implementation files were changed.

| Lane | Targeted automated denominator | Selected | Deferred | Result |
|---|---:|---:|---:|---|
| Step 1 storage: 9 `S3SellerProductImageStorageTests`, 2 `S3SellerProductImageStorageMetricsTests`, and 1 multipart-upload API test | 12 | 7 | 5 | 7 passed |
| Step 2 database: Maven tests specifically exercising Flyway fresh/legacy/repeat/failure/restore behavior | 0 | 0 | 0 | No Maven class/test exists in `src/test/java`; not represented as a pass |
| Component A: 2 `HealthProbeIntegrationTests` and 1 `GracefulShutdownIntegrationTests` | 3 | 2 | 1 | 2 passed |
| Component D Java logs/metrics: 5 request-correlation, 1 structured-logging, 3 metrics-exposure tests | 9 | 5 | 4 | 5 passed |

The Step 1 denominator is scoped to storage-specific tests plus the multipart-upload API case; other seller API tests cover general listing and URL behavior. The storage metrics class is counted once in Step 1, not counted again in Component D.

## Commands and test evidence

The default `java` was JDK 17 while `pom.xml` requires Java 21. JDK 21.0.5 was available at `C:\Program Files\Java\jdk-21`. An initial offline Maven attempt failed before test discovery because the Spring Boot 4.0.8 parent POM was not cached. With network access approved for Maven Central, the selected suite completed; later invocations reused the cache offline.

First invocation — 6 storage cases plus the readiness and graceful-shutdown cases; 8 passed:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
.\mvnw.cmd --batch-mode '-Dtest=S3SellerProductImageStorageTests#storesValidatedImageWithStablePublicUrlAndMetadata+storesJpegAndWebpBasedOnMagicBytes+rejectsSpoofedImageContent+rejectsContentTypeThatDisagreesWithMagicBytes+rejectsImagesLargerThanFiveMebibytes+ignoresExternalAndMalformedUrlsWhenDeleting,HealthProbeIntegrationTests#databaseOutageOnlyRemovesReadinessAndRecoversWithoutRestart,GracefulShutdownIntegrationTests#inFlightRequestCompletesBeforeApplicationStops' test
```

Storage rollback cleanup follow-up — 1 passed:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
.\mvnw.cmd --batch-mode --offline '-Dmaven.repo.local=C:\Users\User\.m2\repository' '-Dtest=S3SellerProductImageStorageTests#deletesCreatedObjectWhenTransactionRollsBack' test
```

Component D Java observability sample — 5 passed:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
.\mvnw.cmd --batch-mode --offline '-Dmaven.repo.local=C:\Users\User\.m2\repository' '-Dtest=RequestCorrelationFilterTests#acceptsSafeIdsAndEmitsOnlyTemplatedAccessFields+replacesMalformedAndOversizedIdsAndUsesUnknownRouteForUnmappedRequest,StructuredLoggingTests#emitsParseableJsonAccessLogWithoutRequestCredentialsQueriesOrBody,MetricsExposureIntegrationTests#exposesPrometheusAndOnlyTheExplicitActuatorSurface+allowsScrapeWithoutJwtButKeepsOtherProtectedApiRoutesAuthenticated' test
```

The aggregate results were 8 + 1 + 5 = 14 tests, each invocation reporting zero failures, errors, and skips. Raw command logs are in `../work/round1-runtime/`:

- `maven-selected-escalated.log` — first invocation (8 passed)
- `maven-s3-rollback.log` — rollback follow-up (1 passed)
- `maven-observability-selected.log` — observability invocation (5 passed)
- `maven-selected.log` — initial offline dependency-resolution failure, before tests ran

## Findings from code and test scope

- Image storage generates a UUID key under `products/`, derives MIME and extension from validated bytes, and returns the configured public base URL. Upload registers best-effort object cleanup for transaction rollback; delete accepts only a matching managed URL and defers deletion until commit when a transaction is active. See `src/main/java/com/mythix/tcg/seller/application/S3SellerProductImageStorage.java:56-71`, `:77-107`, and `:110-145`. The selected unit tests pass for format/size rejection, URL scoping, and rollback cleanup.
- The tested store uses a Mockito `S3Client`, so these passes do not prove S3Mock/provider interoperability, cross-replica read, Pod restart persistence, existing-image migration, or real-bucket/database URL consistency. Those remain Step 1 acceptance work.
- The Component A outage test disables Boot's DB health indicator and injects its own `AtomicReference<Health>` (`src/test/java/com/mythix/tcg/HealthProbeIntegrationTests.java:23-26`, `:35-59`, `:69-80`). It verifies readiness/liveness grouping and recovery logic, not a real database outage. The graceful-shutdown test does exercise a local HTTP request held in flight while the Spring context closes (`src/test/java/com/mythix/tcg/GracefulShutdownIntegrationTests.java:31-60`), but it does not send Kubernetes SIGTERM or test Service endpoint drain.
- Manifest configuration matches the documented contract: two initial replicas, `maxUnavailable: 0`, 45-second termination grace, 5-second `preStop`, liveness on `/actuator/health/liveness`, and readiness on `/actuator/health/readiness` (`infra/kubernetes/base/deployment.yaml:9-17`, `:32-40`, `:100-127`). It does not establish live rollout, HPA/load, PDB, or node-disruption behavior.
- Migration code separates the non-web migration runner from the API and enables Flyway through the migration profile; the Kubernetes Job sets `neon,migration` and `FLYWAY_ENABLED=true` (`src/main/java/com/mythix/tcg/DatabaseMigrationApplication.java:7-23`; `infra/kubernetes/base/migration/job.yaml:23-55`). The documented DB matrix calls for fresh V1–latest, legacy upgrade, repeat, failure gate, same-image, and backup/restore (`docs/kubernetes-step-2-versioned-database-migration.md:98-120`; live acceptance matrix `docs/kubernetes-live-acceptance-plan.md:200-219`). There is no Maven test class for those database scenarios, so this audit could not provide new Flyway execution evidence.
- The Component D Java sample passed the request-ID safety/templating, JSON secret-redaction, Prometheus metric exposure, and unauthenticated scrape checks. The private network path, production scrape, collector/receiver, and alert behavior are external acceptance, not covered by these MockMvc/log-capture tests.

## Still unfinished vs unavailable here

Implementation contracts and local test cases exist for S3-only storage, DB readiness/liveness behavior, graceful shutdown, migration gating, and Java logs/metrics. This pass did not independently reproduce the older status document's reported full-suite, migration-rehearsal, or cloud image-migration evidence.

Live acceptance remains unfinished: migrate existing images and reconcile database references against a real bucket; upload/read across two replicas; replace/restart Pods; exercise real transaction rollback/shared-reference deletion; run fresh/legacy/repeat/failing Flyway migrations and isolated restore; and run Component A outage, rollout, HPA/load, connection-budget, and disruption cases. These need protected staging DB/S3 credentials and fixtures, a healthy S3Mock or approved staging object store, a working Kubernetes cluster/context with the required platform/metrics components, and safe restore targets. No credentials were read or recorded and no cloud, database, container, or cluster state was changed in this audit.
