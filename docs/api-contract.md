# Frontend–backend API contract

เอกสารนี้ยึด client จริงใน `tcg-store-frontend/src/api` เป็น integration baseline และเทียบกับ Jira snapshot วันที่ 2026-09-08. Base URL ฝั่ง frontend คือ `http://localhost:8080/api` ดังนั้น path ด้านล่างไม่ใส่ `/api` ซ้ำ

## Canonical endpoints

| Capability | Method/path | Auth | สถานะ backend |
| --- | --- | --- | --- |
| Health | `GET /health` | Public | มีแล้ว |
| Card master search | `GET /cards?page=&size=&search=` | Public | มีแล้ว (internal/catalog API) |
| Register | `POST /auth/register` | Public | Implemented on `feat/scrum-9-auth-api` |
| Login | `POST /auth/login` | Public | Implemented on `feat/scrum-9-auth-api` |
| Current user | `GET /auth/me` | Bearer | Implemented on `feat/scrum-9-auth-api` |
| Forgot/reset password | `POST /auth/forgot-password`, `POST /auth/reset-password` | Public/token | frontend มี client แต่ Jira backend ยังไม่กำหนด |
| Update profile | `PUT /users/{userId}` | Bearer/owner | SCRUM-6; ก่อน implement ควรเปลี่ยนเป็น `/users/me` เพื่อลด IDOR risk |
| Browse products | `GET /products`, `GET /products/{id}` | Public | Implemented on `feat/scrum-10-browse-products-api` |
| Seller inventory | `GET/POST /seller/products` | Bearer/Seller | SCRUM-7 |
| Seller listing | `PUT/DELETE /seller/products/{id}` | Bearer/owner | SCRUM-7 |
| Listing visibility | `PATCH /seller/products/{id}/status` | Bearer/owner | SCRUM-7 |
| Checkout | `POST /orders` | Bearer | SCRUM-12 |
| Buyer orders | `GET /orders/me` | Bearer/owner | SCRUM-38; Jira ระบุ `/orders/my-orders` จึงต้องตกลงชื่อก่อน implement |
| Buyer order detail | `GET /orders/{id}` | Bearer/owner | SCRUM-38 |
| Seller orders/profit | `/seller/orders...` | Bearer/Seller | SCRUM-26; endpoint ย่อยยังไม่ล็อก |

`/admin/*` มี client placeholder ใน frontend แต่ไม่อยู่ใน Jira backend scope ชุดนี้ จึงยังไม่ถือเป็น committed contract

## Shared values

| Concept | Canonical API value | หมายเหตุ |
| --- | --- | --- |
| User role | `CUSTOMER`, `SELLER`, `ADMIN` | frontend ใช้ `CUSTOMER`; DB/Jira บางจุดยังใช้ `USER` ต้อง map หรือ migrate ก่อนปล่อย API |
| Product status | `SHOW`, `HIDE` | ตรงกับ frontend และ schema |
| Order status | `PENDING`, `FULFILLED`, `CANCELLED` | ตรงกับ frontend/Jira |
| IDs | JSON string | แม้ DB ใช้ `BIGINT` ให้ serialize เป็น string-compatible value เพื่อไม่ผูก frontend กับชนิด DB |
| Money | JSON number, scale 2 | backend ใช้ `BigDecimal`; ห้ามคำนวณเงินจริงด้วย floating point |
| Date/time | ISO-8601 UTC | ตัวอย่าง `2026-09-01T10:30:00Z` |

## DTO mapping rules

- `User.fullName` ใน persistence ส่งเป็น `name` เพื่อให้ตรง frontend
- available stock ส่งเป็น `stock = onHand - reserved`; ห้ามเปิดให้ clientแก้ `reserved` โดยตรง
- product image ส่งเป็น `image`; เลือก `customImageUrl` ก่อน แล้ว fallback ไป card master image
- order total ส่งเป็น `totalAmount`; SCRUM-38 ใช้คำว่า `totalPrice` จึงควร normalize ก่อน implement
- response ของ list ต้องเลือก envelope เดียวทั้งระบบ เช่น `{ "data": { "items": [], "page": {} } }`; ห้ามสลับระหว่าง raw array, Spring `Page`, และ custom object โดยไม่บันทึก contract

## Error shape

Feature ใหม่ควรตอบรูปแบบเดียวกัน:

```json
{
  "code": "VALIDATION_ERROR",
  "message": "Request validation failed",
  "fieldErrors": { "email": "must be a valid email" },
  "traceId": "optional-correlation-id"
}
```

ขั้นต่ำต้องแยก `400` validation, `401` unauthenticated, `403` forbidden, `404` missing resource และ `409` conflict/duplicate/active-order constraint
