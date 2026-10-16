import importlib.util
import json
import sys
import tempfile
import threading
import unittest
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


MODULE_PATH = Path(__file__).resolve().parents[1] / "api_smoke.py"
SPEC = importlib.util.spec_from_file_location("api_smoke", MODULE_PATH)
api_smoke = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = api_smoke
SPEC.loader.exec_module(api_smoke)


class MockApi(BaseHTTPRequestHandler):
    requests = []

    def log_message(self, *_args):
        pass

    def do_GET(self):
        self.requests.append(("GET", self.path))
        if self.path == "/api/health":
            self.reply(200, {"status": "UP"})
        elif self.path in ("/api/products", "/api/cards"):
            key = "content" if self.path == "/api/cards" else "items"
            self.reply(200, {key: [], "totalElements": 0, "totalPages": 0})
        elif self.path in ("/api/cart", "/api/orders/me", "/api/admin/users"):
            self.reply(401, {"error": "unauthorized"})
        elif self.path.startswith("/actuator/"):
            self.reply(404, {"error": "not found"})
        else:
            self.reply(404, {})

    def do_OPTIONS(self):
        self.requests.append(("OPTIONS", self.path))
        self.send_response(204)
        self.send_header("Access-Control-Allow-Origin", "http://frontend.test")
        self.send_header("Access-Control-Allow-Headers", "Authorization, Content-Type, Idempotency-Key, X-Request-ID")
        self.send_header("Access-Control-Expose-Headers", "X-Request-ID")
        self.end_headers()

    def reply(self, status, value):
        data = json.dumps(value).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)


class SmokeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), MockApi)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        cls.base_url = f"http://127.0.0.1:{cls.server.server_port}"

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join(timeout=3)

    def setUp(self):
        MockApi.requests.clear()

    def test_readonly_baseline_against_mock_http_server(self):
        report = api_smoke.run_smoke("local", self.base_url)
        self.assertEqual("passed", report["status"])
        self.assertEqual("readonly", report["mode"])
        self.assertEqual(8, report["summary"]["passed"])
        self.assertIn(("GET", "/api/products"), MockApi.requests)
        self.assertIn(("GET", "/api/cards"), MockApi.requests)
        self.assertEqual("skipped", next(c["status"] for c in report["checks"] if c["name"] == "buyer-login-and-me"))

    def test_cors_preflight_uses_configured_frontend_origin(self):
        report = api_smoke.run_smoke(
            "local", self.base_url, environ={"TCG_SMOKE_FRONTEND_ORIGIN": "http://frontend.test"},
        )
        self.assertEqual("passed", report["status"])
        self.assertIn(("OPTIONS", "/api/health"), MockApi.requests)

    def test_production_mutation_is_refused_before_any_request_and_keeps_reason(self):
        transport_calls = []
        report = api_smoke.run_smoke(
            "production", "https://api.example.test", mutating=True,
            transport=lambda *args: transport_calls.append(args),
        )
        self.assertEqual("failed", report["status"])
        self.assertEqual("production mutation is forbidden", report["checks"][0]["error"])
        self.assertEqual("blocked before any request", report["checks"][1]["error"])
        self.assertEqual([], transport_calls)

    def test_authenticated_redirect_to_another_host_is_blocked(self):
        handler = api_smoke._NoCrossOriginRedirect()
        request = urllib.request.Request(
            "https://api.example.test/api/auth/me",
            headers={"Authorization": "Bearer sentinel-token"},
        )
        with self.assertRaisesRegex(api_smoke.SmokeError, "cross-origin redirect refused"):
            handler.redirect_request(
                request, None, 302, "Found", {},
                "https://attacker.example.test/collect",
            )

    def test_missing_fixture_policy_blocks_staging_before_any_request(self):
        transport_calls = []
        report = api_smoke.run_smoke(
            "staging", "https://api.example.test", mutating=True, environ={},
            transport=lambda *args: transport_calls.append(args),
        )
        self.assertEqual("failed", report["status"])
        self.assertEqual("staging mutation requires --fixture-policy", report["checks"][0]["error"])
        self.assertEqual("blocked before any request", report["checks"][1]["error"])
        self.assertEqual([], transport_calls)

    def test_cloud_read_checks_cannot_be_silently_skipped(self):
        transport_calls = []
        report = api_smoke.run_smoke(
            "production", "https://api.example.test", environ={},
            transport=lambda *args: transport_calls.append(args),
        )
        self.assertEqual("failed", report["status"])
        self.assertFalse(any(check["status"] == "skipped" for check in report["checks"]))
        self.assertEqual("preflight", report["checks"][0]["name"])
        self.assertEqual([], transport_calls)

    def test_storage_public_base_url_requires_reviewed_https_same_host_and_no_secrets(self):
        storage = {
            "host": "objects.example.test",
            "publicBaseUrl": "https://objects.example.test/storage/v1/object/public/fixture-bucket",
        }
        env = {"TCG_SMOKE_STORAGE_PUBLIC_BASE_URL": storage["publicBaseUrl"]}
        self.assertEqual(
            ("https://objects.example.test", "/storage/v1/object/public/fixture-bucket"),
            api_smoke._storage_public_base(storage, env),
        )
        for public_base_url in (
            "http://objects.example.test/storage/v1/object/public/fixture-bucket",
            "https://other.example.test/storage/v1/object/public/fixture-bucket",
            "https://user:password@objects.example.test/storage/v1/object/public/fixture-bucket",
            "https://objects.example.test/storage/v1/object/public/fixture-bucket?token=secret",
        ):
            with self.subTest(public_base_url=public_base_url):
                storage["publicBaseUrl"] = public_base_url
                env["TCG_SMOKE_STORAGE_PUBLIC_BASE_URL"] = public_base_url
                with self.assertRaises(api_smoke.SmokeError):
                    api_smoke._storage_public_base(storage, env)

    def test_report_is_redacted_and_parent_directory_is_created(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "nested" / "reports" / "report.json"
            report = api_smoke.run_smoke("local", self.base_url, output=output)
            saved = json.loads(output.read_text(encoding="utf-8"))
            contents = output.read_text(encoding="utf-8")
        self.assertEqual(report, saved)
        self.assertEqual(1, saved["schemaVersion"])
        self.assertTrue(all("body" not in check for check in saved["checks"]))
        self.assertNotIn("response values", contents)

    def test_non_smoke_exception_text_never_reaches_report(self):
        report = api_smoke.run_smoke(
            "local", "http://localhost", environ={"TCG_SMOKE_BUYER_EMAIL": "secret@example.test"},
            transport=lambda *_: (_ for _ in ()).throw(RuntimeError("password=sentinel-token")),
        )
        serialized = json.dumps(report)
        self.assertNotIn("sentinel-token", serialized)
        self.assertNotIn("secret@example.test", serialized)
        self.assertIn("unexpected failure (RuntimeError)", serialized)

    def test_mutating_flow_contract_idempotency_authorization_and_cleanup(self):
        listing_id = "842"
        order_id = "991"
        image_url = {"value": "https://objects.example.test/storage/v1/object/public/tcg-fixture-bucket/products/123e4567-e89b-12d3-a456-426614174000.png"}
        requests = []
        cleanup_should_fail = {"value": False}

        def response(status, value=None, headers=None, url=None):
            body = value if isinstance(value, bytes) else (b"" if value is None else json.dumps(value).encode())
            return api_smoke.Response(status, headers or {"Content-Type": "application/json"}, body, url or "")

        def transport(method, url, headers, body, _timeout):
            from urllib.parse import urlsplit

            parsed = urlsplit(url)
            route = parsed.path
            requests.append((method, route, url, dict(headers), body))
            if route == "/api/auth/login":
                credentials = json.loads(body)
                role = {"seller@example.test": "seller", "buyer@example.test": "buyer", "second@example.test": "second"}[credentials["email"]]
                return response(200, {"token": role}, url=url)
            token = headers.get("Authorization", "").removeprefix("Bearer ")
            if route == "/api/auth/me":
                ids = {"seller": "71", "buyer": "72", "second": "73"}
                return response(200, {"id": ids[token]}, url=url)
            if route == "/api/admin/users":
                return response(403 if token == "buyer" else 401, {"error": "forbidden"}, url=url)
            if route == "/api/orders/me":
                return response(200, [{"id": order_id}], url=url)
            if route == "/api/cart" and method == "GET":
                cart_items = [] if sum(req[1] == "/api/seller/products" and req[0] == "POST" for req in requests) == 0 else [
                    {"listingId": listing_id, "quantity": 1}
                ]
                return response(200, {"items": cart_items, "totalItems": len(cart_items), "totalAmount": 0}, url=url)
            if route == "/api/seller/products" and method == "POST":
                return response(201, {"id": listing_id, "image": image_url["value"]}, url=url)
            if route == "/storage/v1/object/public/tcg-fixture-bucket/products/123e4567-e89b-12d3-a456-426614174000.png":
                return response(200, b"", {"Content-Type": "image/png"}, url=url)
            if route == "/api/cart/items" and method == "POST":
                return response(201, {"items": [{"listingId": listing_id, "quantity": 1}], "totalItems": 1, "totalAmount": 1}, url=url)
            if route == f"/api/cart/items/{listing_id}" and method == "PATCH":
                return response(200, {"items": [{"listingId": listing_id, "quantity": 1}], "totalItems": 1, "totalAmount": 1}, url=url)
            if route == "/api/orders" and method == "POST":
                return response(201, {"id": order_id, "items": [{"listingId": listing_id}]}, url=url)
            if route == f"/api/orders/{order_id}" and method == "GET":
                return response(200 if token == "buyer" else 404, {"id": order_id, "items": [{"productId": listing_id}]}, url=url)
            if route == "/api/seller/products" and method == "GET":
                return response(200, [{"id": listing_id, "stock": 1}], url=url)
            if route == f"/api/seller/products/{listing_id}/status" and method == "PATCH":
                return response(500 if cleanup_should_fail["value"] else 204, {"error": "cleanup failed"}, url=url)
            raise AssertionError(f"unexpected mocked request {method} {route}")

        policy = {
            "schemaVersion": 1,
            "environment": "staging",
            "apiOrigin": "https://api.example.test",
            "databaseIdentity": "staging-db-fixture",
            "fixtureMarker": "fixture-only",
            "testDatasetIdentity": "dedicated-dataset",
            "approvedSellerId": "71",
            "approvedBuyerId": "72",
            "secondBuyerId": "73",
            "authorizationChecks": True,
            "storage": {
                "host": "objects.example.test", "bucket": "tcg-fixture-bucket",
                "fixturePrefix": "products/",
                "publicBaseUrl": "https://objects.example.test/storage/v1/object/public/tcg-fixture-bucket",
            },
        }
        environ = {
            "TCG_SMOKE_DATABASE_IDENTITY": "staging-db-fixture",
            "TCG_SMOKE_FIXTURE_MARKER": "fixture-only",
            "TCG_SMOKE_TEST_DATASET_IDENTITY": "dedicated-dataset",
            "TCG_SMOKE_STORAGE_HOST": "objects.example.test",
            "TCG_SMOKE_STORAGE_BUCKET": "tcg-fixture-bucket",
            "TCG_SMOKE_FIXTURE_PREFIX": "products/",
            "TCG_SMOKE_STORAGE_PUBLIC_BASE_URL": "https://objects.example.test/storage/v1/object/public/tcg-fixture-bucket",
            "TCG_SMOKE_APPROVED_SELLER_ID": "71",
            "TCG_SMOKE_APPROVED_BUYER_ID": "72",
            "TCG_SMOKE_SECOND_BUYER_ID": "73",
            "TCG_SMOKE_SELLER_EMAIL": "seller@example.test",
            "TCG_SMOKE_SELLER_PASSWORD": "seller-password-sentinel",
            "TCG_SMOKE_BUYER_EMAIL": "buyer@example.test",
            "TCG_SMOKE_BUYER_PASSWORD": "buyer-password-sentinel",
            "TCG_SMOKE_SECOND_BUYER_EMAIL": "second@example.test",
            "TCG_SMOKE_SECOND_BUYER_PASSWORD": "second-password-sentinel",
        }
        with tempfile.TemporaryDirectory() as directory:
            policy_path = Path(directory) / "fixture-policy.json"
            policy_path.write_text(json.dumps(policy), encoding="utf-8")
            report = api_smoke.run_smoke(
                "staging", "https://api.example.test", fixture_policy=policy_path,
                mutating=True, transport=transport, environ=environ,
            )

            self.assertEqual("passed", report["status"], report)
            self.assertEqual("passed", next(check["status"] for check in report["checks"] if check["name"] == "cleanup-hide-test-listing"))
            cart_read = next(i for i, req in enumerate(requests) if req[0] == "GET" and req[1] == "/api/cart")
            listing_create = next(i for i, req in enumerate(requests) if req[0] == "POST" and req[1] == "/api/seller/products")
            self.assertLess(cart_read, listing_create)
            checkout_requests = [req for req in requests if req[0] == "POST" and req[1] == "/api/orders"]
            self.assertEqual(2, len(checkout_requests))
            self.assertEqual(checkout_requests[0][3]["Idempotency-Key"], checkout_requests[1][3]["Idempotency-Key"])
            self.assertEqual(checkout_requests[0][4], checkout_requests[1][4])
            self.assertEqual("PATCH", requests[-1][0])
            self.assertEqual("/api/seller/products/842/status", requests[-1][1])
            self.assertNotIn("password-sentinel", json.dumps(report))
            requests.clear()
            cleanup_should_fail["value"] = True
            failed_cleanup = api_smoke.run_smoke(
                "staging", "https://api.example.test", fixture_policy=policy_path,
                mutating=True, transport=transport, environ=environ,
            )
        self.assertEqual("failed", failed_cleanup["status"])
        self.assertEqual("passed", next(check["status"] for check in failed_cleanup["checks"] if check["name"] == "staging-mutation-flow"))
        cleanup_result = next(check for check in failed_cleanup["checks"] if check["name"] == "cleanup-hide-test-listing")
        self.assertEqual("failed", cleanup_result["status"])
        self.assertNotIn("cleanup failed", json.dumps(failed_cleanup))
        requests.clear()
        cleanup_should_fail["value"] = False
        image_url["value"] = "https://objects.example.test/evil/storage/v1/object/public/tcg-fixture-bucket/products/123e4567-e89b-12d3-a456-426614174000.png"
        with tempfile.TemporaryDirectory() as malicious_directory:
            malicious_policy_path = Path(malicious_directory) / "fixture-policy.json"
            malicious_policy_path.write_text(json.dumps(policy), encoding="utf-8")
            malicious_prefix = api_smoke.run_smoke(
                "staging", "https://api.example.test", fixture_policy=malicious_policy_path,
                mutating=True, transport=transport, environ=environ,
            )
        self.assertEqual("failed", malicious_prefix["status"])
        flow_result = next(check for check in malicious_prefix["checks"] if check["name"] == "staging-mutation-flow")
        self.assertEqual("failed", flow_result["status"])
        self.assertEqual("uploaded object URL was outside the approved public base path", flow_result["error"])
        self.assertEqual("passed", next(check["status"] for check in malicious_prefix["checks"] if check["name"] == "cleanup-hide-test-listing"))
        self.assertFalse(any(req[1] == "/api/orders" and req[0] == "POST" for req in requests))


if __name__ == "__main__":
    unittest.main()
