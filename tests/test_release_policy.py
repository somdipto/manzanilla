"""PR publication policy, including committed Git-tree behavior."""
from pathlib import Path
import subprocess

import pytest

from scripts import release_policy
from scripts.release_policy import check_repository, download_previous, previous_asset, release_impact, validate_change


VERSIONS = {"android/VERSION", "android/VERSION_CODE"}


@pytest.mark.parametrize("path", [
    "android/hotel/src/main/java/HotelActivity.kt", "android/hotel/src/test/java/UpdaterTest.kt",
    "ui/reception/reception.js", "package.json", "package-lock.json", "scripts/build-reception.cjs",
    "shared/reception-locales.json", "shared/translation-languages.json", "shared/translation-runtime.txt",
    "scripts/release_policy.py", "scripts/release_policy_check.py", "scripts/publish_release.py", "scripts/verify-signed-upgrade.sh",
    "scripts/run-updater-emulator.sh",
    "scripts/signed_upgrade_proof.py",
    ".github/workflows/publish.yml", ".github/workflows/release.yml", ".github/workflows/verify.yml",
])
def test_release_inputs_require_both_version_changes(path):
    assert release_impact(path)
    with pytest.raises(ValueError, match="both"):
        validate_change([path], "17.0.28", "58", "17.0.29", "59")
    assert validate_change(VERSIONS | {path}, "17.0.28", "58", "17.0.29", "59")


def test_docs_backend_and_test_only_changes_do_not_require_an_apk():
    paths = ["README.md", "docs/update-delivery.md", "AGENTS.md", "cloud/fish.py",
             "tests/test_fish.py", "tests/test_release_policy.py", "android-guide.md",
             "scripts/check_fish_auth.py"]
    assert not any(release_impact(path) for path in paths)
    assert validate_change(paths, "17.0.28", "58", "17.0.28", "58") is False


@pytest.mark.parametrize("paths,name,code", [
    ({"android/VERSION"}, "17.0.29", "59"),
    ({"android/VERSION_CODE"}, "17.0.29", "59"),
    (VERSIONS, "17.0.28", "59"),
    (VERSIONS, "17.0.29", "58"),
    (VERSIONS, "17.0.27", "60"),
    (VERSIONS, "17.0.30", "57"),
    (VERSIONS, "17.0.29-rc.1", "59"),
    (VERSIONS, "17.00.29", "59"),
    (VERSIONS, "v17.0.29", "59"),
    (VERSIONS, "17.0.29", "059"),
    (VERSIONS, "17.0.29", "0"),
    (VERSIONS, "17.0.29", "-59"),
    (VERSIONS, "17.0.29", "2100000001"),
])
def test_invalid_partial_unchanged_or_lower_identity_is_rejected(paths, name, code):
    with pytest.raises(ValueError):
        validate_change(paths, "17.0.28", "58", name, code)


def test_semantic_order_is_numeric_and_version_only_bump_is_valid():
    assert validate_change(VERSIONS, "17.9.9", "58", "17.10.0", "59")


def git(path: Path, *arguments: str) -> str:
    return subprocess.run(["git", "-C", str(path), *arguments], check=True,
                          capture_output=True, text=True).stdout.strip()


def commit(path: Path, message: str) -> str:
    git(path, "add", ".")
    git(path, "-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid",
        "-c", "commit.gpgsign=false", "commit", "-qm", message)
    return git(path, "rev-parse", "HEAD")


def test_git_policy_reads_committed_versions_and_detects_a_missing_bump(tmp_path):
    git(tmp_path, "init", "-q")
    (tmp_path / "android").mkdir()
    (tmp_path / "android/VERSION").write_text("17.0.28\n")
    (tmp_path / "android/VERSION_CODE").write_text("58\n")
    base = commit(tmp_path, "base")
    (tmp_path / "android/Updater.kt").write_text("// synthetic app change\n")
    candidate = commit(tmp_path, "app without bump")
    # Uncommitted edits must not let an unversioned PR pass.
    (tmp_path / "android/VERSION").write_text("17.0.29\n")
    (tmp_path / "android/VERSION_CODE").write_text("59\n")
    with pytest.raises(ValueError, match="both"):
        check_repository(tmp_path, base, candidate)
    bumped = commit(tmp_path, "explicit candidate")
    assert check_repository(tmp_path, base, bumped)


def test_git_policy_cannot_hide_a_removed_app_file_as_a_docs_rename(tmp_path):
    git(tmp_path, "init", "-q")
    (tmp_path / "android").mkdir()
    (tmp_path / "android/VERSION").write_text("17.0.28\n")
    (tmp_path / "android/VERSION_CODE").write_text("58\n")
    (tmp_path / "android/Updater.kt").write_text("// synthetic source\n")
    base = commit(tmp_path, "base")
    (tmp_path / "docs").mkdir()
    git(tmp_path, "mv", "android/Updater.kt", "docs/Updater.kt")
    candidate = commit(tmp_path, "moved source")
    with pytest.raises(ValueError, match="both"):
        check_repository(tmp_path, base, candidate)


def test_git_policy_fails_closed_when_the_base_is_missing(tmp_path):
    git(tmp_path, "init", "-q")
    (tmp_path / "android").mkdir()
    (tmp_path / "android/VERSION").write_text("17.0.30\n")
    (tmp_path / "android/VERSION_CODE").write_text("60\n")
    candidate = commit(tmp_path, "base")
    with pytest.raises(subprocess.CalledProcessError):
        check_repository(tmp_path, "does-not-exist", candidate)


@pytest.mark.parametrize("prefix,suffix", [("nvapi-", ""), ("eyJ", ".")])
def test_credential_scan_excludes_fixtures_and_never_prints_matches(tmp_path, prefix, suffix):
    import sys

    git(tmp_path, "init", "-q")
    (tmp_path / "android/hotel").mkdir(parents=True)
    (tmp_path / "android/hotel/build.gradle.kts").write_text('applicationId = "dev.orange.hotel"\n')
    (tmp_path / "docs").mkdir()
    synthetic = prefix + "a" * 24 + suffix
    (tmp_path / "docs/example.txt").write_text(synthetic)
    commit(tmp_path, "base")
    guard = Path(__file__).resolve().parents[1] / "scripts/release_policy_check.py"
    def scan():
        return subprocess.run([sys.executable, str(guard)], cwd=tmp_path, text=True, capture_output=True)
    assert scan().returncode == 0  # documentation fixtures are excluded
    (tmp_path / "app.py").write_text(synthetic)
    commit(tmp_path, "synthetic literal")
    failed = scan()
    assert failed.returncode == 1
    assert "app.py" in failed.stderr and synthetic not in failed.stderr


def test_credential_scan_failure_does_not_pass(tmp_path, monkeypatch, capsys):
    import importlib.util
    from types import SimpleNamespace

    (tmp_path / "android/hotel").mkdir(parents=True)
    (tmp_path / "android/hotel/build.gradle.kts").write_text('applicationId = "dev.orange.hotel"\n')
    guard = Path(__file__).resolve().parents[1] / "scripts/release_policy_check.py"
    spec = importlib.util.spec_from_file_location("fixture_release_guard", guard)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    def fake_run(argv, **kwargs):
        return SimpleNamespace(stdout=str(tmp_path), returncode=0) if argv[1] == "rev-parse" else SimpleNamespace(returncode=2)
    monkeypatch.setattr(module.subprocess, "run", fake_run)
    assert module.main() == 1
    assert "scan could not complete" in capsys.readouterr().err


def published_release(*names, ident=7):
    return {
        "id": ident, "tag_name": "v17.0.43", "draft": False, "prerelease": False,
        "assets": [{"name": name, "size": 3, "state": "uploaded"} for name in names],
    }


def test_previous_apk_is_selected_by_distribution_not_asset_order():
    latest = published_release("unrelated.apk", "Manzanilla-Hotel.apk", "Manzanilla-Reception.apk")
    assert previous_asset(latest, [], "personal-test") == ("v17.0.43", "Manzanilla-Reception.apk", 3)
    assert previous_asset(latest, [], "hotel") == ("v17.0.43", "Manzanilla-Hotel.apk", 3)


def test_first_hotel_bootstrap_requires_complete_history_without_hotel():
    latest = published_release("Manzanilla-Reception.apk")
    earlier = published_release("Manzanilla-Reception.apk", ident=6)
    assert previous_asset(latest, [latest, earlier], "hotel")[1] == "Manzanilla-Reception.apk"
    for incomplete in ([], [earlier], [latest, {"id": 6}]):
        with pytest.raises(ValueError):
            previous_asset(latest, incomplete, "hotel")


@pytest.mark.parametrize("prior_name", ["Manzanilla-Hotel.apk", "hotel-release-metadata.json"])
def test_hotel_never_bootstraps_again_after_any_published_hotel_pair(prior_name):
    latest = published_release("Manzanilla-Reception.apk")
    earlier = published_release(prior_name, ident=6)
    with pytest.raises(ValueError, match="forbidden"):
        previous_asset(latest, [latest, earlier], "hotel")


@pytest.mark.parametrize("mutation", [
    lambda release: release.pop("assets"),
    lambda release: release.update(draft=True),
    lambda release: release.update(prerelease=True),
    lambda release: release["assets"][0].update(size=0),
    lambda release: release["assets"][0].update(state="new"),
    lambda release: release["assets"].append(dict(release["assets"][0])),
])
def test_malformed_or_unfinished_previous_hotel_is_not_absence(mutation):
    latest = published_release("Manzanilla-Hotel.apk", "Manzanilla-Reception.apk")
    mutation(latest)
    with pytest.raises(ValueError):
        previous_asset(latest, [latest], "hotel")


def test_personal_feed_does_not_select_hotel_when_personal_asset_is_missing():
    latest = published_release("Manzanilla-Hotel.apk")
    with pytest.raises(ValueError):
        previous_asset(latest, [latest], "personal-test")


def test_previous_download_uses_exact_asset_and_complete_history(tmp_path, monkeypatch):
    import json
    from types import SimpleNamespace

    latest = published_release("Manzanilla-Reception.apk")
    calls = []
    def fake_run(argv, **kwargs):
        calls.append(argv)
        if argv[:3] == ["gh", "api", "--paginate"]:
            return SimpleNamespace(stdout=json.dumps([[latest], []]))
        if argv[:2] == ["gh", "api"]:
            return SimpleNamespace(stdout=json.dumps(latest))
        assert argv == [
            "gh", "release", "download", "v17.0.43", "--repo", "fixture/repository",
            "--pattern", "Manzanilla-Reception.apk", "--dir", str(tmp_path),
        ]
        (tmp_path / "Manzanilla-Reception.apk").write_bytes(b"apk")
        return SimpleNamespace(stdout="")
    monkeypatch.setattr(release_policy.subprocess, "run", fake_run)
    download_previous("fixture/repository", tmp_path, "hotel")
    assert len(calls) == 3
    assert (tmp_path / "Manzanilla-Reception.apk").read_bytes() == b"apk"


@pytest.mark.parametrize("failure_stage", ["latest", "history", "download"])
def test_previous_download_never_falls_back_after_transport_failure(tmp_path, monkeypatch, failure_stage):
    import json
    from types import SimpleNamespace

    latest = published_release("Manzanilla-Reception.apk")
    calls = []
    def fake_run(argv, **kwargs):
        stage = "history" if "--paginate" in argv else "download" if "download" in argv else "latest"
        calls.append(stage)
        if stage == failure_stage:
            raise subprocess.CalledProcessError(1, argv, stderr="private diagnostic body")
        return SimpleNamespace(stdout=json.dumps([[latest]]) if stage == "history" else json.dumps(latest))
    monkeypatch.setattr(release_policy.subprocess, "run", fake_run)
    with pytest.raises(ValueError, match="Cannot retrieve") as error:
        download_previous("fixture/repository", tmp_path, "hotel")
    assert calls == ["latest", "history", "download"][:["latest", "history", "download"].index(failure_stage) + 1]
    assert "private diagnostic body" not in str(error.value)
    assert not list(tmp_path.iterdir())


def test_previous_download_rejects_stale_directory_before_contacting_github(tmp_path, monkeypatch):
    (tmp_path / "Manzanilla-Reception.apk").write_bytes(b"stale")
    def unexpected_call(*args, **kwargs):
        pytest.fail("stale previous directory triggered a download")
    monkeypatch.setattr(release_policy.subprocess, "run", unexpected_call)
    with pytest.raises(ValueError, match="empty"):
        download_previous("fixture/repository", tmp_path, "hotel")


def test_previous_download_rejects_truncated_bytes(tmp_path, monkeypatch):
    import json
    from types import SimpleNamespace

    latest = published_release("Manzanilla-Hotel.apk")
    def fake_run(argv, **kwargs):
        if argv[:2] == ["gh", "api"]:
            return SimpleNamespace(stdout=json.dumps(latest))
        (tmp_path / "Manzanilla-Hotel.apk").write_bytes(b"x")
        return SimpleNamespace(stdout="")
    monkeypatch.setattr(release_policy.subprocess, "run", fake_run)
    with pytest.raises(ValueError, match="does not match"):
        download_previous("fixture/repository", tmp_path, "hotel")
