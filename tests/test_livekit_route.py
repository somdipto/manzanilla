"""Self-hosted interpreter route (issue #8): mint/end contract parity.

The mint path is offline (hand-rolled token), so the whole route is exercised
through the app with a stubbed room deletion.
"""
import base64, json

import pytest
from fastapi.testclient import TestClient

from cloud.db import Store
from cloud.models import Hotel
import cloud.app as server
import cloud.livekit_route as route

REQUEST = {'guest_language': 'es', 'staff_language': 'en', 'mode': 'translation'}


@pytest.fixture
def livekit_env(tmp_path, monkeypatch):
    store = Store(str(tmp_path / 'lk.db'))
    store.save_hotel(Hotel(id='a', name='Synthetic').model_dump())
    monkeypatch.setattr(server, 'store', store)
    monkeypatch.setenv('VOICE_ROUTE', 'livekit')
    monkeypatch.setenv('LIVEKIT_URL', 'wss://voice.example.test')
    monkeypatch.setenv('LIVEKIT_API_KEY', 'testkey')
    monkeypatch.setenv('LIVEKIT_API_SECRET', 'testsecret')
    monkeypatch.setenv('FISH_HOTEL_DAILY_SECONDS', '1800')
    monkeypatch.setenv('FISH_ACCOUNT_DAILY_SECONDS', '3600')
    dev = store.issue('a', 'device')
    deleted = []
    async def fake_delete(http_base, key, secret, room):
        deleted.append(room)
        return True
    monkeypatch.setattr(route, 'delete_room', fake_delete)
    return store, {'Authorization': 'Bearer ' + dev['token']}, deleted


def claims(token):
    body = token.split('.')[1]
    body += '=' * (-len(body) % 4)
    return json.loads(base64.urlsafe_b64decode(body))


def test_mint_matches_hosted_response_shape(livekit_env):
    _, headers, _ = livekit_env
    client = TestClient(server.app)
    response = client.post('/api/device/fish-session', json=REQUEST, headers=headers)
    assert response.status_code == 200
    data = response.json()
    assert data['transport'] == 'livekit'
    assert data['livekit_url'] == 'wss://voice.example.test'
    assert data['max_duration_seconds'] == 600
    assert data['session_id'].startswith('manz-')
    grant = claims(data['token'])
    assert grant['video']['roomJoin'] and grant['video']['room'] == data['session_id']
    assert grant['iss'] == 'testkey'
    metadata = json.loads(grant['metadata'])
    assert metadata == {'guest_language': 'es', 'staff_language': 'en'}


def test_end_deletes_the_room_and_settles(livekit_env):
    store, headers, deleted = livekit_env
    client = TestClient(server.app)
    minted = client.post('/api/device/fish-session', json=REQUEST, headers=headers).json()
    response = client.post('/api/device/fish-session/' + minted['session_id'] + '/end', headers=headers)
    assert response.status_code == 200
    assert response.json() == {'status': 'ended', 'session_id': minted['session_id']}
    assert deleted == [minted['session_id']]
    assert store.rows('SELECT status FROM fish_sessions WHERE session_id=?',
                      (minted['session_id'],))[0]['status'] == 'ended'


def test_fish_route_remains_default(monkeypatch, tmp_path):
    monkeypatch.delenv('VOICE_ROUTE', raising=False)
    import cloud.app as app_module
    import inspect
    source = inspect.getsource(app_module.fish_session)
    assert "os.getenv('VOICE_ROUTE','fish')" in source


def test_missing_server_config_refuses_cleanly(livekit_env, monkeypatch):
    _, headers, _ = livekit_env
    monkeypatch.delenv('LIVEKIT_API_SECRET')
    client = TestClient(server.app)
    response = client.post('/api/device/fish-session', json=REQUEST, headers=headers)
    assert response.status_code == 503
    assert response.headers.get('X-Manzanilla-Session-Creation') == 'not-created'


def test_end_dispatch_does_not_hijack_hosted_sessions(livekit_env):
    # Hosted Fish session ids never carry the self-hosted room prefix.
    assert not 'fish_01ABC'.startswith(route.ROOM_PREFIX)
    assert 'manz-01ABC'.startswith(route.ROOM_PREFIX)
