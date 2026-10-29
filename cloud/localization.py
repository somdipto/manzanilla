"""Offline interface copy shared by Reception, Android and the staff page.

The catalogue is editable only in shared/reception-locales.json. Translation
presence is not human language review or provider/device speech qualification.
"""
from functools import lru_cache
import json
from pathlib import Path
import re

_UNAVAILABLE = "This service is unavailable. Please ask hotel staff."
_PLACEHOLDER = re.compile(r"\{([A-Za-z_][A-Za-z0-9_]*)\}")


def _unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"Duplicate locale key: {key}")
        result[key] = value
    return result


def _placeholders(text: str) -> tuple[str, ...]:
    literal = _PLACEHOLDER.sub("", text)
    if "{" in literal or "}" in literal:
        raise ValueError("Invalid named interface placeholder")
    return tuple(sorted(_PLACEHOLDER.findall(text)))


def _validate_catalogue(result: dict) -> None:
    if not isinstance(result, dict) or type(result.get("schema")) is not int or result["schema"] != 1:
        raise ValueError("Unsupported interface catalogue schema")
    languages = result.get("languages")
    if not isinstance(languages, list) or len(languages) != 52:
        raise ValueError("Interface catalogue requires all 52 languages")
    codes = set()
    for language in languages:
        if not isinstance(language, dict):
            raise ValueError("Invalid interface language")
        code = language.get("code")
        if not isinstance(code, str) or not re.fullmatch(r"[a-z]{2}", code) or code in codes:
            raise ValueError("Invalid or duplicate interface language code")
        if language.get("direction") not in ("ltr", "rtl"):
            raise ValueError("Invalid interface language direction")
        for field in ("name", "native"):
            if not isinstance(language.get(field), str) or not language[field].strip():
                raise ValueError("Missing interface language name")
        codes.add(code)
    if not {"es", "en"} <= codes:
        raise ValueError("Missing default interface languages")
    messages = result.get("messages")
    if not isinstance(messages, dict) or _UNAVAILABLE not in messages:
        raise ValueError("Missing interface messages")
    for message, translations in messages.items():
        if not isinstance(message, str) or not message.strip():
            raise ValueError("Invalid interface message ID")
        if not isinstance(translations, dict) or set(translations) != codes or translations.get("en") != message:
            raise ValueError("Incomplete interface message coverage")
        expected = _placeholders(message)
        for text in translations.values():
            if not isinstance(text, str) or not text.strip():
                raise ValueError("Blank interface translation")
            if _placeholders(text) != expected:
                raise ValueError("Mismatched interface placeholders")


@lru_cache(maxsize=1)
def catalogue() -> dict:
    """Load once per process; callers must treat the returned catalogue as read-only."""
    source = Path(__file__).resolve().parent.parent / "shared" / "reception-locales.json"
    with source.open(encoding="utf-8") as stream:
        result = json.load(stream, object_pairs_hook=_unique_object)
    _validate_catalogue(result)
    return result


@lru_cache(maxsize=1)
def _codes() -> frozenset[str]:
    return frozenset(language["code"] for language in catalogue()["languages"])


def ui_text(language: str, message: str, **values: str) -> str:
    """Substitute named plain-text values; never interpret HTML or format syntax."""
    code = language if isinstance(language, str) and language in _codes() else "es"
    messages = catalogue()["messages"]
    fallback = messages[_UNAVAILABLE][code]
    try:
        text = messages[message][code]
        names = set(_PLACEHOLDER.findall(text))
        if names != set(values) or any(not isinstance(value, str) for value in values.values()):
            return fallback
        if "{" in _PLACEHOLDER.sub("", text) or "}" in _PLACEHOLDER.sub("", text):
            return fallback
        if not text.strip():
            return fallback
        return _PLACEHOLDER.sub(lambda match: values[match[1]], text)
    except (KeyError, TypeError):
        return fallback
