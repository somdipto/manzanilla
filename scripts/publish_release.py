"""Publish verified APKs through a draft; never expose or replace incomplete releases.

The signed build and release_identity.py establish APK signing/version continuity.
This helper makes the final publication atomic for latest-release consumers and
supports resuming only the same tag, commit and exact artifact bytes.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
from urllib.error import HTTPError
from urllib.parse import quote, urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener


APK = "Manzanilla-Reception.apk"
MANIFEST = "release-metadata.json"
ASSETS = (APK, MANIFEST)
SEMVER = r"(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)"


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


class PublicationError(RuntimeError):
    pass


class SafeRedirect(HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, newurl):
        target = urlsplit(newurl)
        if target.scheme != "https":
            raise PublicationError("Release requests require HTTPS redirects")
        redirected = super().redirect_request(request, fp, code, message, headers, newurl)
        if redirected is not None and target.netloc != urlsplit(request.full_url).netloc:
            redirected.remove_header("Authorization")
        return redirected


class GitHub:
    def __init__(self, repository: str, token: str):
        self.repository = repository
        self.token = token
        self.opener = build_opener(SafeRedirect())

    def request(self, method, path, *, payload=None, data=None, missing=False, upload=False):
        host = "https://uploads.github.com" if upload else "https://api.github.com"
        headers = {"Authorization": "Bearer " + self.token,
                   "Accept": "application/vnd.github+json", "User-Agent": "Manzanilla-release",
                   "X-GitHub-Api-Version": "2022-11-28"}
        if payload is not None:
            data = json.dumps(payload).encode()
            headers["Content-Type"] = "application/json"
        elif data is not None:
            headers["Content-Type"] = "application/octet-stream"
        request = Request(host + path, data=data, headers=headers, method=method)
        try:
            with self.opener.open(request, timeout=120) as response:
                result = response.read(4 * 1024 * 1024)
                return json.loads(result) if result else None
        except HTTPError as error:
            if missing and error.code == 404:
                return None
            # Do not copy provider bodies, headers or request credentials into logs.
            raise PublicationError(f"GitHub publication request failed with HTTP {error.code}") from None

    def release(self, tag):
        release = self.request("GET", f"/repos/{self.repository}/releases/tags/{quote(tag, safe='')}", missing=True)
        if release is not None:
            return release
        # GitHub's release-by-tag endpoint excludes drafts, including drafts
        # visible to the authenticated publisher. Search the paginated list so
        # interrupted uploads can resume without creating a duplicate draft.
        matches = []
        for page in range(1, 1001):
            releases = self.request("GET", f"/repos/{self.repository}/releases?per_page=100&page={page}")
            if not isinstance(releases, list):
                raise PublicationError("Invalid GitHub release list")
            matches.extend(item for item in releases if isinstance(item, dict) and item.get("tag_name") == tag)
            if len(releases) < 100:
                if len(matches) > 1:
                    raise PublicationError("Multiple releases share the candidate tag")
                return matches[0] if matches else None
        raise PublicationError("GitHub release list exceeded its pagination limit")

    def tag_commit(self, tag):
        ref = self.request("GET", f"/repos/{self.repository}/git/ref/tags/{quote(tag, safe='')}", missing=True)
        if ref is None:
            return None
        obj = ref["object"]
        for _ in range(5):
            if obj["type"] == "commit":
                return obj["sha"]
            if obj["type"] != "tag" or not re.fullmatch(r"[0-9a-f]{40}", obj["sha"]):
                break
            obj = self.request("GET", f"/repos/{self.repository}/git/tags/{obj['sha']}")["object"]
        raise PublicationError("Release tag does not resolve to one immutable commit")

    def create_draft(self, tag, commit):
        return self.request("POST", f"/repos/{self.repository}/releases", payload={
            "tag_name": tag, "target_commitish": commit, "name": "Manzanilla " + tag[1:],
            "body": f"Verified candidate {commit}. Physical-device, voice entitlement and real-service acceptance remain separate release gates.",
            "draft": True, "prerelease": False, "make_latest": "false"})

    def upload(self, release_id, name, data):
        return self.request("POST", f"/repos/{self.repository}/releases/{release_id}/assets?name={quote(name, safe='')}",
                            data=data, upload=True)

    def remove_empty_placeholder(self, asset_id):
        self.request("DELETE", f"/repos/{self.repository}/releases/assets/{asset_id}")

    def publish(self, release_id):
        return self.request("PATCH", f"/repos/{self.repository}/releases/{release_id}",
                            payload={"draft": False, "prerelease": False, "make_latest": "true"})

    def public_manifest(self, tag):
        # This is a public asset download. No authorization is sent to the CDN.
        url = f"https://github.com/{self.repository}/releases/download/{tag}/{MANIFEST}"
        with self.opener.open(Request(url, headers={"User-Agent": "Manzanilla-release"}), timeout=60) as response:
            data = response.read(64 * 1024 + 1)
        if len(data) > 64 * 1024:
            raise PublicationError("Published release metadata exceeds its size limit")
        return data


class Publisher:
    def __init__(self, api, repository: str, tag: str, commit: str):
        if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
            raise PublicationError("Invalid repository identity")
        if not re.fullmatch("v" + SEMVER, tag) or not re.fullmatch(r"[0-9a-f]{40}", commit):
            raise PublicationError("Publication requires a stable version tag and exact commit")
        self.api, self.repository, self.tag, self.commit = api, repository, tag, commit

    def _release(self):
        release = self.api.release(self.tag)
        target = self.api.tag_commit(self.tag)
        if target is not None and target != self.commit:
            raise PublicationError("Existing release tag points to a different commit")
        if release is not None:
            if target != self.commit or release.get("tag_name") != self.tag or release.get("prerelease") is not False:
                raise PublicationError("Release identity does not match the candidate")
            if not isinstance(release.get("draft"), bool) or type(release.get("id")) is not int:
                raise PublicationError("Invalid release state")
        return release

    @staticmethod
    def _assets(release):
        assets = {}
        for asset in release.get("assets", []):
            name = asset.get("name")
            if name not in ASSETS:
                continue
            if name in assets or asset.get("state") != "uploaded" or type(asset.get("size")) is not int or asset["size"] <= 0:
                raise PublicationError("Release contains incomplete or duplicate updater assets")
            value = asset.get("digest", "")
            if not isinstance(value, str) or not re.fullmatch(r"sha256:[0-9a-f]{64}", value):
                raise PublicationError("Release asset has no verified SHA-256 digest")
            assets[name] = asset
        return assets

    def _manifest(self, data, apk_digest):
        try:
            manifest = json.loads(data)
            valid = (type(manifest.get("schema")) is int and manifest["schema"] == 1 and manifest.get("tag") == self.tag
                     and manifest.get("version_name") == self.tag[1:]
                     and manifest.get("package_name") == "dev.orange.hotel"
                     and type(manifest.get("version_code")) is int and 0 < manifest["version_code"] <= 2_100_000_000
                     and manifest.get("sha256") == apk_digest
                     and manifest.get("url") == f"https://github.com/{self.repository}/releases/download/{self.tag}/{APK}")
        except (ValueError, TypeError, AttributeError):
            valid = False
        if not valid:
            raise PublicationError("Release metadata does not describe this immutable APK")

    def already_published(self):
        release = self._release()
        if release is None or release["draft"]:
            return False
        assets = self._assets(release)
        if set(assets) != set(ASSETS):
            raise PublicationError("Published release is incomplete; it cannot be treated as a successful rerun")
        data = self.api.public_manifest(self.tag)
        if digest(data) != assets[MANIFEST]["digest"].removeprefix("sha256:") or len(data) != assets[MANIFEST]["size"]:
            raise PublicationError("Published metadata does not match its asset digest")
        self._manifest(data, assets[APK]["digest"].removeprefix("sha256:"))
        return True

    def publish(self, directory: Path):
        files = {name: (directory / name).read_bytes() for name in ASSETS}
        if not files[APK]:
            raise PublicationError("The verified APK is empty")
        self._manifest(files[MANIFEST], digest(files[APK]))
        release = self._release()
        if release is None:
            if self.api.tag_commit(self.tag) != self.commit:
                raise PublicationError("Create and verify the immutable version tag before publishing")
            release = self.api.create_draft(self.tag, self.commit)
        if release["draft"]:
            # GitHub may leave an empty `starter` placeholder after a failed
            # upload. It contains no immutable artifact bytes. Never remove a
            # nonempty/uploaded/digest-bearing asset or any public-release asset.
            placeholders = [asset for asset in release.get("assets", [])
                            if asset.get("name") in ASSETS and asset.get("state") == "starter"
                            and type(asset.get("size")) is int and asset["size"] == 0
                            and asset.get("digest") in (None, "")]
            for asset in placeholders:
                if type(asset.get("id")) is not int or asset["id"] <= 0:
                    raise PublicationError("Invalid empty upload placeholder identity")
                self.api.remove_empty_placeholder(asset["id"])
            if placeholders:
                release = self._release()
                if release is None or not release["draft"]:
                    raise PublicationError("Draft state changed while recovering an empty upload")
            existing = self._assets(release)
            for name, data in files.items():
                if name in existing:
                    if existing[name]["digest"] != "sha256:" + digest(data) or existing[name]["size"] != len(data):
                        raise PublicationError("An existing draft asset differs; immutable assets are never replaced")
                else:
                    self.api.upload(release["id"], name, data)
        # Re-read server digests after all uploads. Latest still points to the old release.
        release = self._release()
        if release is None:
            raise PublicationError("Release disappeared before publication")
        assets = self._assets(release)
        for name, data in files.items():
            if name not in assets or assets[name]["digest"] != "sha256:" + digest(data) or assets[name]["size"] != len(data):
                raise PublicationError("Uploaded assets do not match the verified candidate")
        if release["draft"]:
            published = self.api.publish(release["id"])
            if published.get("draft") is not False:
                raise PublicationError("GitHub did not confirm final publication")
        return "published"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", required=True)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--directory", type=Path)
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--github-output", type=Path)
    args = parser.parse_args()
    token = os.environ.get("GH_TOKEN", "")
    if not token:
        parser.error("GH_TOKEN must be configured for the publication workflow")
    publisher = Publisher(GitHub(args.repository, token), args.repository, args.tag, args.commit)
    if args.check:
        result = "true" if publisher.already_published() else "false"
        if args.github_output:
            with args.github_output.open("a") as output:
                output.write("published=" + result + "\n")
        print("Complete release already published: " + result)
    else:
        if args.directory is None:
            parser.error("--directory is required for publication")
        publisher.publish(args.directory)
        print("Verified immutable release is published")


if __name__ == "__main__":
    main()
