/* Browser-only fixture. Serve the repository root, then import and call this module. */
export async function runReceptionCaptionFixture(assetBase = new URL('../android/hotel/src/main/assets/reception/', import.meta.url)) {
  const assert = (condition, message) => { if (!condition) throw new Error(message); };
  const response = await fetch(new URL('index.html', assetBase));
  assert(response.ok, 'Reception HTML is available');
  const html = new DOMParser().parseFromString(await response.text(), 'text/html');
  // Only the isolated fixture removes CSP to install its fake native bridge before startup.
  html.querySelectorAll('script,meta[http-equiv="Content-Security-Policy"]').forEach(node => node.remove());
  const base = html.createElement('base');
  base.href = String(assetBase);
  html.head.prepend(base);
  const frame = document.createElement('iframe');
  frame.title = 'Reception caption fixture';
  frame.width = '837';
  frame.height = '471';
  const loaded = new Promise(resolve => frame.addEventListener('load', resolve, { once: true }));
  frame.srcdoc = '<!doctype html>' + html.documentElement.outerHTML;
  document.body.appendChild(frame);
  await loaded;
  const win = frame.contentWindow;
  const doc = frame.contentDocument;
  const messages = [];
  win.ManzaniaNative = { postMessage: value => messages.push(JSON.parse(value)) };
  const locale = doc.createElement('script');
  locale.src = new URL('locales.js', assetBase).href;
  const localeLoaded = new Promise((resolve, reject) => { locale.onload = resolve; locale.onerror = reject; });
  doc.body.appendChild(locale);
  await localeLoaded;
  const evidence = doc.createElement('script');
  evidence.src = new URL('language-turns.js', assetBase).href;
  const evidenceLoaded = new Promise((resolve, reject) => { evidence.onload = resolve; evidence.onerror = reject; });
  doc.body.appendChild(evidence);
  await evidenceLoaded;
  const script = doc.createElement('script');
  script.src = new URL('reception.js', assetBase).href;
  const started = new Promise((resolve, reject) => {
    script.addEventListener('load', resolve, { once: true });
    script.addEventListener('error', reject, { once: true });
  });
  doc.body.appendChild(script);
  await started;
  const receive = event => win.manzania.receive(event);
  const click = selector => {
    const button = doc.querySelector(selector);
    assert(button && !button.disabled, `Enabled control: ${selector}`);
    button.click();
  };
  receive({ type: 'reception_info', audio_available: true });
  receive({ type: 'language_preferences', language: 'en', staff_language: 'es' });
  receive({ type: 'reception_info', audio_available: true, local_audio_state: 'unknown' });
  assert(doc.body.textContent.includes('Mic state unknown'), 'Recreated UI preserves unresolved microphone ownership');
  receive({ type: 'reception_info', audio_available: true, local_audio_state: 'off' });
  const begin = (verifySelection = false) => {
    click('[data-action="translation"]');
    assert(messages.at(-1).action === 'prepare_translation', 'Language screen prepares configuration before Start');
    if (verifySelection) {
      const starts = messages.filter(message => message.action === 'start').length;
      for (const [side, value] of [['language', 'en'], ['staff_language', 'fr'], ['language', 'ko'], ['staff_language', 'es']]) {
        const picker = doc.querySelector(`[data-language-side="${side}"]`);
        picker.value = value;
        picker.dispatchEvent(new win.Event('change', { bubbles: true }));
        const prepared = messages.at(-1);
        assert(prepared.action === 'prepare_translation' &&
          prepared.language === doc.querySelector('#language').value &&
          prepared.staff_language === doc.querySelector('#staff_language').value, 'Picker changes prepare the exact selected pair');
        assert(messages.filter(message => message.action === 'start').length === starts && !doc.querySelector('.voice-ball'), 'Preparation does not start capture');
      }
    }
    click('[data-action="start"]');
    const session_id = messages.filter(message => message.action === 'start').at(-1).session_id;
    const scope = { session_id, generation: 1 };
    assert(doc.querySelector('.voice-ball')?.disabled, 'Connecting does not offer capture controls');
    assert(doc.querySelector('.voice-ball')?.dataset.hearing === 'false', 'Connecting keeps capture off');
    receive({ ...scope, type: 'audio_state', state: 'recording' });
    receive({ ...scope, type: 'reception_event', phase: 'listening' });
    return scope;
  };
  let scope = begin();
  const caption = (utterance_id, text, extra = {}) => receive({ ...scope, type: 'caption', utterance_id, field: 'original', side: 'human', operation: 'replace', final: false, text, ...extra });
  const report = (utterance_id, source_language, original, text = source_language === 'und' ? '' : 'Translation') => receive({ ...scope, type: 'interpretation', utterance_id, source_language, original, text, language_attribution: 'model' });
  const bubbles = () => [...doc.querySelectorAll('.caption')];
  const exact = '<img src=x onerror="throw Error(1)">\n  Exact source & punctuation.';
  report('first', 'und', exact);
  const first = bubbles()[0];
  assert(first.classList.contains('caption-pending'), 'First unclear utterance stays neutral');
  assert(first.querySelector('.said').textContent === exact && !first.querySelector('img'), 'Original text is exact and escaped');
  report('staff1', 'es', 'Sí, tenemos una habitación doble.', 'Yes, we have a double room.');
  report('staff2', 'es', 'Sí, tenemos una habitación doble.', 'Yes, we have a double room.');
  report('staff3', 'es', 'Sí, tenemos una habitación doble.', 'Yes, we have a double room.');
  assert(bubbles().slice(1).every(node => node.classList.contains('caption-reception')), 'Three identical receptionist utterances stay right');
  assert(bubbles()[1].querySelector('.translated').textContent === 'Yes, we have a double room.', 'Translation stays in its source bubble');
  report('staff-number1', 'und', '204');
  report('staff-number2', 'und', '204');
  assert(bubbles().slice(-2).every(node => node.classList.contains('caption-pending')), 'Repeated unclear numbers stay neutral after reception');
  report('guest1', 'en', 'Where is the lift?', '¿Dónde está el ascensor?');
  report('guest2', 'en', 'Where is the lift?', '¿Dónde está el ascensor?');
  report('guest3', 'en', 'Where is the lift?', '¿Dónde está el ascensor?');
  report('guest-name', 'und', 'Alex', ' \t\n');
  assert(bubbles().slice(-4, -1).every(node => node.classList.contains('caption-guest')), 'Guest repeats stay left');
  assert(bubbles().at(-1).classList.contains('caption-pending') && !bubbles().at(-1).querySelector('.translated'), 'Unclear whitespace report has no speaker or translated section');
  const count = bubbles().length;
  report('guest1', 'es', 'A duplicate must not overwrite a record.');
  assert(bubbles().length === count && bubbles()[6].classList.contains('caption-guest'), 'Duplicate report cannot add or move bubbles');
  caption('late', '204', { final: true });
  caption('output', 'Output before language', { side: 'assistant', field: 'text', final: true, source_utterance_id: 'late' });
  caption('late', 'Sí, tenemos otra habitación.', { final: true, source_language: 'es', language_attribution: 'model' });
  const late = bubbles().at(-1);
  assert(late.classList.contains('caption-reception') && late.querySelector('.caption-side').textContent.includes('Receptionist'), 'Language arriving after translation updates position and label');
  caption('late', 'Stale partial');
  assert(!late.textContent.includes('Stale partial'), 'Final source rejects a late partial');
  const before = win.performance.now();
  for (let id = 0; id < 120; id++) report(`replay-${id}`, id % 4 === 0 ? 'en' : id % 4 === 1 ? 'es' : 'und', `Room ${id}`);
  const replayMs = win.performance.now() - before;
  assert(bubbles()[0] === first, 'Streaming updates preserve existing bubble nodes');
  const mic = doc.querySelector('.voice-ball');
  mic.click();
  assert(messages.at(-1).action === 'mute' && messages.at(-1).muted === true, 'Microphone toggle sends mute');
  receive({ ...scope, type: 'audio_state', state: 'muted' });
  assert(doc.querySelector('.voice-ball').getAttribute('aria-pressed') === 'true', 'Muted state remains visible');
  doc.querySelector('.voice-ball').click();
  receive({ ...scope, type: 'audio_state', state: 'recording' });
  receive({ ...scope, type: 'mic_level', level: 0.75, bands: [0.7, 0.5, 0.2] });
  for (const [audio, label] of [['muted', 'Unmute the microphone'], ['off', 'Microphone off'], ['unknown', 'Microphone state unknown · ask staff']]) {
    receive({ ...scope, type: 'audio_state', state: 'recording' });
    receive({ ...scope, type: 'mic_level', level: 0.8, bands: [0.7, 0.5, 0.2] });
    assert(doc.querySelector('.voice-ball').style.getPropertyValue('--level') === '0.8', 'Recording uses measured levels');
    receive({ ...scope, type: 'audio_state', state: audio });
    const checkStoppedMeter = () => {
      const button = doc.querySelector('.voice-ball');
      assert(['--level','--b0','--b1','--b2'].every(key => button.style.getPropertyValue(key) === '0'), `${audio} clears every animation input`);
      assert(button.dataset.hearing === 'false' && button.getAttribute('aria-label') === label, `${audio} has truthful accessible capture state`);
      assert(bubbles()[0] === first, `${audio} keeps existing transcript nodes`);
    };
    checkStoppedMeter();
    receive({ type: 'connection', connected: true });
    checkStoppedMeter();
    receive({ ...scope, generation: 0, type: 'mic_level', level: 1, bands: [1, 1, 1] });
    checkStoppedMeter();
  }
  receive({ ...scope, type: 'audio_state', state: 'recording' });
  assert(bubbles()[0] === first, 'Mic events do not rebuild captions');
  receive({ ...scope, type: 'input_activity' });
  assert(messages.at(-1).action === 'activity' && bubbles()[0] === first, 'Raw input keeps activity alive without placing a bubble');
  const scroller = doc.querySelector('.session-scroll');
  scroller.scrollTop = scroller.scrollHeight;
  report('follow', 'es', 'Nueva frase.', 'New phrase.');
  assert(scroller.scrollHeight - scroller.clientHeight - scroller.scrollTop < 2, 'Bottom readers follow new captions');
  scroller.scrollTop = 0;
  report('scrollback', 'es', 'Otra frase.', 'Another phrase.');
  assert(scroller.scrollTop === 0, 'Scrollback remains fixed');
  receive({ type: 'connection', connected: true });
  assert(bubbles().length === 133 && doc.querySelector('.session-scroll').scrollTop === 0, 'Full render preserves all turns and scrollback');
  const oldScope = scope;
  receive({ type: 'visibility', visible: false });
  receive({ ...scope, type: 'audio_state', state: 'off' });
  receive({ type: 'language_preferences', language: 'fr', staff_language: 'de' });
  scope = begin();
  receive({ ...oldScope, type: 'interpretation', utterance_id: 'stale', original: 'Must not appear', text: '', source_language: 'fr', language_attribution: 'model' });
  assert(bubbles().length === 0, 'Previous session reports are fenced');
  report('new-unknown', 'und', '204');
  assert(bubbles()[0].classList.contains('caption-pending'), 'Previous session side does not leak into a new pair');
  const cases = [['fr','de'], ['de','fr'], ['ru','uk'], ['ar','fa'], ['hi','mr'], ['zh','ja'], ['ja','zh'], ['ko','es']];
  for (const [guest, staff] of cases) {
    receive({ type: 'visibility', visible: false });
    receive({ ...scope, type: 'audio_state', state: 'off' });
    receive({ type: 'language_preferences', language: guest, staff_language: staff });
    scope = begin(guest === 'ko');
    report('staff1', staff, 'Same original', 'Paired translation');
    report('staff2', staff, 'Same original', 'Paired translation');
    report('staff3', staff, 'Same original', 'Paired translation');
    report('guest', guest, 'Same original', 'Paired translation');
    assert(bubbles().slice(0,3).every(node => node.classList.contains('caption-reception')), `${guest}/${staff}: staff repeats stay right`);
    assert(bubbles()[3].classList.contains('caption-guest'), `${guest}/${staff}: guest stays left`);
    assert(bubbles()[0].getBoundingClientRect().right > bubbles()[3].getBoundingClientRect().right, `${guest}/${staff}: physical bubble positions match sides`);
  }
  const originalSession = scope.session_id;
  receive({ ...scope, type: 'reception_event', phase: 'error', text: 'Simulated service failure' });
  assert(!doc.querySelector('.voice-ball'), 'A stopped conversation has no nonfunctional mute button');
  assert(doc.querySelector('[data-action="restart"]')?.disabled, 'Retry waits while microphone release is uncertain');
  receive({ ...scope, type: 'audio_state', state: 'off' });
  click('[data-action="restart"]');
  const restarted = messages.filter(message => message.action === 'start').at(-1);
  assert(restarted.session_id !== originalSession && bubbles().length === 4, 'Retry starts a new session while retaining visible history');
  win.__lastScope = scope;
  return { frame, replayMs, assertions: 'Model language, repeats, neutral unknowns, whitespace, capture levels, late language, finality, escaping, scrolling, session fencing, eight language orientations and physical positions' };
}
