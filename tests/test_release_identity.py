"""Publication gates exercise values read from APKs, without signing or contacting services."""
from dataclasses import replace
import hashlib
import json
from pathlib import Path
from subprocess import CalledProcessError, CompletedProcess

import pytest

from android.release_identity import Identity, inspect, semver, validate


PREVIOUS = Identity("dev.orange.hotel", "17.0.4", 34, frozenset({"original-signer"}), "personal-test")
CANDIDATE = replace(PREVIOUS, name="17.0.5", code=35)


def test_explicit_candidate_supersedes_installed_release_without_run_counters():
    validate(CANDIDATE, PREVIOUS, "17.0.5", 35, "v17.0.5", distribution="personal-test")


@pytest.mark.parametrize("candidate,previous,name,code,tag", [
    (CANDIDATE, PREVIOUS, "17.0.5", 35, "v700"),
    (CANDIDATE, PREVIOUS, "17.0.6", 35, "v17.0.6"),
    (CANDIDATE, PREVIOUS, "17.0.5", 36, "v17.0.5"),
    (replace(CANDIDATE, code=34), PREVIOUS, "17.0.5", 34, "v17.0.5"),
    (CANDIDATE, replace(PREVIOUS, code=36), "17.0.5", 35, "v17.0.5"),
    (CANDIDATE, replace(PREVIOUS, name="17.0.6"), "17.0.5", 35, "v17.0.5"),
    (replace(CANDIDATE, package="other.package"), PREVIOUS, "17.0.5", 35, "v17.0.5"),
    (replace(CANDIDATE, signers=frozenset({"new-key"})), PREVIOUS, "17.0.5", 35, "v17.0.5"),
    (replace(CANDIDATE, signers=frozenset()), PREVIOUS, "17.0.5", 35, "v17.0.5"),
])
def test_tag_apk_mismatch_downgrade_and_signer_replacement_block_publication(candidate, previous, name, code, tag):
    with pytest.raises(ValueError):
        validate(candidate, previous, name, code, tag, distribution="personal-test")


@pytest.mark.parametrize("name", ["700", "17.00.5", "17.0.5-rc.1", "v17.0.5", "17.0.5garbage"])
def test_publication_accepts_only_explicit_stable_semantic_versions(name):
    with pytest.raises(ValueError):
        semver(name)


def test_first_hotel_artifact_can_explicitly_bootstrap_original_signer_and_version():
    validate(replace(CANDIDATE, distribution="hotel"), PREVIOUS, "17.0.5", 35, "v17.0.5",
             distribution="hotel")
    with pytest.raises(ValueError):
        validate(CANDIDATE, replace(PREVIOUS, distribution="hotel"), "17.0.5", 35, "v17.0.5",
                 distribution="personal-test")


@pytest.mark.parametrize("selected,declared", [("hotel", "personal-test"), ("personal-test", "hotel"),
                                             ("unknown", "unknown"), ("hotel", "")])
def test_selected_distribution_must_match_candidate(selected, declared):
    with pytest.raises(ValueError):
        validate(replace(CANDIDATE, distribution=declared), PREVIOUS, "17.0.5", 35, "v17.0.5",
                 distribution=selected)


def manifest_tree(value):
    return ('N: android=http://schemas.android.com/apk/res/android\n'
            '  E: manifest (line=1)\n'
            '    E: application (line=2)\n'
            '      E: meta-data (line=3)\n'
            '        A: android:name(0x01010003)="dev.orange.hotel.DISTRIBUTION" (Raw: "dev.orange.hotel.DISTRIBUTION")\n'
            f'        A: android:value(0x01010024)={value}\n')


@pytest.mark.parametrize("tree,required,expected", [
    (manifest_tree('"hotel" (Raw: "hotel")'), True, "hotel"),
    (manifest_tree('"personal-test" (Raw: "personal-test")'), True, "personal-test"),
    ("  E: manifest\n    E: application\n", False, "personal-test"),
])
def test_inspection_reads_actual_manifest_and_verified_signer(monkeypatch, tree, required, expected):
    calls = []

    def run(command, **kwargs):
        calls.append(command)
        if command[0] == "apksigner":
            output = "Signer #1 certificate SHA-256 digest: " + "A" * 64
        elif command[2] == "badging":
            output = "package: name='dev.orange.hotel' versionCode='35' versionName='17.0.5'"
        else:
            output = tree
        return CompletedProcess(command, 0, stdout=output)

    monkeypatch.setattr("android.release_identity.subprocess.run", run)
    result = inspect(Path("candidate.apk"), require_distribution=required)
    assert result == Identity("dev.orange.hotel", "17.0.5", 35, frozenset({"a" * 64}), expected)
    assert calls[-1] == ["aapt", "dump", "xmltree", "candidate.apk", "AndroidManifest.xml"]


@pytest.mark.parametrize("tree", [
    "  E: manifest\n    E: application\n",
    manifest_tree('"unrecognized" (Raw: "unrecognized")'),
    manifest_tree('@0x7f010001'),
    manifest_tree('"hotel" (Raw: "hotel")') + (
        '      E: meta-data\n'
        '        A: android:name(0x01010003)="dev.orange.hotel.DISTRIBUTION"\n'
        '        A: android:value(0x01010024)="personal-test"\n'),
    manifest_tree('"hotel"').replace("    E: application", "    E: activity"),
])
def test_new_candidates_reject_missing_invalid_duplicate_or_nonapplication_distribution(monkeypatch, tree):
    def run(command, **kwargs):
        output = ("Signer #1 certificate SHA-256 digest: " + "a" * 64 if command[0] == "apksigner"
                  else "package: name='dev.orange.hotel' versionCode='35' versionName='17.0.5'"
                  if command[2] == "badging" else tree)
        return CompletedProcess(command, 0, stdout=output)

    monkeypatch.setattr("android.release_identity.subprocess.run", run)
    with pytest.raises(ValueError, match="distribution"):
        inspect(Path("candidate.apk"), require_distribution=True)


def test_unverified_apk_never_reaches_manifest_inspection(monkeypatch):
    calls = []

    def run(command, **kwargs):
        calls.append(command[0])
        raise CalledProcessError(1, command)

    monkeypatch.setattr("android.release_identity.subprocess.run", run)
    with pytest.raises(CalledProcessError):
        inspect(Path("unverified.apk"), require_distribution=True)
    assert calls == ["apksigner"]


@pytest.mark.parametrize("distribution,filename", [("personal-test", "Manzanilla-Reception.apk"),
                                                ("hotel", "Manzanilla-Hotel.apk")])
def test_cli_emits_selected_distribution_without_changing_schema_or_download_identity(
        tmp_path, monkeypatch, distribution, filename):
    from android import release_identity

    (tmp_path / "VERSION").write_text("17.0.5")
    (tmp_path / "VERSION_CODE").write_text("35")
    apk = tmp_path / filename
    apk.write_bytes(b"candidate bytes")
    previous = tmp_path / "previous.apk"
    output = tmp_path / "metadata.json"
    monkeypatch.setattr(release_identity, "__file__", str(tmp_path / "release_identity.py"))

    def inspected(path, *, require_distribution):
        if path == apk:
            assert require_distribution is True
            return replace(CANDIDATE, distribution=distribution)
        assert path == previous and require_distribution is False
        return PREVIOUS

    monkeypatch.setattr(release_identity, "inspect", inspected)
    monkeypatch.setattr("sys.argv", ["release_identity.py", "--apk", str(apk), "--previous-apk", str(previous),
                                    "--tag", "v17.0.5", "--repository", "owner/hotel",
                                    "--distribution", distribution, "--output", str(output)])
    release_identity.main()
    manifest = json.loads(output.read_bytes())
    assert manifest == {
        "schema": 1, "tag": "v17.0.5", "distribution": distribution,
        "url": f"https://github.com/owner/hotel/releases/download/v17.0.5/{filename}",
        "package_name": "dev.orange.hotel", "version_name": "17.0.5", "version_code": 35,
        "sha256": hashlib.sha256(b"candidate bytes").hexdigest(),
    }
