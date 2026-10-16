"""Strict release receipt, bundle integrity, and exact inventory helpers."""

from __future__ import annotations

import hashlib
import json
import re
from decimal import Decimal, InvalidOperation
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import yaml

RECEIPT_SCHEMA_VERSION = 2
BUNDLE_FILES = ("application.yaml", "migration.yaml", "prerequisites.yaml")
SHA256 = re.compile(r"^[0-9a-f]{64}$")
RELEASE_ID = re.compile(r"^[a-z0-9](?:[a-z0-9-]{0,38}[a-z0-9])?$")
COMMIT = re.compile(r"^[0-9a-f]{40}$")
TIMESTAMP = re.compile(r"^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d+)?Z$")
IMAGE_REPOSITORY = "ghcr.io/mudst-2026-luntungsadungyong/tcg-store-backend"
IMAGE_REF = re.compile(re.escape(IMAGE_REPOSITORY) + r"@sha256:[0-9a-f]{64}$")
ENVIRONMENTS = {"staging", "production"}
NAMESPACES = {"staging": "tcg-store-staging", "production": "tcg-store-production"}
COMPATIBILITY = {"backward-compatible", "unknown", "incompatible"}
STATUSES = {"accepted", "rejected", "rolled-back", "recovery-required"}
PHASES = {"verification", "render", "admission", "prerequisites", "migration", "rollout", "smoke", "observation", "staging", "production", "rollback", "recovery"}
TOP_LEVEL = {
    "schemaVersion", "releaseId", "environment", "sourceCommit", "buildRunId", "deployRunId", "image",
    "bundleHash", "configHash", "previousReleaseId", "migrationCompatibility", "phase", "status", "timestamps", "evidence",
}
IMAGE_FIELDS = {"repository", "indexDigest", "platformDigests", "verification"}
PLATFORM_KEYS = {"linux/amd64", "linux/arm64"}
VERIFICATION_KEYS = {"signature", "provenance", "sbom"}
TIMESTAMP_KEYS = {"startedAt", "completedAt"}
EVIDENCE_KEYS = {
    "bundleSchemaVersion", "bundleHashAlgorithm", "configHashAlgorithm", "migrationResult", "rolloutResult",
    "smokeResult", "observationWindowSeconds", "previousReceiptId", "previousBundleHash", "approvalReference",
    "secretReadinessCheckedAt", "secretReadinessIssuer", "inventoryHash", "inventory",
}
SECRET_WORDS = re.compile(r"password|secret|token|credential|authorization|cookie|jdbc|connection.?string|private.?key", re.I)
ALLOWED_SECRET_METADATA = {"secretReadinessCheckedAt", "secretReadinessIssuer"}
SAFE_REFERENCE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:/@+-]{0,199}$")
RELEASE_OWNED_GVKS = {
    ("apps/v1", "Deployment"),
    ("v1", "Service"),
    ("v1", "ConfigMap"),
    ("networking.k8s.io/v1", "Ingress"),
    ("networking.k8s.io/v1", "NetworkPolicy"),
    ("autoscaling/v2", "HorizontalPodAutoscaler"),
    ("policy/v1", "PodDisruptionBudget"),
    ("traefik.io/v1alpha1", "Middleware"),
    ("traefik.io/v1alpha1", "ServersTransport"),
}
RELEASE_OWNED_KINDS = {kind for _, kind in RELEASE_OWNED_GVKS}
RELEASE_OWNER_LABEL = "app.kubernetes.io/managed-by"
RELEASE_OWNER_VALUE = "tcg-release"


class ReceiptError(ValueError):
    """A receipt or release bundle is invalid or incomplete."""


def bundle_hash(directory: str | Path) -> str:
    """Hash the three C-rendered input files in C's canonical order."""
    root = Path(directory)
    digest = hashlib.sha256()
    for name in BUNDLE_FILES:
        path = root / name
        if not path.is_file() or path.is_symlink():
            raise ReceiptError(f"release bundle is missing a regular {name}")
        digest.update(name.encode("utf-8"))
        digest.update(b"\0")
        digest.update(path.read_bytes())
    return digest.hexdigest()


def _yaml_documents(path: Path) -> list[dict[str, Any]]:
    try:
        docs = [doc for doc in yaml.safe_load_all(path.read_text(encoding="utf-8")) if doc is not None]
    except (OSError, UnicodeError, yaml.YAMLError) as exc:
        raise ReceiptError("release bundle YAML is unreadable") from exc
    if any(not isinstance(doc, dict) for doc in docs):
        raise ReceiptError("release bundle contains a non-object resource")
    return docs


def config_hash(directory: str | Path) -> str:
    """Match C exactly: json.dumps(data, sort_keys=True, separators=(",", ":"))."""
    root = Path(directory)
    matches = [doc for doc in _yaml_documents(root / "prerequisites.yaml")
               if doc.get("kind") == "ConfigMap" and (doc.get("metadata") or {}).get("name") == "tcg-store-backend-config"]
    if len(matches) != 1 or not isinstance(matches[0].get("data"), dict):
        raise ReceiptError("bundle must contain exactly one approved release ConfigMap")
    encoded = json.dumps(matches[0]["data"], sort_keys=True, separators=(",", ":")).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def _bundle_image(directory: Path) -> str:
    workloads: list[dict[str, Any]] = []
    for filename, kind in (("application.yaml", "Deployment"), ("migration.yaml", "Job")):
        workloads.extend(doc for doc in _yaml_documents(directory / filename) if doc.get("kind") == kind)
    if len(workloads) != 2:
        raise ReceiptError("bundle must contain exactly one API Deployment and one migration Job")
    images: list[str] = []
    for workload in workloads:
        try:
            containers = workload["spec"]["template"]["spec"]["containers"]
            image = containers[0]["image"]
        except (KeyError, IndexError, TypeError) as exc:
            raise ReceiptError("bundle workload has no primary container image") from exc
        if not isinstance(image, str) or not IMAGE_REF.fullmatch(image):
            raise ReceiptError("bundle workload image is not an immutable approved repository digest")
        images.append(image)
    if images[0] != images[1]:
        raise ReceiptError("API and migration images differ in the retained bundle")
    return images[0]


def verify_bundle(directory: str | Path) -> dict[str, Any]:
    """Recompute bundle metadata, config, environment, namespace, and image from files."""
    root = Path(directory)
    try:
        metadata = json.loads((root / "bundle.json").read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        raise ReceiptError("bundle metadata is missing or invalid") from exc
    if not isinstance(metadata, dict) or type(metadata.get("schemaVersion", 1)) is not int:
        raise ReceiptError("bundle metadata has an invalid schema version")
    version = metadata.get("schemaVersion", 1)
    if version not in (1, 2):
        raise ReceiptError("unsupported Kubernetes bundle schema version")
    if metadata.get("fixture") is not False:
        raise ReceiptError("fixture bundles cannot be recorded as deployable releases")
    environment = metadata.get("environment")
    if not isinstance(environment, str) or environment not in ENVIRONMENTS or metadata.get("namespace") != NAMESPACES[environment]:
        raise ReceiptError("bundle environment or namespace is not an approved release target")
    actual_bundle, actual_config = bundle_hash(root), config_hash(root)
    if version == 2:
        if metadata.get("bundleHash") != actual_bundle or metadata.get("configHash") != actual_config:
            raise ReceiptError("bundle metadata hashes do not match the rendered files")
    elif "bundleHash" in metadata or "configHash" in metadata:
        raise ReceiptError("legacy bundle schema must not claim v2 hash fields")
    return {"schemaVersion": version, "bundleHash": actual_bundle, "configHash": actual_config,
            "environment": environment, "namespace": NAMESPACES[environment], "image": _bundle_image(root)}


def _safe_tree(value: Any, path: str = "receipt") -> None:
    if isinstance(value, dict):
        for key, item in value.items():
            if not isinstance(key, str) or (SECRET_WORDS.search(key) and key not in ALLOWED_SECRET_METADATA):
                raise ReceiptError(f"{path} contains a forbidden field")
            _safe_tree(item, f"{path}.{key}")
    elif isinstance(value, list):
        if len(value) > 500:
            raise ReceiptError(f"{path} exceeds the supported list size")
        for index, item in enumerate(value):
            _safe_tree(item, f"{path}[{index}]")
    elif isinstance(value, str):
        if len(value) > 512 or re.search(r"(?i)(jdbc:|bearer\s|eyJ[a-zA-Z0-9_-]{10,}|-----BEGIN .*PRIVATE KEY-----|https?://[^\s/@:]+:[^\s/@]+@)", value):
            raise ReceiptError(f"{path} contains an unsafe value")


def _parse_utc_timestamp(value: Any, field: str) -> tuple[datetime, Decimal]:
    if not isinstance(value, str) or not TIMESTAMP.fullmatch(value):
        raise ReceiptError(f"{field} must be a valid UTC calendar timestamp")
    fraction_match = re.search(r"\.(\d+)Z$", value)
    fraction = Decimal(0)
    base = value
    if fraction_match:
        try:
            fraction = Decimal("0." + fraction_match.group(1))
        except InvalidOperation as exc:
            raise ReceiptError(f"{field} must be a valid UTC calendar timestamp") from exc
        base = value[:fraction_match.start()] + "Z"
    try:
        parsed = datetime.fromisoformat(base[:-1] + "+00:00")
    except ValueError as exc:
        raise ReceiptError(f"{field} must be a valid UTC calendar timestamp") from exc
    if parsed.tzinfo is None or parsed.utcoffset() != timezone.utc.utcoffset(parsed):
        raise ReceiptError(f"{field} must use UTC")
    return parsed, fraction


def _timestamp_duration_seconds(started: tuple[datetime, Decimal], completed: tuple[datetime, Decimal]) -> Decimal:
    delta = completed[0] - started[0]
    whole_seconds = delta.days * 86_400 + delta.seconds
    return Decimal(whole_seconds) + completed[1] - started[1]


def validate_receipt(receipt: Any, *, bundle_dir: str | Path | None = None,
                     recovery_compatibility: bool = False) -> dict[str, Any]:
    if not isinstance(receipt, dict) or set(receipt) != TOP_LEVEL:
        raise ReceiptError("receipt fields do not match the approved schema")
    _safe_tree(receipt)
    version = receipt.get("schemaVersion")
    if type(version) is not int or version not in ({1, 2} if recovery_compatibility else {2}):
        raise ReceiptError("new release receipts require schema version 2; v1 is recovery-only")
    if not isinstance(receipt["releaseId"], str) or not RELEASE_ID.fullmatch(receipt["releaseId"]):
        raise ReceiptError("releaseId is invalid")
    if not isinstance(receipt["environment"], str) or receipt["environment"] not in ENVIRONMENTS:
        raise ReceiptError("environment must be staging or production")
    if not isinstance(receipt["sourceCommit"], str) or not COMMIT.fullmatch(receipt["sourceCommit"]):
        raise ReceiptError("sourceCommit must be a full commit SHA")
    for key in ("buildRunId", "deployRunId"):
        if type(receipt[key]) is not int or receipt[key] < 1:
            raise ReceiptError(f"{key} must be a positive integer")
    for key in ("bundleHash", "configHash"):
        if not isinstance(receipt[key], str) or not SHA256.fullmatch(receipt[key]):
            raise ReceiptError(f"{key} must be a lowercase SHA-256 hash")
    previous = receipt["previousReleaseId"]
    if previous is not None and (not isinstance(previous, str) or not RELEASE_ID.fullmatch(previous) or previous == receipt["releaseId"]):
        raise ReceiptError("previousReleaseId is invalid")
    if not isinstance(receipt["migrationCompatibility"], str) or receipt["migrationCompatibility"] not in COMPATIBILITY:
        raise ReceiptError("migrationCompatibility is invalid")
    if (not isinstance(receipt["phase"], str) or receipt["phase"] not in PHASES
            or not isinstance(receipt["status"], str) or receipt["status"] not in STATUSES):
        raise ReceiptError("phase or status is invalid")

    image = receipt["image"]
    if not isinstance(image, dict) or set(image) != IMAGE_FIELDS or image.get("repository") != IMAGE_REPOSITORY:
        raise ReceiptError("image identity does not match the approved GHCR repository")
    index_digest, platforms, verification = image.get("indexDigest"), image.get("platformDigests"), image.get("verification")
    if not isinstance(index_digest, str) or not re.fullmatch(r"sha256:[0-9a-f]{64}", index_digest):
        raise ReceiptError("image index digest is invalid")
    if not isinstance(platforms, dict) or set(platforms) != PLATFORM_KEYS or any(not isinstance(d, str) or not re.fullmatch(r"sha256:[0-9a-f]{64}", d) for d in platforms.values()):
        raise ReceiptError("platform digest inventory is invalid")
    if platforms["linux/amd64"] == platforms["linux/arm64"]:
        raise ReceiptError("platform digests must be distinct")
    if not isinstance(verification, dict) or set(verification) != VERIFICATION_KEYS or any(verification[k] is not True for k in VERIFICATION_KEYS):
        raise ReceiptError("image verification evidence is incomplete")

    timestamps, evidence = receipt["timestamps"], receipt["evidence"]
    if not isinstance(timestamps, dict) or set(timestamps) != TIMESTAMP_KEYS:
        raise ReceiptError("receipt timestamps must contain startedAt and completedAt")
    started_at = _parse_utc_timestamp(timestamps["startedAt"], "startedAt")
    completed_at = _parse_utc_timestamp(timestamps["completedAt"], "completedAt")
    if completed_at < started_at:
        raise ReceiptError("receipt completion precedes its start")
    if not isinstance(evidence, dict) or set(evidence) - EVIDENCE_KEYS:
        raise ReceiptError("receipt evidence contains unsupported fields")
    if type(evidence.get("bundleSchemaVersion")) is not int or evidence["bundleSchemaVersion"] not in (1, 2):
        raise ReceiptError("receipt must identify the validated bundle schema")
    if version == 2 and evidence.get("bundleSchemaVersion") != 2:
        raise ReceiptError("new receipts may reference only C bundle schema v2")
    if evidence.get("bundleHashAlgorithm") != "sha256-filename-nul-bytes-v1" or evidence.get("configHashAlgorithm") != "sha256-canonical-configmap-json-v1":
        raise ReceiptError("receipt hash algorithms are missing or unsupported")
    for key in ("migrationResult", "rolloutResult", "smokeResult"):
        if key in evidence and evidence[key] not in {"passed", "failed", "not-run", "blocked"}:
            raise ReceiptError(f"{key} is invalid")
    if "observationWindowSeconds" in evidence and (type(evidence["observationWindowSeconds"]) is not int or not 0 <= evidence["observationWindowSeconds"] <= 86_400):
        raise ReceiptError("observationWindowSeconds is invalid")
    if "previousBundleHash" in evidence and (not isinstance(evidence["previousBundleHash"], str) or not SHA256.fullmatch(evidence["previousBundleHash"])):
        raise ReceiptError("previousBundleHash is invalid")
    if "inventory" in evidence:
        if version == 1:
            raise ReceiptError("legacy receipts have unknown resource ownership and cannot authorize pruning")
        validate_inventory(evidence["inventory"], environment=receipt["environment"])
        if evidence.get("inventoryHash") != inventory_hash(evidence["inventory"]):
            raise ReceiptError("managed resource inventory hash does not match")
    if "inventoryHash" in evidence and (not isinstance(evidence["inventoryHash"], str) or not SHA256.fullmatch(evidence["inventoryHash"])):
        raise ReceiptError("inventoryHash is invalid")
    if "approvalReference" in evidence and (not isinstance(evidence["approvalReference"], str) or not SAFE_REFERENCE.fullmatch(evidence["approvalReference"])):
        raise ReceiptError("approvalReference is invalid")
    if "previousReceiptId" in evidence and (not isinstance(evidence["previousReceiptId"], str) or not RELEASE_ID.fullmatch(evidence["previousReceiptId"])):
        raise ReceiptError("previousReceiptId is invalid")
    readiness_fields = {"secretReadinessCheckedAt", "secretReadinessIssuer"} & set(evidence)
    if readiness_fields and readiness_fields != {"secretReadinessCheckedAt", "secretReadinessIssuer"}:
        raise ReceiptError("secret readiness metadata fields must appear together")
    if readiness_fields:
        _parse_utc_timestamp(evidence["secretReadinessCheckedAt"], "secret readiness checkedAt")
        if evidence["secretReadinessIssuer"] != "platform-operator":
            raise ReceiptError("secret readiness issuer is not trusted")

    if bundle_dir is not None:
        actual = verify_bundle(bundle_dir)
        exact_image = f"{image['repository']}@{image['indexDigest']}"
        if (actual["bundleHash"] != receipt["bundleHash"] or actual["configHash"] != receipt["configHash"]
                or actual["schemaVersion"] != evidence["bundleSchemaVersion"]
                or actual["environment"] != receipt["environment"] or actual["image"] != exact_image):
            raise ReceiptError("receipt identity, hashes, environment, or image does not match retained bundle files")
        if receipt["schemaVersion"] == 2 and receipt["status"] == "accepted":
            expected_inventory = bundle_inventory(bundle_dir, receipt["environment"])
            actual_inventory = validate_inventory(evidence.get("inventory"), environment=receipt["environment"])
            if actual_inventory != expected_inventory or evidence.get("inventoryHash") != inventory_hash(expected_inventory):
                raise ReceiptError("accepted receipt inventory does not match the retained labeled bundle")
    elif receipt["status"] == "accepted":
        raise ReceiptError("accepted receipts must be validated against their retained bundle files")
    if version == 1 and receipt["status"] == "accepted" and not recovery_compatibility:
        raise ReceiptError("legacy accepted receipts are recovery-only")
    if receipt["status"] == "accepted":
        _validate_accepted_evidence(receipt)
    return receipt


def _validate_accepted_evidence(receipt: dict[str, Any]) -> None:
    evidence = receipt["evidence"]
    if receipt["phase"] not in {"staging", "production"}:
        raise ReceiptError("accepted receipts must represent a completed environment release")
    if evidence.get("migrationResult") != "passed" or evidence.get("rolloutResult") != "passed" or evidence.get("smokeResult") != "passed":
        raise ReceiptError("accepted receipt is missing successful migration, rollout, or smoke evidence")
    minimum_window = 600 if receipt["schemaVersion"] == 2 else 1
    if (type(evidence.get("observationWindowSeconds")) is not int
            or evidence["observationWindowSeconds"] < minimum_window):
        if receipt["schemaVersion"] == 2:
            raise ReceiptError("accepted v2 receipt requires an observation window of at least 600 seconds")
        raise ReceiptError("legacy accepted receipt requires a positive observation window")
    started = _parse_utc_timestamp(receipt["timestamps"]["startedAt"], "startedAt")
    completed = _parse_utc_timestamp(receipt["timestamps"]["completedAt"], "completedAt")
    if _timestamp_duration_seconds(started, completed) < evidence["observationWindowSeconds"]:
        raise ReceiptError("accepted receipt timestamps do not cover the recorded observation window")
    if receipt["schemaVersion"] == 2 and ("inventory" not in evidence or "inventoryHash" not in evidence):
        raise ReceiptError("accepted v2 receipt requires the exact bundle inventory and its hash")
    if receipt["migrationCompatibility"] != "backward-compatible":
        raise ReceiptError("accepted release must declare backward-compatible migrations")


def load_receipt(path: str | Path, *, bundle_dir: str | Path | None = None,
                 recovery_compatibility: bool = False) -> dict[str, Any]:
    try:
        value = json.loads(Path(path).read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        raise ReceiptError("release receipt is missing or invalid JSON") from exc
    return validate_receipt(value, bundle_dir=bundle_dir, recovery_compatibility=recovery_compatibility)


def write_receipt(path: str | Path, receipt: dict[str, Any], *, bundle_dir: str | Path | None = None) -> dict[str, Any]:
    validated = validate_receipt(receipt, bundle_dir=bundle_dir)
    target = Path(path)
    target.parent.mkdir(parents=True, exist_ok=True)
    temporary = target.with_name(target.name + ".tmp")
    temporary.write_text(json.dumps(validated, sort_keys=True, indent=2, ensure_ascii=True) + "\n", encoding="utf-8")
    temporary.replace(target)
    return validated


def verify_promotion(staging_receipt: dict[str, Any], contract: dict[str, Any], *, bundle_dir: str | Path) -> None:
    """Require an accepted v2 staging receipt for the exact verified image contract."""
    stage = validate_receipt(staging_receipt, bundle_dir=bundle_dir)
    if stage["environment"] != "staging" or stage["status"] != "accepted" or stage["phase"] != "staging":
        raise ReceiptError("production promotion requires an accepted v2 staging receipt")
    expected_verification = {"signature": True, "provenance": True, "sbom": True}
    if not isinstance(contract, dict) or contract.get("verification") != expected_verification:
        raise ReceiptError("verified image contract is incomplete")
    expected = {"repository": contract.get("repository"), "indexDigest": contract.get("indexDigest"),
                "platformDigests": contract.get("platformDigests"), "verification": contract.get("verification")}
    if stage["sourceCommit"] != contract.get("sourceCommit") or stage["buildRunId"] != contract.get("buildRunId"):
        raise ReceiptError("staging receipt source or build run differs from the verified artifact")
    if stage["image"] != expected:
        raise ReceiptError("staging receipt image digests differ from the verified artifact")
    if stage["evidence"].get("migrationResult") != "passed" or stage["evidence"].get("smokeResult") != "passed":
        raise ReceiptError("staging receipt does not contain successful release gates")


def validate_inventory(inventory: Any, *, environment: str | None = None) -> list[dict[str, str]]:
    """Validate exact release-owned namespaced identities, excluding bootstrap and Job resources."""
    if environment is not None and (not isinstance(environment, str) or environment not in ENVIRONMENTS):
        raise ReceiptError("inventory environment must be staging or production")
    if not isinstance(inventory, list) or len(inventory) > 500:
        raise ReceiptError("managed resource inventory must be a bounded list")
    normalized: list[dict[str, str]] = []
    seen: set[tuple[str, str, str, str]] = set()
    for item in inventory:
        if not isinstance(item, dict) or set(item) != {"apiVersion", "kind", "namespace", "name"}:
            raise ReceiptError("managed resource identity must contain exactly apiVersion/kind/namespace/name")
        if any(not isinstance(item[k], str) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9./-]{0,199}", item[k]) for k in item):
            raise ReceiptError("managed resource identity contains an invalid value")
        expected_namespace = NAMESPACES.get(environment) if environment is not None else None
        if (item["namespace"] not in NAMESPACES.values()
                or (expected_namespace is not None and item["namespace"] != expected_namespace)
                or (item["apiVersion"], item["kind"]) not in RELEASE_OWNED_GVKS):
            raise ReceiptError("inventory may contain only approved exact-GVK resources in a release namespace")
        key = (item["apiVersion"], item["kind"], item["namespace"], item["name"])
        if key in seen:
            raise ReceiptError("managed resource inventory has duplicates")
        seen.add(key)
        normalized.append({k: item[k] for k in ("apiVersion", "kind", "namespace", "name")})
    if len({item["namespace"] for item in normalized}) > 1:
        raise ReceiptError("managed inventory cannot span release namespaces")
    return sorted(normalized, key=lambda item: (item["apiVersion"], item["kind"], item["namespace"], item["name"]))


def inventory_hash(inventory: Any) -> str:
    canonical = json.dumps(validate_inventory(inventory), sort_keys=True, separators=(",", ":")).encode("utf-8")
    return hashlib.sha256(canonical).hexdigest()


def bundle_inventory(directory: str | Path, environment: str) -> list[dict[str, str]]:
    """Derive prune-eligible identities only from v2 manifests carrying C's owner label."""
    if not isinstance(environment, str) or environment not in ENVIRONMENTS:
        raise ReceiptError("inventory environment must be staging or production")
    root = Path(directory)
    try:
        metadata = json.loads((root / "bundle.json").read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        raise ReceiptError("bundle metadata is missing or invalid") from exc
    if not isinstance(metadata, dict) or metadata.get("schemaVersion") != 2 or metadata.get("environment") != environment:
        raise ReceiptError("automatic inventory requires a matching C bundle schema v2")
    identities = []
    for filename in BUNDLE_FILES:
        for resource in _yaml_documents(root / filename):
            kind = resource.get("kind")
            if kind not in RELEASE_OWNED_KINDS:
                continue
            metadata = resource.get("metadata") or {}
            if metadata.get("namespace") != NAMESPACES[environment]:
                raise ReceiptError("release-controlled bundle resource has the wrong namespace")
            labels = metadata.get("labels") or {}
            if labels.get(RELEASE_OWNER_LABEL) != RELEASE_OWNER_VALUE:
                raise ReceiptError("release-controlled bundle resource lacks the C ownership label")
            identities.append({"apiVersion": resource.get("apiVersion", ""), "kind": kind,
                               "namespace": metadata["namespace"], "name": metadata.get("name", "")})
    return validate_inventory(identities, environment=environment)


def inventory_delta(previous: Any, current: Any) -> list[dict[str, str]]:
    """Return exact current release-owned identities absent from prior inventory."""
    normalized_before = validate_inventory(previous)
    after = validate_inventory(current)
    old_namespaces = {item["namespace"] for item in normalized_before}
    new_namespaces = {item["namespace"] for item in after}
    if old_namespaces and new_namespaces and old_namespaces != new_namespaces:
        raise ReceiptError("inventory delta cannot cross release namespaces")
    before = {(x["apiVersion"], x["kind"], x["namespace"], x["name"]) for x in normalized_before}
    return [item for item in after if (item["apiVersion"], item["kind"], item["namespace"], item["name"]) not in before]


def assert_release_owned_live_resource(resource: Any, expected: dict[str, str]) -> None:
    """Require exact identity and C's live ownership label before a prune decision."""
    if not isinstance(resource, dict):
        raise ReceiptError("live resource is not an object")
    metadata = resource.get("metadata") or {}
    labels = metadata.get("labels") or {}
    actual = {"apiVersion": resource.get("apiVersion"), "kind": resource.get("kind"),
              "namespace": metadata.get("namespace"), "name": metadata.get("name")}
    if (actual != expected or actual["namespace"] not in NAMESPACES.values()
            or (actual["apiVersion"], actual["kind"]) not in RELEASE_OWNED_GVKS):
        raise ReceiptError("live resource identity is not in the exact release-owned inventory")
    if labels.get(RELEASE_OWNER_LABEL) != RELEASE_OWNER_VALUE:
        raise ReceiptError("live resource lacks the release ownership label; refusing prune")
