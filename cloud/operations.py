import hashlib, json, secrets, time
from typing import Literal
from fastapi import HTTPException
from pydantic import Field, StrictBool
from .models import Strict, BookingLookup, Taxi, Search, Operator
from .gateway import Gateway, phone_number

class Start(Strict):
    session_id: str = Field(pattern=r'^[A-Za-z0-9_-]{1,100}$')
    language: str = Field(max_length=35,pattern=r'^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*$')
    staff_language: str = Field(max_length=35,pattern=r'^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*$')
    mode: Literal['reception']

class Action(Strict):
    action: Literal['checkin','taxi','question','operator']
    call_id: str = Field(min_length=1,max_length=100)
    arguments: dict = Field(default_factory=dict)

class Confirmation(Strict):
    confirmation_id: str = Field(min_length=1,max_length=100)
    accepted: StrictBool

FIELDS = {
    'checkin':[('reference','Reservation reference'),('surname','Surname')],
    'taxi':[('destination','Destination'),('pickup_time','Pickup time'),('guest_name','Guest name'),('room_number','Room number')],
    'question':[('query','Your hotel question')], 'operator':[]}
MODELS = {'checkin':BookingLookup,'taxi':Taxi,'question':Search,'operator':Operator}


def receipt(result):
    status = result['status']
    if 'instructions' in result:
        text = result['instructions'] or 'No approved check-in instructions are configured. Ask the operator.'
        text += ' This is guidance only; check-in has not been completed.'
    elif 'matches' in result:
        text = '\n'.join(k + ': ' + v for k,v in result['matches'].items()) or 'No approved answer was found. Please ask the operator.'
    else:
        text = result.get('reason') or {
            'queued':'Request saved in the staff inbox. No taxi booking or call is confirmed.',
            'dialer_required':'Open the phone dialer to call. No answer or connection is confirmed.',
            'unavailable':'This service is unavailable. Please ask the operator or hotel staff.',
            'not_found':'Reservation not found with those details. Please ask the operator.',
            'cancelled':'Cancelled. Nothing was sent.',
            'denied':'This operation is not authorized.',
            'pending':'The operation is already being processed. No successful outcome is confirmed.',
            'failed':'No successful outcome was confirmed. Please ask the operator.',
        }.get(status,'Operation result available below.')
    return {'status':status,'text':text,'result':result}


class Operations:
    def __init__(self, store, edge_execute):
        self.store, self.edge_execute = store, edge_execute

    def expire(self):
        now = time.time()
        self.store.run("UPDATE sessions SET status='expired',ended=? WHERE status='active' AND id IN (SELECT id FROM operation_sessions WHERE expires<?)",(now,now))
        # Keep bounded replay tombstones, but never delete durable hotel inbox receipts.
        old = now - 7*86400
        self.store.run('DELETE FROM write_claims WHERE session IN (SELECT id FROM operation_sessions WHERE expires<?)',(old,))
        self.store.run('DELETE FROM calls WHERE session IN (SELECT id FROM operation_sessions WHERE expires<?)',(old,))
        self.store.run('DELETE FROM operation_sessions WHERE expires<?',(old,))

    def valid(self, dev, sid):
        self.expire()
        rows = self.store.rows('SELECT s.* FROM sessions s JOIN operation_sessions o ON s.id=o.id JOIN credentials c ON c.id=s.device WHERE s.id=? AND s.hotel=? AND s.device=? AND c.hotel=s.hotel AND c.role=\'device\' AND s.status=\'active\' AND o.expires>?',
                               (sid,dev['hotel'],dev['id'],time.time()))
        if not rows: raise HTTPException(403,'Operation session ended, expired, revoked or not owned by this device')
        return rows[0]

    def authorized(self, dev, sid):
        try: self.valid(dev,sid); return True
        except HTTPException: return False

    def start(self, dev, value):
        self.expire()
        with self.store.lock, self.store.db:
            db = self.store.db
            db.execute('BEGIN IMMEDIATE')
            now = time.time()
            if not db.execute("SELECT id FROM credentials WHERE id=? AND hotel=? AND role='device'",(dev['id'],dev['hotel'])).fetchone():
                raise HTTPException(401,'Unauthorized device')
            old = db.execute('SELECT * FROM sessions WHERE id=?',(value.session_id,)).fetchone()
            if old:
                meta = db.execute('SELECT * FROM operation_sessions WHERE id=?',(value.session_id,)).fetchone()
                if (old['hotel'] != dev['hotel'] or old['device'] != dev['id'] or old['status'] != 'active'
                    or not meta or meta['expires'] <= now):
                    raise HTTPException(403,'Operation session ended, expired or not owned by this device')
                if (meta['language'],meta['staff_language']) != (value.language,value.staff_language):
                    raise HTTPException(409,'Session metadata conflict')
            else:
                db.execute("UPDATE sessions SET status='ended',ended=? WHERE device=? AND status='active' AND id IN (SELECT id FROM operation_sessions)",(now,dev['id']))
                db.execute('INSERT INTO sessions VALUES(?,?,?,?,?,?,?)',
                           (value.session_id,dev['hotel'],dev['id'],now,None,'active',value.language))
                db.execute('INSERT INTO operation_sessions VALUES(?,?,?,?)',
                           (value.session_id,value.language,value.staff_language,now+1800))
        hotel = self.store.hotel(dev['hotel'])
        safe = {k:hotel[k] for k in ('name','default_language','supported_languages')}
        safe.update({k:phone_number(hotel[k]) for k in ('operator_primary','operator_secondary','emergency_contact')})
        return {'status':'ready','session_id':value.session_id,'hotel':safe}

    def end(self, dev, sid):
        self.valid(dev,sid)
        self.store.run("UPDATE sessions SET status='ended',ended=? WHERE id=?",(time.time(),sid))
        self.store.run("UPDATE operation_actions SET state='cancelled',result=? WHERE session=? AND state='confirmation'",
                       (json.dumps(receipt({'status':'cancelled'})),sid))
        return {'status':'ended','text':'Operation session closed.'}

    async def action(self, dev, sid, value):
        self.valid(dev,sid)
        missing = [{'name':name,'label':label,'required':True} for name,label in FIELDS[value.action]
                   if not isinstance(value.arguments.get(name),str) or not value.arguments[name].strip()]
        if missing: return {'status':'needs_arguments','text':'Please complete these details.','fields':missing}
        try: args = MODELS[value.action].model_validate(value.arguments).model_dump()
        except Exception: raise HTTPException(422,'Invalid operation arguments')
        serialized = json.dumps(args,sort_keys=True)
        fingerprint = hashlib.sha256(serialized.encode()).hexdigest()
        with self.store.lock:
            self.valid(dev,sid)
            self.store.run('INSERT OR IGNORE INTO operation_call_ids VALUES(?,?,?,?)',
                           (sid,value.call_id,value.action,fingerprint))
            identity = self.store.rows('SELECT * FROM operation_call_ids WHERE session=? AND call_id=?',(sid,value.call_id))[0]
            if identity['action'] != value.action or identity['fingerprint'] != fingerprint:
                raise HTTPException(409,'Idempotency conflict')
            confirmation = secrets.token_urlsafe(24) if value.action == 'taxi' else None
            state = 'confirmation' if confirmation else 'executing'
            claimed = self.store.run('INSERT OR IGNORE INTO operation_actions(session,call_id,action,arguments,fingerprint,confirmation_id,state) VALUES(?,?,?,?,?,?,?)',
                                     (sid,value.call_id,value.action,serialized,fingerprint,confirmation,state))
            if not claimed.rowcount:
                old = self.store.rows('SELECT * FROM operation_actions WHERE session=? AND action=? AND fingerprint=?',
                                      (sid,value.action,fingerprint))
                return self.saved(old[0])
        if confirmation:
            return self.saved(self.store.rows('SELECT * FROM operation_actions WHERE session=? AND call_id=?',(sid,value.call_id))[0])
        return await self.execute(dev,sid,value.call_id,value.action,args)

    def saved(self, row):
        if row['result']: return json.loads(row['result'])
        if row['state'] == 'confirmation':
            args = json.loads(row['arguments'])
            return {'status':'confirmation_required','confirmation_id':row['confirmation_id'],
                    'summary':'Taxi request: ' + args['destination'] + '; pickup ' + args['pickup_time'] +
                              '; guest ' + args['guest_name'] + '; room ' + args['room_number'],
                    'text':'Confirm this exact request before it is sent. A request is not a taxi booking.'}
        return receipt({'status':'pending'})

    async def confirm(self, dev, sid, value):
        with self.store.lock:
            self.valid(dev,sid)
            rows = self.store.rows('SELECT * FROM operation_actions WHERE session=? AND confirmation_id=?',(sid,value.confirmation_id))
            if not rows: raise HTTPException(404,'Confirmation not found for this session')
            row = rows[0]
            if row['state'] != 'confirmation': return self.saved(row)
            result = receipt({'status':'cancelled'}) if not value.accepted else None
            claimed = self.store.run("UPDATE operation_actions SET state=?,result=? WHERE session=? AND call_id=? AND state='confirmation'",
                                     ('executing' if value.accepted else 'cancelled',json.dumps(result) if result else None,sid,row['call_id']))
            if not claimed.rowcount:
                return self.saved(self.store.rows('SELECT * FROM operation_actions WHERE session=? AND call_id=?',(sid,row['call_id']))[0])
            if result: return result
        return await self.execute(dev,sid,row['call_id'],row['action'],json.loads(row['arguments']))

    async def execute(self, dev, sid, call_id, action, args):
        async def approved(*_): return True  # Only reached for a claimed, device-approved stored draft.
        gateway = Gateway(self.store,dev['hotel'],sid,self.edge_execute,approved,
                          authorized=lambda:self.authorized(dev,sid))
        # Hashed suffix IDs prevent a caller selecting another action's internal call IDs.
        prefix = hashlib.sha256(call_id.encode()).hexdigest()
        if action == 'checkin':
            result = await gateway.execute('lookup_booking',args,prefix+':lookup')
            if result['status'] == 'ok':
                result = await gateway.execute('get_checkin_instructions',{'booking_id':result['booking_id']},prefix+':guidance')
        else:
            tool = {'taxi':'call_taxi','question':'search_hotel_knowledge','operator':'connect_operator'}[action]
            result = await gateway.execute(tool,args,prefix)
        response = receipt(result)
        self.store.run("UPDATE operation_actions SET state='done',result=? WHERE session=? AND call_id=?",(json.dumps(response),sid,call_id))
        return response
