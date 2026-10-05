import asyncio, json, time
import pytest
from fastapi.testclient import TestClient
from cloud.db import Store
from cloud.models import Hotel
from cloud.gateway import Gateway
from edge.executor import Executor
import cloud.app as server

@pytest.fixture
def platform(tmp_path, monkeypatch):
    db=Store(str(tmp_path/'test.db'))
    monkeypatch.setattr(server,'store',db)
    monkeypatch.setenv('ORANGE_ADMIN_TOKEN','x'*40)
    server.tickets.clear(); server.active_devices.clear(); server.edge_connections.clear()
    for hid in ['a','b']: db.save_hotel(Hotel(id=hid,name='Hotel '+hid,connector='demo',knowledge={'breakfast':'7–10 AM'},checkin_instructions='Present ID to reception').model_dump())
    return db,TestClient(server.app)

async def unavailable(*args): return {'status':'unavailable'}
async def yes(*args): return True
async def no(*args): return False

def run(coro): return asyncio.run(coro)

def gateway(db, sid='s', confirm=yes): return Gateway(db,'a',sid,unavailable,confirm)

def test_notes_persist_and_duplicate_calls(platform):
    db,_=platform; g=gateway(db)
    args={'original_request':'Extend my stay tomorrow','summary':'Discuss extension','room_number':'204'}
    first=run(g.execute('leave_reception_note',args,'c1'))
    assert first['status']=='saved'
    assert run(g.execute('leave_reception_note',args,'c1'))==first
    assert len(db.rows('SELECT * FROM items'))==1
    assert run(g.execute('leave_reception_note',{**args,'summary':'different'},'c1'))['status']=='denied'
    reopened=Store(db.db.execute('PRAGMA database_list').fetchone()[2])
    assert reopened.rows('SELECT * FROM items')[0]['hotel']=='a'

def test_confirmation_cannot_be_supplied_by_model(platform):
    db,_=platform; g=gateway(db,confirm=no)
    args={'original_request':'note','summary':'note'}
    assert run(g.execute('leave_reception_note',{**args,'confirmed':True},'1'))['status']=='invalid_arguments'
    assert run(g.execute('leave_reception_note',args,'2'))['status']=='cancelled'
    assert not db.rows('SELECT * FROM items')

def test_booking_verification_and_guidance(platform):
    db,_=platform; g=gateway(db)
    assert run(g.execute('get_guest',{'booking_id':'demo-204'},'1'))['status']=='denied'
    assert run(g.execute('lookup_booking',{'reference':'DEMO-204','surname':'wrong'},'2'))['status']=='not_found'
    found=run(g.execute('lookup_booking',{'reference':'DEMO-204','surname':'Smith'},'3'))
    assert found['demo'] is True
    result=run(g.execute('get_checkin_instructions',{'booking_id':found['booking_id']},'4'))
    assert result['guidance_only'] and result['checkin_completed'] is False
    assert run(gateway(db,'another').execute('get_guest',{'booking_id':found['booking_id']},'5'))['status']=='denied'

def test_policy_and_validation(platform):
    db,_=platform; g=gateway(db)
    for tool in ['unlock_room','charge_card','shell','refund','cancel_booking']:
        assert run(g.execute(tool,{},tool))['status']=='denied'
    assert run(g.execute('call_taxi',{'destination':'airport'},'bad'))['status']=='invalid_arguments'

def test_taxi_does_not_claim_booking(platform):
    db,_=platform; result=run(gateway(db).execute('call_taxi',{'destination':'Airport','pickup_time':'Now'},'1'))
    assert result['status']=='queued' and result['booked'] is False
    assert len(db.rows("SELECT * FROM items WHERE kind='taxi'"))==1

def test_escalation_durable_when_no_operator(platform):
    db,_=platform; result=run(gateway(db).execute('connect_operator',{},'1'))
    assert result['status']=='unavailable' and not result['connected']
    assert len(db.rows("SELECT * FROM items WHERE kind='escalation'"))==1

def test_pms_failure_safe(platform):
    db,_=platform; h=db.hotel('a');h['connector']='unconfigured';db.save_hotel(h)
    assert run(gateway(db).execute('lookup_booking',{'reference':'ABC','surname':'Smith'},'1'))['status']=='unavailable'
    assert run(gateway(db).execute('leave_reception_note',{'original_request':'hello','summary':'hello'},'2'))['status']=='saved'

def test_concierge(platform):
    db,_=platform
    result=run(gateway(db).execute('search_hotel_knowledge',{'query':'breakfast'},'1'))
    assert result['matches']=={'breakfast':'7–10 AM'}

def test_tenant_isolation_and_revocation(platform):
    db,client=platform; staff=db.issue('a','staff');dev=db.issue('a','device')
    hdr={'Authorization':'Bearer '+staff['token']}
    assert client.get('/api/hotels/a/overview',headers=hdr).status_code==200
    assert client.get('/api/hotels/b/overview',headers=hdr).status_code==403
    assert client.get('/api/hotels',headers=hdr).status_code==401
    assert client.post('/api/device/session',headers=hdr).status_code==401
    dh={'Authorization':'Bearer '+dev['token']}
    hb=client.post('/api/device/heartbeat',headers=dh,json={'app_version':'0.1','config_version':0})
    assert hb.json()['hotel']['id']=='a'
    assert client.post('/api/device/heartbeat',headers=dh,json={'app_version':'0.1','config_version':0,'protocol':99}).status_code==426
    client.delete('/api/credentials/'+dev['id'],headers={'Authorization':'Bearer '+'x'*40})
    assert client.post('/api/device/session',headers=dh).status_code==401

def test_resolution_scope_and_summary(platform):
    db,client=platform; ident=db.item('b','s','note',{'summary':'private'})
    staff=db.issue('a','staff'); hdr={'Authorization':'Bearer '+staff['token']}
    assert client.patch('/api/hotels/a/items/'+ident,headers=hdr).status_code==404
    summary=client.get('/api/hotels/a/morning',params={'start':time.time()-3600,'end':time.time()+1},headers=hdr).json()
    assert summary['items']==[] and summary['automatically_resolved'] is None

def test_ticket_single_use_and_real_gateway_path(platform,monkeypatch):
    db,client=platform; dev=db.issue('a','device')
    async def fake_relay(ws,hotel,gateway,pending):
        await ws.send_json({'type':'ready'})
        msg=await ws.receive_json()
        result=await gateway.execute('connect_operator',msg,'real-call')
        await ws.send_json(result)
    monkeypatch.setattr(server,'relay',fake_relay)
    ticket=client.post('/api/device/session',headers={'Authorization':'Bearer '+dev['token']}).json()['ticket']
    with client.websocket_connect('/ws/device',headers={'X-Orange-Ticket':ticket}) as ws:
        assert ws.receive_json()['type']=='ready'
        ws.send_json({'reason':'human now'})
        assert ws.receive_json()['status']=='unavailable'
    with pytest.raises(Exception):
        with client.websocket_connect('/ws/device',headers={'X-Orange-Ticket':ticket}): pass
    assert db.rows('SELECT * FROM sessions')[0]['status']=='ended'
    assert db.rows('SELECT * FROM items')[0]['kind']=='escalation'

def test_missing_openai_key_reports_failure(platform,monkeypatch):
    db,client=platform;dev=db.issue('a','device');monkeypatch.delenv('OPENAI_API_KEY',raising=False)
    ticket=client.post('/api/device/session',headers={'Authorization':'Bearer '+dev['token']}).json()['ticket']
    with client.websocket_connect('/ws/device',headers={'X-Orange-Ticket':ticket}) as ws:
        assert ws.receive_json()['type']=='error'

def test_edge_allowlist(tmp_path):
    path=tmp_path/'reservations.json';path.write_text(json.dumps([{'reference':'ABC','surname':'Smith','id':'1','guest_name':'Alex','room_number':'204','eligible':True,'status':'arrival'}]))
    executor=Executor(path)
    assert executor.execute('shell',{'command':'whoami'})['status']=='denied'
    assert executor.execute('read_reservation',{'reference':'ABC','surname':'Smith','path':'/etc/passwd'})['status']=='invalid_arguments'
    assert executor.execute('read_reservation',{'reference':'ABC','surname':'Smith'})['booking']['id']=='1'
    assert executor.execute('read_reservation',{'reference':'ABC','surname':'wrong'})['booking'] is None

def test_staff_setup_authorization_scoped(platform):
    db,client=platform; dev=db.issue('a','device');staff=db.issue('a','staff');other=db.issue('b','staff')
    header={'Authorization':'Bearer '+dev['token']}
    assert client.post('/api/device/staff-check',headers=header,json={'staff_token':staff['token']}).status_code==200
    assert client.post('/api/device/staff-check',headers=header,json={'staff_token':other['token']}).status_code==403
    assert client.post('/api/device/staff-check',headers=header,json={'staff_token':dev['token']}).status_code==403
