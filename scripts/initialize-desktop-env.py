"""Create a new local-only .env without printing generated credentials."""
import json
import secrets
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
LOCAL_REQUIRED = ("POSTGRES_PASSWORD", "LOCAL_APP_JWT_SECRET", "LOCAL_OBJECT_STORAGE_ACCESS_KEY", "LOCAL_OBJECT_STORAGE_SECRET_KEY")
CLOUD_REQUIRED = ("APP_JWT_SECRET", "NEON_JDBC_URL", "NEON_DB_USERNAME", "NEON_DB_PASSWORD", "OBJECT_STORAGE_BUCKET",
                  "OBJECT_STORAGE_PUBLIC_BASE_URL", "OBJECT_STORAGE_ACCESS_KEY", "OBJECT_STORAGE_SECRET_KEY")


def read_environment(path):
    values = {}
    if path.is_file():
        for line in path.read_text(encoding="utf-8-sig").splitlines():
            if line.strip() and not line.lstrip().startswith("#") and "=" in line:
                key, value = line.split("=", 1)
                values[key.strip()] = value.strip().strip("\"'")
    return values


def missing(values, keys):
    return [key for key in keys if not values.get(key) or "replace-with" in values[key] or "<" in values[key]]


def main():
    destination = ROOT / ".env"
    created = not destination.exists()
    if created:
        values = {
            "BACKEND_IMAGE_NAME": "tcg-store-service-backend",
            "BACKEND_IMAGE_TAG": "dev",
            "DESKTOP_FRONTEND_IMAGE": "tcg-store-service-frontend:dev",
            "FRONTEND_SOURCE_DIR": ".local/pr39-work/tcg-store-frontend",
            "FRONTEND_DOCKERFILE": "../../../docker/desktop/frontend.Dockerfile",
            "POSTGRES_DB": "tcg_acceptance",
            "POSTGRES_USER": "tcg_acceptance",
            "POSTGRES_PASSWORD": secrets.token_hex(24),
            "POSTGRES_PORT": "25432",
            "SERVER_PORT": "8080",
            "FRONTEND_PORT": "3000",
            "S3MOCK_API_PORT": "9090",
            "APP_JWT_SECRET": secrets.token_hex(32),
            "LOCAL_OBJECT_STORAGE_BUCKET": "tcg-product-images",
            "LOCAL_OBJECT_STORAGE_ACCESS_KEY": "local-" + secrets.token_hex(12),
            "LOCAL_OBJECT_STORAGE_SECRET_KEY": secrets.token_hex(24),
        }
        values["LOCAL_APP_JWT_SECRET"] = values["APP_JWT_SECRET"]
        with destination.open("x", encoding="utf-8", newline="\n") as stream:
            stream.write("# Generated local Docker Desktop environment. Never commit or print this file.\n")
            stream.write("# Cloud credentials are intentionally absent; this stack uses only fresh local services.\n")
            for key, value in values.items():
                stream.write(f"{key}={value}\n")
    values = read_environment(destination)
    result = {"created": created, "path": str(destination), "localMissing": missing(values, LOCAL_REQUIRED),
              "cloudMissing": missing(values, CLOUD_REQUIRED),
              "cloudProviderSetting": "OBJECT_STORAGE_ENDPOINT is additionally needed for a non-AWS S3-compatible provider"}
    print(json.dumps(result, indent=2))
    return 1 if result["localMissing"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
