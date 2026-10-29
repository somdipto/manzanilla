"""Enforce explicit APK versions and select exact previous release artifacts.

Version checks read committed Git trees only. Previous-artifact download is an
explicit, read-only GitHub operation used by isolated signed-upgrade jobs.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import subprocess
import sys
from collections.abc import Iterable


VERSION_PATHS = frozenset({"android/VERSION", "android/VERSION_CODE"})
RELEASE_FILES = frozenset({
    "package.json", "package-lock.json",
    "shared/reception-locales.json",
    "scripts/build-reception.cjs", "scripts/release_policy.py", "scripts/release_policy_check.py",
    "scripts/check_fish_auth.py",
    "scripts/publish_release.py", "scripts/verify-signed-upgrade.sh",
    "scripts/signed_upgrade_proof.py",
    "scripts/run-updater-emulator.sh",
    ".github/workflows/publish.yml", ".github/workflows/release.yml",
    ".github/workflows/verify.yml",
})
MAX_VERSION_CODE = 2_100_000_000


def release_impact(path: str) -> bool:
    return path.startswith(("android/", "ui/reception/")) or path in RELEASE_FILES


def stable_version(value: str) -> tuple[int, int, int]:
    if not re.fullmatch(r"(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)", value):
        raise ValueError("android/VERSION must be a stable major.minor.patch version")
    return tuple(int(part) for part in value.split("."))


def version_code(value: str) -> int:
    if not re.fullmatch(r"[1-9][0-9]*", value):
        raise ValueError("android/VERSION_CODE must be a positive canonical integer")
    result = int(value)
    if result > MAX_VERSION_CODE:
        raise ValueError("android/VERSION_CODE exceeds Android's supported publication limit")
    return result


def validate_change(paths: Iterable[str], base_name: str, base_code: str,
                    head_name: str, head_code: str) -> bool:
    changed = frozenset(paths)
    required = bool(changed & VERSION_PATHS) or any(release_impact(path) for path in changed)
    if not required:
        return False
    if not VERSION_PATHS <= changed:
        raise ValueError("App/release changes must include both android/VERSION and android/VERSION_CODE")
    if stable_version(head_name) <= stable_version(base_name):
        raise ValueError("android/VERSION must increase above the PR base")
    if version_code(head_code) <= version_code(base_code):
        raise ValueError("android/VERSION_CODE must increase above the PR base")
    return True


def git(repository: Path, *arguments: str) -> str:
    result = subprocess.run(["git", "-C", str(repository), *arguments], check=True,
                            capture_output=True, text=True)
    return result.stdout


def check_repository(repository: Path, base: str, head: str) -> bool:
    # Resolve refs first; later commands receive only full commit IDs, not options.
    base_sha = git(repository, "rev-parse", "--verify", "--end-of-options", base + "^{commit}").strip()
    head_sha = git(repository, "rev-parse", "--verify", "--end-of-options", head + "^{commit}").strip()
    # Include both old and new paths of renames. A moved Android/build file must
    # not evade the release policy merely because its new path looks like docs.
    status = git(repository, "diff", "--name-status", "-z", base_sha, head_sha, "--").split("\0")
    paths = []
    index = 0
    while index < len(status) and status[index]:
        operation = status[index]
        index += 1
        count = 2 if operation.startswith(("R", "C")) else 1
        paths.extend(status[index:index + count])
        index += count
    if not (frozenset(paths) & VERSION_PATHS) and not any(release_impact(path) for path in paths):
        return False
    values = [git(repository, "show", f"{revision}:{path}").strip()
              for revision in (base_sha, head_sha)
              for path in ("android/VERSION", "android/VERSION_CODE")]
    return validate_change(paths, *values)


def _release_assets(release: dict, *, published: bool) -> dict:
    if not isinstance(release, dict):
        raise ValueError("Malformed GitHub release metadata")
    if (type(release.get("id")) is not int or type(release.get("draft")) is not bool
            or type(release.get("prerelease")) is not bool):
        raise ValueError("Incomplete GitHub release metadata")
    tag = release.get("tag_name")
    if not isinstance(tag, str) or not tag or (published and not re.fullmatch(r"v[0-9]+\.[0-9]+\.[0-9]+", tag)):
        raise ValueError("Previous stable release tag is missing or invalid")
    if published and (release["draft"] or release["prerelease"]):
        raise ValueError("Previous release is not a published stable release")
    assets = release.get("assets")
    if not isinstance(assets, list):
        raise ValueError("Missing complete previous-release asset list")
    result = {}
    for asset in assets:
        if not isinstance(asset, dict) or not isinstance(asset.get("name"), str):
            raise ValueError("Malformed previous-release asset")
        if asset["name"] in result:
            raise ValueError("Ambiguous previous-release asset")
        result[asset["name"]] = asset
    return result


def previous_asset(latest: dict, history: list, distribution: str) -> tuple[str, str, int]:
    """Allow hotel bootstrap only when complete published history has no hotel APK."""
    if distribution not in ("personal-test", "hotel"):
        raise ValueError("Invalid release distribution")
    assets = _release_assets(latest, published=True)
    name = "Manzanilla-Reception.apk" if distribution == "personal-test" else "Manzanilla-Hotel.apk"
    if name not in assets and distribution == "hotel":
        if not isinstance(history, list) or not history:
            raise ValueError("Cannot establish first hotel release from incomplete history")
        found_latest = False
        for release in history:
            release_assets = _release_assets(release, published=False)
            found_latest |= release["id"] == latest["id"]
            if not release["draft"] and ("Manzanilla-Hotel.apk" in release_assets or "hotel-release-metadata.json" in release_assets):
                raise ValueError("A prior hotel release exists; personal-test bootstrap is forbidden")
        if not found_latest:
            raise ValueError("Latest release is absent from complete release history")
        name = "Manzanilla-Reception.apk"
    asset = assets.get(name)
    if (asset is None or type(asset.get("size")) is not int or asset["size"] <= 0
            or asset.get("state") != "uploaded"):
        raise ValueError("Exact previous-distribution APK is missing or incomplete")
    return latest["tag_name"], name, asset["size"]


def download_previous(repository: str, directory: Path, distribution: str) -> None:
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
        raise ValueError("Invalid GitHub repository")
    directory.mkdir(parents=True, exist_ok=True)
    if any(directory.iterdir()):
        raise ValueError("Previous-artifact directory must be empty")
    def github(*arguments):
        return subprocess.run(["gh", *arguments], check=True, capture_output=True, text=True).stdout
    try:
        latest = json.loads(github("api", f"repos/{repository}/releases/latest"))
        history = []
        assets = _release_assets(latest, published=True)
        if distribution == "hotel" and "Manzanilla-Hotel.apk" not in assets:
            pages = json.loads(github("api", "--paginate", "--slurp",
                                     f"repos/{repository}/releases?per_page=100"))
            if not isinstance(pages, list) or not pages or any(not isinstance(page, list) for page in pages):
                raise ValueError("Cannot establish complete published release history")
            history = [release for page in pages for release in page]
        tag, name, size = previous_asset(latest, history, distribution)
        github("release", "download", tag, "--repo", repository, "--pattern", name,
               "--dir", str(directory))
    except (json.JSONDecodeError, subprocess.CalledProcessError, OSError) as from_error:
        raise ValueError("Cannot retrieve complete previous-release metadata or exact APK") from from_error
    files = list(directory.iterdir())
    if len(files) != 1 or files[0].name != name or not files[0].is_file() or files[0].stat().st_size != size:
        raise ValueError("Downloaded previous APK does not match selected release asset")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", help="PR base commit, or main's pre-merge commit")
    parser.add_argument("--head", default="HEAD", help="Committed candidate tree")
    parser.add_argument("--repository", type=Path, default=Path("."))
    parser.add_argument("--download-previous", type=Path, metavar="DIRECTORY")
    parser.add_argument("--github-repository")
    parser.add_argument("--distribution", choices=("personal-test", "hotel"))
    args = parser.parse_args()
    if args.download_previous is not None:
        if args.base or not args.github_repository or not args.distribution:
            parser.error("download requires --github-repository and --distribution, without --base")
        try:
            download_previous(args.github_repository, args.download_previous, args.distribution)
        except ValueError as error:
            print("Previous release failed: " + str(error), file=sys.stderr)
            return 1
        print("PASS: exact previous-distribution APK selected")
        return 0
    if not args.base or args.github_repository or args.distribution:
        parser.error("version check requires --base")
    try:
        required = check_repository(args.repository, args.base, args.head)
    except (ValueError, subprocess.CalledProcessError) as error:
        message = str(error) if isinstance(error, ValueError) else "Cannot read complete base/head version trees"
        print("Release policy failed: " + message, file=sys.stderr)
        return 1
    print("PASS: explicit app version and code increase" if required else "PASS: no APK release-impacting changes")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
