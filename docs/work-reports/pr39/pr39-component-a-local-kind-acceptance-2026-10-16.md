# Component A — Local Kind acceptance attempt

Date: 2026-10-16 (Asia/Bangkok)  
Branch: `feat/kubernetes`  
Source HEAD: `927f3c29306149461764d8aa5c75bb9ac900537a`  
Overall result: **BLOCKED during image load by Docker/host responsiveness**

## Scope and safety boundary

Attempted local Component A acceptance using only the named Kind cluster
`tcg-component-a`. No Neon/cloud database, shared bucket, browser E2E, or shared
service configuration was changed. The existing
`tcg-store-service-backend-cloud` and `tcg-store-service-frontend` containers
were running before provisioning and remained present in the last successful
Docker listing. No image, volume, database, S3 data, or unrelated container was
removed.

## Preflight and work completed

| Item | Result |
|---|---|
| Git worktree | `feat/kubernetes`, HEAD `927f3c2`; only pre-existing unrelated untracked user files were present. |
| Docker preflight | Engine `29.4.0`; cloud backend healthy and frontend running. |
| C: free before work | `9,632,428,032` bytes (about 8.97 GiB). |
| Kind binary | Cached Kind v0.33.0; SHA-256 matched `target/component-a-tools/kind-windows-amd64.sha256sum`. |
| Backend image | Build succeeded from this HEAD; image index `sha256:c68e9848eb65ff87e9777d78ca3e48e09eedff6d2be17a882678c23be97ad6db`, config/image ID `sha256:c509b952f62ea51e5218f31937bb388bd9d298b57e9182d4dabb468217b6210d`, OCI revision matched HEAD. |
| Kind cluster | Created `tcg-component-a` with pinned Kind node image `kindest/node:v1.36.4@sha256:099e049362a1526b2db71494e1947aae99bd16290d7c895f2b7ea312e3cbfaed`. Kubectl confirmed control-plane and both workers `Ready`. |
| C: after cluster creation | About 8.99 GiB free. |
| Image load | Began loading the backend image to `tcg-component-a-worker`, control-plane, and `tcg-component-a-worker2`. Completion could not be confirmed. |

After the image load began, the Docker health query did not return within the
10-second check and subsequent kubectl and PowerShell checks also stopped
returning promptly. The load process was interrupted. In keeping with the
operator stop condition, no further Docker API calls or Docker Desktop restart
was attempted. The final free-space value and cluster state could not be
reconfirmed.

## Acceptance verdicts

| Scenario | Verdict | Evidence/boundary |
|---|---|---|
| A-00 baseline, 2 Ready API Pods and 600-second stability | **BLOCKED** | Cluster nodes were Ready, but API/dependency workloads and the stability collector were not deployed. |
| A-01 shared state and Pod replacement | **NOT TESTED** | No application Pods or isolated database/S3 fixture were deployed. |
| A-02 per-Pod DB outage and recovery | **NOT TESTED** | No Toxiproxy or API Pods were deployed; no fault was injected. |
| A-03 SIGTERM with admitted in-flight request | **NOT TESTED** | No deterministic request fixture or API Pods were running. |
| A-04 rolling update | **NOT TESTED** | No baseline/candidate Deployment was running. |
| A-05 failed rollout and rollback | **NOT TESTED** | No rollout mutation was made. |
| A-06 HPA 2→3→4→2 | **BLOCKED** | No API Deployment, Metrics Server, or load stage was run. |
| A-07 measured DB connection budget | **UNKNOWN** | No isolated PostgreSQL observation or provider quota evidence was collected. |
| A-08 worker drain/PDB | **NOT TESTED** | Cluster topology was created, but no API Pods/PDB were deployed and no node was drained. |

No scenario is reported as PASS. No harness or manifest defect was confirmed,
so no source changes were made. Focused tests were not run because execution
stopped before deploying or changing the harness.

## Cleanup and final state

Cleanup was not attempted because Docker stopped responding and the task
requires stopping when its API fails. The three-node Kind cluster was created
and had been observed Ready; its final running state after the failed image-load
check is **unconfirmed**. Do not assume it was cleaned up. The dedicated
kubeconfig remains under ignored `.local/pr39-work/component-a/kubeconfig`.
Existing cloud containers and user data were not deliberately changed.

## Rerun gate

First restore normal Docker/host responsiveness without deleting data or
restarting Docker repeatedly. Then inspect only the `tcg-component-a` resources
and disk space. Resume with image-load verification or, if the cluster is
incomplete, clean up only `tcg-component-a` before recreating it. Maintain the
2 GiB C: stop threshold and verify the 9 GiB starting capacity again before the
full workload/image set is loaded.
