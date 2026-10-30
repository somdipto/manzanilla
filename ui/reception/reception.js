/* Device UI state machine. All provider credentials and microphone I/O stay native. */
(() => {
  'use strict';
  const native = !!window.ManzaniaNative;
  // Object.hasOwn requires WebView 93; supported tablets may have older engines.
  const hasOwn = (object, key) => Object.prototype.hasOwnProperty.call(object, key);
  const content = document.getElementById('content');
  const catalogue = window.MANZANILLA_UI;
  const languages = Object.create(null);
  catalogue.languages.forEach(language => { languages[language.code] = language; });
  // Catalogue order preserves the first-ten hardware keypad mapping.
  const translationLanguages = Object.create(null);
  catalogue.speech_languages.forEach(language => { translationLanguages[language.code] = language; });
  let languageSide = 'language';
  let appearance = 'dark';
  const themeIcon = () => appearance === 'light'
    ? '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" aria-hidden="true"><path d="M20 15.7A8.5 8.5 0 0 1 8.3 4 8.5 8.5 0 1 0 20 15.7Z"/></svg>'
    : '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" aria-hidden="true"><circle cx="12" cy="12" r="4"/><path d="M12 2v2m0 16v2M2 12h2m16 0h2M5 5l1.5 1.5m11 11L19 19M5 19l1.5-1.5m11-11L19 5"/></svg>';
  const shortcuts = ['checkin', 'operator', 'taxi', 'question'];
  const concierge = () => ['Arrival guide','Request help from reception','Request a taxi through staff','Ask about the hotel'].map(message => uiText(message));
  const state = { page: 'idle', mode: 'translation', night: false, language: 'es', staff_language: 'es', demo: !native, connected: false, audioAvailable: false, audioGate: '', audio: 'off', active: false, phase: '', sessionId: '', generation: null, closed: true, terminated: true, error: '', metrics: '', utterances: new Map(), draft: null, confirmed: false, operation: null, outcome: null, notices: [], hotel: '', pendingPage: '', micLevel: 0, turns: [] };
  Object.assign(state, { distribution: 'personal-test', configured: false, connectionState: 'unconfigured', services: null, pendingShortcut: null, welcome: null });
  const serviceNames = ['checkin','question','taxi','operator','message'];
  const isHotel = () => state.distribution === 'hotel';
  function serviceReason(name = '') {
    if (!state.configured || state.connectionState === 'unconfigured') return uiText('Hotel services need staff setup.');
    if (state.connectionState !== 'ready') return uiText({
      checking: 'Checking the hotel connection. Please wait.',
      unauthorized: 'The hotel device is not authorized. Please ask staff.',
      offline: 'The hotel connection is unavailable. Please ask staff.'
    }[state.connectionState] || 'The hotel connection is unavailable. Please ask staff.');
    if (!state.services) return uiText('The hotel service needs an update. Please ask staff.');
    if (name ? !state.services[name] : !serviceNames.some(id => state.services[id])) return uiText('This service is not configured by the hotel. Please ask staff.');
    return '';
  }
  const serviceNote = name => serviceReason(name) ? `<p class="service-reason" role="status">${esc(serviceReason(name))}</p>` : '';
  const serviceChoice = (id, label, busy = false) => `<div class="service-choice">${action(id, `<strong>${esc(label)}</strong>`, 'choice shortcut', busy || !!state.pendingShortcut || !!serviceReason(id), true)}${serviceNote(id)}</div>`;
  const reviewBlocked = () => !!state.operation && !state.operation.reconciliationPending && !!serviceReason(state.operation.action);
  const confirmationReason = () => serviceReason(state.operation?.action || state.requestedAction || '');
  let queuedShortcut = '';
  let watchedReceipt = '';
  function unwatchReceipt() {
    if (watchedReceipt) send('unwatch_request');
    watchedReceipt = '';
  }
  function syncReceiptWatch() {
    const item = state.page === 'saved' && state.active && !state.closed && state.mode === 'reception'
      ? state.outcome?.itemId || '' : '';
    if (item === watchedReceipt) return;
    unwatchReceipt();
    if (item) { watchedReceipt = item; send('watch_request', { item_id:item }); }
  }
  function requestStatus() {
    if (!state.outcome?.itemId) return '';
    const status = state.outcome.staffStatus || 'open';
    const message = { open:'Submitted', acknowledged:'Acknowledged', resolved:'Staff marked the request resolved' }[status];
    return `<section class="request-status" role="status" aria-live="polite"><h3>${esc(uiText('Request status'))}</h3><p>${esc(uiText(message))}</p>${status === 'open' ? fine(uiText('Waiting for reception')) : ''}${state.outcome.statusUnavailable ? fine(uiText('Request status is unavailable. Showing the last known state.')) : ''}</section>`;
  }
  const captionNodes = new Map();
  const turnLedger = new window.ManzanillaLanguageTurns();
  state.turns = turnLedger.turns;
  const selected = { language: false, staff_language: false };
  let defaultReceived = false;
  let preferencesRestored = false;
  const icons = {
    arrow: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true"><path d="M5 12h14m-5-5 5 5-5 5"/></svg>',
    translate: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" aria-hidden="true"><path d="M3 5h12M9 2v3m3 0c-.5 5-3.5 8-8 10m1-8c1.5 3 4.2 5.7 7 7m1 7 4-11 4 11m-6.4-4h4.8"/></svg>',
    bell: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" aria-hidden="true"><path d="M3 17h18M5 17v-3a7 7 0 0 1 14 0v3M2 21h20M12 4v3m-2-3h4"/></svg>',
    back: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true"><path d="m14 6-6 6 6 6"/></svg>',
    bed: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" aria-hidden="true"><path d="M3 18v3m18-3v3M3 11V5h18v6M2 18v-5a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v5H2Zm4-7V8h5v3m2 0V8h5v3"/></svg>',
    car: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" aria-hidden="true"><path d="m4 9 2-5h12l2 5M3 18v3m18-3v3M2 18v-6l2-3h16l2 3v6H2Zm3-5h2m10 0h2"/></svg>',
    message: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" aria-hidden="true"><path d="M3 3h18v14H8l-5 4V3Zm4 5h10M7 12h7"/></svg>',
    mic: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" aria-hidden="true"><rect x="9" y="2" width="6" height="12" rx="3"/><path d="M5 10v2a7 7 0 0 0 14 0v-2M12 19v3m-4 0h8"/></svg>',
    micOff: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" aria-hidden="true"><rect x="9" y="2" width="6" height="12" rx="3"/><path d="M5 10v2a7 7 0 0 0 14 0v-2M12 19v3m-4 0h8M3 3l18 18"/></svg>'
  };
  const esc = (value) => String(value ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
  function uiText(message, values = {}) {
    const locale = hasOwn(languages, state.language) ? state.language : 'es';
    const generic = catalogue.messages['This service is unavailable. Please ask hotel staff.'][locale];
    const text = catalogue.messages[message] && catalogue.messages[message][locale];
    if (typeof text !== 'string' || !text.trim()) return generic;
    const names = text.match(/\{[A-Za-z_][A-Za-z0-9_]*\}/g) || [];
    if (names.some(name => !hasOwn(values, name.slice(1, -1))) || Object.keys(values).some(name => names.indexOf('{' + name + '}') < 0)) return generic;
    return text.replace(/\{([A-Za-z_][A-Za-z0-9_]*)\}/g, (_, name) => String(values[name]));
  }
  const langName = (language) => translationLanguages[language]?.native || languages[language]?.native || language;
  const languagePicker = (side, label) => `<section class="language-side" data-keypad="${languageSide === side}"><label for="${side}">${esc(label)}</label><select id="${side}" data-language-side="${side}" lang="${state[side]}" dir="${languageDir(state[side])}" aria-describedby="${side}-hint" ${state.audio !== 'off' ? 'disabled' : ''}>${Object.entries(translationLanguages).map(([id,item]) => `<option value="${id}" lang="${id}" dir="${languageDir(id)}" ${state[side] === id ? 'selected' : ''}>${esc(item.native)}</option>`).join('')}</select><small id="${side}-hint">${esc(uiText(side === 'language' ? 'The language you speak' : 'The language reception speaks'))}</small></section>`;
  const send = (action, detail = {}) => {
    if (!native) return;
    const scope = state.sessionId && !['start','info','languages','appearance','night','settings','voice_setup','apply_update','check_updates'].includes(action)
      ? { session_id: state.sessionId, ...(state.generation === null ? {} : { generation: state.generation }) } : {};
    window.ManzaniaNative.postMessage(JSON.stringify({ action, ...scope, ...detail }));
  };
  const action = (name, label, className = 'primary', disabled = false, trustedMarkup = false) => `<button type="button" class="${className}" data-action="${name}" ${disabled ? 'disabled' : ''}>${trustedMarkup ? label : esc(label)}</button>`;
  const fine = (text) => `<p class="fine">${esc(text)}</p>`;
  const operationField = (field, index) => {
    const limit = Number.isInteger(field.max_length) && field.max_length > 0 ? field.max_length
      : ({ original_request:4000, guest_name:100, room_number:20, destination:300, pickup_time:100, query:300 })[field.name] || 300;
    const attrs = `id="operation-field-${index}" name="${esc(field.name)}" maxlength="${limit}" ${field.required ? 'required' : ''} ${state.operation.pending || state.operation.reconciliationPending ? 'disabled' : ''} autocomplete="off" dir="auto"`;
    const value = esc(state.operation.values[field.name] || '');
    const labels = { original_request: 'Exact message to send to staff', destination: 'Destination', pickup_time: 'Pickup time', query: 'Your hotel question', guest_name: state.operation.action === 'message' ? 'Guest name (optional)' : 'Guest name', room_number: state.operation.action === 'message' ? 'Room number (optional)' : 'Room number' };
    return `<label for="operation-field-${index}">${esc(uiText(labels[field.name] || 'This service is unavailable. Please ask hotel staff.'))}${field.required ? ' *' : ''}${field.name === 'original_request' ? `<textarea ${attrs}>${value}</textarea>` : `<input ${attrs} value="${value}">`}</label>`;
  };

  const languageDir = language => languages[language]?.direction || 'auto';
  const captionSpeaker = entry => entry.side === 'guest' ? uiText("Guest") : entry.side === 'staff' ? uiText("Receptionist") : uiText("speaker unknown");
  const captionParts = entry => ['original','text'].filter(field => entry[field] !== '').map(field => {
    const output = entry.side === 'assistant' ? uiText("Assistant output") : field === 'original' ? uiText("Original") : entry.association === 'unlinked' ? uiText("Provider output") : uiText("Translation");
    const speaker = entry.speakerAttribution === 'selected' ? uiText('{speaker} (selected)', { speaker: captionSpeaker(entry) }) : captionSpeaker(entry);
    const language = entry[field === 'original' ? 'from' : 'to'];
    let label = entry.side === 'assistant' ? output : uiText('{output} · {speaker}', { output, speaker });
    if (entry.association === 'unlinked') label = uiText('{label} · unlinked', { label });
    if (language) label = uiText('{label} · {language}', { label, language: langName(language) });
    return { field, language, text: entry[field], label };
  });
  const captionClass = entry => `caption caption-${entry.side === 'guest' || entry.side === 'human' ? 'guest' : 'reception'}`;
  const captionBody = entry => captionParts(entry).map((part,index) => `<div class="caption-part${index ? ' caption-divider' : ''}" data-field="${part.field}" data-final="${entry.final[part.field]}"><span class="caption-language">${esc(part.label)}</span><p class="${state.mode === 'translation' ? part.field === 'original' ? 'said' : 'translated' : `caption-${part.field}`}" lang="${esc(part.language || 'und')}" dir="${languageDir(part.language)}">${esc(part.text)}</p></div>`).join('');
  const caption = entry => `<article class="${captionClass(entry)}" data-caption-key="${esc(entry.id)}" aria-atomic="true">${captionBody(entry)}</article>`;
  // One application-owned record per recognized utterance; revisions update it in place.
  const turnLabel = turn => turn.side === 'guest' ? uiText('Guest · {language}', { language: langName(turn.sourceLanguage) })
    : turn.side === 'staff' ? uiText('Receptionist · {language}', { language: langName(turn.sourceLanguage) })
    : turn.directionReason === 'mismatch' && turn.detectedLanguage
      ? uiText('{label} · {language}', { label: uiText('Language'), language: langName(turn.detectedLanguage) }) : uiText('Language unclear');
  const turnClass = turn => turn.side === 'guest' ? 'guest' : turn.side === 'staff' ? 'reception' : 'pending';
  const turnHtml = turn => {
    const said = turn.original, translated = turn.text;
    const streaming = translated && turn.status === 'streaming';
    const unresolved = turn.status === 'needs_direction' && turn.recognitionStatus !== 'partial';
    const mismatch = turn.directionReason === 'mismatch';
    const status = turn.status === 'error' ? uiText('Translation failed. Original speech is preserved.')
      : unresolved ? uiText(mismatch ? 'Detected language does not match. Stop and select the correct languages.' : 'Choose who spoke.')
      : turn.status === 'cancelled' || turn.playbackStatus === 'interrupted' ? uiText('Stopped') : '';
    const controls = unresolved && !mismatch && !state.closed ? `<div class="direction-controls">${['guest','receptionist'].map(role => `<button type="button" class="secondary" data-action="resolve:${role}:${encodeURIComponent(turn.id)}" data-revision="${turn.revision}">${esc(uiText(role === 'guest' ? 'Guest' : 'Receptionist'))}</button>`).join('')}</div>` : '';
    return `<span class="caption-side">${esc(turnLabel(turn))}</span><p class="said" lang="${esc(turn.sourceLanguage || turn.detectedLanguage || 'und')}" dir="auto">${esc(said)}</p>${translated ? `<p class="translated${streaming ? ' live' : ''}" lang="${esc(turn.targetLanguage || 'und')}" dir="auto">${esc(translated)}</p>` : ''}${status ? `<p class="turn-status" role="status">${esc(status)}</p>` : ''}${controls}`;
  };
  function updateTurn(turn) {
    const transcript = content.querySelector('.conversation');
    if (!transcript) return;
    const scroller = transcript.closest('.session-scroll');
    const follow = scroller && scroller.scrollHeight - scroller.clientHeight - scroller.scrollTop < 60;
    if (!turn.node || !turn.node.isConnected) {
      transcript.querySelector('.empty-transcript')?.remove();
      turn.node = document.createElement('article');
      turn.node.dataset.turnId = turn.id;
      turn.node.setAttribute('aria-atomic', 'true');
      transcript.appendChild(turn.node);
    }
    turn.node.className = `caption caption-${turnClass(turn)}`;
    turn.node.innerHTML = turnHtml(turn);
    if (follow) scroller.scrollTop = scroller.scrollHeight;
  }
  function receiveTranslationRecord(event) {
    const changed = turnLedger.accept(event, state.language, state.staff_language);
    changed.forEach(updateTurn);
    if (changed.some(turn => turn.original.trim())) activity();
  }
  const welcomeHtml = () => {
    if (!state.welcome) return '';
    const status = state.welcome.status === 'error' ? uiText('Audio unavailable')
      : state.welcome.status === 'interrupted' ? uiText('Stopped') : '';
    return `<aside class="translation-welcome" data-status="${esc(state.welcome.status)}" role="status" lang="${esc(state.welcome.language)}" dir="auto"><span>Manzanilla · ${esc(uiText('Welcome'))}</span><p>${esc(state.welcome.text)}</p>${status ? `<p class="turn-status">${esc(status)}</p>` : ''}</aside>`;
  };
  function settleTranslation() {
    if (state.mode !== 'translation') return;
    turnLedger.settle(state.sessionId, state.generation).forEach(updateTurn);
    if (state.welcome && ['queued', 'playing'].includes(state.welcome.status)) state.welcome.status = 'interrupted';
  }
  const conversation = () => {
    if (state.mode === 'translation') {
      if (!state.turns.length) return `<div class="empty-transcript">${esc(uiText("Speak. We translate both ways."))}</div>`;
      return state.turns.map(turn => `<article class="caption caption-${turnClass(turn)}" data-turn-id="${esc(turn.id)}" aria-atomic="true">${turnHtml(turn)}</article>`).join('');
    }
    return state.utterances.size ? [...state.utterances.values()].map(caption).join('') : `<div class="empty-transcript">${esc(uiText("Choose a service or type a message. The microphone stays off."))}</div>`;
  };
  function updateCaption(entry) {
    const transcript = content.querySelector('.conversation');
    if (!transcript) return;
    const scroller = transcript.closest('.session-scroll');
    const follow = scroller && scroller.scrollHeight - scroller.clientHeight - scroller.scrollTop < 60;
    let node = captionNodes.get(entry.id);
    if (!node) {
      transcript.querySelector('.empty-transcript')?.remove();
      node = document.createElement('article');
      node.className = captionClass(entry);
      node.dataset.captionKey = entry.id;
      node.setAttribute('aria-atomic', 'true');
      transcript.appendChild(node);
      captionNodes.set(entry.id, node);
    }
    node.innerHTML = captionBody(entry);
    if (follow) scroller.scrollTop = scroller.scrollHeight;
  }
  const phaseLabel = () => ({ connecting: uiText("Connecting"), ready: uiText("Staff-led support"), greeting: uiText("Welcome"), thinking: uiText("Translating"), speaking: uiText("Speaking"), listening: uiText("Live"), ending: uiText("Stopping"), stopped: uiText("Stopped") })[state.phase] || uiText("Hotel assistance");
  const sessionAudioText = () => ({ off:uiText("Mic off"), recording:uiText("Mic on"), muted:uiText("Mic muted"), stopping:uiText("Releasing mic"), unknown:uiText("Mic state unknown · ask staff") })[state.audio];
  const micLabel = () => ({ recording: uiText("Mute the microphone"), muted: uiText("Unmute the microphone"), off: state.phase === 'connecting' ? uiText("Connecting · microphone off") : uiText("Microphone off"), stopping: uiText("Stopping · releasing microphone"), unknown: uiText("Microphone state unknown · ask staff") })[state.audio];
  function resetMicLevels() {
    if (state.audio !== 'recording') { state.micLevel = 0; state.bands = [0, 0, 0]; }
  }
  const sessionDock = () => {
    if (state.mode === 'translation') {
      if (state.terminated) {
        const safe = !state.active && state.audio === 'off';
        return `<div class="session-actions" data-stopped="true"><div class="session-control"><div class="session-control-copy"><strong>${esc(uiText('Stopped'))}</strong><span>${esc(sessionAudioText())}</span></div></div>${action('restart', uiText("Try again"), 'primary', !safe)}</div>`;
      }
      resetMicLevels();
      const bands = state.bands || [state.micLevel, state.micLevel, state.micLevel];
      const muted = state.audio === 'muted';
      const label = micLabel();
      const enabled = state.active && !state.closed && ['recording','muted'].includes(state.audio);
      return `<button type="button" class="voice-ball" data-action="mute" data-hearing="${state.audio === 'recording'}" aria-pressed="${muted}" aria-label="${esc(label)}" ${enabled ? '' : 'disabled'} style="--level:${state.micLevel};--b0:${bands[0]};--b1:${bands[1]};--b2:${bands[2]}">${state.audio === 'recording' ? icons.mic : icons.micOff}</button>`;
    }
    const stopped = state.closed || state.terminated;
    const restart = state.terminated && !state.active && state.audio === 'off';
    const indicator = `<div class="session-icon" role="img" aria-label="${esc(uiText('Staff-led support'))}">${icons.bell}</div>`;
    return `<div class="session-actions" data-stopped="${stopped}"><div class="session-control">${indicator}<div class="session-control-copy"><strong>${esc(stopped ? state.phase === 'ending' ? uiText("Stopping…") : uiText("Stopped") : phaseLabel())}</strong><span>${esc(sessionAudioText())}</span></div></div>${!stopped ? `<div class="service-choice">${action('message', uiText("Message staff"), 'secondary', ['connecting','greeting'].includes(state.phase) || !!serviceReason('message'))}${serviceNote('message')}</div>` : ''}${action(restart ? 'restart' : 'stop', restart ? uiText("Restart") : stopped ? uiText("Releasing…") : uiText("Stop"), 'stop', stopped && !restart)}</div>`;
  };

  function syncMic() {
    resetMicLevels();
    const button = content.querySelector('.voice-ball');
    if (!button) return;
    const muted = state.audio === 'muted';
    button.disabled = !state.active || state.closed || !['recording','muted'].includes(state.audio);
    button.dataset.hearing = String(state.audio === 'recording');
    button.setAttribute('aria-pressed', String(muted));
    button.setAttribute('aria-label', micLabel());
    button.style.setProperty('--level', String(state.micLevel));
    (state.bands || [state.micLevel, state.micLevel, state.micLevel]).forEach((band, index) => button.style.setProperty('--b' + index, String(band)));
    button.innerHTML = state.audio === 'recording' ? icons.mic : icons.micOff;
  }

  function renderNotices() {
    const target = document.getElementById('notices');
    target.hidden = !state.notices.length;
    target.innerHTML = state.notices.map((notice, index) => `<div class="notice${['error','failed','unavailable','denied'].includes(notice.status) ? ' error' : ''}"><span><strong>${esc(uiText('Service update'))}</strong> ${esc(notice.text)}</span><button data-dismiss="${index}" aria-label="${esc(uiText('Dismiss service update'))}">×</button></div>`).join('');
  }
  function addNotice(text, status = '') {
    if (!text) return;
    state.notices.push({ text: String(text), status: String(status) });
    renderNotices();
  }

  function render() {
    document.documentElement.dataset.appearance = appearance;
    document.documentElement.dataset.translation = state.page === 'active' && state.mode === 'translation' ? 'live' : '';
    document.documentElement.lang = state.language;
    document.documentElement.dir = languageDir(state.language);
    document.documentElement.dataset.night = String(state.night);
    document.documentElement.dataset.page = state.page;
    document.documentElement.dataset.captureActive = String(state.active);
    const nightToggle = document.getElementById('night-toggle');
    nightToggle.textContent = state.night ? uiText("After-hours on") : uiText("After-hours reception");
    nightToggle.setAttribute('aria-label', state.night ? uiText("Turn off after-hours reception") : uiText("Turn on after-hours reception"));
    nightToggle.setAttribute('aria-pressed', String(state.night));
    nightToggle.hidden = isHotel() || state.page !== 'idle';
    document.getElementById('settings').hidden = !native || !['idle','error','translation_languages'].includes(state.page);
    const setupLabel = isHotel() || state.mode !== 'translation' ? uiText("Staff setup") : uiText("Translation setup");
    document.getElementById('settings').setAttribute('aria-label', setupLabel);
    document.getElementById('settings').title = setupLabel;
    document.getElementById('check-updates').hidden = isHotel() || !native || state.page !== 'idle';
    if (isHotel()) document.getElementById('update-offer')?.remove();
    content.dataset.page = state.page;
    document.getElementById('mode-title').textContent = state.night ? uiText("After-hours reception") : uiText("Reception");
    const connection = document.getElementById('connection');
    connection.hidden = false;
    connection.dataset.state = state.connectionState;
    document.getElementById('connection-text').textContent = uiText({
      unconfigured: 'Hotel setup needed', checking: 'Checking hotel connection', ready: 'Hotel connected',
      offline: 'Hotel connection unavailable', unauthorized: 'Hotel authorization required'
    }[state.connectionState]);
    document.getElementById('hotel-label').textContent = state.hotel || uiText("Hotel reception");
    document.getElementById('privacy').innerHTML = `<span class="mic-dot${state.audio === 'recording' ? ' mic-live' : ''}"></span>${esc(sessionAudioText())}`;
    document.title = uiText('Reception');
    document.getElementById('check-updates').textContent = uiText('Check updates');
    document.querySelector('.clock').setAttribute('aria-label', uiText('Local date and time'));
    document.getElementById('notices').setAttribute('aria-label', uiText('Hotel service updates'));
    updateClock();
    const guestLocale = () => `<label class="assist-label" for="guest-locale">${esc(uiText('Language'))}</label><select id="guest-locale" lang="${state.language}">${Object.entries(languages).map(([code,item]) => `<option value="${code}" lang="${code}" dir="${item.direction}" ${state.language === code ? 'selected' : ''}>${esc(item.native)}</option>`).join('')}</select>`;
    const homeAction = (name, icon, label, note = '', unavailable = '') => `<button class="home-action${name === (state.night ? 'reception' : 'translation') ? ' home-action-main' : ''}" data-action="${name}" ${unavailable ? 'disabled' : ''}>${name === (state.night ? 'reception' : 'translation') ? '<span class="orb">' + icons.arrow + '</span>' : icon}<span>${esc(label)}</span>${unavailable || note ? '<small>' + esc(unavailable || note) + '</small>' : ''}</button>`;
    const html = {
      idle: () => `<div class="welcome"><h1>${esc(uiText('Welcome.'))}</h1><p>${esc(uiText(state.night ? 'Self-service hotel assistance.' : 'Guest and staff support.'))}</p></div>${guestLocale()}<nav class="home-actions" aria-label="${esc(uiText('Reception services'))}">${state.night ? homeAction('reception',icons.bell,uiText("Hotel assistance"),uiText("Self-service support"),serviceReason()) + homeAction('translation',icons.translate,uiText("Live translation"),state.audioAvailable ? uiText("Talk to each other") : uiText("Audio unavailable")) : homeAction('translation',icons.translate,uiText("Live translation"),state.audioAvailable ? uiText("Talk to each other") : uiText("Audio unavailable")) + homeAction('reception',icons.bell,uiText("Hotel assistance"),'',serviceReason())}${homeAction('request:checkin',icons.bed,uiText('Arrival guide'),'',serviceReason('checkin'))}${homeAction('request:taxi',icons.car,uiText('Request a taxi through staff'),'',serviceReason('taxi'))}${homeAction('request:message',icons.message,uiText("Message staff"),'',serviceReason('message'))}</nav>${!isHotel() ? `<div class="appearance-row"><button class="appearance-toggle" data-action="appearance" aria-label="${esc(uiText('Appearance'))}" title="${esc(uiText('Appearance'))}">${themeIcon()}</button></div>` : ''}`,
      languages: () => `<div class="status-line"><div><h2 class="language-heading">${esc(uiText('Hotel assistance'))}</h2></div><button class="back" data-action="back">${icons.back}${esc(uiText('Back'))}</button></div><label class="assist-label" for="assist-language">${esc(uiText('Language'))}</label><select id="assist-language" class="assist-language" lang="${state.language}" dir="${languageDir(state.language)}">${Object.entries(languages).map(([id,item]) => `<option value="${id}" lang="${id}" dir="${languageDir(id)}" ${state.language === id ? 'selected' : ''}>${esc(item.native)}</option>`).join('')}</select><div class="service-availability">${[...shortcuts,'message'].map((id,i) => `<div><strong>${esc(i < shortcuts.length ? concierge()[i] : uiText('Message staff'))}</strong>${serviceNote(id)}</div>`).join('')}</div>${serviceNote()}${action('assist-go',uiText("Continue"),'primary assist-go',!!serviceReason(queuedShortcut))}`,
      translation_languages: () => `<div class="status-line"><div><h2 class="language-heading">${esc(uiText('Choose languages'))}</h2><p class="keypad-hint">${esc(uiText('Guest ↔ Receptionist'))}</p></div><button class="back" data-action="back">${icons.back}${esc(uiText('Back'))}</button></div><div class="language-pair">${languagePicker('language',uiText("Guest language"))}${languagePicker('staff_language',uiText("Receptionist language"))}</div><p id="translation-readiness" class="setup-feedback" role="status">${esc(translationReadiness())}</p><button type="button" class="primary setup-start" data-action="${native && !state.audioAvailable ? 'setup-translation' : 'start'}" aria-describedby="translation-readiness" ${state.audio !== 'off' ? 'disabled' : ''}>${esc(uiText(native && !state.audioAvailable ? 'Set up translation' : 'Start translation'))}</button><p class="fine">${esc(uiText('Speech recognition → Gemini translation → Fish speech'))}</p>`,
      active: () => state.mode === 'translation'
        ? `<button class="translate-back" data-action="back" aria-label="${esc(uiText('Back'))}">${icons.back}</button><p class="pair-title" dir="ltr"><bdi>${esc(langName(state.language))}</bdi> ↔ <bdi>${esc(langName(state.staff_language))}</bdi></p><div class="session-scroll"><div id="translation-welcome">${welcomeHtml()}</div><div class="conversation" role="log" aria-label="${esc(uiText('Conversation transcript'))}" aria-live="polite">${conversation()}</div></div>${sessionDock()}`
        : `<div class="status-line session-top"><div><h2>${esc(uiText('Hotel assistance'))}</h2><p>${esc(phaseLabel())}</p></div><button class="back" data-action="back">${icons.back}${esc(uiText('Back'))}</button></div>${state.error ? `<div class="notice error">${esc(state.error)}</div>` : ''}<div class="session-scroll">${!state.closed ? `<div class="shortcut-list">${shortcuts.map((id,i) => serviceChoice(id, concierge()[i], ['connecting','greeting'].includes(state.phase))).join('')}</div>` : ''}<div class="conversation" role="log" aria-label="${esc(uiText('Conversation transcript'))}" aria-live="polite">${conversation()}</div>${state.metrics ? `<p class="metrics">${esc(state.metrics)}</p>` : ''}</div>${sessionDock()}`,
      operation_form: () => `<div class="status-line"><h2>${esc(state.operation?.action === 'message' ? uiText("Message staff") : concierge()[shortcuts.indexOf(state.operation?.action)] || uiText("Hotel request"))}</h2><button class="back" data-action="back">${icons.back}${esc(uiText('Back'))}</button></div>${guestLocale()}${serviceNote(state.operation?.action)}<form id="operation-form" class="operation-form" aria-busy="${!!state.operation?.pending}">${(state.operation?.fields || []).map(operationField).join('')}<div class="button-row"><button type="submit" class="primary" ${state.operation?.pending || reviewBlocked() ? 'disabled' : ''}>${esc(uiText(state.operation?.pending ? 'Reviewing…' : state.operation?.reconciliationPending ? 'Retry' : 'Review'))}</button>${action('cancel-operation',uiText("Cancel"),'secondary',state.operation?.pending || state.operation?.reconciliationPending)}</div></form>${action('stop',uiText("End"),'stop')}`,
      confirming: () => `<div class="status-line"><h2>${esc(uiText('Confirm request?'))}</h2><button class="back" data-action="back">${icons.back}${esc(uiText('Back'))}</button></div><div class="draft" dir="auto">${esc(state.draft?.message || '')}</div>${confirmationReason() ? fine(confirmationReason()) : ''}${fine(state.draft?.attempted ? state.draft.approved === false ? uiText("No cancellation receipt yet. Retry to check the result.") : uiText("No receipt yet. Retry the same request to check its result.") : uiText("Confirm only if the details are correct."))}<div class="button-row">${action(state.draft?.attempted ? 'retry-draft' : 'confirm',state.confirmed ? state.draft?.approved === false ? uiText("Cancelling…") : uiText("Sending…") : state.draft?.attempted ? uiText("Retry") : uiText("Confirm"),'primary',state.confirmed || (!state.draft?.attempted && !!confirmationReason()))}${state.operation ? action('edit-draft',uiText("Edit"),'secondary',state.confirmed || state.draft?.attempted) : ''}${action('cancel-draft',uiText("Cancel"),'secondary',state.confirmed)}</div>`,
      saved: () => `<h2>${esc(uiText('Request receipt'))}</h2><section class="request-receipt"><p class="lead" dir="auto">${esc(state.outcome?.text || uiText('No receipt returned. Ask staff to check.'))}</p>${state.outcome?.receipt ? `<div class="receipt" dir="auto"><bdi>${esc(state.outcome.receipt)}</bdi></div>` : ''}</section>${requestStatus()}<div class="button-row">${state.pendingShortcut?.reconciliationPending ? action('retry-shortcut',uiText(state.pendingShortcut.pending ? 'Sending…' : 'Retry'),'primary',state.pendingShortcut.pending) : `${state.outcome?.helpAction === 'operator' ? action('receipt-help',uiText('Request help from reception')) : ''}${action('continue',uiText("Continue"))}`}${action('stop',uiText("End"),'secondary')}</div>`,
      error: () => `<div class="status-line"><h2>${esc(uiText('Conversation unavailable'))}</h2><button class="back" data-action="back">${icons.back}${esc(uiText('Back'))}</button></div><div class="notice error">${esc(state.error || uiText("Please ask reception for help."))}</div>${fine(state.audio === 'off' ? uiText("Microphone off. Staff can check this device’s setup.") : uiText("Audio release is not confirmed. Ask reception for help before restarting."))}<div class="button-row">${action('retry',uiText("Try again"),'primary',state.audio !== 'off')}${action('home',uiText("Back to home"),'secondary')}</div>`
    };
    const focusedElement = document.activeElement;
    const focusedAction = focusedElement?.dataset?.action;
    const focusedSelect = focusedElement?.dataset?.languageSide;
    const focusedAssist = focusedElement?.id === 'assist-language';
    const focusedLocale = focusedElement?.id === 'guest-locale';
    const focusedInput = focusedElement?.closest('#operation-form') ? { name:focusedElement.name,start:focusedElement.selectionStart,end:focusedElement.selectionEnd } : null;
    const scrollTop = content.scrollTop;
    const scrolls = [...content.querySelectorAll('.session-scroll,.draft,.language-list')].map(element => ({ top: element.scrollTop, follow: element.classList.contains('session-scroll') && element.scrollHeight - element.clientHeight - element.scrollTop < 60 }));
    const retainedTurns = new Map(state.turns.filter(turn => turn.node?.isConnected).map(turn => [turn.id, turn.node]));
    captionNodes.clear();
    content.innerHTML = (html[state.page] || html.idle)();
    if (['idle','languages','translation_languages'].includes(state.page)) content.insertAdjacentHTML('beforeend', fine(uiText('Interface translations have not been reviewed by a human.')));
    if (state.page === 'translation_languages') content.insertAdjacentHTML('beforeend', fine(uiText('Speech support depends on the provider and tablet. Staff qualification is required.')));
    content.querySelectorAll('[data-caption-key]').forEach(node => captionNodes.set(node.dataset.captionKey, node));
    const turnNodes = new Map([...content.querySelectorAll("[data-turn-id]")].map(node => [node.dataset.turnId, node]));
    state.turns.forEach(turn => {
      const rendered = turnNodes.get(turn.id);
      const retained = retainedTurns.get(turn.id);
      if (rendered && retained) {
        retained.className = rendered.className;
        rendered.replaceWith(retained);
        turn.node = retained;
      } else turn.node = rendered || null;
    });
    content.scrollTop = scrollTop;
    [...content.querySelectorAll('.session-scroll,.draft,.language-list')].forEach((element,index) => {
      const previous = scrolls[index];
      element.scrollTop = previous?.follow ? element.scrollHeight : previous?.top || 0;
    });
    if (focusedAction) [...content.querySelectorAll('[data-action]')].find(button => button.dataset.action === focusedAction)?.focus({ preventScroll:true });
    if (focusedSelect) content.querySelector(`[data-language-side="${focusedSelect}"]`)?.focus({ preventScroll:true });
    if (focusedAssist) content.querySelector('#assist-language')?.focus({ preventScroll:true });
    if (focusedLocale) content.querySelector('#guest-locale')?.focus({ preventScroll:true });
    if (focusedInput) {
      const input = [...content.querySelectorAll('#operation-form input,#operation-form textarea')].find(element => element.name === focusedInput.name);
      input?.focus({ preventScroll:true });
      if (input && focusedInput.start !== null) input.setSelectionRange(focusedInput.start,focusedInput.end);
    }
    renderNotices();
    syncReceiptWatch();
  }

  function activity() { send('activity'); }
  function applyAppearance(value) {
    if (value !== 'light' && value !== 'dark') return;
    const changed = appearance !== value;
    appearance = value;
    if (changed) render();
  }
  function toggleAppearance() {
    if (isHotel() || state.page !== 'idle' || state.active) return;
    activity();
    applyAppearance(appearance === 'light' ? 'dark' : 'light');
    send('appearance', { value:appearance });
  }
  function sameLanguage() { return state.mode === 'translation' && state.language === state.staff_language; }
  function translationReadiness() {
    if (state.audio !== 'off') return state.audio === 'unknown'
      ? uiText("Microphone release could not be confirmed. Retry after the previous conversation closes.")
      : uiText("Waiting for the previous conversation to release the microphone.");
    if (!state.audioAvailable) return state.audioGate || uiText('Checking translation setup…');
    if (sameLanguage()) return uiText("Same language: choose who spoke for each turn.");
    return uiText("Ready. Tap Start translation to turn on the microphone.");
  }
  function page(next) { if (next !== 'saved') unwatchReceipt(); state.page = next; render(); }
  function toggleNight() {
    if (isHotel() || state.page !== 'idle' || state.active) return;
    activity();
    state.night = !state.night;
    state.mode = state.night ? 'reception' : 'translation';
    render();
    send('night', { enabled: state.night });
  }
  function chooseMode(mode) {
    if (state.active || state.audio !== 'off') return;
    if (mode === 'reception' && serviceReason()) { toast(serviceReason()); return; }
    queuedShortcut = '';
    state.mode = mode;
    if (mode === 'translation') {
      for (const side of ['language','staff_language']) if (!hasOwn(translationLanguages, state[side])) state[side] = 'es';
    }
    state.error = '';
    silence();
    page(mode === 'translation' ? 'translation_languages' : 'languages');
  }
  function clearGuest() {
    unwatchReceipt();
    state.utterances.clear();
    captionNodes.clear();
    turnLedger.clear();
    state.welcome = null;
    const transcript = content.querySelector('.conversation');
    if (transcript) transcript.innerHTML = conversation();
    state.draft = null;
    state.confirmed = false;
    state.operation = null;
    state.outcome = null;
    state.pendingShortcut = null;
    state.metrics = '';
    state.notices = [];
    document.getElementById('toast').hidden = true;
    renderNotices();
  }
  function selectLanguage(side, language) {
    if (state.audio !== 'off' || !hasOwn(languages, language) || (state.active && (state.mode === 'translation' || side !== 'language'))) return;
    if (state[side] === language) return;
    state[side] = language;
    selected[side] = true;
    if (side === 'language' && state.configured) { state.connectionState = 'checking'; state.services = null; }
    send('languages', { language: state.language, staff_language: state.staff_language });
  }
  function start(restart = false) {
    if (state.active || state.phase === 'ending' || state.audio !== 'off') return;
    if (state.mode === 'reception' && serviceReason(queuedShortcut)) { toast(serviceReason(queuedShortcut)); return; }
    if (state.mode === 'translation' && !state.audioAvailable) {
      if (native) send('voice_setup'); else toast(state.audioGate || uiText('Checking translation setup…'));
      return;
    }
    if (state.mode === 'translation' && (!hasOwn(translationLanguages, state.language) || !hasOwn(translationLanguages, state.staff_language))) {
      toast(uiText("Choose supported languages.")); return;
    }
    if (!native) { state.error = uiText("Voice conversations require the reception tablet. No microphone has been opened."); page('error'); return; }
    silence();
    if (!restart) clearGuest();
    state.welcome = null;
    state.pendingPage = '';
    state.micLevel = 0;
    state.sessionId = window.crypto?.randomUUID?.() || 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, character => {
      const value = window.crypto?.getRandomValues ? window.crypto.getRandomValues(new Uint8Array(1))[0] & 15 : Math.floor(Math.random() * 16);
      return (character === 'x' ? value : (value & 3) | 8).toString(16);
    });
    state.generation = null;
    state.closed = false;
    state.terminated = false;
    state.active = true;
    state.phase = 'connecting';
    state.error = '';
    page('active');
    send('start', { session_id: state.sessionId, mode: state.mode, language: state.language, staff_language: state.staff_language });
  }
  function silence() {
    send('silence');
    state.micLevel = 0;
  }
  function chooseShortcut(name) {
    if (!state.active || state.closed || state.mode !== 'reception' || state.page !== 'active' || ['connecting','greeting'].includes(state.phase)) return;
    if (state.pendingShortcut) return;
    if (serviceReason(name)) { toast(serviceReason(name)); return; }
    state.requestedAction = name;
    if (state.demo) { addNotice(uiText("Hotel requests require a connected reception tablet."), 'unavailable'); return; }
    state.pendingShortcut = { action: name, arguments: {}, pending: true, reconciliationPending: false };
    send(name, { arguments: state.pendingShortcut.arguments });
  }
  function finishNavigation() {
    if (!state.pendingPage || !state.terminated || state.active || state.audio !== 'off') return false;
    const next = state.pendingPage;
    state.pendingPage = '';
    queuedShortcut = '';
    if (next === 'idle') clearGuest();
    page(next);
    return true;
  }
  function stop(next = '') {
    unwatchReceipt();
    if (next) state.pendingPage = next;
    if (!state.active) { finishNavigation(); return; }
    if (state.phase === 'ending') return;
    silence();
    settleTranslation();
    state.closed = true;
    state.draft = null;
    state.confirmed = false;
    state.operation = null;
    state.outcome = null;
    state.pendingShortcut = null;
    queuedShortcut = '';
    state.phase = 'ending';
    if (state.audio === 'recording') state.audio = 'stopping';
    page('active');
    send('stop');
  }
  function goBack() {
    if (state.page === 'idle') return;
    if (state.active || state.audio !== 'off') {
      stop(state.mode === 'translation' ? 'translation_languages' : 'languages');
      return;
    }
    queuedShortcut = '';
    page(state.page === 'active' ? state.mode === 'translation' ? 'translation_languages' : 'languages' : 'idle');
  }
  let toastTimer;
  function toast(message) {
    const target = document.getElementById('toast');
    target.textContent = message;
    target.hidden = false;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => { target.hidden = true; }, 5500);
  }
  function perform(name) {
    activity();
    if (name.startsWith('resolve:')) {
      const [,role,key] = name.split(':');
      const turn = turnLedger.byId.get(decodeURIComponent(key));
      if (state.closed || !turn || turn.status !== 'needs_direction' || turn.recognitionStatus === 'partial' ||
          turn.directionReason === 'mismatch' || !['guest','receptionist'].includes(role)) return;
      send('resolve_direction', { utterance_id: turn.utteranceId, revision: turn.revision, role });
      return;
    }
    if (name.startsWith('request:')) {
      const request = name.split(':')[1];
      if (state.page !== 'idle' || !['checkin','taxi','message'].includes(request)) return;
      if (serviceReason(request)) { toast(serviceReason(request)); return; }
      chooseMode('reception');
      if (state.page === 'languages') queuedShortcut = request;
      return;
    }
    if (name.startsWith('pair:')) {
      const [,side,language] = name.split(':');
      if (state.page !== 'translation_languages' || state.audio !== 'off' || !['language','staff_language'].includes(side) || !hasOwn(translationLanguages,language)) return;
      selectLanguage(side,language);
      languageSide = side;
      render();
      return;
    }
    if (name.startsWith('language:')) { const language=name.split(':')[1]; if(state.page !== 'languages' || !languages[language])return;selectLanguage('language',language); start(); return; }
    switch (name) {
      case 'appearance': toggleAppearance(); break;
      case 'translation': chooseMode('translation'); break;
      case 'reception': chooseMode('reception'); break;
      case 'assist-go': {
        const language = document.getElementById('assist-language')?.value;
        if (state.page !== 'languages' || !languages[language]) return;
        selectLanguage('language', language);
        start();
        break;
      }
      case 'start': start(); break;
      case 'setup-translation': if (native && !state.active && state.audio === 'off') send('voice_setup'); break;
      case 'mute':
        if (state.mode === 'translation' && state.active && !state.closed && ['recording','muted'].includes(state.audio)) {
          activity();
          send('mute', { muted: state.audio !== 'muted' });
        }
        break;
      case 'restart': if (state.terminated && state.audio === 'off') start(true); break;
      case 'stop': stop(); break;
      case 'back': goBack(); break;
      case 'home': if (state.active || state.audio !== 'off') stop('idle'); else { clearGuest(); page('idle'); } break;
      case 'retry': if(state.mode === 'reception') start(); else page('translation_languages'); break;
      case 'checkin': case 'operator': case 'taxi': case 'question': chooseShortcut(name); break;
      case 'message': chooseShortcut('message'); break;
      case 'retry-shortcut': {
        const attempt = state.pendingShortcut;
        if (state.page !== 'saved' || !state.active || state.closed || !attempt?.reconciliationPending || attempt.pending) return;
        attempt.pending = true;
        send(attempt.action, { arguments: attempt.arguments });
        render();
        break;
      }
      case 'receipt-help':
        if (state.page !== 'saved' || state.outcome?.helpAction !== 'operator' || !state.active || state.closed) return;
        if (serviceReason('operator')) { toast(serviceReason('operator')); return; }
        state.outcome = null;
        page('active');
        chooseShortcut('operator');
        break;
      case 'cancel-operation': if (!state.operation?.pending && !state.operation?.reconciliationPending) { state.operation = null; page('active'); } break;
      case 'edit-draft':
        if (!state.active || state.closed || state.confirmed || !state.operation || !state.draft || state.draft.attempted) return;
        state.draft = null;
        state.operation.pending = false;
        page('operation_form');
        break;
      case 'confirm':
      case 'retry-draft':
      case 'cancel-draft':
        if (!state.active || state.closed || !state.draft || state.confirmed) return;
        if (name === 'retry-draft' && !state.draft.attempted) return;
        if (name === 'confirm' && !state.draft.attempted && confirmationReason()) { toast(confirmationReason()); return; }
        state.confirmed = true;
        if (name !== 'retry-draft') state.draft.approved = name === 'confirm';
        state.draft.attempted = true;
        send('confirm', { draft_id: state.draft.draft_id, approved: state.draft.approved });
        render();
        break;
      case 'continue':
        if (state.pendingShortcut?.reconciliationPending) return;
        state.outcome = null;
        page(state.active && !state.closed ? 'active' : 'idle');
        break;
    }
  }
  content.addEventListener('click', event => { const button = event.target.closest('[data-action]'); if (button && !button.disabled) perform(button.dataset.action); });
  content.addEventListener('focusin', event => {
    const side = event.target.dataset.languageSide;
    if (!['language','staff_language'].includes(side)) return;
    languageSide = side;
    content.querySelectorAll('.language-side').forEach(element => { element.dataset.keypad = String(element.querySelector('select').dataset.languageSide === side); });
  });
  content.addEventListener('change', event => {
    const side = event.target.dataset.languageSide;
    if (side) perform(`pair:${side}:${event.target.value}`);
    else if (event.target.id === 'assist-language' || event.target.id === 'guest-locale') {
      selectLanguage('language', event.target.value);
      render();
    }
  });
  content.addEventListener('input', event => {
    const input = event.target;
    if (state.operation && input.closest('#operation-form')) {
      state.operation.values[input.name] = input.value;
      input.setCustomValidity('');
    }
    activity();
  });
  content.addEventListener('submit', event => {
    if (event.target.id !== 'operation-form') return;
    event.preventDefault();
    if (!state.active || state.closed || !state.operation || state.operation.pending || reviewBlocked()) return;
    for (const input of event.target.querySelectorAll('[required]')) input.setCustomValidity(input.value.trim() ? '' : uiText("Please complete this field."));
    if (!event.target.reportValidity()) return;
    activity();
    const operation = state.operation;
    operation.pending = true;
    send(operation.action, { arguments: { ...operation.values } });
    render();
  });
  document.getElementById('notices').addEventListener('click', event => {
    const button = event.target.closest('[data-dismiss]');
    if (!button) return;
    state.notices.splice(Number(button.dataset.dismiss), 1);
    activity();
    renderNotices();
  });
  document.getElementById('night-toggle').addEventListener('click', toggleNight);
  document.getElementById('settings').addEventListener('click',()=>send(state.mode === 'translation' ? 'voice_setup' : 'settings'));
  document.getElementById('check-updates').addEventListener('click',()=>{ if (!isHotel()) send('check_updates'); });

  function hardware(key) {
    activity();
    if (state.page === 'translation_languages' && /^[0-9]$/.test(key)) { perform('pair:' + languageSide + ':' + Object.keys(translationLanguages)[key === '0' ? 9 : Number(key)-1]); return; }
    if (state.page === 'languages' && /^[0-9]$/.test(key)) { perform('language:' + Object.keys(languages)[key === '0' ? 9 : Number(key)-1]); return; }
    if (key === 'back' || key === 'red' || key === '0') { goBack(); return; }
    if (key === 'green') {
      if (state.active) stop();
      else if (state.page === 'idle') chooseMode(state.night ? 'reception' : 'translation');
      else if (state.page === 'translation_languages') start();
      else if (state.page === 'active' && state.terminated) start(true);
      return;
    }
    if (state.page === 'idle' && key === '1') toggleAppearance();
    else if (state.page === 'idle' && key === '2') chooseMode('reception');
    else if (state.page === 'active' && state.mode === 'reception' && /^[1-4]$/.test(key)) chooseShortcut(shortcuts[Number(key)-1]);
  }

  function offerUpdate(version, text, phase, percent) {
    if (isHotel()) return;
    let bar = document.getElementById('update-offer');
    if (!bar) {
      bar = document.createElement('button');
      bar.id = 'update-offer';
      bar.type = 'button';
      bar.className = 'update-offer';
      bar.addEventListener('click', () => {
        if (['checking','progress','installing'].includes(bar.dataset.phase)) return;
        const action = bar.dataset.phase === 'current' ? 'check_updates' : 'apply_update';
        bar.dataset.phase = action === 'check_updates' ? 'checking' : 'installing';
        send(action);
      });
      document.body.appendChild(bar);
    }
    phase = phase || 'ready';
    bar.dataset.phase = phase;
    if (phase === 'installed') { bar.hidden = true; return; }
    bar.hidden = false;
    if (['checking','progress','installing'].includes(phase)) {
      bar.disabled = true;
      const pct = Number(percent);
      const known = Number.isFinite(pct) && pct >= 0;
      bar.innerHTML = `${esc(text || uiText("Downloading…"))}<span class="track"><span class="fill" style="width:${known ? Math.min(100,pct) : 0}%"></span></span>`;
      return;
    }
    bar.disabled = false;
    if (phase === 'current') bar.textContent = text || uiText("Up to date. Tap to check again.");
    else if (phase === 'confirm') bar.textContent = text || uiText("Tap to open the installer. The file is already downloaded.");
    else if (phase === 'failed') bar.textContent = text || uiText("Update did not finish. Tap to try the installer again.");
    else bar.textContent = text || uiText('Update {version} is downloaded. Tap to install.', { version });
  }
  function matchesSession(event) {
    if (!state.sessionId || event.session_id !== state.sessionId || !Number.isInteger(event.generation) || event.generation < 0) return false;
    if (state.generation === null) {
      state.generation = event.generation;
      // Back can precede the native generation handshake. Retain that intent
      // and cancel the exact admitted session before consuming its late events.
      if (state.closed && !state.terminated && state.phase === 'ending') {
        send('silence');
        send('stop');
      }
    }
    return event.generation === state.generation;
  }
  function detachGuest(preserveHistory = false) {
    unwatchReceipt();
    settleTranslation();
    state.closed = true;
    state.terminated = true;
    state.active = false;
    state.phase = preserveHistory ? 'stopped' : '';
    state.micLevel = 0;
    state.draft = null;
    state.confirmed = false;
    state.operation = null;
    state.outcome = null;
    state.pendingShortcut = null;
    queuedShortcut = '';
    if (state.audio !== 'off') state.audio = 'unknown';
    if (state.audio === 'off') send('languages', { language: state.language, staff_language: state.staff_language });
    if (!preserveHistory) { clearGuest(); state.pendingPage = ''; }
  }
  function receive(event) {
    if (typeof event === 'string') { try { event = JSON.parse(event); } catch { return; } }
    if (!event || typeof event !== 'object') return;
    const type = event.type;
    if (type === 'translation_setup_return') {
      if (!state.active && state.audio === 'off') { state.mode = 'translation'; page('translation_languages'); }
      return;
    }
    if (type === 'navigation' && event.action === 'back') {
      if (state.active && !matchesSession(event)) return;
      goBack();
      return;
    }
    if (type === 'appearance') { applyAppearance(event.value); return; }
    if (type === 'visibility') {
      if (event.visible === false) { detachGuest(); state.error = ''; page('idle'); }
      return;
    }
    if (type === 'night_mode') {
      if (typeof event.enabled !== 'boolean' || state.night === event.enabled) return;
      state.night = event.enabled;
      if (state.page === 'idle') state.mode = state.night ? 'reception' : 'translation';
      render();
      return;
    }
    if (type === 'connection') {
      state.connectionState = ['unconfigured','checking','ready','offline','unauthorized'].includes(event.state) ? event.state : 'offline';
      state.connected = state.connectionState === 'ready' && event.connected === true;
      if (!state.connected && state.connectionState === 'ready') state.connectionState = 'offline';
      render(); return;
    }
    if (type === 'reception_info') {
      state.distribution = event.distribution === 'hotel' ? 'hotel' : 'personal-test';
      state.configured = event.configured === true;
      state.services = null;
      if (event.services_supported !== false && event.services && serviceNames.every(id => typeof event.services[id] === 'boolean')) {
        state.services = {};
        serviceNames.forEach(id => { state.services[id] = event.services[id]; });
      }
      if (['unconfigured','checking','ready','offline','unauthorized'].includes(event.state)) {
        state.connectionState = event.state;
        state.connected = event.state === 'ready';
      } else if (!state.configured) {
        state.connectionState = 'unconfigured';
        state.connected = false;
      }
      state.hotel = String(event.hotel_name || '');
      state.audioAvailable = event.audio_available === true;
      if (!state.active && ['off','unknown'].includes(event.local_audio_state)) state.audio = event.local_audio_state;
      if (typeof event.audio_gate === 'string' && event.audio_gate) state.audioGate = event.audio_gate;
      if (!state.active && !defaultReceived && !preferencesRestored && !selected.staff_language &&
          hasOwn(state.page === 'translation_languages' ? translationLanguages : languages, event.staff_language)) state.staff_language = event.staff_language;
      defaultReceived = true;
      render(); return;
    }
    if (type === 'language_preferences') {
      if (state.active && state.mode === 'translation') return;
      for (const side of ['language','staff_language']) {
        if (!selected[side]) state[side] = hasOwn(state.page === 'translation_languages' ? translationLanguages : languages, event[side]) ? event[side] : 'es';
      }
      preferencesRestored = true;
      render(); return;
    }
    if (type === 'update') { offerUpdate(String(event.version || '')); return; }
    if (type === 'update_status') { offerUpdate(String(event.version || ''), String(event.text || uiText("Updating…")), String(event.phase || ''), event.percent); return; }
    if (!matchesSession(event)) return;
    if (type === 'audio_state') {
      if (!['off','recording','stopping','unknown','muted'].includes(event.state)) return;
      if (state.closed && !['off','unknown'].includes(event.state)) {
        if (state.terminated) return;
        state.audio = 'stopping';
      } else state.audio = event.state;
      resetMicLevels();
      if (!finishNavigation()) {
        if (state.page === 'active' && state.mode === 'translation' && !state.terminated) {
          // A mic toggle only changes the dock and privacy line; rebuilding the
          // transcript would flicker and drop streaming captions mid-utterance.
          const privacy = document.getElementById('privacy');
          if (privacy) privacy.innerHTML = `<span class="mic-dot${state.audio === 'recording' ? ' mic-live' : ''}"></span>${esc(sessionAudioText())}`;
          syncMic();
        } else {
          render();
        }
      }
      return;
    }
    if (state.terminated) return;
    const terminal = type === 'reception_event' && ['stopped','timeout','error'].includes(event.phase);
    if (state.closed && !terminal) return;
    if (type === 'input_activity') { activity(); return; }
    if (type === 'audio_processing') {
      if (event.aec === false || event.noise_suppression === false)
        addNotice(uiText('Echo cancellation or noise suppression is unavailable on this tablet. Use headphones.'), 'unavailable');
      return;
    }
    if (type === 'request_status') {
      if (state.page !== 'saved' || !watchedReceipt || event.item_id !== watchedReceipt ||
          event.item_id !== state.outcome?.itemId) return;
      if (Number.isInteger(event.version) && event.version >= (state.outcome.statusVersion ?? -1) &&
          ['open','acknowledged','resolved'].includes(event.status)) {
        state.outcome.staffStatus = event.status;
        state.outcome.statusVersion = event.version;
      }
      state.outcome.statusUnavailable = event.status_available === false;
      // Update only the live staff state, never the immutable execution receipt.
      const target = content.querySelector('.request-status');
      if (target) target.outerHTML = requestStatus();
      return;
    }
    if (type === 'mic_level') {
      const level = state.audio === 'recording' ? Math.min(1, Math.max(0, Number(event.level) || 0)) : 0;
      state.micLevel = level;
      if (state.audio === 'recording' && Array.isArray(event.bands) && event.bands.length >= 3) state.bands = event.bands.slice(0, 3).map(value => Math.min(1, Math.max(0, Number(value) || 0)));
      else state.bands = [level, level * 0.72, level * 0.4];
      const meter = content.querySelector('.voice-ball');
      meter?.style.setProperty('--level', String(level));
      (state.bands || []).forEach((band, index) => meter?.style.setProperty('--b' + index, String(band)));
      return;
    }
    if (type === 'notice') {
      addNotice(event.text, event.status);
      if (['error','failed','unavailable','denied','save_failed'].includes(event.status)) {
        state.confirmed = false;
        if (state.operation) state.operation.pending = false;
        render();
      }
      return;
    }
    if (type === 'metrics') { state.metrics = String(event.text || ''); if (state.page === 'active') render(); return; }
    if (type === 'translation.utterance') {
      if (state.mode === 'translation') receiveTranslationRecord(event);
      return;
    }
    if (type === 'translation.welcome') {
      if (state.mode !== 'translation' || typeof event.text !== 'string' || event.language !== state.language ||
          !Number.isInteger(event.output_generation) || event.output_generation < 1 ||
          !['queued', 'playing', 'complete', 'interrupted', 'error'].includes(event.status) ||
          (state.welcome && (state.welcome.output_generation !== event.output_generation ||
            ['complete', 'interrupted', 'error'].includes(state.welcome.status)))) return;
      state.welcome = { text: event.text, language: event.language, output_generation: event.output_generation, status: event.status };
      const welcome = document.getElementById('translation-welcome');
      if (welcome) welcome.innerHTML = welcomeHtml();
      return;
    }
    if (type === 'caption') {
      if (typeof event.utterance_id !== 'string' || !event.utterance_id || !['human','guest','staff','assistant'].includes(event.side) || !['original','text'].includes(event.field) || !['append','replace'].includes(event.operation) || typeof event.text !== 'string' || typeof event.final !== 'boolean') return;
      if (state.mode === 'translation') return;
      // Unlinked provider IDs identify revisions of one field, never a translation pair.
      const unlinked = event.association === 'unlinked';
      const key = JSON.stringify([state.sessionId, event.utterance_id, unlinked ? event.field : null]);
      let entry = state.utterances.get(key);
      if (!entry) {
        const side = event.side === 'assistant' ? 'assistant' : unlinked && event.speaker_attribution !== 'selected' ? 'human' : event.side;
        entry = { id: key, side, association: unlinked ? 'unlinked' : 'linked', speakerAttribution: side !== 'assistant' && event.speaker_attribution === 'selected' ? 'selected' : 'unknown', from: '', to: '', original: '', text: '', final: { original: false, text: false } };
        state.utterances.set(key, entry);
      }
      if (entry.final[event.field] && (!event.final || event.operation === 'append')) return;
      entry[event.field] = event.operation === 'replace' ? event.text : entry[event.field] + event.text;
      entry.final[event.field] = event.final;
      if (typeof event.from === 'string' && event.from) entry.from = event.from;
      if (typeof event.to === 'string' && event.to) entry.to = event.to;
      updateCaption(entry);
      if (event.side !== 'assistant' && event.text.trim()) activity();
      return;
    }
    if (type === 'operation_form') {
      if (![...shortcuts,'message'].includes(event.action) || !Array.isArray(event.fields) || state.draft) return;
      const fields = event.fields.filter(field => field && typeof field.name === 'string' && field.name && typeof field.label === 'string');
      if (!fields.length) { addNotice(uiText("This request did not provide usable fields. Please ask reception for help."), 'unavailable'); return; }
      const previous = state.operation?.action === event.action ? state.operation : null;
      const allFields = new Map((previous?.fields || []).map(field => [field.name, field]));
      fields.forEach(field => allFields.set(field.name, field));
      const emptyValues = Object.create(null);
      for (const name of allFields.keys()) emptyValues[name] = '';
      state.operation = { action: event.action, fields: [...allFields.values()], values: { ...emptyValues, ...previous?.values }, pending: false };
      state.pendingShortcut = null;
      page('operation_form'); return;
    }
    if (type === 'operation_result') {
      const text = String(event.text || '');
      if (['confirmation_required','needs_confirmation','fields_required'].includes(event.status)) return;
      if (['unavailable','error','pending'].includes(event.status) && event.result?.help_action !== 'operator' && (state.operation || state.draft)) {
        // A lost response may already have saved the note. Keep its confirmation
        // identity so Retry is idempotent instead of submitting a second note.
        state.confirmed = false;
        if (state.operation) {
          state.operation.pending = false;
          state.operation.reconciliationPending = event.reconciliation_pending === true || state.operation.reconciliationPending === true;
        }
        addNotice(text || uiText("No receipt was received. Retry or ask reception to check."), event.status);
        page(state.draft ? 'confirming' : 'operation_form');
        return;
      }
      if (['unavailable','error','pending'].includes(event.status) && state.pendingShortcut &&
          (event.reconciliation_pending === true || event.status === 'pending' || state.pendingShortcut.reconciliationPending)) {
        state.pendingShortcut.pending = false;
        state.pendingShortcut.reconciliationPending = true;
        state.outcome = { text: text || uiText('No receipt yet. Retry the same request to check its result.'), receipt: '', helpAction: '' };
        page('saved'); return;
      }
      if (state.draft && !state.confirmed) { addNotice(text, event.status); return; }
      state.pendingShortcut = null;
      const receipt = event.result?.item_id || event.result?.request_id || event.result?.escalation_id;
      const itemId = typeof receipt === 'string' && /^[A-Za-z0-9_-]{1,200}$/.test(receipt) &&
        !['pending','error','unavailable','cancelled'].includes(event.status) ? receipt : '';
      const previousStatus = itemId && itemId === state.outcome?.itemId ? state.outcome : null;
      if (watchedReceipt !== itemId) unwatchReceipt();
      state.outcome = { text: text || uiText('No receipt returned. Ask staff to check.'), receipt: typeof receipt === 'string' ? uiText('Receipt: {id}', { id: receipt }) : '', itemId, staffStatus:previousStatus?.staffStatus || 'open', statusVersion:previousStatus?.statusVersion ?? -1, statusUnavailable:previousStatus?.statusUnavailable || false, helpAction: event.result?.help_action === 'operator' ? 'operator' : '' };
      state.draft = null;
      state.confirmed = false;
      state.operation = null;
      page('saved'); return;
    }
    if (type !== 'reception_event') return;
    const phase = event.phase;
    if (terminal) {
      const error = phase === 'error' ? String(event.text || event.message || uiText("Please ask reception to check the connection and device setup.")) : '';
      detachGuest(true);
      state.error = error;
      if (!finishNavigation()) page('active');
      if (error) { state.notices.push({ status: 'error', text: error }); renderNotices(); }
      if (phase === 'timeout') toast(uiText("Conversation ended after inactivity."));
      return;
    }
    if (phase === 'ending') {
      settleTranslation();
      state.closed = true;
      state.micLevel = 0;
      state.draft = null;
      state.operation = null;
      state.phase = 'ending';
      if (state.audio === 'recording' || state.audio === 'muted') state.audio = 'stopping';
      page('active'); return;
    }
    if (phase === 'confirming') {
      if (typeof event.draft_id !== 'string' || !event.draft_id || state.draft?.draft_id === event.draft_id) return;
      state.draft = { draft_id: event.draft_id, guest_name: String(event.guest_name || ''), room_reference: String(event.room_reference || ''), message: String(event.message || '') };
      state.pendingShortcut = null;
      state.confirmed = false;
      if (state.operation) state.operation.pending = false;
      page('confirming'); return;
    }
    if (!['connecting','ready','greeting','listening','thinking','speaking'].includes(phase)) return;
    state.phase = phase;
    if (state.mode === 'translation' && state.page === 'active') { syncMic(); return; }
    // Captions and provider phases must not displace a guest's review or typed form.
    if (state.page === 'confirming' && state.draft?.draft_id.startsWith('voice:') && state.confirmed && phase === 'listening') {
      state.draft = null; state.confirmed = false; state.page = 'active';
    }
    if (['confirming','saved','operation_form'].includes(state.page)) return;
    page('active');
    if (['ready','listening'].includes(phase) && queuedShortcut) {
      const request = queuedShortcut;
      queuedShortcut = '';
      if (request === 'message') perform('message');
      else chooseShortcut(request);
    }
  }
  window.manzania = Object.freeze({ receive });
  document.addEventListener('keydown', event => {
    if (event.key === 'Escape') { event.preventDefault(); hardware('back'); return; }
    if (event.target.matches('input,textarea')) return;
    if (/^[0-9]$/.test(event.key)) { event.preventDefault(); hardware(event.key); }
  });
  document.addEventListener('pointerdown', activity, { passive: true });
  document.addEventListener('wheel', activity, { passive: true });
  function hideGuest() {
    unwatchReceipt();
    silence();
    if (state.active) send('stop');
    detachGuest();
    state.error = '';
    page('idle');
  }
  document.addEventListener('visibilitychange', () => { if (document.hidden) hideGuest(); });
  window.addEventListener('pagehide', hideGuest);
  function updateClock() {
    const now = new Date();
    document.getElementById('date').textContent = new Intl.DateTimeFormat(state.language,{ weekday:'short',day:'numeric',month:'short' }).format(now);
    document.getElementById('time').textContent = new Intl.DateTimeFormat(state.language,{ hour:'2-digit',minute:'2-digit' }).format(now);
  }
  updateClock();
  setInterval(updateClock,60000);
  render();
  send('info');
})();
