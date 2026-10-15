import asyncio, json, time
from concurrent.futures import ThreadPoolExecutor
import pytest
from fastapi.testclient import TestClient
from cloud.db import Store
from cloud.models import Hotel
from cloud.gateway import Gateway
import cloud.app as server

TAXI={'destination':'Airport','pickup_time':'08:30 tomorrow','guest_name':'Synthetic Guest','room_number':'204'}

@pytest.fixture
def operations(tmp_path,monkeypatch):
    store=Store(str(tmp_path/'isolated.db'))
    monkeypatch.setattr(server,'store',store)
    monkeypatch.setenv('ORANGE_ADMIN_TOKEN','x'*40)
    for hid in ['a','b']:
        store.save_hotel(Hotel(id=hid,name='Synthetic '+hid,connector='demo',knowledge={'breakfast':'7–10 AM'},
                               checkin_instructions='Present ID to reception').model_dump())
    dev=store.issue('a','device'); other=store.issue('a','device'); foreign=store.issue('b','device')
    headers=lambda d:{'Authorization':'Bearer '+d['token']}
    client=TestClient(server.app)
    start={'session_id':'operation-fixture','language':'es','staff_language':'es','mode':'reception'}
    assert client.post('/api/device/operations/start',headers=headers(dev),json=start).status_code==200
    return store,client,dev,other,foreign,headers,start


def action(client,headers,action,args,call='call-1',sid='operation-fixture'):
    return client.post('/api/device/operations/'+sid+'/action',headers=headers,
                       json={'action':action,'call_id':call,'arguments':args})


def confirm(client,headers,ident,accepted=True,sid='operation-fixture'):
    return client.post('/api/device/operations/'+sid+'/confirm',headers=headers,
                       json={'confirmation_id':ident,'accepted':accepted})


def test_operation_auth_tenant_and_user_selector_denials(operations):
    store,client,dev,other,foreign,headers,start=operations
    for d in [other,foreign]:
        assert action(client,headers(d),'question',{'query':'breakfast'}).status_code==403
        assert confirm(client,headers(d),'anything').status_code==403
        assert client.post('/api/device/operations/operation-fixture/end',headers=headers(d)).status_code==403
        assert client.post('/api/device/operations/start',headers=headers(d),json=start).status_code==403
    assert action(client,{},'operator',{}).status_code==401
    assert action(client,headers(dev),'question',{'query':'breakfast','hotel_id':'b'}).status_code==422
    assert client.post('/api/device/operations/start',headers=headers(dev),json={**start,'hotel_id':'b'}).status_code==422
    assert client.post('/api/device/operations/start',headers=headers(dev),json={**start,'mode':'translation'}).status_code==422


def test_missing_fields_and_approved_knowledge_only(operations):
    store,client,dev,_,_,headers,_=operations; hdr=headers(dev)
    expected={'checkin':{'reference','surname'},'taxi':set(TAXI),'question':{'query'}}
    for name,fields in expected.items():
        response=action(client,hdr,name,{}).json()
        assert response['status']=='needs_arguments'
        assert {f['name'] for f in response['fields']}==fields
        assert all(f['required'] for f in response['fields'])
    found=action(client,hdr,'question',{'query':'breakfast'},'question').json()
    assert found['result']['matches']=={'breakfast':'7–10 AM'} and '7–10 AM' in found['text']
    unknown=action(client,hdr,'question',{'query':'secret rooftop access code'},'unknown').json()
    assert unknown['result']['matches']=={} and 'operator' in unknown['text']
    assert not store.rows('SELECT * FROM items')


def test_checkin_guidance_and_connector_unavailability(operations):
    store,client,dev,_,_,headers,_=operations; hdr=headers(dev)
    result=action(client,hdr,'checkin',{'reference':'DEMO-204','surname':'Smith'},'check').json()
    assert result['result']['guidance_only'] and result['result']['checkin_completed'] is False
    assert 'Present ID' in result['text']
    assert action(client,hdr,'checkin',{'reference':'DEMO-204','surname':'Wrong'},'wrong').json()['status']=='not_found'
    hotel=store.hotel('a'); hotel['connector']='unconfigured'; store.save_hotel(hotel)
    unavailable=action(client,hdr,'checkin',{'reference':'REAL-000','surname':'Guest'},'offline').json()
    assert unavailable['status']=='unavailable' and 'operator' in unavailable['text']


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


def test_concurrent_confirmations_and_new_call_ids_do_not_double_write(operations):
    store,client,dev,_,_,headers,_=operations; hdr=headers(dev)
    with ThreadPoolExecutor(max_workers=4) as pool:
        drafts=list(pool.map(lambda i:action(client,hdr,'taxi',TAXI,'call-'+str(i)).json(),range(4)))
    assert len({draft['confirmation_id'] for draft in drafts})==1
    with ThreadPoolExecutor(max_workers=4) as pool:
        results=list(pool.map(lambda _:confirm(client,hdr,drafts[0]['confirmation_id']).json(),range(4)))
    assert all(r['status'] in {'queued','pending'} for r in results)
    assert len(store.rows("SELECT * FROM items WHERE kind='taxi'"))==1
    assert confirm(client,hdr,drafts[0]['confirmation_id']).json()['result']['booked'] is False


def test_ended_expired_revoked_and_replaced_sessions_deny_pending_writes(operations):
    store,client,dev,_,_,headers,start=operations; hdr=headers(dev)
    draft=action(client,hdr,'taxi',TAXI).json()
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


def test_confirmation_cannot_cross_sessions_and_retention_prunes_drafts(operations):
    store,client,dev,other,_,headers,start=operations
    draft=action(client,headers(dev),'taxi',TAXI).json()
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
