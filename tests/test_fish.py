"""Historical hosted leases remain cleanup-only after application-route cutover."""
import asyncio
import json
import time

import httpx
import pytest
from fastapi.testclient import TestClient

import cloud.app as server
import cloud.fish as fish
from cloud.db import Store
from cloud.models import Hotel


@pytest.fixture
def historical(tmp_path, monkeypatch):
    store = Store(str(tmp_path / 'history.db'))
    store.save_hotel(Hotel(id='a', name='Synthetic', supported_languages=['es', 'en']).model_dump())
    store.save_hotel(Hotel(id='b', name='Other synthetic').model_dump())
    owner = store.issue('a', 'device')
    principal = store.auth(owner['token'], 'device')
    ident = fish.request_lease(principal, 'historical-request')
    store.reserve_fish(principal, 600, 1800, 3600, 60, 3600, reservation=ident)
    store.run("UPDATE fish_sessions SET status='uncertain',creation_deadline=? WHERE id=?", (time.time() - 1, ident))
    monkeypatch.setattr(server, 'store', store)
    monkeypatch.setenv('FISH_API_KEY', 'synthetic-cleanup-secret')
    monkeypatch.setenv('ORANGE_ADMIN_TOKEN', 'x' * 40)
    state = {'status': 'active', 'end_status': 204, 'calls': []}
    actual_client = httpx.AsyncClient

    async def provider(request):
        assert request.headers['authorization'] == 'Bearer synthetic-cleanup-secret'
        state['calls'].append((request.method, request.url.path, dict(request.url.params)))
        if state.get('failure') == 'timeout':
            raise httpx.ReadTimeout('private synthetic-cleanup-secret', request=request)
        if state.get('failure') == 'network':
            raise httpx.ConnectError('private synthetic-cleanup-secret', request=request)
        if state.get('failure') == 'http':
            return httpx.Response(503, json={'private': 'synthetic-cleanup-secret'})
        if request.method == 'GET':
            assert request.url.path == '/v1/agent/sessions'
            assert request.url.params['end_user_id'] == ident
            assert request.url.params['status'] == 'pending,active,unknown,completed,failed'
            return httpx.Response(200, json=state.get('listing', {
                'sessions': [{'session_id': 'historical-session', 'end_user_id': ident, 'status': state['status']}],
                'has_more': False}))
        assert request.method == 'POST' and request.url.path == '/v1/agent/sessions/historical-session/end'
        return httpx.Response(state['end_status'])

    monkeypatch.setattr(fish.httpx, 'AsyncClient', lambda **kwargs: actual_client(transport=httpx.MockTransport(provider), **kwargs))
    yield store, TestClient(server.app), owner, ident, state
    store.db.close()


def endpoint():
    return '/api/device/fish-session-requests/historical-request/end'


def auth(owner):
    return {'Authorization': 'Bearer ' + owner['token']}


@pytest.mark.parametrize('status', ['pending', 'active', 'unknown', 'completed', 'failed'])
def test_revocation_recovers_exact_uncertain_lease_after_deleting_device_credential(historical, status):
    store, client, owner, ident, state = historical
    state['status'] = status
    response = client.delete('/api/credentials/' + owner['id'], headers={'Authorization': 'Bearer ' + 'x' * 40})
    assert response.status_code == 200
    assert store.auth(owner['token'], 'device') is None
    assert store.rows('SELECT session_id,status,reserved_seconds FROM fish_sessions') == [
        {'session_id': 'historical-session', 'status': 'ended', 'reserved_seconds': 600}]
    assert [method for method, _, _ in state['calls']] == (['GET'] if status in {'completed', 'failed'} else ['GET', 'POST'])
    calls = list(state['calls'])
    assert client.post(endpoint(), headers=auth(owner)).status_code == 200
    assert state['calls'] == calls
    assert client.post('/api/device/fish-session', headers=auth(owner), json={
        'guest_language': 'es', 'staff_language': 'en', 'mode': 'translation',
        'protocol': 'translation-v1', 'request_id': 'new-request'}).status_code == 401


@pytest.mark.parametrize('caller', ['device', 'admin'])
@pytest.mark.parametrize('case', ['empty', 'foreign', 'multiple', 'incomplete', 'missing-pagination', 'malformed', 'invalid-id'])
def test_uncertain_cleanup_requires_unique_complete_exact_provider_match(historical, caller, case):
    store, client, owner, ident, state = historical
    match = {'session_id': 'historical-session', 'end_user_id': ident, 'status': 'active'}
    listing = {'sessions': [match], 'has_more': False}
    if case == 'empty': listing['sessions'] = []
    if case == 'foreign': match['end_user_id'] = 'somebody-else'
    if case == 'multiple': listing['sessions'].append(dict(match))
    if case == 'incomplete': listing['has_more'] = True
    if case == 'missing-pagination': del listing['has_more']
    if case == 'malformed': listing['sessions'] = None
    if case == 'invalid-id': match['session_id'] = '../other'
    state['listing'] = listing
    if caller == 'admin':
        response = client.delete('/api/credentials/' + owner['id'], headers={'Authorization': 'Bearer ' + 'x' * 40})
        assert response.status_code == 202 and response.json()['voice_cleanup'] == 'pending'
    else:
        response = client.post(endpoint(), headers=auth(owner))
        assert response.status_code == 503
    assert len(state['calls']) == 1 and state['calls'][0][0] == 'GET'
    assert store.rows('SELECT session_id,reserved_seconds FROM fish_sessions') == [{'session_id': None, 'reserved_seconds': 600}]
    assert store.rows('SELECT status FROM fish_sessions')[0]['status'] not in {'ended', 'expired'}


@pytest.mark.parametrize('failure', ['http', 'timeout', 'network'])
def test_failed_revocation_lookup_stays_pending_without_leaking_provider_details(historical, failure):
    store, client, owner, ident, state = historical
    state['failure'] = failure
    response = client.delete('/api/credentials/' + owner['id'], headers={'Authorization': 'Bearer ' + 'x' * 40})
    assert response.status_code == 202 and response.json()['voice_cleanup'] == 'pending'
    assert 'private' not in response.text and 'synthetic-cleanup-secret' not in response.text
    assert not store.auth_device_lease(ident)
    assert store.rows('SELECT status FROM fish_sessions') == [{'status': 'revoked'}]


@pytest.mark.parametrize('end_status', [200, 202, 503])
def test_recovered_revoked_session_requires_confirmed_end_and_original_bearer_can_retry(historical, end_status):
    store, client, owner, ident, state = historical
    state['end_status'] = end_status
    response = client.delete('/api/credentials/' + owner['id'], headers={'Authorization': 'Bearer ' + 'x' * 40})
    assert response.status_code == 202
    assert store.rows('SELECT status,session_id FROM fish_sessions') == [{'status': 'ending', 'session_id': 'historical-session'}]
    assert not store.auth(owner['token'], 'device')
    state['end_status'] = 204
    assert client.post(endpoint(), headers=auth(owner)).status_code == 200
    assert [method for method, _, _ in state['calls']] == ['GET', 'POST', 'POST']
    assert store.rows('SELECT status,reserved_seconds FROM fish_sessions') == [{'status': 'ended', 'reserved_seconds': 600}]


def test_historical_cleanup_capability_does_not_authorize_other_leases_or_operations(historical):
    store, client, owner, ident, state = historical
    other = store.issue('a', 'device')
    foreign = store.issue('b', 'device')
    staff = store.issue('a', 'staff')
    store.run('DELETE FROM credentials WHERE id=?', (owner['id'],))
    replacement = store.issue('a', 'device')
    for credential in (other, foreign, staff, replacement, {'token': 'wrong'}):
        assert client.post(endpoint(), headers=auth(credential)).status_code == 401
    assert state['calls'] == []
    wrong = client.post('/api/device/fish-session-requests/other-request/end', headers=auth(owner))
    assert wrong.status_code == 404
    assert client.post('/api/device/heartbeat', json={'app_version': 'synthetic', 'config_version': 0}, headers=auth(owner)).status_code == 401
    assert client.post(endpoint(), headers=auth(owner)).status_code == 200


@pytest.mark.parametrize('known_id', [False, True])
def test_crashed_creation_recovers_only_when_deadline_passes(historical, known_id):
    store, client, owner, ident, state = historical
    store.run("UPDATE fish_sessions SET status='creating',creation_deadline=?,session_id=? WHERE id=?",
              (time.time() + 60, 'historical-session' if known_id else None, ident))
    assert client.post(endpoint(), headers=auth(owner)).status_code == 503
    assert state['calls'] == []
    store.run('UPDATE fish_sessions SET creation_deadline=? WHERE id=?', (time.time() - 1, ident))
    assert client.post(endpoint(), headers=auth(owner)).status_code == 200
    assert [method for method, _, _ in state['calls']] == (['POST'] if known_id else ['GET', 'POST'])


def test_missing_cleanup_key_keeps_original_unsettled_history(historical, monkeypatch):
    store, client, owner, ident, state = historical
    monkeypatch.delenv('FISH_API_KEY')
    assert client.post(endpoint(), headers=auth(owner)).status_code == 503
    assert not state['calls']
    assert store.rows('SELECT status FROM fish_sessions') == [{'status': 'uncertain'}]


def test_cleanup_after_database_restart_preserves_exact_identity_and_budget(historical, monkeypatch):
    store, client, owner, ident, state = historical
    path = store.db.execute('PRAGMA database_list').fetchone()[2]
    reopened = Store(path)
    monkeypatch.setattr(server, 'store', reopened)
    try:
        assert client.post(endpoint(), headers=auth(owner)).status_code == 200
        assert reopened.rows('SELECT id,status,reserved_seconds FROM fish_sessions') == [
            {'id': ident, 'status': 'ended', 'reserved_seconds': 600}]
    finally:
        reopened.db.close()


@pytest.mark.parametrize('stage,category,status', [
    ('https://private', 'http', 401), ('configuration', 'private', 0),
    ('configuration', 'http', 999), ('configuration', 'http', '401'),
])
def test_failure_reference_rejects_unbounded_values(stage, category, status):
    with pytest.raises(ValueError):
        fish.failure_headers(stage, category, status)
