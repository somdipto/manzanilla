"""Revisioned ASR evidence, deterministic direction and explicit manual resolution."""
import re
import unicodedata
from uuid import uuid4

from .contract import LANGUAGES, MAX_TEXT

_SOUND = re.compile(r'\[[^\[\]]*\]')
_SPEAKER = re.compile(r'<\|speaker:\d+\|>')
_WORDS = re.compile(r'[^\W_]+', re.UNICODE)
_TRANSLATION = {'waiting', 'needs_direction', 'streaming', 'complete', 'cancelled', 'error', 'ignored'}
_PLAYBACK = {'idle', 'queued', 'playing', 'complete', 'interrupted', 'error'}


def speech_input(original: str) -> str:
    """Fish's documented markup stripping, only for derived translation input."""
    return ' '.join(_SOUND.sub('', _SPEAKER.sub('', original)).split())


def is_ambiguous(text: str) -> bool:
    """Conservatively withhold short/name/numeric turns; this is not another LID."""
    words = _WORDS.findall(text)
    letters = [char for char in text if unicodedata.category(char).startswith('L')]
    if not letters:
        return True
    # These writing systems do not normally delimit every word with a space.
    unsegmented = any('\u3400' <= char <= '\u9fff' or '\u3040' <= char <= '\u30ff'
                      or '\u0e00' <= char <= '\u0e7f' for char in letters)
    return len(words) < 2 and not (unsegmented and len(letters) >= 4)


class Conversation:
    def __init__(self, session_id: str, guest: str, staff: str):
        if not session_id or guest not in LANGUAGES or staff not in LANGUAGES:
            raise ValueError('invalid conversation')
        self.session_id, self.guest, self.staff = session_id, guest, staff
        self._records: dict[str, dict] = {}
        self._languages: dict[str, set[str]] = {}
        self._asr_session = uuid4().hex
        self._started = False

    @property
    def records(self) -> dict[str, dict]:
        return {key: value.copy() for key, value in self._records.items()}

    def get(self, utterance_id: str) -> dict:
        try:
            return self._records[utterance_id].copy()
        except KeyError:
            raise ValueError('unknown utterance') from None

    def accept(self, event: dict) -> dict | None:
        kind = event.get('type')
        if kind == 'session_started':
            identity = event.get('session_id')
            if not isinstance(identity, str) or not identity or len(identity) > 160:
                raise ValueError('invalid ASR session identity')
            if self._started or self._records:
                raise ValueError('ASR session cannot restart')
            self._asr_session, self._started = identity, True
            return None
        if kind not in ('speech_start', 'draft', 'committed'):
            return None
        index = event.get('turn_index')
        if type(index) is not int or index < 0:
            raise ValueError('invalid ASR turn identity')
        ident = f'{self._asr_session}:{index}'
        previous = self._records.get(ident)
        if previous and previous['recognition_status'] != 'partial':
            return None  # A committed record cannot be rewritten by late drafts/replays.
        if kind == 'speech_start' and previous:
            return None
        original = '' if kind == 'speech_start' else event.get('text')
        if not isinstance(original, str) or len(original) > MAX_TEXT:
            raise ValueError('invalid ASR transcript')
        detected = event.get('language_code')
        if detected is not None and (not isinstance(detected, str) or not re.fullmatch(r'[a-z]{2}', detected)):
            raise ValueError('invalid ASR language')
        degraded = event.get('degraded', False)
        user_turn = event.get('user_turn', True)
        if type(degraded) is not bool or type(user_turn) is not bool:
            raise ValueError('invalid ASR evidence')
        if detected:
            self._languages.setdefault(ident, set()).add(detected)
        final = kind == 'committed'
        spoken = speech_input(original)
        reason = 'uncertain'
        source = target = role = None
        status = 'waiting' if not final else 'needs_direction'
        if final:
            if not spoken:
                status = 'ignored'
            elif degraded or not user_turn:
                pass  # Preserve recoverable or acknowledgement speech for explicit direction.
            elif detected and detected not in (self.guest, self.staff):
                reason = 'mismatch'
            elif len(self._languages.get(ident, ())) > 1:
                reason = 'mixed'
            elif self.guest == self.staff:
                reason = 'same_language'
                source = target = self.guest
            elif detected and not is_ambiguous(spoken):
                source = detected
                role = 'guest' if source == self.guest else 'receptionist'
                target = self.staff if role == 'guest' else self.guest
                reason, status = 'recognized', 'waiting'
        record = {
            'session_id': self.session_id, 'utterance_id': ident,
            'revision': previous['revision'] + 1 if previous else 1,
            'source_language': source, 'target_language': target, 'role': role,
            'original': original, 'translation': '',
            'recognition_status': 'degraded' if final and degraded else 'final' if final else 'partial',
            'translation_status': status, 'playback_status': 'idle', 'output_generation': 0,
            'direction_reason': reason, 'detected_language': detected,
        }
        self._records[ident] = record
        return record.copy()

    def resolve(self, utterance_id: str, revision: int, role: str) -> dict:
        record = self.get(utterance_id)
        if (type(revision) is not int or revision != record['revision']
                or role not in ('guest', 'receptionist')
                or record['recognition_status'] not in ('final', 'degraded')
                or record['translation_status'] != 'needs_direction'
                or record['direction_reason'] == 'mismatch'):
            raise ValueError('direction resolution is not current or permitted')
        source, target = (self.guest, self.staff) if role == 'guest' else (self.staff, self.guest)
        record.update(source_language=source, target_language=target, role=role,
                      direction_reason='manual', translation_status='waiting',
                      revision=record['revision'] + 1)
        self._records[utterance_id] = record
        return record.copy()

    def update(self, utterance_id: str, **changes) -> dict:
        """Output stages can change output only, never rewrite recognition or routing."""
        if set(changes) - {'translation', 'translation_status', 'playback_status', 'output_generation'}:
            raise ValueError('immutable recognition field')
        record = self.get(utterance_id)
        candidate = {**record, **changes}
        if (not isinstance(candidate['translation'], str) or len(candidate['translation']) > MAX_TEXT
                or candidate['translation_status'] not in _TRANSLATION
                or candidate['playback_status'] not in _PLAYBACK
                or type(candidate['output_generation']) is not int
                or candidate['output_generation'] < record['output_generation']):
            raise ValueError('invalid output update')
        if record['role'] is None and (candidate['translation'] or candidate['playback_status'] in ('queued', 'playing', 'complete')):
            raise ValueError('unresolved utterance cannot produce output')
        if (record['translation_status'] == 'cancelled' or record['playback_status'] == 'interrupted') and candidate != record:
            raise ValueError('cancelled output cannot resume')
        if candidate == record:
            return record
        candidate['revision'] += 1
        self._records[utterance_id] = candidate
        return candidate.copy()
