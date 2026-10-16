# Component C — Kubernetes environments and secure edge

สถานะ: Implementation และ local verification ผ่าน; ยังไม่ถือว่า production-ready จนกว่าจะผ่าน live-cluster acceptance

วันที่จัดทำ: 2026-10-16

แผนหลัก: [Kubernetes backend distribution plan](./kubernetes-backend-distribution-plan.md)

## 1. เป้าหมาย

ทำให้ backend deploy ผ่าน Kubernetes ได้แบบแยก `local`, `staging` และ `production` ชัดเจน ใช้ image digest เดียวกันระหว่าง API กับ migration Job เปิดบริการสาธารณะเฉพาะ HTTPS API และไม่เก็บ Secret จริงไว้ใน Git หรือ CI artifact

Component C รวม Step 5 และ Step 6 ของแผนหลัก โดยรับผิดชอบ environment overlays, Ingress/TLS, NetworkPolicy, config/Secret reference และ manifest validation

## 2. ขอบเขต

อยู่ใน Component C:

- จัดโครง Kustomize overlays สำหรับ `local`, `staging` และ `production`
- กำหนด namespace, labels, CORS, database/object-storage references และค่าที่ต่างกันในแต่ละ environment
- รับ immutable image digest จาก Component B และผูก API กับ migration Job ให้เป็น digest เดียวกัน
- เพิ่ม Ingress, HTTPS/TLS, upload limit, timeout และ edge rate limit
- เพิ่ม NetworkPolicy สำหรับ Ingress, DNS, Neon PostgreSQL และ S3-compatible object storage
- กำหนด Secret contract และวิธีอ้างอิง Secret provider โดยไม่มีค่าจริงใน repository
- เพิ่ม deterministic validation สำหรับ render, schema, placeholder, mutable image และ secret leakage
- ส่ง interface ที่ Component D ใช้ deploy, promote และ rollback

ไม่อยู่ใน Component C:

- probes, graceful shutdown, HPA, PDB และ topology spread ซึ่ง Component A เป็น owner
- Dockerfile, build/publish/sign/SBOM/provenance ซึ่ง Component B เป็น owner
- deploy workflow, approval, smoke orchestration, automatic rollback และ observability ซึ่ง Component D เป็น owner
- การสร้าง cluster, DNS zone, Neon project/bucket, Secret manager หรือ certificate issuer จริง
- การ commit credential, API key, certificate private key หรือ Secret value

## 3. สถานะก่อนเริ่ม Component C

รายการด้านล่างเป็น baseline ก่อน implementation ดูผลล่าสุดในหัวข้อ 16 และงานที่ยังเหลือในหัวข้อ 17

สิ่งที่มีแล้ว:

- `infra/kubernetes/base` มี Deployment, ClusterIP Service, ServiceAccount, HPA และ PDB
- Deployment มี probes, non-root, read-only root filesystem, dropped capabilities, rolling update และ writable `/tmp`
- `infra/kubernetes/base/migration` มี one-shot Flyway Job
- `infra/kubernetes/overlays/neon` มี namespace, ConfigMap และ image rewrite
- API และ migration อ้าง Secret ชื่อ `tcg-store-backend-secrets`
- CI รัน `kubectl kustomize` สำหรับ Neon API และ migration overlays

ช่องว่างที่ต้องปิด:

- ยังไม่มี overlays แยก local/staging/production
- Neon overlay ยังมี `sha-REPLACE_ME` และ `replace-with-*`
- ยังไม่มี Ingress, TLS/certificate, NetworkPolicy หรือ Secret-provider resource
- CI ตรวจเพียง render ผ่าน แต่ยังไม่ตรวจ Kubernetes schema, placeholder, policy และ secret leakage
- API กับ migration มี image rewrite คนละจุด จึงยังไม่มี single digest source of truth
- Component B ยังไม่ได้ส่ง published GHCR digest และ attestation จริงจาก `main`

## 4. ข้อตกลงสถาปัตยกรรม

```text
Frontend/CDN
    |
    | HTTPS: api.<environment-domain>
    v
Ingress controller + TLS
    |
    v
ClusterIP Service :80
    |
    +--> backend Pods :8080
           |--> Neon PostgreSQL ผ่าน TLS
           +--> Neon/S3-compatible object storage ผ่าน HTTPS

release digest เดียวกัน ---> migration Job ---> API Deployment
Secret provider -----------> tcg-store-backend-secrets
```

หลักการ:

- Backend Service ต้องคงเป็น `ClusterIP`; ห้ามใช้ public `NodePort` หรือ `LoadBalancer` โดยตรง
- เปิดสาธารณะเฉพาะ API host ผ่าน HTTPS; ห้ามเปิด migration Job, PostgreSQL หรือ Kubernetes API
- `/actuator` ใช้ภายใน cluster สำหรับ probes/monitoring และต้องไม่ถูก route ออก public edge
- Staging กับ production ต้องใช้คนละ namespace, database branch/role, object-storage bucket และ Secret
- Production ใช้ immutable digest (`image@sha256:...`) ไม่ใช้ `latest`, `dev` หรือ mutable tag
- Flyway รันหนึ่งครั้งก่อน rollout; ห้ามให้ API ทุก replica ทำ migration เอง

## 5. Environment model

โครงสร้างเป้าหมาย:

```text
infra/kubernetes/
├── base/                             # workload กลาง; A เป็น owner ของ reliability resources
│   ├── deployment.yaml
│   ├── service.yaml
│   ├── serviceaccount.yaml
│   ├── horizontalpodautoscaler.yaml
│   ├── poddisruptionbudget.yaml
│   └── migration/
├── components/
│   ├── edge/                         # Ingress/TLS resources
│   └── network/                      # NetworkPolicy resources
└── overlays/
    ├── local/
    │   ├── kustomization.yaml
    │   ├── namespace.yaml
    │   ├── configmap.yaml
    │   └── migration/kustomization.yaml
    ├── staging/
    │   ├── kustomization.yaml
    │   ├── namespace.yaml
    │   ├── configmap.yaml
    │   ├── edge/
    │   ├── network/
    │   └── migration/kustomization.yaml
    └── production/
        ├── kustomization.yaml
        ├── namespace.yaml
        ├── configmap.yaml
        ├── edge/
        ├── network/
        └── migration/kustomization.yaml
```

Environment contract:

| Environment | Namespace | Public edge | Data isolation | Image policy |
|---|---|---|---|---|
| Local | `tcg-store-local` | port-forward เป็นค่าเริ่มต้น; Ingress เป็น optional | local Postgres/S3Mock เท่านั้น | local/dev image ได้ |
| Staging | `tcg-store-staging` | HTTPS staging hostname | Neon branch/role และ bucket สำหรับ staging | immutable digest เท่านั้น |
| Production | `tcg-store-production` | HTTPS production hostname | Neon production role และ production bucket | verified immutable digest เท่านั้น |

`overlays/neon` เดิมต้องถูกย้ายความหมายไปเป็น staging/production หรือประกาศเป็น compatibility overlay ชั่วคราว ห้ามใช้ชื่อ provider แทนชื่อ environment ต่อไปโดยไม่มีคำอธิบาย

## 6. Config และ Secret contract

ConfigMap เก็บเฉพาะค่าที่ไม่ลับ:

| Key | Consumer | กติกา |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | API/Job | API ใช้ environment profile; Job เพิ่ม `migration` |
| `SERVER_PORT` | API | ต้องเป็น `8080` ให้ตรงกับ probes และ Service |
| `SPRING_JPA_HIBERNATE_DDL_AUTO` | API | ใช้ `validate`; Flyway เป็นเจ้าของ schema |
| `FLYWAY_ENABLED` | API/Job | API=`false`, migration Job=`true` |
| `APP_CORS_ALLOWED_ORIGINS` | API | exact origin; production ห้าม wildcard |
| `OBJECT_STORAGE_ENDPOINT` | API | HTTPS endpoint ของ environment นั้น |
| `OBJECT_STORAGE_REGION` | API | region ของ provider |
| `OBJECT_STORAGE_BUCKET` | API | แยก bucket ต่อ environment |
| `OBJECT_STORAGE_PUBLIC_BASE_URL` | API | URL ของ bucket/environment เดียวกัน |
| `OBJECT_STORAGE_PATH_STYLE` | API | กำหนดตาม provider |
| `APP_LOG_LEVEL`, `JPA_SHOW_SQL` | API | production ใช้ระดับปลอดภัยและปิด SQL output |

Secret `tcg-store-backend-secrets` ต้องมี:

| Secret key | API | Migration Job |
|---|---:|---:|
| `neon-jdbc-url` | ใช้ | ไม่ใช้ |
| `neon-db-username` | ใช้ | ไม่ใช้ |
| `neon-db-password` | ใช้ | ไม่ใช้ |
| `neon-migration-jdbc-url` | ไม่ใช้ | ใช้ |
| `neon-migration-db-username` | ไม่ใช้ | ใช้ |
| `neon-migration-db-password` | ไม่ใช้ | ใช้ |
| `app-jwt-secret` | ใช้ | ไม่ใช้ |
| `object-storage-access-key` | ใช้ | ไม่ใช้ |
| `object-storage-secret-key` | ใช้ | ไม่ใช้ |

กติกา Secret:

- `secret.example.yaml` เป็นเอกสารตัวอย่างเท่านั้นและห้ามถูก include ใน deployable Kustomization
- Staging/production อ้าง Secret provider หรือสร้าง Secret นอก Git ผ่าน release process
- CI ตรวจเฉพาะชื่อ Secret และชื่อ key โดยไม่อ่านหรือพิมพ์ value
- ต้องเปิด encryption at rest และจำกัด RBAC สำหรับ Secret ใน cluster จริง
- Production JDBC acceptance ใช้ TLS พร้อมตรวจ certificate/hostname (`sslmode=verify-full`)
- API ใช้ least-privilege runtime role ส่วน migration ใช้ migration role และ direct endpoint แยกจาก pooled runtime endpoint; การเปลี่ยน key mapping ใน Job ต้องประสาน owner ของ `base/migration`

## 7. Image และ migration contract

- Component B ส่งค่า registry repository, manifest digest, commit SHA และ verification result
- Component C ต้องมี source of truth ของ digest เพียงจุดเดียวต่อ release
- API overlay และ migration overlay ต้อง render เป็น digest เดียวกันแบบ byte-for-byte
- Production validation fail ทันทีถ้าพบ `sha-REPLACE_ME`, `REPLACE`, `latest`, `dev` หรือ tag ที่ไม่มี digest
- Migration Job ใช้ image/entrypoint เดียวกับ artifact ที่ API ใช้
- Component D สร้าง Job identity ต่อ release, รอ `Complete` และหยุด rollout เมื่อ Job fail
- Migration Job รับเฉพาะ migration DB Secret; ไม่รับ runtime DB role, JWT หรือ object-storage credentials
- Rollback application ไม่ได้ rollback schema จึงต้องใช้ backward-compatible migration หรือ Neon branch/backup ที่ยืนยันแล้ว

## 8. Ingress และ TLS

Baseline สำหรับแผนปัจจุบัน:

- ใช้ standard Kubernetes `Ingress`
- สำหรับ k3s ใช้ Traefik component; controller-specific annotations/Middleware แยกออกจาก common overlay
- ใช้ cert-manager หรือ certificate service ของ platform; staging ใช้ staging issuer ก่อน production issuer
- DNS/certificate issuer จริงเป็น prerequisite ที่ platform owner และ Component D ต้องจัดให้

ข้อกำหนด:

- route เฉพาะ API hostname ไป `tcg-store-backend:80`
- บังคับ HTTPS และกำหนด HTTP-to-HTTPS behavior
- TLS certificate ต้องครอบคลุม hostname จริงและมี renewal evidence
- edge request body limit ต้องมากกว่า application request limit `6 MB` อย่างมีขอบเขต เช่น `7–8 MB`
- กำหนด connect/read/send timeout สำหรับ upload และ API โดยไม่เปิดแบบไม่จำกัด
- rate limit route login/upload ตามความสามารถของ controller และ trusted client-IP chain
- ห้าม route `/actuator`, migration Job หรือ internal management path ออก public
- ตั้งค่า forwarded headers เฉพาะเมื่อยืนยัน proxy topology แล้ว

หาก production platform ไม่ใช้ Traefik ให้เปลี่ยนเฉพาะ edge component โดยไม่เปลี่ยน Service หรือ application overlay contract

## 9. NetworkPolicy

Policy ขั้นต่ำ:

1. default-deny ingress และ egress ใน staging/production namespace
2. อนุญาต Ingress controller เข้า backend เฉพาะ TCP `8080`
3. อนุญาต DNS ไป cluster DNS เฉพาะ UDP/TCP `53`
4. อนุญาต API ออก PostgreSQL TCP `5432`
5. อนุญาต API ออก object storage HTTPS TCP `443`
6. อนุญาต migration Job ออก DNS และ PostgreSQL เท่านั้น
7. ไม่อนุญาต public traffic เข้า Pod โดยตรง และไม่อนุญาต Kubernetes API โดยไม่มีเหตุผล

Neon และ object storage อาจเปลี่ยน IP ได้ จึงห้ามเดา CIDR ใน manifest หาก CNI รองรับ FQDN policy หรือ egress gateway ให้ใช้ความสามารถนั้น มิฉะนั้นต้องบันทึก broader egress exception และความเสี่ยงอย่างชัดเจน

YAML render ผ่านไม่เท่ากับ policy ทำงาน ต้องทดสอบกับ CNI ตัวเดียวกับ production

## 10. งานย่อยและลำดับทำ

### C0 — ยืนยัน platform contract

- ยืนยัน Kubernetes version, CNI, Ingress class, DNS hostname, certificate issuer และ Secret provider
- ยืนยัน staging/production Neon endpoints, roles, buckets และ CORS origins โดยไม่บันทึก credentials
- ยืนยัน labels/ports กับ Component A และ digest output กับ Component B

ผ่านเมื่อมีค่า non-secret และ interface ครบพอให้ render manifests ได้

### C1 — สร้าง environment overlays

- สร้าง local/staging/production overlays และ migration targets
- แยก namespaces, labels, ConfigMaps และ data references
- จัดการสถานะ `overlays/neon` เดิมและห้ามมี config ซ้ำแบบกำกวม

ผ่านเมื่อทุก overlay render แยกกันได้และไม่มี environment ใช้ data reference ของอีก environment

### C2 — ทำ immutable release input

- สร้างกลไก inject digest เพียงครั้งเดียวให้ API และ migration
- เพิ่ม equality check และห้าม mutable production image

ผ่านเมื่อ app/job render เป็น digest เดียวกันและ placeholder/mutable tag ทำให้ validation fail

### C3 — ทำ Secret delivery contract

- เพิ่ม provider reference หรือ documented out-of-Git creation path
- ตรวจ required key names แบบไม่อ่าน values
- แยก API-only Secret ออกจาก migration Job

ผ่านเมื่อ production render ไม่มี literal Secret data และ workload รับเฉพาะ key ที่ต้องใช้

### C4 — เพิ่ม edge

- เพิ่ม Ingress, TLS reference, host/path, upload limit, timeout และ rate controls
- ปิด public actuator routes และคง Service เป็น ClusterIP

ผ่านเมื่อ HTTPS edge tests, certificate, CORS, upload limit และ unknown-host tests ผ่าน

### C5 — เพิ่ม NetworkPolicy

- เพิ่ม default deny และ allow rules ตาม dependency graph
- ทดสอบ allowed/denied paths บน CNI จริง

ผ่านเมื่อ backend/Job ติดต่อ dependency ที่จำเป็นได้ และ unauthorized Pod ติดต่อไม่ได้

### C6 — เพิ่ม manifest validation

- render ทุก API/migration overlay
- schema validate ตาม Kubernetes version ที่รองรับ
- ตรวจ placeholders, digest equality, TLS, Service type, CORS, Secret resource และ NetworkPolicy
- pin เวอร์ชันเครื่องมือ validation และไม่พิมพ์ rendered Secret

ผ่านเมื่อ local และ CI ให้ผล deterministic และ fail ตาม negative fixtures ที่กำหนด

### C7 — Staging acceptance และส่งต่อ Component D

- deploy migration ก่อน API ด้วย digest เดียวกัน
- ทดสอบ HTTPS/DNS, max upload, CORS, Secret injection และ NetworkPolicy
- ส่ง overlay paths, validation command, migration semantics และ rollback constraints ให้ D

ผ่านเมื่อ D สามารถ deploy staging และหยุด rollout เมื่อ migration/smoke fail ได้

## 11. Validation matrix

Static/CI:

- `kustomize build` ผ่านทุก API และ migration overlay
- Kubernetes schema validation ผ่านตาม cluster version
- production ไม่มี placeholder, example Secret, wildcard CORS หรือ mutable image
- backend Service เป็น ClusterIP เท่านั้น
- Ingress มี host/TLS และไม่มี public actuator path
- API และ migration ใช้ digest เดียวกัน
- NetworkPolicy selectors/ports ตรงกับ workloads
- CI log/artifact ไม่มี Secret value

Live cluster:

- HTTPS certificate/hostname ถูกต้องและ HTTP behavior เป็นไปตามแผน
- upload ขนาดสูงสุดที่รองรับผ่าน edge; request เกิน limit ถูกปฏิเสธอย่างคาดเดาได้
- exact CORS origin ผ่านและ origin อื่นถูกปฏิเสธ
- Ingress controller ติดต่อ backend ได้ แต่ unauthorized namespace ติดต่อไม่ได้
- API ติดต่อ Neon/S3 ได้ และ migration ติดต่อเฉพาะ Neon ได้
- migration fail แล้ว API รุ่นใหม่ไม่ rollout
- migration และ API รายงาน image digest เดียวกัน
- Secret value ไม่ปรากฏใน Pod events/logs หรือ release output

Component A live tests เรื่อง two replicas, DB outage/recovery, graceful rollout, rollback และ HPA ต้องรันร่วมใน staging หลัง Component C เปิด environment ได้แล้ว

## 12. File ownership และการแบ่ง agent

แบ่งทำพร้อมกันได้ 4 workstreams:

| Agent | Write scope | งาน |
|---|---|---|
| C-overlay | `infra/kubernetes/overlays/**` | local/staging/production config และ migration overlays |
| C-edge | ไฟล์ใหม่ใต้ `infra/kubernetes/components/edge/**` | Ingress/TLS/Traefik resources |
| C-network | ไฟล์ใหม่ใต้ `infra/kubernetes/components/network/**` | NetworkPolicy และ connectivity matrix |
| C-validation | validation script และ workflow ใหม่ของ C | render/schema/policy/placeholder checks |

Integration owner เพียงคนเดียวแก้ shared Kustomization และรวม digest/Secret contracts ห้ามหลาย agent แก้ไฟล์เดียวกันพร้อมกัน

ไฟล์ที่ต้องประสาน owner:

- `base/deployment.yaml`, HPA และ PDB — Component A
- `base/migration/**` และ image contract — Component B/A
- `.github/workflows/ci.yml` และ `docker-build.yml` — Component B
- `deploy-*.yml`, smoke tests และ rollout runbooks — Component D

## 13. Dependency และ blocker

| Dependency | เริ่มทำ C ได้หรือไม่ | เงื่อนไขปิดงาน |
|---|---|---|
| Component A | ได้ | ต้องใช้ stable labels/ports และรัน A live tests หลังมี staging |
| Component B | ได้โดยใช้ placeholder contract | ปิด production acceptance ไม่ได้จนกว่าจะได้ published/verified digest |
| Component D | ทำ contract ขนานได้ | D รับ overlay/validation/migration interfaces ไปทำ deployment workflow |
| Cluster/platform | ทำ manifests ได้บางส่วน | live acceptance ต้องมี cluster, CNI, Ingress, DNS, TLS issuer และ Secret provider จริง |

## 14. Definition of Done

- [x] local/staging/production API และ migration overlays render แยกกันได้
- [ ] Production ไม่มี placeholder, mutable image, literal Secret หรือ example resource
- [ ] API และ migration ใช้ verified immutable digest เดียวกัน
- [ ] Secret contract ครบและไม่มีค่าจริงอยู่ใน Git/CI output
- [ ] Backend เปิดสาธารณะผ่าน HTTPS Ingress เท่านั้น
- [ ] TLS, host routing, upload limit, timeout, CORS และ actuator exclusion ผ่าน live test
- [ ] NetworkPolicy ผ่าน allowed/denied connectivity tests บน production-equivalent CNI
- [ ] Migration fail แล้ว application rollout ไม่เริ่ม
- [x] Schema/policy validation ผ่านทุก overlay และ negative tests fail ตามที่ออกแบบ
- [ ] Component D รับ interface ไป deploy staging/production ได้
- [ ] Component A live-cluster acceptance รันต่อบน environment นี้ได้

## 15. ความเสี่ยง

| ความเสี่ยง | วิธีรับมือ |
|---|---|
| Ingress annotations ต่างกันตาม controller | แยก controller-specific edge component และยืนยัน version ก่อน implement |
| NetworkPolicy ถูก accept แต่ CNI ไม่ enforce | บังคับ live connectivity test และบันทึก CNI/version |
| Neon/S3 IP เปลี่ยน | ใช้ FQDN-aware policy/egress gateway หรือบันทึก broader exception |
| Secret/example หลุดเข้า production | แยก example ออกจาก Kustomization และเพิ่ม CI fail rules |
| API/Job ใช้คนละ image | inject digest จาก source เดียวและ assert equality |
| Migration ผ่านแต่ rollback app ไม่ได้ | ใช้ backward-compatible migration และ Neon branch/backup rehearsal |
| Default deny บล็อก DNS/DB/S3 | ทดสอบ allow rules ใน staging ก่อนเปิด production enforcement |
| Component ชนไฟล์กัน | กำหนด write scope และ integration owner คนเดียว |

## 16. Implementation report — 2026-10-16

Implementation ทำขนานโดย Luna 6 จำนวน 5 workstreams และตรวจรวมโดย main agent ตาม skill `kubernetes-patterns` ซึ่งใช้กำหนด non-root/read-only workload, Secret references, probes และการคง Service ไว้ภายใน cluster

สิ่งที่เพิ่ม:

- `infra/kubernetes/overlays/local`, `staging`, `production`: API/migration/prerequisites targets แยก namespace และ config; local ใช้ 1 replica ไม่มี HPA/PDB ส่วน cloud คง reliability defaults ของ A
- Overlay patches กำหนด UID/GID/fsGroup `10001`, seccomp `RuntimeDefault` และ Service selector เฉพาะ API โดยไม่เปลี่ยน base reliability files
- `infra/kubernetes/components/edge`: `/api` และ `/api/auth` ผ่าน Traefik HTTPS, namespace-scoped Middleware, body cap 8 MiB, API rate 100/s burst 50, auth rate 5/s burst 10 และ ServersTransport timeouts; rate limit เป็น per-controller-instance
- `infra/kubernetes/components/network`: default deny และ API/migration allow rules; public IPv4 egress exception จำกัดตาม port และ excluded ranges ตาม platform contract
- `infra/kubernetes/local-dependencies`: optional PostgreSQL/S3Mock พร้อม PVC แยกจาก Compose volumes; bucket `tcg-product-images-local`
- `scripts/kubernetes/render.py`: copy source overlays ไป temporary workspace แล้ว inject non-secret release inputs และ digest เดียวกัน โดยคง resources/patches ของจริงไว้; output แยก prerequisites/migration/application และ bundle metadata
- Validator ตรวจ resource inventory, image identity, config/Secret references, private Service, TLS/routes, middleware/transport และ approved NetworkPolicy specs; fixture bundles ไม่ใช้ deploy
- `scripts/deploy-kubernetes.ps1`: explicit context/environment/release ID, preflight bundle/Secret key names, release-specific migration Job และ migration-before-API gate; ไม่มีการลบ Job เก่า
- `.github/workflows/kubernetes-manifests.yml`: pinned/checksummed tooling, fixture rendering, policy regression tests, helper tests และ strict schemas รวม Traefik CRDs โดยไม่ ignore missing schemas
- เอกสาร platform contract, deployment acceptance และ local dependencies; อัปเดต README/Neon runbook ให้ชี้เส้นทางใหม่ โดยคง Neon overlay/helper เดิมเป็น legacy compatibility

ผลตรวจที่ main agent รันเอง:

| การตรวจ | ผล |
|---|---|
| Kustomize API/migration/prerequisites ของ local/staging/production | ผ่าน 9 targets |
| Python policy/regression tests หลัง Component D integration | ผ่าน 30 tests, ไม่มี skipped |
| Final local release bundle schema | Valid 6, Invalid 0, Errors 0, Skipped 0 |
| Final staging fixture bundle schema | Valid 21, Invalid 0, Errors 0, Skipped 0 |
| Final production template bundle schema | Valid 21, Invalid 0, Errors 0, Skipped 0 |
| Optional local dependency schema | Valid 7, Invalid 0, Errors 0, Skipped 0 |
| PowerShell deploy adapter suite | ผ่าน: fixture/mismatch preflight, explicit context, migration gate และ real local render/validate พร้อม mocked kubectl |
| Local bundle preflight และ staging fixture rejection | ผ่าน |
| GitHub workflow actionlint และ whitespace check | ผ่าน |
| GitHub Kubernetes manifests workflow | ผ่านทุก matrix: local/staging/production — [run 38924514444](https://github.com/MUDST-2026-luntungsadungyong/tcg-store-backend/actions/runs/38924514444), commit `73c7e17240984e3e10479a512c41c249769e9b05` |

Cloud schema/tests ใช้ synthetic fixtures; ไม่ใช่หลักฐานว่า published image, credentials, DNS, TLS หรือ CNI ของ production ทำงานแล้ว GitHub workflow ข้างต้นยืนยัน rendering/policy/strict schema ใน CI เท่านั้น ไม่ใช่ live-cluster acceptance

เครื่องมือที่ตรวจ: kubectl `v1.34.1` / Kustomize `v5.7.1`, Python `3.13`, PyYAML `6.0.3`, kubeconform `v0.7.0`, Traefik schema `v3.5.0`, actionlint `v1.7.12`

ไม่ได้ deploy cluster หรือเปลี่ยน Docker services/volumes, Neon schema/data หรือ cloud bucket จริงใน Component C รอบนี้ เครื่องนี้ไม่มี Kubernetes context และ Docker Desktop Kubernetes ยัง disabled; repository ยังไม่มี staging/production environments, deployment variables/secrets หรือ self-hosted runner `tcg-deploy`

## 17. งานที่ยังเหลือก่อนปิด Component C และเชื่อม A/B/D

- [ ] รับ published/verified GHCR manifest digest พร้อม supply-chain evidence จาก Component B
- [ ] เตรียม non-secret release inputs จริงสำหรับ staging/production: API/Frontend hosts, storage endpoints/buckets, issuer และ Ingress class
- [ ] เตรียม Kubernetes context, policy-enforcing CNI, Traefik+CRDs, DNS, TLS issuer และ Secret delivery จริง
- [ ] สร้าง runtime/migration DB roles และ direct migration endpoint ที่เหมาะสม; ทดสอบ TLS certificate/hostname โดยไม่เปิดเผย Secret values
- [ ] Render production inputs จริงให้ไม่มี placeholder/fixture แล้วผ่าน schema และ server-side admission dry-run
- [ ] ทดสอบ migration success/failure gate และ deploy staging บน cluster จริง
- [ ] ทดสอบ HTTPS/certificate renewal/HTTP behavior, CORS, max upload/over-limit และ API/auth rate limits
- [ ] ทดสอบ NetworkPolicy allowed/denied paths กับ CNI จริง และยืนยัน public egress exception หรือแทนด้วย FQDN/egress gateway
- [ ] รัน Component A acceptance: shared state 2 replicas, DB outage/recovery, graceful rollout, failed rollout/rollback, HPA และ Neon connection budget
- [ ] Component D ทำ deployment concurrency, promotion approval, smoke tests, observability และ rollback/restore orchestration

คำสั่งและเงื่อนไขทดสอบ: [Deployment inputs and acceptance](./kubernetes-component-c-acceptance.md)

## 18. Component D integration update

ผลล่าสุดของ C ที่ main รันซ้ำ: 30 regression tests ไม่มี skip; release bundles 48 resources และ optional local dependencies 7 รวม 55 resources ผ่าน strict kubeconform ตัวเลข final bundles ใน section 16 อัปเดตเป็นผลหลัง D integration แล้ว

Schema v2 เพิ่ม full/CI/bootstrap split, canonical bundle/config hashes, exact ownership labels และ `APP_ENVIRONMENT`; cloud เพิ่ม monitoring ingress ที่ต้อง match namespace และ Prometheus pod selectors พร้อมกัน Approved predecessor schema v1 ยัง validate เพื่อ recovery ได้ แต่ไม่ใช้ new CI deployment/automatic prune

Deploy helper แบบ CI ไม่อ่าน Secret/ไม่ apply bootstrap และตรวจ current Lease ก่อนทุก apply รวมถึงหลัง migration wait; progress มี actual UTC start/completion และ atomic publication Tests ครอบคลุม stolen/expired/lost-during-migration Lease และยังคงหยุดก่อน API เมื่อ migration fail

รายละเอียดและ acceptance ที่ยังไม่ปิด: [D implementation status](./kubernetes-component-d-implementation-status.md) และ [D release operations](./kubernetes-component-d-release-operations.md) ไม่มีการ deploy/เปลี่ยน data หรือ Docker services ในรอบ integration นี้
