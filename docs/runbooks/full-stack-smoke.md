# Full stack smoke acceptance

The API harness defaults to read only. It supports `local`, `staging`, and `production`; staging and production require HTTPS. Production mutation is rejected before the transport is called. Reports contain check names, statuses, durations, a run ID, and sanitized failure reasons only.

## Required read only inputs

For staging and production, provide these through the runner's secret/environment channel:

| Input | Purpose |
|---|---|
| `TCG_SMOKE_BUYER_EMAIL`, `TCG_SMOKE_BUYER_PASSWORD` | Dedicated smoke identity for login and `/api/auth/me` |
| `TCG_SMOKE_KNOWN_OBJECT_URL` | HTTPS URL of a known, test-owned image fixture |
| `TCG_SMOKE_STORAGE_HOST` | Exact allowlisted hostname for that fixture |
| `TCG_SMOKE_STORAGE_PUBLIC_BASE_URL` | Exact reviewed public base URL for the test object bucket |
| `TCG_SMOKE_FRONTEND_ORIGIN` or `--frontend-origin` | Exact deployed frontend origin used for the CORS preflight contract |

Run `python scripts/smoke/api_smoke.py --environment production --base-url https://<api-host> --frontend-origin https://<frontend-host> --output artifacts/production-smoke.json` for the production read checks. The CLI also accepts `TCG_SMOKE_FRONTEND_ORIGIN`; the release orchestrator can pass this environment variable through without changing its CLI. Staging uses the same read checks. The run fails before making requests if required inputs are missing or the fixture URL does not use the allowlisted storage host.

Required read checks cover `/api/health`, paged `/api/products` and `/api/cards` contracts, anonymous cart/orders/admin denial, non-admin admin denial, public non-routing for Actuator endpoints, buyer login plus `/api/auth/me`, CORS allowance for `Authorization`, `Content-Type`, `Idempotency-Key`, and `X-Request-ID` plus exposed `X-Request-ID`, and retrieval of the known image fixture. Local runs may omit cloud-only identities and the known object; those checks are explicitly marked skipped only for local runs.

The cluster-private liveness and readiness probes are separate acceptance checks. They must run from an authorized in-cluster or private runner against `/actuator/health/liveness` and `/actuator/health/readiness`; the public harness intentionally expects those paths not to route through the public host. Production acceptance must include evidence for both private probes, catalog reads, authenticated `/me`, anonymous and non-admin authorization denials, known object retrieval, and frontend read-only browser checks. Missing evidence is pending, never healthy by omission.

## Staging mutation fixture

Mutation is an opt-in staging-only flow. Before invoking it, the platform owner must provide an isolated staging database and object bucket (or an isolated clone) whose identities are allowlisted. Use dedicated approved seller, buyer, and second-buyer accounts; the buyer cart must be empty. Never point this mode at production or a shared customer dataset.

The `--fixture-policy` JSON file contains no credentials. Example shape:

```json
{
  "schemaVersion": 1,
  "environment": "staging",
  "apiOrigin": "https://staging-api.example.invalid",
  "databaseIdentity": "<exact staging database fixture identity>",
  "fixtureMarker": "<marker verified in the isolated fixture database>",
  "testDatasetIdentity": "<unique allowlisted dataset identifier>",
  "approvedSellerId": "<dedicated seller id>",
  "approvedBuyerId": "<dedicated buyer id>",
  "secondBuyerId": "<dedicated second buyer id>",
  "authorizationChecks": true,
  "storage": {
    "host": "<exact object storage host>",
    "bucket": "<exact bucket identity>",
    "fixturePrefix": "products/",
    "publicBaseUrl": "https://<exact object storage host>/<provider bucket path>"
  }
}
```

Set matching `TCG_SMOKE_DATABASE_IDENTITY`, `TCG_SMOKE_FIXTURE_MARKER`, `TCG_SMOKE_TEST_DATASET_IDENTITY`, `TCG_SMOKE_APPROVED_SELLER_ID`, `TCG_SMOKE_APPROVED_BUYER_ID`, `TCG_SMOKE_STORAGE_HOST`, `TCG_SMOKE_STORAGE_BUCKET`, `TCG_SMOKE_FIXTURE_PREFIX`, and `TCG_SMOKE_STORAGE_PUBLIC_BASE_URL` inputs, plus dedicated seller/buyer email and password inputs. With `authorizationChecks` enabled, also provide `TCG_SMOKE_SECOND_BUYER_ID`, `TCG_SMOKE_SECOND_BUYER_EMAIL`, and `TCG_SMOKE_SECOND_BUYER_PASSWORD`.

Then run `python scripts/smoke/api_smoke.py --environment staging --base-url https://<staging-api-host> --fixture-policy <protected-policy-file> --mutating --output artifacts/staging-smoke.json`.

Before requests, the harness compares the API origin and supplied database, marker, dataset, account, host, bucket, and public-base URL identities against the fixture policy. The public base URL must exactly match `TCG_SMOKE_STORAGE_PUBLIC_BASE_URL`, use HTTPS on the allowlisted storage host, and have no credentials, query, or fragment. The returned image URL must use that same origin, have no credentials, query, or fragment, and begin with the exact reviewed public-base path plus `/`; only then is the remaining key checked against `products/<uuid>.<image extension>`. This supports provider URLs such as `/storage/v1/object/public/<bucket>/products/...` without accepting a malicious path prefix. The database identity and marker, and the actual bucket identity, must be independently verified by the platform owner and injected into the run; the public application API cannot attest those infrastructure identities on its own. The harness reads only the exact URL returned for its newly created listing. It then logs in, verifies seller and buyer account IDs, checks the buyer cart is empty, creates one uniquely named listing and scoped image, exercises add/update/read cart behavior, performs checkout twice using the same run-scoped idempotency key, verifies the same order and one reservation, checks cross-buyer order denial, then hides only the listing it created. Cleanup failure remains a failed run and is reported as its own check. The resulting order/listing history may prevent physical deletion under normal retention rules; do not bypass domain cleanup through direct database or bucket operations. Test-owned images must stay under the allowlisted bucket and object key scope.

## Browser contract

`scripts/smoke/frontend_smoke.spec.js` is read only: it searches the deployed card discovery page and checks the login form's password visibility control without submitting credentials. Locators were checked against `CardDiscoveryPage.tsx` and `LoginPage.tsx` in the separately maintained frontend repository. This browser harness does **not** implement the full frontend login, seller upload, cart, or checkout journey; those remain live end-to-end acceptance gates and must not be inferred as passed from the API mutation harness. Supply `TCG_SMOKE_ENVIRONMENT` and `TCG_FRONTEND_URL`, then run `npm ci` and `npm test` from `scripts/smoke`. Playwright trace, screenshots, and video are disabled; do not enable HAR capture or save browser storage state containing credentials or tokens.

## External release gates

Mocked tests establish request ordering and contracts, not staging or production acceptance. Live gates still require platform-provided exact database and bucket identities, fixture marker verification, dedicated account provisioning, a known object fixture, private probe access, deployed frontend URL/build identity, and attached run evidence. No production mutation, cloud API, or deployment is part of local test execution.
