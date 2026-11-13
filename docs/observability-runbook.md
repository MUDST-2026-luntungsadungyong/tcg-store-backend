# TCG Store Observability Runbook

> เอกสารปฏิบัติการสำหรับ AKS demo (`aks-demo`)  
> อัปเดต: 13 พฤศจิกายน 2026

## 1. วัตถุประสงค์และขอบเขต

Runbook นี้ใช้สำหรับตรวจสุขภาพ backend, ตรวจ metrics/logs, ตอบสนองต่อ
alert และป้องกันข้อมูลสำคัญรั่วไหลในระบบ TCG Store บน Azure AKS demo
เอกสารนี้อธิบายเฉพาะ environment `aks-demo`; เกณฑ์เตือนเป็นค่าเริ่มต้น
สำหรับการสาธิต ไม่ใช่ SLO production ที่อนุมัติแล้ว

ห้ามใช้ runbook นี้เพื่อเปิด `/actuator/prometheus` ผ่าน public ingress,
เปลี่ยน Secret, หรือ bypass release workflow.

## 2. Architecture

```text
Browser / Cloudflare Pages
          |
          | HTTPS /api/*
          v
Caddy gateway (public LoadBalancer)
          |
          | private cluster traffic
          v
Service tcg-store-backend (ClusterIP:80 -> Pod:8080)
          |
          +--> PostgreSQL / Neon
          +--> Object storage

ama-metrics (kube-system) -- private scrape /actuator/prometheus --> Backend Pod
          |
          v
Azure Monitor Workspace (Managed Prometheus) <--> Azure Managed Grafana

ama-logs / Container Insights -- stdout + stderr --> Log Analytics Workspace

Azure Prometheus Rule Groups -- optional Action Group --> Email / Teams
```

`/actuator/prometheus` ต้องเข้าถึงได้เฉพาะ collector ภายใน AKS ผ่าน
ServiceMonitor/NetworkPolicy เท่านั้น ส่วน public gateway อนุญาตเฉพาะ
`/api/*`; การเรียก `/actuator/prometheus` จาก internet ต้องได้ `404`.

## 3. Azure resources

| หน้าที่ | Resource / ค่า |
| --- | --- |
| Subscription | `Azure for Students` |
| Resource group | `rg-tcg-store-aks-demo` |
| Kubernetes cluster | `aks-tcg-store-demo` (Malaysia West) |
| Application namespace | `tcg-store-aks-demo` |
| Backend deployment / service | `tcg-store-backend` / `tcg-store-backend` |
| Azure Monitor Workspace | `amw-tcg-store-aks-demo` |
| Log Analytics Workspace | `law-tcg-store-aks-demo` |
| Azure Managed Grafana | `tcg-store-grafana` |
| Prometheus rule groups | `tcg-store-aks-demo-recording`, `tcg-store-aks-demo-alerts` |
| Metrics collector | `ama-metrics` in `kube-system` |
| Log collector | `ama-logs` / Container Insights |
| Dashboard UID | `tcg-store-backend` |

## 4. วิธีเข้าดู Grafana

1. Azure Portal → resource **`tcg-store-grafana`** → เปิด **Endpoint**.
2. เข้าสู่ระบบด้วยบัญชีที่ได้รับ Azure RBAC/Grafana Viewer หรือ Editor.
3. เปิด dashboard **TCG Store Backend** (`/d/tcg-store-backend/tcg-store-backend`).
4. เลือก datasource `amw-tcg-store-aks-demo` และตัวแปร environment เป็น
   `aks-demo`.
5. เริ่มตรวจที่ panel **Scrape targets up** ก่อนเสมอ. ค่า `1` หมายถึง
   collector scrape backend ได้; ไม่มี series ไม่ได้แปลว่าระบบปกติ.

หากเข้า Grafana ไม่ได้ ให้ตรวจ Azure RBAC ของผู้ใช้ก่อน ไม่ควรแชร์บัญชีหรือ
สร้าง public dashboard.

## 5. Dashboard reference

| Panel / Signal | ความหมาย | ดูอะไรเมื่อผิดปกติ |
| --- | --- | --- |
| Scrape target / Ready pods | target scrape และ deployment available | `up`, ServiceMonitor, Pod readiness |
| Request rate | ปริมาณ HTTP request ต่อวินาที | traffic ลด/พุ่งผิดปกติ, route ที่เกี่ยวข้อง |
| 5xx ratio | สัดส่วน HTTP 5xx ใน 5 นาที | logs ด้วย request ID, recent deploy, database/storage |
| Read p95 latency | p95 ของ `GET /api/products` และ `/api/cards` | DB pool, Neon latency, CPU/memory, query load |
| JVM memory | heap/non-heap memory | memory leak, GC pressure, OOM risk |
| Hikari connection pool | active/idle/pending/max connections | pool saturation, connection timeout, Neon availability |
| Storage telemetry | storage operation/error/latency | object storage error; `UNKNOWN` คือไม่มี sample ไม่ใช่ success |
| Kubernetes state | replicas, restarts, OOMKilled | rollout, crash loop, resource limit |

## 6. วิธีค้นหา backend logs

Azure Portal → **`law-tcg-store-aks-demo`** → **Logs** → KQL mode.

### Backend logs ล่าสุด

```kusto
ContainerLogV2
| where TimeGenerated > ago(1h)
| where PodNamespace == "tcg-store-aks-demo" and ContainerName == "backend"
| project TimeGenerated, PodName, LogSource, LogMessage
| order by TimeGenerated desc
| take 100
```

### Error ย้อนหลัง 1 ชั่วโมง

```kusto
ContainerLogV2
| where TimeGenerated > ago(1h)
| where PodNamespace == "tcg-store-aks-demo" and ContainerName == "backend"
| where LogSource =~ "stderr" or LogMessage has '"level":"ERROR"'
| project TimeGenerated, PodName, LogSource, LogMessage
| order by TimeGenerated desc
```

### ค้นหาด้วย Request ID

รับค่า `X-Request-Id` จาก response หรือ client แล้วแทนค่าใน query:

```kusto
ContainerLogV2
| where TimeGenerated > ago(2h)
| where PodNamespace == "tcg-store-aks-demo" and ContainerName == "backend"
| where LogMessage has "<request-id>"
| project TimeGenerated, PodName, LogSource, LogMessage
| order by TimeGenerated asc
```

### Logs รอบ pod restart

```kusto
ContainerLogV2
| where TimeGenerated between (datetime(<restart-time-minus-10m>) .. datetime(<restart-time-plus-10m>))
| where PodNamespace == "tcg-store-aks-demo" and ContainerName == "backend"
| project TimeGenerated, PodName, LogSource, LogMessage
| order by TimeGenerated asc
```

Structured log มี `timestamp`, `level`, `application`, `environment`,
`requestId`, `route`, `method`, `status`, `durationMs`, `message` และ exception
เมื่อมีข้อผิดพลาด. อย่า copy raw log ไปยังช่องทาง public.

## 7. Alert catalog

Alert rule group: `tcg-store-aks-demo-alerts`. หากไม่มี Action Group attached
alert จะยังแสดงใน Azure Monitor/Grafana ได้ แต่จะไม่ส่ง Email หรือ Teams.

| Alert | เงื่อนไข | ระดับ | การตอบสนองแรก |
| --- | --- | --- | --- |
| `TCGStoreBackendScrapeMissingAksDemo` | ไม่มี `up` series 2 นาที | Critical | ตรวจ ServiceMonitor, ama-metrics, Service/endpoint |
| `TCGStoreBackendScrapeDownAksDemo` | `up == 0` 2 นาที | Warning | ตรวจ Pod readiness, network policy และ actuator ภายใน cluster |
| `TCGStoreBackendClusterMetricsUnknownAksDemo` | ไม่มี deployment availability metric | Warning | ตรวจ `ama-metrics-ksm` / kube-state-metrics telemetry |
| `TCGStoreBackendUnavailableAksDemo` | backend available replicas `< 1` 2 นาที | Critical | ตรวจ workload/events แล้ว restore availability |
| `TCGStoreBackendContainerRestartsAksDemo` | restart ≥ 3 ใน 10 นาที | Warning | ตรวจ previous logs, OOM, config และ image ล่าสุด |
| `TCGStoreBackendOOMKilledAksDemo` | last termination = OOMKilled | Critical | ตรวจ memory/JVM และ resource limit ก่อน restart/rollback |
| `TCGStoreBackendHigh5xxRatioAksDemo` | 5xx > 1% และอย่างน้อย 100 requests ใน 5 นาที | Warning | ใช้ request ID/logs, ตรวจ deployment/DB/storage |
| `TCGStoreBackendHighReadLatencyAksDemo` | product/card read p95 > 1s 10 นาที | Warning | ตรวจ DB pool, Neon, CPU/memory และ slow query |
| `TCGStoreBackendHikariPressureAksDemo` | pool use > 80%, pending > 0 หรือ timeout | Warning | ลด load, ตรวจ DB/connection config, ห้ามเพิ่ม pool แบบเดา |
| `TCGStoreBackendHikariMetricsUnknownAksDemo` | ไม่มี Hikari max metric | Warning | ตรวจ Micrometer/Actuator scrape path ก่อนตีความว่าปกติ |
| `TCGStoreBackendStorageErrorsAksDemo` | storage error rate > 0 | Warning | ตรวจ object-storage provider/credential reference โดยไม่เปิด secret |
| `TCGStoreBackendStorageMetricsUnknownAksDemo` | ไม่มี storage counter/histogram | Warning | ทำ approved known-fixture operation; `UNKNOWN` ไม่ใช่ healthy |

## 8. Incident procedures

### API down หรือ backend unavailable

1. ยืนยันภายนอกด้วย `GET /api/health`; บันทึกเวลาและ `X-Request-Id`.
2. Grafana: ดู `up`, ready replicas และ restart/OOM panels.
3. Azure Portal → AKS → Workloads → namespace `tcg-store-aks-demo` →
   `tcg-store-backend`; ตรวจ Ready status และ Events.
4. Log Analytics: ค้นหา error ก่อนเวลาที่ alert เริ่ม.
5. ตรวจ deployment/migration run ล่าสุดจาก GitHub Actions.
6. หาก deployment ใหม่เป็นสาเหตุ ให้ใช้ approved rollback procedure;
   ห้าม rollback database migration แบบเดา.
7. ตรวจ health และ `up=1` หลังแก้ไข แล้วบันทึก incident summary.

### 5xx ratio สูง

1. เปิด 5xx panel เพื่อแยกช่วงเวลาและ route.
2. ดึง `X-Request-Id` จาก response ที่ผิดพลาด แล้วค้นหาใน `ContainerLogV2`.
3. ตรวจ exception pattern ว่าเป็น validation, database, storage หรือ auth.
4. เปรียบเทียบกับ deploy/configuration ล่าสุดและ Hikari/storage panels.
5. ถ้าส่งผลกับผู้ใช้ ให้ลดหรือหยุด traffic เฉพาะส่วนตาม change-management
   ที่ทีมอนุมัติ; ห้ามลบ data เพื่อแก้ alert.
6. ยืนยันอัตรา 5xx กลับสู่ baseline ก่อน resolve alert.

### Read latency สูง

1. ยืนยัน p95 panel และ request rate ว่าเป็น load จริง ไม่ใช่ no-data.
2. ตรวจ Hikari active/pending/max, JVM memory และ Kubernetes CPU/memory.
3. ค้นหา slow/error logs ตามช่วงเวลา; ใช้ request ID เมื่อมี.
4. ตรวจ Neon/database health และ query/index ที่เปลี่ยนล่าสุด.
5. อย่าขยาย resource หรือเพิ่ม pool limit แบบทันทีโดยไม่มี owner อนุมัติ.
6. หลังแก้ไข เปรียบเทียบ p95 กับ baseline เดิมอย่างน้อย 10 นาที.

### Database pool เต็มหรือ Hikari pressure

1. ตรวจ `hikaricp_connections_active`, `idle`, `pending`, `max` และ timeout.
2. ตรวจ concurrent request rate, long-running operation และ Neon connectivity.
3. ระบุ route/request ID ที่ทำให้ connection ค้างจาก logs/APM evidence.
4. ตรวจ connection leak, transaction boundary และ pool configuration ใน PR ล่าสุด.
5. Scale/restart เฉพาะเมื่อ owner อนุมัติและบันทึกผลกระทบ; restart ไม่ใช่
   วิธีแก้ connection leak ถาวร.
6. ยืนยัน pending/timeout ลดลงและ latency กลับสู่ baseline.

## 9. วิธีทดสอบ alert อย่างปลอดภัย

1. ตรวจว่า Action Group มี owner และปลายทางที่อนุมัติแล้วก่อน; ห้ามใส่ email
   ส่วนตัวโดยไม่ยืนยัน.
2. Deploy rule group โดยส่ง `actionGroupResourceId` ไปที่
   `infra/observability/azure/aks-demo-prometheus-rules.bicep`.
3. ใช้ **Test action group** ของ Azure Monitor เพื่อตรวจ delivery ก่อน.
4. ทดสอบ backend metrics ด้วย read-only scenario ก่อน เช่น `/api/health` หรือ
   bounded load. อย่าทำ 100+ intentional 5xx ใน shared demo เพื่อบังคับ alert.
5. หากจำเป็นต้อง test rule จริง ให้กำหนด maintenance window, owner, rollback
   plan และผลกระทบที่ยอมรับได้ก่อน.
6. บันทึกเวลา rule fired, เวลาที่ส่งถึงปลายทาง, ผู้รับ, และเวลาที่ resolve.

สถานะปัจจุบัน: Prometheus rule groups พร้อมใช้งาน แต่ Action Group ยังเป็น
dependency แยก จึงยังไม่ถือว่า alert email/Teams delivery ได้รับการยืนยัน.

## 10. ควบคุมค่าใช้จ่าย Azure

- ใช้ resource group `rg-tcg-store-aks-demo` และ tag resource ตาม environment
  เพื่อเห็นต้นทุนรวมได้ชัดเจน.
- ตรวจ **Cost Management → Budgets** ของ `tcg-aks-demo-20usd` (แจ้งเตือน
  25%, 50%, 75% ของ USD 20 ต่อเดือน) และ Cost Analysis ทุกสัปดาห์.
- ตรวจ compute/node, ACR, managed disk, public IP/network, Log Analytics และ
  Grafana plan แยกกัน; Azure AKS Free ลดเฉพาะ control-plane charge ไม่ได้
  ทำให้ node/network/storage ฟรีทั้งหมด.
- ตั้ง log retention ให้เหมาะกับ demo และลด query time range ให้แคบก่อน run
  KQL เพื่อควบคุม ingestion/query cost.
- หลีกเลี่ยง high-cardinality metric labels, continuous load test และการเก็บ
  verbose debug/SQL logs ใน environment shared.
- ปิดหรือ scale down resource ที่ไม่ได้ใช้เมื่อได้รับอนุมัติจาก owner;
  ห้ามลบ workspace/cluster เพื่อประหยัดค่าใช้จ่ายโดยไม่มี backup/approval.

## 11. ปกป้องข้อมูลส่วนบุคคลและความลับ

ห้ามบันทึกหรือส่งต่อใน log, metric label, dashboard annotation, issue, PR หรือ
alert notification:

- password, JWT/access/refresh token, cookie, API key, connection string หรือ secret
- ข้อมูลชำระเงินและหลักฐานชำระเงิน
- email, เบอร์โทร, LINE ID และ PII ที่ไม่จำเป็น
- object key, signed URL, bucket credential หรือข้อมูลระบุตัวตนลูกค้า

แนวปฏิบัติ:

1. ใช้ `requestId` เพื่อ trace แทนการใช้ข้อมูลผู้ใช้.
2. จำกัด Azure RBAC ตามหน้าที่: DevOps ใช้ Monitoring Contributor;
   developer/QA เป็น Grafana Viewer/Editor หรือ Log Analytics Reader เท่าที่จำเป็น.
3. เก็บ secret ใน Kubernetes Secret/secret management เท่านั้น; ห้าม print,
   export หรือ screenshot ค่า secret.
4. ใช้ private Service, ServiceMonitor และ NetworkPolicy สำหรับ metrics.
5. ก่อนแชร์ log ให้ redaction ค่าอ่อนไหวและแชร์ช่วงเวลาหรือ request ID เท่าที่จำเป็น.
6. ทบทวน retention, workspace access และ Action Group recipients เป็นระยะ.

## 12. Handoff checklist

- [ ] `up{job="tcg-store-backend",environment="aks-demo"}` เป็น `1`
- [ ] Backend deployment Ready และ `/api/health` ตอบ `UP`
- [ ] Container Insights มี backend log ล่าสุด
- [ ] Grafana dashboard ใช้ datasource/Environment ถูกต้อง
- [ ] Action Group, owner และ escalation path ได้รับอนุมัติ
- [ ] ทดสอบ delivery ของ Action Group สำเร็จ
- [ ] Budget และ retention ถูกทบทวน
- [ ] ทีมรู้ตำแหน่งเอกสารนี้และไม่ log PII/secrets
