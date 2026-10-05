"""Outbound read-only executor. No shell, arbitrary file paths, or browser tool."""
import asyncio, json, os
from pathlib import Path
from websockets.asyncio.client import connect

class Executor:
    def __init__(self, reservation_file):
        self.path=Path(reservation_file).resolve()
    def execute(self, capability, args):
        if capability!='read_reservation': return {'status':'denied'}
        if set(args)!={'reference','surname'} or any(not isinstance(v,str) or len(v)>100 for v in args.values()): return {'status':'invalid_arguments'}
        try:
            # Exactly one owner-configured file; never a model-selected path.
            if self.path.stat().st_size>5_000_000: return {'status':'failed'}
            data=json.loads(self.path.read_text())
            for row in data:
                if row['reference']==args['reference'] and row['surname'].casefold()==args['surname'].casefold():
                    if not isinstance(row.get('eligible'),bool): return {'status':'failed'}
                    return {'status':'ok','booking':{k:row[k] for k in ('id','guest_name','room_number','eligible','status')}}
            return {'status':'ok','booking':None}
        except (OSError,ValueError,KeyError,TypeError): return {'status':'unavailable'}

async def run():
    url=os.environ['ORANGE_EDGE_URL']
    if not url.startswith('wss://'): raise ValueError('Edge requires WSS')
    token=os.environ['ORANGE_EDGE_TOKEN']
    executor=Executor(os.environ['ORANGE_RESERVATION_FILE'])
    delay=1
    while True:
        try:
            async with connect(url,additional_headers={'Authorization':'Bearer '+token},max_size=65536,open_timeout=15) as ws:
                delay=1
                async def heartbeat():
                    while True:
                        await ws.send(json.dumps({'type':'heartbeat'})); await asyncio.sleep(25)
                task=asyncio.create_task(heartbeat())
                try:
                    async for raw in ws:
                        msg=json.loads(raw)
                        if msg.get('type')=='execute':
                            result=executor.execute(msg.get('capability'),msg.get('args',{}))
                            await ws.send(json.dumps({'type':'result','id':msg['id'],'result':result}))
                finally:
                    task.cancel(); await asyncio.gather(task,return_exceptions=True)
        except (OSError,ValueError,TimeoutError): pass
        except Exception: pass
        await asyncio.sleep(delay); delay=min(delay*2,30)
if __name__=='__main__': asyncio.run(run())
