# Component A isolated Kubernetes lab

This lab is a disposable Kind environment for the Component A reliability plan. It is separate from the development Compose stack and the shared Neon development branch. It deploys only into namespace `tcg-store-acceptance` and has no external Ingress or TLS edge. Its PostgreSQL and S3-compatible dependencies are ephemeral and run inside the lab. **Never point this lab at the shared development database or bucket.**

## Topology and limitations

`infra/reliability/kind-cluster.yaml` declares one control-plane plus two workers. The create command pins `kindest/node:v1.36.4@sha256:099e049362a1526b2db71494e1947aae99bd16290d7c895f2b7ea312e3cbfaed`; the image is a command-line setting because Kind's node entries do not accept an `image` field. Use Kind v0.33.0 or newer. See the [Kind v0.33.0 release](https://github.com/kubernetes-sigs/kind/releases/tag/v0.33.0) and [Kubernetes 1.36 lifecycle](https://kubernetes.io/releases/1.36/).

All three Kind nodes are containers on one physical Docker host. Node drain and rescheduling exercise Kubernetes scheduling/PDB behavior, not physical host, power, storage, or control-plane HA. Mark A-08 `NOT TESTED` if the expected Pod placement cannot be verified across two worker nodes.

The lab overlay includes the base Deployment, Service, HPA and PDB unchanged: two replicas, HPA range 2–4, PDB `minAvailable: 1`, base resources, probes, `preStop`, 45-second grace, and rollout settings. It sets `FLYWAY_ENABLED=false` and Hibernate DDL mode `validate` for the API Pods. A separate one-shot migration overlay prepares the isolated PostgreSQL dependency before the API is applied.

The Kind HPA requires a working Metrics API. Kind does not provide one by default. Before A-06, verify `kubectl get apiservice v1beta1.metrics.k8s.io` and `kubectl top nodes`; if metrics are unavailable or stale, mark scaling evidence `UNKNOWN/BLOCKED` rather than attributing the result to the app. Do not install an unpinned metrics component as an implicit side effect of this overlay.

## Preflight and immutable local image

Record the exact source commit, dirty-tree status and local image config digest in the run evidence. The overlay tag is `tcg-store-backend:pr39-component-a-baseline`; rebuild it from the reviewed source immediately before a run and record its immutable image ID. The tag alone is not proof of source identity. The overlay inherits `imagePullPolicy: IfNotPresent`. Load that image onto every Kind node and verify each Pod's actual image ID. Kind documents `kind load docker-image` and the interaction with `IfNotPresent` in its [quick start](https://kind.sigs.k8s.io/docs/user/quick-start/#loading-an-image-into-your-cluster).

Create the named Kind cluster only after reviewing capacity. Create `tcg-store-backend-secrets` out-of-band with run-scoped random values; `infra/reliability/secrets.example.yaml` is excluded from Kustomize resources and must never be applied as-is. The JDBC URL must remain the isolated `postgres-acceptance` Service URL. Record only the Secret reference/version, never values. Use `kubectl port-forward` for test traffic; this lab intentionally defines no public Service or Ingress.

From the repository root, the operator-controlled sequence is:

```powershell
kind create cluster --config infra/reliability/kind-cluster.yaml --image kindest/node:v1.36.4@sha256:099e049362a1526b2db71494e1947aae99bd16290d7c895f2b7ea312e3cbfaed --kubeconfig .local/pr39-work/component-a/kubeconfig
kubectl get nodes -o wide
docker build --tag tcg-store-backend:pr39-component-a-baseline .
kind load docker-image tcg-store-backend:pr39-component-a-baseline --name tcg-component-a
```

The image build/load commands above are illustrative and must use the exact reviewed source commit/tag and image digest for the run; they do not imply that this draft has been built or loaded. Create the namespace first, create the real Secret out-of-band, then apply the Kustomize overlay:

```powershell
kubectl apply -f infra/reliability/app/namespace.yaml
# Create tcg-store-backend-secrets from run-scoped values without printing them.
kubectl apply -k infra/reliability/dependencies/
kubectl wait --for=condition=Available deployment/postgres-acceptance deployment/s3mock-acceptance -n tcg-store-acceptance --timeout=5m
kubectl apply -k infra/reliability/migration/
kubectl wait --for=condition=complete job/tcg-store-backend-migration -n tcg-store-acceptance --timeout=5m
kubectl apply -k infra/reliability/
kubectl rollout status deployment/tcg-store-backend -n tcg-store-acceptance --timeout=5m
kubectl get pods,hpa,pdb -n tcg-store-acceptance -o wide
```

Before load, capture node allocatable resources and current host Docker limits. Base requests/limits are kept intact; at four backend Pods their application limits alone sum to 4 CPU and 4 GiB, before Kind/Kubernetes, the OS, observer tools, and any fault sidecars. Keep the HPA at its base 2–4 settings but do not ramp load until the live resource/Neon connection budget and reserve are proven safe. This is three Kubernetes nodes on one host, not three independent capacity pools.

## Isolated per-Pod DB fault path

For A-02 only, update only the Secret JDBC URL to the value shown in `fault/secret-url.example.yaml`, then apply `infra/reliability/fault/`. The patch adds a loopback-only Shopify Toxiproxy native sidecar to **each application Pod**. Kubernetes starts that sidecar before the app container, and its startup probe gates application startup on the proxy API being available. No proxy Service is created and port 8474 binds only to Pod loopback. The app connects to `127.0.0.1:15432`; each Pod's proxy independently forwards to the isolated in-cluster PostgreSQL Service. Toxiproxy supports a JSON startup config and an HTTP API to enable/disable one proxy; see the [official Shopify Toxiproxy documentation](https://github.com/Shopify/toxiproxy).

The isolated PostgreSQL path is non-TLS inside the disposable Kind network; this is not a production transport configuration. Do not apply this fault overlay to a shared or production namespace. The proxy's extra resources alter Pod CPU/memory utilization, so revert it before A-06. The Toxiproxy image is pinned to the verified linux/amd64 manifest digest for this Docker Desktop host.

To test one Pod, first identify its immutable UID, then port-forward only that Pod's sidecar admin port (for example, local 18474 to remote 8474). Disable proxy `acceptance-postgres` through the local forwarded Toxiproxy API for that Pod; probe liveness and readiness directly on that Pod and verify its UID/restart count remain stable. The API operation is `PATCH /proxies/acceptance-postgres` with JSON body `{"enabled":false}`; restore with `{"enabled":true}`. Keep the proxy disabled for the agreed 120-second observation, then re-enable it and record readiness/traffic recovery. Repeat separately for the other Pod. Never stop the database to simulate a per-Pod fault or create an unrestricted Toxiproxy Service. Keep the admin API bound to loopback and accessible only through an operator-controlled Pod port-forward.

Restore the direct `postgres-acceptance` JDBC URL, then remove the proxy sidecar and config volume explicitly. Applying the base overlay alone does not reliably remove fields previously added to a Deployment:

```powershell
kubectl patch deployment tcg-store-backend -n tcg-store-acceptance --type=strategic --patch-file infra/reliability/fault/restore-patch.yaml
kubectl delete configmap tcg-db-fault-proxy-config -n tcg-store-acceptance --ignore-not-found
kubectl apply -k infra/reliability/
kubectl rollout status deployment/tcg-store-backend -n tcg-store-acceptance --timeout=5m
kubectl get pods -n tcg-store-acceptance -o jsonpath='{range .items[*]}{.metadata.name}{" "}{range .spec.initContainers[*]}{.name}{" "}{end}{"\n"}{end}'
```

Confirm no `db-fault-proxy` sidecars or toxics remain and both replicas are ready before other phases.

## Cleanup

After collecting evidence, stop test traffic and restore the known-good image/config/HPA. Delete only namespace `tcg-store-acceptance` when its fixtures are reviewed for run ownership. Delete only Kind cluster `tcg-component-a` when no further tests depend on it. Its PostgreSQL and S3mock data are ephemeral inside that cluster. Never run broad cleanup against an external database or bucket.

## Official references

- [Kind release v0.33.0 and published node image digests](https://github.com/kubernetes-sigs/kind/releases/tag/v0.33.0)
- [Kind cluster configuration](https://kind.sigs.k8s.io/docs/user/configuration/)
- [Kind loading local images](https://kind.sigs.k8s.io/docs/user/quick-start/#loading-an-image-into-your-cluster)
- [Kubernetes 1.36 release support](https://kubernetes.io/releases/1.36/)
- [Kubernetes HPA metrics prerequisites](https://kubernetes.io/docs/tasks/run-application/horizontal-pod-autoscale/)
