import copy
import json
import tempfile
import unittest
from pathlib import Path

from render import (
    ROOT,
    ValidationError,
    build_workspace,
    load_config,
    render,
    validate_config,
    validate_manifests,
    validate_bundle,
    _render_bundle,
    label_release_resources,
)
from traefik_schema import close_object_schemas


class KubernetesRenderPolicyTests(unittest.TestCase):
    @staticmethod
    def _bundle(targets):
        rendered = {name: render(path) for name, path in targets.items()}
        manifests = {
            "application": [d for d in rendered["application"] if d.get("kind") not in {"Namespace", "ConfigMap", "NetworkPolicy"}],
            "migration": [d for d in rendered["migration"] if d.get("kind") == "Job"],
            "prerequisites": rendered["prerequisites"],
        }
        label_release_resources(manifests)
        return manifests

    @classmethod
    def setUpClass(cls):
        cls.config = load_config(ROOT / "scripts/kubernetes/fixtures/ci-release.json", False)
        cls.rendered = {}
        cls.temporary = tempfile.TemporaryDirectory()
        workspace = Path(cls.temporary.name)
        targets = build_workspace(workspace, "staging", cls.config, False)
        cls.rendered = cls._bundle(targets)

    @classmethod
    def tearDownClass(cls):
        cls.temporary.cleanup()

    def assert_policy_rejects(self, mutate):
        documents = copy.deepcopy(self.rendered)
        mutate(documents)
        with self.assertRaises(ValidationError):
            validate_manifests(documents, "staging", self.config)

    def test_clean_render_passes(self):
        validate_config(self.config, "staging", False)
        validate_manifests(self.rendered, "staging", self.config)
        self.assertEqual(2, sum(item.get("kind") == "Ingress" for item in self.rendered["application"]))

    def test_cloud_workloads_require_private_registry_pull_secret(self):
        deployment = next(item for item in self.rendered["application"] if item.get("kind") == "Deployment")
        migration = next(item for item in self.rendered["migration"] if item.get("kind") == "Job")
        expected = [{"name": "tcg-store-ghcr-pull"}]
        self.assertEqual(expected, deployment["spec"]["template"]["spec"]["imagePullSecrets"])
        self.assertEqual(expected, migration["spec"]["template"]["spec"]["imagePullSecrets"])

        def mutate(documents):
            workload = next(item for item in documents["application"] if item.get("kind") == "Deployment")
            workload["spec"]["template"]["spec"].pop("imagePullSecrets")

        self.assert_policy_rejects(mutate)

    def test_release_ownership_is_required_on_controlled_resources(self):
        def mutate(docs):
            config_map = next(item for item in docs["prerequisites"] if item.get("kind") == "ConfigMap")
            config_map["metadata"]["labels"].pop("app.kubernetes.io/managed-by")
        self.assert_policy_rejects(mutate)

    def test_telemetry_cannot_claim_a_different_environment(self):
        def mutate(docs):
            config_map = next(item for item in docs["prerequisites"] if item.get("kind") == "ConfigMap")
            config_map["data"]["APP_ENVIRONMENT"] = "production"
        self.assert_policy_rejects(mutate)

    def test_v2_requires_explicit_telemetry_environment(self):
        def mutate(docs):
            config_map = next(item for item in docs["prerequisites"] if item.get("kind") == "ConfigMap")
            config_map["data"].pop("APP_ENVIRONMENT")
        self.assert_policy_rejects(mutate)

    def test_release_labels_do_not_claim_bootstrap_or_migration_jobs(self):
        for docs in self.rendered.values():
            for doc in docs:
                if doc["kind"] in {"Namespace", "ServiceAccount", "Job"}:
                    self.assertNotEqual("tcg-release", doc.get("metadata", {}).get("labels", {}).get("app.kubernetes.io/managed-by"))

    def test_monitoring_policy_requires_namespace_and_pod_selector_together(self):
        def mutate(docs):
            policy = next(item for item in docs["prerequisites"] if item.get("metadata", {}).get("name") == "tcg-store-backend-monitoring-ingress")
            policy["spec"]["ingress"][0]["from"][0].pop("podSelector")
        self.assert_policy_rejects(mutate)

    def test_unapproved_resource_is_rejected(self):
        self.assert_policy_rejects(lambda docs: docs["application"].append({"apiVersion": "traefik.io/v1alpha1", "kind": "IngressRoute", "metadata": {"name": "extra"}}))

    def test_api_and_migration_image_mismatch_is_rejected(self):
        def mutate(docs):
            job = next(item for item in docs["migration"] if item.get("kind") == "Job")
            job["spec"]["template"]["spec"]["containers"][0]["image"] = "ghcr.io/example/other@sha256:" + "b" * 64
        self.assert_policy_rejects(mutate)

    def test_mutable_image_tag_is_rejected(self):
        bad = dict(self.config, image="ghcr.io/example/backend:latest")
        with self.assertRaises(ValidationError):
            validate_config(bad, "staging", False)

    def test_secret_resource_is_rejected(self):
        self.assert_policy_rejects(lambda docs: docs["application"].append({"apiVersion": "v1", "kind": "Secret", "metadata": {"name": "leak"}, "data": {"x": "redacted"}}))

    def test_config_credential_field_is_rejected_recursively(self):
        bad = dict(self.config, deployment={"database": {"jdbc_url": "not-read"}})
        with self.assertRaises(ValidationError):
            validate_config(bad, "staging", False)

    def test_public_service_is_rejected(self):
        def mutate(docs):
            service = next(item for item in docs["application"] if item.get("kind") == "Service")
            service["spec"]["type"] = "LoadBalancer"
        self.assert_policy_rejects(mutate)

    def test_catchall_ingress_is_rejected(self):
        def mutate(docs):
            ingress = next(item for item in docs["application"] if item.get("kind") == "Ingress" and item["metadata"]["name"] == "tcg-store-backend")
            ingress["spec"]["rules"][0]["http"]["paths"][0]["path"] = "/"
        self.assert_policy_rejects(mutate)

    def test_wildcard_cors_is_rejected(self):
        def mutate(docs):
            config_map = next(item for item in docs["prerequisites"] if item.get("kind") == "ConfigMap")
            config_map["data"]["APP_CORS_ALLOWED_ORIGINS"] = "*"
        self.assert_policy_rejects(mutate)

    def test_configmap_credential_key_is_rejected(self):
        def mutate(docs):
            config_map = next(item for item in docs["prerequisites"] if item.get("kind") == "ConfigMap")
            config_map["data"]["NEON_DB_PASSWORD"] = "synthetic-fixture-value"
        self.assert_policy_rejects(mutate)

    def test_literal_database_password_environment_value_is_rejected(self):
        def mutate(docs):
            deployment = next(item for item in docs["application"] if item.get("kind") == "Deployment")
            env = deployment["spec"]["template"]["spec"]["containers"][0]["env"]
            env.append({"name": "NEON_DB_PASSWORD", "value": "synthetic-fixture-value"})
        self.assert_policy_rejects(mutate)

    def test_missing_middleware_is_rejected(self):
        def mutate(docs):
            docs["application"][:] = [item for item in docs["application"] if not (item.get("kind") == "Middleware" and item.get("metadata", {}).get("name") == "tcg-auth-chain")]
        self.assert_policy_rejects(mutate)

    def test_broadened_network_policy_is_rejected(self):
        def mutate(docs):
            policy = next(item for item in docs["prerequisites"] if item.get("kind") == "NetworkPolicy" and item["metadata"]["name"].endswith("api-egress"))
            policy["spec"]["egress"] = [{}]
        self.assert_policy_rejects(mutate)

    def test_placeholder_release_config_is_rejected(self):
        bad = dict(self.config, api_host="api.example.invalid")
        with self.assertRaises(ValidationError):
            validate_config(bad, "staging", False)

    def test_wrong_ingress_class_is_rejected(self):
        bad = dict(self.config, ingress_class="nginx")
        with self.assertRaises(ValidationError):
            validate_config(bad, "staging", False)

    def test_production_test_domain_requires_template_mode(self):
        with self.assertRaises(ValidationError):
            validate_config(self.config, "production", False)

    def test_fixture_marker_cannot_be_cleared_to_deploy_test_domain(self):
        from render import validate_bundle

        output = Path(self.temporary.name) / "bundle-test"
        _ = __import__("render")._render_bundle(output, "staging", self.config, False, True)
        bundle_path = output / "staging" / "bundle.json"
        import json
        bundle = json.loads(bundle_path.read_text(encoding="utf-8"))
        bundle["fixture"] = False
        bundle_path.write_text(json.dumps(bundle), encoding="utf-8")
        with self.assertRaises(ValidationError):
            validate_bundle(output / "staging", "staging", "test-release")

    def test_local_settings_do_not_require_release_config(self):
        local_targets = build_workspace(Path(self.temporary.name) / "local", "local", {}, False)
        rendered = self._bundle(local_targets)
        validate_manifests(rendered, "local", None)

    def test_versioned_bundle_separates_bootstrap_and_ci(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            _render_bundle(root, "local", None, False, True)
            directory = root / "local"
            metadata = validate_bundle(directory, "local", "test-ci")
            self.assertEqual(2, metadata["schemaVersion"])
            self.assertEqual(64, len(metadata["bundleHash"]))
            import yaml
            bootstrap = list(yaml.safe_load_all((directory / "bootstrap.yaml").read_text()))
            application = list(yaml.safe_load_all((directory / "ci-application.yaml").read_text()))
            self.assertEqual({"Namespace", "ServiceAccount"}, {doc["kind"] for doc in bootstrap})
            self.assertNotIn("ServiceAccount", {doc["kind"] for doc in application})

    def test_ci_inputs_cannot_add_bootstrap_or_unapproved_resources(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            _render_bundle(root, "local", None, False, True)
            directory = root / "local"
            path = directory / "ci-prerequisites.yaml"
            path.write_text(path.read_text() + "\n---\napiVersion: v1\nkind: Namespace\nmetadata:\n  name: escape\n")
            with self.assertRaisesRegex(ValidationError, "approved derivation"):
                validate_bundle(directory, "local", "test-ci")

    def test_render_hash_detects_post_validation_manifest_changes(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            _render_bundle(root, "local", None, False, True)
            directory = root / "local"
            path = directory / "application.yaml"
            path.write_text(path.read_text() + "\n# changed after rendering\n")
            with self.assertRaisesRegex(ValidationError, "hash differs"):
                validate_bundle(directory, "local", "test-ci")

    def test_legacy_bundle_is_readable_but_unknown_version_is_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            _render_bundle(root, "local", None, False, True)
            directory = root / "local"
            path = directory / "bundle.json"
            bundle = json.loads(path.read_text())
            bundle.pop("schemaVersion")
            path.write_text(json.dumps(bundle))
            self.assertEqual(1, validate_bundle(directory, "local", "legacy")["schemaVersion"])
            bundle["schemaVersion"] = 999
            path.write_text(json.dumps(bundle))
            with self.assertRaisesRegex(ValidationError, "schema version"):
                validate_bundle(directory, "local", "legacy")

    def test_approved_legacy_cloud_bundle_is_recovery_readable(self):
        import yaml

        config = dict(self.config)
        for key in ("api_host", "frontend_origin", "object_storage_endpoint", "object_storage_public_base_url"):
            config[key] = config[key].replace("validation.test", "release-validation.net")
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            _render_bundle(root, "staging", config, False, True)
            directory = root / "staging"
            metadata_path = directory / "bundle.json"
            metadata = json.loads(metadata_path.read_text())
            metadata["schemaVersion"] = 1
            metadata.pop("bundleHash")
            metadata.pop("configHash")
            metadata_path.write_text(json.dumps(metadata))
            # Reconstruct exactly the predecessor contract, not a weakened v2.
            for name in ("application", "migration", "prerequisites"):
                path = directory / f"{name}.yaml"
                documents = list(yaml.safe_load_all(path.read_text()))
                documents = [doc for doc in documents if doc["metadata"]["name"] != "tcg-store-backend-monitoring-ingress"]
                for document in documents:
                    document["metadata"].get("labels", {}).pop("app.kubernetes.io/managed-by", None)
                    if document["kind"] == "ConfigMap":
                        document["data"].pop("APP_ENVIRONMENT")
                path.write_text("".join(yaml.safe_dump(doc, explicit_start=True) for doc in documents))
            result = validate_bundle(directory, "staging", "legacy-recovery")
            self.assertEqual(1, result["schemaVersion"])
            self.assertEqual(config["image"], result["applicationImage"])

    def test_crd_schema_closes_nested_objects_and_keeps_scalar_types(self):
        schema = {"type": "object", "properties": {"metadata": {"type": "object"}, "spec": {"type": "object", "properties": {
            "buffering": {"type": "object", "properties": {"maxRequestBodyBytes": {"type": "integer"}}}
        }}}}
        close_object_schemas(schema)
        self.assertFalse(schema["additionalProperties"])
        self.assertNotIn("additionalProperties", schema["properties"]["metadata"])
        self.assertFalse(schema["properties"]["spec"]["additionalProperties"])
        self.assertFalse(schema["properties"]["spec"]["properties"]["buffering"]["additionalProperties"])
        self.assertEqual("integer", schema["properties"]["spec"]["properties"]["buffering"]["properties"]["maxRequestBodyBytes"]["type"])

    def test_kubeconform_rejects_malformed_traefik_middleware(self):
        import os
        import subprocess

        binary = os.environ.get("KUBECONFORM_PATH")
        schemas = os.environ.get("TCG_TRAEFIK_SCHEMA_DIR")
        if not binary or not schemas:
            self.skipTest("set KUBECONFORM_PATH and TCG_TRAEFIK_SCHEMA_DIR for integration schema tests")
        for fixture in ("middleware-wrong-field.yaml", "middleware-wrong-type.yaml"):
            path = ROOT / "scripts/kubernetes/fixtures" / fixture
            result = subprocess.run([
                binary, "-strict", "-kubernetes-version", "1.34.1", "-schema-location", "default",
                "-schema-location", str(Path(schemas) / "{{.Group}}/{{.ResourceKind}}_{{.ResourceAPIVersion}}.json"), str(path),
            ], capture_output=True, text=True, check=False)
            self.assertNotEqual(0, result.returncode, f"kubeconform unexpectedly accepted {fixture}")


if __name__ == "__main__":
    unittest.main()
