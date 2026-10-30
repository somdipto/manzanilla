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

REQUEST = {'guest_language': 'es', 'staff_language': 'en', 'mode': 'translation',
           'protocol': 'translation-v1', 'request_id': 'synthetic-request'}


@pytest.fixture
def livekit_env(tmp_path, monkeypatch):
    store = Store(str(tmp_path / 'lk.db'))
    store.save_hotel(Hotel(id='a', name='Synthetic', supported_languages=['es', 'en']).model_dump())
    monkeypatch.setattr(server, 'store', store)
    monkeypatch.setenv('VOICE_ROUTE', 'livekit')
    monkeypatch.setenv('ALLOW_UNQUALIFIED_LIVEKIT', '1')
    monkeypatch.setenv('LIVEKIT_WORKER_ENABLED', '1')
    monkeypatch.setenv('FISH_API_KEY', 'synthetic-fish-key')
    monkeypatch.setenv('FISH_VOICE_ID', 'synthetic-voice')
    monkeypatch.setenv('GEMINI_API_KEY', 'synthetic-gemini-key')
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
        store.save_hotel(Hotel(id=hotel_id, name='Other synthetic hotel', supported_languages=['es', 'en']).model_dump())
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
    invalid_pair = client.post('/api/device/fish-session', json={**REQUEST, 'staff_language': 'xx'}, headers=headers)
    assert invalid_pair.status_code == 422
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


@pytest.mark.parametrize('entrypoint', ['session', 'request', 'admin'])
@pytest.mark.parametrize('setting,replacement', [
    ('LIVEKIT_URL', 'wss://replacement.example.test'),
    ('LIVEKIT_API_KEY', 'replacement-key'),
    ('LIVEKIT_API_SECRET', 'replacement-secret'),
    ('LIVEKIT_API_SECRET', None),
])
def test_cleanup_requires_original_authority_after_restart(
        livekit_env, monkeypatch, entrypoint, setting, replacement):
    store, headers, deleted = livekit_env
    monkeypatch.setenv('ORANGE_ADMIN_TOKEN', 'x' * 40)
    client = TestClient(server.app)
    device = store.auth(server.bearer(headers['Authorization']), 'device')
    request = {**REQUEST, 'request_id': 'authority-bound-request'}
    minted = client.post('/api/device/fish-session', json=request, headers=headers).json()
    recorded = store.rows('SELECT * FROM fish_sessions')[0]
    authority = recorded['cleanup_authority']
    assert len(authority) == 64 and set(authority) <= set('0123456789abcdef')
    assert all(value not in json.dumps(recorded)
               for value in ('testkey', 'testsecret', 'voice.example.test'))
    assert 'cleanup_authority' not in minted
    path = store.db.execute('PRAGMA database_list').fetchone()[2]
    store.db.close()
    reopened = Store(path)
    monkeypatch.setattr(server, 'store', reopened)
    assert reopened.rows('SELECT cleanup_authority FROM fish_sessions') == [
        {'cleanup_authority': authority}]
    if entrypoint == 'admin':
        url = '/api/credentials/' + device['id']
        def cleanup():
            return client.delete(url, headers={'Authorization': 'Bearer ' + 'x' * 40})
    else:
        url = ('/api/device/fish-session/' + minted['session_id'] + '/end'
               if entrypoint == 'session' else
               '/api/device/fish-session-requests/authority-bound-request/end')
        def cleanup():
            return client.post(url, headers=headers)
    # Even a successful/absent-room response from the replacement deployment
    # could not prove that the original room is gone: do not contact it at all.
    with monkeypatch.context() as changed:
        if replacement is None:
            changed.delenv(setting)
        else:
            changed.setenv(setting, replacement)
        refused = cleanup()
        assert refused.status_code == (202 if entrypoint == 'admin' else 503)
        if entrypoint == 'admin':
            assert refused.json() == {'status': 'revoked', 'voice_cleanup': 'pending'}
        assert deleted == []
        assert reopened.rows('SELECT status,cleanup_authority FROM fish_sessions') == [
            {'status': 'ending', 'cleanup_authority': authority}]
        if entrypoint != 'admin':
            blocked = client.post('/api/device/fish-session',
                                  json={**REQUEST, 'request_id': 'another-request'}, headers=headers)
            assert blocked.status_code == (503 if replacement is None else 409)
    # The original credentials can clean up even after admin revocation removed
    # device access. No migration or current environment replaces lease identity.
    settled = cleanup()
    assert settled.status_code == 200
    assert deleted == [minted['session_id']]
    assert reopened.rows('SELECT status FROM fish_sessions') == [{'status': 'ended'}]
    assert cleanup().status_code == 200 and deleted == [minted['session_id']]


@pytest.mark.parametrize('entrypoint', ['session', 'request', 'admin'])
@pytest.mark.parametrize('authority', [None, ''])
def test_unknown_cleanup_authority_never_contacts_current_deployment(
        livekit_env, monkeypatch, entrypoint, authority):
    store, headers, deleted = livekit_env
    monkeypatch.setenv('ORANGE_ADMIN_TOKEN', 'x' * 40)
    client = TestClient(server.app)
    device = store.auth(server.bearer(headers['Authorization']), 'device')
    minted = client.post('/api/device/fish-session',
                         json={**REQUEST, 'request_id': 'unknown-authority'}, headers=headers).json()
    store.run('UPDATE fish_sessions SET cleanup_authority=?', (authority,))
    if entrypoint == 'admin':
        response = client.delete('/api/credentials/' + device['id'],
                                 headers={'Authorization': 'Bearer ' + 'x' * 40})
        assert response.status_code == 202
        assert response.json()['voice_cleanup'] == 'pending'
    else:
        url = ('/api/device/fish-session/' + minted['session_id'] + '/end'
               if entrypoint == 'session' else
               '/api/device/fish-session-requests/unknown-authority/end')
        assert client.post(url, headers=headers).status_code == 503
    assert deleted == []
    assert store.rows('SELECT status,cleanup_authority FROM fish_sessions') == [
        {'status': 'ending', 'cleanup_authority': authority}]


def test_equivalent_http_endpoint_keeps_cleanup_authority(livekit_env, monkeypatch):
    _, headers, deleted = livekit_env
    client = TestClient(server.app)
    minted = client.post('/api/device/fish-session', json=REQUEST, headers=headers).json()
    monkeypatch.setenv('LIVEKIT_URL', 'wss://VOICE.EXAMPLE.TEST:443/')
    response = client.post('/api/device/fish-session/' + minted['session_id'] + '/end', headers=headers)
    assert response.status_code == 200 and deleted == [minted['session_id']]


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
    assert client.post(url, headers={'Authorization': 'Bearer ' + other['token']}).status_code == 401
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


@pytest.mark.parametrize('has_route', [False, True])
def test_legacy_database_migration_preserves_existing_sessions(tmp_path, monkeypatch, has_route):
    import asyncio, sqlite3
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
        if has_route:
            db.execute("ALTER TABLE fish_sessions ADD COLUMN route TEXT NOT NULL DEFAULT 'fish'")
            db.execute("UPDATE fish_sessions SET route='livekit' WHERE id='one'")
    store = Store(str(path))
    assert store.rows('SELECT id,session_id,status,route FROM fish_sessions ORDER BY id') == [
        {'id': 'one', 'session_id': 'manz-legacy', 'status': 'live', 'route': 'livekit'},
        {'id': 'three', 'session_id': None, 'status': 'uncertain', 'route': 'fish'},
        {'id': 'two', 'session_id': 'hosted-session', 'status': 'ending', 'route': 'fish'},
    ]
    monkeypatch.setenv('LIVEKIT_URL', 'wss://voice.example.test')
    monkeypatch.setenv('LIVEKIT_API_KEY', 'testkey')
    monkeypatch.setenv('LIVEKIT_API_SECRET', 'testsecret')
    async def unexpected_delete(*args):
        pytest.fail('Migration must not guess which deployment owns a legacy room')
    monkeypatch.setattr(route, 'delete_room', unexpected_delete)
    assert all(row['cleanup_authority'] is None for row in store.rows('SELECT * FROM fish_sessions'))
    assert asyncio.run(route.terminate(store, store.rows("SELECT * FROM fish_sessions WHERE id='one'")[0])) is False
    assert store.rows("SELECT status FROM fish_sessions WHERE id='one'") == [{'status': 'ending'}]
    # Migration is one-time: a later Fish ID that resembles a room is not reclassified.
    store.run("UPDATE fish_sessions SET session_id='manz-hosted' WHERE id='two'")
    reopened = Store(str(path))
    assert reopened.rows("SELECT route FROM fish_sessions WHERE id='two'") == [{'route': 'fish'}]
    assert all(row['cleanup_authority'] is None for row in reopened.rows('SELECT * FROM fish_sessions'))
    assert asyncio.run(route.terminate(reopened, reopened.rows("SELECT * FROM fish_sessions WHERE id='one'")[0])) is False


def test_end_uses_stored_fish_route_even_for_room_shaped_hosted_id(livekit_env, monkeypatch):
    import cloud.fish as fish
    store, headers, deleted = livekit_env
    device = store.auth(server.bearer(headers['Authorization']), 'device')
    ident = store.reserve_fish(device, 600, 1800, 3600, 60, 3600)
    assert store.rows('SELECT route,cleanup_authority FROM fish_sessions') == [
        {'route': 'fish', 'cleanup_authority': None}]
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
    budget = client.post('/api/device/fish-session', json={**REQUEST, 'request_id': 'next-request'}, headers=headers)
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


@pytest.mark.parametrize('setting', ['LIVEKIT_WORKER_ENABLED', 'FISH_API_KEY', 'FISH_VOICE_ID', 'GEMINI_API_KEY'])
def test_managed_start_requires_explicit_worker_and_separate_provider_configuration(livekit_env, monkeypatch, setting):
    store, headers, deleted = livekit_env
    monkeypatch.delenv(setting)
    monkeypatch.setenv('VOICE_ROUTE', 'fish')  # A legacy selector never restores hosted creation.
    result = TestClient(server.app).post('/api/device/fish-session', json=REQUEST, headers=headers)
    assert result.status_code == 503
    assert result.headers['X-Manzanilla-Session-Creation'] == 'not-created'
    assert store.rows('SELECT * FROM fish_sessions') == [] and deleted == []


@pytest.mark.parametrize('url', ['ws://127.0.0.1:17880', 'ws://remote.example.test', 'wss://user:password@voice.example.test'])
def test_managed_start_never_accepts_insecure_or_embedded_credential_transport(livekit_env, monkeypatch, url):
    store, headers, _ = livekit_env
    monkeypatch.setenv('LIVEKIT_URL', url)
    response = TestClient(server.app).post('/api/device/fish-session', json=REQUEST, headers=headers)
    assert response.status_code == 503 and store.rows('SELECT * FROM fish_sessions') == []


def test_same_language_has_application_owned_manual_direction(livekit_env):
    store, headers, _ = livekit_env
    response = TestClient(server.app).post('/api/device/fish-session', json={**REQUEST, 'staff_language': 'es'}, headers=headers)
    assert response.status_code == 200
    metadata = json.loads(claims(response.json()['token'])['metadata'])
    assert metadata == {'guest_language': 'es', 'staff_language': 'es'}


@pytest.mark.parametrize('protocol', [None, 'language-v2'])
def test_legacy_caption_clients_are_rejected_before_any_new_route_is_created(livekit_env, protocol):
    store, headers, deleted = livekit_env
    request = {key: value for key, value in REQUEST.items() if key != 'protocol'}
    if protocol:
        request['caption_protocol'] = protocol
    response = TestClient(server.app).post('/api/device/fish-session', json=request, headers=headers)
    assert response.status_code == 422
    assert not store.rows('SELECT * FROM fish_sessions') and not deleted
