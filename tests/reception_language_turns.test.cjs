const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const { LanguageTurns } = require('../ui/reception/language-turns.js');
const utterance = (id, changes = {}) => ({
  type: 'translation.utterance',
  session_id: changes.session_id ?? 'session-1',
  generation: 1,
  record: {
    session_id: 'session-1', utterance_id: id, revision: 1,
    source_language: null, target_language: null, role: null,
    original: '204', translation: '', recognition_status: 'final',
    translation_status: 'needs_direction', playback_status: 'idle',
    output_generation: 0, direction_reason: 'uncertain', detected_language: null,
    ...changes
  }
});
const directed = (id, role, source, target, changes = {}) => utterance(id, {
  role, source_language: source, target_language: target, detected_language: source,
  original: 'Recognized original', translation: 'Translated text',
  translation_status: 'complete', direction_reason: 'recognized', ...changes
});

test('partial and final revisions replace one utterance while preserving recognized originals verbatim', () => {
  const ledger = new LanguageTurns();
  const partial = '  مرحباً [laughter]\n';
  const original = '  مرحباً [laughter]، أحتاج غرفة ٢٠٤.\n';
  const first = ledger.accept(utterance('asr:1', {
    original: partial, recognition_status: 'partial', translation_status: 'waiting'
  }), 'ar', 'es');
  assert.equal(first.length, 1);
  assert.equal(first[0].original, partial);
  assert.equal(first[0].recognitionStatus, 'partial');
  assert.equal(first[0].side, null);
  const changed = ledger.accept(directed('asr:1', 'guest', 'ar', 'es', {
    revision: 2, original, translation: '  Necesito la habitación 204.\n'
  }), 'ar', 'es');
  assert.equal(changed.length, 1);
  assert.equal(ledger.turns.length, 1);
  assert.equal(changed[0], ledger.turns[0]);
  assert.equal(changed[0].utteranceId, 'asr:1');
  assert.equal(changed[0].revision, 2);
  assert.equal(changed[0].recognitionStatus, 'final');
  assert.equal(changed[0].status, 'complete');
  assert.equal(changed[0].side, 'guest');
  assert.equal(changed[0].original, original);
  assert.equal(changed[0].text, '  Necesito la habitación 204.\n');
});

test('guest guest guest staff staff guest follows each supplied record, never alternation', () => {
  const ledger = new LanguageTurns();
  for (const [index, role] of ['guest', 'guest', 'guest', 'receptionist', 'receptionist', 'guest'].entries()) {
    ledger.accept(directed(String(index), role, role === 'guest' ? 'en' : 'es', role === 'guest' ? 'es' : 'en'), 'en', 'es');
  }
  assert.deepEqual(ledger.turns.map(t => t.side), ['guest', 'guest', 'guest', 'staff', 'staff', 'guest']);
});

test('every distinct speech-language pair accepts explicit guest and receptionist evidence', () => {
  const codes = JSON.parse(fs.readFileSync('shared/translation-languages.json', 'utf8')).languages.map(language => language.code);
  for (const guest of codes) for (const staff of codes) {
    if (guest === staff) continue;
    const ledger = new LanguageTurns();
    ledger.accept(directed('g1', 'guest', guest, staff), guest, staff);
    ledger.accept(directed('g2', 'guest', guest, staff), guest, staff);
    ledger.accept(utterance('u1'), guest, staff);
    ledger.accept(directed('s1', 'receptionist', staff, guest), guest, staff);
    ledger.accept(directed('s2', 'receptionist', staff, guest), guest, staff);
    ledger.accept(utterance('u2'), guest, staff);
    assert.deepEqual(ledger.turns.map(t => t.side), ['guest', 'guest', null, 'staff', 'staff', null], `${guest}/${staff}`);
  }
});

test('und, numbers, names and shared words stay neutral after either role', () => {
  const ledger = new LanguageTurns();
  for (const role of ['guest', 'receptionist']) {
    ledger.accept(directed(`${role}-known`, role, role === 'guest' ? 'fr' : 'de', role === 'guest' ? 'de' : 'fr'), 'fr', 'de');
    for (const [i, original] of ['204', '204', 'Alex', 'OK', 'No'].entries()) {
      const [turn] = ledger.accept(utterance(`${role}-${i}`, { original, detected_language: 'und' }), 'fr', 'de');
      assert.equal(turn.side, null);
      assert.equal(turn.original, original);
      assert.equal(turn.text, '');
      assert.equal(turn.directionReason, 'uncertain');
      assert.equal(turn.status, 'needs_direction');
    }
  }
});

test('degraded and mixed recognition remain visible without claiming either role', () => {
  const ledger = new LanguageTurns();
  ledger.accept(directed('known', 'receptionist', 'de', 'fr'), 'fr', 'de');
  for (const direction_reason of ['uncertain', 'mixed']) {
    const [turn] = ledger.accept(utterance(direction_reason, {
      original: '  Bonjour / Guten Tag\n', recognition_status: 'degraded', direction_reason
    }), 'fr', 'de');
    assert.equal(turn.side, null);
    assert.equal(turn.recognitionStatus, 'degraded');
    assert.equal(turn.original, '  Bonjour / Guten Tag\n');
    assert.equal(turn.directionReason, direction_reason);
  }
});

test('inconsistent or nonfinal record evidence cannot claim a human side', () => {
  for (const changes of [
    { role: 'staff' },
    { source_language: 'de', detected_language: 'de' },
    { target_language: 'ja' },
    { detected_language: 'ja' },
    { recognition_status: 'partial' },
    { recognition_status: 'degraded' },
    { direction_reason: 'uncertain' }
  ]) {
    const ledger = new LanguageTurns();
    ledger.accept(directed('invalid', 'guest', 'fr', 'de', changes), 'fr', 'de');
    assert.ok(ledger.turns.every(turn => turn.side === null), JSON.stringify(changes));
  }
  const same = new LanguageTurns();
  same.accept(directed('same', 'guest', 'fr', 'fr'), 'fr', 'fr');
  assert.ok(same.turns.every(turn => turn.side === null), 'Equal languages require manual role evidence');
});

test('late manual direction affects only its own record, not earlier or later neutral turns', () => {
  const ledger = new LanguageTurns();
  ledger.accept(utterance('first'), 'fr', 'de');
  ledger.accept(utterance('second'), 'fr', 'de');
  ledger.accept(directed('known', 'guest', 'fr', 'de'), 'fr', 'de');
  ledger.accept(utterance('last'), 'fr', 'de');
  const changed = ledger.accept(directed('second', 'receptionist', 'de', 'fr', {
    revision: 2, original: '204', direction_reason: 'manual', detected_language: null
  }), 'fr', 'de');
  assert.deepEqual(changed.map(t => t.utteranceId), ['second']);
  assert.deepEqual(ledger.turns.map(t => t.side), [null, 'staff', 'guest', null]);
});

test('text, detected language and preceding translation do not supply a missing role', () => {
  const ledger = new LanguageTurns();
  ledger.accept(directed('known', 'guest', 'fr', 'de'), 'fr', 'de');
  ledger.accept(utterance('neutral', {
    original: 'Bonjour, merci', detected_language: 'fr'
  }), 'fr', 'de');
  ledger.accept(directed('literal', 'receptionist', 'de', 'fr', {
    original: 'Bonjour, merci'
  }), 'fr', 'de');
  assert.deepEqual(ledger.turns.map(t => t.side), ['guest', null, 'staff']);
});

test('same-language selection stays neutral until an explicit manual role revision', () => {
  for (const [role, side] of [['guest', 'guest'], ['receptionist', 'staff']]) {
    const ledger = new LanguageTurns();
    ledger.accept(utterance('same', {
      original: '¿Tiene una habitación?', detected_language: 'es', direction_reason: 'same_language'
    }), 'es', 'es');
    assert.equal(ledger.turns[0].side, null);
    const [turn] = ledger.accept(directed('same', role, 'es', 'es', {
      revision: 2, original: '¿Tiene una habitación?', translation: '¿Tiene una habitación?',
      direction_reason: 'manual'
    }), 'es', 'es');
    assert.equal(ledger.turns.length, 1);
    assert.equal(turn.side, side);
    assert.equal(turn.original, '¿Tiene una habitación?');
    assert.equal(turn.text, turn.original);
  }
});

test('third-language mismatch remains visible and neutral after either selected speaker', () => {
  const ledger = new LanguageTurns();
  for (const role of ['guest', 'receptionist']) {
    ledger.accept(directed(role, role, role === 'guest' ? 'fr' : 'de', role === 'guest' ? 'de' : 'fr'), 'fr', 'de');
    const [turn] = ledger.accept(utterance(`${role}-third`, {
      original: '部屋はありますか？', detected_language: 'ja', direction_reason: 'mismatch'
    }), 'fr', 'de');
    assert.equal(turn.side, null);
    assert.equal(turn.original, '部屋はありますか？');
    assert.equal(turn.status, 'needs_direction');
    assert.equal(turn.directionReason, 'mismatch');
  }
  assert.equal(ledger.turns.length, 4);
});

test('translation failure and cancellation retain recognized original and partial translation', () => {
  for (const status of ['error', 'cancelled']) for (const translation of ['', 'Wo ist']) {
    const ledger = new LanguageTurns();
    const original = '  Où est la chambre 204 ?\n';
    ledger.accept(directed(status, 'guest', 'fr', 'de', {
      original, translation, translation_status: 'streaming', output_generation: 1
    }), 'fr', 'de');
    const [turn] = ledger.accept(directed(status, 'guest', 'fr', 'de', {
      revision: 2, original, translation, translation_status: status,
      playback_status: status === 'cancelled' ? 'interrupted' : 'error', output_generation: 1
    }), 'fr', 'de');
    assert.equal(ledger.turns.length, 1);
    assert.equal(turn.original, original);
    assert.equal(turn.text, translation);
    assert.equal(turn.status, status);
    assert.equal(turn.side, 'guest');
  }
});

test('stale and duplicate revisions cannot overwrite text, status or direction', () => {
  const ledger = new LanguageTurns();
  const current = directed('id', 'guest', 'fr', 'de', { revision: 3, original: 'Final original' });
  ledger.accept(current, 'fr', 'de');
  const before = { ...ledger.turns[0] };
  for (const revision of [1, 2, 3]) {
    assert.deepEqual(ledger.accept(utterance('id', {
      revision, original: 'Late partial', recognition_status: 'partial', translation_status: 'waiting'
    }), 'fr', 'de'), []);
    assert.deepEqual(ledger.turns[0], before);
  }
});

test('identical text with distinct IDs stays separate and session identity scopes reused IDs', () => {
  const ledger = new LanguageTurns();
  ledger.accept(directed('one', 'guest', 'fr', 'de'), 'fr', 'de');
  ledger.accept(directed('two', 'guest', 'fr', 'de'), 'fr', 'de');
  ledger.accept(utterance('one', { session_id: 'session-2' }), 'fr', 'de');
  assert.equal(ledger.turns.length, 3);
  assert.deepEqual(ledger.turns.map(t => t.side), ['guest', 'guest', null]);
  ledger.clear();
  ledger.accept(utterance('one'), 'fr', 'de');
  assert.equal(ledger.turns.length, 1);
  assert.equal(ledger.turns[0].revision, 1);
  assert.equal(ledger.turns[0].side, null);
});

test('local Stop settles only its scoped history and rejects late provider resurrection', () => {
  const ledger = new LanguageTurns();
  ledger.accept(directed('streaming', 'guest', 'fr', 'de', {
    original: 'Original untouched', translation: 'Partial translation', translation_status: 'streaming', playback_status: 'playing'
  }), 'fr', 'de');
  ledger.accept(directed('translated', 'receptionist', 'de', 'fr', { playback_status: 'queued' }), 'fr', 'de');
  ledger.accept(utterance('uncertain'), 'fr', 'de');
  ledger.accept(directed('other', 'guest', 'fr', 'de', {
    session_id: 'another-session', translation_status: 'streaming', playback_status: 'playing'
  }), 'fr', 'de');
  ledger.settle('session-1', 1);
  assert.deepEqual(ledger.turns.map(turn => turn.status), ['cancelled', 'complete', 'cancelled', 'streaming']);
  assert.deepEqual(ledger.turns.map(turn => turn.playbackStatus), ['interrupted', 'interrupted', 'idle', 'playing']);
  assert.equal(ledger.turns[0].original, 'Original untouched');
  assert.equal(ledger.turns[0].text, 'Partial translation');
  assert.deepEqual(ledger.accept(directed('streaming', 'guest', 'fr', 'de', {
    revision: 99, original: 'Original untouched', playback_status: 'complete'
  }), 'fr', 'de'), []);
  assert.equal(ledger.turns[0].status, 'cancelled');
});

test('output revisions cannot rewrite a committed recognition original', () => {
  const ledger = new LanguageTurns();
  ledger.accept(directed('final', 'guest', 'fr', 'de', { original: 'Exact final original' }), 'fr', 'de');
  assert.deepEqual(ledger.accept(directed('final', 'guest', 'fr', 'de', {
    revision: 2, original: 'Reconstructed by a model'
  }), 'fr', 'de'), []);
  assert.equal(ledger.turns[0].original, 'Exact final original');
});

test('welcome, output and legacy hosted events never enter the human utterance ledger', () => {
  const ledger = new LanguageTurns();
  for (const event of [
    { type: 'translation.welcome', session_id: 'session-1', text: 'Bienvenue', language: 'fr', status: 'playing', output_generation: 1 },
    { type: 'translation.output', session_id: 'session-1', status: 'complete', output_generation: 1 },
    { type: 'interpretation', utterance_id: 'legacy', source_language: 'fr', language_attribution: 'model', original: 'Bonjour', text: 'Hallo' },
    { type: 'caption', utterance_id: 'legacy', side: 'human', field: 'original', operation: 'replace', final: true, text: 'Bonjour' }
  ]) assert.deepEqual(ledger.accept(event, 'fr', 'de'), []);
  assert.equal(ledger.turns.length, 0);
});

test('offline catalogue validates every offered language and named substitution contract', () => {
  const { parseCatalogue, placeholders } = require('../scripts/build-reception.cjs');
  const source = fs.readFileSync('shared/reception-locales.json', 'utf8');
  const catalogue = parseCatalogue(source);
  assert.equal(catalogue.languages.length, 52);
  for (const [message, translations] of Object.entries(catalogue.messages)) {
    for (const { code } of catalogue.languages) {
      assert.ok(translations[code].trim(), `${code}: ${message}`);
      assert.deepEqual([...placeholders(translations[code])].sort(), [...placeholders(message)].sort(), `${code}: ${message}`);
    }
  }
  assert.ok(catalogue.messages['This service is unavailable. Please ask hotel staff.']);
});

test('catalogue validation rejects incomplete locales, blank text and unsafe substitution contracts', () => {
  const { parseCatalogue } = require('../scripts/build-reception.cjs');
  const source = fs.readFileSync('shared/reception-locales.json', 'utf8');
  const original = JSON.parse(source);
  const message = Object.keys(original.messages).find(key => key.includes('{'));
  assert.ok(message, 'Real guest messages exercise named values');
  const invalid = change => {
    const value = JSON.parse(source);
    change(value);
    assert.throws(() => parseCatalogue(JSON.stringify(value)));
  };
  invalid(value => value.languages.pop());
  invalid(value => value.languages.push(value.languages[0]));
  invalid(value => { [value.languages[0], value.languages[1]] = [value.languages[1], value.languages[0]]; });
  invalid(value => { value.languages[0].direction = 'auto'; });
  invalid(value => { delete value.messages[message].ar; });
  invalid(value => { value.messages[message].es = ' \n\t '; });
  invalid(value => { value.messages[message].es = 'Sin el valor obligatorio'; });
  invalid(value => { value.messages[message].ar += ' {unexpected}'; });
  invalid(value => { delete value.messages['This service is unavailable. Please ask hotel staff.']; });
  const missingMessage = JSON.parse(source);
  delete missingMessage.messages['Welcome.'];
  assert.throws(() => parseCatalogue(JSON.stringify(missingMessage), ['Welcome.']));
  const duplicate = JSON.stringify(original).replace('"messages":{', '"messages":{' + JSON.stringify(message) + ':' + JSON.stringify(original.messages[message]) + ',');
  assert.throws(() => parseCatalogue(duplicate), 'Duplicate JSON message keys cannot silently overwrite translations');
});
