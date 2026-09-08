# ⚡ Mythix TCG Platform — Backend API Service (Legacy reference)

> เอกสารนี้เก็บไว้เป็น reference จากโครงเดิม คำสั่งและสถานะปัจจุบันให้ยึด `README.md`, `docs/architecture.md` และ `docs/api-contract.md` ที่ repository root
> **RESTful API Service สำหรับแพลตฟอร์มตลาดการ์ด Pokémon (C2C Marketplace)**  
> พัฒนาด้วย **Spring Boot 3.3.4**, **Java 21 LTS**, **Spring Data JPA**, **Spring Security**, และ **PostgreSQL 16**  
> *คู่มือฉบับนี้เขียนขึ้นสำหรับนักพัฒนาทุกคนในทีม รวมถึงผู้เริ่มต้นที่ไม่มีพื้นฐาน Spring Boot มาก่อน*

---

## 📑 สารบัญ
1. [ภาพรวมของ Spring Boot (สำหรับคนคุ้นเคย Node.js/Express)](#1-ภาพรวมของ-spring-boot)
2. [โครงสร้างโปรเจกต์และหน้าที่ของแต่ละโฟลเดอร์](#2-โครงสร้างโปรเจกต์และหน้าที่ของแต่ละโฟลเดอร์)
3. [สิ่งที่ต้องเตรียมก่อนเริ่มรัน (Prerequisites)](#3-สิ่งที่ต้องเตรียมก่อนเริ่มรัน)
4. [วิธีรันโปรเจกต์ (แบบละเอียดทีละสเต็ป)](#4-วิธีรันโปรเจกต์)
   - [วิธีที่ 1: รันผ่าน IntelliJ IDEA (แนะนำสำหรับเขียนโค้ด)](#วิธีที่-1-รันผ่าน-intellij-idea-แนะนำ)
   - [วิธีที่ 2: รันผ่าน Terminal / Command Line](#วิธีที่-2-รันผ่าน-terminal--command-line)
5. [ทดสอบยิง API (Endpoints & Examples)](#5-ทดสอบยิง-api)
6. [การเชื่อมต่อฐานข้อมูลและการตั้งค่า (`application.yml`)](#6-การเชื่อมต่อฐานข้อมูลและการตั้งค่า)
7. [ปัญหาที่พบบ่อยและวิธีแก้ (Troubleshooting)](#7-ปัญหาที่พบบ่อยและวิธีแก้)

---

## 1. ภาพรวมของ Spring Boot

### 💡 Spring Boot คืออะไร?
Spring Boot คือเฟรมเวิร์กยอดนิยมอันดับ 1 ของภาษา Java สำหรับสร้าง Web API ระดับองค์กร (Enterprise) ที่มีระบบความปลอดภัยสูง เสถียร และมีเครื่องมือจัดการฐานข้อมูลในตัว

### 🔄 เปรียบเทียบกับ Node.js (Express) ให้เห็นภาพทันที:
| คอนเซปต์ | Node.js (Express) | Spring Boot (Java) | หน้าที่ |
|---|---|---|---|
| **ตัวเปิด Server** | `app.listen(8080)` ใน `server.js` | `TcgStoreBackendApplication.java` | จุดเริ่มต้นของโปรแกรม เปิดพอร์ต 8080 |
| **ตัวรับ Route/URL** | `app.use('/api', router)` | `@RestController` + `@RequestMapping` | รับ HTTP Request (GET, POST, ฯลฯ) |
| **จัดการตาราง DB** | Prisma Schema / Mongoose Model | **JPA Entity (`@Entity`, `@Table`)** | แปลงแถวใน Database ให้เป็น Object ใน Java |
| **คำสั่งคิวรี DB** | `prisma.card.findMany()` | **`JpaRepository` (`findAll()`, `save()`)** | คำสั่งดึง/บันทึกข้อมูลสำเร็จรูป ไม่ต้องเขียน SQL เอง |
| **ไฟล์แพ็กเกจ** | `package.json` | `pom.xml` (Maven) | ประกาศ Library และ Dependencies |
| **ไฟล์ตัวแปรระบบ** | `.env` | `application.yml` | เก็บค่า Host, Port, User/Password ฐานข้อมูล |

---

## 2. โครงสร้างโปรเจกต์และหน้าที่ของแต่ละโฟลเดอร์

โปรเจกต์ถูกออกแบบตามมาตรฐานสากล **3-Tier Architecture** เพื่อแยกหน้าที่ของโค้ดให้ชัดเจน:

```text
tcg-store-backend/
├── pom.xml                                  # รายชื่อ Dependencies (Spring Web, JPA, PostgreSQL, Lombok)
├── mvnw / mvnw.cmd                          # Maven Wrapper สำหรับสั่งรัน/บิลด์โดยไม่ต้องลง Maven ในเครื่อง
├── src/
│   ├── main/
│   │   ├── java/com/mythix/tcg/
│   │   │   ├── TcgStoreBackendApplication.java  # [Main Entrypoint] ตัวเปิดเซิร์ฟเวอร์หลัก
│   │   │   ├── config/                          # [ตั้งค่าระบบ]
│   │   │   │   └── SecurityConfig.java          # ปลดล็อก CORS และอนุญาตให้ Frontend เรียก /api/** ได้
│   │   │   ├── entity/                          # [Database Models] คลาสแทน 8 ตารางในฐานข้อมูล
│   │   │   │   ├── User.java                    # ตาราง users
│   │   │   │   ├── SellerProfile.java           # ตาราง seller_profiles
│   │   │   │   ├── Card.java                    # ตาราง cards (การ์ดแม่บท 2,000 ใบ)
│   │   │   │   ├── Product.java                 # ตาราง products (สต๊อกสินค้าที่ผู้ขายลง)
│   │   │   │   ├── CartItem.java                # ตาราง cart_items (ตะกร้าสินค้า)
│   │   │   │   ├── Order.java                   # ตาราง orders (ใบสั่งซื้อ)
│   │   │   │   ├── OrderItem.java               # ตาราง order_items (รายการสินค้าในคำสั่งซื้อ)
│   │   │   │   └── CollectionItem.java          # ตาราง collection_items (สมุดสะสมการ์ด)
│   │   │   ├── repository/                      # [Database Queries] คลาสสำหรับคิวรีข้อมูล
│   │   │   │   ├── CardRepository.java          # ค้นหาการ์ด, กรองตามชื่อ, กรองตามความหายาก
│   │   │   │   └── UserRepository.java          # ค้นหาผู้ใช้ตาม Email
│   │   │   └── controller/                      # [HTTP Routers] จุดรับส่ง API กับ Frontend
│   │   │       ├── HealthController.java        # GET /api/health เช็กสถานะเซิร์ฟเวอร์
│   │   │       └── CardController.java          # GET /api/cards ดึงข้อมูลการ์ด 2,000 ใบ
│   │   └── resources/
│   │       └── application.yml                  # ค่าคอนฟิกเชื่อมต่อ PostgreSQL 5432
│   └── test/
│       └── java/com/mythix/tcg/
│           └── TcgStoreBackendApplicationTests.java # Automated Test ทดสอบโหลด Application Context
```

---

## 3. สิ่งที่ต้องเตรียมก่อนเริ่มรัน

1. **Docker Desktop:** ต้องเปิดและรัน Container ฐานข้อมูล `tcg_postgres` ไว้ที่พอร์ต `5432`  
   *(หากยังไม่ได้รัน ให้ไปที่โฟลเดอร์ `Docker_compose_DB` แล้วพิมพ์ `docker compose up -d`)*
2. **Java 21 LTS:** ติดตั้งแล้วในเครื่อง (หรือเปิดผ่าน IntelliJ ที่ติดตั้ง Java 21 อัตโนมัติ)

---

## 4. วิธีรันโปรเจกต์

### วิธีที่ 1: รันผ่าน IntelliJ IDEA (แนะนำ)

1. เปิดโปรแกรม **IntelliJ IDEA**
2. ไปที่แถบโครงสร้างโฟลเดอร์ด้านซ้ายมือ แล้วดับเบิ้ลคลิกเปิดไฟล์:  
   👉 `src/main/java/com/mythix/tcg/TcgStoreBackendApplication.java`
3. สังเกตที่บรรทัด `public class TcgStoreBackendApplication` หรือบรรทัด `public static void main`:
   * จะมี **ไอคอนลูกศรสีเขียว (▶)** อยู่หน้าบรรทัด
   * ให้ **คลิกที่ปุ่มสีเขียว ▶** แล้วเลือก **`Run 'TcgStoreBackendApplication'`**  
   *(หรือกดคีย์ลัด `Shift + F10` บนคีย์บอร์ด)*
4. สังเกตหน้าต่าง Run Console ด้านล่าง:
   * จะเห็นรูปโลโก้ Spring Boot
   * เมื่อขึ้นข้อความ:
     ```text
     Tomcat started on port 8080 (http) with context path '/'
     Started TcgStoreBackendApplication in 3.126 seconds
     ```
   * แปลว่า **เซิร์ฟเวอร์ Backend รันสำเร็จ พร้อมใช้งานแล้ว!**
5. หากต้องการหยุดรัน ให้กด **ปุ่มสี่เหลี่ยมสีแดง (■ Stop)** ที่มุมบนขวาหรือในหน้าต่าง Run Console

---

### วิธีที่ 2: รันผ่าน Terminal / Command Line

หากต้องการรันผ่าน PowerShell หรือ Terminal ให้พิมพ์คำสั่งตามนี้:

```powershell
# 1. เข้าไปที่โฟลเดอร์โปรเจกต์ Backend
cd c:\Devops_NEW\tcg-store-backend\tcg-store-backend

# 2. สั่งรันด้วย Maven Wrapper
.\mvnw.cmd spring-boot:run
```

*(หากต้องการหยุดรัน ให้กด `Ctrl + C` ใน Terminal)*

#### คำสั่งรัน Automated Test:
```powershell
.\mvnw.cmd test
```
คำสั่งนี้จะทำการทดสอบอัตโนมัติ โดยเชื่อมต่อกับฐานข้อมูลและตรวจเช็กว่าทั้ง 8 ตารางตรงกับโค้ด Java หรือไม่ หากผ่านจะขึ้นคำว่า `BUILD SUCCESS`

---

## 5. ทดสอบยิง API (Endpoints & Examples)

เมื่อเซิร์ฟเวอร์เปิดอยู่ (พอร์ต 8080) คุณสามารถเปิดเบราว์เซอร์ (Chrome/Edge) หรือใช้ Postman ทดสอบยิง API ได้ทันที:

### 1. เช็กสถานะความพร้อมของเซิร์ฟเวอร์ (Health Check)
* **URL:** `http://localhost:8080/api/health`
* **Method:** `GET`
* **ตัวอย่างผลลัพธ์ (JSON):**
  ```json
  {
    "status": "UP",
    "message": "Mythix TCG Backend is running healthy!",
    "database": "Connected",
    "total_cards_in_db": 2000
  }
  ```

### 2. ดึงรายชื่อการ์ดโปเกมอน (แบ่งหน้า หน้าละ 20 ใบ)
* **URL:** `http://localhost:8080/api/cards`
* **Method:** `GET`
* **พารามิเตอร์เสริม (Query Params):**
  * `page`: หน้าที่ต้องการ (เริ่มต้นที่ 0) เช่น `http://localhost:8080/api/cards?page=1`
  * `size`: จำนวนการ์ดต่อหน้า เช่น `http://localhost:8080/api/cards?size=10`
  * `search`: ค้นหาชื่อการ์ด เช่น `http://localhost:8080/api/cards?search=Charizard`
* **ตัวอย่างผลลัพธ์ (JSON):**
  ```json
  {
    "content": [
      {
        "id": "base1-4",
        "name": "Charizard",
        "supertype": "Pokémon",
        "subtypes": "Stage 2",
        "hp": "120",
        "types": "Fire",
        "rarity": "Rare Holo",
        "setName": "Base",
        "cardNumber": "4",
        "imageSmall": "https://images.pokemontcg.io/base1/4.png",
        "imageLarge": "https://images.pokemontcg.io/base1/4_hires.png",
        "marketPrice": 45000.00
      }
    ],
    "totalElements": 2000,
    "totalPages": 100
  }
  ```

### 3. ดึงข้อมูลการ์ดรายใบตาม ID
* **URL:** `http://localhost:8080/api/cards/pop4-4`
* **Method:** `GET`

---

## 6. การเชื่อมต่อฐานข้อมูลและการตั้งค่า (`application.yml`)

ไฟล์ตั้งค่าอยู่ที่ `src/main/resources/application.yml`:

```yaml
server:
  port: 8080                           # พอร์ตที่ Spring Boot จะเปิดให้บริการ

spring:
  application:
    name: tcg-store-backend
  datasource:
    url: jdbc:postgresql://localhost:5432/tcg_store   # ที่อยู่ Database ใน Docker
    username: postgres                               # Username
    password: postgres                               # Password
    driver-class-name: org.postgresql.Driver
  jpa:
    hibernate:
      ddl-auto: validate               # สำคัญมาก: สั่งให้ Spring Boot ตรวจสอบว่า Entity ตรงกับ Database จริงไหม
    show-sql: true                     # แสดงคำสั่ง SQL ใน Console เวลาดึงข้อมูล
    open-in-view: false
```

> 🔒 **ความปลอดภัยของ `ddl-auto: validate`:**  
> เราตั้งค่าเป็น `validate` เพื่อป้องกันไม่ให้ Spring Boot ไปลบหรือแก้โครงสร้างตารางใน Database โดยพลการ แต่จะทำหน้าที่ตรวจสอบความถูกต้องของ Entity ให้แทน

---

## 7. ปัญหาที่พบบ่อยและวิธีแก้ (Troubleshooting)

### ❓ ปัญหาที่ 1: `Connection to localhost:5432 refused`
* **สาเหตุ:** Container ฐานข้อมูลใน Docker ยังไม่ได้เปิด หรือ Docker Desktop ปิดอยู่
* **วิธีแก้:**  
  1. เปิดโปรแกรม Docker Desktop
  2. เปิด Terminal แล้วพิมพ์:
     ```bash
     docker start tcg_postgres
     ```
  3. จากนั้นลองรัน Spring Boot ใหม่อีกครั้ง

---

### ❓ ปัญหาที่ 2: `Port 8080 is already in use`
* **สาเหตุ:** มีโปรแกรมอื่นหรือ Spring Boot ตัวเก่าค้างอยู่ที่พอร์ต 8080
* **วิธีแก้:**  
  1. สั่งปิด Process ที่ค้างใน Terminal:
     ```powershell
     Stop-Process -Id (Get-NetTCPConnection -LocalPort 8080).OwningProcess -Force
     ```
  2. หรือเปลี่ยนพอร์ตในไฟล์ `application.yml` จาก `8080` เป็น `8081`

---

### ❓ ปัญหาที่ 3: IntelliJ ฟ้องเส้นสีแดงใต้ฟังก์ชัน เช่น `.getId()` หรือ `.getName()`
* **สาเหตุ:** IntelliJ ยังไม่ได้เปิดใช้งาน Annotation Processing ของ Library Lombok
* **วิธีแก้:**  
  1. ใน IntelliJ ไปที่เมนู **File** ➔ **Settings** (หรือกด `Ctrl + Alt + S`)
  2. ค้นหาคำว่า **"Annotation Processors"** (อยู่ใต้ `Build, Execution, Deployment` > `Compiler`)
  3. ติ๊กถูกที่ช่อง **`[✔] Enable annotation processing`** แล้วกด **OK**
