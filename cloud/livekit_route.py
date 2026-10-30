"""Application-controlled interpreter transport with durable room ownership.

New managed sessions require an explicitly configured interpreter worker. Historic
Fish leases continue to settle through their original route in fish.py.
"""
import asyncio, base64, hashlib, hmac, json, os, time
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urlsplit

import httpx
from fastapi import HTTPException

from .fish import (MAX_DURATION, NOT_CREATED, UNAVAILABLE,
                   duration_cap, request_lease, failure_headers)

ROOM_PREFIX = 'manz-'
# Limit replay of an unused device ticket. Connected LiveKit clients receive
# refreshed credentials for reconnect; this does not revoke those credentials.
JOIN_TOKEN_SECONDS = 120


def speech_languages():
    return json.loads((Path(__file__).resolve().parent.parent /
                       'shared/translation-languages.json').read_text(encoding='utf-8'))['languages']


def config(values=None):
    url, key, secret = values if values is not None else (
        os.getenv('LIVEKIT_URL', ''), os.getenv('LIVEKIT_API_KEY', ''),
        os.getenv('LIVEKIT_API_SECRET', ''))
    try:
        parsed = urlsplit(url)
        local = parsed.scheme == 'ws' and parsed.hostname == '127.0.0.1'
        if ((parsed.scheme != 'wss' and not local) or not parsed.hostname or
                parsed.username or parsed.password or parsed.query or parsed.fragment or
                parsed.path not in ('', '/') or not key or not secret or
                any(char.isspace() for char in url) or
                (parsed.port is not None and not 1 <= parsed.port <= 65535)):
            raise ValueError('Invalid transport configuration')
    except ValueError:
        raise HTTPException(503, UNAVAILABLE, headers=failure_headers('configuration', 'unconfigured')) from None
    http = ('https://' if parsed.scheme == 'wss' else 'http://') + parsed.netloc
    return url, http.rstrip('/'), key, secret


def cleanup_authority(http_base: str, key: str, secret: str) -> str:
    """Opaque identity of the exact deployment and signing authority."""
    endpoint = str(httpx.URL(http_base)).rstrip('/')
    canonical = json.dumps([endpoint, key, secret], ensure_ascii=False, separators=(',', ':'))
    return hashlib.sha256(canonical.encode('utf-8')).hexdigest()


def token(key: str, secret: str, identity: str, room: str, ttl: int,
          metadata: str = '', name: str = '', admin: bool = False, issued_at: int | None = None) -> str:
    """Hand-rolled HS256 LiveKit access token; no extra service dependency."""
    now = int(time.time()) if issued_at is None else issued_at
    # LiveKit RoomService.DeleteRoom requires roomCreate, not roomAdmin.
    # The short-lived server-only deletion token needs no room-join privileges.
    grants = ({'roomCreate': True} if admin else
              {'roomJoin': True, 'room': room, 'canPublish': True, 'canSubscribe': True})
    claims = {'iss': key, 'sub': identity, 'iat': now, 'nbf': now - 10, 'exp': now + ttl,
              'video': grants}
    if metadata:
        claims['metadata'] = metadata
    if name:
        claims['name'] = name
    def part(value):
        raw = json.dumps(value, separators=(',', ':')).encode()
        return base64.urlsafe_b64encode(raw).rstrip(b'=')
    head = part({'alg': 'HS256', 'typ': 'JWT'})
    body = part(claims)
    signature = hmac.new(secret.encode(), head + b'.' + body, hashlib.sha256).digest()
    return (head + b'.' + body + b'.' + base64.urlsafe_b64encode(signature).rstrip(b'=')).decode()


def room_name(ident: str) -> str:
    return ROOM_PREFIX + ident[:40]


async def delete_room(http_base: str, key: str, secret: str, room: str) -> bool:
    admin = token(key, secret, 'manzanilla-backend', room, 60, admin=True)
    try:
        async with asyncio.timeout(10):
            async with httpx.AsyncClient(timeout=8) as client:
                response = await client.post(
                    http_base + '/twirp/livekit.RoomService/DeleteRoom',
                    headers={'Authorization': 'Bearer ' + admin,
                             'Content-Type': 'application/json'},
                    json={'room': room})
                if response.status_code == 200:
                    return True
                # LiveKit 1.13.10 reports an absent room as Twirp NotFound.
                # A proxy 404 or an unrelated error is not proof of deletion.
                if response.status_code == 404:
                    try:
                        return response.json().get('code') == 'not_found'
                    except (ValueError, AttributeError):
                        pass
                return False
    except (httpx.HTTPError, ValueError, TimeoutError):
        return False


async def mint(store, dev, value, *, transport=None, local=False):
    """Reserve one route-B lease; a token is not provider-access certification."""
    try:
        url, http_base, key, secret = config(transport)
        reservation = request_lease(dev, value.request_id) if value.request_id else None
        prior = store.rows('SELECT id FROM fish_sessions WHERE id=? AND device=? AND hotel=?',
                           (reservation, dev['id'], dev['hotel'])) if reservation else []
        if not prior:
            codes = {language['code'] for language in speech_languages()}
            if value.guest_language not in codes or value.staff_language not in codes:
                raise HTTPException(422, 'Unsupported speech language',
                                    headers=failure_headers('configuration', 'contract'))
            if not local and (not url.startswith('wss://') or
                    os.getenv('ALLOW_UNQUALIFIED_LIVEKIT') != '1' or
                    os.getenv('LIVEKIT_WORKER_ENABLED') != '1' or
                    not all(os.getenv(name) for name in ('FISH_API_KEY', 'FISH_VOICE_ID', 'GEMINI_API_KEY'))):
                raise HTTPException(503, UNAVAILABLE,
                                    headers=failure_headers('configuration', 'unconfigured'))
        try:
            ident = store.reserve_fish(dev, MAX_DURATION, duration_cap('FISH_HOTEL_DAILY_SECONDS', 1800),
                                       duration_cap('FISH_ACCOUNT_DAILY_SECONDS', 3600), 60, JOIN_TOKEN_SECONDS,
                                       reservation=reservation,
                                       route='livekit', session_id_for=room_name,
                                       cleanup_authority=cleanup_authority(http_base, key, secret))
        except PermissionError:
            raise HTTPException(401, 'Unauthorized device', headers=failure_headers('admission', 'unauthorized')) from None
        except ValueError:
            raise HTTPException(409, 'Interpreter session already outstanding', headers=failure_headers('admission', 'conflict')) from None
        except OverflowError:
            raise HTTPException(429, 'Interpreter daily duration budget exhausted', headers=failure_headers('admission', 'budget')) from None
    except HTTPException as exc:
        # Do not label a replay of an existing request "not created": it may
        # have already delivered a live token to the original caller.
        if not value.request_id or not store.rows('SELECT id FROM fish_sessions WHERE id=?',
                                                  (request_lease(dev, value.request_id),)):
            exc.headers = {**(exc.headers or {}), **NOT_CREATED}
        raise
    room = room_name(ident)
    issued_at = int(time.time())
    expires = datetime.fromtimestamp(issued_at + JOIN_TOKEN_SECONDS, timezone.utc)
    metadata = json.dumps({'guest_language': value.guest_language,
                           'staff_language': value.staff_language}, separators=(',', ':'))
    try:
        ticket = token(key, secret, 'device-' + ident[:24], room, JOIN_TOKEN_SECONDS,
                       metadata=metadata, name='reception', issued_at=issued_at)
        activated = store.run("""UPDATE fish_sessions SET status='live' WHERE id=? AND status='creating'
            AND creation_deadline>? AND expires>? AND EXISTS(SELECT 1 FROM credentials c
            WHERE c.id=fish_sessions.device AND c.hotel=fish_sessions.hotel AND c.role='device'
            AND c.hash=fish_sessions.credential_hash)""", (ident, time.time(), time.time()))
        if not activated.rowcount:
            raise HTTPException(401, 'Interpreter session expired or device revoked',
                                headers=failure_headers('admission', 'unauthorized'))
        return {'session_id': room, 'expires_at': expires.strftime('%Y-%m-%dT%H:%M:%S.%f')[:-3] + 'Z',
                'max_duration_seconds': MAX_DURATION, 'transport': 'livekit',
                'livekit_url': url, 'token': ticket}
    except HTTPException as exc:
        store.run("UPDATE fish_sessions SET status='ended' WHERE id=? AND status='creating'", (ident,))
        exc.headers = {**(exc.headers or {}), **NOT_CREATED}
        raise


async def terminate(store, lease, *, transport=None) -> bool:
    """Delete the room; retain the blocking lease if the server cannot confirm."""
    if lease['status'] in {'ended', 'expired'}:
        return True
    store.run("UPDATE fish_sessions SET status='ending' WHERE id=? AND status NOT IN ('ended','expired')",
              (lease['id'],))
    try:
        _, http_base, key, secret = config(transport)
    except HTTPException:
        return False
    authority = lease.get('cleanup_authority')
    if not authority or not hmac.compare_digest(authority, cleanup_authority(http_base, key, secret)):
        return False
    if not await delete_room(http_base, key, secret, lease['session_id']):
        return False
    store.run("UPDATE fish_sessions SET status='ended' WHERE id=?", (lease['id'],))
    return True


async def end(store, dev, session_id: str):
    """Same end contract as fish.end, settling through room deletion."""
    rows = store.rows('SELECT * FROM fish_sessions WHERE session_id=? AND device=? AND hotel=?',
                      (session_id, dev['id'], dev['hotel']))
    if not rows:
        raise HTTPException(404, 'Interpreter session not found')
    if not store.auth_device_lease(rows[0]['id']):
        raise HTTPException(401, 'Unauthorized device')
    if not await terminate(store, rows[0]):
        raise HTTPException(503, UNAVAILABLE)
    if not store.auth_device_lease(rows[0]['id']):
        raise HTTPException(401, 'Unauthorized device')
    return {'status': 'ended', 'session_id': session_id}
