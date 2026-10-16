#!/usr/bin/env python3
"""Offline-friendly API smoke checks for the TCG Store deployment."""

from __future__ import annotations

import argparse
import http.client
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable, Mapping


PNG_FIXTURE = bytes.fromhex(
    "89504e470d0a1a0a0000000d49484452000000010000000108060000001f15c489"
    "0000000b49444154789c636000020000050001a5f645400000000049454e44ae426082"
)
MAX_BODY_BYTES = 2 * 1024 * 1024


class SmokeError(Exception):
    """An expected smoke failure with a safe, reportable message."""


@dataclass
class Response:
    status: int
    headers: Mapping[str, str]
    body: bytes
    url: str

    def json(self) -> Any:
        try:
            return json.loads(self.body.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as exc:
            raise SmokeError("response was not valid JSON") from exc


class _NoCrossOriginRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, new_url):
        old = urllib.parse.urlsplit(request.full_url)
        new = urllib.parse.urlsplit(new_url)
        if (old.scheme.lower(), old.hostname, old.port) != (
            new.scheme.lower(), new.hostname, new.port
        ):
            raise SmokeError("cross-origin redirect refused")
        return super().redirect_request(request, fp, code, message, headers, new_url)


def _default_transport(
    method: str, url: str, headers: Mapping[str, str], body: bytes | None, timeout: float
) -> Response:
    request = urllib.request.Request(url, data=body, headers=dict(headers), method=method)
    opener = urllib.request.build_opener(_NoCrossOriginRedirect())
    try:
        with opener.open(request, timeout=timeout) as response:
            payload = response.read(MAX_BODY_BYTES + 1)
            if len(payload) > MAX_BODY_BYTES:
                raise SmokeError("response exceeded the 2 MiB smoke limit")
            return Response(response.status, dict(response.headers.items()), payload, response.geturl())
    except urllib.error.HTTPError as response:
        payload = response.read(MAX_BODY_BYTES + 1)
        if len(payload) > MAX_BODY_BYTES:
            payload = b""
        return Response(response.code, dict(response.headers.items()), payload, response.geturl())
    except (urllib.error.URLError, TimeoutError, OSError, http.client.HTTPException) as exc:
        raise SmokeError(f"request failed ({type(exc).__name__})") from exc


def _origin(value: str) -> str:
    parsed = urllib.parse.urlsplit(value)
    if parsed.scheme not in {"http", "https"} or not parsed.hostname:
        raise SmokeError("URL must include an http(s) scheme and host")
    if parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise SmokeError("URL must not contain credentials, query, or fragment")
    if parsed.path not in {"", "/"}:
        raise SmokeError("origin must not contain a path")
    port = f":{parsed.port}" if parsed.port else ""
    return f"{parsed.scheme.lower()}://{parsed.hostname.lower()}{port}"


def _request_origin(value: str) -> str:
    parsed = urllib.parse.urlsplit(value)
    if parsed.scheme.lower() not in {"http", "https"} or not parsed.hostname:
        raise SmokeError("request URL must include an http(s) scheme and host")
    port = parsed.port
    if port == (443 if parsed.scheme.lower() == "https" else 80):
        port = None
    return f"{parsed.scheme.lower()}://{parsed.hostname.lower()}" + (f":{port}" if port else "")


def _storage_public_base(storage: Mapping[str, Any], env: Mapping[str, str]) -> tuple[str, str]:
    """Validate the reviewed provider URL and return its origin and exact path scope."""
    public_base_url = storage.get("publicBaseUrl")
    if not isinstance(public_base_url, str) or public_base_url != _required_text(env, "TCG_SMOKE_STORAGE_PUBLIC_BASE_URL"):
        raise SmokeError("fixture policy mismatch: storage.publicBaseUrl")
    parsed = urllib.parse.urlsplit(public_base_url)
    if (parsed.scheme.lower() != "https" or not parsed.hostname or parsed.username or parsed.password
            or parsed.query or parsed.fragment):
        raise SmokeError("storage.publicBaseUrl must be HTTPS without credentials, query, or fragment")
    if parsed.netloc.lower() != str(storage.get("host", "")).lower():
        raise SmokeError("storage.publicBaseUrl host did not match the approved storage host")
    path = parsed.path.rstrip("/")
    if not path or "\\" in path or any(segment in {".", ".."} for segment in path.split("/")):
        raise SmokeError("storage.publicBaseUrl path must be an explicit normalized bucket scope")
    return _request_origin(public_base_url), path


def _safe_error(exc: Exception) -> str:
    """Keep reports useful without serializing arbitrary exception text."""
    if isinstance(exc, SmokeError):
        return str(exc)[:180]
    return f"unexpected failure ({type(exc).__name__})"


def _json_object(response: Response) -> dict[str, Any]:
    value = response.json()
    if not isinstance(value, dict):
        raise SmokeError("response contract expected a JSON object")
    return value


def _required_text(env: Mapping[str, str], name: str) -> str:
    value = env.get(name, "").strip()
    if not value:
        raise SmokeError(f"required environment input is missing: {name}")
    return value


def _read_policy(path: str | os.PathLike[str]) -> dict[str, Any]:
    try:
        value = json.loads(Path(path).read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise SmokeError("fixture policy could not be read as JSON") from exc
    if not isinstance(value, dict):
        raise SmokeError("fixture policy must be a JSON object")
    return value


def _validate_mutation_preflight(
    base_url: str, fixture_policy: str | os.PathLike[str] | None, env: Mapping[str, str]
) -> dict[str, Any]:
    if not fixture_policy:
        raise SmokeError("staging mutation requires --fixture-policy")
    if not base_url.startswith("https://"):
        raise SmokeError("staging mutation requires an HTTPS API origin")
    policy = _read_policy(fixture_policy)
    storage = policy.get("storage")
    if not isinstance(storage, dict):
        raise SmokeError("fixture policy storage identity is missing")
    required_policy = {
        "schemaVersion": 1,
        "environment": "staging",
        "apiOrigin": _origin(base_url),
        "databaseIdentity": _required_text(env, "TCG_SMOKE_DATABASE_IDENTITY"),
        "fixtureMarker": _required_text(env, "TCG_SMOKE_FIXTURE_MARKER"),
        "testDatasetIdentity": _required_text(env, "TCG_SMOKE_TEST_DATASET_IDENTITY"),
    }
    for key, expected in required_policy.items():
        if policy.get(key) != expected:
            raise SmokeError(f"fixture policy mismatch: {key}")
    for key, env_name in (
        ("host", "TCG_SMOKE_STORAGE_HOST"),
        ("bucket", "TCG_SMOKE_STORAGE_BUCKET"),
        ("fixturePrefix", "TCG_SMOKE_FIXTURE_PREFIX"),
    ):
        if storage.get(key) != _required_text(env, env_name):
            raise SmokeError(f"fixture policy mismatch: storage.{key}")
    if not re.fullmatch(r"[A-Za-z0-9.-]+(?::[0-9]{1,5})?", storage["host"]):
        raise SmokeError("fixture storage host is not a valid host identity")
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._/-]{0,127}", storage["bucket"]):
        raise SmokeError("fixture storage bucket identity is invalid")
    prefix = storage["fixturePrefix"]
    # SellerProductImageStorage writes products/<uuid>.<extension>; requiring
    # this exact prefix makes the reviewed policy describe the API's real keyspace.
    if prefix != "products/":
        raise SmokeError("fixture storage prefix must match the API upload keyspace")
    _storage_public_base(storage, env)
    dataset_id = policy["testDatasetIdentity"]
    if not isinstance(dataset_id, str) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]{2,63}", dataset_id):
        raise SmokeError("testDatasetIdentity must be a safe unique dataset identifier")
    seller_id = _required_text(env, "TCG_SMOKE_APPROVED_SELLER_ID")
    if policy.get("approvedSellerId") != seller_id:
        raise SmokeError("fixture policy mismatch: approvedSellerId")
    if policy.get("authorizationChecks") not in {True, False}:
        raise SmokeError("fixture policy authorizationChecks must be boolean")
    seller_email = _required_text(env, "TCG_SMOKE_SELLER_EMAIL")
    seller_password = _required_text(env, "TCG_SMOKE_SELLER_PASSWORD")
    _required_text(env, "TCG_SMOKE_BUYER_EMAIL")
    _required_text(env, "TCG_SMOKE_BUYER_PASSWORD")
    if policy["authorizationChecks"]:
        _required_text(env, "TCG_SMOKE_SECOND_BUYER_EMAIL")
        _required_text(env, "TCG_SMOKE_SECOND_BUYER_PASSWORD")
        second_buyer_id = _required_text(env, "TCG_SMOKE_SECOND_BUYER_ID")
        if policy.get("secondBuyerId") != second_buyer_id:
            raise SmokeError("fixture policy mismatch: secondBuyerId")
    buyer_id = _required_text(env, "TCG_SMOKE_APPROVED_BUYER_ID")
    if policy.get("approvedBuyerId") != buyer_id:
        raise SmokeError("fixture policy mismatch: approvedBuyerId")
    # Keep credential values out of the returned policy and all report paths.
    if not seller_email or not seller_password:
        raise SmokeError("approved seller credentials are incomplete")
    return policy


def _validate_readonly_preflight(
    environment: str, env: Mapping[str, str], frontend_origin: str | None
) -> None:
    if environment == "local":
        return
    _required_text(env, "TCG_SMOKE_BUYER_EMAIL")
    _required_text(env, "TCG_SMOKE_BUYER_PASSWORD")
    object_url = _required_text(env, "TCG_SMOKE_KNOWN_OBJECT_URL")
    storage_host = _required_text(env, "TCG_SMOKE_STORAGE_HOST").lower()
    if not frontend_origin:
        raise SmokeError("cloud smoke requires --frontend-origin for the CORS read check")
    _origin(frontend_origin)
    parsed = urllib.parse.urlsplit(object_url)
    if parsed.scheme.lower() != "https" or parsed.netloc.lower() != storage_host or parsed.username or parsed.password:
        raise SmokeError("known fixture object URL must use the allowlisted HTTPS storage host")


class SmokeRunner:
    def __init__(
        self,
        environment: str,
        base_url: str,
        *,
        frontend_origin: str | None,
        fixture_policy: str | os.PathLike[str] | None,
        mutating: bool,
        transport: Callable[[str, str, Mapping[str, str], bytes | None, float], Response],
        environ: Mapping[str, str],
    ) -> None:
        self.environment = environment
        self.base_url = _origin(base_url)
        self.frontend_origin = _origin(frontend_origin) if frontend_origin else None
        self.fixture_policy = fixture_policy
        self.mutating = mutating
        self.transport = transport
        self.environ = environ
        self.run_id = uuid.uuid4().hex[:16]
        self.checks: list[dict[str, Any]] = []
        self.tokens: dict[str, str] = {}
        self.created_listing: str | None = None
        self.cleanup_needed = False

    def request(
        self,
        method: str,
        path_or_url: str,
        *,
        token: str | None = None,
        body: bytes | None = None,
        content_type: str | None = None,
        extra_headers: Mapping[str, str] | None = None,
    ) -> Response:
        parsed_target = urllib.parse.urlsplit(path_or_url)
        url = path_or_url if parsed_target.scheme in {"http", "https"} else urllib.parse.urljoin(self.base_url + "/", path_or_url.lstrip("/"))
        headers = {"Accept": "application/json"}
        if token:
            headers["Authorization"] = f"Bearer {token}"
        if content_type:
            headers["Content-Type"] = content_type
        headers.update(extra_headers or {})
        if token and _request_origin(url) != _request_origin(self.base_url):
            raise SmokeError("authenticated requests must stay on the approved API origin")
        response = self.transport(method, url, headers, body, 15.0)
        requested = urllib.parse.urlsplit(url)
        final = urllib.parse.urlsplit(response.url)
        if token and _request_origin(response.url) != _request_origin(url):
            raise SmokeError("authenticated request redirected across origins")
        return response

    def check(self, name: str, operation: Callable[[], None], status: str = "passed") -> None:
        start = time.monotonic()
        result = status
        try:
            operation()
        except Exception as exc:  # report safe messages only; never include bodies or inputs
            result = "failed"
            message = _safe_error(exc)
            self.checks.append({
                "name": name,
                "status": result,
                "durationMs": round((time.monotonic() - start) * 1000),
                "error": message[:180],
            })
        else:
            self.checks.append({
                "name": name,
                "status": result,
                "durationMs": round((time.monotonic() - start) * 1000),
            })

    @staticmethod
    def expect(response: Response, statuses: set[int], contract: str) -> Response:
        if response.status not in statuses:
            raise SmokeError(f"{contract}: expected HTTP {','.join(map(str, sorted(statuses)))}, got {response.status}")
        return response

    def _baseline(self) -> None:
        def health() -> None:
            obj = _json_object(self.expect(self.request("GET", "/api/health"), {200}, "API health"))
            if obj.get("status") != "UP":
                raise SmokeError("API health status was not UP")
        self.check("public-health", health)

        for endpoint, contract, rows_key in (
            ("/api/products", "products", "items"),
            ("/api/cards", "cards", "content"),
        ):
            def catalog(path: str = endpoint, label: str = contract, key: str = rows_key) -> None:
                value = self.request("GET", path)
                self.expect(value, {200}, label)
                obj = _json_object(value)
                if not isinstance(obj.get(key), list) or not isinstance(obj.get("totalElements"), int):
                    raise SmokeError(f"{label} response did not match the paged catalog contract")
            self.check(f"catalog-{contract}", catalog)

        for endpoint, name in (
            ("/api/cart", "anonymous-cart"),
            ("/api/orders/me", "anonymous-orders"),
            ("/api/admin/users", "anonymous-admin"),
        ):
            self.check(name, lambda path=endpoint: self.expect(self.request("GET", path), {401}, name))

        for endpoint, name in (
            ("/actuator/health/readiness", "public-readiness-not-routed"),
            ("/actuator/prometheus", "public-prometheus-not-routed"),
        ):
            def not_public(path: str = endpoint, label: str = name) -> None:
                response = self.request("GET", path)
                if response.status != 404:
                    raise SmokeError(f"{label}: expected public route to return HTTP 404, got {response.status}")
            self.check(name, not_public)

        if self.frontend_origin:
            def cors() -> None:
                response = self.request(
                    "OPTIONS", "/api/health", extra_headers={
                        "Origin": self.frontend_origin or "",
                        "Access-Control-Request-Method": "GET",
                        "Access-Control-Request-Headers": "authorization,content-type,idempotency-key,x-request-id",
                    },
                )
                if response.status < 200 or response.status >= 300:
                    raise SmokeError(f"CORS preflight returned HTTP {response.status}")
                allowed = next((v for k, v in response.headers.items() if k.lower() == "access-control-allow-origin"), "")
                if allowed != self.frontend_origin:
                    raise SmokeError("CORS allow-origin did not match configured frontend origin")
                allowed_headers = next((v for k, v in response.headers.items() if k.lower() == "access-control-allow-headers"), "")
                required_headers = {"authorization", "content-type", "idempotency-key", "x-request-id"}
                if not required_headers.issubset({value.strip().lower() for value in allowed_headers.split(",")}):
                    raise SmokeError("CORS did not allow all required request headers")
                exposed_headers = next((v for k, v in response.headers.items() if k.lower() == "access-control-expose-headers"), "")
                if "x-request-id" not in {value.strip().lower() for value in exposed_headers.split(",")}:
                    raise SmokeError("CORS did not expose X-Request-ID")
            self.check("cors-preflight", cors)

        email = self.environ.get("TCG_SMOKE_BUYER_EMAIL", "")
        password = self.environ.get("TCG_SMOKE_BUYER_PASSWORD", "")
        if email and password:
            self.check("buyer-login-and-me", lambda: self._login_and_me("buyer", email, password))
            self.check("non-admin-admin-denial", lambda: self.expect(
                self.request("GET", "/api/admin/users", token=self.tokens.get("buyer")),
                {403}, "non-admin admin access",
            ))
        elif self.environment == "local":
            self.checks.append({"name": "buyer-login-and-me", "status": "skipped", "durationMs": 0})
            self.checks.append({"name": "non-admin-admin-denial", "status": "skipped", "durationMs": 0})
        else:
            self.check("buyer-login-and-me", lambda: (_ for _ in ()).throw(
                SmokeError("cloud smoke requires dedicated buyer credentials")))
            self.check("non-admin-admin-denial", lambda: (_ for _ in ()).throw(
                SmokeError("non-admin authorization check requires the dedicated buyer credentials")))

        object_url = self.environ.get("TCG_SMOKE_KNOWN_OBJECT_URL", "").strip()
        if object_url:
            def object_read() -> None:
                parsed = urllib.parse.urlsplit(object_url)
                if self.environment != "local" and parsed.scheme != "https":
                    raise SmokeError("cloud known object URL must use HTTPS")
                allowed_host = self.environ.get("TCG_SMOKE_STORAGE_HOST", "").strip().lower()
                if allowed_host and (parsed.netloc or "").lower() != allowed_host:
                    raise SmokeError("known object URL host did not match the approved storage host")
                if self.environment != "local" and not allowed_host:
                    raise SmokeError("cloud known object check requires TCG_SMOKE_STORAGE_HOST")
                response = self.request("GET", object_url)
                self.expect(response, {200}, "known fixture object")
                ctype = next((v for k, v in response.headers.items() if k.lower() == "content-type"), "")
                if not ctype.lower().startswith("image/"):
                    raise SmokeError("known fixture object was not served as an image")
            self.check("known-object-read", object_read)
        elif self.environment == "local":
            self.checks.append({"name": "known-object-read", "status": "skipped", "durationMs": 0})
        else:
            self.check("known-object-read", lambda: (_ for _ in ()).throw(
                SmokeError("cloud smoke requires TCG_SMOKE_KNOWN_OBJECT_URL")))

    def _login(self, role: str, email: str, password: str) -> str:
        payload = json.dumps({"email": email, "password": password}).encode("utf-8")
        response = self.expect(self.request("POST", "/api/auth/login", body=payload, content_type="application/json"), {200}, f"{role} login")
        obj = _json_object(response)
        token = obj.get("token")
        if not isinstance(token, str) or not token:
            raise SmokeError(f"{role} login did not return a token")
        return token

    def _login_and_me(self, role: str, email: str, password: str) -> None:
        token = self._login(role, email, password)
        self.tokens[role] = token
        self.expect(self.request("GET", "/api/auth/me", token=token), {200}, f"{role} /me")

    @staticmethod
    def _cart(response: Response, contract: str) -> dict[str, Any]:
        cart = _json_object(response)
        if not isinstance(cart.get("items"), list) or not isinstance(cart.get("totalItems"), int):
            raise SmokeError(f"{contract} did not match the cart response contract")
        if not isinstance(cart.get("totalAmount"), (int, float)):
            raise SmokeError(f"{contract} did not include the numeric totalAmount")
        return cart

    def _mutating_flow(self, policy: dict[str, Any]) -> None:
        env = self.environ
        seller_token = self._login("seller", env["TCG_SMOKE_SELLER_EMAIL"], env["TCG_SMOKE_SELLER_PASSWORD"])
        buyer_token = self._login("buyer", env["TCG_SMOKE_BUYER_EMAIL"], env["TCG_SMOKE_BUYER_PASSWORD"])
        seller = _json_object(self.expect(self.request("GET", "/api/auth/me", token=seller_token), {200}, "seller /me"))
        buyer = _json_object(self.expect(self.request("GET", "/api/auth/me", token=buyer_token), {200}, "buyer /me"))
        self.tokens["seller"] = seller_token
        self.tokens["buyer"] = buyer_token
        if str(seller.get("id", seller.get("userId", ""))) != str(policy["approvedSellerId"]):
            raise SmokeError("authenticated seller did not match approvedSellerId")
        if str(buyer.get("id", buyer.get("userId", ""))) != str(policy["approvedBuyerId"]):
            raise SmokeError("authenticated buyer did not match approvedBuyerId")
        self.expect(self.request("GET", "/api/admin/users", token=buyer_token), {403}, "non-admin admin access")
        initial_cart = self._cart(self.expect(self.request("GET", "/api/cart", token=buyer_token), {200}, "initial cart read"), "initial cart read")
        if initial_cart.get("items") != [] or initial_cart.get("totalItems") != 0:
            raise SmokeError("dedicated buyer cart must be empty before the smoke run")

        listing_name = f"SMOKE-{self.run_id}"
        boundary = f"tcg-smoke-{self.run_id}"
        fields = {
            "name": listing_name, "game": "Pokemon", "price": "1.00", "cost": "0.00",
            "stock": "2", "status": "SHOW", "condition": "Near Mint", "language": "EN",
        }
        body = bytearray()
        for key, value in fields.items():
            body.extend(f"--{boundary}\r\nContent-Disposition: form-data; name=\"{key}\"\r\n\r\n{value}\r\n".encode())
        body.extend(f"--{boundary}\r\nContent-Disposition: form-data; name=\"image\"; filename=\"fixture.png\"\r\nContent-Type: image/png\r\n\r\n".encode())
        body.extend(PNG_FIXTURE)
        body.extend(f"\r\n--{boundary}--\r\n".encode())
        created = _json_object(self.expect(self.request(
            "POST", "/api/seller/products", token=seller_token, body=bytes(body),
            content_type=f"multipart/form-data; boundary={boundary}",
        ), {201}, "seller listing create"))
        listing_id = str(created.get("id", ""))
        if not listing_id.isdigit() or int(listing_id) <= 0:
            raise SmokeError("created listing did not return a positive numeric id")
        self.created_listing = listing_id
        self.cleanup_needed = True
        image_url = next((created.get(key) for key in ("image", "imageLarge", "imageSmall") if isinstance(created.get(key), str) and created.get(key)), None)
        if not image_url:
            raise SmokeError("created listing did not return an uploaded image URL")
        public_origin, public_path = _storage_public_base(policy["storage"], env)
        image_parts = urllib.parse.urlsplit(image_url)
        if (image_parts.scheme.lower() != "https" or image_parts.username or image_parts.password
                or image_parts.query or image_parts.fragment or _request_origin(image_url) != public_origin):
            raise SmokeError("uploaded object URL did not match the approved HTTPS public base URL")
        scoped_path = public_path + "/"
        if not image_parts.path.startswith(scoped_path):
            raise SmokeError("uploaded object URL was outside the approved public base path")
        scoped_key = urllib.parse.unquote(image_parts.path[len(scoped_path):])
        if not re.fullmatch(r"products/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.(?:png|jpg|webp)", scoped_key):
            raise SmokeError("uploaded object key was outside the approved fixture prefix")
        object_response = self.expect(self.request("GET", image_url), {200}, "uploaded fixture object")
        object_type = next((v for k, v in object_response.headers.items() if k.lower() == "content-type"), "")
        if not object_type.lower().startswith("image/"):
            raise SmokeError("uploaded fixture object was not served as an image")

        add_response = self.expect(self.request("POST", "/api/cart/items", token=buyer_token,
            body=json.dumps({"listingId": listing_id, "quantity": 1}).encode(), content_type="application/json"), {201}, "cart add")
        self._cart(add_response, "cart add")
        update_response = self.expect(self.request("PATCH", f"/api/cart/items/{listing_id}", token=buyer_token,
            body=b'{"quantity":1}', content_type="application/json"), {200}, "cart update")
        self._cart(update_response, "cart update")
        cart = self._cart(self.expect(self.request("GET", "/api/cart", token=buyer_token), {200}, "cart read"), "cart read")
        items = cart.get("items")
        if not isinstance(items, list) or not any(str(i.get("listingId")) == listing_id and i.get("quantity") == 1 for i in items if isinstance(i, dict)):
            raise SmokeError("cart read did not contain the test listing at quantity one")

        idempotency_key = f"tcg-smoke-{policy['testDatasetIdentity']}-{self.run_id}"
        checkout = self.expect(self.request("POST", "/api/orders", token=buyer_token,
            body=b"{}", content_type="application/json", extra_headers={"Idempotency-Key": idempotency_key}), {201}, "checkout")
        checkout_obj = _json_object(checkout)
        order_id = str(checkout_obj.get("id", ""))
        if not order_id:
            raise SmokeError("checkout did not return an order id")
        retry_obj = _json_object(self.expect(self.request("POST", "/api/orders", token=buyer_token,
            body=b"{}", content_type="application/json", extra_headers={"Idempotency-Key": idempotency_key}), {201}, "idempotent checkout retry"))
        if str(retry_obj.get("id")) != order_id:
            raise SmokeError("checkout retry returned a different order")
        buyer_orders_value = self.expect(self.request("GET", "/api/orders/me", token=buyer_token), {200}, "buyer orders readback").json()
        if not isinstance(buyer_orders_value, list) or not any(
            isinstance(item, dict) and str(item.get("id")) == order_id for item in buyer_orders_value
        ):
            raise SmokeError("buyer orders did not contain the checkout order")
        order = _json_object(self.expect(self.request("GET", f"/api/orders/{urllib.parse.quote(order_id, safe='')}", token=buyer_token), {200}, "order readback"))
        order_items = order.get("items")
        if not isinstance(order_items, list) or sum(
            1 for item in order_items if isinstance(item, dict) and (
                str(item.get("productId", "")) == listing_id
                or str((item.get("listing") or {}).get("id", "")) == listing_id
            )
        ) != 1:
            raise SmokeError("order did not contain exactly one test listing item")
        if policy["authorizationChecks"]:
            second_token = self._login("second buyer", env["TCG_SMOKE_SECOND_BUYER_EMAIL"], env["TCG_SMOKE_SECOND_BUYER_PASSWORD"])
            second_buyer = _json_object(self.expect(self.request("GET", "/api/auth/me", token=second_token), {200}, "second buyer /me"))
            if str(second_buyer.get("id", second_buyer.get("userId", ""))) != str(policy["secondBuyerId"]):
                raise SmokeError("authenticated second buyer did not match secondBuyerId")
            self.expect(self.request("GET", f"/api/orders/{urllib.parse.quote(order_id, safe='')}", token=second_token), {403, 404}, "cross-buyer order access")

        inventory_response = self.expect(self.request("GET", "/api/seller/products?search=" + urllib.parse.quote(listing_name), token=seller_token), {200}, "seller inventory readback")
        inventory_value = inventory_response.json()
        if isinstance(inventory_value, dict):
            inventory_rows = inventory_value.get("items", [])
        elif isinstance(inventory_value, list):
            inventory_rows = inventory_value
        else:
            inventory_rows = []
        listing_row = next((row for row in inventory_rows if isinstance(row, dict) and str(row.get("id")) == listing_id), None)
        if listing_row is None or listing_row.get("stock") != 1:
            raise SmokeError("inventory did not show exactly one unit reserved after idempotent retry")

    def _cleanup_listing(self) -> None:
        if not self.created_listing:
            return
        email = self.environ.get("TCG_SMOKE_SELLER_EMAIL", "")
        password = self.environ.get("TCG_SMOKE_SELLER_PASSWORD", "")
        if not email or not password:
            raise SmokeError("cleanup could not authenticate approved seller")
        token = self.tokens.get("seller") or self._login("seller", email, password)
        self.expect(self.request("PATCH", f"/api/seller/products/{self.created_listing}/status", token=token,
            body=b'{"status":"HIDE"}', content_type="application/json"), {204}, "hide test listing cleanup")

    def run(self, preflight_policy: dict[str, Any] | None) -> dict[str, Any]:
        if self.mutating:
            try:
                self._mutating_flow(preflight_policy or {})
                self.checks.append({"name": "staging-mutation-flow", "status": "passed", "durationMs": 0})
            except Exception as exc:
                self.checks.append({"name": "staging-mutation-flow", "status": "failed", "durationMs": 0, "error": _safe_error(exc)})
            finally:
                if self.cleanup_needed:
                    self.check("cleanup-hide-test-listing", self._cleanup_listing)
        else:
            self._baseline()
        failed = any(item["status"] == "failed" for item in self.checks)
        return {
            "schemaVersion": 1,
            "environment": self.environment,
            "mode": "mutating" if self.mutating else "readonly",
            "runId": self.run_id,
            "status": "failed" if failed else "passed",
            "checks": self.checks,
            "summary": {"passed": sum(c["status"] == "passed" for c in self.checks),
                        "failed": sum(c["status"] == "failed" for c in self.checks),
                        "skipped": sum(c["status"] == "skipped" for c in self.checks)},
        }


def run_smoke(
    environment: str,
    base_url: str,
    output: str | os.PathLike[str] | None = None,
    fixture_policy: str | os.PathLike[str] | None = None,
    mutating: bool = False,
    frontend_origin: str | None = None,
    *,
    transport: Callable[[str, str, Mapping[str, str], bytes | None, float], Response] | None = None,
    environ: Mapping[str, str] | None = None,
) -> dict[str, Any]:
    env = os.environ if environ is None else environ
    effective_frontend_origin = frontend_origin or env.get("TCG_SMOKE_FRONTEND_ORIGIN")
    run_id = uuid.uuid4().hex[:16]
    try:
        if environment not in {"local", "staging", "production"}:
            raise SmokeError("environment must be local, staging, or production")
        _origin(base_url)
        if environment != "local" and urllib.parse.urlsplit(base_url).scheme.lower() != "https":
            raise SmokeError("staging and production require an HTTPS API URL")
        if mutating and environment == "production":
            raise SmokeError("production mutation is forbidden")
        if mutating and environment != "staging":
            raise SmokeError("mutation is supported only in staging")
        policy = _validate_mutation_preflight(base_url, fixture_policy, env) if mutating else None
        if not mutating:
            _validate_readonly_preflight(environment, env, effective_frontend_origin)
        runner = SmokeRunner(environment, base_url, frontend_origin=effective_frontend_origin,
            fixture_policy=fixture_policy, mutating=mutating,
            transport=transport or _default_transport, environ=env)
        report = runner.run(policy)
    except Exception as exc:
        checks = [{"name": "preflight", "status": "failed", "durationMs": 0, "error": _safe_error(exc)}]
        if mutating:
            checks.append({"name": "staging-mutation-flow", "status": "failed", "durationMs": 0,
                           "error": "blocked before any request"})
        report = {"schemaVersion": 1, "environment": environment, "mode": "mutating" if mutating else "readonly",
                  "runId": run_id, "status": "failed", "checks": checks,
                  "summary": {"passed": 0, "failed": len(checks), "skipped": 0}}
    if output is not None:
        output_path = Path(output)
        output_path.parent.mkdir(parents=True, exist_ok=True)
        output_path.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return report


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--environment", required=True, choices=("local", "staging", "production"))
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--fixture-policy", type=Path)
    parser.add_argument("--frontend-origin", default=os.environ.get("TCG_SMOKE_FRONTEND_ORIGIN"))
    parser.add_argument("--mutating", action="store_true", help="enable guarded staging fixture flow")
    args = parser.parse_args(argv)
    report = run_smoke(args.environment, args.base_url, args.output, args.fixture_policy,
                       args.mutating, args.frontend_origin)
    print(json.dumps({"status": report["status"], "mode": report["mode"], "runId": report["runId"], "summary": report["summary"]}))
    return 0 if report["status"] == "passed" else 1


if __name__ == "__main__":
    sys.exit(main())
