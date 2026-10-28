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
    assert check_fish_auth.main() == 1
    assert "fixture-only-key" not in capsys.readouterr().err


def test_absent_key_is_not_a_network_call():
    assert check_fish_auth.check("", lambda *_args, **_kwargs: 1) == ("missing", None)
