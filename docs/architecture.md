# Backend architecture

## แนวทางหลัก

โปรเจกต์ใช้ **feature-first modular monolith**: โค้ดที่เปลี่ยนเพราะ business capability เดียวกันอยู่ใกล้กัน และแต่ละ feature ค่อยแบ่งเป็น `api`, `application`, `domain`, `persistence` ตามความจำเป็น ไม่สร้าง layer ว่างไว้ล่วงหน้า

```text
com.mythix.tcg
├── auth/                 # login, register, JWT (SCRUM-9)
├── user/                 # profile/contact (SCRUM-6)
├── seller/               # seller upgrade/profile (SCRUM-8)
├── catalog/              # card master data and search
├── product/              # marketplace listings/inventory (SCRUM-7, SCRUM-10)
├── cart/                 # cart lifecycle (SCRUM-11)
├── order/                # checkout, reservation, buyer/seller orders (SCRUM-12, 26, 38)
├── collection/           # personal card collection
└── common/               # cross-cutting config, error model, health
```

## Layer responsibilities

| Layer | หน้าที่ | ห้ามทำ |
| --- | --- | --- |
| `api` | HTTP mapping, request validation, DTO mapping | เรียก repository โดยตรงใน feature ใหม่ |
| `application` | use case, authorization, transaction boundary | ผูกกับ JSON/UI shape โดยไม่จำเป็น |
| `domain` | entity, value object, domain rule | อ้าง controller หรือ transport DTO |
| `persistence` | Spring Data repository และ query | ตัดสินสิทธิ์ของผู้ใช้ |

`CardController` ยังเรียก repository โดยตรงเพราะเป็นโค้ดเดิม งาน feature ถัดไปควรเพิ่ม application service แล้วค่อยย้าย logic โดยไม่รวม refactor ที่ไม่เกี่ยวข้องใน PR เดียวกัน

## Dependency rules

1. `api -> application -> domain`; persistence เป็น adapter ที่ application เรียกผ่าน repository contract
2. feature หนึ่งไม่ควรแก้ entity ของอีก featureโดยตรง ให้เรียก use case/service ของเจ้าของข้อมูล
3. API ใช้ request/response DTO เสมอ ไม่ serialize JPA entity ออกสู่ frontend โดยตรง
4. identity ต้องมาจาก `SecurityContext` หลัง SCRUM-9 ห้ามรับ `userId` หรือ `sellerId` จาก client เพื่อใช้ตัดสิน ownership
5. operation ที่แตะ stock/order หลายแถวต้องมี transaction boundary ใน application service

## Critical flows

### Checkout and stock reservation

`order` เป็นเจ้าของ transaction ของ checkout และต้อง lock/conditional-update `product.on_hand` กับ `product.reserved` เพื่อป้องกัน oversell ห้ามลด stock ใน controller หรือวน update แบบไม่มี transaction

### Seller order view

รายการ seller order ต้องกรองด้วย seller จาก token และคำนวณ revenue/cost/profit จาก snapshot ใน `order_items` ไม่ใช้ราคาปัจจุบันของ product

### Buyer order history

buyer identity ต้องมาจาก token, เรียงล่าสุดก่อน และปฏิเสธการอ่าน order ของบัญชีอื่นด้วย `403 Forbidden` ตาม SCRUM-38

## Database

ไฟล์ bootstrap สำหรับ local PostgreSQL อยู่ที่ `infra/postgres/init`. PostgreSQL รันไฟล์เหล่านี้เฉพาะตอนสร้าง volume ใหม่ จึงไม่ควรใช้เป็น production migration mechanism. ก่อน deploy หลาย environment ให้ย้าย schema evolution ไป Flyway/Liquibase และเก็บ seed data สำหรับ development แยกจาก migration จริง
