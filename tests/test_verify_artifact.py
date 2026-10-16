import io
import json
import subprocess
import zipfile

import pytest

from scripts.release import verify_artifact as verifier

SHA = "a" * 40
INDEX = "sha256:" + "1" * 64
AMD = "sha256:" + "2" * 64
ARM = "sha256:" + "3" * 64
RUN_ID = 123


def contract_bytes():
    return (f"image={verifier.IMAGE}\ntag=sha-{SHA}\ndigest={INDEX}\nreference={verifier.IMAGE}@{INDEX}\n"
            f"commit={SHA}\nlinux_amd64_digest={AMD}\nlinux_arm64_digest={ARM}\n").encode()


def archive_bytes(mode=0o600):
    stream = io.BytesIO()
    with zipfile.ZipFile(stream, "w") as archive:
        info = zipfile.ZipInfo("image-contract.txt")
        info.external_attr = mode << 16
        archive.writestr(info, contract_bytes())
    return stream.getvalue()


def test_default_zip_regular_permissions_are_accepted(tmp_path):
    path = tmp_path / "contract.zip"
    path.write_bytes(archive_bytes())
    parsed = verifier._safe_contract_from_zip(path, "image-contract.txt", SHA, RUN_ID)
    assert parsed["buildRunId"] == RUN_ID
    assert parsed["platformDigests"] == {"linux/amd64": AMD, "linux/arm64": ARM}


def test_zip_symlink_is_rejected(tmp_path):
    path = tmp_path / "contract.zip"
    path.write_bytes(archive_bytes(0o120777))
    with pytest.raises(verifier.VerificationError, match="regular file"):
        verifier._safe_contract_from_zip(path, "image-contract.txt", SHA)


def test_index_child_digests_must_match_actual_registry_manifest():
    index = {"schemaVersion": 2, "manifests": [
        {"digest": AMD, "platform": {"os": "linux", "architecture": "amd64"}},
        {"digest": ARM, "platform": {"os": "linux", "architecture": "arm64"}},
    ]}
    parsed = verifier._parse_contract(contract_bytes(), SHA)
    verifier._validate_index(json.dumps(index), parsed)
    index["manifests"][1]["digest"] = AMD
    with pytest.raises(verifier.VerificationError, match="child digests"):
        verifier._validate_index(json.dumps(index), parsed)


def test_command_errors_never_echo_credentials_or_stderr():
    def runner(*args, **kwargs):
        return subprocess.CompletedProcess(args[0], 1, stdout="", stderr="token=super-secret")

    with pytest.raises(verifier.VerificationError) as error:
        verifier._run(runner, ["gh", "api"])
    assert "super-secret" not in str(error.value)


@pytest.mark.parametrize("run", [
    {"id": RUN_ID + 1},
    {"id": RUN_ID, "repository": {"full_name": "someone/fork"}, "path": verifier.WORKFLOW},
    {"id": RUN_ID, "repository": {"full_name": verifier.EXPECTED_REPOSITORY}, "path": "other.yml"},
])
def test_run_identity_is_fail_closed(run):
    with pytest.raises(verifier.VerificationError):
        verifier._validate_run(run, RUN_ID, verifier.EXPECTED_REPOSITORY)


def test_prior_image_reverification_does_not_require_build_artifact():
    calls = []

    def runner(args, **kwargs):
        calls.append(args)
        stdout = ""
        if args[:4] == ["docker", "buildx", "imagetools", "inspect"]:
            stdout = json.dumps({"schemaVersion": 2, "manifests": [
                {"digest": AMD, "platform": {"os": "linux", "architecture": "amd64"}},
                {"digest": ARM, "platform": {"os": "linux", "architecture": "arm64"}},
            ]})
        return subprocess.CompletedProcess(args, 0, stdout=stdout, stderr="")

    verifier.reverify_image_identity({
        "repository": verifier.IMAGE, "indexDigest": INDEX,
        "platformDigests": {"linux/amd64": AMD, "linux/arm64": ARM},
    }, SHA, runner=runner)
    assert any(args[0] == "cosign" for args in calls)
    assert all("actions/artifacts" not in " ".join(args) for args in calls)
    index_attestations = [args for args in calls if args[:3] == ["gh", "attestation", "verify"] and args[3].endswith(INDEX)]
    child_attestations = [args for args in calls if args[:3] == ["gh", "attestation", "verify"] and args[3].endswith((AMD, ARM))]
    assert len(index_attestations) == 1 and "--predicate-type" not in index_attestations[0]
    assert len(child_attestations) == 2 and all(args[-2:] == ["--predicate-type", "https://spdx.dev/Document/v2.3"] for args in child_attestations)


def test_verify_release_accepts_only_protected_main_run_and_exact_artifact():
    repository = verifier.EXPECTED_REPOSITORY
    run = {"id": RUN_ID, "repository": {"full_name": repository}, "head_repository": {"full_name": repository},
           "path": verifier.WORKFLOW, "status": "completed", "conclusion": "success", "event": "push",
           "head_branch": "main", "head_sha": SHA, "head_commit": {"id": SHA}}
    endpoints = {
        f"repos/{repository}/actions/runs/{RUN_ID}": run,
        f"repos/{repository}/branches/main/protection": {"required_pull_request_reviews": {"required_approving_review_count": 1}},
        f"repos/{repository}/compare/{SHA}...main": {"status": "identical"},
        f"repos/{repository}/actions/runs/{RUN_ID}/artifacts": {"artifacts": [{
            "id": 987, "name": f"container-image-contract-{SHA}", "expired": False,
            "workflow_run": {"id": RUN_ID},
        }]},
    }

    calls = []

    def runner(args, **kwargs):
        calls.append(args)
        if args[0] == "gh" and "api" in args:
            endpoint = args[-1]
            if endpoint.endswith("/zip"):
                return subprocess.CompletedProcess(args, 0, stdout=archive_bytes(), stderr="")
            return subprocess.CompletedProcess(args, 0, stdout=json.dumps(endpoints[endpoint]), stderr="")
        if args[:4] == ["docker", "buildx", "imagetools", "inspect"]:
            index = {"schemaVersion": 2, "manifests": [
                {"digest": AMD, "platform": {"os": "linux", "architecture": "amd64"}},
                {"digest": ARM, "platform": {"os": "linux", "architecture": "arm64"}},
            ]}
            return subprocess.CompletedProcess(args, 0, stdout=json.dumps(index), stderr="")
        return subprocess.CompletedProcess(args, 0, stdout="", stderr="")

    result = verifier.verify_release(RUN_ID, repository, runner=runner)
    assert result["buildRunId"] == RUN_ID
    assert result["verification"] == {"signature": True, "provenance": True, "sbom": True}
    index_attestations = [args for args in calls if args[:3] == ["gh", "attestation", "verify"] and args[3].endswith(INDEX)]
    child_attestations = [args for args in calls if args[:3] == ["gh", "attestation", "verify"] and args[3].endswith((AMD, ARM))]
    assert len(index_attestations) == 1 and "--predicate-type" not in index_attestations[0]
    assert len(child_attestations) == 2 and all(args[-2:] == ["--predicate-type", "https://spdx.dev/Document/v2.3"] for args in child_attestations)
