# Component D — Observability, release orchestration and cloud rollout

สถานะ: Main implementation และ offline verification ผ่าน — logs/metrics, monitoring, release core/orchestration, API smoke และ recovery runbooks; ยังไม่ปิด real staging/production acceptance, full browser journey ยังไม่ implement ครบ และยังไม่ได้ deploy

วันที่: 2026-10-16

ติดตาม implementation/เจ้าของงาน/ผลทดสอบ: [Component D implementation status](./kubernetes-component-d-implementation-status.md)

Platform inputs และ release flow ที่เตรียมไว้: [Release operations](./kubernetes-component-d-release-operations.md)

แผนหลัก: [Kubernetes backend distribution](./kubernetes-backend-distribution-plan.md) · ส่งต่อจาก [Component A](./kubernetes-component-a-runtime-reliability.md), [Component B](./kubernetes-component-b-container-supply-chain-plan.md) และ [Component C](./kubernetes-component-c-platform-edge-plan.md)

## 1. เป้าหมายและขอบเขต

ทำให้ทีมทราบว่าระบบทำงานปกติหรือไม่ ปัญหาเกิดจาก API, PostgreSQL, object storage หรือ cluster และปล่อย backend release จาก artifact ที่ตรวจสอบแล้วไป staging/production พร้อมหลักฐานและเส้นทาง recovery ที่ทดสอบได้

รวม observability/deployment ของ Step 8 และ Step 9:

- Structured JSON logs และ request correlation
- Metrics, dashboards, alert rules และ signal ที่ใช้ตัดสิน release
- Trusted image handoff จาก B, staging deploy, migration gate, smoke และ production approval
- Promote digest เดิมโดยไม่ build ซ้ำ; เก็บ release record และ previous-release bundle
- Conditional automatic rollback, backup/restore rehearsal และ full-stack acceptance

ไม่รวมการเลือกหรือซื้อ cloud account, สร้าง cluster/DNS/Secret manager จริง, แยก microservices หรือเปลี่ยน business logic ของ checkout การเพิ่ม infrastructure หรือ integration ใน frontend repo ต้องมี artifact/owner/provider contract ก่อน

## 2. Baseline ที่ตรวจจาก repository

| พื้นที่ | มีแล้ว | D ต้องเติม |
|---|---|---|
| Application | Spring Boot 4.0.8, Actuator, health probes, JWT stateless | Prometheus registry, JSON logs, correlation/filter และ telemetry tests |
| Health | Liveness แยก DB; readiness รวม DB | Public smoke ผ่าน `/api/health`; metrics/S3 checks เพิ่มเติม เพราะ readiness ไม่ได้ตรวจ S3 |
| A | Graceful shutdown, replicas/HPA/PDB และ connection budget | รัน live tests และเก็บ evidence บน staging |
| B | Main/dispatch image publishing, index/child digests, signature, provenance/SBOM verification และ `image-contract.txt` | รับ artifact จาก run ที่เชื่อถือได้; actual registry acceptance ยัง pending |
| C | 3 environment overlays, renderer/validator, Ingress/TLS, NetworkPolicy และ migration-first adapter | Release identity, monitoring integration, CI identity/bootstrap split และ rollback snapshots |
| Deploy helper | Explicit context, unique migration Job, wait before API | Environment lock, smoke, promotion, phase-aware failure handling และ restore prior config |
| Monitoring | Actuator เปิดเฉพาะ health; ไม่พบ established metrics/log pipeline/dashboard | กำหนด telemetry contracts และ monitoring destination ให้ครบ |
| Frontend | เป็น Vite/static application ตามแผนหลัก; มี checkout แยก `C:/Users/Lenovo/tcg-store-frontend` | รับ frontend commit/build ID, staging URL และ API base URL; ยังไม่พบ CDN-provider deployment contract ใน backend |

งาน C ปัจจุบันยังไม่ commit ใน branch `feat/kubernetes` จึงต้องรวมเป็น source revision ที่ชัดเจนก่อนออก release ส่วน tests ที่ C ผ่านในเครื่องไม่ใช่หลักฐานว่า DNS/TLS/CNI หรือ credentials จริงทำงานแล้ว

## 3. Release และ observability architecture

```text
Protected main -> B: build/test/scan/publish/sign/attest
                        |
                        v
             trusted build run + image-contract.txt
                        |
                 D: verify exact identity
                        |
             render C staging bundle + admission
                        |
             migration -> rollout -> smoke -> observe
                        |
                staging release receipt
                        |
              protected production approval
                        |
             same digest + production config
                        |
             migration -> rollout -> read-only smoke
                        |
              accepted OR safe rollback/manual recovery

API stdout JSON -> platform log collector -> searchable log destination
API Micrometer -> private Prometheus scrape -> dashboards/alerts
Cluster metrics -> Prometheus                 -> release observation gate
```

Instrumentation เปลี่ยน application artifact จึงต้องให้ B build/publish source commit ที่มี D instrumentation ใหม่ก่อน ไม่ใช้ image เดิมที่ยังไม่มี telemetry แล้วถือว่า D deployment ผ่าน

## 4. Instrumentation contract

### D1 — Structured logs และ correlation

- ใช้ Spring Boot structured console logging ให้เป็น JSON; ส่ง stdout/stderr และไม่เขียน log file ลง Pod
- Fields ขั้นต่ำ: timestamp, level, application, environment, logger, message, requestId, route template, method, status, durationMs; pod/namespace/version/release ให้ collector enrich จาก metadata
- Filter สร้าง request ID เมื่อไม่มีค่า หรือเมื่อ header ไม่ผ่าน validation: อนุญาตเฉพาะรูปแบบที่กำหนดและความยาวสูงสุด 64 ตัวอักษร; ห้ามรับ newline/control characters
- รับ/คืน `X-Request-ID`, ใส่ MDC และล้างใน `finally`; ทดสอบทั้ง success, error, async/error dispatch และ request ถัดไปบน thread เดิม
- ปรับ CORS allow/expose headers ให้ browser ใช้ `X-Request-ID` ได้ และคง `Authorization`, `Content-Type`, `Accept`, `Idempotency-Key` เดิม
- ไม่ใส่ Authorization/JWT, password/reset body, JDBC URL, cookies, query string, presigned object URLs หรือ PII ใน access logs; route ใช้ template ไม่ใช้ raw URL ที่มี user/order ID
- ไม่ log checkout request body, upload bytes หรือ full entity; diagnostics ต้องอาศัย request ID และ sanitized error code
- Health/scrape logs ใช้ระดับ/ปริมาณที่ไม่กลบ business failures

ผ่านเมื่อ log parse เป็น JSON ได้, correlation ต่อเนื่อง, ไม่มี MDC ข้าม request และ secret-sentinel tests ไม่พบข้อมูลลับใน logs

### D2 — Metrics และ private scrape

- เพิ่ม `micrometer-registry-prometheus` โดยใช้ dependency management ของ Boot version ปัจจุบัน
- Baseline ใช้ `/actuator/prometheus` ผ่าน private Service/Pod port เดิม `8080`; คง probe paths และ health semantics ของ A
- เปิดเฉพาะ `health,prometheus`; กำหนด SecurityConfig ให้ scrape endpoint ใช้ได้ตาม internal-network contract โดยไม่เปิด actuator ทั้งหมดหรือให้ Prometheus ถือ JWT ของผู้ใช้
- Ingress ยังคง route เฉพาะ `/api` และ `/api/auth`; `/actuator/prometheus` ต้องไม่เข้าถึงผ่าน public host
- เพิ่ม NetworkPolicy อนุญาตเฉพาะ monitoring namespace และ Prometheus pod labels ที่กำหนดเข้า TCP 8080; monitoring ไม่ใช่ public ingress
- Baseline นี้พึ่ง NetworkPolicy และ trusted cluster boundary สำหรับ scrape access หาก platform ต้องการ mTLS/auth ให้เพิ่มเป็น integration ที่ทดสอบแยกก่อน production
- เก็บ HTTP count/error/duration แบบ route template, JVM/GC/heap/threads, process/container resources และ Hikari active/idle/pending/timeout
- เพิ่ม S3 operation latency/error ด้วย finite operation/outcome labels; ไม่ใช้ object key, URL, bucket credentials หรือ customer ID เป็น label
- เริ่ม histogram สำหรับ HTTP/S3 ที่มีขอบเขต buckets ชัดเจน; จับคู่ metric names กับ scrape output จริงก่อนเขียน alert
- Common labels จำกัด application/environment; pod/instance มาจาก scrape discovery และ release version มาจาก metadata/info metric ไม่ใส่ requestId/userId/email เป็น label

ต้องตรวจร่วมกับ C: validator มี exact inventory ของ resources, ConfigMap keys และ approved NetworkPolicy specs จึงต้องเพิ่ม monitoring rule/telemetry config ใน schema ที่ระบุชัดพร้อม negative tests ห้ามปิด allowlist หรือเปิด arbitrary resources เพื่อให้ D ผ่าน

### D3 — Monitoring destination, dashboard และ alerts

Baseline ใช้ Prometheus-compatible monitoring, Grafana dashboards และ Alertmanager-compatible routing หาก cluster มีอยู่แล้วให้ reuse หากยังไม่มีให้จัด deployment configuration แยกภายใต้ `infra/observability` และบันทึก storage/retention/resource budget ก่อนติดตั้งจริง

- Scrape API แต่ละ replica ผ่าน Service discovery; แยก monitoring identity ออกจาก runtime ServiceAccount
- Metrics-server ยังคงเป็น prerequisite ของ HPA; การมี Prometheus ไม่ได้ทดแทน Kubernetes Metrics API
- Pod readiness/restarts/OOM และ deployment availability ต้องรับ cluster metrics จาก kube-state-metrics/container metrics ไม่ถือว่า Micrometer มีข้อมูลเหล่านี้ครบ
- Neon project/endpoint quota ต้องมี provider signal หรือ exporter ที่ยืนยันได้; Hikari metrics บอก pool pressure แต่ไม่เท่ากับ quota ของ Neon ทั้ง project
- Structured stdout ต้องถูก collector ส่งไป searchable log destination ที่ platform จัดให้ พร้อม retention/การจำกัดสิทธิ์ หากยังไม่มี destination ให้บันทึกเป็น acceptance pending
- Dashboard/Prometheus/Grafana ไม่เปิด public โดยไม่มี access control; alert receiver credential อยู่ใน Secret manager และกำหนด owner/escalation ของแต่ละ alert
- ใช้ standard scrape config เป็น baseline; ServiceMonitor/PrometheusRule ใช้ใน optional Operator integration และต้องมี CRD/schema validation ที่ pin version โดยไม่ ignore missing schemas

ค่าเริ่มต้นสำหรับ staging calibration; ยังไม่ใช่ SLA ที่ยืนยันแล้ว:

| Signal | เงื่อนไขเริ่มต้น | ระดับ/การใช้งาน |
|---|---|---|
| Availability | Ready API เป็น 0 ต่อเนื่อง 2 นาที หรือ synthetic read ล้ม 3 ครั้งติด | Critical; แยก DB/edge/backend จาก signals อื่น |
| Replica deficit | Ready ต่ำกว่า 2 ต่อเนื่อง 5 นาทีใน cloud environment | Warning; ตรวจ rollout/HPA/node |
| 5xx | มากกว่า 1% ใน 5 นาที เมื่อมีอย่างน้อย 100 requests | Warning/release hold; low traffic ใช้ synthetic checks เพิ่ม |
| Latency | p95 ของ catalog/read routes เกิน 1 วินาทีต่อเนื่อง 10 นาที | Warning; แยก upload/checkout ไม่ใช้ threshold เดียวทุก route |
| Restart/OOM | Restart เพิ่มตั้งแต่ 3 ครั้งใน 10 นาที หรือพบ OOMKilled | Warning/Critical ตาม impact |
| DB pressure | Hikari usage มากกว่า 80% หรือ pending/timeout ต่อเนื่อง 5 นาที | Warning; ตรวจ budget รวม rollout surge |
| Neon quota | Provider usage มากกว่า 80% ของ quota จริง | Warning; ถ้าไม่มี signal ให้แสดง unknown ไม่แสดง healthy |
| Object storage | S3 errors ต่อเนื่องหรือ known fixture object อ่านไม่ได้ | Warning; readiness UP อย่างเดียวไม่พิสูจน์ storage health |
| Telemetry freshness | Scrape/exporter/log pipeline หายเกินช่วงที่กำหนด | Monitoring failure; ไม่ตีความ no-data เป็น healthy |

ผ่านเมื่อ dashboard query ตรง metrics จริง, promtool tests ผ่าน firing/recovery/no-data cases และมี live receiver/delivery evidence ใน environment ทดสอบ

## 5. Trusted artifact และ release record

### D4 — รับ release จาก Component B

ใช้ `.github/workflows/docker-build.yml` เดิมเป็น publisher เพียงตัวเดียว Artifact ปัจจุบันชื่อ `container-image-contract-<commit>` และมี `image-contract.txt` เก็บ image, tag, index digest/reference, commit และ amd64/arm64 child digests

ก่อน deploy ต้อง:

1. รับ exact build run ID แล้วตรวจผ่าน GitHub API ว่า workflow/repository ถูกต้อง, run สำเร็จ และ source อยู่บน protected `main` ตาม policy
2. ตรวจ event/ref ด้วย เพราะ B อนุญาต `workflow_dispatch`; การมี signature จาก workflow ที่ถูกรันบน branch อื่นยังไม่เพียงพอสำหรับ production
3. Download artifact จาก run นั้นโดยตรง; parse เป็น data ด้วย strict fields/รูปแบบ ห้าม source หรือ execute เนื้อหา artifact
4. ตรวจ canonical GHCR repository, full source SHA, index digest และ child platform digests ให้ตรงกัน
5. Re-verify Cosign identity/issuer และ GitHub provenance/SBOM attestations ผูกกับ source SHA และ workflow ที่อนุญาต ไม่เชื่อแค่ข้อความ `verified=true` ใน artifact
6. ใช้ index digest เป็น release identity; เก็บ child digests เป็น evidence ของ architecture และห้าม resolve mutable tag ใหม่ระหว่าง staging/production

Baseline workflow เป็น manual dispatch รับ build run ID จนกว่าจะผ่าน acceptance แล้วจึงเพิ่ม automatic staging จาก successful build ได้ PR/fork validation ไม่เข้าถึง deployment credentials และ privileged job ไม่รัน code/artifact จาก PR ที่ไม่น่าเชื่อถือ

สร้าง release receipt แบบ versioned JSON มีอย่างน้อย:

- schemaVersion, releaseId, environment, sourceCommit, buildRunId และ deployRunId
- image repository/index digest/child digests และ attestation verification references
- rendered bundle schema version/hash, non-secret config hash และ migration compatibility decision
- previous successful release ID/bundle hash; ห้ามใช้ `kubectl rollout undo` เป็นหลักฐาน config snapshot
- migration Job name/result, rollout result, smoke summary, observation window และ timestamps
- staging receipt ID ที่ production ใช้อ้างอิง, approval evidence และ frontend commit/build ID เมื่อทดสอบ full stack
- phase/result เช่น accepted, rejected, rolled-back, recovery-required พร้อม sanitized reason

Receipt/log artifacts ไม่มี secrets, user JWT, JDBC URL หรือ full HTTP bodies และต้องกำหนด retention ที่ไม่สั้นกว่ารอบ rollback โดยจัดเก็บ last-known-good bundle ให้หาได้แม้ build artifact ของ B หมดอายุ

## 6. Deployment และ promotion

### D5 — Identity, bootstrap และ locking

- แยก staging/production GitHub Environments และ deployment identities
- ใช้ short-lived cluster access ผ่าน OIDC federation เมื่อ platform รองรับ; fallback เป็น trusted runner พร้อม short-lived credential broker ที่ platform จัดให้ ไม่ฝังค่า kubeconfig อายุยาวใน repository
- Namespace, runtime ServiceAccount, RBAC, Secret delivery, issuer และ monitoring platform เป็น bootstrap ของ platform owner ก่อน release
- ปรับ adapter ของ C ให้แยก bootstrap resources ออกจาก namespaced release resources ก่อนใช้กับ CI เพื่อไม่ต้องให้ CI สร้าง Namespace หรือถือ cluster-admin
- ปัจจุบัน adapter ใช้ `get secret` เพื่อพิมพ์ key names แม้ไม่พิมพ์ values แต่ RBAC `get` อนุญาตอ่าน Secret data ทั้งก้อน D ต้องเปลี่ยน CI preflight ไปใช้ trusted Secret-readiness contract/cluster-side validation ที่คืนเฉพาะผล โดยไม่ grant Secret get/list ให้ CI
- สิทธิ์ deploy workloads เองก็สามารถใช้ Secret references ได้ จึงต้องคง approved image/resource/Secret-reference validation และ admission policy ของ platform ไม่ถือว่าการตัด `get secret` ปิดความเสี่ยงทั้งหมด
- จำกัด RBAC ตาม namespace/resources/verbs ที่ release ใช้; create ของ Job ไม่สามารถจำกัดด้วย resourceNames แบบเดียวกับ get จึงใช้ namespace/admission restrictions ประกอบ
- ทุกคำสั่งต้องระบุ context/environment; ไม่เลือก current context อัตโนมัติ
- ใช้ environment-wide concurrency group `tcg-deploy-staging` / `tcg-deploy-production`, `cancel-in-progress: false` ครอบคลุม migration, rollout, smoke และ rollback ทั้ง flow
- ถ้ามี deploy จากหลายช่องทาง ใช้ namespace-scoped Lease/lock ร่วมกัน และตรวจ current release ก่อนเปลี่ยน state ป้องกัน stale/superseded deploy; concurrency ไม่ใช่ FIFO guarantee

### D6 — Staging release

1. Verify artifact identity และ lock environment
2. ตรวจ C production-equivalent prerequisites, real config, Secret readiness, monitoring freshness และ database connection budget
3. เก็บ previous accepted release bundle/config reference และ schema history ก่อน mutation; ยืนยัน backup/restore evidence สำหรับ migration ที่กำลังจะรัน
4. Render bundle ด้วย digest เดียวให้ API/Job; ตรวจ policy, schema และ server-side admission dry-run บน cluster จริง
5. Classify migration ว่ารองรับ prior API หรือไม่ ก่อนอนุญาต automatic rollback
6. Deploy prerequisites ที่ compatible กับ API รุ่นเดิม; รัน unique migration Job และหยุดก่อน API หาก fail
7. Rollout API, รอ Ready และรัน smoke ตามหัวข้อ 7
8. Observe เริ่มต้น 10 นาทีด้วย traffic ที่ควบคุมได้; no-data/insufficient samples เป็น unknown/hold ไม่ใช่ pass
9. บันทึก receipt และ unlock ใน failure/success paths; staging ผ่านแล้วจึงมี production candidate

ConfigMap/NetworkPolicy ของ C ถูก apply ก่อน migration จึงอาจกระทบ Pod รุ่นเก่า ต้องตรวจความเข้ากันได้และ restore config/policy ที่อนุมัติไว้เมื่อ failure phase อนุญาต

### D7 — Production promotion

- ต้องมี accepted staging receipt ของ index digest/source commit เดียวกัน และ A/C live gates ผ่านตาม release scope
- ใช้ protected production Environment approval; ตรวจว่าบัญชี/แผน GitHub ที่ใช้อยู่รองรับ gate จริง หาก gate ใช้งานไม่ได้ให้เป็น blocker ไม่แทนด้วย boolean input ที่ใครก็เปิดได้
- Promote digest เดิมโดยไม่ rebuild/retag/re-resolve image; render production config ใหม่และตรวจแยก
- Validate candidate ไม่ถูก supersede และ compatibility ของ frontend/backend/schema ก่อน deploy
- ใช้ migration-first flow และ observation window เหมือน staging แต่ smoke ไม่เปลี่ยน business data
- Production login อาจออก short-lived token ให้ dedicated smoke account เพื่อ `GET /api/auth/me` ได้ หากไม่มี side effect ที่ไม่อนุญาต; token ไม่ถูกบันทึกลง artifact
- ถ้า smoke/rollout fail ให้เข้าตาราง recovery ห้ามทำ pipeline green เพียงเพราะ rollback สำเร็จ

## 7. Smoke และ full-stack matrix

### D8 — ชุดทดสอบและ data lifecycle

| ชุด | Endpoint/flow จากโค้ดจริง | Staging | Production |
|---|---|---|---|
| Public health | `GET /api/health` ตรวจ API และ DB; catalog `GET /api/products`, detail และ `/api/cards` | ต้องผ่าน | Read-only ต้องผ่าน |
| Internal probes | `/actuator/health/liveness`, `/actuator/health/readiness` ผ่าน private cluster path | ต้องผ่าน | Internal check เท่านั้น; public Ingress ต้องไม่ route |
| Authentication | `POST /api/auth/login` -> `GET /api/auth/me`; invalid/expired JWT | Dedicated test accounts | Dedicated smoke account/read paths ตาม policy |
| Authorization | Anonymous cart/orders=401; non-admin `/api/admin/**` ถูกปฏิเสธ; seller ownership | ตรวจหลาย users/roles | Non-mutating denial/read checks เท่านั้น |
| Upload | Multipart `POST /api/seller/products`, PUT เปลี่ยนรูป, GET public image URL | Approved test seller/listing, ตรวจ object/DB consistency และข้าม replicas | GET known fixture object เท่านั้น; ไม่ upload/delete |
| Cart | `/api/cart`, POST/PATCH/DELETE `/api/cart/items...` | แยก test buyer/listing | ไม่เปลี่ยน cart |
| Checkout | `POST /api/orders` พร้อม `Idempotency-Key`; GET `/api/orders/me` และ `/{orderId}` | Retry key เดิมต้องได้ order เดิมและ reserve ครั้งเดียว; buyer อื่นอ่านไม่ได้ | ไม่สร้าง order |
| Edge | TLS/host/HTTP behavior/CORS; `/actuator` ไม่เปิด public | รวม max upload, 413 และ auth rate 429 ใน controlled test | Bounded read/preflight; ไม่ load/fault test |
| Full frontend | Login -> catalog -> upload -> cart -> checkout ผ่าน staging frontend URL | Browser test ต้องผ่านพร้อม frontend build ID | Browser read/login path ที่อนุญาตหลัง frontend promotion |

`/api/health` เป็น route สาธารณะที่มีอยู่จริงใน HealthController/SecurityConfig และเข้าผ่าน C Ingress `/api` ได้ ต่างจาก Actuator probes ที่ต้องตรวจภายใน cluster เท่านั้น External availability ใช้ catalog read เป็นหลัก และอาจใช้ `/api/health` ประกอบตาม API contract ไม่ทดสอบ public `/actuator` โดยคาดหวัง HTTP 200

ข้อกำหนด smoke harness:

- CLI ระบุ environment/base URL/fixture scope; credentials ผ่าน environment/secret channel และไม่อยู่ใน command arguments หรือ reports
- Mutating tests เปิดได้เฉพาะ staging/isolated clone ที่ match allowlist ทั้ง API host, database/bucket identity และ fixture marker; production mode ต้องปฏิเสธการเปิด flag mutation
- ใช้ test buyer/approved seller และ test-run ID เฉพาะ ห้าม seed demo accounts ลง production
- Retry write เฉพาะที่มี contract รองรับ เช่น checkout ต้องใช้ idempotency key เดิมที่ผูกกับ test run ไม่สร้าง key ใหม่ใน retry
- Listing ที่มี order history อาจลบ physical record ไม่ได้; ใช้ isolated staging clone หรือ documented retention/hide หลัง cancel ตาม domain contract ไม่บังคับลบ SQL ข้าม business logic
- Cleanup ระบุ test-owned IDs/object keys เท่านั้น; failure ทำให้ receipt แจ้ง cleanup pending และไม่ยกเลิก failed test result
- Demo password reset ถูกปิดโดย default ใน AuthService ต้องยืนยันว่า staging/production release ไม่เปิด `app.auth.demo-reset-enabled`; ไม่ใช้ reset API เพื่อเตรียม smoke credentials
- Browser artifacts ต้อง redact token/password/PII และไม่อัปโหลด HAR ที่มี auth headers ทั้งก้อน

## 8. Rollback และ recovery matrix

### D9 — Phase-aware recovery

| จุดที่ fail | พฤติกรรมที่ต้องได้ | สิ่งที่ห้ามอนุมาน |
|---|---|---|
| Verify/render/admission ก่อน mutation | Reject release, ไม่มี rollout | Artifact download สำเร็จไม่ได้แปลว่า image verified |
| Prerequisite/config apply | Restore approved prior non-secret config/policy เมื่อ safe แล้วตรวจ old API | Pod เดิมยังอยู่ไม่ได้แปลว่า config/policy เดิมไม่เปลี่ยน |
| Migration fail | หยุดก่อน API, เก็บ redacted evidence; ตรวจ partial schema/locks และ prior API health | ไม่ auto reverse SQL หรือ `flyway clean` |
| Rollout/smoke fail หลัง migration | Auto restore previous approved application bundle/config เฉพาะเมื่อ schema compatibility ยืนยันและ prior bundle verified | `rollout undo` อย่างเดียวไม่คืน ConfigMap/Ingress/NetworkPolicy |
| Schema incompatible/unknown | Recovery-required, หยุด promotion และใช้ reviewed forward fix/restore runbook | ไม่ restart prior API ที่อ่าน schema ใหม่ไม่ได้ |
| First deploy ไม่มี prior accepted release | Mark failed และ controlled recovery ตาม bootstrap plan | ไม่สร้าง last-known-good ขึ้นจากภาพที่ยังไม่เคยผ่าน |
| Rollback fail | Mark recovery-required และ escalate พร้อม release/request IDs | ไม่ loop rollback หรือรายงาน success |
| Monitoring/no-data | Hold promotion และตรวจ freshness/synthetic evidence | ไม่ rollback เพียงเพราะ metrics exporter ไม่ส่งข้อมูล |

Rollback ใช้ previous receipt ที่ผูก image digest, config/bundle hash และ schema compatibility; validator ต้องรองรับ bundle schema รุ่นปัจจุบันและ predecessor ที่อนุมัติไว้โดยไม่เปิด arbitrary resources Migration ที่ผ่านแล้วคงบันทึก Flyway version เดิม ไม่รัน migration รุ่นเก่าย้อนกลับอัตโนมัติ

การ apply bundle เก่าอย่างเดียวอาจทิ้ง resources ที่ release ใหม่เพิ่มไว้ ต้อง reconcile inventory และลบเฉพาะ resource ที่บันทึกว่า release นี้สร้างและเป็นของระบบนี้ พร้อมตรวจ exact names/ownership ก่อน ห้าม prune ทั้ง namespace หรือใช้ label กว้างที่กระทบ resources ของ platform

Secret rotation แยกจาก app rollback: เก็บ provider reference/version และ compatibility policy โดยไม่ snapshot values ลง release artifact หาก Secret version เดิมไม่มีแล้วต้องเป็น recovery decision ไม่อ้างว่า app rollback คืน credential สำเร็จ

### D10 — Backup/restore และ disruption rehearsal

- ใช้ isolated Neon branch/restore target ตรวจ schema/Flyway history, users/listings/carts/orders/constraints และ prior API compatibility
- Record backup/PITR reference, restore start/end, observed RPO/RTO และ recovery owner; ยืนยัน retention/recovery capability ที่ account ใช้จริงก่อน production ไม่สมมติว่า free plan มีทุกความสามารถ
- Restore DB ต้องตรวจ object references ด้วย เพราะ database restore ไม่คืนรูปที่ถูกลบออกจาก object storage
- ยืนยัน provider ของ `OBJECT_STORAGE_ENDPOINT` ที่ใช้จริงและ recovery/versioning/backup ที่รองรับ ปัจจุบันแผนทีมใช้ Neon Object Storage แต่ application รองรับ S3-compatible provider อื่น หาก provider ไม่มี recovery ให้กำหนด immutable object copies/manifest และ retention ทางเลือกก่อนยืนยัน image recovery acceptance
- Rehearsal ไม่ restore ทับ production; cutover ของข้อมูลจริงเป็นขั้นตอน operation ที่ต้องมีแผนและผู้รับผิดชอบ
- Node drain/reboot, DB outage และ failing rollout ทำบน staging/isolated lab เท่านั้น และใช้ A/C controls ตามเดิม
- Single-node cluster ใช้ทดสอบ recovery ได้ แต่ไม่ถือเป็น node-level HA; multi-node acceptance ต้องมี nodes/metrics จริง

## 9. Work packages และ file ownership

D0 เริ่มจากกำหนด receipt JSON schema, versioned C bundle extension/rollback compatibility, exact telemetry/monitoring labels และ platform inputs ที่ยังขาด พร้อมระบุ owner ของ shared files ด้านล่างก่อนแจก implementation ทุก agent ใช้ contract ฉบับเดียวกัน

| งาน | Deliverables/paths ที่เสนอ | Owner/ขอบเขต |
|---|---|---|
| D0 contract | Release receipt schema, telemetry fields, platform/receiver/frontend inputs | Integration lead; ตกลง C extension และ B handoff ก่อน code merge |
| D1 logs | `common/observability/RequestCorrelationFilter.java`, `RequestCorrelationFilterTests.java`, `StructuredLoggingTests.java` | Agent logs; ส่ง logging settings ให้ lead รวม ไม่แก้ business services |
| D2 metrics | `common/observability/MetricsConfiguration.java`, `S3OperationObservation.java`, `MetricsExposureIntegrationTests.java` | Agent metrics; lead รวม `pom.xml`, `SecurityConfig.java`, `application.yml` และ S3 adapter call sites |
| D3 monitoring | `infra/observability/**`, dashboard JSON, alert/promtool tests | Agent monitoring; platform install แยกจาก application bundle |
| D4/D5 release trust | `scripts/release/verify_artifact.py`, `receipts.py`, `locking.py` และ tests ชื่อเดียวกันใต้ `scripts/release/tests` | Agent release; lead เป็น owner ของ JSON schemas/CLI contracts; coordinate B/C |
| D6/D7 workflows | `scripts/release/orchestrate.py`, `test_orchestrate.py`, `.github/workflows/deploy-staging.yml`, `deploy-production.yml`, shared `deploy-environment.yml` ถ้าจำเป็น | Agent orchestration; ใช้ verifier/adapter เดิม ไม่ทำ image publisher ใหม่ |
| D8 smoke | `scripts/smoke/**`, test fixtures, browser config สำหรับ deployed URL | Agent smoke; แยก staging mutation และ production read modes |
| D9/D10 recovery | `scripts/release/recovery.py`, `scripts/release/tests/test_recovery.py`, `docs/runbooks/kubernetes-release-recovery.md`, `data-restore-rehearsal.md` และ evidence templates | Recovery agent หลัง receipt/adapter interface stable |

Shared-file rule:

- Lead เท่านั้นรวม `pom.xml`, `application.yml`, `SecurityConfig.java`, `scripts/deploy-kubernetes.ps1`, `scripts/kubernetes/render.py` และ tests ของ C
- C owns overlay/NetworkPolicy validation ต้องเพิ่ม monitoring ingress, explicit config keys, bootstrap/release split และ bundle-schema compatibility โดย review owner เดียว
- B owns `docker-build.yml`/`ci.yml`; เปลี่ยน publisher contract เท่าที่จำเป็นและยังต้องผ่าน gates เดิม
- A owns probe/HPA/PDB/runtime behavior; D ใช้ health paths เดิมเป็น baseline ไม่เปลี่ยน semantics เพื่อให้ monitoring ผ่าน
- Frontend repo รับผิดชอบ build/CDN-provider deployment; D บันทึก artifact/URL และรัน browser acceptance ไม่สมมติว่า backend workflow deploy frontend ได้แล้ว

แบ่งขนานเริ่มต้น 4 agents: logs, metrics, monitoring, smoke ส่วน lead ทำ D0 contract/receipt-interface integration เมื่อ API ของ contract stable จึงเพิ่ม release/workflow/recovery agents ได้ เพื่อไม่ให้แก้ helper/workflow/schema ชนกัน

## 10. ลำดับ implementation และ gates

1. D0 — สรุป receipt/bundle schema, C integration, monitoring destination และ real platform input checklist
2. D1/D2/D3/D8 — ทำ instrumentation, dashboard/rules และ smoke harness แบบขนาน; ผ่าน unit/contract/negative tests ในเครื่อง
3. D4/D5 — ทำ trusted artifact verifier, deployment identity/bootstrap split และ lock/concurrency tests
4. D6 — Integrate migration/rollout/smoke/observation และ phase-aware rollback บน isolated staging fixture
5. Commit/integrate source แล้วให้ B publish/verify image ของ revision ที่มี D code; รับ exact run/digest จริง
6. Deploy staging จริงและปิด A/C live gates ที่เกี่ยวข้อง พร้อม recovery rehearsal
7. D7 — Verify production approval gate และ promote digest เดิม; ตรวจ frontend pairing และ production read-only smoke
8. D9/D10 — ปิด measured recovery, dashboards/alerts และ runbooks พร้อมผู้รับผิดชอบ

เตรียม workflow code และ offline tests ได้ก่อน A/C live acceptance แต่ production promotion จะไม่เปิดจน evidence ที่จำเป็นครบ การมีแผน D นี้ไม่ใช่การอนุญาต deploy/สร้าง cloud resources ในรอบวางแผน

## 11. Verification matrix

| ระดับ | สิ่งที่ต้องพิสูจน์ |
|---|---|
| Application | JSON/redaction/MDC isolation, bounded metric labels, scrape exposure, unchanged probes/auth/CORS |
| Release offline | Wrong repo/ref/SHA/run/digest/attestation rejected, fixture rejected, stale receipt rejected, no credential logs |
| Workflow | actionlint, least-privilege permissions, no PR privileged execution, exact artifact run, per-environment locks, cancellation/failure paths |
| Monitoring | promtool rules/alerts incl no-data, dashboard queries, strict optional CRD schemas, exact NP scrape allowance |
| Deployment mock | Migration failure blocks API, smoke failure triggers only safe rollback, config restore, first-release/no-previous, recovery failure remains failed |
| Staging live | TLS/CORS/metrics/log delivery, safe fixture E2E, A two-pod/outage/HPA/drain และ C ingress/network tests |
| Production live | Approval, unchanged digest, non-mutating smoke, observation/receiver freshness และ retained release evidence |

## 12. Definition of Done

- [ ] Application ส่ง structured redacted logs และ request ID ที่ไม่รั่วข้าม request
- [ ] Metrics scrape ทำงานผ่าน private path; public actuator ถูกปิดตาม C contract
- [ ] Dashboard/alert ครอบคลุม HTTP/JVM/pool/cluster/S3/Neon quota พร้อมแยก unknown/no-data
- [ ] Trusted artifact ผูก exact source/build run/image digest/signature/provenance/SBOM
- [ ] CI identities/bootstrap/Secret readiness และ environment concurrency ผ่าน tests
- [ ] Migration-first staging flow, smoke และ observation ผ่านกับ real environment
- [ ] A/C live gates และ backward-compatible rollback rehearsal ผ่าน
- [ ] Production approval gate enforce จริงและ promote index digest เดิม
- [ ] Failure คืน prior approved bundle/config เมื่อ safe; unknown schema เข้าสู่ recovery-required
- [ ] Restore DB/object consistency มี evidence และ measured RPO/RTO
- [ ] Frontend version/API URL/CORS pairing และ full-stack staging ผ่าน
- [ ] Last-known-good release receipt, monitoring receiver owner และ runbooks พร้อมใช้

## 13. งานค้างจาก A/B/C ที่ต้องรวมไว้ใน acceptance รอบ D

- A: Two replicas/shared S3 state, restart, DB outage/recovery, in-flight rollout, failed rollout/rollback, HPA/connection budget และ multi-node drain
- B: Actual GHCR publish/scan/attest/sign verification และ published index digest handoff จาก trusted main revision
- C: Real config/Secret delivery/roles/direct TLS DB endpoint, DNS/TLS/renewal, upload/CORS/rate limit และ NetworkPolicy enforcement กับ CNI จริง
- Platform: Kubernetes context ยังไม่มีในเครื่องนี้, monitoring/log destinations, short-lived deploy access, issuer และ receiver configuration ยังต้องเตรียม
- Frontend: Provider/build/deploy contract และ staging/production URLs ยังต้องรับจาก frontend owner
- Operations: Database/object recovery capability/retention และ target RPO/RTO ต้องยืนยันจาก environment จริง

รายการนี้แยกจาก implementation checklist เพื่อไม่ปิด Component D เพียงเพราะ workflow syntax หรือ local tests ผ่าน

## 14. แหล่งอ้างอิงสำหรับ implementation

- [Spring Boot 4.0 metrics/Prometheus](https://docs.spring.io/spring-boot/4.0/reference/actuator/metrics.html)
- [Spring Boot 4.0 structured logging](https://docs.spring.io/spring-boot/4.0/reference/features/logging.html)
- [GitHub deployment environments/concurrency](https://docs.github.com/en/actions/how-tos/deploy/configure-and-manage-deployments/control-deployments)
- [GitHub secure use](https://docs.github.com/en/actions/reference/security/secure-use)
- [Prometheus metric labels/cardinality](https://prometheus.io/docs/practices/naming/)
- [Prometheus histogram guidance](https://prometheus.io/docs/practices/histograms/)
