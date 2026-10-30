"""Application-owned translation events; never provider client-tool calls."""
import json
from pathlib import Path
import struct

ROOT = Path(__file__).resolve().parents[2]
SPEECH_LANGUAGES = json.loads((ROOT / 'shared/translation-languages.json').read_text(encoding='utf-8'))['languages']
LANGUAGES = {row['code']: row['name'] for row in SPEECH_LANGUAGES}
_GREETINGS = json.loads((ROOT / 'cloud/fish_greetings.json').read_text(encoding='utf-8'))
STATE_ATTRIBUTE = 'lk.agent.state'
EVENT_TOPIC = 'agent-event'
MAX_TEXT = 8000
AUDIO_TOPIC = 'translation-audio-v1'
AUDIO_RATE = 24000
AUDIO_CHUNK_BYTES = 1920
AUDIO_CREDIT = 6
AUDIO_HEADER = struct.Struct('!4sIII')


def audio_packet(generation: int, sequence: int, pcm: bytes) -> bytes:
    if (type(generation) is not int or not 0 < generation <= 0xffffffff
            or type(sequence) is not int or not 0 <= sequence <= 0xffffffff
            or len(pcm) > AUDIO_CHUNK_BYTES or len(pcm) % 2):
        raise ValueError('invalid PCM packet')
    return AUDIO_HEADER.pack(b'MZP1', generation, sequence, AUDIO_RATE) + pcm


def read_audio_packet(packet: bytes) -> tuple[int, int, bytes]:
    if not 16 <= len(packet) <= 16 + AUDIO_CHUNK_BYTES or len(packet) % 2:
        raise ValueError('invalid PCM packet')
    magic, generation, sequence, rate = AUDIO_HEADER.unpack_from(packet)
    if magic != b'MZP1' or not generation or rate != AUDIO_RATE:
        raise ValueError('invalid PCM header')
    return generation, sequence, packet[16:]


def greeting(language: str) -> str:
    if language not in LANGUAGES or not _GREETINGS.get(language, '').strip():
        raise ValueError('unsupported welcome language')
    return _GREETINGS[language]


def state(value: str) -> dict:
    assert value in ('listening', 'thinking', 'speaking', 'idle')
    return {STATE_ATTRIBUTE: value}


def utterance(record: dict) -> bytes:
    return json.dumps({'type': 'translation.utterance', 'record': record}, ensure_ascii=False).encode('utf-8')


def ready(session_id: str, guest: str, staff: str) -> bytes:
    if guest not in LANGUAGES or staff not in LANGUAGES:
        raise ValueError('unsupported speech language')
    return json.dumps({'type': 'translation.ready', 'session_id': session_id,
                       'guest_language': guest, 'staff_language': staff}).encode()


def welcome(session_id: str, text: str, language: str, status: str, output_generation: int) -> bytes:
    if text != greeting(language) or status not in ('queued', 'playing', 'complete', 'interrupted', 'error'):
        raise ValueError('invalid welcome')
    return json.dumps({'type': 'translation.welcome', 'session_id': session_id, 'text': text,
                       'language': language, 'status': status,
                       'output_generation': output_generation}, ensure_ascii=False).encode('utf-8')


def output(session_id: str, generation: int, status: str) -> bytes:
    if status not in ('playing', 'complete', 'interrupted', 'error'):
        raise ValueError('invalid output status')
    return json.dumps({'type': 'translation.output', 'session_id': session_id,
                       'output_generation': generation, 'status': status}).encode()


def error(recovered: bool) -> bytes:
    return json.dumps({'type': 'error', 'recovered': recovered}).encode()


def session_ended(reason: str) -> bytes:
    assert reason in ('conversation_timeout', 'ended')
    return json.dumps({'type': 'session.ended', 'reason': reason}).encode()
