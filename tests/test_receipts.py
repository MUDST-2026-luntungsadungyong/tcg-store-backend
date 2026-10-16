import hashlib
import json

import pytest
import yaml

from scripts.release import receipts

REPO = receipts.IMAGE_REPOSITORY
SHA = "a" * 40
INDEX = "sha256:" + "1" * 64
IMAGE_REF = f"{REPO}@{INDEX}"


def make_bundle(path, *, image=IMAGE_REF, environment="staging", version=2, config_value="café"):
    path.mkdir(parents=True, exist_ok=True)
    (path / "application.yaml").write_text(yaml.safe_dump({"apiVersion": "apps/v1", "kind": "Deployment", "metadata": {"name": "api", "namespace": f"tcg-store-{environment}", "labels": {receipts.RELEASE_OWNER_LABEL: receipts.RELEASE_OWNER_VALUE}}, "spec": {"template": {"spec": {"containers": [{"name": "api", "image": image}]}}}}, sort_keys=False), encoding="utf-8")
    (path / "migration.yaml").write_text(yaml.safe_dump({"apiVersion": "batch/v1", "kind": "Job", "metadata": {"name": "migration", "namespace": f"tcg-store-{environment}"}, "spec": {"template": {"spec": {"containers": [{"name": "migration", "image": image}]}}}}, sort_keys=False), encoding="utf-8")
    (path / "prerequisites.yaml").write_text(yaml.safe_dump({"apiVersion": "v1", "kind": "ConfigMap", "metadata": {"name": "tcg-store-backend-config", "namespace": f"tcg-store-{environment}", "labels": {receipts.RELEASE_OWNER_LABEL: receipts.RELEASE_OWNER_VALUE}}, "data": {"unicode": config_value, "plain": "yes"}}, sort_keys=False, allow_unicode=True), encoding="utf-8")
    metadata = {"schemaVersion": version, "environment": environment, "namespace": f"tcg-store-{environment}", "fixture": False}
    if version == 2:
        metadata["bundleHash"] = receipts.bundle_hash(path)
        metadata["configHash"] = receipts.config_hash(path)
    (path / "bundle.json").write_text(json.dumps(metadata), encoding="utf-8")
    return path


def make_receipt(bundle, *, schema_version=2, image_digest=INDEX, config_digest=None):
    actual = receipts.verify_bundle(bundle)
    evidence = {"bundleSchemaVersion": actual["schemaVersion"],
                "bundleHashAlgorithm": "sha256-filename-nul-bytes-v1",
                "configHashAlgorithm": "sha256-canonical-configmap-json-v1",
                "migrationResult": "passed", "rolloutResult": "passed", "smokeResult": "passed",
                "observationWindowSeconds": 600,
                "secretReadinessCheckedAt": "2026-10-16T10:00:00Z",
                "secretReadinessIssuer": "platform-operator"}
    if schema_version == 2:
        inventory = receipts.bundle_inventory(bundle, "staging")
        evidence["inventory"] = inventory
        evidence["inventoryHash"] = receipts.inventory_hash(inventory)
    return {"schemaVersion": schema_version, "releaseId": "release-123", "environment": "staging",
            "sourceCommit": SHA, "buildRunId": 123, "deployRunId": 456,
            "image": {"repository": REPO, "indexDigest": image_digest,
                      "platformDigests": {"linux/amd64": "sha256:" + "2" * 64, "linux/arm64": "sha256:" + "3" * 64},
                      "verification": {"signature": True, "provenance": True, "sbom": True}},
            "bundleHash": actual["bundleHash"], "configHash": config_digest or actual["configHash"],
            "previousReleaseId": None, "migrationCompatibility": "backward-compatible",
            "phase": "staging", "status": "accepted",
            "timestamps": {"startedAt": "2026-10-16T10:00:00Z", "completedAt": "2026-10-16T10:10:00Z"},
            "evidence": evidence}


def test_config_hash_matches_default_json_ensure_ascii_true(tmp_path):
    bundle = make_bundle(tmp_path / "bundle")
    documents = list(yaml.safe_load_all((bundle / "prerequisites.yaml").read_text(encoding="utf-8")))
    data = next(doc["data"] for doc in documents if isinstance(doc, dict) and doc.get("kind") == "ConfigMap")
    expected = hashlib.sha256(json.dumps(data, sort_keys=True, separators=(",", ":")).encode()).hexdigest()
    assert receipts.config_hash(bundle) == expected


def test_secret_readiness_metadata_is_allowed_but_other_secret_keys_are_not(tmp_path):
    bundle = make_bundle(tmp_path / "bundle")
    receipt = make_receipt(bundle)
    assert receipts.validate_receipt(receipt, bundle_dir=bundle) is receipt
    receipt["evidence"]["secretValue"] = "must-not-be-recorded"
    with pytest.raises(receipts.ReceiptError, match="forbidden"):
        receipts.validate_receipt(receipt)


def test_receipt_must_match_actual_manifest_image(tmp_path):
    bundle = make_bundle(tmp_path / "bundle")
    receipt = make_receipt(bundle, image_digest="sha256:" + "9" * 64)
    with pytest.raises(receipts.ReceiptError, match="image does not match"):
        receipts.validate_receipt(receipt, bundle_dir=bundle)


def test_receipt_must_match_bundle_hash_config_and_environment(tmp_path):
    bundle = make_bundle(tmp_path / "bundle")
    receipt = make_receipt(bundle)
    receipt["bundleHash"] = "f" * 64
    with pytest.raises(receipts.ReceiptError, match="hashes"):
        receipts.validate_receipt(receipt, bundle_dir=bundle)


@pytest.mark.parametrize("started,completed", [
    ("2026-13-16T10:00:00Z", "2026-10-16T10:10:00Z"),
    ("2026-02-30T10:00:00Z", "2026-10-16T10:10:00Z"),
])
def test_invalid_calendar_timestamps_are_rejected(tmp_path, started, completed):
    bundle = make_bundle(tmp_path / "bundle")
    receipt = make_receipt(bundle)
    receipt["timestamps"] = {"startedAt": started, "completedAt": completed}
    with pytest.raises(receipts.ReceiptError, match="valid UTC calendar"):
        receipts.validate_receipt(receipt, bundle_dir=bundle)


def test_timestamp_order_uses_instants_not_string_order():
    receipt = {
        "schemaVersion": 2, "releaseId": "release-123", "environment": "staging", "sourceCommit": SHA,
        "buildRunId": 123, "deployRunId": 456,
        "image": {"repository": REPO, "indexDigest": INDEX,
                  "platformDigests": {"linux/amd64": "sha256:" + "2" * 64, "linux/arm64": "sha256:" + "3" * 64},
                  "verification": {"signature": True, "provenance": True, "sbom": True}},
        "bundleHash": "a" * 64, "configHash": "b" * 64, "previousReleaseId": None,
        "migrationCompatibility": "unknown", "phase": "recovery", "status": "rejected",
        "timestamps": {"startedAt": "2026-10-16T10:00:00Z", "completedAt": "2026-10-16T10:00:00.1Z"},
        "evidence": {"bundleSchemaVersion": 2, "bundleHashAlgorithm": "sha256-filename-nul-bytes-v1",
                     "configHashAlgorithm": "sha256-canonical-configmap-json-v1"},
    }
    assert receipts.validate_receipt(receipt)["timestamps"]["completedAt"].endswith(".1Z")


@pytest.mark.parametrize("window,completed", [(599, "2026-10-16T10:10:00Z"), (600, "2026-10-16T10:09:59.999Z")])
def test_accepted_observation_must_meet_ten_minutes_and_actual_elapsed_time(tmp_path, window, completed):
    bundle = make_bundle(tmp_path / "bundle")
    receipt = make_receipt(bundle)
    receipt["evidence"]["observationWindowSeconds"] = window
    receipt["timestamps"]["completedAt"] = completed
    with pytest.raises(receipts.ReceiptError, match="600 seconds|timestamps do not cover"):
        receipts.validate_receipt(receipt, bundle_dir=bundle)


@pytest.mark.parametrize("key,value", [
    ("secretReadinessIssuer", "untrusted-runner"),
    ("secretReadinessCheckedAt", "2026-02-30T10:00:00Z"),
])
def test_included_secret_readiness_metadata_is_validated(tmp_path, key, value):
    bundle = make_bundle(tmp_path / "bundle")
    receipt = make_receipt(bundle)
    receipt["evidence"][key] = value
    with pytest.raises(receipts.ReceiptError, match="secret readiness"):
        receipts.validate_receipt(receipt, bundle_dir=bundle)


def test_secret_readiness_metadata_must_be_a_complete_pair(tmp_path):
    bundle = make_bundle(tmp_path / "bundle")
    receipt = make_receipt(bundle)
    receipt["evidence"].pop("secretReadinessIssuer")
    with pytest.raises(receipts.ReceiptError, match="must appear together"):
        receipts.validate_receipt(receipt, bundle_dir=bundle)


def test_accepted_receipt_cannot_be_validated_from_metadata_only(tmp_path):
    bundle = make_bundle(tmp_path / "bundle")
    with pytest.raises(receipts.ReceiptError, match="retained bundle files"):
        receipts.validate_receipt(make_receipt(bundle))


def test_promotion_requires_accepted_v2_receipt_and_matching_bundle(tmp_path):
    bundle = make_bundle(tmp_path / "bundle")
    receipt = make_receipt(bundle)
    contract = {"repository": REPO, "sourceCommit": SHA, "buildRunId": 123, "indexDigest": INDEX,
                "platformDigests": receipt["image"]["platformDigests"],
                "verification": {"signature": True, "provenance": True, "sbom": True}}
    receipts.verify_promotion(receipt, contract, bundle_dir=bundle)
    with pytest.raises(receipts.ReceiptError, match="retained bundle files"):
        receipts.validate_receipt(receipt)


def test_v1_is_recovery_only_and_cannot_carry_prune_inventory(tmp_path):
    bundle = make_bundle(tmp_path / "legacy", version=1)
    receipt = make_receipt(bundle, schema_version=1)
    receipt["evidence"]["bundleSchemaVersion"] = 1
    # Legacy accepted receipts retain their historical positive-window contract;
    # the v2 ten-minute minimum must not invalidate recovery compatibility.
    receipt["evidence"]["observationWindowSeconds"] = 60
    receipt["timestamps"]["completedAt"] = "2026-10-16T10:01:00Z"
    with pytest.raises(receipts.ReceiptError, match="recovery-only"):
        receipts.validate_receipt(receipt, recovery_compatibility=False)
    assert receipts.validate_receipt(receipt, bundle_dir=bundle, recovery_compatibility=True)
    receipt["status"] = "rejected"
    receipt["phase"] = "recovery"
    receipt["evidence"].pop("migrationResult")
    receipt["evidence"].pop("rolloutResult")
    receipt["evidence"].pop("smokeResult")
    receipt["evidence"].pop("observationWindowSeconds")
    assert receipts.validate_receipt(receipt, recovery_compatibility=True)
    receipt["evidence"]["inventory"] = []
    receipt["evidence"]["inventoryHash"] = receipts.inventory_hash([])
    with pytest.raises(receipts.ReceiptError, match="ownership"):
        receipts.validate_receipt(receipt, recovery_compatibility=True)


@pytest.mark.parametrize("kind", ["Namespace", "ServiceAccount", "Secret", "Job", "ClusterRole"])
def test_bootstrap_and_migration_resources_are_excluded_from_inventory(kind):
    identity = {"apiVersion": "v1", "kind": kind, "namespace": "tcg-store-staging", "name": "x"}
    with pytest.raises(receipts.ReceiptError, match="only approved"):
        receipts.validate_inventory([identity])


def test_prune_candidate_requires_exact_identity_and_live_owner_label():
    expected = {"apiVersion": "apps/v1", "kind": "Deployment", "namespace": "tcg-store-staging", "name": "api"}
    live = {**expected, "metadata": {"name": "api", "namespace": "tcg-store-staging",
                                      "labels": {receipts.RELEASE_OWNER_LABEL: receipts.RELEASE_OWNER_VALUE}}}
    receipts.assert_release_owned_live_resource(live, expected)
    live["metadata"]["labels"].clear()
    with pytest.raises(receipts.ReceiptError, match="ownership label"):
        receipts.assert_release_owned_live_resource(live, expected)


@pytest.mark.parametrize("kind", ["Middleware", "ServersTransport"])
def test_traefik_owned_resources_are_accepted_by_exact_gvk(kind):
    identity = {"apiVersion": "traefik.io/v1alpha1", "kind": kind,
                "namespace": "tcg-store-staging", "name": "edge-policy"}
    assert receipts.validate_inventory([identity]) == [identity]


def test_inventory_cannot_cross_environment_namespaces():
    staging = {"apiVersion": "v1", "kind": "Service", "namespace": "tcg-store-staging", "name": "api"}
    production = {**staging, "namespace": "tcg-store-production"}
    with pytest.raises(receipts.ReceiptError, match="cannot span"):
        receipts.validate_inventory([staging, production])


def test_bundle_inventory_requires_c_label_and_excludes_migration_job(tmp_path):
    bundle = make_bundle(tmp_path / "bundle")
    app = next(yaml.safe_load_all((bundle / "application.yaml").read_text(encoding="utf-8")))
    app.setdefault("metadata", {}).get("labels", {}).pop(receipts.RELEASE_OWNER_LABEL)
    (bundle / "application.yaml").write_text(yaml.safe_dump(app), encoding="utf-8")
    with pytest.raises(receipts.ReceiptError, match="ownership label"):
        receipts.bundle_inventory(bundle, "staging")
    app.setdefault("metadata", {}).setdefault("labels", {})[receipts.RELEASE_OWNER_LABEL] = receipts.RELEASE_OWNER_VALUE
    (bundle / "application.yaml").write_text(yaml.safe_dump(app), encoding="utf-8")
    metadata = {"schemaVersion": 2, "environment": "staging", "namespace": "tcg-store-staging", "fixture": False,
                "bundleHash": receipts.bundle_hash(bundle), "configHash": receipts.config_hash(bundle)}
    (bundle / "bundle.json").write_text(json.dumps(metadata), encoding="utf-8")
    result = receipts.bundle_inventory(bundle, "staging")
    assert {item["kind"] for item in result} == {"Deployment", "ConfigMap"}
