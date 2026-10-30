import asyncio, hashlib, json, os, secrets, time, uuid
from datetime import datetime
from pathlib import Path
from zoneinfo import ZoneInfo
from fastapi import FastAPI, HTTPException, Header, WebSocket, WebSocketDisconnect, Depends, Request, Query
from fastapi.responses import FileResponse, JSONResponse, Response
from pydantic import BaseModel, Field, field_validator
from .db import Store, HotelVersionConflict
from .models import Hotel, BookingLookup
from .gateway import Gateway, phone_number
from .realtime import relay
from .operations import Operations, Start, Action, Confirmation
from .localization import catalogue
from .hotel_answers import ContentSelector, ContentApproval, draft_content, approve_content, content_review, content_availability
from .livekit_route import mint as livekit_mint
from .fish import TranslationSession, end_lease, request_lease, revoke_sessions, NOT_CREATED
from .models import Strict
from typing import Literal

store = Store(os.getenv('ORANGE_DB','data/orange.db'))
app = FastAPI(title='Orange Night Reception', version='0.1.0')
tickets = {}
edge_connections = {}
edge_pending = {}
active_devices = set()
device_connections = {}

# New managed sessions use the application-controlled LiveKit worker.
# Minting requires its Fish, voice and Gemini configuration. The legacy generic
# relay and hosted Fish Agent are not fallback routes.
GENERIC_RELAY_ENABLED = False
GENERIC_RELAY_BLOCK_REASON = 'This voice route is disabled: the approved interpreter route is the managed Fish recognition, Gemini translation and Fish synthesis session endpoint. Use staff-led reception actions or ask hotel staff; no fallback was started.'

def require_generic_relay():
    if not GENERIC_RELAY_ENABLED: raise HTTPException(503,GENERIC_RELAY_BLOCK_REASON)

def bearer(value): return value[7:] if value and value.startswith('Bearer ') else ''
def admin(authorization: str = Header(default='')):
    secret = os.getenv('ORANGE_ADMIN_TOKEN','')
    if len(secret)<32 or not secrets.compare_digest(bearer(authorization),secret): raise HTTPException(401,'Unauthorized')
    return True

def scoped(hid, authorization):
    secret = os.getenv('ORANGE_ADMIN_TOKEN','')
    if len(secret)>=32 and secrets.compare_digest(bearer(authorization),secret):
        return {'role':'admin','id':'admin','hotel':hid}
    staff = store.auth(bearer(authorization),'staff')
    if not staff or staff['hotel']!=hid: raise HTTPException(403,'Forbidden')
    return staff

@app.get('/health')
def health(): return {'status':'ok','version':'0.1.0','protocol':1}

@app.get('/admin')
def dashboard(): return FileResponse(Path(__file__).parent.parent/'admin/index.html')

@app.get('/admin/locales.js')
def dashboard_locales():
    return Response('window.MANZANILLA_UI = ' + json.dumps(catalogue(),ensure_ascii=True,separators=(',',':')) + ';',
                    media_type='application/javascript')

@app.get('/api/hotels', dependencies=[Depends(admin)])
def hotels(): return [json.loads(row['config']) for row in store.rows('SELECT config FROM hotels')]

@app.put('/api/hotels/{hid}', dependencies=[Depends(admin)])
def save_hotel(hid: str, value: Hotel):
    if value.id != hid: raise HTTPException(400,'Hotel ID mismatch')
    try: ZoneInfo(value.timezone)
    except Exception: raise HTTPException(400,'Invalid timezone')
    submitted = value.model_dump()
    if 'config_version' not in value.model_fields_set:
        submitted.pop('config_version')
    try: return store.save_hotel(submitted)
    except HotelVersionConflict as exc:
        return JSONResponse(status_code=409,content={'detail':'Hotel configuration conflict','config_version':exc.version})

@app.post('/api/hotels/{hid}/content/draft', dependencies=[Depends(admin)])
async def draft_hotel_content(hid: str, value: ContentSelector, authorization: str = Header(default='')):
    return await draft_content(store,hid,value,authorize=lambda:admin(authorization))

@app.post('/api/hotels/{hid}/content/approve', dependencies=[Depends(admin)])
def approve_hotel_content(hid: str, value: ContentApproval, authorization: str = Header(default='')):
    return approve_content(store,hid,value,authorize=lambda:admin(authorization))

class Credential(BaseModel):
    role: str = Field(pattern='^(device|edge|staff)$')
@app.post('/api/hotels/{hid}/credentials', dependencies=[Depends(admin)])
def issue(hid: str, value: Credential):
    try: return store.issue(hid,value.role)
    except KeyError: raise HTTPException(404,'Hotel not found')

@app.delete('/api/credentials/{ident}', dependencies=[Depends(admin)])
async def revoke(ident: str):
    store.run('DELETE FROM credentials WHERE id=?',(ident,))
    store.run("UPDATE sessions SET status='revoked',ended=? WHERE device=? AND status='active'",(time.time(),ident))
    closing = []
    for hid, connection in list(edge_connections.items()):
        if connection['credential']['id'] == ident:
            edge_connections.pop(hid,None)
            for pending in list(edge_pending.values()):
                if pending[0] is connection and not pending[1].done():
                    pending[1].set_result({'status':'unavailable'})
            closing.append(connection['ws'])
    ws = device_connections.get(ident)
    if ws: closing.append(ws)
    async def close(ws):
        try: await ws.close(code=4401)
        except (RuntimeError,WebSocketDisconnect): pass
    results = await asyncio.gather(revoke_sessions(store,ident),*(close(ws) for ws in closing))
    # Revocation of the credential succeeds independently of upstream room
    # cleanup. Preserve the retryable lease and tell staff when cleanup is pending.
    if not results[0]:
        return JSONResponse(status_code=202, content={'status':'revoked','voice_cleanup':'pending'})
    return {'status':'revoked'}

@app.get('/api/hotels/{hid}/overview')
def overview(hid: str, authorization: str = Header(default=''),
             item_status: Literal['all','unfinished','resolved'] = 'all',
             before: str = Query(default='',max_length=100), limit: int = Query(default=100,ge=1,le=500)):
    principal = scoped(hid,authorization)
    try: hotel = store.hotel(hid)
    except KeyError: raise HTTPException(404,'Hotel not found')
    try: content_admin = admin(authorization)
    except HTTPException: content_admin = False
    now = time.time()
    devices = store.rows('SELECT id,role,seen,version,config_version FROM credentials WHERE hotel=? AND role IN (\'device\',\'edge\')',(hid,))
    for row in devices: row['online'] = bool(row['seen'] and now-row['seen']<75)
    where, parameters = 'hotel=?', [hid]
    if item_status == 'unfinished':
        where += " AND status IN ('open','acknowledged')"
    elif item_status == 'resolved':
        where += " AND status='resolved'"
    if before:
        anchor = store.rows('SELECT created,id FROM items WHERE hotel=? AND id=?',(hid,before))
        if not anchor: raise HTTPException(404,'Inbox page not found')
        where += ' AND (created<? OR (created=? AND id<?))'
        parameters.extend([anchor[0]['created'],anchor[0]['created'],anchor[0]['id']])
    items = store.rows('SELECT * FROM items WHERE '+where+' ORDER BY created DESC,id DESC LIMIT ?',
                       (*parameters,limit+1))
    next_page = items[limit-1]['id'] if len(items)>limit else None
    items = items[:limit]
    for item in items: item['payload']=json.loads(item['payload'])
    return {'hotel':hotel,'devices':devices,'items':items,'items_next':next_page,'principal':principal,
            'sessions':store.rows('SELECT * FROM sessions WHERE hotel=? ORDER BY started DESC LIMIT 100',(hid,)),
            'content_review':content_review(store,hid),'content_admin':content_admin,
            'integrations':{'pms':hotel['connector'], 'crm':'not configured', 'edge':hid in edge_connections}}

class ItemTransition(Strict):
    status: Literal['acknowledged','resolved']
    expected_version: int = Field(ge=0,strict=True)
    responsible_name: str = Field(default='',max_length=80,strict=True)
    resolution_note: str = Field(default='',max_length=1000,strict=True)


def public_item(row):
    return {'item_id':row['id'],'status':row['status'],'version':row['state_version'],
            'created':row['created'],'acknowledged_at':row['acknowledged_at'],'resolved_at':row['resolved_at']}


@app.patch('/api/hotels/{hid}/items/{ident}')
async def transition_item(hid: str, ident: str, request: Request, authorization: str = Header(default='')):
    body = await request.body()
    value = None
    if body:
        try: value = ItemTransition.model_validate_json(body,strict=True)
        except ValueError: raise HTTPException(422,'Invalid item transition')
        if value.status == 'acknowledged' and not value.responsible_name.strip():
            raise HTTPException(422,'A responsible name is required')
        if value.status == 'resolved' and not value.resolution_note.strip():
            raise HTTPException(422,'A private resolution note is required')
    with store.lock, store.db:
        db = store.db
        db.execute('BEGIN IMMEDIATE')
        # Authenticate inside the write transaction: a concurrent revocation
        # must not authorize a transition based on a stale principal.
        principal = scoped(hid,authorization)
        row = db.execute('SELECT * FROM items WHERE id=? AND hotel=?',(ident,hid)).fetchone()
        if not row: raise HTTPException(404,'Item not found')
        if value is None and row['status'] == 'resolved':
            return public_item(row)
        status = value.status if value else 'resolved'
        version = value.expected_version if value else row['state_version']
        name = value.responsible_name if value else ''
        note = value.resolution_note if value else 'Resolved through legacy staff client'
        canonical = json.dumps([principal['role'],principal['id'],version,status,name,note],
                               ensure_ascii=False,separators=(',',':'))
        fingerprint = hashlib.sha256(canonical.encode('utf-8')).hexdigest()
        if fingerprint == row['last_transition_hash']:
            return public_item(row)
        if (row['acknowledged_by'] and principal['role'] != 'admin'
                and row['acknowledged_by'] != principal['id']):
            raise HTTPException(403,'Item belongs to another staff credential')
        allowed = (row['status'] == 'open' and status == 'acknowledged'
                   or row['status'] == 'acknowledged' and status == 'resolved'
                   or value is None and row['status'] == 'open')
        if version != row['state_version'] or not allowed:
            return JSONResponse(status_code=409,content={'detail':'Item state conflict','current':public_item(row)})
        now = time.time()
        if status == 'acknowledged':
            cur = db.execute("""UPDATE items SET status='acknowledged',acknowledged_at=?,acknowledged_by=?,
                responsible_name=?,last_transition_hash=?,state_version=state_version+1
                WHERE id=? AND hotel=? AND status=? AND state_version=?""",
                (now,principal['id'],name,fingerprint,ident,hid,row['status'],version))
        else:
            cur = db.execute("""UPDATE items SET status='resolved',resolved_at=?,resolution_note=?,
                last_transition_hash=?,state_version=state_version+1
                WHERE id=? AND hotel=? AND status=? AND state_version=?""",
                (now,note,fingerprint,ident,hid,row['status'],version))
        if not cur.rowcount:
            current = db.execute('SELECT * FROM items WHERE id=? AND hotel=?',(ident,hid)).fetchone()
            return JSONResponse(status_code=409,content={'detail':'Item state conflict','current':public_item(current)})
        db.execute('INSERT INTO audit VALUES(?,?,?,?,?,?)',
                   (str(uuid.uuid4()),hid,row['session'],now,'item_transition',status))
        return public_item(db.execute('SELECT * FROM items WHERE id=? AND hotel=?',(ident,hid)).fetchone())

@app.get('/api/hotels/{hid}/morning')
def morning(hid: str, start: float, end: float, authorization: str = Header(default='')):
    scoped(hid,authorization)
    if end<=start or end-start>7*86400: raise HTTPException(400,'Invalid time window')
    sessions = store.rows('SELECT * FROM sessions WHERE hotel=? AND started>=? AND started<?',(hid,start,end))
    items = store.rows('SELECT * FROM items WHERE hotel=? AND created>=? AND created<?',(hid,start,end))
    for row in items: row['payload']=json.loads(row['payload'])
    return {'hotel_id':hid,'timezone':store.hotel(hid)['timezone'],'start':start,'end':end,
            'session_count':len(sessions),'automatically_resolved':None,
            'note_count':sum(i['kind']=='note' for i in items),
            'escalation_count':sum(i['kind']=='escalation' for i in items),'items':items}

class Heartbeat(BaseModel):
    app_version: str = Field(max_length=40)
    config_version: int = Field(ge=0)
    protocol: int = 1
    language: str = ''
    capabilities_only: bool = False

    @field_validator('language')
    @classmethod
    def offered_language(cls, value):
        if value not in {row['code'] for row in catalogue()['languages']}:
            raise ValueError('Unsupported guest language')
        return value

def device_auth(auth):
    result=store.auth(bearer(auth),'device')
    if not result: raise HTTPException(401,'Unauthorized device')
    return result

class StaffCheck(BaseModel):
    staff_token: str = Field(min_length=1, max_length=200)

@app.post('/api/device/staff-check')
def staff_check(value: StaffCheck, authorization: str = Header(default='')):
    dev=device_auth(authorization)
    staff=store.auth(value.staff_token,'staff')
    if not staff or staff['hotel']!=dev['hotel']: raise HTTPException(403,'Staff authorization required')
    return {'authorized':True}

@app.post('/api/device/heartbeat')
def heartbeat(value: Heartbeat, authorization: str = Header(default='')):
    with store.lock:
        dev=device_auth(authorization)
        if value.protocol!=1: raise HTTPException(426,'Client update required')
        store.run('UPDATE credentials SET seen=?,version=?,config_version=? WHERE id=?',(time.time(),value.app_version,value.config_version,dev['id']))
        hotel=store.hotel(dev['hotel'])
        language=value.language if 'language' in value.model_fields_set else hotel['default_language']
        content=content_availability(store,dev['hotel'],language)
        staff=store.has_staff(dev['hotel'])
        services={
            'checkin':content['checkin'],
            'question':bool(content['question'] and os.getenv('GEMINI_API_KEY','').strip()),
            'taxi':bool((hotel['taxi_mode']=='staff_request' and staff) or
                        (hotel['taxi_mode']=='dialer' and phone_number(hotel.get('taxi_number','')))),
            'operator':bool(phone_number(hotel.get('operator_primary','')) or staff),
            'message':staff,
        }
        if value.capabilities_only:
            hotel={key:hotel[key] for key in ('id','name','timezone','default_language','supported_languages','config_version')}
        return {'hotel':hotel,'device_id':dev['id'],'protocol':1,'services':services}

class VoiceSession(Strict):
    mode: Literal['translation','reception']
    language: str = Field(max_length=35,pattern=r'^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*$')
    staff_language: str = Field(max_length=35,pattern=r'^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*$')
    session_id: str = Field(pattern=r'^[A-Za-z0-9_-]{1,100}$')
    generation: int = Field(default=0, ge=0)

@app.post('/api/device/operations/start')
def operations_start(value: Start, authorization: str = Header(default='')):
    return Operations(store,edge_execute).start(device_auth(authorization),value)

@app.post('/api/device/operations/{sid}/action')
async def operations_action(sid: str, value: Action, authorization: str = Header(default='')):
    return await Operations(store,edge_execute).action(device_auth(authorization),sid,value)

@app.post('/api/device/operations/{sid}/confirm')
async def operations_confirm(sid: str, value: Confirmation, authorization: str = Header(default='')):
    return await Operations(store,edge_execute).confirm(device_auth(authorization),sid,value)

@app.post('/api/device/operations/{sid}/end')
def operations_end(sid: str, authorization: str = Header(default='')):
    return Operations(store,edge_execute).end(device_auth(authorization),sid)

@app.get('/api/device/operations/{sid}/items/{ident}')
def operations_item_status(sid: str, ident: str, authorization: str = Header(default='')):
    with store.lock, store.db:
        store.db.execute('BEGIN')
        dev = device_auth(authorization)
        Operations(store,edge_execute).valid(dev,sid)
        row = store.db.execute('SELECT * FROM items WHERE id=? AND hotel=? AND session=?',
                               (ident,dev['hotel'],sid)).fetchone()
        if not row: raise HTTPException(404,'Item not found')
        return public_item(row)

@app.post('/api/device/fish-session')
async def fish_session(value: TranslationSession, authorization: str = Header(default='')):
    try: dev = device_auth(authorization)
    except HTTPException as exc:
        exc.headers = NOT_CREATED
        raise
    with store.lock:
        prior = store.rows('SELECT id FROM fish_sessions WHERE id=?',(request_lease(dev,value.request_id),))
        if not prior:
            supported = store.hotel(dev['hotel'])['supported_languages']
            if value.guest_language not in supported or value.staff_language not in supported:
                raise HTTPException(422,'Interpreter languages are not enabled by hotel staff',headers=NOT_CREATED)
    return await livekit_mint(store,dev,value)

def voice_cleanup_lease(authorization, *, session_id=None, request_id=None):
    # This historical capability authorizes only cleanup of its exact immutable
    # lease. It never restores the revoked credential's device authority.
    token = bearer(authorization)
    if not token: raise HTTPException(401,'Unauthorized device')
    credential_hash = hashlib.sha256(token.encode()).hexdigest()
    owners = store.rows('SELECT DISTINCT hotel,device FROM fish_sessions WHERE credential_hash=?',(credential_hash,))
    if not owners: raise HTTPException(401,'Unauthorized device')
    if session_id is not None:
        rows = store.rows('SELECT * FROM fish_sessions WHERE session_id=? AND credential_hash=?',
                          (session_id,credential_hash))
    else:
        rows = []
        for owner in owners:
            ident = request_lease({'hotel':owner['hotel'],'id':owner['device']},request_id)
            rows.extend(store.rows('SELECT * FROM fish_sessions WHERE id=? AND credential_hash=?',
                                   (ident,credential_hash)))
    if len(rows) != 1: raise HTTPException(404,'Interpreter session not found')
    return rows[0]


@app.post('/api/device/fish-session/{session_id}/end')
async def fish_session_end(session_id: str, authorization: str = Header(default='')):
    lease = voice_cleanup_lease(authorization,session_id=session_id)
    if not await end_lease(store,lease): raise HTTPException(503,'Interpreter cleanup is pending')
    return {'status':'ended','session_id':session_id}

@app.post('/api/device/fish-session-requests/{request_id}/end')
async def fish_session_request_end(request_id: str, authorization: str = Header(default='')):
    lease = voice_cleanup_lease(authorization,request_id=request_id)
    if not await end_lease(store,lease): raise HTTPException(503,'Interpreter cleanup is pending')
    return {'status':'ended'}

@app.post('/api/device/session')
def session(value: VoiceSession, authorization: str = Header(default='')):
    dev=device_auth(authorization)
    require_generic_relay()
    if value.mode == 'translation' and value.language.casefold() == value.staff_language.casefold():
        raise HTTPException(422,'Choose two different interpreter languages')
    if store.rows('SELECT id FROM sessions WHERE id=?',(value.session_id,)):
        raise HTTPException(409,'Session identifier already used')
    now=time.time()
    for key in list(tickets):
        if tickets[key]['expires']<now: del tickets[key]
    # One short-lived ticket per credential prevents unbounded issuance.
    for key in list(tickets):
        if tickets[key]['device']['id']==dev['id']: del tickets[key]
    token=secrets.token_urlsafe(32)
    tickets[token]={'device':dev,'expires':now+30,'metadata':value.model_dump()}
    return {'ticket':token,'expires_in':30,'protocol':1}

async def edge_execute(hid, capability, args):
    connection=edge_connections.get(hid)
    if not connection: return {'status':'unavailable'}
    ws=connection['ws']; cred=connection['credential']
    if store.auth(bearer(ws.headers.get('authorization')),'edge') != cred:
        if edge_connections.get(hid) is connection: edge_connections.pop(hid,None)
        try: await ws.close(code=4401)
        except (RuntimeError,WebSocketDisconnect): pass
        return {'status':'unavailable'}
    ident=str(uuid.uuid4())
    future=asyncio.get_running_loop().create_future()
    edge_pending[ident]=(connection,future)
    try:
        await ws.send_json({'type':'execute','id':ident,'capability':capability,'args':args})
        result=await asyncio.wait_for(future,10)
        if store.auth(bearer(ws.headers.get('authorization')),'edge') != cred:
            return {'status':'unavailable'}
        return result
    except (TimeoutError,RuntimeError,WebSocketDisconnect): return {'status':'unavailable'}
    finally: edge_pending.pop(ident,None)

@app.post('/api/hotels/{hid}/edge-check', dependencies=[Depends(admin)])
async def edge_check(hid: str, value: BookingLookup):
    try: store.hotel(hid)
    except KeyError: raise HTTPException(404,'Hotel not found')
    return await edge_execute(hid,'read_reservation',value.model_dump())

@app.websocket('/ws/edge')
async def edge(ws: WebSocket):
    cred=store.auth(bearer(ws.headers.get('authorization')),'edge')
    if not cred or cred['hotel'] in edge_connections:
        await ws.close(code=4401); return
    await ws.accept()
    if store.auth(bearer(ws.headers.get('authorization')),'edge') != cred or cred['hotel'] in edge_connections:
        await ws.close(code=4401); return
    hid=cred['hotel']; connection={'credential':cred,'ws':ws}; edge_connections[hid]=connection
    try:
        while True:
            msg=await asyncio.wait_for(ws.receive_json(),75)
            if store.auth(bearer(ws.headers.get('authorization')),'edge') != cred: break
            store.run('UPDATE credentials SET seen=? WHERE id=?',(time.time(),cred['id']))
            if msg.get('type')=='heartbeat': await ws.send_json({'type':'pong'})
            elif msg.get('type')=='result':
                pending=edge_pending.get(msg.get('id'))
                if pending and pending[0] is connection and not pending[1].done(): pending[1].set_result(msg.get('result',{'status':'failed'}))
    except (WebSocketDisconnect,TimeoutError,ValueError,RuntimeError): pass
    finally:
        if edge_connections.get(hid) is connection: edge_connections.pop(hid,None)
        for pending in list(edge_pending.values()):
            if pending[0] is connection and not pending[1].done():
                pending[1].set_result({'status':'unavailable'})

@app.websocket('/ws/device')
async def voice(ws: WebSocket):
    # Ticket delivered in header, never query parameters/access logs.
    entry=tickets.pop(ws.headers.get('x-orange-ticket',''),None)
    if not GENERIC_RELAY_ENABLED:
        await ws.close(code=4403,reason='This voice route is disabled; use the approved interpreter session endpoint')
        return
    if not entry or entry['expires']<time.time(): await ws.close(code=4401); return
    dev=entry['device']
    valid=store.rows('SELECT id FROM credentials WHERE id=? AND role=\'device\'',(dev['id'],))
    if not valid or dev['id'] in active_devices: await ws.close(code=4409); return
    active_devices.add(dev['id']); await ws.accept(); device_connections[dev['id']]=ws
    metadata=entry['metadata']; sid=metadata['session_id']; hid=dev['hotel']; pending={}
    scope={k:metadata[k] for k in ('session_id','generation')}
    claimed=store.run('INSERT OR IGNORE INTO sessions VALUES(?,?,?,?,?,?,?)',
                      (sid,hid,dev['id'],time.time(),None,'active',metadata['language']))
    if not claimed.rowcount:
        active_devices.discard(dev['id']); device_connections.pop(dev['id'],None)
        await ws.close(code=4409); return
    def authorized():
        return bool(store.rows("SELECT s.id FROM sessions s JOIN credentials c ON c.id=s.device WHERE s.id=? AND s.status='active' AND c.role='device' AND c.hotel=s.hotel",(sid,)))
    async def device_request(kind, payload, timeout):
        ident=str(uuid.uuid4()); future=asyncio.get_running_loop().create_future(); pending[ident]=future
        try:
            await ws.send_json({'type':kind,'id':ident,**payload,**scope})
            return await asyncio.wait_for(future,timeout)
        except TimeoutError: return {}
        finally: pending.pop(ident,None)
    async def confirm(name,args):
        result=await device_request('confirm',{'tool':name,'arguments':args},60)
        return result.get('accepted') is True
    async def handoff(kind,number,backup):
        result=await device_request('handoff',{'kind':kind,'number':number,'backup':backup},15)
        status=result.get('status')
        return {'status':status if status in {'dialer_opened','failed'} else 'unavailable','number':number}
    gateway=Gateway(store,hid,sid,edge_execute,confirm,handoff,authorized,language=metadata['language'])
    try:
        await relay(ws,store.hotel(hid),gateway,pending,metadata)
    except Exception:
        try: await ws.send_json({'type':'error','message':'Connection lost. Please retry or use the operator button.',**scope})
        except Exception: pass
    finally:
        store.run('UPDATE sessions SET ended=?,status=? WHERE id=?',(time.time(),'ended',sid))
        active_devices.discard(dev['id']); device_connections.pop(dev['id'],None)
        try: await ws.close()
        except RuntimeError: pass
