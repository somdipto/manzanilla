import asyncio, base64, json
import pytest
from fastapi.testclient import TestClient
from cloud.db import Store
from cloud.models import Hotel
import cloud.app as server
import cloud.realtime as realtime

VOICE = {'mode':'reception','language':'es','staff_language':'es','session_id':'gemini-fixture','generation':7}

class GeminiUpstream:
    """Synthetic BidiGenerateContent peer: setup -> audio -> tool -> audio turn.

    No provider credentials or network calls. Exercises raw Gemini messages,
    not an echo or the removed OpenAI event protocol.
    """
    def __init__(self, mode='reception'):
        self.events=[]; self.queue=asyncio.Queue(); self.mode=mode; self.started=False
    async def send(self, raw):
        event=json.loads(raw); self.events.append(event)
        assert len(event)==1
        if 'setup' in event:
            assert not self.started
            self.started=True
            await self.queue.put({'setupComplete':{}})
        elif 'realtimeInput' in event:
            audio=event['realtimeInput']['audio']
            assert audio['mimeType']=='audio/pcm;rate=24000'
            assert base64.b64decode(audio['data']) == b'\x01\x00'*960
            await self.queue.put({'serverContent':{'inputTranscription':{'text':'Mi solicitud','languageCode':'es'}}})
            # Even a malicious/unexpected provider tool request is denied in translation.
            await self.queue.put({'toolCall':{'functionCalls':[{'id':'note-1','name':'leave_reception_note',
                'args':{'original_request':'Extend my stay','summary':'Discuss extension'}}]}})
        elif 'toolResponse' in event:
            self.result=event['toolResponse']['functionResponses'][0]['response']
            await self.queue.put({'serverContent':{'modelTurn':{'role':'model','parts':[{'inlineData':{
                'mimeType':'audio/pcm;rate=24000','data':base64.b64encode(b'\x00\x00'*100).decode()}}]},
                'outputTranscription':{'text':'Solicitud guardada','languageCode':'es'}}})
            await self.queue.put({'serverContent':{'turnComplete':True}})
            await self.queue.put({'serverContent':{'inputTranscription':{'text':'Otra pregunta','languageCode':'es'},'interrupted':True}})
    async def recv(self): return json.dumps(await self.queue.get())
    def __aiter__(self): return self
    async def __anext__(self): return await self.recv()
    async def __aenter__(self): return self
    async def __aexit__(self,*args): pass

@pytest.fixture
def relay_fixture(tmp_path,monkeypatch):
    db=Store(str(tmp_path/'db')); db.save_hotel(Hotel(id='a',name='A').model_dump())
    monkeypatch.setattr(server,'store',db)
    monkeypatch.setattr(server,'LIVE_AUDIO_ENABLED',True)  # Mocked transport, never a live qualification.
    monkeypatch.setenv('GEMINI_API_KEY','TEST-NOT-A-REAL-KEY')
    server.tickets.clear(); server.active_devices.clear(); server.device_connections.clear()
    upstream=GeminiUpstream()
    def connect(url,**kwargs):
        assert url == realtime.ENDPOINT
        assert kwargs['additional_headers']=={'x-goog-api-key':'TEST-NOT-A-REAL-KEY'}
        return upstream
    monkeypatch.setattr(realtime,'connect',connect)
    dev=db.issue('a','device')
    return db,TestClient(server.app),dev,upstream

@pytest.mark.parametrize('mode',['reception','translation'])
def test_gemini_audio_tools_captions_and_interrupt(relay_fixture,mode):
    db,client,dev,upstream=relay_fixture
    metadata={**VOICE,'mode':mode,'staff_language':'en' if mode=='translation' else 'es'}
    ticket=client.post('/api/device/session',headers={'Authorization':'Bearer '+dev['token']},json=metadata).json()['ticket']
    captions=[]; pcm=None; confirmation=None
    with client.websocket_connect('/ws/device',headers={'X-Orange-Ticket':ticket}) as ws:
        ready=ws.receive_json()
        assert ready=={'type':'ready','session_id':metadata['session_id'],'generation':7}
        ws.send_bytes(b'\x01\x00'*960)
        while True:
            event=ws.receive_json()
            assert event['session_id']==metadata['session_id'] and event['generation']==7
            if event['type']=='confirm':
                assert mode=='reception'
                confirmation=event
                ws.send_json({'type':'confirmation','id':event['id'],'accepted':True})
            elif event['type']=='caption': captions.append(event)
            elif event['type']=='audio_item': pcm=ws.receive_bytes()
            elif event['type']=='interrupt': break
        # A later transcription cannot be FIFO-paired with the preceding output.
        captions.append(ws.receive_json())
        ws.send_json({'type':'stop'})
    assert pcm==b'\x00\x00'*100
    assert len(db.rows('SELECT * FROM items')) == (1 if mode=='reception' else 0)
    assert upstream.result['status']==('saved' if mode=='reception' else 'denied')
    assert bool(confirmation)==(mode=='reception')
    config=upstream.events[0]['setup']
    assert bool(config.get('tools'))==(mode=='reception')
    assert 'TEST-NOT-A-REAL-KEY' not in json.dumps(config)
    originals=[c for c in captions if c.get('field')=='original']
    outputs=[c for c in captions if c.get('side')=='assistant']
    assert len(originals)==2 and originals[0]['utterance_id']!=originals[1]['utterance_id']
    assert outputs[0]['utterance_id']==outputs[1]['utterance_id']
    assert outputs[0]['operation']=='append' and outputs[1]['operation']=='replace' and outputs[1]['final']
    assert all(c['utterance_id']!=outputs[0]['utterance_id'] for c in originals)


def test_invalid_pcm_is_rejected_before_provider(relay_fixture):
    db,client,dev,upstream=relay_fixture
    ticket=client.post('/api/device/session',headers={'Authorization':'Bearer '+dev['token']},json=VOICE).json()['ticket']
    with client.websocket_connect('/ws/device',headers={'X-Orange-Ticket':ticket}) as ws:
        assert ws.receive_json()['type']=='ready'
        ws.send_bytes(b'odd')
        assert ws.receive_json()['type']=='error'
    assert not any('realtimeInput' in e for e in upstream.events)
    assert db.rows('SELECT * FROM sessions')[0]['status']=='ended'
