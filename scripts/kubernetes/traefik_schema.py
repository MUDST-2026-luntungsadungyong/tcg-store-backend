#!/usr/bin/env python3
"""Convert the official Traefik CRD OpenAPI schemas to kubeconform schema files."""

import argparse
import json
from pathlib import Path

import yaml


def close_object_schemas(node: object) -> None:
    """Disallow undeclared object fields without closing map or opaque fields."""
    if isinstance(node, dict):
        if node.get("x-kubernetes-preserve-unknown-fields") is True:
            return
        if node.get("type") == "object" and "additionalProperties" not in node:
            node["additionalProperties"] = False
        for key, value in node.items():
            if key == "metadata":
                continue  # Kubernetes ObjectMeta has fields outside each CRD's spec.
            close_object_schemas(value)
    elif isinstance(node, list):
        for item in node:
            close_object_schemas(item)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True, help="official Traefik CRD YAML")
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    try:
        resources = list(yaml.safe_load_all(args.input.read_text(encoding="utf-8")))
    except (OSError, yaml.YAMLError):
        parser.error("unable to read the pinned Traefik CRD YAML")
    count = 0
    for crd in resources:
        if not isinstance(crd, dict) or crd.get("kind") != "CustomResourceDefinition":
            continue
        spec = crd.get("spec", {})
        group = spec.get("group")
        kind = spec.get("names", {}).get("kind")
        for version in spec.get("versions", []):
            if version.get("served") is False:
                continue
            schema = version.get("schema", {}).get("openAPIV3Schema")
            if not group or not kind or not isinstance(schema, dict):
                continue
            # Kubeconform checks the Kubernetes envelope as well as the CRD spec.
            schema.setdefault("type", "object")
            schema.setdefault("properties", {})
            schema["properties"].setdefault("apiVersion", {"type": "string"})
            schema["properties"].setdefault("kind", {"type": "string"})
            schema["properties"].setdefault("metadata", {"type": "object"})
            close_object_schemas(schema)
            output = args.output_dir / group / f"{kind.lower()}_{version['name']}.json"
            output.parent.mkdir(parents=True, exist_ok=True)
            output.write_text(json.dumps(schema, separators=(",", ":")), encoding="utf-8")
            count += 1
    if count == 0:
        parser.error("the input contains no versioned Traefik CRD schemas")
    print(f"Generated strict schema files for {count} Traefik CRD versions")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
