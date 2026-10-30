"""Loopback-only browser translator; run in its own process, never cloud.app."""
import asyncio
from contextlib import asynccontextmanager
import fcntl
import ipaddress
import json
import os
from pathlib import Path
import secrets
import stat
import sys
from urllib.parse import urlsplit
from uuid import UUID

from fastapi import FastAPI, HTTPException, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import FileResponse, JSONResponse
from pydantic import Field, field_validator

from . import fish, livekit_route
from .db import Store
from .models import Empty, Hotel, Strict

ROOT = Path(__file__).resolve().parent.parent
CLEANUP = 'Session cleanup is not confirmed. Keep this server running and retry cleanup before starting again.'
START_FAILED = 'Interpreter could not start. Check Fish, Gemini and local LiveKit configuration. Provider access has not been verified.'
SECURITY_HEADERS = {
    'Cache-Control': 'no-store',
    'Content-Security-Policy': "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self' wss: https:; media-src 'self' blob:; font-src 'self'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'",
    'Permissions-Policy': 'microphone=(self), camera=(), geolocation=()',
    'Referrer-Policy': 'no-referrer',
    'X-Content-Type-Options': 'nosniff',
    'X-Frame-Options': 'DENY',
}


def default_state_dir():
    if sys.platform == 'darwin':
        return Path.home() / 'Library' / 'Application Support' / 'Manzanilla' / 'web'
    return Path(os.getenv('XDG_DATA_HOME', str(Path.home() / '.local' / 'share'))) / 'manzanilla' / 'web'


def request_uuid(value):
    # Canonical spelling keeps request ownership independent of UUID aliases.
    if str(UUID(value)) != value:
        raise ValueError('Use a canonical UUID request identity')
    return value


class Configuration(Strict):
    api_key: str = Field(default='', max_length=4096)
    translation_api_key: str = Field(default='', max_length=4096)
    voice_id: str = Field(min_length=1, max_length=200)

    @field_validator('api_key', 'translation_api_key')
    @classmethod
    def valid_key(cls, value):
        if value and (value != value.strip() or any(ord(c) < 33 or ord(c) > 126 for c in value)):
            raise ValueError('Invalid credential format')
        return value

    @field_validator('voice_id')
    @classmethod
    def valid_voice(cls, value):
        return fish.provider_id(value)


class Start(Strict):
    guest_language: str = Field(min_length=2, max_length=2)
    staff_language: str = Field(min_length=2, max_length=2)
    request_id: str = Field(min_length=36, max_length=36)

    @field_validator('request_id')
    @classmethod
    def valid_id(cls, value):
        return request_uuid(value)

    @field_validator('guest_language', 'staff_language')
    @classmethod
    def valid_language(cls, value):
        if value not in {item['code'] for item in livekit_route.speech_languages()}:
            raise ValueError('Unsupported language')
        return value


class LocalOnly:
    """Check raw ASGI client/Host, not forwarded headers or a CORS allowlist."""
    def __init__(self, app, transport_url):
        self.app = app
        self.headers = dict(SECURITY_HEADERS)
        if transport_url.startswith('ws://127.0.0.1:'):
            companion = 'http://' + transport_url[5:]
            self.headers['Content-Security-Policy'] = self.headers['Content-Security-Policy'].replace(
                "connect-src 'self'", "connect-src 'self' " + transport_url + ' ' + companion)

    async def __call__(self, scope, receive, send):
        if scope['type'] != 'http':
            await self.app(scope, receive, send)
            return
        headers = {}
        duplicates = False
        for key, value in scope['headers']:
            name = key.decode('latin1').lower()
            if name in headers and name in {'host', 'origin', 'content-type', 'x-manzanilla-web'}:
                duplicates = True
            headers[name] = value.decode('latin1')
        host = headers.get('host', '')
        try:
            client = scope.get('client')
            local_client = bool(client) and ipaddress.ip_address(client[0]).is_loopback
            parsed = urlsplit('http://' + host)
            hostname = parsed.hostname
            local_host = hostname == 'localhost' or ipaddress.ip_address(hostname or '').is_loopback
            valid_host = bool(host) and not parsed.username and not parsed.password and not parsed.path and not parsed.query and not parsed.fragment
            if parsed.port is not None and not 1 <= parsed.port <= 65535:
                valid_host = False
        except ValueError:
            local_client = local_host = valid_host = False
        expected_origin = scope.get('scheme', 'http') + '://' + host
        cross_origin = headers.get('origin', expected_origin) != expected_origin
        mutation = scope['method'] not in {'GET', 'HEAD'}
        allowed = (local_client and local_host and valid_host and not duplicates and not cross_origin
                   and headers.get('sec-fetch-site') not in {'cross-site', 'same-site'})
        if mutation:
            allowed = (allowed and headers.get('x-manzanilla-web') == '1'
                       and headers.get('content-type', '').split(';', 1)[0].strip().lower() == 'application/json')
        async def secured_send(message):
            if message['type'] == 'http.response.start':
                message['headers'] = list(message.get('headers', [])) + [
                    (key.lower().encode(), value.encode()) for key, value in self.headers.items()]
            await send(message)
        if not allowed:
            await JSONResponse({'detail': 'Only same-origin loopback requests are allowed.'}, status_code=403)(scope, receive, secured_send)
            return
        await self.app(scope, receive, secured_send)


class WebState:
    """Single-process owner; SQLite retains raw request IDs that Fish hashes away."""
    def __init__(self, state_dir, *, transport=None):
        self.directory = Path(state_dir).expanduser().absolute()
        if self.directory.is_symlink():
            raise ValueError('State directory must not be a symbolic link')
        self.directory.mkdir(mode=0o700, parents=True, exist_ok=True)
        if self.directory.stat().st_uid != os.getuid():
            raise ValueError('State directory must belong to the current user')
        self.directory.chmod(0o700)
        self.lock_fd = os.open(self.directory / 'owner.lock', os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
        try:
            fcntl.flock(self.lock_fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError:
            os.close(self.lock_fd)
            raise RuntimeError('Another browser translator owns this state directory') from None
        self.store = None
        try:
            for name in ('state.sqlite3', 'state.sqlite3-wal', 'state.sqlite3-shm', 'config.json', 'transport.json'):
                path = self.directory / name
                if path.is_symlink():
                    raise ValueError('Private state files must not be symbolic links')
                if path.exists():
                    if path.stat().st_uid != os.getuid() or not stat.S_ISREG(path.stat().st_mode):
                        raise ValueError('Invalid private state file')
                    path.chmod(0o600)
            mask = os.umask(0o077)
            try:
                self.store = Store(str(self.directory / 'state.sqlite3'))
            finally:
                os.umask(mask)
            self.store.run('CREATE TABLE IF NOT EXISTS web_requests(id TEXT PRIMARY KEY, status TEXT NOT NULL, stop INTEGER NOT NULL DEFAULT 0)')
            self.store.run("CREATE UNIQUE INDEX IF NOT EXISTS web_pending ON web_requests(status) WHERE status='pending'")
            if not self.store.rows('SELECT id FROM hotels WHERE id=?', ('local-browser',)):
                self.store.save_hotel(Hotel(id='local-browser', name='Local browser translator', timezone='UTC').model_dump())
            devices = self.store.rows("SELECT id,hotel,role FROM credentials WHERE hotel='local-browser' AND role='device'")
            if not devices:
                self.store.issue('local-browser', 'device', 'local-browser-device')
                devices = self.store.rows("SELECT id,hotel,role FROM credentials WHERE id='local-browser-device'")
            if len(devices) != 1:
                raise ValueError('Invalid local device ownership')
            self.device = devices[0]
            config_path = self.directory / 'config.json'
            saved = json.loads(config_path.read_text()) if config_path.exists() else {}
            # Persisted user settings win over process environment, including
            # after restart. No shared credentials or alternate provider keys.
            self.key = saved.get('api_key', os.getenv('FISH_API_KEY', ''))
            self.voice = saved.get('voice_id', os.getenv('FISH_VOICE_ID', ''))
            self.translation_key = saved.get('translation_api_key', os.getenv('GEMINI_API_KEY', ''))
            transport_path = self.directory / 'transport.json'
            if transport is None:
                if transport_path.exists():
                    owned = json.loads(transport_path.read_text())
                else:
                    if self.store.rows("SELECT id FROM fish_sessions WHERE route='livekit' AND status NOT IN ('ended','expired')"):
                        raise ValueError('Restore original transport.json before historical room cleanup')
                    owned = {'port': 17880, 'tcp_port': 17881, 'udp_port': 17882,
                             'key': secrets.token_hex(16), 'secret': secrets.token_hex(32)}
                    self.write_private('transport.json', owned)
                ports = [owned.get(name) for name in ('port', 'tcp_port', 'udp_port')]
                if any(type(port) is not int or not 1024 <= port <= 65535 for port in ports) or len(set(ports)) != 3:
                    raise ValueError('Invalid persisted LiveKit ports')
                self.owned_transport = owned
                transport = (f"ws://127.0.0.1:{owned['port']}", owned['key'], owned['secret'])
            else:
                self.owned_transport = None
            url, _, key, secret = livekit_route.config(transport)
            self.transport = (url, key, secret)
            self.workers = {}
            self.apply_environment()
            self.guard = asyncio.Lock()
            self.mints = {}
            self.ends = {}
        except BaseException:
            self.close()
            raise

    def close(self):
        if self.store is not None:
            self.store.db.close()
            self.store = None
        if self.lock_fd is not None:
            os.close(self.lock_fd)
            self.lock_fd = None

    def apply_environment(self):
        os.environ['FISH_API_KEY'] = self.key
        os.environ['FISH_VOICE_ID'] = self.voice

    def write_private(self, name, value):
        path = self.directory / name
        temporary = self.directory / (name + '.new')
        fd = os.open(temporary, os.O_CREAT | os.O_TRUNC | os.O_WRONLY | os.O_NOFOLLOW, 0o600)
        with os.fdopen(fd, 'w') as stream:
            os.fchmod(stream.fileno(), 0o600)
            json.dump(value, stream)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
        directory_fd = os.open(self.directory, os.O_RDONLY)
        try:
            os.fsync(directory_fd)
        finally:
            os.close(directory_fd)

    def save_config(self):
        self.write_private('config.json', {'api_key': self.key, 'voice_id': self.voice,
                                          'translation_api_key': self.translation_key})

    def pending(self):
        rows = self.store.rows("SELECT id FROM web_requests WHERE status='pending'")
        return rows[0]['id'] if rows else None

    def status(self):
        return {'configured': bool(self.key and self.voice and self.translation_key),
                'key_configured': bool(self.key), 'translation_key_configured': bool(self.translation_key),
                'voice_id': self.voice, 'languages': livekit_route.speech_languages(),
                'pending_request_id': self.pending(), 'route': 'application-controlled',
                'translation_model': 'gemini-3.8-flash', 'asr_model': 'transcribe-stream-1',
                'tts_model': 's2.1-pro', 'provider_access': 'not_checked'}

    def settled(self, request_id):
        self.store.run("UPDATE web_requests SET status='ended' WHERE id=? AND status='pending'", (request_id,))

    async def configure(self, value):
        async with self.guard:
            if self.pending():
                raise HTTPException(409, 'Finish session cleanup before changing configuration.')
            previous = self.key, self.voice, self.translation_key
            self.key, self.voice = value.api_key or self.key, value.voice_id
            self.translation_key = value.translation_api_key or self.translation_key
            try:
                self.save_config()
            except OSError:
                self.key, self.voice, self.translation_key = previous
                raise HTTPException(503, 'Private configuration could not be saved.') from None
            self.apply_environment()
            return self.status()

    async def cleanup(self, request_id):
        # Only called after mint is finished, or for durable restart recovery.
        ident = fish.request_lease(self.device, request_id)
        leases = self.store.rows('SELECT * FROM fish_sessions WHERE id=?', (ident,))
        if not leases:
            # Owner recorded before mint; a crash before reservation proves no call.
            self.settled(request_id)
            return {'status': 'ended'}
        try:
            if leases[0]['route'] == 'livekit':
                owned = self.workers.pop(request_id, None)
                if owned:
                    worker, task = owned
                    try:
                        await worker.stop()
                        await asyncio.gather(task, return_exceptions=True)
                    except BaseException:
                        self.workers[request_id] = owned
                        raise
                if not await livekit_route.terminate(self.store, leases[0], transport=self.transport):
                    raise HTTPException(503, CLEANUP)
            else:
                await fish.end_request(self.store, self.device, request_id)
        except Exception:
            raise HTTPException(503, CLEANUP) from None
        self.settled(request_id)
        return {'status': 'ended'}

    async def mint(self, value):
        failure = None
        result = None
        try:
            value = fish.TranslationSession(**value.model_dump(), mode='translation', protocol='translation-v1')
            result = await livekit_route.mint(self.store, self.device, value,
                                             transport=self.transport, local=True)
            # Import lazily: production admin does not load the audio runtime.
            worker_path = str(ROOT / 'voice-worker')
            if worker_path not in sys.path:
                sys.path.insert(0, worker_path)
            from voice_worker.session import InterpreterSession
            from voice_worker.llm import TranslateStream
            url, key, secret = self.transport
            ident = fish.request_lease(self.device, value.request_id)
            worker = InterpreterSession(
                result['session_id'], livekit_url=url, api_key=key, api_secret=secret,
                fish_key=self.key, voice_id=self.voice,
                llm=TranslateStream('https://generativelanguage.googleapis.com/v1beta/openai',
                                    self.translation_key, 'gemini-3.8-flash'),
                client_identity='device-' + ident[:24],
                guest=value.guest_language, staff=value.staff_language,
                session_id=result['session_id'])
            task = asyncio.create_task(worker.run())
            task.add_done_callback(self.consume)
            self.workers[value.request_id] = worker, task
        except HTTPException as exc:
            headers = fish.NOT_CREATED if (exc.headers or {}).get('X-Manzanilla-Session-Creation') == 'not-created' else None
            failure = HTTPException(exc.status_code, START_FAILED, headers=headers)
            if headers:
                self.settled(value.request_id)
        except Exception:
            failure = HTTPException(503, START_FAILED)
        rows = self.store.rows('SELECT stop,status FROM web_requests WHERE id=?', (value.request_id,))
        if rows[0]['stop']:
            if rows[0]['status'] == 'pending':
                await self.cleanup(value.request_id)
            raise HTTPException(409, 'Start was stopped. The session has been ended.', headers=fish.NOT_CREATED)
        if failure:
            raise failure
        return {key: result[key] for key in ('session_id', 'expires_at', 'max_duration_seconds', 'transport', 'livekit_url', 'token')}

    @staticmethod
    def consume(task):
        if not task.cancelled():
            task.exception()

    async def start(self, value, request):
        async with self.guard:
            prior = self.store.rows('SELECT status FROM web_requests WHERE id=?', (value.request_id,))
            if prior:
                headers = fish.NOT_CREATED if prior[0]['status'] in {'ended', 'blocked'} else None
                raise HTTPException(409, 'This request identity has already been used. Finish cleanup before starting again.', headers=headers)
            if self.pending():
                raise HTTPException(409, CLEANUP, headers=fish.NOT_CREATED)
            if not self.key or not self.voice or not self.translation_key:
                raise HTTPException(503, 'Configure your Fish key, voice and Gemini key before starting.', headers=fish.NOT_CREATED)
            try:
                self.save_config()
            except OSError:
                raise HTTPException(503, 'Private request ownership could not be saved.', headers=fish.NOT_CREATED) from None
            self.store.run("INSERT INTO web_requests(id,status) VALUES(?,'pending')", (value.request_id,))
            task = asyncio.create_task(self.mint(value))
            def mint_finished(done):
                self.consume(done)
                if self.mints.get(value.request_id) is done:
                    self.mints.pop(value.request_id, None)
            task.add_done_callback(mint_finished)
            self.mints[value.request_id] = task
        async def watch_disconnect():
            # The JSON body has already been consumed. Await ASGI disconnect
            # directly: Request.is_disconnected's cancellation scope can swallow
            # this watcher's cancellation and make a stopped HTTP owner hang.
            while not task.done():
                event = await request.receive()
                if event['type'] == 'http.disconnect':
                    self.store.run('UPDATE web_requests SET stop=1 WHERE id=?', (value.request_id,))
                    return
        watcher = asyncio.create_task(watch_disconnect())
        try:
            return await asyncio.shield(task)
        except asyncio.CancelledError:
            self.store.run('UPDATE web_requests SET stop=1 WHERE id=?', (value.request_id,))
            # A still-running mint observes stop itself. If it just completed,
            # retain an independent cleanup task even though the HTTP owner left.
            if task.done() and self.pending() == value.request_id:
                cleanup = asyncio.create_task(self.cleanup(value.request_id))
                cleanup.add_done_callback(self.consume)
                self.ends[value.request_id] = cleanup
            raise
        finally:
            watcher.cancel()
            await asyncio.gather(watcher, return_exceptions=True)
            if task.done():
                self.mints.pop(value.request_id, None)

    async def end(self, request_id):
        async with self.guard:
            rows = self.store.rows('SELECT status FROM web_requests WHERE id=?', (request_id,))
            if not rows or rows[0]['status'] == 'blocked':
                # Deny unowned requests while fencing a delayed HTTP Start. No
                # provider lookup is allowed for an unknown caller-supplied ID.
                self.store.run("INSERT OR IGNORE INTO web_requests(id,status,stop) VALUES(?,'blocked',1)", (request_id,))
                raise HTTPException(404, 'No session belongs to this request.', headers=fish.NOT_CREATED)
            if rows[0]['status'] == 'ended':
                return {'status': 'ended'}
            self.store.run('UPDATE web_requests SET stop=1 WHERE id=?', (request_id,))
            task = self.ends.get(request_id)
            if task is None or task.done():
                mint = self.mints.get(request_id)
                # A completed mint belongs to an earlier cleanup attempt. An
                # explicit Retry must perform a new end, not replay its failure.
                # An End already waiting captured the running task below and
                # still reports that attempt's outcome without a duplicate end.
                if mint is not None and mint.done():
                    self.mints.pop(request_id, None)
                    mint = None
                async def finish():
                    if mint is not None:
                        try:
                            await asyncio.shield(mint)
                        except HTTPException as exc:
                            if exc.detail == CLEANUP:
                                raise
                        except Exception:
                            pass
                        finally:
                            self.mints.pop(request_id, None)
                    if self.pending() != request_id:
                        return {'status': 'ended'}
                    return await self.cleanup(request_id)
                task = asyncio.create_task(finish())
                task.add_done_callback(self.consume)
                self.ends[request_id] = task
        try:
            return await asyncio.shield(task)
        finally:
            if task.done() and self.ends.get(request_id) is task:
                self.ends.pop(request_id, None)

    async def shutdown(self):
        pending = self.pending()
        if pending:
            try:
                await self.end(pending)
            except HTTPException:
                pass  # Durable ownership intentionally survives shutdown.
        self.close()


def create_app(state_dir=None, *, static_dir=None, transport=None):
    runtime = WebState(state_dir or default_state_dir(), transport=transport)
    assets = Path(static_dir) if static_dir is not None else ROOT / 'ui' / 'web'

    @asynccontextmanager
    async def lifespan(app):
        try:
            yield
        finally:
            await runtime.shutdown()

    app = FastAPI(lifespan=lifespan, docs_url=None, redoc_url=None, openapi_url=None)
    app.state.web = runtime
    app.add_middleware(LocalOnly, transport_url=runtime.transport[0])

    @app.exception_handler(RequestValidationError)
    async def invalid(request, exc):
        # Pydantic's default errors include submitted input (possibly a key).
        return JSONResponse({'detail': 'Invalid request. Check the selected languages, voice ID and request identity.'}, status_code=422)

    @app.exception_handler(Exception)
    async def unexpected(request, exc):
        return JSONResponse({'detail': 'Local translator could not complete this request. Check server configuration and retry cleanup if needed.'}, status_code=500, headers=SECURITY_HEADERS)

    @app.get('/api/web/status')
    async def status():
        return runtime.status()

    @app.post('/api/web/config')
    async def configure(value: Configuration):
        return await runtime.configure(value)

    @app.post('/api/web/session')
    async def start(value: Start, request: Request):
        return await runtime.start(value, request)

    @app.post('/api/web/session/{request_id}/end')
    async def end(request_id: str, value: Empty):
        try:
            request_uuid(request_id)
        except (ValueError, AttributeError):
            raise HTTPException(422, 'Invalid request identity.') from None
        return await runtime.end(request_id)

    async def asset(name):
        path = assets / name
        if not path.is_file():
            raise HTTPException(503, 'Browser assets are unavailable. Run npm run build:web before starting the server.')
        return FileResponse(path)

    @app.get('/')
    async def index():
        return await asset('index.html')

    @app.get('/styles.css')
    async def styles():
        return await asset('styles.css')

    @app.get('/dist/app.js')
    async def javascript():
        return await asset('dist/app.js')

    @app.get('/playback-worklet.js')
    async def playback_worklet():
        return await asset('playback-worklet.js')

    return app
