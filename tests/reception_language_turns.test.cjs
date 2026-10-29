const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const { LanguageTurns, sideFor } = require('../android/hotel/src/main/assets/reception/language-turns.js');
const report = (id, language, original = 'Repeated phrase', text = 'Translation') => ({
  type: 'interpretation', utterance_id: id, source_language: language,
  language_attribution: 'model', original, text: language === 'und' ? '' : text
});
const caption = (id, text, extra = {}) => ({ type: 'caption', utterance_id: id, side: 'human', field: 'original', operation: 'replace', final: false, text, ...extra });

test('X Y Y X X follows selected languages while an uncertain third-language transcript stays neutral', () => {
  const ledger = new LanguageTurns();
  for (const [index, language] of ['en', 'gu', 'gu', 'en', 'en', 'und'].entries()) {
    ledger.accept(report(String(index), language, language === 'en' ? 'I need a room.' : 'મને એક રૂમ જોઈએ છે.'), 'en', 'gu');
  }
  assert.deepEqual(ledger.turns.map(t => t.side), ['guest', 'staff', 'staff', 'guest', 'guest', null]);
  assert.equal(ledger.turns[1].original, 'મને એક રૂમ જોઈએ છે.');
  assert.equal(ledger.turns[5].text, '');
});

test('all 2652 ordered picker pairs map both languages by selection, including repeats', () => {
  const source = fs.readFileSync('android/hotel/src/main/java/com/agentdeck/hotel/DeskLanguages.kt', 'utf8');
  const codes = [...source.match(/val codes: List<String> = listOf\(([\s\S]*?)\)/)[1].matchAll(/"([a-z]{2})"/g)].map(m => m[1]);
  assert.equal(codes.length, 52);
  for (const guest of codes) for (const staff of codes) {
    if (guest === staff) continue;
    const ledger = new LanguageTurns();
    for (const [id, language] of [['s1', staff], ['s2', staff], ['s3', staff], ['u1', 'und'], ['g1', guest], ['g2', guest], ['g3', guest], ['u2', 'und']]) ledger.accept(report(id, language), guest, staff);
    assert.deepEqual(ledger.turns.map(t => t.side), ['staff', 'staff', 'staff', null, 'guest', 'guest', 'guest', null], `${guest}/${staff}`);
  }
});

test('numbers, names and shared words remain neutral after either known speaker', () => {
  const ledger = new LanguageTurns();
  for (const lang of ['de', 'fr']) {
    ledger.accept(report(`${lang}-known`, lang), 'fr', 'de');
    for (const [i, text] of ['204', '204', 'Alex', 'OK', 'No'].entries()) ledger.accept(report(`${lang}-${i}`, 'und', text), 'fr', 'de');
    assert.equal(ledger.turns.at(-1).side, null);
  }
});

test('first ambiguous utterance is neutral and is never assigned from a later utterance', () => {
  const ledger = new LanguageTurns();
  ledger.accept(report('first', 'und', '204'), 'ja', 'es');
  assert.equal(ledger.turns[0].side, null);
  ledger.accept(report('second', 'es'), 'ja', 'es');
  assert.equal(ledger.turns[0].side, null);
  assert.equal(ledger.turns[1].side, 'staff');
});

test('script, common words and repeated text cannot override the model language', () => {
  const ledger = new LanguageTurns();
  ledger.accept(report('a', 'fr', 'Ich brauche ein Zimmer.'), 'fr', 'de');
  ledger.accept(report('b', 'de', 'Ich brauche ein Zimmer.'), 'fr', 'de');
  ledger.accept(report('c', 'und', 'Bonjour, merci'), 'fr', 'de');
  assert.deepEqual(ledger.turns.map(t => t.side), ['guest', 'staff', null]);
});

test('provider locale codes normalize but unsupported codes cannot change placement', () => {
  assert.equal(sideFor('FR-fr', 'fr', 'de'), 'guest');
  assert.equal(sideFor('de_DE', 'fr', 'de'), 'staff');
  const ledger = new LanguageTurns();
  ledger.accept(report('good', 'de'), 'fr', 'de');
  assert.deepEqual(ledger.accept(report('bad', 'en'), 'fr', 'de'), []);
  assert.equal(ledger.turns.length, 1);
  assert.deepEqual(ledger.accept({ ...report('bad2', 'fr'), language_attribution: 'guessed' }, 'fr', 'de'), []);
});

test('a report is atomic, immutable and deduplicated by call ID rather than text', () => {
  const ledger = new LanguageTurns();
  ledger.accept(report('a', 'de', 'Original', 'Paired translation'), 'fr', 'de');
  assert.deepEqual(ledger.accept(report('a', 'fr', 'Changed'), 'fr', 'de'), []);
  ledger.accept(report('b', 'de', 'Original', 'Paired translation'), 'fr', 'de');
  assert.equal(ledger.turns.length, 2);
  assert.equal(ledger.turns[0].original, 'Original');
  assert.equal(ledger.turns[0].text, 'Paired translation');
});

test('consecutive source segments stay separate even before any output arrives', () => {
  const ledger = new LanguageTurns();
  ledger.accept(caption('fr', 'Bonjour', { source_language: 'fr', language_attribution: 'model' }), 'fr', 'de');
  ledger.accept(caption('de', 'Guten Tag', { source_language: 'de', language_attribution: 'model' }), 'fr', 'de');
  assert.equal(ledger.turns.length, 2);
  assert.deepEqual(ledger.turns.map(t => t.side), ['guest', 'staff']);
});

test('translation cannot freeze an unknown source side or determine the next side', () => {
  const ledger = new LanguageTurns();
  ledger.accept(report('known', 'fr'), 'fr', 'de');
  ledger.accept(caption('new', '204', { final: true }), 'fr', 'de');
  ledger.accept(caption('out', 'Translation', { side: 'assistant', field: 'text', final: true, source_utterance_id: 'new' }), 'fr', 'de');
  assert.equal(ledger.turns.at(-1).side, null);
  ledger.accept(caption('new', 'Ich habe eine Frage.', { final: true, source_language: 'de', language_attribution: 'model' }), 'fr', 'de');
  assert.equal(ledger.turns.at(-1).side, 'staff');
  assert.equal(ledger.turns.at(-1).text, 'Translation');
});

test('late metadata updates its own source only, never uncertain successors', () => {
  const ledger = new LanguageTurns();
  ledger.accept(caption('old', '204', { final: true }), 'fr', 'de');
  ledger.accept(report('uncertain', 'und'), 'fr', 'de');
  ledger.accept(report('new-known', 'fr'), 'fr', 'de');
  ledger.accept(report('new-uncertain', 'und'), 'fr', 'de');
  ledger.accept(caption('old', '204', { final: true, source_language: 'de', language_attribution: 'model' }), 'fr', 'de');
  assert.deepEqual(ledger.turns.map(t => t.side), ['staff', null, 'guest', null]);
});

test('unlinked output, including a greeting, never claims a human side or consumes a source', () => {
  const ledger = new LanguageTurns();
  ledger.accept(caption('greeting', 'Welcome', { side: 'assistant', field: 'text' }), 'fr', 'de');
  ledger.accept(report('source', 'de'), 'fr', 'de');
  ledger.accept(caption('late', 'Bonjour', { side: 'assistant', field: 'text' }), 'fr', 'de');
  assert.deepEqual(ledger.turns.map(t => t.side), [null, 'staff', null]);
  assert.equal(ledger.turns[1].text, 'Translation');
});

test('final text rejects late partials while late explicit language remains usable', () => {
  const ledger = new LanguageTurns();
  ledger.accept(caption('a', 'Complete.', { final: true }), 'fr', 'de');
  ledger.accept(caption('a', 'Late partial', { source_language: 'de', language_attribution: 'model' }), 'fr', 'de');
  assert.equal(ledger.turns[0].original, 'Complete.');
  assert.equal(ledger.turns[0].side, 'staff');
});

test('reset clears IDs for a new pair', () => {
  const ledger = new LanguageTurns();
  ledger.accept(report('same-id', 'de'), 'fr', 'de');
  ledger.clear();
  ledger.accept(report('same-id', 'und'), 'zh', 'ja');
  assert.equal(ledger.turns.length, 1);
  assert.equal(ledger.turns[0].side, null);
});

test('a restart preserving visible history still scopes IDs to its new session', () => {
  const ledger = new LanguageTurns();
  ledger.accept({ ...report('same-id', 'de'), session_id: 'old', generation: 1 }, 'fr', 'de');
  ledger.accept({ ...report('same-id', 'und'), session_id: 'new', generation: 2 }, 'fr', 'de');
  assert.equal(ledger.turns.length, 2);
  assert.deepEqual(ledger.turns.map(t => t.side), ['staff', null]);
});

test('invalid report fields and a translation with unknown direction cannot create bubbles', () => {
  const ledger = new LanguageTurns();
  for (const event of [
    { ...report('a', 'und'), text: 'Guessed translation' },
    { ...report('b', 'de'), text: '' },
    { ...report('c', 'de'), original: 204 },
    { ...report('d', 'de'), original: '   ' }
  ]) assert.deepEqual(ledger.accept(event, 'fr', 'de'), []);
  assert.equal(ledger.turns.length, 0);
});

test('unclear whitespace translation is canonical empty while real text is preserved exactly', () => {
  const ledger = new LanguageTurns();
  const original = '  Alex, 204?\n';
  const translation = '  Álex, ¿204?\n';
  ledger.accept(report('staff', 'de', original, translation), 'fr', 'de');
  ledger.accept({ ...report('unclear-staff', 'und', original), text: ' \t\n' }, 'fr', 'de');
  ledger.accept(report('guest', 'fr', original, translation), 'fr', 'de');
  ledger.accept({ ...report('unclear-guest', 'und', original), text: '\u2003 ' }, 'fr', 'de');
  assert.deepEqual(ledger.turns.map(turn => turn.side), ['staff', null, 'guest', null]);
  assert.deepEqual(ledger.turns.map(turn => turn.text), [translation, '', translation, '']);
  assert.ok(ledger.turns.every(turn => turn.original === original));
});
