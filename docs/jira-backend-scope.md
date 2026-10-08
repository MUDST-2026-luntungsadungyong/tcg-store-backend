# Jira backend scope

Snapshot จาก Jira board `SCRUM Sprint 0` วันที่ 2026-09-08 ใช้เพื่อจัด module และ integration boundary เท่านั้น สถานะล่าสุดให้ดูใน Jira เสมอ

| Jira | Priority/status ณ วันที่อ่าน | Scope | Target module | Frontend link |
| --- | --- | --- | --- | --- |
| SCRUM-5 | Highest / Done | Database schema + JPA entities | ทุก domain + `infra/postgres` | schema contract |
| SCRUM-35 | Medium / Done | Initial Spring Boot project | project foundation | — |
| SCRUM-9 | High / Action Plan_step2 | Register, login, `/auth/me`, BCrypt, JWT | `auth`, `user`, `common/security` | SCRUM-15 |
| SCRUM-6 | Medium / Action Plan_step2 | User profile and contact channels | `user` | SCRUM-16 |
| SCRUM-8 | Medium / Action Plan_step2 | Self-serve seller upgrade/profile | `seller`, `user` | SCRUM-16 |
| SCRUM-7 | High / Action Plan_step2 | Seller listing CRUD and visibility | `product`, `seller` | SCRUM-13 |
| SCRUM-10 | High / Action Plan_step2 | Browse/search/filter marketplace | `catalog`, `product` | SCRUM-14 |
| SCRUM-11 | Low / Action Plan_step2 | Add/remove/change cart items | `cart`, `product` | SCRUM-17 |
| SCRUM-12 | Low / Action Plan_step2 | Checkout and atomic stock reservation | `order`, `cart`, `product` | SCRUM-17, SCRUM-20 |
| SCRUM-26 | High / Action Plan_step2 | Seller orders, status, cost/profit | `order`, `seller` | SCRUM-27 |
| SCRUM-38 | Medium / Action Plan_step2 | Buyer order history/detail, status filter | `order` | SCRUM-25 |

## Implementation guardrails extracted from tickets

- Passwords are BCrypt hashes only; duplicate email returns `409` or an agreed `400`; invalid login returns `401`.
- JWT has expiration and `userId` claim; ownership is resolved from authenticated context.
- Checkout reserves stock atomically so two buyers cannot buy the same remaining unit.
- Buyer A cannot read Buyer B's order; seller sees only order items belonging to their shop.
- Order status filtering uses `PENDING`, `CONFIRMED`, `SENT`, `FULFILLED`, `CANCELLED` and history sorts newest first.
- Profit uses purchase-time cost snapshots.
- Each service use case needs JUnit 5 + Mockito tests and CI must pass.

## Open contract decisions

1. `USER` (Jira/DB) versus `CUSTOMER` (frontend).
2. `/orders/my-orders` (SCRUM-38) versus `/orders/me` (frontend client).
3. `totalPrice` (SCRUM-38) versus `totalAmount` (frontend type/schema).
4. Stable list/pagination envelope for products and orders.
5. Forgot/reset-password behavior is present in frontend but absent from the backend Jira scope.

แก้ decision เหล่านี้ใน `docs/api-contract.md` ก่อน merge implementation PR แรกที่เกี่ยวข้อง
