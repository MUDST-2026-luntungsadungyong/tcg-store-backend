# Component D — Implementation status

วันที่: 2026-10-16 · repository: `C:/Users/Lenovo/tcg-store-backend` · branch: `feat/kubernetes`

สถานะ: Main implementation และ local/offline verification ผ่าน; Component D ยังไม่ปิด live acceptance และ full browser journey ยังไม่ implement ครบตามแผน งาน source อยู่บน branch `feat/kubernetes` และยังไม่ได้ deploy หรือเปลี่ยน Docker Desktop/ข้อมูล Neon/local

แผน: [Component D](./kubernetes-component-d-observability-release-plan.md)

Platform inputs/วิธีใช้ release flow: [Release operations](./kubernetes-component-d-release-operations.md)

แผนปิด live acceptance ตั้งแต่ Step 1 ถึง Component D: [Step 1–Component D live acceptance plan](./kubernetes-live-acceptance-plan.md)

ผลทดสอบจริง Step 1–2 วันที่ 2026-10-16: [Live acceptance results](./kubernetes-step-1-2-live-acceptance-results.md) — แก้ Flyway integration แล้ว; local migration 6 scenarios และ S3 validate-only สอง backend ผ่าน พร้อม cloud S3/Neon snapshot-local restore; ยังรอ Neon branch/Kubernetes/cloud replica acceptance

## Work board

Agents ชุดก่อนหยุดเพราะ usage limit และถูกปิดแล้ว งานบางส่วนที่เขียนไว้ยังต้องตรวจ ไม่ถือว่า complete จากการมีไฟล์อย่างเดียว ชุดใหม่ใช้ Luna 6 และทำงานบน checkout หลักร่วมกัน โดยแบ่ง write scope ไม่ซ้อนกัน

| งาน | Owner | State | Write scope | Gate ก่อนรับงาน |
|---|---|---|---|---|
| Logs / metrics | James → Main review | Accepted locally | Java observability/S3 adapters/tests, `pom.xml`, `application.yml`, `SecurityConfig.java` | Full Maven 195 tests, 0 failure/error, 2 live-DB skips; JSON/redaction/MDC, actual scrape metrics/CORS/probes ผ่าน |
| Monitoring | Boyle → Main review | Accepted locally | `infra/observability`, monitoring ingress policy, observability runbook | Main รัน promtool ซ้ำผ่าน: 8 recording / 21 alert rules และ rule tests; dashboard 4 contract tests ผ่าน |
| Release trust / receipts / Lease / recovery | Averroes → Main review | Accepted locally | Release core/schema/tests และ recovery/restore runbooks | Main รัน pytest จริง 54 cases ผ่าน; exact digest/bundle/inventory binding, Lease ownership, ไม่ reverse migration |
| Deployment orchestration | Linnaeus → Main review | Accepted locally | Orchestrator/tests, staging/production/release-validation workflows, actionlint config | Main รวมตรวจ phase/failure/observation/promotion/retention และ actionlint ผ่าน |
| Smoke harness | McClintock → Main review | Accepted locally | `scripts/smoke`, smoke runbook | Main รัน 10 mocked tests ผ่าน; production mutation rejected before request, provider public-base-path scope และ cleanup |
| Independent integration tests | Heisenberg → Main review | Accepted locally | `test_release_integration.py` | Agent เพิ่ม 6 real-receipt integration cases; main เพิ่ม retention/reused-ID/production promotion รวมเป็น 9 integration cases |
| Shared integration / final review | Main | Accepted locally | C renderer/deploy adapter/tests, dashboard contracts, integration refinements และแผน/status docs | Combined verification ผ่าน; แยก live/unfinished gates ชัดเจน |

State ใช้ Running → Review → Accepted locally; Accepted locally ไม่ใช่ merged หรือ production-ready งานทุกส่วนยังอยู่บน branch เดิมและต้องผ่าน PR/CI/live acceptance ก่อน merge ปิด agents ทั้ง 6 ตัวหลังส่งงานและตรวจรอบแก้แล้ว

## Shared contracts

- Bundle schema v2: full application/migration/prerequisites, derived `bootstrap.yaml`, `ci-prerequisites.yaml`, `ci-application.yaml`, bundle/config hashes
- CI ไม่ apply Namespace/ServiceAccount ไม่อ่าน Secret values และใช้ readiness metadata จาก protected platform path
- `app.kubernetes.io/managed-by=tcg-release` ใช้เฉพาะ release-controlled resources; bootstrap และ unique migration Jobs ไม่อยู่ใน prune inventory
- Recovery ต้องตรวจ verified prior/current inventories และ live ownership ก่อนลบชื่อใด ๆ ห้าม prune ทั้ง namespace หรือย้อน SQL
- Migration fail/unknown compatibility → recovery-required; monitoring no-data → hold ไม่ถือว่า healthy และไม่ rollback เพียงเพราะ exporter ขาดข้อมูล
- Staging/production ใช้ published index digest เดิม และ trusted build/deployment run identity
- Candidate bundle เจ็ดไฟล์เก็บก่อน cluster calls ที่ `<state-root>/<environment>/releases/<release-id>/candidate-bundle` เพื่อให้ failure receipt ยังตรวจ hash ได้หลังจบ process; accepted bundle แยกที่ `<state-root>/<environment>/bundles/<release-id>`
- Production approval/staging-run linkage เก็บใน `<state-root>/production/<release-id>-promotion-evidence.json`; ไม่ snapshot Secret values ลง artifact

## Verification evidence

ผลจาก agents ถูก main ตรวจ log/diff และรัน Python/PowerShell/schema/monitoring/workflow suites ซ้ำแล้ว ผล local ไม่ใช่ live acceptance

- Application: focused 20 tests ผ่าน และ full Maven 195 tests, 0 failures, 0 errors, 2 skipped; main ตรวจ full-run evidence ที่ `target/component-d-application-tests.log` การข้ามสองรายการคือ PostgreSQL checkout concurrency และ TiDB live validation ที่ต้องใช้ฐานข้อมูลจริง ไม่ใช่ผ่านโดยไม่ได้รัน
- C renderer: main รัน 30 regression tests ผ่าน ไม่มี skip รวม schema v2/ownership/environment, approved v1 cloud recovery และ strict Traefik negative tests
- Strict kubeconform: release resources 48 + local dependencies 7 = 55 resources ผ่าน ไม่มี error/skip
- Deploy adapter: main รัน mocked tests ผ่าน รวม CI ไม่อ่าน Secret/ไม่ apply bootstrap, stolen/expired Lease, เสีย Lease ระหว่าง migration แล้วต้อง block API และ actual UTC progress/atomic publication
- Monitoring: main รัน promtool config/rules/unit tests ผ่าน (8 recording / 21 alert rules), dashboard 4 tests ผ่าน; แก้ grid ไม่ให้ซ้อน และเลือก environment ได้แม้ scrape หายทั้งหมด
- Release core: main รัน pytest 54 tests ผ่าน รวม wrong run/ref/repo/digest/ZIP/index/signature/attestation failures, receipt/bundle/inventory binding, time validation, Lease/CAS และ recovery decisions
- Orchestration: main รัน unittest 21 tests ผ่าน (12 contracts + 9 integration cases ใช้ real receipts/recovery APIs); รวม success persistence, failed-bundle retention, reused release ID rejection, migration failure, safe prior config/application restore, read-only rollback smoke, no-data hold และ production same-digest/run-linkage แบบจำลอง
- Smoke API: main รัน 10 mocked tests ผ่าน; browser discovery พบ 2 read-only tests แต่ยังไม่ได้รัน browser กับ deployed frontend
- Workflow: main รัน actionlint ผ่านทั้ง staging/production/release-validation/kubernetes-manifests; GitHub Actions jobs ยังไม่ได้รันบน GitHub

Python รวม 119 tests ผ่าน; full Maven มี 195 tests รวม 2 external-DB skips ไม่รวม promtool rule scenarios/PowerShell scenarios เป็น Python test count

## จุดที่ main review ส่งกลับแก้/รวมเพิ่ม

- แก้ histogram tests ให้ยอมรับ standard `le` bucket tag โดยยังจำกัด application metric labels; ตรวจ metric names จาก scrape จริง
- แก้ Boot/Jackson test wiring และ actuator denial expectation โดยไม่เปิด endpoint เพิ่มเพื่อให้ tests ผ่าน
- Suppress known JDBC/generated-password startup emitters พร้อม synthetic sentinel tests; access log ไม่บันทึก credential/query/body
- เพิ่ม explicit `APP_ENVIRONMENT`, exact monitoring namespace AND pod selector และ resource ownership ให้ schema v2 โดยคง approved v1 recovery readability
- ตรวจ Lease ก่อนทุก CI apply หลัง migration wait; ไม่ใช้ outer lock check อย่างเดียว และแก้ PowerShell automatic JSON datetime conversion ให้รักษา UTC
- แก้ B handoff ให้ตรวจ provenance ที่ index และ SPDX ที่ child ตาม publisher จริง ไม่เรียกหา index SBOM ที่ B ไม่ได้สร้าง
- บังคับ actual retained bundle/image/config/inventory กับ accepted receipt, UTC calendar/instant comparison และ observation อย่างน้อย 600 วินาที
- รอ collector-backed observation แบบ bounded; ไม่สร้าง start time ย้อนหลัง และไม่ rollback จาก monitoring no-data อย่างเดียว
- เก็บ failed candidate bundle, atomic accepted state และ exact staging receipt+bundle artifact; recovery ไม่ rerun migration เก่า
- Fix dashboard panels ไม่ซ้อน/เลือก environment ได้เมื่อ scrape หาย และ provider image path scope ตาม exact reviewed public base URL

## External acceptance ที่ยังต้องทำ

1. Kubernetes context/platform, namespace/bootstrap, short-lived CI identity และ protected readiness source
2. Actual B protected-main publish/signature/provenance/SBOM/index/child verification
3. Private Prometheus scrape, exporter signals, log destination/retention, authenticated dashboards และ alert receiver
4. Staging fixture identity/accounts, frontend build ID/URL, real TLS/CORS/network isolation และ API/browser smoke
5. Two-replica/outage/HPA/drain/rollback tests จาก A/C และ measured database/object restore rehearsal
6. Protected production approval และ same-digest promotion พร้อม fresh observation evidence

## Implementation/verification ที่ยังไม่ควรตีความว่าครบ

- Full browser journey login → seller upload → cart → checkout ยังไม่ได้ implement เป็น automated browser suite; API mutation harness กับ browser read-only checks ไม่ทดแทนข้อนี้
- Platform collector/exporters/log pipeline/receiver deployment เป็น external integration ที่ยังไม่ได้ติดตั้ง ไม่ใช่สิ่งที่ application workflow สร้างให้อัตโนมัติ
- Controlled edge 413/429 tests, private probes, actual publish/attestation/approval/rollback/restore และ A/C disruption/load gates ยังไม่มี live evidence
- Production orchestration ผ่าน offline same-digest integration case แต่ยังไม่ยืนยัน real GitHub protected approval, artifact download/authentication หรือ cluster admission/RBAC

ไม่มีการใช้ fixture/mocked result เพื่อปิด live acceptance และไม่มีการอ่าน credential จาก `.env` มาบันทึกในเอกสารนี้

## สรุปสิ่งที่เหลือต้องทดสอบก่อนปิด Step 1–Component D

Automated local/offline gates ผ่านแล้วตามหัวข้อ Verification evidence ด้านบน รายการต่อไปนี้เป็น acceptance ที่ยังต้องใช้ environment, artifact หรือ credential จริง ห้ามใช้ fixture/mock ปิดรายการ

| ส่วน | สิ่งที่เหลือต้องทดสอบ | หลักฐานขั้นต่ำสำหรับปิดงาน |
|---|---|---|
| Step 1 — S3 object storage | ย้ายรูปเดิมเข้า bucket จริงและอัปเดต database URL; upload ผ่าน API replica หนึ่งแล้วอ่านผ่านอีก replica; Pod replacement แล้วยังอ่านได้; transaction rollback ลบ object ใหม่; shared object reference ไม่ถูกลบก่อน listing สุดท้าย | Object inventory ก่อน/หลังแบบไม่เปิดเผย credential, database reference audit, two-replica request IDs และ restart/rollback evidence ทั้ง local S3Mock กับ cloud S3-compatible storage |
| Step 2 — Versioned migration | Fresh database, upgrade จาก schema เดิม, repeat migration, migration failure ต้องหยุด API rollout และ backup/restore บน Neon/PostgreSQL จริง | Flyway history/validate, row-count หรือ checksum ที่ไม่เปิดเผยข้อมูลลูกค้า, failed-migration release record และ restore rehearsal record |
| Component A — Reliability | Database outage/recovery โดย liveness ยัง UP และ readiness DOWN/ฟื้นเอง; two-replica shared state; SIGTERM/in-flight request; rolling update/failed rollout/rollback; HPA/load และ connection budget | Cluster events, probe timeline, request success/error totals, Pod placement/HPA samples และ DB connection peak เทียบ quota จริง |
| Component B — Supply chain | Protected-main build/publish ไป GHCR; amd64/arm64 index; vulnerability/secret gates; Cosign signature; provenance ที่ index; SPDX SBOM ที่ child manifests; API/migration ใช้ digest เดียวกัน | Successful trusted workflow run ID, immutable index/child digests, verification output และ image contract artifact |
| Component C — Environments/edge | Render ด้วย non-secret inputs และ digest จริง; server-side admission; Secret delivery/RBAC; DNS/TLS renewal/HTTP redirect; `/api` routing โดยไม่เปิด `/actuator`; upload limit 8 MiB; rate limit; CORS; NetworkPolicy allowed/denied paths; Neon/S3/DNS egress | Retained schema-v2 bundle, admission output, certificate/routing responses, 413/429/CORS samples และ CNI connectivity matrix บน target cluster |
| Component D — Observability/release | Private Prometheus scrape; exporter/log pipeline/dashboard/alert receiver; observation window อย่างน้อย 600 วินาที; staging migration/rollout/smoke; full browser login → seller upload → catalog/cart/checkout/authorization; protected production approval/same-digest promotion; conditional rollback และ database/object restore | Accepted staging receipt+bundle, collector-backed observation evidence, browser report ผูกกับ frontend/backend build IDs, production promotion evidence และ recovery/restore records |

ลำดับที่แนะนำคือปิด B trusted artifact ก่อน แล้วใช้ digest เดิมทำ C admission/staging จากนั้นรัน A disruption/load และ D observability/full-stack gates บน staging ชุดเดียวกัน เมื่อหลักฐานทั้งหมดผ่านจึงทำ production approval/promotion โดยไม่ build image ใหม่
