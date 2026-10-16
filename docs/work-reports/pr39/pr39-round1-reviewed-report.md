# PR #39 — รายงานตรวจงานและทดสอบรอบแรก

วันที่ 2026-10-16 (Asia/Bangkok)

ตรวจ branch `feat/kubernetes` ที่ commit `b859aa13689c87135dc2f9ce088e0c63d1c13560` เทียบกับ `main` ที่ `3338f760613aae5b166975f8bccd83c14938ecf2` และตรวจ remote แล้วว่า PR #39 ยังชี้ commit นี้

อ่าน `kubernetes-live-acceptance-plan.md` และ `kubernetes-component-d-implementation-status.md` ครบก่อนแบ่งงาน พร้อมตรวจแผนหลัก เอกสาร component และ runbooks ประกอบ ใช้เอกสารเป็นข้อมูล acceptance; คำสั่ง deploy/restore ในเอกสารไม่ได้ถูกนำไปรันกับระบบจริง

แบ่งให้ agent `gpt-6-luna` 3 สาย: runtime/storage/database, supply chain/platform และ release/observability/smoke โดย main ตรวจ source, raw logs, Surefire XML และรันทวนจุดสำคัญด้วยตัวเอง

## ผลทดสอบรอบแรก

คำว่า “ครึ่งแรก” ในรอบนี้หมายถึงประมาณครึ่งหนึ่งของ automated cases ในชุดที่ระบุ ไม่ใช่เสร็จครึ่งหนึ่งของ live acceptance หรือครึ่งหนึ่งของ application tests ทั้ง repository

| ชุดทดสอบของ agents | รัน / จำนวนในขอบเขต | ผล |
|---|---:|---|
| S3 unit/metrics/multipart ที่ inventory ไว้ | 7 / 12 | ผ่าน 7 |
| Runtime probes / graceful shutdown | 2 / 3 | ผ่าน 2 |
| Java logs / metrics / correlation | 5 / 9 | ผ่าน 5 |
| Artifact verifier | 4 / 9 | ผ่าน 4 |
| Kubernetes renderer | 15 / 30 | ผ่าน 15 |
| Release core / orchestration / dashboard / API smoke | 42 / 80 | ผ่าน 42 |
| PowerShell deployment adapter | 1 scenario suite ทั้งไฟล์ | ผ่าน; ไม่แปลงเป็นจำนวน unit cases |

รวม agents: Java 14 cases ผ่าน; Python 61 จาก 119 cases ผ่าน ไม่มี assertion failures หรือ skips ในชุดที่รันสำเร็จ รายงานรายสายระบุ selection และส่วนที่ยังไม่ได้รันไว้

Main รันทวน Python อีก 11 cases ผ่าน (8 release/artifact/recovery + 3 renderer) รวม migration-failure, monitoring no-data, production same-digest promotion และ policy ของ renderer จำนวนนี้มีการทดสอบซ้ำกับ agents จึงไม่บวกเป็นจำนวน cases ใหม่ทั้งหมด มี 2 cases ที่ main เลือกเพิ่มจากส่วนที่ agents defer ไว้ ทำให้ Python ที่รันแล้วแบบไม่ซ้ำเป็น 63 จาก 119 cases

Main ตรวจ Maven logs ทั้งสามรอบ (`8 + 1 + 5`) และ Surefire XML: failures/errors/skips เป็นศูนย์ XML ของ storage รอบแรกถูกแทนที่โดยการรัน rollback case ภายหลัง จึงใช้ console log รอบแรกยืนยัน 6 cases เดิมร่วมกับ exact selection ในรายงาน runtime

## สิ่งที่ยังไม่เสร็จและผลตรวจของ main

### 1. เงื่อนไขตรวจ protected main ยังยอมรับหลักฐานที่ไม่พอ

`scripts/release/verify_artifact.py:193–200` ยอมรับ `enforce_admins.enabled=true` เพียงอย่างเดียว แม้ไม่มี required review หรือ required status checks จึงยังพิสูจน์ gate เรื่อง protected main ตามที่แผนต้องการไม่ได้

Main ทำ reproduction แบบ offline โดยใช้ successful verifier fixture เดิมและแทนเฉพาะ protection response: response ว่างถูกปฏิเสธ แต่ `enforce_admins` อย่างเดียวผ่าน จึงยืนยันพฤติกรรมนี้จากการรันจริง ไม่ใช่แค่ข้อสังเกตจาก source

นี่ไม่ใช่หลักฐานว่า GitHub `main` ของ repo ปัจจุบันตั้งค่าเช่นนั้น ต้องแก้ predicate ให้ต้องมี substantive protection ตาม policy ที่ทีมกำหนด และเพิ่ม negative regression case ก่อนถือว่า B-01 gate ครบ

### 2. Full browser journey ยังไม่มี implementation

`scripts/smoke/frontend_smoke.spec.js` มีเพียงค้นหาการ์ดและตรวจปุ่มแสดงรหัสผ่าน ไม่มี automated login → seller upload → catalog/cart → checkout → authorization ครบตาม FE-01–FE-07

`orchestrate.py:449–458` เรียก API smoke เท่านั้น และ accepted receipt ไม่มี fields ผูก browser report/frontend build identity จึงต้องเพิ่ม suite กับหลักฐานเชื่อม build/release หรือกำหนด external gate ที่ตรวจสอบย้อนกลับได้ API mutation mock ที่ผ่านไม่ปิด browser gate

### 3. Backup/restore และ A/C live gates ยังพึ่งการตรวจภายนอก

แผนต้องการ restore evidence ก่อน production migration และ A/C acceptance ก่อน promotion แต่ CLI/receipt ปัจจุบันไม่มี input ที่ผูกหลักฐานเหล่านี้เป็น automated gate โดยตรง นับเป็นช่องว่างของ acceptance integration ไม่ใช่หลักฐานว่าเคยมี unsafe deployment เกิดขึ้นแล้ว

ต้องกำหนดเจ้าของและจุด enforce สำหรับ DB/object restore, two-replica/outage/load, TLS/CORS/admission/network และบันทึกหลักฐานที่ผูก release ก่อนใช้ promotion flow จริง

### 4. Live acceptance ของ Step 1–Component D ยังเปิดอยู่

| ส่วน | งานที่ต้องทำจริงต่อ |
|---|---|
| Step 1 / Cloud S3 | ตรวจและย้ายรูปเดิม, reconcile DB/object inventory, upload/read ข้าม replicas, restart persistence, rollback/shared-reference cleanup |
| Step 2 / DB | Fresh DB, legacy upgrade, repeat migration, failure gate และ isolated DB/object restore กับ PostgreSQL/Neon จริง |
| A / Reliability | 2 Pods, DB outage/recovery, rollout/rollback, SIGTERM/drain, HPA/load และ connection quota จริง |
| B / Supply chain | Protected-main publish, multi-arch GHCR digest, signature/provenance/child SBOM และ digest handoff จาก trusted workflow จริง |
| C / Platform | Server admission/RBAC/Secret delivery, DNS/TLS, routing, 413/429, CORS และ NetworkPolicy allow/deny บน CNI จริง |
| D / Operations | Private scrape, exporters/log pipeline/alert receiver, collector-backed observation อย่างน้อย 600 วินาที, accepted staging และ production promotion/recovery |
| Frontend | Deployed frontend build identity/URL และ browser mutation journey เต็มรูปแบบ |

Policy ที่อนุญาต public IPv4 egress บน ports ที่ระบุเป็นข้อจำกัดที่เอกสารยอมรับไว้แล้ว Platform ต้องเลือก FQDN/egress gateway หรือยอมรับ exception ตามกระบวนการของทีมก่อน production

## ข้อจำกัดที่ตรวจพบในเครื่องนี้

- Default Java เป็น 17 แต่มี JDK 21; agent ใช้ JDK 21 และดาวน์โหลด Maven dependencies แล้ว จึงรันชุด Java ที่เลือกผ่านได้ ไม่ได้ลด version ใน POM
- Docker CLI มีอยู่ แต่ทั้ง default/desktop-linux engine pipe ไม่พร้อมใช้งาน ตรวจซ้ำด้วยสิทธิ์นอก sandbox แล้ว
- `kubectl config get-contexts -o name` ไม่แสดง context จึงยังไม่มี target cluster สำหรับ live acceptance
- `kubectl kustomize` รอบ sandbox ติด Windows Access denied; คำสั่งอ่านไฟล์เดียวกันและ renderer tests ผ่านเมื่อให้สิทธิ์อ่านครบ จัดเป็นข้อจำกัด environment ไม่ใช่ renderer assertion failure
- เครื่องใช้ kubectl v1.36.1 / Kustomize v5.8.1 ต่างจาก CI ที่ pin kubectl v1.34.1 จึงยังไม่ทดแทนผล CI ตาม toolchain ที่ pin ไว้
- ยังไม่ได้รัน strict kubeconform, actionlint หรือ promtool รอบนี้: ไม่มี binaries ที่ต้องใช้; Prometheus validator ไม่มี pinned local binary/image ที่พร้อมใช้
- ไม่มี deployed frontend URL/build ID และ protected staging fixture inputs สำหรับ live browser/API mutation; ไม่ได้รัน live requests หรือสร้าง infrastructure

## ลำดับงานถัดไป

1. แก้ verifier protection predicate พร้อม negative test สำหรับ admin-enforcement-only response
2. รัน automated cases ส่วนที่เหลือ และตรวจ pinned schema/workflow/Prometheus tools ตาม CI
3. เติม browser journey กับ release/build evidence และกำหนด backup/restore + A/C gate linkage
4. เตรียม trusted published artifact, staging context/platform และ protected fixtures แล้วเริ่ม live acceptance ตามลำดับ B → C → S3/DB → A/D/Frontend

ยังไม่มีการเปลี่ยน implementation, commit, push หรือ deploy ในรอบ audit นี้ Backend working tree สะอาดอยู่บน `feat/kubernetes` หลักฐานและรายงานอยู่ภายนอก Git checkout

## รายงานรายสาย

- [Runtime / S3 / Database](C:/Users/User/Documents/Codex/2026-10-16/https-github-com-mudst-2026-luntungsadungyong-2/outputs/pr39-runtime-round1.md)
- [Supply chain / Kubernetes / Deployment adapter](C:/Users/User/Documents/Codex/2026-10-16/https-github-com-mudst-2026-luntungsadungyong-2/outputs/pr39-platform-round1.md)
- [Release / Observability / Smoke](C:/Users/User/Documents/Codex/2026-10-16/https-github-com-mudst-2026-luntungsadungyong-2/outputs/pr39-release-round1.md)

Raw logs/selection runners อยู่ใน workspace `work/round1-*`; parent review อยู่ใน `work/parent-review*.log` และ reproduction script `work/parent-review-protection.py` โดยเป็น offline evidence เท่านั้น
