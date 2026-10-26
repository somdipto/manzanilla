"""An isolated GitHub model verifies draft/resume/publication order and artifact identity."""
import copy
import json
from pathlib import Path
from urllib.request import Request

import pytest

from scripts.publish_release import APK, MANIFEST, GitHub, PublicationError, Publisher, SafeRedirect, digest


REPOSITORY = "owner/hotel"
TAG = "v17.0.29"
COMMIT = "a" * 40


def metadata(apk):
    return json.dumps({"schema": 1, "tag": TAG, "version_name": "17.0.29", "version_code": 59,
                       "package_name": "dev.orange.hotel", "sha256": digest(apk),
                       "url": f"https://github.com/{REPOSITORY}/releases/download/{TAG}/{APK}"}).encode()


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
        assert {row["name"] for row in self.value["assets"]} == {APK, MANIFEST}
        self.calls.append("publish_latest")
        self.value["draft"] = False
        return copy.deepcopy(self.value)

    def remove_empty_placeholder(self, asset_id):
        assert self.value["draft"]
        asset = next(row for row in self.value["assets"] if row.get("id") == asset_id)
        assert asset["state"] == "starter" and asset["size"] == 0 and not asset.get("digest")
        self.calls.append("discard_empty_placeholder")
        self.value["assets"].remove(asset)

    def public_manifest(self, tag):
        assert not self.value["draft"]
        return self.contents[MANIFEST]


@pytest.fixture
def candidate(tmp_path):
    apk = b"synthetic signed APK bytes; real APK qualification runs before publication"
    (tmp_path / APK).write_bytes(apk)
    (tmp_path / MANIFEST).write_bytes(metadata(apk))
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


def test_latest_is_switched_only_after_both_server_digests_are_verified(candidate):
    api = FakeGitHub()
    publisher(api).publish(candidate)
    assert api.calls == ["read", "create_draft", "upload:" + APK, "upload:" + MANIFEST, "read", "publish_latest"]
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
