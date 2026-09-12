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
| Cart | `GET /cart`, `POST/PATCH/DELETE /cart/items...` | Bearer | SCRUM-11 |
| Personal collection | `GET/POST /collection`, `DELETE /collection/{id}` | Bearer | SCRUM-28 |

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

## Shopping cart (SCRUM-11)

Cart is scoped to the authenticated user from the JWT/SecurityContext. The API never accepts a client-supplied `userId`.

| Method/path | Request | Response | Notes |
| --- | --- | --- | --- |
| `GET /cart` | none | `CartResponse` | Returns the current user's cart; stale quantities are retained and marked unavailable |
| `POST /cart/items` | `{ "listingId": "42", "quantity": 2 }` | `CartResponse` (`201`) | Adds to an existing line rather than replacing it |
| `PATCH /cart/items/{listingId}` | `{ "quantity": 3 }` | `CartResponse` | Sets the line quantity absolutely |
| `DELETE /cart/items/{listingId}` | none | empty (`204`) | Removes only the current user's line |

`CartResponse` contains `items`, `totalItems`, and `totalAmount`. Each item contains buyer-safe listing fields, string `listingId`/`sellerId`, `price`, `quantity`, `available`, `availableQuantity`, and `subtotal`. Seller `cost` is never returned.

Availability is calculated as `max(0, onHand - reserved)`. Add/update require listing status `SHOW` and enough currently available stock. A cart read does not reserve, decrement, normalize, or otherwise mutate stock or quantity. Checkout, order creation, stock reservation, and cart clearing remain SCRUM-12 scope.

Cart errors use the shared error shape: `CART_NOT_ALLOWED` (403), `LISTING_NOT_FOUND` or `CART_ITEM_NOT_FOUND` (404), and `LISTING_NOT_AVAILABLE` or `INSUFFICIENT_STOCK` (409). Invalid quantities and malformed listing IDs return `VALIDATION_ERROR` (400).

## Personal collection (SCRUM-28)

Collection endpoints always scope records to the authenticated JWT principal; clients do not send a `userId`.

| Method/path | Request | Response |
| --- | --- | --- |
| `GET /collection?page=&size=` | none; `page` starts at 0 and `size` is 1–100 | `{ "items": [], "page": { "page": 0, "size": 20, "totalElements": 0, "totalPages": 0 } }` |
| `POST /collection` | `{ "cardId": "...", "listingId": "42", "condition": "Near Mint", "language": "EN", "quantity": 1 }` | collection item (`201`) |
| `DELETE /collection/{id}` | none | empty (`204`) |

`cardId` resolves a catalog card directly. `listingId` resolves a product listing and then its card. Adding the same card increments its quantity atomically per user; `(user_id, card_id)` is unique. Missing references, invalid pagination, and invalid quantities return `VALIDATION_ERROR` (400). Missing cards/listings return `CARD_NOT_FOUND`/`LISTING_NOT_FOUND` (404).

## Checkout (SCRUM-12)

`POST /orders` checks out every item in the authenticated buyer's current cart. The request body is optional (an empty JSON object is accepted) and must not contain a client-supplied `userId`, total, price, seller, or stock value. Clients should send an `Idempotency-Key` header (maximum 100 characters) when they may retry a request; repeating the same key for the same buyer returns the original order without reserving stock again.

| Method/path | Request | Response |
| --- | --- | --- |
| `POST /orders` | optional `{}` and optional `Idempotency-Key` header | `201 CheckoutResponse` |

The response contains string `id`/listing/seller IDs, `orderNumber`, status `PENDING`, the current cart prices and quantities, `totalAmount`, and an ISO-8601 UTC `createdAt`. Seller cost is persisted in the order-item snapshot for seller/profit flows but is never returned to the buyer. Checkout validates every line after acquiring deterministic pessimistic product locks, increments `reserved`, creates the order and snapshots, then clears the cart in one transaction.

Checkout errors use `CHECKOUT_NOT_ALLOWED` (403), `EMPTY_CART`, `LISTING_NOT_AVAILABLE`, or `INSUFFICIENT_STOCK` (409), and `VALIDATION_ERROR` (400). Any validation or stock failure rolls back the order, reservation, and cart changes together.

For an existing PostgreSQL Docker volume, run the idempotent migration at `infra/postgres/migrations/001_checkout_idempotency.sql` before starting the backend. The compose service mounts it at `/migrations`; for example: `docker compose exec -T postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -f /migrations/001_checkout_idempotency.sql`. Fresh databases receive the same column and constraint from the init schema.

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
