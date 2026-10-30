"""Fish realtime speech-to-text client (transcribe-stream-1, Preview).

One websocket per interpreter session. Streams 16 kHz PCM16 mono upstream and
yields server events: speech_start, draft, committed, backchannel and errors.
The provider performs per-turn language identification and turn detection.
agent_speaking informs backchannel classification; it is not acoustic echo
cancellation. The client must continuously supply its AEC-cleaned microphone.

Protocol: https://docs.fish.audio/api-reference/endpoint/websocket/asr-realtime
"""
import asyncio

import msgpack
import websockets

ENDPOINT = 'wss://api.fish.audio/v1/asr/realtime'
MODEL = 'transcribe-stream-1'
START_TIMEOUT_SECONDS = 15



class FishASR:
    def __init__(self, api_key: str, endpoint: str = ENDPOINT):
        if not api_key:
            raise ValueError('Fish API key is required for realtime speech recognition')
        self._key = api_key
        self._endpoint = endpoint
        self._ws = None
        self._reader_task: asyncio.Task | None = None
        self.events: asyncio.Queue = asyncio.Queue(maxsize=128)
        self.session_id: str | None = None
        self.started: dict | None = None

    async def __aenter__(self):
        await self.connect()
        return self

    async def __aexit__(self, *exc):
        await self.close()

    async def connect(self):
        self._ws = await websockets.connect(
            self._endpoint,
            additional_headers={'Authorization': 'Bearer ' + self._key, 'model': MODEL},
            open_timeout=15, close_timeout=1, max_size=2**20, max_queue=16)
        try:
            await self._send({'type': 'config',
                              'audio': {'format': 'pcm_s16le', 'sample_rate': 16000, 'channels': 1},
                              'turn_detection': {'mode': 'auto', 'eager': False},
                              'results': {'drafts': True, 'diarize': False, 'ignore_timestamps': False}})
            self._reader_task = asyncio.create_task(self._reader())
            async with asyncio.timeout(START_TIMEOUT_SECONDS):
                while True:
                    event = await self.events.get()
                    if event.get('type') == 'session_started':
                        effective = event.get('config', {})
                        if (not isinstance(event.get('session_id'), str) or not event['session_id']
                                or effective.get('audio') != {'format': 'pcm_s16le', 'sample_rate': 16000, 'channels': 1}
                                or effective.get('turn_detection', {}).get('mode') != 'auto'
                                or effective.get('turn_detection', {}).get('eager') is not False
                                or effective.get('results', {}).get('drafts') is not True
                                or effective.get('results', {}).get('diarize') is not False
                                or effective.get('results', {}).get('ignore_timestamps') is not False):
                            raise RuntimeError('Speech recognition configuration rejected')
                        self.session_id = event['session_id']
                        self.started = event
                        return self
                    if event.get('type') in ('error', 'closed'):
                        raise RuntimeError('speech recognition closed or rejected the session')
        except BaseException:
            await self.close()
            raise

    async def _send(self, message: dict):
        if self._ws is not None:
            await self._ws.send(msgpack.packb(message, use_bin_type=True))

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
                    if not isinstance(frame, bytes):
                        raise ValueError
                    event = msgpack.unpackb(frame, raw=False)
                    if not isinstance(event, dict) or not isinstance(event.get('type'), str):
                        raise ValueError
                except (ValueError, TypeError, msgpack.UnpackException):
                    await self.events.put({'type': 'error', 'error_type': 'invalid_protocol'})
                    return
                await self.events.put(event)
        except websockets.ConnectionClosed:
            pass
        finally:
            if not asyncio.current_task().cancelling():
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
