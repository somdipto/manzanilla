"""Application-owned Fish ASR -> deterministic direction -> Gemini -> Fish TTS.

The microphone remains live during every output. Reliable generation-tagged PCM
has bounded receiver-render credit; only an acknowledged EOF completes playback.
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
from .records import Conversation, speech_input
from .tts import FishTTS

LOG = logging.getLogger('voice_worker.session')
CLAUSE_END = tuple('.!?;:\n。！？；：')
MIN_CLAUSE = 12
PLAYBACK_ACK_SECONDS = 10
SPEAKING_PAUSE_SECONDS = 2
TABLET_WAIT_SECONDS = 30


def pop_clause(buffer: str) -> str | None:
    for index, char in enumerate(buffer):
        if char in CLAUSE_END and index + 1 >= MIN_CLAUSE:
            return buffer[:index + 1]
    return None


def presentation(text: str) -> str:
    # Fish interprets square brackets as performance directives. Render the
    # same ordinary parentheses in both displayed translation and synthesis.
    return text.replace('[', '(').replace(']', ')')


def device_identity(room_name: str, room_prefix: str = 'manz-') -> str:
    """The identity the backend puts in this room's device token.

    `cloud/livekit_route.py` names a room `room_prefix + lease[:40]` and issues its
    device token for `'device-' + lease[:24]`, so the worker must strip its own
    configured namespace rather than a hard-coded prefix or it would ignore that
    client's acknowledgements, direction controls and join metadata.
    """
    return 'device-' + room_name.removeprefix(room_prefix)[:24]


class Bench:
    """Boundary timings only: never transcript, provider body or credentials."""
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
                 fish_key: str, voice_id: str, llm: TranslateStream,
                 bench: Bench | None = None, max_seconds: int = MAX_SECONDS,
                 client_identity: str | None = None, guest: str | None = None,
                 staff: str | None = None, session_id: str | None = None,
                 room_prefix: str = 'manz-'):
        if not fish_key or not voice_id:
            raise ValueError('Fish credentials and voice are required')
        self.room_name = room_name
        self.session_id = session_id or room_name
        self.client_identity = client_identity or device_identity(room_name, room_prefix)
        self._url, self._grant = livekit_url, (api_key, api_secret)
        self._fish_key, self._voice, self._llm = fish_key, voice_id, llm
        self._max_seconds = min(max_seconds, MAX_SECONDS)
        self.bench = bench or Bench(None, room_name)
        self.room = rtc.Room()
        self._connect_task = None
        self._close_task = None
        self.asr = None
        self.guest, self.staff = guest or '', staff or ''
        self.conversation = None
        self.turn = None
        self.turns = asyncio.Queue(maxsize=16)
        self.controls = asyncio.Queue(maxsize=32)
        self._turn_lock = asyncio.Lock()
        self._queued = set()
        self._generation = 0
        self._active_generation = 0
        self._active_id = None
        self._audio_sequence = 0
        self._audio_ack = -1
        self._audio_changed = asyncio.Event()
        self._audio_error = False
        self._last_audio = 0
        self._speaking_hint = False
        self._connected = False
        self._closed = False
        self._run_task = None
        self._mic_ready = asyncio.Event()
        self._capture_task = None
        self._microphone = asyncio.Queue(maxsize=400)  # at most 32 seconds of 80 ms PCM
        self.ended = asyncio.Event()
        self.speaking = False
        self.agent_identity = 'agent-' + room_name[:40]
        self.deadline = time.monotonic() + self._max_seconds

    async def start(self):
        """Connect transport only; provider startup waits for the authorized mic."""
        if self._connected:
            return
        if self.ended.is_set():
            raise RuntimeError('Session ended')
        key, secret = self._grant
        token = (api.AccessToken(key, secret).with_identity(self.agent_identity)
                 .with_name('Manzanilla interpreter')
                 .with_grants(api.VideoGrants(room_join=True, room=self.room_name,
                     can_publish=True, can_subscribe=True, can_update_own_metadata=True)).to_jwt())

        @self.room.on('data_received')
        def data(packet):
            if (packet.topic != 'client-event' or not packet.participant
                    or packet.participant.identity != self.client_identity
                    or len(packet.data) > 2048 or self.ended.is_set()):
                return
            try:
                value = json.loads(packet.data)
                if isinstance(value, dict):
                    if value.get('type') == 'translation.played':
                        self.played(value)
                    else:
                        self.controls.put_nowait(value)
            except (ValueError, asyncio.QueueFull):
                pass

        if self._connect_task is None:
            # Cancelling Room.connect's Python await can orphan its Rust FFI
            # connection. The SDK owns its signal timeout; we own settlement.
            self._connect_task = asyncio.create_task(self.room.connect(
                self._url, token, options=rtc.RoomOptions(connect_timeout=10)))
        await asyncio.shield(self._connect_task)
        self._connected = True
        if self.ended.is_set():
            await self._close()
            raise RuntimeError('Session ended')
        self.bench.mark('room_connected')

    async def run(self):
        self._run_task = asyncio.current_task()
        try:
            async with asyncio.timeout(self._max_seconds):
                await self.start()
                await self._wait_tablet()
                if self.ended.is_set():
                    return False
                self.conversation = Conversation(self.session_id, self.guest, self.staff)
                self._capture_task = asyncio.create_task(self._capture_mic())
                async with asyncio.TaskGroup() as startup:
                    startup.create_task(self._llm.validate())
                    startup.create_task(self._open_asr())
                await self._main()
            return True
        except asyncio.TimeoutError:
            await self._send(contract.session_ended('conversation_timeout'))
            return False
        except asyncio.CancelledError:
            raise
        except Exception:
            # Provider exceptions can include request bodies/authorization.
            LOG.warning('interpreter session failed')
            if self._connected:
                await self._send(contract.error(recovered=False))
            return False
        finally:
            await self._close()

    async def stop(self):
        self.ended.set()
        task = self._run_task
        if task and task is not asyncio.current_task() and not task.done():
            task.cancel()
            await asyncio.gather(task, return_exceptions=True)
        # Another Stop may cancel the run task while it awaits shielded
        # cleanup. Every Stop must still await that same owned cleanup.
        await self._close()

    async def _wait_tablet(self):
        async with asyncio.timeout(TABLET_WAIT_SECONDS):
            while not self.ended.is_set():
                participant = self.room.remote_participants.get(self.client_identity)
                if participant:
                    try:
                        value = json.loads(participant.metadata or '{}')
                    except ValueError:
                        value = {}
                    guest = self.guest or value.get('guest_language')
                    staff = self.staff or value.get('staff_language')
                    if guest not in contract.LANGUAGES or staff not in contract.LANGUAGES:
                        raise ValueError('Unsupported speech language')
                    for publication in participant.track_publications.values():
                        if publication.track and publication.kind == rtc.TrackKind.KIND_AUDIO:
                            self.guest, self.staff = guest, staff
                            self._mic_track = publication.track
                            return
                await asyncio.sleep(.05)
        raise RuntimeError('Authorized microphone unavailable')

    def played(self, value):
        """Authenticated receiver boundary, never packet arrival credit."""
        if (self.ended.is_set() or not self._active_generation
                or type(value.get('output_generation')) is not int
                or value['output_generation'] != self._active_generation):
            return False
        sequence = value.get('sequence')
        if (set(value) != {'type', 'output_generation', 'sequence'}
                or value.get('type') != 'translation.played'
                or type(sequence) is not int or sequence < 0
                or sequence >= self._audio_sequence):
            self._audio_error = True
            self._audio_changed.set()
            return False
        if sequence <= self._audio_ack:
            return False
        self._audio_ack = sequence
        self._audio_changed.set()
        return True

    async def _audio_wait(self, generation, *, drain=False):
        async with asyncio.timeout(PLAYBACK_ACK_SECONDS):
            while True:
                if generation != self._active_generation:
                    raise asyncio.CancelledError
                if self._audio_error:
                    raise RuntimeError('Invalid playback acknowledgement')
                pending = self._audio_sequence - self._audio_ack - 1
                if pending < (1 if drain else contract.AUDIO_CREDIT):
                    return
                self._audio_changed.clear()
                await self._audio_changed.wait()

    async def _publish_pcm(self, generation, pcm):
        await self._audio_wait(generation)
        packet = contract.audio_packet(generation, self._audio_sequence, pcm)
        # Reserve before await: a receiver ACK may arrive before publish returns.
        self._audio_sequence += 1
        await asyncio.wait_for(self.room.local_participant.publish_data(
            packet, reliable=True, topic=contract.AUDIO_TOPIC,
            destination_identities=[self.client_identity]), PLAYBACK_ACK_SECONDS)

    async def _speaking_watch(self, generation):
        # Fish's backchannel hint includes committed model/TTS work. A pause
        # exceeding two seconds without PCM clears it until PCM actually resumes.
        while generation == self._active_generation:
            await asyncio.sleep(.1)
            if (self._speaking_hint and time.monotonic() - self._last_audio > SPEAKING_PAUSE_SECONDS
                    and generation == self._active_generation):
                self._speaking_hint = False
                await self.asr.agent_speaking(False)

    async def _open_asr(self):
        self.asr = FishASR(self._fish_key)
        await self.asr.connect()
        self.conversation.accept(self.asr.started)
        self.bench.mark('asr_started')

    async def _main(self):
        tasks = [asyncio.create_task(self._pump_mic()), asyncio.create_task(self._events()),
                 asyncio.create_task(self._controls()), asyncio.create_task(self._watchdog())]
        if self._capture_task:
            tasks.append(self._capture_task)
        stopped = asyncio.create_task(self.ended.wait())
        ready = asyncio.create_task(self._mic_ready.wait())
        try:
            done, _ = await asyncio.wait([ready, stopped, *tasks], return_when=asyncio.FIRST_COMPLETED)
            for task in done:
                if task not in (ready, stopped):
                    await task
            if stopped in done:
                return
            if not self._mic_ready.is_set():
                raise RuntimeError('Microphone startup failed')
            await self._send(contract.ready(self.session_id, self.guest, self.staff))
            await self._set_state('listening')
            tasks.append(asyncio.create_task(self._turn_loop()))
            done, _ = await asyncio.wait([*tasks, stopped], return_when=asyncio.FIRST_COMPLETED)
            for task in done:
                if task is not stopped:
                    await task
                    if not self.ended.is_set():
                        raise RuntimeError('Pipeline stopped unexpectedly')
        finally:
            for task in [*tasks, stopped, ready]:
                task.cancel()
            await asyncio.gather(*tasks, stopped, ready, return_exceptions=True)
            await self._interrupt()

    async def _watchdog(self):
        while not self.ended.is_set():
            if self.client_identity not in self.room.remote_participants:
                self.ended.set()
                return
            await asyncio.sleep(1)

    async def _capture_mic(self):
        # Begin bounded capture before provider handshakes, retaining initial
        # syllables. Overflow is a visible failure, not silent audio omission.
        stream = rtc.AudioStream(self._mic_track, sample_rate=16000, num_channels=1,
                                 frame_size_ms=80, capacity=25)
        self._mic_ready.set()
        try:
            async for event in stream:
                if self.ended.is_set():
                    return
                self._microphone.put_nowait(bytes(event.frame.data))
            raise RuntimeError('Microphone stream ended')
        finally:
            await stream.aclose()

    async def _pump_mic(self):
        while not self.ended.is_set():
            frame = await self._microphone.get()
            await asyncio.wait_for(self.asr.send_audio(frame), timeout=3)

    async def _events(self):
        while not self.ended.is_set():
            await self._handle_event(await self.asr.events.get())

    async def _handle_event(self, event):
        kind = event.get('type')
        if kind == 'user_turn_taken':
            await self._interrupt()
        elif kind in ('closed', 'session_done'):
            raise RuntimeError('Speech recognition connection closed')
        elif kind == 'error':
            if (event.get('recoverable') is True
                    and event.get('error_type') in ('engine_error', 'invalid_request')):
                await self._send(contract.error(recovered=True))
            else:
                raise RuntimeError('Speech recognition failed')
        elif kind in ('speech_start', 'draft', 'committed'):
            try:
                record = self.conversation.accept(event)
            except ValueError:
                await self._send(contract.error(recovered=True))
                return
            if record:
                if kind == 'committed':
                    self.bench.mark('asr_committed')
                await self._record(record)
                if kind == 'committed' and record['translation_status'] == 'waiting':
                    await self._enqueue(record['utterance_id'])

    async def _controls(self):
        while not self.ended.is_set():
            await self.resolve(await self.controls.get())

    async def resolve(self, value):
        """Internal dispatch only; RTC admission authenticates the sender first."""
        if (not self.conversation or self.ended.is_set() or not isinstance(value, dict)
                or set(value) != {'type', 'utterance_id', 'revision', 'role'}
                or value.get('type') != 'translation.resolve'):
            return False
        try:
            record = self.conversation.resolve(value['utterance_id'], value['revision'], value['role'])
        except (ValueError, KeyError, TypeError):
            await self._send(contract.error(recovered=True))
            return False
        await self._record(record)
        await self._enqueue(record['utterance_id'])
        return True

    async def _enqueue(self, ident):
        if ident in self._queued:
            return
        try:
            self.turns.put_nowait(ident)
            self._queued.add(ident)
        except asyncio.QueueFull:
            await self._record(self.conversation.update(ident, translation_status='error', playback_status='error'))
            await self._send(contract.error(recovered=True))

    async def _turn_loop(self):
        # Welcome is outside Conversation and can be interrupted, never replayed.
        async with self._turn_lock:
            self.turn = asyncio.create_task(self._output(None))
        await self._await_turn()
        while not self.ended.is_set():
            ident = await self.turns.get()
            async with self._turn_lock:
                self._queued.discard(ident)
                if self.conversation.get(ident)['translation_status'] != 'waiting':
                    continue
                self._active_id = ident
                self.turn = asyncio.create_task(self._output(ident))
            await self._await_turn()

    async def _await_turn(self):
        current = self.turn
        try:
            await current
        except asyncio.CancelledError:
            if asyncio.current_task().cancelling():
                raise
        finally:
            if self.turn is current:
                self.turn = None

    async def _output(self, ident):
        if self.ended.is_set():
            return
        self._generation += 1
        generation = self._generation
        self._active_generation, self._active_id = generation, ident
        self._audio_sequence, self._audio_ack = 0, -1
        self._audio_error = False
        self._audio_changed.clear()
        record = self.conversation.get(ident) if ident else None
        target = record['target_language'] if record else self.guest
        welcome = contract.greeting(self.guest) if not ident else None
        clauses = asyncio.Queue(maxsize=8)
        producer = hint = None
        failed = False
        try:
            self.speaking = self._speaking_hint = True
            self._last_audio = time.monotonic()
            hint = asyncio.create_task(self._speaking_watch(generation))
            await self.asr.agent_speaking(True)
            if ident:
                await self._record(self.conversation.update(ident, output_generation=generation,
                    translation_status='streaming', playback_status='queued'))
            else:
                # Repeat readiness immediately before welcome. A client that missed
                # the first packet must not discard the only greeting.
                await self._send(contract.ready(self.session_id, self.guest, self.staff))
                await self._send(contract.welcome(self.session_id, welcome, self.guest, 'queued', generation))
            await self._set_state('thinking')
            producer = asyncio.create_task(self._generate(record, welcome, clauses))
            async with asyncio.timeout(90):
                await self._speak_stream(clauses, target, generation, ident, welcome, producer)
                await producer
            await self._finish_output(generation, 'complete')
        except asyncio.CancelledError:
            await self._finish_output(generation, 'interrupted')
            raise
        except Exception:
            failed = True
            await self._finish_output(generation, 'error')
        finally:
            for task in (producer, hint):
                if task:
                    task.cancel()
            await asyncio.gather(*(task for task in (producer, hint) if task), return_exceptions=True)
            self.speaking = False
            if self._speaking_hint:
                self._speaking_hint = False
                await self.asr.agent_speaking(False)
            if not self.ended.is_set():
                await self._set_state('listening')
            if failed:
                await self._send(contract.error(recovered=bool(ident)))
                if not ident:
                    self.ended.set()

    async def _finish_output(self, generation, status):
        if not generation or generation != self._active_generation:
            return
        ident = self._active_id
        # Fence both record and PCM before any network/provider await. Late
        # producer callbacks and repeated finally paths cannot revive this turn.
        self._active_generation, self._active_id = 0, None
        self._audio_changed.set()
        record = None
        if ident:
            previous = self.conversation.get(ident)['translation_status']
            translation = previous if previous == 'complete' else {
                'complete': 'complete', 'interrupted': 'cancelled', 'error': 'error'}[status]
            record = self.conversation.update(ident, translation_status=translation, playback_status=status)
        if status != 'complete':
            self.bench.mark('playback_flushed', generation=generation)
        await self._send(contract.output(self.session_id, generation, status))
        if record:
            await self._record(record)
        else:
            await self._send(contract.welcome(self.session_id, contract.greeting(self.guest),
                                              self.guest, status, generation))
        self.speaking = False
        if self._speaking_hint:
            self._speaking_hint = False
            await self.asr.agent_speaking(False)

    async def _generate(self, record, welcome, clauses):
        generation = self._active_generation

        async def remember(text):
            if generation != self._active_generation:
                raise asyncio.CancelledError
            current = self.conversation.get(record['utterance_id'])
            await self._record(self.conversation.update(record['utterance_id'],
                                                        translation=current['translation'] + text))

        if record is None:
            await clauses.put(presentation(welcome))
        elif record['source_language'] == record['target_language']:
            text = presentation(speech_input(record['original']))
            await remember(text)
            await clauses.put(text)
        else:
            self.bench.mark('llm_requested', generation=self._active_generation)
            buffer = ''
            total = 0
            async for delta in self._llm.stream(interpreter_prompt(record['source_language'], record['target_language']),
                                                speech_input(record['original'])):
                total += len(delta)
                if total > 8000:
                    raise RuntimeError('Translation too long')
                delta = presentation(delta)
                await remember(delta)
                buffer += delta
                while (clause := pop_clause(buffer)):
                    await clauses.put(clause)
                    buffer = buffer[len(clause):]
            if buffer.strip():
                await clauses.put(buffer)
        if record is not None:
            if generation != self._active_generation:
                raise asyncio.CancelledError
            await self._record(self.conversation.update(record['utterance_id'], translation_status='complete'))
        await clauses.put(None)

    async def _speak_stream(self, clauses, target, generation, ident, welcome, producer):
        tts = FishTTS(self._fish_key, self._voice, language=target)
        feeder = None
        first = True
        pcm = bytearray()

        async def feed():
            while True:
                clause = await clauses.get()
                if clause is None:
                    break
                if generation != self._active_generation:
                    raise asyncio.CancelledError
                await tts.send_text(clause)
                await tts.flush()
            await tts.finish()

        async def publish(piece):
            nonlocal first
            if generation != self._active_generation:
                raise asyncio.CancelledError
            if not self._speaking_hint:
                self._speaking_hint = True
                await self.asr.agent_speaking(True)
            if first:
                await self._send(contract.output(self.session_id, generation, 'playing'))
                if ident:
                    await self._record(self.conversation.update(ident, playback_status='playing'))
                else:
                    await self._send(contract.welcome(self.session_id, welcome, self.guest, 'playing', generation))
                await self._set_state('speaking')
                self.bench.mark('first_audio_published', generation=generation)
                first = False
            await self._publish_pcm(generation, piece)
            self._last_audio = time.monotonic()

        try:
            await tts.connect()
            feeder = asyncio.create_task(feed())
            while True:
                receiver = asyncio.create_task(tts.audio.get())
                try:
                    watched = [receiver]
                    for task in (producer, feeder):
                        if task.done():
                            await task
                        else:
                            watched.append(task)
                    done, _ = await asyncio.wait(watched, timeout=.1, return_when=asyncio.FIRST_COMPLETED)
                    for task in (producer, feeder):
                        if task.done():
                            await task
                    if receiver not in done:
                        continue
                    chunk = receiver.result()
                finally:
                    if not receiver.done():
                        receiver.cancel()
                        await asyncio.gather(receiver, return_exceptions=True)
                if chunk is None:
                    break
                # Retain at most one packet between provider chunks, including
                # odd byte boundaries. No padding or first/last sample loss.
                view = memoryview(chunk)
                for offset in range(0, len(view), contract.AUDIO_CHUNK_BYTES):
                    pcm.extend(view[offset:offset + contract.AUDIO_CHUNK_BYTES])
                    if len(pcm) >= contract.AUDIO_CHUNK_BYTES:
                        piece = bytes(pcm[:contract.AUDIO_CHUNK_BYTES])
                        del pcm[:contract.AUDIO_CHUNK_BYTES]
                        await publish(piece)
            await feeder
            if tts.error or len(pcm) % 2:
                raise RuntimeError('Synthesis failed or incomplete')
            if pcm:
                await publish(bytes(pcm))
            if first:
                raise RuntimeError('Synthesis returned no audio')
            await self._publish_pcm(generation, b'')
            await self._audio_wait(generation, drain=True)
            await self._finish_output(generation, 'complete')
        except asyncio.CancelledError:
            await self._finish_output(generation, 'interrupted')
            raise
        except Exception:
            await self._finish_output(generation, 'error')
            raise
        finally:
            if feeder:
                feeder.cancel()
                await asyncio.gather(feeder, return_exceptions=True)
            await tts.close()

    async def _interrupt(self):
        async with self._turn_lock:
            # The turn waiter clears self.turn as soon as this task settles.
            # Keep the exact task: a later self.turn.cancel() can end the session.
            current = self.turn
            if current and not current.done():
                self.bench.mark('interrupted')
                if not self._active_generation and self._active_id:
                    ident, self._active_id = self._active_id, None
                    current.cancel()
                    await self._record(self.conversation.update(ident,
                        translation_status='cancelled', playback_status='interrupted'))
                try:
                    await self._finish_output(self._active_generation, 'interrupted')
                finally:
                    current.cancel()
                    await asyncio.gather(current, return_exceptions=True)
            while not self.turns.empty():
                self.turns.get_nowait()
            pending, self._queued = self._queued, set()
            for ident in pending:
                await self._record(self.conversation.update(ident,
                    translation_status='cancelled', playback_status='interrupted'))

    async def _record(self, record):
        await self._send(contract.utterance(record))
        self.bench.mark('record_sent', revision=record['revision'])
        if record['translation_status'] == 'complete':
            self.bench.mark('translation_complete', generation=record['output_generation'])

    async def _set_state(self, value):
        await self.room.local_participant.set_attributes(contract.state(value))

    async def _send(self, payload):
        await self.room.local_participant.publish_data(payload, reliable=True, topic=contract.EVENT_TOPIC)

    async def _close(self):
        self.ended.set()
        if self._close_task is None:
            self._close_task = asyncio.create_task(self._close_owned())
        await asyncio.shield(self._close_task)

    async def _close_owned(self):
        self._closed = True
        if self._connect_task:
            # Even a cancelled Start still owns a possibly successful late
            # Rust connection. Stop acknowledges only after it is disconnected.
            await asyncio.gather(self._connect_task, return_exceptions=True)
        if self._capture_task:
            self._capture_task.cancel()
            await asyncio.gather(self._capture_task, return_exceptions=True)
        try:
            await self._interrupt()
        except Exception:
            LOG.warning('terminal output notification unavailable')
        if self.conversation:
            for record in list(self.conversation.records.values()):
                if record['translation_status'] in ('waiting', 'streaming', 'needs_direction'):
                    try:
                        await self._record(self.conversation.update(record['utterance_id'],
                            translation_status='cancelled', playback_status='interrupted'))
                    except Exception:
                        pass
        try:
            if self.asr:
                await self.asr.close()
        finally:
            try:
                await self.room.disconnect()
            finally:
                self.bench.mark('session_closed')
                self.bench.close()
