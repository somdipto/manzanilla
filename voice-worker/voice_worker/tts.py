"""Fish streaming text-to-speech client (WebSocket /v1/tts/live).

One websocket per utterance: send a start event, stream text clauses, flush,
stop; receive raw PCM audio chunks as they are generated, so playback starts on
the first clause instead of the completed paragraph.

Protocol: https://docs.fish.audio/api-reference/endpoint/websocket/tts-live
"""
import asyncio
import logging
import time

import msgpack
import websockets

ENDPOINT = 'wss://api.fish.audio/v1/tts/live'
MODEL = 's2.1-pro'
LOG = logging.getLogger('voice_worker.tts')


class FishTTS:
    def __init__(self, api_key: str, voice_id: str, sample_rate: int = 24000, endpoint: str = ENDPOINT,
                 *, language: str):
        if not api_key:
            raise ValueError('Fish API key is required for streaming synthesis')
        if not voice_id:
            raise ValueError('A configured voice is required for streaming synthesis')
        self._key = api_key
        self._voice = voice_id
        self._rate = sample_rate
        self._language = language
        self._endpoint = endpoint
        self._ws = None
        self._reader_task: asyncio.Task | None = None
        self.error = False
        self.audio: asyncio.Queue = asyncio.Queue(maxsize=16)
        self.first_audio_at: float | None = None

    async def __aenter__(self):
        await self.connect()
        return self

    async def __aexit__(self, *exc):
        await self.close()

    async def connect(self):
        self._ws = await websockets.connect(
            self._endpoint,
            additional_headers={'Authorization': 'Bearer ' + self._key, 'model': MODEL},
            open_timeout=15, close_timeout=1, max_size=2**20, max_queue=8)
        await self._send({'event': 'start', 'request': {
            'text': '', 'reference_id': self._voice, 'format': 'pcm',
            'sample_rate': self._rate, 'chunk_length': 120, 'normalize': True, 'latency': 'normal',
            'language': self._language,
            'prosody': {'speed': 1.0, 'volume': 0, 'normalize_loudness': True}}})
        self._reader_task = asyncio.create_task(self._reader())
        return self

    async def _send(self, message: dict):
        if self._ws is not None:
            await self._ws.send(msgpack.packb(message, use_bin_type=True))

    async def send_text(self, text: str):
        if text:
            await self._send({'event': 'text', 'text': text})

    async def flush(self):
        await self._send({'event': 'flush'})

    async def finish(self):
        await self._send({'event': 'stop'})

    async def _reader(self):
        finished = False
        try:
            async for frame in self._ws:
                try:
                    if not isinstance(frame, bytes):
                        raise ValueError
                    event = msgpack.unpackb(frame, raw=False)
                    if not isinstance(event, dict):
                        raise ValueError
                except (ValueError, TypeError, msgpack.UnpackException):
                    self.error = True
                    break
                kind = event.get('event')
                if kind == 'audio':
                    if not isinstance(event.get('audio'), bytes):
                        self.error = True
                        break
                    if self.first_audio_at is None:
                        self.first_audio_at = time.time()
                    await self.audio.put(event['audio'])
                elif kind == 'finish':
                    self.error = event.get('reason') != 'stop'
                    finished = True
                    break
                elif kind == 'error':
                    # Provider payloads can contain utterance text or credentials.
                    LOG.warning('synthesis returned an error')
                    self.error = True
                    break
        except websockets.ConnectionClosed:
            pass
        finally:
            # A provider that disappears mid-utterance must not look like a
            # complete translation merely because some audio arrived first.
            if not finished and self._ws is not None:
                self.error = True
            if not asyncio.current_task().cancelling():
                await self.audio.put(None)

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
        # Unblock any consumer still waiting on audio.
        try:
            self.audio.put_nowait(None)
        except asyncio.QueueFull:
            pass
