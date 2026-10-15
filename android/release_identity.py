"""Validate signed APK publication identity and emit the updater's immutable manifest.

Requires SDK aapt/apksigner on PATH. This helper never signs, publishes or calls a provider.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
from dataclasses import dataclass


@dataclass(frozen=True)
class Identity:
    package: str
    name: str
    code: int
    signers: frozenset[str]


def semver(value: str) -> tuple[int, int, int]:
    if not re.fullmatch(r"(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)", value):
        raise ValueError("Published VERSION must be stable major.minor.patch SemVer")
    return tuple(int(part) for part in value.split("."))


def inspect(apk: Path) -> Identity:
    # apksigner must complete successfully before any untrusted manifest fields are used.
    signature = subprocess.run(["apksigner", "verify", "--verbose", "--print-certs", str(apk)],
                               check=True, text=True, capture_output=True).stdout
    signers = frozenset(re.findall(r"^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})$",
                                   signature, re.MULTILINE))
    if not signers:
        raise ValueError("APK has no verified signer certificates")
    badging = subprocess.run(["aapt", "dump", "badging", str(apk)], check=True,
                            text=True, capture_output=True).stdout
    match = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging, re.MULTILINE)
    if not match:
        raise ValueError("Cannot read APK package/version")
    return Identity(match[1], match[3], int(match[2]), frozenset(s.lower() for s in signers))


def validate(candidate: Identity, previous: Identity, name: str, code: int, tag: str) -> None:
    expected = semver(name)
    if tag != f"v{name}":
        raise ValueError("Publication tag does not match android/VERSION")
    if candidate.package != "dev.orange.hotel" or previous.package != candidate.package:
        raise ValueError("Application identity must remain dev.orange.hotel")
    if candidate.name != name or candidate.code != code:
        raise ValueError("APK name/code does not match the explicit version files")
    if code <= max(34, previous.code):
        raise ValueError("APK code must exceed published code 34 and the current published APK")
    if expected <= semver(previous.name):
        raise ValueError("Semantic version must exceed the currently published APK")
    if not candidate.signers or candidate.signers != previous.signers:
        raise ValueError("APK signing fingerprint differs from the published installation")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--previous-apk", type=Path, required=True)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--repository", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).parent
    name = (root / "VERSION").read_text().strip()
    code = int((root / "VERSION_CODE").read_text().strip())
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", args.repository):
        raise ValueError("Invalid GitHub repository")
    candidate = inspect(args.apk)
    validate(candidate, inspect(args.previous_apk), name, code, args.tag)
    with args.apk.open("rb") as apk:
        digest = hashlib.file_digest(apk, "sha256").hexdigest()
    args.output.write_text(json.dumps({
        "schema": 1, "tag": args.tag,
        "url": f"https://github.com/{args.repository}/releases/download/{args.tag}/{args.apk.name}",
        "package_name": candidate.package, "version_name": candidate.name,
        "version_code": candidate.code, "sha256": digest,
    }, indent=2) + "\n")


if __name__ == "__main__":
    main()
