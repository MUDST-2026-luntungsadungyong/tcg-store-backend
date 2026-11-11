# คู่มือ AKS ของ TCG Store (สถานะ 11 พฤศจิกายน 2026)

คู่มือนี้อธิบายระบบที่ deploy จริง ณ วันที่ระบุ ไม่ใช่ manifest Kubernetes ทุก environment ใน repo. หน้าเว็บอยู่ Cloudflare Pages; AKS รัน Spring Boot API และ Caddy API gateway; Neon เก็บฐานข้อมูลและรูปภาพ. โดเมนที่ถูกต้องคือ `tcgmythix.store` และกำลังรอ Cloudflare DNS activation. URL ที่ใช้ได้ก่อนผูกโดเมนคือ `https://tcg-store-frontend.pages.dev/`.

## AKS คืออะไร และใครคุมอะไร

Azure Kubernetes Service (AKS) คือบริการ Kubernetes ที่ Azure ดูแล control plane ให้. Control plane จัดตาราง Pod และรักษาสถานะที่ประกาศไว้; **node** คือ VM ที่รัน container จริง. AKS Free ยกเว้นค่าจัดการ control plane แต่ node, disk, load balancer, public IP และ ACR มีค่าใช้จ่าย. ในโครงการนี้ resource group คือ `rg-tcg-store-aks-demo`, cluster คือ `aks-tcg-store-demo` ที่ Malaysia West, และ namespace คือ `tcg-store-aks-demo`.

```text
GitHub main -> docker-build.yml (verify/GHCR) -> deploy-aks-demo.yml
             -> GitHub OIDC -> ACR -> AKS node -> migration Job -> backend Pod
Browser -> Cloudflare Pages -> Pages Function /api -> HTTPS Caddy on AKS
        -> backend ClusterIP Service -> backend Pod -> Neon Postgres/Object Storage
```

| ส่วน | หน้าที่จริง | แก้ที่ไหน |
| --- | --- | --- |
| Azure AKS control plane | จัดตาราง Pod, รักษาจำนวน replica, จัดการ Kubernetes API | Azure Portal → `aks-tcg-store-demo` |
| Node pool | กำหนดจำนวน/ขนาด VM ที่รัน Pods; ตอนตั้งต้นมี `Standard_D4s_v4` 1 node | AKS → Node pools; **ไม่มีไฟล์ใน repo ที่สร้าง/ปรับ node pool** |
| Deployment `tcg-store-backend` | กำหนด image, env, probes, resource requests/limits และ replica | `infra/kubernetes/base/deployment.yaml` + `overlays/aks-demo/patches/demo-capacity.yaml` |
| HPA | ปรับจำนวน **Pod** ตาม CPU/memory; AKS demo ล็อก 1..1 จึงไม่ขยาย | `base/horizontalpodautoscaler.yaml` + demo capacity patch |
| Service `tcg-store-backend` | ชื่อและพอร์ตภายในคลัสเตอร์ (`ClusterIP:80` → Pod:8080) | `base/service.yaml` |
| Migration Job | รัน Flyway ก่อน rollout API ทุก commit ที่ deploy | `base/migration/job.yaml`, `overlays/aks-demo/migration/` |
| API gateway Caddy | รับ HTTPS `/api/*`, proxy ไป backend Service; path อื่นตอบ 404 | `overlays/aks-demo/api-gateway/` |
| Azure Load Balancer / public IP | เปิด gateway สู่ภายนอกด้วย DNS label `tcgstore-demo-2026-11.malaysiawest.cloudapp.azure.com` | `api-gateway/service.yaml` (`type: LoadBalancer`) |
| PVC `managed-csi` | เก็บใบรับรอง TLS ของ Caddy ข้ามการเปลี่ยน Pod | `api-gateway/caddy-data-pvc.yaml` |
| ACR Basic | เก็บ backend image ที่ AKS ดึงด้วย managed identity `AcrPull` | `tcgstorecimah2026.azurecr.io` |
| Neon | PostgreSQL demo branch และ Object Storage; อยู่ **นอก AKS** | Neon dashboard; connection secret ใน Kubernetes |
| Cloudflare Pages | build และให้บริการ React/Vite frontend จาก frontend repo `main` | Cloudflare → Workers & Pages → `tcg-store-frontend` |

ชื่อ Kubernetes Deployment/Service ของ gateway ยังเป็น `tcg-store-frontend` เพราะคง DNS label, Load Balancer และ TLS storage จากตอนย้าย frontend; container ปัจจุบันคือ Caddy API gateway ไม่ได้เสิร์ฟหน้าเว็บ.

## Repo, config และ secret ที่ต้องรู้

| ไฟล์/ตำแหน่ง | หน้าที่/ค่าที่ตั้งไว้ |
| --- | --- |
| `Dockerfile` | สร้าง Spring Boot Java 21 image สำหรับ `linux/amd64`; runtime รัน non-root |
| `.github/workflows/docker-build.yml` | push/merge เข้า `main` → Maven verify, scan, build/publish GHCR; เป็น workflow ที่ AKS deploy รอผลสำเร็จ |
| `.github/workflows/deploy-aks-demo.yml` | `workflow_run` หลัง build สำเร็จบน `main`; checkout SHA เดียวกัน, Azure login ด้วย OIDC, Docker Buildx push ACR, pin digest ทั้ง app/migration, server dry-run, migration Job, rollout Deployment |
| `infra/kubernetes/base/` | Deployment, ClusterIP Service, HPA, PDB, service account, migration Job ที่ใช้ร่วมกัน |
| `infra/kubernetes/overlays/aks-demo/kustomization.yaml` | รวม base และตั้ง ACR image digest สำหรับ API; digest ใน Git เป็นค่าเริ่มต้น ส่วน workflow แทนค่าด้วย digest ที่ build ใน run ก่อน apply |
| `infra/kubernetes/overlays/aks-demo/patches/demo-capacity.yaml` | API 1 replica; HPA 1..1; PDB minAvailable 0. มี 1 node จึงไม่มี high availability |
| `infra/kubernetes/overlays/aks-demo/configmap.yaml` | `SPRING_PROFILES_ACTIVE=neon`, port 8080, Hibernate validate, API `FLYWAY_ENABLED=false`, CORS origins, object storage endpoint/bucket/public URL, log level |
| `infra/kubernetes/overlays/aks-demo/migration/` | profile `neon,migration`, `FLYWAY_ENABLED=true`, ใช้ migration credentials จาก Secret; image digest เดียวกับ API |
| `infra/kubernetes/overlays/aks-demo/api-gateway/caddy-configmap.yaml` | Caddy HTTPS, path `/api` → `tcg-store-backend...svc.cluster.local:80`, อื่น ๆ 404 |
| Frontend `functions/api/[[path]].ts` | Pages Function proxy `/api` ไป `API_ORIGIN`; รองรับ WebSocket, ตอบ API แบบ no-store |
| Cloudflare Pages settings | Production branch `main`; build `npm ci --legacy-peer-deps && npm run build`, output `dist`; build vars `VITE_API_BASE_URL=/api`, `VITE_ENABLE_MOCK_API=false`; runtime var `API_ORIGIN=https://tcgstore-demo-2026-11.malaysiawest.cloudapp.azure.com` |

Kubernetes Secret `tcg-store-backend-secrets` อยู่ใน namespace demo และ **ไม่อยู่ใน Git**. Deployment ใช้ `neon-jdbc-url`, `neon-db-username`, `neon-db-password`, `app-jwt-secret`, `object-storage-access-key`, `object-storage-secret-key`; migration ใช้ `neon-migration-jdbc-url`, `neon-migration-db-username`, `neon-migration-db-password`. ห้ามเปิดค่า Secret ในภาพหน้าจอหรือ log. Neon demo branch `aks-demo-2026-11-11` ตั้งให้ลบอัตโนมัติ 18 พ.ย. 2026; หลังวันนั้น API จะต่อฐานข้อมูลไม่ได้จนกว่าจะเปลี่ยน branch/credentials.

GitHub Actions repository variables สำหรับ Azure login คือ `AZURE_CLIENT_ID`, `AZURE_TENANT_ID`, `AZURE_SUBSCRIPTION_ID`. Identity `id-tcg-github-aks-demo` ใช้ OIDC จึงไม่มี Azure client secret ใน GitHub; role demo ให้ ACR push และเข้าถึง AKS admin credentials. AKS managed identity ใช้ `AcrPull` เพื่อดึง image. แยกสิทธิ์สองฝั่งนี้ให้ชัด: GitHub **push/deploy**, AKS **pull/run**.

`.github/workflows/deploy-staging.yml` และ `deploy-production.yml` เป็น pipeline เดิมที่ใช้ self-hosted runner และ **ไม่ใช่** pipeline ของ AKS demo. Frontend Docker image/workflow เดิมก็ไม่ใช่ทาง deploy ปัจจุบัน; Cloudflare Pages deploy จาก frontend `main` ผ่าน Git integration.

## เมื่อ merge PR แล้วเกิดอะไรขึ้น

1. Backend `main` trigger `docker-build.yml`; หาก verify/build ไม่สำเร็จ AKS deployment ไม่เริ่ม.
2. `deploy-aks-demo.yml` รับ SHA ของ run ที่สำเร็จ, login Azure ผ่าน OIDC, build SHA นั้นเข้า ACR tag `sha-<commit>` และใช้ **digest** ของ image จริง.
3. Workflow แทน digest ใน Kustomize app และ migration, ทำ server dry-run, รัน Flyway Job ชื่อ `tcg-store-backend-migration-<sha7>` และรอ `Complete`.
4. จากนั้น apply app overlay, รอ `deployment/tcg-store-backend` rollout ผ่าน. Gateway Caddy ไม่ถูก deploy โดย workflow นี้; เปลี่ยน gateway ต้อง apply overlay gateway แยก.
5. Frontend merge `main` → Cloudflare Pages build/publish ของตัวเอง; Pages Function `API_ORIGIN` ส่ง request ไป AKS gateway.

ตรวจผล: GitHub backend → Actions → `Build, Verify and Publish Container Image` แล้ว `Deploy backend to AKS demo`; ดู SHA ให้ตรงกัน, migration step และ rollout step เป็นสีเขียว. ถ้า deploy ล้มเหลว ให้อ่าน step ที่ล้มก่อน rerun; migration ล้มให้ดู `Show migration logs on failure`. อย่าแก้ Pod ด้วยมือเพื่อแก้ถาวร เพราะ Deployment จะสร้างกลับตาม manifest.

## ใช้ Azure Portal ครั้งแรก

1. เข้า [Azure Portal](https://portal.azure.com/) → เลือก subscription **Azure for Students** → Resource groups → `rg-tcg-store-aks-demo` เพื่อเห็น AKS, ACR, public IP, disk และ resource ที่มีค่าใช้จ่าย.
2. เปิด `aks-tcg-store-demo` → **Overview** ดู Provisioning state, Kubernetes version, location. `Running/Ready` ของ node และ Pod ต้องตรวจต่อในหน้า Kubernetes resources.
3. เมนู **Node pools** ดูจำนวน node, VM size, autoscaling. การเพิ่ม node เพิ่มค่า VM/disk; HPA 1..1 ใน manifest ไม่ได้เพิ่ม node. Node pool autoscaler เป็นคนละอย่างกับ HPA.
4. เมนู **Kubernetes resources → Workloads** เลือก namespace `tcg-store-aks-demo`: ดู `tcg-store-backend` และ `tcg-store-frontend` (gateway), Ready/Desired, Pods, restarts, image และ Events. Migration อยู่ใน Jobs; `Complete` หมายถึง schema update ผ่าน.
5. **Services and ingresses**: `tcg-store-backend` ต้องเป็น ClusterIP; `tcg-store-frontend` คือ LoadBalancer ของ gateway ที่มี public endpoint. **Storage** ดู PVC `tcg-store-frontend-caddy-data` ต้อง Bound เพื่อเก็บ TLS.
6. **Monitoring → Metrics** ดู CPU/memory ของ cluster/node. **Monitoring → Insights** มีรายละเอียด node/container และ log เฉพาะเมื่อเปิด Container insights แล้ว; ถ้าขึ้น Configure monitoring แปลว่ายังไม่เปิด. การเปิดอาจสร้าง Log Analytics workspace/ค่า ingest เพิ่ม ให้ดู estimate ก่อน. **Activity log** ดูเหตุการณ์ Azure resource เช่น scale/update ไม่ใช่ application log.
7. **Cost Management → Budgets** ดู `tcg-aks-demo-20usd`: alert 25/50/75% ของ USD 20 ต่อเดือนเป็นแค่การแจ้งเตือน. ดู **Cost analysis** แยก compute, ACR, disk, public IP/network และ Log Analytics (ถ้าเปิด).

Azure Portal ต้องมีสิทธิ์ Kubernetes cluster/user role ที่เหมาะสมจึงจะเห็น Workloads. ถ้าหน้านั้นว่าง/เข้าไม่ได้ ให้ใช้ `az aks get-credentials` และ `kubectl` ด้านล่าง; อย่าตีความว่า app หายทันที.

## ตรวจด้วย PowerShell และอ่านอาการ

```powershell
az account show --query '{name:name,state:state}'
az aks get-credentials -g rg-tcg-store-aks-demo -n aks-tcg-store-demo
kubectl get nodes -o wide
kubectl get deploy,pods,svc,hpa,jobs,pvc -n tcg-store-aks-demo
kubectl rollout status deployment/tcg-store-backend -n tcg-store-aks-demo
kubectl logs deployment/tcg-store-backend -n tcg-store-aks-demo --tail=100
kubectl logs deployment/tcg-store-frontend -n tcg-store-aks-demo --tail=100
kubectl get events -n tcg-store-aks-demo --sort-by=.lastTimestamp
Invoke-RestMethod https://tcg-store-frontend.pages.dev/api/health
Invoke-RestMethod https://tcgstore-demo-2026-11.malaysiawest.cloudapp.azure.com/api/health
```

| อาการ | ดูก่อน | มักเกี่ยวกับ |
| --- | --- | --- |
| Node `NotReady` | AKS → Node pools, `kubectl describe node` | VM/cluster/network; Pod อาจถูก schedule ไม่ได้ |
| Pod `Pending` | Pod Events, node allocatable/requests, PVC | node เต็ม, volume ไม่ Bound, image pull |
| `ImagePullBackOff` | Pod Events, ACR, AKS AcrPull | digest/registry/managed identity |
| `CrashLoopBackOff` | `kubectl logs ... --previous`, Pod Events | app startup, Secret/ConfigMap, Neon |
| Deployment Ready `0/1` | rollout status, readiness probe, logs | app ยังไม่พร้อม; ดู `/actuator/health/readiness` ภายใน |
| Migration Job failed | GitHub deploy step + Job logs | schema/Neon connection; rollout ต้องหยุด |
| Pages หน้าเว็บขึ้นแต่ `/api/health` 502/503 | Pages runtime `API_ORIGIN`, Caddy pod/logs, backend readiness | proxy/origin/API ไม่พร้อม |
| `/api/health` ขึ้นแต่ข้อมูลหาย | Neon branch, DB health/seed | branch ผิดหรือ demo branch หมดอายุ |
| `www` หรือ apex เปิดไม่ได้ | Cloudflare DNS zone/Pages Custom domains/TLS | nameserver ยังไม่ active หรือ domain ยังไม่ผูก Pages |

การ monitor ขั้นต่ำทุกวัน: GitHub Actions ล่าสุดสีเขียว, node Ready, backend/gateway Ready `1/1`, restart ไม่เพิ่มต่อเนื่อง, migration สำเร็จ, `/api/health` ตอบ UP, Neon branch/storage ใช้ได้ และ Azure Cost analysis ไม่เกินงบ. `kubectl top nodes,pods -n tcg-store-aks-demo` ใช้ได้เมื่อ metrics-server พร้อม; หากคำสั่งไม่รองรับ ไม่ได้แปลว่าแอปล่ม.

## เปลี่ยนค่าและกู้คืน

- เปลี่ยนค่าไม่ลับใน demo ConfigMap แล้ว merge PR: workflow apply overlay; ถ้าแก้เฉพาะ ConfigMap โดย image/Pod template ไม่เปลี่ยน Pod เดิมอาจยังถือ env เก่า ต้อง `kubectl rollout restart deployment/tcg-store-backend -n tcg-store-aks-demo` หลังตรวจ config. การแก้ live ด้วย `kubectl edit` จะเกิด config drift.
- เปลี่ยน secret ต้องแก้ Kubernetes Secret ผ่านช่องทางที่ปลอดภัย แล้ว restart backend; ห้าม commit credential. Neon branch ใหม่ต้องแก้ runtime และ migration credentials ให้สอดคล้อง.
- Gateway เปลี่ยนแยก: `kubectl apply -k infra/kubernetes/overlays/aks-demo/api-gateway` แล้ว `kubectl rollout status deployment/tcg-store-frontend -n tcg-store-aks-demo`. ตรวจ HTTPS และ `/api/health` หลังเปลี่ยน.
- App rollback: ดู `kubectl rollout history deployment/tcg-store-backend -n tcg-store-aks-demo` แล้วใช้ `kubectl rollout undo` เมื่อจำเป็น; **DB migration ไม่ rollback ตาม image**. อ่าน `docs/runbooks/kubernetes-release-recovery.md` ก่อนย้อน release ที่เปลี่ยน schema.
- หยุดค่าใช้จ่าย: scale Pod ลงไม่หยุดค่า node/disk/LB. เมื่อไม่ต้องใช้ demo แล้ว ตรวจ resource group และลบตามแผนหลังส่งงาน; Neon branch ตั้ง auto-delete แยกจาก Azure.

## สิ่งที่ยังไม่ได้ทำ

โดเมน `tcgmythix.store` และ sender domain สำหรับ Brevo ใช้งานแล้ว. AKS ตั้งค่า Brevo SMTP และ Kubernetes Secret สำหรับบัญชี SMTP แล้ว; ระบบส่งอีเมลยังปิดอยู่จนกว่าจะปล่อยโค้ดและ migration ใหม่ ตรวจคิวอีเมลที่รอส่ง แล้วจึงเปิดใช้งาน. ระบบ inbox ในแอปยังใช้งานตามปกติ.

## เอกสารอ้างอิง

- [AKS Kubernetes resources ใน Azure Portal](https://learn.microsoft.com/azure/aks/kubernetes-portal)
- [Monitor AKS](https://learn.microsoft.com/azure/aks/monitor-aks)
- [Enable Container insights](https://learn.microsoft.com/azure/azure-monitor/insights/container-insights-enable-existing-clusters)
- [Scale node pools](https://learn.microsoft.com/azure/aks/scale-node-pools)
- [AKS pricing tiers](https://learn.microsoft.com/azure/aks/free-standard-pricing-tiers)
