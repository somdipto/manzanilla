"""End-to-end evidence bench for the self-hosted interpreter route (issue #8).

Brings up a local livekit-server (--dev), the voice-worker, and a scripted
reception device that plays a real synthesized utterance into the room and
records everything the worker emits: agent audio, lk.agent.state attributes and
agent-event captions. Every stage is a real provider call (Fish realtime ASR,
an OpenAI-compatible LLM, Fish streaming TTS); nothing in the translation path
is canned. The only fixture is the device's own input speech, synthesized once
by Fish TTS because the bench has no human speaker.

Usage:
  FISH_API_KEY=... FISH_VOICE_ID=... LLM_API_KEY=... \
  python scripts/livekit_pipeline_bench.py [--llm-base URL] [--llm-model NAME] \
      [--livekit-bin PATH] [--out DIR]

Writes bench.jsonl (raw timing marks) and bench.md (measured table) to --out.
"""
import argparse, asyncio, json, os, subprocess, sys, time, urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(ROOT / 'voice-worker'))

import httpx  # noqa: E402
from livekit import api, rtc  # noqa: E402

from voice_worker.audio import load_wav_16k, wav_bytes  # noqa: E402
from voice_worker.session import Bench  # noqa: E402

DEV_KEY, DEV_SECRET = 'devkey', 'secret'
# A real guest check-in utterance in Spanish; not curated for the pipeline.
UTTERANCE_ES = ('Buenas tardes, tengo una reserva a nombre de Garcia para dos noches, '
                'y queria saber si puedo hacer el check in ahora.')
UTTERANCE_ES_2 = 'Muchas gracias, donde esta el ascensor?'


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
    mark('device_input_end', utterance=label)


async def run(args):
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    marks = Bench(str(out / 'bench.jsonl'), 'bench-device')
    server = subprocess.Popen(
        [args.livekit_bin, '--dev', '--bind', '127.0.0.1', '--port', '7880'],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    worker_env = dict(os.environ, LIVEKIT_URL='ws://127.0.0.1:7880',
                      LIVEKIT_API_KEY=DEV_KEY, LIVEKIT_API_SECRET=DEV_SECRET,
                      FISH_API_KEY=os.environ['FISH_API_KEY'],
                      FISH_VOICE_ID=os.environ['FISH_VOICE_ID'],
                      LLM_BASE_URL=args.llm_base, LLM_API_KEY=os.environ['LLM_API_KEY'],
                      LLM_MODEL=args.llm_model, ROOM_PREFIX='manz-bench-',
                      VOICE_BENCH_LOG=str(out / 'bench.jsonl'))
    worker = subprocess.Popen(
        [sys.executable, '-m', 'voice_worker'], env=worker_env,
        cwd=str(ROOT / 'voice-worker'),
        stdout=open(out / 'worker.log', 'w'), stderr=subprocess.STDOUT)
    room_name = 'manz-bench-' + str(int(time.time()))
    try:
        for _ in range(50):
            try:
                urllib.request.urlopen('http://127.0.0.1:7880', timeout=1)
                break
            except Exception:
                await asyncio.sleep(0.2)
        await synthesize(os.environ['FISH_API_KEY'], os.environ['FISH_VOICE_ID'],
                         UTTERANCE_ES, out / 'turn1.wav')
        await synthesize(os.environ['FISH_API_KEY'], os.environ['FISH_VOICE_ID'],
                         UTTERANCE_ES_2, out / 'turn2.wav')

        room = rtc.Room()
        events: list[dict] = []
        first_agent_audio: list[float] = []
        measure_from = [0.0]
        agent_audio = bytearray()
        attributes: list[tuple[float, str]] = []

        @room.on('track_subscribed')
        def _on_track(track, publication, participant):
            if track.kind != rtc.TrackKind.KIND_AUDIO:
                return
            async def consume():
                stream = rtc.AudioStream(track, sample_rate=48000, num_channels=1)
                async for frame_event in stream:
                    # Measure only audio that follows the device utterance; the
                    # earlier greeting is contract parity, not reply latency.
                    if not first_agent_audio and measure_from[0] and time.time() >= measure_from[0]:
                        first_agent_audio.append(time.time())
                    agent_audio.extend(bytes(frame_event.frame.data))
            asyncio.create_task(consume())

        @room.on('data_received')
        def _on_data(packet):
            if packet.topic == 'agent-event':
                events.append({'at': time.time(), 'payload': json.loads(packet.data)})

        @room.on('participant_attributes_changed')
        def _on_attrs(changed, participant):
            state = changed.get('lk.agent.state')
            if state:
                attributes.append((time.time(), state))

        await room.connect('ws://127.0.0.1:7880',
                           device_token('bench-device', room_name,
                                        {'guest_language': 'es', 'staff_language': 'en'}))
        source = rtc.AudioSource(16000, 1)
        track = rtc.LocalAudioTrack.create_audio_track('mic', source)
        await room.local_participant.publish_track(track)
        marks.mark('device_joined')

        # Turn 1: play the full utterance, expect greeting-then-translation audio,
        # then a caption with original + translation.
        pcm1 = load_wav_16k(str(out / 'turn1.wav'))
        marks.mark('device_input_start', utterance='turn1')
        measure_from[0] = time.time()
        await asyncio.create_task(play_wav(room, source, pcm1, marks.mark, 'turn1'))
        t_input_end = time.time()
        caption1 = None
        deadline = t_input_end + 60
        while time.time() < deadline:
            caption1 = next((e for e in events
                             if e['payload'].get('toolName')
                             == 'manzanilla_publish_interpretation_v2'), None)
            if caption1:
                break
            await asyncio.sleep(0.05)
        marks.mark('device_turn1_done', caption_received=bool(caption1))

        table = []
        if first_agent_audio:
            table.append(('device input end -> first agent audio (turn 1, incl. greeting)',
                          round(first_agent_audio[0] - t_input_end, 3)))
        if caption1:
            table.append(('device input end -> caption on device (turn 1)',
                          round(caption1['at'] - t_input_end, 3)))
            params = caption1['payload']['params']
            table.append(('turn 1 original (real ASR transcript)', params['original']))
            table.append(('turn 1 translation (real LLM output)', params['translation']))
            table.append(('turn 1 source_language', params['source_language']))
        states = ', '.join(f'{state}' for _, state in attributes)
        table.append(('lk.agent.state sequence observed', states or 'none'))

        (out / 'agent-audio-turn1.wav').write_bytes(wav_bytes(bytes(agent_audio), 48000))
        lines = ['# Self-hosted route bench (issue #8)', '',
                 'Every stage below is a real provider call captured from live pipeline',
                 'events; the only fixture is the device input speech, synthesized by',
                 'Fish TTS because the bench has no human speaker.', '',
                 '| measurement | value |', '| --- | --- |']
        lines += [f'| {k} | {v} |' for k, v in table]
        (out / 'bench.md').write_text('\n'.join(lines) + '\n')
        print('\n'.join(lines))
        if not caption1:
            print('FAIL: no caption reached the device', file=sys.stderr)
            return 1
        if not caption1['payload']['params']['translation']:
            print('FAIL: caption translation empty', file=sys.stderr)
            return 1
        return 0
    finally:
        worker.terminate()
        server.terminate()
        marks.close()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--llm-base', default='https://integrate.api.nvidia.com/v1')
    parser.add_argument('--llm-model', default='google/gemma-3-12b-it')
    parser.add_argument('--livekit-bin', default='/tmp/lkbin/livekit-server')
    parser.add_argument('--out', default='/tmp/bench')
    args = parser.parse_args()
    raise SystemExit(asyncio.run(run(args)))


if __name__ == '__main__':
    main()
