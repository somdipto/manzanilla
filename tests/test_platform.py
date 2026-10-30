import asyncio, json, time
import pytest
from fastapi.testclient import TestClient
from cloud.db import Store
from cloud.models import Hotel
from cloud.gateway import Gateway
from edge.executor import Executor
import cloud.app as server
from cloud.hotel_answers import ContentApproval, approve_content, source_hash

VOICE = {'mode':'reception','language':'es','staff_language':'es','session_id':'test-voice','generation':3}

@pytest.fixture
def platform(tmp_path, monkeypatch, reservation_connector):
    db=Store(str(tmp_path/'test.db'))
    monkeypatch.setattr(server,'store',db)
    monkeypatch.setenv('ORANGE_ADMIN_TOKEN','x'*40)
    monkeypatch.delenv('GEMINI_API_KEY',raising=False)
    server.tickets.clear(); server.active_devices.clear(); server.edge_connections.clear()
    for hid in ['a','b']: db.save_hotel(Hotel(id=hid,name='Hotel '+hid,connector='rest',knowledge={'breakfast':'7–10 AM'},checkin_instructions='Present ID to reception').model_dump())
    return db,TestClient(server.app)

async def unavailable(*args): return {'status':'unavailable'}
async def yes(*args): return True
async def no(*args): return False

def run(coro): return asyncio.run(coro)

def gateway(db, sid='s', confirm=yes): return Gateway(db,'a',sid,unavailable,confirm)

def approve_rendering(db, kind='knowledge', key='breakfast', language='en', title='Breakfast',
                      body='7–10 AM', hid='a'):
    hotel=db.hotel(hid)
    source=hotel['knowledge'][key] if kind=='knowledge' else hotel['checkin_instructions']
    approval=ContentApproval(kind=kind,content_key=key,language=language,
        source_hash=source_hash(kind,key,source),title=title,body=body)
    approve_content(db,hid,approval,authorize=lambda:None)
    return approval.model_dump()

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
    assert run(g.execute('get_guest',{'booking_id':'fixture-204'},'1'))['status']=='denied'
    assert run(g.execute('lookup_booking',{'reference':'FIXTURE-204','surname':'wrong'},'2'))['status']=='not_found'
    found=run(g.execute('lookup_booking',{'reference':'FIXTURE-204','surname':'Smith'},'3'))
    assert found['status']=='ok'
    approve_rendering(db,'arrival','',title='Arrival',body='Present ID to reception')
    result=run(g.execute('get_checkin_instructions',{'booking_id':found['booking_id']},'4'))
    assert result['guidance_only'] and result['checkin_completed'] is False
    assert result['instructions']=='Arrival\nPresent ID to reception'
    assert result['fact_refs'][0]['kind']=='arrival'
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
    missing=run(gateway(db).execute('search_hotel_knowledge',{'query':'breakfast'},'unapproved'))
    assert missing['matches']=={} and missing['help_action']=='operator'
    approve_rendering(db)
    result=run(gateway(db).execute('search_hotel_knowledge',{'query':'breakfast'},'1'))
    assert result['matches']=={'Breakfast':'7–10 AM'}
    assert result['fact_refs'][0]['language']=='en'
    other=Gateway(db,'b','foreign',unavailable,yes)
    assert run(other.execute('search_hotel_knowledge',{'query':'breakfast'},'1'))['matches']=={}

def test_tenant_isolation_and_revocation(platform):
    db,client=platform; staff=db.issue('a','staff');dev=db.issue('a','device')
    hdr={'Authorization':'Bearer '+staff['token']}
    assert client.get('/api/hotels/a/overview',headers=hdr).status_code==200
    assert client.get('/api/hotels/b/overview',headers=hdr).status_code==403
    assert client.get('/api/hotels',headers=hdr).status_code==401
    assert client.post('/api/device/session',headers=hdr,json=VOICE).status_code==401
    dh={'Authorization':'Bearer '+dev['token']}
    hb=client.post('/api/device/heartbeat',headers=dh,json={'app_version':'0.1','config_version':0})
    assert hb.json()['hotel']['id']=='a'
    assert client.post('/api/device/heartbeat',headers=dh,json={'app_version':'0.1','config_version':0,'protocol':99}).status_code==426
    client.delete('/api/credentials/'+dev['id'],headers={'Authorization':'Bearer '+'x'*40})
    assert client.post('/api/device/session',headers=dh,json=VOICE).status_code==401

def test_resolution_scope_and_summary(platform):
    db,client=platform; ident=db.item('b','s','note',{'summary':'private'})
    staff=db.issue('a','staff'); hdr={'Authorization':'Bearer '+staff['token']}
    assert client.patch('/api/hotels/a/items/'+ident,headers=hdr).status_code==404
    summary=client.get('/api/hotels/a/morning',params={'start':time.time()-3600,'end':time.time()+1},headers=hdr).json()
    assert summary['items']==[] and summary['automatically_resolved'] is None

def test_ticket_single_use_and_real_gateway_path(platform,monkeypatch):
    db,client=platform; dev=db.issue('a','device')
    monkeypatch.setattr(server,'GENERIC_RELAY_ENABLED',True)  # Offline fake relay only.
    async def fake_relay(ws,hotel,gateway,pending,metadata):
        await ws.send_json({'type':'ready'})
        msg=await ws.receive_json()
        result=await gateway.execute('connect_operator',msg,'real-call')
        await ws.send_json(result)
    monkeypatch.setattr(server,'relay',fake_relay)
    ticket=client.post('/api/device/session',headers={'Authorization':'Bearer '+dev['token']},json=VOICE).json()['ticket']
    with client.websocket_connect('/ws/device',headers={'X-Orange-Ticket':ticket}) as ws:
        assert ws.receive_json()['type']=='ready'
        ws.send_json({'reason':'human now'})
        assert ws.receive_json()['status']=='unavailable'
    with pytest.raises(Exception):
        with client.websocket_connect('/ws/device',headers={'X-Orange-Ticket':ticket}): pass
    assert db.rows('SELECT * FROM sessions')[0]['status']=='ended'
    assert db.rows('SELECT * FROM items')[0]['kind']=='escalation'

def test_missing_gemini_key_reports_failure(platform,monkeypatch):
    db,client=platform;dev=db.issue('a','device');monkeypatch.delenv('GEMINI_API_KEY',raising=False)
    monkeypatch.setattr(server,'GENERIC_RELAY_ENABLED',True)  # Missing-key component failure only.
    ticket=client.post('/api/device/session',headers={'Authorization':'Bearer '+dev['token']},json=VOICE).json()['ticket']
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


def test_demo_configuration_is_rejected_and_stale_records_fail_unavailable(platform):
    db,client=platform
    actual=db.hotel('a')
    response=client.put('/api/hotels/a',headers={'Authorization':'Bearer '+'x'*40},
                        json={**actual,'connector':'demo'})
    assert response.status_code==422 and db.hotel('a')==actual
    # Simulate a persisted configuration from before the runtime cutover.
    db.save_hotel({**actual,'connector':'demo'})
    g=gateway(db)
    result=run(g.execute('lookup_booking',{'reference':'DEMO-204','surname':'Smith'},'old-demo'))
    assert result['status']=='unavailable'
    assert run(g.execute('get_guest',{'booking_id':'demo-204'},'guest'))['status']=='denied'
    assert not db.rows('SELECT * FROM items')


def test_content_review_requires_admin_and_current_scoped_source(platform,monkeypatch):
    db,client=platform
    monkeypatch.delenv('GEMINI_API_KEY',raising=False)
    admin={'Authorization':'Bearer '+'x'*40}
    staff=db.issue('a','staff'); device=db.issue('a','device')
    selector={'kind':'knowledge','content_key':'breakfast','language':'es',
              'source_hash':source_hash('knowledge','breakfast','7–10 AM')}
    approval={**selector,'title':'Desayuno','body':'  De 7 a 10 h.\n  '}
    for headers in ({},{'Authorization':'Bearer '+staff['token']},
                    {'Authorization':'Bearer '+device['token']}):
        for route in ('draft','approve'):
            response=client.post('/api/hotels/a/content/'+route,headers=headers,
                                 json=selector if route=='draft' else approval)
            assert response.status_code==401
    failed=client.post('/api/hotels/a/content/draft',headers=admin,json=selector)
    assert failed.status_code==503
    assert not db.rows('SELECT * FROM approved_content')
    assert client.post('/api/hotels/a/content/approve',headers=admin,
                       json={**approval,'hotel':'b'}).status_code==422
    assert client.post('/api/hotels/missing/content/approve',headers=admin,json=approval).status_code==404
    accepted=client.post('/api/hotels/a/content/approve',headers=admin,json=approval)
    assert accepted.status_code==200
    row=db.rows('SELECT * FROM approved_content')[0]
    assert row['hotel']=='a' and row['body']==approval['body'] and row['approved_by']=='admin'
    assert not db.rows("SELECT * FROM approved_content WHERE hotel='b'")
    hotel=db.hotel('a'); hotel['knowledge']['breakfast']='08:00–11:00'; db.save_hotel(hotel)
    conflict=client.post('/api/hotels/a/content/approve',headers=admin,json=approval)
    assert conflict.status_code==409
    assert db.rows('SELECT * FROM approved_content')[0]==row


@pytest.fixture
def readiness(platform,monkeypatch):
    import socket
    db,client=platform
    dev=db.issue('a','device')
    def no_network(*args,**kwargs):
        pytest.fail('Hotel readiness must not make network or provider calls')
    monkeypatch.setattr(socket.socket,'connect',no_network)
    def probe(**values):
        response=client.post('/api/device/heartbeat',
            headers={'Authorization':'Bearer '+dev['token']},
            json={'app_version':'17.0.41','config_version':7,**values})
        assert response.status_code==200
        result=response.json()
        assert set(result['services'])=={'checkin','question','taxi','operator','message'}
        assert all(type(value) is bool for value in result['services'].values())
        return result
    return db,client,dev,probe


def test_heartbeat_services_are_scoped_and_do_not_rewrite_hotel(readiness,monkeypatch):
    db,_,dev,probe=readiness
    monkeypatch.setenv('GEMINI_API_KEY','synthetic-readiness-only')
    db.issue('b','staff')
    db.issue('a','edge')
    approve_rendering(db,hid='b')
    approve_rendering(db,'arrival','',hid='b')
    hotel=db.hotel('a')
    hotel['services']={'spa':'Ask reception'}
    hotel=db.save_hotel(hotel)
    result=probe(language='en')
    assert result['services']=={'checkin':False,'question':False,'taxi':False,'operator':False,'message':False}
    assert result['hotel']==hotel and db.hotel('a')==hotel
    assert result['device_id']==dev['id'] and result['protocol']==1
    stored=db.rows('SELECT seen,version,config_version FROM credentials WHERE id=?',(dev['id'],))[0]
    assert stored['seen'] is not None and stored['version']=='17.0.41' and stored['config_version']==7
    assert not db.rows('SELECT * FROM sessions')
    assert not db.rows('SELECT * FROM fish_sessions')
    assert not db.rows('SELECT * FROM calls')
    assert not db.rows('SELECT * FROM items')


def test_heartbeat_uses_requested_locale_and_only_legacy_callers_use_hotel_default(readiness,monkeypatch):
    db,_,_,probe=readiness
    monkeypatch.setenv('GEMINI_API_KEY','synthetic-readiness-only')
    db.save_hotel({**db.hotel('a'),'default_language':'es'})
    approve_rendering(db,language='es',title='Desayuno',body='De 7 a 10 h.')
    approve_rendering(db,'arrival','',language='es',title='Llegada',body='Presente su documento en recepción.')
    for request in ({},{'language':'es'}):
        flags=probe(**request)['services']
        assert flags['question'] is True and flags['checkin'] is True
    for language in ('en','ar'):
        flags=probe(language=language)['services']
        assert flags['question'] is False and flags['checkin'] is False
    approve_rendering(db,language='ar',title='الإفطار',body='من السابعة إلى العاشرة.')
    assert probe(language='ar')['services']['question'] is True
    assert probe(language='ar')['services']['checkin'] is False
    db.save_hotel({**db.hotel('a'),'default_language':'en'})
    assert probe()['services']['question'] is False
    assert probe(language='es')['services']['question'] is True


@pytest.mark.parametrize('language',['',' ','ES','es-ES','zz',None,42,True])
def test_heartbeat_rejects_present_invalid_locale_without_legacy_fallback(readiness,language):
    db,client,dev,_=readiness
    db.save_hotel({**db.hotel('a'),'default_language':'es'})
    approve_rendering(db,language='es')
    response=client.post('/api/device/heartbeat',
        headers={'Authorization':'Bearer '+dev['token']},
        json={'app_version':'17.0.41','config_version':0,'language':language})
    assert response.status_code==422
    assert db.rows('SELECT seen FROM credentials WHERE id=?',(dev['id'],))[0]['seen'] is None


def test_heartbeat_requires_current_approvals_and_gemini_only_for_questions(readiness,monkeypatch):
    db,_,_,probe=readiness
    approve_rendering(db)
    approve_rendering(db,'arrival','')
    for key in ('','   '):
        monkeypatch.setenv('GEMINI_API_KEY',key)
        flags=probe(language='en')['services']
        assert flags['checkin'] is True and flags['question'] is False
    monkeypatch.setenv('GEMINI_API_KEY','synthetic-readiness-only')
    flags=probe(language='en')['services']
    assert flags['checkin'] is True and flags['question'] is True
    hotel=db.hotel('a'); hotel['knowledge']['breakfast']='08:00–11:00'; db.save_hotel(hotel)
    flags=probe(language='en')['services']
    assert flags['checkin'] is True and flags['question'] is False
    db.save_hotel({**db.hotel('a'),'checkin_instructions':'Ask reception for the new procedure'})
    flags=probe(language='en')['services']
    assert flags['checkin'] is False and flags['question'] is False
    approve_rendering(db,body='08:00–11:00')
    approve_rendering(db,'arrival','',body='Ask reception for the new procedure')
    assert probe(language='en')['services']['question'] is True
    assert probe(language='en')['services']['checkin'] is True
    db.run("DELETE FROM approved_content WHERE hotel='a'")
    flags=probe(language='en')['services']
    assert flags['checkin'] is False and flags['question'] is False


def test_heartbeat_questions_require_all_eligible_facts_within_count_limit(readiness,monkeypatch):
    db,_,_,probe=readiness
    monkeypatch.setenv('GEMINI_API_KEY','synthetic-readiness-only')
    knowledge={f'fact-{index}':'Approved source' for index in range(33)}
    db.save_hotel({**db.hotel('a'),'knowledge':knowledge})
    for key in list(knowledge)[:32]:
        approve_rendering(db,key=key,title=key,body='Approved answer')
    approve_rendering(db,'arrival','')
    assert probe(language='en')['services']['question'] is True
    approve_rendering(db,key='fact-32',title='Final fact',body='Approved answer')
    flags=probe(language='en')['services']
    assert flags['question'] is False and flags['checkin'] is True
    db.run("DELETE FROM approved_content WHERE hotel='a' AND content_key='fact-32'")
    assert probe(language='en')['services']['question'] is True


def test_heartbeat_question_context_counts_full_unicode_content_at_boundary(readiness,monkeypatch):
    db,_,_,probe=readiness
    monkeypatch.setenv('GEMINI_API_KEY','synthetic-readiness-only')
    db.save_hotel({**db.hotel('a'),'knowledge':{'a':'源','b':'源','c':'源'}})
    for key,size in (('a',8000),('b',8000),('c',7991)):
        approve_rendering(db,key=key,title='題',body='界'*size)
    assert probe(language='en')['services']['question'] is True
    approve_rendering(db,key='c',title='題',body='界'*7992)
    assert probe(language='en')['services']['question'] is False


def test_heartbeat_staff_services_follow_last_scoped_credential_revocation(readiness):
    db,client,_,probe=readiness
    foreign=db.issue('b','staff')
    assert probe()['services']['message'] is False
    first=db.issue('a','staff'); second=db.issue('a','staff')
    for field in ('message','taxi','operator'):
        assert probe()['services'][field] is True
    admin={'Authorization':'Bearer '+'x'*40}
    assert client.delete('/api/credentials/'+first['id'],headers=admin).status_code==200
    assert probe()['services']['message'] is True
    assert client.delete('/api/credentials/'+second['id'],headers=admin).status_code==200
    flags=probe()['services']
    assert flags['message'] is False and flags['taxi'] is False and flags['operator'] is False
    assert db.auth(foreign['token'],'staff')['hotel']=='b'


@pytest.mark.parametrize('number',['+34123456789','112'])
def test_heartbeat_phone_handoffs_need_valid_numbers_not_staff(readiness,number):
    db,_,_,probe=readiness
    db.save_hotel({**db.hotel('a'),'taxi_mode':'dialer','taxi_number':number,'operator_primary':number})
    flags=probe()['services']
    assert flags['taxi'] is True and flags['operator'] is True and flags['message'] is False
    db.save_hotel({**db.hotel('a'),'taxi_mode':'staff_request'})
    assert probe()['services']['taxi'] is False
    db.issue('a','staff')
    assert probe()['services']['taxi'] is True


@pytest.mark.parametrize('number',['','+12','12','+1234567890123456','tel:+34123456789',
                                  '*123#','123;456',' 112','112 ',None,112])
def test_heartbeat_invalid_legacy_phones_never_enable_dialer_services(readiness,number):
    db,_,_,probe=readiness
    # Saved legacy dictionaries may predate today's configuration validation.
    db.save_hotel({**db.hotel('a'),'taxi_mode':'dialer','taxi_number':number,
        'operator_primary':number,'operator_secondary':'+34123456789','emergency_contact':'112'})
    flags=probe()['services']
    assert flags['taxi'] is False and flags['operator'] is False
    db.issue('a','staff')
    flags=probe()['services']
    assert flags['taxi'] is False and flags['operator'] is True and flags['message'] is True


def test_heartbeat_preserves_protocol_rejection_and_requires_live_device_credential(readiness):
    db,client,dev,probe=readiness
    probe()
    previous=db.rows('SELECT seen,version,config_version FROM credentials WHERE id=?',(dev['id'],))[0]
    payload={'app_version':'unsupported-client','config_version':99,'protocol':2,'language':'es'}
    headers={'Authorization':'Bearer '+dev['token']}
    assert client.post('/api/device/heartbeat',headers=headers,json=payload).status_code==426
    assert db.rows('SELECT seen,version,config_version FROM credentials WHERE id=?',(dev['id'],))[0]==previous
    payload['protocol']=1
    for role in ('staff','edge'):
        credential=db.issue('a',role)
        assert client.post('/api/device/heartbeat',
            headers={'Authorization':'Bearer '+credential['token']},json=payload).status_code==401
    assert client.post('/api/device/heartbeat',json=payload).status_code==401
    db.run('DELETE FROM credentials WHERE id=?',(dev['id'],))
    assert client.post('/api/device/heartbeat',headers=headers,json=payload).status_code==401


def test_hotel_configuration_cas_preserves_winner_and_requires_admin(platform):
    db,client=platform
    admin={'Authorization':'Bearer '+'x'*40}
    original=db.hotel('a')
    change={**original,'name':'Reviewed hotel name','services':{'spa':'Exact spa policy','untouched':'Keep me'},
            'knowledge':{**original['knowledge'],'quiet hours':'22:00–07:00'}}
    saved=client.put('/api/hotels/a',headers=admin,json=change)
    assert saved.status_code==200 and saved.json()['config_version']==original['config_version']+1
    stale=client.put('/api/hotels/a',headers=admin,json={**original,'name':'Stale overwrite'})
    assert stale.status_code==409 and stale.json()['config_version']==saved.json()['config_version']
    assert db.hotel('a')==saved.json()
    unversioned={key:value for key,value in original.items() if key!='config_version'}
    assert client.put('/api/hotels/a',headers=admin,json=unversioned).status_code==409
    staff=db.issue('a','staff')
    assert client.put('/api/hotels/a',headers={'Authorization':'Bearer '+staff['token']},
                      json=saved.json()).status_code==401
    assert client.put('/api/hotels/a',json=saved.json()).status_code==401
    assert db.hotel('a')==saved.json()
    reviewed={**saved.json(),'name':'Second review'}
    assert client.put('/api/hotels/a',headers=admin,json=reviewed).json()['config_version']==3
    assert db.hotel('a')['services']==change['services'] and db.hotel('a')['knowledge']==change['knowledge']
    created=client.put('/api/hotels/new',headers=admin,json={'id':'new','name':'New property','config_version':999})
    assert created.status_code==200 and created.json()['config_version']==1
    principal=client.get('/api/hotels/a/overview',headers=admin).json()['principal']
    assert principal=={'role':'admin','id':'admin','hotel':'a'}


def test_hotel_configuration_cas_is_shared_across_database_owners(platform):
    from concurrent.futures import ThreadPoolExecutor
    from threading import Barrier
    from cloud.db import HotelVersionConflict
    db,_=platform
    external=Store(db.db.execute('PRAGMA database_list').fetchone()[2])
    snapshot=db.hotel('a')
    barrier=Barrier(2)
    def save(pair):
        owner,name=pair
        barrier.wait(timeout=5)
        try: return owner.save_hotel({**snapshot,'name':name})
        except HotelVersionConflict as conflict: return conflict.version
    try:
        with ThreadPoolExecutor(2) as pool:
            results=list(pool.map(save,[(db,'One'),(external,'Two')]))
        winners=[result for result in results if isinstance(result,dict)]
        conflicts=[result for result in results if isinstance(result,int)]
        assert len(winners)==1 and conflicts==[snapshot['config_version']+1]
        assert db.hotel('a')==winners[0] and external.hotel('a')==winners[0]
    finally:
        external.db.close()


def test_item_metadata_migration_keeps_historical_unknowns_and_is_restart_safe(tmp_path):
    import sqlite3
    path=tmp_path/'legacy-items.db'
    connection=sqlite3.connect(path)
    connection.executescript("""
        CREATE TABLE hotels(id TEXT PRIMARY KEY,config TEXT NOT NULL);
        CREATE TABLE items(id TEXT PRIMARY KEY,hotel TEXT NOT NULL REFERENCES hotels(id),
            session TEXT NOT NULL,kind TEXT,created REAL,payload TEXT,status TEXT DEFAULT 'open');
    """)
    connection.execute('INSERT INTO hotels VALUES(?,?)',('old',json.dumps(Hotel(id='old',name='Historical').model_dump())))
    for status in ('open','resolved'):
        connection.execute('INSERT INTO items VALUES(?,?,?,?,?,?,?)',
                           (status,'old','expired-history','note',123.0,json.dumps({'original_request':'Exact '+status}),status))
    connection.commit()
    connection.close()
    for _ in range(2):
        db=Store(str(path))
        try:
            rows=db.rows('SELECT * FROM items ORDER BY id')
            assert {row['status'] for row in rows}=={'open','resolved'}
            for row in rows:
                assert row['state_version']==0
                assert all(row[field] is None for field in (
                    'acknowledged_at','acknowledged_by','responsible_name','resolved_at','resolution_note','last_transition_hash'))
                assert row['session']=='expired-history' and row['created']==123.0
                assert json.loads(row['payload'])['original_request']=='Exact '+row['status']
        finally:
            db.db.close()


def test_staff_inbox_pages_keep_old_unfinished_requests_reachable(platform):
    db,client=platform
    staff=db.issue('a','staff'); headers={'Authorization':'Bearer '+staff['token']}
    old_open=db.item('a','historical-session','note',{'original_request':'Still needs staff'})
    old_ack=db.item('a','historical-session','taxi',{'destination':'Still waiting'})
    db.run('UPDATE items SET created=1 WHERE hotel=?',('a',))
    db.run("UPDATE items SET status='acknowledged',state_version=1,acknowledged_by=? WHERE id=?",(staff['id'],old_ack))
    with db.lock,db.db:
        db.db.executemany("INSERT INTO items(id,hotel,session,kind,created,payload,status) VALUES(?,'a','history','note',2,'{}','resolved')",
                         [(f'resolved-{index:04d}',) for index in range(620)])
    foreign=db.item('b','foreign','note',{'original_request':'Private other hotel'})
    first=client.get('/api/hotels/a/overview',headers=headers).json()
    assert len(first['items'])==100 and first['items_next']
    ids=[item['id'] for item in first['items']]
    cursor=first['items_next']
    # New arrivals do not shift the cursor and hide or duplicate older rows.
    newest=db.item('a','new-session','note',{'original_request':'New arrival'})
    while cursor:
        page=client.get('/api/hotels/a/overview',headers=headers,params={'before':cursor}).json()
        assert len(page['items'])<=100
        ids.extend(item['id'] for item in page['items'])
        cursor=page['items_next']
    assert len(ids)==len(set(ids))==622
    assert {old_open,old_ack}<=set(ids) and newest not in ids and foreign not in ids
    unfinished=client.get('/api/hotels/a/overview',headers=headers,params={'item_status':'unfinished','limit':1}).json()
    unfinished_ids=[]
    while True:
        unfinished_ids.extend(item['id'] for item in unfinished['items'])
        if not unfinished['items_next']: break
        unfinished=client.get('/api/hotels/a/overview',headers=headers,
                              params={'item_status':'unfinished','limit':1,'before':unfinished['items_next']}).json()
    assert set(unfinished_ids)=={old_open,old_ack,newest}
    resolved=client.get('/api/hotels/a/overview',headers=headers,params={'item_status':'resolved','limit':500}).json()
    assert len(resolved['items'])==500 and all(item['status']=='resolved' for item in resolved['items'])
    assert client.get('/api/hotels/a/overview',headers=headers,params={'before':foreign}).status_code==404
    assert client.get('/api/hotels/b/overview',headers=headers,params={'before':foreign}).status_code==403
    assert client.get('/api/hotels/a/overview',params={'before':old_open}).status_code==403
    for params in ({'limit':0},{'limit':501},{'item_status':'unknown'}):
        assert client.get('/api/hotels/a/overview',headers=headers,params=params).status_code==422
    resolved_old=client.patch('/api/hotels/a/items/'+old_ack,headers=headers,
                             json={'status':'resolved','expected_version':1,'resolution_note':'Handled after paging'})
    assert resolved_old.status_code==200 and resolved_old.json()['version']==2
    assert client.patch('/api/hotels/a/items/'+old_ack,headers=headers,
                        json={'status':'resolved','expected_version':1,'resolution_note':'Stale change'}).status_code==409


def test_capabilities_only_heartbeat_stays_bounded_with_large_approved_hotel(readiness):
    db,client,dev,probe=readiness
    db.issue('a','staff')
    knowledge={f'fact-{index}':'Exact approved source. '*330 for index in range(40)}
    hotel=db.save_hotel({**db.hotel('a'),'knowledge':knowledge})
    for key,source in knowledge.items():
        approve_rendering(db,key=key,title=key,body=source)
    approve_rendering(db,'arrival','',title='Arrival',body='Present ID to reception')
    legacy=probe(language='en')
    assert len(json.dumps(legacy).encode())>256*1024
    response=client.post('/api/device/heartbeat',headers={'Authorization':'Bearer '+dev['token']},
                         json={'app_version':'17.0.47','config_version':0,'language':'en','capabilities_only':True})
    assert response.status_code==200 and len(response.content)<4096
    minimal=response.json()
    assert minimal['hotel']=={key:hotel[key] for key in ('id','name','timezone','default_language','supported_languages','config_version')}
    assert minimal['services']==legacy['services']
    assert minimal['services']['taxi'] and minimal['services']['message'] and minimal['services']['checkin']
    assert not minimal['services']['question']  # Oversized knowledge is not silently truncated.


def test_managed_new_language_admission_preserves_existing_request_reconciliation(platform,monkeypatch):
    from fastapi import HTTPException
    from cloud.fish import request_lease
    db,client=platform
    dev=db.issue('a','device'); principal=db.auth(dev['token'],'device')
    headers={'Authorization':'Bearer '+dev['token']}
    calls=[]

    async def admission(store,device,value):
        calls.append(value.request_id)
        if value.request_id=='prior':
            raise HTTPException(409,'Recorded creation request already used')
        return {'status':'admitted'}

    monkeypatch.setattr(server,'livekit_mint',admission)
    body={'guest_language':'es','staff_language':'en','mode':'translation','request_id':'new','protocol':'translation-v1'}
    rejected=client.post('/api/device/fish-session',headers=headers,json=body)
    assert rejected.status_code==422 and rejected.headers['X-Manzanilla-Session-Creation']=='not-created'
    rejected=client.post('/api/device/fish-session',headers=headers,json={**body,'guest_language':'en','staff_language':'es'})
    assert rejected.status_code==422 and not calls and not db.rows('SELECT * FROM fish_sessions')
    assert client.post('/api/device/fish-session',headers=headers,json={**body,'guest_language':'hi'}).json()=={'status':'admitted'}
    ident=request_lease(principal,'prior')
    db.reserve_fish(principal,60,600,600,30,30,ident)
    db.run("UPDATE fish_sessions SET status='ended' WHERE id=?",(ident,))
    # A recorded request must reach the existing identity/reconciliation rules,
    # even if its pair is no longer enabled by hotel staff.
    prior=client.post('/api/device/fish-session',headers=headers,json={**body,'request_id':'prior'})
    assert prior.status_code==409 and 'X-Manzanilla-Session-Creation' not in prior.headers
    assert calls==['new','prior']


def test_revoked_voice_bearer_authorizes_only_exact_historical_cleanup(platform,monkeypatch):
    from cloud.fish import request_lease
    db,client=platform
    monkeypatch.delenv('FISH_API_KEY',raising=False)
    owner=db.issue('a','device'); other=db.issue('a','device'); foreign=db.issue('b','device')
    principal=db.auth(owner['token'],'device')
    ident=request_lease(principal,'original-request')
    db.reserve_fish(principal,60,600,600,30,30,ident)
    db.run("UPDATE fish_sessions SET status='ended',session_id='original-provider-session' WHERE id=?",(ident,))
    db.run('DELETE FROM credentials WHERE id=?',(owner['id'],))
    replacement=db.issue('a','device')
    endpoints=['/api/device/fish-session/original-provider-session/end',
               '/api/device/fish-session-requests/original-request/end']
    for endpoint in endpoints:
        for token in ('','wrong-token',other['token'],foreign['token'],replacement['token']):
            response=client.post(endpoint,headers={'Authorization':'Bearer '+token})
            assert response.status_code==401
        response=client.post(endpoint,headers={'Authorization':'Bearer '+owner['token']})
        assert response.status_code==200 and response.json()['status']=='ended'
    for device in (other,foreign):
        other_principal=db.auth(device['token'],'device')
        other_lease=request_lease(other_principal,'different-request')
        db.reserve_fish(other_principal,60,600,600,30,30,other_lease)
        db.run("UPDATE fish_sessions SET status='ended',session_id=? WHERE id=?",('session-'+device['id'],other_lease))
        for endpoint in endpoints:
            assert client.post(endpoint,headers={'Authorization':'Bearer '+device['token']}).status_code==404
        assert client.post('/api/device/fish-session-requests/different-request/end',
                           headers={'Authorization':'Bearer '+owner['token']}).status_code==404
    headers={'Authorization':'Bearer '+owner['token']}
    assert client.post('/api/device/fish-session/not-owned/end',headers=headers).status_code==404
    assert client.post('/api/device/fish-session-requests/not-owned/end',headers=headers).status_code==404
    body={'guest_language':'hi','staff_language':'en','mode':'translation','request_id':'new','protocol':'translation-v1'}
    assert client.post('/api/device/fish-session',headers=headers,json=body).status_code==401
    assert client.post('/api/device/heartbeat',headers=headers,json={'app_version':'test','config_version':0}).status_code==401
    assert client.post('/api/device/operations/start',headers=headers,
                       json={'session_id':'new','language':'en','staff_language':'hi','mode':'reception'}).status_code==401
    assert client.get('/api/hotels/a/overview',headers=headers).status_code==403
    # Possession of the old capability does not turn unknown provider settlement
    # into positive cleanup proof.
    db.run("UPDATE fish_sessions SET status='uncertain',session_id=NULL WHERE id=?",(ident,))
    pending=client.post('/api/device/fish-session-requests/original-request/end',headers=headers)
    assert pending.status_code==503 and db.rows('SELECT status FROM fish_sessions WHERE id=?',(ident,))[0]['status']=='uncertain'
