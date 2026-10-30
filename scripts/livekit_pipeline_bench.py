"""End-to-end evidence bench for the self-hosted interpreter route (issue #8).

Brings up a local livekit-server (--dev), the voice-worker, and a scripted
reception device that plays a real synthesized utterance into the room and
records everything the worker emits: agent audio, lk.agent.state attributes and
agent-event utterance records. Every stage is a real provider call (Fish realtime ASR,
Gemini translation, Fish streaming TTS); nothing in the translation path
is canned. The only fixture is the device's own input speech, synthesized once
by Fish TTS because the bench has no human speaker.

Usage:
  FISH_API_KEY=... FISH_VOICE_ID=... GEMINI_API_KEY=... \\
  python scripts/livekit_pipeline_bench.py [--livekit-bin PATH] [--out DIR]

Writes bench.jsonl (raw timing marks) and bench.md (measured table) to --out.
"""
import argparse, asyncio, json, math, os, subprocess, sys, time, urllib.request, uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(ROOT / 'voice-worker'))

import httpx  # noqa: E402
from livekit import api, rtc  # noqa: E402

from voice_worker.audio import load_wav_16k, wav_bytes  # noqa: E402
from voice_worker.session import Bench  # noqa: E402
from voice_worker.worker import GEMINI_MODEL
from scripts.livekit_probe_playback import ProbePlayback

DEV_KEY, DEV_SECRET = 'devkey', 'secret'
# A real guest check-in utterance in Spanish; not curated for the pipeline.
UTTERANCE_ES = ('Buenas tardes, tengo una reserva a nombre de Garcia para dos noches, '
                'y queria saber si puedo hacer el check in ahora.')


def read_worker_marks(path: Path, room_name: str) -> dict:
    """Use only this exact room. JSONL may also contain previous probe runs."""
    marks = {}
    if not path.exists():
        return marks
    for line in path.read_text().splitlines():
        try:
            row = json.loads(line)
        except (ValueError, TypeError):
            continue
        if not isinstance(row, dict) or row.get('session') != room_name:
            continue
        timestamp, event = row.get('ts'), row.get('event')
        if (not isinstance(timestamp, (float, int)) or isinstance(timestamp, bool)
                or not math.isfinite(timestamp) or not isinstance(event, str)):
            continue
        marks.setdefault(event, []).append(row)
    return marks


def worker_mark(marks: dict, name: str, generation=None):
    for row in marks.get(name, []):
        if generation is None or row.get('generation') == generation:
            return row['ts']
    return None


def probe_errors(caption, first_capture, received_audio_bytes):
    """Smoke checks only; this never certifies acoustic quality or latency."""
    failures = []
    if not isinstance(caption, dict):
        failures.append('no caption reached the device')
    else:
        payload = caption.get('payload')
        params = payload.get('record') if isinstance(payload, dict) else None
        if (not isinstance(params, dict) or not params.get('translation')
                or params.get('translation_status') != 'complete'):
            failures.append('translation record incomplete')
    if first_capture is None:
        failures.append('no first-turn PCM publish attempt was recorded')
    if received_audio_bytes <= 0:
        failures.append('no agent PCM reached the renderer')
    return failures


def device_token(identity: str, room: str, metadata: dict) -> str:
    return (api.AccessToken(DEV_KEY, DEV_SECRET)
            .with_identity(identity).with_name('bench device')
            .with_metadata(json.dumps(metadata))
            .with_grants(api.VideoGrants(room_join=True, room=room))
            .to_jwt())


async def synthesize(fish_key: str, voice: str, text: str, out: Path):
    """One real Fish HTTP TTS call per fixture phrase, saved as 16 kHz WAV."""
    if out.exists():
        return
    async with httpx.AsyncClient(timeout=60) as client:
        response = await client.post(
            'https://api.fish.audio/v1/tts',
            headers={'Authorization': 'Bearer ' + fish_key, 'model': 's2.1-pro'},
            json={'text': text, 'reference_id': voice, 'format': 'pcm',
                  'sample_rate': 16000, 'normalize': True})
        response.raise_for_status()
    out.write_bytes(wav_bytes(response.content, 16000))


async def play_wav(room: rtc.Room, source: rtc.AudioSource, pcm: bytes, mark, label: str):
    """Stream 16 kHz mono PCM into the room at real-time pace in 20 ms frames."""
    frame_bytes = 320 * 2
    for offset in range(0, len(pcm), frame_bytes):
        piece = pcm[offset:offset + frame_bytes]
        if len(piece) < frame_bytes:
            piece += b'\x00' * (frame_bytes - len(piece))
        frame = rtc.AudioFrame(data=piece, sample_rate=16000, num_channels=1,
                               samples_per_channel=320)
        await source.capture_frame(frame)
    # The queue can retain samples after the last capture_frame returns.
    # This is still a local SDK playout boundary, not tablet acoustic input.
    await source.wait_for_playout()
    mark('device_input_end', utterance=label)


async def run(args):
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    marks = Bench(str(out / 'bench.jsonl'), 'bench-device')
    server = subprocess.Popen(
        [args.livekit_bin, '--dev', '--bind', '127.0.0.1', '--port', '7880'],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    worker_env = dict(os.environ,
                      PYTHONPATH=str(ROOT) + os.pathsep + os.environ.get('PYTHONPATH', ''),
                      LIVEKIT_URL='ws://127.0.0.1:7880',
                      LIVEKIT_API_KEY=DEV_KEY, LIVEKIT_API_SECRET=DEV_SECRET,
                      FISH_API_KEY=os.environ['FISH_API_KEY'],
                      FISH_VOICE_ID=os.environ['FISH_VOICE_ID'],
                      GEMINI_API_KEY=os.environ['GEMINI_API_KEY'], LIVEKIT_WORKER_ENABLED='1',
                      ROOM_PREFIX='manz-bench-',
                      VOICE_BENCH_LOG=str(out / 'bench.jsonl'))
    worker = subprocess.Popen(
        [sys.executable, '-m', 'voice_worker'], env=worker_env,
        cwd=str(ROOT / 'voice-worker'),
        stdout=open(out / 'worker.log', 'w'), stderr=subprocess.STDOUT)
    room_name = 'manz-bench-' + uuid.uuid4().hex
    room = rtc.Room()
    playback = ProbePlayback(room, 'agent-' + room_name[:40])
    connection = source = None
    try:
        # Establish the actual browser renderer before making any paid call.
        await playback.start()
        for _ in range(50):
            try:
                urllib.request.urlopen('http://127.0.0.1:7880', timeout=1)
                break
            except Exception:
                await asyncio.sleep(0.2)
        await synthesize(os.environ['FISH_API_KEY'], os.environ['FISH_VOICE_ID'],
                         UTTERANCE_ES, out / 'turn1.wav')

        events: list[dict] = []
        agent_audio = playback.pcm
        attributes: list[tuple[float, str]] = []


        @room.on('data_received')
        def _on_data(packet):
            playback.accept(packet)
            if (packet.topic == 'agent-event' and packet.participant
                    and packet.participant.identity == playback.agent_identity):
                events.append({'at': time.time(), 'payload': json.loads(packet.data)})

        @room.on('participant_attributes_changed')
        def _on_attrs(changed, participant):
            state = changed.get('lk.agent.state')
            if state:
                attributes.append((time.time(), state))

        connection = asyncio.create_task(room.connect('ws://127.0.0.1:7880',
                           device_token('device-' + room_name.removeprefix('manz-')[:24], room_name,
                                        {'guest_language': 'es', 'staff_language': 'en'}),
                           options=rtc.RoomOptions(connect_timeout=10)))
        await asyncio.shield(connection)
        source = rtc.AudioSource(16000, 1)
        track = rtc.LocalAudioTrack.create_audio_track('mic', source)
        await room.local_participant.publish_track(track)
        marks.mark('device_joined')
        # Wait for the actual welcome result, not an arbitrary delay. The
        # microphone remains live; agent_speaking is not an acoustic echo gate.
        async with asyncio.timeout(45):
            while not any(event['payload'].get('type') == 'translation.welcome'
                          and event['payload'].get('status') == 'complete' for event in events):
                if any(event['payload'].get('type') == 'error'
                       and event['payload'].get('recovered') is False for event in events):
                    raise RuntimeError('Pipeline startup failed')
                await asyncio.sleep(.05)

        # Turn 1: play the full utterance, expect translation audio, then a
        # caption with original + translation.
        pcm1 = load_wav_16k(str(out / 'turn1.wav'))
        marks.mark('device_input_start', utterance='turn1')
        await asyncio.create_task(play_wav(room, source, pcm1, marks.mark, 'turn1'))
        t_input_end = time.time()
        caption1 = None
        deadline = t_input_end + 60
        while time.time() < deadline:
            caption1 = next((e for e in events
                             if e['payload'].get('type') == 'translation.utterance'
                             and e['payload']['record'].get('translation_status') == 'complete'), None)
            if caption1:
                break
            await asyncio.sleep(0.05)
        marks.mark('device_turn1_done', caption_received=bool(caption1))
        if playback.error:
            raise playback.error

        # Only the current, randomly named room may contribute marks: the log
        # is append-only and may include previous runs in the same directory.
        worker_marks = read_worker_marks(out / 'bench.jsonl', room_name)
        table = []
        committed = worker_mark(worker_marks, 'asr_committed')
        generation = caption1['payload']['record']['output_generation'] if caption1 else 2
        llm_at = worker_mark(worker_marks, 'llm_requested', generation=generation)
        first_turn_audio = worker_mark(worker_marks, 'first_audio_published', generation=generation)
        caption_at = worker_mark(worker_marks, 'translation_complete', generation=generation)
        if committed:
            table.append(('local input playout end -> ASR commit (s)', round(committed - t_input_end, 2)))
        if llm_at is not None and first_turn_audio is not None:
            table.append(('LLM request -> first worker PCM publish attempt (s)',
                          round(first_turn_audio - llm_at, 2)))
        if first_turn_audio is not None:
            table.append(('local input playout end -> first worker PCM publish attempt (s)', round(first_turn_audio - t_input_end, 2)))
        if caption_at:
            table.append(('input end -> caption emitted after playback (s)', round(caption_at - t_input_end, 2)))
        if caption1:
            params = caption1['payload']['record']
            table.append(('caption lag on device after emit (ms)', round((caption1['at'] - caption_at) * 1000, 1) if caption_at else '?'))
            table.append(('turn 1 original (real ASR transcript)', params['original']))
            table.append(('turn 1 translation (real LLM output)', params['translation']))
            table.append(('turn 1 source_language', params['source_language']))
        agent = next((p for p in room.remote_participants.values()
                      if p.identity.startswith('agent-')), None)
        table.append(('lk.agent.state at end (device view)',
                      agent.attributes.get('lk.agent.state') if agent else 'agent gone'))
        states = ', '.join(f'{state}' for _, state in attributes)
        table.append(('lk.agent.state change events observed on device', states or 'none (see note)'))

        # The capture includes welcome PCM; Chromium worklet acknowledgements
        # establish render consumption, never physical speaker audibility.
        (out / 'agent-audio-session.wav').write_bytes(wav_bytes(bytes(agent_audio), 24000))
        lines = ['# Self-hosted route bench (issue #8)', '',
                 'Every stage below is a real provider call captured from live pipeline',
                 'events; the only fixture is the device input speech, synthesized by',
                 'Fish TTS because the bench has no human speaker.',
                 f'LLM model: {GEMINI_MODEL}.',
                 'Times use local SDK/worker wall-clock marks, not tablet acoustic input',
                 'or audible speaker onset. The first PCM-publish mark is an attempt.',
                 'Completion requires shipped AudioWorklet consumption in headless Chromium,',
                 'not physical speaker output. agent-audio-session.wav includes the greeting.',
                 'No matched hosted baseline, speech correctness or blind naturalness',
                 'assessment is established by this smoke probe.', '',
                 '| measurement | value |', '| --- | --- |']
        lines += [f'| {k} | {v} |' for k, v in table]
        (out / 'bench.md').write_text('\n'.join(lines) + '\n')
        print('\n'.join(lines))
        failures = probe_errors(caption1, first_turn_audio, len(agent_audio))
        for failure in failures:
            print('FAIL: ' + failure, file=sys.stderr)
        if failures:
            return 1
        return 0
    finally:
        try:
            if connection:
                await asyncio.gather(connection, return_exceptions=True)
            if source:
                await source.aclose()
            await room.disconnect()
            await playback.close()
        finally:
            worker.terminate()
            server.terminate()
            marks.close()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--livekit-bin', default='/tmp/lkbin/livekit-server')
    parser.add_argument('--out', default='/tmp/bench')
    args = parser.parse_args()
    raise SystemExit(asyncio.run(run(args)))


if __name__ == '__main__':
    main()
