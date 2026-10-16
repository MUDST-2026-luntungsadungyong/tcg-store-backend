#!/usr/bin/env python3
"""Fail-closed staging deploy and production promotion orchestration.

All cluster mutations are delegated to the Component C deploy adapter. The
adapter's progress receipt is used to choose recovery; migrations are never
reversed automatically.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Callable, Mapping, Sequence
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[2]
if __package__ in {None, ""} and str(ROOT) not in sys.path:
    # Direct `python scripts/release/orchestrate.py` execution otherwise puts
    # only scripts/release on sys.path, breaking imports of the shared core.
    sys.path.insert(0, str(ROOT))
REQUIRED_SECRET_KEYS = {
    "neon-jdbc-url", "neon-db-username", "neon-db-password",
    "neon-migration-jdbc-url", "neon-migration-db-username", "neon-migration-db-password",
    "app-jwt-secret", "object-storage-access-key", "object-storage-secret-key",
}
SAFE_ID = re.compile(r"^[a-z0-9](?:[a-z0-9-]{0,38}[a-z0-9])?$")
SHA = re.compile(r"^[0-9a-f]{64}$")
RETAINED_BUNDLE_FILES = ("application.yaml", "migration.yaml", "prerequisites.yaml", "bootstrap.yaml",
                         "ci-prerequisites.yaml", "ci-application.yaml", "bundle.json")


class ReleaseError(RuntimeError):
    """A release gate failed; caller must leave the workflow failed."""


def _json_file(path: Path, purpose: str) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise ReleaseError(f"{purpose} is missing or invalid JSON") from exc
    if not isinstance(value, dict):
        raise ReleaseError(f"{purpose} must be a JSON object")
    return value


def validate_secret_readiness(path: Path, environment: str, now: datetime | None = None) -> dict[str, Any]:
    """Validate the platform-operator contract without ever reading Secret data."""
    receipt = _json_file(path, "Secret readiness receipt")
    namespace = f"tcg-store-{environment}"
    if receipt.get("schemaVersion") != 1 or receipt.get("issuer") != "platform-operator":
        raise ReleaseError("Secret readiness receipt has an unsupported schema or issuer")
    if receipt.get("environment") != environment or receipt.get("namespace") != namespace:
        raise ReleaseError("Secret readiness receipt environment or namespace does not match")
    if receipt.get("secretName") != "tcg-store-backend-secrets" or receipt.get("ready") is not True:
        raise ReleaseError("Secret readiness receipt does not attest the required Secret as ready")
    names = receipt.get("keyNames")
    if not isinstance(names, list) or len(names) != len(REQUIRED_SECRET_KEYS) or set(names) != REQUIRED_SECRET_KEYS:
        raise ReleaseError("Secret readiness receipt keyNames do not match the nine-key contract")
    stamp = receipt.get("checkedAt")
    if not isinstance(stamp, str) or not stamp.endswith("Z"):
        raise ReleaseError("Secret readiness checkedAt must be an ISO UTC timestamp")
    try:
        checked = datetime.fromisoformat(stamp[:-1] + "+00:00")
    except ValueError as exc:
        raise ReleaseError("Secret readiness checkedAt must be an ISO UTC timestamp") from exc
    current = now or datetime.now(timezone.utc)
    age = current - checked.astimezone(timezone.utc)
    if age < timedelta(0) or age > timedelta(minutes=15):
        raise ReleaseError("Secret readiness receipt is stale or future-dated (maximum age 15 minutes)")
    return receipt


def _run(runner: Callable[..., Any], command: Sequence[str], *, cwd: Path = ROOT,
         env: Mapping[str, str] | None = None, capture: bool = True, check: bool = True) -> Any:
    child_env = dict(os.environ if env is None else env)
    if env is None:
        child_env.pop("GH_TOKEN", None)
        child_env.pop("GITHUB_TOKEN", None)
    if Path(str(command[0])).name.lower() == "gh" and "GH_TOKEN" not in child_env:
        token = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")
        if token:
            child_env["GH_TOKEN"] = token
    try:
        result = runner(list(map(str, command)), cwd=str(cwd), env=child_env,
                        text=True, stdout=subprocess.PIPE if capture else None,
                        stderr=subprocess.PIPE if capture else None, check=False)
    except OSError as exc:
        raise ReleaseError(f"required release command is unavailable: {Path(command[0]).name}") from exc
    if check and getattr(result, "returncode", 1) != 0:
        raise ReleaseError(f"release command failed: {Path(command[0]).name} {Path(command[1]).name if len(command)>1 else ''}".strip())
    return result


def _credential_scoped_runner(runner: Callable[..., Any]) -> Callable[..., Any]:
    """Expose GitHub credentials only to gh while preserving protected smoke inputs."""
    def scoped(command: Sequence[str], **kwargs: Any) -> Any:
        child_env = dict(os.environ if kwargs.get("env") is None else kwargs["env"])
        if Path(str(command[0])).name.lower() == "gh":
            token = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")
            if token:
                child_env.setdefault("GH_TOKEN", token)
        else:
            child_env.pop("GH_TOKEN", None)
            child_env.pop("GITHUB_TOKEN", None)
        kwargs["env"] = child_env
        return runner(command, **kwargs)
    return scoped


def _atomic_json(path: Path, value: Mapping[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + ".tmp")
    temporary.write_text(json.dumps(value, sort_keys=True, indent=2) + "\n", encoding="utf-8")
    temporary.replace(path)


def _sha_bundle(bundle: Path) -> str:
    """Use C's stable content identity while requiring all v2 CI views."""
    digest = hashlib.sha256()
    for name in ("application.yaml", "migration.yaml", "prerequisites.yaml"):
        path = bundle / name
        if not path.is_file() or path.is_symlink():
            raise ReleaseError(f"rendered bundle is missing a regular {name}")
        raw = path.read_bytes()
        digest.update(name.encode("utf-8") + b"\0" + raw)
    for name in ("bootstrap.yaml", "ci-prerequisites.yaml", "ci-application.yaml"):
        path = bundle / name
        if not path.is_file() or path.is_symlink():
            raise ReleaseError(f"rendered v2 CI bundle is missing a regular {name}")
    return digest.hexdigest()


def _copy_bundle_atomically(source: Path, target: Path) -> Path:
    """Copy only the whitelisted non-secret C v2 bundle files, then publish by rename."""
    target.parent.mkdir(parents=True, exist_ok=True)
    temporary = target.with_name(target.name + ".tmp")
    if target.exists() or temporary.exists():
        raise ReleaseError("retained bundle destination already exists; release IDs are immutable")
    for name in RETAINED_BUNDLE_FILES:
        origin = source / name
        if not origin.is_file() or origin.is_symlink():
            raise ReleaseError(f"validated candidate bundle is missing regular file {name}")
    temporary.mkdir()
    for name in RETAINED_BUNDLE_FILES:
        shutil.copyfile(source / name, temporary / name)
    if _sha_bundle(temporary) != _sha_bundle(source):
        raise ReleaseError("persisted candidate bundle content differs from the validated render")
    temporary.rename(target)
    return target


def _persist_candidate_bundle(source: Path, release_state: Path) -> Path:
    """Retain the validated, non-secret seven-file C v2 bundle before cluster calls."""
    release_state.mkdir(parents=True, exist_ok=True)
    return _copy_bundle_atomically(source, release_state / "candidate-bundle")


def _bundle_inventory(bundle: Path, namespace: str, receipts_api: Any) -> list[dict[str, str]]:
    try:
        inventory = receipts_api.bundle_inventory(bundle, namespace.removeprefix("tcg-store-"))
    except Exception as exc:
        raise ReleaseError("cannot derive exact namespaced inventory from the validated bundle") from exc
    if any(item.get("namespace") != namespace for item in inventory):
        raise ReleaseError("bundle contains a resource outside the release namespace")
    return inventory


def _restore_previous(runner: Callable[..., Any], args: argparse.Namespace, current_bundle: Path,
                      previous_receipt: Mapping[str, Any], previous_bundle: Path,
                      receipts_api: Any, workdir: Path, lease: Any) -> bool:
    """Restore only the verified prior C bundle, then prune its exact inventory delta."""
    try:
        validation_result = _run(runner, [sys.executable, str(ROOT / "scripts/kubernetes/render.py"),
            "validate-bundle", "--manifest-directory", str(previous_bundle),
            "--environment", args.environment, "--release-id", f"rb-{args.release_id}"[:40].rstrip("-"),
            "--format", "json"])
        validation = json.loads(validation_result.stdout)
        namespace = f"tcg-store-{args.environment}"
        lease.assert_held()
        _run(runner, ["kubectl", "--context", args.context, "--namespace", namespace,
                      "apply", "-f", str(previous_bundle / "ci-prerequisites.yaml")])
        lease.assert_held()
        _run(runner, ["kubectl", "--context", args.context, "--namespace", namespace,
                      "apply", "-f", str(previous_bundle / "ci-application.yaml")])
        lease.assert_held()
        _run(runner, ["kubectl", "--context", args.context, "--namespace", namespace,
                      "rollout", "status", f"deployment/{validation['applicationDeploymentName']}",
                      "--timeout=5m"])
        restored_smoke = _smoke(args, runner, workdir, _bundle_frontend_origin(previous_bundle))
        if restored_smoke.get("status") != "passed":
            return False
        old_inventory = previous_receipt.get("evidence", {}).get("inventory")
        if old_inventory is None:
            return False
        current_inventory = _bundle_inventory(current_bundle, namespace, receipts_api)
        delta = receipts_api.inventory_delta(old_inventory, current_inventory)
        try:
            import yaml
            current_docs = []
            for filename in ("ci-application.yaml", "migration.yaml", "ci-prerequisites.yaml"):
                current_docs.extend(yaml.safe_load_all((current_bundle / filename).read_text(encoding="utf-8")))
        except (ImportError, OSError, ValueError):
            return False
        docs_by_identity = {(doc.get("apiVersion"), doc.get("kind"), doc.get("metadata", {}).get("namespace"),
                             doc.get("metadata", {}).get("name")): doc for doc in current_docs if isinstance(doc, dict)}
        for index, entry in enumerate(delta):
            identity = (entry["apiVersion"], entry["kind"], entry["namespace"], entry["name"])
            document = docs_by_identity.get(identity)
            if document is None or entry["namespace"] != namespace:
                return False
            if document.get("metadata", {}).get("labels", {}).get("app.kubernetes.io/managed-by") != "tcg-release":
                return False
            live_result = _run(runner, ["kubectl", "--context", args.context, "--namespace", namespace,
                "get", entry["kind"], entry["name"], "-o", "json"])
            try:
                live = json.loads(live_result.stdout)
            except (TypeError, json.JSONDecodeError):
                return False
            live_metadata = live.get("metadata", {}) if isinstance(live, dict) else {}
            live_identity = (live.get("apiVersion"), live.get("kind"), live_metadata.get("namespace"), live_metadata.get("name"))
            expected_identity = (entry["apiVersion"], entry["kind"], entry["namespace"], entry["name"])
            try:
                receipts_api.assert_release_owned_live_resource(live, entry)
            except Exception:
                return False
            if live_identity != expected_identity:
                return False
            manifest = workdir / f"prune-owned-{index}.yaml"
            manifest.write_text(yaml.safe_dump(document, sort_keys=False, explicit_start=True), encoding="utf-8")
            lease.assert_held()
            _run(runner, ["kubectl", "--context", args.context, "--namespace", namespace,
                          "delete", "-f", str(manifest), "--ignore-not-found=true"])
        return True
    except (ReleaseError, KeyError, ValueError):
        return False


def _safe_progress(path: Path) -> dict[str, Any]:
    data = _json_file(path, "deploy progress receipt")
    phase = data.get("phase")
    if phase not in {"prerequisites", "migration", "rollout", "rollout-complete"}:
        raise ReleaseError("deploy helper returned an unknown phase; manual recovery is required")
    if not isinstance(data.get("migrationCompleted"), bool):
        raise ReleaseError("deploy helper progress receipt lacks migrationCompleted")
    if phase == "rollout-complete":
        for key in ("startedAt", "rolloutCompletedAt"):
            value = data.get(key)
            if not isinstance(value, str) or not value.endswith("Z"):
                raise ReleaseError(f"deploy helper progress receipt lacks actual {key}")
            try:
                datetime.fromisoformat(value[:-1] + "+00:00")
            except ValueError as exc:
                raise ReleaseError(f"deploy helper progress receipt has invalid {key}") from exc
        if data["rolloutCompletedAt"] < data["startedAt"]:
            raise ReleaseError("deploy helper rollout completion precedes deployment start")
    return data


def _load_shared_apis() -> tuple[Any, Any, Any, Any]:
    try:
        from scripts.release import verify_artifact, receipts, locking, recovery
    except ImportError as exc:
        raise ReleaseError("shared release verification/receipt/locking/recovery modules are unavailable") from exc
    return verify_artifact, receipts, locking, recovery


def _call_receipt_write(api: Any, receipt: dict[str, Any], path: Path,
                        bundle: Path | None = None) -> None:
    # The receipt module owns canonical validation and persistence.
    try:
        api.validate_receipt(receipt, bundle_dir=bundle)
        api.write_receipt(path, receipt, bundle_dir=bundle)
    except TypeError as exc:
        raise ReleaseError("release receipt module API does not match the orchestration contract") from exc


def _load_previous(state_root: Path, environment: str, receipts_api: Any, verify_api: Any,
                   repository: str, runner: Callable[..., Any]) -> tuple[dict[str, Any], Path] | None:
    pointer = state_root / environment / "last-known-good.json"
    if not pointer.exists():
        return None
    metadata = _json_file(pointer, "last-known-good state")
    root = state_root.resolve()
    receipt_path = Path(str(metadata.get("receiptPath", ""))).resolve()
    bundle = Path(str(metadata.get("bundlePath", ""))).resolve()
    if root not in receipt_path.parents or root not in bundle.parents:
        raise ReleaseError("last-known-good paths escape the trusted release state root")
    if Path(str(metadata.get("receiptPath", ""))).is_symlink() or Path(str(metadata.get("bundlePath", ""))).is_symlink():
        raise ReleaseError("last-known-good state cannot reference symlinks")
    if not receipt_path.is_file() or not bundle.is_dir():
        raise ReleaseError("last-known-good state references missing files; recovery is required")
    receipt = receipts_api.load_receipt(receipt_path, bundle_dir=bundle)
    receipts_api.validate_receipt(receipt, bundle_dir=bundle)
    if receipt.get("status") != "accepted" or receipt.get("environment") != environment:
        raise ReleaseError("last-known-good receipt is not an accepted release for this environment")
    if receipt.get("evidence", {}).get("bundleSchemaVersion") != 2:
        raise ReleaseError("legacy bundle ownership is unknown; automatic restoration and pruning are disabled")
    actual_hash = _sha_bundle(bundle)
    expected = metadata.get("bundleHash")
    if not isinstance(expected, str) or actual_hash != expected or actual_hash != receipt.get("bundleHash"):
        raise ReleaseError("last-known-good bundle hash does not match its accepted receipt")
    try:
        verify_api.reverify_image_identity(receipt.get("image", {}), receipt.get("sourceCommit"), runner=runner)
        bundle_validation = receipts_api.verify_bundle(bundle)
        c_validation = json.loads(_run(runner, [sys.executable, str(ROOT / "scripts/kubernetes/render.py"),
            "validate-bundle", "--manifest-directory", str(bundle), "--environment", environment,
            "--release-id", receipt["releaseId"], "--format", "json"]).stdout)
    except Exception as exc:
        raise ReleaseError("last-known-good image, receipt, or bundle could not be independently reverified") from exc
    if (bundle_validation.get("bundleHash") != actual_hash
            or c_validation.get("bundleHash") != actual_hash
            or c_validation.get("schemaVersion") != 2):
        raise ReleaseError("last-known-good image identity or rendered bundle does not match its verified receipt")
    if bundle_validation.get("schemaVersion") != 2:
        raise ReleaseError("last-known-good bundle is not a verified v2 CI bundle")
    return receipt, bundle


def _check_environment_protection(runner: Callable[..., Any], repository: str, environment: str) -> None:
    """Require GitHub's live environment configuration to include reviewers."""
    token = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")
    if not token:
        raise ReleaseError("GitHub token is unavailable for protected-environment verification")
    cmd = ["gh", "api", f"repos/{repository}/environments/{environment}"]
    result = _run(runner, cmd, env={**os.environ, "GH_TOKEN": token})
    try:
        config = json.loads(result.stdout)
    except (TypeError, json.JSONDecodeError) as exc:
        raise ReleaseError("GitHub environment protection response is invalid") from exc
    rules = config.get("protection_rules") or []
    reviewers = [rule for rule in rules if rule.get("type") == "required_reviewers" and rule.get("reviewers")]
    if not reviewers:
        raise ReleaseError(f"GitHub Environment '{environment}' has no required reviewer protection rule")


def _trusted_staging_receipt(runner: Callable[..., Any], repository: str, run_id: str,
                             staging_receipt_path: Path, staging_bundle: Path, release_id: str,
                             current: Mapping[str, Any], receipts_api: Any) -> dict[str, Any]:
    result = _run(runner, ["gh", "api", f"repos/{repository}/actions/runs/{run_id}"])
    try:
        run = json.loads(result.stdout)
    except (TypeError, json.JSONDecodeError) as exc:
        raise ReleaseError("staging workflow run metadata is invalid") from exc
    if (str(run.get("id")) != run_id or run.get("path") != ".github/workflows/deploy-staging.yml"
            or str((run.get("repository") or {}).get("full_name", "")).lower() != repository.lower()
            or str((run.get("head_repository") or {}).get("full_name", "")).lower() != repository.lower()
            or run.get("event") != "workflow_dispatch" or run.get("head_branch") != "main"
            or run.get("status") != "completed" or run.get("conclusion") != "success"):
        raise ReleaseError("staging receipt must come from a successful deploy-staging workflow_dispatch on main")
    try:
        run_completed = datetime.fromisoformat(str(run.get("updated_at", "")).replace("Z", "+00:00"))
    except ValueError as exc:
        raise ReleaseError("staging workflow run completion timestamp is invalid") from exc
    if (datetime.now(timezone.utc) - run_completed.astimezone(timezone.utc) > timedelta(days=7)
            or run_completed > datetime.now(timezone.utc) + timedelta(minutes=2)):
        raise ReleaseError("staging promotion evidence is stale or future-dated")
    expected_receipt = staging_bundle.parent / f"{release_id}.json"
    if (staging_receipt_path.resolve() != expected_receipt.resolve()
            or not staging_bundle.is_dir() or staging_bundle.is_symlink()):
        raise ReleaseError("the exact-run staging artifact is missing its retained bundle directory")
    retained_names = {"application.yaml", "migration.yaml", "prerequisites.yaml", "bootstrap.yaml",
                      "ci-prerequisites.yaml", "ci-application.yaml", "bundle.json"}
    try:
        files = list(staging_bundle.iterdir())
    except OSError as exc:
        raise ReleaseError("the exact-run staging bundle cannot be read") from exc
    if (len(files) != len(retained_names) or {item.name for item in files} != retained_names
            or any(item.is_symlink() or not item.is_file() for item in files)
            or staging_receipt_path.is_symlink() or not staging_receipt_path.is_file()):
        raise ReleaseError("staging artifact must contain the receipt and exactly seven regular non-secret v2 bundle files")
    receipt = receipts_api.load_receipt(staging_receipt_path, bundle_dir=staging_bundle)
    receipts_api.validate_receipt(receipt, bundle_dir=staging_bundle)
    receipts_api.verify_promotion(receipt, current, bundle_dir=staging_bundle)
    try:
        validation = json.loads(_run(runner, [sys.executable, str(ROOT / "scripts/kubernetes/render.py"),
            "validate-bundle", "--manifest-directory", str(staging_bundle), "--environment", "staging",
            "--release-id", release_id, "--format", "json"]).stdout)
    except Exception as exc:
        raise ReleaseError("the exact-run staging C bundle did not validate") from exc
    if validation.get("schemaVersion") != 2 or validation.get("bundleHash") != receipt.get("bundleHash"):
        raise ReleaseError("staging receipt and retained C v2 bundle do not match")
    if receipt.get("releaseId") != release_id or receipt.get("environment") != "staging":
        raise ReleaseError("staging receipt identity does not match the production candidate")
    if receipt.get("deployRunId") != int(run_id):
        raise ReleaseError("staging receipt deployRunId is not bound to the exact successful staging run")
    return receipt


def _rollback_allowed(plan: Any, *, previous_verified: bool, migration_completed: bool,
                      compatibility: str) -> bool:
    action = plan.get("action") if isinstance(plan, dict) else str(plan)
    return (action == "rollback" and previous_verified and migration_completed
            and compatibility == "backward-compatible")


def _record_failure(receipts_api: Any, env_state: Path, *, args: argparse.Namespace,
                    artifact: Mapping[str, Any], artifact_run: str, image: str, phase: str,
                    bundle: Path, config_hash: str, rolled_back: bool,
                    migration_completed: bool, smoke_result: str = "not-run") -> None:
    now = datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")
    phase_map = {"smoke-or-observation": "smoke", "unknown": "recovery"}
    receipt_phase = phase_map.get(phase, phase)
    if receipt_phase not in {"verification", "render", "admission", "prerequisites", "migration", "rollout", "smoke", "observation", "rollback", "recovery"}:
        receipt_phase = "recovery"
    failed = {"schemaVersion": 2, "releaseId": args.release_id, "environment": args.environment,
              "sourceCommit": artifact["sourceCommit"], "buildRunId": int(artifact_run),
              "deployRunId": int(os.environ["GITHUB_RUN_ID"]),
              "image": {"repository": artifact["repository"], "indexDigest": artifact["indexDigest"],
                        "platformDigests": artifact["platformDigests"], "verification": artifact["verification"]},
              "bundleHash": _sha_bundle(bundle), "configHash": config_hash,
              "previousReleaseId": None, "migrationCompatibility": args.migration_compatibility,
              "phase": receipt_phase, "status": "rolled-back" if rolled_back else "recovery-required",
              "timestamps": {"startedAt": now, "completedAt": now},
              "evidence": {"bundleSchemaVersion": 2,
                           "bundleHashAlgorithm": "sha256-filename-nul-bytes-v1",
                           "configHashAlgorithm": "sha256-canonical-configmap-json-v1",
                           "migrationResult": "passed" if migration_completed else "failed",
                           "rolloutResult": "failed", "smokeResult": smoke_result,
                           "observationWindowSeconds": 0}}
    _call_receipt_write(receipts_api, failed, env_state / f"{args.release_id}-failed.json", bundle)


def _render_config(source: Path, image: str, target: Path) -> None:
    config = _json_file(source, "non-secret release config")
    config["image"] = image
    target.write_text(json.dumps(config, sort_keys=True, separators=(",", ":")), encoding="utf-8")


def _deploy_command(args: argparse.Namespace, bundle: Path, progress: Path,
                    readiness: Path, lease: Any) -> list[str]:
    return ["pwsh", "-NoProfile", "-NonInteractive", "-File", str(ROOT / "scripts/deploy-kubernetes.ps1"),
            "-Python", sys.executable,
            "-Context", args.context, "-Environment", args.environment,
            "-ManifestDirectory", str(bundle), "-ReleaseId", args.release_id,
            "-CIMode", "-SecretReadinessFile", str(readiness), "-ProgressFile", str(progress),
            "-LeaseName", str(lease.lease_name), "-LeaseHolderIdentity", str(lease.holder_identity)]


def _smoke(args: argparse.Namespace, runner: Callable[..., Any], directory: Path,
           frontend_origin: str) -> dict[str, Any]:
    script = ROOT / "scripts/smoke/api_smoke.py"
    if not script.is_file():
        raise ReleaseError("bounded read-only smoke CLI is unavailable")
    report = directory / "smoke-report.json"
    _run(runner, [sys.executable, str(script), "--environment", args.environment,
                  "--base-url", args.base_url, "--frontend-origin", frontend_origin,
                  "--output", str(report)])
    return _json_file(report, "smoke report")


def _bundle_frontend_origin(bundle: Path) -> str:
    """Extract the prior release's public CORS origin from its retained ConfigMap."""
    try:
        import yaml
        for document in yaml.safe_load_all((bundle / "prerequisites.yaml").read_text(encoding="utf-8")):
            if (isinstance(document, dict) and document.get("kind") == "ConfigMap"
                    and document.get("metadata", {}).get("name") == "tcg-store-backend-config"):
                origin = document.get("data", {}).get("APP_CORS_ALLOWED_ORIGINS")
                if isinstance(origin, str) and origin.strip():
                    return origin
    except (ImportError, OSError, ValueError):
        pass
    raise ReleaseError("retained release bundle does not contain a validated frontend CORS origin")


def _observation(path: Path, *, environment: str, release_id: str, image_digest: str,
                 rollout_completed_at: str, now: datetime | None = None) -> dict[str, Any]:
    evidence = _json_file(path, "monitoring observation evidence")
    at = evidence.get("observedAt")
    if evidence.get("status") != "healthy" or evidence.get("noData") is not False:
        raise ReleaseError("monitoring observation is unhealthy or unknown; promotion is held")
    if (evidence.get("environment") != environment or evidence.get("releaseId") != release_id
            or evidence.get("imageDigest") != image_digest
            or evidence.get("rolloutCompletedAt") != rollout_completed_at):
        raise ReleaseError("monitoring evidence is not bound to this environment, release, and image digest")
    if (not isinstance(at, str) or not at.endswith("Z") or not isinstance(rollout_completed_at, str)
            or not rollout_completed_at.endswith("Z")
            or not isinstance(evidence.get("windowStartedAt"), str)
            or not evidence["windowStartedAt"].endswith("Z")):
        raise ReleaseError("monitoring evidence and rollout completion must contain UTC timestamps")
    try:
        observed = datetime.fromisoformat(at[:-1] + "+00:00")
        rollout_done = datetime.fromisoformat(rollout_completed_at[:-1] + "+00:00")
        window_started = datetime.fromisoformat(str(evidence.get("windowStartedAt", ""))[:-1] + "+00:00")
    except ValueError as exc:
        raise ReleaseError("monitoring observation timestamps are invalid") from exc
    current = now or datetime.now(timezone.utc)
    age = current - observed.astimezone(timezone.utc)
    if age < timedelta(0) or age > timedelta(minutes=2):
        raise ReleaseError("monitoring evidence is stale; no-data is never a pass")
    if window_started < rollout_done or observed < window_started:
        raise ReleaseError("monitoring window must start after actual rollout completion")
    if abs((observed - current).total_seconds()) > 120:
        raise ReleaseError("monitoring evidence timestamp is not fresh")
    if type(evidence.get("sampleCount")) is not int or evidence["sampleCount"] < 1:
        raise ReleaseError("monitoring evidence has no qualifying samples")
    if not isinstance(evidence.get("observationWindowSeconds"), int) or evidence["observationWindowSeconds"] < 600:
        raise ReleaseError("monitoring evidence must cover the full 10-minute observation window")
    if (observed - window_started).total_seconds() < evidence["observationWindowSeconds"]:
        raise ReleaseError("monitoring evidence does not span its declared observation window")
    return evidence


def _wait_for_observation(path: Path, *, environment: str, release_id: str, image_digest: str,
                          rollout_completed_at: str, lease: Any, timeout_seconds: int = 900,
                          poll_interval_seconds: int = 15,
                          clock: Callable[[], float] = time.monotonic,
                          sleep: Callable[[float], None] = time.sleep,
                          now: Callable[[], datetime] = lambda: datetime.now(timezone.utc)) -> dict[str, Any]:
    """Hold the deployment Lease while polling for fresh, full-window evidence."""
    if timeout_seconds < 1 or poll_interval_seconds < 1:
        raise ValueError("observation wait bounds must be positive")
    deadline = clock() + timeout_seconds
    last_error: ReleaseError | None = None
    while True:
        lease.assert_held()
        try:
            return _observation(path, environment=environment, release_id=release_id,
                image_digest=image_digest, rollout_completed_at=rollout_completed_at, now=now())
        except ReleaseError as exc:
            last_error = exc
        remaining = deadline - clock()
        if remaining <= 0:
            break
        sleep(min(poll_interval_seconds, remaining))
    raise ReleaseError(f"trusted monitoring evidence did not complete within {timeout_seconds}s: {last_error}") from last_error


def orchestrate(args: argparse.Namespace, *, runner: Callable[..., Any] = subprocess.run,
                shared: tuple[Any, Any, Any, Any] | None = None) -> dict[str, Any]:
    if not SAFE_ID.fullmatch(args.release_id):
        raise ReleaseError("release_id must be a safe DNS label")
    if not isinstance(args.context, str) or not args.context.strip():
        raise ReleaseError("an explicit non-empty Kubernetes context is required")
    if not re.fullmatch(r"[1-9][0-9]*", args.build_run_id or ""):
        raise ReleaseError("build_run_id must be a positive numeric GitHub run ID")
    if args.environment == "production" and not re.fullmatch(r"[1-9][0-9]*", args.staging_run_id or ""):
        raise ReleaseError("production requires a numeric staging_run_id")
    if args.environment == "production" and (args.staging_receipt is None or args.staging_bundle is None):
        raise ReleaseError("production requires the exact-run staging receipt and retained bundle artifact")
    if args.environment == "staging" and not re.fullmatch(r"[1-9][0-9]*", args.build_run_id or ""):
        raise ReleaseError("staging requires a numeric build_run_id")

    run_id_text = os.environ.get("GITHUB_RUN_ID", "")
    if not re.fullmatch(r"[1-9][0-9]*", run_id_text):
        raise ReleaseError("GITHUB_RUN_ID must be a positive numeric GitHub Actions run ID")
    approval_reference: str | None = None
    if args.environment == "production":
        server_url = os.environ.get("GITHUB_SERVER_URL", "")
        parsed_server = urlsplit(server_url)
        if (parsed_server.scheme != "https" or not parsed_server.hostname or parsed_server.username
                or parsed_server.password or parsed_server.path not in {"", "/"}
                or parsed_server.query or parsed_server.fragment):
            raise ReleaseError("GITHUB_SERVER_URL must be an HTTPS origin for the protected approval reference")
        if os.environ.get("GITHUB_REPOSITORY", "").lower() != args.repository.lower():
            raise ReleaseError("GITHUB_REPOSITORY does not match the promotion repository")
        approval_reference = f"{server_url.rstrip('/')}/{args.repository}/actions/runs/{run_id_text}"
    runner = _credential_scoped_runner(runner)
    verify_api, receipts_api, locking_api, recovery_api = shared or _load_shared_apis()
    _check_environment_protection(runner, args.repository, args.environment)
    validate_secret_readiness(args.readiness_file, args.environment)

    artifact_run = int(args.build_run_id)
    artifact = verify_api.verify_release(artifact_run, args.repository, runner=runner)
    required = {"repository", "sourceCommit", "buildRunId", "image", "indexDigest", "platformDigests", "verification"}
    if not isinstance(artifact, dict) or not required.issubset(artifact) or artifact["buildRunId"] != artifact_run:
        raise ReleaseError("artifact verifier returned an incomplete or mismatched identity")
    image = f"{artifact['repository']}@{artifact['indexDigest']}"
    if artifact["repository"] != "ghcr.io/mudst-2026-luntungsadungyong/tcg-store-backend" or not str(artifact["indexDigest"]).startswith("sha256:"):
        raise ReleaseError("verified artifact repository or index digest is outside the approved contract")
    state_root = args.state_root.resolve()
    env_state = state_root / args.environment
    release_state = env_state / "releases" / args.release_id
    accepted_receipt_path = env_state / f"{args.release_id}.json"
    if (accepted_receipt_path.exists() or (env_state / f"{args.release_id}-failed.json").exists()
            or (env_state / f"{args.release_id}-promotion-evidence.json").exists()
            or (env_state / "bundles" / args.release_id).exists() or release_state.exists()):
        raise ReleaseError("release_id is immutable and already has persisted state; choose a new release_id")
    staging_receipt: dict[str, Any] | None = None
    if args.environment == "production":
        staging_receipt = _trusted_staging_receipt(runner, args.repository, args.staging_run_id,
            args.staging_receipt, args.staging_bundle, args.release_id, artifact, receipts_api)

    progress_root = args.progress_root.resolve()
    if progress_root != state_root:
        raise ReleaseError("progress-root must match the trusted release state root")
    env_state.mkdir(parents=True, exist_ok=True)
    with locking_api.LeaseLock(args.context, f"tcg-store-{args.environment}", args.release_id, runner=runner) as lease:
        if not callable(getattr(lease, "assert_held", None)):
            raise ReleaseError("LeaseLock must provide assert_held before any cluster write")
        lease.assert_held()
        prior = _load_previous(state_root, args.environment, receipts_api, verify_api, args.repository, runner)
        if prior is not None and artifact_run <= prior[0]["buildRunId"]:
            raise ReleaseError("candidate build run is not newer than the independently verified last-good release")
        with tempfile.TemporaryDirectory(prefix="tcg-release-") as work:
            workdir = Path(work)
            config_path = workdir / "release-config.json"
            _render_config(args.config, image, config_path)
            bundle_parent = workdir / "bundle"
            _run(runner, [sys.executable, str(ROOT / "scripts/kubernetes/render.py"), "render",
                          "--environment", args.environment, "--config", str(config_path),
                          "--output-dir", str(bundle_parent), "--validate"])
            bundle = bundle_parent / args.environment
            if not (bundle / "bundle.json").is_file() or _sha_bundle(bundle) == "":
                raise ReleaseError("renderer did not produce the expected validated release bundle")
            metadata = _json_file(bundle / "bundle.json", "rendered bundle metadata")
            if metadata.get("schemaVersion") != 2 or metadata.get("environment") != args.environment:
                raise ReleaseError("renderer bundle schema/environment does not match the CI contract")
            # CI-derived files are generated and structurally checked by C's validator.
            validation = json.loads(_run(runner, [sys.executable, str(ROOT / "scripts/kubernetes/render.py"),
                "validate-bundle", "--manifest-directory", str(bundle), "--environment", args.environment,
                "--release-id", args.release_id, "--format", "json"]).stdout)
            if validation.get("bundleHash") != _sha_bundle(bundle) or not all((bundle / name).is_file() for name in
                    ("ci-prerequisites.yaml", "ci-application.yaml", "bootstrap.yaml")):
                raise ReleaseError("C CI derivation or bundle hash contract is incomplete")
            release_state = env_state / "releases" / args.release_id
            bundle = _persist_candidate_bundle(bundle, release_state)
            for filename in ("ci-prerequisites.yaml", "ci-application.yaml"):
                _run(runner, ["kubectl", "--context", args.context, "--namespace", f"tcg-store-{args.environment}",
                              "apply", "--dry-run=server", "-f", str(bundle / filename)])
            _run(runner, ["kubectl", "--context", args.context, "--namespace", f"tcg-store-{args.environment}",
                          "apply", "--dry-run=server", "-f", str(bundle / "migration.yaml")])

            migration_compatibility = args.migration_compatibility
            if migration_compatibility != "backward-compatible":
                raise ReleaseError("accepted releases require explicit backward-compatible migration evidence")
            previous = prior[0] if prior else None
            progress = release_state / "deploy-progress.json"
            deploy = _deploy_command(args, bundle, progress, args.readiness_file, lease)
            lease.assert_held()
            deployed = _run(runner, deploy, check=False)
            phase = "rollout-complete"
            if deployed.returncode != 0:
                if progress.exists():
                    p = _safe_progress(progress)
                    phase = p["phase"]
                    migration_completed = p["migrationCompleted"]
                else:
                    phase, migration_completed = "unknown", False
                rolled_back = False
                plan = recovery_api.plan_recovery(phase, migration_compatibility,
                    has_verified_previous={"imageVerified": prior is not None,
                        "receiptVerified": prior is not None, "configurationBundleVerified": prior is not None})
                if _rollback_allowed(plan, previous_verified=prior is not None,
                                    migration_completed=migration_completed,
                                    compatibility=migration_compatibility):
                    lease.assert_held()
                    rolled_back = _restore_previous(runner, args, bundle, prior[0], prior[1], receipts_api, workdir, lease)
                _record_failure(receipts_api, env_state, args=args, artifact=artifact,
                                artifact_run=artifact_run, image=image, phase=phase,
                                bundle=bundle, config_hash=validation["configHash"],
                                rolled_back=rolled_back, migration_completed=migration_completed)
                raise ReleaseError(f"deployment failed in phase {phase}; automatic rollback={rolled_back}; pipeline remains failed")

            if not progress.is_file():
                raise ReleaseError("deploy helper did not write its required progress receipt")
            phase_data = _safe_progress(progress)
            if phase_data["phase"] != "rollout-complete" or phase_data["migrationCompleted"] is not True:
                raise ReleaseError("deploy helper did not attest successful migration and rollout completion")
            try:
                candidate_config = _json_file(config_path, "validated non-secret release config")
                smoke = _smoke(args, runner, workdir, candidate_config["frontend_origin"])
                if smoke.get("status") != "passed":
                    raise ReleaseError("read-only smoke checks failed")
            except ReleaseError as exc:
                plan = recovery_api.plan_recovery("rollout", migration_compatibility,
                    has_verified_previous={"imageVerified": prior is not None,
                        "receiptVerified": prior is not None, "configurationBundleVerified": prior is not None})
                rolled_back = False
                if _rollback_allowed(plan, previous_verified=prior is not None,
                                    migration_completed=True, compatibility=migration_compatibility):
                    lease.assert_held()
                    rolled_back = _restore_previous(runner, args, bundle, prior[0], prior[1], receipts_api, workdir, lease)
                _record_failure(receipts_api, env_state, args=args, artifact=artifact,
                                artifact_run=artifact_run, image=image, phase="smoke-or-observation",
                                bundle=bundle, config_hash=validation["configHash"],
                                rolled_back=rolled_back, migration_completed=True,
                                smoke_result="failed")
                raise ReleaseError(f"{exc}; automatic rollback={rolled_back}; pipeline remains failed") from exc
            rollout_completed_at = phase_data.get("rolloutCompletedAt")
            try:
                observation = _wait_for_observation(args.observation_file, environment=args.environment,
                    release_id=args.release_id, image_digest=artifact["indexDigest"],
                    rollout_completed_at=rollout_completed_at, lease=lease)
            except ReleaseError as exc:
                _record_failure(receipts_api, env_state, args=args, artifact=artifact,
                                artifact_run=artifact_run, image=image, phase="observation",
                                bundle=bundle, config_hash=validation["configHash"],
                                rolled_back=False, migration_completed=True, smoke_result="passed")
                raise ReleaseError(f"{exc}; promotion is held pending monitoring evidence; no rollback was triggered") from exc
            bundle_hash = _sha_bundle(bundle)
            # Keep the collector's actual precision: rounding down can shorten
            # a valid 600-second window when deployment started fractionally.
            completed = observation["observedAt"]
            started = phase_data["startedAt"]
            receipt = {"schemaVersion": 2, "releaseId": args.release_id, "environment": args.environment,
                       "sourceCommit": artifact["sourceCommit"], "buildRunId": int(artifact_run),
                       "deployRunId": int(run_id_text),
                       "image": {"repository": artifact["repository"], "indexDigest": artifact["indexDigest"],
                                 "platformDigests": artifact["platformDigests"], "verification": artifact["verification"]},
                       "bundleHash": bundle_hash, "configHash": validation["configHash"],
                       "previousReleaseId": previous.get("releaseId") if previous else None,
                       "migrationCompatibility": migration_compatibility,
                       "phase": args.environment, "status": "accepted",
                       "timestamps": {"startedAt": started, "completedAt": completed},
                       "evidence": {"bundleSchemaVersion": 2,
                                    "bundleHashAlgorithm": "sha256-filename-nul-bytes-v1",
                                    "configHashAlgorithm": "sha256-canonical-configmap-json-v1",
                                    "migrationResult": "passed", "rolloutResult": "passed",
                                    "smokeResult": "passed",
                                    "observationWindowSeconds": observation["observationWindowSeconds"],
                                    "secretReadinessCheckedAt": validate_secret_readiness(args.readiness_file, args.environment)["checkedAt"],
                                    "secretReadinessIssuer": "platform-operator"}}
            accepted_bundle = _copy_bundle_atomically(bundle, env_state / "bundles" / args.release_id)
            inventory = receipts_api.bundle_inventory(accepted_bundle, args.environment)
            receipt["evidence"]["inventory"] = inventory
            receipt["evidence"]["inventoryHash"] = receipts_api.inventory_hash(inventory)
            if previous is not None:
                receipt["evidence"]["previousReceiptId"] = previous["releaseId"]
                receipt["evidence"]["previousBundleHash"] = prior[0]["bundleHash"]
            receipt_path = env_state / f"{args.release_id}.json"
            _call_receipt_write(receipts_api, receipt, receipt_path, accepted_bundle)
            if args.environment == "production":
                assert staging_receipt is not None and approval_reference is not None
                promotion_evidence = {"schemaVersion": 1, "environment": "production",
                    "releaseId": args.release_id, "imageDigest": artifact["indexDigest"],
                    "productionDeployRunId": int(run_id_text), "approvalReference": approval_reference,
                    "stagingWorkflowRunId": int(args.staging_run_id),
                    "stagingDeployRunId": staging_receipt["deployRunId"],
                    "stagingReceiptId": staging_receipt["releaseId"],
                    "stagingSourceCommit": staging_receipt["sourceCommit"],
                    "stagingBuildRunId": staging_receipt["buildRunId"]}
                _atomic_json(env_state / f"{args.release_id}-promotion-evidence.json", promotion_evidence)
            pointer = {"receiptPath": str(receipt_path), "bundlePath": str(accepted_bundle), "bundleHash": bundle_hash}
            _atomic_json(env_state / "last-known-good.json", pointer)
            return receipt


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--environment", required=True, choices=("staging", "production"))
    parser.add_argument("--repository", required=True)
    parser.add_argument("--build-run-id", required=True)
    parser.add_argument("--release-id", required=True)
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--context", required=True)
    parser.add_argument("--readiness-file", type=Path, required=True)
    parser.add_argument("--observation-file", type=Path, required=True)
    parser.add_argument("--state-root", type=Path, required=True)
    parser.add_argument("--progress-root", type=Path, required=True,
                        help="trusted shared root where the platform collector reads release progress")
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--migration-compatibility", required=True,
                        choices=("backward-compatible", "incompatible", "unknown"))
    parser.add_argument("--staging-run-id")
    parser.add_argument("--staging-receipt", type=Path)
    parser.add_argument("--staging-bundle", type=Path)
    parser.add_argument("--staging-receipt-id")
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    if args.environment == "production" and (not args.staging_receipt or not args.staging_bundle or not args.staging_receipt_id):
        print("production requires the exact staging receipt and retained bundle artifact", file=sys.stderr)
        return 2
    try:
        receipt = orchestrate(args)
    except ReleaseError as exc:
        print(f"release rejected: {exc}", file=sys.stderr)
        return 1
    print(json.dumps({"releaseId": receipt["releaseId"], "result": receipt["status"],
                      "digest": receipt["image"]["indexDigest"]}, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
