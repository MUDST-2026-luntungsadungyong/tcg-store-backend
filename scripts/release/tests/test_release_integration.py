"""Offline end-to-end release orchestration tests using the real receipt APIs.

The renderer, Kubernetes/GitHub commands, trusted artifact verifier, Lease, smoke
runner, and monitoring wait are controlled test doubles. Bundle integrity,
receipt persistence/validation, inventory derivation, and recovery decisions use
the production modules.
"""

from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

import yaml

from scripts.release import orchestrate, receipts, recovery


REPOSITORY = "ghcr.io/mudst-2026-luntungsadungyong/tcg-store-backend"
COMMIT = "b" * 40


class _Lease:
    lease_name = "tcg-release-lock"
    holder_identity = "release-integration:offline-holder"

    def __init__(self) -> None:
        self.assertions = 0

    def __enter__(self) -> _Lease:
        return self

    def __exit__(self, *_args: object) -> bool:
        return False

    def assert_held(self) -> None:
        self.assertions += 1


class _Locking:
    def __init__(self) -> None:
        self.lease = _Lease()

    def LeaseLock(self, *_args: object, **_kwargs: object) -> _Lease:
        return self.lease


class _Verifier:
    def __init__(self, artifact: dict[str, object]) -> None:
        self.artifact = artifact

    def verify_release(self, *_args: object, **_kwargs: object) -> dict[str, object]:
        return self.artifact

    def reverify_image_identity(self, *_args: object, **_kwargs: object) -> None:
        return None


class ReleaseIntegrationTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.state = self.root / "state"
        self.state.mkdir()
        self.calls: list[list[str]] = []
        self.deploy_mode = "success"
        self.candidate_smoke_fails = False
        self.smoke_origins: list[str] = []
        self.locking = _Locking()
        self.artifact = self._artifact(123, "a")
        self.shared = (
            _Verifier(self.artifact), receipts, self.locking, recovery,
        )
        self.readiness = self.root / "secret-readiness.json"
        now = datetime.now(timezone.utc).replace(microsecond=0)
        self._write_json(self.readiness, {
            "schemaVersion": 1,
            "issuer": "platform-operator",
            "environment": "staging",
            "namespace": "tcg-store-staging",
            "secretName": "tcg-store-backend-secrets",
            "ready": True,
            "keyNames": sorted(orchestrate.REQUIRED_SECRET_KEYS),
            "checkedAt": now.isoformat().replace("+00:00", "Z"),
        })
        self.config = self.root / "release-config.json"
        self._write_json(self.config, {
            "environment": "staging",
            "frontend_origin": "https://new.frontend.invalid",
        })

    @staticmethod
    def _write_json(path: Path, value: object) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(value, sort_keys=True), encoding="utf-8")

    @staticmethod
    def _artifact(run_id: int, digest_letter: str) -> dict[str, object]:
        index = "sha256:" + digest_letter * 64
        return {
            "repository": REPOSITORY,
            "sourceCommit": COMMIT,
            "buildRunId": run_id,
            "image": f"{REPOSITORY}@{index}",
            "indexDigest": index,
            "platformDigests": {
                "linux/amd64": "sha256:" + "c" * 64,
                "linux/arm64": "sha256:" + "d" * 64,
            },
            "verification": {"signature": True, "provenance": True, "sbom": True},
        }

    def _bundle(self, directory: Path, artifact: dict[str, object], *, origin: str,
                release_name: str = "tcg-store-backend", environment: str = "staging") -> dict[str, object]:
        directory.mkdir(parents=True, exist_ok=True)
        image = f"{artifact['repository']}@{artifact['indexDigest']}"
        owner = {"app.kubernetes.io/managed-by": "tcg-release"}
        deployment = {
            "apiVersion": "apps/v1", "kind": "Deployment",
            "metadata": {"name": release_name, "namespace": f"tcg-store-{environment}", "labels": owner},
            "spec": {"template": {"spec": {"containers": [{"name": "api", "image": image}]}}},
        }
        migration = {
            "apiVersion": "batch/v1", "kind": "Job",
            "metadata": {"name": f"{release_name}-migration", "namespace": f"tcg-store-{environment}"},
            "spec": {"template": {"spec": {"containers": [{"name": "migration", "image": image}]}}},
        }
        config_map = {
            "apiVersion": "v1", "kind": "ConfigMap",
            "metadata": {"name": "tcg-store-backend-config", "namespace": f"tcg-store-{environment}", "labels": owner},
            "data": {"APP_CORS_ALLOWED_ORIGINS": origin, "APP_ENVIRONMENT": environment},
        }
        for filename, documents in (
            ("application.yaml", [deployment]),
            ("migration.yaml", [migration]),
            ("prerequisites.yaml", [config_map]),
        ):
            (directory / filename).write_text(
                yaml.safe_dump_all(documents, sort_keys=False), encoding="utf-8")
        for filename in ("bootstrap.yaml", "ci-prerequisites.yaml", "ci-application.yaml"):
            (directory / filename).write_text("# offline CI derivation\n", encoding="utf-8")

        bundle_hash = receipts.bundle_hash(directory)
        config_hash = receipts.config_hash(directory)
        self._write_json(directory / "bundle.json", {
            "schemaVersion": 2,
            "fixture": False,
            "environment": environment,
            "namespace": f"tcg-store-{environment}",
            "bundleHash": bundle_hash,
            "configHash": config_hash,
        })
        return receipts.verify_bundle(directory)

    def _seed_accepted_previous(self) -> tuple[dict[str, object], Path]:
        previous_artifact = self._artifact(122, "e")
        bundle = self.state / "staging" / "bundles" / "previous-release"
        verified = self._bundle(bundle, previous_artifact, origin="https://old.frontend.invalid")
        inventory = receipts.bundle_inventory(bundle, "staging")
        now = datetime.now(timezone.utc).replace(microsecond=0)
        started = now - timedelta(seconds=700)
        completed = now
        receipt = {
            "schemaVersion": 2,
            "releaseId": "previous-release",
            "environment": "staging",
            "sourceCommit": COMMIT,
            "buildRunId": 122,
            "deployRunId": 88,
            "image": {key: previous_artifact[key] for key in
                      ("repository", "indexDigest", "platformDigests", "verification")},
            "bundleHash": verified["bundleHash"],
            "configHash": verified["configHash"],
            "previousReleaseId": None,
            "migrationCompatibility": "backward-compatible",
            "phase": "staging",
            "status": "accepted",
            "timestamps": {
                "startedAt": started.isoformat().replace("+00:00", "Z"),
                "completedAt": completed.isoformat().replace("+00:00", "Z"),
            },
            "evidence": {
                "bundleSchemaVersion": 2,
                "bundleHashAlgorithm": "sha256-filename-nul-bytes-v1",
                "configHashAlgorithm": "sha256-canonical-configmap-json-v1",
                "migrationResult": "passed",
                "rolloutResult": "passed",
                "smokeResult": "passed",
                "observationWindowSeconds": 600,
                "inventory": inventory,
                "inventoryHash": receipts.inventory_hash(inventory),
            },
        }
        receipt_path = self.state / "staging" / "previous-release.json"
        receipts.write_receipt(receipt_path, receipt, bundle_dir=bundle)
        self._write_json(self.state / "staging" / "last-known-good.json", {
            "receiptPath": str(receipt_path),
            "bundlePath": str(bundle),
            "bundleHash": verified["bundleHash"],
        })
        return receipt, bundle

    def _args(self, release_id: str = "candidate-release") -> argparse.Namespace:
        return orchestrate.build_parser().parse_args([
            "--environment", "staging",
            "--repository", "example/tcg-store-backend",
            "--build-run-id", "123",
            "--release-id", release_id,
            "--config", str(self.config),
            "--context", "offline-kubernetes-context",
            "--readiness-file", str(self.readiness),
            "--observation-file", str(self.root / "observation.json"),
            "--state-root", str(self.state),
            "--progress-root", str(self.state),
            "--base-url", "https://api.staging.invalid",
            "--migration-compatibility", "backward-compatible",
        ])

    def _runner(self, command: list[str], **_kwargs: object) -> subprocess.CompletedProcess[str]:
        self.calls.append(command)
        if Path(command[0]).name.lower() == "gh":
            output = {"protection_rules": [{"type": "required_reviewers", "reviewers": [{"id": 7}]}]}
            return subprocess.CompletedProcess(command, 0, json.dumps(output), "")
        if (Path(command[0]) == Path(sys.executable)
                and Path(command[1]).name.lower() == "render.py"):
            if command[2] == "render":
                config = json.loads(Path(command[command.index("--config") + 1]).read_text(encoding="utf-8"))
                output = Path(command[command.index("--output-dir") + 1]) / "staging"
                self._bundle(output, self.artifact, origin=config["frontend_origin"])
                return subprocess.CompletedProcess(command, 0, "", "")
            bundle = Path(command[command.index("--manifest-directory") + 1])
            verified = receipts.verify_bundle(bundle)
            result = {**verified, "applicationDeploymentName": "tcg-store-backend"}
            return subprocess.CompletedProcess(command, 0, json.dumps(result), "")
        if Path(command[0]).name.lower() == "pwsh":
            progress = Path(command[command.index("-ProgressFile") + 1])
            now = datetime.now(timezone.utc).replace(microsecond=0)
            if self.deploy_mode == "migration-fail":
                payload = {"phase": "migration", "migrationCompleted": False}
                code = 1
            elif self.deploy_mode == "rollout-fail":
                payload = {"phase": "rollout", "migrationCompleted": True}
                code = 1
            else:
                rollout_done = now - timedelta(seconds=601)
                payload = {
                    "phase": "rollout-complete", "migrationCompleted": True,
                    "startedAt": (rollout_done - timedelta(seconds=30)).isoformat().replace("+00:00", "Z"),
                    "rolloutCompletedAt": rollout_done.isoformat().replace("+00:00", "Z"),
                }
                code = 0
            self._write_json(progress, payload)
            return subprocess.CompletedProcess(command, code, "", "offline deploy result")
        if command[0] == "kubectl":
            return subprocess.CompletedProcess(command, 0, "", "")
        self.fail(f"unexpected external command in offline test: {command[0]}")
        raise AssertionError("unreachable")

    def _smoke(self, _args: argparse.Namespace, _runner: object, _directory: Path,
               frontend_origin: str) -> dict[str, str]:
        self.smoke_origins.append(frontend_origin)
        if self.candidate_smoke_fails and frontend_origin == "https://new.frontend.invalid":
            raise orchestrate.ReleaseError("mock read-only smoke failure")
        return {"status": "passed"}

    def _observe(self, path: Path, *, environment: str, release_id: str,
                 image_digest: str, rollout_completed_at: str, lease: _Lease,
                 **_kwargs: object) -> dict[str, object]:
        lease.assert_held()
        observed = datetime.now(timezone.utc).replace(microsecond=0)
        rollout_done = datetime.fromisoformat(rollout_completed_at.replace("Z", "+00:00"))
        window_started = max(rollout_done, observed - timedelta(seconds=600))
        evidence: dict[str, object] = {
            "status": "healthy", "noData": False, "environment": environment,
            "releaseId": release_id, "imageDigest": image_digest,
            "rolloutCompletedAt": rollout_completed_at,
            "windowStartedAt": window_started.isoformat().replace("+00:00", "Z"),
            "observedAt": observed.isoformat().replace("+00:00", "Z"),
            "sampleCount": 12, "observationWindowSeconds": 600,
        }
        self._write_json(path, evidence)
        return orchestrate._observation(path, environment=environment, release_id=release_id,
            image_digest=image_digest, rollout_completed_at=rollout_completed_at, now=observed)

    def _run(self, *, smoke_fails: bool = False, seed_previous: bool = False,
             release_id: str = "candidate-release") -> dict[str, object]:
        if seed_previous:
            self._seed_accepted_previous()
        self.candidate_smoke_fails = smoke_fails
        old_run_id = os.environ.get("GITHUB_RUN_ID")
        with patch.dict(os.environ, {"GITHUB_RUN_ID": "456", "GH_TOKEN": "offline-only-token"}), \
             patch.object(orchestrate, "_smoke", side_effect=self._smoke), \
             patch.object(orchestrate, "_wait_for_observation", side_effect=self._observe):
            try:
                return orchestrate.orchestrate(self._args(release_id), runner=self._runner, shared=self.shared)
            finally:
                if old_run_id is None:
                    os.environ.pop("GITHUB_RUN_ID", None)

    def test_success_persists_real_accepted_receipt_bundle_pointer_and_inventory_hash(self) -> None:
        receipt = self._run()
        environment_state = self.state / "staging"
        receipt_path = environment_state / "candidate-release.json"
        bundle_path = environment_state / "bundles" / "candidate-release"
        persisted = receipts.load_receipt(receipt_path, bundle_dir=bundle_path)
        pointer = json.loads((environment_state / "last-known-good.json").read_text(encoding="utf-8"))

        self.assertEqual(receipt["status"], "accepted")
        self.assertEqual(persisted["image"]["repository"], REPOSITORY)
        self.assertEqual(persisted["image"]["indexDigest"], self.artifact["indexDigest"])
        self.assertEqual(persisted["bundleHash"], receipts.bundle_hash(bundle_path))
        self.assertEqual(pointer["receiptPath"], str(receipt_path))
        self.assertEqual(pointer["bundlePath"], str(bundle_path))
        inventory = receipts.bundle_inventory(bundle_path, "staging")
        self.assertEqual(persisted["evidence"]["inventory"], inventory)
        self.assertEqual(persisted["evidence"]["inventoryHash"], receipts.inventory_hash(inventory))
        started = datetime.fromisoformat(persisted["timestamps"]["startedAt"].replace("Z", "+00:00"))
        completed = datetime.fromisoformat(persisted["timestamps"]["completedAt"].replace("Z", "+00:00"))
        self.assertGreaterEqual((completed - started).total_seconds(), 600)
        self.assertEqual(persisted["evidence"]["observationWindowSeconds"], 600)

    def test_migration_failure_never_restores_prior_api_or_replays_old_migration(self) -> None:
        self._seed_accepted_previous()
        self.deploy_mode = "migration-fail"
        with patch.dict(os.environ, {"GITHUB_RUN_ID": "456", "GH_TOKEN": "offline-only-token"}):
            with self.assertRaises(orchestrate.ReleaseError):
                orchestrate.orchestrate(self._args(), runner=self._runner, shared=self.shared)

        self.assertEqual(len([call for call in self.calls if Path(call[0]).name.lower() == "pwsh"]), 1)
        self.assertFalse(any(call[0] == "kubectl" and "rollout" in call for call in self.calls))
        self.assertFalse(any(call[0] == "kubectl" and "apply" in call
                             and not any(argument.startswith("--dry-run") for argument in call)
                             and any(Path(argument).name == "ci-application.yaml" for argument in call)
                             for call in self.calls))
        failure_path = self.state / "staging" / "candidate-release-failed.json"
        failed = receipts.load_receipt(failure_path)
        self.assertEqual(failed["status"], "recovery-required")
        self.assertEqual(failed["phase"], "migration")
        self.assertEqual(failed["evidence"]["migrationResult"], "failed")
        self.assertTrue((self.state / "staging" / "last-known-good.json").is_file())

    def test_rollout_failure_restores_verified_prior_bundle_without_running_old_migration(self) -> None:
        self._seed_accepted_previous()
        self.deploy_mode = "rollout-fail"
        with patch.dict(os.environ, {"GITHUB_RUN_ID": "456", "GH_TOKEN": "offline-only-token"}), \
             patch.object(orchestrate, "_smoke", side_effect=self._smoke):
            with self.assertRaises(orchestrate.ReleaseError):
                orchestrate.orchestrate(self._args(), runner=self._runner, shared=self.shared)

        previous_bundle = self.state / "staging" / "bundles" / "previous-release"
        self.assertTrue(any(call[0] == "kubectl" and "apply" in call
                            and str(previous_bundle / "ci-application.yaml") in call for call in self.calls))
        self.assertFalse(any(call[0] == "pwsh" for call in self.calls if
                             "previous-release" in " ".join(call)))
        self.assertEqual(self.smoke_origins, ["https://old.frontend.invalid"])
        failure = receipts.load_receipt(self.state / "staging" / "candidate-release-failed.json")
        self.assertEqual(failure["status"], "rolled-back")
        self.assertEqual(failure["phase"], "rollout")
        pointer = json.loads((self.state / "staging" / "last-known-good.json").read_text(encoding="utf-8"))
        self.assertEqual(pointer["receiptPath"], str(self.state / "staging" / "previous-release.json"))

    def test_smoke_failure_restores_prior_bundle_and_keeps_pipeline_failed(self) -> None:
        self._seed_accepted_previous()
        with self.assertRaises(orchestrate.ReleaseError):
            self._run(smoke_fails=True)

        self.assertEqual(self.smoke_origins,
                         ["https://new.frontend.invalid", "https://old.frontend.invalid"])
        previous_bundle = self.state / "staging" / "bundles" / "previous-release"
        self.assertTrue(any(call[0] == "kubectl" and "apply" in call
                            and str(previous_bundle / "ci-application.yaml") in call for call in self.calls))
        failure = receipts.load_receipt(self.state / "staging" / "candidate-release-failed.json")
        self.assertEqual(failure["status"], "rolled-back")
        self.assertEqual(failure["phase"], "smoke")
        self.assertEqual(failure["evidence"]["smokeResult"], "failed")

    def test_no_data_holds_release_without_automatic_rollback(self) -> None:
        self._seed_accepted_previous()

        def no_data(path: Path, *, environment: str, release_id: str,
                    image_digest: str, rollout_completed_at: str, lease: _Lease,
                    **_kwargs: object) -> None:
            lease.assert_held()
            now = datetime.now(timezone.utc).replace(microsecond=0)
            self._write_json(path, {
                "status": "unknown", "noData": True, "environment": environment,
                "releaseId": release_id, "imageDigest": image_digest,
                "rolloutCompletedAt": rollout_completed_at,
                "windowStartedAt": (now - timedelta(seconds=600)).isoformat().replace("+00:00", "Z"),
                "observedAt": now.isoformat().replace("+00:00", "Z"),
                "sampleCount": 0, "observationWindowSeconds": 600,
            })
            orchestrate._observation(path, environment=environment, release_id=release_id,
                image_digest=image_digest, rollout_completed_at=rollout_completed_at, now=now)

        with patch.dict(os.environ, {"GITHUB_RUN_ID": "456", "GH_TOKEN": "offline-only-token"}), \
             patch.object(orchestrate, "_smoke", side_effect=self._smoke), \
             patch.object(orchestrate, "_wait_for_observation", side_effect=no_data):
            with self.assertRaises(orchestrate.ReleaseError):
                orchestrate.orchestrate(self._args(), runner=self._runner, shared=self.shared)

        self.assertFalse(any(call[0] == "kubectl" and "rollout" in call for call in self.calls))
        self.assertEqual(self.smoke_origins, ["https://new.frontend.invalid"])
        failure = receipts.load_receipt(self.state / "staging" / "candidate-release-failed.json")
        self.assertEqual(failure["status"], "recovery-required")
        self.assertEqual(failure["phase"], "observation")
        self.assertFalse((self.state / "staging" / "candidate-release.json").exists())
        pointer = json.loads((self.state / "staging" / "last-known-good.json").read_text(encoding="utf-8"))
        self.assertEqual(pointer["receiptPath"], str(self.state / "staging" / "previous-release.json"))

    def test_first_deploy_failure_without_previous_release_requires_recovery(self) -> None:
        with self.assertRaises(orchestrate.ReleaseError):
            self._run(smoke_fails=True)

        failure = receipts.load_receipt(self.state / "staging" / "candidate-release-failed.json")
        self.assertEqual(failure["status"], "recovery-required")
        self.assertEqual(failure["phase"], "smoke")
        self.assertFalse((self.state / "staging" / "last-known-good.json").exists())
        self.assertFalse(any(call[0] == "kubectl" and "rollout" in call for call in self.calls))

    def test_failed_bundle_remains_verifiable_after_orchestrator_temporary_directory_exits(self) -> None:
        self.deploy_mode = "migration-fail"
        with self.assertRaises(orchestrate.ReleaseError):
            self._run()
        retained = self.state / "staging" / "releases" / "candidate-release" / "candidate-bundle"
        failure_path = self.state / "staging" / "candidate-release-failed.json"
        self.assertTrue(retained.is_dir(), "failure evidence must retain the exact non-secret candidate bundle")
        failed = receipts.load_receipt(failure_path, bundle_dir=retained)
        self.assertEqual(failed["bundleHash"], receipts.bundle_hash(retained))
        self.assertEqual({"application.yaml", "migration.yaml", "prerequisites.yaml", "bootstrap.yaml",
                          "ci-prerequisites.yaml", "ci-application.yaml", "bundle.json"},
                         {path.name for path in retained.iterdir()})

    def test_accepted_release_id_cannot_be_reused_for_another_cluster_write(self) -> None:
        self._run()
        self.calls.clear()
        with self.assertRaisesRegex(orchestrate.ReleaseError, "immutable"):
            self._run()
        self.assertFalse(any(call[0] in {"kubectl", "pwsh"} for call in self.calls))

    def test_production_promotes_exact_staging_digest_and_retains_run_approval_linkage(self) -> None:
        stage = self._run()
        artifact_root = self.root / "downloaded-staging-artifact"
        artifact_root.mkdir()
        staged_receipt = artifact_root / "candidate-release.json"
        shutil.copyfile(self.state / "staging" / "candidate-release.json", staged_receipt)
        shutil.copytree(self.state / "staging" / "bundles" / "candidate-release", artifact_root / "bundle")
        readiness = json.loads(self.readiness.read_text())
        readiness.update(environment="production", namespace="tcg-store-production")
        self._write_json(self.readiness, readiness)
        args = self._args()
        args.environment = "production"
        args.base_url = "https://api.production.invalid"
        args.staging_run_id = "456"
        args.staging_receipt_id = "candidate-release"
        args.staging_receipt = staged_receipt
        args.staging_bundle = artifact_root / "bundle"

        def production_runner(command, **kwargs):
            if command[0] == "gh" and command[-1].endswith("/actions/runs/456"):
                run = {"id": 456, "path": ".github/workflows/deploy-staging.yml",
                       "event": "workflow_dispatch", "head_branch": "main", "head_sha": "f" * 40,
                       "status": "completed", "conclusion": "success",
                       "repository": {"full_name": args.repository},
                       "head_repository": {"full_name": args.repository},
                       "updated_at": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")}
                return subprocess.CompletedProcess(command, 0, json.dumps(run), "")
            if Path(command[0]) == Path(sys.executable) and Path(command[1]).name == "render.py" and command[2] == "render":
                output = Path(command[command.index("--output-dir") + 1]) / "production"
                self._bundle(output, self.artifact, origin="https://production.frontend.invalid", environment="production")
                return subprocess.CompletedProcess(command, 0, "", "")
            return self._runner(command, **kwargs)

        with patch.dict(os.environ, {"GITHUB_RUN_ID": "789", "GITHUB_SERVER_URL": "https://github.com",
                                    "GITHUB_REPOSITORY": args.repository, "GH_TOKEN": "offline-only-token"}), \
             patch.object(orchestrate, "_smoke", side_effect=self._smoke), \
             patch.object(orchestrate, "_wait_for_observation", side_effect=self._observe):
            production = orchestrate.orchestrate(args, runner=production_runner, shared=self.shared)
        retained = self.state / "production" / "bundles" / "candidate-release"
        validated = receipts.load_receipt(self.state / "production" / "candidate-release.json", bundle_dir=retained)
        self.assertEqual(production["status"], "accepted")
        self.assertEqual(validated["image"], stage["image"])
        self.assertEqual(validated["deployRunId"], 789)
        evidence = json.loads((self.state / "production" / "candidate-release-promotion-evidence.json").read_text())
        self.assertEqual(evidence["stagingWorkflowRunId"], 456)
        self.assertEqual(evidence["stagingDeployRunId"], 456)
        self.assertEqual(evidence["stagingBuildRunId"], stage["buildRunId"])
        self.assertEqual(evidence["approvalReference"], f"https://github.com/{args.repository}/actions/runs/789")


if __name__ == "__main__":
    unittest.main()
