"""Fish realtime speech-to-text client (transcribe-stream-1, Preview).

One websocket per interpreter session. Streams 16 kHz PCM16 mono upstream and
yields server events: speech_start, draft, committed, backchannel and errors.
The provider performs per-turn language identification and turn detection; this
client never guesses either. `agent_speaking` marks our own playback windows so
the provider's echo gate can ignore our own synthesized voice.

Protocol: https://docs.fish.audio/api-reference/endpoint/websocket/asr-realtime
"""
import asyncio
import json
import logging
import time
from dataclasses import dataclass, field

import msgpack
import websockets

ENDPOINT = 'wss://api.fish.audio/v1/asr/realtime'
MODEL = 'transcribe-stream-1'
START_TIMEOUT_SECONDS = 15
LOG = logging.getLogger('voice_worker.asr')


@dataclass
class Committed:
    text: str
    language_code: str
    user_turn: bool
    turn_kind: str
    turn_index: int
    audio_end_ms: int
    received_at: float = field(default_factory=time.time)


class FishASR:
    def __init__(self, api_key: str, endpoint: str = ENDPOINT):
        if not api_key:
            raise ValueError('Fish API key is required for realtime speech recognition')
        self._key = api_key
        self._endpoint = endpoint
        self._ws = None
        self._reader_task: asyncio.Task | None = None
        self.events: asyncio.Queue = asyncio.Queue()
        self.session_id: str | None = None

    async def __aenter__(self):
        await self.connect()
        return self

    async def __aexit__(self, *exc):
        await self.close()

    async def connect(self):
        self._ws = await websockets.connect(
            self._endpoint,
            additional_headers={'Authorization': 'Bearer ' + self._key, 'model': MODEL},
            open_timeout=15, max_size=2**20)
        try:
            await self._send({'type': 'config',
                              'audio': {'format': 'pcm_s16le', 'sample_rate': 16000, 'channels': 1},
                              'turn_detection': {'mode': 'auto', 'eager': True},
                              'results': {'drafts': False, 'vad_events': False}})
            self._reader_task = asyncio.create_task(self._reader())
            async with asyncio.timeout(START_TIMEOUT_SECONDS):
                while True:
                    event = await self.events.get()
                    if event.get('type') == 'session_started':
                        self.session_id = event.get('session_id')
                        return self
                    if event.get('type') in ('error', 'closed'):
                        raise RuntimeError('speech recognition closed or rejected the session')
        except BaseException:
            await self.close()
            raise

    async def _send(self, message: dict):
        if self._ws is not None:
            await self._ws.send(msgpack.packb(message))

    async def send_audio(self, pcm_s16le_16k: bytes):
        await self._send({'type': 'audio', 'data': pcm_s16le_16k})

    async def agent_speaking(self, speaking: bool):
        await self._send({'type': 'agent_speaking', 'speaking': speaking})

    async def finish(self):
        await self._send({'type': 'finalize'})

    async def _reader(self):
        try:
            async for frame in self._ws:
                try:
                    event = msgpack.unpackb(frame, raw=False)
                except Exception:
                    continue
                await self.events.put(event)
        except websockets.ConnectionClosed:
            pass
        finally:
            await self.events.put({'type': 'closed'})

    async def close(self):
        ws, self._ws = self._ws, None
        if ws is not None:
            try:
                await ws.close()
            except Exception:
                pass
        task, self._reader_task = self._reader_task, None
        if task:
            task.cancel()
            await asyncio.gather(task, return_exceptions=True)
