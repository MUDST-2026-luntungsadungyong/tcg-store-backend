# 🐳 คู่มือการติดตั้งและใช้งาน Local Database ด้วย Docker Compose
### Mythix TCG Platform (DevOps Task: SCRUM-19 / DO-01)

> เอกสารแนะนำการติดตั้งและใช้งานเซิร์ฟเวอร์ฐานข้อมูล PostgreSQL 16 จำลอง สำหรับนักพัฒนาทุกคนในทีม 8 คน  
> **เหมาะสำหรับผู้เริ่มต้น (DevOps Knowledge = 0)** ใช้งานได้ง่ายด้วยคำสั่งเดียว

---

## 📁 1. โครงสร้างไฟล์และหน้าที่ของแต่ละไฟล์

ระบบจัดการฐานข้อมูลแยก schema migration ออกจากข้อมูลตัวอย่าง:

```text
tcg-store-backend/
├── docker-compose.desktop.yml
├── src/main/resources/db/migration/
│   ├── V1__baseline_schema.sql
│   └── V2__...sql ถึง V9__...sql
└── infra/postgres/init/
    ├── 02-data.sql
    └── 03-profile-avatar.sql
```

| ชื่อไฟล์ | เปรียบเทียบเข้าใจง่าย | หน้าที่ทางเทคนิค |
|---|---|---|
| 📄 **`docker-compose.desktop.yml`** | **"ผู้จัดการร้าน / สวิตช์ไฟ"** | สั่งให้ Docker เปิด PostgreSQL, backend local/cloud, frontend และ S3Mock ตาม service/profile ที่เลือก พร้อม Persistent Volume |
| 📁 **`src/main/resources/db/migration`** | **"พิมพ์เขียวและสมุดประวัติการต่อเติม"** | Flyway รันไฟล์ `V1`–`V9` ตามลำดับ บันทึก checksum ใน `flyway_schema_history` และหยุด backend หาก schema ไม่ถูกต้อง |
| 📄 **`infra/postgres/init/02-data.sql`** | **"รถขนเฟอร์นิเจอร์เข้าบ้าน"** | คำสั่ง `INSERT` ข้อมูลการ์ด Pokémon จริง 2,000 ใบ, บัญชีทดสอบ Buyer/Seller และสินค้าพร้อมขาย เพื่อให้ทีมหยิบไปทดสอบหน้าเว็บได้ทันที |

---

## 📊 2. สรุปข้อมูลตั้งต้น (Seed Data ใน `02-data.sql`)

### 🎴 การ์ด Pokémon แม่บท (ตาราง `cards` รวม 2,000 ใบ)
ข้อมูลดึงมาจาก Official Pokémon TCG API โดยตรง มีระดับความหายากและประเภทคละกันอย่างเป็นธรรมชาติ:
* ⚪ **Rare:** 501 ใบ
* 🟢 **Uncommon:** 448 ใบ
* 🔵 **Rare Holo (การ์ดฟอยล์วิบวับ):** 410 ใบ
* ⚪ **Common (การ์ดทั่วไป):** 385 ใบ
* 🟡 **Promo Cards (การ์ดโปรโมชั่น):** 124 ใบ
* 🟣 **การ์ดระดับสูง (Ultra Rare / EX / GX / V / VMAX / Shiny):** 132 ใบ
* ⚡ **หมวดหมู่การ์ด:** คละทั้ง Pokémon (Basic, Stage 1, Stage 2, SP, EX, VMAX), Trainer (Item, Supporter, Stadium) และ Energy

### 👥 บัญชีผู้ใช้ตัวอย่าง (ตาราง `users`)
รหัสผ่านของทุกบัญชีถูกเข้ารหัสแบบ **BCrypt** ปลอดภัยตามมาตรฐานสากล:

| Role | Email | รหัสผ่านจริง (Plaintext) | คำอธิบาย |
|---|---|---|---|
| **Buyer** | `buyer@mythix.com` | `password123` | ลูกค้าทั่วไป สำหรับทดสอบค้นหาการ์ด, เพิ่มลงตะกร้า, และกดสั่งซื้อ |
| **Buyer (ไม่เคยยื่น Seller)** | `buyer2@mythix.com` | `password123` | บัญชี buyer ใหม่ที่มี `seller_status=NONE` และยังไม่เคยกดขอเป็น seller |
| **Seller** | `seller@mythix.com` | `password123` | ผู้ขายที่ผ่านการ Verify แล้ว มีร้านค้าชื่อ *"Pewter City Card Vault"* สำหรับทดสอบจัดการสต๊อกและดู Dashboard กำไร |
| **Admin** | `admin@mythix.com` | `password123` | ผู้ดูแลระบบสำหรับทดสอบ seller application queue และ approve/reject |

บัญชีข้างต้นเป็น credential สำหรับ local seed เท่านั้น ห้ามนำไปใช้ใน shared หรือ production environment

### 📦 รายการสินค้าลงขายเริ่มต้น (ตาราง `products`)
มีสินค้าลงขายตัวอย่าง **20 รายการ** ผูกกับการ์ดจริง:
* มีสภาพคละกัน: `Mint`, `Near Mint`, `Lightly Played`
* มีภาษา: `EN`, `JP`
* มีราคาขายจริง, ต้นทุนการ์ด (`cost`), และสต๊อกพร้อมขายจริง (`on_hand` 2–20 ใบ)

---

## 🚀 3. ขั้นตอนการรันใช้งานจริง (Step-by-Step)

### ขั้นตอนที่ 1: ตรวจสอบความพร้อมของโปรแกรม
* เปิดโปรแกรม **Docker Desktop** บนคอมพิวเตอร์ของคุณ
* รอจนกว่าไอคอนรูปปลาวาฬที่มุมซ้ายล่างของ Docker Desktop จะเป็น **สีเขียว (Engine Running)**

### ขั้นตอนที่ 2: เริ่ม Local stack
เปิด Terminal (PowerShell หรือ Command Prompt) ในโฟลเดอร์โปรเจกต์:
```powershell
./scripts/use-local.ps1 -Build
```
คำสั่งนี้เปิด PostgreSQL และ S3Mock ก่อน จากนั้นรัน `migrations-local`, ลง seed เฉพาะฐานที่ยังว่าง แล้วจึงเปิด backend และ frontend หาก migration ไม่ผ่าน backend จะไม่เริ่มทำงาน

### ขั้นตอนที่ 3: ตรวจสอบสถานะการทำงาน
พิมพ์คำสั่ง:
```bash
docker compose -f docker-compose.desktop.yml --profile local ps
```
จะเห็นผลลัพธ์แสดงว่า Container ชื่อ `tcg-store-service-postgres-local` มีสถานะเป็น **`Up` (healthy)** บน Port `0.0.0.0:5432->5432/tcp`

---

## 🛠️ 4. ข้อมูลสำหรับเชื่อมต่อ Database (Connection Config)

นำข้อมูลด้านล่างนี้ไปกรอกในโปรแกรมจัดการฐานข้อมูล (DBeaver, pgAdmin, DataGrip, VS Code Database Client) หรือใน Spring Boot Backend:

* **Host:** `localhost`
* **Port:** `5432`
* **Database Name:** `tcg_store`
* **Username:** `postgres`
* **Password:** `postgres`
* **JDBC URL:** `jdbc:postgresql://localhost:5432/tcg_store`

คู่มือนี้อธิบาย local PostgreSQL เท่านั้น หากกำลังใช้ Neon Compose ให้สลับกลับด้วย `./scripts/use-local.ps1 -Build` ซึ่งจะหยุด Neon stack ก่อนเริ่ม local stack ดู [Environment setup](environment-setup.md) สำหรับขั้นตอนสลับ runtime

---

## ⚙️ 5. การตั้งค่าใน Spring Boot Backend (`application.yml`)

โปรเจกต์อ่านค่าจาก environment โดยมีค่า local default ดังนี้:

```yaml
spring:
  datasource:
    url: ${DB_URL:jdbc:postgresql://localhost:5432/tcg_store}
    username: ${DB_USERNAME:postgres}
    password: ${DB_PASSWORD:postgres}
    driver-class-name: org.postgresql.Driver
  jpa:
    hibernate:
      ddl-auto: validate   # ตรวจสอบ Entity ว่าตรงกับ Schema ที่สร้างไว้
    show-sql: ${JPA_SHOW_SQL:false}
    properties:
      hibernate:
        format_sql: true
```

---

## 🛑 6. คำสั่งที่มีประโยชน์ในการใช้งานประจำวัน

| คำสั่ง | ผลลัพธ์ | เมื่อไหร่ที่ควรใช้ |
|---|---|---|
| `docker compose -f docker-compose.desktop.yml --profile local up -d postgres-local` | สตาร์ทฐานข้อมูล local | ตอนเริ่มทำงานในแต่ละวัน |
| `docker compose -f docker-compose.desktop.yml --profile local stop postgres-local` | ปิดฐานข้อมูลโดยเก็บ volume | ตอนเลิกงาน / ปิดคอม |
| `docker compose -f docker-compose.desktop.yml --profile local logs -f postgres-local` | ดูข้อความ log ของ PostgreSQL | ตอนต้องการดีบักปัญหา |
| `docker compose -f docker-compose.desktop.yml --profile local down -v` | **ล้างข้อมูลทั้งหมดใน Compose volumes** | ใช้เฉพาะเมื่อต้องการ reset และยืนยันแล้วว่าสำรองข้อมูลไว้ |
