# Mythix TCG Store — Backend

Backend API สำหรับแพลตฟอร์มซื้อขายการ์ด TCG ของ Mythix พัฒนาด้วย Spring Boot, Java 21 และ PostgreSQL โครงสร้างปัจจุบันจัดตาม business feature เพื่อรองรับงาน Backend ใน Jira และเชื่อมกับ `tcg-store-frontend` โดยไม่ให้ controller, entity และ repository ของทุก feature ปะปนกัน

[![CI](https://github.com/MUDST-2026-luntungsadungyong/tcg-store-backend/actions/workflows/ci.yml/badge.svg)](https://github.com/MUDST-2026-luntungsadungyong/tcg-store-backend/actions/workflows/ci.yml)

## สถานะของโปรเจกต์

| พื้นที่ | สถานะปัจจุบัน |
| --- | --- |
| Foundation | Maven project อยู่ที่ repository root, Java 21, Spring Boot 3.3.4 |
| Database | PostgreSQL schema + development seed และ Docker Compose พร้อมใช้ |
| API ที่ทำงานแล้ว | `GET /api/health`, `GET /api/cards`, `GET /api/cards/{id}` |
| Authentication | มี BCrypt bean และ security boundary; JWT/register/login ยังเป็น SCRUM-9 |
| Marketplace | entity มีแล้ว; product browse และ seller CRUD ยังเป็น SCRUM-7/SCRUM-10 |
| Cart/checkout/order | entity มีแล้ว; use cases ยังเป็น SCRUM-11/SCRUM-12/SCRUM-26/SCRUM-38 |
| Quality gates | GitHub Actions รัน Maven verify และตรวจ PR title ตาม Conventional Commits; test profile ใช้ H2 เพื่อให้ CI ไม่พึ่ง external DB |

> ตารางนี้ตั้งใจแยก “มีโครงสร้างแล้ว” ออกจาก “feature เสร็จแล้ว” เพื่อไม่ให้ frontend เข้าใจว่า endpoint จาก Jira พร้อมใช้งานก่อนเวลา

## Tech stack

- Java 21 และ Spring Boot 3.3.4
- Spring Web, Validation, Data JPA และ Security
- PostgreSQL 16 สำหรับ local/runtime
- H2 เฉพาะ automated test
- Lombok และ Maven Wrapper
- Docker Compose และ GitHub Actions

## เริ่มใช้งาน

### Requirements

- Java 21
- Docker Desktop หรือ PostgreSQL 16
- Git

### Setup

```bash
git clone https://github.com/MUDST-2026-luntungsadungyong/tcg-store-backend.git
cd tcg-store-backend
```

สร้างไฟล์ environment จากตัวอย่าง (Docker Compose จะอ่าน `.env` อัตโนมัติ):

```powershell
Copy-Item .env.example .env
```

Compose จะหยุดทันทีหากไม่มี APP_JWT_SECRET; ค่าที่อยู่ใน .env.example เป็นค่า development เท่านั้นและต้องเปลี่ยนก่อนใช้ใน shared/production

หรือบน macOS/Linux:

```bash
cp .env.example .env
```

เริ่มต้นด้วย backend และ PostgreSQL ใน local Docker (คำสั่งนี้จะสั่ง migration สำหรับ volume เดิมก่อนเริ่ม backend):

```bash
docker compose -f docker-compose.desktop.yml --profile local up --build -d postgres-local backend-local frontend
docker compose -f docker-compose.desktop.yml ps
```

จากนั้นตรวจ `http://localhost:8080/api/health` (หรือ port ที่ตั้งใน `SERVER_PORT`)

Compose เก็บ PostgreSQL data ใน `tcg-store-service_postgres_data` และเก็บรูปผ่าน S3Mock ใน `tcg-store-service_s3mock_data` โดย S3Mock จะเปิดอัตโนมัติพร้อม Local backend

เริ่ม Cloud backend ที่ใช้ Neon ให้ปิด local backend ก่อน (ทั้งคู่ใช้ host port 8080):

```powershell
docker compose -f docker-compose.desktop.yml --profile cloud up --build -d backend-cloud frontend
```

Local backend ใช้ S3Mock เสมอและไม่มี filesystem fallback ส่วน Cloud backend ใช้ S3-compatible Object Storage จากค่า `OBJECT_STORAGE_*`; ห้ามเปิด Local และ Cloud backend พร้อมกันเพราะใช้ host port 8080 ร่วมกัน

หากต้องการรัน backend จาก source แทน Docker ให้เปิด PostgreSQL และ S3Mock:

```bash
docker compose -f docker-compose.desktop.yml --profile local up -d postgres-local s3mock
```

แล้วรัน backend:

```powershell
$env:OBJECT_STORAGE_ENDPOINT="http://localhost:9090"
$env:OBJECT_STORAGE_REGION="us-east-1"
$env:OBJECT_STORAGE_BUCKET="tcg-product-images"
$env:OBJECT_STORAGE_PUBLIC_BASE_URL="http://localhost:9090/tcg-product-images"
$env:OBJECT_STORAGE_PATH_STYLE="true"
$env:OBJECT_STORAGE_ACCESS_KEY="s3mock-local-access"
$env:OBJECT_STORAGE_SECRET_KEY="s3mock-local-secret"
.\mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=dev
```

macOS/Linux ให้ export ค่า `OBJECT_STORAGE_*` ชุดเดียวกัน แล้วใช้ `./mvnw spring-boot:run -Dspring-boot.run.profiles=dev` จากนั้นตรวจ `http://localhost:8080/api/health`

> PostgreSQL รันไฟล์ใน `infra/postgres/init` เฉพาะครั้งแรกที่สร้าง volume หากแก้ schema แล้วใช้ volume เดิม การเปลี่ยนแปลงจะไม่ถูกรันซ้ำ

หากกำลังใช้ Neon ให้สลับไป local ด้วย `./scripts/use-local.ps1 -Build` ซึ่งจะหยุด Neon stack ก่อนเริ่ม local stack หากต้องการ Neon ให้ใช้ `./scripts/use-neon.ps1 -Build` หลัง `neon login`; ดู [Environment setup](docs/environment-setup.md) สำหรับขั้นตอนสลับ runtime

## เชื่อมกับ frontend

ใน `tcg-store-frontend/.env`:

```dotenv
VITE_API_BASE_URL=http://localhost:8080/api
VITE_ENABLE_MOCK_API=false
```

backend อนุญาต CORS จาก `http://localhost:5173` เป็นค่าเริ่มต้น หาก frontend รันจาก origin อื่นให้ตั้ง `APP_CORS_ALLOWED_ORIGINS`. รายการ endpoint/field ที่ตรวจเทียบจาก frontend จริงอยู่ใน [Frontend–backend API contract](docs/api-contract.md)

เมื่อรัน Spring Boot ตรงจาก shell ค่า database และ object storage ต้องอยู่ใน process environment; Spring Boot ไม่อ่านไฟล์ `.env` เอง ส่วน Docker Compose จะจัดค่า local S3Mock หรือ Neon Object Storage ให้ตาม profile ที่เลือก

## Environment variables

| Variable | Default | รายละเอียด |
| --- | --- | --- |
| `SERVER_PORT` | `8080` | HTTP port |
| `DB_URL` | `jdbc:postgresql://localhost:5432/tcg_store` | JDBC URL |
| `DB_USERNAME` | `postgres` | application DB user |
| `DB_PASSWORD` | `postgres` | application DB password; ต้องเปลี่ยนใน shared/prod environment |
| `POSTGRES_DB` | `tcg_store` | local Compose database |
| `POSTGRES_USER` | `postgres` | local Compose user |
| `POSTGRES_PASSWORD` | `postgres` | local Compose password |
| `POSTGRES_PORT` | `5432` | host port ของ PostgreSQL |
| `OBJECT_STORAGE_ENDPOINT` | required | S3-compatible endpoint; Local Compose ใช้ S3Mock และ Cloud ใช้ Neon Object Storage |
| `OBJECT_STORAGE_REGION` | `us-east-2` | region สำหรับ S3 client |
| `OBJECT_STORAGE_BUCKET` | `tcg-product-images` | bucket สำหรับรูปสินค้าที่อัปโหลด |
| `OBJECT_STORAGE_PUBLIC_BASE_URL` | required | public URL prefix ของ bucket |
| `OBJECT_STORAGE_PATH_STYLE` | `true` | ใช้ path-style URL สำหรับ S3-compatible provider |
| `OBJECT_STORAGE_ACCESS_KEY` | required | S3 access key; ห้าม commit |
| `OBJECT_STORAGE_SECRET_KEY` | required | S3 secret key; ห้าม commit |
| `APP_CORS_ALLOWED_ORIGINS` | `http://localhost:5173` | comma-separated trusted origins |
| `APP_JWT_SECRET` | required (or explicit `dev` profile) | HMAC secret for signing JWT; startup fails fast when missing outside the local `dev` profile |
| `APP_JWT_EXPIRATION_MINUTES` | `60` | JWT expiration time in minutes |
| `APP_LOG_LEVEL` | `INFO` | log level ของ `com.mythix.tcg` |
| `JPA_SHOW_SQL` | `false` | แสดง SQL สำหรับ debug local |

ห้าม commit `.env` หรือ secret จริงลง repository

## โครงสร้างโปรเจกต์

```text
tcg-store-backend/
├── .github/workflows/ci.yml
├── docs/
│   ├── architecture.md
│   ├── api-contract.md
│   └── jira-backend-scope.md
├── infra/postgres/init/       # local schema + development seed
├── src/main/java/com/mythix/tcg/
│   ├── auth/                  # SCRUM-9 boundary
│   ├── user/                  # profile/contact
│   ├── seller/                # seller profile/upgrade
│   ├── catalog/               # card master data
│   ├── product/               # marketplace listing/inventory
│   ├── cart/                  # shopping cart
│   ├── order/                 # checkout and order lifecycle
│   ├── collection/            # personal collection
│   └── common/                # config and health
├── src/test/
├── docker-compose.desktop.yml
├── Dockerfile
├── pom.xml
└── CONTRIBUTING.md
```

รายละเอียด dependency rule และ transaction boundary อยู่ใน [Backend architecture](docs/architecture.md)

## คำสั่งหลัก

| Command | ใช้ทำอะไร |
| --- | --- |
| `.\mvnw.cmd spring-boot:run` | รัน application บน Windows |
| `.\mvnw.cmd test` | รัน unit/integration tests ด้วย test profile |
| `.\mvnw.cmd verify` | รัน quality gate เดียวกับ CI |
| `docker compose -f docker-compose.desktop.yml --profile local up -d postgres-local backend-local frontend` | เปิด Local PostgreSQL, backend และ frontend |
| `docker compose -f docker-compose.desktop.yml --profile cloud up -d backend-cloud frontend` | เปิด backend ที่เชื่อม Neon และ frontend |
| `docker compose -f docker-compose.desktop.yml --profile local logs -f backend-local` | ดู Local backend logs |
| `docker compose -f docker-compose.desktop.yml --profile local logs -f postgres-local` | ดู PostgreSQL logs |
| `docker compose -f docker-compose.desktop.yml --profile local --profile cloud stop` | หยุดทุก service โดยเก็บ volume |

สำหรับสลับ runtime ให้ใช้ helper ใน [Environment setup](docs/environment-setup.md) ซึ่งเลือก services จาก Compose file กลางเดียว

## Jira scope และ API contract

- [Jira backend scope](docs/jira-backend-scope.md) — backend tickets, priority, module mapping และ open decisions
- [Frontend–backend API contract](docs/api-contract.md) — endpoints และ field mapping จาก frontend ปัจจุบัน
- [Backend architecture](docs/architecture.md) — package rules, ownership และ critical transaction flows
- [Local database guide](docs/local-database.md) — รายละเอียดฐานข้อมูลเดิม
- [Environment setup](docs/environment-setup.md) — สร้าง `.env`, ขอสิทธิ์/credential อย่างปลอดภัย และเลือก local, Neon หรือ TiDB
- [Component D implementation status](docs/kubernetes-component-d-implementation-status.md) — logs/metrics, release workflows, ผลทดสอบ และ live acceptance ที่ยังเหลือ
- [Kubernetes release operations](docs/kubernetes-component-d-release-operations.md) — platform inputs, staging artifact/promotion, monitoring evidence และ recovery boundaries
- [Contributing guide](CONTRIBUTING.md) — branch, code, test และ PR conventions

## Known gaps

- JWT authentication/filter ยังไม่ถูก implement; endpoint ที่ต้อง auth จึงยังไม่พร้อม
- controller เดิมของ card master ยังคืน JPA entity/Spring `Page` โดยตรง ควรเปลี่ยนเป็น DTO/envelope ตอนทำ SCRUM-10
- Flyway migration gate, probes/graceful shutdown และ hardened multi-arch image มี implementation แล้ว; Kubernetes environments/secure edge อยู่ใน [Component C](docs/kubernetes-component-c-platform-edge-plan.md) และยังต้องผ่าน acceptance บน cluster จริง
- Jira กับ frontend ยังมีชื่อบางจุดไม่ตรงกัน (`USER`/`CUSTOMER`, `/orders/my-orders`/`/orders/me`, `totalPrice`/`totalAmount`) และถูกบันทึกเป็น open decisions ใน API contract

## การร่วมพัฒนา

อ่าน [CONTRIBUTING.md](CONTRIBUTING.md) ก่อนเริ่มงาน ทุก PR ต้องอ้าง Jira key, จำกัด scope ต่อ feature, เพิ่ม test ตาม acceptance criteria และรัน `mvnw verify` ผ่านก่อนขอ review
