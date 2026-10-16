import argparse
import base64
import hashlib
import hmac
import json
import struct
import os
import time
import urllib.error
import urllib.request
import uuid
import zlib

import boto3
import psycopg
from botocore.config import Config


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def png_fixture():
    def chunk(kind, data):
        payload = kind + data
        return struct.pack(">I", len(data)) + payload + struct.pack(">I", zlib.crc32(payload) & 0xFFFFFFFF)

    return (
        bytes([137, 80, 78, 71, 13, 10, 26, 10])
        + chunk(b"IHDR", struct.pack(">IIBBBBB", 1, 1, 8, 6, 0, 0, 0))
        + chunk(b"IDAT", zlib.compress(bytes([0, 0x34, 0x56, 0x78, 0xFF])))
        + chunk(b"IEND", b"")
    )


def token_for(user_id):
    secret = os.environ["TCG_ACCEPT_JWT_SECRET"].encode()
    now = int(time.time())
    encode = lambda value: base64.urlsafe_b64encode(
        json.dumps(value, separators=(",", ":")).encode()
    ).rstrip(b"=").decode()
    unsigned = encode({"alg": "HS256", "typ": "JWT"}) + "." + encode(
        {"sub": "step1-local@example.test", "userId": user_id, "role": "USER", "iat": now, "exp": now + 1800}
    )
    signature = hmac.new(secret, unsigned.encode(), hashlib.sha256).digest()
    return unsigned + "." + base64.urlsafe_b64encode(signature).rstrip(b"=").decode()


def request(base, token, method, path, body=None, content_type="application/json"):
    headers = {"Authorization": "Bearer " + token}
    if body is not None:
        headers["Content-Type"] = content_type
    req = urllib.request.Request(base + path, data=body, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=20) as response:
            return response.status, response.read()
    except urllib.error.HTTPError as error:
        # Consume the body without retaining or printing it; test evidence records only status.
        error.read()
        return error.code, b""


def multipart(fields, file_bytes):
    boundary = "----tcg-accept-" + uuid.uuid4().hex
    pieces = []
    for name, value in fields.items():
        pieces.extend([
            f"--{boundary}\r\nContent-Disposition: form-data; name=\"{name}\"\r\n\r\n".encode(),
            str(value).encode(), b"\r\n"
        ])
    pieces.extend([
        f"--{boundary}\r\nContent-Disposition: form-data; name=\"image\"; filename=\"fixture.png\"\r\nContent-Type: image/png\r\n\r\n".encode(),
        file_bytes, f"\r\n--{boundary}--\r\n".encode()
    ])
    return ("multipart/form-data; boundary=" + boundary), b"".join(pieces)


def json_request(base, token, method, path, value):
    return request(base, token, method, path, json.dumps(value).encode())


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--backend-a", required=True)
    parser.add_argument("--backend-b", required=True)
    parser.add_argument("--db-host", required=True)
    parser.add_argument("--db-port", type=int, required=True)
    parser.add_argument("--db-name", required=True)
    parser.add_argument("--db-user", required=True)
    parser.add_argument("--bucket", required=True)
    parser.add_argument("--s3-endpoint", required=True)
    parser.add_argument("--public-base", required=True)
    parser.add_argument("--restart-check", action="store_true")
    args = parser.parse_args()

    s3 = boto3.client(
        "s3", endpoint_url=args.s3_endpoint, region_name="us-east-1",
        aws_access_key_id=os.environ["TCG_ACCEPT_S3_ACCESS_KEY"],
        aws_secret_access_key=os.environ["TCG_ACCEPT_S3_SECRET_KEY"],
        config=Config(s3={"addressing_style": "path"}, retries={"max_attempts": 2})
    )
    db = psycopg.connect(
        host=args.db_host, port=args.db_port, dbname=args.db_name,
        user=args.db_user, password=os.environ["TCG_ACCEPT_DB_PASSWORD"], connect_timeout=10
    )
    try:
        if args.restart_check:
            with db.cursor() as cur:
                cur.execute("SELECT id FROM users WHERE email = %s", ("step1-local@example.test",))
                user_row = cur.fetchone()
            require(user_row is not None, "synthetic seller row disappeared after object-store restart")
            # The surviving row URL is evidence of the key; it is never printed.
            with db.cursor() as cur:
                cur.execute("SELECT custom_image_url FROM local_cards WHERE custom_image_url LIKE %s LIMIT 1", (args.public_base + "/products/%",))
                row = cur.fetchone()
            require(row is not None, "no retained image URL found for restart persistence check")
            key = row[0].removeprefix(args.public_base + "/")
            body = s3.get_object(Bucket=args.bucket, Key=key)["Body"].read()
            require(body == png_fixture(), "persisted object bytes changed after S3Mock and backend restarts")
            print("PASS: uploaded object remained readable by a fresh S3 client after isolated S3Mock restart.")
            return

        with db.cursor() as cur:
            cur.execute(
                "INSERT INTO users (email,password,full_name,role,seller_status,created_at,updated_at) "
                "VALUES (%s,%s,%s,%s,%s,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP) RETURNING id",
                ("step1-local@example.test", "unused-local-acceptance-password", "Step 1 Acceptance", "USER", "APPROVED")
            )
            user_id = cur.fetchone()[0]
            # Inject a deterministic failure after image upload to exercise transaction rollback cleanup.
            cur.execute("""
                CREATE FUNCTION tcg_accept_reject_probe() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.price = 12.35 THEN
                        RAISE EXCEPTION 'isolated acceptance rollback probe';
                    END IF;
                    RETURN NEW;
                END $$
            """)
            cur.execute("""
                CREATE TRIGGER tcg_accept_reject_probe BEFORE INSERT ON local_cards
                FOR EACH ROW EXECUTE FUNCTION tcg_accept_reject_probe()
            """)
        db.commit()
        token = token_for(user_id)
        base_fields = {
            "name": "S1 Acceptance Card", "game": "Pokemon", "price": "12.34",
            "cost": "2.00", "stock": "1", "status": "SHOW", "condition": "NM", "language": "EN"
        }
        fixture = png_fixture()
        content_type, body = multipart(base_fields, fixture)
        before = {item["Key"] for page in s3.get_paginator("list_objects_v2").paginate(Bucket=args.bucket) for item in page.get("Contents", [])}
        status, response = request(args.backend_a, token, "POST", "/api/seller/products", body, content_type)
        require(status == 201, f"replica A upload/create returned HTTP {status}")
        first = json.loads(response)
        product_a = int(first["id"])
        image_url = first["image"]
        require(image_url.startswith(args.public_base + "/products/"), "API response did not contain a managed image URL")
        key = image_url.removeprefix(args.public_base + "/")
        s3.head_object(Bucket=args.bucket, Key=key)
        require(s3.get_object(Bucket=args.bucket, Key=key)["Body"].read() == fixture, "S3 GET returned different bytes")
        print("PASS: replica A accepted a validated PNG, uploaded it to S3Mock, and S3 GET returned the exact fixture bytes.")

        # A second independently configured replica updates the shared object through its own application storage client.
        product_b_payload = {
            "name": "S1 Shared Reference", "game": "Pokemon", "image": image_url,
            "price": 12.34, "cost": 2.0, "stock": 1, "status": "SHOW",
            "condition": "NM", "language": "EN", "set": "Seller Listings"
        }
        status, response = json_request(args.backend_b, token, "POST", "/api/seller/products", product_b_payload)
        require(status == 201, f"replica B shared-reference create returned HTTP {status}")
        product_b = int(json.loads(response)["id"])
        status, response = request(args.backend_b, token, "GET", "/api/seller/products")
        require(status == 200, f"replica B product metadata GET returned HTTP {status}")
        rows = json.loads(response)
        require(any(int(row["id"]) == product_b and row["image"] == image_url for row in rows), "replica B metadata GET did not return the shared image URL")
        print("PASS: replica B GET returned the shared product metadata and managed image URL before deletion.")
        status, _ = request(args.backend_a, token, "DELETE", f"/api/seller/products/{product_a}")
        require(status == 204, f"replica A first delete returned HTTP {status}")
        s3.head_object(Bucket=args.bucket, Key=key)
        with db.cursor() as cur:
            cur.execute("SELECT count(*) FROM local_cards WHERE custom_image_url = %s", (image_url,))
            references = cur.fetchone()[0]
        require(references == 1, "first product delete did not preserve exactly one shared database reference")
        print("PASS: deleting one of two products preserved the shared S3 object and remaining database reference.")

        status, _ = request(args.backend_b, token, "DELETE", f"/api/seller/products/{product_b}")
        require(status == 204, f"replica B final delete returned HTTP {status}")
        try:
            s3.head_object(Bucket=args.bucket, Key=key)
            raise AssertionError("final reference delete left the S3 object present")
        except s3.exceptions.ClientError as error:
            require(error.response["Error"]["Code"] in ("404", "NoSuchKey", "NotFound"), "S3 returned an unexpected error after final delete")
        print("PASS: final product delete through replica B removed the now-unreferenced S3 object after commit.")

        before_rollback = {item["Key"] for page in s3.get_paginator("list_objects_v2").paginate(Bucket=args.bucket) for item in page.get("Contents", [])}
        rollback_fields = dict(base_fields)
        rollback_fields["name"] = "rollback-probe"
        rollback_fields["price"] = "12.35"
        rollback_type, rollback_body = multipart(rollback_fields, fixture)
        status, _ = request(args.backend_a, token, "POST", "/api/seller/products", rollback_body, rollback_type)
        require(status >= 500, f"rollback trigger request returned unexpected HTTP {status}")
        after_rollback = {item["Key"] for page in s3.get_paginator("list_objects_v2").paginate(Bucket=args.bucket) for item in page.get("Contents", [])}
        require(after_rollback == before_rollback, "rollback left a newly uploaded S3 object behind")
        print("PASS: forced Postgres insert failure rolled back the API transaction and removed its newly uploaded object.")

        invalid_fields = dict(base_fields)
        invalid_fields["name"] = "S1 Spoofed Image"
        invalid_type, invalid_body = multipart(invalid_fields, b"not a PNG image")
        status, _ = request(args.backend_a, token, "POST", "/api/seller/products", invalid_body, invalid_type)
        require(400 <= status < 500, f"spoofed image validation returned unexpected HTTP {status}")
        after_invalid = {item["Key"] for page in s3.get_paginator("list_objects_v2").paginate(Bucket=args.bucket) for item in page.get("Contents", [])}
        require(after_invalid == before_rollback, "spoofed image validation created an S3 object")
        print("PASS: spoofed image bytes were rejected before upload; S3 object inventory did not change.")

        survivor_fields = dict(base_fields)
        survivor_fields["name"] = "S1 Restart Persistence"
        survivor_type, survivor_body = multipart(survivor_fields, fixture)
        status, response = request(args.backend_a, token, "POST", "/api/seller/products", survivor_body, survivor_type)
        require(status == 201, f"restart persistence fixture create returned HTTP {status}")
        print("PASS: retained a synthetic product image to verify persistence across S3Mock restart.")
    finally:
        db.close()


if __name__ == "__main__":
    try:
        main()
    except AssertionError as error:
        print(f"FAIL: {error}")
        raise SystemExit(1)
    except Exception as error:
        print(f"FAIL: local API/S3 runtime raised {type(error).__name__}; diagnostic details suppressed.")
        raise SystemExit(1)
