# 🐳 คู่มือการติดตั้งและใช้งาน Local Database ด้วย Docker Compose
### Mythix TCG Platform (DevOps Task: SCRUM-19 / DO-01)

> เอกสารแนะนำการติดตั้งและใช้งานเซิร์ฟเวอร์ฐานข้อมูล PostgreSQL 16 จำลอง สำหรับนักพัฒนาทุกคนในทีม 8 คน  
> **เหมาะสำหรับผู้เริ่มต้น (DevOps Knowledge = 0)** ใช้งานได้ง่ายด้วยคำสั่งเดียว

---

## 📁 1. โครงสร้างไฟล์และหน้าที่ของแต่ละไฟล์

ระบบจัดการฐานข้อมูลประกอบด้วย 3 ไฟล์หลักที่ทำงานประสานกัน:

```text
tcg-store-backend/
├── docker-compose.yml
└── infra/postgres/init/
    ├── 01-schema.sql
    └── 02-data.sql
```

| ชื่อไฟล์ | เปรียบเทียบเข้าใจง่าย | หน้าที่ทางเทคนิค |
|---|---|---|
| 📄 **`docker-compose.yml`** | **"ผู้จัดการร้าน / สวิตช์ไฟ"** | สั่งให้ Docker ดึง Image `postgres:16-alpine` มาเปิดเป็น Container โดยไม่ต้องลงโปรแกรม PostgreSQL ลงเครื่องจริง, แมป Port `5432`, และสร้าง Persistent Volume |
| 📄 **`infra/postgres/init/01-schema.sql`** | **"พิมพ์เขียวสร้างบ้าน (โครงสร้าง)"** | สั่งสร้างตารางทั้ง 8 ตารางตาม ER Diagram (`cards`, `users`, `seller_profiles`, `products`, `cart_items`, `orders`, `order_items`, `collection_items`) พร้อม Primary Key, Foreign Key และ Constraint ป้องกันสต๊อกติดลบ |
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
| **Seller** | `seller@mythix.com` | `password123` | ผู้ขายที่ผ่านการ Verify แล้ว มีร้านค้าชื่อ *"Pewter City Card Vault"* สำหรับทดสอบจัดการสต๊อกและดู Dashboard กำไร |

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

### ขั้นตอนที่ 2: เริ่มต้นเปิดฐานข้อมูล
เปิด Terminal (PowerShell หรือ Command Prompt) ในโฟลเดอร์โปรเจกต์:
```bash
docker compose up -d
```
> 💡 **พารามิเตอร์ `-d` (Detached Mode):** สั่งให้เซิร์ฟเวอร์รันในเบื้องหลัง Terminal จะไม่ค้าง สามารถพิมพ์คำสั่งอื่นต่อได้ทันที

### ขั้นตอนที่ 3: ตรวจสอบสถานะการทำงาน
พิมพ์คำสั่ง:
```bash
docker compose ps
```
จะเห็นผลลัพธ์แสดงว่า Container ชื่อ `tcg_postgres` มีสถานะเป็น **`Up` (healthy)** บน Port `0.0.0.0:5432->5432/tcp`

---

## 🛠️ 4. ข้อมูลสำหรับเชื่อมต่อ Database (Connection Config)

นำข้อมูลด้านล่างนี้ไปกรอกในโปรแกรมจัดการฐานข้อมูล (DBeaver, pgAdmin, DataGrip, VS Code Database Client) หรือใน Spring Boot Backend:

* **Host:** `localhost`
* **Port:** `5432`
* **Database Name:** `tcg_store`
* **Username:** `postgres`
* **Password:** `postgres`
* **JDBC URL:** `jdbc:postgresql://localhost:5432/tcg_store`

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
| `docker compose up -d` | สตาร์ทฐานข้อมูลขึ้นมาทำงาน | ตอนเริ่มทำงานในแต่ละวัน |
| `docker compose down` | ปิดฐานข้อมูล (ข้อมูลใน Volume ไม่หาย) | ตอนเลิกงาน / ปิดคอม |
| `docker compose logs -f` | ดูข้อความ Log ของเซิร์ฟเวอร์แบบ Real-time | ตอนต้องการดีบักปัญหา |
| `docker compose down -v` | **ล้างข้อมูลทั้งหมดทิ้งรวมถึง Volume** | เมื่อต้องการ Reset ฐานข้อมูลให้กลับไปเริ่มต้นใหม่เหมือนวันแรก |
