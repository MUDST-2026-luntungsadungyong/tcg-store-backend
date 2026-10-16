"""Pure phase-aware release recovery decisions; SQL is never reversed here."""

from __future__ import annotations

from typing import Any

from .receipts import ReceiptError, inventory_delta, validate_inventory

KNOWN_PRE_MUTATION_PHASES = {"verification", "render", "admission"}
KNOWN_CONFIG_PHASES = {"prerequisites", "config", "configuration"}
KNOWN_MIGRATION_PHASES = {"migration", "migration-failed", "migration-failure"}
KNOWN_POST_MIGRATION_PHASES = {"rollout", "rollout-complete", "smoke", "smoke-or-observation", "observation", "post-migration"}


def _previous_is_restorable(value: Any) -> bool:
    """Require independently verified image, receipt, and retained bundle evidence."""
    if isinstance(value, dict):
        return value.get("imageVerified") is True and value.get("receiptVerified") is True and value.get("configurationBundleVerified") is True
    return False


def plan_recovery(phase: str, migration_compatibility: str, has_verified_previous: bool) -> str:
    """Choose one bounded action without attempting a database reversal.

    A mapping must express independent verification of the prior image,
    receipt, and retained configuration bundle. A truthy boolean cannot
    establish those independent checks.
    """
    if not isinstance(phase, str) or not isinstance(migration_compatibility, str):
        return "recovery-required"
    phase = phase.strip().lower()
    compatibility = migration_compatibility.strip().lower()
    prior = _previous_is_restorable(has_verified_previous)
    if compatibility not in {"backward-compatible", "unknown", "incompatible"}:
        return "recovery-required"
    if phase in KNOWN_PRE_MUTATION_PHASES:
        return "reject"
    if phase in KNOWN_CONFIG_PHASES:
        return "restore-configuration" if prior else "recovery-required"
    if phase in KNOWN_MIGRATION_PHASES:
        # A failed migration may have partially changed schema or hold locks.
        return "recovery-required"
    if phase in KNOWN_POST_MIGRATION_PHASES:
        if compatibility == "backward-compatible" and prior:
            return "rollback"
        return "recovery-required"
    if phase in {"rollback", "rollback-failed", "recovery", "unknown"}:
        return "recovery-required"
    return "recovery-required"


def exact_managed_resource_delta(previous_inventory: Any, current_inventory: Any) -> list[dict[str, str]]:
    """List only exact current names absent from the prior managed inventory.

    This is the bounded set to review when restoring an older bundle. It does
    not construct selectors, namespace-wide deletes, or SQL/data operations.
    """
    return inventory_delta(previous_inventory, current_inventory)


def verify_prune_candidate(resource: Any, expected: dict[str, str]) -> None:
    """Require an exact live resource identity and release ownership label."""
    from .receipts import assert_release_owned_live_resource

    assert_release_owned_live_resource(resource, expected)


__all__ = ["plan_recovery", "exact_managed_resource_delta", "verify_prune_candidate", "ReceiptError", "validate_inventory"]
