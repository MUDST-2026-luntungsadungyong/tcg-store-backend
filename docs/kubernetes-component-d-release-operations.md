# Component D — Release operations and pending platform inputs

เอกสารนี้อธิบาย workflow ที่เตรียมไว้ ไม่ใช่หลักฐานว่า deploy แล้ว และไม่ใช่คำสั่งให้รันกับ production ทันที

สถานะ/ผลทดสอบ: [Implementation status](./kubernetes-component-d-implementation-status.md) · แผน: [Component D](./kubernetes-component-d-observability-release-plan.md)

## ก่อนเปิดใช้งาน workflow

1. รวม source revision ที่มี A/B/C/D ตามกระบวนการ review ของทีม แล้วให้ B publish/sign/attest image ของ protected `main` จริง รับ exact build run ID ไม่ใช้ local image ID หรือ tag ที่เปลี่ยนได้
2. Platform owner เตรียม cluster/context, CNI, Traefik/CRDs, DNS/TLS issuer, Namespace/runtime ServiceAccount, external Secret delivery และ CI identity แบบ short-lived ก่อน ไม่ใช้ runtime identity ไป deploy
3. จัด GitHub Environments `staging` และ `production` ให้มี required reviewers และจำกัดการใช้ protected `main`; label runner `tcg-deploy` ต้องเป็น trusted runner ที่ PR/untrusted code ใช้ไม่ได้
4. Runner มี Python/PyYAML ตาม pinned requirements, PowerShell, kubectl, Docker Buildx, GitHub CLI และ Cosign รุ่นที่ platform ตรวจแล้ว ให้สิทธิ์ registry/attestation ที่จำเป็นเท่านั้น
5. จัด protected persistent release-state path และ platform collector แยกจาก application ไม่วาง credentials หรือ Secret snapshots ใน bundle/artifact

Private GHCR images require a namespace-scoped `kubernetes.io/dockerconfigjson` Secret named
`tcg-store-ghcr-pull` in both `tcg-store-staging` and `tcg-store-production`. The staging and
production API and migration workloads reference this name through `imagePullSecrets`. A platform
operator or external-secret controller must provision it before deployment with a read-only GHCR
credential; the release workflow must not render, read, or persist the registry credential.

การตรวจ attestations ใช้ read permission ไม่ต้องให้ workflow นี้ publish/sign artifact ใหม่ ให้ platform ตรวจ GitHub token scope และ registry authentication ที่ใช้งานจริงตาม [GitHub attestation permissions](https://docs.github.com/en/rest/repos/attestations#list-attestations) และ [GitHub CLI verification](https://cli.github.com/manual/gh_attestation_verify) โดยเฉพาะ private repository/registry

## Environment variables ของ GitHub Environment

| Variable | ข้อมูลที่ต้องให้ |
|---|---|
| `TCG_KUBE_CONTEXT` | ชื่อ context ของ environment ที่ตั้งใจ deploy ห้ามเลือก default context อัตโนมัติ |
| `TCG_RELEASE_CONFIG` | Absolute path ของ non-secret release JSON ตาม C contract; ไม่ใส่ JDBC/credentials |
| `TCG_SECRET_READINESS` | Protected file ที่ platform operator เขียน readiness metadata ของ Secret contract |
| `TCG_OBSERVATION` | Protected file ที่ collector เขียน fresh release-bound monitoring evidence |
| `TCG_RELEASE_STATE_ROOT` | Persistent state root สำหรับ receipts, retained bundles, progress และ last-known-good pointer |
| `TCG_RELEASE_PROGRESS_ROOT` | ต้องตรงกับ state root; collector ใช้อ่าน actual progress ของ release |
| `TCG_API_URL` | Exact HTTPS API origin ของ environment |
| `TCG_MIGRATION_COMPATIBILITY` | ผล review migration; accepted flow ต้องเป็น `backward-compatible` ไม่ใช่การตั้งค่าเพื่อข้าม review |

Secret readiness metadata ประกอบด้วย schema/issuer/environment/namespace/Secret name/ready/checkedAt/keyNames เท่านั้น issuer เป็น `platform-operator`, keyNames ต้องตรง nine-key contract และ checkedAt ต้องสดภายใน 15 นาที ห้ามเขียน Secret values ลง file นี้ การมี JSON ใน checkout หรือ input ที่คนทั่วไปเขียนได้ไม่ใช่ trusted readiness

CI identity ต้องถูกปฏิเสธการ get/list Secret และสร้าง Namespace; bootstrap/Secret delivery อยู่นอก workflow นี้ รายละเอียด runtime/migration roles และ key names อยู่ใน [C acceptance](./kubernetes-component-c-acceptance.md)

Smoke credentials และ known test-owned object URL ส่งผ่าน protected runner/secret channel ตาม [smoke runbook](./runbooks/full-stack-smoke.md) ไม่ใช้ workflow inputs/command arguments สำหรับ password/token; CORS origin รับจาก validated release configuration

For staging, configure `TCG_SMOKE_BUYER_EMAIL`, `TCG_SMOKE_BUYER_PASSWORD`,
`TCG_SMOKE_KNOWN_OBJECT_URL`, and `TCG_SMOKE_STORAGE_HOST` as `staging` GitHub Environment
secrets. The workflow maps those four names explicitly into the preflight and smoke process only;
do not store them as repository variables because variables are not masked.

## Collector / observation contract

Collector ต้องอ่าน `<state-root>/<environment>/releases/<release-id>/deploy-progress.json` ที่ helper publish แบบ atomic และเริ่มประเมินหลัง actual `rolloutCompletedAt` เท่านั้น

Evidence ที่ orchestrator รับต้องมี `environment`, `releaseId`, `imageDigest`, `rolloutCompletedAt`, `windowStartedAt`, `observedAt`, `sampleCount`, `observationWindowSeconds`, `status` และ `noData` โดย:

- ผูกกับ release/environment/index digest และ actual rollout timestamp เดียวกัน
- window เริ่มหลัง rollout สำเร็จและครอบคลุมอย่างน้อย 600 วินาทีจริง ไม่ backdate หรือสร้างเวลาเริ่มย้อนหลัง
- `observedAt` สดไม่เกิน 2 นาที และมี qualifying samples
- Missing/stale/unhealthy/no-data ต้องไม่กลายเป็น healthy; hold promotion และบันทึก failed/recovery evidence ไม่ rollback เพราะ telemetry ขาดอย่างเดียว

Orchestrator รอ evidence แบบ bounded พร้อมตรวจ Lease; repository นี้ยังไม่ได้ติดตั้ง platform collector หรือยืนยัน live Prometheus/log/alert destinations ห้ามเขียน `healthy` ด้วยมือเพื่อปิด acceptance การส่ง metrics ได้ไม่ใช่หลักฐาน quota, replica availability หรือ S3 consistency โดยอัตโนมัติ ต้องอาศัย signals/fixture ตามแผน

## Staging → production

- `deploy-staging.yml` รับ exact B build run ID กับ immutable release ID ตรวจ image identity/index/child digests/signature/provenance/SBOM ก่อน render/admission/migration/rollout/smoke/observe
- CI ใช้ derived namespaced manifests ไม่ apply bootstrap และ helper ตรวจ Lease ก่อนแต่ละ apply รวมถึงหลัง migration wait ก่อน API
- Accepted staging artifact มี receipt และ retained non-secret v2 bundle เจ็ดไฟล์ ไม่ใช่ receipt JSON อย่างเดียว
- `deploy-production.yml` รับ build run ID, staging run ID และ release ID เดิม ดาวน์โหลด artifact จาก staging run ที่ระบุเท่านั้น ตรวจ successful main workflow identity และ receipt/bundle binding แล้ว promote index digest เดิม ไม่ build ซ้ำ
- ห้าม reuse release ID ที่มี persisted state แล้ว และห้าม candidate เก่าทับ independently verified last-known-good โดยใช้ deploy flow ปกติ
- Rollout/smoke failure คืน prior verified configuration/application เฉพาะเมื่อ backward-compatible และ rollback health checks ผ่าน ไม่รัน migration เก่าหรือ reverse SQL; workflow ที่ล้มยังคงล้มแม้คืนรุ่นก่อนสำเร็จ

Recovery/restore: [Release recovery](./runbooks/kubernetes-release-recovery.md), [Data restore rehearsal](./runbooks/data-restore-rehearsal.md)

Bundle ของ candidate ทั้งสำเร็จและล้มถูกเก็บที่ `<state-root>/<environment>/releases/<release-id>/candidate-bundle` ก่อน cluster calls เพื่อให้ตรวจ hash ของ failure receipt ย้อนหลังได้ ส่วน accepted bundle อยู่ที่ `<state-root>/<environment>/bundles/<release-id>` และ production promotion linkage อยู่ที่ `<state-root>/production/<release-id>-promotion-evidence.json` ให้ platform กำหนด retention/access control ของ state root โดยไม่ใส่ Secret snapshots

## Acceptance ที่ยังไม่เกิดจากการมี workflow

ต้องรันกับ real staging พร้อม exact fixture/credentials และ frontend build identity แล้วเก็บ TLS/CORS/private scrape/network isolation, two-replica/outage/HPA/drain/rollback และ DB/object restore evidence ก่อน production approval จริง Full browser login/upload/cart/checkout acceptance ยังไม่ปิดจาก browser read-only checks สองรายการใน repository นี้
