import { Translator } from './translator.js';

const translator = new Translator();
const byId = (id) => document.getElementById(id);
const ui = {
  settingsToggle: byId('settings-toggle'), settingsClose: byId('settings-close'),
  settings: byId('settings-panel'), form: byId('settings-form'), key: byId('api-key'),
  translationKey: byId('translation-api-key'), welcome: byId('welcome-status'),
  voice: byId('voice-id'), save: byId('save-settings'), settingsMessage: byId('settings-message'),
  configStatus: byId('configuration-status'), settingsLock: byId('settings-lock'),
  first: byId('guest-language'), second: byId('staff-language'), swap: byId('swap-languages'),
  languageHelp: byId('language-help'), readiness: byId('readiness-banner'),
  readinessMessage: byId('readiness-message'), readinessAction: byId('readiness-action'),
  error: byId('error-message'), notice: byId('notice-message'), cleanup: byId('cleanup-banner'),
  retryCleanup: byId('retry-cleanup'), startStop: byId('start-stop'), mute: byId('mute'),
  resumeAudio: byId('resume-audio'), microphone: byId('microphone-label'), dot: byId('capture-dot'),
  level: byId('microphone-level'), status: byId('session-status'), empty: byId('empty-state'),
  transcript: byId('transcript'), scroller: byId('conversation-scroll'), latest: byId('latest-caption'),
};

const activePhases = new Set(['permission', 'connecting', 'listening', 'thinking', 'speaking', 'reconnecting', 'stopping']);
const phaseLabels = {
  idle: 'Ready when you are.', permission: 'Allow microphone access in your browser. You can stop at any time.',
  connecting: 'Connecting to translation…', listening: 'Listening for either language.',
  thinking: 'Translating…', speaking: 'Playing translated audio.', reconnecting: 'Reconnecting…',
  stopping: 'Microphone off. Ending the session…', cleanup: 'Session cleanup needs confirmation.',
  error: 'The conversation could not continue.',
};
let state = { phase: 'idle', microphone: false, muted: false, audioBlocked: false, requestId: null, message: '' };
let configuration = null;
let loadingStatus = false;
let statusFailed = false;
let saving = false;
let starting = false;
let stopping = false;
let muting = false;
let resuming = false;
let startRevision = 0;
let statusFlight = null;
let languageSignature = '';
let sessionPair = null;
let utterances = new Map();
let welcomeView = null;
const LANGUAGE_STORAGE = 'manzanilla.web.languages';

function rememberLanguages() {
  try { localStorage.setItem(LANGUAGE_STORAGE, JSON.stringify([ui.first.value, ui.second.value])); }
  catch (_) { /* Language preference is optional; cleanup storage is enforced separately. */ }
}

function showMessage(element, message) {
  element.textContent = message || '';
  element.hidden = !message;
}

function errorText(error, fallback) {
  return error instanceof Error && error.message ? error.message : fallback;
}

function isActive() {
  return starting || activePhases.has(state.phase);
}

function needsCleanup() {
  return state.phase === 'cleanup' || (!isActive() && Boolean(state.requestId || configuration?.pending_request_id));
}

function pairValid() {
  return Boolean(languageInfo(ui.first.value) && languageInfo(ui.second.value));
}

function setSettings(open) {
  ui.settings.hidden = !open;
  ui.settingsToggle.setAttribute('aria-expanded', String(open));
  if (open) {
    (ui.key.disabled ? ui.settingsClose : configuration?.key_configured ? ui.voice : ui.key).focus();
  } else {
    ui.key.value = '';
    ui.translationKey.value = '';
    ui.settingsToggle.focus();
  }
}

function render() {
  const active = isActive();
  const cleanup = needsCleanup();
  const locked = active || cleanup || stopping;
  const configured = configuration?.configured === true && !statusFailed;
  const pair = pairValid();
  ui.first.disabled = locked || saving || loadingStatus || !configuration;
  ui.second.disabled = ui.first.disabled;
  ui.swap.disabled = ui.first.disabled;
  ui.languageHelp.textContent = locked
    ? 'Stop the conversation before changing languages.'
    : ui.first.value === ui.second.value ? 'Both speakers use the same language. Identify Guest or Receptionist for each utterance.'
      : 'Translation goes both ways automatically. Unclear speech asks you to identify the speaker.';

  ui.startStop.textContent = stopping || state.phase === 'stopping' ? 'Stopping…' : active ? 'Stop conversation' : 'Start conversation';
  ui.startStop.dataset.stop = String(active || stopping);
  ui.startStop.disabled = stopping || state.phase === 'stopping' || (!active && (!configured || !pair || cleanup || saving || loadingStatus));
  ui.mute.disabled = muting || stopping || !active || !(state.microphone || state.muted);
  ui.mute.textContent = state.muted ? 'Unmute microphone' : 'Mute microphone';
  ui.mute.setAttribute('aria-pressed', String(state.muted));
  ui.resumeAudio.hidden = !state.audioBlocked || !active;
  ui.resumeAudio.disabled = resuming || stopping;
  ui.resumeAudio.textContent = resuming ? 'Enabling audio…' : 'Enable translated audio';

  const capturing = state.microphone && !state.muted && !stopping;
  ui.microphone.textContent = capturing ? 'Microphone on' : state.muted && active ? 'Microphone muted' : 'Microphone off';
  ui.dot.dataset.live = String(capturing);
  if (!capturing) ui.level.value = 0;

  ui.cleanup.hidden = !cleanup;
  ui.retryCleanup.disabled = stopping;
  ui.retryCleanup.textContent = stopping ? 'Confirming cleanup…' : 'Retry cleanup';
  ui.key.disabled = locked || saving || loadingStatus;
  ui.translationKey.disabled = ui.key.disabled;
  ui.voice.disabled = ui.key.disabled;
  ui.save.disabled = locked || saving || loadingStatus || !configuration;
  ui.save.textContent = saving ? 'Saving…' : 'Save settings';
  ui.settingsLock.hidden = !locked;
  ui.configStatus.textContent = loadingStatus && !configuration ? 'Checking local configuration…'
    : statusFailed ? 'Cannot reach the local server. Retry the connection below.'
      : configured ? 'Fish and Gemini credentials are configured. Provider access is checked only when you start.'
        : 'Add your Fish API key, Fish voice ID and separate Gemini API key to enable translation.';

  ui.readiness.hidden = active || cleanup || (configured && !statusFailed) || (loadingStatus && !statusFailed);
  ui.readinessMessage.textContent = statusFailed
    ? 'The local translation server is unavailable. Check that it is running, then retry.'
    : 'Connect your Fish and Gemini accounts in Settings before starting a conversation.';
  ui.readinessAction.textContent = statusFailed ? 'Retry connection' : 'Open settings';
  ui.readinessAction.disabled = loadingStatus;

  if (welcomeView) {
    const blocked = state.audioBlocked && ['queued', 'playing'].includes(welcomeView.status);
    welcomeView.label.textContent = `App welcome · ${blocked ? 'audio queued — enable translated audio to play' : welcomeView.status}`;
  }
  ui.status.textContent = stopping ? phaseLabels.stopping
    : active && state.audioBlocked ? 'Translated audio is queued, not playing. Enable translated audio to hear it.'
    : active && state.muted ? 'Microphone muted. Unmute to continue; translated audio may still finish.'
    : active || cleanup ? state.message || phaseLabels[state.phase] || 'Translation session active.'
      : loadingStatus ? 'Checking local server…'
        : statusFailed ? 'Local server unavailable. Microphone off.'
          : !configured ? 'Setup needed. Microphone off.'
            : !pair ? 'Choose a Guest language and Receptionist language.'
              : state.message || phaseLabels[state.phase] || phaseLabels.idle;
  for (const entry of utterances.values()) {
    const playback = state.audioBlocked && ['queued', 'playing'].includes(entry.record.playback_status)
      ? ' Audio queued, not playing. Enable translated audio.'
      : { idle: '', queued: ' Audio queued.', playing: ' Playing audio.', complete: ' Audio finished.', interrupted: ' Audio interrupted.', error: ' Audio playback failed.' }[entry.record.playback_status];
    entry.status.textContent = entry.translationStatusText + playback +
      (entry.record.recognition_status === 'degraded' ? ' Recognition is uncertain.' : '');
    for (const button of entry.controls.querySelectorAll('button')) button.disabled = !active || stopping || entry.resolving;
  }
}

function populateLanguages(languages) {
  const signature = JSON.stringify(languages);
  if (signature === languageSignature) return;
  languageSignature = signature;
  const previous = [ui.first.value, ui.second.value];
  if (!previous[0] || !previous[1]) {
    try {
      const saved = JSON.parse(localStorage.getItem(LANGUAGE_STORAGE));
      if (Array.isArray(saved) && saved.length === 2) {
        previous[0] = saved[0];
        previous[1] = saved[1];
      }
    } catch (_) { /* Ignore malformed or unavailable optional preferences. */ }
  }
  for (const select of [ui.first, ui.second]) {
    select.replaceChildren();
    for (const language of languages) {
      const option = document.createElement('option');
      option.value = language.code;
      option.textContent = language.native === language.name ? language.name : `${language.name} / ${language.native}`;
      select.append(option);
    }
  }
  const codes = languages.map((language) => language.code);
  ui.first.value = codes.includes(previous[0]) ? previous[0] : codes.includes('en') ? 'en' : codes[0] || '';
  ui.second.value = codes.includes(previous[1]) ? previous[1] : codes.includes('es') ? 'es' : codes.find((code) => code !== ui.first.value) || '';
}

function applyConfiguration(value) {
  configuration = value;
  statusFailed = false;
  populateLanguages(value.languages);
  if (document.activeElement !== ui.voice && !ui.voice.value) ui.voice.value = value.voice_id || '';
}

async function refreshStatus() {
  if (statusFlight) return statusFlight;
  loadingStatus = true;
  render();
  statusFlight = (async () => {
    try {
      applyConfiguration(await translator.status());
    } catch (error) {
      statusFailed = true;
      showMessage(ui.error, errorText(error, 'Could not load local server status.'));
    } finally {
      loadingStatus = false;
      statusFlight = null;
      render();
    }
  })();
  return statusFlight;
}

function languageInfo(code) {
  return configuration?.languages.find((language) => language.code === code);
}

function addUtterance(record) {
  if (!sessionPair) return;
  let entry = utterances.get(record.utterance_id);
  if (entry && entry.record.revision >= record.revision) return;
  const follow = ui.scroller.scrollHeight - ui.scroller.scrollTop - ui.scroller.clientHeight < 70;
  if (!entry) {
    const article = document.createElement('article');
    article.className = 'utterance';
    const header = document.createElement('div');
    header.className = 'utterance-header';
    const original = document.createElement('p');
    original.className = 'original-text';
    original.dir = 'auto';
    const translated = document.createElement('section');
    translated.className = 'translation-section';
    const label = document.createElement('p');
    label.className = 'translation-label';
    const text = document.createElement('p');
    text.className = 'translation-text';
    text.dir = 'auto';
    translated.append(label, text);
    const status = document.createElement('p');
    status.className = 'utterance-status';
    const controls = document.createElement('div');
    controls.className = 'direction-controls';
    article.append(header, original, translated, status, controls);
    entry = { article, header, original, translated, label, text, status, controls, record, resolving: false };
    utterances.set(record.utterance_id, entry);
    ui.transcript.append(article);
  }
  entry.record = record;
  entry.resolving = false;
  entry.article.dataset.side = record.role || 'unclear';
  entry.article.dataset.revision = String(record.revision);
  const source = languageInfo(record.source_language)?.name || record.source_language;
  const target = languageInfo(record.target_language)?.name || record.target_language;
  const speaker = record.role === 'guest' ? 'Guest' : record.role === 'receptionist' ? 'Receptionist' : 'Speaker unclear';
  entry.header.textContent = `${speaker}${source ? ` · ${source}` : ''} · Original${record.recognition_status === 'partial' && record.translation_status === 'waiting' ? ' (recognizing…)' : ''}`;
  entry.original.textContent = record.original;
  entry.original.lang = record.source_language || record.detected_language || '';
  entry.translated.hidden = !record.translation;
  entry.label.textContent = `${target || 'Pending language'} translation`;
  entry.text.textContent = record.translation;
  entry.text.lang = record.target_language || '';
  const status = {
    waiting: record.recognition_status === 'partial' ? 'Recognizing speech…' : 'Waiting for translation.',
    needs_direction: record.direction_reason === 'mismatch'
      ? `Detected ${languageInfo(record.detected_language)?.name || record.detected_language || 'another language'}. Correct the selected languages before continuing.`
      : 'Identify the speaker to translate this utterance.',
    streaming: 'Translating…', complete: 'Translation complete.', cancelled: 'Translation interrupted. Recognized speech is preserved.',
    error: 'Translation failed. Recognized speech is preserved.', ignored: 'No translation was spoken.',
  }[record.translation_status];
  entry.translationStatusText = status;
  entry.controls.replaceChildren();
  if (record.translation_status === 'needs_direction' && record.recognition_status !== 'partial') {
    if (record.direction_reason === 'mismatch') {
      const correct = document.createElement('button');
      correct.type = 'button';
      correct.className = 'quiet-button';
      correct.textContent = 'Stop and correct languages';
      correct.addEventListener('click', async () => {
        await stopConversation();
        if (!ui.first.disabled) ui.first.focus();
      });
      entry.controls.append(correct);
    } else {
      for (const role of ['guest', 'receptionist']) {
        const button = document.createElement('button');
        button.type = 'button';
        button.className = 'secondary-button';
        button.textContent = role === 'guest' ? 'Guest spoke' : 'Receptionist spoke';
        button.addEventListener('click', async () => {
          entry.resolving = true;
          render();
          try { await translator.resolveDirection(record.utterance_id, record.revision, role); }
          catch (error) { showMessage(ui.notice, errorText(error, 'Direction could not be sent.')); }
          finally { entry.resolving = false; render(); }
        });
        entry.controls.append(button);
      }
    }
  }
  ui.empty.hidden = true;
  render();
  if (follow) {
    ui.scroller.scrollTop = ui.scroller.scrollHeight;
    ui.latest.hidden = true;
  } else ui.latest.hidden = false;
}

async function startConversation() {
  if (ui.startStop.disabled || isActive() || needsCleanup()) return;
  const revision = ++startRevision;
  starting = true;
  sessionPair = { first: ui.first.value, second: ui.second.value };
  utterances = new Map();
  ui.welcome.replaceChildren();
  welcomeView = null;
  ui.welcome.hidden = true;
  rememberLanguages();
  ui.transcript.replaceChildren();
  ui.empty.hidden = false;
  ui.latest.hidden = true;
  showMessage(ui.error, '');
  showMessage(ui.notice, '');
  render();
  try {
    await translator.start({ guest_language: sessionPair.first, staff_language: sessionPair.second });
  } catch (error) {
    if (revision === startRevision) showMessage(ui.error, errorText(error, 'Could not start translation.'));
  } finally {
    if (revision === startRevision) {
      starting = false;
      render();
      if (!isActive()) await refreshStatus();
    }
  }
}

async function stopConversation() {
  if (stopping) return;
  ++startRevision;
  starting = false;
  stopping = true;
  showMessage(ui.error, '');
  render();
  try {
    await translator.stop();
    if (configuration) configuration.pending_request_id = null;
  } catch (error) {
    showMessage(ui.error, errorText(error, 'Session cleanup is not confirmed. Retry cleanup before starting again.'));
  } finally {
    stopping = false;
    render();
    await refreshStatus();
  }
}

translator.addEventListener('state', (event) => {
  state = event.detail;
  render();
});
translator.addEventListener('utterance', (event) => addUtterance(event.detail));
translator.addEventListener('welcome', (event) => {
  const welcome = event.detail;
  const label = document.createElement('span');
  welcomeView = { label, status: welcome.status };
  const text = document.createElement('p');
  text.textContent = welcome.text;
  text.lang = welcome.language;
  text.dir = 'auto';
  ui.welcome.replaceChildren(label, text);
  ui.welcome.hidden = false;
  render();
});
translator.addEventListener('notice', (event) => showMessage(ui.notice, event.detail.message));
translator.addEventListener('level', (event) => {
  const value = event.detail.value;
  ui.level.hidden = event.detail.available !== true;
  ui.level.value = state.microphone && !state.muted && !stopping && Number.isFinite(value) ? Math.min(1, Math.max(0, value)) : 0;
});

ui.settingsToggle.addEventListener('click', () => setSettings(ui.settings.hidden));
ui.settingsClose.addEventListener('click', () => setSettings(false));
ui.settings.addEventListener('keydown', (event) => {
  if (event.key === 'Escape') {
    event.preventDefault();
    setSettings(false);
  }
});
ui.readinessAction.addEventListener('click', () => {
  if (statusFailed) {
    showMessage(ui.error, '');
    void refreshStatus();
  } else setSettings(true);
});
ui.form.addEventListener('submit', async (event) => {
  event.preventDefault();
  if (ui.save.disabled) return;
  const apiKey = ui.key.value.trim();
  const translationKey = ui.translationKey.value.trim();
  const values = { voice_id: ui.voice.value.trim() };
  if (apiKey) values.api_key = apiKey;
  if (translationKey) values.translation_api_key = translationKey;
  ui.key.value = '';
  ui.translationKey.value = '';
  saving = true;
  showMessage(ui.settingsMessage, '');
  render();
  try {
    applyConfiguration(await translator.configure(values));
    ui.voice.value = configuration.voice_id || '';
    ui.settingsMessage.dataset.error = 'false';
    showMessage(ui.settingsMessage, 'Settings saved. Start a conversation when you are ready.');
    showMessage(ui.error, '');
  } catch (error) {
    ui.settingsMessage.dataset.error = 'true';
    showMessage(ui.settingsMessage, errorText(error, 'Could not save settings.'));
  } finally {
    saving = false;
    render();
  }
});
ui.first.addEventListener('change', () => { rememberLanguages(); render(); });
ui.second.addEventListener('change', () => { rememberLanguages(); render(); });
ui.swap.addEventListener('click', () => {
  if (ui.swap.disabled) return;
  const first = ui.first.value;
  ui.first.value = ui.second.value;
  ui.second.value = first;
  rememberLanguages();
  render();
});
ui.startStop.addEventListener('click', () => {
  if (isActive()) void stopConversation();
  else void startConversation();
});
ui.retryCleanup.addEventListener('click', () => { void stopConversation(); });
ui.mute.addEventListener('click', async () => {
  if (ui.mute.disabled) return;
  muting = true;
  render();
  try {
    await translator.setMuted(!state.muted);
  } catch (error) {
    showMessage(ui.error, errorText(error, 'Could not change microphone state. Stop the conversation if needed.'));
  } finally {
    muting = false;
    render();
  }
});
ui.resumeAudio.addEventListener('click', async () => {
  if (ui.resumeAudio.disabled) return;
  resuming = true;
  render();
  try {
    await translator.resumeAudio();
  } catch (error) {
    showMessage(ui.error, errorText(error, 'Audio is still blocked. Check browser sound permissions and try again.'));
  } finally {
    resuming = false;
    render();
  }
});
ui.latest.addEventListener('click', () => {
  ui.scroller.scrollTop = ui.scroller.scrollHeight;
  ui.latest.hidden = true;
});
ui.scroller.addEventListener('scroll', () => {
  if (ui.scroller.scrollHeight - ui.scroller.scrollTop - ui.scroller.clientHeight < 70) ui.latest.hidden = true;
}, { passive: true });

render();
void refreshStatus();
