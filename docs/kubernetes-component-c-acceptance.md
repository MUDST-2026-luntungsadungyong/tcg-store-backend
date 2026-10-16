# Component C — Deployment inputs and acceptance

เอกสารนี้ใช้คู่กับ [Component C plan](./kubernetes-component-c-platform-edge-plan.md), [platform contract](./kubernetes-component-c-platform-contract.md) และ [local dependencies](./kubernetes-local-dependencies.md)

## ข้อมูลที่ต้องเตรียม

ก่อน deploy staging/production ต้องมี Kubernetes context, namespace, Traefik v3 พร้อม CRDs, CNI ที่ enforce NetworkPolicy, namespaced certificate Issuer และ DNS ที่ชี้มาถูก edge

Secret `tcg-store-backend-secrets` ต้องถูกส่งเข้า namespace จากช่องทางนอก Git โดยใช้ keys ต่อไปนี้:

- Runtime DB: `neon-jdbc-url`, `neon-db-username`, `neon-db-password`
- Migration DB: `neon-migration-jdbc-url`, `neon-migration-db-username`, `neon-migration-db-password`
- API: `app-jwt-secret`, `object-storage-access-key`, `object-storage-secret-key`

Runtime role ให้สิทธิ์เฉพาะที่ API ต้องใช้; migration role ให้สิทธิ์แก้ schema และใช้ direct Neon endpoint แยกจาก pooled runtime endpoint ใช้ `sslmode=verify-full` และ trust configuration ที่ตรวจ certificate/hostname ได้ ห้ามบันทึก JDBC URL หรือ credentials ใน release JSON

การตรวจชื่อ Secret keys ไม่ใช่การตรวจค่าที่อยู่ข้างใน ต้องทดสอบ JDBC TLS/direct endpoint กับ environment จริงโดยไม่พิมพ์ค่า Secret ก่อนปิด acceptance

Release JSON มีเฉพาะ non-secret configuration ได้แก่ image digest, API hostname, frontend origin, storage endpoint/region/bucket/public URL/path style, ingress class และ TLS issuer ใช้โครง key จาก `scripts/kubernetes/fixtures/ci-release.json` แล้วเติมค่าจริงในไฟล์นอก Git ข้อมูล fixture ไม่ใช่ release ที่ deploy ได้

Production ต้องรับ published/verified registry digest จาก Component B ห้ามใช้ local Docker image ID แทน registry manifest digest

## Render และตรวจจากเครื่อง

รันจาก root ของ backend โดยเก็บ Python tools และ rendered artifacts ใน `target` ที่ Git ignore:

```powershell
python -m venv target/kubernetes-tools
./target/kubernetes-tools/Scripts/python.exe -m pip install -r scripts/kubernetes/requirements.txt

# Local ใช้ dev image และ local endpoint จาก overlay โดยตรง
./target/kubernetes-tools/Scripts/python.exe scripts/kubernetes/render.py render `
  --environment local --output-dir target/kubernetes-rendered --validate

# Cloud ใช้ JSON ที่มี non-secret inputs จริงและ published image digest
./target/kubernetes-tools/Scripts/python.exe scripts/kubernetes/render.py render `
  --environment staging --config C:/secure-local/staging-release.json `
  --output-dir target/kubernetes-rendered --validate
```

เปลี่ยน `--environment` เป็น `production` สำหรับ production inputs ห้ามใช้ fixture JSON ของ CI ไป deploy Output schema v2 แต่ละ environment มี `prerequisites.yaml`, `migration.yaml`, `application.yaml` และ `bundle.json` พร้อม bundle/config hashes และ derived files `bootstrap.yaml`, `ci-prerequisites.yaml`, `ci-application.yaml` โดย deploy adapter ตรวจ derivation และ hashes ซ้ำก่อนติดต่อ cluster

`bootstrap.yaml` มี Namespace และ runtime ServiceAccount ให้ platform owner เตรียมไว้ก่อน CI identity ไม่ต้องสร้าง resource สองชนิดนี้ ส่วน CI apply เฉพาะ namespaced prerequisites/application และ release-specific migration Job; `APP_ENVIRONMENT` ต้องตรงกับ environment จริง เพื่อไม่ให้ logs/metrics ของ cloud แสดงเป็น local

Schema gate ใน `.github/workflows/kubernetes-manifests.yml` ใช้ kubectl `v1.34.1`, kubeconform `v0.7.0` และ Traefik CRD schema `v3.5.0` ต้องตรวจ versions/CRDs ของ cluster เป้าหมายให้เข้ากัน รวมถึง server-side admission dry-run ที่ยังต้องใช้ context จริง

หลังเตรียม namespace/Secret/platform ครบแล้วจึงใช้ adapter:

```powershell
./scripts/deploy-kubernetes.ps1 `
  -Context staging-cluster -Environment staging `
  -ManifestDirectory target/kubernetes-rendered/staging `
  -ReleaseId release-20261016-01 `
  -Python ./target/kubernetes-tools/Scripts/python.exe
```

ชื่อ context ในตัวอย่างต้องเปลี่ยนเป็น context เป้าหมายที่ตั้งใจ deploy; เอกสารนี้ไม่ได้สร้างหรือเลือก cluster อัตโนมัติ

ตัวอย่างข้างบนเป็น manual/operator flow ซึ่งต้องมีสิทธิ์ `get Secret` และแสดงเฉพาะชื่อ keys โดยไม่พิมพ์ค่า แต่สิทธิ์นี้ยังสามารถอ่าน Secret data ผ่าน API ได้ จึงไม่ใช่ identity ที่ให้ CI ใช้

Component D ใช้ `-CIMode` ร่วมกับ `-SecretReadinessFile`, `-ProgressFile`, `-LeaseName` และ `-LeaseHolderIdentity` จาก release orchestrator เท่านั้น readiness เป็น metadata ที่ platform operator เขียนไว้ใน protected path ไม่ใช่การอ่าน Secret จาก CI ทุก apply ตรวจ Lease holder/namespace/expiry ซ้ำ รวมถึงหลัง migration wait ก่อน apply API หากเสีย lock ต้องหยุด ไม่ข้าม guard เพื่อให้ deploy ผ่าน

Progress JSON ระบุ actual UTC `startedAt` และ `rolloutCompletedAt` และ publish ด้วย atomic rename เพื่อให้ observation ตรวจช่วงเวลาหลัง rollout ได้ การมีไฟล์ progress ไม่ใช่ accepted release ต้องผ่าน smoke/observation และมี verified release receipt ของ D ต่อด้วย

Resource ที่อยู่ใน release-controlled inventory ของ schema v2 มี top-level label `app.kubernetes.io/managed-by=tcg-release` โดยไม่ claim ownership ของ Namespace, ServiceAccount หรือ unique migration Jobs Recovery ต้องตรวจทั้ง verified exact inventory และ live ownership ก่อนลบ resource ใด ๆ label อย่างเดียวไม่อนุญาต prune

## ลำดับ deploy

1. Render bundle ของ environment โดย inject image digest เดียวกันให้ API และ migration
2. ผ่าน schema/policy validation และ server-side admission dry-run บน target cluster
3. เตรียม namespace, Secret delivery, issuer และ controller prerequisites ให้พร้อม
4. ใช้ deploy adapter ที่ระบุ context ชัดเจน รัน migration Job ที่มีชื่อเฉพาะ release
5. รอ migration สำเร็จก่อน apply API Deployment
6. ตรวจ HTTPS, upload, CORS และ allowed/denied network paths
7. ทำ Component A live tests บน staging ชุดเดียวกัน

การ apply ConfigMap/NetworkPolicy เป็น shared prerequisite อาจกระทบ workload รุ่นเก่าก่อน rollout ต้องใช้ config/schema ที่รองรับรุ่นเก่าและ review diff ก่อน release การเปลี่ยน Secret ที่เป็น environment variable มีผลกับ Pod ใหม่; การ sync Secret สำเร็จอย่างเดียวไม่ได้ restart API โดยอัตโนมัติ

Job ที่สำเร็จแล้วไม่ถูกรันซ้ำด้วยการ apply เฉย ๆ ใช้ release ID ใหม่เมื่อสร้าง Job ใหม่ และอย่า deploy สอง release พร้อมกันใน environment เดียวกัน Component D ต้องกำหนด concurrency gate และ promotion approval

หาก migration fail ให้หยุด rollout แล้วตรวจเหตุผลผ่านช่องทางที่ redact credentials หาก API rollout fail หลัง migration ผ่าน ต้องตรวจ schema compatibility ก่อน rollback API; การ rollback Deployment ไม่ย้อน schema หรือข้อมูล

## Acceptance ที่ต้องบันทึกหลักฐาน

| พื้นที่ | หลักฐานที่ต้องมี |
|---|---|
| Artifact | Published digest ของ API และ migration ตรงกัน; signature/attestation ผ่านจาก B |
| Config | Namespace/data references แยก local/staging/production; ไม่มี placeholder ใน release render |
| Secret | Provider ส่ง key ครบ; ไม่มี value ใน Git/log/artifact; runtime/migration role แยกกัน |
| HTTPS | DNS/certificate/hostname ถูกต้อง; HTTP redirect หรือ reject ตาม platform contract |
| Routing | `/api` ใช้งานได้; `/actuator` และ unknown host ไม่ route เข้า backend |
| Upload | ภาพที่ application รองรับผ่านได้; request เกิน 8 MiB ถูก edge ปฏิเสธ |
| Rate limit | API/auth limit ทำงาน; ทดสอบ client IP หลัง proxy และหลาย controller replicas |
| CORS | Frontend origin ที่กำหนดผ่าน; origin อื่นไม่ได้รับ CORS allow response |
| Network | Traefik เข้า API ได้; unauthorized Pod ถูกบล็อก; DNS/Neon/S3 ติดต่อได้ |
| Migration | Job ผ่านก่อน rollout; failure case ไม่ apply API รุ่นใหม่; repeat release ID ถูกปฏิเสธ |
| Reliability A | Two-replica state, outage/recovery, graceful rollout, failed rollout/rollback, HPA และ connection budget |

NetworkPolicy ชุดนี้อนุญาต public IPv4 ตาม port 5432/443 โดยมี excluded ranges จึงไม่ใช่การจำกัด destination เฉพาะ Neon/S3 ต้องบันทึก exception นี้หรือใช้ FQDN policy/egress gateway ของ platform ก่อน production acceptance Kubernetes อนุญาต traffic บางประเภทจาก node ตาม semantics ของ NetworkPolicy จึงต้องใช้ diagnostic Pod ต่าง namespace ใน denied-path test

## แหล่งอ้างอิง

- [Traefik v3.5 Ingress annotations](https://doc.traefik.io/traefik/v3.5/reference/routing-configuration/kubernetes/ingress/)
- [Traefik v3.5 buffering](https://doc.traefik.io/traefik/v3.5/reference/routing-configuration/http/middlewares/buffering/)
- [Kubernetes NetworkPolicy semantics](https://kubernetes.io/docs/concepts/services-networking/network-policies/)
- [Neon Flyway direct connection](https://neon.com/docs/guides/flyway)
