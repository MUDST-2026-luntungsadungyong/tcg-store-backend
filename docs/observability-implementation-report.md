# รายงานสรุประบบ Observability ของ TCG Store

**วันที่จัดทำ:** 13 พฤศจิกายน 2026  
**Environment:** `aks-demo`  
**ขอบเขต:** AKS, Azure Managed Prometheus, Azure Managed Grafana, Container Insights และ Azure Monitor alert rules

## 1. บทสรุปสำหรับผู้บริหาร

ระบบ Observability ของ TCG Store มีองค์ประกอบหลักทำงานแล้ว ได้แก่

- Backend ส่ง metrics ผ่าน Spring Boot Actuator/Micrometer ภายใน AKS
- Azure Managed Prometheus scrape metrics ของ backend ทุก 30 วินาที
- Azure Managed Grafana แสดง dashboard ของ backend ได้
- Container Insights เก็บ stdout/stderr ของ backend เข้า Log Analytics
- Backend เขียน structured JSON log พร้อม request correlation ID
- Azure Prometheus rules สำหรับ availability, 5xx, latency, database pool,
  storage และ Kubernetes health ถูก deploy แล้ว

สรุปสถานะปัจจุบัน:

| ส่วนงาน | สถานะ | ความหมาย |
| --- | --- | --- |
| Metrics และ Grafana dashboard | พร้อมใช้งาน | ดู health, traffic, 5xx, latency, JVM, Hikari และ Kubernetes state ได้ |
| Backend logs และ Container Insights | พร้อมใช้งาน | ค้นหา logs และ trace ด้วย request ID ได้ |
| Public metrics isolation | ผ่าน | `/actuator/prometheus` ไม่เปิดออก internet |
| Alert rules | deploy แล้ว | Rule สามารถ evaluate จาก metrics ได้ |
| Alert Email / Teams | **ยังไม่พร้อม** | ยังไม่มี Azure Monitor Action Group ที่ผูกปลายทาง |
| Runbook | พร้อม | มีคู่มือปฏิบัติการและการรับมือ incident |

ดังนั้น **Grafana และ log observability ใช้งานได้แล้ว** แต่ระบบแจ้งเตือนถึง
Email/Teams ยังไม่ถือว่าเสร็จจนกว่าจะสร้างและทดสอบ Action Group.

---

## 2. ระบบทำงานอย่างไร

### 2.1 เส้นทางของ Metrics

```text
ผู้ใช้เรียก /api/*
        |
        v
Caddy public gateway
        |
        v
Backend Pod (Spring Boot)
        |
        | Micrometer สร้าง HTTP/JVM/Hikari metrics
        v
/actuator/prometheus  [private only]
        |
        | ServiceMonitor: tcg-store-backend, port http, interval 30s
        v
ama-metrics (namespace kube-system)
        |
        v
Azure Monitor Workspace: amw-tcg-store-aks-demo
        |                         |
        |                         +--> Azure Prometheus Rule Groups
        v                                      |
Azure Managed Grafana                         +--> Azure Monitor Action Group (ยังไม่ผูก)
```

Backend จะนับ request, status code, latency, JVM memory และ Hikari database
connections โดย Micrometer จากนั้นเปิด endpoint `/actuator/prometheus` สำหรับ
collector ภายใน cluster เท่านั้น. `ServiceMonitor` บอก `ama-metrics` ให้ scrape
Service `tcg-store-backend` ผ่าน named port `http` ทุก 30 วินาที แล้วเก็บเข้า
Azure Monitor Workspace. Grafana ใช้ workspace นี้เป็น datasource เพื่อ query
PromQL และวาดกราฟ.

### 2.2 เส้นทางของ Logs

```text
Backend application log (structured JSON to stdout/stderr)
        |
        v
Container Insights / ama-logs
        |
        v
Log Analytics Workspace: law-tcg-store-aks-demo
        |
        v
Azure Portal Logs (KQL query)
```

Log JSON มีบริบทของ request ได้แก่ `application`, `environment`, `requestId`,
`route`, `method`, `status`, `durationMs`, message และ exception เมื่อมี error.
ผู้ปฏิบัติงานนำค่า `X-Request-Id` จาก response ไปค้นหา log เพื่อดูเหตุการณ์
ตั้งแต่ request เข้าจนจบได้.

### 2.3 ทำไม metrics endpoint ไม่ public

`/actuator/prometheus` อาจเปิดเผยรายละเอียดภายใน เช่น ชื่อ metric, component,
resource pressure หรือ topology จึงกำหนดให้:

- backend Service เป็น `ClusterIP`
- public Caddy gateway route เฉพาะ `/api/*`
- ServiceMonitor/NetworkPolicy อนุญาต collector ภายใน AKS
- public request ไปที่ `/actuator/prometheus` ตอบ `404 Not Found`

---

## 3. Azure resources ที่เกี่ยวข้อง

| หน้าที่ | Resource / ค่า | สถานะ |
| --- | --- | --- |
| Subscription | `Azure for Students` | ใช้งานอยู่ |
| Resource group | `rg-tcg-store-aks-demo` | ใช้งานอยู่ |
| AKS | `aks-tcg-store-demo` (Malaysia West) | ใช้งานอยู่ |
| Application namespace | `tcg-store-aks-demo` | ใช้งานอยู่ |
| Backend workload | deployment/service `tcg-store-backend` | Ready หลัง restart ล่าสุด |
| Azure Monitor Workspace | `amw-tcg-store-aks-demo` | รับ Prometheus metrics |
| Log Analytics Workspace | `law-tcg-store-aks-demo` | รับ Container Insights logs |
| Managed Grafana | `tcg-store-grafana` | Dashboard imported |
| Metrics collector | `ama-metrics` ใน `kube-system` | ทำงานอยู่ |
| Log collector | `ama-logs` / Container Insights | ทำงานอยู่ |
| Dashboard UID | `tcg-store-backend` | ใช้งานได้ |
| Rule groups | `tcg-store-aks-demo-recording`, `tcg-store-aks-demo-alerts` | deploy แล้ว |

สิทธิ์ที่ควรมีต่อไป:

| กลุ่ม | สิทธิ์แนะนำ |
| --- | --- |
| DevOps | Monitoring Contributor |
| Developer | Grafana Editor หรือ Viewer ตามหน้าที่ |
| QA | Grafana Viewer และ Log Analytics Reader |
| Grafana managed identity | Monitoring Reader |

ควรยืนยัน Azure IAM ของทีมเป็นระยะ และให้สิทธิ์เท่าที่จำเป็นเท่านั้น.

---

## 4. สรุปการดำเนินงานราย Phase

| Phase | วัตถุประสงค์ | สิ่งที่ทำแล้ว | สถานะ |
| --- | --- | --- | --- |
| 1 | เตรียม Azure monitoring resources | Register `Microsoft.Monitor`, เตรียม AMW/LAW/Grafana | เสร็จ |
| 2 | เปิด monitoring บน AKS | เปิด Prometheus metrics และ Container Insights, มี AMA collectors | เสร็จ |
| 3 | เก็บ backend metrics | เพิ่ม Azure `ServiceMonitor` สำหรับ `/actuator/prometheus` ทุก 30 วินาที | เสร็จ |
| 4 | Dashboard และ Prometheus rules | Import dashboard, deploy recording/alert groups, ยืนยัน `up=1` | เสร็จ |
| 5 | Alert routing | เตรียม Bicep รองรับ `actionGroupResourceId` | **ค้าง Action Group** |
| 6 | Logs | Structured JSON logs, correlation filter, KQL runbook, Container Insights validation | เสร็จ |
| 7 | End-to-end testing | health, load, controlled 5xx, public isolation, backend restart/recovery | เสร็จตามหลักฐานที่ยกเว้น screenshot; alert delivery ยังรอ Phase 5 |
| 8 | Runbook | คู่มือปฏิบัติการฉบับทีม | เสร็จ |

### Phase 1 — Azure foundation

ทำให้ Azure subscription รองรับ monitoring โดย register resource provider
`Microsoft.Monitor` และเตรียม resource หลัก 3 ตัว:

1. Azure Monitor Workspace สำหรับ Prometheus metrics
2. Log Analytics Workspace สำหรับ Container Insights logs
3. Azure Managed Grafana สำหรับแสดง dashboard

การแยก metrics กับ logs เป็นคนละ workspace ทำให้เลือกระยะเก็บข้อมูล, RBAC และ
ต้นทุนได้ชัดเจน.

### Phase 2 — AKS monitoring

AKS เชื่อมกับ Azure Monitor Workspace และ Log Analytics Workspace แล้ว.
Agent ที่เกี่ยวข้อง ได้แก่:

- `ama-metrics`: scrape และส่ง Prometheus metrics
- `ama-metrics-ksm`: ส่ง Kubernetes state metrics เช่น deployment replicas และ pod state
- `ama-logs`: เก็บ container stdout/stderr สำหรับ Container Insights

ผลคือ application metrics และ Kubernetes metrics ถูกเก็บโดย Azure โดยไม่ต้อง
บริหาร Prometheus server เอง.

### Phase 3 — Application metrics scrape

เพิ่ม component observability ใน Kubernetes configuration:

```text
infra/kubernetes/components/observability/
  servicemonitor-backend.yaml
  kustomization.yaml
```

ServiceMonitor ใช้ API group `azmonitoring.coreos.com/v1` เพื่อเลือก Service
`tcg-store-backend`, named port `http`, path `/actuator/prometheus`, interval
30 วินาที และ label `environment=aks-demo`. Metric ไม่ต้องออก public internet.

### Phase 4 — Dashboard และ rule groups

นำ dashboard `infra/observability/dashboard/tcg-store-backend.json` เข้า Azure
Managed Grafana ด้วย UID `tcg-store-backend`. ยืนยัน live metric:

```promql
up{job="tcg-store-backend",environment="aks-demo"}
```

ให้ผล `1` ซึ่งแปลว่า collector scrape backend สำเร็จ. นอกจากนี้มี metrics ของ
HTTP requests, JVM, Hikari pool, deployment availability และ pod restarts.

Prometheus rule groups ถูก deploy จาก:

```text
infra/observability/azure/aks-demo-prometheus-rules.bicep
```

### Phase 5 — Alert routing

Template รองรับ parameter `actionGroupResourceId`. หากส่ง Azure resource ID ของ
Action Group ไปตอน deploy, Azure จะแนบ action group ให้ทุก alert rule.

สถานะปัจจุบันคือ parameter ว่างโดยตั้งใจ เพราะยังไม่มี Action Group ที่อนุมัติ
owner/recipient แล้ว. ดังนั้น rule สามารถ fire ใน Azure ได้ แต่ไม่มีปลายทางส่ง
Email หรือ Teams.

### Phase 6 — Backend logs

Backend เปิด structured log แบบ logstash JSON และมี `RequestCorrelationFilter`
ที่:

1. รับ `X-Request-Id` จาก client หรือสร้างใหม่
2. เก็บค่าใน MDC ตลอด request
3. เติม route, method, status และ duration
4. คืนค่า `X-Request-Id` ใน HTTP response
5. ล้าง MDC หลัง request รวมถึง async/error path

การทดสอบผ่าน 6 tests:

- `StructuredLoggingTests`: 1 test
- `RequestCorrelationFilterTests`: 5 tests

Container Insights query backend logs ในเวลา 1 ชั่วโมงพบ 50 records จึงยืนยัน
ได้ว่า stdout/stderr ถูกส่งเข้า `ContainerLogV2` แล้ว.

### Phase 7 — Validation

| Test | หลักฐาน/ผล |
| --- | --- |
| Backend health | `GET /api/health` ตอบ HTTP 200, `status: UP`, database connected |
| Small load | 30 `GET /api/cards?size=1`: สำเร็จ 30/30, เฉลี่ย 164.38 ms |
| Controlled 5xx | invalid `sortBy` ที่ `/api/products` ตอบ HTTP 500 โดยไม่เขียนข้อมูล |
| Correlation ID | controlled 5xx: `8f2086d8-f9ad-4cf5-bb0f-fabf24a14817` |
| Public metrics isolation | `/actuator/prometheus` ผ่าน public gateway ตอบ HTTP 404 |
| Pod restart | operator restart `tcg-store-backend`; หลัง restart health ตอบ 200 UP |
| Recovery request ID | `78e60f4f-aeac-430e-accd-954ae754ed5c` |

Screenshot evidence ถูกยกเว้นสำหรับ demo ตามข้อตกลง. Alert delivery ไม่สามารถ
ทดสอบได้ก่อน Phase 5 มี Action Group.

### Phase 8 — Runbook

เพิ่ม [observability-runbook.md](observability-runbook.md) สำหรับการดู Grafana,
ค้นหา logs, อ่าน alert, รับมือ API down/5xx/latency/Hikari pressure, ทดสอบ alert,
ควบคุมค่าใช้จ่าย และป้องกัน PII.

---

## 5. Grafana dashboard มีอะไรบ้าง

| Panel | ใช้ตอบคำถามอะไร |
| --- | --- |
| Scrape targets up | Azure scrape backend ได้หรือไม่ |
| Ready pods | backend มี replica พร้อมรับงานเท่าไร |
| Request rate | ปริมาณ API traffic เปลี่ยนอย่างไร |
| 5xx ratio/rate | ระบบผิดพลาดจาก server มากผิดปกติหรือไม่ |
| Read p95 latency | การอ่าน product/card ช้ากว่า 1 วินาทีหรือไม่ |
| JVM memory | memory/GC pressure เสี่ยง OOM หรือไม่ |
| Hikari pool | database connections ใกล้เต็ม มี pending/timeout หรือไม่ |
| Storage | object-storage error/latency มีหรือไม่ |
| Kubernetes state | restart, OOMKilled และ availability เป็นอย่างไร |

การอ่านค่าที่สำคัญ:

- `up = 1`: scrape สำเร็จ
- ไม่มี `up` series: telemetry หาย ไม่ใช่ health
- `UNKNOWN`: metric source ยังไม่มีข้อมูล ไม่ใช่ success
- 5xx ratio ต่ำแต่ request น้อย: ยังไม่ควรสรุปว่า service แข็งแรง ต้องดู traffic ด้วย

---

## 6. Alert catalog และความหมาย

| Alert | Trigger | สิ่งแรกที่ต้องตรวจ |
| --- | --- | --- |
| `TCGStoreBackendScrapeMissingAksDemo` | ไม่มี `up` metric | ServiceMonitor, ama-metrics, Service endpoints |
| `TCGStoreBackendScrapeDownAksDemo` | `up == 0` | pod readiness, in-cluster network, actuator |
| `TCGStoreBackendClusterMetricsUnknownAksDemo` | ไม่มี deployment metric | `ama-metrics-ksm` telemetry |
| `TCGStoreBackendUnavailableAksDemo` | available replicas < 1 | workload, events, rollout ล่าสุด |
| `TCGStoreBackendContainerRestartsAksDemo` | restart ≥ 3 ใน 10 นาที | previous logs, config, OOM |
| `TCGStoreBackendOOMKilledAksDemo` | terminated reason = OOMKilled | JVM memory, resource limit |
| `TCGStoreBackendHigh5xxRatioAksDemo` | 5xx > 1% และ ≥100 requests/5m | request ID, exception, deploy/DB/storage |
| `TCGStoreBackendHighReadLatencyAksDemo` | read p95 > 1s /10m | Hikari pool, DB, CPU/memory |
| `TCGStoreBackendHikariPressureAksDemo` | pool >80%, pending หรือ timeout | DB connectivity, long transaction, leak |
| `TCGStoreBackendHikariMetricsUnknownAksDemo` | ไม่มี pool max metric | Actuator/Micrometer scrape |
| `TCGStoreBackendStorageErrorsAksDemo` | storage error rate > 0 | storage provider และ credential reference |
| `TCGStoreBackendStorageMetricsUnknownAksDemo` | ไม่มี storage metrics | approved known-fixture operation |

---

## 7. วิธีปฏิบัติเมื่อเกิดเหตุ

### 7.1 API down

1. เรียก `/api/health` และบันทึก timestamp กับ `X-Request-Id`.
2. ดู Grafana: `up`, ready replicas, restart และ OOM panel.
3. AKS → Workloads → `tcg-store-aks-demo` → `tcg-store-backend`; ตรวจ Ready และ Events.
4. KQL ค้นหา backend error ก่อนช่วง alert.
5. ตรวจ GitHub Actions deployment/migration ล่าสุด.
6. หาก release เป็นสาเหตุ ให้ใช้ rollback ที่ทีมอนุมัติ; ห้าม rollback migration แบบเดา.
7. ตรวจ health และ `up=1` หลังแก้ไข.

### 7.2 5xx สูง

1. ดูช่วงเวลาและ route ใน Grafana.
2. นำ request ID ไปค้นหา Log Analytics.
3. แยกสาเหตุเป็น validation, auth, database, storage หรือ code exception.
4. เปรียบเทียบกับ deploy/config ล่าสุดและ Hikari/storage panels.
5. แก้ที่ต้นเหตุและยืนยัน 5xx ratio กลับสู่ baseline.

### 7.3 Latency สูง

1. ยืนยันว่า p95 สูงภายใต้ traffic จริง.
2. ตรวจ request rate, Hikari active/pending/max, JVM และ CPU/memory.
3. ตรวจ Neon/database health และ query ที่เปลี่ยนล่าสุด.
4. อย่าปรับ pool size หรือ resource limit โดยไม่มีผลวิเคราะห์/อนุมัติ.
5. เปรียบเทียบ p95 อย่างน้อย 10 นาทีหลังแก้.

### 7.4 Database pool เต็ม

1. ดู `hikaricp_connections_active`, `idle`, `pending`, `max` และ timeout.
2. หา request/route ที่มี long-running transaction.
3. ตรวจ connection leak, transaction boundary และ database availability.
4. Scale/restart ได้เฉพาะเมื่อ owner อนุมัติ และไม่ใช้ restart แทนการแก้ leak.
5. ยืนยัน pending/timeout ลดลงและ latency กลับปกติ.

---

## 8. วิธีดู logs ที่ใช้จริง

เข้า Azure Portal → `law-tcg-store-aks-demo` → **Logs** แล้วใช้ KQL:

```kusto
ContainerLogV2
| where TimeGenerated > ago(1h)
| where PodNamespace == "tcg-store-aks-demo" and ContainerName == "backend"
| project TimeGenerated, PodName, LogSource, LogMessage
| order by TimeGenerated desc
| take 100
```

ค้นหาด้วย request ID:

```kusto
ContainerLogV2
| where TimeGenerated > ago(2h)
| where PodNamespace == "tcg-store-aks-demo" and ContainerName == "backend"
| where LogMessage has "<request-id>"
| project TimeGenerated, PodName, LogSource, LogMessage
| order by TimeGenerated asc
```

ห้าม log หรือส่งออก: password, JWT/access token, API key, connection string,
payment data, email, เบอร์โทร, LINE ID, object key หรือ signed URL.

---

## 9. ค่าใช้จ่ายและความปลอดภัย

### การควบคุมค่าใช้จ่าย

- ตรวจ `tcg-aks-demo-20usd` ใน Azure Cost Management และ alert 25/50/75%.
- Review ค่า compute/node, ACR, disk, public IP/network, Log Analytics และ Grafana.
- จำกัด retention และ KQL time range ตามความจำเป็น.
- หลีกเลี่ยง debug/SQL logs, high-cardinality labels และ load tests ต่อเนื่อง.
- การปิด/ลด resource ต้องได้รับอนุมัติ และห้ามลบ cluster/workspace เพื่อลดค่าใช้จ่ายโดยพลการ.

### การป้องกันข้อมูล

- ใช้ request ID เพื่อ trace แทน PII.
- ใช้ least-privilege RBAC; QA ไม่ควรมีสิทธิ์แก้ Azure resources.
- Secret ต้องอยู่ใน Kubernetes Secret/secret management ไม่ใช่ Git, log, dashboard หรือ screenshot.
- Metric endpoint ต้อง private และ public gateway ต้อง route เฉพาะ API.
- ตรวจ recipients ของ Action Group ก่อนเปิด alert delivery ทุกครั้ง.

---

## 10. สิ่งที่ต้องทำต่อเพื่อปิดงานทั้งหมด

เหลือเพียง Alert Notification:

1. สร้าง Azure Monitor Action Group
2. กำหนด owner และผู้รับ Email/Teams ที่ได้รับอนุมัติ
3. นำ Action Group resource ID ใส่เป็น `actionGroupResourceId` ตอน deploy rule groups
4. ใช้ **Test action group** ของ Azure Monitor เพื่อทดสอบการส่ง
5. บันทึกเวลาที่ rule fired, เวลาที่แจ้งเตือนถึงผู้รับ และเวลา resolve

เมื่อทำครบ จะได้ Observability ครบวงจร:

```text
Metrics + Grafana + Logs + Alert rules + Email/Teams notification
```

## 11. เอกสารและ Pull Requests ที่เกี่ยวข้อง

| หัวข้อ | เอกสาร / PR |
| --- | --- |
| Phase 4 dashboard/rules | PR #93 (merged) |
| Phase 5 alert routing template | PR #95 (draft; รอ Action Group) |
| Phase 6 backend logs | PR #97 (draft) |
| Phase 7 validation | PR #98 (draft) |
| Phase 8 runbook | PR #99 (draft) |
| คู่มือปฏิบัติการ | `docs/observability-runbook.md` |

> สถานะ PR อาจเปลี่ยนภายหลัง ให้ตรวจ GitHub ก่อน merge หรือส่งมอบ.

