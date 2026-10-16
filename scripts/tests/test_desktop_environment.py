"""Local Desktop configuration and secret-boundary regression checks."""
import importlib.util
import json
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("desktop_env", ROOT / "scripts/initialize-desktop-env.py")
ENV = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(ENV)


def test_initializer_creates_complete_local_values(tmp_path, monkeypatch, capsys):
    monkeypatch.setattr(ENV, "ROOT", tmp_path)
    assert ENV.main() == 0
    output = capsys.readouterr().out
    report = json.loads(output)
    values = ENV.read_environment(tmp_path / ".env")
    assert report["created"] is True
    assert report["localMissing"] == []
    assert set(report["cloudMissing"]) == set(ENV.CLOUD_REQUIRED) - {"APP_JWT_SECRET"}
    for name in ENV.LOCAL_REQUIRED:
        assert len(values[name]) >= 30
        assert values[name] not in output


def test_initializer_preserves_existing_file(tmp_path, monkeypatch, capsys):
    monkeypatch.setattr(ENV, "ROOT", tmp_path)
    path = tmp_path / ".env"
    path.write_text("# protected existing values\nPOSTGRES_PASSWORD=existing-value\n", encoding="utf-8")
    before = path.read_bytes()
    assert ENV.main() == 1
    assert path.read_bytes() == before
    report = json.loads(capsys.readouterr().out)
    assert report["created"] is False
    assert "LOCAL_APP_JWT_SECRET" in report["localMissing"]


def test_missing_rejects_placeholders_and_empty_values():
    assert ENV.missing({"a": "", "b": "replace-with-secret", "c": "<secret>", "d": "valid"},
                       ("a", "b", "c", "d", "e")) == ["a", "b", "c", "e"]


def test_compose_profiles_are_isolated():
    config = yaml.safe_load((ROOT / "docker-compose.desktop.yml").read_text(encoding="utf-8"))
    services = config["services"]
    assert config["name"] == "tcg-store-service"
    assert set(services) == {"postgres-local", "s3mock", "migrations-local", "seed-local",
                            "backend-local", "migrations-cloud", "backend-cloud", "frontend"}
    for service in services.values():
        assert all(port.startswith("127.0.0.1:") for port in service.get("ports", []))
    backend = services["backend-local"]
    assert "LOCAL_APP_JWT_SECRET" in backend["environment"]["APP_JWT_SECRET"]
    assert "LOCAL_OBJECT_STORAGE_ACCESS_KEY" in backend["environment"]["OBJECT_STORAGE_ACCESS_KEY"]
    assert backend["environment"]["OBJECT_STORAGE_ENDPOINT"] == "http://s3mock:9090"
    assert backend["environment"]["DB_URL"].startswith("jdbc:postgresql://postgres-local:")
    assert "NEON_" not in json.dumps(backend)
    cloud = services["backend-cloud"]
    assert cloud["environment"]["SPRING_PROFILES_ACTIVE"] == "neon"
    assert cloud["environment"]["FLYWAY_ENABLED"] == "false"
    assert cloud["depends_on"]["migrations-cloud"]["condition"] == "service_completed_successfully"
    assert services["seed-local"]["profiles"] == ["local"]
    assert services["frontend"]["build"]["args"]["VITE_API_BASE_URL"] == "/api"


def test_frontend_build_excludes_all_environment_files():
    ignored = (ROOT / "docker/desktop/frontend.Dockerfile.dockerignore").read_text().splitlines()
    assert ".env" in ignored
    assert ".env.*" in ignored
    assert "node_modules" in ignored


def test_launcher_defaults_to_cloud_without_refreshing_credentials():
    launcher = (ROOT / "scripts/start-desktop-acceptance.ps1").read_text()
    assert "[string]$Runtime = 'cloud'" in launcher
    assert "docker-compose.desktop.yml" in launcher
    assert "setup-shared-neon" not in launcher
    assert "Get-Content" not in launcher
    assert "stop backend-local migrations-local seed-local postgres-local s3mock" in launcher
    assert launcher.index('"backend-$Runtime"\nif') < launcher.index('--wait-timeout 90 frontend')
