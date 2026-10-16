# Component B — Container and software supply-chain plan

สถานะ: Runtime และ release reproducibility acceptance ผ่าน; main-branch registry publish/attestation และ digest handoff ยัง pending

วันที่จัดทำ: 2026-10-16

Parent plan: [แผนทำ Backend ให้รองรับ Kubernetes และการกระจายโหลด](./kubernetes-backend-distribution-plan.md)

Component นี้รวม Step 4 และส่วน build/security ของ Step 8 โดยรับผิดชอบตั้งแต่ source code ถูกนำไป build จนได้ immutable image digest ที่ Component C และ D นำไป deploy ต่อได้

## 1. เป้าหมาย

- ได้ backend image ที่ build ซ้ำได้ มีขนาดและสิทธิ์เท่าที่จำเป็น
- ใช้ image เดียวกันสำหรับ API และ migration runner
- รองรับ `linux/amd64` และ `linux/arm64`
- ไม่ใช้ `latest` เป็น release contract
- สร้าง SBOM, provenance และลายเซ็นที่ตรวจย้อนกลับถึง Git commit ได้
- ให้ secret/dependency/image scan เป็น blocking gate ตาม policy
- ทำ Local/Cloud Compose ให้มี health dependency และ security behavior ใกล้ Kubernetes
- ส่งออก image tag และ digest แบบ machine-readable ให้ Component C/D ใช้ต่อ

## 2. Baseline และช่องว่างปัจจุบัน

| พื้นที่ | ปัจจุบัน | เป้าหมาย Component B |
| --- | --- | --- |
| Base image | `eclipse-temurin:21-*-alpine` แบบ mutable tag | pin version และ multi-arch digest |
| Runtime identity | user/group `spring` แต่ UID/GID ไม่ตายตัว | fixed numeric UID/GID เช่น `10001:10001` |
| JVM memory | ไม่มี container memory policy ที่ประกาศชัด | ใช้ percentage-based heap และ exit เมื่อ OOM |
| OCI metadata | workflow ใส่เพียง metadata พื้นฐาน | source/revision/version/created/licenses/title |
| Compose backend | ไม่มี healthcheck/read-only/capability policy | healthcheck, read-only root, `/tmp` tmpfs, drop capabilities |
| Local image tag | `tcg-store-service-backend:latest` | local tag `dev` หรือ tag ที่กำหนดได้ |
| Architecture | workflow build ค่า default architecture เดียว | `linux/amd64,linux/arm64` |
| Release identity | short SHA และ `latest` | full Git SHA tag และ digest |
| SBOM/provenance | ยังไม่มี | SPDX/CycloneDX SBOM และ SLSA-compatible provenance |
| Secret scan | Gitleaks `continue-on-error` | blocking gate |
| Vulnerability scan | Trivy action จาก `master`, exit code 0 | pin action และ block ตาม policy |
| Reproducibility | ยังไม่มีการเปรียบเทียบ build | fixed build timestamp และ rebuild comparison |

## 3. Target image contract

### 3.1 Dockerfile

1. ใช้ multi-stage build ต่อไป แต่ pin builder และ runtime image ด้วย version + digest
2. ใช้ runtime JRE เท่านั้น ไม่พก Maven cache, compiler หรือ source code ไป production layer
3. สร้าง user/group ด้วย UID/GID คงที่ `10001:10001`
4. ให้ `/app/app.jar` เป็นของ runtime user และไม่มีไฟล์อื่นที่เขียนได้โดยไม่จำเป็น
5. กำหนด JVM policy เริ่มต้นแบบ container-aware:
   - `-XX:InitialRAMPercentage=25.0`
   - `-XX:MaxRAMPercentage=75.0`
   - `-XX:+ExitOnOutOfMemoryError`
   - `-Djava.io.tmpdir=/tmp`
6. รับ OCI labels ผ่าน build arguments ได้แก่ title, description, source URL, revision, version, created และ licenses
7. ไม่ใช้ build arguments หรือ `ENV` สำหรับ credentials
8. ใช้ exec-form entrypoint เพื่อให้ Java เป็น PID 1 และรับ SIGTERM โดยตรง
9. API และ migration runner ต้องใช้ artifact `/app/app.jar` เดียวกัน

หมายเหตุ: digest ของ base image ต้องเลือกจาก official multi-architecture manifest ตอน implementation และบันทึกวิธีอัปเดต ห้ามใส่ digest ที่เดาเองลงแผน

### 3.2 Reproducible build

- ใช้ Git commit timestamp เป็น `SOURCE_DATE_EPOCH`
- กำหนด Maven output timestamp สำหรับ JAR ผ่าน `pom.xml` หรือ build property ที่ตรวจสอบได้
- lock dependency versions ที่ยังอาศัยค่าลอย
- build จาก clean Git context โดย `.dockerignore` ตัด `.git`, `.env*`, logs, IDE state, local agents, diffs และ generated artifacts
- release reproducibility ผ่าน Buildx exporter ที่เปิด `rewrite-timestamp=true`
- Docker Desktop Compose ใช้สำหรับ dev และไม่ถือเป็นหลักฐาน reproducibility ของ release image
- เก็บ provenance จริงใน publish job และบันทึก digest เฉพาะเมื่อมีผลรายงานยืนยันแล้ว

### 3.3 Runtime security contract

- process รันเป็น numeric non-root user `10001`
- root filesystem เป็น read-only
- mount `/tmp` เป็น tmpfs
- `no-new-privileges:true`
- drop Linux capabilities ทั้งหมด
- จำกัดจำนวน process ด้วย `pids_limit`
- ห้าม mount Docker socket หรือ host credential directories
- container ต้องเริ่มได้โดยไม่มี write access ใต้ `/app`
- image history และ exported filesystem ต้องไม่มี `.env`, access key, JWT secret หรือ database password

## 4. Canonical Compose plan

แก้เฉพาะ `docker-compose.desktop.yml` และคง Local/Cloud profiles เดิม

### 4.1 Backend และ migration

- เปลี่ยน local image จาก `:latest` เป็น `${BACKEND_IMAGE_TAG:-dev}`
- เพิ่ม backend healthcheck ที่ `/actuator/health/readiness`
- ใช้ common security anchor สำหรับ backend-local, backend-cloud และ migration runners
- เปิด `read_only: true`, `/tmp` tmpfs, `cap_drop: ALL`, `no-new-privileges`, `pids_limit` และ `stop_grace_period: 45s`
- migration runners ต้องใช้ image ID เดียวกับ backend และเขียนได้เฉพาะ `/tmp`
- migration success เป็น gate ก่อน backend เหมือนเดิม
- ไม่รวม filesystem upload volume กลับมา

### 4.2 Supporting services

- pin Postgres และ S3Mock ด้วย version + digest ที่รองรับ architecture เป้าหมาย
- bind Postgres host port กับ `127.0.0.1` เพื่อไม่เปิดฐานข้อมูลออก LAN โดยไม่ตั้งใจ
- คง named volumes `tcg-store-service_postgres_data` และ `tcg-store-service_s3mock_data`; ห้าม recreate/delete volume ในงาน hardening
- ตรวจ healthcheck ของ Postgres และ S3Mock ว่าใช้ binary ที่อยู่จริงใน image ที่ pin แล้ว
- frontend ยังใช้ service แยก ไม่รวมเข้า backend container

### 4.3 Compose acceptance

- `local` และ `cloud` profiles render ผ่าน
- Local flow: Postgres healthy → migration success → seed success → S3Mock healthy → backend ready
- Cloud flow: migration success → backend ready โดยใช้ Neon/S3 credentials จาก runtime environment
- backend container เขียน `/app` ไม่ได้แต่เขียน `/tmp` ได้
- `docker inspect` ยืนยัน non-root, dropped capabilities และ read-only root filesystem
- stop/restart ยังผ่าน graceful shutdown และข้อมูลใน named volumes ไม่หาย

## 5. CI และ image publishing

### 5.1 Pull-request gates ใน `.github/workflows/ci.yml`

ลำดับ gate:

1. Maven tests และ migration matrix เดิมต้องผ่าน
2. validate Compose ทั้ง `local` และ `cloud`
3. lint Dockerfile และ workflow configuration ด้วย tool/action ที่ pin version
4. build `linux/amd64` image แล้วรัน container smoke test
5. ตรวจ non-root/read-only/tmpfs behavior
6. scan repository และ build context ด้วย Gitleaks แบบ blocking
7. dependency review สำหรับ pull request
8. Trivy filesystem/config scan
9. Trivy image scan โดยรายงาน HIGH และ block CRITICAL ตาม policy เริ่มต้น
10. render Kubernetes manifests เพื่อยืนยันว่า image contract ยังเข้ากันได้

ห้ามใช้ `continue-on-error: true`, action จาก branch `master` หรือ scanner `exit-code: 0` ใน blocking gate

### 5.2 Main-branch publish workflow

ปรับ `.github/workflows/docker-build.yml` หรือแทนด้วย `image-publish.yml` เพียง workflow เดียวเพื่อไม่ build/push ซ้ำ

1. checkout exact commit
2. setup QEMU และ Buildx
3. authenticate GHCR ด้วยสิทธิ์ขั้นต่ำ `packages: write`
4. build/push `linux/amd64,linux/arm64`
5. publish immutable tag `sha-<full-git-sha>`; ไม่ publish `latest`
6. บันทึก manifest-list digest เช่น `sha256:...`
7. scan published digest ก่อนอนุญาต deployment workflow
8. สร้าง SBOM และ provenance attestation
9. sign digest แบบ keyless ด้วย GitHub OIDC/Cosign
10. verify signature, provenance และ SBOM จาก registry
11. เขียน tag, digest, commit และ SBOM artifact ลง job summary/output สำหรับ Component C/D

ถ้า scan/sign/verify ล้มเหลว image อาจอยู่ใน registry ภายใต้ immutable SHA แต่ต้องไม่ถูก promote หรือ deploy

### 5.3 Release identity contract

```text
Git commit
   |
   v
ghcr.io/mudst-2026-luntungsadungyong/tcg-store-backend:sha-<full-sha>
   |
   v
ghcr.io/.../tcg-store-backend@sha256:<manifest-digest>
                         |                    |
                         v                    v
                    API Deployment       Migration Job
```

API และ migration ต้องอ้าง digest เดียวกันในหนึ่ง release ห้ามให้ tag resolution เกิดคนละเวลาแล้วได้คนละ image

## 6. Supply-chain policy

| Control | Pull request | Main publish | Blocking condition |
| --- | --- | --- | --- |
| Gitleaks | repository history/diff | full release commit | พบ verified secret |
| Dependency review | changed dependencies | inherited from merged PR | vulnerability เกิน policy |
| Trivy filesystem/config | source, IaC, Dockerfile | release source | misconfiguration/critical finding |
| Trivy image | amd64 test image | multi-arch digest | CRITICAL ที่มี fix ตาม policy เริ่มต้น |
| SBOM | optional preview | required artifact/attestation | สร้างหรือ verify ไม่ได้ |
| Provenance | ไม่ publish | required | subject digest ไม่ตรง release image |
| Signature | ไม่ publish | required | sign/verify ไม่ผ่าน |
| Image tag policy | validate | enforce | ใช้ mutable `latest` ใน release |

การเปลี่ยน severity threshold ต้องแก้ policy/document พร้อมเหตุผล ห้ามลด gate ด้วย `continue-on-error`

## 7. Test matrix

| Test | amd64 | arm64 | Local Compose | Cloud Compose | CI |
| --- | --- | --- | --- | --- | --- |
| image build | required | required | — | — | required |
| JVM startup/health | native | QEMU หรือ native runner | required | required | required |
| migration entrypoint | required | required | required | required | required |
| non-root/read-only/tmpfs | required | required | required | required | required |
| secret absence/image history | required | manifest/shared layers | — | — | required |
| vulnerability scan | required | required | — | — | required |
| SBOM/provenance/signature verification | — | — | — | — | publish only |
| repeated-build digest comparison | required | optional after baseline | — | — | scheduled/manual + release gate |

Arm64 test ผ่านเมื่อ image build สำเร็จและ JVM/migration entrypoint เริ่มได้จริง ไม่ใช่เพียงมีชื่อ platform อยู่ใน manifest

## 8. Work packages สำหรับแจก agent

งาน B1–B3 ทำขนานกันได้เพราะมี write ownership แยกกัน จากนั้น B4 เป็น integration pass

### B1 — Docker image hardening and reproducibility

ไฟล์เจ้าของ:

- `Dockerfile`
- `.dockerignore`
- `pom.xml` เฉพาะ reproducible-build metadata

ส่งมอบ:

- pinned multi-arch base images
- fixed UID/GID, JVM policy และ OCI labels
- deterministic JAR/image build contract
- image-level smoke/security test commands

### B2 — Docker Desktop Compose hardening

ไฟล์เจ้าของ:

- `docker-compose.desktop.yml`
- scripts ที่เรียก Compose เฉพาะเมื่อจำเป็นต้องปรับ tag/health behavior

ส่งมอบ:

- backend/migration health and security anchors
- local tag ไม่ใช้ `latest`
- supporting image pins และ loopback-only database port
- Local/Cloud profile verification โดยไม่ทำลาย volumes เดิม

### B3 — CI, registry and supply-chain gates

ไฟล์เจ้าของ:

- `.github/workflows/ci.yml`
- `.github/workflows/docker-build.yml` หรือ replacement `image-publish.yml`
- scanner policy/config files ที่สร้างใหม่

ส่งมอบ:

- multi-arch immutable publish
- blocking scans
- SBOM, provenance, signature และ verification
- digest output contract สำหรับ Component C/D

### B4 — Integration owner

ไฟล์เจ้าของ:

- เอกสาร Component B และ runbook ที่เกี่ยวข้อง
- แก้ไฟล์ B1–B3 ได้เฉพาะ integration conflict หลัง owner ส่งงานแล้ว

หน้าที่:

- ตรวจ image เดียวกันกับ API/migration
- รัน test matrix รวม
- ตรวจ workflow permissions และ secret leakage
- สรุป digest, SBOM, scan results และ known risks

## 9. ลำดับ implementation ที่แนะนำ

1. บันทึก baseline image size, user, layers, CVEs และ build time
2. เริ่ม B1, B2 และ B3 ขนานกันตาม file ownership
3. รวม B1 ก่อนเพื่อให้ B2/B3 ใช้ hardened image contract
4. รัน Local Compose โดย reuse named volumes เดิม
5. รัน Cloud Compose แบบ smoke โดยไม่พิมพ์ credentials ลง log
6. build และ smoke test ทั้ง amd64/arm64
7. เปิด blocking scans และแก้ findings ก่อน publish
8. publish temporary immutable SHA, scan/sign/verify digest
9. ส่ง digest contract ให้ Component C/D
10. บันทึก evidence และปิด acceptance checklist

## 10. Definition of Done

- [x] base images และ CI actions ที่สำคัญถูก pin
- [x] Dockerfile กำหนด fixed non-root UID/GID `10001:10001`
- [x] Compose กำหนด read-only root filesystem, `/tmp` tmpfs และ runtime security controls
- [x] image configuration กำหนด JVM memory policy ให้สอดคล้องกับ Kubernetes limits
- [x] Final Local/Cloud runtime acceptance บน image ปัจจุบันผ่าน
- [x] amd64 และ arm64 JVM/migration runtime acceptance ผ่าน
- [x] release workflow บังคับ full SHA tag และ manifest digest โดยไม่ publish `latest`
- [x] Cloud API และ migration ใช้ image เดียวกันใน final runtime acceptance
- [x] Gitleaks/dependency/Trivy gates ถูกตั้งให้ block pipeline ตาม policy
- [x] Trivy image scan พบ CRITICAL `0`
- [ ] SBOM, provenance และ signature สร้างและ verify ผ่าน
- [x] Release reproducibility ผ่าน Buildx `rewrite-timestamp=true`; OCI manifest `sha256:3fea0574201e88eb29f9c4e92223dfa45ebd7aa5f95804951156630cfb8430bd`, archive SHA-256 `1601DFD961CD66EB946E1C28C22997BCEC20209206CC0096C39903AF70627F7`
- [x] Cloud S3 required environment variables fail fast ใน Compose
- [x] Gitleaks ตรวจ Git history ผ่าน และ build context/image ไม่มีไฟล์ credential
- [ ] ส่ง image digest contract ให้ Component C/D แล้ว

Runtime และ release reproducibility acceptance ผ่านแล้วตามหลักฐานใน Implementation report; registry publish/attestation และ digest handoff ยังต้องรันบน GitHub Actions หลัง merge เข้า `main` เพราะต้องใช้ GHCR และ GitHub OIDC จริง ห้ามถือว่า workflow syntax ผ่านเท่ากับ acceptance ผ่าน

## 11. Implementation report — 2026-10-16

### B1 — Image hardening และ reproducibility

- pin Temurin JDK/JRE 21.0.12.1 multi-architecture manifest digest
- ยืนยัน dependency baseline เป็น Spring Boot `4.0.8`, Tomcat `11.0.25` และ Jackson BOM `2.21.7`/`3.1.7`
- compile JAR บน `$BUILDPLATFORM` และเลือก architecture เฉพาะ runtime เพื่อลด QEMU build time
- ใช้ Maven cache mount ที่ไม่ถูกบันทึกใน image layer
- runtime ใช้ `10001:10001`, JRE-only, exec entrypoint และ percentage-based JVM memory policy
- กำหนด OCI source/revision/version/created/licenses/title/description labels
- Buildx release reproducibility ผ่านด้วย `rewrite-timestamp=true`; OCI manifest `sha256:3fea0574201e88eb29f9c4e92223dfa45ebd7aa5f95804951156630cfb8430bd` และ archive SHA-256 `1601DFD961CD66EB946E1C28C22997BCEC20209206CC0096C39903AF70627F7`
- Docker Desktop Compose เป็น dev build และไม่ใช้ยืนยัน release reproducibility

### B2 — Canonical Docker Desktop Compose

- ใช้ local tag `dev` ที่ override ได้แทน `latest`
- backend และ migration ใช้ common security policy: read-only root, `/tmp` tmpfs, drop capabilities, no-new-privileges, PID limit และ 45-second grace period
- เพิ่ม readiness healthcheck และคง migration/seed dependency gates
- pin PostgreSQL 16.15 และ S3Mock 5.1.0 ด้วย multi-architecture digest
- bind PostgreSQL และ S3Mock กับ loopback host ports
- cloud profile บังคับ `OBJECT_STORAGE_BUCKET`, `OBJECT_STORAGE_PUBLIC_BASE_URL`, `OBJECT_STORAGE_ACCESS_KEY` และ `OBJECT_STORAGE_SECRET_KEY` ด้วย required interpolation; CI ตั้ง dummy values สำหรับ Compose validation
- named volume declarations คงชื่อเดิมไว้
- Local final acceptance: readiness `UP`; migration และ seed exit `0`; counts `global_cards=2001`, `local_cards=20`; S3Mock มี 20 objects ที่ persist; volume มี 41 files / 15.1 MB; security controls ผ่าน
- Cloud final acceptance: Neon migration exit `0`, readiness `UP`, backend และ migration ใช้ image เดียวกัน; หลัง restore cloud backend healthy ที่ `:8080`
- amd64 image: `sha256:7b594f2ff8eb018b16fd41db327ab97e6e6b82bc16095ead57340d04aa512782`; Trivy image scan CRITICAL `0`
- arm64 Java และ migration runner exit `0`; image `sha256:405c9f29042afae1bdf7121099456618f2860a8c2a4f01a0418c3f4cadea01e8`

### B3 — CI และ software supply chain

- PR CI ตรวจ Compose/Kustomize, Hadolint, hardened runtime behavior และ Trivy image gate
- เปลี่ยน Gitleaks เป็น official open-source CLI image แบบ pin digest เพื่อไม่ผูกกับ organization license ของ GitHub Action
- allow เฉพาะ fingerprint ของ Spring Boot generated test password ใน historical Surefire report; finding อื่นยัง block ตามเดิม
- main workflow build/push `linux/amd64,linux/arm64` ด้วย full SHA tag และ reproducible image exporter
- main workflow ถูกตั้งค่าให้สร้าง/ตรวจ SPDX SBOM สอง architecture, keyless Cosign OCI SLSA/SPDX attestations และ keyless Cosign signature โดยไม่ผูก acceptance กับ GitHub Artifact Attestations ซึ่งใช้ไม่ได้กับ organization plan ปัจจุบัน
- เมื่อ publish ผ่าน workflow จะส่ง `image-contract.txt` ซึ่งมี image, full SHA tag, manifest digest, canonical reference และ commit

### Verification evidence

- Maven: 184 tests passed, 2 skipped
- Dependency scan: 0 HIGH/CRITICAL findings
- Trivy image scan: CRITICAL `0`
- Release reproducibility: Buildx `rewrite-timestamp=true` ผ่าน; OCI manifest `sha256:3fea0574201e88eb29f9c4e92223dfa45ebd7aa5f95804951156630cfb8430bd`; archive SHA-256 `1601DFD961CD66EB946E1C28C22997BCEC20209206CC0096C39903AF70627F7`
- Cloud Compose: required environment variables สำหรับ S3 ถูกตั้งเป็น fail-fast; CI Compose validation ตั้ง dummy values สำหรับ `OBJECT_STORAGE_BUCKET`, `OBJECT_STORAGE_PUBLIC_BASE_URL`, `OBJECT_STORAGE_ACCESS_KEY` และ `OBJECT_STORAGE_SECRET_KEY`
- Gitleaks: 84 commits scanned, no leaks after one fingerprint-specific historical exception
- Local runtime: image `sha256:7b594f2ff8eb018b16fd41db327ab97e6e6b82bc16095ead57340d04aa512782`; readiness `UP`, migration/seed exit `0`, counts `2001` global + `20` local, S3 `20` objects persisted, volume `41` files / `15.1 MB`, security controls ผ่าน
- Cloud runtime: Neon migration exit `0`, readiness `UP`, backend/migration image เดียวกัน; restored cloud backend healthy ที่ `:8080`
- arm64 runtime: Java และ migration exit `0`, image `sha256:405c9f29042afae1bdf7121099456618f2860a8c2a4f01a0418c3f4cadea01e8`
- Final Local/Cloud and amd64/arm64 runtime acceptance: ผ่านตามรายการข้างต้น
- Registry acceptance ที่ยังเหลือ: rerun จาก commit ที่มี Cosign OCI fallback, verify SBOM/provenance/signature จาก registry และส่ง digest contract จริงให้ Component C/D; protected-main run หลัง merge ยังเป็น final release gate

### B4 — Registry acceptance run 1 และ provider limitation

- workflow dispatch run `38891638571` จาก commit `6d6a162e333d2bad02eb0928f13c098591d38056` สร้างและ push multi-architecture candidate digest `sha256:751e6438f0e0b0050dfafe25b6a019a819906a07460a299f7222a066f8c25444`
- Maven, Compose validation, Gitleaks, source scan, reproducible amd64 build, GHCR push, platform digest resolution, amd64/arm64 CRITICAL scan และ SPDX SBOM generation ผ่าน
- run หยุดที่ `actions/attest-build-provenance` เพราะ GitHub ตอบว่า Artifact Attestations ไม่พร้อมใช้กับ organization plan ปัจจุบัน จึงไม่มี signature, accepted image contract หรือ digest handoff จาก run นี้
- candidate digest จาก run แรกถือเป็น **unaccepted** และห้ามใช้ deploy แม้ว่าตัว image จะอยู่ใน GHCR
- workflow และ release verifier เปลี่ยนเป็น keyless Cosign signature พร้อม SLSA provenance attestation ที่ index digest และ SPDX JSON attestations ที่ child digest ทั้งสอง architecture; verification pin workflow identity, OIDC issuer และ exact Git commit SHA

## 12. ความเสี่ยงที่ต้องเฝ้าระวัง

- Alpine/musl และ native libraries อาจให้ behavior ต่างระหว่าง amd64/arm64 จึงต้อง smoke จริงทั้งสอง architecture
- การเปิด read-only root อาจเผย path ที่ library พยายามเขียน ต้องอนุญาตเฉพาะ `/tmp` ไม่เปิดทั้ง filesystem
- การ pin digest ต้องมีกระบวนการ update สม่ำเสมอ ไม่เช่นนั้นจะค้าง CVE เก่า
- QEMU ช้ากว่า native runner; timeout ต้องแยกจากผล correctness
- scanner policy ที่เข้มทันทีอาจเจอหนี้เดิม ควรบันทึก exception แบบมีวันหมดอายุแทนการปิด gate
- full image digest อาจต่างจาก provenance timestamp จึงต้องแยก reproducible artifact check ออกจาก signed release metadata

## 13. Component A — การทดสอบที่ยังเหลือ

รายการนี้ต้องทำบน Kubernetes cluster ที่ login ได้ มี image SHA จริง, Secret จริง, Neon PostgreSQL, Neon Object Storage และ Metrics API ก่อนปิด Component A

### A1 — Two-replica shared-state

1. ยืนยัน Deployment มี `2/2 Ready`
2. ยิง request หลายครั้งผ่าน Service และยืนยันว่าถึงทั้งสอง Pod จาก access log/pod identity
3. upload รูปและสร้าง listing ผ่าน Pod หนึ่ง
4. อ่าน record และ object ผ่านอีก Pod
5. restart หนึ่ง Pod แล้วตรวจว่ารูปและข้อมูลยังอยู่

ผ่านเมื่อทั้งสอง replicas ใช้ PostgreSQL/S3 state ชุดเดียวกันและไม่มี local filesystem dependency

### A2 — Database outage and recovery

1. บล็อก database egress ของ backend Pods แบบชั่วคราวใน test environment
2. readiness ต้องเป็น `DOWN` และ Pod ถูกนำออกจาก Service endpoints
3. liveness ต้องยังเป็น `UP`
4. restart count ต้องไม่เพิ่มจาก DB outage
5. เปิด database กลับและ readiness ต้องฟื้นโดยไม่ restart Pod

### A3 — Graceful rollout under traffic

1. ส่ง request ต่อเนื่องและมี request ที่ใช้เวลานานค้างอยู่
2. rollout ไป image SHA ใหม่
3. ยืนยัน Pod เก่าหยุดรับ request ใหม่หลังเริ่ม terminate
4. in-flight request ต้องจบภายใน 30 วินาทีและ Pod ออกก่อน 45 วินาที
5. rollout ต้องไม่มีช่วงที่ Ready replicas เป็นศูนย์และไม่มี request สำคัญถูกตัด

### A4 — Failed rollout and rollback

1. deploy test image/config ที่ readiness ไม่ผ่านโดยตั้งใจ
2. Deployment ต้องเก็บ Pod รุ่นเดิมที่ Ready ไว้เพราะ `maxUnavailable: 0`
3. rollout ต้อง timeout/fail อย่างเห็นได้ชัด
4. rollback ไป revision ก่อนหน้า
5. readiness และ smoke request ต้องกลับมาผ่าน

### A5 — HPA and Neon connection budget

1. ยืนยัน Metrics API พร้อมและ HPA แสดง CPU/memory targets ไม่เป็น unknown
2. load test ให้ scale จาก 2 ไปสูงสุดไม่เกิน 4 Pods แล้ว scale down กลับ
3. ตรวจ active/queued Neon connections ระหว่าง steady state และ rollout surge
4. API connections ต้องไม่เกิน 25 และต้องเหลือ operational reserve ตาม quota ที่ยืนยันจริง
5. ต้องไม่มี connection timeout, pool exhaustion หรือ OOMKilled

### A6 — Topology spread and voluntary disruption

1. บน multi-node cluster ยืนยัน Pods กระจายคนละ hostname เมื่อ capacity เพียงพอ
2. drain node ที่มี backend Pod
3. PDB ต้องรักษา available Pod อย่างน้อย 1 ตัว
4. Service ต้องยังตอบ smoke request ระหว่าง reschedule

### A7 — Component A closure evidence

- เก็บคำสั่งและผล `kubectl get deploy,pods,hpa,pdb,endpoints`
- เก็บ rollout history และ rollback result
- เก็บ request success/error summary ระหว่าง rollout
- เก็บ Neon connection peak และ resource utilization
- บันทึก cluster type, node count, image digest และเวลาทดสอบ
- เมื่อ A1–A6 ผ่านจึงเปลี่ยนสถานะ Component A จาก `In progress` เป็น `Complete`
