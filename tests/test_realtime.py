import asyncio, base64, json
from fastapi.testclient import TestClient
from cloud.db import Store
from cloud.models import Hotel
import cloud.app as server
import cloud.realtime as realtime

class Upstream:
    def __init__(self): self.events=[]; self.queue=asyncio.Queue()
    async def send(self, raw):
        event=json.loads(raw);self.events.append(event)
        if event['type']=='session.update':
            await self.queue.put({'type':'session.updated'})
            await self.queue.put({'type':'response.created'})
            await self.queue.put({'type':'response.function_call_arguments.done','name':'leave_reception_note','call_id':'note-1', 'arguments':json.dumps({'original_request':'Extend my stay','summary':'Discuss extension'})})
            await self.queue.put({'type':'response.done'})
        if event['type']=='response.create':
            await self.queue.put({'type':'response.created'})
            await self.queue.put({'type':'response.output_audio.delta','item_id':'item-1','delta':base64.b64encode(b'\x00\x00'*100).decode()})
            await self.queue.put({'type':'input_audio_buffer.speech_started'})
            await self.queue.put({'type':'response.done'})
    def __aiter__(self): return self
    async def __anext__(self): return json.dumps(await self.queue.get())
    async def __aenter__(self): return self
    async def __aexit__(self,*args): pass

def test_realtime_audio_confirmation_and_interruption(tmp_path,monkeypatch):
    db=Store(str(tmp_path/'db'));db.save_hotel(Hotel(id='a',name='A').model_dump())
    monkeypatch.setattr(server,'store',db)
    monkeypatch.setenv('OPENAI_API_KEY','TEST-NOT-A-REAL-KEY')
    upstream=Upstream()
    monkeypatch.setattr(realtime,'connect',lambda *args,**kwargs:upstream)
    dev=db.issue('a','device');client=TestClient(server.app)
    ticket=client.post('/api/device/session',headers={'Authorization':'Bearer '+dev['token']}).json()['ticket']
    with client.websocket_connect('/ws/device',headers={'X-Orange-Ticket':ticket}) as ws:
        assert ws.receive_json()['type']=='ready'
        first=ws.receive_json()
        if first['type']=='state': confirm=ws.receive_json(); completed=True
        else: confirm=first; completed=False
        assert confirm['type']=='confirm'
        ws.send_bytes(b'\x00\x00'*960)
        ws.send_json({'type':'confirmation','id':confirm['id'],'accepted':True})
        if not completed: assert ws.receive_json()['type']=='state'
        assert ws.receive_json()['type']=='audio_item'
        assert ws.receive_bytes()==b'\x00\x00'*100
        assert ws.receive_json()['type']=='interrupt'
        ws.send_json({'type':'played','item_id':'item-1','ms':2})
        assert ws.receive_json()['type']=='state'
        ws.send_json({'type':'stop'})
    assert len(db.rows('SELECT * FROM items'))==1
    assert any(e['type']=='input_audio_buffer.append' for e in upstream.events)
    assert any(e['type']=='conversation.item.truncate' for e in upstream.events)
    output=next(e for e in upstream.events if e['type']=='conversation.item.create')
    assert json.loads(output['item']['output'])['status']=='saved'
    config=upstream.events[0]['session']
    assert config['audio']['input']['format']['rate']==24000
    assert 'TEST-NOT-A-REAL-KEY' not in json.dumps(config)
