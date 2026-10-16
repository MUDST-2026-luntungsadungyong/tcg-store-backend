#!/usr/bin/env python3
"""Bounded Component A observation runner; performs GETs and records markers only.

This runner does not mutate Kubernetes, send SIGTERM, deploy/rollback, or inject
faults. `run` can issue explicitly configured private/loopback GET traffic only;
`mark` records operator-observed event/identity metadata without performing it.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import re
import time
import uuid
from pathlib import Path
from typing import Any, Callable

try:
    from .collect import SCHEMA_DESCRIPTIONS, SCHEMA_VERSION, _append_jsonl, _safe_local_url, http_get_attempt, schema_hash, utc_now
except ImportError:  # direct script invocation
    from collect import SCHEMA_DESCRIPTIONS, SCHEMA_VERSION, _append_jsonl, _safe_local_url, http_get_attempt, schema_hash, utc_now

MAX_RPS = 2.0
MAX_REQUESTS = 1200
MAX_RUN_DURATION_SECONDS = 600
MARKER_EVENTS = {
    "phase_observed",
    "inflight_request_started",
    "sigterm_observed",
    "inflight_request_completed",
    "process_exit_observed",
    "candidate_rollout_observed",
    "failed_rollout_observed",
    "rollback_known_good_observed",
    "fault_effect_observed",
    "fault_removal_observed",
}
PHASES = {"baseline", "shared_state", "outage_recovery", "sigterm", "rollout", "failed_rollout_rollback", "hpa_load", "connection_budget", "placement_drain"}


def _ensure_run_dir(path: Path) -> None:
    if path.exists() and not path.is_dir():
        raise ValueError("run-dir must be a directory")
    path.mkdir(parents=True, exist_ok=True)


def _safe_marker_value(value: str | None, name: str, maximum: int = 128) -> str | None:
    if value is None:
        return None
    if not value or len(value) > maximum or not re.fullmatch(r"[A-Za-z0-9._:@/+,-]+", value):
        raise ValueError(f"{name} contains unsupported characters or exceeds its limit")
    return value


def _percentile(values: list[float], percentile: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    index = max(0, math.ceil(percentile * len(ordered)) - 1)
    return round(ordered[index], 3)


def record_marker(
    run_dir: Path,
    *,
    phase: str,
    event: str,
    pod_uid: str | None = None,
    request_id: str | None = None,
    identity_role: str | None = None,
    identity: str | None = None,
) -> dict[str, Any]:
    if phase not in PHASES:
        raise ValueError("phase must be a known Component A phase")
    if event not in MARKER_EVENTS:
        raise ValueError("unsupported marker event")
    pod_uid = _safe_marker_value(pod_uid, "pod_uid")
    request_id = _safe_marker_value(request_id, "request_id")
    identity = _safe_marker_value(identity, "identity", 256)
    if identity_role and identity_role not in {"baseline", "candidate", "known-good", "rollback"}:
        raise ValueError("identity_role must be baseline, candidate, known-good, or rollback")
    if identity is not None and identity_role is None:
        raise ValueError("identity_role is required when identity is supplied")
    if event in {"inflight_request_started", "sigterm_observed", "inflight_request_completed"} and (pod_uid is None or request_id is None):
        raise ValueError("in-flight/SIGTERM markers require both pod_uid and request_id")
    if event == "process_exit_observed" and pod_uid is None:
        raise ValueError("process-exit markers require pod_uid")
    if event in {"candidate_rollout_observed", "failed_rollout_observed"} and (identity is None or identity_role != "candidate"):
        raise ValueError("candidate/failed rollout marker requires candidate identity")
    if event == "rollback_known_good_observed" and (identity is None or identity_role not in {"known-good", "rollback"}):
        raise ValueError("rollback marker requires known-good/rollback identity")
    _ensure_run_dir(run_dir)
    path = run_dir / "scenario.jsonl"
    if not path.exists():
        path.touch(exist_ok=False)
    record = {
        "schema_version": SCHEMA_VERSION,
        "record_type": "scenario_event",
        "run_id": run_dir.name,
        "timestamp_utc": utc_now(),
        "phase": phase,
        "event": event,
        "operator_observed_only": True,
        "performed_by_runner": False,
        "pod_uid": pod_uid,
        "request_id": request_id,
        "identity_role": identity_role,
        "identity": identity,
    }
    _append_jsonl(path, record)
    return record


def run_phase(
    run_dir: Path,
    *,
    phase: str,
    duration_seconds: int = 30,
    requests_per_second: float = 0,
    url: str | None = None,
    route_template: str = "/",
    runner_get: Callable[..., dict[str, Any]] = http_get_attempt,
    clock: Callable[[], float] = time.monotonic,
    sleeper: Callable[[float], None] = time.sleep,
) -> dict[str, Any]:
    if phase not in PHASES:
        raise ValueError("phase must be a known Component A phase")
    if not 1 <= duration_seconds <= MAX_RUN_DURATION_SECONDS:
        raise ValueError(f"duration must be 1..{MAX_RUN_DURATION_SECONDS} seconds")
    if not 0 <= requests_per_second <= MAX_RPS:
        raise ValueError(f"requests-per-second must be between 0 and {MAX_RPS}")
    if requests_per_second > 0 and not url:
        raise ValueError("an explicit private/loopback --url is required when request rate is nonzero")
    if requests_per_second == 0 and url:
        raise ValueError("--url is not accepted unless a nonzero request rate is requested")
    if url:
        _safe_local_url(url)
        if not re.fullmatch(r"/[A-Za-z0-9._{}:/-]{0,255}", route_template):
            raise ValueError("route-template must be a bounded path template without query parameters")
    planned_requests = math.ceil(duration_seconds * requests_per_second)
    if planned_requests > MAX_REQUESTS:
        raise ValueError(f"planned requests exceed hard cap {MAX_REQUESTS}")
    _ensure_run_dir(run_dir)
    requests_path = run_dir / "requests.jsonl"
    scenario_path = run_dir / "scenario.jsonl"
    if not requests_path.exists():
        requests_path.touch(exist_ok=False)
    if not scenario_path.exists():
        scenario_path.touch(exist_ok=False)
    run_id = run_dir.name
    _append_jsonl(scenario_path, {
        "schema_version": SCHEMA_VERSION, "record_type": "scenario_event", "run_id": run_id,
        "timestamp_utc": utc_now(), "phase": phase, "event": "phase_observed",
        "operator_observed_only": True, "performed_by_runner": False, "boundary": "phase_start",
    })
    start = clock()
    deadline = start + duration_seconds
    interval = (1.0 / requests_per_second) if requests_per_second else None
    next_request = start
    attempts = []
    while interval is not None and len(attempts) < planned_requests:
        now = clock()
        if now >= deadline or now < next_request:
            if now >= deadline:
                break
            sleeper(min(next_request - now, max(0.0, deadline - now)))
            continue
        # One synchronous GET at a time; no hidden retries, concurrency, or body capture.
        attempt = runner_get(url, run_id=run_id, route_template=route_template)
        attempt.setdefault("route_template", route_template)
        attempt.setdefault("run_id", run_id)
        attempt.setdefault("method", "GET")
        attempt.setdefault("retry", False)
        if any(key not in attempt for key in ("request_id", "status_code", "state", "duration_ms")):
            attempt["state"] = "UNKNOWN"
            attempt["error"] = "incomplete_http_accounting"
        attempt["phase"] = phase
        _append_jsonl(requests_path, attempt)
        attempts.append(attempt)
        next_request = start + len(attempts) * interval
    while interval is None and clock() < deadline:
        sleeper(min(0.25, max(0.0, deadline - clock())))
    elapsed = max(0.0, clock() - start)
    _append_jsonl(scenario_path, {
        "schema_version": SCHEMA_VERSION, "record_type": "scenario_event", "run_id": run_id,
        "timestamp_utc": utc_now(), "phase": phase, "event": "phase_observed",
        "operator_observed_only": True, "performed_by_runner": False, "boundary": "phase_end",
        "elapsed_seconds": round(elapsed, 3),
    })
    statuses: dict[str, int] = {}
    for attempt in attempts:
        key = str(attempt.get("status_code")) if attempt.get("status_code") is not None else "transport_error"
        statuses[key] = statuses.get(key, 0) + 1
    durations = [float(item["duration_ms"]) for item in attempts if isinstance(item.get("duration_ms"), (int, float))]
    down_count = sum(1 for item in attempts if item.get("state") == "DOWN")
    unknown_count = sum(1 for item in attempts if item.get("state") == "UNKNOWN")
    transport_error_count = sum(1 for item in attempts if item.get("error") is not None)
    summary = {
        "schema_version": SCHEMA_VERSION, "record_type": "scenario_summary", "run_id": run_id,
        "phase": phase, "status": "FAIL" if down_count else ("UNKNOWN" if unknown_count else "OBSERVED"),
        "traffic_mode": "bounded_get" if url else "markers_only", "attempts": len(attempts),
        "scheduled": planned_requests, "sent": len(attempts), "completed": len(attempts),
        "in_flight_at_end": 0, "cancelled": 0, "dropped": max(0, planned_requests - len(attempts)),
        "planned_attempts": planned_requests, "requests_per_second_limit": requests_per_second,
        "delivered_requests_per_second": round(len(attempts) / elapsed, 3) if elapsed else None,
        "completed_2xx": sum(1 for x in attempts if isinstance(x.get("status_code"), int) and 200 <= x["status_code"] < 300),
        "transport_errors": transport_error_count, "down_observations": down_count,
        "unknown_observations": unknown_count, "http_status_counts": statuses, "latency_ms": {
            "p50": _percentile(durations, 0.50), "p95": _percentile(durations, 0.95), "p99": _percentile(durations, 0.99),
        },
        "elapsed_seconds": round(elapsed, 3), "generated_at_utc": utc_now(),
        "schema_sha256": {
            "scenario": schema_hash(SCHEMA_DESCRIPTIONS["scenario"]),
            "request": schema_hash(SCHEMA_DESCRIPTIONS["probe"]),
        },
        "files_sha256": {
            name: hashlib.sha256((run_dir / name).read_bytes()).hexdigest()
            for name in ("requests.jsonl", "scenario.jsonl")
        },
    }
    summary_path = run_dir / f"summary-{phase}.json"
    with summary_path.open("x", encoding="utf-8") as stream:
        json.dump(summary, stream, indent=2, sort_keys=True)
        stream.write("\n")
    return summary


def finalize_run(run_dir: Path) -> dict[str, Any]:
    """Hash the final append-only streams after all operator markers are added."""
    if not run_dir.is_dir():
        raise ValueError("run-dir must exist before finalization")
    paths = {name: run_dir / name for name in ("scenario.jsonl", "requests.jsonl")}
    missing = [name for name, path in paths.items() if not path.is_file()]
    records: dict[str, list[dict[str, Any]]] = {name: [] for name in paths}
    malformed = 0
    for name, path in paths.items():
        if not path.is_file():
            continue
        for line in path.read_text(encoding="utf-8").splitlines():
            try:
                item = json.loads(line)
                if not isinstance(item, dict):
                    raise ValueError("record is not an object")
                records[name].append(item)
            except (json.JSONDecodeError, ValueError):
                malformed += 1
    requests = records["requests.jsonl"]
    down = sum(1 for item in requests if item.get("state") == "DOWN")
    unknown = sum(1 for item in requests if item.get("state") == "UNKNOWN")
    if down:
        status = "FAIL"
    elif missing or malformed or unknown:
        status = "UNKNOWN"
    else:
        status = "OBSERVED"
    summary = {
        "schema_version": SCHEMA_VERSION, "record_type": "scenario_final_summary",
        "run_id": run_dir.name, "status": status, "missing_files": missing,
        "malformed_records": malformed, "scenario_event_count": len(records["scenario.jsonl"]),
        "request_attempts": len(requests), "down_observations": down, "unknown_observations": unknown,
        "http_status_counts": {
            str(code): sum(1 for item in requests if item.get("status_code") == code)
            for code in sorted({item.get("status_code") for item in requests if item.get("status_code") is not None})
        },
        "schema_sha256": {
            "scenario": schema_hash(SCHEMA_DESCRIPTIONS["scenario"]),
            "request": schema_hash(SCHEMA_DESCRIPTIONS["probe"]),
        },
        "files_sha256": {
            name: hashlib.sha256(path.read_bytes()).hexdigest()
            for name, path in paths.items() if path.is_file()
        },
        "generated_at_utc": utc_now(),
    }
    with (run_dir / "summary-final.json").open("x", encoding="utf-8") as stream:
        json.dump(summary, stream, indent=2, sort_keys=True)
        stream.write("\n")
    return summary


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)
    run = subparsers.add_parser("run", help="run a bounded observation phase; optional GET-only traffic")
    run.add_argument("--run-dir", type=Path, required=True)
    run.add_argument("--phase", choices=sorted(PHASES), required=True)
    run.add_argument("--duration-seconds", type=int, default=30)
    run.add_argument("--requests-per-second", type=float, default=0)
    run.add_argument("--url", help="explicit localhost/private-IP GET target; no URL is recorded")
    run.add_argument("--route-template", default="/")
    marker = subparsers.add_parser("mark", help="append an operator-observed marker only; performs no cluster action")
    marker.add_argument("--run-dir", type=Path, required=True)
    marker.add_argument("--phase", choices=sorted(PHASES), required=True)
    marker.add_argument("--event", choices=sorted(MARKER_EVENTS), required=True)
    marker.add_argument("--pod-uid")
    marker.add_argument("--request-id")
    marker.add_argument("--identity-role", choices=["baseline", "candidate", "known-good", "rollback"])
    marker.add_argument("--identity", help="sanitized image digest/ref or rollout identity; recorded as supplied")
    finalize = subparsers.add_parser("finalize", help="hash final JSONL streams after all marker writes")
    finalize.add_argument("--run-dir", type=Path, required=True)
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        if args.command == "mark":
            result = record_marker(
                args.run_dir, phase=args.phase, event=args.event, pod_uid=args.pod_uid,
                request_id=args.request_id, identity_role=args.identity_role, identity=args.identity,
            )
        elif args.command == "finalize":
            result = finalize_run(args.run_dir)
        else:
            result = run_phase(
                args.run_dir, phase=args.phase, duration_seconds=args.duration_seconds,
                requests_per_second=args.requests_per_second, url=args.url, route_template=args.route_template,
            )
    except (ValueError, OSError) as exc:
        print(json.dumps({"status": "UNKNOWN", "error": str(exc)}))
        return 2
    print(json.dumps(result, sort_keys=True))
    return 0 if result.get("status", "OBSERVED") == "OBSERVED" else 2


if __name__ == "__main__":
    raise SystemExit(main())
