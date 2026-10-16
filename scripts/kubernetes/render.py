#!/usr/bin/env python3
"""Render and validate Kubernetes release bundles without reading Secret values."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path
from urllib.parse import urlparse

import yaml

ROOT = Path(__file__).resolve().parents[2]
ENVIRONMENTS = {"local": "tcg-store-local", "staging": "tcg-store-staging", "production": "tcg-store-production"}
CONFIG_KEYS = {
    "image", "api_host", "frontend_origin", "object_storage_endpoint", "object_storage_region",
    "object_storage_bucket", "object_storage_public_base_url", "object_storage_path_style",
    "ingress_class", "tls_issuer",
}
FORBIDDEN_CONFIG_KEY = re.compile(r"(secret|password|credential|token|api[_-]?key|access[_-]?key|jdbc|database[_-]?url|connection[_-]?string)", re.I)
IMAGE_RE = re.compile(r"^[a-zA-Z0-9][a-zA-Z0-9._:/-]*@sha256:[a-fA-F0-9]{64}$")
PLACEHOLDERS = re.compile(r"(\.invalid\b|REPLACE_ME|replace-with-|example\.com|changeme)", re.I)
API_SECRET_KEYS = {"neon-jdbc-url", "neon-db-username", "neon-db-password", "app-jwt-secret", "object-storage-access-key", "object-storage-secret-key"}
MIGRATION_SECRET_KEYS = {"neon-migration-jdbc-url", "neon-migration-db-username", "neon-migration-db-password"}
RELEASE_MANAGER = "tcg-release"
BOOTSTRAP_OR_EPHEMERAL_KINDS = {"Namespace", "ServiceAccount", "Job"}


class ValidationError(ValueError):
    pass


def fail(message: str) -> None:
    raise ValidationError(message)


def load_config(path: Path | None, allow_template: bool) -> dict:
    if path is None:
        if allow_template:
            return {"image": "ghcr.io/example/tcg-store-backend@sha256:" + "0" * 64,
                    "api_host": "api.example.invalid", "frontend_origin": "https://shop.example.invalid",
                    "object_storage_endpoint": "https://storage.example.invalid", "object_storage_region": "test-1",
                    "object_storage_bucket": "template", "object_storage_public_base_url": "https://cdn.example.invalid/template",
                    "object_storage_path_style": True, "ingress_class": "traefik", "tls_issuer": "template-issuer"}
        return {}
    try:
        config = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        fail("release config must be readable JSON")
    if not isinstance(config, dict):
        fail("release config must be a JSON object")
    def reject_credentials(value: object) -> None:
        if isinstance(value, dict):
            for key, child in value.items():
                if FORBIDDEN_CONFIG_KEY.search(str(key)):
                    fail("release config contains a forbidden credential/JDBC field")
                reject_credentials(child)
        elif isinstance(value, list):
            for child in value:
                reject_credentials(child)
        elif isinstance(value, str) and re.search(r"jdbc:|password\s*=|secret\s*=", value, re.I):
            fail("release config contains a credential or JDBC value")
    reject_credentials(config)
    extra, missing = set(config) - CONFIG_KEYS, CONFIG_KEYS - set(config)
    if extra:
        fail("release config contains unknown fields")
    if missing:
        fail("release config is missing required fields")
    return config


def _https_url(value: object, field: str, *, origin: bool = False) -> str:
    if not isinstance(value, str):
        fail(f"{field} must be a URL string")
    parsed = urlparse(value)
    allowed_path = parsed.path in ("", "/") if origin else True
    if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment or not allowed_path:
        fail(f"{field} must be an HTTPS URL without credentials, query, fragment, or an origin path")
    return value.rstrip("/")


def validate_config(config: dict, environment: str, allow_template: bool) -> None:
    def reject_credentials(value: object) -> None:
        if isinstance(value, dict):
            for key, child in value.items():
                if FORBIDDEN_CONFIG_KEY.search(str(key)):
                    fail("release config contains a forbidden credential/JDBC field")
                reject_credentials(child)
        elif isinstance(value, list):
            for child in value:
                reject_credentials(child)
        elif isinstance(value, str) and re.search(r"jdbc:|password\s*=|secret\s*=", value, re.I):
            fail("release config contains a credential or JDBC value")
    reject_credentials(config)
    if set(config) != CONFIG_KEYS:
        fail("release config fields do not match the non-secret contract")
    image = config.get("image")
    if not isinstance(image, str) or not IMAGE_RE.fullmatch(image):
        fail("image must be repository@sha256:<64 hex characters>")
    host = config.get("api_host")
    if not isinstance(host, str) or ":" in host or not re.fullmatch(r"(?=.{1,253}$)(?:[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\.)+[a-zA-Z]{2,63}", host):
        fail("api_host must be an exact DNS hostname, not a wildcard")
    origin = _https_url(config.get("frontend_origin"), "frontend_origin", origin=True)
    endpoint = _https_url(config.get("object_storage_endpoint"), "object_storage_endpoint")
    public = _https_url(config.get("object_storage_public_base_url"), "object_storage_public_base_url")
    for key in ("object_storage_region", "object_storage_bucket", "ingress_class", "tls_issuer"):
        if not isinstance(config.get(key), str) or not config[key].strip():
            fail(f"{key} must be a non-empty string")
    if not isinstance(config.get("object_storage_path_style"), bool):
        fail("object_storage_path_style must be a JSON boolean")
    if config.get("ingress_class") != "traefik":
        fail("the bundled edge component requires ingress_class=traefik")
    if endpoint == public:
        fail("object storage endpoint and public base URL must be distinct values")
    if not allow_template and PLACEHOLDERS.search(json.dumps(config, sort_keys=True)):
        fail("release config contains a placeholder or reserved .invalid hostname")
    if environment == "production" and not allow_template and any(".test" in str(config[key]).lower() for key in ("api_host", "frontend_origin", "object_storage_endpoint", "object_storage_public_base_url")):
        fail("production release config cannot use reserved .test fixture domains")


def write_yaml(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(yaml.safe_dump(value, sort_keys=False, explicit_start=True), encoding="utf-8")


def _kustomization(namespace: str, resources: list[str], image: str) -> dict:
    repository, digest = image.split("@", 1)
    return {
        "apiVersion": "kustomize.config.k8s.io/v1beta1", "kind": "Kustomization", "namespace": namespace,
        "resources": resources,
        "images": [{"name": "tcg-store-backend", "newName": repository, "digest": digest}],
    }


def _set_image_digest(kustomization_path: Path, image: str) -> None:
    doc = yaml.safe_load(kustomization_path.read_text(encoding="utf-8"))
    repository, digest = image.split("@", 1)
    entries = [item for item in doc.get("images", []) if item.get("name") == "tcg-store-backend"]
    if len(entries) != 1:
        fail("overlay must contain exactly one tcg-store-backend image entry")
    entry = entries[0]
    entry["newName"] = repository
    entry.pop("newTag", None)
    entry["digest"] = digest
    kustomization_path.write_text(yaml.safe_dump(doc, sort_keys=False), encoding="utf-8")


def _patch_configmap(path: Path, config: dict) -> None:
    doc = yaml.safe_load(path.read_text(encoding="utf-8"))
    data = doc.setdefault("data", {})
    data["SPRING_PROFILES_ACTIVE"] = "neon"
    if config:
        data.update({
            "APP_CORS_ALLOWED_ORIGINS": _https_url(config["frontend_origin"], "frontend_origin", origin=True),
            "OBJECT_STORAGE_ENDPOINT": _https_url(config["object_storage_endpoint"], "object_storage_endpoint"),
            "OBJECT_STORAGE_REGION": config["object_storage_region"],
            "OBJECT_STORAGE_BUCKET": config["object_storage_bucket"],
            "OBJECT_STORAGE_PUBLIC_BASE_URL": _https_url(config["object_storage_public_base_url"], "object_storage_public_base_url"),
            "OBJECT_STORAGE_PATH_STYLE": str(config["object_storage_path_style"]).lower(),
        })
    path.write_text(yaml.safe_dump(doc, sort_keys=False), encoding="utf-8")


def _patch_release_edge(kube_root: Path, overlay: Path, config: dict) -> None:
    edge_dir = kube_root / "components" / "edge"
    ingress_files = [edge_dir / "ingress.yaml", edge_dir / "ingress-auth.yaml"]
    for ingress_path in ingress_files:
        if not ingress_path.is_file():
            fail("both shared edge Ingress resources are required")
        ingress_doc = yaml.safe_load(ingress_path.read_text(encoding="utf-8"))
        ingress_doc.setdefault("metadata", {}).setdefault("annotations", {})["cert-manager.io/issuer"] = config["tls_issuer"]
        ingress_path.write_text(yaml.safe_dump(ingress_doc, sort_keys=False), encoding="utf-8")

    # Host and namespace-specific middleware patches belong to the parent overlay.
    # Change only host values in existing inline JSON patches; preserve all targets,
    # replacements, components, and middleware annotations.
    overlay_kustomization = overlay / "kustomization.yaml"
    doc = yaml.safe_load(overlay_kustomization.read_text(encoding="utf-8"))
    for entry in doc.get("patches", []):
        patch_text = entry.get("patch")
        if not isinstance(patch_text, str):
            continue
        try:
            operations = yaml.safe_load(patch_text)
        except yaml.YAMLError:
            continue
        if not isinstance(operations, list):
            continue
        changed = False
        for operation in operations:
            if operation.get("path") in ("/spec/tls/0/hosts/0", "/spec/rules/0/host"):
                operation["value"] = config["api_host"]
                changed = True
        if changed:
            entry["patch"] = yaml.safe_dump(operations, sort_keys=False, default_flow_style=False).rstrip()
    overlay_kustomization.write_text(yaml.safe_dump(doc, sort_keys=False), encoding="utf-8")


def build_workspace(temp_root: Path, environment: str, config: dict, allow_template: bool) -> dict[str, Path]:
    source = ROOT / "infra" / "kubernetes"
    if not (source / "base" / "kustomization.yaml").is_file():
        fail("infra/kubernetes/base/kustomization.yaml is missing")
    copied = temp_root / "infra" / "kubernetes"
    shutil.copytree(source, copied)
    overlay = copied / "overlays" / environment
    migration = overlay / "migration"
    prerequisites = overlay / "prerequisites"
    for path in (overlay / "kustomization.yaml", migration / "kustomization.yaml", prerequisites / "kustomization.yaml"):
        if not path.is_file():
            fail(f"required {environment} Kustomize target is missing")
    for configmap in (prerequisites / "configmap.yaml",):
        if not configmap.is_file():
            fail("environment prerequisite ConfigMap is missing")
        _patch_configmap(configmap, config)
    if config:
        for path in (overlay / "kustomization.yaml", migration / "kustomization.yaml"):
            _set_image_digest(path, config["image"])
        _patch_release_edge(copied, overlay, config)
    return {"application": overlay, "migration": migration, "prerequisites": prerequisites}


def render(kustomize_dir: Path) -> list[dict]:
    command = shutil.which("kubectl")
    if not command:
        fail("kubectl is required to render Kustomize overlays")
    result = subprocess.run([command, "kustomize", str(kustomize_dir)], capture_output=True, text=True, check=False)
    if result.returncode:
        fail("kubectl kustomize failed to render a target")
    try:
        return [item for item in yaml.safe_load_all(result.stdout) if isinstance(item, dict)]
    except yaml.YAMLError:
        fail("kubectl kustomize produced invalid YAML")


def _all_values(value: object):
    if isinstance(value, dict):
        for key, item in value.items():
            yield key, item
            yield from _all_values(item)
    elif isinstance(value, list):
        for item in value:
            yield from _all_values(item)


def _resource_ids(documents: list[dict]) -> set[tuple[str, str]]:
    return {(doc.get("kind", ""), doc.get("metadata", {}).get("name", "")) for doc in documents}


def label_release_resources(manifests: dict[str, list[dict]]) -> None:
    """Identify only the exact namespaced resources managed by release recovery.

    This label alone never authorizes pruning: recovery must also compare the
    verified prior/current inventory and inspect ownership on the live object.
    Bootstrap identities and unique migration Jobs are not rollback inventory.
    """
    for documents in manifests.values():
        for document in documents:
            if document.get("kind") not in BOOTSTRAP_OR_EPHEMERAL_KINDS:
                document.setdefault("metadata", {}).setdefault("labels", {})["app.kubernetes.io/managed-by"] = RELEASE_MANAGER


def _approved_network_policies() -> dict[str, dict]:
    component = ROOT / "infra/kubernetes/components/network"
    kustomization = yaml.safe_load((component / "kustomization.yaml").read_text(encoding="utf-8"))
    approved = {}
    for resource in kustomization.get("resources", []):
        for doc in yaml.safe_load_all((component / resource).read_text(encoding="utf-8")):
            if isinstance(doc, dict) and doc.get("kind") == "NetworkPolicy":
                approved[doc.get("metadata", {}).get("name", "")] = doc.get("spec")
    return approved


def validate_manifests(manifests: dict[str, list[dict]], environment: str, config: dict | None, allow_template: bool = False, bundle_version: int = 2) -> None:
    api, migration, prerequisites = (manifests[name] for name in ("application", "migration", "prerequisites"))
    approved_network = _approved_network_policies()
    if bundle_version == 1:
        approved_network.pop("tcg-store-backend-monitoring-ingress", None)
    for docs in manifests.values():
        if len(_resource_ids(docs)) != len(docs):
            fail("bundle contains duplicate resource identities")
        for doc in docs:
            if bundle_version == 2 and doc.get("kind") not in BOOTSTRAP_OR_EPHEMERAL_KINDS:
                if doc.get("metadata", {}).get("labels", {}).get("app.kubernetes.io/managed-by") != RELEASE_MANAGER:
                    fail("release-managed resource ownership label is missing or invalid")
            if doc.get("kind") == "Secret":
                fail("rendered output contains a Secret resource")
            if doc.get("kind") == "Service" and doc.get("spec", {}).get("type", "ClusterIP") != "ClusterIP":
                fail("all Services must be ClusterIP")
            for key, value in _all_values(doc):
                if isinstance(value, str) and PLACEHOLDERS.search(value) and not allow_template:
                    fail("rendered output contains a placeholder")
    expected_application = {
        ("ServiceAccount", "tcg-store-backend"), ("Service", "tcg-store-backend"), ("Deployment", "tcg-store-backend"),
    }
    expected_prerequisites = {("Namespace", ENVIRONMENTS[environment]), ("ConfigMap", "tcg-store-backend-config")}
    if environment == "local":
        expected_migration = {("Job", "tcg-store-backend-migration")}
    else:
        expected_application.update({("PodDisruptionBudget", "tcg-store-backend"), ("HorizontalPodAutoscaler", "tcg-store-backend")})
        expected_application.update({("Ingress", "tcg-store-backend"), ("Ingress", "tcg-store-backend-auth")})
        expected_application.update({("Middleware", name) for name in ("tcg-body-buffer", "tcg-auth-rate-limit", "tcg-api-rate-limit", "tcg-edge-chain", "tcg-auth-chain")})
        expected_application.add(("ServersTransport", "tcg-backend-transport"))
        expected_prerequisites.update({("NetworkPolicy", name) for name in approved_network})
        expected_migration = {("Job", "tcg-store-backend-migration")}
    if _resource_ids(api) != expected_application or _resource_ids(migration) != expected_migration or _resource_ids(prerequisites) != expected_prerequisites:
        fail("bundle contains a missing or unapproved Kubernetes resource")
    deploys = [d for d in api if d.get("kind") == "Deployment" and d.get("metadata", {}).get("name") == "tcg-store-backend"]
    jobs = [d for d in migration if d.get("kind") == "Job" and d.get("metadata", {}).get("name") == "tcg-store-backend-migration"]
    if len(deploys) != 1 or len(jobs) != 1:
        fail("API Deployment and migration Job are required")
    def images(workload):
        return [container.get("image", "") for container in workload["spec"]["template"]["spec"].get("containers", [])]
    api_images, migration_images = images(deploys[0]), images(jobs[0])
    if len(api_images) != 1 or len(migration_images) != 1 or api_images[0] != migration_images[0]:
        fail("API and migration must use the same image")
    if environment != "local" and (not config or api_images[0] != config["image"] or not IMAGE_RE.fullmatch(api_images[0])):
        fail("cloud API and migration must use the configured immutable image digest")
    expected_namespace = ENVIRONMENTS[environment]
    for docs in manifests.values():
        for doc in docs:
            if doc.get("kind") != "Namespace" and doc.get("metadata", {}).get("namespace") != expected_namespace:
                fail("rendered resource has a missing or incorrect environment namespace")
    configmaps = [d for d in prerequisites if d.get("kind") == "ConfigMap"]
    if len(configmaps) != 1 or not isinstance(configmaps[0].get("data"), dict):
        fail("exactly one non-secret ConfigMap is required")
    config_data = configmaps[0]["data"]
    expected_configmap_keys = {
        "SPRING_PROFILES_ACTIVE", "SERVER_PORT", "SPRING_JPA_HIBERNATE_DDL_AUTO", "FLYWAY_ENABLED",
        "APP_CORS_ALLOWED_ORIGINS", "OBJECT_STORAGE_ENDPOINT", "OBJECT_STORAGE_REGION", "OBJECT_STORAGE_BUCKET",
        "OBJECT_STORAGE_PUBLIC_BASE_URL", "OBJECT_STORAGE_PATH_STYLE", "APP_LOG_LEVEL", "JPA_SHOW_SQL",
    }
    if bundle_version == 2 or "APP_ENVIRONMENT" in config_data:
        expected_configmap_keys.add("APP_ENVIRONMENT")
        if config_data.get("APP_ENVIRONMENT") != environment:
            fail("telemetry environment must match the exact release environment")
    if set(config_data) != expected_configmap_keys or any(FORBIDDEN_CONFIG_KEY.search(key) for key in config_data):
        fail("ConfigMap contains a credential/JDBC field")
    if config_data.get("SPRING_PROFILES_ACTIVE") != "neon":
        fail("all rendered environments must use the neon Spring profile")
    if environment == "local":
        if config is not None:
            fail("local rendering must use checked-in local settings without release config")
        if not config_data.get("APP_CORS_ALLOWED_ORIGINS", "").startswith("http://localhost"):
            fail("local CORS must retain its checked-in localhost origin")
        ingress = [d for d in api if d.get("kind") == "Ingress"]
        if ingress:
            fail("local target must not expose an edge Ingress")
    else:
        if config_data.get("APP_CORS_ALLOWED_ORIGINS") != config["frontend_origin"] or "*" in config_data.get("APP_CORS_ALLOWED_ORIGINS", ""):
            fail("CORS must use the configured exact HTTPS frontend origin")
        for key in ("OBJECT_STORAGE_ENDPOINT", "OBJECT_STORAGE_PUBLIC_BASE_URL"):
            if not config_data.get(key, "").startswith("https://"):
                fail("cloud object storage URLs must use HTTPS")
    ingress = [d for d in api if d.get("kind") == "Ingress"]
    if environment != "local":
        expected_ingresses = {"tcg-store-backend": "/api", "tcg-store-backend-auth": "/api/auth"}
        if len(ingress) != len(expected_ingresses) or {d.get("metadata", {}).get("name") for d in ingress} != set(expected_ingresses):
            fail("cloud edge must contain exactly the public API and authentication Ingress resources")
        for edge in ingress:
            spec = edge.get("spec", {})
            if "defaultBackend" in spec:
                fail("Ingress defaultBackend catch-all routes are forbidden")
            hosts = [r.get("host") for r in spec.get("rules", [])]
            tls = spec.get("tls", [])
            if spec.get("ingressClassName") != "traefik":
                fail("bundled edge Ingress resources require ingressClassName=traefik")
            if hosts != [config["api_host"]] or len(tls) != 1 or tls[0].get("hosts") != hosts or not tls[0].get("secretName"):
                fail("each Ingress must use TLS for exactly the configured API host")
            annotations = edge.get("metadata", {}).get("annotations", {})
            if annotations.get("cert-manager.io/issuer") != config["tls_issuer"]:
                fail("Ingress must use the configured namespaced TLS issuer")
            if annotations.get("traefik.ingress.kubernetes.io/router.entrypoints") != "websecure" or annotations.get("traefik.ingress.kubernetes.io/router.tls") != "true":
                fail("public Ingress must bind to Traefik websecure with TLS enabled")
            expected_path = expected_ingresses[edge["metadata"]["name"]]
            paths = [p for rule in spec.get("rules", []) for p in rule.get("http", {}).get("paths", [])]
            if len(paths) != 1 or paths[0].get("path") != expected_path or paths[0].get("pathType") != "Prefix":
                fail("public Ingress contains an unexpected catch-all, actuator, or application path")
            backend = paths[0].get("backend", {}).get("service", {})
            if backend.get("name") != "tcg-store-backend" or backend.get("port", {}).get("number") != 80:
                fail("public Ingress must target the API ClusterIP Service on port 80")
            expected_chain = "tcg-edge-chain" if expected_path == "/api" else "tcg-auth-chain"
            middleware_ref = annotations.get("traefik.ingress.kubernetes.io/router.middlewares", "")
            if middleware_ref != f"{expected_namespace}-{expected_chain}@kubernetescrd":
                fail("Ingress does not reference its environment-scoped Traefik middleware chain")
            if expected_path == "/api/auth" and annotations.get("traefik.ingress.kubernetes.io/router.priority") != "100":
                fail("authentication Ingress must have priority 100")
        middleware = {d["metadata"]["name"]: d.get("spec") for d in api if d.get("kind") == "Middleware"}
        if middleware.get("tcg-body-buffer", {}).get("buffering") != {"maxRequestBodyBytes": 8388608, "memRequestBodyBytes": 2097152}:
            fail("Traefik request-body buffer limits do not match the approved edge contract")
        if middleware.get("tcg-api-rate-limit", {}).get("rateLimit") != {"average": 100, "period": "1s", "burst": 50}:
            fail("API rate limit does not match the approved edge contract")
        if middleware.get("tcg-auth-rate-limit", {}).get("rateLimit") != {"average": 5, "period": "1s", "burst": 10}:
            fail("authentication rate limit does not match the approved edge contract")
        if middleware.get("tcg-edge-chain", {}).get("chain", {}).get("middlewares") != [{"name": "tcg-body-buffer"}, {"name": "tcg-api-rate-limit"}]:
            fail("API middleware chain does not match the approved edge contract")
        if middleware.get("tcg-auth-chain", {}).get("chain", {}).get("middlewares") != [{"name": "tcg-body-buffer"}, {"name": "tcg-auth-rate-limit"}]:
            fail("authentication middleware chain does not match the approved edge contract")
        server_transport = next(d for d in api if d.get("kind") == "ServersTransport")
        if server_transport.get("spec", {}).get("forwardingTimeouts") != {"dialTimeout": "10s", "responseHeaderTimeout": "30s", "idleConnTimeout": "90s"}:
            fail("backend ServersTransport does not match the approved timeout contract")
    services = [d for d in api if d.get("kind") == "Service"]
    if not services or any(s.get("spec", {}).get("type", "ClusterIP") != "ClusterIP" for s in services):
        fail("API must expose only ClusterIP Services")
    if environment != "local":
        service_annotations = services[0].get("metadata", {}).get("annotations", {})
        if service_annotations.get("traefik.ingress.kubernetes.io/service.serverstransport") != f"{expected_namespace}-tcg-backend-transport@kubernetescrd":
            fail("backend Service must reference the namespace-scoped approved ServersTransport")
    podspecs = [deploys[0]["spec"]["template"]["spec"], jobs[0]["spec"]["template"]["spec"]]
    for pod in podspecs:
        if environment != "local" and pod.get("imagePullSecrets") != [{"name": "tcg-store-ghcr-pull"}]:
            fail("cloud workloads must use the approved private GHCR pull Secret")
        if pod.get("initContainers") or pod.get("ephemeralContainers"):
            fail("workloads cannot contain unvalidated init or ephemeral containers")
        if any(pod.get(key) is True for key in ("hostNetwork", "hostPID", "hostIPC")):
            fail("workloads cannot share host namespaces or networking")
        if any("hostPath" in volume for volume in pod.get("volumes", [])):
            fail("workloads cannot mount host paths")
        if pod.get("automountServiceAccountToken") is not False or pod.get("securityContext", {}).get("runAsNonRoot") is not True:
            fail("workloads must disable service-account token mounts and run as non-root")
        for container in pod.get("containers", []):
            sec = container.get("securityContext", {})
            if sec.get("allowPrivilegeEscalation") is not False or sec.get("readOnlyRootFilesystem") is not True or "ALL" not in sec.get("capabilities", {}).get("drop", []):
                fail("containers must disable privilege escalation, use a read-only root, and drop all capabilities")
    api_refs = {item["valueFrom"]["secretKeyRef"]["key"] for item in deploys[0]["spec"]["template"]["spec"]["containers"][0].get("env", []) if "secretKeyRef" in item.get("valueFrom", {})}
    migration_refs = {item["valueFrom"]["secretKeyRef"]["key"] for item in jobs[0]["spec"]["template"]["spec"]["containers"][0].get("env", []) if "secretKeyRef" in item.get("valueFrom", {})}
    for workload in (deploys[0], jobs[0]):
        pod = workload["spec"]["template"]["spec"]
        for container in pod.get("containers", []):
            for entry in container.get("envFrom", []):
                if "secretRef" in entry:
                    fail("workloads cannot import all keys from an external Secret")
            for entry in container.get("env", []):
                if re.search(r"(PASSWORD|USERNAME|SECRET|TOKEN|ACCESS_KEY|JDBC_URL)", entry.get("name", ""), re.I):
                    secret_ref = entry.get("valueFrom", {}).get("secretKeyRef")
                    if not secret_ref:
                        fail("credential-bearing environment variables must use a named Secret key reference")
                    if secret_ref.get("name") != "tcg-store-backend-secrets":
                        fail("credential environment variables must reference the external application Secret contract")
    if environment != "local" and (api_refs != API_SECRET_KEYS or migration_refs != MIGRATION_SECRET_KEYS):
        fail("API/migration Secret key references violate the least-privilege contract")
    policies = [d for d in prerequisites if d.get("kind") == "NetworkPolicy"]
    if environment != "local" and not any(p.get("spec", {}).get("podSelector") == {} and set(p.get("spec", {}).get("policyTypes", [])) == {"Ingress", "Egress"} for p in policies):
        fail("prerequisites must include default-deny ingress and egress")
    if environment != "local":
        approved = approved_network
        actual = {doc["metadata"]["name"]: doc.get("spec") for doc in policies}
        if actual != approved:
            fail("NetworkPolicy resources differ from the approved API and migration traffic policy")


def split_bootstrap(manifests: dict[str, list[dict]]) -> dict[str, list[dict]]:
    """Derive least-privilege CI inputs from already-validated full manifests."""
    return {
        "bootstrap": [d for d in manifests["prerequisites"] if d.get("kind") == "Namespace"]
            + [d for d in manifests["application"] if d.get("kind") == "ServiceAccount"],
        "ci-prerequisites": [d for d in manifests["prerequisites"] if d.get("kind") != "Namespace"],
        "ci-application": [d for d in manifests["application"] if d.get("kind") != "ServiceAccount"],
    }


def bundle_hash(directory: Path) -> str:
    digest = hashlib.sha256()
    for name in ("application.yaml", "migration.yaml", "prerequisites.yaml"):
        digest.update(name.encode("utf-8") + b"\0")
        digest.update((directory / name).read_bytes())
    return digest.hexdigest()


def config_hash(manifests: dict[str, list[dict]]) -> str:
    config = next(d["data"] for d in manifests["prerequisites"] if d.get("kind") == "ConfigMap")
    return hashlib.sha256(json.dumps(config, sort_keys=True, separators=(",", ":")).encode("utf-8")).hexdigest()


def _render_bundle(output_dir: Path, environment: str, config: dict | None, allow_template: bool, validate: bool) -> dict:
    with tempfile.TemporaryDirectory(prefix="tcg-k8s-render-") as temporary:
        targets = build_workspace(Path(temporary), environment, config or {}, allow_template)
        rendered_all = {name: render(path) for name, path in targets.items()}
    rendered = {
        "application": [d for d in rendered_all["application"] if d.get("kind") not in {"Namespace", "ConfigMap", "NetworkPolicy"}],
        "migration": [d for d in rendered_all["migration"] if d.get("kind") == "Job"],
        "prerequisites": rendered_all["prerequisites"],
    }
    label_release_resources(rendered)
    if validate:
        validate_manifests(rendered, environment, config, allow_template)
    dest = output_dir / environment
    dest.mkdir(parents=True, exist_ok=True)
    for name, docs in rendered.items():
        (dest / f"{name}.yaml").write_text("".join(yaml.safe_dump(doc, sort_keys=False, explicit_start=True) for doc in docs), encoding="utf-8")
    for name, docs in split_bootstrap(rendered).items():
        (dest / f"{name}.yaml").write_text("".join(yaml.safe_dump(doc, sort_keys=False, explicit_start=True) for doc in docs), encoding="utf-8")
    fixture = bool(allow_template or (config and any(".test" in str(config.get(k, "")).lower() for k in ("api_host", "frontend_origin", "object_storage_endpoint", "object_storage_public_base_url"))))
    counts = {name: {kind: sum(doc.get("kind") == kind for doc in docs) for kind in sorted({doc.get("kind", "Unknown") for doc in docs})} for name, docs in rendered.items()}
    bundle = {"schemaVersion": 2, "environment": environment, "namespace": ENVIRONMENTS[environment],
              "fixture": fixture, "resourceCounts": counts, "bundleHash": bundle_hash(dest),
              "configHash": config_hash(rendered)}
    if environment != "local":
        bundle["externalSecretContract"] = {"migrationSslMode": "verify-full", "migrationEndpointType": "direct", "valuesInspected": False}
    (dest / "bundle.json").write_text(json.dumps(bundle, indent=2) + "\n", encoding="utf-8")
    return bundle


def validate_bundle(directory: Path, environment: str, release_id: str) -> dict:
    if not re.fullmatch(r"[a-z0-9](?:[a-z0-9-]{0,38}[a-z0-9])?", release_id):
        fail("release ID is invalid")
    try:
        bundle = json.loads((directory / "bundle.json").read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        fail("bundle metadata is missing or invalid")
    if bundle.get("fixture") is not False or bundle.get("environment") != environment or bundle.get("namespace") != ENVIRONMENTS[environment]:
        fail("fixture or mismatched environment bundle is not deployable")
    version = bundle.get("schemaVersion", 1)
    if type(version) is not int or version not in (1, 2):
        fail("unsupported bundle schema version")
    expected_secret_contract = {"migrationSslMode": "verify-full", "migrationEndpointType": "direct", "valuesInspected": False}
    if environment != "local" and bundle.get("externalSecretContract") != expected_secret_contract:
        fail("bundle must declare the external migration Secret contract without claiming its values were inspected")
    try:
        docs = {name: [d for d in yaml.safe_load_all((directory / f"{name}.yaml").read_text(encoding="utf-8")) if isinstance(d, dict)] for name in ("application", "migration", "prerequisites")}
    except (OSError, yaml.YAMLError):
        fail("bundle manifests are unreadable YAML")
    app = [d for d in docs["application"] if d.get("kind") == "Deployment"]
    jobs = [d for d in docs["migration"] if d.get("kind") == "Job"]
    if len(app) != 1 or len(jobs) != 1:
        fail("bundle must contain one application Deployment and one migration Job")
    image = lambda workload: workload["spec"]["template"]["spec"]["containers"][0].get("image", "")
    images = (image(app[0]), image(jobs[0]))
    if images[0] != images[1] or (environment != "local" and not IMAGE_RE.fullmatch(images[0])):
        fail("bundle application and migration images must match; cloud images require immutable digests")
    configmaps = [d for d in docs["prerequisites"] if d.get("kind") == "ConfigMap"]
    ingress = [d for d in docs["application"] if d.get("kind") == "Ingress"]
    bundle_config = None
    if environment != "local":
        if len(configmaps) != 1 or len(ingress) != 2:
            fail("cloud bundle configuration or both edge Ingresses are missing")
        first_ingress = next((d for d in ingress if d.get("metadata", {}).get("name") == "tcg-store-backend"), {})
        rules = first_ingress.get("spec", {}).get("rules", [])
        annotations = first_ingress.get("metadata", {}).get("annotations", {})
        if len(rules) != 1:
            fail("cloud bundle must have one exact-host ingress rule")
        data = configmaps[0].get("data", {})
        bundle_config = {
            "image": images[0], "api_host": rules[0].get("host"), "frontend_origin": data.get("APP_CORS_ALLOWED_ORIGINS"),
            "object_storage_endpoint": data.get("OBJECT_STORAGE_ENDPOINT"), "object_storage_region": data.get("OBJECT_STORAGE_REGION"),
            "object_storage_bucket": data.get("OBJECT_STORAGE_BUCKET"), "object_storage_public_base_url": data.get("OBJECT_STORAGE_PUBLIC_BASE_URL"),
            "object_storage_path_style": data.get("OBJECT_STORAGE_PATH_STYLE") == "true",
            "ingress_class": first_ingress.get("spec", {}).get("ingressClassName"), "tls_issuer": annotations.get("cert-manager.io/issuer"),
        }
        validate_config(bundle_config, environment, False)
        domains = [bundle_config[key] for key in ("api_host", "frontend_origin", "object_storage_endpoint", "object_storage_public_base_url")]
        if any(".test" in str(value).lower() or PLACEHOLDERS.search(str(value)) for value in domains):
            fail("reserved or placeholder domains make this bundle a fixture")
    validate_manifests(docs, environment, bundle_config, bundle_version=version)
    actual_bundle_hash, actual_config_hash = bundle_hash(directory), config_hash(docs)
    if version == 2:
        if bundle.get("bundleHash") != actual_bundle_hash or bundle.get("configHash") != actual_config_hash:
            fail("bundle or configuration hash differs from the rendered release")
        for name, expected in split_bootstrap(docs).items():
            try:
                actual = list(yaml.safe_load_all((directory / f"{name}.yaml").read_text(encoding="utf-8")))
            except (OSError, yaml.YAMLError):
                fail("CI/bootstrap bundle inputs are unreadable")
            if actual != expected:
                fail("CI/bootstrap bundle inputs differ from their approved derivation")
    refs = [item.get("valueFrom", {}).get("secretKeyRef", {}) for item in jobs[0]["spec"]["template"]["spec"]["containers"][0].get("env", [])]
    if any(ref.get("key") not in MIGRATION_SECRET_KEYS for ref in refs if ref):
        fail("migration Secret references violate the external Secret contract")
    return {
        "fixture": False, "environment": environment, "namespace": ENVIRONMENTS[environment],
        "applicationImage": images[0], "migrationImage": images[1], "migrationJobName": jobs[0]["metadata"]["name"],
        "applicationDeploymentName": app[0]["metadata"]["name"], "placeholders": [], "hasLiteralSecretResources": False,
        "cloudConfigValidated": environment != "local",
        "migrationSslMode": "verify-full" if environment != "local" else "", "migrationEndpointType": "direct" if environment != "local" else "",
        "externalSecretContract": expected_secret_contract if environment != "local" else None,
        "secretName": "tcg-store-backend-secrets",
        "schemaVersion": version, "bundleHash": actual_bundle_hash, "configHash": actual_config_hash,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command")
    render_parser = subparsers.add_parser("render", help="render an environment bundle")
    render_parser.add_argument("--environment", required=True, choices=ENVIRONMENTS)
    render_parser.add_argument("--config", type=Path)
    render_parser.add_argument("--output-dir", type=Path, required=True, help="explicit destination for YAML artifacts")
    render_parser.add_argument("--validate", action="store_true", help="enforce release and manifest policy")
    render_parser.add_argument("--allow-template", action="store_true", help="CI-only, visibly non-deployable template fixture mode")
    bundle_parser = subparsers.add_parser("validate-bundle", help="validate already-rendered deployment artifacts")
    bundle_parser.add_argument("--manifest-directory", type=Path, required=True)
    bundle_parser.add_argument("--environment", required=True, choices=ENVIRONMENTS)
    bundle_parser.add_argument("--release-id", required=True)
    bundle_parser.add_argument("--format", choices=("json",), default="json")
    supplied = list(sys.argv[1:] if argv is None else argv)
    if supplied and supplied[0].startswith("-"):
        supplied.insert(0, "render")
    args = parser.parse_args(supplied)
    try:
        if args.command == "validate-bundle":
            print(json.dumps(validate_bundle(args.manifest_directory, args.environment, args.release_id), separators=(",", ":")))
            return 0
        if args.command not in ("render", None):
            parser.error("unknown command")
        if args.allow_template and os.environ.get("TCG_K8S_FIXTURE_MODE") != "1":
            fail("--allow-template requires TCG_K8S_FIXTURE_MODE=1 (CI fixture mode)")
        if args.environment == "local" and args.config is not None:
            fail("local rendering uses its checked-in development config and does not accept --config")
        config = load_config(args.config, args.allow_template)
        if args.environment != "local" or args.config is not None:
            validate_config(config, args.environment, args.allow_template)
        bundle = _render_bundle(args.output_dir, args.environment, config or None, args.allow_template, args.validate)
        print(f"{args.environment}: bundle fixture={str(bundle['fixture']).lower()}; resource counts only")
        return 0
    except ValidationError as error:
        print(f"validation failed: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
