const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { transformSync } = require('esbuild');

const root = path.join(__dirname, '..');
const source = transformSync(fs.readFileSync(path.join(root, 'ui/web/app.js'), 'utf8'), { format: 'cjs' }).code;
const markup = fs.readFileSync(path.join(root, 'ui/web/index.html'), 'utf8');
const languages = JSON.parse(fs.readFileSync(path.join(root, 'shared/translation-languages.json'), 'utf8')).languages;
const tick = () => new Promise(resolve => setImmediate(resolve));

// A DOM boundary fixture: run the real controller, keeping transport events explicit.
async function fixture(saved) {
  let document, transport;
  class Element extends EventTarget {
    constructor(tag) {
      super(); this.tag = tag; this.children = []; this.dataset = {}; this.attributes = {};
      this.value = ''; this.hidden = false; this.disabled = false; this.textContent = '';
      this.scrollTop = 0; this.scrollHeight = 100; this.clientHeight = 100;
    }
    append(...children) { this.children.push(...children); }
    replaceChildren(...children) { this.children = children; }
    setAttribute(key, value) { this.attributes[key] = value; }
    focus() { document.activeElement = this; }
    querySelectorAll(selector) {
      return this.children.flatMap(child => [
        ...(selector === child.tag || selector === `.${child.className}` ? [child] : []),
        ...child.querySelectorAll(selector),
      ]);
    }
    click() { if (!this.disabled) this.dispatchEvent(new Event('click')); }
  }
  const nodes = new Map();
  for (const match of markup.matchAll(/<([a-z]+)\b[^>]*\bid="([^"]+)"[^>]*>/g)) {
    const node = new Element(match[1]);
    node.hidden = /\bhidden\b/.test(match[0]);
    node.disabled = /\bdisabled\b/.test(match[0]);
    nodes.set(match[2], node);
  }
  document = { getElementById: id => nodes.get(id), createElement: tag => new Element(tag), activeElement: null };
  const storage = new Map(saved ? [['manzanilla.web.languages', JSON.stringify(saved)]] : []);
  class Translator extends EventTarget {
    constructor() { super(); transport = this; this.resolutions = []; }
    async status() { return { configured: true, key_configured: true, translation_key_configured: true, voice_id: 'voice', languages }; }
    async start(pair) { this.pair = pair; this.emit('state', { phase: 'listening', microphone: true }); }
    async stop() { this.stopped = true; this.emit('state', { phase: 'idle', microphone: false }); }
    async configure(values) { this.saved = values; return this.status(); }
    async resolveDirection(...args) { this.resolutions.push(args); }
    emit(type, detail) { this.dispatchEvent(new CustomEvent(type, { detail })); }
  }
  vm.runInNewContext(source, {
    require: name => { assert.equal(name, './translator.js'); return { Translator }; },
    document, localStorage: { getItem: key => storage.get(key) || null, setItem: (key, value) => storage.set(key, value) },
  });
  await tick();
  return { nodes, transport, storage, document, node: id => nodes.get(id), start: async () => { nodes.get('start-stop').click(); await tick(); } };
}
function record(id, changes = {}) {
  return { session_id: 'session', utterance_id: id, revision: 1, source_language: 'en', target_language: 'es',
    role: 'guest', original: 'Where is reception?', translation: '', recognition_status: 'partial',
    translation_status: 'waiting', playback_status: 'idle', output_generation: 0,
    direction_reason: 'recognized', detected_language: 'en', ...changes };
}

test('28 choices persist including same-language pair without blocking Start or Stop', async () => {
  const f = await fixture(['ar', 'ar']);
  assert.equal(f.node('guest-language').children.length, 28);
  assert.equal(f.node('guest-language').value, 'ar');
  assert.equal(f.node('staff-language').value, 'ar');
  assert.equal(f.node('start-stop').disabled, false);
  await f.start();
  assert.equal(f.transport.pair.guest_language, 'ar');
  assert.equal(f.transport.pair.staff_language, 'ar');
  assert.equal(f.node('start-stop').disabled, false);
  assert.equal(f.node('guest-language').disabled, true);
  f.node('start-stop').click();
  await tick();
  assert.equal(f.transport.stopped, true);
  f.node('staff-language').value = 'es';
  f.node('staff-language').dispatchEvent(new Event('change'));
  assert.deepEqual(JSON.parse(f.storage.get('manzanilla.web.languages')), ['ar', 'es']);
});

test('revision updates retain bubble identity and original through failure; role not text direction sets side', async () => {
  const f = await fixture();
  await f.start();
  f.transport.emit('utterance', record('a'));
  const article = f.node('transcript').children[0];
  f.transport.emit('utterance', record('a', { revision: 2, recognition_status: 'final', translation_status: 'error', original: ' أين الاستقبال؟ ', source_language: 'ar' }));
  f.transport.emit('utterance', record('a', { revision: 1, original: 'stale' }));
  assert.equal(f.node('transcript').children.length, 1);
  assert.equal(f.node('transcript').children[0], article);
  assert.equal(article.dataset.side, 'guest');
  assert.equal(article.querySelectorAll('.original-text')[0].textContent, ' أين الاستقبال؟ ');
  assert.equal(article.querySelectorAll('.original-text')[0].dir, 'auto');
  assert.match(article.querySelectorAll('.utterance-status')[0].textContent, /failed/);
  f.transport.emit('utterance', record('b', { role: 'receptionist', translation: 'مرحبا', target_language: 'ar', translation_status: 'complete' }));
  const receptionist = f.node('transcript').children[1];
  assert.equal(receptionist.dataset.side, 'receptionist');
  assert.equal(receptionist.querySelectorAll('.translation-text')[0].dir, 'auto');
  assert.equal(receptionist.querySelectorAll('.translation-text')[0].lang, 'ar');
  assert.equal(receptionist.querySelectorAll('.translation-section')[0].hidden, false);
});

test('normal speech has no direction prompt; uncertain records resolve exact revision and mismatch corrects selection', async () => {
  const f = await fixture();
  await f.start();
  f.transport.emit('utterance', record('normal', { recognition_status: 'final', translation_status: 'complete' }));
  assert.equal(f.node('transcript').children[0].querySelectorAll('button').length, 0);
  f.transport.emit('utterance', record('uncertain', { role: null, revision: 4, recognition_status: 'final', translation_status: 'needs_direction', direction_reason: 'uncertain' }));
  const uncertain = f.node('transcript').children[1];
  assert.equal(uncertain.dataset.side, 'unclear');
  uncertain.querySelectorAll('button')[1].click();
  await tick();
  assert.deepEqual(f.transport.resolutions[0], ['uncertain', 4, 'receptionist']);
  f.transport.emit('utterance', record('third', { role: null, recognition_status: 'final', translation_status: 'needs_direction', direction_reason: 'mismatch', detected_language: 'fr' }));
  const buttons = f.node('transcript').children[2].querySelectorAll('button');
  assert.equal(buttons.length, 1);
  buttons[0].click();
  await tick();
  assert.equal(f.transport.stopped, true);
  assert.equal(f.document.activeElement, f.node('guest-language'));
  assert.equal(f.node('transcript').children.length, 3);
});

test('welcome stays outside human history and secrets clear on save without browser persistence', async () => {
  const f = await fixture();
  await f.start();
  f.transport.emit('welcome', { text: 'مرحبا', language: 'ar', status: 'playing' });
  f.transport.emit('welcome', { text: 'مرحبا', language: 'ar', status: 'complete' });
  assert.equal(f.node('transcript').children.length, 0);
  assert.equal(f.node('welcome-status').children.length, 2);
  assert.equal(f.node('welcome-status').children[1].lang, 'ar');
  f.node('start-stop').click();
  await tick();
  f.node('api-key').value = 'private-fish';
  f.node('translation-api-key').value = 'private-gemini';
  f.node('settings-form').dispatchEvent(new Event('submit', { cancelable: true }));
  await tick();
  assert.equal(f.transport.saved.translation_api_key, 'private-gemini');
  assert.equal(f.node('api-key').value, '');
  assert.equal(f.node('translation-api-key').value, '');
  assert.equal([...f.storage.values()].some(value => value.includes('private-')), false);
});

test('degraded committed originals expose manual role controls while partials do not', async () => {
  const f = await fixture();
  await f.start();
  const uncertain = { role: null, source_language: null, target_language: null,
    translation_status: 'needs_direction', direction_reason: 'uncertain' };
  f.transport.emit('utterance', record('degraded', uncertain));
  const article = f.node('transcript').children[0];
  assert.equal(article.querySelectorAll('button').length, 0);
  f.transport.emit('utterance', record('degraded', { ...uncertain, revision: 2, recognition_status: 'degraded' }));
  assert.equal(article.querySelectorAll('button').length, 2);
  assert.equal(article.querySelectorAll('.original-text')[0].textContent, 'Where is reception?');
  assert.match(article.querySelectorAll('.utterance-status')[0].textContent, /Recognition is uncertain/);
  article.querySelectorAll('button')[0].click();
  await tick();
  assert.deepEqual(f.transport.resolutions[0], ['degraded', 2, 'guest']);
});

test('autoplay suspension labels pending audio as unplayed without hiding recognition or translation', async () => {
  const f = await fixture();
  await f.start();
  f.transport.emit('utterance', record('a', {
    recognition_status: 'final', translation_status: 'complete', translation: '¿Dónde está recepción?', playback_status: 'playing',
  }));
  f.transport.emit('welcome', { text: 'Welcome', language: 'en', status: 'playing' });
  const article = f.node('transcript').children[0];
  f.transport.emit('state', { phase: 'listening', microphone: true, audioBlocked: true });
  assert.match(article.querySelectorAll('.utterance-status')[0].textContent, /Audio queued, not playing/);
  assert.match(f.node('welcome-status').children[0].textContent, /audio queued/);
  assert.match(f.node('session-status').textContent, /queued, not playing/);
  assert.equal(f.node('resume-audio').hidden, false);
  assert.equal(article.querySelectorAll('.original-text')[0].textContent, 'Where is reception?');
  assert.equal(article.querySelectorAll('.translation-text')[0].textContent, '¿Dónde está recepción?');
  f.transport.emit('state', { phase: 'listening', microphone: true, audioBlocked: false });
  assert.match(article.querySelectorAll('.utterance-status')[0].textContent, /Playing audio/);
  assert.equal(f.node('resume-audio').hidden, true);
  f.transport.emit('utterance', record('a', {
    revision: 2, recognition_status: 'final', translation_status: 'complete',
    translation: '¿Dónde está recepción?', playback_status: 'interrupted',
  }));
  f.transport.emit('state', { phase: 'idle', microphone: false });
  assert.match(article.querySelectorAll('.utterance-status')[0].textContent, /Translation complete.*Audio interrupted/);
  assert.equal(f.node('transcript').children[0], article);
});

test('cancelled partial recognition no longer claims to be recognizing after local Stop', async () => {
  const f = await fixture();
  await f.start();
  f.transport.emit('utterance', record('a'));
  const article = f.node('transcript').children[0];
  f.transport.emit('utterance', record('a', { revision: 2, translation_status: 'cancelled', translation: ' Partial' }));
  f.transport.emit('state', { phase: 'idle', microphone: false });
  assert.doesNotMatch(article.querySelectorAll('.utterance-header')[0].textContent, /recognizing/);
  assert.match(article.querySelectorAll('.utterance-status')[0].textContent, /interrupted/);
  assert.equal(article.querySelectorAll('.original-text')[0].textContent, 'Where is reception?');
  assert.equal(article.querySelectorAll('.translation-text')[0].textContent, ' Partial');
});
