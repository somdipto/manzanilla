"""Owned browser route lifecycle: real SQLite admission, synthetic audio/room I/O."""
import asyncio
import json
import stat
import sys
from types import ModuleType
from uuid import uuid4

import httpx
import pytest

from cloud import fish, livekit_route
from cloud.web import create_app

HEADERS = {'X-Manzanilla-Web': '1', 'Origin': 'http://127.0.0.1:4173'}
KEY = 'synthetic-server-only-secret'
GEMINI = 'synthetic-gemini-secret'


def start_body(request_id=None):
    return {'request_id': request_id or str(uuid4()), 'guest_language': 'es', 'staff_language': 'en'}


@pytest.fixture
def local_web(tmp_path, monkeypatch):
    monkeypatch.setenv('FISH_API_KEY', KEY)
    monkeypatch.setenv('FISH_VOICE_ID', 'synthetic-voice')
    monkeypatch.setenv('GEMINI_API_KEY', GEMINI)
    monkeypatch.setenv('FISH_ACCOUNT_DAILY_SECONDS', '3600')
    monkeypatch.setenv('FISH_HOTEL_DAILY_SECONDS', '1800')
    state = {'workers': [], 'deletes': [], 'delete_ok': True}

    class Worker:
        def __init__(self, room, **kwargs):
            self.room, self.options = room, kwargs
            self.ended = asyncio.Event()
            self.stops = 0
            state['workers'].append(self)
        async def run(self):
            await self.ended.wait()
        async def stop(self):
            self.stops += 1
            self.ended.set()

    session = ModuleType('voice_worker.session')
    session.InterpreterSession = Worker
    llm = ModuleType('voice_worker.llm')
    llm.TranslateStream = lambda base, key, model: (base, key, model)
    monkeypatch.setitem(sys.modules, 'voice_worker.session', session)
    monkeypatch.setitem(sys.modules, 'voice_worker.llm', llm)
    real_mint = livekit_route.mint

    async def mint(*args, **kwargs):
        result = await real_mint(*args, **kwargs)
        if state.get('entered'):
            state['entered'].set()
            await state['release'].wait()
        return result

    async def delete(http, key, secret, room):
        state['deletes'].append((http, key, secret, room))
        return state['delete_ok']

    monkeypatch.setattr(livekit_route, 'mint', mint)
    monkeypatch.setattr(livekit_route, 'delete_room', delete)
    apps = []

    def factory(directory=None, client_host='127.0.0.1', **options):
        app = create_app(state_dir=directory or tmp_path / 'private', **options)
        apps.append(app)
        client = httpx.AsyncClient(transport=httpx.ASGITransport(app=app, client=(client_host, 12345)),
                                  base_url='http://127.0.0.1:4173', headers=HEADERS)
        return app, client

    yield factory, state, tmp_path
    for app in apps:
        if app.state.web.store is not None:
            app.state.web.close()


def test_configuration_is_private_and_never_certifies_provider_access(local_web, monkeypatch):
    factory, state, directory = local_web
    monkeypatch.setenv('FISH_API_KEY', '')
    monkeypatch.setenv('GEMINI_API_KEY', '')
    async def scenario():
        app, client = factory()
        async with client:
            status = (await client.get('/api/web/status')).json()
            assert not status['configured'] and status['provider_access'] == 'not_checked'
            assert len(status['languages']) == 28 and 'ar' in {item['code'] for item in status['languages']}
            response = await client.post('/api/web/config', json={'api_key': KEY, 'voice_id': 'synthetic-voice'})
            assert not response.json()['configured']
            missing = await client.post('/api/web/session', json=start_body())
            assert missing.status_code == 503 and 'Gemini' in missing.text
            assert not app.state.web.pending()
            response = await client.post('/api/web/config', json={'translation_api_key': GEMINI, 'voice_id': 'synthetic-voice'})
            assert response.json()['configured'] and response.json()['provider_access'] == 'not_checked'
            assert KEY not in response.text and GEMINI not in response.text
            response = await client.post('/api/web/config', json={'voice_id': 'synthetic-voice'})
            assert response.json()['key_configured'] and response.json()['translation_key_configured']
            for name in ('config.json', 'transport.json', 'state.sqlite3'):
                assert stat.S_IMODE((directory / 'private' / name).stat().st_mode) == 0o600
            assert stat.S_IMODE((directory / 'private').stat().st_mode) == 0o700
            invalid = await client.post('/api/web/config', json={'translation_api_key': GEMINI + '\n', 'voice_id': 'synthetic-voice'})
            assert invalid.status_code == 422 and GEMINI not in invalid.text
            assert not state['workers'] and not state['deletes']
    asyncio.run(scenario())


def test_real_admission_owns_worker_keys_room_and_idempotent_stop(local_web):
    factory, state, _ = local_web
    async def scenario():
        app, client = factory()
        body = start_body()
        async with client:
            response = await client.post('/api/web/session', json=body)
            assert response.status_code == 200
            ticket = response.json()
            assert set(ticket) == {'session_id', 'expires_at', 'max_duration_seconds', 'transport', 'livekit_url', 'token'}
            assert KEY not in response.text and GEMINI not in response.text
            worker = state['workers'][0]
            assert worker.room == ticket['session_id']
            assert worker.options['fish_key'] == KEY
            assert worker.options['llm'] == ('https://generativelanguage.googleapis.com/v1beta/openai', GEMINI, 'gemini-3.8-flash')
            assert worker.options['client_identity'] == 'device-' + fish.request_lease(app.state.web.device, body['request_id'])[:24]
            assert (await client.post('/api/web/config', json={'voice_id': 'other'})).status_code == 409
            assert (await client.post('/api/web/session', json=start_body())).status_code == 409
            for _ in range(2):
                assert (await client.post('/api/web/session/' + body['request_id'] + '/end', json={})).json() == {'status': 'ended'}
            assert worker.stops == 1 and len(state['deletes']) == 1
            assert state['deletes'][0][3] == ticket['session_id']
            assert app.state.web.store.rows('SELECT route,status FROM fish_sessions') == [{'route': 'livekit', 'status': 'ended'}]
            assert (await client.post('/api/web/session', json=body)).status_code == 409
    asyncio.run(scenario())


def test_stop_before_start_survives_restart(local_web):
    factory, state, _ = local_web
    async def scenario():
        app, client = factory()
        body = start_body()
        async with client:
            assert (await client.post('/api/web/session/' + body['request_id'] + '/end', json={})).status_code == 404
        app.state.web.close()
        app, client = factory()
        async with client:
            rejected = await client.post('/api/web/session', json=body)
            assert rejected.status_code == 409 and rejected.headers['x-manzanilla-session-creation'] == 'not-created'
            assert not state['workers'] and not state['deletes']
    asyncio.run(scenario())


@pytest.mark.parametrize('cancel_http', [False, True])
@pytest.mark.parametrize('cleanup_fails', [False, True])
def test_late_creation_is_fenced_and_failed_cleanup_retries_exact_room(local_web, cancel_http, cleanup_fails):
    factory, state, _ = local_web
    async def scenario():
        app, client = factory()
        state['entered'], state['release'] = asyncio.Event(), asyncio.Event()
        state['delete_ok'] = not cleanup_fails
        body = start_body()
        async with client:
            starting = asyncio.create_task(client.post('/api/web/session', json=body))
            await state['entered'].wait()
            mint = app.state.web.mints[body['request_id']]
            if cancel_http:
                starting.cancel()
                with pytest.raises(asyncio.CancelledError):
                    await starting
            else:
                stopping = asyncio.create_task(client.post('/api/web/session/' + body['request_id'] + '/end', json={}))
                while not app.state.web.store.rows('SELECT stop FROM web_requests')[0]['stop']:
                    await asyncio.sleep(0)
            state['release'].set()
            await asyncio.gather(mint, return_exceptions=True)
            if not cancel_http:
                started, stopped = await asyncio.gather(starting, stopping)
                assert started.status_code == (503 if cleanup_fails else 409) and 'token' not in started.json()
                assert stopped.status_code == (503 if cleanup_fails else 200)
            assert len(state['deletes']) == 1 and state['workers'][0].stops == 1
            if cleanup_fails:
                assert app.state.web.pending() == body['request_id']
                state['delete_ok'] = True
                assert (await client.post('/api/web/session/' + body['request_id'] + '/end', json={})).status_code == 200
                assert state['deletes'][0] == state['deletes'][1]
            assert app.state.web.pending() is None
    asyncio.run(asyncio.wait_for(scenario(), timeout=5))


def test_restart_preserves_keys_and_rejects_rotated_transport_cleanup(local_web, monkeypatch):
    factory, state, directory = local_web
    async def scenario():
        app, client = factory()
        body = start_body()
        async with client:
            assert (await client.post('/api/web/session', json=body)).status_code == 200
        transport = app.state.web.transport
        await state['workers'][0].stop()  # Simulate lost process; durable room remains.
        app.state.web.close()
        monkeypatch.setenv('FISH_API_KEY', 'different')
        monkeypatch.setenv('GEMINI_API_KEY', 'different')
        app, client = factory(transport=(transport[0], transport[1], 'rotated-secret'))
        assert app.state.web.key == KEY and app.state.web.translation_key == GEMINI
        async with client:
            assert (await client.post('/api/web/session/' + body['request_id'] + '/end', json={})).status_code == 503
            assert not state['deletes'] and app.state.web.pending() == body['request_id']
        app.state.web.close()
        app, client = factory()
        assert app.state.web.transport == transport
        async with client:
            assert (await client.post('/api/web/session/' + body['request_id'] + '/end', json={})).status_code == 200
            assert len(state['deletes']) == 1 and app.state.web.pending() is None
    asyncio.run(scenario())


def test_crash_before_reservation_settles_without_room_io(local_web):
    factory, state, _ = local_web
    async def scenario():
        app, client = factory()
        ident = str(uuid4())
        app.state.web.store.run("INSERT INTO web_requests(id,status) VALUES(?,'pending')", (ident,))
        async with client:
            assert (await client.post('/api/web/session/' + ident + '/end', json={})).status_code == 200
            assert not state['workers'] and not state['deletes']
    asyncio.run(scenario())


@pytest.mark.parametrize('headers', [
    {'Origin': 'https://attacker.invalid'}, {'Origin': 'http://127.0.0.1:9999'},
    {'Origin': 'null'}, {'Host': 'attacker.invalid'}, {'Host': '127.0.0.1.attacker.invalid'},
    {'Host': 'user@127.0.0.1:4173'}, {'Sec-Fetch-Site': 'cross-site'},
    {'X-Manzanilla-Web': ''}, {'Content-Type': 'text/plain'},
])
def test_cross_origin_and_simple_post_cannot_configure(local_web, headers):
    factory, state, _ = local_web
    async def scenario():
        app, client = factory()
        async with client:
            response = await client.post('/api/web/config', json={'voice_id': 'changed'}, headers=headers)
            assert response.status_code == 403 and response.headers['cache-control'] == 'no-store'
            assert 'access-control-allow-origin' not in response.headers
            assert app.state.web.voice == 'synthetic-voice' and not state['workers']
    asyncio.run(scenario())


def test_non_loopback_socket_cannot_spoof_forwarded_headers(local_web):
    factory, _, _ = local_web
    async def scenario():
        app, client = factory(client_host='192.0.2.5')
        async with client:
            assert (await client.get('/api/web/status', headers={'X-Forwarded-For': '127.0.0.1'})).status_code == 403
    asyncio.run(scenario())


def test_security_policy_only_allows_exact_local_transport_and_static_allowlist(local_web):
    factory, state, directory = local_web
    assets = directory / 'assets'
    (assets / 'dist').mkdir(parents=True)
    for name, content in [('index.html', '<title>Synthetic</title>'), ('styles.css', 'body{}'), ('dist/app.js', ''), ('playback-worklet.js', ''), ('private.txt', KEY)]:
        (assets / name).write_text(content)
    async def scenario():
        app, client = factory(static_dir=assets)
        async with client:
            for path in ('/', '/styles.css', '/dist/app.js', '/playback-worklet.js'):
                response = await client.get(path)
                assert response.status_code == 200
                csp = response.headers['content-security-policy']
                assert 'ws://127.0.0.1:17880 http://127.0.0.1:17880' in csp
                assert ' ws:;' not in csp and "frame-ancestors 'none'" in csp
            for path in ('/private.txt', '/config.json', '/transport.json', '/docs', '/openapi.json'):
                assert (await client.get(path)).status_code == 404
            assert not state['workers']
    asyncio.run(scenario())


def test_same_language_allowed_and_unsupported_speech_rejected(local_web):
    factory, state, _ = local_web
    async def scenario():
        app, client = factory()
        async with client:
            assert (await client.post('/api/web/session', json={**start_body(), 'guest_language': 'ka'})).status_code == 422
            body = {**start_body(), 'staff_language': 'es'}
            assert (await client.post('/api/web/session', json=body)).status_code == 200
            assert (await client.post('/api/web/session/' + body['request_id'] + '/end', json={})).status_code == 200
    asyncio.run(scenario())


def test_second_owner_is_rejected_without_replacing_state(local_web):
    factory, state, _ = local_web
    app, client = factory()
    with pytest.raises(RuntimeError, match='Another browser translator'):
        factory()
    asyncio.run(client.aclose())
    assert not state['workers']


def test_budget_refusal_is_proven_noncreation(local_web, monkeypatch):
    factory, state, _ = local_web
    monkeypatch.setenv('FISH_ACCOUNT_DAILY_SECONDS', '0')
    async def scenario():
        app, client = factory()
        async with client:
            response = await client.post('/api/web/session', json=start_body())
            assert response.status_code == 429 and response.headers['x-manzanilla-session-creation'] == 'not-created'
            assert app.state.web.pending() is None and not state['workers'] and not state['deletes']
    asyncio.run(scenario())


def load_runner():
    import importlib.util
    from cloud.web import ROOT
    spec = importlib.util.spec_from_file_location('manzanilla_run_web', ROOT / 'scripts/run-web.py')
    runner = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(runner)
    return runner


def test_runner_requires_real_binary_before_opening_state(monkeypatch, tmp_path, capsys):
    runner = load_runner()
    monkeypatch.setattr(sys, 'argv', ['run-web.py', '--state-dir', str(tmp_path / 'not-created'),
                                    '--livekit-server', '/missing/livekit-server'])
    monkeypatch.setattr(runner.shutil, 'which', lambda path: None)
    with pytest.raises(SystemExit) as stopped:
        runner.main()
    assert stopped.value.code == 1
    assert not (tmp_path / 'not-created').exists()
    assert '--livekit-server' in capsys.readouterr().err


def test_runner_launches_loopback_only_with_private_stable_authority(local_web, monkeypatch):
    factory, state, directory = local_web
    app, client = factory()
    runtime = app.state.web
    runner = load_runner()
    bindings = []
    launches = []

    class Socket:
        def __enter__(self): return self
        def __exit__(self, *args): pass
        def bind(self, address): bindings.append(address)

    class Process:
        stopped = False
        def poll(self): return 0 if self.stopped else None
        def terminate(self): self.stopped = True
        def wait(self, timeout=None): return 0

    def spawn(command, **kwargs):
        launches.append((command, kwargs))
        return Process()

    monkeypatch.setattr(runner.socket, 'socket', lambda *args: Socket())
    monkeypatch.setattr(runner.socket, 'create_connection', lambda *args, **kwargs: Socket())
    monkeypatch.setattr(runner.subprocess, 'Popen', spawn)
    process = runner.start_livekit(runtime, '/explicit/livekit-server')
    command, options = launches[0]
    assert command == ['/explicit/livekit-server', '--config', str(directory / 'private/livekit.json')]
    assert bindings == [('127.0.0.1', 17880), ('127.0.0.1', 17881), ('127.0.0.1', 17882)]
    assert not any(name.startswith(('FISH_', 'GEMINI_', 'LIVEKIT_', 'OPENAI_')) for name in options['env'])
    path = directory / 'private/livekit.json'
    configuration = json.loads(path.read_text())
    assert configuration['bind_addresses'] == ['127.0.0.1']
    assert configuration['rtc']['node_ip'] == '127.0.0.1'
    assert configuration['rtc']['use_external_ip'] is False
    assert configuration['rtc']['interfaces']['includes'] == ['lo', 'lo0']
    assert configuration['keys'] == {runtime.transport[1]: runtime.transport[2]}
    assert stat.S_IMODE(path.stat().st_mode) == 0o600
    runner.stop_livekit(process)
    assert process.stopped and not state['workers']
    # socket monkeypatches are deliberately removed before asyncio creates its
    # own event-loop wakeup sockets.
    monkeypatch.undo()
    asyncio.run(client.aclose())


def test_historical_hosted_web_cleanup_keeps_original_saved_key_without_gemini(local_web, monkeypatch):
    factory, state, directory = local_web
    actual_client = httpx.AsyncClient
    requests = []

    async def provider(request):
        requests.append(request)
        assert request.headers['authorization'] == 'Bearer ' + KEY
        assert request.url.path == '/v1/agent/sessions/historical-web-session/end'
        return httpx.Response(204)

    async def scenario():
        app, client = factory()
        body = start_body()
        runtime = app.state.web
        ident = fish.request_lease(runtime.device, body['request_id'])
        runtime.store.reserve_fish(runtime.device, 600, 1800, 3600, 60, 3600, reservation=ident)
        runtime.store.run("UPDATE fish_sessions SET status='live',session_id='historical-web-session' WHERE id=?", (ident,))
        runtime.store.run("INSERT INTO web_requests(id,status) VALUES(?,'pending')", (body['request_id'],))
        runtime.translation_key = ''
        runtime.save_config()
        await client.aclose()
        runtime.close()
        monkeypatch.setenv('FISH_API_KEY', 'replacement-must-not-be-used')
        app, client = factory()
        monkeypatch.setattr(fish.httpx, 'AsyncClient', lambda **kwargs: actual_client(
            transport=httpx.MockTransport(provider), **kwargs))
        async with client:
            assert not (await client.get('/api/web/status')).json()['configured']
            response = await client.post('/api/web/session/' + body['request_id'] + '/end', json={})
            assert response.status_code == 200
            assert app.state.web.pending() is None
            assert len(requests) == 1 and not state['workers'] and not state['deletes']
    asyncio.run(scenario())


def test_missing_persisted_transport_never_rotates_unsettled_authority(local_web):
    factory, state, directory = local_web
    async def scenario():
        app, client = factory()
        async with client:
            assert (await client.post('/api/web/session', json=start_body())).status_code == 200
        await state['workers'][0].stop()
        app.state.web.close()
        saved = directory / 'private/transport.json'
        original = saved.read_bytes()
        saved.unlink()
        with pytest.raises(ValueError, match='Restore original transport'):
            factory()
        assert not saved.exists() and not state['deletes']
        saved.write_bytes(original)
    asyncio.run(scenario())
