# Component A — Preflight ที่เริ่มดำเนินการจริง

วันที่ 2026-10-16 (Asia/Bangkok) · repo `C:\Users\User\tcg-store-backend`
สถานะ: PREFLIGHT COMPLETE / LIVE TESTS BLOCKED

เริ่มงานตามแผนด้วย read-only diagnostics แล้ว ไม่มี fault injection, load,
Pod deletion, schema/data modification หรือ infrastructure provisioning
เว็บเดิมยังทำงาน Docker Compose cloud profile เหมือนเดิม

## หลักฐานที่ตรวจได้

| ส่วน | ผลจริง |
|---|---|
| kubectl | Client v1.36.1 ติดตั้งแล้ว |
| Kubernetes contexts | รายการว่าง, current-context is not set จึงไม่มี cluster สำหรับทดสอบ |
| Docker Desktop | Engine 29.8.0, 12 CPUs, memory ประมาณ 7.61 GiB |
| Current runtime | frontend และ backend-cloud healthy; ไม่ใช่ 2 Kubernetes replicas |
| Liveness/readiness | HTTP 200 / UP ทั้งคู่ใน single-instance snapshot |
| Metrics endpoint | HTTP 200; Hikari total 1, active 0, idle 1, max 5, pending 0, timeouts 0 ใน snapshot |
| Metric labels | environment=local ตาม Desktop configuration ไม่ใช่ staging release evidence |
| DB read-only | transaction_read_only=on; client TLS ยืนยันได้ |
| DB version | PostgreSQL 18.6 |
| DB configured server limit | max_connections 901, superuser reserved 4, reserved_connections 0 |
| Role/database limits | -1 ทั้งคู่ คือไม่ได้กำหนด connection cap แยกในสอง fields นี้ |
| DB snapshot count | client connections 5 รวม observer 1; active 1, idle 4; non-observer 4 |
| Application connection route | direct endpoint ไม่ใช่ pooler; ยังไม่เปลี่ยน endpoint |
| Neon CLI metadata | branches list ล้มด้วย authorization/internal error ไม่ได้เรียก login หรือแก้ linkage |
| Disk | C: ว่างประมาณ 2.95 GiB, D: ประมาณ 777.16 GiB |

Server slots หลังหัก reserved แบบ conservative = 897 แต่ยังไม่ใช่ provider/project
quota ที่ยืนยัน scope แล้ว หรือ budget สำหรับ test branch/compute ใหม่
ตัวเลข connections ข้างต้นเป็น snapshot ไม่ใช่ peak ระหว่าง HPA/rollout
ไม่มีการอ้างว่า A-07 ผ่านจาก 901 หรือจากสูตร 25 connections

Endpoint metrics ที่อ่านเป็น raw application snapshot ไม่ใช่ collector-backed
600s observation และไม่พบ tcg_neon_connections_used/limit บน raw endpoint นี้
ยังไม่ได้ยืนยัน external quota exporter, Prometheus หรือ log collector ของ staging

## สถานะ test cases

- A-00: BLOCKED — ยังเริ่ม baseline 2 Ready Pods + collectors 600s ไม่ได้
- A-01–A-06: NOT TESTED — shared-state, outage, SIGTERM, rollout, rollback และ HPA/load
- A-07: UNKNOWN — ไม่มี measured peak, terminating-Pod samples และ bound ของ workload อื่น
- A-08: NOT TESTED — ยังไม่มี multi-node test cluster

ไม่เปลี่ยน readiness/liveness configuration เพื่อทำให้ผลดูผ่าน
ไม่ตัด connection หรือ suspend Neon development ร่วมที่ Desktop ใช้อยู่

## สิ่งที่ต้องเปิดให้ทำต่อ

1. ใช้ existing staging context ที่เข้าถึงได้ หรืออนุมัติสร้าง Kubernetes lab แยก
   บน Docker Desktop พร้อม 2 worker nodes, Metrics API และ collectors
   การสร้าง lab ต้องตรวจ memory capacity ก่อน โดยเฉพาะ 4 Pods + rollout overlap
2. จัด Neon branch/compute แยกและ object fixture scope ที่อนุมัติแล้ว
   Neon CLI ตอนนี้อ่าน branch metadata ไม่ผ่าน จึงต้องแก้การยืนยันตัวตน/สิทธิ์
   ผ่านช่องทางเดิม ไม่ส่ง API key/password ในแชต
3. ผูก baseline/candidate image digests, fixture identities และ recovery configuration
   กับ run_id ก่อน fault/load; ยืนยัน budget จาก compute ที่จะใช้ทดสอบจริงอีกครั้ง

Multi-worker lab บนเครื่องเดียวพิสูจน์ Pod scheduling/drain ได้ แต่ไม่ใช่หลักฐาน
ว่า service รอดจาก physical-host outage
การตั้ง cluster/Neon branch ใหม่ยังไม่ได้ทำ เพราะเป็น infrastructure expansion
ที่แผนกำหนดให้ได้รับอนุมัติก่อน

## Evidence

- `.local/pr39-work/component-a-preflight.json` — sanitized results, timestamp UTC
- `.local/pr39-work/component-a-preflight-readonly.py` — read-only diagnostic script
- `kubectl config get-contexts`, `kubectl config current-context`, client version output
- Neon branches-list authorization error โดยไม่มี credential values ในรายงาน

คำสั่งตรวจใช้ SELECT/BEGIN READ ONLY ผ่าน PostgreSQL client container ชั่วคราว
ที่ถูกลบอัตโนมัติ ไม่สร้าง service/volume ใหม่ ไม่ใช้ secret บน command line
ไม่ได้ commit/push และยังไม่ปิด Component A
