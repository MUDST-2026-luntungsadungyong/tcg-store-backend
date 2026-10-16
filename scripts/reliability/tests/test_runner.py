from __future__ import annotations

import json
import sys
from pathlib import Path

import pytest

RELIABILITY_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(RELIABILITY_DIR))
import run_component_a as component_runner  # noqa: E402


class FakeClock:
    def __init__(self):
        self.value = 0.0

    def __call__(self):
        return self.value

    def sleep(self, seconds):
        self.value += seconds


def test_run_phase_is_bounded_get_only_and_accounts_each_attempt(tmp_path):
    clock = FakeClock()
    requests = []

    def fake_get(url, **kwargs):
        requests.append((url, kwargs))
        return {
            "request_id": f"req-{len(requests)}", "status_code": 200,
            "state": "UP", "duration_ms": 12.5, "error": None,
        }

    result = component_runner.run_phase(
        tmp_path / "run-1", phase="baseline", duration_seconds=2,
        requests_per_second=1, url="http://127.0.0.1:18080/api/health",
        route_template="/api/health", runner_get=fake_get,
        clock=clock, sleeper=clock.sleep,
    )
    rows = [json.loads(line) for line in (tmp_path / "run-1" / "requests.jsonl").read_text().splitlines()]
    assert result["planned_attempts"] == 2
    assert result["attempts"] == len(requests) == len(rows) == 2
    assert result["completed_2xx"] == 2
    assert result["latency_ms"]["p95"] == 12.5
    assert all(call[0] == "http://127.0.0.1:18080/api/health" for call in requests)
    assert all(row["route_template"] == "/api/health" and "url" not in row for row in rows)
    assert all(row["retry"] is False for row in rows)


def test_runner_unknown_transport_count_is_not_zero_or_success(tmp_path):
    clock = FakeClock()

    def unknown_get(url, **kwargs):
        return {"request_id": "r1", "status_code": None, "state": "UNKNOWN", "duration_ms": 3, "error": "transport_error"}

    result = component_runner.run_phase(
        tmp_path / "run-unknown", phase="shared_state", duration_seconds=1,
        requests_per_second=1, url="http://127.0.0.1:18080/api/health",
        runner_get=unknown_get, clock=clock, sleeper=clock.sleep,
    )
    assert result["status"] == "UNKNOWN"
    assert result["http_status_counts"] == {"transport_error": 1}


def test_runner_has_short_default_and_hard_rate_duration_caps():
    args = component_runner.build_parser().parse_args(["run", "--run-dir", "run", "--phase", "baseline"])
    assert args.duration_seconds == 30
    assert args.requests_per_second == 0
    with pytest.raises(ValueError):
        component_runner.run_phase(Path("ignored"), phase="baseline", duration_seconds=601)
    with pytest.raises(ValueError):
        component_runner.run_phase(
            Path("ignored"), phase="baseline", duration_seconds=600, requests_per_second=2.1,
            url="http://127.0.0.1/health",
        )
    with pytest.raises(ValueError):
        component_runner.run_phase(
            Path("ignored"), phase="baseline", duration_seconds=601,
            url="http://127.0.0.1/health",
        )


def test_mark_records_observed_sigterm_and_image_identities_without_action(tmp_path):
    run_dir = tmp_path / "run-markers"
    sigterm = component_runner.record_marker(
        run_dir, phase="sigterm", event="sigterm_observed", pod_uid="pod-uid-1", request_id="req-1",
    )
    rollback = component_runner.record_marker(
        run_dir, phase="failed_rollout_rollback", event="rollback_known_good_observed",
        pod_uid="pod-uid-2", identity_role="rollback", identity="registry.local/api@sha256:abc123",
    )
    assert sigterm["operator_observed_only"] is True and sigterm["performed_by_runner"] is False
    assert rollback["identity_role"] == "rollback"
    assert rollback["identity"] == "registry.local/api@sha256:abc123"
    candidate = component_runner.record_marker(
        run_dir, phase="rollout", event="candidate_rollout_observed",
        identity_role="candidate", identity="registry.local/api@sha256:def456",
    )
    assert candidate["identity_role"] == "candidate"
    failed = component_runner.record_marker(
        run_dir, phase="failed_rollout_rollback", event="failed_rollout_observed",
        identity_role="candidate", identity="registry.local/api@sha256:bad999",
    )
    assert failed["event"] == "failed_rollout_observed"
    lines = (run_dir / "scenario.jsonl").read_text().splitlines()
    assert len(lines) == 4


def test_mark_rejects_secret_like_or_malformed_values(tmp_path):
    with pytest.raises(ValueError):
        component_runner.record_marker(
            tmp_path / "bad", phase="rollout", event="candidate_rollout_observed",
            identity_role="candidate", identity="token=abc",
        )
    with pytest.raises(ValueError):
        component_runner.record_marker(tmp_path / "bad", phase="unknown", event="sigterm_observed")
    with pytest.raises(ValueError):
        component_runner.record_marker(tmp_path / "bad", phase="sigterm", event="sigterm_observed", pod_uid="uid-only")


def test_mark_event_names_do_not_trigger_any_side_effect(tmp_path):
    # The runner has no kubectl/process-control dependency; marker recording is append-only evidence.
    marker = component_runner.record_marker(
        tmp_path / "marker", phase="sigterm", event="inflight_request_started",
        pod_uid="uid-1", request_id="req-22",
    )
    assert marker["event"] == "inflight_request_started"
    assert marker["performed_by_runner"] is False


def test_finalize_hashes_final_streams_and_reports_missing_as_unknown(tmp_path):
    run_dir = tmp_path / "run-final"
    component_runner.record_marker(
        run_dir, phase="rollout", event="candidate_rollout_observed",
        identity_role="candidate", identity="registry.local/api@sha256:abc123",
    )
    (run_dir / "requests.jsonl").write_text("", encoding="utf-8")
    final = component_runner.finalize_run(run_dir)
    assert final["status"] == "OBSERVED"
    assert final["scenario_event_count"] == 1
    assert set(final["files_sha256"]) == {"scenario.jsonl", "requests.jsonl"}

    unknown_dir = tmp_path / "run-incomplete"
    unknown_dir.mkdir()
    unknown = component_runner.finalize_run(unknown_dir)
    assert unknown["status"] == "UNKNOWN"
    assert set(unknown["missing_files"]) == {"scenario.jsonl", "requests.jsonl"}
