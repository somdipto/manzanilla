"""Controlled-session admission and end-only recovery of historical Fish leases."""
import asyncio, hashlib, json, os, time
from pathlib import Path
from typing import Literal
import httpx
from fastapi import HTTPException
from pydantic import Field, field_validator
from .models import Strict

# Historical hosted sessions must still be settled after the route-B cutover.
BASE = 'https://api.fish.audio/v1/agent'
MAX_DURATION = 600
SUPPORTED_LANGUAGES = frozenset(row['code'] for row in json.loads(
    (Path(__file__).resolve().parent.parent / 'shared/translation-languages.json').read_text(encoding='utf-8')
)['languages'])
UNAVAILABLE = 'Interpreter session unavailable. Ask staff to check provider configuration, entitlement and quota; no fallback was started.'
NOT_CREATED = {'X-Manzanilla-Session-Creation':'not-created'}
# This diagnostic is advisory only. It never establishes whether a session was
# created; the separate NOT_CREATED header and durable lease remain authoritative.
VOICE_FAILURE_HEADER = 'X-Manzanilla-Voice-Failure'
FAILURE_STAGES = frozenset({'configuration','admission','session_create','session_ticket'})
FAILURE_CATEGORIES = frozenset({'http','timeout','network','response','contract',
                                'unconfigured','budget','conflict','unauthorized'})

def failure_headers(stage, category, status=0):
    """Bounded public reference: no exception text, response body, URL or credential."""
    valid_status = type(status) is int and (400 <= status <= 599 if category == 'http' else status == 0)
    if stage not in FAILURE_STAGES or category not in FAILURE_CATEGORIES or not valid_status:
        raise ValueError('Invalid voice failure reference')
    return {VOICE_FAILURE_HEADER:f'{stage}:{category}:{status}'}

class TranslationSession(Strict):
    guest_language: str = Field(max_length=2)
    staff_language: str = Field(max_length=2)
    mode: Literal['translation']
    protocol: Literal['translation-v1']
    request_id: str = Field(min_length=1, max_length=200, pattern=r'^[A-Za-z0-9_:-]+$')

    @field_validator('guest_language','staff_language')
    @classmethod
    def supported_language(cls, value):
        if value not in SUPPORTED_LANGUAGES:
            raise ValueError('Unsupported interpreter language')
        return value

def provider_id(value):
    if not isinstance(value,str) or not 1 <= len(value) <= 200 or any(not(c.isascii() and (c.isalnum() or c in '-_')) for c in value):
        raise ValueError('Invalid provider identifier')
    return value

def duration_cap(name, default):
    try:
        value = int(os.getenv(name,str(default)))
        if value < 0: raise ValueError('Negative duration cap')
        return value
    except ValueError:
        raise HTTPException(503,'Interpreter duration budget is not configured correctly',
                            headers=failure_headers('configuration','contract')) from None

async def terminate(store, lease):
    """Retain the blocking lease if the provider cannot confirm termination."""
    if lease['status'] in {'ended','expired'}:
        return True
    store.run("UPDATE fish_sessions SET status='ending' WHERE id=? AND status NOT IN ('ended','expired')",(lease['id'],))
    key = os.getenv('FISH_API_KEY','')
    if not key: return False
    try:
        async with asyncio.timeout(15):
            async with httpx.AsyncClient(timeout=10,headers={'Authorization':'Bearer '+key}) as client:
                response = await client.post(BASE+'/sessions/'+provider_id(lease['session_id'])+'/end')
                response.raise_for_status()
                if response.status_code != 204: return False
        store.run("UPDATE fish_sessions SET status='ended' WHERE id=?",(lease['id'],))
        return True
    except (httpx.HTTPError,ValueError,TypeError,TimeoutError):
        return False

async def end(store, dev, session_id):
    rows = store.rows('SELECT * FROM fish_sessions WHERE session_id=? AND device=? AND hotel=?',
                      (session_id,dev['id'],dev['hotel']))
    if not rows: raise HTTPException(404,'Interpreter session not found')
    if not store.auth_device_lease(rows[0]['id']): raise HTTPException(401,'Unauthorized device')
    if not await terminate(store,rows[0]): raise HTTPException(503,UNAVAILABLE)
    current = store.auth_device_lease(rows[0]['id'])
    if not current: raise HTTPException(401,'Unauthorized device')
    return {'status':'ended','session_id':session_id}

def request_lease(dev, request_id):
    return hashlib.sha256(json.dumps([dev['hotel'],dev['id'],request_id],separators=(',',':')).encode()).hexdigest()

async def _recover_and_terminate(store, lease):
    """Clean up an exact persisted Fish lease without granting device authorization."""
    ident = lease['id']
    if not lease['session_id'] and lease['status'] not in {'ended','expired'}:
        # A lost creation response is not "not created". Fish documents exact end_user_id queries.
        key = os.getenv('FISH_API_KEY','')
        if not key: return False
        try:
            async with asyncio.timeout(15):
                async with httpx.AsyncClient(timeout=10,headers={'Authorization':'Bearer '+key}) as client:
                    response = await client.get(BASE+'/sessions',params={
                        'end_user_id':ident,'status':'pending,active,unknown,completed,failed','page_size':2})
                    response.raise_for_status()
                    data = response.json()
                    if not isinstance(data,dict): return False
                    sessions = data.get('sessions')
                    if (data.get('has_more') is not False or not isinstance(sessions,list) or len(sessions) != 1
                            or not isinstance(sessions[0],dict) or sessions[0].get('end_user_id') != ident):
                        return False
                    session_id = provider_id(sessions[0]['session_id'])
                    store.run('UPDATE fish_sessions SET session_id=? WHERE id=? AND session_id IS NULL',(session_id,ident))
                    if sessions[0].get('status') in {'completed','failed'}:
                        store.run("UPDATE fish_sessions SET status='ended' WHERE id=?",(ident,))
                    lease = store.rows('SELECT * FROM fish_sessions WHERE id=?',(ident,))[0]
        except (httpx.HTTPError,KeyError,ValueError,TypeError,TimeoutError):
            return False
    return await terminate(store,lease)


async def end_lease(store, lease):
    """End an exact persisted lease; the caller must authorize cleanup of this row."""
    ident = lease['id']
    # A backend crash skips mint's finally. Recovery must normalize this exact overdue
    # request itself; waiting for another mint deadlocks with native admission.
    store.run("UPDATE fish_sessions SET status='uncertain' WHERE id=? AND status='creating' AND creation_deadline<=?",
              (ident,time.time()))
    lease = store.rows('SELECT * FROM fish_sessions WHERE id=?',(ident,))[0]
    if lease['status'] == 'creating': return False
    if lease['route'] == 'livekit':
        # Persisted route, authority and room; never infer ownership from current settings.
        from .livekit_route import terminate as terminate_livekit
        return bool(lease['session_id']) and await terminate_livekit(store, lease)
    if lease['route'] == 'fish': return await _recover_and_terminate(store, lease)
    return False


async def end_request(store, dev, request_id):
    """Recover only this device's immutable creation request, never its newer conversation."""
    ident = request_lease(dev,request_id)
    rows = store.rows('SELECT * FROM fish_sessions WHERE id=? AND device=? AND hotel=?',
                      (ident,dev['id'],dev['hotel']))
    if not rows or not store.auth_device_lease(ident): raise HTTPException(503,UNAVAILABLE)
    if not await end_lease(store,rows[0]): raise HTTPException(503,UNAVAILABLE)
    if not store.auth_device_lease(ident): raise HTTPException(401,'Unauthorized device')
    return {'status':'ended'}


async def revoke_sessions(store, device):
    store.run("UPDATE fish_sessions SET status='revoked' WHERE device=? AND status IN ('creating','live','uncertain','ending')",(device,))
    rows = store.rows("SELECT * FROM fish_sessions WHERE device=? AND status='revoked'",(device,))
    if rows:
        return all(await asyncio.gather(*(end_lease(store,row) for row in rows)))
    return True

