# PR #39 — ย้าย working checkout เสร็จแล้ว

วันที่ 2026-10-16 (Asia/Bangkok)

ใช้ `C:\Users\User\tcg-store-backend` เป็น working directory หลักต่อจากนี้
branch `feat/kubernetes` ที่ commit `b859aa13689c87135dc2f9ce088e0c63d1c13560`
ติดตาม `origin/feat/kubernetes` และตรวจ ahead/behind เป็น 0/0
งานแก้ไขยังไม่ commit ไม่มี push/merge

## งานที่นำมาครบ

- PR branch/history และ code changes ทั้ง 11 ไฟล์จาก workspace เดิม ตรวจ SHA256 ตอนคัดลอกตรงกัน
- รายงาน 13 ไฟล์รวมแผน Component A อยู่ใน `docs\work-reports\pr39` ตรวจ hash เทียบต้นทางครบ
- Logs/evidence และ verified frontend checkout อยู่ใน ignored `.local\pr39-work`
- Frontend ยังคง main `0273f7089172fc221ababb791d7bac47e3c48c14`, checkout สะอาด
- `.env` ชุดที่ใช้งานได้มาแล้ว ไม่ขาด local/cloud required fields ไม่มีการแสดงค่า secrets
- `.env` ปลายทางเดิมเก็บเป็น ignored `.env.pre-migration-20261016`
- เก็บ branch เดิม `feat/scrum-59-card-details-ui`, `.neon` linkage และ upload data ไว้

ปรับ frontend context/Dockerfile paths ให้ชี้ภายใน repo ใหม่ และเพิ่ม `.local/`
ใน Git/Docker ignore เพื่อกัน local evidence, tooling และ nested frontend checkout
ออกจากการ commit/backend build context ไม่แก้ sibling frontend repo เดิมของผู้ใช้
สร้าง test runtime ใหม่ใน ignored `.local\pr39-tools` จึงไม่ต้องใช้ venv จาก workspace เก่า

## ผลตรวจหลังย้าย

- Build backend/frontend จาก repo ใหม่สำเร็จ ได้ runtime image IDs เดิม
- Configuration tests ผ่าน 6/6; Compose config และ git diff --check ผ่าน
- สร้าง containers ใหม่ทั้ง 8 ให้ Compose metadata อ้างอิง working directory/config file ที่ repo ใหม่ครบ
- frontend และ backend-cloud healthy; local services ไม่ได้รันตามรูปที่ตกลง
- migrations-cloud exit 0 / No migration necessary ไม่มี reset, seed หรือ schema upgrade
- HTTP frontend, readiness, direct catalog และ catalog ผ่าน frontend proxy: 200 ทั้งหมด
- เก็บ named volumes เดิมทั้งหมด Docker data disk ยังคงอยู่ D: ไม่มี down -v/prune

หลักฐานล่าสุด:
`C:\Users\User\tcg-store-backend\.local\pr39-work\moved-runtime-verification.json`
พร้อม build/start logs ใน directory เดียวกัน

## ทำงานต่อ

```powershell
cd C:\Users\User\tcg-store-backend
pwsh -NoProfile -File scripts/start-desktop-acceptance.ps1 -Runtime cloud -Build
```

แผนงานต่อคือ `docs\work-reports\pr39\pr39-component-a-test-plan.md`
ยังไม่มีการทำ Kubernetes outage/load/fault test ในขั้นตอนย้ายนี้

ต้นทาง `C:\Users\User\Documents\Codex\2026-10-16\https-github-com-mudst-2026-luntungsadungyong-2`
ยังเก็บไว้สำหรับ recovery ไม่ได้ลบงานหรือ environment เดิม
ไฟล์ helper เก่าใน evidence archive เป็นประวัติ อาจอ้าง path เก่า ห้าม rerun
ให้ใช้ launcher ใน scripts ของ repo ใหม่เท่านั้น

C: เหลือประมาณ 2.95 GiB จากการตรวจล่าสุด ควรเผื่อพื้นที่ก่อน build/test ขนาดใหญ่
ยังไม่ได้ลบ caches/workspace เดิมเพื่อคืนพื้นที่โดยอัตโนมัติ
