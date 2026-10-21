"""Speaker-side language tables in reception.js must cover every picker language.

Sides follow the two selected languages: script evidence decides distinct-script
pairs, common-word tables decide same-script pairs. This test proves the static
coverage of that design for the full DeskLanguages picker list.
"""
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DESK = ROOT / 'android/hotel/src/main/java/com/agentdeck/hotel/DeskLanguages.kt'
JS = ROOT / 'android/hotel/src/main/assets/reception/reception.js'


def picker_codes():
    block = re.search(r'val codes: List<String> = listOf\((.*?)\)', DESK.read_text(encoding='utf-8'), re.S).group(1)
    return re.findall(r'"([a-z]{2})"', block)


def table_keys(source, name):
    match = re.search(name + r"\s*=\s*\{(.*?)\};", source, re.S)
    assert match, f'{name} table exists'
    return set(re.findall(r"(?<![\w'])([a-z]{2})\s*:", match.group(1)))


def test_every_picker_language_has_side_evidence():
    source = JS.read_text(encoding='utf-8')
    codes = picker_codes()
    assert len(codes) == 52
    script_of = table_keys(source, 'SCRIPT_OF')
    words = table_keys(source, 'WORDS')
    assert script_of <= set(codes), 'SCRIPT_OF only names picker languages'
    assert words <= set(codes), 'WORDS only names picker languages'
    # Languages without a SCRIPT_OF entry default to the Latin group.
    script_groups = {}
    for code in codes:
        script_groups.setdefault(script_of and code if code in script_of else 'latn', None)
    # Map each code to its script group key exactly as the JS does.
    explicit = re.search(r'SCRIPT_OF\s*=\s*\{(.*?)\};', source, re.S).group(1)
    group_of = {code: re.search(code + r":'([a-z]+)'", explicit).group(1) if code in script_of else 'latn'
                for code in codes}
    groups = {}
    for code, group in group_of.items():
        groups.setdefault(group, []).append(code)
    for group, members in groups.items():
        if len(members) > 1 or group in ('jpan', 'hani'):
            # Same-script collisions (and Han/kana overlap) need word tables for every member.
            missing = [code for code in members if code not in words]
            assert not missing, f'script group {group} lacks common-word tables for {missing}'
        else:
            # A unique script always decides the side by itself.
            assert group in ('hebr', 'kore', 'beng', 'taml', 'telu', 'knda', 'gujr', 'guru',
                             'grek', 'thai', 'armn', 'geor'), f'unexpected singleton script {group}'


def test_word_tables_are_nontrivial_and_distinct():
    source = JS.read_text(encoding='utf-8')
    words_block = re.search(r'WORDS\s*=\s*\{(.*?)\n  \};', source, re.S).group(1)
    tables = {}
    for code, words in re.findall(r"([a-z]{2}):'([^']+)'", words_block):
        entries = set(words.split())
        assert len(entries) >= 10, f'{code} has a usable common-word table'
        tables[code] = entries
    script_of = table_keys(source, 'SCRIPT_OF')
    explicit = re.search(r'SCRIPT_OF\s*=\s*\{(.*?)\};', source, re.S).group(1)
    group_of = {code: re.search(code + r":'([a-z]+)'", explicit).group(1) if code in script_of else 'latn'
                for code in picker_codes()}
    groups = {}
    for code, group in group_of.items():
        groups.setdefault(group, []).append(code)
    for group, members in groups.items():
        if len(members) < 2:
            continue
        for i, a in enumerate(members):
            for b in members[i + 1:]:
                if a in tables and b in tables:
                    assert tables[a] != tables[b], f'{a}/{b} word tables must differ'
