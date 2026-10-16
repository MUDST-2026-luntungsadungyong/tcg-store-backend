# Step 2 — Versioned database migration

สถานะเอกสาร: Implemented; แก้ Boot 4 Flyway integration แล้ว — local fresh/repeat/legacy/restore/checksum rejection/failure gate ผ่าน 6 scenarios; Neon snapshot → local PG18 restore ผ่าน ยังรอ provider-side Neon branch/Kubernetes acceptance ดู [ผล Step 1–2](./kubernetes-step-1-2-live-acceptance-results.md)
Parent plan: [แผนทำ Backend ให้รองรับ Kubernetes และการกระจายโหลด](./kubernetes-backend-distribution-plan.md)

## 1. เป้าหมาย

ให้ schema ของ PostgreSQL เปลี่ยนผ่าน Flyway แบบมีลำดับ ตรวจ checksum ย้อนหลัง รันซ้ำได้ และหยุด deployment ก่อน rollout หาก migration ไม่สำเร็จ โดย migration และ API ต้องใช้ backend image version เดียวกัน

โปรเจกต์ใช้ Spring Boot `4.0.8` ผ่าน `spring-boot-starter-flyway` และ pin Flyway `11.20.2` พร้อม PostgreSQL module ตัว migration runner ต้องพบ Flyway bean และ validate สำเร็จก่อน exit 0

## 2. โครงสร้างที่ใช้งาน

```text
src/main/resources/db/migration/
├── V1__baseline_schema.sql
├── V2__checkout_idempotency.sql
├── V3__seller_approval.sql
├── V4__audit_logs.sql
├── V5__align_audit_log_details_text.sql
├── V6__notifications.sql
├── V7__notification_seller_applications.sql
├── V8__backfill_pending_seller_application_notifications.sql
└── V9__align_legacy_local_schema.sql

infra/postgres/init/
├── 02-data.sql                 # development seed เท่านั้น
└── 03-profile-avatar.sql       # development seed เท่านั้น
```

กฎสำคัญ:

- หลัง merge ห้ามแก้ไฟล์ `V1`–`V9`; schema change ใหม่ต้องเพิ่ม `V10__...sql` ต่อท้าย
- API container ใช้ `FLYWAY_ENABLED=false` และ `ddl-auto=validate`
- migration runner เปิด Flyway ด้วย profile `migration` และปิดตัวเองหลัง Flyway กับ Hibernate validation ผ่าน
- database ที่มี schema เดิมก่อน Flyway จะถูก baseline เป็น version 8 แล้วรัน V9 เพื่อ normalize ชื่อ `cards/products`; ฐานที่มี Flyway history version 9 แล้วไม่ถูกแก้ย้อนหลัง
- database ใหม่จะรัน V1 ถึง V9 ตามลำดับ
- production ห้ามรัน development seed
- migration profile ใช้ค่า JWT/S3 แบบ inert ภายใน process จึงต้องใช้เฉพาะ database credentials และไม่ติดต่อ object storage

## 3. Runtime flow

### Local Docker

```text
postgres-local healthy
        |
        v
migrations-local (backend image + Flyway)
        |
        v
seed-local (development data only)
        |
        v
backend-local
```

`migrations-local` และ `backend-local` build จาก image เดียวกัน ส่วน `seed-local` แยกออกชัดเจนเพื่อไม่ให้ production นำบัญชีทดสอบไปใช้

### Cloud Docker

```text
migrations-cloud (backend image + Neon credentials)
        |
        v
backend-cloud
```

หาก Flyway หรือ Hibernate schema validation ล้มเหลว `backend-cloud` จะไม่เริ่มทำงาน

### Kubernetes

```text
apply migration overlay
        |
        v
wait Job/complete ── failure ──> stop; do not roll out
        |
      success
        v
apply application overlay
        |
        v
wait Deployment rollout
```

Migration Job อยู่ที่ `infra/kubernetes/base/migration/job.yaml` และ Neon migration overlay อยู่ที่ `infra/kubernetes/overlays/neon/migration` ทั้ง Job และ Deployment ใช้ image name/tag ชุดเดียวกัน

## 4. คำสั่งใช้งาน

Local และ Cloud ยังใช้คำสั่งศูนย์กลางเดิม โดย migration จะทำงานอัตโนมัติก่อน backend:

```powershell
.\scripts\use-local.ps1 -Build
.\scripts\use-neon.ps1 -Build
```

Kubernetes release gate:

```powershell
.\scripts\deploy-neon-kubernetes.ps1
```

ก่อนใช้ release gate ต้องมี `tcg-store-backend-secrets` ใน namespace `tcg-store` และต้องแทน `sha-REPLACE_ME` ด้วย immutable Git SHA tag เดียวกันใน application และ migration overlay

## 5. CI acceptance matrix

| Scenario | สิ่งที่ตรวจ |
| --- | --- |
| Fresh database | Flyway รัน V1–V9 ครบและ Hibernate `validate` ผ่าน |
| Repeat migration | รันซ้ำแล้วจำนวน migration ไม่เพิ่มและข้อมูลไม่เสีย |
| Existing schema adoption | จำลอง schema เดิมที่ใช้ `cards/products`, baseline ที่ version 8, รัน V9 และยืนยันว่าชื่อตารางกับข้อมูลถูกย้ายครบ |
| Seed separation | seed ถูกใส่หลัง migration เฉพาะ development flow |
| Backup/restore | `pg_dump`/`pg_restore` แล้วจำนวนข้อมูลหลักตรงกันและ Flyway validate ผ่าน |
| Kubernetes render | application overlay และ migration overlay render ผ่าน |
| Release failure | migration Job ล้มเหลวแล้ว deployment rollout ไม่ถูกเรียก |

## 6. Definition of Done

- [x] ใช้ Flyway migration V1–V9
- [x] ใช้ `ddl-auto=validate`
- [x] รองรับ fresh database และ baseline database เดิม
- [x] แยก development seed ออกจาก production migration
- [x] Local และ Cloud Compose มี migration gate
- [x] Kubernetes มี migration Job ที่ใช้ image เดียวกับ API
- [x] Deployment script รอ migration สำเร็จก่อน rollout
- [x] CI ตรวจ fresh, repeat, baseline upgrade และ backup/restore
- [x] ไม่มี raw SQL migration runner ตัวเก่าใน runtime flow

## 7. การเพิ่ม migration ครั้งถัดไป

1. สร้างไฟล์ `src/main/resources/db/migration/V10__short_description.sql`
2. เขียน migration แบบ forward-only และหลีกเลี่ยง destructive change ใน release เดียวกับ code ที่ยังอ่าน schema เก่า
3. เพิ่ม data backfill แบบแบ่งช่วงหากตารางใหญ่
4. รัน `mvnw verify` และ CI database migration matrix
5. สร้าง backup ก่อน production migration
6. deploy ด้วย migration gate และตรวจ `flyway_schema_history`

## 8. Verification evidence — 2026-10-15

- `mvnw verify`: 180 tests, 0 failures, 0 errors, 2 environment-dependent tests skipped
- Legacy rehearsal: schema ที่มี `cards/products` ถูก baseline ที่ V8, V9 เปลี่ยนเป็น `global_cards/local_cards`, เก็บข้อมูลเดิมครบ และ Hibernate validation ผ่าน
- Local Compose: migration/seed gates จบสำเร็จและฐานเดิม 20 listings ไม่เสียหาย
- Neon: migration gate จบ exit 0 บน PostgreSQL 18.6, Flyway validate version 9 ผ่านโดยไม่มี unsupported-database warning และ backend readiness เป็น `UP`
- Compose Local/Cloud และ Kustomize application/migration overlays render ผ่าน

## 9. Boot 4 integration fix and retest — 2026-10-16

- `spring-boot-starter-flyway` เพิ่ม Boot 4 Flyway integration กลับเข้า runtime; คง `flyway-database-postgresql` และ Flyway `11.20.2`
- Migration runner ต้องพบ Flyway bean และ validate สำเร็จก่อน exit 0
- Real PostgreSQL acceptance ผ่าน 6 scenarios: fresh V1–V9, repeat/seed, baseline V8 → V9, backup/restore, checksum drift rejection และ Compose migration failure gate
- S3 local สอง backend ทดสอบผ่าน migration จริง ใช้ `ddl-auto=validate` ทั้งคู่ ไม่มี Hibernate schema-create fallback
- Neon snapshot → isolated PostgreSQL 18 restore ผ่านทั้ง checksum/counts 8 ตารางและ Flyway validation ด้วย fixed image
- Full Maven suite ผ่าน 197 tests, 0 failures/errors, 2 external-DB skips; ไม่แก้ migration SQL V1–V9
- ยังรอ restore/fresh/upgrade บน Neon branch แยกและ Kubernetes release acceptance ตาม [รายงานรวม](./kubernetes-step-1-2-live-acceptance-results.md)
