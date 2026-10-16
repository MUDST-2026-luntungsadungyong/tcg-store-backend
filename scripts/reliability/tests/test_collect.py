from __future__ import annotations

import hashlib
import json
import sys
from pathlib import Path
from urllib.error import HTTPError, URLError

import pytest

RELIABILITY_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(RELIABILITY_DIR))
import collect  # noqa: E402


class FakeResponse:
    def __init__(self, status=200, body=b'{"status":"UP"}', headers=None):
        self.status = status
        self._body = body
        self.headers = headers or {}
        self.closed = False

    def read(self, size=-1):
        return self._body[:size]

    def close(self):
        self.closed = True


class FakeClock:
    def __init__(self):
        self.value = 0.0

    def __call__(self):
        return self.value

    def sleep(self, seconds):
        self.value += seconds


def fake_kubectl_ok(args, **kwargs):
    resource = args[-3]
    fixtures = {
        "pods": [{"metadata": {"name": "api-abc", "uid": "pod-uid-1"}, "spec": {"nodeName": "worker-a"}, "status": {"phase": "Running"}}],
        "nodes": [{"metadata": {"name": "worker-a", "uid": "node-uid-1"}, "status": {"conditions": [{"type": "Ready", "status": "True"}], "allocatable": {"cpu": "2"}}}],
        "deployments": [{"metadata": {"name": "api", "uid": "deploy-uid-1", "generation": 1}, "spec": {"replicas": 2, "template": {"spec": {"containers": [{"name": "api", "image": "api@sha256:abc"}]}}}, "status": {"observedGeneration": 1, "replicas": 2, "readyReplicas": 2, "availableReplicas": 2}}],
        "replicasets": [{"metadata": {"name": "api-rs", "uid": "rs-uid-1", "ownerReferences": [{"kind": "Deployment", "name": "api", "uid": "deploy-uid-1"}], "annotations": {"deployment.kubernetes.io/revision": "1"}}, "spec": {"template": {"spec": {"containers": [{"name": "api", "image": "api@sha256:abc"}]}}}, "status": {"replicas": 2, "readyReplicas": 2, "availableReplicas": 2}}],
        "horizontalpodautoscalers": [{"metadata": {"name": "api-hpa", "uid": "hpa-uid-1"}, "spec": {"minReplicas": 2, "maxReplicas": 4}, "status": {"currentReplicas": 2, "desiredReplicas": 2, "currentMetrics": [{"type": "Resource", "resource": {"name": "cpu", "current": {"averageUtilization": 25}}}]}}],
        "poddisruptionbudgets": [{"metadata": {"name": "api-pdb", "uid": "pdb-uid-1"}, "spec": {"minAvailable": 1}, "status": {"currentHealthy": 2, "desiredHealthy": 1, "expectedPods": 2, "disruptionsAllowed": 1}}],
        "endpointslices": [{"metadata": {"name": "api-slice", "uid": "slice-uid-1"}, "addressType": "IPv4", "endpoints": [{"targetRef": {"kind": "Pod", "name": "api-abc", "uid": "pod-uid-1"}, "conditions": {"ready": True, "serving": True, "terminating": False}}]}],
        "events": [],
    }
    items = fixtures[resource]
    return type("Result", (), {"returncode": 0, "stdout": json.dumps({"items": items}), "stderr": ""})()


def test_local_url_rejects_public_hosts_credentials_and_query():
    for value in (
        "https://example.com/health",
        "http://user:password@127.0.0.1/health",
        "http://127.0.0.1/health?token=secret",
    ):
        with pytest.raises(ValueError):
            collect._safe_local_url(value)


def test_probe_map_requires_pod_uid_and_explicit_private_endpoint(tmp_path):
    mapping = tmp_path / "probes.json"
    mapping.write_text(json.dumps({
        "schema_version": 1,
        "pods": [{
            "pod_uid": "pod-uid-1",
            "liveness_url": "http://127.0.0.1:18081/actuator/health/liveness",
            "readiness_url": "http://127.0.0.1:18081/actuator/health/readiness",
        }],
    }), encoding="utf-8")
    assert collect.load_probe_map(mapping)[0]["pod_uid"] == "pod-uid-1"


def test_http_attempt_records_id_status_duration_and_no_body_or_url():
    captured = {}

    def opener(request, timeout):
        captured["request_id"] = request.get_header("X-request-id")
        captured["url"] = request.full_url
        captured["timeout"] = timeout
        return FakeResponse(headers={"X-Request-ID": "123e4567-e89b-12d3-a456-426614174000"})

    record = collect.http_get_attempt(
        "http://127.0.0.1:18081/actuator/health/liveness", run_id="r1",
        route_template="/actuator/health/liveness", pod_uid="uid-1", probe="liveness", opener=opener,
    )
    assert record["state"] == "UP"
    assert record["status_code"] == 200
    assert record["request_id"] == captured["request_id"]
    assert record["response_request_id"] == "123e4567-e89b-12d3-a456-426614174000"
    assert record["duration_ms"] >= 0
    assert record["retry"] is False
    assert "url" not in record and "body" not in record and "secret" not in json.dumps(record)


def test_http_503_is_down_and_transport_error_is_unknown():
    def unavailable(request, timeout):
        raise HTTPError(request.full_url, 503, "not included in evidence", {}, None)

    def disconnected(request, timeout):
        raise URLError("sensitive URL must not leak")

    down = collect.http_get_attempt("http://127.0.0.1:18081/live", run_id="r", route_template="/live", opener=unavailable)
    unknown = collect.http_get_attempt("http://127.0.0.1:18081/live", run_id="r", route_template="/live", opener=disconnected)
    assert down["state"] == "DOWN" and down["status_code"] == 503
    assert unknown["state"] == "UNKNOWN" and unknown["error"] == "transport_error"
    assert "sensitive URL" not in json.dumps(unknown)


def test_kubectl_uses_explicit_context_and_namespace_and_sanitizes_failure():
    seen = []

    def runner(args, **kwargs):
        seen.append(args)
        return type("Result", (), {"returncode": 1, "stdout": "", "stderr": "secret value from tool"})()

    state, records, error = collect.kubectl_resource("isolated-lab", "acceptance-a", "pods", runner=runner)
    assert state == "UNKNOWN" and records == [] and error == "kubectl_read_failed"
    assert seen[0][:5] == ["kubectl", "--context", "isolated-lab", "--namespace", "acceptance-a"]
    assert "secret" not in json.dumps({"state": state, "error": error})


def test_sanitizer_captures_uid_restart_and_endpoint_conditions_not_addresses_or_messages():
    pod = collect.sanitize_item("pods", {
        "metadata": {"name": "api-abc", "uid": "uid-1", "resourceVersion": "9", "labels": {"secret": "dont-copy"}},
        "spec": {"nodeName": "worker-a", "containers": [{"name": "api", "image": "api@sha256:abc"}]},
        "status": {"phase": "Running", "containerStatuses": [{"name": "api", "ready": True, "restartCount": 2, "imageID": "api@sha256:abc"}]},
    })
    event = collect.sanitize_item("events", {
        "metadata": {"name": "event-1"}, "type": "Warning", "reason": "BackOff", "message": "secret=do-not-export",
        "involvedObject": {"kind": "Pod", "name": "api-abc", "uid": "uid-1"},
    })
    assert pod["uid"] == "uid-1" and pod["container_statuses"][0]["restart_count"] == 2
    assert event["regarding_uid"] == "uid-1" and "message" not in event
    assert "dont-copy" not in json.dumps(pod) and "do-not-export" not in json.dumps(event)


def test_collector_missing_probe_mapping_is_unknown_and_hashes_jsonl(tmp_path):
    clock = FakeClock()
    summary = collect.collect(
        context="isolated-lab", namespace="acceptance-a", run_dir=tmp_path / "run-1",
        duration_seconds=1, snapshot_cadence_seconds=1, probe_cadence_seconds=1,
        runner=fake_kubectl_ok, clock=clock, sleeper=clock.sleep,
    )
    assert summary["status"] == "UNKNOWN"
    assert summary["unknown_observations"] > 0
    probes = (tmp_path / "run-1" / "probes.jsonl").read_text(encoding="utf-8")
    assert '"error":"per_pod_probe_mapping_missing"' in probes
    digest = hashlib.sha256((tmp_path / "run-1" / "cluster.jsonl").read_bytes()).hexdigest()
    assert summary["files_sha256"]["cluster.jsonl"] == digest
    assert summary["schema_sha256"]["probe"] == collect.schema_hash(collect.SCHEMA_DESCRIPTIONS["probe"])


def test_collector_marks_unmatched_pod_uid_unknown_without_probe_request(tmp_path):
    clock = FakeClock()
    mapping = [{
        "pod_uid": "stale-uid",
        "liveness_url": "http://127.0.0.1:18081/live",
        "readiness_url": "http://127.0.0.1:18081/ready",
    }]
    called = []

    def opener(*args, **kwargs):
        called.append(True)
        return FakeResponse()

    summary = collect.collect(
        context="isolated-lab", namespace="acceptance-a", run_dir=tmp_path / "run-2",
        duration_seconds=1, snapshot_cadence_seconds=1, probe_cadence_seconds=1,
        probe_map=mapping, runner=fake_kubectl_ok, opener=opener,
        clock=clock, sleeper=clock.sleep,
    )
    probes = [json.loads(line) for line in (tmp_path / "run-2" / "probes.jsonl").read_text().splitlines()]
    assert not called
    assert all(item["state"] == "UNKNOWN" and item["error"] == "pod_uid_not_in_latest_snapshot" for item in probes)
    assert all(item["record_type"] == "probe_observation" and item["request_id"] is None for item in probes)
    assert summary["status"] == "UNKNOWN"
    assert summary["probe_attempts"] == 0


def test_collector_probes_only_uid_matched_local_targets_and_accounts_each_attempt(tmp_path):
    clock = FakeClock()
    requests = []

    def opener(request, timeout):
        requests.append(request.full_url)
        return FakeResponse()

    mapping = [{
        "pod_uid": "pod-uid-1",
        "liveness_url": "http://127.0.0.1:18081/actuator/health/liveness",
        "readiness_url": "http://127.0.0.1:18081/actuator/health/readiness",
    }]
    summary = collect.collect(
        context="isolated-lab", namespace="acceptance-a", run_dir=tmp_path / "run-3",
        duration_seconds=1, snapshot_cadence_seconds=1, probe_cadence_seconds=1,
        probe_map=mapping, runner=fake_kubectl_ok, opener=opener,
        clock=clock, sleeper=clock.sleep,
    )
    records = [json.loads(line) for line in (tmp_path / "run-3" / "probes.jsonl").read_text().splitlines()]
    assert len(requests) == 2
    assert len(records) == summary["probe_attempts"] == 2
    assert all(item["record_type"] == "http_attempt" and item["pod_uid"] == "pod-uid-1" for item in records)
    assert all(item["state"] == "UP" and item["request_id"] for item in records)
    assert summary["status"] == "OBSERVED"


def test_collector_health_down_is_failure_not_unknown_or_pass(tmp_path):
    clock = FakeClock()

    def down(request, timeout):
        raise HTTPError(request.full_url, 503, "not serialized", {}, None)

    mapping = [{
        "pod_uid": "pod-uid-1",
        "liveness_url": "http://127.0.0.1:18081/actuator/health/liveness",
        "readiness_url": "http://127.0.0.1:18081/actuator/health/readiness",
    }]
    summary = collect.collect(
        context="isolated-lab", namespace="acceptance-a", run_dir=tmp_path / "run-4",
        duration_seconds=1, snapshot_cadence_seconds=1, probe_cadence_seconds=1,
        probe_map=mapping, runner=fake_kubectl_ok, opener=down,
        clock=clock, sleeper=clock.sleep,
    )
    assert summary["status"] == "FAIL"
    assert summary["down_observations"] == 2
    assert summary["unknown_observations"] == 0


def test_collector_bounds_duration_and_refuses_overwrite(tmp_path):
    with pytest.raises(ValueError):
        collect.collect(context="c", namespace="n", run_dir=tmp_path / "bad", duration_seconds=3601)
    occupied = tmp_path / "occupied"
    occupied.mkdir()
    (occupied / "cluster.jsonl").write_text("existing\n", encoding="utf-8")
    with pytest.raises(FileExistsError):
        collect.collect(context="c", namespace="n", run_dir=occupied, duration_seconds=1)
