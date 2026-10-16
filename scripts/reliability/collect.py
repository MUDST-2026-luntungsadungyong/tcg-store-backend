#!/usr/bin/env python3
"""Bounded, read-only Kubernetes and direct per-Pod health evidence collector.

The collector never reads Secrets or response bodies into evidence. Per-Pod probe
URLs must be explicitly supplied by an operator through a local port-forward or
pod proxy; a Service URL is not accepted as evidence for an individual Pod.
"""

from __future__ import annotations

import argparse
import hashlib
import ipaddress
import json
import re
import subprocess
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import HTTPRedirectHandler, ProxyHandler, Request, build_opener

SCHEMA_VERSION = 1
MAX_DURATION_SECONDS = 3600
MAX_PROBE_PODS = 32
MAX_RESPONSE_BYTES = 1024 * 1024
RESOURCE_KINDS = {
    "pods": "pods",
    "nodes": "nodes",
    "deployments": "deployments",
    "replicasets": "replicasets",
    "hpas": "horizontalpodautoscalers",
    "pdbs": "poddisruptionbudgets",
    "endpointslices": "endpointslices",
    "events": "events",
}
REQUIRED_NONEMPTY_RESOURCES = {"pods", "nodes", "deployments", "replicasets", "hpas", "pdbs", "endpointslices"}
SCHEMA_DESCRIPTIONS = {
    "cluster": {
        "schema_version": SCHEMA_VERSION,
        "record_type": "cluster_snapshot",
        "fields": ["run_id", "timestamp_utc", "elapsed_seconds", "namespace", "resources"],
    },
    "probe": {
        "schema_version": SCHEMA_VERSION,
        "record_type": "http_attempt",
        "fields": ["request_id", "pod_uid", "probe", "status_code", "state", "duration_ms", "error"],
    },
    "scenario": {
        "schema_version": SCHEMA_VERSION,
        "record_type": "scenario_event",
        "fields": ["run_id", "timestamp_utc", "phase", "event", "pod_uid", "request_id", "identity"],
    },
}


class _NoRedirectHandler(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


_PRIVATE_HTTP_OPENER = build_opener(ProxyHandler({}), _NoRedirectHandler()).open


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def schema_hash(description: dict[str, Any]) -> str:
    blob = json.dumps(description, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return hashlib.sha256(blob).hexdigest()


def _safe_local_url(value: str) -> tuple[str, str]:
    """Validate a private/loopback HTTP target; return (host, path), not URL."""
    parsed = urlsplit(value)
    if parsed.scheme not in ("http", "https") or not parsed.hostname:
        raise ValueError("target must be an explicit HTTP(S) URL")
    if parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise ValueError("target URL must not include credentials, query, or fragment")
    host = parsed.hostname.lower()
    if host != "localhost":
        try:
            address = ipaddress.ip_address(host)
        except ValueError as exc:
            raise ValueError("target host must be localhost or a literal private/loopback IP") from exc
        if not (address.is_private or address.is_loopback or address.is_link_local):
            raise ValueError("target host must be private, loopback, or link-local")
    path = parsed.path or "/"
    if len(path) > 512:
        raise ValueError("target path exceeds the evidence-safe limit")
    return host, path


def load_probe_map(path: Path | None) -> list[dict[str, str]]:
    if path is None:
        return []
    payload = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(payload, dict) or payload.get("schema_version") != 1:
        raise ValueError("probe map must be a schema_version 1 object")
    entries = payload.get("pods")
    if not isinstance(entries, list) or len(entries) > MAX_PROBE_PODS:
        raise ValueError("probe map pods must be a list with at most 32 entries")
    seen: set[str] = set()
    result = []
    for item in entries:
        if not isinstance(item, dict):
            raise ValueError("each probe map entry must be an object")
        pod_uid = item.get("pod_uid")
        if not isinstance(pod_uid, str) or not pod_uid or len(pod_uid) > 128 or pod_uid in seen:
            raise ValueError("each probe entry needs a unique, bounded pod_uid")
        seen.add(pod_uid)
        urls = {}
        for probe_name in ("liveness", "readiness"):
            value = item.get(f"{probe_name}_url")
            if not isinstance(value, str):
                raise ValueError(f"probe entry needs {probe_name}_url")
            _, endpoint_path = _safe_local_url(value)
            if endpoint_path.rstrip("/") != f"/actuator/health/{probe_name}":
                raise ValueError(f"{probe_name}_url must target the explicit actuator {probe_name} path")
            urls[probe_name] = value
        result.append({"pod_uid": pod_uid, **urls})
    return result


def http_get_attempt(
    url: str,
    *,
    run_id: str,
    route_template: str,
    pod_uid: str | None = None,
    probe: str | None = None,
    timeout_seconds: float = 2.0,
    opener: Callable[..., Any] = _PRIVATE_HTTP_OPENER,
) -> dict[str, Any]:
    """Issue one GET; never retries or records URL/body, only safe accounting."""
    _safe_local_url(url)
    request_id = str(uuid.uuid4())
    req = Request(url, method="GET", headers={"X-Request-ID": request_id, "Cache-Control": "no-store"})
    start_wall = utc_now()
    start = time.monotonic()
    status_code = None
    state = "UNKNOWN"
    error = None
    response_request_id = None
    try:
        response = opener(req, timeout=timeout_seconds)
        try:
            status_code = int(response.status)
            response_request_id = response.headers.get("X-Request-ID") if getattr(response, "headers", None) else None
            raw = response.read(MAX_RESPONSE_BYTES + 1)
            if len(raw) > MAX_RESPONSE_BYTES:
                error = "response_too_large"
            elif 200 <= status_code < 300:
                try:
                    payload = json.loads(raw.decode("utf-8")) if raw else None
                    payload_status = payload.get("status") if isinstance(payload, dict) else None
                    if payload_status in ("DOWN", "OUT_OF_SERVICE"):
                        state = "DOWN"
                    elif payload_status in ("UP", "OK"):
                        state = "UP"
                    elif payload_status is None:
                        state = "UNKNOWN"
                        error = "health_status_missing"
                    else:
                        state = "UNKNOWN"
                        error = "health_status_unrecognized"
                except (UnicodeDecodeError, json.JSONDecodeError):
                    state = "UNKNOWN"
                    error = "invalid_json_response"
            elif status_code >= 500:
                state = "DOWN"
            else:
                state = "UNKNOWN"
        finally:
            response.close()
    except HTTPError as exc:
        status_code = int(exc.code)
        state = "DOWN" if status_code >= 500 else "UNKNOWN"
        response_request_id = exc.headers.get("X-Request-ID") if exc.headers else None
        exc.close()
    except (URLError, TimeoutError, OSError) as exc:
        error = "timeout" if isinstance(exc, TimeoutError) else "transport_error"
        # Do not serialize exception text: it can contain a URL or credentials.
    except Exception:
        error = "request_error"
    duration_ms = round((time.monotonic() - start) * 1000, 3)
    safe_response_request_id = (
        response_request_id
        if isinstance(response_request_id, str) and re.fullmatch(r"[A-Fa-f0-9-]{1,64}", response_request_id)
        else None
    )
    record = {
        "schema_version": SCHEMA_VERSION,
        "record_type": "http_attempt",
        "run_id": run_id,
        "timestamp_utc": start_wall,
        "request_id": request_id,
        "response_request_id": safe_response_request_id,
        "method": "GET",
        "route_template": route_template,
        "pod_uid": pod_uid,
        "pod_uid_attribution": "explicit_pod_uid_mapping" if pod_uid else "UNKNOWN",
        "probe": probe,
        "status_code": status_code,
        "state": state,
        "duration_ms": duration_ms,
        "error": error,
        "retry": False,
    }
    return record


def _condition_summary(conditions: Any) -> list[dict[str, Any]]:
    if not isinstance(conditions, list):
        return []
    return [
        {key: item.get(key) for key in ("type", "status", "reason", "lastTransitionTime") if key in item}
        for item in conditions
        if isinstance(item, dict)
    ]


def sanitize_item(kind: str, item: dict[str, Any]) -> dict[str, Any]:
    meta = item.get("metadata") or {}
    status = item.get("status") or {}
    spec = item.get("spec") or {}
    base = {"name": meta.get("name"), "uid": meta.get("uid"), "resource_version": meta.get("resourceVersion")}
    if kind == "pods":
        containers = spec.get("containers") or []
        statuses = status.get("containerStatuses") or []
        base.update({
            "node_name": spec.get("nodeName"),
            "phase": status.get("phase"),
            "deletion_timestamp": meta.get("deletionTimestamp"),
            "conditions": _condition_summary(status.get("conditions")),
            "containers": [{"name": c.get("name"), "image": c.get("image")} for c in containers if isinstance(c, dict)],
            "container_statuses": [
                {
                    "name": c.get("name"), "image_id": c.get("imageID"), "ready": c.get("ready"),
                    "restart_count": c.get("restartCount"),
                    "state_reason": ((c.get("state") or {}).get("waiting") or {}).get("reason"),
                    "last_terminated_reason": ((c.get("lastState") or {}).get("terminated") or {}).get("reason"),
                    "last_exit_code": ((c.get("lastState") or {}).get("terminated") or {}).get("exitCode"),
                }
                for c in statuses if isinstance(c, dict)
            ],
        })
    elif kind == "nodes":
        base.update({"conditions": _condition_summary(status.get("conditions")), "allocatable": status.get("allocatable")})
    elif kind == "deployments":
        base.update({
            "generation": meta.get("generation"), "observed_generation": status.get("observedGeneration"),
            "replicas": status.get("replicas"), "updated_replicas": status.get("updatedReplicas"),
            "ready_replicas": status.get("readyReplicas"), "available_replicas": status.get("availableReplicas"),
            "unavailable_replicas": status.get("unavailableReplicas"), "conditions": _condition_summary(status.get("conditions")),
            "images": [c.get("image") for c in ((spec.get("template") or {}).get("spec") or {}).get("containers", []) if isinstance(c, dict)],
            "revision": (meta.get("annotations") or {}).get("deployment.kubernetes.io/revision"),
        })
    elif kind == "replicasets":
        owners = meta.get("ownerReferences") or []
        base.update({
            "owner_references": [{"name": x.get("name"), "uid": x.get("uid"), "kind": x.get("kind")} for x in owners if isinstance(x, dict)],
            "replicas": status.get("replicas"), "ready_replicas": status.get("readyReplicas"),
            "available_replicas": status.get("availableReplicas"),
            "revision": (meta.get("annotations") or {}).get("deployment.kubernetes.io/revision"),
            "images": [c.get("image") for c in ((spec.get("template") or {}).get("spec") or {}).get("containers", []) if isinstance(c, dict)],
            "container_image_ids": [c.get("imageID") for c in (status.get("containerStatuses") or []) if isinstance(c, dict)],
        })
    elif kind == "hpas":
        metrics = []
        for metric in status.get("currentMetrics") or []:
            if not isinstance(metric, dict):
                continue
            metric_type = metric.get("type")
            source = metric.get(str(metric_type or "").lower()) or {}
            current = source.get("current") or {}
            entry = {"type": metric_type}
            if metric_type == "Resource":
                entry.update({"name": source.get("name"), "average_utilization": current.get("averageUtilization"), "average_value": current.get("averageValue")})
            elif metric_type == "Pods":
                entry.update({"name": (source.get("metric") or {}).get("name"), "average_value": current.get("averageValue")})
            elif metric_type in {"Object", "External"}:
                entry.update({"name": (source.get("metric") or {}).get("name"), "value": current.get("value")})
            metrics.append(entry)
        base.update({
            "min_replicas": spec.get("minReplicas"), "max_replicas": spec.get("maxReplicas"),
            "current_replicas": status.get("currentReplicas"), "desired_replicas": status.get("desiredReplicas"),
            "current_metrics": metrics, "conditions": _condition_summary(status.get("conditions")),
        })
    elif kind == "pdbs":
        base.update({
            "min_available": spec.get("minAvailable"), "max_unavailable": spec.get("maxUnavailable"),
            "current_healthy": status.get("currentHealthy"), "desired_healthy": status.get("desiredHealthy"),
            "expected_pods": status.get("expectedPods"), "disruptions_allowed": status.get("disruptionsAllowed"),
            "observed_generation": status.get("observedGeneration"),
        })
    elif kind == "endpointslices":
        endpoints = []
        for endpoint in item.get("endpoints") or []:
            target = endpoint.get("targetRef") or {}
            endpoints.append({
                "target_name": target.get("name"), "target_uid": target.get("uid"),
                "ready": (endpoint.get("conditions") or {}).get("ready"),
                "serving": (endpoint.get("conditions") or {}).get("serving"),
                "terminating": (endpoint.get("conditions") or {}).get("terminating"),
            })
        base.update({"address_type": item.get("addressType"), "endpoints": endpoints})
    elif kind == "events":
        involved = item.get("regarding") or item.get("involvedObject") or {}
        base.update({
            "event_type": item.get("type"), "reason": item.get("reason"), "action": item.get("action"),
            "count": item.get("count"), "first_timestamp": item.get("firstTimestamp") or item.get("eventTime"),
            "last_timestamp": item.get("lastTimestamp") or item.get("series", {}).get("lastObservedTime"),
            "regarding_kind": involved.get("kind"), "regarding_name": involved.get("name"), "regarding_uid": involved.get("uid"),
        })
    return base


def kubectl_resource(context: str, namespace: str, resource: str, *, runner=subprocess.run, timeout_seconds: float = 15) -> tuple[str, list[dict[str, Any]], str | None]:
    args = ["kubectl", "--context", context]
    if resource != "nodes":
        args.extend(["--namespace", namespace])
    args.extend(["get", resource, "-o", "json"])
    try:
        result = runner(args, capture_output=True, text=True, check=False, timeout=timeout_seconds)
    except Exception:
        return "UNKNOWN", [], "kubectl_unavailable_or_timeout"
    if result.returncode != 0:
        return "UNKNOWN", [], "kubectl_read_failed"
    try:
        payload = json.loads(result.stdout)
        items = payload.get("items")
        if not isinstance(items, list):
            raise ValueError("items missing")
        kind = next(k for k, v in RESOURCE_KINDS.items() if v == resource)
        return "OK", [sanitize_item(kind, item) for item in items if isinstance(item, dict)], None
    except (json.JSONDecodeError, ValueError, StopIteration, AttributeError):
        return "UNKNOWN", [], "invalid_kubectl_json"


def _append_jsonl(path: Path, record: dict[str, Any]) -> None:
    with path.open("a", encoding="utf-8", newline="\n") as stream:
        stream.write(json.dumps(record, sort_keys=True, separators=(",", ":"), ensure_ascii=False) + "\n")


def collect(
    *, context: str, namespace: str, run_dir: Path, duration_seconds: int = 30,
    snapshot_cadence_seconds: int = 5, probe_cadence_seconds: int = 1,
    probe_map: list[dict[str, str]] | None = None,
    runner=subprocess.run, opener=_PRIVATE_HTTP_OPENER, clock=time.monotonic, sleeper=time.sleep,
) -> dict[str, Any]:
    if not context or not namespace:
        raise ValueError("explicit kube context and namespace are required")
    if not (1 <= duration_seconds <= MAX_DURATION_SECONDS):
        raise ValueError(f"duration must be 1..{MAX_DURATION_SECONDS} seconds")
    if not (1 <= snapshot_cadence_seconds <= 60 and 1 <= probe_cadence_seconds <= 60):
        raise ValueError("cadences must be 1..60 seconds")
    if run_dir.exists() and any(run_dir.iterdir()):
        raise FileExistsError("run directory must be absent or empty; existing evidence is never overwritten")
    run_dir.mkdir(parents=True, exist_ok=True)
    cluster_path = run_dir / "cluster.jsonl"
    probes_path = run_dir / "probes.jsonl"
    cluster_path.touch(exist_ok=False)
    probes_path.touch(exist_ok=False)
    run_id = str(uuid.uuid4())
    start = clock()
    deadline = start + duration_seconds
    next_snapshot = start
    next_probe = start
    unknown_count = 0
    down_count = 0
    sample_count = 0
    probe_attempts = 0
    last_pod_uids: set[str] | None = None
    latest_pods_state = "UNKNOWN"
    last_pod_snapshot_at: float | None = None
    probe_map = probe_map or []
    while True:
        now = clock()
        if now >= deadline and sample_count:
            break
        timestamp = utc_now()
        if now >= next_snapshot:
            resource_records = {}
            for logical, resource in RESOURCE_KINDS.items():
                remaining = deadline - clock()
                if remaining <= 0:
                    state, items, error = "UNKNOWN", [], "snapshot_budget_exhausted"
                else:
                    state, items, error = kubectl_resource(
                        context, namespace, resource, runner=runner, timeout_seconds=min(15.0, remaining),
                    )
                resource_records[logical] = {"state": state, "error": error, "items": items}
                if state != "OK":
                    unknown_count += 1
                elif logical in REQUIRED_NONEMPTY_RESOURCES and not items:
                    resource_records[logical] = {"state": "UNKNOWN", "error": "required_resource_missing", "items": []}
                    unknown_count += 1
                    state = "UNKNOWN"
                if logical == "hpas" and state == "OK" and any(not item.get("current_metrics") for item in items):
                    resource_records[logical]["data_state"] = "UNKNOWN"
                    resource_records[logical]["data_error"] = "hpa_current_metrics_missing"
                    unknown_count += 1
                if logical == "pods":
                    latest_pods_state = state
                    if state == "OK":
                        last_pod_uids = {str(item.get("uid")) for item in items if item.get("uid")}
                        last_pod_snapshot_at = clock()
                    else:
                        last_pod_uids = None
                        last_pod_snapshot_at = None
            _append_jsonl(cluster_path, {
                "schema_version": SCHEMA_VERSION, "record_type": "cluster_snapshot", "run_id": run_id,
                "timestamp_utc": timestamp, "elapsed_seconds": round(now - start, 3), "namespace": namespace,
                "resources": resource_records,
            })
            sample_count += 1
            next_snapshot = now + snapshot_cadence_seconds
        if now >= next_probe:
            if not probe_map:
                _append_jsonl(probes_path, {
                    "schema_version": SCHEMA_VERSION, "record_type": "probe_sample", "run_id": run_id,
                    "timestamp_utc": timestamp, "state": "UNKNOWN", "error": "per_pod_probe_mapping_missing",
                    "source_age_seconds": None,
                })
                unknown_count += 1
            else:
                for entry in probe_map:
                    for probe_name in ("liveness", "readiness"):
                        pod_snapshot_age = None if last_pod_snapshot_at is None else max(0.0, now - last_pod_snapshot_at)
                        if latest_pods_state != "OK" or last_pod_uids is None:
                            result = {
                                "schema_version": SCHEMA_VERSION, "record_type": "probe_observation", "run_id": run_id,
                                "timestamp_utc": timestamp, "request_id": None,
                                "route_template": f"/actuator/health/{probe_name}",
                                "pod_uid": entry["pod_uid"], "probe": probe_name, "status_code": None,
                                "state": "UNKNOWN", "duration_ms": None, "error": "pod_snapshot_missing",
                                "pod_snapshot_age_seconds": pod_snapshot_age,
                            }
                        elif entry["pod_uid"] not in last_pod_uids:
                            result = {
                                "schema_version": SCHEMA_VERSION, "record_type": "probe_observation", "run_id": run_id,
                                "timestamp_utc": timestamp, "request_id": None,
                                "route_template": f"/actuator/health/{probe_name}",
                                "pod_uid": entry["pod_uid"], "probe": probe_name, "status_code": None,
                                "state": "UNKNOWN", "duration_ms": None, "error": "pod_uid_not_in_latest_snapshot",
                                "pod_snapshot_age_seconds": pod_snapshot_age,
                            }
                        elif pod_snapshot_age is None or pod_snapshot_age > 2 * snapshot_cadence_seconds:
                            result = {
                                "schema_version": SCHEMA_VERSION, "record_type": "probe_observation", "run_id": run_id,
                                "timestamp_utc": timestamp, "request_id": None,
                                "route_template": f"/actuator/health/{probe_name}",
                                "pod_uid": entry["pod_uid"], "probe": probe_name, "status_code": None,
                                "state": "UNKNOWN", "duration_ms": None, "error": "pod_snapshot_stale",
                                "pod_snapshot_age_seconds": round(pod_snapshot_age, 3) if pod_snapshot_age is not None else None,
                            }
                        else:
                            result = http_get_attempt(
                                entry[f"{probe_name}_url"], run_id=run_id,
                                route_template=f"/actuator/health/{probe_name}", pod_uid=entry["pod_uid"],
                                probe=probe_name, opener=opener,
                            )
                            result["pod_snapshot_age_seconds"] = round(pod_snapshot_age, 3)
                            probe_attempts += 1
                        _append_jsonl(probes_path, result)
                        if result["state"] == "UNKNOWN":
                            unknown_count += 1
                        elif result["state"] == "DOWN":
                            down_count += 1
            next_probe = now + probe_cadence_seconds
        if clock() >= deadline:
            break
        due = min(next_snapshot, next_probe, deadline)
        delay = max(0.01, min(0.25, due - clock()))
        sleeper(delay)
    files = {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in (cluster_path, probes_path)}
    summary = {
        "schema_version": SCHEMA_VERSION, "record_type": "collector_summary", "run_id": run_id,
        "status": "FAIL" if down_count else ("UNKNOWN" if unknown_count else "OBSERVED"),
        "context": context, "namespace": namespace,
        "duration_seconds": duration_seconds, "snapshot_cadence_seconds": snapshot_cadence_seconds,
        "probe_cadence_seconds": probe_cadence_seconds, "snapshot_records": sample_count,
        "probe_attempts": probe_attempts, "unknown_observations": unknown_count,
        "down_observations": down_count,
        "schema_sha256": {key: schema_hash(value) for key, value in SCHEMA_DESCRIPTIONS.items()},
        "files_sha256": files, "generated_at_utc": utc_now(),
    }
    with (run_dir / "summary.json").open("x", encoding="utf-8") as stream:
        stream.write(json.dumps(summary, indent=2, sort_keys=True) + "\n")
    return summary


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--kube-context", required=True, help="Explicit kubectl context; no current-context fallback")
    parser.add_argument("--namespace", required=True)
    parser.add_argument("--run-dir", required=True, type=Path)
    parser.add_argument("--duration-seconds", type=int, default=30, help="Bounded observation duration (default 30; max 3600)")
    parser.add_argument("--snapshot-cadence-seconds", type=int, default=5)
    parser.add_argument("--probe-cadence-seconds", type=int, default=1)
    parser.add_argument("--probe-map", type=Path, help="JSON per-Pod UID liveness/readiness URL map via port-forward/proxy")
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        mapping = load_probe_map(args.probe_map)
        summary = collect(
            context=args.kube_context, namespace=args.namespace, run_dir=args.run_dir,
            duration_seconds=args.duration_seconds, snapshot_cadence_seconds=args.snapshot_cadence_seconds,
            probe_cadence_seconds=args.probe_cadence_seconds, probe_map=mapping,
        )
    except (ValueError, FileExistsError, OSError, json.JSONDecodeError) as exc:
        print(json.dumps({"status": "UNKNOWN", "error": str(exc)}))
        return 2
    print(json.dumps(summary, sort_keys=True))
    return 0 if summary["status"] == "OBSERVED" else 2


if __name__ == "__main__":
    raise SystemExit(main())
