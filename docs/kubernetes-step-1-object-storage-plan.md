# Step 1 — ย้าย Seller Product Images ไป Object Storage

สถานะเอกสาร: Implementation plan  
Parent plan: [แผนทำ Backend ให้รองรับ Kubernetes และการกระจายโหลด](./kubernetes-backend-distribution-plan.md)

## 1. เป้าหมาย

ทำให้รูปสินค้าที่ seller อัปโหลดเป็น shared state ซึ่งทุก backend replica เข้าถึงได้ และไม่สูญหายเมื่อ Pod ถูก restart, reschedule หรือ rollout

แนวทางที่เลือก:

- Production ใช้ S3-compatible object storage
- Local/integration ใช้ Adobe S3Mock หรือ local filesystem ผ่าน storage contract เดียวกัน
- Database เก็บ stable public URL ของรูป
- Bucket เปิดอ่านเฉพาะ object ที่เป็น public catalog images หรือเปิดผ่าน CDN/public gateway
- Write/delete credentials อยู่เฉพาะ backend Secret

งานนี้ต้องเสร็จก่อนเพิ่ม backend replicas มากกว่า 1

## 2. ปัญหาใน implementation ปัจจุบัน

ปัจจุบัน `SellerProductImageStorage`:

- เขียนไฟล์ลง `SELLER_PRODUCT_IMAGE_DIR`
- เปิดไฟล์ผ่าน Spring MVC resource handler ที่ `/uploads/products/**`
- Kubernetes mount `/app/uploads` ด้วย `emptyDir`
- Docker Compose mount named volume ซึ่งแชร์ได้เฉพาะ container/host เดียว

ผลเมื่อมีหลาย Pods:

1. Request upload อาจเข้า Pod A แต่ request อ่านรูปครั้งต่อไปอาจเข้า Pod B
2. `emptyDir` ของแต่ละ Pod ไม่แชร์กัน
3. Pod replacement ทำให้รูปใน Pod เดิมหาย
4. การ scale และ rolling update อาจทำให้ URL เดิมตอบ 404
5. PersistentVolume แบบ `ReadWriteOnce` ไม่แก้กรณีหลาย nodes

## 3. Scope และ non-goals

อยู่ใน scope:

- Storage interface และ local/S3 implementations
- Image validation เดิม: PNG, JPEG, WebP และขนาดไม่เกิน 5 MiB
- Transaction-aware cleanup
- S3Mock local environment
- Runtime config และ secret contract
- Migration รูปเดิม
- Unit/integration tests
- Kubernetes Deployment/ConfigMap/Secret changes

ยังไม่อยู่ใน scope:

- Image resizing หรือ thumbnail generation
- Background media processing queue
- Signed download URLs สำหรับรูปสินค้าสาธารณะ
- Private user documents
- CDN image transformation

## 4. Target request flow

### Upload

```text
Client multipart upload
        |
        v
SellerProductController
        |
        v
SellerProductService transaction
        |
        +--> Validate size, magic bytes and content type
        |
        +--> Generate object key: products/<uuid>.<ext>
        |
        +--> PUT object to object storage
        |
        +--> Store stable public URL in local_cards.custom_image_url
        |
        +--> Commit database transaction
```

หาก transaction rollback หลัง upload สำเร็จ ให้ลบ object ที่เพิ่งสร้างแบบ best effort และบันทึก warning/metric หาก cleanup ไม่สำเร็จ

### Read

```text
Browser --> public object URL/CDN --> object storage
```

Backend ไม่ต้อง proxy image bytes ใน production ลดภาระ Pod และลด network hop

### Delete/replace

- ตรวจว่า URL เป็น object ที่ระบบจัดการเอง
- ตรวจว่าไม่มี product record อื่นอ้าง URL เดิม
- รอ database transaction commit ก่อนลบ object เก่า
- การลบ object ที่ไม่มีอยู่แล้วถือเป็น idempotent success
- URL ภายนอกหรือ URL ที่ไม่ match managed key ต้องไม่ถูกลบ

## 5. Java design

### 5.1 Storage contract

สร้าง interface ใน application layer:

```java
public interface SellerProductImageStore {
    String store(MultipartFile image);
    void delete(String imageUrl);
}
```

`SellerProductService` ต้องพึ่ง interface นี้แทน concrete filesystem class

### 5.2 Implementations

```text
seller/application/
├── SellerProductImageStore.java
├── SellerProductImageValidator.java
├── SellerProductImageStorageException.java
└── InvalidSellerProductImageException.java

seller/infrastructure/storage/
├── LocalSellerProductImageStore.java
├── S3SellerProductImageStore.java
├── SellerProductImageStorageProperties.java
└── SellerProductImageStorageConfiguration.java
```

หน้าที่:

| Component | หน้าที่ |
|---|---|
| `SellerProductImageStore` | Contract ที่ application service ใช้ |
| `SellerProductImageValidator` | ตรวจขนาด, magic bytes, MIME และคืน format/extension |
| `LocalSellerProductImageStore` | รักษาพฤติกรรม filesystem สำหรับ local/test |
| `S3SellerProductImageStore` | PUT/DELETE object ผ่าน S3-compatible API |
| `StorageProperties` | Bind และ validate runtime configuration |
| `StorageConfiguration` | เลือก implementation ตาม property |

ใช้ `@ConditionalOnProperty` หรือ explicit configuration เพื่อให้มี storage bean เพียงตัวเดียว

### 5.3 Managed object key

รูปแบบ key:

```text
products/<uuid>.png
products/<uuid>.jpg
products/<uuid>.webp
```

ข้อกำหนด:

- ไม่ใช้ original filename
- ไม่ยอมรับ path segment จาก client
- UUID สร้างฝั่ง server
- Content type ของ object ต้องตรงกับ magic bytes
- กำหนด cache metadata สำหรับ immutable UUID URL
- ตรวจ public base URL ก่อนแปลง URL กลับเป็น object key สำหรับ delete

### 5.4 Public URL

สร้าง URL จาก:

```text
<OBJECT_STORAGE_PUBLIC_BASE_URL>/<object-key>
```

ตัวอย่าง:

```text
https://images.example.com/products/550e8400-e29b-41d4-a716-446655440000.webp
```

ไม่เก็บ presigned URL ใน database เพราะมีวันหมดอายุ

## 6. Configuration contract

### Non-secret config

| Environment variable | ตัวอย่าง | หมายเหตุ |
|---|---|---|
| `SELLER_PRODUCT_IMAGE_STORAGE` | `local` หรือ `s3` | ค่า production ต้องเป็น `s3` |
| `OBJECT_STORAGE_ENDPOINT` | `http://s3mock:9090` | เว้นว่างได้สำหรับ AWS S3 |
| `OBJECT_STORAGE_REGION` | `auto` หรือ region จริง | ขึ้นกับ provider |
| `OBJECT_STORAGE_BUCKET` | `tcg-product-images` | แยก staging/production |
| `OBJECT_STORAGE_PUBLIC_BASE_URL` | `https://images.example.com` | ไม่มี trailing slash |
| `OBJECT_STORAGE_PATH_STYLE` | `true` | S3Mock ใช้ path-style |
| `SELLER_PRODUCT_IMAGE_DIR` | `/app/uploads/products` | ใช้เฉพาะ local mode |

### Secret config

| Environment variable | ตำแหน่ง |
|---|---|
| `OBJECT_STORAGE_ACCESS_KEY` | Kubernetes Secret/secret manager |
| `OBJECT_STORAGE_SECRET_KEY` | Kubernetes Secret/secret manager |

ห้ามใส่ production access key ใน ConfigMap, Dockerfile, Compose file ที่ commit ค่าไว้ หรือ CI logs

## 7. Docker และ S3Mock local environment

เพิ่ม Compose profile เช่น `object-storage` โดยใช้ไฟล์ override เพื่อบังคับให้ backend ใช้ S3 mode เมื่อเปิด profile:

```powershell
docker compose `
  -f docker-compose.yml `
  -f docker-compose.object-storage.yml `
  --profile object-storage up --build
```

การแยก override สำคัญ เพราะ Compose profile ที่เปิดเฉพาะ S3Mock ไม่ได้เปลี่ยนค่า `SELLER_PRODUCT_IMAGE_STORAGE` ของ backend ให้เอง

```text
backend --> s3mock:9090
developer/browser --> localhost:9090
```

Services ที่ต้องมี:

1. `s3mock`: S3-compatible test server พร้อม named volume และ initial bucket
2. `backend`: inject endpoint, bucket และ dummy development credentials ผ่าน `.env`

ข้อกำหนด Compose:

- Pin S3Mock image version ห้ามใช้ `latest`
- มี healthcheck
- Persist data ด้วย named volume
- API port bind กับ localhost
- Dummy credentials สำหรับ S3Mock เป็นค่าทดสอบที่เปิดเผยได้; หาก override ให้เก็บใน `.env` ซึ่งถูก gitignore
- Production ห้ามใช้ S3Mock หรือ credentials ชุด local

Local filesystem mode คงไว้ได้สำหรับ fast unit tests แต่ integration test ของ distributed behavior ต้องใช้ S3Mock หรือ S3-compatible test container

คำสั่ง local S3 flow:

```powershell
docker compose `
  -f docker-compose.yml `
  -f docker-compose.object-storage.yml `
  --profile object-storage `
  config --quiet

docker compose `
  -f docker-compose.yml `
  -f docker-compose.object-storage.yml `
  --profile object-storage `
  up --build backend
```

คำสั่งนี้ยังใช้ PostgreSQL/migrations เดิม แต่ backend จะรอ S3Mock healthy และ S3Mock จะสร้าง initial bucket ก่อนรับ traffic ส่วน default `docker compose up` ยังเป็น filesystem mode เหมือนเดิม

## 8. Kubernetes changes

### Deployment

- ตั้ง `SELLER_PRODUCT_IMAGE_STORAGE=s3`
- inject endpoint/region/bucket/public URL จาก ConfigMap
- inject access/secret key จาก Secret
- ลบ `uploads` volume และ volumeMount จาก production overlay
- คง `/tmp` เป็น `emptyDir` สำหรับ temporary runtime files
- ไม่ mount long-lived user content เข้า Pod

### Secret management

ระยะเริ่มต้น:

- CI สร้าง Kubernetes Secret จาก environment secret

ระยะ production:

- External Secrets Operator หรือ encrypted secret workflow
- แยก credentials ระหว่าง staging และ production
- Service account ของ object storage ให้สิทธิ์เฉพาะ bucket/prefix ที่ต้องใช้

### Network

- Backend egress ไป object storage endpoint ผ่าน HTTPS
- Browser อ่านรูปจาก public base URL/CDN โดยตรง
- ตั้ง CORS ของ bucket เฉพาะกรณี frontend ต้อง fetch ด้วย browser API; การแสดง `<img>` ปกติไม่จำเป็นต้องเปิดกว้าง

## 9. Dependency plan

เพิ่ม AWS SDK for Java v2 เฉพาะ module ที่จำเป็นสำหรับ S3-compatible API และ pin version ผ่าน BOM

หลีกเลี่ยงการดึง AWS SDK ทั้งชุดเพื่อลด image size และ dependency surface

ก่อน merge ต้องตรวจ:

- Dependency vulnerability scan
- Runtime image size delta
- รองรับ custom endpoint และ path-style access สำหรับ S3Mock
- รองรับ provider production ที่เลือก

## 10. Data migration plan

### 10.1 Inventory

- Export รายการ `custom_image_url` ที่ไม่เป็น null
- ระบุ URL ที่อยู่ใต้ `/uploads/products/`
- ตรวจไฟล์จริงใน volume/host
- สร้างรายงาน missing file, duplicate reference และ external URL

### 10.2 Copy

- Upload ไฟล์เดิมไป `products/<existing-filename>` เพื่อรักษา UUID เดิม
- กำหนด content type จาก magic bytes
- ตรวจ checksum/size หลัง upload
- ห้าม overwrite object ที่มี checksum ต่างกันโดยไม่หยุดตรวจสอบ

### 10.3 Database update

- Backup database ก่อน update
- Update เฉพาะ URL ที่ match local managed prefix
- เปลี่ยนเป็น `<public-base-url>/products/<filename>`
- External URLs ไม่ถูกแก้
- เก็บ mapping old URL → new URL เพื่อ audit และ rollback

### 10.4 Cutover

1. Deploy code ที่อ่าน URL ได้ทั้ง local และ object storage
2. Freeze image writes ชั่วคราวหรือใช้ maintenance window
3. Copy และ verify objects
4. Update database URLs ใน transaction/batches
5. เปิด `s3` storage mode
6. Run smoke tests
7. เก็บ local volumeไว้ระยะ rollback ก่อนลบ

## 11. Test plan

### Unit tests

- PNG/JPEG/WebP ผ่าน
- spoofed MIME และ magic bytes mismatch ถูก reject
- empty/oversized file ถูก reject
- generated key ไม่ใช้ original filename
- managed URL แปลงเป็น key ได้ถูกต้อง
- external/malformed URL ไม่ถูก delete
- delete object ที่ไม่มีอยู่เป็น success
- storage error ถูกแปลงเป็น `SellerProductImageStorageException`

### Transaction tests

- DB commit แล้ว object ใหม่คงอยู่
- DB rollback แล้ว object ใหม่ถูก cleanup
- replace image แล้ว object เก่าถูกลบหลัง commit เท่านั้น
- rollback ตอน replace แล้ว object เก่ายังคงอยู่
- shared URL ยังไม่ถูกลบเมื่อ record อื่นอ้างอยู่

### Integration tests กับ S3Mock

- Upload แล้วอ่าน object ได้
- Backend instance A upload และ instance B คืน URL/metadata เดียวกัน
- Restart backend containers แล้ว object ยังอยู่
- Invalid credentials ทำให้ startup fail หรือ readiness DOWN ตาม policy
- S3Mock unavailable แล้ว API คืน controlled error และไม่มี partial DB commit

### Kubernetes acceptance tests

- Deploy 2 replicas
- Upload หลายครั้งผ่าน Service โดยไม่ใช้ sticky session
- Delete/replace ผ่าน
- Restart ทั้งสอง Pods แล้วรูปยังเปิดได้
- Rolling update แล้วรูปไม่ตอบ 404
- Secret ไม่ปรากฏใน Pod logs หรือ CI output

## 12. Security checklist

- Bucket/prefix permissions เป็น least privilege
- Backend มี write/delete; public client ไม่มี write
- TLS สำหรับ production object storage
- ไม่ trust original filename หรือ client MIME เพียงอย่างเดียว
- จำกัดขนาดทั้ง Spring multipart, Ingress และ application validator
- Public object URL ใช้ UUID เดายากและไม่มี PII
- ห้ามเปิด directory listing
- เปิด provider audit logs เมื่อรองรับ
- มี credential rotation procedure
- Lifecycle rule สำหรับ orphan objects ใช้หลังมี audit/reconciliation เท่านั้น

## 13. Failure handling และ observability

Metrics/logs ที่ควรมี:

- Upload success/failure count
- Upload latency และ bytes
- Delete/cleanup failure count
- Object storage timeout/throttle count
- Orphan cleanup warnings
- Missing object/404 rate ที่ CDN/storage

Log ต้องมี correlation ID และ object key ที่ไม่ลับ แต่ห้าม log credentials, signed headers หรือ full secret-bearing endpoint

## 14. Rollback plan

ก่อน cutover:

- Backup database
- เก็บ local upload volume
- เก็บ URL mapping
- ทดสอบ local storage mode กับ release เดียวกัน

หาก production cutover ล้มเหลว:

1. หยุด image writes
2. Roll back Deployment ไป image ก่อนหน้า
3. Restore URL mapping หรือใช้ compatibility read path
4. เปิด local storage mode
5. ตรวจว่ารูปที่สร้างระหว่าง cutover ถูก copy กลับหรือเก็บไว้ใน object storage

ห้ามลบ local volume จนกว่าจะผ่าน observation period และ reconcile object/database ครบ

## 15. Implementation sequence

### 1A — Refactor โดยยังไม่เปลี่ยน behavior

- สร้าง storage interface
- ย้าย validation เป็น reusable component
- เปลี่ยน filesystem class ให้ implement interface
- คง tests เดิมและเพิ่ม contract tests

ผลลัพธ์: local behavior เหมือนเดิมทุกอย่าง

### 1B — เพิ่ม S3-compatible implementation

- เพิ่ม SDK และ configuration properties
- Implement PUT/DELETE/public URL
- เพิ่ม unit tests ด้วย fake/mock client
- รักษา transaction-aware cleanup

### 1C — เพิ่ม S3Mock Compose profile

- เพิ่ม S3Mock และ initial bucket configuration
- เพิ่ม `.env.example` placeholders
- เพิ่ม integration tests
- ทดสอบ backend อย่างน้อย 2 containers

### 1D — เพิ่ม Kubernetes configuration

- เพิ่ม ConfigMap keys และ Secret refs
- ถอด uploads volume ใน staging overlay
- Deploy 2 replicas ใน local/staging cluster
- ผ่าน acceptance tests

### 1E — Migrate existing images

- Inventory, backup, copy, verify และ update URLs
- เก็บ report/mapping ไว้เป็น deployment artifact ที่ปลอดภัย

### 1F — Production cutover

- เปิด S3 mode
- Run smoke tests
- Monitor errors/latency
- หลัง observation period จึงถอด local resource handler และลบ volume

## 16. Definition of Done สำหรับ Step 1

- `SellerProductService` ไม่รู้รายละเอียด filesystem หรือ S3
- Production ไม่มี long-lived image data ใน Pod filesystem
- 2+ replicas อ่าน/เขียน/ลบรูปชุดเดียวกันได้
- Pod restart และ rolling update ไม่ทำให้รูปหาย
- Transaction rollback ไม่ทิ้ง object ใหม่โดยไม่จำเป็น
- Migration รูปเดิมมี backup, verification และ rollback mapping
- Credentials อยู่ใน secret store เท่านั้น
- Unit, S3Mock integration และ Kubernetes acceptance tests ผ่าน
- Runbook อธิบาย upload failure, credential rotation และ rollback แล้ว
