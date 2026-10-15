# Contributing to Mythix TCG Store Backend

คู่มือนี้ทำให้การพัฒนา backend สอดคล้องกับ Jira, frontend contract และ quality gate เดียวกันทั้งทีม

## 1. ก่อนเริ่มงาน

1. อ่าน Jira ticket, acceptance criteria, linked frontend ticket และ dependency ให้ครบ
2. ตรวจ [Jira backend scope](docs/jira-backend-scope.md) และ [API contract](docs/api-contract.md)
3. ถ้า ticket กับ frontend ใช้ชื่อ endpoint/field ไม่ตรงกัน ให้ตกลงและแก้ contract ก่อนเขียน implementation
4. เช็กว่า issue ที่เป็น dependency เสร็จแล้ว หรือบันทึก assumption ใน PR ให้ชัด

เอกสารใน repository เป็นข้อมูลประกอบการทำงาน ไม่แทน acceptance criteria ล่าสุดใน Jira

## 2. Branch และ commit

`main` เป็น protected/production-ready branch และ `develop` เป็น integration branch ห้าม push ตรง

ชื่อ branch ใช้ Jira key และ scope:

```text
feat/scrum-9-auth-api
fix/scrum-12-stock-race
docs/scrum-38-order-contract
```

PR title ถูกตรวจโดย GitHub Actions ให้ใช้ type เดียวกับ Conventional Commits เช่น `feat`, `fix`, `docs`, `test`, `ci` หรือ `chore` หาก title ไม่ผ่านจะ merge ไม่ได้เมื่อเปิด branch protection ให้ required check นี้

ใช้ Conventional Commits และใส่ Jira keyในข้อความเมื่อเหมาะสม:

```text
feat(auth): implement registration flow (SCRUM-9)
fix(order): reserve stock atomically (SCRUM-12)
test(product): cover seller ownership rule (SCRUM-7)
```

## 3. Package และ code boundaries

เพิ่มโค้ดใน feature ที่เป็นเจ้าของ use case:

```text
feature/
├── api/             # controller + request/response DTO
├── application/     # service/use case + transaction boundary
├── domain/          # entity/value object/domain rule
└── persistence/     # repository/query adapter
```

สร้างเฉพาะ layer ที่ feature ใช้จริง และทำตามกติกา:

- controller ทำ HTTP/validation/mapping เท่านั้น ไม่ใส่ business logic และไม่เรียก repository โดยตรงในโค้ดใหม่
- service เป็นเจ้าของ authorization, workflow และ `@Transactional`
- ห้ามคืน JPA entity จาก public API ให้สร้าง response DTO
- ห้ามรับ `userId`/`sellerId` จาก request แล้วเชื่อว่าเป็นเจ้าของข้อมูล ให้ดึง identity จาก `SecurityContext`
- ข้าม feature ผ่าน application service/port ไม่แก้ entity ของ feature อื่นแบบกระจาย
- money ใช้ `BigDecimal`; time ใน API ใช้ ISO-8601 UTC
- list endpoint ต้องมี pagination limit และ deterministic ordering

อ่านรายละเอียดเพิ่มเติมใน [architecture.md](docs/architecture.md)

## 4. Security และ error handling

- password ต้อง hash ด้วย BCrypt และห้าม log credential/token
- endpoint ใหม่ต้องระบุชัดว่า Public, Authenticated, Seller หรือ Admin
- ตรวจทั้ง authentication และ ownership; test กรณีอ่าน/แก้ข้อมูลข้ามบัญชี
- validate request ด้วย Jakarta Validation และตอบ status ตามประเภท (`400`, `401`, `403`, `404`, `409`)
- ใช้ error response shape กลางตาม [API contract](docs/api-contract.md)
- secret/config มาจาก environment; ห้าม commit `.env`, key หรือ production credential

## 5. Database และ concurrency

- schema migration อยู่ใน `src/main/resources/db/migration` และ Flyway รันผ่าน migration service/Job ก่อนเริ่ม API
- ห้ามแก้ไฟล์ `V*` ที่เคยใช้งานแล้ว ให้เพิ่ม version ใหม่เสมอ; development seed อยู่ใน `infra/postgres/init` และไม่ใช่ production migration
- schema change ต้องแก้ JPA mapping, SQL, test และ API docs ที่เกี่ยวข้องใน PR เดียวกัน
- ห้ามลบ/เปลี่ยนชื่อ column ที่ใช้แล้วโดยไม่มี migration/backward-compatibility plan
- checkout/stock reservation ต้องอยู่ใน transaction และมี concurrency-safe update/lock พร้อม test ป้องกัน oversell
- order item ต้อง snapshot ชื่อ ราคา และต้นทุนตอนซื้อ เพื่อให้ประวัติและกำไรไม่เปลี่ยนตาม product ภายหลัง

## 6. Tests

ขั้นต่ำของ feature PR:

- unit test ของ service/use case ทั้ง happy path และ business failure
- controller test สำหรับ validation, auth และ response status/shape
- repository/integration test เมื่อมี custom query, locking หรือ transaction behavior
- regression test ทุก bug fix

รันก่อนเปิด PR:

```powershell
.\mvnw.cmd verify
```

macOS/Linux:

```bash
./mvnw verify
```

test profile ใช้ H2 เพื่อให้ smoke test และ CI ทำงานโดยไม่ต้องเปิด PostgreSQL แต่ query/locking ที่พึ่ง PostgreSQL ต้องมี integration test กับ PostgreSQL/Testcontainers เพิ่มต่างหาก

## 7. Pull request checklist

- [ ] PR title ใช้ Conventional Commit เช่น `feat(auth): implement SCRUM-9 registration`
- [ ] ระบุ Jira key และ linked frontend ticket
- [ ] อธิบาย API/schema change และแนบตัวอย่าง request/response ถ้ามี
- [ ] อัปเดต `docs/api-contract.md` เมื่อ contract เปลี่ยน
- [ ] เพิ่มหรือแก้ test ครบ acceptance criteria
- [ ] ทดสอบ authorization/ownership และ negative cases
- [ ] `.\mvnw.cmd verify` ผ่าน
- [ ] ตรวจว่าไม่มี `.env`, secret, build output หรือ IDE files ใน diff
- [ ] ใช้ `Closes ...` เฉพาะเมื่อ acceptance criteria ของ issue ครบจริง

## 8. Review guide

Reviewer ควรตรวจตามลำดับ: correctness ของ acceptance criteria, security/ownership, transaction และ stock consistency, API compatibility กับ frontend, test coverage แล้วจึง style/refactor. ถ้า PR เปลี่ยน contract โดยไม่มีการแก้เอกสาร ให้ request changes ก่อน merge
