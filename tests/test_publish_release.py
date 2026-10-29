"""An isolated GitHub model verifies draft/resume/publication order and artifact identity."""
import copy
import json
from pathlib import Path
from subprocess import CalledProcessError, CompletedProcess
from urllib.request import Request

import pytest

from scripts.publish_release import ASSETS, PAIRS, GitHub, PublicationError, Publisher, SafeRedirect, digest


REPOSITORY = "owner/hotel"
TAG = "v17.0.29"
COMMIT = "a" * 40
SIGNER = "b" * 64
APK, MANIFEST = PAIRS[0][1:]
HOTEL_APK, HOTEL_MANIFEST = PAIRS[1][1:]


def metadata(apk, distribution="personal-test"):
    name = APK if distribution == "personal-test" else HOTEL_APK
    return json.dumps({"schema": 1, "tag": TAG, "version_name": "17.0.29", "version_code": 59,
                       "package_name": "dev.orange.hotel", "sha256": digest(apk), "distribution": distribution,
                       "url": f"https://github.com/{REPOSITORY}/releases/download/{TAG}/{name}"}).encode()


def proof(apk, distribution):
    return {"schema": 1, "commit": COMMIT, "apk_sha256": digest(apk), "package_name": "dev.orange.hotel",
            "version_code": 59, "signer_sha256": SIGNER, "distribution": distribution,
            "ui_ready": True, "bridge": True, "mic_off": True, "retained_preferences": True,
            "retained_voice_key": True}


class FakeGitHub:
    def __init__(self):
        self.target = COMMIT
        self.value = None
        self.calls = []
        self.contents = {}
        self.fail_upload = None
        self.leave_empty_placeholder = False
        self.corrupt_digest = None

    def release(self, tag):
        self.calls.append("read")
        return copy.deepcopy(self.value)

    def tag_commit(self, tag):
        return self.target

    def create_draft(self, tag, commit):
        self.calls.append("create_draft")
        self.value = {"id": 7, "tag_name": tag, "draft": True, "prerelease": False, "assets": []}
        return copy.deepcopy(self.value)

    def upload(self, release_id, name, data):
        assert self.value["draft"], "assets reached a public release"
        self.calls.append("upload:" + name)
        if self.fail_upload == name:
            if self.leave_empty_placeholder:
                self.value["assets"].append({"id": 101, "name": name, "state": "starter", "size": 0, "digest": None})
            raise OSError("simulated interrupted upload")
        self.contents[name] = data
        self.value["assets"].append({"name": name, "state": "uploaded", "size": len(data),
                                     "digest": "sha256:" + (self.corrupt_digest or digest(data))})

    def publish(self, release_id):
        assert {row["name"] for row in self.value["assets"]} == set(ASSETS)
        self.calls.append("publish_latest")
        self.value["draft"] = False
        return copy.deepcopy(self.value)

    def remove_empty_placeholder(self, asset_id):
        assert self.value["draft"]
        asset = next(row for row in self.value["assets"] if row.get("id") == asset_id)
        assert asset["state"] == "starter" and asset["size"] == 0 and not asset.get("digest")
        self.calls.append("discard_empty_placeholder")
        self.value["assets"].remove(asset)

    def public_manifest(self, tag, name):
        assert not self.value["draft"]
        return self.contents[name]


@pytest.fixture
def candidate(tmp_path, monkeypatch):
    for distribution, apk_name, manifest in PAIRS:
        apk = f"{distribution} synthetic signed APK bytes".encode()
        (tmp_path / apk_name).write_bytes(apk)
        (tmp_path / manifest).write_bytes(metadata(apk, distribution))
        (tmp_path / f"verification-{distribution}.json").write_text(json.dumps(proof(apk, distribution)))

    def run(command, **kwargs):
        # SDK output boundary only; publisher still uses the real shared inspector.
        if command[0] == "apksigner":
            output = "Signer #1 certificate SHA-256 digest: " + SIGNER
        elif command[2] == "badging":
            output = "package: name='dev.orange.hotel' versionCode='59' versionName='17.0.29'"
        else:
            distribution = "hotel" if Path(command[3]).read_bytes().startswith(b"hotel") else "personal-test"
            output = ('  E: manifest\n    E: application\n      E: meta-data\n'
                      '        A: android:name(0x01010003)="dev.orange.hotel.DISTRIBUTION"\n'
                      f'        A: android:value(0x01010024)="{distribution}"\n')
        return CompletedProcess(command, 0, stdout=output)

    monkeypatch.setattr("android.release_identity.subprocess.run", run)
    return tmp_path


def publisher(api):
    return Publisher(api, REPOSITORY, TAG, COMMIT)


def test_github_finds_draft_on_paginated_release_list():
    api = GitHub(REPOSITORY, "synthetic-token")
    draft = {"tag_name": TAG, "draft": True, "id": 7}
    calls = []

    def request(method, path, **kwargs):
        calls.append(path)
        if "/tags/" in path:
            return None  # GitHub's by-tag endpoint excludes drafts.
        return [{"tag_name": f"v1.0.{i}"} for i in range(100)] if path.endswith("&page=1") else [draft]

    api.request = request
    assert api.release(TAG) == draft
    assert len(calls) == 3


def test_github_rejects_ambiguous_drafts():
    api = GitHub(REPOSITORY, "synthetic-token")
    api.request = lambda method, path, **kwargs: None if "/tags/" in path else [{"tag_name": TAG}, {"tag_name": TAG}]
    with pytest.raises(PublicationError, match="Multiple releases"):
        api.release(TAG)


def test_latest_is_switched_only_after_all_four_server_digests_are_verified(candidate):
    api = FakeGitHub()
    publisher(api).publish(candidate)
    assert api.calls == ["read", "create_draft", *["upload:" + name for name in ASSETS], "read", "publish_latest"]
    assert not api.value["draft"]
    assert publisher(api).already_published()


def test_interrupted_upload_remains_hidden_and_resume_uploads_only_missing_asset(candidate):
    api = FakeGitHub()
    api.fail_upload = MANIFEST
    with pytest.raises(OSError):
        publisher(api).publish(candidate)
    assert api.value["draft"]
    assert "publish_latest" not in api.calls
    assert not publisher(api).already_published()
    api.fail_upload = None
    publisher(api).publish(candidate)
    assert api.calls.count("upload:" + APK) == 1
    assert api.calls.count("publish_latest") == 1


def test_draft_resume_never_replaces_different_artifact_bytes(candidate):
    api = FakeGitHub()
    api.fail_upload = MANIFEST
    with pytest.raises(OSError):
        publisher(api).publish(candidate)
    api.fail_upload = None
    replacement = b"a different rebuild of the same version"
    (candidate / APK).write_bytes(replacement)
    (candidate / MANIFEST).write_bytes(metadata(replacement))
    (candidate / "verification-personal-test.json").write_text(json.dumps(proof(replacement, "personal-test")))
    before = list(api.calls)
    with pytest.raises(PublicationError, match="never replaced"):
        publisher(api).publish(candidate)
    assert api.calls[len(before):] == ["read"]
    assert api.value["draft"]


def test_interrupted_empty_upload_placeholder_is_recovered_without_replacing_uploaded_bytes(candidate):
    api = FakeGitHub()
    api.fail_upload = MANIFEST
    api.leave_empty_placeholder = True
    with pytest.raises(OSError):
        publisher(api).publish(candidate)
    api.fail_upload = None
    publisher(api).publish(candidate)
    assert api.calls.count("discard_empty_placeholder") == 1
    assert api.calls.count("upload:" + APK) == 1
    assert api.calls[-1] == "publish_latest"


@pytest.mark.parametrize("draft,size,checksum", [(False, 0, None), (True, 1, None), (True, 0, "sha256:" + "0" * 64)])
def test_placeholder_recovery_never_deletes_public_nonempty_or_digest_bearing_assets(candidate, draft, size, checksum):
    api = FakeGitHub()
    api.create_draft(TAG, COMMIT)
    api.value.update(draft=draft, assets=[{"id": 101, "name": APK, "state": "starter", "size": size, "digest": checksum}])
    with pytest.raises(PublicationError):
        publisher(api).publish(candidate)
    assert "discard_empty_placeholder" not in api.calls
    assert "publish_latest" not in api.calls


def test_server_digest_mismatch_never_becomes_latest(candidate):
    api = FakeGitHub()
    api.corrupt_digest = "0" * 64
    with pytest.raises(PublicationError, match="do not match"):
        publisher(api).publish(candidate)
    assert api.value["draft"]
    assert "publish_latest" not in api.calls


def test_exact_published_candidate_is_an_idempotent_noop(candidate):
    api = FakeGitHub()
    publisher(api).publish(candidate)
    before = list(api.calls)
    publisher(api).publish(candidate)
    assert api.calls[len(before):] == ["read", "read"]
    assert api.calls.count("publish_latest") == 1
    assert publisher(api).already_published()


@pytest.mark.parametrize("mode", ["different_commit", "no_tag", "wrong_tag", "prerelease"])
def test_existing_release_cannot_be_adopted_from_a_different_identity(candidate, mode):
    api = FakeGitHub()
    publisher(api).publish(candidate)
    if mode == "different_commit":
        api.target = "b" * 40
    elif mode == "no_tag":
        api.target = None
    elif mode == "wrong_tag":
        api.value["tag_name"] = "v17.0.28"
    else:
        api.value["prerelease"] = True
    before = list(api.calls)
    with pytest.raises(PublicationError):
        publisher(api).publish(candidate)
    assert api.calls[len(before):] == ["read"]


@pytest.mark.parametrize("change", ["missing", "duplicate", "unfinished", "digest", "metadata", "version", "url"])
def test_published_state_requires_complete_matching_metadata_and_assets(candidate, change):
    api = FakeGitHub()
    publisher(api).publish(candidate)
    if change == "missing":
        api.value["assets"].pop()
    elif change == "duplicate":
        api.value["assets"].append(dict(api.value["assets"][0]))
    elif change == "unfinished":
        api.value["assets"][0]["state"] = "starter"
    elif change == "digest":
        api.value["assets"][0]["digest"] = None
    elif change == "metadata":
        api.contents[MANIFEST] += b"unexpected"
    else:
        value = json.loads(api.contents[MANIFEST])
        value["version_code" if change == "version" else "url"] = 0 if change == "version" else "https://other.invalid/app.apk"
        data = json.dumps(value).encode()
        api.contents[MANIFEST] = data
        api.value["assets"][1].update(size=len(data), digest="sha256:" + digest(data))
    with pytest.raises(PublicationError):
        publisher(api).already_published()


def test_invalid_local_metadata_stops_before_any_remote_mutation(candidate):
    api = FakeGitHub()
    (candidate / MANIFEST).write_text("{}")
    with pytest.raises(PublicationError):
        publisher(api).publish(candidate)
    assert not api.calls


def test_redirects_never_forward_token_to_a_cdn_or_allow_cleartext():
    request = Request("https://api.github.com/path", headers={"Authorization": "Bearer synthetic-ci-token"})
    redirect = SafeRedirect().redirect_request(request, None, 302, "Found", {}, "https://release-assets.githubusercontent.com/file")
    assert redirect.get_header("Authorization") is None
    with pytest.raises(PublicationError):
        SafeRedirect().redirect_request(request, None, 302, "Found", {}, "http://example.invalid/file")


@pytest.mark.parametrize("name", [HOTEL_APK, HOTEL_MANIFEST, "verification-personal-test.json",
                                "verification-hotel.json"])
def test_missing_artifact_or_private_evidence_cannot_expose_existing_draft(candidate, name):
    api = FakeGitHub()
    api.create_draft(TAG, COMMIT)
    (candidate / name).unlink()
    before = list(api.calls)
    with pytest.raises(PublicationError):
        publisher(api).publish(candidate)
    assert api.calls == before
    assert api.value["draft"]


@pytest.mark.parametrize("distribution", ["personal-test", "hotel"])
@pytest.mark.parametrize("field,value", [
    ("schema", True), ("commit", "c" * 40), ("apk_sha256", "0" * 64),
    ("package_name", "other.package"), ("version_code", 58), ("version_code", True),
    ("signer_sha256", "c" * 64), ("signer_sha256", "B" * 64), ("distribution", "unknown"),
    ("ui_ready", False), ("bridge", False), ("mic_off", False),
    ("retained_preferences", False), ("retained_voice_key", 1), ("extra", "private data"),
])
def test_stale_mismatched_or_incomplete_proof_leaves_draft_hidden(candidate, distribution, field, value):
    path = candidate / f"verification-{distribution}.json"
    evidence = json.loads(path.read_bytes())
    evidence[field] = value
    path.write_text(json.dumps(evidence))
    api = FakeGitHub()
    api.create_draft(TAG, COMMIT)
    with pytest.raises(PublicationError, match="proof"):
        publisher(api).publish(candidate)
    assert api.value["draft"]
    assert not any(call.startswith("upload:") for call in api.calls)
    assert "publish_latest" not in api.calls


def test_cross_distribution_proof_cannot_authorize_other_apk(candidate):
    (candidate / "verification-hotel.json").write_bytes((candidate / "verification-personal-test.json").read_bytes())
    api = FakeGitHub()
    with pytest.raises(PublicationError, match="proof"):
        publisher(api).publish(candidate)
    assert not api.calls


@pytest.mark.parametrize("field,value", [
    ("distribution", None), ("distribution", "personal-test"), ("version_code", 60),
    ("sha256", "0" * 64), ("url", f"https://github.com/{REPOSITORY}/releases/download/{TAG}/{APK}"),
])
def test_invalid_hotel_metadata_cannot_use_valid_testing_pair(candidate, field, value):
    path = candidate / HOTEL_MANIFEST
    manifest = json.loads(path.read_bytes())
    if value is None:
        del manifest[field]
    else:
        manifest[field] = value
    path.write_text(json.dumps(manifest))
    api = FakeGitHub()
    with pytest.raises(PublicationError):
        publisher(api).publish(candidate)
    assert not api.calls


def test_metadata_and_proof_cannot_relabel_actual_testing_apk_as_hotel(candidate):
    apk = (candidate / APK).read_bytes()
    (candidate / HOTEL_APK).write_bytes(apk)
    (candidate / HOTEL_MANIFEST).write_bytes(metadata(apk, "hotel"))
    (candidate / "verification-hotel.json").write_text(json.dumps(proof(apk, "hotel")))
    api = FakeGitHub()
    with pytest.raises(PublicationError, match="actual signed APK"):
        publisher(api).publish(candidate)
    assert not api.calls


def test_apksigner_failure_prevents_any_publication(candidate, monkeypatch):
    def run(command, **kwargs):
        raise CalledProcessError(1, command)

    monkeypatch.setattr("android.release_identity.subprocess.run", run)
    api = FakeGitHub()
    with pytest.raises(PublicationError):
        publisher(api).publish(candidate)
    assert not api.calls


def test_hotel_upload_interruption_resumes_without_replacing_personal_pair(candidate):
    api = FakeGitHub()
    api.fail_upload = HOTEL_MANIFEST
    with pytest.raises(OSError):
        publisher(api).publish(candidate)
    assert api.value["draft"]
    assert set(api.contents) == {APK, MANIFEST, HOTEL_APK}
    api.fail_upload = None
    publisher(api).publish(candidate)
    for name in (APK, MANIFEST, HOTEL_APK):
        assert api.calls.count("upload:" + name) == 1
    assert api.calls.count("publish_latest") == 1
    assert set(api.contents) == set(ASSETS)  # Private proof is never uploaded.


@pytest.mark.parametrize("change", ["historical", "distribution", "code", "url", "empty", "stale"])
def test_remote_hotel_pair_is_required_and_bound_to_its_exact_metadata(candidate, change):
    api = FakeGitHub()
    publisher(api).publish(candidate)
    if change == "historical":
        api.value["assets"] = [item for item in api.value["assets"] if item["name"] in (APK, MANIFEST)]
    elif change == "empty":
        next(item for item in api.value["assets"] if item["name"] == HOTEL_APK)["size"] = 0
    elif change == "stale":
        next(item for item in api.value["assets"] if item["name"] == HOTEL_APK)["digest"] = "sha256:" + "0" * 64
    else:
        manifest = json.loads(api.contents[HOTEL_MANIFEST])
        if change == "distribution":
            manifest["distribution"] = "personal-test"
        elif change == "code":
            manifest["version_code"] = 60
        else:
            manifest["url"] = f"https://github.com/{REPOSITORY}/releases/download/{TAG}/{APK}"
        data = json.dumps(manifest).encode()
        api.contents[HOTEL_MANIFEST] = data
        next(item for item in api.value["assets"] if item["name"] == HOTEL_MANIFEST).update(
            size=len(data), digest="sha256:" + digest(data))
    with pytest.raises(PublicationError):
        publisher(api).already_published()


@pytest.mark.parametrize("field", ["schema", "commit", "apk_sha256", "package_name", "version_code",
                                 "signer_sha256", "distribution", "ui_ready", "bridge", "mic_off",
                                 "retained_preferences", "retained_voice_key"])
def test_private_proof_requires_every_exact_schema_field(candidate, field):
    path = candidate / "verification-hotel.json"
    evidence = json.loads(path.read_bytes())
    del evidence[field]
    path.write_text(json.dumps(evidence))
    api = FakeGitHub()
    with pytest.raises(PublicationError, match="proof"):
        publisher(api).publish(candidate)
    assert not api.calls


def test_each_valid_individual_proof_still_requires_one_shared_signer(candidate, monkeypatch):
    from android import release_identity

    real_run = release_identity.subprocess.run

    def run(command, **kwargs):
        if command[0] == "apksigner" and Path(command[-1]).name == HOTEL_APK:
            return CompletedProcess(command, 0, stdout="Signer #1 certificate SHA-256 digest: " + "c" * 64)
        return real_run(command, **kwargs)

    monkeypatch.setattr(release_identity.subprocess, "run", run)
    path = candidate / "verification-hotel.json"
    evidence = json.loads(path.read_bytes())
    evidence["signer_sha256"] = "c" * 64
    path.write_text(json.dumps(evidence))
    api = FakeGitHub()
    with pytest.raises(PublicationError, match="signing identity"):
        publisher(api).publish(candidate)
    assert not api.calls
