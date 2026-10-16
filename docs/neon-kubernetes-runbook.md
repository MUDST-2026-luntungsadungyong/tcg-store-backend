# Neon PostgreSQL + Kubernetes runbook

## เป้าหมาย

ใช้ Neon เป็น PostgreSQL และ S3-compatible Object Storage โดย backend รันได้ทั้งบน local Docker และ Kubernetes ผ่าน environment variables ชุดเดียวกัน

Neon ไม่ใช่ database dialect ใหม่สำหรับระบบนี้: backend ใช้ PostgreSQL JDBC driver และ `ddl-auto: validate` เหมือน local PostgreSQL การย้ายไป Neon จึงเป็นการย้าย PostgreSQL endpoint ไม่ใช่การย้ายไป TiDB/MySQL

## สิ่งที่เพิ่มใน repository

- `src/main/resources/application-neon.yml` บังคับใช้ `NEON_JDBC_URL`, `NEON_DB_USERNAME` และ `NEON_DB_PASSWORD` โดยไม่มี local fallback
- `.env.example` เป็นรายการตัวแปรรวมสำหรับการรันจากเครื่อง และ `.env` เป็นไฟล์ runtime เพียงไฟล์เดียว
- Spring Boot Actuator health probes ที่ `/actuator/health/liveness` และ `/actuator/health/readiness`
- `infra/kubernetes/base` เป็น Deployment, Service และ ServiceAccount ที่ใช้กับ cluster ใดก็ได้
- `infra/kubernetes/overlays/neon` เป็น overlay สำหรับ Neon โดยไม่ใส่ credential จริง
- Product image runtime ใช้ S3 เท่านั้น; Local profile เปิด S3Mock อัตโนมัติและ Cloud/Kubernetes ใช้ Neon Object Storage

## Shared development database

ทีมใช้ Neon project `hidden-block-50177803` และ branch `development` ร่วมกัน ข้อมูล seed ถูกลงไว้ที่ branch นี้แล้ว จึงไม่ต้องรันไฟล์ seed ซ้ำในแต่ละเครื่อง สมาชิกแต่ละคนต้องถูกเพิ่มเข้า Neon project ก่อน แล้วรัน:

```powershell
neon login
./scripts/use-neon.ps1 -Build
```

สคริปต์จะ link ไปที่ branch `development` และดึงทั้ง PostgreSQL กับ Object Storage credentials ลง `.env` ซึ่งถูก ignore จาก Git ห้ามส่ง credential ผ่าน commit, pull request หรือแชทสาธารณะ

ตรวจว่าเชื่อมฐานกลางสำเร็จด้วย:

```powershell
docker compose -f docker-compose.desktop.yml ps backend-cloud
Invoke-RestMethod http://localhost:8080/actuator/health/readiness
```

ถ้าต้องกลับไป local ให้ใช้ helper ซึ่งหยุด Neon Compose ก่อนเริ่ม local stack:

```powershell
./scripts/use-local.ps1 -Build
```

## ขั้นตอน bootstrap Neon ครั้งแรก

1. สร้าง Neon project และ branch ที่ตั้งใจใช้เป็น shared development หรือ staging
2. คัดลอก connection details ลงไฟล์ `.env` ที่ไม่ commit โดยอ้างอิง `.env.example` และเติม `sslmode=require`
3. เปิด Cloud stack เพื่อให้ `migrations-cloud` รัน Flyway ก่อน backend:

```powershell
neon login
./scripts/use-neon.ps1 -Build
```

schema อยู่ใน `src/main/resources/db/migration` และ Flyway บันทึกผลใน `flyway_schema_history` ห้ามแก้ migration version ที่ apply แล้ว ให้เพิ่ม `V10`, `V11`, ... เท่านั้น Development seed ใน `infra/postgres/init` ไม่ถูกรันกับ Neon อัตโนมัติและห้ามใช้ใน production

4. สร้าง bucket `tcg-product-images` ใน Neon Object Storage และกำหนด visibility เป็น `public_read` เพราะระบบคืน stable public URL ให้ frontend
5. รัน `neon env pull --service postgres --service object-storage --file .env` แล้วให้ `prepare-neon-env.ps1` map ค่า `AWS_*` เป็น `OBJECT_STORAGE_*`
6. ตรวจ schema ด้วย `./mvnw --batch-mode verify` และรัน backend ด้วย `SPRING_PROFILES_ACTIVE=neon`
7. ตรวจ `GET /actuator/health/readiness`, `GET /api/health` และทดสอบ upload/read/delete object ก่อนให้ frontend ชี้มายัง endpoint ใหม่

## รัน backend จาก source โดยใช้ Neon

ก่อนรัน backend จาก source ให้เตรียม `.env` จาก branch กลางหลัง `neon login` แล้วสั่ง helper นี้เพื่อหยุด backend container ที่ใช้งานอยู่และเริ่ม Spring ด้วย `neon` profile:

```powershell
./scripts/setup-shared-neon.ps1
./scripts/start-neon-local.ps1
```

## เลือก runtime ระหว่าง local Docker กับ Neon

- Local Docker PostgreSQL: ใช้ `.env` ไฟล์เดียว แล้วรัน `./scripts/use-local.ps1 -Build`
- Neon cloud database ใน Docker: ใช้ `.env` ไฟล์เดียว แล้วรัน `./scripts/use-neon.ps1 -Build`
- Neon เมื่อรัน backend จาก source: เตรียม `.env` แล้วรัน `./scripts/start-neon-local.ps1`

ทั้งสอง helper ใช้ `docker-compose.desktop.yml` และหยุด backend ตัวอื่นก่อน เพราะ backend ใช้ port `8080` ร่วมกัน การเปิด PostgreSQL container ไม่ได้ทำให้ backend เปลี่ยนไป Neon อัตโนมัติ

ถ้าต้องการรัน Compose เอง ให้ใช้ helper ตามลำดับนี้:

```powershell
# Local Docker PostgreSQL + S3Mock + backend + frontend
./scripts/use-local.ps1 -Build

# Neon shared development in Docker
neon login
./scripts/use-neon.ps1 -Build
```

ไฟล์ `.env` มี credential และถูก ignore จาก Git ห้าม commit หรือแชร์ไฟล์นี้

ทั้งสองทางใช้ backend image และ PostgreSQL driver ชุดเดียวกัน ต่างกันเฉพาะ profile/connection environment เท่านั้น ห้ามรัน compose สองชุดพร้อมกันบน port เดียวกัน

## Deploy บน Kubernetes

ใช้ overlays แยก environment จาก Component C เป็นเส้นทางหลัก:

- `infra/kubernetes/overlays/local` — Kubernetes local พร้อม optional [PostgreSQL/S3Mock dependencies](./kubernetes-local-dependencies.md)
- `infra/kubernetes/overlays/staging` — namespace `tcg-store-staging`
- `infra/kubernetes/overlays/production` — namespace `tcg-store-production`

ดู [deployment inputs และ acceptance](./kubernetes-component-c-acceptance.md) และ [platform contract](./kubernetes-component-c-platform-contract.md) ก่อน render/deploy ต้องรับ published immutable digest จาก Component B แล้ว inject ให้ API กับ migration จาก release input เดียวกัน

สร้าง Secret นอก Git ตาม key contract รวมถึง `neon-migration-*` สำหรับ direct migration endpoint และ migration role แยกจาก runtime role ใช้ Secret provider หรือ protected env file; ห้าม include `secret.example.yaml` ใน release

Deploy adapter `scripts/deploy-kubernetes.ps1` รับ rendered bundle และบังคับระบุ context/environment/release ID รอ migration Job สำเร็จก่อน apply API และไม่ลบ Job เก่าโดยอัตโนมัติ Production ต้องผ่าน schema, policy และ admission validation ก่อน deploy

`overlays/neon` และ `scripts/deploy-neon-kubernetes.ps1` คงไว้เป็น legacy compatibility สำหรับ namespace `tcg-store` เท่านั้น ไม่ใช่เส้นทาง production ใหม่ และยังมี placeholder ที่ต้องแก้ก่อนใช้

## ขอบเขตและ rollback

- local Docker PostgreSQL ยังเป็น rollback environment และไม่ถูกเปลี่ยนโดย profile Neon
- `ddl-auto` ยังคงเป็น `validate`; application จะไม่แก้ schema อัตโนมัติ
- รูป seller ใช้ Neon Object Storage เท่านั้น; Pod ไม่มี uploads volume และจะไม่ fallback ไป filesystem
- ก่อน migration ให้ export schema/data และบันทึก Neon branch ที่ใช้เป็น staging
- rollback ระดับ application ทำด้วย `kubectl rollout undo deployment/tcg-store-backend -n tcg-store`
- rollback ระดับ database ให้ restore ไป Neon branch/backup ที่ยืนยันแล้ว; ห้ามใช้ `ddl-auto=update` หรือแก้ schema ย้อนกลับแบบ ad-hoc

## งานถัดไป

1. สร้าง Neon staging branch แยกจาก shared development และทำ backup/restore rehearsal
2. เพิ่ม S3 upload/read/delete acceptance test ใน CI environment ที่มี bucket ทดสอบ
3. รัน Component A live-cluster acceptance สำหรับ probes, graceful rollout, rollback และ HPA
4. ทดสอบ Component C Ingress/TLS และ NetworkPolicy บน cluster/CNI จริงหลังมี DNS และ issuer
