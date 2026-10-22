/* Isolated browser fixture. Native preferences are replayed through the real bridge events. */
export async function runReceptionAppearanceFixture(assetBase = new URL('../android/hotel/src/main/assets/reception/', import.meta.url), viewport = { width: 837, height: 471 }) {
  const assert = (value, message) => { if (!value) throw new Error(message); };
  const response = await fetch(new URL('index.html', assetBase));
  const html = new DOMParser().parseFromString(await response.text(), 'text/html');
  html.querySelectorAll('script,meta[http-equiv="Content-Security-Policy"]').forEach(node => node.remove());
  const base = html.createElement('base');
  base.href = String(assetBase);
  html.head.prepend(base);
  const frame = document.createElement('iframe');
  frame.id = 'appearance-fixture';
  frame.title = 'Isolated reception day and night fixture';
  frame.width = String(viewport.width); frame.height = String(viewport.height);
  const loaded = new Promise(resolve => frame.addEventListener('load', resolve, { once: true }));
  frame.srcdoc = '<!doctype html>' + html.documentElement.outerHTML;
  document.body.appendChild(frame);
  await loaded;
  const win = frame.contentWindow, doc = frame.contentDocument;
  const sent = [];
  win.ManzaniaNative = { postMessage: value => sent.push(JSON.parse(value)) };
  for (const name of ['greetings.js', 'language-turns.js', 'reception.js']) {
    const script = doc.createElement('script');
    script.src = new URL(name, assetBase).href;
    const ready = new Promise((resolve, reject) => { script.onload = resolve; script.onerror = reject; });
    doc.body.appendChild(script);
    await ready;
  }
  const receive = event => win.manzania.receive(event);
  const click = selector => { const node = doc.querySelector(selector); assert(node && !node.disabled, selector); node.click(); };
  const styles = selector => win.getComputedStyle(doc.querySelector(selector));
  const contrast = (foreground, background) => {
    const luminance = color => color.match(/[\d.]+/g).slice(0, 3).map(value => {
      const channel = Number(value) / 255;
      return channel <= .04045 ? channel / 12.92 : ((channel + .055) / 1.055) ** 2.4;
    }).reduce((sum, channel, index) => sum + channel * [.2126, .7152, .0722][index], 0);
    const a = luminance(foreground), b = luminance(background);
    return (Math.max(a, b) + .05) / (Math.min(a, b) + .05);
  };
  const palette = (value, background) => {
    assert(doc.documentElement.dataset.appearance === value, `${value} appearance is active`);
    assert(styles('body').backgroundColor === background, `${value} page background is correct`);
    assert(styles('html').colorScheme === value, `${value} native form controls use the matching scheme`);
  };
  receive({ type: 'reception_info', audio_available: true, local_audio_state: 'off' });
  receive({ type: 'language_preferences', language: 'de', staff_language: 'en' });
  palette('dark', 'rgb(8, 15, 21)');
  click('[data-action="appearance"]');
  palette('light', 'rgb(243, 245, 242)');
  assert(sent.at(-1).action === 'appearance' && sent.at(-1).value === 'light', 'Day appearance persists through native action');
  assert(doc.documentElement.dataset.night === 'false', 'Appearance does not enable after-hours reception');
  click('#night-toggle');
  palette('light', 'rgb(243, 245, 242)');
  assert(sent.at(-1).action === 'night' && sent.at(-1).enabled === true, 'After-hours reception persists independently');
  assert(doc.querySelector('.home-action-main').dataset.action === 'reception', 'After-hours reception offers assistance first');
  click('[data-action="reception"]');
  assert(doc.querySelector('#content').dataset.page === 'languages', 'After-hours primary action opens hotel assistance');
  click('[data-action="back"]');
  assert(doc.documentElement.dataset.night === 'true', 'Navigating back preserves after-hours reception');
  assert(styles('.wordmark').color === 'rgb(24, 44, 56)', 'Brand is readable on day background');
  assert(contrast(styles('.welcome p').color, styles('body').backgroundColor) >= 4.5, 'Day supporting copy meets normal-text contrast');
  click('[data-action="appearance"]');
  palette('dark', 'rgb(8, 15, 21)');
  assert(sent.at(-1).action === 'appearance' && sent.at(-1).value === 'dark', 'Night appearance persists');
  assert(doc.documentElement.dataset.night === 'true', 'Appearance does not disable after-hours reception');
  receive({ type: 'appearance', value: 'light' });
  receive({ type: 'night_mode', enabled: false });
  click('[data-action="translation"]');
  // onResume/pushState sends both values even when the selected page did not change.
  receive({ type: 'appearance', value: 'light' });
  receive({ type: 'night_mode', enabled: true });
  assert(doc.querySelector('#content').dataset.page === 'translation_languages', 'Restoring night state does not reset navigation');
  assert(styles('#language').color === 'rgb(24, 44, 56)' && styles('#language').backgroundColor === 'rgb(228, 236, 235)', 'Day selectors use a readable palette');
  click('[data-action="start"]');
  const start = sent.filter(event => event.action === 'start').at(-1);
  assert(start.mode === 'translation', 'Day/night mode does not change translation into assistance');
  const scope = { session_id: start.session_id, generation: 1 };
  receive({ ...scope, type: 'audio_state', state: 'recording' });
  receive({ ...scope, type: 'reception_event', phase: 'listening' });
  receive({ ...scope, type: 'interpretation', utterance_id: 'day-caption', source_language: 'de', original: 'Guten Morgen.', text: 'Good morning.', language_attribution: 'model' });
  assert(styles('.pair-title').color === 'rgb(38, 38, 36)', 'Active translation heading uses day palette');
  assert(styles('.caption').color === 'rgb(38, 38, 36)', 'Day captions are readable');
  assert(contrast(styles('.caption .translated').color, styles('.caption').backgroundColor) >= 4.5, 'Day translated captions meet normal-text contrast');
  const count = doc.querySelectorAll('.caption').length;
  receive({ type: 'night_mode', enabled: false });
  assert(doc.querySelector('#content').dataset.page === 'active' && doc.querySelectorAll('.caption').length === count, 'Preference replay preserves active translation');
  receive({ type: 'appearance', value: 'invalid' });
  palette('light', 'rgb(243, 245, 242)');
  receive({ type: 'appearance', value: 'dark' });
  palette('dark', 'rgb(8, 15, 21)');
  assert(styles('.pair-title').color === 'rgb(243, 241, 233)', 'Active heading returns to night palette');
  assert(contrast(styles('.caption .translated').color, styles('.caption').backgroundColor) >= 4.5, 'Night translated captions meet normal-text contrast');
  for (const selector of ['.pair-title', '.voice-ball', '#privacy']) {
    const rect = doc.querySelector(selector).getBoundingClientRect();
    assert(rect.left >= 0 && rect.right <= win.innerWidth + 1 && rect.top >= 0 && rect.bottom <= win.innerHeight + 1, `Translation control fits viewport: ${selector}`);
  }
  return { frame, receive, sent, scope, result: 'PASS: day/night controls, palette, native preference replay and translation navigation' };
}
