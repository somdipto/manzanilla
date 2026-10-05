"""Actual cloud + Edge subprocesses connected via trusted test TLS; no model/provider."""
import json, os, socket, ssl, subprocess, sys, time
from pathlib import Path
import httpx

def test_outbound_edge_over_tls(tmp_path):
    root=Path(__file__).resolve().parents[1]
    cert=tmp_path/'cert.pem';key=tmp_path/'key.pem'
    subprocess.run(['openssl','req','-x509','-newkey','rsa:2048','-nodes','-days','1',
                    '-subj','/CN=localhost','-addext','subjectAltName=DNS:localhost,IP:127.0.0.1',
                    '-keyout',str(key),'-out',str(cert)],check=True,capture_output=True)
    with socket.socket() as sock: sock.bind(('127.0.0.1',0));port=sock.getsockname()[1]
    env={**os.environ,'ORANGE_DB':str(tmp_path/'cloud.db'),'ORANGE_ADMIN_TOKEN':'x'*40}
    cloud=subprocess.Popen([sys.executable,'-m','uvicorn','cloud.app:app','--host','127.0.0.1','--port',str(port),'--ssl-keyfile',str(key),'--ssl-certfile',str(cert)],cwd=root,env=env,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    edge=None
    try:
        with httpx.Client(base_url=f'https://localhost:{port}',verify=ssl.create_default_context(cafile=str(cert)),trust_env=False,timeout=3) as client:
            for _ in range(50):
                try:
                    if client.get('/health').status_code==200: break
                except httpx.HTTPError: time.sleep(.1)
            else: raise AssertionError('Cloud did not start')
            headers={'Authorization':'Bearer '+'x'*40}
            assert client.put('/api/hotels/a',headers=headers,json={'id':'a','name':'A','connector':'edge'}).status_code==200
            token=client.post('/api/hotels/a/credentials',headers=headers,json={'role':'edge'}).json()['token']
            records=tmp_path/'reservations.json';records.write_text(json.dumps([{'reference':'ABC','surname':'Smith','id':'1','guest_name':'Alex','room_number':'204','eligible':True,'status':'arrival'}]))
            edgeenv={**env,'SSL_CERT_FILE':str(cert),'ORANGE_EDGE_URL':f'wss://localhost:{port}/ws/edge','ORANGE_EDGE_TOKEN':token,'ORANGE_RESERVATION_FILE':str(records)}
            edge=subprocess.Popen([sys.executable,'-m','edge.executor'],cwd=root,env=edgeenv,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
            for _ in range(50):
                if client.get('/api/hotels/a/overview',headers=headers).json()['integrations']['edge']: break
                time.sleep(.1)
            else: raise AssertionError('Edge did not connect')
            result=client.post('/api/hotels/a/edge-check',headers=headers,json={'reference':'ABC','surname':'Smith'}).json()
            assert result['status']=='ok' and result['booking']['id']=='1'
            assert client.post('/api/hotels/a/edge-check',headers=headers,json={'reference':'ABC','surname':'wrong'}).json()['booking'] is None
            edge.terminate();edge.wait(timeout=5);edge=None
            for _ in range(30):
                if not client.get('/api/hotels/a/overview',headers=headers).json()['integrations']['edge']: break
                time.sleep(.1)
            assert client.post('/api/hotels/a/edge-check',headers=headers,json={'reference':'ABC','surname':'Smith'}).json()['status']=='unavailable'
    finally:
        if edge: edge.terminate();edge.wait(timeout=5)
        cloud.terminate();cloud.wait(timeout=5)
