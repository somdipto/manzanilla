import asyncio
import pytest
from fastapi.testclient import TestClient
from starlette.websockets import WebSocketDisconnect
from cloud.db import Store
from cloud.models import Hotel
import cloud.app as server

@pytest.fixture
def edge_api(tmp_path,monkeypatch):
    store=Store(str(tmp_path/'edge.db'))
    for hid in ('a','b'): store.save_hotel(Hotel(id=hid,name=hid).model_dump())
    monkeypatch.setattr(server,'store',store)
    monkeypatch.setattr(server,'edge_connections',{})
    monkeypatch.setattr(server,'edge_pending',{})
    monkeypatch.setenv('ORANGE_ADMIN_TOKEN','x'*40)
    return store,TestClient(server.app)

class Socket:
    def __init__(self,credential):
        self.headers={'authorization':'Bearer '+credential['token']}
        self.sent=[];self.closed=[];self.dispatched=asyncio.Event()
    async def send_json(self,value):
        self.sent.append(value);self.dispatched.set()
    async def close(self,code): self.closed.append(code)


def test_revoke_detaches_exact_edge_and_resolves_pending_without_new_work(edge_api):
    store,_=edge_api
    revoked=store.issue('a','edge');other=store.issue('b','edge')
    async def scenario():
        ws=Socket(revoked);other_ws=Socket(other)
        connection={'credential':store.auth(revoked['token'],'edge'),'ws':ws}
        other_connection={'credential':store.auth(other['token'],'edge'),'ws':other_ws}
        server.edge_connections.update(a=connection,b=other_connection)
        pending=asyncio.create_task(server.edge_execute('a','read_reservation',{}))
        await ws.dispatched.wait()
        assert await server.revoke(revoked['id'])=={'status':'revoked'}
        assert await pending=={'status':'unavailable'}
        assert 'a' not in server.edge_connections and server.edge_connections['b'] is other_connection
        assert ws.closed==[4401] and other_ws.closed==[]
        assert await server.edge_execute('a','read_reservation',{})=={'status':'unavailable'}
        assert len(ws.sent)==1 and not server.edge_pending
    asyncio.run(scenario())


def test_dispatch_rechecks_credential_before_sending(edge_api):
    store,_=edge_api;credential=store.issue('a','edge')
    async def scenario():
        ws=Socket(credential)
        server.edge_connections['a']={'credential':store.auth(credential['token'],'edge'),'ws':ws}
        store.run('DELETE FROM credentials WHERE id=?',(credential['id'],))
        assert await server.edge_execute('a','read_reservation',{})=={'status':'unavailable'}
        assert ws.sent==[] and 'a' not in server.edge_connections
    asyncio.run(scenario())


def test_revoke_closes_live_edge_socket_immediately(edge_api):
    store,client=edge_api;credential=store.issue('a','edge')
    with client.websocket_connect('/ws/edge',headers={'Authorization':'Bearer '+credential['token']}) as ws:
        ws.send_json({'type':'heartbeat'})
        assert ws.receive_json()=={'type':'pong'}
        response=client.delete('/api/credentials/'+credential['id'],headers={'Authorization':'Bearer '+'x'*40})
        assert response.status_code==200 and 'a' not in server.edge_connections
        with pytest.raises(WebSocketDisconnect) as closed:
            ws.receive_json()
        assert closed.value.code==4401
