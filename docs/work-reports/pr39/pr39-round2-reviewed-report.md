# PR #39 — ผลตรวจครึ่งหลังและผลรวมสองรอบ

วันที่ 2026-10-16 (Asia/Bangkok) · branch `feat/kubernetes` · commit `b859aa13689c87135dc2f9ce088e0c63d1c13560`

ใช้ `gpt-6-luna` 3 agents ชุดเดิมทำครึ่งที่เหลือของแต่ละสาย Main ตรวจรายงาน, raw logs, archived Surefire XML, รายการ test IDs และรันทวนจุดสำคัญด้วยตัวเอง ไม่มีการเปลี่ยน implementation, commit, push หรือ deploy

## ผลครึ่งหลัง

| ชุด | ครึ่งหลัง | ผลรวมกับรอบแรก |
|---|---:|---:|
| S3/config/metrics/multipart API | ผ่าน 5 | ผ่าน 12/12 |
| Runtime probes/shutdown | ผ่าน 1 | ผ่าน 3/3 |
| Java correlation/logging/metrics/CORS | ผ่าน 4 | ผ่าน 9/9 |
| Artifact verifier | ผ่าน 5 | ผ่าน 9/9 |
| Kubernetes renderer | ผ่าน 15 | ผ่าน 30/30 |
| Release core/orchestration/dashboard/API smoke | ผ่าน 38 | ผ่าน 80/80 |

ครึ่งหลังผ่าน Java 10 และ Python 58 cases ไม่มี failures/errors/skips ในผลสุดท้าย รวมสองรอบเป็น Java 24/24 methods ในขอบเขตที่เลือก และ Python 119/119 cases ของ 9 test files ที่ inventory ไว้

Main recollect Python ทั้ง 9 files แล้วเทียบ test IDs กับ selections สองรอบ: 61 + 58 = 119, ไม่ซ้ำและไม่มีตกหล่น การรันทวนของ main ไม่นำมาบวกเพิ่มเป็นจำนวน unique cases

Java 24 methods เป็น targeted inventory ของ PR รอบ audit นี้ ไม่ใช่ full Maven suite ทั้ง repository; ไม่ได้อ้างว่ารัน full `verify` หรือ 195 tests ใหม่ รายการ Flyway fresh/legacy/repeat/restore ไม่มี dedicated Maven cases ใน inventory นี้ จึงยังไม่มีหลักฐานใหม่จากการรันกับฐานข้อมูลจริง

PowerShell deployment adapter scenario suite ผ่านไปแล้วในรอบแรกและไม่ได้รันซ้ำในรอบนี้

## Offline gates ที่ตรวจเพิ่มจนจบ

| Gate | เครื่องมือ/ขอบเขต | ผล |
|---|---|---|
| Strict Kubernetes schemas | kubectl v1.34.1 / Kustomize v5.7.1, kubeconform v0.7.0, schemas จาก official Traefik v3.5.0 CRDs | ผ่าน 55 resources: local 6, staging 21, production 21, local dependencies 7; invalid/errors/skipped = 0 |
| Traefik negative schemas | wrong field และ string แทน integer | ถูกปฏิเสธด้วยเหตุผลตรง schema ทั้งสอง fixture; ไม่ใช่ error เพราะหา schema ไม่พบ |
| Prometheus config/rules/tests | repository-pinned Windows promtool v3.15.0 | ผ่าน config, 8 recording rules, 21 alert rules และ rule tests |
| Workflow lint | actionlint v1.7.12 | ผ่าน 4 files: deploy-staging, deploy-production, release-validation, kubernetes-manifests |

ดาวน์โหลด tools จาก official release sources ลง workspace และตรวจ archive SHA256 ก่อนใช้; promtool ตรวจ executable SHA256 ตรงค่าที่ validator ใน repo pin ไว้ด้วย ไม่ได้แก้ global PATH หรือติดตั้ง service

Renderer ครึ่งหลังรอบแรกมี conditional schema-test skip 1 case เพราะยังไม่ส่ง tool/schema environment จากนั้น main เตรียม pinned tools และ official CRDs ให้ agent รันครึ่งหลัง 15 cases ซ้ำ ผลสุดท้ายผ่านครบและไม่มี skip ส่วน warning เรื่อง pytest cache permission ไม่กระทบ assertions

ครึ่งแรก renderer ใช้ kubectl v1.36.1 ของเครื่อง ครึ่งหลังและ strict schema gate ใช้ pinned v1.34.1 การรวมผลนี้เป็น coverage สองรอบบน Windows ไม่ใช่การอ้างว่าได้รัน GitHub Actions/Linux job จริง

## Main review

- ตรวจ archived Surefire XML ของครึ่งหลัง 6 classes รวม 10 methods: failures/errors/skips เป็นศูนย์ และตรง method inventory ที่เหลือ
- ตรวจ exact Python selections เทียบ collect-only: ครบ 119 cases ไม่มี overlap หรือ missing
- รันทวนอีก 6 cases ผ่าน: concurrent Lease rejection, heartbeat CAS failure, exact ownership ก่อน prune และ safe rollback หลัง rollout/smoke failure โดย pipeline ยังล้มตามเดิม
- รัน strict schema validation, Prometheus validator และ actionlint ด้วยตัวเองตามตารางข้างต้น
- ตรวจ negative schema logs ว่าปฏิเสธ `wrongField` และ `maxRequestBodyBytes` ที่เป็น string โดยไม่มี schema errors/skips
- ตรวจท้ายงาน: HEAD ยังเป็น `b859aa1`, Git working tree สะอาด และ `git diff --check` ผ่าน

## งานที่ยังค้าง

ไม่พบ failure หรือ defect ใหม่จากครึ่งหลัง แต่ findings ของรอบแรกยังไม่ได้แก้:

1. `verify_artifact.py` ยังนับ `enforce_admins=true` อย่างเดียวเป็นหลักฐาน protected main; main จำลองยืนยันไปแล้วในรอบแรก การที่ unit tests ปัจจุบันผ่านไม่ได้ปิดช่องว่างนี้
2. Full browser journey login → seller upload → catalog/cart → checkout → authorization ยังไม่มี suite ครบ และยังไม่ผูก frontend build/browser report เข้ากับ release evidence
3. Backup/restore และ A/C live acceptance ยังต้องมี external gate/หลักฐานที่ผูกกับ release โดย workflow/receipt ปัจจุบันยังไม่ได้ enforce โดยตรง
4. Live acceptance ยังเปิด: protected-main GHCR publish/attest, staging admission/RBAC/Secret delivery, TLS/CORS/413/429/CNI, real DB/S3 migration/restore, two-replica/disruption/load/HPA, collector/exporters/alert receiver และ production approval/promotion

ผล offline ไม่แทน real staging/production acceptance ไม่มีการใช้ fixture render หรือ mock smoke เพื่อปิด live checklist และรอบนี้ไม่ได้สร้าง cluster, เปลี่ยน cloud/database/bucket หรือรัน browser กับ deployed frontend

## รายงานและหลักฐาน

- [Runtime / S3 / Database — รอบสอง](C:/Users/User/Documents/Codex/2026-10-16/https-github-com-mudst-2026-luntungsadungyong-2/outputs/pr39-runtime-round2.md)
- [Supply chain / Kubernetes — รอบสอง](C:/Users/User/Documents/Codex/2026-10-16/https-github-com-mudst-2026-luntungsadungyong-2/outputs/pr39-platform-round2.md)
- [Release / Observability / Smoke — รอบสอง](C:/Users/User/Documents/Codex/2026-10-16/https-github-com-mudst-2026-luntungsadungyong-2/outputs/pr39-release-round2.md)
- [รายงานรวมรอบแรกและรายละเอียด findings](C:/Users/User/Documents/Codex/2026-10-16/https-github-com-mudst-2026-luntungsadungyong-2/outputs/pr39-round1-reviewed-report.md)

Raw evidence ที่เก็บใน workspace:

- `work/round2-runtime/`: exact methods, Maven log, archived Surefire XML/text
- `work/round2-platform/`: exact IDs, artifact/renderer logs และ negative schema logs
- `work/round2-release/`: complementary selection runner และ pytest log
- `work/round2-parent-coverage.json`: ผลตรวจ selections และ XML โดย main
- `work/round2-parent-review.log`: main rerun 6 cases
- `work/round2-schema/summary.json` และ `validation.log`: strict validation 55 resources
- `work/round2-prometheus.log`: config/rules/unit-rule validation
- `work/round2-actionlint.log`: ไม่มี diagnostic output และ process exit 0
- `work/round2-tool-downloads.log`: versions และ checksums ของ tools
