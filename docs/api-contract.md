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

## Notifications (F-06)

Notifications are authenticated, persistent in-app order events. The API derives the recipient from the Bearer token; clients cannot supply a recipient ID.

### Events and persistence

| Type | Recipient | Created when | Default title |
| --- | --- | --- | --- |
| `ORDER_RESERVED` | Each seller with items in the successfully created order | Checkout commits the order and reserved stock | `New card reservation` |
| `ORDER_STATUS_CHANGED` | The buyer on the order | Seller completes the existing frontend “Mark as sent” action | `Your order has been sent` |

The current frontend translates that seller action to backend status `FULFILLED`; the notification is emitted only when the order first enters that status. The order action and notification are in the same database transaction. Notifications remain stored until the user deletes them (no time-based cleanup).

### Endpoints

All routes below require `Authorization: Bearer <token>` and operate only on the authenticated user's notifications.

| Method and path | Behavior |
| --- | --- |
| `GET /api/notifications?page=0&size=20` | Return the user's notifications ordered by `createdAt DESC, id DESC`. `page` is zero-based; `size` defaults to 20 and must be 1–100. |
| `PATCH /api/notifications/{id}/read` | Set `readAt` once; repeating the request returns the same read timestamp. |
| `DELETE /api/notifications/{id}` | Permanently delete an owned notification; returns `204` on success. |

The list response uses Spring's page envelope: `content`, `totalElements`, `totalPages`, `number`, `size`, `first`, and `last`. Each `content` item is `{ id, type, title, message, orderId, readAt, createdAt }`; `readAt` is `null` until read. IDs are JSON numbers and timestamps are ISO date-times. Recipient IDs are not returned.

### Errors

- `401 UNAUTHENTICATED`: missing, invalid, or expired token.
- `400 VALIDATION_ERROR`: invalid page, size, or notification ID.
- `404 NOT_FOUND`: notification does not exist or belongs to another user (ownership is not disclosed).
- `5xx`: unexpected persistence/server failure; clients must show a retryable error and must not treat mock data as success.

Notifications are stored in the database and inserted in the same transaction as checkout or the seller status action. There is no notification channel outside the in-app inbox and no scheduled retention job.

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

## Public collections (Homepage leaderboard)

Public collection reads are intentionally separate from the authenticated collection mutation API. Only these `GET` routes are public; adding and removing collection items still requires the JWT principal.

| Method/path | Request | Response |
| --- | --- | --- |
| `GET /collections/leaderboard?limit=10` | `limit` is 1–10 | `{ "items": [LeaderboardEntry] }` |
| `GET /collections/{userId}?page=0&size=20` | `page` starts at 0; `size` is 1–100 | `{ "owner": PublicCollector, "items": [], "page": {} }` |

`LeaderboardEntry` contains `rank`, string `userId`, public `displayName`/`avatarUrl`, `rareCardCount`, `distinctRareCardCount`, and up to three card `highlights`. `rareCardCount` is the sum of `collection_items.quantity` for cards whose normalized rarity is a rare tier or `Promo`; `Common`, `Uncommon`, and blank rarity are excluded. Ranking is by total quantity descending, distinct rare cards descending, then user ID ascending.

Public responses never include email, password, phone, contact handles, seller cost, or other private profile fields. An existing user with no items returns an empty collection (`200`); an unknown user returns `PUBLIC_COLLECTION_NOT_FOUND` (`404`). Invalid `limit`, `page`, or `size` returns `VALIDATION_ERROR` (`400`).

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
