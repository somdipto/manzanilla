#!/usr/bin/env python3
"""Read-only Fish history diagnostics. Never measures acoustic or per-model latency.

Use --history for an existing JSON export, or --session-id with FISH_API_KEY
in the environment to GET exactly one session. The report excludes transcripts,
credentials, transport tokens and unrecognized metadata. It never starts a call.
"""

import argparse
from collections import defaultdict
from datetime import datetime
import json
import os
from pathlib import Path
import re
import sys


METADATA = {'manzanilla_config_rev', 'guest_language', 'staff_language',
            'asr_model', 'llm_model', 'tts_model'}


def timestamp(value):
    if not isinstance(value, str):
        return None
    try:
        result = datetime.fromisoformat(value.replace('Z', '+00:00'))
        return result if result.tzinfo is not None else None
    except ValueError:
        return None


def summarize(history):
    if not isinstance(history, dict) or not isinstance(history.get('items'), list):
        raise ValueError('Expected a Fish session history object with an items array')
    turns = defaultdict(lambda: {'user': [], 'assistant': []})
    ungrouped = 0
    for item in history['items']:
        if not isinstance(item, dict) or item.get('type') != 'message':
            continue
        role, turn = item.get('role'), item.get('turn_id')
        if role not in ('user', 'assistant'):
            continue
        if type(turn) is not int:
            ungrouped += 1
            continue
        turns[turn][role].append(item)
    observations = []
    for turn, messages in sorted(turns.items()):
        row = {'turn_id': turn, 'user_messages': len(messages['user']),
               'assistant_messages': len(messages['assistant']), 'record_gap_ms': None}
        # Never pair nearest timestamps: require a provider turn id and one message per
        # role. Greeting-only, missing replies and duplicate/split responses stay visible.
        if len(messages['user']) == len(messages['assistant']) == 1:
            start = timestamp(messages['user'][0].get('created_at'))
            end = timestamp(messages['assistant'][0].get('created_at'))
            if start is not None and end is not None and end >= start:
                row.update(status='record_timestamps_only',
                           record_gap_ms=round((end-start).total_seconds()*1000, 3))
            else:
                row['status'] = 'missing_or_invalid_timestamps'
        elif not messages['user']:
            row['status'] = 'no_user_message'
        elif not messages['assistant']:
            row['status'] = 'no_assistant_message'
        else:
            row['status'] = 'ambiguous_multiple_messages'
        observations.append(row)
    metadata = history.get('metadata')
    return {
        'measurement': 'provider_message_record_gap_not_speech_latency',
        'limits': [
            'created_at is a history-record timestamp, not end of speech or audible output',
            'the documented message schema has no ASR, LLM or TTS latency spans',
            'latency_ms on a tool_result measures that tool, not the spoken response',
            'no percentiles or end-to-end acceptance can be inferred from these gaps',
        ],
        'requested_configuration': {key: metadata[key] for key in sorted(METADATA)
                                    if isinstance(metadata, dict) and isinstance(metadata.get(key), str)},
        'ungrouped_messages': ungrouped,
        'turns': observations,
    }


def fetch_session(session_id):
    if not re.fullmatch(r'[A-Za-z0-9_-]{1,200}', session_id):
        raise ValueError('Invalid session identifier')
    key = os.environ.get('FISH_API_KEY', '').strip()
    if not key:
        raise ValueError('Set FISH_API_KEY in the environment; do not put it in command arguments')
    import httpx
    # No configurable URL or redirects: an API credential can only reach Fish's endpoint.
    try:
        with httpx.Client(timeout=20, follow_redirects=False) as client:
            response = client.get('https://api.fish.audio/v1/agent/sessions/'+session_id,
                                  headers={'Authorization': 'Bearer '+key})
            response.raise_for_status()
            return response.json()
    except (httpx.HTTPError, ValueError):
        raise ValueError('Fish history could not be read; check session access and connectivity') from None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument('--history', type=Path, help='Existing Fish session JSON export')
    source.add_argument('--session-id', help='GET one session using FISH_API_KEY')
    args = parser.parse_args()
    try:
        history = json.loads(args.history.read_text(encoding='utf-8')) if args.history else fetch_session(args.session_id)
        print(json.dumps(summarize(history), indent=2, ensure_ascii=False))
    except (OSError, ValueError):
        # Avoid echoing raw provider responses, paths, credentials or supplied JSON.
        print('Cannot read valid session diagnostics. Check the input file or Fish session access and FISH_API_KEY configuration.', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
