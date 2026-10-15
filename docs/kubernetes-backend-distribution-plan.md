# แผนทำ Backend ให้รองรับ Kubernetes และการกระจายโหลด

สถานะเอกสาร: Working plan  
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

1. รูปสินค้ายังเก็บใน local filesystem และ Kubernetes ใช้ `emptyDir`
2. Database migration ยังไม่มี versioned migration runner และ Kubernetes migration gate
3. Readiness, graceful shutdown และ rollout behavior ยังไม่ได้ทดสอบกับ dependency failure จริง
4. ยังไม่มี staging/production overlays, Ingress/TLS และ deployment pipeline
5. Release image ยังต้อง harden, scan และ build multi-architecture

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

เป้าหมาย: ทำให้ทุก Pod อ่าน เขียน และลบรูปชุดเดียวกัน และ Pod restart แล้วรูปไม่หาย

งานหลัก:

- แยก storage contract ออกจาก filesystem implementation
- เพิ่ม S3-compatible implementation
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

### Step 3 — ทำ health และ shutdown behavior ให้พร้อม production

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

### Step 4 — Harden Docker image และ local Compose

เป้าหมาย: ได้ image ที่ reproducible, เล็ก, ปลอดภัย และรันได้ทั้ง amd64/arm64

Dockerfile:

- Pin base image ด้วย patch version หรือ digest
- กำหนด UID/GID ของ runtime user ให้ชัดเจน
- กำหนด JVM memory policy เช่น `MaxRAMPercentage`
- เพิ่ม OCI image labels: source, revision และ version
- รองรับ read-only filesystem และ writable `/tmp`
- เพิ่ม `uploads` และ local artifacts ใน `.dockerignore`

Docker Compose:

- ใช้ profile แยก local PostgreSQL, Neon และ S3Mock
- เปลี่ยน raw SQL migration service ไปใช้ versioned migration
- เพิ่ม backend healthcheck
- ให้ dependent service รอ backend healthy
- Bind database port กับ loopback เมื่อจำเป็นต้องเปิดให้ host
- หลีกเลี่ยง `container_name` ใน flow ที่ใช้ทดสอบหลาย replicas

Exit criteria:

- Dockerfile check และ image scan ผ่าน
- Compose config validate ผ่านทุก profile
- Image ทำงานบน `linux/amd64` และ `linux/arm64`

### Step 5 — แยก Kubernetes environments

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
3. `chore(compose): add S3Mock development profile`
4. `feat(db): add versioned Flyway migrations`
5. `feat(runtime): configure probes and graceful shutdown`
6. `chore(docker): harden and build multi-arch image`
7. `feat(k8s): add local staging and production overlays`
8. `feat(k8s): add ingress TLS and network policies`
9. `feat(k8s): add PDB HPA and topology spread`
10. `ci(deploy): add migration rollout smoke test and rollback`
