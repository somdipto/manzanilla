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
import audioop
import json
import re
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
# transcribe-stream-1 may embed its own diarization/control tokens in committed
# text; they are markup, never spoken language, so they never reach the model,
# the voice or the caption.
CONTROL_TOKEN = re.compile(r'<\|[^|]*\|>')
MIN_CLAUSE = 12
PLAYBACK_QUEUE_MS = 200
TABLET_WAIT_SECONDS = 15
FIRST_AUDIO_PAUSE_SECONDS = 2.0


async def _wait_for_gate_pause(delay: float):
    await asyncio.sleep(delay)


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
        self._turn_lock = asyncio.Lock()
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
            try:
                await asyncio.wait_for(self._main(), timeout=self._max_seconds + 60)
            except asyncio.TimeoutError:
                await self._send(contract.session_ended('conversation_timeout'))
            return True
        except Exception:
            LOG.exception('interpreter session failed')
            try:
                await self._send(contract.error(recovered=False))
            except Exception:
                pass
            return False
        finally:
            await self._close()

    async def _set_state(self, value: str):
        await self.room.local_participant.set_attributes(contract.state(value))
        self.bench.mark('agent_state', state=value)

    async def _connect(self):
        key, secret = self._grant
        token = (api.AccessToken(key, secret)
                 .with_identity(self.agent_identity)
                 .with_name('Manzanilla interpreter')
                 .with_grants(api.VideoGrants(room_join=True, room=self.room_name,
                                              can_publish=True, can_subscribe=True,
                                              can_update_own_metadata=True))
                 .to_jwt())
        await self.room.connect(self._url, token)
        self.source = rtc.AudioSource(48000, 1, queue_size_ms=PLAYBACK_QUEUE_MS)
        track = rtc.LocalAudioTrack.create_audio_track('agent-voice', self.source)
        await self.room.local_participant.publish_track(track)
        await self._set_state('listening')
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
        # Start receiving the microphone before the greeting is spoken. A guest
        # may interrupt that greeting just like any other speech output.
        greeting = asyncio.create_task(self._greet())
        self.turn = greeting
        def pipeline_done(task):
            if not task.cancelled() and task.exception() is not None:
                self.asr.events.put_nowait({'type': 'error'})
        for task in (greeting, mic, turns, watchdog):
            task.add_done_callback(pipeline_done)
        stopped = asyncio.create_task(self.ended.wait())
        event_task = None
        try:
            while not self.ended.is_set():
                event_task = asyncio.create_task(self.asr.events.get())
                done, _ = await asyncio.wait((event_task, stopped), return_when=asyncio.FIRST_COMPLETED)
                if stopped in done:
                    break
                event = event_task.result()
                event_task = None
                kind = event.get('type')
                if kind == 'closed':
                    raise RuntimeError('Speech recognition connection closed')
                if kind == 'speech_start':
                    self.bench.mark('asr_speech_start')
                elif kind == 'user_turn_taken':
                    # In auto mode a speech_start may be a backchannel or
                    # background noise. Only a confirmed user turn interrupts.
                    await self._interrupt()
                elif kind == 'committed':
                    committed = event
                    self.bench.mark('asr_committed', language=committed.get('language_code'),
                                    user_turn=committed.get('user_turn'))
                    # Fish's auto-mode contract uses user_turn for ownership;
                    # turn_kind can be absent on a valid user utterance.
                    if committed.get('user_turn') is True:
                        committed['text'] = CONTROL_TOKEN.sub('', committed.get('text', '')).strip()
                        if committed['text']:
                            committed['_received_monotonic'] = time.monotonic()
                            await self.turns.put(committed)
                        else:
                            self.bench.mark('turn_skipped_markup_only')
                    else:
                        self.bench.mark('turn_skipped_backchannel')
                elif kind == 'error':
                    raise RuntimeError('Voice pipeline failed')
        finally:
            tasks = [mic, turns, watchdog, greeting, stopped]
            if event_task:
                tasks.append(event_task)
            for task in tasks:
                task.cancel()
            if self.turn:
                self.turn.cancel()
                tasks.append(self.turn)
            await asyncio.gather(*tasks, return_exceptions=True)

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
        try:
            async for frame_event in stream:
                if self.ended.is_set():
                    break
                await self.asr.send_audio(bytes(frame_event.frame.data))
            if not self.ended.is_set():
                # Do not leave a seemingly active conversation with no microphone.
                raise RuntimeError('Microphone audio stream ended')
        finally:
            await stream.aclose()

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
            # A greeting may still be unwinding after barge-in. Do not overlap
            # two TTS streams or let its cleanup reset the new turn's state.
            async with self._turn_lock:
                if self.turn:
                    self.turn.cancel()
                    await asyncio.gather(self.turn, return_exceptions=True)
                self.turn = asyncio.create_task(self._translate(turn, committed.get('text', ''), language,
                    committed_at=committed.get('_received_monotonic')))
            try:
                await self.turn
            except asyncio.CancelledError:
                if asyncio.current_task().cancelling():
                    raise
                self.bench.mark('turn_cancelled', turn=turn)
            finally:
                self.turn = None

    async def _translate(self, turn: int, text: str, language: str, *, committed_at: float | None = None):
        if not text.strip():
            return
        await self._set_state('thinking')
        self.bench.mark('llm_requested', turn=turn)
        clauses: asyncio.Queue[str | None] = asyncio.Queue()
        full: list[str] = []

        async def generate():
            buffer = ''
            try:
                async for delta in self._llm.stream(interpreter_prompt(self.guest, self.staff), text):
                    buffer += delta
                    full.append(delta)
                    while (clause := pop_clause(buffer)):
                        await clauses.put(clause)
                        buffer = buffer[len(clause):]
                if buffer.strip():
                    await clauses.put(buffer)
            finally:
                # Wake synthesis even when the model fails; propagate that
                # failure after playback settles instead of hanging forever.
                await clauses.put(None)

        producer = asyncio.create_task(generate())
        try:
            await self._speak_stream(clauses, turn, committed_at=committed_at)
            await producer
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

    async def _speak_stream(self, clauses: asyncio.Queue, turn: int, label: str = 'turn',
                            *, committed_at: float | None = None):
        self.speaking = True
        tts = FishTTS(self._fish_key, self._voice)
        feeder = None
        pause_task = None
        gate_lock = asyncio.Lock()
        started_at = committed_at if committed_at is not None else time.monotonic()
        paused = False
        first = True
        resampler = None
        raw = bytearray()
        pcm = bytearray()
        async def feed():
            first_clause = True
            pending = False
            while True:
                clause = await clauses.get()
                if clause is None:
                    break
                await tts.send_text(clause)
                pending = True
                if first_clause:
                    # Force the first meaningful clause through Fish's text
                    # buffer without waiting for the entire model response.
                    await tts.flush()
                    first_clause = False
                    pending = False
            if pending:
                await tts.flush()
            await tts.finish()
        async def pause_if_silent(delay: float):
            nonlocal paused
            await _wait_for_gate_pause(delay)
            async with gate_lock:
                if first:
                    await self.asr.agent_speaking(False)
                    paused = True
                    await self._set_state('thinking')
                    self.bench.mark('first_audio_wait_paused', turn=turn, label=label)
        async def start_audio():
            nonlocal first, paused
            async with gate_lock:
                if first:
                    if paused:
                        await self.asr.agent_speaking(True)
                        paused = False
                        await self._set_state('speaking')
                    first = False
                    self.bench.mark('first_audio_published', turn=turn, label=label)
        try:
            remaining = FIRST_AUDIO_PAUSE_SECONDS - (time.monotonic() - started_at)
            if remaining <= 0:
                # This turn was already delayed by admission/previous cleanup.
                paused = True
                await self.asr.agent_speaking(False)
                await self._set_state('thinking')
            else:
                await self._set_state('speaking')
                await self.asr.agent_speaking(True)
                # Measured from ASR commitment, including time spent waiting
                # for model output or opening the TTS websocket.
                remaining = max(0, FIRST_AUDIO_PAUSE_SECONDS - (time.monotonic() - started_at))
                pause_task = asyncio.create_task(pause_if_silent(remaining))
                def pause_done(task):
                    if not task.cancelled() and task.exception() is not None:
                        tts.audio.put_nowait(None)
                pause_task.add_done_callback(pause_done)
            await tts.connect()
            feeder = asyncio.create_task(feed())
            def feed_done(task):
                if not task.cancelled() and task.exception() is not None:
                    tts.audio.put_nowait(None)
            feeder.add_done_callback(feed_done)
            while True:
                chunk = await asyncio.wait_for(tts.audio.get(), timeout=30)
                if chunk is None:
                    break
                raw.extend(chunk)
                aligned = len(raw) & ~1
                if aligned:
                    converted, resampler = audioop.ratecv(bytes(raw[:aligned]), 2, 1, 24000, 48000, resampler)
                    del raw[:aligned]
                    pcm.extend(converted)
                while len(pcm) >= 960 * 2:
                    piece = bytes(pcm[:960 * 2])
                    del pcm[:960 * 2]
                    frame = rtc.AudioFrame(data=piece, sample_rate=48000, num_channels=1,
                                           samples_per_channel=960)
                    if first:
                        await start_audio()
                    await self.source.capture_frame(frame)
            if pause_task and pause_task.done():
                await pause_task
            if raw:
                raise ValueError('Synthesis returned an incomplete PCM sample')
            if pcm:
                piece = bytes(pcm) + b'\x00' * (960 * 2 - len(pcm))
                if first:
                    await start_audio()
                await self.source.capture_frame(rtc.AudioFrame(data=piece, sample_rate=48000,
                    num_channels=1, samples_per_channel=960))
            if tts.error:
                raise RuntimeError('Synthesis provider returned an error')
            await feeder
            if first:
                raise RuntimeError('Synthesis returned no audio')
            # Keep the provider's echo/turn gate active until locally queued
            # audio has played out, not merely until synthesis finishes.
            await self.source.wait_for_playout()
        finally:
            if pause_task:
                pause_task.cancel()
                await asyncio.gather(pause_task, return_exceptions=True)
            if feeder:
                feeder.cancel()
                await asyncio.gather(feeder, return_exceptions=True)
            await tts.close()
            self.speaking = False
            await self.asr.agent_speaking(False)
            if not self.ended.is_set():
                await self._set_state('listening')

    async def _interrupt(self):
        async with self._turn_lock:
            if not self.speaking and (not self.turn or self.turn.done()):
                return
            self.bench.mark('interrupted')
            previous = self.turn
            if previous:
                previous.cancel()
            if self.source:
                self.source.clear_queue()
                self.bench.mark('playback_flushed')
            if previous:
                # Finish the old turn's cleanup before a queued new turn can
                # set its own echo gate and state. The queue is cleared first.
                await asyncio.gather(previous, return_exceptions=True)
            self.speaking = False
            await self.asr.agent_speaking(False)
            await self._set_state('listening')

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
        if self.source:
            await self.source.aclose()
            self.source = None
        try:
            await self.room.disconnect()
        except Exception:
            pass
        self.bench.mark('session_closed')
        self.bench.close()
