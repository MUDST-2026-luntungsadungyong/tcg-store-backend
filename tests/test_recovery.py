import pytest

from scripts.release import recovery, receipts


PREVIOUS = {"imageVerified": True, "receiptVerified": True, "configurationBundleVerified": True}
OLD = [{"apiVersion": "v1", "kind": "Service", "namespace": "tcg-store-staging", "name": "api"}]
CURRENT = OLD + [{"apiVersion": "apps/v1", "kind": "Deployment", "namespace": "tcg-store-staging", "name": "api-v2"}]


def test_pre_mutation_rejects_without_cluster_changes():
    assert recovery.plan_recovery("admission", "backward-compatible", PREVIOUS) == "reject"


@pytest.mark.parametrize("phase", ["migration", "migration-failed", "migration-failure"])
def test_migration_failure_never_reruns_or_reverses_migration(phase):
    assert recovery.plan_recovery(phase, "backward-compatible", PREVIOUS) == "recovery-required"


@pytest.mark.parametrize("compatibility,previous,expected", [
    ("backward-compatible", PREVIOUS, "rollback"),
    ("unknown", PREVIOUS, "recovery-required"),
    ("incompatible", PREVIOUS, "recovery-required"),
    ("backward-compatible", False, "recovery-required"),
])
def test_post_migration_recovery_is_bounded(compatibility, previous, expected):
    assert recovery.plan_recovery("rollout-complete", compatibility, previous) == expected


def test_boolean_cannot_claim_prior_image_receipt_and_bundle_are_verified():
    assert recovery.plan_recovery("rollout", "backward-compatible", True) == "recovery-required"


def test_exact_inventory_delta_only_contains_release_owned_namespaced_kinds():
    assert recovery.exact_managed_resource_delta(OLD, CURRENT) == [CURRENT[1]]
    with pytest.raises(receipts.ReceiptError):
        recovery.exact_managed_resource_delta([], [{"apiVersion": "v1", "kind": "Namespace", "namespace": "", "name": "x"}])


def test_live_prune_requires_exact_resource_and_c_owned_label():
    expected = CURRENT[1]
    resource = {"apiVersion": "apps/v1", "kind": "Deployment", "metadata": {
        "namespace": expected["namespace"], "name": expected["name"],
        "labels": {"app.kubernetes.io/managed-by": "tcg-release"},
    }}
    recovery.verify_prune_candidate(resource, expected)
    resource["metadata"]["name"] = "different"
    with pytest.raises(receipts.ReceiptError, match="exact release-owned inventory"):
        recovery.verify_prune_candidate(resource, expected)
