"""Explicitly enabled managed-room worker; local web owns sessions directly.

A room is attempted once. Reconnecting an ASR session or replaying its welcome
requires a deliberate new Start, never automatic paid-provider recovery.
"""
import asyncio
import logging
import os

from livekit import api

from .llm import TranslateStream
from .session import Bench, InterpreterSession

LOG = logging.getLogger('voice_worker')
DISCOVERY_INTERVAL = 1.5
SYNTHETIC_ROOM_PREFIX = 'session-synthetic-'
GEMINI_BASE = 'https://generativelanguage.googleapis.com/v1beta/openai'
GEMINI_MODEL = 'gemini-3.8-flash'


def config_from_env(env=os.environ) -> dict:
    if env.get('LIVEKIT_WORKER_ENABLED') != '1':
        raise SystemExit('Managed worker requires LIVEKIT_WORKER_ENABLED=1')
    missing = [name for name in ('LIVEKIT_URL', 'LIVEKIT_API_KEY', 'LIVEKIT_API_SECRET',
                                 'FISH_API_KEY', 'FISH_VOICE_ID', 'GEMINI_API_KEY')
               if not env.get(name)]
    if missing:
        raise SystemExit('missing environment: ' + ', '.join(missing))
    return {
        'livekit_url': env['LIVEKIT_URL'], 'api_key': env['LIVEKIT_API_KEY'],
        'api_secret': env['LIVEKIT_API_SECRET'], 'fish_key': env['FISH_API_KEY'],
        'voice_id': env['FISH_VOICE_ID'],
        'llm': TranslateStream(GEMINI_BASE, env['GEMINI_API_KEY'], GEMINI_MODEL),
        'prefix': env.get('ROOM_PREFIX', 'manz-'),
        'bench_log': env.get('VOICE_BENCH_LOG') or None,
        'max_seconds': min(int(env.get('MAX_SESSION_SECONDS', '600')), 600),
    }


async def serve_forever(config: dict):
    lk = api.LiveKitAPI(config['livekit_url'], config['api_key'], config['api_secret'])
    active = {}
    attempted = set()
    try:
        while True:
            try:
                rooms = await lk.room.list_rooms(api.ListRoomsRequest())
                names = {room.name for room in rooms.rooms if room.name.startswith(config['prefix'])
                         and not room.name.startswith(SYNTHETIC_ROOM_PREFIX)}
                for name, task in list(active.items()):
                    if name not in names:
                        task.cancel()
                    if task.done():
                        await asyncio.gather(task, return_exceptions=True)
                        active.pop(name)
                attempted.intersection_update(names | set(active))
                for name in sorted(names - attempted):
                    session = InterpreterSession(name,
                        livekit_url=config['livekit_url'], api_key=config['api_key'],
                        api_secret=config['api_secret'], fish_key=config['fish_key'],
                        voice_id=config['voice_id'], llm=config['llm'],
                        bench=Bench(config['bench_log'], name), max_seconds=config['max_seconds'],
                        room_prefix=config['prefix'])
                    attempted.add(name)
                    active[name] = asyncio.create_task(session.run(), name='controlled-interpreter')
            except asyncio.CancelledError:
                raise
            except Exception:
                LOG.warning('managed room discovery unavailable')
            await asyncio.sleep(DISCOVERY_INTERVAL)
    finally:
        for task in active.values():
            task.cancel()
        await asyncio.gather(*active.values(), return_exceptions=True)
        await lk.aclose()


def main():
    logging.basicConfig(level=logging.INFO, format='%(asctime)s %(levelname)s %(name)s %(message)s')
    asyncio.run(serve_forever(config_from_env()))
