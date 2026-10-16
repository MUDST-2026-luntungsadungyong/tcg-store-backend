# Component A — Real-cluster execution plan

Date: 2026-10-16 (Asia/Bangkok)

Branch: `feat/kubernetes` / PR #39

Status: IN PROGRESS — a plan is not an acceptance result

## Outcome and test boundary

Component A closes only when evidence comes from an isolated Kubernetes control
plane with two schedulable workers. Docker Compose remains a smoke baseline and
must not be reported as Kubernetes acceptance. The lab uses the production-like
Deployment contract (two replicas, rolling update, probes, graceful shutdown,
HPA and PDB), but test credentials, fixture identities and database fault paths
are isolated from the shared development runtime.

No test may suspend the shared Neon development compute, revoke shared
credentials, delete a shared namespace, or run an unbounded load. A controlled
TCP proxy in front of the test database is the preferred fault boundary because
it can close existing test connections without changing the external database.

## Frozen acceptance contract

Thresholds are fixed before fault injection and may not be relaxed after a run:

| ID | Scenario | Required result |
|---|---|---|
| A-00 | Baseline | Two Ready Pods on different workers, stable UIDs/restarts for 600 s, per-Pod liveness/readiness and request/DB metrics collected. |
| A-01 | Shared state | Both Pod UIDs serve the same authenticated fixture; a write made through one Pod is readable through the other and after Pod replacement. |
| A-02 | DB outage/recovery | Fault Pod A, Pod B, then both for 120 s each. Affected Pods keep liveness `200/UP`, readiness becomes non-ready and EndpointSlice removes them, with no restart. After fault removal readiness returns within 60 s and remains healthy for two checks. |
| A-03 | SIGTERM/in-flight | A deterministic 10–20 s request is admitted before normal Pod deletion and completes within the 30 s Spring window. Pod exits inside 45 s without 137/SIGKILL; three repetitions are required. |
| A-04 | Rolling update | With HPA fixed at two, a schema-compatible immutable candidate rolls out under continuous traffic. Ready capacity never drops below two and the rollout completes. |
| A-05 | Failed rollout/rollback | A candidate with a deliberately wrong readiness path never receives Service traffic, old Pods keep serving, Deployment reaches `ProgressDeadlineExceeded`, and rollback restores the exact image/config hashes and 2/2 Ready. |
| A-06 | HPA/load | After budget approval, bounded staged load drives 2→3→4 Pods, holds for 600 s, then naturally returns to two after the configured 300 s stabilization. CPU and memory metrics must both be observed; no forced downscale may be called a pass. |
| A-07 | Connection budget | One-second DB/Hikari samples cover baseline, HPA peak, surge and terminating overlap. Observed peak remains below the approved usable quota while preserving reserve. Missing quota or gaps are `UNKNOWN`, never zero. |
| A-08 | Placement/drain | Two Ready replicas are placed on separate workers. Draining one worker under PDB keeps at least one Ready endpoint and reschedules the replica; the run-owned worker is uncordoned afterward. |

Normal phases allow at most 0.1% unexpected request failures. Controlled critical
fixture operations require 100% correctness. Normal-phase p95 must be no more
than `max(500 ms, 1.5 × baseline p95)`. Expected failures during the both-Pod DB
outage are counted separately and do not support a zero-downtime claim.

## Connection gate

Before enabling HPA above two, calculate all quantities in the same quota scope:

```text
C_api = (R_max + maxSurge + terminating_overlap_budget) * pool_per_pod
C_total = C_api + other_clients + migration_jobs + observers + reserve
C_total <= Q_usable
```

Defaults are `R_max=4`, `maxSurge=1`, `pool_per_pod=5`, and `reserve>=5`.
`terminating_overlap_budget` must be measured or bounded explicitly. Provider
quota, PostgreSQL `max_connections`, reserved connections, role/database limits,
pooled-versus-direct units, and baseline clients must be timestamped. If the
provider limit is unavailable or the units do not match, A-07 is `UNKNOWN` and
the run stops before the four-replica stage.

## Execution order and gates

1. Build the PR image once, record source SHA and local immutable image ID, and
   load that exact image into every cluster node.
2. Create a named kind cluster with one control-plane and two workers, pinned
   node image digest, dedicated kubeconfig/context and namespace. Install the
   Metrics Server and wait for both workers and Metrics API.
3. Create only run-scoped Secrets from the local ignored environment. Render the
   acceptance overlay and record a redacted manifest hash. Deploy the DB proxy,
   then two application replicas, HPA and PDB.
4. Start collectors before A-00. Evidence must contain UTC and monotonic time,
   run ID, context, namespace, object UID/resourceVersion, node, image ID,
   restart/deletion state, EndpointSlices, events, HPA/PDB, per-Pod probes,
   request totals/latencies and DB connection samples.
5. Run A-00 and A-01. Abort on unstable baseline, incorrect shared state, missing
   per-Pod evidence or data corruption.
6. Run A-02 with one independently controlled proxy path per Pod. Confirm the
   fault actually blocks DB traffic before starting each outage timer.
7. Freeze HPA at two and run A-03 through A-05. Snapshot known-good image and
   pod-template/config hashes before mutations. Restore the known-good revision
   after every scenario.
8. Re-enable HPA. Run the connection gate, then bounded A-06 stages. Stop load
   immediately on reserve breach, DB queue growth, connection timeout, OOM,
   restart or missing critical metrics.
9. Run A-08, uncordon only the run-owned worker, then perform final smoke and
   verify 2/2 Ready, no fault rules, normal connection reserve and stable probes.
10. Produce PASS/FAIL/BLOCKED/NOT TESTED per A-ID plus a SHA-256 manifest of the
    sanitized evidence bundle. A human review of raw evidence is mandatory.

## Abort and cleanup

Every fault and load action has a `finally` cleanup and a watchdog. Cleanup is
targeted to the run ID: remove proxy toxic rules, stop load, restore the captured
Deployment/HPA, uncordon the selected worker and delete only run-scoped fixtures.
Do not delete a broad namespace/database/bucket or hide failed-run evidence.

## Luna-6 work split and parent review

| Lane | Ownership | Parent review gate |
|---|---|---|
| Cluster/faults | `infra/reliability/**`, `docs/component-a-lab.md` | Rendered objects preserve the base rollout/probe/graceful contract; cluster and cleanup are isolated and reproducible. |
| Collector/load | `scripts/reliability/collect.py`, `run_component_a.py`, related tests | Missing/stale data fails closed; attempts are not silently retried; every mutation has bounded scope and cleanup. |
| DB budget | `scripts/reliability/db_observer.py`, related tests | Read-only SQL, no secret output, quota units are explicit, terminating/other/job/observer connections are included. |
| Primary agent | provision/run, review all patches, inspect raw evidence, issue final verdict | No mock/unit result is promoted to live acceptance. |

## Current blockers at plan freeze

- No Kubernetes context existed at preflight; the isolated cluster must be
  provisioned before any A-ID can pass.
- The existing Neon CLI session could not authorize a branch-list request. The
  cluster may be prepared independently, but live DB fault/load phases must not
  use or mutate the shared development branch. A dedicated database branch or
  an equivalently isolated test database is required.
- A deterministic in-flight endpoint/workload fixture must be verified before
  A-03; a fast `curl` is not evidence of an admitted in-flight request.
