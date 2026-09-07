# 📘 คู่มือการสร้าง REST API ด้วย Spring Boot
### (ฉบับเทียบเคียงสำหรับนักพัฒนาที่คุ้นเคยกับ Node.js / Express)

> เอกสารคู่มือสำหรับทีมพัฒนา เพื่อใช้เป็นแนวทางในการเขียน API ใหม่ (GET, POST, PUT, DELETE)  
> อธิบายเปรียบเทียบกับหลักการของ **Express.js** ที่คุ้นเคย พร้อมตัวอย่างโค้ดจริงที่หยิบไปใช้ได้ทันที

---

## 📑 สารบัญ
1. [ตารางเทียบคำศัพท์ Express (Node.js) vs Spring Boot (Java)](#1-ตารางเทียบคำศัพท์-express-vs-spring-boot)
2. [Flow การทำงานของ 1 Request (ทำไมต้องแยก 3 ชั้น?)](#2-flow-การทำงานของ-1-request)
3. [DTO คืออะไร? ทำไม Java ต้องมี Class มารับ `req.body`?](#3-dto-คืออะไร-ทำไม-java-ต้องมี-class-มารับ-reqbody)
4. [คู่มือทีละสเต็ป: การสร้าง API แบบ POST (ตัวอย่าง: เพิ่มสินค้าลงตะกร้า)](#4-คู่มือทีละสเต็ป-การสร้าง-api-แบบ-post)
5. [Checklist สรุป: เวลาจะเพิ่ม API ใหม่ 1 ตัว ต้องทำอะไรบ้าง?](#5-checklist-สรุป-เวลาจะเพิ่ม-api-ใหม่-1-ตัว)

---

## 1. ตารางเทียบคำศัพท์ Express vs Spring Boot

| สิ่งที่คุณทำใน Express (Node.js) | สิ่งที่ใช้ใน Spring Boot (Java) | หน้าที่ / การใช้งาน |
|---|---|---|
| `const router = express.Router()` | `@RestController`<br>`@RequestMapping("/api/...")` | ประกาศว่าคลาสนี้คือตัวรับส่ง Route |
| `router.get('/path', ...)` | **`@GetMapping("/path")`** | ดักฟัง HTTP GET (ดึงข้อมูล) |
| `router.post('/path', ...)` | **`@PostMapping("/path")`** | ดักฟัง HTTP POST (สร้างข้อมูลใหม่) |
| `router.put('/path', ...)` | **`@PutMapping("/path")`** | ดักฟัง HTTP PUT (อัปเดตข้อมูลทั้งก้อน) |
| `router.delete('/path', ...)` | **`@DeleteMapping("/path")`** | ดักฟัง HTTP DELETE (ลบข้อมูล) |
| `req.body` | **`@RequestBody`** | ดึงข้อมูล JSON จาก Body มาแปลงเป็น Java Object |
| `req.params.id` | **`@PathVariable`** | ดึงค่าตัวแปรจาก URL เช่น `/api/cards/{id}` |
| `req.query.search` | **`@RequestParam`** | ดึง Query String เช่น `/api/cards?search=pikachu` |
| `res.status(200).json(data)` | **`ResponseEntity.ok(data)`** | ส่งข้อมูลกลับพร้อม HTTP Status 200 |
| `res.status(201).json(data)` | **`ResponseEntity.status(201).body(data)`** | ส่งข้อมูลกลับพร้อม HTTP Status 201 (Created) |
| `res.status(404).json(...)` | **`ResponseEntity.notFound().build()`** | ส่งรหัส 404 (Not Found) |
| `res.status(400).json(...)` | **`ResponseEntity.badRequest().body(...)`** | ส่งรหัส 400 (Bad Request) |

---

## 2. Flow การทำงานของ 1 Request

ใน Express หลายคนมักเขียนทุกอย่างลงใน `router.post()` ไฟล์เดียว (ทั้งเช็กค่า, คิดเลข, ยิง SQL)  
แต่ใน **Spring Boot ระดับมาตรฐานสากล** เราจะแบ่งหน้าที่เด็ดขาดเป็น **3 ชั้น**:

```text
[Frontend / Client]
        │
        ▼ (1) ยิง HTTP POST /api/cart พร้อม JSON
┌─────────────────────────────────────────────────────────────┐
│ 1. Controller Layer (`controller/CartController.java`)       │
│    -> ดักรับ Request (@PostMapping)                         │
│    -> ดึงข้อมูล JSON จาก Body (@RequestBody)               │
│    -> เช็กความถูกต้องเบื้องต้น                              │
└──────────────────────────────┬──────────────────────────────┘
                               │ (2) ส่งต่อข้อมูล
                               ▼
┌─────────────────────────────────────────────────────────────┐
│ 2. Service Layer (`service/CartService.java`)               │
│    -> [สมองของระบบ] จัดการ Business Logic ทั้งหมด           │
│    -> เช็กสต๊อก: "สินค้าเหลือพอไหม?"                        │
│    -> คิดคำนวณราคา, เช็กสิทธิ์ผู้ใช้                        │
└──────────────────────────────┬──────────────────────────────┘
                               │ (3) เรียกคำสั่งฐานข้อมูล
                               ▼
┌─────────────────────────────────────────────────────────────┐
│ 3. Repository Layer (`repository/CartItemRepository.java`)   │
│    -> คุยกับ Database โดยตรงผ่าน Spring Data JPA             │
│    -> สั่ง save(), findById(), delete()                     │
└──────────────────────────────┬──────────────────────────────┘
                               │ (4) บันทึกลงตารางจริง
                               ▼
              [PostgreSQL Database (Docker : 5432)]
```

---

## 3. DTO คืออะไร? ทำไม Java ต้องมี Class มารับ `req.body`?

* ใน **Node.js (JavaScript)**: เป็นภาษาที่ไม่มี Type ทำให้เราหยิบ `req.body.productId` มาใช้ได้ทันที แต่ข้อเสียคือ ถ้า Frontend ส่งพิมพ์ผิด เช่น ส่ง `product_id` มา โค้ดจะพังตอนรัน (Runtime Error)
* ใน **Spring Boot (Java)**: เป็นภาษา Type-safe เราจึงสร้างคลาสตัวแทนข้อมูล เรียกว่า **DTO (Data Transfer Object)** ขึ้นมา เพื่อกำหนดว่า JSON ที่ส่งเข้ามาต้องมีหน้าตาอย่างไร:

```java
// DTO สำหรับรับข้อมูลเพิ่มสินค้าลงตะกร้า
@Data
public class AddToCartRequest {
    private Long productId;
    private Integer quantity;
}
```
> เมื่อ Frontend ยิง `{ "productId": 5, "quantity": 2 }` เข้ามา Spring Boot จะตรวจเช็ก Type และแปลงเป็น Object ให้ทันที ถ้าส่งผิดประเภท (เช่น ส่งตัวหนังสือมาในช่องตัวเลข) ระบบจะดักจับและฟ้อง Error ให้ก่อนที่โค้ดจะพัง

---

## 4. คู่มือทีละสเต็ป: การสร้าง API แบบ POST
*(ตัวอย่างจริง: ระบบเพิ่มสินค้าลงตะกร้า `POST /api/cart`)*

### สเต็ปที่ 1: สร้าง DTO สำหรับรับข้อมูล (`dto/AddToCartRequest.java`)
สร้างคลาสเพื่อบอกว่าต้องรับค่าอะไรจาก Frontend บ้าง:
```java
package com.mythix.tcg.dto;

import lombok.Data;

@Data
public class AddToCartRequest {
    private Long userId;
    private Long productId;
    private Integer quantity;
}
```

---

### สเต็ปที่ 2: สร้าง Repository สำหรับสั่งบันทึก (`repository/CartItemRepository.java`)
สร้าง Interface สื่อสารกับ Database (Spring Data JPA มีคำสั่ง `save()` ให้ใช้อัตโนมัติ):
```java
package com.mythix.tcg.repository;

import com.mythix.tcg.entity.CartItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CartItemRepository extends JpaRepository<CartItem, Long> {
    // ฟังก์ชันเสริม: ค้นหาว่ามีสินค้านี้ในตะกร้าของ user คนนี้แล้วหรือยัง
    Optional<CartItem> findByUserIdAndProductId(Long userId, Long productId);
}
```

---

### สเต็ปที่ 3: สร้าง Service สำหรับคิด Logic (`service/CartService.java`)
ตรวจเช็กสต๊อกก่อนเพิ่มลงตะกร้า:
```java
package com.mythix.tcg.service;

import com.mythix.tcg.dto.AddToCartRequest;
import com.mythix.tcg.entity.CartItem;
import com.mythix.tcg.entity.Product;
import com.mythix.tcg.entity.User;
import com.mythix.tcg.repository.CartItemRepository;
import com.mythix.tcg.repository.ProductRepository;
import com.mythix.tcg.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CartService {

    private final CartItemRepository cartItemRepository;
    private final ProductRepository productRepository;
    private final UserRepository userRepository;

    @Transactional
    public CartItem addToCart(AddToCartRequest request) {
        // 1. ตรวจสอบว่าสินค้ามีอยู่จริงไหม
        Product product = productRepository.findById(request.getProductId())
                .orElseThrow(() -> new RuntimeException("ไม่พบสินค้าชิ้นนี้"));

        // 2. ตรวจสอบสต๊อกสินค้า
        int availableStock = product.getOnHand() - product.getReserved();
        if (availableStock < request.getQuantity()) {
            throw new RuntimeException("สินค้าในสต๊อกไม่เพียงพอ");
        }

        // 3. ตรวจสอบผู้ใช้
        User user = userRepository.findById(request.getUserId())
                .orElseThrow(() -> new RuntimeException("ไม่พบผู้ใช้งาน"));

        // 4. บันทึกลงตาราง cart_items
        CartItem cartItem = CartItem.builder()
                .user(user)
                .product(product)
                .quantity(request.getQuantity())
                .build();

        return cartItemRepository.save(cartItem);
    }
}
```

---

### สเต็ปที่ 4: สร้าง Controller สำหรับเปิด Route (`controller/CartController.java`)
เปิด URL ให้ Frontend ยิงเข้ามาได้ (เหมือน `router.post`):
```java
package com.mythix.tcg.controller;

import com.mythix.tcg.dto.AddToCartRequest;
import com.mythix.tcg.entity.CartItem;
import com.mythix.tcg.service.CartService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/cart")
@RequiredArgsConstructor
public class CartController {

    private final CartService cartService;

    // เทียบเท่ากับ router.post('/', (req, res) => { ... })
    @PostMapping
    public ResponseEntity<?> addToCart(@RequestBody AddToCartRequest request) {
        try {
            CartItem savedItem = cartService.addToCart(request);
            // เทียบเท่า res.status(201).json(savedItem)
            return ResponseEntity.status(HttpStatus.CREATED).body(savedItem);
        } catch (Exception e) {
            // เทียบเท่า res.status(400).json({ error: e.message })
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }
}
```

---

## 5. Checklist สรุป: เวลาจะเพิ่ม API ใหม่ 1 ตัว

ทุกครั้งที่ได้รับมอบหมายงานใน Jira ให้สร้าง Endpoint ใหม่ ให้ไล่ทำตามลำดับนี้:

* [ ] **1. สร้าง/ตรวจสอบ Entity:** มีตารางและคลาสใน `entity/` รองรับข้อมูลแล้วหรือยัง?
* [ ] **2. สร้าง DTO (ถ้ามี):** ถ้าเป็น `POST` หรือ `PUT` ต้องรับ JSON หน้าตาแบบไหน? สร้างคลาสรอไว้ในโฟลเดอร์ `dto/`
* [ ] **3. สร้าง Repository:** มี Interface ใน `repository/` สำหรับคิวรีตารางนี้แล้วหรือยัง?
* [ ] **4. เขียน Logic ใน Service:** เขียนฟังก์ชันตรวจสอบเงื่อนไขทางธุรกิจใน `service/`
* [ ] **5. เปิด Route ใน Controller:** ใช้ `@GetMapping`, `@PostMapping`, `@PutMapping`, หรือ `@DeleteMapping` ที่โฟลเดอร์ `controller/`
* [ ] **6. ทดสอบ:** เปิด Postman หรือเบราว์เซอร์ ยิงทดสอบไปที่ `http://localhost:8080/api/...`
