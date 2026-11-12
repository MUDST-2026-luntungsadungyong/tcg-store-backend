# Azure AKS demo backend runbook

Status on 11 November 2026: the backend and API-only Caddy gateway run on
Azure Kubernetes Service. The frontend runs on Cloudflare Pages at
<https://tcg-store-frontend.pages.dev/>. The public API origin is
<https://tcgstore-demo-2026-11.malaysiawest.cloudapp.azure.com/>.
The Azure for Students subscription has $100 credit. Its policy allows resource
deployment in `malaysiawest`, `indonesiacentral`, `japaneast`, `centralindia`, and
`indiasouthcentral`. Southeast Asia and East Asia were rejected for resources.

## Live resources

| Resource | Value |
| --- | --- |
| Resource group | `rg-tcg-store-aks-demo` |
| AKS | `aks-tcg-store-demo` in `malaysiawest`, Free tier |
| Node pool | One `Standard_D4s_v4` node, 4 vCPU, 64 GB OS disk |
| Namespace | `tcg-store-aks-demo` |
| Container registry | `tcgstorecimah2026.azurecr.io` (Basic, `centralindia`) |
| Backend image | Pinned ACR digest from the successful `main` deployment workflow |
| API gateway | Plain Caddy on AKS; public Azure DNS label, HTTPS, and `/api` proxy; `/` returns 404 |
| API gateway TLS storage | `managed-csi` persistent volume claim |
| Database | Neon branch `aks-demo-2026-11-11`, forked from `development`; auto-deletes after seven days |
| Budget | `tcg-aks-demo-20usd`, USD 20/month with 25%, 50%, and 75% email alerts |

ACR Tasks are disallowed on this Azure for Students subscription. The first
image was built locally from a clean `origin/main` worktree and pushed to ACR;
subsequent images are built by GitHub Actions. AKS has `AcrPull` through its
managed identity; the registry
admin account is disabled. The earlier Malaysia West registry was empty and
deleted because ACR Tasks build was unavailable in that region.

The Neon demo branch has a separate Postgres host and storage endpoint. It
retains the public `tcg-product-images` bucket. The local development
credentials could connect to the fork. The existing `neondb_owner` credential
is used for both runtime and migration in this short demo because that owner
cannot change the password of the cloned runtime role. Replace this with a
dedicated runtime credential before longer-lived production use.

## Verification

```powershell
az account show --query '{name:name,state:state}'
az aks show -g rg-tcg-store-aks-demo -n aks-tcg-store-demo `
  --query '{location:location,tier:sku.tier,state:provisioningState}'
az aks get-credentials -g rg-tcg-store-aks-demo -n aks-tcg-store-demo
kubectl get nodes -o wide
kubectl get deployment,job,pods,service -n tcg-store-aks-demo -o wide
kubectl port-forward -n tcg-store-aks-demo service/tcg-store-backend 18080:80
```

In a second terminal, request `http://127.0.0.1:18080/api/health` and
`http://127.0.0.1:18080/actuator/health/readiness`. On 11 November both
reported `UP`; the API reported the database connected and 2,002 cards.
The migration Job completed and the Deployment was `1/1 Available` on a Ready
Azure node. The backend Service remains `ClusterIP`. The public API gateway's
Caddy proxy forwards `/api/*` to that Service over the cluster network. The
AKS `/` returns 404 while the Cloudflare Pages homepage returns 200. Public
`/api/health` through Pages reported `UP`, the database connected, and 2,002
cards. A Pages login validation POST reached the backend and an auction
WebSocket handshake returned HTTP 101.

To redeploy, update both Kustomizations to the same new ACR digest, render and
server dry-run the manifests, run the migration Job first, and only then apply
the app overlay. Never commit the nine database, JWT, and object-storage
secret values. The Kubernetes Secret is `tcg-store-backend-secrets` in the demo
namespace. A temporary source file used to create it must stay outside Git and
be deleted after use.

## Cost and shutdown

AKS Free removes only the control-plane management charge. The Linux node's
public retail price in Malaysia West was USD 0.22/hour on 11 November 2026;
the OS disk, API gateway Load Balancer, public IP, TLS storage disk, and network
add charges. ACR Basic was USD 0.17/day. The budget
sends alerts but does not stop spending. Check Azure Cost Management daily.
When the demo no longer needs to stay online, delete the dedicated resource
group after confirming its contents. Scaling the app to zero does not stop the
node VM and disk charges. Neon removes the demo branch automatically on
18 November 2026; verify this after the submission.

The GitHub build workflow publishes to private GHCR. The
`.github/workflows/deploy-aks-demo.yml` workflow ran successfully after the
main-branch build: it used GitHub OIDC, built the verified commit to ACR, ran
Flyway migration, and rolled out the API. See the successful
[backend deploy run](https://github.com/MUDST-2026-luntungsadungyong/tcg-store-backend/actions/runs/42942899284).
The federated identity `id-tcg-github-aks-demo` is limited to ACR push and
admin credentials for this demo AKS cluster. No Azure client secret is stored
in GitHub. The frontend deploys through Cloudflare Pages Git integration from
`main`; its former AKS deployment workflow was removed.
The purchased domain `tcgmythix.store` has Cloudflare nameservers set in GoDaddy and is waiting for DNS activation and Pages custom-domain setup.
