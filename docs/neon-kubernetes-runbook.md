# Neon PostgreSQL + Kubernetes runbook

## เป้าหมาย

ใช้ Neon เป็น PostgreSQL cloud database ระยะแรก และให้ backend รันได้ทั้งบน local Docker และ Kubernetes โดยเปลี่ยนเฉพาะ environment variables ไม่เปลี่ยน entity หรือ repository layer

Neon ไม่ใช่ database dialect ใหม่สำหรับระบบนี้: backend ใช้ PostgreSQL JDBC driver และ `ddl-auto: validate` เหมือน local PostgreSQL การย้ายไป Neon จึงเป็นการย้าย PostgreSQL endpoint ไม่ใช่การย้ายไป TiDB/MySQL

## สิ่งที่เพิ่มใน repository

- `src/main/resources/application-neon.yml` บังคับใช้ `NEON_JDBC_URL`, `NEON_DB_USERNAME` และ `NEON_DB_PASSWORD` โดยไม่มี local fallback
- `.env.example` เป็นรายการตัวแปรรวมสำหรับการรันจากเครื่อง
- Spring Boot Actuator health probes ที่ `/actuator/health/liveness` และ `/actuator/health/readiness`
- `infra/kubernetes/base` เป็น Deployment, Service และ ServiceAccount ที่ใช้กับ cluster ใดก็ได้
- `infra/kubernetes/overlays/neon` เป็น overlay สำหรับ Neon โดยไม่ใส่ credential จริง

## ขั้นตอน bootstrap Neon ครั้งแรก

1. สร้าง Neon project และ database ชื่อ `tcg_store` ใน branch ที่ตั้งใจใช้เป็น staging
2. คัดลอก connection details ลงไฟล์ `.env` ที่ไม่ commit โดยอ้างอิง `.env.example` และเติม `sslmode=require`
3. รัน schema และข้อมูลทดสอบตามลำดับนี้กับ database ที่ว่าง:

```text
infra/postgres/init/01-schema.sql
infra/postgres/init/02-data.sql       # ใช้เฉพาะ staging/demo; production ไม่ควร seed ชุดนี้
infra/postgres/init/03-profile-avatar.sql
infra/postgres/migrations/001_checkout_idempotency.sql
infra/postgres/migrations/002_revision5_seller_approval.sql
infra/postgres/migrations/003_audit_logs.sql
```

การรันต้องใช้ `psql` ที่ติดตั้งในเครื่องหรือ PostgreSQL client container และต้องตรวจชื่อ database/branch ก่อนทุกครั้ง ห้ามใส่ password ใน command history หรือ commit ไฟล์ที่มีค่าจริง

4. ตรวจ schema ด้วย `./mvnw --batch-mode verify` และรัน backend ด้วย `SPRING_PROFILES_ACTIVE=neon`
5. ตรวจ `GET /actuator/health/readiness` และ `GET /api/health` ก่อนให้ frontend ชี้มายัง endpoint ใหม่

## รัน local จาก profile Neon

PowerShell ตัวอย่าง:

```powershell
$env:SPRING_PROFILES_ACTIVE = "neon"
$env:NEON_JDBC_URL = "jdbc:postgresql://<host>/<database>?sslmode=require&channelBinding=require"
$env:NEON_DB_USERNAME = "<role>"
$env:NEON_DB_PASSWORD = "<password>"
$env:APP_JWT_SECRET = "<random-secret-at-least-32-bytes>"
./mvnw spring-boot:run
```

## เลือก runtime ระหว่าง local Docker กับ Neon

- Local Docker PostgreSQL: ใช้ `.env` จาก `.env.example` แล้วรัน `docker compose up -d`
- Neon cloud database: ใช้ `.env` ไฟล์เดียวกับ local แล้วรัน `docker compose -f docker-compose.neon.yml up -d --build`

หลัง `neon deploy` ถ้า CLI สร้าง `.env.local` ให้สร้าง cloud runtime env ที่ Spring ใช้ด้วยคำสั่งนี้ก่อน:

```powershell
./scripts/prepare-neon-env.ps1
docker compose -f docker-compose.neon.yml up -d --build
```

ไฟล์ `.env` มี credential และถูก ignore จาก Git ห้าม commit หรือแชร์ไฟล์นี้

ทั้งสองทางใช้ backend image และ PostgreSQL driver ชุดเดียวกัน ต่างกันเฉพาะ profile/connection environment เท่านั้น ห้ามรัน compose สองชุดพร้อมกันบน port เดียวกัน

## Deploy บน Kubernetes

1. Build และ push image ด้วย immutable SHA tag ผ่าน workflow เดิม
2. แก้ `newTag` ใน `infra/kubernetes/overlays/neon/kustomization.yaml` ให้เป็น tag ที่ push แล้ว
3. สร้าง Secret จากเครื่องมือจัดการ secret ของ cluster; ห้ามใช้ `secret.example.yaml` เป็น production secret:

```powershell
kubectl create namespace tcg-store
kubectl -n tcg-store create secret generic tcg-store-backend-secrets `
  --from-literal=neon-jdbc-url="$env:NEON_JDBC_URL" `
  --from-literal=neon-db-username="$env:NEON_DB_USERNAME" `
  --from-literal=neon-db-password="$env:NEON_DB_PASSWORD" `
  --from-literal=app-jwt-secret="$env:APP_JWT_SECRET"
```

4. แก้ origin ใน `configmap.yaml` แล้วตรวจ manifest:

```powershell
kubectl kustomize infra/kubernetes/overlays/neon
kubectl apply -k infra/kubernetes/overlays/neon
kubectl -n tcg-store rollout status deployment/tcg-store-backend
kubectl -n tcg-store get pods,svc
```

ถ้าใช้ External Secrets หรือ Sealed Secrets ใน cluster จริง ให้เปลี่ยน `secret.example.yaml` เป็น resource ของเครื่องมือนั้นก่อน deploy

## ขอบเขตและ rollback

- local Docker PostgreSQL ยังเป็น rollback environment และไม่ถูกเปลี่ยนโดย profile Neon
- `ddl-auto` ยังคงเป็น `validate`; application จะไม่แก้ schema อัตโนมัติ
- รูป seller ที่เก็บใน local filesystem เป็น ephemeral บน Kubernetes overlay ชุดนี้ ควรย้ายไป object storage ก่อนเปิดใช้งานจริง
- ก่อน migration ให้ export schema/data และบันทึก Neon branch ที่ใช้เป็น staging
- rollback ระดับ application ทำด้วย `kubectl rollout undo deployment/tcg-store-backend -n tcg-store`
- rollback ระดับ database ให้ restore ไป Neon branch/backup ที่ยืนยันแล้ว; ห้ามใช้ `ddl-auto=update` หรือแก้ schema ย้อนกลับแบบ ad-hoc

## งานถัดไป

1. รับ Neon connection string และสร้าง staging database จริง
2. ทำ idempotent migration runner (Flyway หรือ migration Job แยก) ก่อนให้ Kubernetes เป็น production path
3. เพิ่ม object storage สำหรับ seller images และแยก liveness/readiness smoke test ใน CI
4. เพิ่ม ingress/TLS หลังมี hostname จริงของ backend และ frontend
