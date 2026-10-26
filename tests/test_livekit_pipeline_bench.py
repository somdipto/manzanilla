"""Synthetic checks for the live probe's evidence boundaries. No provider calls."""
import asyncio
import json

from scripts import livekit_pipeline_bench as probe


def test_marks_are_exactly_scoped_to_the_current_room(tmp_path):
    path = tmp_path / 'bench.jsonl'
    rows = [
        {'session': 'manz-bench-old', 'event': 'first_audio_published', 'turn': 1, 'ts': 100},
        {'session': 'manz-bench-new', 'event': 'first_audio_published', 'turn': 0, 'ts': 201},
        {'session': 'manz-bench-new', 'event': 'first_audio_published', 'turn': 1, 'ts': 203},
        {'session': 'manz-bench-new-extra', 'event': 'first_audio_published', 'turn': 1, 'ts': 204},
        {'session': 'manz-bench-new', 'event': 'asr_committed', 'ts': float('nan')},
    ]
    path.write_text('\n'.join(map(json.dumps, rows)) + '\ninvalid json\n')
    marks = probe.read_worker_marks(path, 'manz-bench-new')
    assert probe.worker_mark(marks, 'first_audio_published', turn=1) == 203
    assert probe.worker_mark(marks, 'asr_committed') is None
    assert probe.read_worker_marks(tmp_path / 'missing', 'manz-bench-new') == {}


def test_probe_never_passes_on_caption_alone():
    caption = {'payload': {'params': {'translation': 'synthetic'}}}
    assert probe.probe_errors(caption, 10.0, 640) == []
    assert probe.probe_errors(caption, None, 640)
    assert probe.probe_errors(caption, 10.0, 0)
    assert probe.probe_errors(None, 10.0, 640)
    assert probe.probe_errors({'payload': None}, 10.0, 640)
    assert probe.probe_errors({'payload': {'params': {'translation': ''}}}, 10.0, 640)


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
