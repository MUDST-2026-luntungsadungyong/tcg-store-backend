"""Namespace-scoped Kubernetes Lease lock with CAS updates and heartbeats."""

from __future__ import annotations

import json
import re
import subprocess
import threading
import time
import uuid
from datetime import datetime, timedelta, timezone
from typing import Any, Callable


class LockError(RuntimeError):
    """A deployment lock could not be safely acquired, renewed, or released."""


Runner = Callable[..., subprocess.CompletedProcess[str]]


def _utc_now() -> datetime:
    return datetime.now(timezone.utc).replace(microsecond=0)


def _format_time(value: datetime) -> str:
    return value.isoformat().replace("+00:00", "Z")


def _parse_time(value: Any) -> datetime:
    if not isinstance(value, str):
        raise LockError("lease has no valid renewal timestamp")
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as exc:
        raise LockError("lease has an invalid renewal timestamp") from exc
    if parsed.tzinfo is None or parsed.utcoffset() is None:
        raise LockError("lease renewal timestamp must include a timezone")
    return parsed.astimezone(timezone.utc)


class LeaseLock:
    """Acquire a Kubernetes Lease without stealing a live holder's lock.

    `has_verified_previous` in callers is unrelated to lock state: Lease updates
    are always guarded by Kubernetes resourceVersion through create/replace.
    """

    def __init__(
        self,
        context: str,
        namespace: str,
        holder: str,
        runner: Runner | None = None,
        *,
        lease_name: str = "tcg-release-lock",
        lease_duration_seconds: int = 90,
        acquire_timeout_seconds: float = 120,
        heartbeat_interval_seconds: float | None = None,
    ) -> None:
        for label, value in (("namespace", namespace), ("holder", holder), ("lease name", lease_name)):
            if not isinstance(value, str) or not value or (label != "holder" and not re.fullmatch(r"[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?", value)):
                raise ValueError(f"invalid {label}")
        if not isinstance(context, str) or not context.strip() or context.strip() != context:
            raise ValueError("an explicit Kubernetes context is required")
        if lease_duration_seconds < 15 or acquire_timeout_seconds < 0:
            raise ValueError("lease duration or acquire timeout is invalid")
        self.context = context
        self.namespace = namespace
        self.holder = holder
        self.lease_name = lease_name
        self.lease_duration_seconds = lease_duration_seconds
        self.acquire_timeout_seconds = acquire_timeout_seconds
        self.heartbeat_interval_seconds = heartbeat_interval_seconds or max(2, lease_duration_seconds / 3)
        if self.heartbeat_interval_seconds >= lease_duration_seconds:
            raise ValueError("heartbeat interval must be shorter than the Lease duration")
        self._runner = runner or subprocess.run
        self._identity = f"{holder}:{uuid.uuid4()}"
        self._stop = threading.Event()
        self._heartbeat_thread: threading.Thread | None = None
        self._acquired = False
        self._heartbeat_error: Exception | None = None

    def _base(self) -> list[str]:
        return ["kubectl", "--context", self.context]

    @property
    def holder_identity(self) -> str:
        """Unique holder value written to the Kubernetes Lease."""
        return self._identity

    def _run(self, args: list[str], payload: dict[str, Any] | None = None) -> subprocess.CompletedProcess[str]:
        command = self._base() + args
        result = self._runner(command, input=json.dumps(payload) if payload is not None else None,
                              capture_output=True, text=True, check=False)
        return result

    def _get(self) -> dict[str, Any] | None:
        result = self._run(["get", "lease", self.lease_name, "-n", self.namespace, "-o", "json"])
        if result.returncode:
            if "notfound" in (result.stderr or "").lower() or "not found" in (result.stderr or "").lower():
                return None
            raise LockError("unable to inspect the deployment Lease")
        try:
            lease = json.loads(result.stdout)
        except json.JSONDecodeError as exc:
            raise LockError("Kubernetes returned invalid Lease data") from exc
        if not isinstance(lease, dict) or not isinstance(lease.get("metadata", {}).get("resourceVersion"), str):
            raise LockError("Kubernetes Lease has no resourceVersion for compare-and-swap")
        return lease

    def _lease_payload(self, current: dict[str, Any] | None) -> dict[str, Any]:
        now = _format_time(_utc_now())
        metadata = {"name": self.lease_name, "namespace": self.namespace}
        spec = {"holderIdentity": self._identity, "leaseDurationSeconds": self.lease_duration_seconds,
                "acquireTime": now, "renewTime": now, "leaseTransitions": 0}
        if current is not None:
            metadata["resourceVersion"] = current["metadata"]["resourceVersion"]
            old = current.get("spec", {})
            spec["acquireTime"] = old.get("acquireTime", now)
            spec["leaseTransitions"] = int(old.get("leaseTransitions", 0)) + (1 if old.get("holderIdentity") else 0)
        return {"apiVersion": "coordination.k8s.io/v1", "kind": "Lease", "metadata": metadata, "spec": spec}

    @staticmethod
    def _active(current: dict[str, Any]) -> bool:
        if not isinstance(current, dict) or not isinstance(current.get("spec", {}), dict):
            raise LockError("Kubernetes Lease data is malformed")
        spec = current.get("spec", {})
        identity = spec.get("holderIdentity")
        if not identity:
            return False
        duration = spec.get("leaseDurationSeconds")
        if type(duration) is not int or duration < 1:
            raise LockError("held Lease has an invalid duration; refusing to steal it")
        renewed = _parse_time(spec.get("renewTime"))
        return _utc_now() < renewed + timedelta(seconds=duration)

    def acquire(self) -> "LeaseLock":
        deadline = time.monotonic() + self.acquire_timeout_seconds
        while True:
            current = self._get()
            if current is not None and self._active(current):
                if time.monotonic() >= deadline:
                    raise LockError("timed out waiting for an active deployment Lease")
                time.sleep(min(1.0, max(0.05, deadline - time.monotonic())))
                continue
            payload = self._lease_payload(current)
            action = ["create", "-f", "-"] if current is None else ["replace", "-f", "-"]
            result = self._run(action, payload)
            if result.returncode == 0:
                self._acquired = True
                self._heartbeat_thread = threading.Thread(target=self._heartbeat_loop, name="tcg-release-lease-heartbeat", daemon=True)
                self._heartbeat_thread.start()
                return self
            if time.monotonic() >= deadline:
                raise LockError("timed out acquiring deployment Lease after a compare-and-swap conflict")
            time.sleep(min(0.5, max(0.05, deadline - time.monotonic())))

    def _heartbeat_loop(self) -> None:
        while not self._stop.wait(self.heartbeat_interval_seconds):
            try:
                current = self._get()
                if current is None or current.get("spec", {}).get("holderIdentity") != self._identity:
                    raise LockError("deployment Lease ownership changed while held")
                payload = self._lease_payload(current)
                payload["spec"]["acquireTime"] = current["spec"].get("acquireTime", payload["spec"]["acquireTime"])
                payload["spec"]["leaseTransitions"] = current["spec"].get("leaseTransitions", 0)
                result = self._run(["replace", "-f", "-"], payload)
                if result.returncode:
                    raise LockError("deployment Lease heartbeat compare-and-swap failed")
            except Exception as exc:  # surfaced to the owning deployment at context exit
                self._heartbeat_error = exc
                self._stop.set()

    def assert_held(self) -> None:
        """Fail closed unless this process still owns a live, renewable Lease."""
        if not self._acquired:
            raise LockError("deployment Lease is not held by this process")
        if self._heartbeat_error is not None:
            raise LockError("deployment Lease heartbeat failed") from self._heartbeat_error
        current = self._get()
        if current is None or current.get("spec", {}).get("holderIdentity") != self._identity:
            raise LockError("deployment Lease ownership changed")
        if not self._active(current):
            raise LockError("deployment Lease expired while held")
        if self._heartbeat_error is not None:
            raise LockError("deployment Lease heartbeat failed") from self._heartbeat_error

    def release(self) -> None:
        if not self._acquired:
            return
        self._stop.set()
        if self._heartbeat_thread is not None:
            self._heartbeat_thread.join(timeout=self.heartbeat_interval_seconds + 2)
        heartbeat_error = self._heartbeat_error
        current = self._get()
        if current is None or current.get("spec", {}).get("holderIdentity") != self._identity:
            self._acquired = False
            raise LockError("refusing to release a Lease owned by another holder")
        payload = self._lease_payload(current)
        payload["spec"]["holderIdentity"] = ""
        payload["spec"]["acquireTime"] = current["spec"].get("acquireTime", payload["spec"]["renewTime"])
        payload["spec"]["leaseTransitions"] = current["spec"].get("leaseTransitions", 0)
        result = self._run(["replace", "-f", "-"], payload)
        self._acquired = False
        if result.returncode:
            raise LockError("Lease release compare-and-swap failed")
        if heartbeat_error:
            raise LockError("Lease heartbeat failed while the release was running") from heartbeat_error

    def __enter__(self) -> "LeaseLock":
        return self.acquire()

    def __exit__(self, exc_type: Any, exc: Any, traceback: Any) -> bool:
        self.release()
        return False
