"""The display-only model report contract ships identically in both provisioning paths."""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def test_native_and_managed_caption_tool_contracts_match():
    managed = json.loads((ROOT / 'cloud/fish_caption_tool.json').read_text())
    native = json.loads((ROOT / 'android/hotel/src/main/assets/fish-caption-tool.json').read_text())
    assert managed == native
    assert managed['tool_type'] == 'client'
    assert managed['expects_response'] is False
    assert [argument['name'] for argument in managed['arguments']] == ['source_language', 'original', 'translation']
    assert not managed.get('url')
    assert not managed.get('headers')
