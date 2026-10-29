"""Require explicit increasing APK versions for release-impacting PR changes.

Reads committed Git trees only. It never modifies a branch, contacts a service,
signs an APK or publishes a release.
"""
from __future__ import annotations

import argparse
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
    "scripts/publish_release.py", "scripts/verify-signed-upgrade.sh",
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


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", required=True, help="PR base commit, or main's pre-merge commit")
    parser.add_argument("--head", default="HEAD", help="Committed candidate tree")
    parser.add_argument("--repository", type=Path, default=Path("."))
    args = parser.parse_args()
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
