"""Self-hosted interpreter route (issue #8): mint/end contract parity.

The mint path is offline (hand-rolled token), so the whole route is exercised
through the app with a stubbed room deletion.
"""
import base64, json
from datetime import datetime, timezone

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
    monkeypatch.setenv('ALLOW_UNQUALIFIED_LIVEKIT', '1')
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
    assert grant['exp'] - grant['iat'] == route.JOIN_TOKEN_SECONDS == 120
    assert datetime.fromisoformat(data['expires_at'].replace('Z', '+00:00')) == datetime.fromtimestamp(grant['exp'], timezone.utc)
    assert grant['iss'] == 'testkey'
    metadata = json.loads(grant['metadata'])
    assert metadata == {'guest_language': 'es', 'staff_language': 'en'}


@pytest.mark.parametrize('opt_in', [None, '0', 'true', '01', ' 1', '1 '])
@pytest.mark.parametrize('request_id', [None, 'rejected-new-request'])
def test_new_livekit_request_requires_exact_opt_in_before_any_reservation_or_token(
        livekit_env, monkeypatch, opt_in, request_id):
    store, headers, deleted = livekit_env
    monkeypatch.delenv('ALLOW_UNQUALIFIED_LIVEKIT')
    if opt_in is not None:
        monkeypatch.setenv('ALLOW_UNQUALIFIED_LIVEKIT', opt_in)
    request = {**REQUEST, **({'request_id': request_id} if request_id else {})}
    def unexpected_creation(*args, **kwargs):
        pytest.fail('Disabled LiveKit must not reserve a lease or mint a token')
    with monkeypatch.context() as blocked:
        blocked.setattr(store, 'reserve_fish', unexpected_creation)
        blocked.setattr(route, 'token', unexpected_creation)
        with TestClient(server.app) as client:
            response = client.post('/api/device/fish-session', json=request, headers=headers)
    assert response.status_code == 503
    assert response.json() == {'detail': route.UNAVAILABLE}
    assert response.headers['X-Manzanilla-Voice-Failure'] == 'configuration:unconfigured:0'
    assert response.headers['X-Manzanilla-Session-Creation'] == 'not-created'
    assert store.rows('SELECT * FROM fish_sessions') == [] and deleted == []
    # Rejection consumed neither a write identity nor reserved daily seconds.
    monkeypatch.setenv('ALLOW_UNQUALIFIED_LIVEKIT', '1')
    accepted = TestClient(server.app).post('/api/device/fish-session', json=request, headers=headers)
    assert accepted.status_code == 200
    assert store.rows('SELECT route,status,reserved_seconds FROM fish_sessions') == [
        {'route': 'livekit', 'status': 'live', 'reserved_seconds': 600}]


@pytest.mark.parametrize('status', ['creating', 'live', 'uncertain', 'ending', 'revoked', 'ended', 'expired'])
def test_opt_in_withdrawal_preserves_every_recorded_request_and_its_replay_contract(
        livekit_env, monkeypatch, status):
    store, headers, deleted = livekit_env
    client = TestClient(server.app)
    request = {**REQUEST, 'request_id': 'recorded-request'}
    assert client.post('/api/device/fish-session', json=request, headers=headers).status_code == 200
    store.run('UPDATE fish_sessions SET status=?', (status,))
    recorded = store.rows('SELECT * FROM fish_sessions')
    monkeypatch.delenv('ALLOW_UNQUALIFIED_LIVEKIT')
    def unexpected_token(*args, **kwargs):
        pytest.fail('A recorded request must not mint another token')
    monkeypatch.setattr(route, 'token', unexpected_token)
    replay = client.post('/api/device/fish-session', json=request, headers=headers)
    assert replay.status_code == 409
    assert replay.json() == {'detail': 'Interpreter session already outstanding'}
    assert replay.headers['X-Manzanilla-Voice-Failure'] == 'admission:conflict:0'
    assert 'X-Manzanilla-Session-Creation' not in replay.headers
    new = client.post('/api/device/fish-session', json={**REQUEST, 'request_id': 'different-request'}, headers=headers)
    assert new.status_code == 503
    assert new.headers['X-Manzanilla-Session-Creation'] == 'not-created'
    assert store.rows('SELECT * FROM fish_sessions') == recorded and deleted == []


@pytest.mark.parametrize('hotel_id', ['a', 'b'])
def test_another_callers_recorded_request_cannot_bypass_livekit_opt_in(livekit_env, monkeypatch, hotel_id):
    store, headers, deleted = livekit_env
    client = TestClient(server.app)
    request = {**REQUEST, 'request_id': 'shared-client-request-id'}
    assert client.post('/api/device/fish-session', json=request, headers=headers).status_code == 200
    recorded = store.rows('SELECT * FROM fish_sessions')
    if hotel_id != 'a':
        store.save_hotel(Hotel(id=hotel_id, name='Other synthetic hotel').model_dump())
    other = store.issue(hotel_id, 'device')
    monkeypatch.delenv('ALLOW_UNQUALIFIED_LIVEKIT')
    response = client.post('/api/device/fish-session', json=request,
                           headers={'Authorization': 'Bearer ' + other['token']})
    assert response.status_code == 503
    assert response.headers['X-Manzanilla-Session-Creation'] == 'not-created'
    assert store.rows('SELECT * FROM fish_sessions') == recorded and deleted == []


def test_disabled_livekit_keeps_authentication_and_language_failures(livekit_env, monkeypatch):
    store, headers, _ = livekit_env
    monkeypatch.delenv('ALLOW_UNQUALIFIED_LIVEKIT')
    client = TestClient(server.app)
    unauthorized = client.post('/api/device/fish-session', json=REQUEST)
    assert unauthorized.status_code == 401
    assert unauthorized.headers['X-Manzanilla-Session-Creation'] == 'not-created'
    invalid_pair = client.post('/api/device/fish-session', json={**REQUEST, 'staff_language': 'es'}, headers=headers)
    assert invalid_pair.status_code == 422
    assert invalid_pair.headers['X-Manzanilla-Voice-Failure'] == 'configuration:contract:0'
    assert invalid_pair.headers['X-Manzanilla-Session-Creation'] == 'not-created'
    assert store.rows('SELECT * FROM fish_sessions') == []


def test_end_deletes_the_room_and_settles_after_opt_in_withdrawal(livekit_env, monkeypatch):
    store, headers, deleted = livekit_env
    client = TestClient(server.app)
    minted = client.post('/api/device/fish-session', json=REQUEST, headers=headers).json()
    monkeypatch.delenv('ALLOW_UNQUALIFIED_LIVEKIT')
    response = client.post('/api/device/fish-session/' + minted['session_id'] + '/end', headers=headers)
    assert response.status_code == 200
    assert response.json() == {'status': 'ended', 'session_id': minted['session_id']}
    assert deleted == [minted['session_id']]
    assert store.rows('SELECT status FROM fish_sessions WHERE session_id=?',
                      (minted['session_id'],))[0]['status'] == 'ended'


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


def test_lost_response_cleanup_is_owned_and_uses_stored_route_after_switch(livekit_env, monkeypatch):
    store, headers, deleted = livekit_env
    client = TestClient(server.app)
    request = {**REQUEST, 'request_id': 'lost-livekit-response'}
    minted = client.post('/api/device/fish-session', json=request, headers=headers).json()
    # Simulate a backend configuration flip and a client that never saw the response.
    monkeypatch.setenv('VOICE_ROUTE', 'fish')
    monkeypatch.delenv('ALLOW_UNQUALIFIED_LIVEKIT')
    other = store.issue('a', 'device')
    url = '/api/device/fish-session-requests/lost-livekit-response/end'
    assert client.post(url, headers={'Authorization': 'Bearer ' + other['token']}).status_code == 503
    assert deleted == []
    response = client.post(url, headers=headers)
    assert response.status_code == 200 and response.json() == {'status': 'ended'}
    assert deleted == [minted['session_id']]
    assert store.rows('SELECT route,status,reserved_seconds FROM fish_sessions') == [
        {'route': 'livekit', 'status': 'ended', 'reserved_seconds': 600}]
    assert client.post(url, headers=headers).json() == {'status': 'ended'}
    assert deleted == [minted['session_id']]


def test_revocation_deletes_livekit_room_and_failure_remains_retryable(livekit_env, monkeypatch):
    store, headers, deleted = livekit_env
    monkeypatch.setenv('ORANGE_ADMIN_TOKEN', 'x' * 40)
    client = TestClient(server.app)
    minted = client.post('/api/device/fish-session', json=REQUEST, headers=headers).json()
    monkeypatch.delenv('ALLOW_UNQUALIFIED_LIVEKIT')
    device = store.auth(server.bearer(headers['Authorization']), 'device')
    attempts = []
    async def failing_delete(http_base, key, secret, room):
        attempts.append(room)
        return False
    monkeypatch.setattr(route, 'delete_room', failing_delete)
    admin = {'Authorization': 'Bearer ' + 'x' * 40}
    url = '/api/credentials/' + device['id']
    response = client.delete(url, headers=admin)
    assert response.status_code == 202
    assert response.json() == {'status': 'revoked', 'voice_cleanup': 'pending'}
    assert client.post('/api/device/fish-session', json=REQUEST, headers=headers).status_code == 401
    assert store.rows('SELECT route,status FROM fish_sessions') == [{'route': 'livekit', 'status': 'ending'}]
    assert attempts == [minted['session_id']]
    async def succeeding_delete(http_base, key, secret, room):
        deleted.append(room)
        return True
    monkeypatch.setattr(route, 'delete_room', succeeding_delete)
    response = client.delete(url, headers=admin)
    assert response.json() == {'status': 'revoked'}
    assert deleted == [minted['session_id']]
    assert store.rows('SELECT status FROM fish_sessions') == [{'status': 'ended'}]


def test_livekit_room_is_persisted_before_token_and_revocation_does_not_mark_pending_cleanup_ended(livekit_env, monkeypatch):
    store, headers, deleted = livekit_env
    monkeypatch.setenv('ORANGE_ADMIN_TOKEN', 'x' * 40)
    original_token = route.token
    device = store.auth(server.bearer(headers['Authorization']), 'device')
    def revoke_during_creation(*args, **kwargs):
        row = store.rows('SELECT route,status,session_id FROM fish_sessions')[0]
        assert row['route'] == 'livekit' and row['status'] == 'creating'
        assert row['session_id'].startswith(route.ROOM_PREFIX)
        store.run('DELETE FROM credentials WHERE id=?', (device['id'],))
        store.run("UPDATE fish_sessions SET status='revoked' WHERE id=?", (route.request_lease(device, 'in-flight'),))
        return original_token(*args, **kwargs)
    monkeypatch.setattr(route, 'token', revoke_during_creation)
    client = TestClient(server.app)
    response = client.post('/api/device/fish-session', json={**REQUEST, 'request_id': 'in-flight'}, headers=headers)
    assert response.status_code == 401
    assert response.headers['X-Manzanilla-Session-Creation'] == 'not-created'
    assert store.rows('SELECT status FROM fish_sessions') == [{'status': 'revoked'}]
    admin = {'Authorization': 'Bearer ' + 'x' * 40}
    assert client.delete('/api/credentials/' + device['id'], headers=admin).json() == {'status': 'revoked'}
    assert len(deleted) == 1
    assert store.rows('SELECT status FROM fish_sessions') == [{'status': 'ended'}]


def test_duplicate_preflight_does_not_claim_original_session_was_not_created(livekit_env, monkeypatch):
    store, headers, _ = livekit_env
    client = TestClient(server.app)
    request = {**REQUEST, 'request_id': 'livekit-existing'}
    assert client.post('/api/device/fish-session', json=request, headers=headers).status_code == 200
    duplicate = client.post('/api/device/fish-session', json=request, headers=headers)
    assert duplicate.status_code == 409
    assert 'X-Manzanilla-Session-Creation' not in duplicate.headers
    other = client.post('/api/device/fish-session', json={**REQUEST, 'request_id': 'new-id'}, headers=headers)
    assert other.status_code == 409 and other.headers['X-Manzanilla-Session-Creation'] == 'not-created'
    monkeypatch.delenv('LIVEKIT_API_SECRET')
    monkeypatch.delenv('ALLOW_UNQUALIFIED_LIVEKIT')
    duplicate = client.post('/api/device/fish-session', json=request, headers=headers)
    assert duplicate.status_code == 503
    assert 'X-Manzanilla-Session-Creation' not in duplicate.headers
    assert store.rows('SELECT status FROM fish_sessions') == [{'status': 'live'}]


def test_legacy_database_migration_preserves_existing_sessions(tmp_path):
    import sqlite3
    path = tmp_path / 'legacy.db'
    with sqlite3.connect(path) as db:
        db.execute('''CREATE TABLE fish_sessions(
            id TEXT PRIMARY KEY, hotel TEXT NOT NULL, device TEXT NOT NULL, credential_hash TEXT NOT NULL,
            session_id TEXT UNIQUE, created REAL NOT NULL, creation_deadline REAL NOT NULL, expires REAL NOT NULL,
            reserved_seconds INTEGER NOT NULL, status TEXT NOT NULL)''')
        db.executemany('INSERT INTO fish_sessions VALUES(?,?,?,?,?,?,?,?,?,?)', [
            ('one', 'a', 'd1', 'hash1', 'manz-legacy', 1, 2, 3, 600, 'live'),
            ('two', 'a', 'd2', 'hash2', 'hosted-session', 1, 2, 3, 600, 'ending'),
            ('three', 'a', 'd3', 'hash3', None, 1, 2, 3, 600, 'uncertain'),
        ])
    store = Store(str(path))
    assert store.rows('SELECT id,session_id,status,route FROM fish_sessions ORDER BY id') == [
        {'id': 'one', 'session_id': 'manz-legacy', 'status': 'live', 'route': 'livekit'},
        {'id': 'three', 'session_id': None, 'status': 'uncertain', 'route': 'fish'},
        {'id': 'two', 'session_id': 'hosted-session', 'status': 'ending', 'route': 'fish'},
    ]
    # Migration is one-time: a later Fish ID that resembles a room is not reclassified.
    store.run("UPDATE fish_sessions SET session_id='manz-hosted' WHERE id='two'")
    reopened = Store(str(path))
    assert reopened.rows("SELECT route FROM fish_sessions WHERE id='two'") == [{'route': 'fish'}]


def test_end_uses_stored_fish_route_even_for_room_shaped_hosted_id(livekit_env, monkeypatch):
    import cloud.fish as fish
    store, headers, deleted = livekit_env
    device = store.auth(server.bearer(headers['Authorization']), 'device')
    ident = store.reserve_fish(device, 600, 1800, 3600, 60, 3600)
    store.run("UPDATE fish_sessions SET session_id='manz-hosted-provider-id',status='live' WHERE id=?", (ident,))
    closed = []
    async def close_hosted(store_arg, lease):
        closed.append(lease['session_id'])
        store_arg.run("UPDATE fish_sessions SET status='ended' WHERE id=?", (lease['id'],))
        return True
    monkeypatch.setattr(fish, 'terminate', close_hosted)
    response = TestClient(server.app).post('/api/device/fish-session/manz-hosted-provider-id/end', headers=headers)
    assert response.status_code == 200
    assert closed == ['manz-hosted-provider-id'] and deleted == []


def test_livekit_startup_references_distinguish_configuration_conflict_and_budget(livekit_env, monkeypatch):
    store, headers, _ = livekit_env
    client = TestClient(server.app)
    same_language = client.post('/api/device/fish-session', json={**REQUEST, 'staff_language': 'es'}, headers=headers)
    assert same_language.status_code == 422
    assert same_language.headers['X-Manzanilla-Voice-Failure'] == 'configuration:contract:0'
    assert same_language.headers['X-Manzanilla-Session-Creation'] == 'not-created'
    monkeypatch.delenv('LIVEKIT_API_SECRET')
    unconfigured = client.post('/api/device/fish-session', json=REQUEST, headers=headers)
    assert unconfigured.headers['X-Manzanilla-Voice-Failure'] == 'configuration:unconfigured:0'
    assert unconfigured.headers['X-Manzanilla-Session-Creation'] == 'not-created'
    monkeypatch.setenv('LIVEKIT_API_SECRET', 'testsecret')
    assert client.post('/api/device/fish-session', json=REQUEST, headers=headers).status_code == 200
    conflict = client.post('/api/device/fish-session', json=REQUEST, headers=headers)
    assert conflict.status_code == 409
    assert conflict.headers['X-Manzanilla-Voice-Failure'] == 'admission:conflict:0'
    store.run("UPDATE fish_sessions SET status='ended'")
    monkeypatch.setenv('FISH_HOTEL_DAILY_SECONDS', '600')
    budget = client.post('/api/device/fish-session', json=REQUEST, headers=headers)
    assert budget.status_code == 429
    assert budget.headers['X-Manzanilla-Voice-Failure'] == 'admission:budget:0'
    assert 'testsecret' not in str(budget.headers) + budget.text


@pytest.mark.parametrize('status,body,expected', [
    (200, {}, True),
    (404, {'code': 'not_found', 'msg': 'requested room does not exist'}, True),
    (404, {'code': 'bad_route'}, False),
    (403, {'code': 'permission_denied'}, False),
    (503, {'code': 'unavailable'}, False),
])
def test_livekit_room_deletion_uses_required_server_grant_and_classifies_confirmed_absence(monkeypatch, status, body, expected):
    import asyncio
    import httpx
    seen = []
    async def handle(request):
        token = request.headers['authorization'].removeprefix('Bearer ')
        grant = claims(token)['video']
        assert grant == {'roomCreate': True}
        assert json.loads(request.content) == {'room': 'manz-test-room'}
        assert request.url.path == '/twirp/livekit.RoomService/DeleteRoom'
        seen.append(grant)
        return httpx.Response(status, json=body)
    actual_client = httpx.AsyncClient
    monkeypatch.setattr(route.httpx, 'AsyncClient', lambda **kwargs: actual_client(
        transport=httpx.MockTransport(handle), **kwargs))
    result = asyncio.run(route.delete_room('https://livekit.example.test', 'fake-key', 'fake-secret', 'manz-test-room'))
    assert result is expected and len(seen) == 1
