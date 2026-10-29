"""Private disposable-emulator proof for the exact signed release candidate.

Invoked only by verify-signed-upgrade.sh. Captured subprocess output is never
published verbatim: device storage, logcat, intents and provider errors are not
diagnostics for this gate.
"""
from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from android.release_identity import inspect

PACKAGE = "dev.orange.hotel"
EVIDENCE = ROOT / "work/signed-upgrade-qa"


def run(*command: str, timeout: int = 90) -> str:
    return subprocess.run(command, check=True, capture_output=True, text=True,
                          timeout=timeout, cwd=ROOT).stdout


def digest(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def require(condition: bool) -> None:
    if not condition:
        raise ValueError("Signed upgrade proof requirement was not met")


def instrument(case: str, distribution: str, marker: str, expected: str) -> None:
    output = run("adb", "shell", "am", "instrument", "-w", "-r", "-e", "isolated", "1",
                 "-e", "case", case, "-e", "distribution", distribution, "-e", "marker", marker,
                 f"{PACKAGE}.test/com.agentdeck.hotel.UpgradeDataFixture", timeout=75)
    # Retain only our fixed, value-free assertion stream and framework result code.
    # Never retain arbitrary instrumentation exceptions, status bundles or logs.
    lines = [line.removeprefix("INSTRUMENTATION_STATUS: stream=")
             .removeprefix("INSTRUMENTATION_RESULT: stream=").strip()
             for line in output.splitlines()]
    safe = [line for line in lines if line == expected or
            line == f"FAIL case={case} upgrade data fixture did not complete" or
            re.fullmatch(r"INSTRUMENTATION_CODE: -?\d+", line)]
    (EVIDENCE / f"data-{case}.txt").write_text("\n".join(safe) + "\n")
    require(lines.count(expected) == 1 and lines.count("INSTRUMENTATION_CODE: -1") == 1)
    require(not any(line.startswith("FAIL ") or line.startswith("INSTRUMENTATION_FAILED:")
                    for line in lines))
    print(expected, flush=True)


def install(apk: Path, *, replace: bool = False, fixture: bool = False) -> None:
    flags = ["-r"] if replace else ["-t"] if fixture else []
    output = run("adb", "install", *flags, str(apk), timeout=180)
    require("Success" in output.splitlines())


def installed_time() -> str:
    output = run("adb", "shell", "dumpsys", "package", PACKAGE)
    times = re.findall(r"^\s*firstInstallTime=(.+)$", output, re.MULTILINE)
    require(len(times) == 1)
    return times[0].strip()


def main() -> int:
    EVIDENCE.mkdir(parents=True, exist_ok=True)
    # A failed rerun cannot leave a previous successful proof or screenshot.
    (EVIDENCE / "verification.json").unlink(missing_ok=True)
    (EVIDENCE / "reception.png").unlink(missing_ok=True)
    stage = "environment"
    try:
        distribution = os.environ["RELEASE_DISTRIBUTION"]
        require(distribution in ("personal-test", "hotel"))
        require(re.fullmatch(r"emulator-\d+", os.environ["ANDROID_SERIAL"]) is not None)
        commit = os.environ["GITHUB_SHA"]
        require(re.fullmatch(r"[0-9a-f]{40}", commit) is not None)
        require(run("git", "rev-parse", "HEAD").strip() == commit)
        require(run("adb", "shell", "getprop", "ro.kernel.qemu").strip() == "1")
        require(not run("adb", "shell", "pm", "list", "packages", PACKAGE).strip())
        temporary = Path(os.environ["RUNNER_TEMP"])
        previous = list((temporary / "previous").glob("*.apk"))
        require(len(previous) == 1 and previous[0].is_file())
        if distribution == "personal-test":
            apk_name, metadata_name = "Manzanilla-Reception.apk", "release-metadata.json"
        else:
            apk_name, metadata_name = "Manzanilla-Hotel.apk", "hotel-release-metadata.json"
        candidate = temporary / "candidate" / apk_name
        fixture = temporary / "upgrade-fixture/hotel-release-androidTest.apk"
        stage = "candidate-identity"
        identity = inspect(candidate, require_distribution=True)
        prior = inspect(previous[0], require_distribution=False)
        fixture_identity = inspect(fixture, require_distribution=False)
        metadata = json.loads((temporary / "candidate" / metadata_name).read_text())
        apk_sha256 = digest(candidate)
        require(identity.package == prior.package == PACKAGE)
        require(identity.distribution == distribution)
        require(prior.distribution == distribution or
                (distribution == "hotel" and prior.distribution == "personal-test"))
        require(identity.code > prior.code)
        require(identity.signers == prior.signers == fixture_identity.signers and len(identity.signers) == 1)
        require(fixture_identity.package == f"{PACKAGE}.test")
        require(identity.name == (ROOT / "android/VERSION").read_text().strip())
        require(identity.code == int((ROOT / "android/VERSION_CODE").read_text().strip()))
        require(metadata["schema"] == 1 and metadata["package_name"] == identity.package and
                metadata["version_code"] == identity.code and metadata["version_name"] == identity.name and
                metadata["distribution"] == distribution and metadata["sha256"] == apk_sha256)
        (EVIDENCE / "candidate-identity.json").write_text(json.dumps({
            "package_name": identity.package, "version_code": identity.code,
            "version_name": identity.name, "distribution": distribution,
            "apk_sha256": apk_sha256, "signer_sha256": next(iter(identity.signers)),
            "commit": commit,
        }, indent=2) + "\n")
        stage = "install-previous"
        install(previous[0])
        install(fixture, fixture=True)
        stage = "seed"
        retained = "preferences=true private_file=true encrypted_marker=true voice_credential=true"
        instrument("seed", prior.distribution, commit,
                   f"PASS case=seed {retained} distribution={prior.distribution}")
        before = installed_time()
        stage = "in-place-install"
        install(candidate, replace=True)
        require(installed_time() == before)
        # Inspect the exact installed bytes as well as the supplied release file.
        paths = run("adb", "shell", "pm", "path", PACKAGE).splitlines()
        require(len(paths) == 1 and paths[0].startswith("package:"))
        with tempfile.TemporaryDirectory(prefix="signed-upgrade-installed-") as directory:
            installed = Path(directory) / "installed.apk"
            run("adb", "pull", paths[0].removeprefix("package:").strip(), str(installed))
            require(digest(installed) == apk_sha256)
            require(inspect(installed, require_distribution=True) == identity)
        stage = "retained-data"
        instrument("verify", distribution, commit,
                   f"PASS case=verify {retained} distribution={distribution}")
        stage = "bundled-credential-policy"
        bundled = "bundled_key=true direct_ready=true" if distribution == "personal-test" else \
                  "bundled_key=false managed_required=true"
        instrument("bundled", distribution, commit,
                   f"PASS case=bundled {bundled} distribution={distribution}")
        stage = "reception"
        run("adb", "shell", "settings", "put", "secure", "immersive_mode_confirmations", "confirmed")
        instrument("reception", distribution, commit,
                   f"PASS case=reception ui_ready=true bridge=true mic_off=true distribution={distribution}")
        run("adb", "pull", f"/sdcard/Android/data/{PACKAGE}.test/files/signed-reception.png",
            str(EVIDENCE / "reception.png"))
        require((EVIDENCE / "reception.png").stat().st_size > 0)
        proof = {
            "schema": 1, "commit": commit, "apk_sha256": apk_sha256,
            "package_name": identity.package, "version_code": identity.code,
            "signer_sha256": next(iter(identity.signers)), "distribution": distribution,
            "ui_ready": True, "bridge": True, "mic_off": True,
            "retained_preferences": True, "retained_voice_key": True,
        }
        (EVIDENCE / "verification.json").write_text(json.dumps(proof, indent=2) + "\n")
        (EVIDENCE / "result.txt").write_text(f"PASS signed upgrade distribution={distribution}\n")
        return 0
    except Exception:
        # No arbitrary exception text: subprocess failures can carry device data.
        message = f"FAIL signed upgrade stage={stage}\n"
        (EVIDENCE / "result.txt").write_text(message)
        print(message, file=sys.stderr, end="")
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
