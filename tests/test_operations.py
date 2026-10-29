import asyncio, json, time
from concurrent.futures import ThreadPoolExecutor
from threading import Event
import pytest
from fastapi.testclient import TestClient
from cloud.db import Store
from cloud.models import Hotel
from cloud.gateway import Gateway
import cloud.app as server
from cloud.hotel_answers import ContentApproval, approve_content, source_hash

TAXI={'destination':'Airport','pickup_time':'08:30 tomorrow','guest_name':'Synthetic Guest','room_number':'204'}
MESSAGE={'original_request':'Please ask reception about a later departure.\nKeep this exact wording.',
         'guest_name':'Synthetic Guest','room_number':'204'}

@pytest.fixture
def operations(tmp_path,monkeypatch):
    store=Store(str(tmp_path/'isolated.db'))
    monkeypatch.setattr(server,'store',store)
    monkeypatch.setenv('ORANGE_ADMIN_TOKEN','x'*40)
    monkeypatch.delenv('GEMINI_API_KEY',raising=False)
    for hid in ['a','b']:
        store.save_hotel(Hotel(id=hid,name='Synthetic '+hid,knowledge={'breakfast':'7–10 AM'},
                               checkin_instructions='Ask the reception desk for arrival assistance.').model_dump())
    dev=store.issue('a','device'); other=store.issue('a','device'); foreign=store.issue('b','device')
    headers=lambda d:{'Authorization':'Bearer '+d['token']}
    client=TestClient(server.app)
    start={'session_id':'operation-fixture','language':'en','staff_language':'es','mode':'reception'}
    assert client.post('/api/device/operations/start',headers=headers(dev),json=start).status_code==200
    return store,client,dev,other,foreign,headers,start


def action(client,headers,action,args,call='call-1',sid='operation-fixture'):
    return client.post('/api/device/operations/'+sid+'/action',headers=headers,
                       json={'action':action,'call_id':call,'arguments':args})


def confirm(client,headers,ident,accepted=True,sid='operation-fixture'):
    return client.post('/api/device/operations/'+sid+'/confirm',headers=headers,
                       json={'confirmation_id':ident,'accepted':accepted})


def approve_rendering(store, kind='knowledge', key='breakfast', language='en',
                      title='Breakfast', body='7–10 AM', hotel_id='a'):
    hotel=store.hotel(hotel_id)
    source=hotel['knowledge'][key] if kind=='knowledge' else hotel['checkin_instructions']
    return approve_content(store,hotel_id,ContentApproval(
        kind=kind,content_key=key,language=language,source_hash=source_hash(kind,key,source),
        title=title,body=body),authorize=lambda:None)

def test_operation_auth_tenant_and_user_selector_denials(operations):
    store,client,dev,other,foreign,headers,start=operations
    for d in [other,foreign]:
        assert action(client,headers(d),'question',{'query':'breakfast'}).status_code==403
        assert action(client,headers(d),'checkin',{}).status_code==403
        assert confirm(client,headers(d),'anything').status_code==403
        assert client.post('/api/device/operations/operation-fixture/end',headers=headers(d)).status_code==403
        assert client.post('/api/device/operations/start',headers=headers(d),json=start).status_code==403
    assert action(client,{},'operator',{}).status_code==401
    assert action(client,headers(dev),'question',{'query':'breakfast','hotel_id':'b'}).status_code==422
    assert client.post('/api/device/operations/start',headers=headers(dev),json={**start,'hotel_id':'b'}).status_code==422
    assert client.post('/api/device/operations/start',headers=headers(dev),json={**start,'mode':'translation'}).status_code==422


def test_missing_fields_and_approved_knowledge_only(operations):
    store,client,dev,_,_,headers,_=operations; hdr=headers(dev)
    expected={'taxi':set(TAXI),'question':{'query'},'message':set(MESSAGE)}
    for name,fields in expected.items():
        response=action(client,hdr,name,{}).json()
        assert response['status']=='needs_arguments'
        assert {f['name'] for f in response['fields']}==fields
        assert all(f['required'] for f in response['fields'] if name != 'message' or f['name']=='original_request')
        assert all(f['max_length'] > 0 for f in response['fields'])
        if name == 'message':
            assert not any(f['required'] for f in response['fields'] if f['name'] != 'original_request')
    missing=action(client,hdr,'question',{'query':'breakfast'},'unapproved').json()
    assert missing['result']['matches']=={}
    assert missing['result']['help_action']=='operator'
    assert not store.rows('SELECT * FROM approved_content')
    approve_rendering(store)
    found=action(client,hdr,'question',{'query':'breakfast'},'question').json()
    assert found['result']['matches']=={'Breakfast':'7–10 AM'} and '7–10 AM' in found['text']
    assert found['result']['fact_refs'][0]['content_key']=='breakfast'
    assert action(client,hdr,'question',{'query':'breakfast'},'unapproved').json()['result']['matches']=={}
    punctuated=action(client,hdr,'question',{'query':'breakfast?'},'punctuation').json()
    assert punctuated['result']['matches']==found['result']['matches']
    unknown=action(client,hdr,'question',{'query':'secret rooftop access code'},'unknown').json()
    assert unknown['result']['matches']=={}
    assert not store.rows('SELECT * FROM items')


def test_incomplete_form_returns_all_fields_and_model_limits(operations):
    _,client,dev,_,_,headers,_=operations
    response=action(client,headers(dev),'taxi',{'destination':'Airport'}).json()
    fields={field['name']:field for field in response['fields']}
    assert set(fields)==set(TAXI)
    assert fields['destination']['max_length']==300
    assert fields['pickup_time']['max_length']==100
    assert fields['room_number']['max_length']==20
    note=action(client,headers(dev),'message',{}).json()
    assert {field['name']:field['max_length'] for field in note['fields']}=={
        'original_request':4000,'guest_name':100,'room_number':20}


@pytest.mark.parametrize('connector',['unconfigured','rest','edge'])
def test_arrival_guidance_requires_no_identity_or_reservation_integration(operations,monkeypatch,connector):
    store,client,dev,_,_,headers,_=operations; hdr=headers(dev)
    hotel=store.hotel('a'); hotel['connector']=connector; store.save_hotel(hotel)
    def forbidden(*_,**__):
        raise AssertionError('Arrival guidance must not access reservation integrations')
    monkeypatch.setattr(server,'edge_execute',forbidden)
    import cloud.gateway as gateway
    monkeypatch.setattr(gateway,'connector',forbidden)
    approve_rendering(store,'arrival','',title='Arrival',body=hotel['checkin_instructions'])
    result=action(client,hdr,'checkin',{},'guide').json()
    assert result['status']=='ok'
    assert result['result']['instructions']=='Arrival\n'+hotel['checkin_instructions']
    assert result['result']['guidance_only'] is True and result['result']['checkin_completed'] is False
    assert result['result']['fact_refs'][0]['kind']=='arrival'
    assert 'Arrival\n'+hotel['checkin_instructions'] in result['text']
    assert action(client,hdr,'checkin',{},'retry').json()==result
    assert action(client,hdr,'checkin',{'reference':'UNNECESSARY','surname':'Guest'},'identity').status_code==422
    assert not store.rows('SELECT * FROM calls')
    assert not store.rows('SELECT * FROM items')


@pytest.mark.parametrize('instructions',['',' \n ','Existing instructions are not an approval.'])
def test_arrival_guidance_without_approved_policy_is_truthfully_unavailable(operations,instructions):
    store,client,dev,_,_,headers,_=operations
    hotel=store.hotel('a'); hotel['checkin_instructions']=instructions; store.save_hotel(hotel)
    result=action(client,headers(dev),'checkin',{},'no-policy').json()
    assert result['status']=='unavailable'
    assert result['result']['instructions']==''
    assert result['result']['guidance_only'] is True and result['result']['checkin_completed'] is False
    assert not store.rows('SELECT * FROM calls')
    assert not store.rows('SELECT * FROM items')


def test_confirmation_exact_binding_cancel_and_durable_receipt(operations):
    store,client,dev,_,_,headers,start=operations; hdr=headers(dev)
    draft=action(client,hdr,'taxi',TAXI).json()
    assert draft['status']=='confirmation_required' and not store.rows('SELECT * FROM items')
    assert action(client,hdr,'taxi',{**TAXI,'destination':'Different'},'call-1').status_code==409
    assert action(client,hdr,'taxi',TAXI,'new-id').json()==draft
    assert action(client,hdr,'taxi',{**TAXI,'destination':'Different'},'new-id').status_code==409
    assert confirm(client,hdr,'invented').status_code==404
    assert confirm(client,hdr,draft['confirmation_id'],'true').status_code==422
    cancelled=confirm(client,hdr,draft['confirmation_id'],False).json()
    assert cancelled['status']=='cancelled'
    assert confirm(client,hdr,draft['confirmation_id'],True).json()==cancelled
    assert not store.rows('SELECT * FROM items')
    next_draft=action(client,hdr,'taxi',{**TAXI,'pickup_time':'09:00'},'next').json()
    receipt=confirm(client,hdr,next_draft['confirmation_id']).json()
    assert receipt['status']=='queued' and receipt['result']['booked'] is False
    assert confirm(client,hdr,next_draft['confirmation_id']).json()==receipt
    assert action(client,hdr,'taxi',{**TAXI,'pickup_time':'09:00'},'third-id').json()==receipt
    reopened=Store(store.db.execute('PRAGMA database_list').fetchone()[2])
    assert len(reopened.rows("SELECT * FROM items WHERE hotel='a' AND kind='taxi'"))==1
    assert json.loads(reopened.rows("SELECT result FROM operation_actions WHERE call_id='next'")[0]['result'])==receipt


@pytest.mark.parametrize('operation,args,success,kind',[
    ('taxi',TAXI,'queued','taxi'),('message',MESSAGE,'saved','note')])
def test_concurrent_confirmations_and_new_call_ids_do_not_double_write(operations,operation,args,success,kind):
    store,client,dev,_,_,headers,_=operations; hdr=headers(dev)
    with ThreadPoolExecutor(max_workers=4) as pool:
        drafts=list(pool.map(lambda i:action(client,hdr,operation,args,'call-'+str(i)).json(),range(4)))
    assert len({draft['confirmation_id'] for draft in drafts})==1
    with ThreadPoolExecutor(max_workers=4) as pool:
        results=list(pool.map(lambda _:confirm(client,hdr,drafts[0]['confirmation_id']).json(),range(4)))
    assert all(r['status'] in {success,'pending'} for r in results)
    assert len(store.rows("SELECT * FROM items WHERE kind=?",(kind,)))==1
    assert confirm(client,hdr,drafts[0]['confirmation_id']).json()['status']==success


def test_pending_note_retries_and_cancel_resolve_one_delayed_write(operations,monkeypatch):
    from cloud.operations import Operations
    store,client,dev,_,_,headers,_=operations; hdr=headers(dev)
    draft=action(client,hdr,'message',MESSAGE).json()
    entered,release=Event(),Event()
    original_execute=Operations.execute

    async def delayed_execute(self,*args):
        entered.set()
        assert await asyncio.to_thread(release.wait,5), 'Test did not release the delayed write'
        return await original_execute(self,*args)

    monkeypatch.setattr(Operations,'execute',delayed_execute)
    with ThreadPoolExecutor(max_workers=1) as pool:
        pending=pool.submit(confirm,client,hdr,draft['confirmation_id'])
        try:
            assert entered.wait(5)
            assert confirm(client,hdr,draft['confirmation_id']).json()['status']=='pending'
            assert confirm(client,hdr,draft['confirmation_id'],False).json()['status']=='pending'
            assert action(client,hdr,'message',MESSAGE,'retry-during-write').json()['status']=='pending'
            assert not store.rows('SELECT * FROM items')
        finally:
            release.set()
        receipt=pending.result(timeout=5).json()
    assert receipt['status']=='saved'
    assert confirm(client,hdr,draft['confirmation_id']).json()==receipt
    assert confirm(client,hdr,draft['confirmation_id'],False).json()==receipt
    assert len(store.rows("SELECT * FROM items WHERE kind='note'"))==1


@pytest.mark.parametrize('operation,args',[('taxi',TAXI),('message',MESSAGE)])
def test_ended_expired_revoked_and_replaced_sessions_deny_pending_writes(operations,operation,args):
    store,client,dev,_,_,headers,start=operations; hdr=headers(dev)
    draft=action(client,hdr,operation,args).json()
    assert client.post('/api/device/operations/operation-fixture/end',headers=hdr).json()['status']=='ended'
    assert confirm(client,hdr,draft['confirmation_id']).status_code==403
    assert action(client,hdr,'operator',{}).status_code==403
    assert client.post('/api/device/operations/start',headers=hdr,json=start).status_code==403
    second={**start,'session_id':'second'}
    assert client.post('/api/device/operations/start',headers=hdr,json=second).status_code==200
    store.run('UPDATE operation_sessions SET expires=? WHERE id=?',(time.time()-1,'second'))
    assert action(client,hdr,'operator',{},sid='second').status_code==403
    third={**start,'session_id':'third'}
    assert client.post('/api/device/operations/start',headers=hdr,json=third).status_code==200
    assert client.post('/api/device/operations/start',headers=hdr,json={**start,'session_id':'fourth'}).status_code==200
    assert action(client,hdr,'operator',{},sid='third').status_code==403
    store.run('DELETE FROM credentials WHERE id=?',(dev['id'],))
    assert action(client,hdr,'operator',{},sid='fourth').status_code==401
    assert not store.rows('SELECT * FROM items')


def test_operator_and_taxi_dialer_receipts_never_claim_answered_or_booked(operations):
    store,client,dev,_,_,headers,_=operations; hdr=headers(dev)
    hotel=store.hotel('a');hotel.update(operator_primary='+34910000000',operator_secondary='+34910000001',taxi_number='+34910000002',taxi_mode='dialer');store.save_hotel(hotel)
    operator=action(client,hdr,'operator',{}).json()
    assert operator['status']=='dialer_required' and operator['result']['connected'] is False
    assert operator['result']['number']==hotel['operator_primary'] and operator['result']['fallback']==hotel['operator_secondary']
    assert action(client,hdr,'operator',{},'different-call').json()==operator
    draft=action(client,hdr,'taxi',TAXI,'taxi').json()
    taxi=confirm(client,hdr,draft['confirmation_id']).json()
    assert taxi['status']=='dialer_required' and taxi['result']['booked'] is False
    assert taxi['result']['number']==hotel['taxi_number']
    assert len(store.rows('SELECT * FROM items'))==2


def test_gateway_concurrent_write_dedup_and_authorization(operations):
    store,*_=operations
    async def scenario():
        entered=asyncio.Event(); resume=asyncio.Event()
        async def approve(*_): entered.set(); await resume.wait(); return True
        async def offline(*_): return {'status':'unavailable'}
        first=Gateway(store,'a','gateway-fixture',offline,approve)
        second=Gateway(store,'a','gateway-fixture',offline,approve)
        task=asyncio.create_task(first.execute('call_taxi',TAXI,'one'))
        await entered.wait()
        duplicate=await second.execute('call_taxi',TAXI,'another-id')
        assert duplicate['status']=='pending'
        resume.set(); receipt=await task
        assert await second.execute('call_taxi',TAXI,'another-id')==receipt
        assert (await second.execute('call_taxi',{**TAXI,'destination':'Different'},'another-id'))['status']=='denied'
        denied=Gateway(store,'a','revoked',offline,approve,authorized=lambda:False)
        assert (await denied.execute('call_taxi',TAXI,'denied'))['status']=='denied'
    asyncio.run(scenario())
    assert len(store.rows("SELECT * FROM items WHERE kind='taxi'"))==1


@pytest.mark.parametrize('operation,args',[('taxi',TAXI),('message',MESSAGE)])
def test_confirmation_cannot_cross_sessions_and_retention_prunes_drafts(operations,operation,args):
    store,client,dev,other,_,headers,start=operations
    draft=action(client,headers(dev),operation,args).json()
    assert client.post('/api/device/operations/start',headers=headers(other),json={**start,'session_id':'other-session'}).status_code==200
    assert confirm(client,headers(other),draft['confirmation_id'],sid='other-session').status_code==404
    store.run('UPDATE operation_sessions SET expires=? WHERE id=?',(time.time()-8*86400,'operation-fixture'))
    assert client.post('/api/device/operations/start',headers=headers(dev),json={**start,'session_id':'fresh'}).status_code==200
    assert not store.rows("SELECT * FROM operation_actions WHERE session='operation-fixture'")
    assert not store.rows("SELECT * FROM operation_call_ids WHERE session='operation-fixture'")
    assert confirm(client,headers(dev),draft['confirmation_id']).status_code==403
    assert not store.rows('SELECT * FROM items')


def test_unsafe_fallback_is_not_offered_as_dialer_number(operations):
    store,client,dev,_,_,headers,_=operations
    hotel=store.hotel('a')
    hotel.update(operator_primary='+34910000000',operator_secondary='',emergency_contact='tel:*123#')
    store.save_hotel(hotel)
    result=action(client,headers(dev),'operator',{}).json()
    assert result['status']=='dialer_required' and result['result']['fallback']==''


def test_message_exact_confirmation_cancel_retry_and_durable_truthful_receipt(operations):
    store,client,dev,_,_,headers,_=operations; hdr=headers(dev)
    draft=action(client,hdr,'message',MESSAGE).json()
    assert draft['status']=='confirmation_required'
    assert MESSAGE['original_request'] in draft['summary']
    assert not store.rows('SELECT * FROM items')
    assert action(client,hdr,'message',{**MESSAGE,'original_request':'Changed'},'call-1').status_code==409
    assert action(client,hdr,'message',MESSAGE,'retry').json()==draft
    assert confirm(client,hdr,draft['confirmation_id'],'true').status_code==422
    cancelled=confirm(client,hdr,draft['confirmation_id'],False).json()
    assert cancelled['status']=='cancelled'
    assert confirm(client,hdr,draft['confirmation_id']).json()==cancelled
    assert action(client,hdr,'message',MESSAGE,'after-cancel').json()==cancelled
    assert not store.rows('SELECT * FROM items')
    revised={**MESSAGE,'original_request':MESSAGE['original_request']+'\nA separately reviewed addition.'}
    next_draft=action(client,hdr,'message',revised,'next').json()
    assert revised['original_request'] in next_draft['summary']
    receipt=confirm(client,hdr,next_draft['confirmation_id']).json()
    assert receipt['status']=='saved'
    # An Android response can be lost after the durable write. A later Cancel
    # must return that original receipt, not claim it cancelled a saved note.
    assert confirm(client,hdr,next_draft['confirmation_id'],False).json()==receipt
    assert receipt['result']['destination']=='staff inbox'
    assert receipt['result']['staff_acknowledged'] is False
    assert receipt['result']['pms_delivered'] is False
    assert confirm(client,hdr,next_draft['confirmation_id']).json()==receipt
    assert action(client,hdr,'message',revised,'new-retry').json()==receipt
    reopened=Store(store.db.execute('PRAGMA database_list').fetchone()[2])
    item=reopened.rows('SELECT * FROM items')[0]
    assert item['id']==receipt['result']['item_id'] and item['hotel']=='a' and item['kind']=='note'
    payload=json.loads(item['payload'])
    assert payload['original_request']==revised['original_request']
    assert payload['guest_name']==MESSAGE['guest_name'] and payload['room_number']==MESSAGE['room_number']
    assert json.loads(reopened.rows("SELECT result FROM operation_actions WHERE call_id='next'")[0]['result'])==receipt


def test_message_argument_boundaries_preserve_original_and_do_not_accept_model_approval(operations):
    store,client,dev,_,_,headers,_=operations; hdr=headers(dev)
    for args in [{},{'original_request':''},{'original_request':' \n '}]:
        missing=action(client,hdr,'message',args).json()
        assert missing['status']=='needs_arguments'
        assert [field['name'] for field in missing['fields'] if field['required']]==['original_request']
    for args in [{'original_request':'x'*4001},
                 {**MESSAGE,'guest_name':'x'*101},{**MESSAGE,'room_number':'x'*21},
                 {**MESSAGE,'confirmed':True},{**MESSAGE,'summary':'Invented account'},
                 {**MESSAGE,'hotel_id':'b'}]:
        assert action(client,hdr,'message',args).status_code==422
    exact=' \n'+('ñ'*3995)+'\n  '
    assert len(exact)==4000
    draft=action(client,hdr,'message',{'original_request':exact}).json()
    assert exact in draft['summary']
    assert action(client,hdr,'message',{'original_request':exact,'guest_name':'','room_number':''},'retry').json()==draft
    assert not store.rows('SELECT * FROM items')
    result=confirm(client,hdr,draft['confirmation_id']).json()
    assert result['status']=='saved'
    payload=json.loads(store.rows('SELECT payload FROM items')[0]['payload'])
    assert payload['original_request']==exact and len(payload['summary'])<=1000


@pytest.mark.parametrize('invalidate',['end','expire','revoke'])
def test_message_confirmation_cannot_cross_authorization_write_race(operations,monkeypatch,invalidate):
    store,client,dev,_,_,headers,_=operations; hdr=headers(dev)
    draft=action(client,hdr,'message',MESSAGE).json()
    original_item=store.item
    another_process=Store(store.db.execute('PRAGMA database_list').fetchone()[2])
    def fenced_item(hid,sid,kind,payload,require_session=False):
        if invalidate=='end':
            another_process.run("UPDATE sessions SET status='ended' WHERE id=?",(sid,))
        elif invalidate=='expire':
            another_process.run('UPDATE operation_sessions SET expires=0 WHERE id=?',(sid,))
        else:
            another_process.run('DELETE FROM credentials WHERE id=?',(dev['id'],))
        return original_item(hid,sid,kind,payload,require_session=require_session)
    monkeypatch.setattr(store,'item',fenced_item)
    response=confirm(client,hdr,draft['confirmation_id']).json()
    assert response['status']=='denied'
    assert not store.rows('SELECT * FROM items')


def test_message_confirmation_and_receipts_are_tenant_and_session_isolated(operations):
    store,client,dev,other,foreign,headers,start=operations
    draft=action(client,headers(dev),'message',MESSAGE).json()
    for device in [other,foreign]:
        assert confirm(client,headers(device),draft['confirmation_id']).status_code==403
        sid='isolated-'+device['id']
        assert client.post('/api/device/operations/start',headers=headers(device),
                           json={**start,'session_id':sid}).status_code==200
        assert confirm(client,headers(device),draft['confirmation_id'],sid=sid).status_code==404
    assert not store.rows('SELECT * FROM items')
    receipt=confirm(client,headers(dev),draft['confirmation_id']).json()
    assert receipt['status']=='saved'
    assert len(store.rows("SELECT * FROM items WHERE hotel='a'"))==1
    assert not store.rows("SELECT * FROM items WHERE hotel='b'")


def test_spanish_operation_journey_is_pinned_and_preserves_authoritative_values(operations):
    store,client,dev,_,_,headers,start=operations
    hdr=headers(dev); sid='spanish-journey'
    spanish={**start,'session_id':sid,'language':'es','staff_language':'ar'}
    assert client.post('/api/device/operations/start',headers=hdr,json=spanish).status_code==200
    fields=action(client,hdr,'message',{},sid=sid).json()
    assert fields['status']=='needs_arguments'
    labels={field['name']:field['label'] for field in fields['fields']}
    assert 'mensaje' in labels['original_request'].casefold()
    assert 'nombre' in labels['guest_name'].casefold()
    assert 'habitaci' in labels['room_number'].casefold()
    assert any(word in fields['text'].casefold() for word in ('complet','datos','detalles'))
    original='  Keep English unchanged: Alex <204>.\n العربية كما هي  '
    values={'original_request':original,'guest_name':'Alex العربية','room_number':'204-ب'}
    draft=action(client,hdr,'message',values,'spanish-draft',sid).json()
    assert draft['status']=='confirmation_required'
    assert all(value in draft['summary'] for value in values.values())
    assert 'mensaje' in draft['summary'].casefold() and 'confirm' in draft['text'].casefold()
    conflict=client.post('/api/device/operations/start',headers=hdr,json={**spanish,'language':'en'})
    assert conflict.status_code==409, 'A live operation cannot silently change its pinned locale'
    assert action(client,hdr,'message',values,'same-draft',sid).json()==draft
    cancelled=confirm(client,hdr,draft['confirmation_id'],False,sid).json()
    assert cancelled['status']=='cancelled' and 'cancel' in cancelled['text'].casefold()
    assert not store.rows('SELECT * FROM items')
    revised={**values,'original_request':original+'\nAdditional exact request.'}
    next_draft=action(client,hdr,'message',revised,'spanish-next',sid).json()
    saved=confirm(client,hdr,next_draft['confirmation_id'],True,sid).json()
    assert saved['status']=='saved'
    assert 'mensaje' in saved['text'].casefold()
    assert saved['result']['staff_acknowledged'] is False and saved['result']['pms_delivered'] is False
    item=store.rows('SELECT * FROM items')[0]
    payload=json.loads(item['payload'])
    assert item['id']==saved['result']['item_id']
    assert all(payload[key]==value for key,value in revised.items())
    # Locale/config changes cannot rewrite an immutable execution receipt.
    hotel=store.hotel('a'); hotel['default_language']='ar'; store.save_hotel(hotel)
    assert confirm(client,hdr,next_draft['confirmation_id'],False,sid).json()==saved
    assert action(client,hdr,'message',revised,'spanish-replay',sid).json()==saved


@pytest.mark.parametrize('language',['es','ar'])
def test_localized_pending_receipt_uses_the_owning_session(operations,monkeypatch,language):
    from cloud.operations import Operations
    store,client,dev,_,_,headers,start=operations
    hdr=headers(dev); sid='pending-'+language
    assert client.post('/api/device/operations/start',headers=hdr,
                       json={**start,'session_id':sid,'language':language}).status_code==200
    draft=action(client,hdr,'message',MESSAGE,sid=sid).json()
    entered,release=Event(),Event()
    execute=Operations.execute

    async def delayed(self,*args):
        entered.set()
        assert await asyncio.to_thread(release.wait,5)
        return await execute(self,*args)

    monkeypatch.setattr(Operations,'execute',delayed)
    with ThreadPoolExecutor(max_workers=1) as pool:
        writing=pool.submit(confirm,client,hdr,draft['confirmation_id'],True,sid)
        try:
            assert entered.wait(5)
            pending=confirm(client,hdr,draft['confirmation_id'],False,sid).json()
            assert pending['status']=='pending'
            if language=='es':
                assert any(word in pending['text'].casefold() for word in ('proces','curso','tramita'))
            else:
                assert any('\u0600'<=character<='\u06ff' for character in pending['text'])
            assert action(client,hdr,'message',MESSAGE,'pending-replay',sid).json()==pending
        finally:
            release.set()
        saved=writing.result(timeout=5).json()
    assert saved['status']=='saved' and len(store.rows('SELECT * FROM items'))==1
    assert confirm(client,hdr,draft['confirmation_id'],True,sid).json()==saved


@pytest.mark.parametrize('language,query,title,body',[
    ('es','breakfast?','Desayuno','De 7 a 10 h.'),
    ('ar','الإفطار؟','الإفطار','من الساعة ٧ إلى ١٠ صباحًا.')])
def test_approved_answers_use_exact_rendering_and_pinned_guest_locale(
        operations,monkeypatch,language,query,title,body):
    import cloud.hotel_answers as answers
    store,client,dev,_,_,headers,start=operations; hdr=headers(dev); sid='facts-'+language
    hotel=store.hotel('a')
    if language=='ar':
        hotel['knowledge']['الإفطار']=hotel['knowledge'].pop('breakfast')
        store.save_hotel(hotel)
    key='الإفطار' if language=='ar' else 'breakfast'
    approve_rendering(store,key=key,language=language,title=title,body=body)
    async def forbidden(*_,**__):
        raise AssertionError('Normalized exact answers must not contact Gemini')
    monkeypatch.setattr(answers,'_gemini_json',forbidden)
    assert client.post('/api/device/operations/start',headers=hdr,
                       json={**start,'session_id':sid,'language':language}).status_code==200
    result=action(client,hdr,'question',{'query':query},sid=sid).json()
    assert result['result']['matches']=={title:body}
    assert result['result']['fact_refs'][0]['language']==language
    assert title in result['text'] and body in result['text']
    assert not store.rows('SELECT * FROM items')


@pytest.mark.parametrize('operation',['question','checkin'])
@pytest.mark.parametrize('legacy',[False,True])
def test_information_replay_withdraws_stale_rows_without_touching_durable_receipts(
        operations,operation,legacy):
    store,client,dev,_,_,headers,_=operations; hdr=headers(dev)
    approve_rendering(store)
    approve_rendering(store,'arrival','',title='Arrival',body='Ask reception.')
    taxi=action(client,hdr,'taxi',TAXI,'taxi').json()
    taxi=confirm(client,hdr,taxi['confirmation_id']).json()
    message=action(client,hdr,'message',MESSAGE,'message').json()
    message=confirm(client,hdr,message['confirmation_id']).json()
    durable=store.rows("SELECT * FROM operation_actions WHERE action IN ('taxi','message') ORDER BY call_id")
    args={'query':'breakfast'} if operation=='question' else {}
    information=action(client,hdr,operation,args,'information').json()
    assert information['status']=='ok' and information['result']['fact_refs']
    if legacy:
        information['result'].pop('fact_refs')
        store.run("UPDATE operation_actions SET result=? WHERE call_id='information'",(json.dumps(information),))
    else:
        hotel=store.hotel('a')
        if operation=='question': hotel['knowledge']['breakfast']='08:00–11:00'
        else: hotel['checkin_instructions']='Changed arrival instructions.'
        store.save_hotel(hotel)
    replay=action(client,hdr,operation,args,'information').json()
    assert replay['status']=='stale' and replay['result']['help_action']=='operator'
    assert replay['result'].get('matches',{})=={}
    assert replay['result'].get('instructions','')==''
    assert '7–10 AM' not in replay['text'] and 'Ask reception.' not in replay['text']
    assert json.loads(store.rows("SELECT result FROM operation_actions WHERE call_id='information'")[0]['result'])==replay
    assert action(client,hdr,'taxi',TAXI,'taxi-replay').json()==taxi
    assert action(client,hdr,'message',MESSAGE,'message-replay').json()==message
    assert store.rows("SELECT * FROM operation_actions WHERE action IN ('taxi','message') ORDER BY call_id")==durable


@pytest.mark.parametrize('stage',['provider','settlement'])
@pytest.mark.parametrize('invalidate',['source','rendering','unselected','new_conflict','end','revoke'])
def test_question_wait_and_settlement_recheck_real_ledger_rows(operations,monkeypatch,stage,invalidate):
    import cloud.hotel_answers as answers
    store,client,dev,_,_,headers,_=operations; hdr=headers(dev)
    approve_rendering(store)
    if invalidate=='unselected':
        hotel=store.hotel('a'); hotel['knowledge']['checkout']='Checkout is before 11:00.'; store.save_hotel(hotel)
        approve_rendering(store,key='checkout',title='Checkout',body='Before 11:00.')
    entered,release=Event(),Event()
    async def selection(*_,**__):
        if stage=='provider':
            entered.set()
            assert await asyncio.to_thread(release.wait,5)
        return {'fact_ids':['0']}
    monkeypatch.setattr(answers,'_gemini_json',selection)
    execute=Gateway.execute
    async def delayed(self,*args,**kwargs):
        result=await execute(self,*args,**kwargs)
        if stage=='settlement':
            assert result['matches']=={'Breakfast':'7–10 AM'}
            entered.set()
            assert await asyncio.to_thread(release.wait,5)
        return result
    monkeypatch.setattr(Gateway,'execute',delayed)
    with ThreadPoolExecutor(max_workers=1) as pool:
        pending=pool.submit(action,client,hdr,'question',{'query':'When is breakfast served?'},'race')
        try:
            assert entered.wait(5)
            assert store.rows("SELECT result FROM operation_actions WHERE call_id='race'")==[{'result':None}]
            if invalidate=='source':
                hotel=store.hotel('a'); hotel['knowledge']['breakfast']='08:00–11:00'; store.save_hotel(hotel)
            elif invalidate=='rendering':
                approve_rendering(store,body='Updated approved hours.')
            elif invalidate=='unselected':
                hotel=store.hotel('a'); hotel['knowledge']['checkout']='Checkout conflicts with breakfast policy.'; store.save_hotel(hotel)
            elif invalidate=='new_conflict':
                hotel=store.hotel('a'); hotel['knowledge']['breakfast exception']='Breakfast service is suspended.'; store.save_hotel(hotel)
            elif invalidate=='end':
                assert client.post('/api/device/operations/operation-fixture/end',headers=hdr).status_code==200
            else:
                store.run('DELETE FROM credentials WHERE id=?',(dev['id'],))
        finally:
            release.set()
        response=pending.result(timeout=5).json()
    assert response['status'] in ('stale','unavailable','unknown','denied')
    assert response['result'].get('matches',{})=={} and '7–10 AM' not in response['text']
    saved=json.loads(store.rows("SELECT result FROM operation_actions WHERE call_id='race'")[0]['result'])
    assert saved==response
    if stage=='provider':
        call=json.loads(store.rows("SELECT result FROM calls WHERE tool='search_hotel_knowledge'")[0]['result'])
        assert call.get('matches',{})=={} and call['status']!='ok'
    replay=action(client,hdr,'question',{'query':'When is breakfast served?'},'race')
    if invalidate in ('end','revoke'):
        assert replay.status_code in (401,403)
    else:
        assert replay.json()['result'].get('matches',{})=={}
    assert not store.rows('SELECT * FROM items')


@pytest.mark.parametrize('invalidate',['source','rendering','end','revoke'])
def test_arrival_is_revalidated_in_its_own_settlement_transaction(operations,monkeypatch,invalidate):
    import cloud.operations as operation_module
    store,client,dev,_,_,headers,_=operations; hdr=headers(dev)
    approve_rendering(store,'arrival','',title='Arrival',body='Approved arrival instructions.')
    guidance=operation_module.arrival_guidance
    def changed_after_lookup(*args,**kwargs):
        result=guidance(*args,**kwargs)
        assert result['instructions']=='Arrival\nApproved arrival instructions.'
        if invalidate=='source':
            hotel=store.hotel('a'); hotel['checkin_instructions']='Changed policy.'; store.save_hotel(hotel)
        elif invalidate=='rendering':
            approve_rendering(store,'arrival','',title='Arrival',body='Changed approved rendering.')
        elif invalidate=='end':
            store.run("UPDATE sessions SET status='ended' WHERE id='operation-fixture'")
        else:
            store.run('DELETE FROM credentials WHERE id=?',(dev['id'],))
        return result
    monkeypatch.setattr(operation_module,'arrival_guidance',changed_after_lookup)
    result=action(client,hdr,'checkin',{},'arrival-race').json()
    assert result['status']==('denied' if invalidate in ('end','revoke') else 'stale')
    assert result['result']['instructions']==''
    assert result['result']['help_action']=='operator'
    assert 'Approved arrival instructions.' not in result['text']
    assert json.loads(store.rows("SELECT result FROM operation_actions WHERE call_id='arrival-race'")[0]['result'])==result
    assert not store.rows('SELECT * FROM items')
