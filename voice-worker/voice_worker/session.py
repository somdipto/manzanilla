"""One interpreter session inside one LiveKit room.

Pipeline: room microphone -> Fish realtime recognition (turn detection and
per-turn language identification on the provider side) -> streaming translation
-> Fish streaming synthesis -> room playback, first clause first. A user speech
start during playback cancels generation and flushes queued audio, then the new
utterance is heard in full.

The session emits exactly the wire contract the reception client renders:
lk.agent.state participant attributes and display-only caption reports on the
agent-event data topic. Raw lk.transcription streams are agent-side timing
signals only; identified captions never depend on them.
"""
import asyncio
import json
import logging
import time

from livekit import api, rtc

from . import contract
from .asr import FishASR
from .llm import TranslateStream
from .prompt import MAX_SECONDS, interpreter_prompt
from .tts import FishTTS

LOG = logging.getLogger('voice_worker.session')

CLAUSE_END = tuple('.!?;:\n。！？；：')
MIN_CLAUSE = 12
PLAYBACK_QUEUE_MS = 200
TABLET_WAIT_SECONDS = 15


def pop_clause(buffer: str) -> str | None:
    """Longest complete clause worth synthesizing, else None."""
    for index, char in enumerate(buffer):
        if char in CLAUSE_END and index + 1 >= MIN_CLAUSE:
            return buffer[:index + 1]
    return None


class Bench:
    """JSONL timing sink; one line per pipeline boundary, wall-clock stamps."""
    def __init__(self, path: str | None, session: str):
        self._file = open(path, 'a', buffering=1) if path else None
        self._session = session

    def mark(self, event: str, **fields):
        if self._file:
            self._file.write(json.dumps({'ts': time.time(), 'session': self._session,
                                         'event': event, **fields}) + '\n')

    def close(self):
        if self._file:
            self._file.close()


class InterpreterSession:
    def __init__(self, room_name: str, *, livekit_url: str, api_key: str, api_secret: str,
                 fish_key: str, voice_id: str, llm: TranslateStream, greetings: dict,
                 bench: Bench | None = None, max_seconds: int = MAX_SECONDS):
        self.room_name = room_name
        self._url = livekit_url
        self._grant = (api_key, api_secret)
        self._fish_key = fish_key
        self._voice = voice_id
        self._llm = llm
        self._greetings = greetings
        self._max_seconds = max_seconds
        self.bench = bench or Bench(None, room_name)
        self.room = rtc.Room()
        self.source: rtc.AudioSource | None = None
        self.asr: FishASR | None = None
        self.guest = ''
        self.staff = ''
        self.speaking = False
        self.turn: asyncio.Task | None = None
        self.turns: asyncio.Queue = asyncio.Queue()
        self.turn_count = 0
        self.deadline = time.monotonic() + max_seconds
        self.ended = asyncio.Event()
        self.agent_identity = 'agent-' + room_name[:40]

    async def run(self):
        try:
            await self._connect()
            await self._wait_tablet()
            await self._open_asr()
            await self._greet()
            await asyncio.wait_for(self._main(), timeout=self._max_seconds + 60)
        except asyncio.TimeoutError:
            await self._send(contract.session_ended('conversation_timeout'))
        except Exception:
            LOG.exception('interpreter session failed')
            try:
                await self._send(contract.error(recovered=False))
            except Exception:
                pass
        finally:
            await self._close()

    async def _connect(self):
        key, secret = self._grant
        token = (api.AccessToken(key, secret)
                 .with_identity(self.agent_identity)
                 .with_name('Manzanilla interpreter')
                 .with_grants(api.VideoGrants(room_join=True, room=self.room_name,
                                              can_publish=True, can_subscribe=True))
                 .to_jwt())
        await self.room.connect(self._url, token)
        self.source = rtc.AudioSource(48000, 1, queue_size_ms=PLAYBACK_QUEUE_MS)
        track = rtc.LocalAudioTrack.create_audio_track('agent-voice', self.source)
        await self.room.local_participant.publish_track(track)
        await self.room.local_participant.update_attributes(contract.state('listening'))
        self.bench.mark('room_connected')

    async def _wait_tablet(self):
        deadline = time.monotonic() + TABLET_WAIT_SECONDS
        while time.monotonic() < deadline and not self.ended.is_set():
            for participant in self.room.remote_participants.values():
                metadata = self._languages(participant)
                if metadata:
                    self.guest, self.staff = metadata
                    self.bench.mark('tablet_present', guest=self.guest, staff=self.staff)
                    return
            await asyncio.sleep(0.2)
        raise TimeoutError('no reception device joined with a language pair')

    @staticmethod
    def _languages(participant) -> tuple[str, str] | None:
        try:
            data = json.loads(participant.metadata or '{}')
        except ValueError:
            return None
        guest, staff = data.get('guest_language'), data.get('staff_language')
        if isinstance(guest, str) and isinstance(staff, str) and guest and staff and guest != staff:
            return guest, staff
        return None

    async def _open_asr(self):
        self.asr = FishASR(self._fish_key)
        await self.asr.connect()
        await self.asr.agent_speaking(False)
        self.bench.mark('asr_started')

    async def _greet(self):
        text = self._greetings.get(self.guest) or self._greetings.get('en', '')
        if not text:
            return
        await self._speak(text, caption=False, label='greeting')

    async def _main(self):
        mic = asyncio.create_task(self._pump_mic())
        turns = asyncio.create_task(self._turn_loop())
        watchdog = asyncio.create_task(self._watchdog())
        try:
            while not self.ended.is_set():
                event = await self.asr.events.get()
                kind = event.get('type')
                if kind == 'closed':
                    break
                if kind == 'speech_start':
                    self.bench.mark('asr_speech_start')
                    await self._interrupt()
                elif kind == 'committed':
                    committed = event
                    self.bench.mark('asr_committed', language=committed.get('language_code'),
                                    user_turn=committed.get('user_turn'))
                    if committed.get('user_turn') and committed.get('turn_kind') == 'user':
                        await self.turns.put(committed)
                    else:
                        self.bench.mark('turn_skipped_backchannel')
                elif kind == 'error':
                    raise RuntimeError('recognition error: ' + str(event.get('message')))
        finally:
            for task in (mic, turns, watchdog):
                task.cancel()
            if self.turn:
                self.turn.cancel()

    async def _watchdog(self):
        while not self.ended.is_set():
            if time.monotonic() >= self.deadline:
                await self._send(contract.session_ended('conversation_timeout'))
                self.ended.set()
                return
            if not self.room.remote_participants:
                self.ended.set()
                return
            await asyncio.sleep(1)

    async def _pump_mic(self):
        tracks = []
        for participant in self.room.remote_participants.values():
            for publication in participant.track_publications.values():
                if publication.track and publication.kind == rtc.TrackKind.KIND_AUDIO:
                    tracks.append(publication.track)
        if not tracks:
            event = asyncio.Event()
            @self.room.on('track_subscribed')
            def _on_sub(track, publication, participant):
                if track.kind == rtc.TrackKind.KIND_AUDIO:
                    tracks.append(track)
                    event.set()
            await event.wait()
        stream = rtc.AudioStream(tracks[0], sample_rate=16000, num_channels=1)
        async for frame_event in stream:
            if self.ended.is_set():
                break
            await self.asr.send_audio(bytes(frame_event.frame.data))

    async def _turn_loop(self):
        while not self.ended.is_set():
            committed = await self.turns.get()
            self.turn_count += 1
            turn = self.turn_count
            language = (committed.get('language_code') or '').lower()
            if language not in (self.guest, self.staff):
                self.bench.mark('turn_uncertain_language', language=language)
                await self._caption(turn, 'und', committed.get('text', ''), '')
                continue
            self.turn = asyncio.create_task(self._translate(turn, committed.get('text', ''), language))
            try:
                await self.turn
            except asyncio.CancelledError:
                self.bench.mark('turn_cancelled', turn=turn)
            finally:
                self.turn = None

    async def _translate(self, turn: int, text: str, language: str):
        if not text.strip():
            return
        await self.room.local_participant.update_attributes(contract.state('thinking'))
        self.bench.mark('llm_requested', turn=turn)
        clauses: asyncio.Queue[str | None] = asyncio.Queue()
        full: list[str] = []

        async def generate():
            buffer = ''
            async for delta in self._llm.stream(interpreter_prompt(self.guest, self.staff), text):
                buffer += delta
                full.append(delta)
                clause = pop_clause(buffer)
                if clause:
                    await clauses.put(clause)
                    buffer = buffer[len(clause):]
            if buffer.strip():
                await clauses.put(buffer)
            await clauses.put(None)

        producer = asyncio.create_task(generate())
        try:
            await self._speak_stream(clauses, turn)
            translation = ''.join(full).strip()
            await self._caption(turn, language, text, translation)
        finally:
            producer.cancel()
            try:
                await producer
            except (asyncio.CancelledError, Exception):
                pass

    async def _speak(self, text: str, *, caption: bool, label: str):
        queue: asyncio.Queue[str | None] = asyncio.Queue()
        await queue.put(text)
        await queue.put(None)
        await self._speak_stream(queue, 0, label=label)

    async def _speak_stream(self, clauses: asyncio.Queue, turn: int, label: str = 'turn'):
        self.speaking = True
        await self.room.local_participant.update_attributes(contract.state('speaking'))
        await self.asr.agent_speaking(True)
        tts = FishTTS(self._fish_key, self._voice)
        await tts.connect()
        async def feed():
            while True:
                clause = await clauses.get()
                if clause is None:
                    break
                await tts.send_text(clause)
            await tts.flush()
            await tts.finish()
        feeder = asyncio.create_task(feed())
        first = True
        try:
            while True:
                chunk = await tts.audio.get()
                if chunk is None:
                    break
                if first:
                    first = False
                    self.bench.mark('first_audio_published', turn=turn, label=label)
                for offset in range(0, len(chunk), 960 * 2):
                    piece = chunk[offset:offset + 960 * 2]
                    if len(piece) < 960 * 2:
                        piece = piece + b'\x00' * (960 * 2 - len(piece))
                    frame = rtc.AudioFrame(data=piece, sample_rate=48000, num_channels=1,
                                           samples_per_channel=960)
                    await self.source.capture_frame(frame)
        finally:
            feeder.cancel()
            await tts.close()
            self.speaking = False
            await self.asr.agent_speaking(False)
            if not self.ended.is_set():
                await self.room.local_participant.update_attributes(contract.state('listening'))

    async def _interrupt(self):
        if not self.speaking:
            return
        self.bench.mark('interrupted')
        if self.turn:
            self.turn.cancel()
        if self.source:
            self.source.clear_queue()
            self.bench.mark('playback_flushed')
        self.speaking = False
        await self.asr.agent_speaking(False)
        await self.room.local_participant.update_attributes(contract.state('listening'))

    async def _caption(self, turn: int, language: str, original: str, translation: str):
        call_id = f'lk-{self.room_name[-24:]}-{turn}'
        await self._send(contract.caption(call_id, language, original, translation))
        self.bench.mark('caption_sent', turn=turn, language=language)

    async def _send(self, payload: bytes):
        await self.room.local_participant.publish_data(payload, reliable=True,
                                                       topic=contract.EVENT_TOPIC)

    async def _close(self):
        self.ended.set()
        if self.asr:
            await self.asr.close()
        try:
            await self.room.disconnect()
        except Exception:
            pass
        self.bench.mark('session_closed')
        self.bench.close()
