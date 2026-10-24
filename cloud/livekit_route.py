"""Self-hosted interpreter transport over our own LiveKit deployment (issue #8).

Same admission, lease, budget and response contract as the hosted Fish route in
fish.py: the device cannot tell the routes apart, and every lease settles through
the same fish_sessions ledger. The minted token joins one LiveKit room; the
voice-worker service (voice-worker/) discovers the room and interprets inside it.
"""
import asyncio, base64, hashlib, hmac, json, os, time
from datetime import datetime, timezone

import httpx
from fastapi import HTTPException

from .fish import (MAX_DURATION, MAX_JOIN_SECONDS, NOT_CREATED, UNAVAILABLE,
                   duration_cap, request_lease)

ROOM_PREFIX = 'manz-'


def config():
    url, key, secret = (os.getenv('LIVEKIT_URL', ''), os.getenv('LIVEKIT_API_KEY', ''),
                        os.getenv('LIVEKIT_API_SECRET', ''))
    if not url.startswith(('wss://', 'ws://')) or not key or not secret:
        raise HTTPException(503, UNAVAILABLE)
    http = 'https://' + url[6:] if url.startswith('wss://') else 'http://' + url[5:]
    return url, http.rstrip('/'), key, secret


def token(key: str, secret: str, identity: str, room: str, ttl: int,
          metadata: str = '', name: str = '', admin: bool = False) -> str:
    """Hand-rolled HS256 LiveKit access token; no extra service dependency."""
    now = int(time.time())
    grants = {'roomJoin': True, 'room': room, 'canPublish': True, 'canSubscribe': True}
    if admin:
        grants['roomAdmin'] = True
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
                return response.status_code == 200
    except (httpx.HTTPError, ValueError, TimeoutError):
        return False


async def mint(store, dev, value):
    """Route-compatible twin of fish.mint: same guards, lease and response shape."""
    if value.guest_language == value.staff_language:
        raise HTTPException(422, 'Choose two different interpreter languages')
    try:
        url, http_base, key, secret = config()
    except HTTPException as exc:
        # A pre-lease refusal proves no session was created; say so like fish.mint.
        exc.headers = {**(exc.headers or {}), **NOT_CREATED}
        raise
    try:
        ident = store.reserve_fish(dev, MAX_DURATION, duration_cap('FISH_HOTEL_DAILY_SECONDS', 1800),
                                   duration_cap('FISH_ACCOUNT_DAILY_SECONDS', 3600), 60, MAX_JOIN_SECONDS,
                                   reservation=request_lease(dev, value.request_id) if value.request_id else None)
    except PermissionError:
        raise HTTPException(401, 'Unauthorized device') from None
    except ValueError:
        raise HTTPException(409, 'Interpreter session already outstanding') from None
    except OverflowError:
        raise HTTPException(429, 'Interpreter daily duration budget exhausted') from None
    room = room_name(ident)
    expires = datetime.fromtimestamp(time.time() + MAX_JOIN_SECONDS, timezone.utc)
    metadata = json.dumps({'guest_language': value.guest_language,
                           'staff_language': value.staff_language}, separators=(',', ':'))
    try:
        store.run('UPDATE fish_sessions SET session_id=? WHERE id=?', (room, ident))
        ticket = token(key, secret, 'device-' + ident[:24], room, MAX_JOIN_SECONDS,
                       metadata=metadata, name='reception')
        activated = store.run("""UPDATE fish_sessions SET status='live' WHERE id=? AND status='creating'
            AND creation_deadline>? AND expires>? AND EXISTS(SELECT 1 FROM credentials c
            WHERE c.id=fish_sessions.device AND c.hotel=fish_sessions.hotel AND c.role='device'
            AND c.hash=fish_sessions.credential_hash)""", (ident, time.time(), time.time()))
        if not activated.rowcount:
            raise HTTPException(401, 'Interpreter session expired or device revoked')
        return {'session_id': room, 'expires_at': expires.strftime('%Y-%m-%dT%H:%M:%S.%f')[:-3] + 'Z',
                'max_duration_seconds': MAX_DURATION, 'transport': 'livekit',
                'livekit_url': url, 'token': ticket}
    except HTTPException as exc:
        store.run("UPDATE fish_sessions SET status='ended' WHERE id=? AND session_id IS NOT NULL", (ident,))
        exc.headers = {**(exc.headers or {}), **NOT_CREATED}
        raise


async def terminate(store, lease) -> bool:
    """Delete the room; retain the blocking lease if the server cannot confirm."""
    if lease['status'] in {'ended', 'expired'}:
        return True
    store.run("UPDATE fish_sessions SET status='ending' WHERE id=? AND status NOT IN ('ended','expired')",
              (lease['id'],))
    try:
        _, http_base, key, secret = config()
    except HTTPException:
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
