"""The device-facing wire contract, identical to what Fish's hosted agent emits.

The Android client renders identified captions only from `agent-event` data
messages shaped as display-only client_tool.call reports (validated by
InterpretationReports). Agent state travels as the lk.agent.state participant
attribute. This module is the only place those payloads are built.
"""
import json

CAPTION_TOOL = 'manzanilla_publish_interpretation_v2'
STATE_ATTRIBUTE = 'lk.agent.state'
EVENT_TOPIC = 'agent-event'
TRANSCRIPTION_TOPIC = 'lk.transcription'
MAX_TEXT = 8000


def state(value: str) -> dict:
    """lk.agent.state values the client maps to listening/thinking/speaking."""
    assert value in ('listening', 'thinking', 'speaking', 'idle')
    return {STATE_ATTRIBUTE: value}


def caption(call_id: str, source_language: str, original: str, translation: str) -> bytes:
    """Display-only caption report; the device validates and dedupes by callId."""
    if not call_id or len(call_id) > 200:
        raise ValueError('invalid caption identity')
    if source_language == 'und':
        translation = ''
    if not original or len(original) > MAX_TEXT or len(translation) > MAX_TEXT:
        raise ValueError('invalid caption text')
    if source_language != 'und' and not translation:
        raise ValueError('a known-language caption needs its translation')
    return json.dumps({
        'type': 'client_tool.call',
        'toolName': CAPTION_TOOL,
        'callId': call_id,
        'expectsResponse': False,
        'params': {'source_language': source_language, 'original': original,
                   'translation': translation},
    }).encode()


def error(recovered: bool) -> bytes:
    return json.dumps({'type': 'error', 'recovered': recovered}).encode()


def session_ended(reason: str) -> bytes:
    assert reason in ('conversation_timeout', 'ended')
    return json.dumps({'type': 'session.ended', 'reason': reason}).encode()
