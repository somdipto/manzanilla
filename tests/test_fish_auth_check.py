import pytest

from scripts import check_fish_auth


def test_read_only_check_sends_auth_to_fish_with_synthetic_filter():
    calls = []

    class FakeConnection:
        def __init__(self, host, timeout):
            calls.append((host, timeout))

        def request(self, method, path, headers):
            assert method == "GET"
            assert path.startswith("/v1/agent/sessions?end_user_id=manual-check-")
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
    monkeypatch.setattr(check_fish_auth.getpass, "getpass", lambda _: "fixture-only-key")
    monkeypatch.setattr(check_fish_auth, "check", lambda _: ("inconclusive", None))
    assert check_fish_auth.main(["--user-credential"]) == 1
    assert "fixture-only-key" not in capsys.readouterr().err


def test_absent_key_is_not_a_network_call():
    assert check_fish_auth.check("", lambda *_args, **_kwargs: 1) == ("missing", None)


def test_absent_user_credential_does_not_use_environment(monkeypatch, capsys):
    monkeypatch.setenv("FISH_API_KEY", "must-not-use-environment-key")
    monkeypatch.setattr(check_fish_auth.getpass, "getpass", lambda _: "")
    assert check_fish_auth.main(["--user-credential"]) == 1
    assert "must-not-use-environment-key" not in capsys.readouterr().err


def test_manual_check_uses_only_privately_supplied_user_credential(monkeypatch, capsys):
    monkeypatch.setenv("FISH_API_KEY", "must-not-use-environment-key")
    monkeypatch.setattr(check_fish_auth.getpass, "getpass", lambda _: "user-owned-key")
    calls = []
    monkeypatch.setattr(check_fish_auth, "check", lambda key: calls.append(key) or ("accepted", 200))
    assert check_fish_auth.main(["--user-credential"]) == 0
    assert calls == ["user-owned-key"]
    output = capsys.readouterr()
    assert "user-owned-key" not in output.out + output.err
    assert "must-not-use-environment-key" not in output.out + output.err


@pytest.mark.parametrize("error", [EOFError, KeyboardInterrupt, check_fish_auth.getpass.GetPassWarning])
def test_cancelled_prompt_never_contacts_provider(error, monkeypatch):
    def cancelled(_):
        raise error()
    monkeypatch.setattr(check_fish_auth.getpass, "getpass", cancelled)
    monkeypatch.setattr(check_fish_auth, "check", lambda _: pytest.fail("cancelled check contacted Fish"))
    assert check_fish_auth.main(["--user-credential"]) == 1


@pytest.mark.parametrize("argv", [[], ["--distribution", "personal-test"], ["--distribution", "hotel"]])
def test_build_distribution_cannot_authorize_a_provider_call(argv, monkeypatch):
    monkeypatch.setattr(check_fish_auth, "check", lambda _: pytest.fail("build check contacted Fish"))
    with pytest.raises(SystemExit) as error:
        check_fish_auth.main(argv)
    assert error.value.code == 2
