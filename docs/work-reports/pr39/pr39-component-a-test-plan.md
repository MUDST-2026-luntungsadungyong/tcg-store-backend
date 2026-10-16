# Component A — Reliability: แผนทดสอบต่อ

วันที่ 2026-10-16 · สถานะ PLANNED ไม่ใช่ผลทดสอบผ่าน

รอบนี้อ่าน source/config และจัดแผนเท่านั้น ไม่เปลี่ยน runtime, ไม่ทำ DB outage,
ไม่เพิ่ม load และไม่แก้ข้อมูล Neon development ที่ Desktop กำลังใช้อยู่

## 1. เป้าหมายและ environment

ปิด acceptance เรื่อง probes/recovery, shared state สอง replicas, graceful
termination, rollout/rollback, HPA และ connection budget ด้วยหลักฐานจาก cluster จริง

Docker Compose ปัจจุบันใช้เป็น functional baseline ได้ แต่ไม่พิสูจน์ Kubernetes
probes, scheduler, Deployment, HPA, PDB หรือ node availability

ใช้ staging/acceptance namespace แยก พร้อม Neon branch/compute และ bucket หรือ
prefix fixtures ที่อนุมัติแล้ว ห้าม suspend Neon shared development หรือ revoke
credentials ร่วมเพื่อทำ outage ให้ fault จำกัดเฉพาะ test Pods
หากสร้าง infrastructure ใหม่ต้องได้รับอนุมัติก่อน ไม่รวมการ provision ในแผนรอบนี้

ต้องมีอย่างน้อย 2 schedulable worker nodes เพื่อปิด node-level HA ถ้ามี node เดียว
ทดสอบระดับ process/Pod ได้ แต่ A-08 ต้องเป็น NOT TESTED ห้ามสรุปว่า node HA ผ่าน

ห้ามใช้ local overlay เดิมโดยไม่ปรับ: ตอนนี้ลดเหลือ 1 replica และเอา HPA/PDB ออก
ใช้ acceptance overlay ที่ยึด staging/base และแยก namespace/secrets/fixtures
staging overlay ปัจจุบันยังมี placeholder image/hosts ต้อง resolve ก่อน deploy

## 2. ค่าที่ตรวจพบจาก source ปัจจุบัน

| รายการ | ค่า |
|---|---|
| Replicas / rollout | 2, maxSurge 1, maxUnavailable 0, minReadySeconds 10 |
| Rollout deadline | progressDeadlineSeconds 600 |
| Startup | liveness endpoint, 5s × 30, timeout 2s |
| Liveness | livenessState เท่านั้น; period 30s, failures 3, timeout 2s |
| Readiness | readinessState + db; period 10s, failures 2, timeout 2s |
| Graceful termination | preStop 5s, Spring timeout 30s, Pod grace 45s |
| HPA | 2–4 Pods, CPU 70%, memory 80%; ขึ้นทีละ 1/30s; ลงทีละ 1/60s หลัง stabilization 300s |
| DB pool / timeout | maximumPoolSize 5/Pod, minimumIdle 0; connection timeout 10s, validation timeout 5s |
| Placement / PDB | hostname spread แบบ soft; minAvailable 1 |

ไฟล์อ้างอิง: backend/docs/kubernetes-component-a-runtime-reliability.md,
backend/docs/kubernetes-live-acceptance-plan.md (A-01–A-08),
backend/infra/kubernetes/base/{deployment,horizontalpodautoscaler,poddisruptionbudget}.yaml,
backend/src/main/resources/application{,-neon}.yml

HealthProbeIntegrationTests ใช้ DB HealthIndicator จำลอง ส่วน graceful test ใช้
test filter และ context.close(): เป็น regression ที่ดี แต่ยังไม่ใช่หลักฐาน
network outage หรือ SIGTERM จาก kubelet บน cluster จริง

## 3. Preflight — ต้องผ่านก่อน fault/load

บันทึก context, cluster/version/CNI, node count/capacity, namespace, deployment UID,
image digest ของ baseline/candidate, rendered-config hash และ secret reference/version
โดยไม่ export Secret values หรือ dump .env

ต้องมี RBAC สำหรับอ่าน events/EndpointSlices/Pods และเปลี่ยนเฉพาะ test workload,
Metrics API ใช้งานได้, collector/logs/private scrape พร้อม, clocks ตรงกัน และ
fixture identities ที่ใช้สร้าง/ลบเฉพาะข้อมูลทดสอบได้

ระบุ Neon branch/compute ID, direct หรือ pooled endpoint, quota scope/หน่วย,
max_connections/reserved slots, role limits, baseline connections ของ workload อื่น
รวม Desktop ถ้าแชร์ quota และวิธีอ่าน connection/queue metrics จริง
ใช้ค่าที่มี timestamp จาก provider/DB; ถ้าไม่ทราบ quota ให้ A-07 เป็น UNKNOWN
และไม่เริ่มทดสอบ HPA เต็มขนาด ไม่อนุมานว่า provider ทุก compute มี quota เท่ากัน

ต้องมี known-good image/config และเส้นทาง recovery ที่ทดลองอ่านได้ก่อนเริ่ม
พร้อม fault cleanup แบบ finally/watchdog และผู้รับผิดชอบ abort

## 4. ลำดับและเกณฑ์ผ่าน

ค่าด้านเวลา/performance ด้านล่างเป็น proposed test contract ต้องล็อกก่อนรัน
และไม่ปรับ threshold ย้อนหลังเพื่อเปลี่ยนผล fail ให้ผ่าน

| ID | วิธีทดสอบ | เกณฑ์ผ่าน / หลักฐานหลัก |
|---|---|---|
| A-00 Baseline | 2 Ready Pods, warm-up แล้วเก็บ low-rate traffic 600s | Probe/Pod UID/restarts, request totals, latency, HPA/resource/DB baseline ครบ |
| A-01 Shared state | ผ่าน Service ด้วยหลาย connections ให้ถึงทั้งสอง Pods; JWT เดียวกัน; สร้าง listing+รูป fixture ผ่าน Pod A อ่าน record ผ่าน B และเทียบ object hash; replace A แล้วอ่านซ้ำ | ทั้งสอง Pod UIDs ให้บริการจริง ไม่พึ่ง sticky session; record/object hash ตรงและคงอยู่หลัง replacement; เก็บ request_id→pod_uid→fixture_id/hash |
| A-02 Outage/recovery | ตัด DB traffic เฉพาะ A แล้วคืน, ทำซ้ำกับ B, สุดท้ายตัดทั้งสอง; คงแต่ละ outage 120s | ทุก Pod ที่ได้รับผล liveness HTTP 200/UP, readiness 503/DOWN และ EndpointSlice ready=false; restart count/UID เดิมไม่เปลี่ยน; คืน DB แล้ว readiness/traffic ฟื้นเองภายใน proposed 60s; ตรวจอย่างน้อย 2 ครั้งติดกัน |
| A-03 SIGTERM | ให้ request fixture ที่ควบคุมระยะเวลา 10–20s เริ่มบน Pod ที่รู้ UID ก่อน normal graceful delete; ยิง traffic ผ่าน Service ต่อเนื่อง; ทำซ้ำ 3 รอบ | มีหลักฐาน request เริ่มก่อน TERM และจบสำเร็จภายใน Spring window 30s; Pod จบภายใน grace 45s ไม่ exit 137/SIGKILL; request ใหม่ไม่ถูกเลือกไป terminating endpoint หลัง routing propagation |
| A-04 Rolling update | ตรึง HPA ที่ 2 ตลอด A-01–A-05 พร้อม snapshot config และคืนก่อน A-06; rollout baseline→verified candidate ที่ schema compatible ขณะยิง traffic และ in-flight fixture | เก็บ old/new digests, rollout/events/EndpointSlices; Ready capacity ไม่ต่ำกว่า 2 ใน controlled case; controlled requests ไม่หลุด/ซ้ำ; rollout Complete |
| A-05 Failed rollout/rollback | ใช้ digest เดิมแต่ patch readiness path ผิดเฉพาะ candidate template เพื่อให้รันได้แต่ไม่ Ready; ไม่มี DB migration ใหม่ | candidate ไม่เข้า ready endpoints, old 2 Pods ยังรับ traffic; เก็บ ProgressDeadlineExceeded หลัง deadline จริง >600s รวม margin ไม่ถือ client timeout อย่างเดียวเป็นหลักฐาน; rollback image+config ที่ snapshot ไว้แล้วกลับ 2/2 Ready และ smoke ผ่าน |
| A-06 HPA/load | คืน HPA 2–4; warm-up 5m, ramp load เป็นขั้นทุก 2–3m ถึงจุด scale ภายใต้ quota; hold 600s; ลด load แล้วดู cooldown 10–15m | Metrics targets ไม่ unknown, scale 2→3→4 ภายใต้ safe budget แล้วกลับ 2 เมื่อทั้ง CPU/memory ต่ำพอ; ไม่มี OOM/restart/connection timeout; แสดง desired/current/ready replicas และ scaling reasons |
| A-07 Connection budget | เก็บตลอด A-00–A-06 รวม HPA สูงสุด + rollout ที่อนุมัติให้ทับกัน และ terminating Pods | peak/API pools/DB totals/queues อยู่ใน budget ที่ล็อกไว้และเหลือ reserve; มี quota จริงพร้อม timestamp ไม่ใช้เฉพาะ active connections |
| A-08 Placement/drain | เฉพาะ multi-node acceptance cluster: ตรวจ hostname แล้ว drain node ที่มี test Pod ภายใต้ PDB; คืน scheduling หลังจบ | มี Pod บนคนละ worker เมื่อ capacity พอ, เหลือ Ready อย่างน้อย 1 และ smoke ต่อเนื่องผ่าน; บันทึก eviction/reschedule/events; single-node = NOT TESTED |

### รายละเอียด outage ที่ห้ามตีความผิด

- Probe HTTP ต้องวัดตรงแต่ละ Pod จาก private test channel ไม่ผ่าน Service อย่างเดียว
  เพราะ Service จะหยุด route ไป NotReady Pods ทำให้ไม่เห็น liveness ของมัน
- เก็บ HTTP 503/DOWN แยกจาก probe timeout หากมีแต่ timeout ยังไม่พิสูจน์ health
  payload DOWN ตาม contract นี้ readiness timeout 2s สั้นกว่า DB acquisition 10s
  จึงเป็นจุดที่ต้องวัดจริง ไม่ปรับ probe เพื่อซ่อน failure ระหว่างรัน
- Proposed detection budget คือภายใน 60s หลังยืนยัน fault มีผลจริง ไม่ใช่นับจาก
  เวลาออกคำสั่งเพียงอย่างเดียว; recovery นับจากยืนยัน DB path กลับมาแล้ว
- ช่วงตัดทั้งสอง Pods คาดว่า business API อาจ 503/timeout ได้ แยก expected-outage
  totals ออกจาก unexpected failures ใน rollout/load; ห้ามอ้าง zero downtime ตอน DB หายทั้งชุด
- ใช้ controllable TCP proxy/approved test-network fault ที่ตัด existing connections ได้
  และยืนยันว่า DB unavailable จริงก่อนเริ่มจับเวลา ไม่ suspend/restart shared Neon
  ถ้าใช้ NetworkPolicy ต้องแก้ allow rules ที่มีผลและพิสูจน์ CNI enforcement:
  เพิ่ม deny policy ทับ allow เดิมอย่างเดียวไม่พอ และ connection เก่าอาจยังอยู่
  ([Kubernetes NetworkPolicy](https://kubernetes.io/docs/concepts/services-networking/network-policies/))

### In-flight / rollout / HPA nuance

ต้องมี deterministic long-request fixture ที่ได้รับอนุมัติ ไม่มีใน-flight จริง
ให้บันทึก A-03 ว่า NOT TESTED; ห้ามนับ curl ที่อาจจบก่อน signal หรือเพิ่ม public
sleep/debug endpoint ใน production image หากต้องใช้ instrumented test image
ต้องระบุ digest ให้ชัดและแยกผลจาก release artifact จริง

จับ timeline deletionTimestamp→EndpointSlice terminating/ready→preStop→TERM→request
complete→process exit ไม่ตั้งสมมติฐานว่า Pod หยุดรับทุก connection ทันทีที่ delete
([termination flow](https://kubernetes.io/docs/tutorials/services/pods-and-endpoint-termination-flow/))

HPA ใช้ CPU และ memory พร้อมกัน จึงยังไม่ scale down หาก memory recommendation
ยังสูง แม้ CPU ลดแล้ว ต้องเก็บเหตุผล ไม่ force scale แล้วอ้างว่า auto scale-down ผ่าน
([HPA behavior](https://kubernetes.io/docs/concepts/workloads/autoscaling/horizontal-pod-autoscale/))

## 5. Connection budget — แก้สมมติฐาน 25 เป็นเพียง nominal

Nominal จาก config: steady 2×5=10, HPA สูงสุด 4×5=20,
rollout non-terminating 5×5=25 แต่ 25 ไม่ใช่ hard upper bound ของทั้งระบบ:
terminating Pods อาจยังถือ connections และจำนวน Pod ระหว่าง rollout อาจมากกว่า
replicas+maxSurge ([Deployment documentation](https://kubernetes.io/docs/concepts/workloads/controllers/deployment/))

ใช้ envelope ก่อนรัน โดยทุกจำนวนต้องอยู่ใน quota scope เดียวกัน:

```text
C_api_envelope = (R_max + surge + T_overlap_budget) × P
C_api_envelope + C_other_bound + C_jobs_bound + Reserve <= Q_usable
P=5, R_max=4, surge=1, Reserve>=5
```

T_overlap_budget ต้องได้จาก rollout/drain ที่วัดและจำกัดจริง ไม่ตั้งเป็น 0 เอง
ถ้ายังจำกัด overlap ไม่ได้ให้ลด replicas/pool หรือใช้ขีดจำกัด connections ที่
บังคับได้กับ test role/proxy ก่อน ไม่ยืนยัน HPA=4 ว่าปลอดภัยจาก quota 30 อย่างเดียว
Migration jobs ต้องจบก่อน rollout เว้นแต่ถูกนับไว้ใน envelope ชัดเจน

ขณะรันต้องรายงานอย่างน้อย:

- จำนวน Pods ที่ Ready/Starting/Terminating พร้อม Hikari active + idle/total,
  max, pending และ timeout counters แยกแต่ละ Pod UID รวม Pod ที่กำลังหายไป
- จำนวน connections รวมฝั่ง PostgreSQL และ non-API/observer/jobs,
  provider quota/used, queues และเวลา scrape; อย่าสรุปว่าค่า active=total
- Peak observed ที่ sampling 1s เทียบกับ Q_usable และ reserve เป็นทั้งจำนวน/%
  ระบุ resolution และ gap; sampling ไม่ใช่หลักประกันว่าจะจับทุก transient peak
- ถ้าใช้ pooled endpoint แยก client connections กับ PostgreSQL server backends
  และใช้ quota ที่ตรงหน่วย ห้ามเอาสองชุดมาบวกซ้ำหรือเทียบ Hikari กับ backend quota
  แบบ 1:1 ([Neon compute/connection limits](https://neon.com/docs/manage/endpoints/))

Missing quota/exporter หรือขาด terminating-Pod samples = UNKNOWN ไม่ใช่ PASS

## 6. Collectors / evidence contract

เปิด collectors ก่อน baseline และให้ทำงานต่อจน recovery/cooldown จบ:

- Probes 1s: UTC timestamp, monotonic elapsed, run_id, Pod UID, endpoint,
  HTTP status, UP/DOWN, duration, timeout/error; จำกัด private diagnostic traffic
- Cluster watch + snapshots 5s: Deployment/ReplicaSets/Pods/node placement,
  HPA/PDB/EndpointSlices/events รวม resourceVersion และ deletionTimestamp
- Requests: ทุก attempt มี request_id, phase, route template, intended workload,
  actual Pod UID จาก collector/access logs, status/transport error/latency และ
  fixture assertion ห้าม hidden automatic retry กลบ initial errors
- Metrics: Hikari/DB 1s ถ้าระบบรองรับอย่างปลอดภัย; CPU/memory/HPA 5–15s;
  capture source timestamp/age ระบุ metric stale หรือ missing และ overhead observer
- Load report: scheduled/sent/completed/in-flight/cancelled/dropped iterations,
  2xx/4xx/429/5xx/timeouts/resets, p50/p95/p99, delivered RPS และผล correctness
  ทั้งราย phase/route และรวม ระบุ retry attempts แยก logical operations

Proposed SLO สำหรับ phase ปกติ: unexpected failures <=0.1%, p95 ไม่เกิน
max(500ms, 1.5×baseline p95) และ controlled critical fixture operations สำเร็จ
100% ไม่มี lost/duplicate writes ส่วน outage จัด expected failures แยกไว้
ต้องอนุมัติ threshold และ max offered load ก่อนเริ่ม fault

Evidence bundle ใช้ run_id เดียว: environment.json, config/digest hashes,
events.ndjson, probes.csv, requests.ndjson, pod-placement.csv, hpa.csv,
connections.csv, rollout-rollback.json, recovery/cleanup receipt, summary.md
และ SHA256 manifest ทุกไฟล์ ไม่มี JWT/password/Secret values/PII/request bodies

## 7. Abort / cleanup / การปิดงาน

หยุด load และคืน fault ทันทีหากแตะ reserve budget, มี unexpected liveness restart,
OOM, critical data corruption/duplicate write, recovery เกิน deadline หรือ metric
สำคัญหายจนควบคุมความเสี่ยงไม่ได้ เก็บหลักฐานก่อน cleanup เท่าที่ปลอดภัย
Failure ที่คาดไว้ของ bad candidate ไม่ใช่เหตุลบ healthy old ReplicaSet

finally/watchdog ต้องคืน network/proxy fault, known-good image+config/HPA,
fixture data/object เฉพาะของ run ตาม reference-safe cleanup policy และ uncordon
เฉพาะ node ที่ run นี้ cordon ไว้ ห้าม cleanup กว้างทั้ง namespace/DB/bucket

หลังทุก phase ยืนยัน baseline 2 Ready, stable probes, queues คืนปกติ,
connection reserve กลับมา และ source/config ไม่มี test fault ค้าง

แต่ละ A-ID ลง PASS/FAIL/BLOCKED/NOT TESTED พร้อม evidence paths; ไม่มี quota หรือ
collector gap ให้ใช้ UNKNOWN ใน measurement ไม่ตีเป็นศูนย์ ไม่ปิด Component A
จนทุก required case ผ่านและ parent review หลักฐานดิบแล้ว

เวลารันคร่าว ๆ 90–150 นาทีหลัง environment พร้อม ไม่รวม provision/แก้ defect
เริ่มงานจริงด้วย read-only cluster/quota/collector preflight แล้วค่อยพัฒนา test
harness/fault fixtures ตามช่องว่างที่พบ ไม่เริ่ม outage/load จาก Compose ปัจจุบัน
