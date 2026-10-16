"""Read-only PostgreSQL connection snapshots and fail-closed budget checks.

Credentials are read from process environment, an optional JSON object on stdin,
or the repository's existing .env loader. They are passed only through the child
environment, never command-line arguments or report output.
"""

from __future__ import annotations

import argparse
import datetime as dt
import importlib.util
import json
import math
import os
from pathlib import Path
import subprocess
import sys
import time
from typing import Any, Callable, Mapping
from urllib.parse import parse_qs, urlsplit


ROOT = Path(__file__).resolve().parents[2]
POSTGRES_CLIENT_IMAGE = (
    "postgres:16.15-alpine3.24@sha256:"
    "721873c34ceb9f8d8fc265984940dc982404c105f19ad51be9fdc5970a6080ea"
)
PG_ENV_KEYS = (
    "PGHOST", "PGPORT", "PGDATABASE", "PGUSER", "PGPASSWORD", "PGSSLMODE",
    "PGCHANNELBINDING", "PGCONNECT_TIMEOUT", "PGAPPNAME", "PGOPTIONS",
)

SNAPSHOT_SQL = r"""BEGIN READ ONLY;
SELECT json_build_object(
  'sourceTimestamp', clock_timestamp() AT TIME ZONE 'UTC',
  'serverVersion', current_setting('server_version'),
  'database', current_database(),
  'role', current_user,
  'maxConnections', current_setting('max_connections')::int,
  'superuserReservedConnections', current_setting('superuser_reserved_connections')::int,
  'reservedConnections', NULLIF(current_setting('reserved_connections', true), '')::int,
  'roleConnectionLimit', (SELECT rolconnlimit FROM pg_roles WHERE rolname = current_user),
  'databaseConnectionLimit', (SELECT datconnlimit FROM pg_database WHERE datname = current_database()),
  'clientConnectionCount', (SELECT count(*) FROM pg_stat_activity WHERE backend_type = 'client backend'),
  'activeClientConnectionCount', (SELECT count(*) FROM pg_stat_activity WHERE backend_type = 'client backend' AND state = 'active'),
  'idleClientConnectionCount', (SELECT count(*) FROM pg_stat_activity WHERE backend_type = 'client backend' AND state = 'idle'),
  'connectionGroups', COALESCE((
    SELECT json_agg(json_build_object(
      'application', COALESCE(application_name, ''),
      'user', COALESCE(usename, ''),
      'database', COALESCE(datname, ''),
      'state', COALESCE(state, 'unknown'),
      'count', connection_count
    ) ORDER BY application_name, usename, datname, state)
    FROM (
      SELECT application_name, usename, datname, state, count(*) AS connection_count
      FROM pg_stat_activity
      WHERE backend_type = 'client backend'
      GROUP BY application_name, usename, datname, state
    ) grouped_connections
  ), '[]'::json)
);
COMMIT;
"""


def _existing_env_file(path: Path) -> dict[str, str]:
    """Use the repository's existing env parser without echoing any values."""
    if not path.is_file():
        return {}
    loader = ROOT / "scripts" / "initialize-desktop-env.py"
    spec = importlib.util.spec_from_file_location("tcg_desktop_env_loader", loader)
    if spec is None or spec.loader is None:
        raise RuntimeError("Environment loader unavailable")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module.read_environment(path)


def resolve_connection_environment(
    environ: Mapping[str, str] | None = None,
    dotenv_path: Path | None = None,
    stdin_values: Mapping[str, Any] | None = None,
) -> dict[str, str]:
    """Return PG variables, with process env > stdin JSON > .env precedence."""
    process_env = dict(os.environ if environ is None else environ)
    file_values = _existing_env_file(dotenv_path or ROOT / ".env")
    values: dict[str, Any] = {**file_values, **dict(stdin_values or {}), **process_env}

    jdbc_url = (
        values.get("NEON_JDBC_URL")
        or values.get("JDBC_DATABASE_URL")
        or values.get("DATABASE_URL")
    )
    username = values.get("PGUSER") or values.get("NEON_DB_USERNAME")
    password = values.get("PGPASSWORD") or values.get("NEON_DB_PASSWORD")
    if not jdbc_url or not username or not password:
        raise ValueError("Database connection settings are incomplete")

    parsed = urlsplit(str(jdbc_url).removeprefix("jdbc:"))
    if parsed.scheme not in {"postgres", "postgresql"} or not parsed.hostname:
        raise ValueError("Database URL is not a PostgreSQL URL")
    database = parsed.path.lstrip("/")
    if not database:
        raise ValueError("Database name is missing")

    query = parse_qs(parsed.query)
    ssl_mode = str(values.get("PGSSLMODE") or query.get("sslmode", ["require"])[0])
    channel_binding = str(
        values.get("PGCHANNELBINDING")
        or query.get("channel_binding", query.get("channelBinding", ["require"]))[0]
    )
    if ssl_mode not in {"disable", "allow", "prefer", "require", "verify-ca", "verify-full"}:
        raise ValueError("Unsupported PostgreSQL SSL mode")
    if channel_binding not in {"disable", "prefer", "require"}:
        raise ValueError("Unsupported PostgreSQL channel binding mode")

    return {
        "PGHOST": str(values.get("PGHOST") or parsed.hostname),
        "PGPORT": str(values.get("PGPORT") or parsed.port or 5432),
        "PGDATABASE": str(values.get("PGDATABASE") or database),
        "PGUSER": str(username),
        "PGPASSWORD": str(password),
        "PGSSLMODE": ssl_mode,
        "PGCHANNELBINDING": channel_binding,
        "PGCONNECT_TIMEOUT": "15",
        "PGAPPNAME": "tcg-component-a-db-observer",
        "PGOPTIONS": "-c default_transaction_read_only=on -c statement_timeout=10000",
    }


def _client_command(context: str) -> list[str]:
    command = ["docker"]
    if context:
        command.extend(["--context", context])
    command.extend(["run", "--rm", "-i", "--pull=never"])
    for key in PG_ENV_KEYS:
        command.extend(["--env", key])
    command.extend([POSTGRES_CLIENT_IMAGE, "psql", "-X", "-A", "-t", "-v", "ON_ERROR_STOP=1"])
    return command


def read_snapshot(
    connection_env: Mapping[str, str],
    *,
    context: str = "desktop-linux",
    runner: Callable[..., subprocess.CompletedProcess[str]] = subprocess.run,
) -> dict[str, Any]:
    """Read one DB snapshot. Failures are generic and never relay client stderr."""
    child_env = dict(os.environ)
    child_env.update(connection_env)
    try:
        result = runner(
            _client_command(context), input=SNAPSHOT_SQL, env=child_env,
            capture_output=True, text=True, timeout=30, check=False,
        )
        if result.returncode != 0:
            raise RuntimeError("PostgreSQL snapshot command failed")
        payload = next((line for line in result.stdout.splitlines() if line.startswith("{")), None)
        if payload is None:
            raise RuntimeError("PostgreSQL snapshot returned no JSON")
        snapshot = json.loads(payload)
        validate_snapshot(snapshot)
        return snapshot
    except Exception as exc:
        # Do not include the exception text: child tools may include connection data.
        raise RuntimeError("Read-only PostgreSQL snapshot unavailable") from None


def validate_snapshot(snapshot: Mapping[str, Any]) -> None:
    required = (
        "sourceTimestamp", "maxConnections", "superuserReservedConnections",
        "reservedConnections", "roleConnectionLimit", "databaseConnectionLimit",
        "clientConnectionCount", "activeClientConnectionCount",
        "idleClientConnectionCount", "connectionGroups",
    )
    missing = [key for key in required if key not in snapshot or snapshot[key] is None]
    if missing:
        raise ValueError("Snapshot missing required fields: " + ",".join(missing))
    if not isinstance(snapshot["connectionGroups"], list):
        raise ValueError("Snapshot connection groups are invalid")
    for key in required[1:-1]:
        if isinstance(snapshot[key], bool) or not isinstance(snapshot[key], int):
            raise ValueError("Snapshot numeric field is invalid")


def sample_peak(
    sampler: Callable[[], Mapping[str, Any]],
    duration_seconds: float,
    interval_seconds: float = 1.0,
    *,
    clock: Callable[[], float] = time.monotonic,
    sleep: Callable[[float], None] = time.sleep,
) -> dict[str, Any]:
    """Sample for a bounded interval and flag missing/late snapshots as UNKNOWN."""
    if not (1 <= duration_seconds <= 3600):
        raise ValueError("duration_seconds must be between 1 and 3600")
    if not (0.1 <= interval_seconds <= duration_seconds):
        raise ValueError("interval_seconds must be positive and no greater than duration")

    start = clock()
    end = start + duration_seconds
    samples: list[dict[str, Any]] = []
    errors = 0
    gaps = 0
    previous_at: float | None = None
    expected = math.floor(duration_seconds / interval_seconds) + 1
    next_at = start

    while next_at <= end + 1e-9:
        delay = next_at - clock()
        if delay > 0:
            sleep(delay)
        observed_at = clock()
        if previous_at is not None and observed_at - previous_at > interval_seconds * 1.5:
            gaps += 1
        previous_at = observed_at
        try:
            snapshot = dict(sampler())
            validate_snapshot(snapshot)
            samples.append({
                "observedAt": dt.datetime.now(dt.timezone.utc).isoformat(),
                "sourceTimestamp": snapshot["sourceTimestamp"],
                "clientConnectionCount": snapshot["clientConnectionCount"],
            })
        except Exception:
            errors += 1
        next_at += interval_seconds

    complete = errors == 0 and gaps == 0 and len(samples) == expected
    peak = max(samples, key=lambda sample: sample["clientConnectionCount"]) if samples else None
    return {
        "status": "COMPLETE" if complete else "UNKNOWN",
        "durationSeconds": duration_seconds,
        "samplingIntervalSeconds": interval_seconds,
        "expectedSamples": expected,
        "receivedSamples": len(samples),
        "missingSamples": max(0, expected - len(samples)),
        "gapCount": gaps,
        "peakClientConnectionCount": peak["clientConnectionCount"] if peak else None,
        "peakSourceTimestamp": peak["sourceTimestamp"] if peak else None,
        "samples": samples,
    }


def compute_budget(
    snapshot: Mapping[str, Any],
    topology: Mapping[str, Any],
    provider_quota: Mapping[str, Any] | None,
) -> dict[str, Any]:
    """Compare a conservative connection envelope against verified usable quota."""
    try:
        validate_snapshot(snapshot)
    except (TypeError, ValueError):
        return {"status": "UNKNOWN", "reason": "database snapshot is incomplete"}

    reasons: list[str] = []
    required_topology = (
        "desiredReplicas", "maxSurge", "terminatingOverlap", "poolMaxPerPod",
        "jobsBound", "observersBound", "otherConnectionsBound", "reserve",
    )
    numbers: dict[str, int] = {}
    for key in required_topology:
        value = topology.get(key)
        if isinstance(value, bool) or not isinstance(value, int) or value < 0:
            reasons.append(f"missing or invalid topology term: {key}")
        else:
            numbers[key] = value

    quota_limit = provider_quota.get("limit") if provider_quota else None
    quota_verified = provider_quota.get("verified") is True if provider_quota else False
    quota_scope = provider_quota.get("scope") if provider_quota else None
    quota_unit = provider_quota.get("unit") if provider_quota else None
    quota_timestamp = provider_quota.get("sourceTimestamp") if provider_quota else None
    if (not quota_verified or isinstance(quota_limit, bool)
            or not isinstance(quota_limit, int) or quota_limit <= 0):
        reasons.append("provider quota is not verified or unavailable")
    if not quota_scope or quota_unit != "server_connections" or not quota_timestamp:
        reasons.append("provider quota scope, unit, or source timestamp is missing")
    elif isinstance(quota_timestamp, str):
        try:
            dt.datetime.fromisoformat(quota_timestamp.replace("Z", "+00:00"))
        except ValueError:
            reasons.append("provider quota source timestamp is invalid")
    else:
        reasons.append("provider quota source timestamp is invalid")

    if reasons:
        return {"status": "UNKNOWN", "reason": "; ".join(reasons)}

    api_envelope = (
        numbers["desiredReplicas"] + numbers["maxSurge"] + numbers["terminatingOverlap"]
    ) * numbers["poolMaxPerPod"]
    total_envelope = (
        api_envelope + numbers["jobsBound"] + numbers["observersBound"]
        + numbers["otherConnectionsBound"] + numbers["reserve"]
    )
    server_usable = (
        snapshot["maxConnections"] - snapshot["superuserReservedConnections"]
        - snapshot["reservedConnections"]
    )
    role_limit = snapshot["roleConnectionLimit"]
    database_limit = snapshot["databaseConnectionLimit"]
    usable_quota = min(quota_limit, server_usable)
    if role_limit >= 0:
        usable_quota = min(usable_quota, role_limit)
    if database_limit >= 0:
        usable_quota = min(usable_quota, database_limit)

    minimum_reserve = 5
    within = total_envelope <= usable_quota and numbers["reserve"] >= minimum_reserve
    return {
        "status": "WITHIN_BUDGET" if within else "OVER_BUDGET",
        "providerQuota": {"limit": quota_limit, "unit": quota_unit, "scope": quota_scope,
                          "sourceTimestamp": quota_timestamp},
        "serverUsableSlots": server_usable,
        "usableQuota": usable_quota,
        "apiEnvelope": api_envelope,
        "jobsBound": numbers["jobsBound"],
        "observersBound": numbers["observersBound"],
        "otherConnectionsBound": numbers["otherConnectionsBound"],
        "reserve": numbers["reserve"],
        "minimumReserve": minimum_reserve,
        "totalEnvelope": total_envelope,
        "headroom": usable_quota - total_envelope,
    }


def _budget_from_environment(values: Mapping[str, str]) -> tuple[dict[str, int], dict[str, Any]]:
    topology_names = {
        "desiredReplicas": "A_BUDGET_DESIRED_REPLICAS",
        "maxSurge": "A_BUDGET_MAX_SURGE",
        "terminatingOverlap": "A_BUDGET_TERMINATING_OVERLAP",
        "poolMaxPerPod": "A_BUDGET_POOL_MAX_PER_POD",
        "jobsBound": "A_BUDGET_JOBS_BOUND",
        "observersBound": "A_BUDGET_OBSERVERS_BOUND",
        "otherConnectionsBound": "A_BUDGET_OTHER_CONNECTIONS_BOUND",
        "reserve": "A_BUDGET_RESERVE",
    }
    topology = {key: int(values[name]) for key, name in topology_names.items() if values.get(name, "").isdigit()}
    raw_limit = values.get("A_PROVIDER_CONNECTION_QUOTA", "")
    quota = {
        "limit": int(raw_limit) if raw_limit.isdigit() else None,
        "verified": values.get("A_PROVIDER_QUOTA_VERIFIED", "").lower() == "true",
        "scope": values.get("A_PROVIDER_QUOTA_SCOPE"),
        "unit": values.get("A_PROVIDER_QUOTA_UNIT"),
        "sourceTimestamp": values.get("A_PROVIDER_QUOTA_SOURCE_TIMESTAMP"),
    }
    return topology, quota


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--duration-seconds", type=float, default=10)
    parser.add_argument("--interval-seconds", type=float, default=1)
    parser.add_argument("--context", default="desktop-linux")
    parser.add_argument("--credentials-stdin", action="store_true",
                        help="read a JSON object of environment names/values from stdin")
    args = parser.parse_args(argv)

    stdin_values: dict[str, Any] = {}
    if args.credentials_stdin:
        try:
            stdin_values = json.load(sys.stdin)
            if not isinstance(stdin_values, dict):
                raise ValueError
        except Exception:
            print(json.dumps({"status": "UNKNOWN", "reason": "invalid credential input"}))
            return 2

    try:
        connection_env = resolve_connection_environment(stdin_values=stdin_values)
        first = read_snapshot(connection_env, context=args.context)
        peak = sample_peak(lambda: read_snapshot(connection_env, context=args.context),
                           args.duration_seconds, args.interval_seconds)
        values = {**_existing_env_file(ROOT / ".env"), **stdin_values, **dict(os.environ)}
        topology, quota = _budget_from_environment(values)
        budget = compute_budget(first, topology, quota)
        overall_status = "COMPLETE"
        if budget["status"] == "OVER_BUDGET":
            overall_status = "OVER_BUDGET"
        elif peak["status"] != "COMPLETE" or budget["status"] != "WITHIN_BUDGET":
            overall_status = "UNKNOWN"
        output = {"status": overall_status, "snapshot": first, "peak": peak, "budget": budget,
                  "sourceScope": "single PostgreSQL server; provider quota is separately sourced"}
        print(json.dumps(output, indent=2, default=str))
        return 0 if overall_status == "COMPLETE" else 3
    except Exception:
        print(json.dumps({"status": "UNKNOWN", "reason": "read-only observation unavailable"}))
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
