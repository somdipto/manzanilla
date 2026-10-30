"""One translation direction per request, with no transcript in system context."""
from .contract import LANGUAGES, ROOT

MAX_SECONDS = 600
_RUNTIME = (ROOT / 'shared/translation-runtime.txt').read_text(encoding='utf-8')


def interpreter_prompt(source: str, target: str) -> str:
    """Render only trusted language codes and catalogue names, never user text."""
    if source not in LANGUAGES or target not in LANGUAGES:
        raise ValueError('unsupported speech language')
    values = {
        'SOURCE_LANGUAGE_NAME': LANGUAGES[source], 'SOURCE_LANGUAGE_CODE': source,
        'TARGET_LANGUAGE_NAME': LANGUAGES[target], 'TARGET_LANGUAGE_CODE': target,
    }
    rendered = _RUNTIME
    for key, value in values.items():
        rendered = rendered.replace('{{' + key + '}}', value)
    return rendered


def translation_messages(source: str, target: str, original: str) -> list[dict]:
    """No conversation history, tool policy, or generated answer enters a request."""
    return [{'role': 'system', 'content': interpreter_prompt(source, target)},
            {'role': 'user', 'content': original}]
