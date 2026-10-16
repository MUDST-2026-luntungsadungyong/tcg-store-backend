# PR #39 runtime/storage/migration test audit — round 2

Date: 2026-10-16

Checkout: `backend`, branch `feat/kubernetes`, commit `b859aa1`

## Outcome

Ran exactly the remaining 10 unique selected Maven methods from the 24-method targeted inventory: 10 passed, 0 failures, 0 errors, 0 skipped. This round adds no new code defects; it confirms the remaining local configuration, metrics, multipart API, timeout, async/error correlation, and CORS cases. Existing real S3, database migration/restore, and Kubernetes live-acceptance gaps remain unchanged.

| Lane | Selected this round | Result |
|---|---:|---:|
| Step 1 storage | 5 | 5 passed |
| Component A | 1 | 1 passed |
| Component D Java observability | 4 | 4 passed |
| Total | 10 | 10 passed, 0 failed, 0 errors, 0 skipped |

Round-one plus round-two coverage is 24/24 targeted methods: Step 1 12/12, Component A 3/3, Component D Java 9/9. Step 2 still has no dedicated Maven test class for Flyway scenarios.

## Exact command

Executed with JDK 21.0.5 and cached dependencies in offline mode:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
.\mvnw.cmd --batch-mode --offline '-Dmaven.repo.local=C:\Users\User\.m2\repository' '-Dtest=S3SellerProductImageStorageTests#rejectsMissingS3Configuration+rejectsRelativeObjectStorageEndpoint,S3SellerProductImageStorageMetricsTests#recordsUploadCounterAndHistogramWithOnlyBoundedTags+recordsStorageFailuresWithoutPuttingObjectIdentifiersInMetricTags,SellerProductControllerIntegrationTests#postAcceptsFrontendMultipartImageUpload,HealthProbeIntegrationTests#gracefulShutdownHasAnExplicitApplicationTimeout,RequestCorrelationFilterTests#doesNotLogAnAsyncRequestBeforeCompletionAndLogsErrorAsFailure+logsOnlyAfterErrorDispatchAndClearsCorrelationBeforeTheNextRequest+restoresPreexistingMdcAndDoesNotLogSynchronousExceptionsAsSuccess,MetricsExposureIntegrationTests#corsPreservesExistingHeadersAndAllowsRequestIdCorrelation' test
```

Surefire class summaries: `MetricsExposureIntegrationTests` 1 pass; `RequestCorrelationFilterTests` 3 pass; `HealthProbeIntegrationTests` 1 pass; `SellerProductControllerIntegrationTests` 1 pass; `S3SellerProductImageStorageMetricsTests` 2 pass; `S3SellerProductImageStorageTests` 2 pass. Maven reported `BUILD SUCCESS`, 10 total tests, and zero failures/errors/skips.

## Evidence retained

- Exact method inventory: `../work/round2-runtime/selected-methods.md`
- Raw command output: `../work/round2-runtime/maven-selected.log`
- Surefire XML and text summaries copied after this run, before a later Maven run could replace them, are in `../work/round2-runtime/` under their original `target/surefire-reports` filenames.
- Round-one output and raw logs remain unchanged in `../outputs/pr39-runtime-round1.md` and `../work/round1-runtime/`.

## Scope limits

The multipart API test establishes the application request path and configured image URL under the test profile; it does not establish a real provider write or read. Storage metrics tests validate bounded labels and counters against a mocked S3 client. The remaining request-correlation tests exercise asynchronous completion, error dispatch, and MDC cleanup locally. The CORS case validates the configured preflight response in MockMvc. These results do not close the previously listed live acceptance requirements for existing image migration, cross-replica S3 reads and persistence, real Flyway database scenarios, Kubernetes SIGTERM/drain, or production metrics collection/networking. No cloud, real database, container, or cluster was changed, and no source implementation was modified.
