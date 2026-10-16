#!/usr/bin/env python3
"""Verify one exact trusted GitHub Actions image artifact and registry identity."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path, PurePosixPath
from typing import Any, Callable

EXPECTED_REPOSITORY = "mudst-2026-luntungsadungyong/tcg-store-backend"
IMAGE = "ghcr.io/" + EXPECTED_REPOSITORY
WORKFLOW = ".github/workflows/docker-build.yml"
ISSUER = "https://token.actions.githubusercontent.com"
HEX64 = re.compile(r"^[0-9a-f]{64}$")
SHA40 = re.compile(r"^[0-9a-f]{40}$")
RUNNER = Callable[..., subprocess.CompletedProcess[Any]]


class VerificationError(ValueError):
    """An artifact failed an identity or integrity check."""


def _run(runner: RUNNER | None, args: list[str], *, binary: bool = False) -> subprocess.CompletedProcess[Any]:
    command = runner or subprocess.run
    try:
        result = command(args, capture_output=True, text=not binary, check=False)
    except OSError as exc:
        raise VerificationError(f"verification command unavailable: {Path(args[0]).name}") from exc
    if result.returncode:
        # stderr may contain command arguments, tokens, or registry credentials.
        raise VerificationError(f"verification command failed: {Path(args[0]).name}")
    return result


def _json_api(runner: RUNNER | None, endpoint: str) -> Any:
    result = _run(runner, ["gh", "api", "-H", "Accept: application/vnd.github+json", endpoint])
    try:
        return json.loads(result.stdout)
    except (TypeError, json.JSONDecodeError) as exc:
        raise VerificationError("GitHub API returned invalid JSON") from exc


def _validate_run(run: Any, run_id: int, repository: str) -> str:
    if not isinstance(run, dict):
        raise VerificationError("GitHub workflow run response is not an object")
    run_repository = run.get("repository")
    head_repository = run.get("head_repository")
    if not isinstance(run_repository, dict) or not isinstance(head_repository, dict):
        raise VerificationError("workflow run repository identity is malformed")
    if (run.get("id") != run_id or not isinstance(run_repository.get("full_name"), str)
            or run_repository["full_name"].lower() != repository.lower()
            or run.get("path") != WORKFLOW):
        raise VerificationError("workflow run ID, repository, or workflow does not match the release")
    if run.get("status") != "completed" or run.get("conclusion") != "success":
        raise VerificationError("workflow run did not complete successfully")
    if run.get("event") not in {"push", "workflow_dispatch"} or run.get("head_branch") != "main":
        raise VerificationError("workflow run was not started from protected main")
    if not isinstance(head_repository.get("full_name"), str) or head_repository["full_name"].lower() != repository.lower():
        raise VerificationError("workflow source repository is not the canonical repository")
    sha = run.get("head_sha")
    if not isinstance(sha, str) or not SHA40.fullmatch(sha) or run.get("head_commit", {}).get("id", sha) != sha:
        raise VerificationError("workflow run has inconsistent source commit metadata")
    return sha


def _parse_contract(data: bytes, source_sha: str, run_id: int | None = None) -> dict[str, Any]:
    try:
        lines = data.decode("utf-8", errors="strict").splitlines()
    except UnicodeDecodeError as exc:
        raise VerificationError("image contract is not UTF-8 text") from exc
    fields: dict[str, str] = {}
    for line in lines:
        if not line or line.count("=") != 1:
            raise VerificationError("image contract contains a malformed line")
        key, value = line.split("=", 1)
        if key in fields or not value:
            raise VerificationError("image contract has duplicate or empty fields")
        fields[key] = value
    expected = {"image", "tag", "digest", "reference", "commit", "linux_amd64_digest", "linux_arm64_digest"}
    if set(fields) != expected:
        raise VerificationError("image contract fields do not match the publisher contract")
    if fields["image"] != IMAGE or fields["commit"] != source_sha or fields["tag"] != f"sha-{source_sha}":
        raise VerificationError("image contract repository, tag, or commit is inconsistent")
    for key in ("digest", "linux_amd64_digest", "linux_arm64_digest"):
        value = fields[key]
        if not value.startswith("sha256:") or not HEX64.fullmatch(value[7:]):
            raise VerificationError(f"{key} is not a full sha256 digest")
    if fields["reference"] != f"{IMAGE}@{fields['digest']}":
        raise VerificationError("image contract reference is inconsistent")
    if fields["linux_amd64_digest"] == fields["linux_arm64_digest"]:
        raise VerificationError("platform child digests must identify distinct images")
    return {
        "repository": IMAGE, "sourceCommit": source_sha, "buildRunId": run_id,
        "image": fields["reference"], "indexDigest": fields["digest"],
        "platformDigests": {"linux/amd64": fields["linux_amd64_digest"], "linux/arm64": fields["linux_arm64_digest"]},
    }


def _safe_contract_from_zip(archive_path: Path, expected_name: str, source_sha: str,
                            run_id: int | None = None) -> dict[str, Any]:
    try:
        with zipfile.ZipFile(archive_path) as archive:
            files = archive.infolist()
            if len(files) != 1:
                raise VerificationError("build artifact must contain exactly one file")
            member = files[0]
            path = PurePosixPath(member.filename)
            if path.is_absolute() or ".." in path.parts or len(path.parts) != 1 or path.name != expected_name:
                raise VerificationError("build artifact contains an unsafe or unexpected path")
            mode = member.external_attr >> 16
            file_type = mode & 0o170000
            if file_type not in (0, 0o100000):
                raise VerificationError("build artifact contract is not a regular file")
            if member.file_size > 16_384 or member.compress_size > 16_384:
                raise VerificationError("build artifact contract exceeds the size limit")
            return _parse_contract(archive.read(member), source_sha, run_id)
    except (OSError, zipfile.BadZipFile) as exc:
        raise VerificationError("GitHub returned an invalid image contract archive") from exc


def _validate_index(raw: str | bytes, contract: dict[str, Any]) -> None:
    try:
        index = json.loads(raw)
    except (TypeError, json.JSONDecodeError) as exc:
        raise VerificationError("registry returned an invalid OCI image index") from exc
    manifests = index.get("manifests") if isinstance(index, dict) else None
    if not isinstance(manifests, list) or len(manifests) != 2:
        raise VerificationError("published image is not an OCI index")
    actual: dict[str, str] = {}
    for manifest in manifests:
        if not isinstance(manifest, dict):
            continue
        platform = manifest.get("platform", {})
        if isinstance(platform, dict):
            key = f"{platform.get('os')}/{platform.get('architecture')}"
            digest = manifest.get("digest")
            if key in actual:
                raise VerificationError("published OCI index has duplicate platform entries")
            if key in {"linux/amd64", "linux/arm64"} and isinstance(digest, str):
                actual[key] = digest
    if actual != contract["platformDigests"]:
        raise VerificationError("published OCI index child digests differ from the signed image contract")


def reverify_image_identity(image: dict[str, Any], source_commit: str, runner: RUNNER | None = None) -> None:
    """Recheck signatures and attestations for a previously accepted image.

    This uses its immutable identity and retained receipt fields, not an unexpired
    GitHub build artifact, so last-known-good verification survives artifact expiry.
    """
    if not isinstance(image, dict) or image.get("repository") != IMAGE:
        raise VerificationError("prior image repository is not approved")
    digest = image.get("indexDigest")
    platforms = image.get("platformDigests")
    if not isinstance(digest, str) or not re.fullmatch(r"sha256:[0-9a-f]{64}", digest):
        raise VerificationError("prior image index digest is invalid")
    if not isinstance(source_commit, str) or not SHA40.fullmatch(source_commit):
        raise VerificationError("prior image source commit is invalid")
    if not isinstance(platforms, dict) or set(platforms) != {"linux/amd64", "linux/arm64"}:
        raise VerificationError("prior image platform digest inventory is invalid")
    identity = f"https://github.com/{EXPECTED_REPOSITORY}/{WORKFLOW}@refs/heads/main"
    index_ref = f"{IMAGE}@{digest}"
    _run(runner, ["cosign", "verify", index_ref, "--certificate-identity", identity,
                  "--certificate-oidc-issuer", ISSUER])
    _run(runner, ["gh", "attestation", "verify", f"oci://{index_ref}", "--repo", EXPECTED_REPOSITORY,
                  "--signer-workflow", f"{EXPECTED_REPOSITORY}/{WORKFLOW}", "--source-digest", source_commit])
    raw_index = _run(runner, ["docker", "buildx", "imagetools", "inspect", "--raw", index_ref]).stdout
    _validate_index(raw_index, {"platformDigests": platforms})
    for child_digest in platforms.values():
        if not isinstance(child_digest, str) or not re.fullmatch(r"sha256:[0-9a-f]{64}", child_digest):
            raise VerificationError("prior image contains an invalid child digest")
        child_ref = f"oci://{IMAGE}@{child_digest}"
        _run(runner, ["gh", "attestation", "verify", child_ref, "--repo", EXPECTED_REPOSITORY,
                      "--signer-workflow", f"{EXPECTED_REPOSITORY}/{WORKFLOW}", "--source-digest", source_commit,
                      "--predicate-type", "https://spdx.dev/Document/v2.3"])


def verify_release(run_id: int, repository: str, runner: RUNNER | None = None) -> dict[str, Any]:
    """Return an immutable, cryptographically reverified image identity."""
    if type(run_id) is not int or run_id < 1:
        raise VerificationError("run ID must be a positive integer")
    if not isinstance(repository, str) or repository.lower() != EXPECTED_REPOSITORY:
        raise VerificationError("repository is not the configured backend image repository")
    run = _json_api(runner, f"repos/{repository}/actions/runs/{run_id}")
    source_sha = _validate_run(run, run_id, repository)
    protection = _json_api(runner, f"repos/{repository}/branches/main/protection")
    reviews = protection.get("required_pull_request_reviews") if isinstance(protection, dict) else None
    checks = protection.get("required_status_checks") if isinstance(protection, dict) else None
    admins = protection.get("enforce_admins") if isinstance(protection, dict) else None
    protected = (isinstance(checks, dict) and bool(checks.get("contexts") or checks.get("checks")))
    protected = protected or (isinstance(reviews, dict) and type(reviews.get("required_approving_review_count")) is int
                              and reviews["required_approving_review_count"] >= 1)
    protected = protected or (isinstance(admins, dict) and admins.get("enabled") is True)
    if not protected:
        raise VerificationError("main branch protection could not be established")
    comparison = _json_api(runner, f"repos/{repository}/compare/{source_sha}...main")
    if not isinstance(comparison, dict) or comparison.get("status") not in {"ahead", "identical"}:
        raise VerificationError("workflow source commit is not contained in the current protected main history")
    artifacts = _json_api(runner, f"repos/{repository}/actions/runs/{run_id}/artifacts")
    if not isinstance(artifacts, dict) or not isinstance(artifacts.get("artifacts"), list):
        raise VerificationError("GitHub artifact listing is malformed")
    expected_artifact = f"container-image-contract-{source_sha}"
    matches = [a for a in artifacts["artifacts"] if isinstance(a, dict) and a.get("name") == expected_artifact and a.get("expired") is False]
    if (len(matches) != 1 or type(matches[0].get("id")) is not int or matches[0]["id"] < 1
            or not isinstance(matches[0].get("workflow_run"), dict)
            or matches[0]["workflow_run"].get("id") != run_id):
        raise VerificationError("exactly one unexpired image contract from this workflow run is required")

    with tempfile.TemporaryDirectory(prefix="tcg-image-contract-") as temporary:
        archive_path = Path(temporary) / "artifact.zip"
        archive = _run(runner, ["gh", "api", "-H", "Accept: application/vnd.github+json",
                                f"repos/{repository}/actions/artifacts/{matches[0]['id']}/zip"], binary=True)
        payload = archive.stdout
        if not isinstance(payload, bytes):
            raise VerificationError("GitHub artifact download did not return binary archive data")
        archive_path.write_bytes(payload)
        contract = _safe_contract_from_zip(archive_path, "image-contract.txt", source_sha, run_id)

    index = _run(runner, ["docker", "buildx", "imagetools", "inspect", "--raw", contract["image"]])
    _validate_index(index.stdout, contract)
    identity = f"https://github.com/{repository}/{WORKFLOW}@refs/heads/main"
    _run(runner, ["cosign", "verify", contract["image"], "--certificate-identity", identity,
                  "--certificate-oidc-issuer", ISSUER])
    _run(runner, ["gh", "attestation", "verify", f"oci://{contract['image']}", "--repo", repository,
                  "--signer-workflow", f"{repository}/{WORKFLOW}", "--source-digest", source_sha])
    for child_digest in contract["platformDigests"].values():
        _run(runner, ["gh", "attestation", "verify", f"oci://{IMAGE}@{child_digest}", "--repo", repository,
                      "--signer-workflow", f"{repository}/{WORKFLOW}", "--source-digest", source_sha,
                      "--predicate-type", "https://spdx.dev/Document/v2.3"])
    contract["verification"] = {"signature": True, "provenance": True, "sbom": True}
    return contract


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run-id", type=int, required=True)
    parser.add_argument("--repository", required=True, help="GitHub owner/repository")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        result = verify_release(args.run_id, args.repository)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, sort_keys=True, separators=(",", ":")) + "\n", encoding="utf-8")
    except (VerificationError, OSError) as exc:
        print(f"artifact verification failed: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
