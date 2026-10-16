# Docker Desktop — เปลี่ยนตามภาพแล้ว

2026-10-16 (Asia/Bangkok) · PR #39 · parent ตรวจผลจริงแล้ว

ใช้ `backend/docker-compose.desktop.yml` กลุ่ม `tcg-store-service` ครบ 8 containers

| ส่วน | สถานะ |
|---|---|
| frontend | Running / healthy · http://localhost:3000 |
| backend-cloud | Running / healthy · http://localhost:8080/api · profile neon |
| migrations-cloud | Exited (0), Flyway และ Hibernate schema validation ผ่าน |
| backend-local, postgres-local, s3mock-local, migrations-local, seed-local | Created / ไม่ได้รัน ตั้งใจให้หยุดขณะใช้ cloud |

Local ที่เป็น Created ไม่ใช่ error ไม่ต้องเปิดเป็นสีเขียวพร้อม cloud

## ผลตรวจจริง

- Build backend/frontend จาก source สำเร็จ
- หน้าเว็บ `/`, API readiness, catalog ตรงจาก backend และผ่าน frontend proxy: HTTP 200 ทั้งหมด
- Readiness เป็น UP และ catalog JSON ผ่าน proxy ตรงกับ backend
- ตรวจค่าภายใน container ยืนยันว่าใช้ Neon และ remote S3 ที่ส่งมา ไม่ใช่ local PostgreSQL/S3Mock โดยไม่แสดง secrets
- Cloud JWT แยกจาก local; migration/backend ใช้ image เดียวกัน
- ตรวจ Neon แบบ READ ONLY และยืนยัน client TLS ก่อน startup พบ baseline version 9 และ canonical tables
- Migration runner รายงาน version 9 / No migration necessary และ exit 0 ไม่มี reset, seed หรือ schema upgrade
- History หลัง startup ยังเป็น baseline version 9 แถวเดิม
- Python configuration/secret-boundary tests 6/6, Java Flyway regression tests 2/2 และ git diff --check ผ่าน ไม่ได้ rerun ทุก test ในรอบสลับ runtime นี้

## สิ่งที่แก้

ปรับ Compose หลักให้ build frontend จาก `work/tcg-store-frontend` main
`0273f7089172fc221ababb791d7bac47e3c48c14` ผ่าน Node 22, ปิด Cypress binary
download ตอน build, ใช้ `/api` ผ่าน Nginx และกัน `.env*` ออกจาก frontend context
พอร์ต bind เฉพาะ loopback

เปลี่ยน dependency จาก flyway-core เป็น spring-boot-starter-flyway เพื่อให้
Spring Boot 4 โหลด Flyway auto-configuration จริง พร้อม regression tests
([เอกสาร Spring](https://spring.io/blog/2025/10/28/modularizing-spring-boot/))
แยก Maven cache จากชุดที่เสียช่วง C: เต็ม

Launcher ใช้ cloud เป็นค่าเริ่มต้น สร้างทั้งสอง profiles เพื่อแสดงใน Desktop
แต่หยุดส่วนที่ไม่ใช้ รอ migration/backend healthy แล้วค่อยเปิด frontend
ไม่เรียก Neon CLI refresh และไม่เขียนทับ credentials
การแสดงครบทั้งสอง profiles ต้องมี cloud/local settings; initializer เตรียม local ให้แล้ว

```powershell
pwsh -NoProfile -File scripts/start-desktop-acceptance.ps1 -Runtime cloud -Build
```

## `.env`

ไม่ขาด required fields ของชุดนี้ รวม custom S3 endpoint ที่ส่งมาแล้ว
TiDB ไม่ได้ใช้จึงไม่ต้องเติมค่าที่ว่าง ข้อมูลจริงเก็บใน ignored `.env`
Remote S3 settings ถูกส่งเข้า backend แล้ว แต่ยังไม่ได้ทดสอบ upload/delete
permissions หรือ browser mutation บน shared database ควร rotate DB password,
storage keys และ JWT secret ที่เคยส่งในแชต

## Docker storage / การถอดชุดเก่า

- Data disk อยู่ที่ `D:\DockerDesktop\disk\docker_data.vhdx` เทียบ SHA256 สำเนาตรงกันก่อนลบสำเนา C:
- เส้นทางเดิม `C:\Users\User\AppData\Local\Docker\wsl\disk` เป็น junction ไป D: ยืนยันด้วย reparse-point query นี่ไม่ใช่การย้ายผ่านหน้า Settings
- ลบ 4 containers/network ของ `tcg-store-backend` แล้ว แต่เก็บ source compose file, images และ volumes เดิม
- ถอด 6 containers/network ของชุดที่สร้างผิดรูปแบบ `tcg-pr39-desktop` และลบไฟล์ Compose เสริมที่งานนี้สร้าง
- เก็บ `tcg_postgres_data`, `tcg_backend_uploads`, anonymous volumes เดิม และ volumes ของชุด local ที่ถอดไว้ครบ ไม่ใช้ down -v หรือ broad prune
- ตรวจล่าสุด C: ว่างประมาณ 5.24 GiB, D: 777.34 GiB

หลักฐาน sanitized: `work/desktop-cloud-verification.json`,
`work/neon-readonly-preflight.json`, `work/desktop-cloud-start.log`,
`work/desktop-canonical-build.log`, `work/docker-data-move.json`,
`work/old-compose-removal.json` ไม่มี push/merge PR

ผลนี้ยืนยัน Desktop cloud runtime ตามภาพ ไม่ใช่การปิด Kubernetes live acceptance
Step 1–Component D, trusted supply chain, production promotion หรือ frontend mutation journey
