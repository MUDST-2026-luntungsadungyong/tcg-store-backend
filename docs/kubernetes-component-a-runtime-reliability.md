# Component A — Runtime reliability and horizontal scaling

สถานะ: Implementation complete; live-cluster acceptance pending

Parent plan: [แผนทำ Backend ให้รองรับ Kubernetes และการกระจายโหลด](./kubernetes-backend-distribution-plan.md)

## 1. ขอบเขตที่ทำแล้ว

- แยก health semantics ชัดเจน:
  - startup ใช้ liveness endpoint แต่มีเวลาเริ่มระบบสูงสุด 150 วินาที
  - liveness ตรวจเฉพาะ `livenessState` และไม่ผูกกับ database
  - readiness ตรวจ `readinessState` และ `db`
- เปิด Spring graceful shutdown 30 วินาที
- ให้ Kubernetes รอ 5 วินาทีก่อนส่ง SIGTERM และให้เวลารวม 45 วินาที
- ตั้ง Deployment เริ่มต้น 2 replicas และ rolling update แบบ `maxUnavailable: 0`
- เพิ่ม topology spread แบบ soft constraint ตาม hostname
- เพิ่ม PodDisruptionBudget ให้เหลือ Pod พร้อมใช้งานอย่างน้อย 1 ตัว
- เพิ่ม HPA ช่วง 2–4 replicas โดยใช้ CPU 70% และ memory 80% เป็นค่าเริ่มต้น
- จำกัด Hikari pool ที่ 5 connections ต่อ Pod และ `minimumIdle=0`
- เพิ่ม automated tests สำหรับ DB readiness recovery และ in-flight request ระหว่าง graceful shutdown

## 2. Probe contract

| Probe | Endpoint | Dependency | ผลเมื่อ database ล่ม |
| --- | --- | --- | --- |
| startup | `/actuator/health/liveness` | process/JVM | ไม่เกี่ยวข้องกับ DB |
| liveness | `/actuator/health/liveness` | `livenessState` | ยังเป็น `UP`; Kubernetes ไม่ restart Pod |
| readiness | `/actuator/health/readiness` | `readinessState`, `db` | เป็น `DOWN`; Service หยุดส่ง traffic เข้า Pod |

เมื่อ database กลับมา HealthIndicator จะตรวจใหม่ตามรอบ readiness probe และ Pod กลับเข้า Service โดยไม่ต้อง restart

## 3. Shutdown timing

```text
Pod enters Terminating
        |
        v
preStop sleep 5s  -> ให้ endpoint removal กระจายไปยัง Service/Ingress
        |
        v
SIGTERM to JVM
        |
        v
Spring graceful shutdown waits up to 30s for in-flight requests
        |
        v
Kubernetes hard limit at 45s
```

ส่วนต่าง 10 วินาทีหลัง `preStop + Spring timeout` เป็น safety margin ก่อน Kubernetes ส่ง SIGKILL

## 4. Replica และ Neon connection budget

ค่าตั้งต้น:

- HPA minimum = 2 Pods
- HPA maximum = 4 Pods
- rolling update `maxSurge` = 1 Pod
- Hikari maximum pool = 5 connections ต่อ Pod
- Hikari minimum idle = 0

งบ connection สูงสุดของ API ระหว่าง rollout:

```text
(maxReplicas + maxSurge) × poolSize
=(4 + 1) × 5
=25 connections
```

ควรมี operational reserve อย่างน้อย 5 connections สำหรับ migration, admin และ incident response ดังนั้น Neon project/endpoint ควรรองรับอย่างน้อย 30 connections ก่อนเปิด HPA ถึง 4 Pods หาก quota จริงต่ำกว่านี้ให้ลด `maxReplicas` หรือ `NEON_POOL_MAX_SIZE` ก่อน deploy ห้ามเพิ่ม replicas โดยไม่คำนวณ budget ใหม่

แนะนำให้ใช้ Neon pooled connection endpoint สำหรับ application Pods และติดตาม active/queued connections ก่อนปรับค่าจาก baseline นี้

## 5. HPA prerequisite

HPA manifest ถูกสร้างไว้แล้ว แต่จะ scale จาก CPU/memory ได้ต่อเมื่อ cluster มี Metrics API เช่น metrics-server หากไม่มี metrics HPA จะรายงาน target เป็น unknown และ Deployment จะยังคงทำงานที่ 2 replicas

## 6. Automated verification

```powershell
.\mvnw.cmd --batch-mode '-Dtest=HealthProbeIntegrationTests,GracefulShutdownIntegrationTests' test
kubectl kustomize infra/kubernetes/overlays/neon
kubectl kustomize infra/kubernetes/overlays/neon/migration
```

Automated tests ยืนยันว่า:

- DB health `DOWN` ทำให้ readiness ตอบ 503
- liveness ยังตอบ 200 ระหว่าง DB outage
- readiness กลับเป็น 200 หลัง DB health ฟื้นโดยไม่สร้าง application context ใหม่
- request ที่กำลังทำงานจบด้วย HTTP 200 ก่อน application shutdown เสร็จ

## 7. Live-cluster acceptance ที่ยังต้องทำ

ต้องใช้ cluster credentials, image SHA จริง และ Secret จริงจึงจะปิด Component A ได้:

1. ตรวจว่า Deployment มี 2 Ready Pods และ Service กระจาย request ถึงทั้งสอง Pod
2. upload ผ่าน Pod หนึ่ง แล้วอ่าน object/record ผ่านอีก Pod
3. block database egress ชั่วคราว แล้วตรวจ readiness DOWN, liveness UP และ restart count ไม่เพิ่ม
4. เปิด database กลับ แล้วตรวจ readiness ฟื้นโดยไม่ restart
5. rollout image SHA ใหม่และยิง request ต่อเนื่อง เพื่อตรวจว่าไม่มี request สำคัญถูกตัด
6. rollout image ที่ readiness ไม่ผ่าน ยืนยันว่า Pod เดิมยังให้บริการ จากนั้น rollback
7. ตรวจ HPA หลังติดตั้ง metrics-server และทำ load test
8. drain node ใน multi-node cluster เพื่อตรวจ topology spread และ PDB

เครื่องปัจจุบัน render manifest และทดสอบ application ได้ แต่ Kubernetes context ตอบว่าไม่มี credentials จึงยังไม่บันทึกผล live-cluster acceptance เป็นผ่าน

## 8. ไฟล์ที่ Component A เป็นเจ้าของ

- `src/main/resources/application.yml`
- `src/test/java/com/mythix/tcg/HealthProbeIntegrationTests.java`
- `src/test/java/com/mythix/tcg/GracefulShutdownIntegrationTests.java`
- `infra/kubernetes/base/deployment.yaml`
- `infra/kubernetes/base/horizontalpodautoscaler.yaml`
- `infra/kubernetes/base/poddisruptionbudget.yaml`
- `infra/kubernetes/base/kustomization.yaml`
