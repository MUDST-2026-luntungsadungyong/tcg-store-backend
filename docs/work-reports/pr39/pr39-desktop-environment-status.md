# Docker Desktop environment — status and missing inputs

> รายงานเก่านี้ถูกแทนที่แล้ว: ย้าย Docker ไป D:, ถอด Compose เก่า,
> เติม cloud inputs และเปิดกลุ่ม tcg-store-service แบบ cloud สำเร็จ
> ดู [ผลล่าสุด](pr39-desktop-cloud-ready.md) ด้านล่างเป็นประวัติ blocker เดิม

วันที่ 2026-10-16 · backend `feat/kubernetes` / `b859aa1`

สถานะ: เตรียม environment ใหม่และ `.env` แล้ว แต่ยัง build/start ไม่สำเร็จ และยังลบ Compose เก่าไม่สำเร็จ เพราะไดรฟ์ C: เต็มจน Docker storage กลายเป็น read-only ไม่มีการอ้างว่า live acceptance ผ่าน

## สิ่งที่เตรียมแล้ว

- New Compose file: `backend/compose.desktop.acceptance.yml`, project `tcg-pr39-desktop`
- Services: PostgreSQL, S3Mock, migration, development seed, backend และ frontend
- ใช้ project-scoped volumes ใหม่ ไม่ mount `tcg_postgres_data` หรือ `tcg_backend_uploads` เดิม
- Bind เฉพาะ loopback: frontend 3000, API 8080, PostgreSQL 15432, S3Mock 9090
- สร้าง ignored `backend/.env` ใหม่ โดยสุ่ม local DB password, JWT secret และ local S3 credentials ไม่พิมพ์ค่าออก log หรือ report
- เพิ่ม initializer และ launcher: `scripts/initialize-desktop-env.py`, `scripts/start-desktop-acceptance.ps1`
- Clone frontend จาก remote ที่ตรวจยืนยันแล้วไว้ที่ `work/tcg-store-frontend`, main commit `0273f7089172fc221ababb791d7bac47e3c48c14`
- เตรียม Desktop frontend Dockerfile แยก ใช้ Node 22 และไม่ดาวน์โหลด Cypress binary; frontend ใช้ `/api` ผ่าน Nginx ไป backend network alias `tcg-backend-service`

Compose configuration validation ผ่าน (`config --quiet`, exit 0) ทั้งก่อน build และหลังเตรียม recipe สุดท้าย แต่ image build ของ recipe สุดท้ายยังไม่ได้รันใหม่เพราะพื้นที่ไม่พอ

## `.env` ขาดอะไร

Local environment ใหม่: **ไม่ขาด required fields** ตาม initializer; ใช้ PostgreSQL และ S3Mock ใหม่บนเครื่อง

Cloud/Neon ยังขาด 7 fields ต่อไปนี้ ทั้งในไฟล์ใหม่และจากการตรวจเฉพาะ completeness ของไฟล์ `.env` ใน project เก่า โดยไม่ได้แสดงหรือคัดลอกค่าจริง:

```text
NEON_JDBC_URL
NEON_DB_USERNAME
NEON_DB_PASSWORD
OBJECT_STORAGE_BUCKET
OBJECT_STORAGE_PUBLIC_BASE_URL
OBJECT_STORAGE_ACCESS_KEY
OBJECT_STORAGE_SECRET_KEY
```

หากใช้ S3-compatible provider ที่ไม่ใช่ default AWS endpoint ต้องเติม `OBJECT_STORAGE_ENDPOINT` ให้ตรง provider ด้วย และยืนยัน region/public URL/TLS แยกกัน ตัวแปร `LOCAL_OBJECT_STORAGE_*` ที่สร้างให้ S3Mock ไม่ใช่ cloud credentials

ไฟล์ใหม่เป็น local-only configuration และไม่อ่าน cloud `.env` เดิมมาใช้ จึงยังไม่ติดต่อหรือแก้ Neon/remote bucket

## ผล build จริงและ blocker

- Docker Desktop เดิมไม่ได้เปิด; เริ่มขึ้นได้บน context `desktop-linux`, Engine 29.8.0
- ดึง PostgreSQL 16.15 และ Adobe S3Mock 5.1.0 ตาม pinned digests สำเร็จ
- เริ่ม build backend/frontend จาก source จริง Backend dependency resolution สำเร็จ แต่ BuildKit commit cache ไม่ได้:

```text
write /var/lib/docker/buildkit/metadata_v2.db: read-only file system
```

- ตรวจ host พบ C: เหลือประมาณ 20 MB และต่อมาลดเป็น 0 bytes จนเขียน report ไม่ได้ Docker CLI ก็แจ้ง `There is not enough space on the disk`
- ลบเฉพาะ archives/เครื่องมือ executable ที่ดาวน์โหลดในงานนี้และดาวน์โหลดใหม่ได้ เพื่อคืนพื้นที่ฉุกเฉิน เก็บ source, reports, credential files, images และทุก volume ไว้ ผล verification รอบก่อนยังอยู่ใน logs แต่ต้องติดตั้ง tools ใหม่หากจะรันซ้ำ
- หยุดเฉพาะ WSL distribution `docker-desktop` หลังคำสั่ง Desktop stop/restart ปกติค้าง แล้วจบเฉพาะ CLI restart/stop processes ที่งานนี้เริ่มไว้ Ubuntu distro อื่นไม่ได้ถูก terminate
- ตรวจหลังหยุดพบ C: เหลือประมาณ 229 MB ซึ่งยังไม่พอสำหรับ build environment ใหม่ ส่วน D: ว่างประมาณ 784.8 GiB

## Compose เก่าที่ยืนยันแล้ว

Project `tcg-store-backend`, source config `C:/Users/User/tcg-store-backend/docker-compose.yml`

```text
tcg_backend
tcg_postgres
tcg_frontend
tcg-store-backend-migrations-1
```

ตรวจ container IDs, Compose project labels, config path และ volume references ก่อนสั่ง removal แล้ว แต่ `docker rm` ล้มเหลวเมื่อ storage เป็น read-only จึง **ยังถือว่า Compose เก่าไม่ถูกลบสำเร็จ** ไม่ได้ลบ source config เก่า, image หรือ volume

เก็บ sanitized inventory ไว้ที่ `work/old-compose-before-removal.json` และเตรียม script ที่ตรวจ IDs/labels ซ้ำก่อนลบเฉพาะ containers/network ของ project นี้ โดยไม่ใช้ `rm -v`, `down -v` หรือ prune แบบกว้าง

## ขั้นตอนที่ต้องตัดสินใจก่อนทำต่อ

ตำแหน่ง data disk ที่ตรวจพบ:

```text
C:/Users/User/AppData/Local/Docker/wsl/disk/docker_data.vhdx  ~6.32 GiB
```

เป้าหมายที่เสนอคือ `D:/DockerDesktop` ซึ่งยังไม่มี directory นี้และมีพื้นที่เพียงพอ การย้าย Docker Desktop data เป็นการเปลี่ยน storage ของแอปทั้งระบบ ไม่ใช่แค่ไฟล์ repo จึงยังไม่ได้ย้ายเอง

หลังย้ายอย่างรักษาข้อมูล หรือหลังคืนพื้นที่ C: เพียงพอ จึงตรวจ Docker filesystem อีกครั้ง ลบเฉพาะ Compose เก่าที่ระบุไว้ build recipe ใหม่ และรอ migration/seed exit 0 กับ service health ทั้งหมด ก่อนเริ่ม local live tests ต่อ

ห้ามถือผล render/unit tests หรือเพียงมีไฟล์ Compose ว่า environment ใหม่พร้อมใช้งาน ขณะรายงานนี้ยังไม่มี healthy stack ใหม่และยังไม่ได้เริ่ม live browser journey
