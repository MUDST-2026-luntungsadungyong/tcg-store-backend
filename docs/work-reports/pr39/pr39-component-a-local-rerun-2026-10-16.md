# Component A — Local Docker/Kind rerun report

วันที่ 2026-10-16 (Asia/Bangkok)  
Branch: `feat/kubernetes`  
Source commit: `cf7e7320bed98a90c2e597e9b497a996b1c4649d`  
ผลรวม: **PREFLIGHT PARTIAL PASS / LIVE ACCEPTANCE BLOCKED BY HOST DISK**

## เป้าหมายและขอบเขต

รันทวน Component A บนเครื่องนี้ด้วย Kind cluster แยกภายใน Docker Desktop โดยไม่ใช้ Kubernetes ของ Docker Desktop, ไม่แตะ Neon/shared bucket และไม่เปลี่ยน Compose services หลัก Lab ใช้ชื่อ `tcg-component-a`, kubeconfig แยกใต้ `.local/pr39-work/component-a` และ topology หนึ่ง control-plane กับสอง workers

รอบนี้หยุดก่อน deploy dependencies/application เพราะพื้นที่ไดรฟ์ C ต่ำกว่า safety gate จึงห้ามนับผลเป็น A-00–A-08 ผ่าน

## สิ่งที่ทำและผลจริง

| ลำดับ | การตรวจ/การทำงาน | ผล |
|---|---|---|
| 1 | ตรวจ Docker Desktop และ workload เดิม | Docker Engine 29.4.0, 12 CPUs, memory ประมาณ 11.54 GiB; `backend-cloud` และ `frontend` ทำงานอยู่ |
| 2 | ตรวจ Kubernetes context | ไม่มี context เดิม; Docker Desktop Kubernetes ยัง disabled |
| 3 | ติดตั้ง Kind แบบ pinned | Kind v0.33.0; SHA-256 `4b22adaa135368c5a465d56bbd8e520cbea87272a06ca00b6078e7b81515c9fc` ผ่าน |
| 4 | สร้าง isolated Kind cluster | Kubernetes v1.36.4 ขึ้นครบ 3 nodes และทั้ง control-plane/worker สองตัวเป็น `Ready` |
| 5 | ตรวจ system pods | CoreDNS, kindnet, API server, etcd, scheduler/controller และ local-path storage เริ่มทำงาน |
| 6 | Build backend image จาก HEAD | สำเร็จเป็น `tcg-store-backend:pr39-component-a-baseline` |
| 7 | ตรวจ image identity | Image/index digest `sha256:6bbc02a48fb9293975f04b7cf0c5dcaaaa4b6b9198c784af5d6f7d072993045f`; OCI revision ตรง `cf7e7320bed98a90c2e597e9b497a996b1c4649d` |
| 8 | Load image เข้า worker แรก | สำเร็จที่ `tcg-component-a-worker` |
| 9 | Host disk safety gate | ก่อนเริ่มมีพื้นที่ C ประมาณ 3.24 GiB; หลัง cluster/build/load worker แรกเหลือประมาณ 0.21 GiB และต่ำสุดประมาณ 0.02 GiB |
| 10 | Docker failure handling | Docker เริ่มตอบ I/O/502 และหยุด engine จึงยุติการทดสอบทันที ไม่ deploy Secret, PostgreSQL, S3Mock, migration หรือ API |
| 11 | Recovery | คืนพื้นที่จาก generated caches, restart Docker Desktop และลบ Kind cluster `tcg-component-a` สำเร็จ |
| 12 | ตรวจระบบเดิมหลัง recovery | `tcg-store-service-backend-cloud` กลับมา `healthy`; frontend ทำงาน; ports 8080/3000 กลับมาตามเดิม |

## สิ่งที่ไม่ได้รัน

- A-00 baseline สอง Ready Pods และ observation 600 วินาที
- A-01 shared database/object state ข้ามสอง replicas และ Pod replacement
- A-02 per-Pod database outage/recovery ผ่าน Toxiproxy
- A-03 SIGTERM/in-flight request สามรอบ
- A-04 rolling update ภายใต้ continuous traffic
- A-05 failed rollout และ rollback
- A-06 HPA 2→3→4→2 พร้อม Metrics API และ bounded load
- A-07 measured connection budget
- A-08 worker drain/PDB/rescheduling

ดังนั้นสถานะ A-00 คือ `BLOCKED`; A-01–A-06/A-08 คือ `NOT TESTED`; A-07 คือ `UNKNOWN`

## การกู้พื้นที่ที่ทำ

ลบเฉพาะ generated/re-downloadable artifacts เพื่อให้ Docker กลับมาทำงาน:

- `target/acceptance-tools`
- `target/component-b-tools`
- `target/kubernetes-tools-bin`
- temporary clean-checkout directory ที่สร้างสำหรับ audit
- Maven dependency cache `.m2/repository` แล้วสร้าง directory ว่างกลับ
- BuildKit cache records ที่สร้างจาก build รอบนี้เท่าที่ระบุ scope ได้

ไม่ได้ลบ Docker images เดิม, named volumes, database data, S3 data, source files หรือ untracked user files การ build/test Maven ครั้งถัดไปต้องดาวน์โหลด dependencies ใหม่

## สถานะหลังจบ

- Kind cluster ถูกลบแล้ว ไม่มี `tcg-component-a-*` nodes/containers ค้าง
- Backend/Frontend หลักกลับมาทำงาน
- Baseline image ที่ build จาก HEAD ยังอยู่ใน local Docker image store
- ไม่มีการแก้ Neon schema/data หรือ cloud/local object bucket
- ไม่มี source change จากการทดสอบ นอกจากรายงานฉบับนี้

## เงื่อนไขก่อน rerun

ควรมีพื้นที่ C ว่างอย่างน้อย 5 GiB ก่อนสร้าง cluster และมากกว่านั้นหากจะติดตั้ง Metrics Server/load images หลาย nodes จากรอบนี้ 3.24 GiB ไม่พอสำหรับ 3-node Kind, current backend image, PostgreSQL/S3Mock/Toxiproxy และหลักฐาน A-00–A-08

เมื่อพื้นที่พร้อมให้รันใหม่ตามลำดับ: cluster → image load ทั้งสอง workers → isolated Secret/dependencies → migration → two API replicas → collectors → A-00 ถึง A-08 โดยเก็บ sanitized evidence ต่อ run ID และหยุดทันทีหาก disk/memory/connection reserve ต่ำกว่า gate
