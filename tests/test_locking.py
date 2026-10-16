import json
import subprocess
import threading
import time
from datetime import datetime, timedelta, timezone

import pytest

from scripts.release.locking import LeaseLock, LockError, _parse_time


class MemoryKube:
    def __init__(self):
        self.lock = threading.Lock()
        self.lease = None
        self.version = 0
        self.fail_replace = False

    def __call__(self, args, *, input=None, **kwargs):
        with self.lock:
            action = next(value for value in args if value in {"get", "create", "replace"})
            if action == "get":
                if self.lease is None:
                    return subprocess.CompletedProcess(args, 1, stdout="", stderr="NotFound")
                return subprocess.CompletedProcess(args, 0, stdout=json.dumps(self.lease), stderr="")
            if action in {"create", "replace"}:
                if self.fail_replace and action == "replace":
                    return subprocess.CompletedProcess(args, 1, stdout="", stderr="resourceVersion conflict")
                candidate = json.loads(input)
                self.version += 1
                candidate.setdefault("metadata", {})["resourceVersion"] = str(self.version)
                self.lease = candidate
                return subprocess.CompletedProcess(args, 0, stdout="", stderr="")
        raise AssertionError(args)


def test_context_is_required_and_always_passed_explicitly():
    with pytest.raises(ValueError, match="explicit Kubernetes context"):
        LeaseLock(None, "tcg-store-staging", "release-1")
    fake = MemoryKube()
    lock = LeaseLock("staging-cluster", "tcg-store-staging", "release-1", runner=fake, acquire_timeout_seconds=0)
    assert lock._base() == ["kubectl", "--context", "staging-cluster"]


@pytest.mark.parametrize("stamp", ["2026-10-16T10:00:00", "not-a-date", "2026-13-99T10:00:00Z"])
def test_malformed_or_naive_lease_dates_are_rejected(stamp):
    with pytest.raises(LockError):
        _parse_time(stamp)


def test_expired_lease_can_be_replaced_but_not_malformed_lease():
    old = (datetime.now(timezone.utc) - timedelta(hours=1)).isoformat().replace("+00:00", "Z")
    assert not LeaseLock._active({"spec": {"holderIdentity": "old", "leaseDurationSeconds": 20, "renewTime": old}})
    with pytest.raises(LockError):
        LeaseLock._active({"spec": {"holderIdentity": "old", "leaseDurationSeconds": 20, "renewTime": "2026-10-16T10:00:00"}})


def test_acquire_assert_and_release_use_compare_and_swap():
    fake = MemoryKube()
    lock = LeaseLock("ctx", "tcg-store-staging", "release-1", runner=fake,
                     acquire_timeout_seconds=0, heartbeat_interval_seconds=1)
    lock.acquire()
    assert fake.lease["spec"]["holderIdentity"] == lock.holder_identity
    with pytest.raises(AttributeError):
        lock.holder_identity = "other"
    lock.assert_held()
    assert fake.lease["metadata"]["resourceVersion"] == "1"
    lock.release()
    assert fake.lease["spec"]["holderIdentity"] == ""
    with pytest.raises(LockError, match="not held"):
        lock.assert_held()


def test_second_holder_cannot_take_live_lease():
    fake = MemoryKube()
    first = LeaseLock("ctx", "tcg-store-staging", "release-1", runner=fake,
                      acquire_timeout_seconds=0, heartbeat_interval_seconds=1)
    first.acquire()
    second = LeaseLock("ctx", "tcg-store-staging", "release-2", runner=fake,
                       acquire_timeout_seconds=0, heartbeat_interval_seconds=1)
    try:
        with pytest.raises(LockError, match="active deployment Lease"):
            second.acquire()
    finally:
        first.release()


def test_heartbeat_failure_is_exposed_by_assert_held():
    fake = MemoryKube()
    lock = LeaseLock("ctx", "tcg-store-staging", "release-1", runner=fake,
                     acquire_timeout_seconds=0, heartbeat_interval_seconds=1)
    lock.acquire()
    lock._heartbeat_error = LockError("mock renewal CAS failure")
    with pytest.raises(LockError, match="heartbeat failed"):
        lock.assert_held()
    with pytest.raises(LockError, match="heartbeat failed"):
        lock.release()


def test_real_heartbeat_compare_and_swap_failure_is_reported():
    fake = MemoryKube()
    lock = LeaseLock("ctx", "tcg-store-staging", "release-1", runner=fake,
                     acquire_timeout_seconds=0, heartbeat_interval_seconds=0.05)
    lock.acquire()
    fake.fail_replace = True
    time.sleep(0.15)
    with pytest.raises(LockError, match="heartbeat failed"):
        lock.assert_held()
    with pytest.raises(LockError, match="compare-and-swap failed"):
        lock.release()
