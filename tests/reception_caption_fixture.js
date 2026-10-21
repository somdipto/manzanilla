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
  const begin = () => {
    click('[data-action="translation"]');
    click('[data-action="start"]');
    const session_id = messages.filter(message => message.action === 'start').at(-1).session_id;
    const scope = { session_id, generation: 1 };
    receive({ ...scope, type: 'audio_state', state: 'recording' });
    receive({ ...scope, type: 'reception_event', phase: 'listening' });
    return scope;
  };
  let scope = begin();
  const caption = (utterance_id, text, extra = {}) => receive({ ...scope, type: 'caption', utterance_id, field: 'original', side: 'human', speaker_attribution: 'unknown', association: 'unlinked', operation: 'replace', final: false, text, ...extra });
  const bubbles = () => [...doc.querySelectorAll('.caption')];
  const exact = '<img src=x onerror="throw Error(1)">\n  Exact source & punctuation.';
  const before = win.performance.now();
  caption('first', exact, { final: true });
  const first = bubbles()[0];
  for (let id = 0; id < 120; id++) {
    for (let revision = 0; revision < 5; revision++) caption(`segment-${id}`, `Segment ${id}, revision ${revision}`, { final: revision === 4 });
  }
  const replayMs = win.performance.now() - before;
  assert(bubbles().length === 1, 'An uninterrupted human run stays one bubble');
  assert(bubbles()[0] === first, 'Bubble identity survives replay');
  assert(first.querySelector('.said').textContent.includes(exact) && !first.querySelector('img'), 'Source text remains exact and escaped');
  assert(first.querySelectorAll('.said').length === 1 && first.querySelector('.said').textContent.includes('Segment 119, revision 4'), 'Human segments join one turn in arrival order');
  assert(first.classList.contains('caption-guest'), 'The first unlabeled human turn is the guest side');
  caption('first', 'late partial');
  assert(first.querySelector('.said').textContent.includes(exact) && !first.querySelector('.said').textContent.includes('late partial'), 'Final source rejects late partial');
  caption('a1', 'Exact translation & output.', { field: 'text', side: 'assistant', final: true });
  assert(bubbles().length === 1 && first.querySelector('.translated').textContent === 'Exact translation & output.', 'Assistant output lands at the bottom of the speaker bubble');
  assert(!doc.body.textContent.includes('Assistant output') && !doc.body.textContent.includes('AI ·'), 'No AI labeling appears inside bubbles');
  assert(!doc.querySelector('[data-speaker]') && !doc.body.textContent.includes('Unspecified'), 'No speaker picker remains');
  caption('next', 'Sí, tenemos una habitación doble libre esta noche.', { final: true });
  const second = bubbles()[1];
  assert(bubbles().length === 2 && second.classList.contains('caption-reception'), 'A staff-language turn lands on the reception side');
  assert(second.querySelector('.said').textContent === 'Sí, tenemos una habitación doble libre esta noche.' && !second.querySelector('.translated'), 'A new human turn starts without output');
  caption('a2', 'Yes, we have a double room free tonight.', { field: 'text', side: 'assistant', final: false });
  assert(second.querySelector('.translated').textContent.includes('Yes, we have') && second.querySelector('.translated').classList.contains('live'), 'Streaming translation is marked in progress');
  caption('a2', 'Yes, we have a double room free tonight.', { field: 'text', side: 'assistant', final: true });
  assert(!second.querySelector('.translated').classList.contains('live'), 'Final translation settles');
  caption('rep2', 'Sí, tenemos una habitación doble libre esta noche.', { final: true });
  assert(bubbles().length === 3 && bubbles()[2].classList.contains('caption-reception'), 'A repeated receptionist phrase stays on the reception side');
  caption('a3', 'Yes, we have a double room free tonight.', { field: 'text', side: 'assistant', final: true });
  caption('rep3', 'Sí, tenemos una habitación doble libre esta noche.', { final: true });
  assert(bubbles().length === 4 && bubbles()[3].classList.contains('caption-reception'), 'A third repeat still holds the reception side');
  caption('a4', 'Yes, we have a double room free tonight.', { field: 'text', side: 'assistant', final: true });
  caption('cont', '¿Desea una habitación con vistas al mar?', { final: true });
  assert(bubbles().length === 5 && bubbles()[4].classList.contains('caption-reception'), 'The same speaker returning after a pause with new words does not flip sides');
  caption('a5', 'Would you like a room with a sea view?', { field: 'text', side: 'assistant', final: true });
  caption('guestq', 'How much is the room for one night?', { final: true });
  assert(bubbles().length === 6 && bubbles()[5].classList.contains('caption-guest'), 'A guest-language turn after reception turns lands on the guest side');
  caption('a6', '¿Cuánto cuesta la habitación por una noche?', { field: 'text', side: 'assistant', final: true });
  caption('num', '204.', { final: true });
  assert(bubbles().length === 7 && bubbles()[6].classList.contains('caption-reception'), 'With no language evidence the side alternates from the previous human turn');
  caption('a7', 'Doscientos cuatro.', { field: 'text', side: 'assistant', final: true });
  caption('stream', '34', { final: false });
  assert(bubbles().length === 8 && bubbles()[7].classList.contains('caption-guest'), 'An ambiguous streaming start falls back to alternation');
  caption('stream', '34 euros cuesta la habitación esta noche.', { final: true });
  assert(bubbles()[7].classList.contains('caption-reception') && bubbles()[7].querySelector('.caption-side').textContent.includes('Receptionist'), 'A streaming turn reclassifies bubble position and label as evidence arrives');
  caption('a8', 'The room costs 34 euros tonight.', { field: 'text', side: 'assistant', final: true });
  const mic = doc.querySelector('.voice-ball');
  assert(mic && mic.dataset.action === 'mute' && mic.tagName === 'BUTTON', 'The dock is a single tappable microphone');
  assert(mic.dataset.hearing === 'true' && mic.getAttribute('aria-pressed') === 'false', 'Live capture shows red');
  mic.click();
  assert(messages.at(-1).action === 'mute' && messages.at(-1).muted === true, 'Tapping the microphone asks native to mute');
  receive({ ...scope, type: 'audio_state', state: 'muted' });
  assert(doc.querySelector('.voice-ball').dataset.hearing === 'false' && doc.querySelector('.voice-ball').getAttribute('aria-pressed') === 'true', 'Muted state shows green with a pressed toggle');
  assert(doc.getElementById('privacy').textContent.includes('Mic muted'), 'Muted state is honestly labeled');
  doc.querySelector('.voice-ball').click();
  assert(messages.at(-1).action === 'mute' && messages.at(-1).muted === false, 'Tapping again asks native to unmute');
  receive({ ...scope, type: 'audio_state', state: 'recording' });
  assert(doc.querySelector('.voice-ball').dataset.hearing === 'true', 'Unmuted capture shows red again');
  receive({ ...scope, type: 'mic_level', level: 0.75, bands: [0.7, 0.5, 0.2] });
  const meter = doc.querySelector('.voice-ball');
  assert(meter.style.getPropertyValue('--level') === '0.75' && bubbles()[0] === first, 'Mic level updates only the existing button');
  receive({ ...scope, type: 'energy', level: 1 });
  assert(doc.querySelector('.voice-ball') === meter, 'Playback energy does not rebuild the button');
  receive({ ...scope, type: 'reception_event', phase: 'thinking' });
  assert(doc.querySelector('.voice-ball') === meter && meter.dataset.hearing === 'true' && bubbles()[0] === first, 'Thinking keeps the live microphone red and the conversation intact');
  const scroller = doc.querySelector('.session-scroll');
  assert(scroller.scrollHeight > scroller.clientHeight, 'Replay creates scrollback');
  scroller.scrollTop = scroller.scrollHeight;
  caption('follow', 'Follow the latest caption');
  assert(scroller.scrollHeight - scroller.clientHeight - scroller.scrollTop < 2, 'Bottom readers follow new captions');
  scroller.scrollTop = 0;
  caption('scrollback', 'Do not pull a reader down');
  assert(scroller.scrollTop === 0, 'Scrollback is retained on new captions');
  receive({ type: 'connection', connected: true });
  const rebuilt = doc.querySelectorAll('.caption');
  assert(rebuilt.length === 9 && doc.querySelector('.session-scroll').scrollTop === 0, 'Full render retains turns and scrollback');
  caption('first', exact + '\nFinal correction', { final: true });
  assert(rebuilt[0] === doc.querySelectorAll('.caption')[0] && rebuilt[0].querySelector('.said').textContent.includes('Final correction'), 'Full render rebuilds the turn index for later revisions');
  const oldScope = scope;
  receive({ type: 'visibility', visible: false });
  receive({ ...oldScope, type: 'audio_state', state: 'off' });
  assert(!doc.querySelector('.caption'), 'Session reset clears visible history');
  receive({ type: 'language_preferences', language: 'ru', staff_language: 'es' });
  scope = begin();
  receive({ ...oldScope, type: 'caption', utterance_id: 'stale', field: 'original', side: 'human', association: 'unlinked', operation: 'replace', final: true, text: 'Must not appear' });
  assert(bubbles().length === 0, 'Previous session captions are fenced');
  caption('greet', '¡Hola! Bienvenido.', { field: 'text', side: 'assistant', final: true });
  const greeting = bubbles()[0];
  assert(bubbles().length === 1 && greeting.classList.contains('caption-reception'), 'The greeting opens on the reception side');
  assert(greeting.querySelector('.said').textContent === '¡Hola! Bienvenido.' && !greeting.querySelector('.translated'), 'The greeting reads as spoken words, not as a translation');
  caption('fresh', 'Здравствуйте, нужна комната на двоих.');
  assert(bubbles().length === 2 && bubbles()[1].querySelector('.said').textContent === 'Здравствуйте, нужна комната на двоих.' && bubbles()[1].classList.contains('caption-guest'), 'A Cyrillic guest turn lands on the guest side with a Latin staff language');
  caption('fresh-t', 'Hello, we need a room for two.', { field: 'text', side: 'assistant', final: true });
  caption('fresh2', 'Por supuesto, ¿para cuántas noches?', { final: true });
  assert(bubbles().length === 3 && bubbles()[2].classList.contains('caption-reception'), 'A Latin staff turn lands on the reception side with a Cyrillic guest language');
  receive({ ...scope, type: 'audio_state', state: 'unknown' });
  receive({ ...scope, type: 'reception_event', phase: 'error', text: 'Previous local release is unconfirmed. No new microphone was opened.' });
  assert(!doc.getElementById('notices').hidden && doc.getElementById('notices').textContent.includes('Previous local release is unconfirmed'), 'Blocked restart errors remain visible on the minimal translation screen');
  const privacy = doc.getElementById('privacy');
  assert(privacy.checkVisibility() && privacy.textContent.includes('Mic state unknown'), 'Unknown release remains visibly labeled on the translation screen');
  const pairCases = [
    ['fr', 'de', 'La chambre est prête, monsieur.', 'Das Zimmer ist fertig, mein Herr.'],
    ['ru', 'uk', 'Где лифт, пожалуйста?', 'Ліфт є наприкінці коридору.'],
    ['ar', 'fa', 'أين المصعد من فضلك؟', 'آسانسور در انتهای راهرو است.'],
    ['hi', 'mr', 'लिफ्ट कहां है कृपया?', 'लिफ्ट दालनाच्या शेवटी आहे.'],
    ['zh', 'ja', '请问电梯在哪里？', 'エレベーターは廊下の奥にあります。'],
    ['ko', 'es', '엘리베이터가 어디에 있나요?', 'El ascensor está al final del pasillo.'],
    ['th', 'de', 'ลิฟต์อยู่ที่ไหนคะ?', 'Der Aufzug ist am Ende des Ganges.']
  ];
  for (const [guestLang, staffLang, guestLine, staffLine] of pairCases) {
    receive({ type: 'visibility', visible: false });
    receive({ ...scope, type: 'audio_state', state: 'off' });
    assert(!doc.querySelector('.caption'), `History clears before the ${guestLang}/${staffLang} pair`);
    receive({ type: 'language_preferences', language: guestLang, staff_language: staffLang });
    scope = begin();
    caption('pg', guestLine, { final: true });
    assert(bubbles().length === 1 && bubbles()[0].classList.contains('caption-guest'), `${guestLang} guest speech lands left in the ${guestLang}/${staffLang} pair`);
    caption('pg-t', 'translation', { field: 'text', side: 'assistant', final: true });
    caption('ps', staffLine, { final: true });
    assert(bubbles().length === 2 && bubbles()[1].classList.contains('caption-reception'), `${staffLang} staff speech lands right in the ${guestLang}/${staffLang} pair`);
  }
  win.__lastScope = scope;
  return { frame, updates: 636, replayMs, assertions: 'Turn merge, language-matched sides, repeat backstop, pause-resume continuity, no-evidence alternation, distinct-script pair, greeting, escaping, finality, no AI labels, mic mute toggle, mic-only updates, scrolling, full render and stale session/reset' };
}
