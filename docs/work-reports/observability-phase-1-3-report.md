# รายงานการดำเนินงาน Observability — Phase 1 ถึง Phase 3

วันที่ดำเนินการ: 12 พฤศจิกายน 2026
ขอบเขต: AKS demo ของ TCG Store (`aks-tcg-store-demo`, `Malaysia West`)

## สรุปผล

ระบบ AKS demo มี Azure Monitor Managed Prometheus, Container Insights, Log Analytics และ Azure Managed Grafana เชื่อมต่อกันแล้ว Backend มี `ServiceMonitor` ใน Kustomize overlay เพื่อให้ Azure Managed Prometheus scrape Spring Boot/Micrometer metrics จาก `/actuator/prometheus` ทุก 30 วินาทีหลังการ merge และ deployment ของ PR นี้

## Phase 1 — Azure Monitoring Resources

ดำเนินการบน Azure subscription `Azure for Students` และ resource group `rg-tcg-store-aks-demo`:

- Register resource provider `Microsoft.Monitor`
- สร้าง Azure Monitor Workspace `amw-tcg-store-aks-demo`
- สร้าง Log Analytics Workspace `law-tcg-store-aks-demo`
- เชื่อม Azure Managed Grafana `tcg-store-grafana` กับ Azure Monitor Workspace
- กำหนด role `Monitoring Data Reader` ให้ managed identity ของ Grafana บน Azure Monitor Workspace เพื่ออ่าน Prometheus metrics

ผลลัพธ์: Grafana สามารถใช้ Azure Monitor Workspace เป็นแหล่งข้อมูล metrics ได้

## Phase 2 — เปิด Monitoring บน AKS

เปิด Azure Monitor บน `aks-tcg-store-demo` โดยเลือก resource จาก Phase 1:

- เปิด Prometheus metrics และเชื่อมกับ `amw-tcg-store-aks-demo`
- เปิด Azure Managed Grafana `tcg-store-grafana`
- เปิด Container Insights / Container logs และเชื่อมกับ `law-tcg-store-aks-demo`
- เลือก preset `Logs and events (Recommended)` ซึ่งเก็บ container logs และ Kubernetes events

ตรวจผลหลัง deployment สำเร็จ:

| รายการ | ผลตรวจ |
| --- | --- |
| Azure monitor metrics extension | `aks-managed-azure-monitor-metrics` ถูกติดตั้ง |
| Azure monitor logs extension | `aks-managed-azure-monitor-logs` ถูกติดตั้ง |
| Container Insights | Enabled |
| `ama-metrics` | Ready `2/2` |
| `ama-logs-rs` | Ready `1/1` |
| `ama-metrics-ksm` | Ready `1/1` |
| `ama-metrics-operator-targets` | Ready `1/1` |

## Phase 3 — เก็บ Backend Metrics

เพิ่ม Azure Managed Prometheus `ServiceMonitor` ที่ `infra/kubernetes/components/observability/servicemonitor-backend.yaml`:

- API group: `azmonitoring.coreos.com/v1`
- เลือก Kubernetes Service: `tcg-store-backend`
- เลือก named port: `http`
- Scrape path: `/actuator/prometheus`
- Scrape interval: `30s`
- เพิ่ม label `environment=aks-demo` ให้ทุก target ด้วย relabeling
- เพิ่ม label-limit ที่ Azure Managed Prometheus กำหนด

เพิ่ม component นี้เข้า `infra/kubernetes/overlays/aks-demo/kustomization.yaml` เพื่อให้ workflow `Deploy backend to AKS demo` apply manifest หลัง merge เข้า `main`

ด้านความปลอดภัย:

- Backend Service เป็น `ClusterIP`
- Public Ingress route เฉพาะ `/api` จึงไม่เผย `/actuator/prometheus` สู่ public ingress
- ปรับ NetworkPolicy สำหรับ Azure collector จาก namespace `kube-system` และ pod label `rsName=ama-metrics` ไปยัง TCP 8080 แล้ว
- AKS demo ปัจจุบันตั้ง Network policy engine เป็น `None`; NetworkPolicy จะยังไม่ถูกบังคับใช้จนกว่าจะเปิด network-policy engine

## การตรวจสอบ

- `kubectl kustomize infra/kubernetes/overlays/aks-demo` render ผ่าน และพบ ServiceMonitor ที่มี path, interval และ environment label ตามที่กำหนด
- `MetricsExposureIntegrationTests` ผ่าน 3/3 ด้วย Java 21
- `git diff --check` ผ่าน

## สิ่งที่ยังไม่อยู่ในขอบเขต PR นี้

- Control-plane metrics ของ AKS ยังไม่ได้เปิด เพราะเป็นตัวเลือกเสริม
- ยังไม่ได้สร้าง Grafana dashboard, Prometheus alert rule หรือ alert receiver (Phase 4 ขึ้นไป)
- Metrics จะเริ่มปรากฏหลัง PR ถูก merge เข้า `main` และ GitHub Actions deploy AKS demo สำเร็จ
