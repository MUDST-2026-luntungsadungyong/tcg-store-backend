# แผนทำ Backend ให้รองรับ Kubernetes และการกระจายโหลด

สถานะเอกสาร: Step 1–2 และ A/B/C implementation พร้อม; B/C local verification ผ่าน; D planned; ยังรอ B registry publish/attestation, real platform inputs และ A/C/D live-cluster acceptance
ขอบเขต: `tcg-store-backend`, Docker, Kubernetes, Neon PostgreSQL และ object storage
เป้าหมาย: ทำให้ Spring Boot backend รันหลาย replicas ได้อย่างปลอดภัย และมีเส้นทาง deploy/rollback ที่ทำซ้ำได้

## 1. Executive summary

Backend ปัจจุบันเป็น modular monolith ที่ใช้ JWT แบบ stateless และเก็บ business state ใน PostgreSQL จึงสามารถ scale horizontally ด้วยหลาย Kubernetes Pods ได้โดยยังไม่ต้องแยก microservices

สิ่งที่พร้อมแล้ว:

- Multi-stage Docker image และ non-root runtime
- Spring Boot Actuator พร้อม startup/liveness/readiness endpoints
- Kubernetes Deployment, Service, ServiceAccount และ Kustomize overlay สำหรับ Neon
- Resource requests/limits และ container security context
- GitHub Actions สำหรับ test, Docker build และ push ไป GHCR

สิ่งที่ยังขวางการกระจายหลาย Pods:

1. Readiness, graceful shutdown และ rollout behavior ยังไม่ได้ทดสอบกับ dependency failure จริง
2. Staging/production overlays และ Ingress/TLS มี implementation แล้ว แต่ยังต้องทดสอบกับ DNS/TLS/CNI จริง
3. Image hardening และ multi-architecture runtime ผ่านในเครื่อง แต่ยังรอ published digest/signature/attestation evidence จาก main pipeline
4. ต้องทำ Component D instrumentation/observability, trusted release/promotion และ recovery พร้อม live acceptance ของ reliability controls

แผนละเอียดของงานแรกอยู่ที่ [Step 1 — Object storage](./kubernetes-step-1-object-storage-plan.md)

## 2. Target architecture

```text
User browser
    |
    | HTTPS
    v
Frontend static hosting / CDN
    |
    | HTTPS API
    v
Ingress / Cloud load balancer
    |
    v
Kubernetes Service
    |--------------------|--------------------|
    v                    v                    v
Backend Pod 1        Backend Pod 2        Backend Pod N
    |                    |                    |
    |--------------------|--------------------|
                |                    |
                v                    v
        Neon PostgreSQL       S3-compatible object storage
```

Frontend เป็น static Vite application จึงควร deploy บน CDN/static hosting โดยไม่จำเป็นต้องอยู่ใน Kubernetes ส่วน backend ใช้ image เดียวกันทุก replica และไม่มี sticky session

## 3. หลักการตัดสินใจ

- Docker image ต้องเหมือนกันทุก environment; config และ secret ถูก inject ตอน runtime
- Docker Compose ใช้สำหรับ local development และ integration testing เท่านั้น
- Production image ใช้ immutable Git SHA tag หรือ image digest ห้ามพึ่ง `latest`
- Database อยู่นอก cluster และเชื่อมต่อผ่าน TLS
- ไฟล์ผู้ใช้ต้องอยู่ใน shared object storage ไม่อยู่ใน Pod
- Schema migration ต้องจบสำเร็จก่อน rollout application
- Liveness ห้ามล้มเพราะ database outage; readiness ต้องหยุดรับ traffic เมื่อ dependency สำคัญล้ม
- Secret จริงห้าม commit ลง Git
- Single-node Kubernetes ใช้เป็น lab/staging ไม่ถือเป็น high availability

## 4. แผนงานตามลำดับ

### Step 1 — ย้ายรูปสินค้าไป S3-compatible object storage

สถานะ: S3-only runtime และ data migration implemented; multi-replica Kubernetes acceptance รวมอยู่ใน Component A

เป้าหมาย: ทำให้ทุก Pod อ่าน เขียน และลบรูปชุดเดียวกัน และ Pod restart แล้วรูปไม่หาย

งานหลัก:

- ใช้ S3-compatible implementation เป็น runtime เดียวและไม่มี filesystem fallback
- ใช้ Adobe S3Mock สำหรับ local/integration environment
- ย้าย credentials ไป Secret และ config ที่ไม่ลับไป ConfigMap
- ย้ายรูปเดิมพร้อมปรับ URL ในฐานข้อมูล
- ถอด `/app/uploads` volume ออกจาก production Deployment

Exit criteria:

- รัน backend อย่างน้อย 2 instances แล้ว upload ผ่าน instance หนึ่งและอ่านได้จากอีก instance
- Pod restart/rollout แล้วรูปยังอยู่
- rollback transaction แล้ว object ที่เพิ่งสร้างถูกลบ
- ลบ listing แล้วไม่ลบ object ที่ listing อื่นยังอ้างอยู่
- ไม่มี access key/secret key ใน Git หรือ image layers

รายละเอียด: [kubernetes-step-1-object-storage-plan.md](./kubernetes-step-1-object-storage-plan.md)

### Step 2 — ทำ versioned database migration

สถานะ: Implemented

เป้าหมาย: schema เปลี่ยนแบบตรวจสอบย้อนหลังและ deploy ซ้ำได้

งานหลัก:

- เพิ่ม Flyway และกำหนด baseline
- ย้าย SQL เป็น `V1__...`, `V2__...` ตามลำดับ
- แยก production migration ออกจาก development/test seed
- ทดสอบทั้ง fresh database และ upgrade จาก schema ก่อนหน้า
- เพิ่ม Kubernetes migration Job หรือ release migration gate
- ใช้ image version เดียวกันสำหรับ migration และ application

Exit criteria:

- Migration ซ้ำไม่ทำลายข้อมูล
- Application ใช้ `ddl-auto=validate`
- Pipeline หยุดก่อน rollout เมื่อ migration ล้มเหลว
- Backup/restore rehearsal ผ่านก่อน production migration

รายละเอียด: [kubernetes-step-2-versioned-database-migration.md](./kubernetes-step-2-versioned-database-migration.md)

### Step 3 — ทำ health และ shutdown behavior ให้พร้อม production

สถานะ: Implemented ใน application และ manifest; รอ live-cluster acceptance

เป้าหมาย: Kubernetes ตัดสินใจ restart/route traffic ได้ถูกต้อง

งานหลัก:

- ให้ liveness ตรวจเฉพาะ process/JVM
- ให้ readiness รวม `readinessState` และ database health
- เปิด Spring graceful shutdown
- กำหนด lifecycle shutdown timeout
- ตรวจ SIGTERM, in-flight request และ database outage behavior
- เพิ่ม smoke tests สำหรับ probes

Exit criteria:

- ปิด database แล้ว readiness เป็น DOWN แต่ liveness ยังเป็น UP
- เปิด database กลับแล้ว readiness ฟื้นโดยไม่ restart Pod
- Rolling update ไม่มี request สำคัญถูกตัดกลางทาง

รายละเอียด: [Component A — Runtime reliability and horizontal scaling](./kubernetes-component-a-runtime-reliability.md)

### Step 4 — Harden Docker image และ local Compose

สถานะ: Implementation, Local/Cloud และ amd64/arm64 runtime acceptance ผ่าน; release reproducibility ผ่าน Buildx `rewrite-timestamp=true`; รอ publish/sign/attest บน `main`

เป้าหมาย: ได้ image ที่ reproducible, เล็ก, ปลอดภัย และรันได้ทั้ง amd64/arm64

Dockerfile:

- Pin base image ด้วย patch version หรือ digest
- กำหนด UID/GID ของ runtime user ให้ชัดเจน
- กำหนด JVM memory policy เช่น `MaxRAMPercentage`
- เพิ่ม OCI image labels: source, revision และ version
- รองรับ read-only filesystem และ writable `/tmp`
- เพิ่ม `uploads` และ local artifacts ใน `.dockerignore`

Docker Compose:

- ใช้ profile แยก Local กับ Cloud โดย Local เปิด S3Mock อัตโนมัติ
- รักษา Flyway migration gate ที่ทำใน Step 2 และตรวจว่า API image กับ migration image เป็น digest เดียวกัน
- เพิ่ม backend healthcheck
- ให้ dependent service รอ backend healthy
- Bind database port กับ loopback เมื่อจำเป็นต้องเปิดให้ host
- หลีกเลี่ยง `container_name` ใน flow ที่ใช้ทดสอบหลาย replicas

Exit criteria:

- Dockerfile check และ image scan ผ่าน
- Compose config validate ผ่านทุก profile
- Image ทำงานบน `linux/amd64` และ `linux/arm64`

### Step 5 — แยก Kubernetes environments

สถานะ: Implemented — local/staging/production overlays และ release bundle validation ผ่านในเครื่อง; รอ published digest, environment inputs และ live-cluster acceptance

เป้าหมาย: base เดียว deploy ได้หลาย environment โดย config ไม่ปะปนกัน

โครงสร้างเป้าหมาย:

```text
infra/kubernetes/
├── base/
│   ├── deployment.yaml
│   ├── service.yaml
│   ├── serviceaccount.yaml
│   └── kustomization.yaml
└── overlays/
    ├── local/
    ├── staging/
    └── production/
```

แต่ละ overlay ต้องกำหนด:

- Namespace
- Image SHA/digest
- CORS และ public base URLs
- Replica count และ resource sizing
- Secret provider/reference
- Ingress hostname/TLS
- Environment labels

Exit criteria:

- `kubectl kustomize` และ schema validation ผ่านทุก overlay
- ไม่มี placeholder เหลือใน staging/production render
- Secret value ไม่ปรากฏใน rendered artifact ที่เก็บใน CI logs

### Step 6 — เพิ่ม Ingress, TLS และ network controls

สถานะ: Implemented ใน manifests — Traefik HTTPS/API-auth routes และ NetworkPolicy ผ่าน schema/policy checks; รอ DNS/TLS/CNI acceptance บน cluster จริง

เป้าหมาย: เปิด backend สู่ internet ผ่าน HTTPS อย่างจำกัด

งานหลัก:

- เพิ่ม standard Kubernetes Ingress
- ผูก DNS และ TLS certificate
- กำหนด request body limit ให้รองรับ image upload
- กำหนด timeout และ rate limiting สำหรับ public/auth endpoints
- เพิ่ม NetworkPolicy ตาม CNI ที่ใช้
- เปิดภายนอกเฉพาะ 80/443 และจำกัด Kubernetes API

Exit criteria:

- API ใช้งานผ่าน HTTPS เท่านั้น
- Certificate renewal ผ่าน
- Database และ Kubernetes API ไม่เปิดสาธารณะ

### Step 7 — เปิดหลาย replicas และ reliability controls

สถานะ: Implemented ค่าเริ่มต้นสำหรับ production; รอ metrics/load baseline และ live-cluster acceptance

เป้าหมาย: กระจาย request และรองรับ Pod/node disruption ตามระดับ cluster

สำหรับ multi-node production:

- Backend replicas อย่างน้อย 2
- Topology spread constraints หรือ pod anti-affinity
- PodDisruptionBudget
- HPA หลังติดตั้ง metrics-server และวัด baseline แล้ว
- คำนวณ database connection budget จาก `replicas × pool size`
- Load test และปรับ CPU/memory requests/limits

สำหรับ single-node lab:

- ใช้ replicas 1 เป็นค่าเริ่มต้น
- ไม่ถือว่า HPA/PDB ทำให้เกิด node-level HA

Exit criteria:

- Scale up/down แล้วไม่มี auth/session issue
- Rollout และ rollback ผ่าน
- ไม่มี OOMKilled หรือ database connection exhaustion ระหว่าง load test

### Step 8 — CI/CD, security และ observability

Release pipeline เป้าหมาย:

1. Unit/integration tests
2. Database migration tests
3. Docker multi-architecture build
4. Generate SBOM
5. Dependency/image/secret scan
6. Push immutable image
7. Validate rendered manifests
8. Run migration gate
9. Deploy staging
10. Wait for rollout และ run smoke tests
11. Promote production ด้วย approval
12. Smoke test และ rollback เมื่อไม่ผ่าน

Observability:

- Structured JSON logs
- Correlation/request ID
- Metrics สำหรับ latency, error rate, JVM, pool และ Pod resources
- Alert สำหรับ availability, 5xx, restart, OOMKilled และ database quota
- ห้าม log password, JWT, Authorization header หรือ PII ที่ไม่จำเป็น

### Step 9 — Cloud rollout และ full-stack verification

ลำดับ rollout:

1. Provision database และ object storage
2. Deploy backend staging
3. Run migration และ smoke tests
4. Deploy frontend staging พร้อม API URL จริง
5. ทดสอบ login, catalog, upload, cart, checkout และ authorization
6. Deploy backend production
7. Deploy frontend production และตั้ง CORS แบบเจาะจง
8. ทดสอบ rollback, node reboot และ restore procedure

## 5. Config และ secret ownership

| ค่า | ประเภท | ตำแหน่ง |
|---|---|---|
| Spring profile, port, log level | Config | ConfigMap |
| CORS origin, public base URL | Config | ConfigMap |
| Object storage endpoint/region/bucket | Config | ConfigMap |
| Database URL/username/password | Secret | Secret manager/Kubernetes Secret |
| JWT secret | Secret | Secret manager/Kubernetes Secret |
| Object storage access/secret key | Secret | Secret manager/Kubernetes Secret |
| Image SHA/digest | Release metadata | Kustomize overlay/deploy pipeline |

Production ควรพัฒนาไปสู่ External Secrets Operator หรือ SOPS/Sealed Secrets แทน raw Secret YAML

## 6. Definition of Done

ถือว่า backend กระจายได้และ Kubernetes-ready เมื่อ:

1. มีอย่างน้อย 2 Pods ที่รับ request ชุดเดียวกันโดยไม่ต้อง sticky session
2. รูปสินค้าอยู่ใน object storage และคงอยู่หลัง Pod replacement
3. Schema migration เป็น versioned และ release หยุดเมื่อ migration fail
4. Startup/liveness/readiness และ graceful shutdown ผ่าน fault tests
5. Image เป็น immutable, multi-architecture, non-root และผ่าน security scan
6. Staging/production manifests render และ deploy ซ้ำได้
7. Ingress/TLS, secret management และ CORS production ถูกต้อง
8. Rolling update, rollback และ smoke tests ทำงานอัตโนมัติ
9. Database connection budget ผ่าน load test
10. Monitoring, alerts, backup และ restore runbook พร้อมใช้งาน

## 7. ลำดับ commit ที่แนะนำใน branch `feat/kubernetes`

1. `refactor(storage): introduce product image storage contract`
2. `feat(storage): add S3-compatible product image storage`
3. `chore(compose): make S3Mock mandatory for local development`
4. `feat(db): add versioned Flyway migrations`
5. `feat(runtime): configure probes and graceful shutdown`
6. `chore(docker): harden and build multi-arch image`
7. `feat(k8s): add local staging and production overlays`
8. `feat(k8s): add ingress TLS and network policies`
9. `feat(k8s): add PDB HPA and topology spread`
10. `ci(deploy): add migration rollout smoke test and rollback`

## 8. Component work packages สำหรับ Step 3–9

งานหลัง Step 2 จัดใหม่เป็น component เพื่อให้หลาย agent ทำพร้อมกันโดยลดการแก้ไฟล์ชนกัน แต่ละ component ต้องส่งมอบทั้ง code, tests, manifests และเอกสารในขอบเขตของตัวเอง

### Component A — Runtime reliability and horizontal scaling

รวม Step 3 และ Step 7

สถานะ: In progress — implementation และ automated application tests พร้อมแล้ว เหลือทดสอบบน cluster จริง

ขอบเขต:

- แยก startup/liveness/readiness semantics และเพิ่ม database readiness
- เปิด graceful shutdown, กำหนด shutdown timeout และทดสอบ SIGTERM/in-flight request
- เพิ่ม topology spread, PodDisruptionBudget และ HPA
- คำนวณ Neon connection budget จาก replica และ pool size
- ทำ two-replica, rollout, rollback และ dependency-failure tests

ไฟล์หลัก: `src/main`, `src/test`, `infra/kubernetes/base` เฉพาะ probe/reliability resources

Acceptance:

- database outage ทำให้ readiness DOWN แต่ liveness UP
- readiness ฟื้นได้โดยไม่ restart Pod
- สอง replicas รับ request และแชร์ PostgreSQL/S3 state ได้
- ไม่มี request สำคัญถูกตัดระหว่าง graceful rollout

Dependency: เริ่มได้ทันทีหลัง Step 2 และทำขนานกับ Component B/C ได้ ส่วน HPA tuning ปิดงานหลังมี metrics baseline จาก Component D

### Component B — Container and software-supply-chain

รวม Step 4 และส่วน build/security ของ Step 8

สถานะ: Implementation และ final Local/Cloud, amd64/arm64 runtime acceptance ผ่าน; รอ GitHub Actions main-branch run เพื่อยืนยัน registry publish, SBOM, provenance, signature และ digest handoff

ผลที่ยืนยันแล้ว:

- Spring Boot `4.0.8`, Tomcat `11.0.25`, Jackson BOM `2.21.7`/`3.1.7`; Maven 184 tests passed, 2 skipped; dependency scan 0 HIGH/CRITICAL
- Local image `sha256:7b594f2ff8eb018b16fd41db327ab97e6e6b82bc16095ead57340d04aa512782`: Trivy CRITICAL 0, readiness UP, migration/seed exit 0, counts 2001 global + 20 local, S3 20 objects persisted, volume 41 files / 15.1 MB และ security controls ผ่าน
- Cloud: Neon migration exit 0, readiness UP, backend/migration ใช้ image เดียวกัน; หลัง restore backend healthy ที่ `:8080`
- arm64 Java และ migration exit 0 ด้วย image `sha256:405c9f29042afae1bdf7121099456618f2860a8c2a4f01a0418c3f4cadea01e8`
- Release reproducibility ผ่าน Buildx `rewrite-timestamp=true`; OCI manifest `sha256:3fea0574201e88eb29f9c4e92223dfa45ebd7aa5f95804951156630cfb8430bd`, archive SHA-256 `1601DFD961CD66EB946E1C28C22997BCEC20209206CC0096C39903AF70627F7`. Docker Desktop Compose ไม่ใช่หลักฐาน reproducibility
- Cloud Compose บังคับ `OBJECT_STORAGE_BUCKET`, `OBJECT_STORAGE_PUBLIC_BASE_URL`, `OBJECT_STORAGE_ACCESS_KEY` และ `OBJECT_STORAGE_SECRET_KEY` แบบ fail-fast; CI ตั้งค่า dummy สำหรับ validation

ยัง pending: registry publish และ verification, SBOM/provenance/signature และส่ง digest ที่ publish จริงให้ Component C/D

ขอบเขต:

- pin base image, UID/GID, JVM memory policy และ OCI labels
- ทำ multi-architecture image สำหรับ amd64/arm64
- เพิ่ม SBOM, dependency/image/secret scan และ immutable SHA/digest publishing
- เพิ่ม Compose health dependency และตรวจ read-only filesystem
- ทำ release image reproducibility ผ่าน Buildx `rewrite-timestamp=true` และ vulnerability gates; Desktop Compose build ไม่ใช้เป็น reproducibility evidence

ไฟล์หลัก: `Dockerfile`, `.dockerignore`, `docker-compose.desktop.yml`, `pom.xml` เฉพาะ reproducible-build metadata, `.github/workflows/ci.yml` และ image-publish workflow เท่านั้น

Acceptance:

- image tag เป็น Git SHA หรือ digest ไม่ใช้ `latest` ใน release
- amd64/arm64 build ผ่าน
- critical vulnerability/secret gate หยุด release ได้
- API และ migration runner ใช้ image digest เดียวกัน

Dependency: เริ่มได้ทันทีหลัง Step 2 และทำขนานกับ Component A/C ได้

รายละเอียด: [Component B — Container and software supply-chain plan](./kubernetes-component-b-container-supply-chain-plan.md)

### Component C — Kubernetes environments and secure edge

รวม Step 5 และ Step 6

สถานะ: Implementation และ local verification ผ่าน — หลัง D integration มี 30 regression tests, strict schema ของ release bundles 48 resources และ local dependencies 7 resources รวม 55 resources ไม่มี skipped; เพิ่ม schema v2/CI-bootstrap split, monitoring ingress และ ownership/telemetry environment validation โดยยังรอ published digest จาก B, inputs/platform จริง และ live-cluster acceptance

ขอบเขต:

- แยก local/staging/production overlays พร้อม namespace, sizing และ labels
- เพิ่ม Ingress, DNS/TLS annotations, upload body limit, timeout และ rate limit
- เพิ่ม NetworkPolicy และ secret-provider reference
- ทำ render/schema validation ทุก overlay และตรวจ placeholder/secret leakage

ไฟล์หลัก: `infra/kubernetes` ยกเว้น reliability resources ที่ Component A รับผิดชอบ

Acceptance:

- ทุก overlay render ผ่านและ production ไม่มี placeholder
- เปิด public เฉพาะ HTTPS API
- database และ Kubernetes API ไม่ถูกเปิดสาธารณะ
- secret value ไม่อยู่ใน Git หรือ CI render logs

Dependency: เริ่มได้ทันทีหลัง Step 2 โดยใช้ image placeholder contract จาก Component B; ผสาน final digest หลัง Component B เสร็จ

รายละเอียด: [Component C — Kubernetes environments and secure edge](./kubernetes-component-c-platform-edge-plan.md)

### Component D — Observability, release orchestration and cloud rollout

รวมส่วน observability/deployment ของ Step 8 และ Step 9

สถานะ: Main implementation/offline verification ผ่านจาก Luna 6 จำนวน 6 agents และ main review; Python 119 tests ผ่าน, Maven 195 tests มี 2 external-DB skips, strict schemas 55 resources ผ่าน ยังไม่ได้ deploy, full browser journey ยังไม่ implement ครบ และ live acceptance ของ A/B/C/D ยังไม่ปิด ดู [implementation status](./kubernetes-component-d-implementation-status.md)

ขอบเขต:

- structured logs, correlation ID, metrics และ alerts
- staging deploy, migration gate, smoke tests, promotion approval และ automatic rollback
- full-stack verification สำหรับ login, catalog, upload, cart, checkout และ authorization
- node reboot, rollback และ restore runbook/rehearsal

ไฟล์หลัก: observability config, `.github/workflows/deploy-*.yml` (สร้างใหม่), smoke tests และ runbooks; ห้ามแก้ `ci.yml` ที่ Component B เป็นเจ้าของโดยไม่ประสาน owner

Acceptance:

- dashboard/alerts ครอบคลุม availability, 5xx, latency, restart, OOM และ DB quota
- staging ผ่านก่อน production และ production ต้องมี approval
- smoke test fail แล้วคืน prior verified bundle เฉพาะเมื่อ schema backward-compatible; migration failure/unknown schema ต้อง recovery-required และ telemetry no-data ต้อง hold
- restore rehearsal และ full-stack checklist ผ่าน

Dependency: เริ่ม instrumentation contract ขนานได้ แต่ deployment/rollout integration ต้องรอ Component A, B และ C

รายละเอียด: [Component D — Observability, release orchestration and cloud rollout](./kubernetes-component-d-observability-release-plan.md)

### Parallel execution map

```text
Step 2 complete
      |
      |-------------------|-------------------|
      v                   v                   v
Component A           Component B           Component C
Runtime               Container/CI          K8s/Edge
      |                   |                   |
      |-------------------|-------------------|
                          v
                    Component D
              Observability + Rollout
```

กติกาการแจก agent:

- แจก A, B และ C พร้อมกันได้ โดยยึด file ownership ด้านบน
- ห้ามให้สอง agent แก้ `docker-compose.desktop.yml` หรือ Kustomize resource เดียวกันพร้อมกันโดยไม่มี owner
- Component D เริ่มส่วน instrumentation contract ได้ แต่ merge release workflow หลัง A/B/C ผ่าน acceptance แล้ว
- ทุก component ต้องส่งรายงานไฟล์ที่แก้, คำสั่งทดสอบ, ผลทดสอบ และความเสี่ยงที่ยังเหลือ
