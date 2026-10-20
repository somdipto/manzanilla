"""Voice admission: the managed Fish interpreter route is open; the legacy generic relay stays closed."""
import time
import pytest
from fastapi.testclient import TestClient
from starlette.websockets import WebSocketDisconnect
from cloud.db import Store
from cloud.models import Hotel
import cloud.app as server
import cloud.fish as fish
import cloud.realtime as realtime


@pytest.fixture
def audio_gate(tmp_path,monkeypatch):
    store=Store(str(tmp_path/'policy.db'))
    store.save_hotel(Hotel(id='a',name='Synthetic unconfigured hotel').model_dump())
    monkeypatch.setattr(server,'store',store)
    monkeypatch.setattr(server,'tickets',{})
    monkeypatch.setenv('FISH_API_KEY','OFFLINE-TEST-KEY')
    monkeypatch.setenv('FISH_VOICE_ID','offline-voice')
    monkeypatch.setenv('GEMINI_API_KEY','OFFLINE-TEST-KEY')
    calls=[]
    def forbidden(*args,**kwargs):
        calls.append((args,kwargs))
        raise AssertionError('Blocked routes must not reach provider code')
    monkeypatch.setattr(server,'relay',forbidden)
    monkeypatch.setattr(fish.httpx,'AsyncClient',forbidden)
    monkeypatch.setattr(realtime,'connect',forbidden)
    dev=store.issue('a','device')
    headers={'Authorization':'Bearer '+dev['token']}
    return store,TestClient(server.app),dev,headers,calls


@pytest.mark.parametrize('body',[
    {'session_id':'closed-translation','language':'es','staff_language':'en','mode':'translation'},
    {'session_id':'closed-reception','language':'es','staff_language':'es','mode':'reception'},
])
def test_generic_relay_admission_stays_closed(audio_gate,body):
    store,client,_,headers,calls=audio_gate
    assert client.post('/api/device/session',json=body).status_code==401
    response=client.post('/api/device/session',headers=headers,json=body)
    assert response.status_code==503
    assert 'staff-led' in response.json()['detail']
    assert 'no fallback' in response.json()['detail']
    assert not calls and not server.tickets
    assert not store.rows('SELECT * FROM fish_sessions')
    assert not store.rows('SELECT * FROM sessions')


def test_stale_audio_ticket_cannot_bypass_the_closed_relay(audio_gate):
    store,client,dev,_,calls=audio_gate
    server.tickets['stale-ticket']={
        'device':store.auth(dev['token'],'device'),'expires':time.time()+30,
        'metadata':{'session_id':'stale','generation':0,'language':'es','staff_language':'en','mode':'translation'}}
    with pytest.raises(WebSocketDisconnect) as denied:
        with client.websocket_connect('/ws/device',headers={'X-Orange-Ticket':'stale-ticket'}):
            pytest.fail('Gated websocket must not be accepted')
    assert denied.value.code==4403
    assert not server.tickets and not calls
    assert not store.rows('SELECT * FROM sessions')


def test_staff_led_operations_remain_operational(audio_gate):
    store,client,_,headers,calls=audio_gate
    sid='staff-led'
    started=client.post('/api/device/operations/start',headers=headers,json={
        'session_id':sid,'language':'es','staff_language':'en','mode':'reception'})
    assert started.status_code==200
    missing=client.post('/api/device/operations/'+sid+'/action',headers=headers,json={
        'action':'message','call_id':'missing','arguments':{}}).json()
    hotel=store.hotel('a')
    hotel['checkin_instructions']='Ask reception for arrival assistance.'
    store.save_hotel(hotel)
    assert missing['status']=='needs_arguments'
    draft=client.post('/api/device/operations/'+sid+'/action',headers=headers,json={
        'action':'message','call_id':'message','arguments':{'original_request':'Synthetic staff-led request'}}).json()
    assert draft['status']=='confirmation_required' and not store.rows('SELECT * FROM items')
    guide=client.post('/api/device/operations/'+sid+'/action',headers=headers,json={
        'action':'checkin','call_id':'guide','arguments':{}}).json()
    assert guide['status']=='ok' and guide['result']['instructions']==hotel['checkin_instructions']
    assert guide['result']['guidance_only'] is True and guide['result']['checkin_completed'] is False
    response=client.post('/api/device/operations/'+sid+'/confirm',headers=headers,json={
        'confirmation_id':draft['confirmation_id'],'accepted':True}).json()
    assert response['status']=='saved' and response['result']['item_id']
    assert response['result']['staff_acknowledged'] is False
    assert response['result']['pms_delivered'] is False
    assert len(store.rows('SELECT * FROM items'))==1 and not calls
