import asyncio, hashlib, json, re
from .models import TOOL_MODELS
from .connectors import connector, ConnectorUnavailable
from .hotel_answers import answer_hotel_question, arrival_guidance, current_answer

INFORMATION = {'search_hotel_knowledge','get_checkin_instructions'}

WRITES = {'leave_reception_note','call_taxi','notify_staff','create_guest_request'}

def phone_number(value):
    # Permit ordinary international or local emergency numbers, never URI/USSD.
    return value if isinstance(value,str) and re.fullmatch(r'\+?[0-9]{3,15}',value) else ''

class Gateway:
    def __init__(self, store, hid, sid, edge_execute, confirm=None, handoff=None, authorized=None, *, language='en'):
        self.store, self.hid, self.sid = store, hid, sid
        self.edge_execute, self.confirm, self.handoff = edge_execute, confirm, handoff
        self.authorized = authorized or (lambda: True)
        self.require_session = authorized is not None
        self.language = language
        self.verified = {}  # Booking access is scoped to this authenticated conversation.
    async def execute(self, name, arguments, call_id):
        if not self.authorized(): return {'status':'denied','reason':'Session ended or device revoked'}
        if not isinstance(call_id,str) or not 1 <= len(call_id) <= 160:
            return {'status':'invalid_arguments'}
        if name not in TOOL_MODELS: return {'status':'denied','reason':'Tool not allowed'}
        try: args = TOOL_MODELS[name].model_validate(arguments).model_dump()
        except Exception: return {'status':'invalid_arguments'}
        args_hash = hashlib.sha256(json.dumps(args, sort_keys=True).encode()).hexdigest()
        # Claim before awaiting confirmation or external IO. A new call ID must not
        # create a second write of the same exact operation in this session.
        with self.store.lock, self.store.db:
            db = self.store.db
            db.execute('BEGIN IMMEDIATE')
            if not self.authorized(): return {'status':'denied','reason':'Session ended or device revoked'}
            claimed = db.execute('INSERT OR IGNORE INTO calls(session,call_id,tool,args_hash) VALUES(?,?,?,?)',
                                 (self.sid,call_id,name,args_hash)).rowcount
            own = db.execute('SELECT * FROM calls WHERE session=? AND call_id=?',(self.sid,call_id)).fetchone()
            if own['tool'] != name or own['args_hash'] != args_hash:
                return {'status':'denied','reason':'Idempotency conflict'}
            owner = call_id
            if name in WRITES | {'connect_operator'}:
                db.execute('INSERT OR IGNORE INTO write_claims VALUES(?,?,?,?)',(self.sid,name,args_hash,call_id))
                owner = db.execute('SELECT call_id FROM write_claims WHERE session=? AND tool=? AND args_hash=?',
                                   (self.sid,name,args_hash)).fetchone()['call_id']
            if not claimed or owner != call_id:
                old = db.execute('SELECT result FROM calls WHERE session=? AND call_id=?',(self.sid,owner)).fetchone()
                if old and old['result']:
                    result = json.loads(old['result'])
                    if name in INFORMATION:
                        result = current_answer(self.store,self.hid,result,self.language,self.authorized)
                        db.execute('UPDATE calls SET result=? WHERE session=? AND call_id=?',
                                   (json.dumps(result),self.sid,owner))
                    elif name == 'get_hotel_info' and 'knowledge' in result:
                        result.pop('knowledge')
                        db.execute('UPDATE calls SET result=? WHERE session=? AND call_id=?',
                                   (json.dumps(result),self.sid,owner))
                    return result
                return {'status':'pending','reason':'Already executing; do not retry with a new ID'}
        executing = False
        try:
            if name in WRITES and (not self.confirm or not await self.confirm(name,args)):
                result = {'status':'cancelled','reason':'Guest confirmation not received'}
            elif not self.authorized():
                result = {'status':'denied','reason':'Session ended or device revoked'}
            else:
                executing = True
                result = await self._execute(name,args)
        except asyncio.CancelledError:
            if not executing:
                with self.store.lock, self.store.db:
                    db = self.store.db
                    db.execute('DELETE FROM write_claims WHERE session=? AND tool=? AND args_hash=? AND call_id=?',
                               (self.sid,name,args_hash,call_id))
                    # Remove duplicate pending rows too: Store's bootstrap would
                    # otherwise recreate the abandoned claim after a restart.
                    db.execute('DELETE FROM calls WHERE session=? AND tool=? AND args_hash=? AND result IS NULL',
                               (self.sid,name,args_hash))
            # Once execution starts, an external write may already have happened.
            # Keep its pending ledger/claim for reconciliation, never safe retry.
            raise
        except ConnectorUnavailable:
            result = {'status':'unavailable','reason':'Reservation system unavailable; ask the operator',
                      'operator':self.store.hotel(self.hid)['operator_primary']}
        except PermissionError:
            result = {'status':'denied','reason':'Session ended or device revoked'}
        except Exception:
            result = {'status':'failed','reason':'Action failed; no successful result confirmed'}
        with self.store.lock, self.store.db:
            db = self.store.db
            db.execute('BEGIN IMMEDIATE')
            if name in INFORMATION:
                result = current_answer(self.store,self.hid,result,self.language,self.authorized)
            elif not self.authorized():
                # Preserve durable write receipts, but never deliver one to a revoked session.
                db.execute('UPDATE calls SET result=? WHERE session=? AND call_id=?',
                           (json.dumps(result),self.sid,call_id))
                return {'status':'denied','reason':'Session ended or device revoked'}
            db.execute('UPDATE calls SET result=? WHERE session=? AND call_id=?',
                       (json.dumps(result),self.sid,call_id))
        self.store.event(self.hid,self.sid,name,result['status'])
        return result
    async def _execute(self, name, args):
        hotel = self.store.hotel(self.hid)
        hotel = {**hotel, **{key:phone_number(hotel.get(key,'')) for key in
                 ('operator_primary','operator_secondary','emergency_contact','taxi_number')}}
        if name == 'lookup_booking':
            booking = await connector(hotel,self.edge_execute).lookup(**args)
            if not self.authorized(): return {'status':'denied','reason':'Session ended or device revoked'}
            if not booking: return {'status':'not_found'}
            self.verified[booking['id']] = booking
            return {'status':'ok','booking_id':booking['id']}
        if name in {'get_guest','get_checkin_status','get_checkin_instructions'}:
            booking = self.verified.get(args['booking_id'])
            if not booking: return {'status':'denied','reason':'Verify reservation reference and surname first'}
            if name == 'get_guest': return {'status':'ok','guest_name':booking['guest_name'],'room_number':booking['room_number']}
            if name == 'get_checkin_status': return {'status':'ok','eligible':booking['eligible'],'booking_status':booking['status']}
            if not booking['eligible']: return {'status':'denied','reason':'Operator must review check-in eligibility'}
            return arrival_guidance(self.store,self.hid,self.language,self.authorized)
        if name == 'get_hotel_info':
            return {'status':'ok','name':hotel['name'],'emergency_contact':hotel['emergency_contact']}
        if name == 'get_hotel_services': return {'status':'ok','services':hotel['services']}
        if name == 'search_hotel_knowledge':
            return await answer_hotel_question(self.store,self.hid,args['query'],self.language,self.authorized)
        if name == 'call_taxi':
            ident = self.store.item(self.hid,self.sid,'taxi',args,require_session=self.require_session)
            if hotel['taxi_mode']=='dialer' and hotel['taxi_number'] and self.handoff:
                result = await self.handoff('taxi', hotel['taxi_number'], '')
                return {**result,'request_id':ident,'booked':False}
            if hotel['taxi_mode']=='dialer' and hotel['taxi_number']:
                return {'status':'dialer_required','number':hotel['taxi_number'],'request_id':ident,'booked':False}
            return {'status':'queued','request_id':ident,'booked':False,
                    'reason':'Request saved in the staff inbox. No taxi booking or call is confirmed.'}
        if name == 'connect_operator':
            ident = self.store.item(self.hid,self.sid,'escalation',args,require_session=self.require_session)
            if hotel['operator_primary'] and self.handoff:
                result = await self.handoff('operator',hotel['operator_primary'],hotel['operator_secondary'])
                return {**result,'escalation_id':ident,'connected':False,'fallback':hotel['operator_secondary'] or hotel['emergency_contact']}
            if hotel['operator_primary']:
                return {'status':'dialer_required','escalation_id':ident,'connected':False,
                        'number':hotel['operator_primary'],'fallback':hotel['operator_secondary'] or hotel['emergency_contact']}
            return {'status':'unavailable','escalation_id':ident,'connected':False,
                    'primary':hotel['operator_primary'],'fallback':hotel['operator_secondary'] or hotel['emergency_contact']}
        kind = 'note' if name == 'leave_reception_note' else 'request'
        ident = self.store.item(self.hid,self.sid,kind,args,require_session=self.require_session)
        # Durable cloud fallback is authoritative; PMS delivery isn't fabricated.
        return {'status':'saved','item_id':ident,'destination':'Orange reception inbox','pms_delivered':False}
