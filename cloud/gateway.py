import hashlib, json
from .models import TOOL_MODELS
from .connectors import connector, ConnectorUnavailable

WRITES = {'leave_reception_note','call_taxi','notify_staff','create_guest_request'}

class Gateway:
    def __init__(self, store, hid, sid, edge_execute, confirm=None, handoff=None):
        self.store, self.hid, self.sid = store, hid, sid
        self.edge_execute, self.confirm, self.handoff = edge_execute, confirm, handoff
        self.verified = {}  # Booking access is scoped to this authenticated conversation.
    async def execute(self, name, arguments, call_id):
        if name not in TOOL_MODELS: return {'status':'denied','reason':'Tool not allowed'}
        try: args = TOOL_MODELS[name].model_validate(arguments).model_dump()
        except Exception: return {'status':'invalid_arguments'}
        args_hash = hashlib.sha256(json.dumps(args, sort_keys=True).encode()).hexdigest()
        old = self.store.rows('SELECT * FROM calls WHERE session=? AND call_id=?', (self.sid,call_id))
        if old:
            if old[0]['tool'] != name or old[0]['args_hash'] != args_hash:
                return {'status':'denied','reason':'Idempotency conflict'}
            return json.loads(old[0]['result']) if old[0]['result'] else {'status':'pending','reason':'Already executing; do not retry with a new ID'}
        self.store.run('INSERT INTO calls(session,call_id,tool,args_hash) VALUES(?,?,?,?)', (self.sid,call_id,name,args_hash))
        try:
            if name in WRITES and (not self.confirm or not await self.confirm(name,args)):
                result = {'status':'cancelled','reason':'Guest confirmation not received'}
            else: result = await self._execute(name,args)
        except ConnectorUnavailable:
            result = {'status':'unavailable','reason':'Reservation system unavailable; ask the operator',
                      'operator':self.store.hotel(self.hid)['operator_primary']}
        except Exception:
            result = {'status':'failed','reason':'Action failed; no successful result confirmed'}
        self.store.run('UPDATE calls SET result=? WHERE session=? AND call_id=?',(json.dumps(result),self.sid,call_id))
        self.store.event(self.hid,self.sid,name,result['status'])
        return result
    async def _execute(self, name, args):
        hotel = self.store.hotel(self.hid)
        if name == 'lookup_booking':
            booking = await connector(hotel,self.edge_execute).lookup(**args)
            if not booking: return {'status':'not_found'}
            self.verified[booking['id']] = booking
            return {'status':'ok','booking_id':booking['id'], 'demo':bool(booking.get('demo'))}
        if name in {'get_guest','get_checkin_status','get_checkin_instructions'}:
            booking = self.verified.get(args['booking_id'])
            if not booking: return {'status':'denied','reason':'Verify reservation reference and surname first'}
            if name == 'get_guest': return {'status':'ok','guest_name':booking['guest_name'],'room_number':booking['room_number']}
            if name == 'get_checkin_status': return {'status':'ok','eligible':booking['eligible'],'booking_status':booking['status']}
            if not booking['eligible']: return {'status':'denied','reason':'Operator must review check-in eligibility'}
            return {'status':'ok','instructions':hotel['checkin_instructions'], 'guidance_only':True,
                    'checkin_completed':False}
        if name == 'get_hotel_info':
            return {'status':'ok','name':hotel['name'],'knowledge':hotel['knowledge'],'emergency_contact':hotel['emergency_contact']}
        if name == 'get_hotel_services': return {'status':'ok','services':hotel['services']}
        if name == 'search_hotel_knowledge':
            terms = args['query'].casefold().split()
            return {'status':'ok','matches':{k:v for k,v in hotel['knowledge'].items() if any(t in (k+' '+v).casefold() for t in terms)}}
        if name == 'call_taxi':
            ident = self.store.item(self.hid,self.sid,'taxi',args)
            if hotel['taxi_mode']=='dialer' and hotel['taxi_number'] and self.handoff:
                result = await self.handoff('taxi', hotel['taxi_number'], '')
                return {**result,'request_id':ident,'booked':False}
            return {'status':'queued','request_id':ident,'booked':False,
                    'reason':'Request saved in the staff inbox. No taxi booking or call is confirmed.'}
        if name == 'connect_operator':
            ident = self.store.item(self.hid,self.sid,'escalation',args)
            if hotel['operator_primary'] and self.handoff:
                result = await self.handoff('operator',hotel['operator_primary'],hotel['operator_secondary'])
                return {**result,'escalation_id':ident,'connected':False,'fallback':hotel['operator_secondary'] or hotel['emergency_contact']}
            return {'status':'unavailable','escalation_id':ident,'connected':False,
                    'primary':hotel['operator_primary'],'fallback':hotel['operator_secondary'] or hotel['emergency_contact']}
        kind = 'note' if name == 'leave_reception_note' else 'request'
        ident = self.store.item(self.hid,self.sid,kind,args)
        # Durable cloud fallback is authoritative; PMS delivery isn't fabricated.
        return {'status':'saved','item_id':ident,'destination':'Orange reception inbox','pms_delivered':False}
