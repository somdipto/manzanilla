"""Synthetic checks for the live probe's evidence boundaries. No provider calls."""
import asyncio
import json
from types import SimpleNamespace

from scripts import livekit_pipeline_bench as probe


def test_marks_are_exactly_scoped_to_the_current_room(tmp_path):
    path = tmp_path / 'bench.jsonl'
    rows = [
        {'session': 'manz-bench-old', 'event': 'first_audio_published', 'generation': 2, 'ts': 100},
        {'session': 'manz-bench-new', 'event': 'first_audio_published', 'generation': 1, 'ts': 201},
        {'session': 'manz-bench-new', 'event': 'first_audio_published', 'generation': 2, 'ts': 203},
        {'session': 'manz-bench-new-extra', 'event': 'first_audio_published', 'generation': 2, 'ts': 204},
        {'session': 'manz-bench-new', 'event': 'asr_committed', 'ts': float('nan')},
    ]
    path.write_text('\n'.join(map(json.dumps, rows)) + '\ninvalid json\n')
    marks = probe.read_worker_marks(path, 'manz-bench-new')
    assert probe.worker_mark(marks, 'first_audio_published', generation=2) == 203
    assert probe.worker_mark(marks, 'asr_committed') is None
    assert probe.read_worker_marks(tmp_path / 'missing', 'manz-bench-new') == {}


def test_probe_never_passes_on_caption_alone():
    caption = {'payload': {'record': {'translation': 'synthetic', 'translation_status': 'complete'}}}
    assert probe.probe_errors(caption, 10.0, 640) == []
    assert probe.probe_errors(caption, None, 640)
    assert probe.probe_errors(caption, 10.0, 0)
    assert probe.probe_errors(None, 10.0, 640)
    assert probe.probe_errors({'payload': None}, 10.0, 640)
    assert probe.probe_errors({'payload': {'record': {'translation': '', 'translation_status': 'complete'}}}, 10.0, 640)
    assert probe.probe_errors({'payload': {'record': {'translation': 'partial', 'translation_status': 'error'}}}, 10.0, 640)


def test_input_end_is_marked_only_after_local_playout():
    trace = []

    class Source:
        async def capture_frame(self, frame):
            trace.append(('capture', frame.samples_per_channel))
        async def wait_for_playout(self):
            trace.append(('drained', None))

    def mark(event, **data):
        trace.append((event, data['utterance']))

    asyncio.run(probe.play_wav(None, Source(), b'\x01\x00' * 400, mark, 'fixture'))
    assert trace == [('capture', 320), ('capture', 320), ('drained', None),
                     ('device_input_end', 'fixture')]


def test_probe_only_acknowledges_current_worklet_render_not_packet_arrival():
    async def scenario():
        sent, render_commands = [], []
        async def publish_data(data, **options):
            sent.append((json.loads(data), options))
        room = SimpleNamespace(local_participant=SimpleNamespace(publish_data=publish_data))
        playback = probe.ProbePlayback(room, 'agent-fixture')
        async def evaluate(script, argument):
            render_commands.append(argument)
        playback._page = SimpleNamespace(evaluate=evaluate)
        from voice_worker import contract
        def packet(topic, data, identity='agent-fixture'):
            return SimpleNamespace(topic=topic, data=data, participant=SimpleNamespace(identity=identity))
        output = contract.output('fixture', 1, 'playing')
        playback.accept(packet(contract.EVENT_TOPIC, output, 'intruder'))
        assert playback.queue.empty()
        playback.accept(packet(contract.EVENT_TOPIC, output))
        playback.accept(packet(contract.AUDIO_TOPIC, contract.audio_packet(1, 0, b'\x01\x00')))
        playback.accept(packet(contract.AUDIO_TOPIC, contract.audio_packet(1, 1, b'')))
        consume = asyncio.create_task(playback._consume())
        try:
            async with asyncio.timeout(.5):
                while len(render_commands) < 3:
                    await asyncio.sleep(0)
            assert not sent
            assert playback.pcm == b'\x01\x00' and playback.received.is_set()
            await playback._rendered({'type': 'played', 'generation': 1, 'sequence': 0, 'eof': False})
            assert sent[-1][0] == {'type': 'translation.played', 'output_generation': 1, 'sequence': 0}
            await playback._rendered({'type': 'played', 'generation': 1, 'sequence': 1, 'eof': True})
            assert sent[-1][0]['sequence'] == 1
            assert all(options == {'reliable': True, 'topic': 'client-event'} for _, options in sent)
            playback.accept(packet(contract.EVENT_TOPIC, contract.output('fixture', 1, 'interrupted')))
            async with asyncio.timeout(.5):
                while playback._active_generation:
                    await asyncio.sleep(0)
            await playback._rendered({'type': 'played', 'generation': 1, 'sequence': 1, 'eof': True})
            assert len(sent) == 2
        finally:
            consume.cancel()
            await asyncio.gather(consume, return_exceptions=True)
    asyncio.run(scenario())
