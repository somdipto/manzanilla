"""Worker entry: discover manzanilla rooms on a LiveKit deployment and run one
interpreter session per room.

Rooms are discovered by polling the server room list; a room whose name starts
with the configured prefix gets an interpreter agent as soon as it exists. One
process can serve several concurrent rooms; each session owns its own provider
connections so a failure tears down one room, never the worker.
"""
import asyncio
import logging
import os

from livekit import api

from .llm import TranslateStream
from .session import Bench, InterpreterSession

LOG = logging.getLogger('voice_worker')


def config_from_env(env=os.environ) -> dict:
    missing = [name for name in ('LIVEKIT_URL', 'LIVEKIT_API_KEY', 'LIVEKIT_API_SECRET',
                                 'FISH_API_KEY', 'FISH_VOICE_ID', 'LLM_BASE_URL', 'LLM_MODEL')
               if not env.get(name)]
    if missing:
        raise SystemExit('missing environment: ' + ', '.join(missing))
    import json, pathlib
    greetings_path = pathlib.Path(env.get('GREETINGS_FILE',
        str(pathlib.Path(__file__).resolve().parent.parent.parent / 'cloud' / 'fish_greetings.json')))
    greetings = json.loads(greetings_path.read_text()) if greetings_path.exists() else {}
    return {
        'livekit_url': env['LIVEKIT_URL'],
        'api_key': env['LIVEKIT_API_KEY'],
        'api_secret': env['LIVEKIT_API_SECRET'],
        'fish_key': env['FISH_API_KEY'],
        'voice_id': env['FISH_VOICE_ID'],
        'llm': TranslateStream(env['LLM_BASE_URL'], env.get('LLM_API_KEY', ''), env['LLM_MODEL'],
                            extra=json.loads(env.get('LLM_EXTRA_JSON', '{}'))),
        'greetings': greetings,
        'prefix': env.get('ROOM_PREFIX', 'manz-'),
        'bench_log': env.get('VOICE_BENCH_LOG') or None,
        'max_seconds': int(env.get('MAX_SESSION_SECONDS', '600')),
    }


async def serve_forever(config: dict):
    lk = api.LiveKitAPI(config['livekit_url'], config['api_key'], config['api_secret'])
    active: dict[str, asyncio.Task] = {}
    LOG.info('worker online at %s, watching prefix %r', config['livekit_url'], config['prefix'])
    while True:
        try:
            rooms = await lk.room.list_rooms(api.ListRoomsRequest())
            names = {room.name for room in rooms.rooms if room.name.startswith(config['prefix'])}
            for name in sorted(names - set(active)):
                bench = Bench(config['bench_log'], name)
                session = InterpreterSession(
                    name, livekit_url=config['livekit_url'], api_key=config['api_key'],
                    api_secret=config['api_secret'], fish_key=config['fish_key'],
                    voice_id=config['voice_id'], llm=config['llm'],
                    greetings=config['greetings'], bench=bench,
                    max_seconds=config['max_seconds'])
                active[name] = asyncio.create_task(session.run(), name=f'session:{name}')
                LOG.info('session started for room %s', name)
            for name in sorted(set(active) - names):
                task = active[name]
                if task.done():
                    del active[name]
            for name in [n for n, task in active.items() if task.done()]:
                active.pop(name)
        except asyncio.CancelledError:
            raise
        except Exception:
            LOG.exception('room discovery failed; retrying')
        await asyncio.sleep(1.5)


def main():
    logging.basicConfig(level=logging.INFO,
                        format='%(asctime)s %(levelname)s %(name)s %(message)s')
    asyncio.run(serve_forever(config_from_env()))
