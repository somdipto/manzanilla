"""Controlled translation behavior with isolated transports; no provider calls."""
import asyncio
import json
import sys
from pathlib import Path
from types import SimpleNamespace

import httpx
import msgpack
import pytest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'voice-worker'))
from voice_worker import asr, tts, session, llm, worker, contract
from voice_worker.records import Conversation


class Socket:
    def __init__(self, events=()):
        self.incoming = asyncio.Queue()
        for event in events:
            self.incoming.put_nowait(msgpack.packb(event, use_bin_type=True))
        self.sent = []
        self.closed = False
    async def send(self, frame):
        assert isinstance(frame, bytes)
        self.sent.append(msgpack.unpackb(frame, raw=False))
    def __aiter__(self): return self
    async def __anext__(self):
        frame = await self.incoming.get()
        if frame is None: raise StopAsyncIteration
        return frame
    async def close(self): self.closed = True


def started():
    return {'type': 'session_started', 'session_id': 'provider-owned', 'config': {
        'audio': {'format': 'pcm_s16le', 'sample_rate': 16000, 'channels': 1},
        'turn_detection': {'mode': 'auto', 'eager': False},
        'results': {'drafts': True, 'diarize': False, 'ignore_timestamps': False}}}


@pytest.mark.parametrize('bad', [False, True])
def test_asr_binary_config_and_effective_handshake(monkeypatch, bad):
    async def scenario():
        event = started()
        if bad: event['config']['results']['drafts'] = False
        socket = Socket([event])
        async def connect(url, **options):
            assert url == asr.ENDPOINT
            assert options['additional_headers'] == {'Authorization': 'Bearer fixture', 'model': 'transcribe-stream-1'}
            return socket
        monkeypatch.setattr(asr.websockets, 'connect', connect)
        client = asr.FishASR('fixture')
        if bad:
            with pytest.raises(RuntimeError, match='configuration'):
                await client.connect()
            assert socket.closed
            return
        await client.connect()
        assert client.started == event
        config = socket.sent[0]
        assert config['turn_detection'] == {'mode': 'auto', 'eager': False}
        assert config['results'] == {'drafts': True, 'diarize': False, 'ignore_timestamps': False}
        await client.send_audio(b'\x01\x00' * 1280)
        assert socket.sent[-1]['data'] == b'\x01\x00' * 1280
        await client.close()
        assert socket.closed
    asyncio.run(scenario())


@pytest.mark.parametrize('closed', [False, True])
def test_asr_start_failure_is_bounded_and_closes_reader(monkeypatch, closed):
    async def scenario():
        socket = Socket()
        if closed: socket.incoming.put_nowait(None)
        async def connect(*args, **kwargs): return socket
        monkeypatch.setattr(asr.websockets, 'connect', connect)
        monkeypatch.setattr(asr, 'START_TIMEOUT_SECONDS', .02)
        client = asr.FishASR('fixture')
        with pytest.raises((RuntimeError, TimeoutError)):
            await asyncio.wait_for(client.connect(), .5)
        assert socket.closed and client._reader_task is None
    asyncio.run(scenario())


@pytest.mark.parametrize('finish', ['stop', 'error', 'unknown', None])
def test_tts_uses_documented_target_schema_and_strict_completion(monkeypatch, finish):
    async def scenario():
        socket = Socket([{'event': 'audio', 'audio': b'\x01\x00'}, {'event': 'finish', 'reason': finish}])
        async def connect(url, **options):
            assert url == tts.ENDPOINT
            assert options['additional_headers']['model'] == 's2.1-pro'
            return socket
        monkeypatch.setattr(tts.websockets, 'connect', connect)
        client = tts.FishTTS('fixture', 'voice', language='ar')
        await client.connect()
        request = socket.sent[0]['request']
        assert request == {'text': '', 'reference_id': 'voice', 'format': 'pcm', 'sample_rate': 24000,
            'chunk_length': 120, 'normalize': True, 'latency': 'normal', 'language': 'ar',
            'prosody': {'speed': 1.0, 'volume': 0, 'normalize_loudness': True}}
        await client.send_text('النص المترجم')
        await client.flush(); await client.finish()
        assert socket.sent[-3:] == [{'event': 'text', 'text': 'النص المترجم'}, {'event': 'flush'}, {'event': 'stop'}]
        assert await client.audio.get() == b'\x01\x00'
        assert await client.audio.get() is None
        assert client.error is (finish != 'stop')
        await client.close()
    asyncio.run(scenario())


def test_full_provider_queues_do_not_deadlock_cancellation(monkeypatch):
    async def scenario():
        socket = Socket([started()])
        async def connect(*args, **kwargs): return socket
        monkeypatch.setattr(asr.websockets, 'connect', connect)
        client = asr.FishASR('fixture')
        await client.connect()
        for i in range(256):
            socket.incoming.put_nowait(msgpack.packb({'type': 'progress', 'seq': i}))
        await asyncio.sleep(0)
        await asyncio.wait_for(client.close(), .5)
        socket = Socket([{'event': 'audio', 'audio': b'\0\0'}] * 64)
        synth = tts.FishTTS('fixture', 'voice', language='es')
        await synth.connect()
        await asyncio.sleep(0)
        await asyncio.wait_for(synth.close(), .5)
    asyncio.run(scenario())



class Participant:
    def __init__(self):
        self.messages = []; self.packets = []; self.render = None
    async def publish_data(self, payload, **kwargs):
        assert kwargs['reliable'] is True
        if kwargs['topic'] == contract.AUDIO_TOPIC:
            packet = contract.read_audio_packet(payload)
            self.packets.append(packet)
            if self.render:
                self.render(packet)
        else:
            self.messages.append(json.loads(payload))
    async def set_attributes(self, attributes): pass


class Room:
    def __init__(self): self.local_participant = Participant(); self.remote_participants = {}; self.handlers = {}
    def on(self, event):
        def register(fn): self.handlers[event] = fn; return fn
        return register
    async def connect(self, *args, **kwargs): pass
    async def disconnect(self): self.disconnected = True


class Synth:
    instances = []
    chunks = [b'\x01\x00' * 720]
    block = False
    fail = False
    def __init__(self, *args, **kwargs):
        self.audio = asyncio.Queue(); self.error = self.fail; self.texts = []; self.language = kwargs['language']
        self.closed = False; self.sent = asyncio.Event()
        Synth.instances.append(self)
    async def connect(self): pass
    async def send_text(self, text):
        self.texts.append(text)
        for chunk in self.chunks: await self.audio.put(chunk)
        self.sent.set()
    async def flush(self): pass
    async def finish(self):
        if self.block: await asyncio.Event().wait()
        await self.audio.put(None)
    async def close(self): self.closed = True


class Recognizer:
    def __init__(self): self.events = asyncio.Queue(); self.gates = []; self.started = started(); self.closed = False
    async def connect(self): pass
    async def agent_speaking(self, value): self.gates.append(value)
    async def close(self): self.closed = True


class Model:
    def __init__(self, chunks=('First sentence. More text.',)):
        self.chunks = chunks; self.calls = []; self.validated = False
    async def validate(self): self.validated = True
    async def stream(self, prompt, original):
        self.calls.append((prompt, original))
        for chunk in self.chunks: yield chunk


@pytest.fixture
def pipeline(monkeypatch):
    monkeypatch.setattr(session.rtc, 'Room', Room)
    monkeypatch.setattr(session, 'FishTTS', Synth)
    Synth.instances = []; Synth.block = False; Synth.fail = False; Synth.chunks = [b'\x01\x00' * 720]
    def make(guest='es', staff='en', model=None):
        obj = session.InterpreterSession('manz-fixture', livekit_url='ws://fixture', api_key='fixture',
            api_secret='fixture-secret-at-least-32-characters', fish_key='fixture', voice_id='voice',
            llm=model or Model(), guest=guest, staff=staff, client_identity='device-fixture')
        obj.conversation = Conversation(obj.session_id, guest, staff)
        obj.conversation.accept(started())
        obj.asr = Recognizer()
        # Isolated consumer simulates rendering; drain regressions below hold
        # this boundary explicitly rather than granting credit on send.
        obj.room.local_participant.render = lambda packet: obj.played({
            'type': 'translation.played', 'output_generation': packet[0], 'sequence': packet[1]})
        return obj
    return make


def final(obj, index=0, language='es', text='¿Dónde está la recepción?', **extra):
    return obj.conversation.accept({'type': 'committed', 'turn_index': index, 'text': text,
                                    'language_code': language, **extra})


def test_streamed_clauses_share_exact_display_and_tts_and_route_each_turn(pipeline):
    async def scenario():
        obj = pipeline(model=Model(['First sentence. Second sentence.']))
        originals = []
        for index, language in enumerate(['es', 'es', 'es', 'en', 'en', 'es']):
            original = '¿Puede llamar un taxi?' if language == 'es' else 'Can you call a taxi?'
            record = final(obj, index, language, original)
            originals.append(original)
            await obj._output(record['utterance_id'])
            result = obj.conversation.get(record['utterance_id'])
            assert result['original'] == original
            assert result['translation'] == ''.join(Synth.instances[-1].texts)
            assert result['translation_status'] == result['playback_status'] == 'complete'
            target = 'en' if language == 'es' else 'es'
            assert Synth.instances[-1].language == target
            prompt, user = obj._llm.calls[-1]
            assert user == original and original not in prompt
            assert f'Target language:\n{contract.LANGUAGES[target]} ({target}).' in prompt
        assert [call[1] for call in obj._llm.calls] == originals
        packets = obj.room.local_participant.packets
        assert {packet[0] for packet in packets} == set(range(1, 7))
        assert sum(not packet[2] for packet in packets) == 6
    asyncio.run(scenario())


def test_drafts_replace_original_and_final_is_immutable(pipeline):
    async def scenario():
        obj = pipeline()
        await obj._handle_event({'type': 'draft', 'turn_index': 2, 'text': '  ¿Dónde', 'language_code': 'es'})
        await obj._handle_event({'type': 'draft', 'turn_index': 2, 'text': '  ¿Dónde está?  ', 'language_code': 'es'})
        await obj._handle_event({'type': 'committed', 'turn_index': 2, 'text': '  ¿Dónde está?  ', 'language_code': 'es'})
        ident = await obj.turns.get()
        assert obj.conversation.get(ident)['original'] == '  ¿Dónde está?  '
        await obj._handle_event({'type': 'draft', 'turn_index': 2, 'text': 'rewritten'})
        assert obj.conversation.get(ident)['original'] == '  ¿Dónde está?  '
        records = [x['record'] for x in obj.room.local_participant.messages]
        assert [x['revision'] for x in records] == [1, 2, 3]
        assert not obj._llm.calls
    asyncio.run(scenario())


def test_uncertainty_backchannel_and_degraded_stay_visible_without_speech(pipeline):
    async def scenario():
        obj = pipeline()
        for index, extra in enumerate([{'text': 'Ana'}, {'text': 'esta frase', 'degraded': True},
                                      {'text': 'oui monsieur', 'language_code': 'fr'},
                                      {'text': 'sí claro', 'user_turn': False},
                                      {'text': '[laughter]', 'user_turn': False}]):
            await obj._handle_event({'type': 'committed', 'turn_index': index, 'language_code': 'es', **extra})
        records = list(obj.conversation.records.values())
        assert len(records) == 5 and all(r['role'] is None for r in records)
        assert [r['translation_status'] for r in records] == ['needs_direction'] * 4 + ['ignored']
        assert obj.turns.empty() and not Synth.instances
    asyncio.run(scenario())


def test_control_requires_authorized_sender_and_current_revision(pipeline):
    async def scenario():
        obj = pipeline()
        await obj.start()
        record = final(obj, text='Ana')
        control = {'type': 'translation.resolve', 'utterance_id': record['utterance_id'],
                   'revision': record['revision'], 'role': 'guest'}
        callback = obj.room.handlers['data_received']
        def packet(identity, value=control):
            return SimpleNamespace(topic='client-event', participant=SimpleNamespace(identity=identity), data=json.dumps(value).encode())
        callback(packet('intruder'))
        assert obj.controls.empty()
        callback(packet('device-fixture'))
        assert await obj.resolve(await obj.controls.get()) is True
        assert await obj.resolve(control) is False
        assert obj.turns.qsize() == 1
        result = obj.conversation.get(record['utterance_id'])
        assert result['role'] == 'guest' and result['original'] == 'Ana'
    asyncio.run(scenario())


def test_same_language_manual_resolution_bypasses_model(pipeline):
    async def scenario():
        obj = pipeline('es', 'es')
        record = final(obj, text='¿Puede llamar un taxi?')
        assert record['role'] is None
        resolved = obj.conversation.resolve(record['utterance_id'], record['revision'], 'receptionist')
        await obj._output(resolved['utterance_id'])
        result = obj.conversation.get(record['utterance_id'])
        assert result['translation'] == record['original']
        assert not obj._llm.calls
    asyncio.run(scenario())


def test_interruption_preserves_completed_translation_and_flushes_generation(pipeline):
    async def scenario():
        obj = pipeline()
        Synth.block = True
        record = final(obj)
        obj.turn = asyncio.create_task(obj._output(record['utterance_id']))
        while not Synth.instances: await asyncio.sleep(0)
        await asyncio.wait_for(Synth.instances[0].sent.wait(), .5)
        await obj._handle_event({'type': 'speech_start', 'turn_index': 1})
        await obj._handle_event({'type': 'user_backchannel', 'turn_index': 1})
        assert not obj.turn.done()
        await obj._handle_event({'type': 'user_turn_taken', 'turn_index': 1})
        result = obj.conversation.get(record['utterance_id'])
        assert result['original'] == record['original'] and result['translation']
        assert result['translation_status'] == 'complete' and result['playback_status'] == 'interrupted'
        assert Synth.instances[0].closed
        assert obj._active_generation == 0
        assert any(event.get('type') == 'translation.output' and event['status'] == 'interrupted'
                   for event in obj.room.local_participant.messages)
    asyncio.run(scenario())


def test_worker_device_identity_matches_the_room_token_for_its_namespace(pipeline):
    from cloud import livekit_route
    from uuid import uuid4
    pipeline()
    identify = uuid4().hex + uuid4().hex
    room = livekit_route.room_name(identify)
    # The backend writes 'device-' + lease[:24] into the room's device token. If the
    # worker derived anything else it would ignore that client's acknowledgements,
    # direction controls and join metadata.
    assert room == livekit_route.ROOM_PREFIX + identify[:40]
    assert session.device_identity(room) == 'device-' + identify[:24]
    built = session.InterpreterSession(room, livekit_url='ws://fixture', api_key='fixture',
        api_secret='fixture', fish_key='fixture', voice_id='voice', llm=object())
    assert built.client_identity == 'device-' + identify[:24]
    # A worker namespace must be used for its own rooms, not a hard-coded prefix.
    prefixed = 'manz-bench-' + identify[:40]
    assert session.device_identity(prefixed, 'manz-bench-') == 'device-' + identify[:24]
    namespaced = session.InterpreterSession(prefixed, livekit_url='ws://fixture', api_key='fixture',
        api_secret='fixture', fish_key='fixture', voice_id='voice', llm=object(),
        room_prefix='manz-bench-')
    assert namespaced.client_identity == 'device-' + identify[:24]


def test_turn_loop_interruption_survives_waiter_clearing_the_task(pipeline):
    async def scenario():
        obj = pipeline()
        obj.room.local_participant.render = None
        Synth.block = True
        Synth.chunks = [b'\x01\x00' * 960] * 8
        yielded = asyncio.Event()
        cleared = asyncio.Event()
        original = obj.room.local_participant.publish_data

        async def publish(payload, **kwargs):
            if kwargs.get('topic') != contract.AUDIO_TOPIC:
                event = json.loads(payload)
                if event.get('type') == 'translation.output' and event.get('status') == 'interrupted' and not yielded.is_set():
                    yielded.set()
                    current = obj.turn
                    if current and not current.done():
                        current.cancel()
                    for _ in range(30):
                        await asyncio.sleep(0)
                        if obj.turn is None:
                            cleared.set()
                            break
            await original(payload, **kwargs)

        obj.room.local_participant.publish_data = publish
        loop = asyncio.create_task(obj._turn_loop())
        async def blocked():
            while obj._audio_sequence < contract.AUDIO_CREDIT:
                await asyncio.sleep(0)
        await asyncio.wait_for(blocked(), 1)
        interrupted = asyncio.create_task(obj._handle_event({'type': 'user_turn_taken', 'turn_index': 9}))
        await asyncio.wait_for(yielded.wait(), 1)
        await asyncio.wait_for(interrupted, 1)
        assert cleared.is_set()
        assert not obj.ended.is_set()
        assert not loop.done()
        assert any(event.get('type') == 'translation.output' and event.get('status') == 'interrupted'
                   for event in obj.room.local_participant.messages)
        obj.ended.set()
        loop.cancel()
        await asyncio.gather(loop, return_exceptions=True)
    asyncio.run(scenario())


def test_queue_overflow_and_interruption_leave_no_pending_orphan(pipeline):
    async def scenario():
        obj = pipeline()
        records = [final(obj, index) for index in range(18)]
        for record in records: await obj._enqueue(record['utterance_id'])
        assert obj.turns.qsize() == 16
        assert obj.conversation.get(records[-1]['utterance_id'])['translation_status'] == 'error'
        # Includes the dequeue-before-lock race: this ID is still waiting admission.
        popped = obj.turns.get_nowait()
        await obj._interrupt()
        assert obj.conversation.get(popped)['translation_status'] == 'cancelled'
        assert not obj._queued and obj.turns.empty()
        assert all(r['translation_status'] in ('cancelled', 'error') for r in obj.conversation.records.values())
    asyncio.run(scenario())


def test_pcm_odd_chunk_boundaries_preserve_all_samples_without_padding(pipeline):
    async def scenario():
        obj = pipeline()
        raw = (1234).to_bytes(2, 'little', signed=True) * 1000
        Synth.chunks = [raw[i:i + 111] for i in range(0, len(raw), 111)]
        obj._llm = Model(['One sentence.'])
        record = final(obj)
        await obj._output(record['utterance_id'])
        packets = obj.room.local_participant.packets
        assert b''.join(packet[2] for packet in packets) == raw
        assert [len(packet[2]) for packet in packets] == [1920, 80, 0]
        assert [packet[1] for packet in packets] == [0, 1, 2]
    asyncio.run(scenario())


@pytest.mark.parametrize('error_type', ['engine_error', 'invalid_request'])
def test_recoverable_asr_error_retains_degraded_and_next_turn(pipeline, error_type):
    async def scenario():
        obj = pipeline()
        await obj._handle_event({'type': 'error', 'error_type': error_type, 'recoverable': True})
        await obj._handle_event({'type': 'committed', 'turn_index': 0, 'language_code': 'es',
                                 'degraded': True, 'text': 'hola buenos días'})
        await obj._handle_event({'type': 'committed', 'turn_index': 1, 'language_code': 'en', 'text': 'good morning'})
        assert len(obj.conversation.records) == 2
        assert obj.turns.qsize() == 1
        assert obj.room.local_participant.messages[0] == {'type': 'error', 'recovered': True}
        assert not obj.ended.is_set()
    asyncio.run(scenario())


@pytest.mark.parametrize('event', [
    {'type': 'error', 'error_type': 'engine_error', 'recoverable': False},
    {'type': 'error', 'error_type': 'engine_error', 'recoverable': 'true'},
    {'type': 'error', 'error_type': 'server_restarting', 'recoverable': True},
    {'type': 'closed'}, {'type': 'session_done'}])
def test_terminal_asr_error_never_restarts(pipeline, event):
    async def scenario():
        obj = pipeline()
        with pytest.raises(RuntimeError): await obj._handle_event(event)
        assert not obj._llm.calls
    asyncio.run(scenario())


def mock_translation(monkeypatch, events):
    body = ''.join('data: ' + (event if isinstance(event, str) else json.dumps(event)) + '\n\n' for event in events)
    requests = []
    def respond(request):
        requests.append(request)
        return httpx.Response(200, content=body)
    client = httpx.AsyncClient
    monkeypatch.setattr(llm.httpx, 'AsyncClient', lambda **kwargs: client(transport=httpx.MockTransport(respond), **kwargs))
    return llm.TranslateStream('https://fixture.invalid', 'fixture', 'fixture'), requests


@pytest.mark.parametrize('ending', [[], ['[DONE]'],
    [{'choices': [{'delta': {}, 'finish_reason': 'stop'}]}],
    [{'error': {'message': 'private'}}, '[DONE]'], ['not JSON', '[DONE]'],
    [{'choices': []}, '[DONE]'],
    [{'choices': [{'delta': {'refusal': 'private'}, 'finish_reason': 'stop'}]}, '[DONE]'],
] + [[{'choices': [{'delta': {}, 'finish_reason': reason}]}, '[DONE]']
     for reason in ('length', 'content_filter', 'tool_calls', 'function_call', 'unknown')])
def test_strict_terminal_model_failure_retains_original_and_partial_text(pipeline, monkeypatch, ending):
    async def scenario():
        obj = pipeline()
        obj._llm, _ = mock_translation(monkeypatch, [
            {'choices': [{'delta': {'content': 'First sentence. unfinished'}, 'finish_reason': None}]}, *ending])
        record = final(obj)
        await asyncio.wait_for(obj._output(record['utterance_id']), .5)
        result = obj.conversation.get(record['utterance_id'])
        assert result['translation_status'] == 'error' and result['playback_status'] == 'error'
        assert result['original'] == record['original']
        assert result['translation'] == 'First sentence. unfinished'
        assert Synth.instances[0].closed
    asyncio.run(scenario())


def test_model_success_requires_stop_and_done_and_keeps_user_separate(monkeypatch):
    async def scenario():
        client, requests = mock_translation(monkeypatch, [
            {'choices': [{'delta': {'content': 'A translated question?'}, 'finish_reason': None}]},
            {'choices': [{'delta': {}, 'finish_reason': 'stop'}]},
            {'choices': [], 'usage': {'completion_tokens': 4}}, '[DONE]'])
        assert ''.join([part async for part in client.stream('trusted system', 'Answer this question?')]) == 'A translated question?'
        request = json.loads(requests[0].content)
        assert request['messages'] == [{'role': 'system', 'content': 'trusted system'},
                                       {'role': 'user', 'content': 'Answer this question?'}]
        assert 'tools' not in request
    asyncio.run(scenario())


@pytest.mark.parametrize('status,payload', [(200, {'id': 'gemini-3.8-flash'}),
    (200, {'id': 'models/gemini-3.8-flash'}), (200, {'id': 'models/models/gemini-3.8-flash'}),
    (200, {'id': 'models/other'}), (200, {'id': 'other'}), (401, {}),
    (200, {'id': 'gemini-3.8-flash', 'large': 'x' * 17000})])
def test_model_readiness_is_readonly_authenticated_bounded_lookup(monkeypatch, status, payload):
    async def scenario():
        requests = []
        def respond(request):
            requests.append(request)
            return httpx.Response(status, json=payload)
        client = httpx.AsyncClient
        monkeypatch.setattr(llm.httpx, 'AsyncClient', lambda **kwargs: client(transport=httpx.MockTransport(respond), **kwargs))
        model = llm.TranslateStream(worker.GEMINI_BASE, 'fixture', worker.GEMINI_MODEL)
        if status == 200 and payload in ({'id': 'gemini-3.8-flash'}, {'id': 'models/gemini-3.8-flash'}):
            await model.validate()
        else:
            with pytest.raises(RuntimeError, match='model access'): await model.validate()
        assert len(requests) == 1 and requests[0].method == 'GET'
        assert str(requests[0].url) == worker.GEMINI_BASE + '/models/gemini-3.8-flash'
        assert requests[0].headers['Authorization'] == 'Bearer fixture'
        assert not requests[0].content
    asyncio.run(scenario())


def test_welcome_once_after_microphone_and_readiness_outside_history(pipeline):
    async def scenario():
        obj = pipeline()
        obj.room.remote_participants[obj.client_identity] = SimpleNamespace()
        mic_live = asyncio.Event()
        async def mic():
            mic_live.set(); obj._mic_ready.set(); await asyncio.Event().wait()
        obj._pump_mic = mic
        task = asyncio.create_task(obj._main())
        try:
            for _ in range(100):
                if any(x.get('type') == 'translation.welcome' and x['status'] == 'complete'
                       for x in obj.room.local_participant.messages): break
                await asyncio.sleep(.001)
            events = obj.room.local_participant.messages
            assert mic_live.is_set()
            assert events[0]['type'] == 'translation.ready'
            welcome = [x for x in events if x['type'] == 'translation.welcome']
            assert [x['status'] for x in welcome] == ['queued', 'playing', 'complete']
            # A client that missed the first readiness packet must not discard the
            # only greeting, so readiness is repeated immediately before it.
            assert events[events.index(welcome[0]) - 1]['type'] == 'translation.ready'
            assert all(x['text'] == contract.greeting('es') for x in welcome)
            assert not obj.conversation.records and not obj._llm.calls
            assert len(Synth.instances) == 1
        finally:
            obj.ended.set()
            await asyncio.wait_for(task, .5)
    asyncio.run(scenario())


def test_welcome_failure_is_visible_terminal_not_hidden_ready(pipeline):
    async def scenario():
        obj = pipeline()
        Synth.fail = True
        await obj._output(None)
        assert obj.ended.is_set()
        events = obj.room.local_participant.messages
        assert {'type': 'error', 'recovered': False} in events
        assert any(x['type'] == 'translation.welcome' and x['status'] == 'error' for x in events)
        assert not obj.conversation.records
    asyncio.run(scenario())


@pytest.mark.parametrize('prefix', ['manz-', '', 'session-', 'session-synthetic-'])
def test_worker_never_discovers_synthetic_or_restarts_failed_room(monkeypatch, prefix):
    async def scenario():
        starts = []; scanned = asyncio.Event()
        ordinary = prefix + 'ordinary'
        class API:
            def __init__(self): self.room = self; self.count = 0; self.closed = False
            async def list_rooms(self, request):
                self.count += 1
                if self.count >= 4: scanned.set()
                return SimpleNamespace(rooms=[SimpleNamespace(name=name) for name in ['session-synthetic-fixture', ordinary]])
            async def aclose(self): self.closed = True
        class Failed:
            def __init__(self, name, **kwargs): starts.append(name)
            async def run(self): return False
        api = API()
        monkeypatch.setattr(worker.api, 'LiveKitAPI', lambda *args: api)
        monkeypatch.setattr(worker, 'InterpreterSession', Failed)
        monkeypatch.setattr(worker, 'DISCOVERY_INTERVAL', .001)
        task = asyncio.create_task(worker.serve_forever(dict(livekit_url='ws://fixture', api_key='fixture',
            api_secret='fixture', prefix=prefix, bench_log=None, fish_key='fixture', voice_id='fixture',
            llm=None, max_seconds=10)))
        await asyncio.wait_for(scanned.wait(), .5)
        task.cancel(); await asyncio.gather(task, return_exceptions=True)
        assert starts == ([] if ordinary.startswith(worker.SYNTHETIC_ROOM_PREFIX) else [ordinary])
        assert api.closed
    asyncio.run(scenario())


def test_stop_before_run_never_opens_transport_or_provider(pipeline):
    async def scenario():
        obj = pipeline()
        connected = []
        async def connect(*args): connected.append(True)
        obj.room.connect = connect
        task = asyncio.create_task(obj.run())
        await obj.stop()
        await asyncio.wait_for(task, .5)
        assert not connected and not obj._llm.validated and not Synth.instances
        assert obj.ended.is_set() and obj._closed
    asyncio.run(scenario())


def test_model_access_failure_prevents_ready_and_welcome_and_closes_asr(pipeline, monkeypatch):
    async def scenario():
        obj = pipeline()
        recognizer = Recognizer()
        monkeypatch.setattr(session, 'FishASR', lambda key: recognizer)
        async def tablet(): pass
        async def capture():
            obj._mic_ready.set()
            await asyncio.Event().wait()
        async def denied(): raise RuntimeError('Translation model access unavailable')
        obj._wait_tablet = tablet; obj._capture_mic = capture; obj._llm.validate = denied
        assert await asyncio.wait_for(obj.run(), .5) is False
        assert not any(event['type'] in ('translation.ready', 'translation.welcome')
                       for event in obj.room.local_participant.messages)
        assert recognizer.closed and obj._capture_task.done()
        assert not Synth.instances
    asyncio.run(scenario())


def test_microphone_remains_continuous_during_output_and_preready_frames_are_kept(pipeline, monkeypatch):
    async def scenario():
        obj = pipeline()
        frames = [b'\x01\x00' * 1280, b'\x02\x00' * 1280]
        finish = asyncio.Event()
        class Stream:
            def __init__(self, *args, **kwargs):
                assert kwargs['frame_size_ms'] == 80
            def __aiter__(self): return self
            async def __anext__(self):
                if frames:
                    return SimpleNamespace(frame=SimpleNamespace(data=frames.pop(0)))
                await finish.wait()
                raise StopAsyncIteration
            async def aclose(self): pass
        monkeypatch.setattr(session.rtc, 'AudioStream', Stream)
        obj._mic_track = object()
        capture = asyncio.create_task(obj._capture_mic())
        await obj._mic_ready.wait()
        assert obj._microphone.qsize() == 2
        sent = []
        async def send(data): sent.append(data)
        obj.asr.send_audio = send
        obj.speaking = True
        pump = asyncio.create_task(obj._pump_mic())
        for _ in range(20):
            if len(sent) == 2: break
            await asyncio.sleep(0)
        assert sent == [b'\x01\x00' * 1280, b'\x02\x00' * 1280]
        capture.cancel(); pump.cancel()
        await asyncio.gather(capture, pump, return_exceptions=True)
    asyncio.run(scenario())


@pytest.mark.parametrize('fail', [False, True])
def test_first_stable_clause_plays_before_model_finishes_and_failure_keeps_it(pipeline, fail):
    async def scenario():
        release = asyncio.Event()
        class Streaming(Model):
            async def stream(self, prompt, original):
                yield 'First stable sentence.'
                await release.wait()
                if fail:
                    raise RuntimeError('Translation stream incomplete')
                yield ' Final sentence.'
        obj = pipeline(model=Streaming())
        Synth.chunks = [b'\x01\x00' * 960]
        record = final(obj)
        output = asyncio.create_task(obj._output(record['utterance_id']))
        while not Synth.instances:
            await asyncio.sleep(0)
        await asyncio.wait_for(Synth.instances[0].sent.wait(), .5)
        async with asyncio.timeout(.5):
            while not obj.room.local_participant.packets:
                await asyncio.sleep(0)
        partial = obj.conversation.get(record['utterance_id'])
        assert partial['translation'] == 'First stable sentence.'
        assert partial['translation_status'] == 'streaming'
        assert partial['playback_status'] == 'playing'
        assert not output.done() and not release.is_set()
        release.set()
        await asyncio.wait_for(output, .5)
        result = obj.conversation.get(record['utterance_id'])
        assert result['translation_status'] == ('error' if fail else 'complete')
        assert result['translation'] == ''.join(Synth.instances[0].texts)
        assert result['original'] == record['original']
        assert result['translation'].startswith('First stable sentence.')
    asyncio.run(scenario())


def test_cancel_before_output_coroutine_runs_settles_record(pipeline):
    async def scenario():
        obj = pipeline()
        record = final(obj)
        obj._active_id = record['utterance_id']
        obj.turn = asyncio.create_task(obj._output(record['utterance_id']))
        await obj._interrupt()
        settled = obj.conversation.get(record['utterance_id'])
        assert settled['translation_status'] == 'cancelled'
        assert settled['original'] == record['original']
        assert not Synth.instances
    asyncio.run(scenario())


def test_http_model_tokens_arrive_before_terminal_event(monkeypatch):
    async def scenario():
        release = asyncio.Event()
        class Body(httpx.AsyncByteStream):
            async def __aiter__(self):
                yield b'data: {"choices":[{"delta":{"content":"First sentence."},"finish_reason":null}]}\n\n'
                await release.wait()
                yield b'data: {"choices":[{"delta":{},"finish_reason":"stop"}]}\n\ndata: [DONE]\n\n'
        def respond(request):
            return httpx.Response(200, stream=Body())
        client = httpx.AsyncClient
        monkeypatch.setattr(llm.httpx, 'AsyncClient',
                            lambda **kwargs: client(transport=httpx.MockTransport(respond), **kwargs))
        stream = llm.TranslateStream('https://fixture.invalid', 'fixture', 'fixture').stream('system', 'hola')
        try:
            assert await asyncio.wait_for(anext(stream), .5) == 'First sentence.'
            assert not release.is_set()
            release.set()
            assert [text async for text in stream] == []
        finally:
            await stream.aclose()
    asyncio.run(scenario())


def test_receiver_credit_bounds_pcm_while_microphone_continues_and_eof_requires_render(pipeline):
    async def scenario():
        obj = pipeline(model=Model(['One complete sentence.']))
        await obj.start()
        sink = obj.room.local_participant
        sink.render = None
        raw = b'\x01\x00' * (960 * 9 + 7)
        Synth.chunks = [raw]
        record = final(obj)
        obj.turn = asyncio.create_task(obj._output(record['utterance_id']))
        async def packets(count):
            async with asyncio.timeout(.5):
                while len(sink.packets) < count:
                    await asyncio.sleep(0)
        def ack(generation, sequence, identity='device-fixture'):
            obj.room.handlers['data_received'](SimpleNamespace(topic='client-event',
                participant=SimpleNamespace(identity=identity),
                data=json.dumps({'type': 'translation.played',
                    'output_generation': generation, 'sequence': sequence}).encode()))
        await packets(6)
        assert len(sink.packets) == 6 and not obj.turn.done()
        assert all(packet[2] for packet in sink.packets)
        sent = asyncio.Event()
        async def mic(data):
            assert data == b'\x13\x00' * 1280
            sent.set()
        obj.asr.send_audio = mic
        pump = asyncio.create_task(obj._pump_mic())
        obj._microphone.put_nowait(b'\x13\x00' * 1280)
        await asyncio.wait_for(sent.wait(), .5)
        ack(1, 5, 'intruder')
        ack(99, 5)
        await asyncio.sleep(0)
        assert len(sink.packets) == 6 and obj._audio_ack == -1
        ack(1, 5)
        await packets(11)
        assert [packet[1] for packet in sink.packets] == list(range(11))
        assert b''.join(packet[2] for packet in sink.packets) == raw
        assert sink.packets[-1][2] == b''
        ack(1, 9)  # All PCM rendered, but the receiver has not consumed EOF.
        await asyncio.sleep(0)
        assert not obj.turn.done()
        assert obj.conversation.get(record['utterance_id'])['playback_status'] == 'playing'
        ack(1, 10)
        await asyncio.wait_for(obj.turn, .5)
        assert obj.conversation.get(record['utterance_id'])['playback_status'] == 'complete'
        assert not obj.played({'type': 'translation.played', 'output_generation': 1, 'sequence': 10})
        pump.cancel()
        await asyncio.gather(pump, return_exceptions=True)
        await obj.stop()
    asyncio.run(scenario())


@pytest.mark.parametrize('bad_ack', [False, True])
def test_missing_or_future_render_ack_is_error_not_sender_completion(pipeline, monkeypatch, bad_ack):
    async def scenario():
        monkeypatch.setattr(session, 'PLAYBACK_ACK_SECONDS', .02)
        obj = pipeline()
        obj.room.local_participant.render = None
        record = final(obj)
        obj.turn = asyncio.create_task(obj._output(record['utterance_id']))
        while not obj.room.local_participant.packets:
            await asyncio.sleep(0)
        if bad_ack:
            assert not obj.played({'type': 'translation.played', 'output_generation': 1, 'sequence': 900})
        await asyncio.wait_for(obj.turn, .5)
        result = obj.conversation.get(record['utterance_id'])
        assert result['playback_status'] == 'error' and result['translation_status'] == 'complete'
        assert result['original'] == record['original'] and result['translation']
        assert not any(event.get('type') == 'translation.output' and event['status'] == 'complete'
                       for event in obj.room.local_participant.messages)
    asyncio.run(scenario())


def test_interruption_announces_terminal_before_slow_provider_close(pipeline, monkeypatch):
    async def scenario():
        obj = pipeline()
        Synth.block = True
        closing, release = asyncio.Event(), asyncio.Event()
        async def close(synth):
            closing.set()
            await release.wait()
            synth.closed = True
        monkeypatch.setattr(Synth, 'close', close)
        record = final(obj)
        obj.turn = asyncio.create_task(obj._output(record['utterance_id']))
        while not Synth.instances:
            await asyncio.sleep(0)
        await Synth.instances[0].sent.wait()
        interrupted = asyncio.create_task(obj._interrupt())
        await asyncio.wait_for(closing.wait(), .5)
        snapshot = obj.conversation.get(record['utterance_id'])
        assert snapshot['translation_status'] == 'complete'
        assert snapshot['playback_status'] == 'interrupted' and snapshot['translation']
        assert any(event.get('type') == 'translation.output' and event['status'] == 'interrupted'
                   for event in obj.room.local_participant.messages)
        assert not interrupted.done()
        assert not obj.played({'type': 'translation.played', 'output_generation': 1, 'sequence': 0})
        release.set()
        await interrupted
        assert obj.conversation.get(record['utterance_id']) == snapshot
        assert sum(event.get('type') == 'translation.output' and event['status'] == 'interrupted'
                   for event in obj.room.local_participant.messages) == 1
    asyncio.run(scenario())


def test_speaking_hint_covers_model_wait_then_long_pause_and_resumes_pcm(pipeline, monkeypatch):
    async def scenario():
        monkeypatch.setattr(session, 'SPEAKING_PAUSE_SECONDS', .01)
        release = asyncio.Event()
        class Waiting(Model):
            async def stream(self, prompt, original):
                await release.wait()
                yield 'The complete translation.'
        obj = pipeline(model=Waiting())
        record = final(obj)
        output = asyncio.create_task(obj._output(record['utterance_id']))
        async with asyncio.timeout(.5):
            while False not in obj.asr.gates:
                await asyncio.sleep(.001)
        assert obj.asr.gates == [True, False]
        assert not obj.room.local_participant.packets
        release.set()
        await asyncio.wait_for(output, .5)
        assert obj.asr.gates == [True, False, True, False]
    asyncio.run(scenario())


@pytest.mark.parametrize('late_failure', [False, True])
def test_stop_owns_late_sdk_connection_and_disconnect_before_ack(pipeline, late_failure):
    async def scenario():
        obj = pipeline()
        connecting, release, disconnecting, disconnected = (asyncio.Event() for _ in range(4))
        async def connect(*args, options):
            assert options.connect_timeout == 10
            connecting.set()
            await release.wait()
            if late_failure:
                raise RuntimeError('fixture connect failure')
        async def disconnect():
            disconnecting.set()
            await disconnected.wait()
        obj.room.connect, obj.room.disconnect = connect, disconnect
        run = asyncio.create_task(obj.run())
        await connecting.wait()
        stop = asyncio.create_task(obj.stop())
        await asyncio.sleep(0)
        assert not obj._connect_task.cancelled() and not stop.done()
        release.set()
        await asyncio.wait_for(disconnecting.wait(), .5)
        assert not stop.done() and not obj._llm.validated and not Synth.instances
        repeated = asyncio.create_task(obj.stop())
        await asyncio.sleep(0)
        await asyncio.sleep(0)
        assert not repeated.done() and not stop.done()
        disconnected.set()
        await asyncio.wait_for(asyncio.gather(stop, repeated), .5)
        assert obj._connect_task.done() and not obj._connect_task.cancelled() and run.done()
    asyncio.run(scenario())
