from __future__ import annotations

import json
import os
import subprocess
import tempfile
import unittest
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock

import yaml

from scripts.release import orchestrate, receipts as receipt_core


class OrchestrationContractsTests(unittest.TestCase):
    def test_run_does_not_leak_github_token_to_kubectl(self) -> None:
        original = os.environ.get("GH_TOKEN")
        smoke_original = os.environ.get("TCG_SMOKE_BUYER_USERNAME")
        os.environ["GH_TOKEN"] = "test-only-token"
        os.environ["TCG_SMOKE_BUYER_USERNAME"] = "offline-smoke-user"
        calls = []

        def runner(command, **kwargs):
            calls.append((command, kwargs))
            return subprocess.CompletedProcess(command, 0, "", "")

        try:
            orchestrate._run(runner, ["kubectl", "--context", "offline", "get", "pods"])
            orchestrate._run(runner, ["gh", "api", "repos/example/repo"])
        finally:
            if original is None:
                os.environ.pop("GH_TOKEN", None)
            else:
                os.environ["GH_TOKEN"] = original
            if smoke_original is None:
                os.environ.pop("TCG_SMOKE_BUYER_USERNAME", None)
            else:
                os.environ["TCG_SMOKE_BUYER_USERNAME"] = smoke_original

        self.assertNotIn("GH_TOKEN", calls[0][1]["env"])
        self.assertEqual(calls[0][1]["env"]["TCG_SMOKE_BUYER_USERNAME"], "offline-smoke-user")
        self.assertEqual(calls[1][1]["env"]["GH_TOKEN"], "test-only-token")

    def test_observation_is_bound_to_release_and_real_rollout_window(self) -> None:
        now = datetime(2026, 10, 16, 12, 10, tzinfo=timezone.utc)
        evidence = {
            "status": "healthy", "noData": False, "environment": "staging",
            "releaseId": "release-1", "imageDigest": "sha256:" + "a" * 64,
            "rolloutCompletedAt": "2026-10-16T11:59:00Z",
            "windowStartedAt": "2026-10-16T12:00:00Z", "observedAt": "2026-10-16T12:10:00Z",
            "sampleCount": 20, "observationWindowSeconds": 600,
        }
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "observation.json"
            path.write_text(json.dumps(evidence), encoding="utf-8")
            result = orchestrate._observation(path, environment="staging", release_id="release-1",
                image_digest="sha256:" + "a" * 64, rollout_completed_at="2026-10-16T11:59:00Z", now=now)
            self.assertEqual(result["observationWindowSeconds"], 600)
            with self.assertRaises(orchestrate.ReleaseError):
                orchestrate._observation(path, environment="production", release_id="release-1",
                    image_digest="sha256:" + "a" * 64, rollout_completed_at="2026-10-16T11:59:00Z", now=now)
            evidence["noData"] = True
            path.write_text(json.dumps(evidence), encoding="utf-8")
            with self.assertRaises(orchestrate.ReleaseError):
                orchestrate._observation(path, environment="staging", release_id="release-1",
                    image_digest="sha256:" + "a" * 64, rollout_completed_at="2026-10-16T11:59:00Z", now=now)
            evidence["noData"] = False
            evidence["windowStartedAt"] = "2026-10-16T12:00:00X"
            path.write_text(json.dumps(evidence), encoding="utf-8")
            with self.assertRaises(orchestrate.ReleaseError):
                orchestrate._observation(path, environment="staging", release_id="release-1",
                    image_digest="sha256:" + "a" * 64, rollout_completed_at="2026-10-16T11:59:00Z", now=now)

    def test_observation_wait_polls_until_window_completes_while_asserting_lease(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "observation.json"
            start = datetime(2026, 10, 16, 12, 0, tzinfo=timezone.utc)
            monotonic = [0.0]
            wallclock = [start]
            lease = SimpleNamespace(assert_held=Mock())

            def sleep(seconds):
                monotonic[0] += seconds
                wallclock[0] += timedelta(seconds=seconds)
                if monotonic[0] >= 600:
                    evidence = {"status": "healthy", "noData": False, "environment": "staging",
                        "releaseId": "release-1", "imageDigest": "sha256:" + "a" * 64,
                        "rolloutCompletedAt": "2026-10-16T11:59:00Z", "windowStartedAt": "2026-10-16T12:00:00Z",
                        "observedAt": wallclock[0].isoformat().replace("+00:00", "Z"),
                        "sampleCount": 10, "observationWindowSeconds": 600}
                    path.write_text(json.dumps(evidence), encoding="utf-8")

            evidence = orchestrate._wait_for_observation(path, environment="staging", release_id="release-1",
                image_digest="sha256:" + "a" * 64, rollout_completed_at="2026-10-16T11:59:00Z",
                lease=lease, timeout_seconds=900, clock=lambda: monotonic[0], sleep=sleep, now=lambda: wallclock[0])
            self.assertEqual(600, monotonic[0])
            self.assertEqual(600, evidence["observationWindowSeconds"])
            self.assertEqual(41, lease.assert_held.call_count)

    def test_deploy_command_passes_python_and_lease_identity_to_helper(self) -> None:
        args = SimpleNamespace(context="offline-context", environment="staging", release_id="r1")
        lock = SimpleNamespace(lease_name="tcg-release-lock", holder_identity="r1:lease-id")
        command = orchestrate._deploy_command(args, Path("bundle"), Path("progress.json"), Path("ready.json"), lock)
        self.assertIn("-Python", command)
        self.assertEqual(command[command.index("-LeaseName") + 1], "tcg-release-lock")
        self.assertEqual(command[command.index("-LeaseHolderIdentity") + 1], "r1:lease-id")
        self.assertIn("offline-context", command)

    def test_promotion_binds_receipt_to_trusted_run_id_not_workflow_source_sha(self) -> None:
        now = datetime.now(timezone.utc).replace(microsecond=0)
        receipt = {"releaseId": "release-1", "environment": "staging", "deployRunId": 456,
            "sourceCommit": "b" * 40, "bundleHash": "c" * 64}
        run = {"id": 456, "path": ".github/workflows/deploy-staging.yml", "event": "workflow_dispatch",
            "head_branch": "main", "head_sha": "a" * 40, "status": "completed", "conclusion": "success",
            "repository": {"full_name": "example/repo"}, "head_repository": {"full_name": "example/repo"},
            "updated_at": now.isoformat().replace("+00:00", "Z")}

        def runner(command, **kwargs):
            self.assertEqual(command[0], "gh")
            return subprocess.CompletedProcess(command, 0, json.dumps(run), "")

        with tempfile.TemporaryDirectory() as temp:
            artifact_root = Path(temp)
            receipt_path = artifact_root / "release-1.json"
            bundle = artifact_root / "bundle"
            bundle.mkdir()
            receipt_path.write_text("{}", encoding="utf-8")
            for filename in ("application.yaml", "migration.yaml", "prerequisites.yaml", "bootstrap.yaml",
                             "ci-prerequisites.yaml", "ci-application.yaml", "bundle.json"):
                (bundle / filename).write_text("offline", encoding="utf-8")
            api = SimpleNamespace(load_receipt=lambda _path, **_kwargs: receipt,
                validate_receipt=lambda candidate, **_kwargs: candidate,
                verify_promotion=lambda candidate, artifact, **_kwargs: self.assertEqual(candidate, receipt))
            original_runner = runner
            def artifact_runner(command, **kwargs):
                if command[0] == "gh":
                    return original_runner(command, **kwargs)
                return subprocess.CompletedProcess(command, 0,
                    json.dumps({"schemaVersion": 2, "bundleHash": receipt["bundleHash"]}), "")
            accepted = orchestrate._trusted_staging_receipt(artifact_runner, "example/repo", "456", receipt_path,
                bundle, "release-1", {"sourceCommit": "b" * 40}, api)
        self.assertEqual(accepted["deployRunId"], 456)
        run["updated_at"] = (now - timedelta(days=8)).isoformat().replace("+00:00", "Z")
        with tempfile.TemporaryDirectory() as temp:
            artifact_root = Path(temp)
            receipt_path = artifact_root / "release-1.json"
            bundle = artifact_root / "bundle"
            bundle.mkdir()
            for filename in ("application.yaml", "migration.yaml", "prerequisites.yaml", "bootstrap.yaml",
                             "ci-prerequisites.yaml", "ci-application.yaml", "bundle.json"):
                (bundle / filename).write_text("offline", encoding="utf-8")
            with self.assertRaises(orchestrate.ReleaseError):
                orchestrate._trusted_staging_receipt(artifact_runner, "example/repo", "456", receipt_path,
                    bundle, "release-1", {"sourceCommit": "b" * 40}, api)

    def test_candidate_smoke_passes_validated_frontend_origin(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            args = SimpleNamespace(environment="staging", base_url="https://api.shop.test")
            captured = []
            def runner(command, **kwargs):
                captured.append(command)
                output = Path(command[command.index("--output") + 1])
                output.write_text(json.dumps({"status": "passed"}), encoding="utf-8")
                return subprocess.CompletedProcess(command, 0, "", "")
            result = orchestrate._smoke(args, runner, directory, "https://shop.test")
            self.assertEqual(result["status"], "passed")
            self.assertEqual(captured[0][captured[0].index("--frontend-origin") + 1], "https://shop.test")

    def test_restoration_frontend_origin_comes_from_retained_bundle_configmap(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            bundle = Path(temp)
            (bundle / "prerequisites.yaml").write_text(
                "apiVersion: v1\nkind: ConfigMap\nmetadata:\n  name: tcg-store-backend-config\n"
                "data:\n  APP_CORS_ALLOWED_ORIGINS: https://previous.shop.test\n", encoding="utf-8")
            self.assertEqual(orchestrate._bundle_frontend_origin(bundle), "https://previous.shop.test")

    def test_direct_script_loading_can_import_shared_release_interfaces_outside_repo_cwd(self) -> None:
        script = Path(orchestrate.__file__).resolve()
        code = f"import runpy; module=runpy.run_path({str(script)!r}, run_name='release_interface_probe'); module['_load_shared_apis']()"
        with tempfile.TemporaryDirectory() as temp:
            result = subprocess.run([sys.executable, "-c", code], cwd=temp,
                capture_output=True, text=True, check=False)
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_progress_receipt_requires_completed_migration(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "progress.json"
            path.write_text(json.dumps({"phase": "rollout-complete", "migrationCompleted": True,
                "startedAt": "2026-10-16T11:59:00Z", "rolloutCompletedAt": "2026-10-16T12:00:00Z"}), encoding="utf-8")
            self.assertTrue(orchestrate._safe_progress(path)["migrationCompleted"])
            path.write_text(json.dumps({"phase": "rollout-complete", "migrationCompleted": None,
                "startedAt": "2026-10-16T11:59:00Z", "rolloutCompletedAt": "2026-10-16T12:00:00Z"}), encoding="utf-8")
            with self.assertRaises(orchestrate.ReleaseError):
                orchestrate._safe_progress(path)

    def test_prune_requires_exact_live_identity_and_release_owner_label(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            current = root / "current"
            previous = root / "previous"
            current.mkdir()
            previous.mkdir()
            resource = {"apiVersion": "v1", "kind": "ConfigMap", "metadata": {
                "name": "release-config", "namespace": "tcg-store-staging",
                "labels": {"app.kubernetes.io/managed-by": "tcg-release"}}, "data": {"x": "y"}}
            for directory in (current, previous):
                for name in ("ci-application.yaml", "migration.yaml", "ci-prerequisites.yaml"):
                    (directory / name).write_text("", encoding="utf-8")
            (current / "ci-prerequisites.yaml").write_text(yaml.safe_dump(resource), encoding="utf-8")
            calls = []

            def runner(command, **kwargs):
                calls.append(command)
                if command[0] == "python":
                    return subprocess.CompletedProcess(command, 0, '{"applicationDeploymentName":"api"}', "")
                if command[0] == "kubectl" and "get" in command:
                    wrong_owner = {**resource, "metadata": {**resource["metadata"], "labels": {}}}
                    return subprocess.CompletedProcess(command, 0, json.dumps(wrong_owner), "")
                return subprocess.CompletedProcess(command, 0, "", "")

            args = SimpleNamespace(context="offline-context", environment="staging", release_id="r2")
            lock = SimpleNamespace(assert_held=Mock())
            def assert_live(resource, expected):
                labels = resource.get("metadata", {}).get("labels", {})
                if labels.get("app.kubernetes.io/managed-by") != "tcg-release":
                    raise ValueError("unowned")

            receipts = SimpleNamespace(bundle_inventory=lambda _bundle, _env: [
                {"apiVersion": "v1", "kind": "ConfigMap", "namespace": "tcg-store-staging", "name": "release-config"}],
                inventory_delta=receipt_core.inventory_delta,
                assert_release_owned_live_resource=assert_live)
            result = orchestrate._restore_previous(runner, args, current, {"evidence": {"inventory": []}},
                previous, receipts, root, lock)
            self.assertFalse(result)
            self.assertFalse(any("delete" in command for command in calls))

    def test_last_good_paths_cannot_escape_trusted_state_root(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp) / "state"
            (root / "staging").mkdir(parents=True)
            pointer = root / "staging" / "last-known-good.json"
            pointer.write_text(json.dumps({"receiptPath": str(Path(temp) / "outside.json"),
                "bundlePath": str(Path(temp) / "outside-bundle"), "bundleHash": "x"}), encoding="utf-8")
            with self.assertRaises(orchestrate.ReleaseError):
                orchestrate._load_previous(root, "staging", Mock(), Mock(), "example/repo", Mock())

    def test_migration_failure_does_not_invoke_rollback_helper(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            ready = root / "ready.json"
            ready.write_text(json.dumps({
                "schemaVersion": 1, "issuer": "platform-operator", "environment": "staging",
                "namespace": "tcg-store-staging", "secretName": "tcg-store-backend-secrets", "ready": True,
                "keyNames": sorted(orchestrate.REQUIRED_SECRET_KEYS),
                "checkedAt": datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z"),
            }), encoding="utf-8")
            config = root / "config.json"
            config.write_text("{}", encoding="utf-8")
            state = root / "state"
            bundle_hash = {"value": "0" * 64}
            deploy_calls = []

            class Lock:
                lease_name = "tcg-release-lock"
                holder_identity = "release-1:mock-holder"

                def __enter__(self):
                    return self

                def __exit__(self, *_args):
                    return False

                def assert_held(self):
                    return None

            class Locking:
                LeaseLock = lambda *_args, **_kwargs: Lock()

            class Receipts:
                def validate_receipt(self, receipt, **_kwargs):
                    return receipt

                def write_receipt(self, path, receipt, **_kwargs):
                    Path(path).write_text(json.dumps(receipt), encoding="utf-8")

            class Recovery:
                @staticmethod
                def plan_recovery(*_args, **_kwargs):
                    return "recovery-required"

            artifact = {
                "repository": "ghcr.io/mudst-2026-luntungsadungyong/tcg-store-backend",
                "sourceCommit": "b" * 40, "buildRunId": 123,
                "image": "ghcr.io/mudst-2026-luntungsadungyong/tcg-store-backend@sha256:" + "a" * 64,
                "indexDigest": "sha256:" + "a" * 64,
                "platformDigests": {"linux/amd64": "sha256:" + "c" * 64, "linux/arm64": "sha256:" + "d" * 64},
                "verification": {"signature": True, "provenance": True, "sbom": True},
            }

            class Verifier:
                @staticmethod
                def verify_release(*_args, **_kwargs):
                    return artifact

            def runner(command, **kwargs):
                if command[0] == "gh":
                    return subprocess.CompletedProcess(command, 0,
                        json.dumps({"protection_rules": [{"type": "required_reviewers", "reviewers": [{"id": 1}]}]}), "")
                if command[0] == sys.executable and command[1].endswith("render.py"):
                    if command[2] == "render":
                        output = Path(command[command.index("--output-dir") + 1]) / "staging"
                        output.mkdir(parents=True)
                        for name in ("application.yaml", "migration.yaml", "prerequisites.yaml",
                                     "bootstrap.yaml", "ci-prerequisites.yaml", "ci-application.yaml"):
                            (output / name).write_text("# offline fixture\n", encoding="utf-8")
                        bundle_hash["value"] = orchestrate._sha_bundle(output)
                        (output / "bundle.json").write_text(json.dumps({"schemaVersion": 2,
                            "environment": "staging", "bundleHash": bundle_hash["value"], "fixture": False}), encoding="utf-8")
                        return subprocess.CompletedProcess(command, 0, "", "")
                    return subprocess.CompletedProcess(command, 0,
                        json.dumps({"bundleHash": bundle_hash["value"], "configHash": "e" * 64}), "")
                if command[0] == "kubectl":
                    return subprocess.CompletedProcess(command, 0, "", "")
                if command[0] == "pwsh":
                    deploy_calls.append(command)
                    progress = Path(command[command.index("-ProgressFile") + 1])
                    progress.write_text(json.dumps({"phase": "migration", "migrationCompleted": False}), encoding="utf-8")
                    return subprocess.CompletedProcess(command, 1, "", "mock migration failure")
                self.fail(f"unexpected external command in offline test: {command[0]}")

            args = SimpleNamespace(environment="staging", repository="example/repo", build_run_id="123",
                release_id="release-1", config=config, context="offline-context", readiness_file=ready,
                observation_file=root / "unused-observation.json", state_root=state, progress_root=state,
                base_url="https://api.example.invalid",
                migration_compatibility="backward-compatible", staging_run_id=None, staging_receipt=None,
                staging_receipt_id=None)
            old_token = os.environ.get("GH_TOKEN")
            old_run_id = os.environ.get("GITHUB_RUN_ID")
            os.environ["GH_TOKEN"] = "offline-test-token"
            os.environ["GITHUB_RUN_ID"] = "789"
            try:
                with self.assertRaises(orchestrate.ReleaseError):
                    orchestrate.orchestrate(args, runner=runner,
                        shared=(Verifier, Receipts(), Locking(), Recovery()))
            finally:
                if old_token is None:
                    os.environ.pop("GH_TOKEN", None)
                else:
                    os.environ["GH_TOKEN"] = old_token
                if old_run_id is None:
                    os.environ.pop("GITHUB_RUN_ID", None)
                else:
                    os.environ["GITHUB_RUN_ID"] = old_run_id
            self.assertEqual(len(deploy_calls), 1, "rollback must not rerun the migration helper")
            self.assertFalse(any("bootstrap.yaml" in command for command in deploy_calls))


if __name__ == "__main__":
    unittest.main()
