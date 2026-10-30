#!/usr/bin/env python3
"""Exercise the actual worker over an isolated local LiveKit server.

This sends synthetic PCM through LiveKit and substitutes fake ASR, translation,
and TTS providers. It measures local event ordering, not real speech quality,
provider latency, acoustic echo, or tablet playback. Never uses real API keys.
Requires a disposable LiveKit server running in --dev mode on localhost.
"""
from __future__ import annotations

import argparse
import asyncio
import ipaddress
import json
import os
import sys
import time
import uuid
from pathlib import Path
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(ROOT / 'voice-worker'))
from livekit import api, rtc
import voice_worker.session as worker
from voice_worker.worker import SYNTHETIC_ROOM_PREFIX
from scripts.livekit_probe_playback import ProbePlayback

DEV_KEY = 'devkey'
DEV_SECRET = 'secret'


class Trace:
    def __init__(self):
        self.events = []

    def mark(self, name, **values):
        self.events.append((name, time.monotonic(), values))

    def close(self):
        pass


class SyntheticASR:
    instance = None

    def __init__(self, key):
        self.events = asyncio.Queue()
        self.ready = asyncio.Event()
        self.frames = 0
        self.injected = False
        self.speaking = False
        self.started = {'type': 'session_started', 'session_id': 'synthetic-asr'}
        SyntheticASR.instance = self

    async def connect(self):
        self.ready.set()

    async def agent_speaking(self, value):
        self.speaking = value

    async def send_audio(self, pcm):
        self.frames += 1
        if self.speaking and not self.injected and any(pcm):
            self.injected = True
            await self.events.put({'type': 'user_turn_taken', 'turn_index': 0})
            await self.events.put({'type': 'committed', 'language_code': 'es',
                                   'user_turn': True, 'turn_kind': 'user', 'turn_index': 0,
                                   'text': 'Synthetic interruption'})

    async def close(self):
        pass


class SyntheticLLM:
    async def validate(self):
        pass

    async def stream(self, prompt, text):
        yield 'Synthetic translation.'


class SyntheticTTS:
    def __init__(self, key, voice, *, language):
        self.audio = asyncio.Queue()
        self.error = False
        self.text = ''
        self.task = None

    async def connect(self):
        pass

    async def send_text(self, text):
        self.text += text

    async def flush(self):
        if self.task:
            return
        async def emit():
            count = 120 if self.text == worker.contract.greeting('es') else 15
            for _ in range(count):
                await self.audio.put((bytes((24, 252)) * 12 + bytes((232, 3)) * 12) * 20)
                await asyncio.sleep(.02)
        self.task = asyncio.create_task(emit())

    async def finish(self):
        if self.task:
            await self.task
        await self.audio.put(None)

    async def close(self):
        if self.task:
            self.task.cancel()
            await asyncio.gather(self.task, return_exceptions=True)


def local_url(url):
    parsed = urlsplit(url)
    if parsed.scheme != 'ws' or parsed.username or parsed.password or parsed.path not in ('', '/'):
        raise ValueError('Probe requires a local ws:// development server')
    host = parsed.hostname or ''
    if host != 'localhost' and not ipaddress.ip_address(host).is_loopback:
        raise ValueError('Probe must connect to loopback only')
    return url


async def run(url):
    # The native RTC library may read proxy variables without honoring NO_PROXY.
    for key in ('http_proxy', 'https_proxy', 'all_proxy', 'HTTP_PROXY', 'HTTPS_PROXY', 'ALL_PROXY'):
        os.environ.pop(key, None)
    worker.FishASR, worker.FishTTS = SyntheticASR, SyntheticTTS
    name = SYNTHETIC_ROOM_PREFIX + uuid.uuid4().hex
    trace = Trace()
    session = worker.InterpreterSession(name, livekit_url=url, api_key=DEV_KEY,
        api_secret=DEV_SECRET, fish_key='fixture', voice_id='fixture', llm=SyntheticLLM(),
        bench=trace, max_seconds=30, client_identity='fixture-device')
    room = rtc.Room()
    playback = ProbePlayback(room, session.agent_identity)
    await playback.start()
    received_audio = playback.received
    received_caption = asyncio.Event()
    records = []
    outputs = []


    @room.on('data_received')
    def data(packet):
        playback.accept(packet)
        if (packet.topic != 'agent-event' or not packet.participant
                or packet.participant.identity != session.agent_identity):
            return
        message = json.loads(packet.data)
        if message.get('type') == 'translation.output':
            outputs.append(message)
        if message.get('type') == 'translation.utterance':
            record = message['record']
            records.append(record)
            if (record['original'] == 'Synthetic interruption'
                    and record['translation'] == 'Synthetic translation.'
                    and record['translation_status'] == 'complete'):
                received_caption.set()

    token = (api.AccessToken(DEV_KEY, DEV_SECRET).with_identity('fixture-device')
             .with_metadata(json.dumps({'guest_language': 'es', 'staff_language': 'en'}))
             .with_grants(api.VideoGrants(room_join=True, room=name, can_publish=True,
                                          can_subscribe=True)).to_jwt())
    connection = asyncio.create_task(room.connect(url, token, options=rtc.RoomOptions(connect_timeout=10)))
    source = task = None
    try:
        await asyncio.shield(connection)
        source = rtc.AudioSource(16000, 1)
        await room.local_participant.publish_track(rtc.LocalAudioTrack.create_audio_track('fixture-mic', source))
        task = asyncio.create_task(session.run())
        await asyncio.wait_for(received_audio.wait(), 15)
        assert SyntheticASR.instance is not None and SyntheticASR.instance.speaking
        # Audio originates from the SDK track and is decoded by the real server.
        frame = rtc.AudioFrame(data=(bytes((24, 252)) * 8 + bytes((232, 3)) * 8) * 20, sample_rate=16000,
                               num_channels=1, samples_per_channel=320)
        for _ in range(40):
            await source.capture_frame(frame)
            if SyntheticASR.instance.injected:
                break
        for _ in range(20):
            if SyntheticASR.instance.injected:
                break
            await asyncio.sleep(.05)
        assert SyntheticASR.instance.injected, 'worker did not receive microphone audio during greeting'
        await asyncio.wait_for(received_caption.wait(), 15)
        if playback.error:
            raise playback.error
        expected = ['interrupted', 'playback_flushed', 'first_audio_published', 'record_sent']
        names = [entry[0] for entry in trace.events]
        for event in expected:
            assert event in names, (event, names)
        interrupted = next(t for e,t,v in trace.events if e == 'interrupted')
        flushed = next(t for e,t,v in trace.events if e == 'playback_flushed')
        first_turn = next(t for e,t,v in trace.events if e == 'first_audio_published' and v.get('generation') == 2)
        assert interrupted <= flushed <= first_turn
        assert SyntheticASR.instance.frames > 0 and SyntheticASR.instance.injected
        assert any(event['output_generation'] == 1 and event['status'] == 'interrupted' for event in outputs)
        assert len({record['utterance_id'] for record in records}) == 1
        result = {'result': 'PASS', 'server': 'local LiveKit 1.13.10',
            'providers': 'synthetic fakes; zero paid calls', 'device_audio_frames_received_by_worker': SyntheticASR.instance.frames,
            'device_received_agent_audio': received_audio.is_set(), 'interruption_flushed': True,
            'output_renderer': 'shipped AudioWorklet in headless Chromium; not physical speakers',
            'device_received_matching_utterance': True,
            'interrupt_event_to_local_flush_ms': round((flushed - interrupted) * 1000, 2),
            'scope': 'transport and ordering only; not acoustic latency, ASR accuracy or naturalness'}
    finally:
        await session.stop()
        stopped = await asyncio.gather(task, return_exceptions=True) if task else []
        await playback.close()
        try:
            if source:
                await source.aclose()
        finally:
            await asyncio.gather(connection, return_exceptions=True)
            await room.disconnect()
        for outcome in stopped:
            if isinstance(outcome, BaseException) and not isinstance(outcome, asyncio.CancelledError):
                raise outcome
    print(json.dumps(result))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--url', default='ws://127.0.0.1:17880')
    args = parser.parse_args()
    asyncio.run(run(local_url(args.url)))


if __name__ == '__main__':
    main()
