import importlib.util
from pathlib import Path
import subprocess
import unittest


MODULE_PATH = Path(__file__).resolve().parents[1] / "db_observer.py"
SPEC = importlib.util.spec_from_file_location("db_observer", MODULE_PATH)
db_observer = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(db_observer)

# This is the repo root for both the draft tree and the eventual target tree.
db_observer.ROOT = Path(__file__).resolve().parents[3]


def snapshot(**overrides):
    value = {
        "sourceTimestamp": "2026-10-16T00:00:00+00:00",
        "maxConnections": 100,
        "superuserReservedConnections": 3,
        "reservedConnections": 2,
        "roleConnectionLimit": -1,
        "databaseConnectionLimit": -1,
        "clientConnectionCount": 7,
        "activeClientConnectionCount": 2,
        "idleClientConnectionCount": 5,
        "connectionGroups": [],
    }
    value.update(overrides)
    return value


class FakeClock:
    def __init__(self):
        self.value = 0.0

    def now(self):
        return self.value

    def sleep(self, seconds):
        self.value += seconds


class DatabaseObserverTests(unittest.TestCase):
    def test_resolves_credentials_without_putting_them_in_command_arguments(self):
        values = db_observer.resolve_connection_environment(
            environ={
                "NEON_JDBC_URL": "jdbc:postgresql://db.example:5433/store?sslmode=require",
                "NEON_DB_USERNAME": "private-user",
                "NEON_DB_PASSWORD": "private-password",
            },
            dotenv_path=Path("missing.env"),
        )
        self.assertEqual(values["PGHOST"], "db.example")
        self.assertEqual(values["PGPORT"], "5433")
        self.assertEqual(values["PGPASSWORD"], "private-password")
        command = db_observer._client_command("desktop-linux")
        self.assertNotIn("private-password", command)
        self.assertNotIn("private-user", command)

    def test_resolves_local_non_tls_database_url_explicitly(self):
        values = db_observer.resolve_connection_environment(
            environ={
                "DATABASE_URL": "postgresql://ignored@127.0.0.1:55432/acceptance?sslmode=disable&channel_binding=disable",
                "PGUSER": "acceptance-observer",
                "PGPASSWORD": "private-password",
            },
            dotenv_path=Path("missing.env"),
        )
        self.assertEqual(values["PGHOST"], "127.0.0.1")
        self.assertEqual(values["PGDATABASE"], "acceptance")
        self.assertEqual(values["PGSSLMODE"], "disable")
        self.assertEqual(values["PGCHANNELBINDING"], "disable")

    def test_snapshot_uses_read_only_stdin_and_passes_credentials_only_in_environment(self):
        captured = {}

        def runner(command, **kwargs):
            captured.update(command=command, **kwargs)
            return subprocess.CompletedProcess(command, 0, '{"sourceTimestamp":"x","maxConnections":100,'
                '"superuserReservedConnections":3,"reservedConnections":2,"roleConnectionLimit":-1,'
                '"databaseConnectionLimit":-1,"clientConnectionCount":1,"activeClientConnectionCount":0,'
                '"idleClientConnectionCount":1,"connectionGroups":[]}', "")

        env = {"PGHOST": "safe.example", "PGUSER": "u", "PGPASSWORD": "do-not-print"}
        result = db_observer.read_snapshot(env, runner=runner)
        self.assertEqual(result["clientConnectionCount"], 1)
        self.assertIn("BEGIN READ ONLY", captured["input"])
        self.assertIn("do-not-print", captured["env"]["PGPASSWORD"])
        self.assertNotIn("do-not-print", captured["command"])
        self.assertIn("--env", captured["command"])

    def test_snapshot_failure_does_not_relay_subprocess_diagnostics(self):
        secret = "sensitive-password"

        def runner(command, **kwargs):
            return subprocess.CompletedProcess(command, 1, "", f"password={secret}")

        with self.assertRaisesRegex(RuntimeError, "Read-only PostgreSQL snapshot unavailable") as caught:
            db_observer.read_snapshot({"PGPASSWORD": secret}, runner=runner)
        self.assertNotIn(secret, str(caught.exception))

    def test_peak_reports_complete_with_expected_sample_count(self):
        clock = FakeClock()
        values = iter((4, 9, 6))
        result = db_observer.sample_peak(
            lambda: snapshot(clientConnectionCount=next(values)), 2, 1,
            clock=clock.now, sleep=clock.sleep,
        )
        self.assertEqual(result["status"], "COMPLETE")
        self.assertEqual(result["expectedSamples"], 3)
        self.assertEqual(result["receivedSamples"], 3)
        self.assertEqual(result["peakClientConnectionCount"], 9)
        self.assertEqual(result["peakSourceTimestamp"], "2026-10-16T00:00:00+00:00")

    def test_peak_is_unknown_when_a_sample_is_missing(self):
        clock = FakeClock()
        calls = iter((snapshot(), RuntimeError("unavailable"), snapshot()))

        def sampler():
            value = next(calls)
            if isinstance(value, Exception):
                raise value
            return value

        result = db_observer.sample_peak(sampler, 2, 1, clock=clock.now, sleep=clock.sleep)
        self.assertEqual(result["status"], "UNKNOWN")
        self.assertEqual(result["missingSamples"], 1)

    def test_peak_is_unknown_when_sampling_gap_exceeds_resolution(self):
        clock = FakeClock()
        calls = 0

        def sampler():
            nonlocal calls
            calls += 1
            if calls == 2:
                clock.value += 1.6
            return snapshot()

        result = db_observer.sample_peak(sampler, 2, 1, clock=clock.now, sleep=clock.sleep)
        self.assertEqual(result["status"], "UNKNOWN")
        self.assertGreaterEqual(result["gapCount"], 1)

    def test_budget_calculates_api_rollout_and_other_connection_envelope(self):
        result = db_observer.compute_budget(
            snapshot(),
            {"desiredReplicas": 4, "maxSurge": 1, "terminatingOverlap": 2,
             "poolMaxPerPod": 5, "jobsBound": 1, "observersBound": 1,
             "otherConnectionsBound": 10, "reserve": 5},
            {"verified": True, "limit": 80, "scope": "isolated-compute",
             "unit": "server_connections", "sourceTimestamp": "2026-10-16T00:00:00Z"},
        )
        self.assertEqual(result["apiEnvelope"], 35)
        self.assertEqual(result["totalEnvelope"], 52)
        self.assertEqual(result["usableQuota"], 80)
        self.assertEqual(result["status"], "WITHIN_BUDGET")

    def test_budget_fails_closed_when_provider_quota_is_missing_or_unverified(self):
        topology = {"desiredReplicas": 2, "maxSurge": 1, "terminatingOverlap": 1,
                    "poolMaxPerPod": 5, "jobsBound": 0, "observersBound": 1,
                    "otherConnectionsBound": 0, "reserve": 5}
        for quota in (None, {"verified": False, "limit": 90, "scope": "x",
                             "unit": "server_connections", "sourceTimestamp": "now"}):
            with self.subTest(quota=quota):
                result = db_observer.compute_budget(snapshot(), topology, quota)
                self.assertEqual(result["status"], "UNKNOWN")

    def test_budget_fails_closed_when_any_topology_term_is_unknown(self):
        topology = {"desiredReplicas": 2, "maxSurge": 1, "terminatingOverlap": None,
                    "poolMaxPerPod": 5, "jobsBound": 0, "observersBound": 1,
                    "otherConnectionsBound": 0, "reserve": 5}
        result = db_observer.compute_budget(
            snapshot(), topology,
            {"verified": True, "limit": 80, "scope": "x", "unit": "server_connections",
             "sourceTimestamp": "2026-10-16T00:00:00Z"},
        )
        self.assertEqual(result["status"], "UNKNOWN")
        self.assertIn("terminatingOverlap", result["reason"])

    def test_budget_respects_server_role_and_database_caps(self):
        result = db_observer.compute_budget(
            snapshot(maxConnections=45, superuserReservedConnections=3, reservedConnections=2,
                     roleConnectionLimit=25, databaseConnectionLimit=20),
            {"desiredReplicas": 2, "maxSurge": 1, "terminatingOverlap": 0,
             "poolMaxPerPod": 5, "jobsBound": 0, "observersBound": 1,
             "otherConnectionsBound": 0, "reserve": 5},
            {"verified": True, "limit": 80, "scope": "x", "unit": "server_connections",
             "sourceTimestamp": "2026-10-16T00:00:00Z"},
        )
        self.assertEqual(result["serverUsableSlots"], 40)
        self.assertEqual(result["usableQuota"], 20)
        self.assertEqual(result["status"], "OVER_BUDGET")

    def test_snapshot_validation_rejects_missing_connection_limits(self):
        incomplete = snapshot()
        incomplete.pop("reservedConnections")
        with self.assertRaises(ValueError):
            db_observer.validate_snapshot(incomplete)


if __name__ == "__main__":
    unittest.main()
