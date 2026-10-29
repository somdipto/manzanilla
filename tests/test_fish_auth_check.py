import pytest

from scripts import check_fish_auth


def test_read_only_check_sends_auth_to_fish_with_synthetic_filter():
    calls = []

    class FakeConnection:
        def __init__(self, host, timeout):
            calls.append((host, timeout))

        def request(self, method, path, headers):
            assert method == "GET"
            assert path.startswith("/v1/agent/sessions?end_user_id=ci-")
            assert path.endswith("&page_size=1")
            assert headers == {"Authorization": "Bearer fixture-only-key"}

        def getresponse(self):
            return type("Response", (), {"status": 200})()

        def close(self):
            calls.append("closed")

    assert check_fish_auth.check("fixture-only-key", FakeConnection) == ("accepted", 200)
    assert calls == [("api.fish.audio", 15), "closed"]


def test_rejected_and_unavailable_are_distinguished_without_exception_text(capsys, monkeypatch):
    class FakeConnection:
        def __init__(self, host, timeout):
            pass

        def request(self, method, path, headers):
            pass

        def getresponse(self):
            return type("Response", (), {"status": 401})()

        def close(self):
            pass

    assert check_fish_auth.check("fixture-only-key", FakeConnection) == ("rejected", 401)
    monkeypatch.setenv("FISH_API_KEY", "fixture-only-key")
    monkeypatch.setattr(check_fish_auth, "check", lambda _: ("inconclusive", None))
    assert check_fish_auth.main(["--distribution", "personal-test"]) == 1
    assert "fixture-only-key" not in capsys.readouterr().err


def test_absent_key_is_not_a_network_call():
    assert check_fish_auth.check("", lambda *_args, **_kwargs: 1) == ("missing", None)


def test_personal_release_requires_injected_disposable_credential(monkeypatch, capsys):
    monkeypatch.delenv("FISH_API_KEY", raising=False)
    assert check_fish_auth.main(["--distribution", "personal-test"]) == 1
    assert "not configured" in capsys.readouterr().err


def test_personal_release_checks_injected_access_for_every_version(monkeypatch):
    monkeypatch.setenv("FISH_API_KEY", "fixture-only-key")
    calls = []
    monkeypatch.setattr(check_fish_auth, "check", lambda key: calls.append(key) or ("accepted", 200))
    assert check_fish_auth.main(["--distribution", "personal-test"]) == 0
    assert calls == ["fixture-only-key"]


@pytest.mark.parametrize("credential", [None, "fixture-only-key"])
def test_hotel_never_contacts_provider_and_rejects_accidental_secret(credential, monkeypatch, capsys):
    if credential is None:
        monkeypatch.delenv("FISH_API_KEY", raising=False)
    else:
        monkeypatch.setenv("FISH_API_KEY", credential)
    def unexpected_check(_key):
        pytest.fail("hotel construction contacted Fish")
    monkeypatch.setattr(check_fish_auth, "check", unexpected_check)
    assert check_fish_auth.main(["--distribution", "hotel"]) == (0 if credential is None else 1)
    assert "fixture-only-key" not in capsys.readouterr().err


@pytest.mark.parametrize("argv", [[], ["--distribution", "unknown"]])
def test_distribution_is_explicit_and_validated(argv):
    with pytest.raises(SystemExit) as error:
        check_fish_auth.main(argv)
    assert error.value.code == 2
