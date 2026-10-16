/* Device UI state machine. All provider credentials and microphone I/O stay native. */
(() => {
  'use strict';
  const native = !!window.ManzaniaNative;
  const content = document.getElementById('content');
  const languages = { es:{name:'Spanish',native:'Español'},en:{name:'English',native:'English'},ca:{name:'Catalan',native:'Català'},fr:{name:'French',native:'Français'},de:{name:'German',native:'Deutsch'},it:{name:'Italian',native:'Italiano'},pt:{name:'Portuguese',native:'Português'},nl:{name:'Dutch',native:'Nederlands'},ar:{name:'Arabic',native:'العربية'},zh:{name:'Chinese',native:'中文'},ru:{name:'Russian',native:'Русский'},uk:{name:'Ukrainian',native:'Українська'},pl:{name:'Polish',native:'Polski'},ro:{name:'Romanian',native:'Română'},sv:{name:'Swedish',native:'Svenska'},el:{name:'Greek',native:'Ελληνικά'},tr:{name:'Turkish',native:'Türkçe'},ja:{name:'Japanese',native:'日本語'},ko:{name:'Korean',native:'한국어'},hi:{name:'Hindi',native:'हिन्दी'},cs:{name:'Czech',native:'Čeština'},da:{name:'Danish',native:'Dansk'},fi:{name:'Finnish',native:'Suomi'},hu:{name:'Hungarian',native:'Magyar'},bg:{name:'Bulgarian',native:'Български'},hr:{name:'Croatian',native:'Hrvatski'},sk:{name:'Slovak',native:'Slovenčina'},sr:{name:'Serbian',native:'Српски'},no:{name:'Norwegian',native:'Norsk'},id:{name:'Indonesian',native:'Bahasa Indonesia'},th:{name:'Thai',native:'ไทย'},vi:{name:'Vietnamese',native:'Tiếng Việt'},ms:{name:'Malay',native:'Bahasa Melayu'},tl:{name:'Tagalog',native:'Tagalog'},he:{name:'Hebrew',native:'עברית'},fa:{name:'Persian',native:'فارسی'},ur:{name:'Urdu',native:'اردو'},bn:{name:'Bengali',native:'বাংলা'},ta:{name:'Tamil',native:'தமிழ்'},te:{name:'Telugu',native:'తెలుగు'},kn:{name:'Kannada',native:'ಕನ್ನಡ'},mr:{name:'Marathi',native:'मराठी'},gu:{name:'Gujarati',native:'ગુજરાતી'},pa:{name:'Punjabi',native:'ਪੰਜਾਬੀ'},ne:{name:'Nepali',native:'नेपाली'},kk:{name:'Kazakh',native:'Қазақша'},ka:{name:'Georgian',native:'ქართული'},hy:{name:'Armenian',native:'Հայերեն'},et:{name:'Estonian',native:'Eesti'},lv:{name:'Latvian',native:'Latviešu'},lt:{name:'Lithuanian',native:'Lietuvių'},af:{name:'Afrikaans',native:'Afrikaans'} };
  // DeskLanguages.kt order; the hardware keypad covers the first ten entries.
  const translationLanguages = languages;
  let languageSide = 'language';
  let appearance = 'dark';
  const themeIcon = () => appearance === 'light'
    ? '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" aria-hidden="true"><path d="M20 15.7A8.5 8.5 0 0 1 8.3 4 8.5 8.5 0 1 0 20 15.7Z"/></svg>'
    : '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" aria-hidden="true"><circle cx="12" cy="12" r="4"/><path d="M12 2v2m0 16v2M2 12h2m16 0h2M5 5l1.5 1.5m11 11L19 19M5 19l1.5-1.5m11-11L19 5"/></svg>';
  const copy = {
    en: { welcome: 'How can I help?', reception: 'Hotel assistance', ready: 'Ready when you are.', start: 'Start conversation', listen: 'Listening', speaking: 'Speaking', connect: 'Connecting', end: 'End conversation', translate: 'Live translation', quiet: 'The microphone stays off until you start.', draft: 'Save this message?', save: 'Save message', cancel: 'Cancel', saved: 'Saved for reception', local: 'Saved on this computer. Not sent to a staff member.', message: 'Leave a message', operator: 'Contact reception', noOperator: 'An operator connection is not set up. Please use the hotel’s posted contact number.', request: 'Ask about your stay, arrival instructions, or a message for reception.', begin: 'Speak. We translate both ways.' },
    es: { welcome: '¿En qué puedo ayudarte?', reception: 'Asistencia del hotel', ready: 'Todo listo.', start: 'Iniciar conversación', listen: 'Escuchando', speaking: 'Hablando', connect: 'Conectando', end: 'Finalizar', translate: 'Traducción en directo', quiet: 'El micrófono permanece apagado hasta que comiences.', draft: '¿Guardar este mensaje?', save: 'Guardar mensaje', cancel: 'Cancelar', saved: 'Guardado para recepción', local: 'Guardado en este ordenador. No enviado al personal.', message: 'Dejar un mensaje', operator: 'Contactar con recepción', noOperator: 'No hay conexión con un operador. Usa el número de contacto indicado por el hotel.', request: 'Pregunta sobre tu estancia, la llegada o deja un mensaje para recepción.', begin: 'Habla con naturalidad y espera tu turno.' },
    ja: { welcome: 'お手伝いします', reception: 'ホテルのご案内', ready: '準備ができました', start: '会話を開始', listen: 'お話を伺っています', speaking: '話しています', connect: '接続中', end: '会話を終了', translate: 'リアルタイム通訳', quiet: '開始するまでマイクはオフです。', draft: 'このメッセージを保存しますか？', save: '保存する', cancel: 'キャンセル', saved: '受付へのメッセージを保存しました', local: 'このパソコンに保存しました。スタッフにはまだ送信されていません。', message: 'メッセージを残す', operator: '受付に連絡', noOperator: 'オペレーターへの接続は未設定です。ホテルに掲示された連絡先をご利用ください。', request: 'ご滞在や到着についてのご質問、または受付へのメッセージをどうぞ。', begin: '受付の方と交互にお話しください。' }
  };
  if (native) {
    copy.en.local = 'Saved on this device. Not sent to a staff member.';
    copy.es.local = 'Guardado en este dispositivo. No enviado al personal.';
    copy.ja.local = 'この端末に保存しました。スタッフにはまだ送信されていません。';
  }
  Object.assign(copy, {
    zh: { ...copy.en, welcome:'有什么可以帮您？',ready:'准备好了',start:'开始对话',listen:'正在聆听',speaking:'正在说话',connect:'正在连接',end:'结束对话',draft:'保存这条留言？',save:'保存留言',cancel:'取消',message:'留下留言',operator:'联系前台' },
    fr: { ...copy.en, welcome:'Comment puis-je vous aider ?',ready:'À votre écoute.',start:'Commencer',listen:'Je vous écoute',speaking:'Je vous réponds',connect:'Connexion',end:'Terminer',draft:'Enregistrer ce message ?',save:'Enregistrer',cancel:'Annuler',message:'Laisser un message',operator:'Contacter la réception' },
    de: { ...copy.en, welcome:'Wie kann ich helfen?',ready:'Bereit für Sie.',start:'Gespräch starten',listen:'Ich höre zu',speaking:'Ich antworte',connect:'Verbinden',end:'Beenden',draft:'Nachricht speichern?',save:'Speichern',cancel:'Abbrechen',message:'Nachricht hinterlassen',operator:'Rezeption kontaktieren' },
    it: { ...copy.en, welcome:'Come posso aiutarti?',ready:'Sono qui per te.',start:'Inizia',listen:'Ti ascolto',speaking:'Sto rispondendo',connect:'Connessione',end:'Termina',draft:'Salvare il messaggio?',save:'Salva',cancel:'Annulla',message:'Lascia un messaggio',operator:'Contatta la reception' },
    pt: { ...copy.en, welcome:'Como posso ajudar?',ready:'Tudo pronto.',start:'Começar',listen:'Estou a ouvir',speaking:'A responder',connect:'A ligar',end:'Terminar',draft:'Guardar a mensagem?',save:'Guardar',cancel:'Cancelar',message:'Deixar mensagem',operator:'Contactar a receção' }
  });
  const confirmationCopy = {
    en: { prepared:'Your message is ready.',notSent:'Not saved or sent.',continue:'Continue',saving:'Saving…',sampleMessage:'Please leave fresh towels in the morning.' },
    es: { prepared:'Tu mensaje está listo.',notSent:'No se ha guardado ni enviado.',continue:'Continuar',saving:'Guardando…',sampleMessage:'Por favor, dejen toallas limpias por la mañana.' },
    ja: { prepared:'メッセージの準備ができました',notSent:'保存・送信はされていません。',continue:'続ける',saving:'保存中…',sampleMessage:'朝に新しいタオルを用意してください。' },
    zh: { prepared:'留言已准备好',notSent:'尚未保存或发送。',continue:'继续',saving:'正在保存…',sampleMessage:'请在早上提供干净的毛巾。' },
    fr: { prepared:'Votre message est prêt.',notSent:'Il n’a été ni enregistré ni envoyé.',continue:'Continuer',saving:'Enregistrement…',sampleMessage:'Merci de déposer des serviettes propres le matin.' },
    de: { prepared:'Ihre Nachricht ist bereit.',notSent:'Nicht gespeichert oder gesendet.',continue:'Weiter',saving:'Wird gespeichert…',sampleMessage:'Bitte legen Sie morgen früh frische Handtücher bereit.' },
    it: { prepared:'Il messaggio è pronto.',notSent:'Non è stato salvato né inviato.',continue:'Continua',saving:'Salvataggio…',sampleMessage:'Per favore, lasciate degli asciugamani puliti al mattino.' },
    pt: { prepared:'A sua mensagem está pronta.',notSent:'Não foi guardada nem enviada.',continue:'Continuar',saving:'A guardar…',sampleMessage:'Por favor, deixem toalhas limpas de manhã.' }
  };
  Object.entries(confirmationCopy).forEach(([language,words]) => Object.assign(copy[language],words));
  const conciergeCopy = {
    en: ['Check in','Talk to a person','Get a taxi','Ask anything'],
    es: ['Llegada al hotel','Hablar con alguien','Pedir un taxi','Hacer una pregunta'],
    ja: ['チェックイン','スタッフと話す','タクシーを呼ぶ','質問する'],
    zh: ['办理入住','联系工作人员','叫出租车','咨询问题'],
    fr: ['Arrivée à l’hôtel','Parler à quelqu’un','Demander un taxi','Poser une question'],
    de: ['Einchecken','Mit jemandem sprechen','Taxi bestellen','Eine Frage stellen'],
    it: ['Check-in','Parla con una persona','Chiedi un taxi','Fai una domanda'],
    pt: ['Fazer check-in','Falar com uma pessoa','Pedir um táxi','Fazer uma pergunta']
  };
  const shortcuts = ['checkin', 'operator', 'taxi', 'question'];
  const concierge = () => conciergeCopy[state.language] || conciergeCopy.en;
  const greet = (language) => (window.MANZANILLA_GREET && window.MANZANILLA_GREET[language]) || '';
  const state = { page: 'idle', mode: 'translation', night: false, language: 'en', staff_language: 'es', demo: !native, connected: false, audio: 'off', active: false, phase: '', sessionId: '', generation: null, closed: true, terminated: true, error: '', metrics: '', utterances: new Map(), draft: null, confirmed: false, operation: null, outcome: null, notices: [], hotel: '', pendingPage: '', micLevel: 0 };
  let queuedShortcut = '';
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
    mic: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" aria-hidden="true"><rect x="9" y="2" width="6" height="12" rx="3"/><path d="M5 10v2a7 7 0 0 0 14 0v-2M12 19v3m-4 0h8"/></svg>'
  };
  const esc = (value) => String(value ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
  const t = () => copy[state.language] || copy.en;
  const langName = (language) => translationLanguages[language]?.native || languages[language]?.native || language;
  const languagePicker = (side, label) => `<section class="language-side" data-keypad="${languageSide === side}"><label for="${side}">${label}</label><select id="${side}" data-language-side="${side}" lang="${state[side]}" dir="${languageDir(state[side])}" aria-describedby="${side}-hint" ${state.audio !== 'off' ? 'disabled' : ''}>${Object.entries(translationLanguages).map(([id,item]) => `<option value="${id}" lang="${id}" dir="${languageDir(id)}" ${state[side] === id ? 'selected' : ''}>${esc(item.native)}</option>`).join('')}</select><small id="${side}-hint">${side === 'language' ? 'The language you speak' : 'The language reception speaks'}</small></section>`;
  const send = (action, detail = {}) => {
    if (!native) return;
    const scope = state.sessionId && !['start','info','languages','appearance','night','settings','apply_update','check_updates'].includes(action)
      ? { session_id: state.sessionId, ...(state.generation === null ? {} : { generation: state.generation }) } : {};
    window.ManzaniaNative.postMessage(JSON.stringify({ action, ...scope, ...detail }));
  };
  const action = (name, label, className = 'primary', disabled = false) => `<button type="button" class="${className}" data-action="${name}" ${disabled ? 'disabled' : ''}>${label}</button>`;
  const fine = (text) => `<p class="fine">${esc(text)}</p>`;

  const languageDir = language => !language ? 'auto' : /^(ar|he|fa|ur)(-|$)/.test(language) ? 'rtl' : 'ltr';
  const captionLabel = entry => entry.kind === 'app_greeting' ? 'Welcome' : entry.side === 'assistant' ? state.mode === 'translation' ? 'Translation · unlinked' : 'Manzanilla · unlinked' : entry.side === 'guest' ? 'Guest' : 'Receptionist';
  const captionLanguage = language => language ? langName(language) : 'Language not identified';
  const caption = entry => {
    const welcome = entry.kind === 'app_greeting';
    const parts = [];
    // The part's language is already shown, so the field label would repeat it.
    if (entry.original) parts.push({ language: entry.from, text: entry.original, field: 'original', label: '' });
    if (entry.text) parts.push({ language: entry.to, text: entry.text, field: 'text', label: '' });
    // Reorder only an explicitly identified language pair, never unrelated feeds.
    const paired = parts.length === 2 && state.language !== state.staff_language && ((entry.from === state.language && entry.to === state.staff_language) || (entry.from === state.staff_language && entry.to === state.language));
    if (paired && parts[0].language === state.staff_language) parts.reverse();
    return `<article id="${esc(entry.id)}" class="caption caption-${entry.side === 'guest' ? 'guest' : 'reception'}" data-source="${welcome ? 'app' : 'provider'}"${welcome ? ' data-kind="app_greeting"' : ''}><span class="caption-side">${captionLabel(entry)}</span>${parts.map((part,index) => `<div class="caption-part${index ? ' caption-divider' : ''}" data-field="${part.field}"><span class="caption-language">${esc(captionLanguage(part.language))}${welcome || !part.label ? '' : ` · ${part.label}`}</span><p class="caption-${part.field === 'original' ? 'original' : 'text'}" lang="${esc(part.language || 'und')}" dir="${languageDir(part.language)}">${esc(part.text)}</p></div>`).join('')}</article>`;
  };
  const conversation = () => state.utterances.size ? [...state.utterances.values()].map(caption).join('') : `<div class="empty-transcript">${esc(state.mode === 'translation' ? t().begin : t().welcome)}</div>`;
  const operationDraft = () => /^(operation|voice):/.test(state.draft?.draft_id || '');
  const phaseLabel = () => ({ connecting: t().connect, greeting: 'Welcome', thinking: 'Translating', speaking: t().speaking, listening: 'Live', ending: 'Stopping', stopped: 'Stopped' })[state.phase] || t().reception;
  const sessionAudioText = () => ({ off:'Mic off', recording:'Mic on', stopping:'Releasing mic', unknown:'Mic state unknown · ask staff' })[state.audio];
  const sessionDock = () => {
    const stopped = state.closed || state.terminated;
    const restart = state.terminated && !state.active && state.audio === 'off';
    return `<div class="session-actions" data-stopped="${stopped}"><div class="session-control"><div class="session-icon" role="meter" aria-label="Microphone input level" aria-valuemin="0" aria-valuemax="100" aria-valuenow="${Math.round(state.micLevel * 100)}" style="--level:${state.micLevel}">${icons.mic}</div><div class="session-control-copy"><strong>${esc(stopped ? state.phase === 'ending' ? 'Stopping…' : 'Stopped' : phaseLabel())}</strong><span>${esc(sessionAudioText())}</span></div></div>${state.mode === 'reception' && !stopped ? action('message', esc(t().message), 'secondary', ['connecting','greeting'].includes(state.phase)) : ''}${action(restart ? 'restart' : 'stop', restart ? 'Restart' : stopped ? 'Releasing…' : 'Stop', 'stop', stopped && !restart)}</div>`;
  };
  function renderNotices() {
    const target = document.getElementById('notices');
    target.hidden = !state.notices.length;
    target.innerHTML = state.notices.map((notice, index) => `<div class="notice${['error','failed','unavailable','denied'].includes(notice.status) ? ' error' : ''}"><span><strong>Service update</strong> ${esc(notice.text)}</span><button data-dismiss="${index}" aria-label="Dismiss service update">×</button></div>`).join('');
  }
  function addNotice(text, status = '') {
    if (!text) return;
    state.notices.push({ text: String(text), status: String(status) });
    renderNotices();
  }

  function render() {
    document.documentElement.dataset.appearance = appearance;
    document.documentElement.lang = ['idle','languages','translation_languages'].includes(state.page) ? 'en' : state.language;
    document.documentElement.dataset.night = String(state.night);
    document.documentElement.dataset.page = state.page;
    document.documentElement.dataset.captureActive = String(state.active);
    const nightToggle = document.getElementById('night-toggle');
    nightToggle.textContent = state.night ? 'Night reception on' : 'Night reception';
    nightToggle.ariaPressed = String(state.night);
    nightToggle.hidden = state.page !== 'idle';
    document.getElementById('settings').hidden = !native || !['idle','error'].includes(state.page);
    document.getElementById('check-updates').hidden = !native || state.page !== 'idle';
    content.dataset.page = state.page;
    document.getElementById('mode-title').textContent = 'Reception';
    document.getElementById('connection-text').textContent = state.connected ? 'Voice configured' : 'Voice setup needed';
    document.getElementById('hotel-label').textContent = state.hotel || 'Hotel reception';
    document.getElementById('privacy').innerHTML = `<span class="mic-dot${state.audio === 'recording' ? ' mic-live' : ''}"></span>${esc(sessionAudioText())}`;
    document.getElementById('hardware-hint').textContent = state.active ? 'Green or red to stop' : state.page === 'translation_languages' ? 'Digits: first ten · Green: start · Red: back' : 'Green: translation';
    const homeAction = (name, icon, label, note = '') => `<button class="home-action${name === 'translation' ? ' home-action-main' : ''}" data-action="${name}">${name === 'translation' ? '<span class="orb">' + icons.arrow + '</span>' : icon}<span>${label}</span>${note ? '<small>' + note + '</small>' : ''}</button>`;
    const html = {
      idle: () => `<div class="welcome"><h1>Welcome.</h1><p>Speak. We’ll translate.</p></div><nav class="home-actions" aria-label="Reception services">${homeAction('translation',icons.translate,'Real-Time Translation','Talk to each other')}${homeAction('reception',icons.bell,'Hotel assistance')}${homeAction('request:checkin',icons.bed,'Check in')}${homeAction('request:taxi',icons.car,'Get a taxi')}${homeAction('request:message',icons.message,'Leave a message')}</nav><div class="appearance-row"><button class="appearance-toggle" data-action="appearance" aria-label="Switch to ${appearance === 'dark' ? 'brighter' : 'dimmed'} appearance" title="Appearance · keypad 1">${themeIcon()}</button></div>`,
      languages: () => `<div class="status-line"><div><h2 class="language-heading">Hotel assistance</h2><p class="keypad-hint">Choose your language to speak with Manzanilla.</p></div><button class="back" data-action="back">${icons.back}Back</button></div><div class="language-list">${Object.entries(languages).map(([id,item],i) => action('language:' + id, `<span class="key">${i < 10 ? i === 9 ? 0 : i + 1 : ''}</span><strong lang="${id}" dir="${languageDir(id)}">${esc(item.native)}</strong>`, 'choice language', state.audio !== 'off')).join('')}</div>${state.audio !== 'off' ? fine('Audio release must be confirmed before starting again.') : ''}`,
      translation_languages: () => `<div class="status-line"><div><h2 class="language-heading">Choose languages</h2><p class="keypad-hint">Speak naturally. We’ll translate both ways.</p></div><button class="back" data-action="back">${icons.back}Back</button></div><div class="language-pair">${languagePicker('language','Guest')}${languagePicker('staff_language','Receptionist')}</div><p id="setup-feedback" class="setup-feedback${sameLanguage() ? ' error' : ''}" role="status">${sameLanguage() ? 'Choose two different languages.' : state.audio !== 'off' ? 'Audio release must be confirmed before starting again.' : 'The microphone stays off until you start.'}</p>${action('start','Start translation','primary setup-start',sameLanguage() || state.audio !== 'off')}`,
      active: () => `<div class="status-line session-top"><div><h2>${state.mode === 'translation' ? 'Translation' : 'Hotel assistance'}</h2><p>${esc(state.mode === 'translation' ? langName(state.language) + ' ↔ ' + langName(state.staff_language) : phaseLabel())}</p></div><button class="back" data-action="back">${icons.back}Back</button></div>${state.error ? `<div class="notice error">${esc(state.error)}</div>` : ''}<div class="session-scroll">${state.mode === 'reception' && !state.closed ? `<div class="shortcut-list">${shortcuts.map((id,i) => action(id, `<span class="key">${i + 1}</span><strong>${esc(concierge()[i])}</strong>`, 'choice shortcut', ['connecting','greeting'].includes(state.phase))).join('')}</div>` : ''}<div class="conversation" role="log" aria-label="Conversation transcript" aria-live="polite">${conversation()}</div>${state.metrics ? `<p class="metrics">${esc(state.metrics)}</p>` : ''}</div>${sessionDock()}`,
      operation_form: () => `<div class="status-line"><h2>${esc(concierge()[shortcuts.indexOf(state.operation?.action)] || 'Hotel request')}</h2><button class="back" data-action="back">${icons.back}Back</button></div><form id="operation-form" class="operation-form">${(state.operation?.fields || []).map((field,index) => `<label for="operation-field-${index}">${esc(field.label)}${field.required ? ' *' : ''}<input id="operation-field-${index}" name="${esc(field.name)}" value="${esc(state.operation.values[field.name] || '')}" ${field.required ? 'required' : ''} autocomplete="off" dir="auto"></label>`).join('')}<div class="button-row"><button type="submit" class="primary">Review request</button>${action('cancel-operation',esc(t().cancel),'secondary')}</div></form>${action('stop',esc(t().end),'stop')}`,
      confirming: () => `<div class="status-line"><h2>${esc(operationDraft() ? 'Confirm hotel request?' : t().draft)}</h2><button class="back" data-action="back">${icons.back}Back</button></div><div class="draft">${state.draft?.guest_name || state.draft?.room_reference ? `<div class="draft-meta">${esc([state.draft?.guest_name,state.draft?.room_reference].filter(Boolean).join(' · '))}</div>` : ''}${esc(state.draft?.message || '')}</div>${operationDraft() ? fine('Only confirm if these request details are correct.') : fine('Saved only on this tablet after you confirm. Not sent to staff.')}<div class="button-row">${action('confirm',esc(state.confirmed ? 'Submitting…' : operationDraft() ? 'Confirm request' : t().save),'primary',state.confirmed)}${action('cancel-draft',esc(t().cancel),'secondary',state.confirmed)}</div>`,
      saved: () => `<h2>${esc(state.outcome ? 'Hotel request update' : t().saved)}</h2><p class="lead" dir="auto">${esc(state.outcome ? state.outcome.text : t().local)}</p>${state.outcome?.receipt ? `<div class="receipt" dir="auto">${esc(state.outcome.receipt)}</div>` : ''}<div class="button-row">${action('continue',esc(t().continue))}${action('stop',esc(t().end),'secondary')}</div>`,
      error: () => `<div class="status-line"><h2>Conversation unavailable</h2><button class="back" data-action="back">${icons.back}Back</button></div><div class="notice error">${esc(state.error || 'Please ask reception for help.')}</div>${fine(state.audio === 'off' ? 'Microphone off. Staff can check this device’s setup.' : 'Audio release is not confirmed. Ask reception for help before restarting.')}<div class="button-row">${action('retry','Try again','primary',state.audio !== 'off')}${action('home','Back to home','secondary')}</div>`
    };
    const focusedElement = document.activeElement;
    const focusedAction = focusedElement?.dataset?.action;
    const focusedSelect = focusedElement?.dataset?.languageSide;
    const focusedInput = focusedElement?.closest('#operation-form') ? { name:focusedElement.name,start:focusedElement.selectionStart,end:focusedElement.selectionEnd } : null;
    const scrollTop = content.scrollTop;
    const scrolls = [...content.querySelectorAll('.session-scroll,.draft,.language-list')].map(element => element.scrollTop);
    content.innerHTML = (html[state.page] || html.idle)();
    content.scrollTop = scrollTop;
    [...content.querySelectorAll('.session-scroll,.draft,.language-list')].forEach((element,index) => { element.scrollTop = scrolls[index] || 0; });
    if (focusedAction) [...content.querySelectorAll('[data-action]')].find(button => button.dataset.action === focusedAction)?.focus({ preventScroll:true });
    if (focusedSelect) content.querySelector(`[data-language-side="${focusedSelect}"]`)?.focus({ preventScroll:true });
    if (focusedInput) {
      const input = [...content.querySelectorAll('#operation-form input')].find(element => element.name === focusedInput.name);
      input?.focus({ preventScroll:true });
      if (input && focusedInput.start !== null) input.setSelectionRange(focusedInput.start,focusedInput.end);
    }
    renderNotices();
  }

  function activity() { send('activity'); }
  function toggleAppearance() {
    if (state.page !== 'idle') return;
    appearance = appearance === 'light' ? 'dark' : 'light';
    render();
    send('appearance', { value:appearance });
  }
  function sameLanguage() { return state.mode === 'translation' && state.language === state.staff_language; }
  function page(next) { state.page = next; render(); }
  function toggleNight() {
    if (state.page !== 'idle' || state.active) return;
    activity(); state.night = !state.night; state.mode = state.night ? 'reception' : 'translation';
    page('idle'); send('night', { enabled: state.night });
  }
  function chooseMode(mode) {
    if (state.active || state.audio !== 'off') return;
    queuedShortcut = '';
    state.mode = mode;
    state.error = '';
    silence();
    page(mode === 'translation' ? 'translation_languages' : 'languages');
  }
  function clearGuest() {
    state.utterances.clear();
    state.draft = null;
    state.confirmed = false;
    state.operation = null;
    state.outcome = null;
    state.metrics = '';
    state.notices = [];
    document.getElementById('toast').hidden = true;
    renderNotices();
  }
  function selectLanguage(side, language) {
    state[side] = language;
    selected[side] = true;
    send('languages', { language: state.language, staff_language: state.staff_language });
  }
  function start(restart = false) {
    if (state.active || state.phase === 'ending' || state.audio !== 'off') return;
    if (sameLanguage()) { toast('Choose a different guest language for translation.'); return; }
    if (!native) { state.error = 'Voice conversations require the reception tablet. No microphone has been opened.'; page('error'); return; }
    silence();
    if (!restart) clearGuest();
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
    send('start', { session_id: state.sessionId, mode: state.mode, language: state.language, staff_language: state.staff_language, greet: greet(state.language) });
  }
  function silence() {
    window.speechSynthesis?.cancel();
    send('silence');
    state.micLevel = 0;
  }
  function chooseShortcut(name) {
    if (!state.active || state.closed || state.mode !== 'reception' || state.page !== 'active' || ['connecting','greeting'].includes(state.phase)) return;
    if (state.demo) { addNotice('Hotel requests require a connected reception tablet.', 'unavailable'); return; }
    send(name);
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
    if (next) state.pendingPage = next;
    if (!state.active) { finishNavigation(); return; }
    if (state.phase === 'ending') return;
    silence();
    state.closed = true;
    state.draft = null;
    state.confirmed = false;
    state.operation = null;
    state.outcome = null;
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
    if (name.startsWith('request:')) {
      const request = name.split(':')[1];
      if (state.page !== 'idle' || !['checkin','taxi','message'].includes(request)) return;
      chooseMode('reception');
      if (state.page === 'languages') queuedShortcut = request;
      return;
    }
    if (name.startsWith('pair:')) {
      const [,side,language] = name.split(':');
      if (state.page !== 'translation_languages' || state.audio !== 'off' || !['language','staff_language'].includes(side) || !Object.hasOwn(translationLanguages,language)) return;
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
      case 'start': start(); break;
      case 'restart': if (state.terminated && state.audio === 'off') start(true); break;
      case 'stop': stop(); break;
      case 'back': goBack(); break;
      case 'home': if (state.active || state.audio !== 'off') stop('idle'); else { clearGuest(); page('idle'); } break;
      case 'retry': if(state.mode === 'reception') start(); else page('translation_languages'); break;
      case 'checkin': case 'operator': case 'taxi': case 'question': chooseShortcut(name); break;
      case 'message':
        if (!state.active || state.closed || state.page !== 'active') return;
        if (state.demo) addNotice('Message review and on-tablet saving require the reception tablet.', 'unavailable');
        else send('message');
        break;
      case 'cancel-operation': state.operation = null; page('active'); break;
      case 'confirm':
      case 'cancel-draft':
        if (!state.active || state.closed || !state.draft || state.confirmed) return;
        state.confirmed = true;
        send('confirm', { draft_id: state.draft.draft_id, approved: name === 'confirm' });
        if (name === 'cancel-draft') { state.draft = null; state.confirmed = false; page('active'); }
        else render();
        break;
      case 'continue':
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
  });
  content.addEventListener('input', event => {
    const input = event.target;
    if (state.operation && input.closest('#operation-form')) state.operation.values[input.name] = input.value;
    activity();
  });
  content.addEventListener('submit', event => {
    if (event.target.id !== 'operation-form') return;
    event.preventDefault();
    if (!state.active || state.closed || !state.operation || !event.target.reportValidity()) return;
    activity();
    const operation = state.operation;
    state.operation = null;
    send(operation.action, { arguments: { ...operation.values } });
    page('active');
  });
  document.getElementById('notices').addEventListener('click', event => {
    const button = event.target.closest('[data-dismiss]');
    if (!button) return;
    state.notices.splice(Number(button.dataset.dismiss), 1);
    activity();
    renderNotices();
  });
  document.getElementById('night-toggle').addEventListener('click', toggleNight);
  document.getElementById('settings').addEventListener('click',()=>send('settings'));
  document.getElementById('check-updates').addEventListener('click',()=>send('check_updates'));

  function hardware(key) {
    activity();
    if (state.page === 'translation_languages' && /^[0-9]$/.test(key)) { perform('pair:' + languageSide + ':' + Object.keys(translationLanguages)[key === '0' ? 9 : Number(key)-1]); return; }
    if (state.page === 'languages' && /^[0-9]$/.test(key)) { perform('language:' + Object.keys(languages)[key === '0' ? 9 : Number(key)-1]); return; }
    if (key === 'back' || key === 'red' || key === '0') { goBack(); return; }
    if (key === 'green') {
      if (state.active) stop();
      else if (state.page === 'idle') chooseMode('translation');
      else if (state.page === 'translation_languages') start();
      else if (state.page === 'active' && state.terminated) start(true);
      return;
    }
    if (state.page === 'idle' && key === '1') toggleAppearance();
    else if (state.page === 'idle' && key === '2') chooseMode('reception');
    else if (state.page === 'active' && state.mode === 'reception' && /^[1-4]$/.test(key)) chooseShortcut(shortcuts[Number(key)-1]);
  }

  function offerUpdate(version, text, phase, percent) {
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
      bar.innerHTML = `${esc(text || 'Downloading…')}<span class="track"><span class="fill" style="width:${known ? Math.min(100,pct) : 0}%"></span></span>`;
      return;
    }
    bar.disabled = false;
    if (phase === 'current') bar.textContent = text || 'Up to date. Tap to check again.';
    else if (phase === 'confirm') bar.textContent = text || 'Tap to open the installer. The file is already downloaded.';
    else if (phase === 'failed') bar.textContent = text || 'Update did not finish. Tap to try the installer again.';
    else bar.textContent = text || ('Update ' + version + ' is downloaded. Tap to install.');
  }
  function matchesSession(event) {
    if (!state.sessionId || event.session_id !== state.sessionId || !Number.isInteger(event.generation) || event.generation < 0) return false;
    if (state.generation === null) state.generation = event.generation;
    return event.generation === state.generation;
  }
  function detachGuest(preserveHistory = false) {
    state.closed = true;
    state.terminated = true;
    state.active = false;
    state.phase = preserveHistory ? 'stopped' : '';
    state.micLevel = 0;
    state.draft = null;
    state.confirmed = false;
    state.operation = null;
    state.outcome = null;
    queuedShortcut = '';
    if (state.audio !== 'off') state.audio = 'unknown';
    if (!preserveHistory) { clearGuest(); state.pendingPage = ''; }
  }
  function receive(event) {
    if (typeof event === 'string') { try { event = JSON.parse(event); } catch { return; } }
    if (!event || typeof event !== 'object') return;
    const type = event.type;
    if (type === 'navigation' && event.action === 'back') {
      if (state.active && !matchesSession(event)) return;
      goBack();
      return;
    }
    if (type === 'appearance') { appearance = event.value === 'dark' ? 'dark' : 'light'; render(); return; }
    if (type === 'visibility') {
      if (event.visible === false) { detachGuest(); state.error = ''; page('idle'); }
      return;
    }
    if (type === 'night_mode') {
      if (state.active) return;
      state.night = event.enabled === true;
      if (state.night) state.mode = 'reception';
      page('idle'); return;
    }
    if (type === 'connection') { state.connected = event.connected === true; render(); return; }
    if (type === 'reception_info') {
      state.hotel = String(event.hotel_name || '');
      if (!defaultReceived && !preferencesRestored && !selected.staff_language && Object.hasOwn(languages, event.staff_language)) state.staff_language = event.staff_language;
      defaultReceived = true;
      render(); return;
    }
    if (type === 'language_preferences') {
      for (const side of ['language','staff_language']) {
        if (!selected[side] && Object.hasOwn(languages, event[side])) state[side] = event[side];
      }
      preferencesRestored = true;
      render(); return;
    }
    if (type === 'update') { offerUpdate(String(event.version || '')); return; }
    if (type === 'update_status') { offerUpdate(String(event.version || ''), String(event.text || 'Updating…'), String(event.phase || ''), event.percent); return; }
    if (!matchesSession(event)) return;
    if (type === 'audio_state') {
      if (!['off','recording','stopping','unknown'].includes(event.state)) return;
      if (state.closed && !['off','unknown'].includes(event.state)) return;
      state.audio = event.state;
      if (state.audio !== 'recording') state.micLevel = 0;
      if (!finishNavigation()) render();
      return;
    }
    if (type === 'app_greeting') {
      if (event.state !== 'started' || !state.active || state.closed || state.terminated || event.language !== state.language || event.staff_language !== state.staff_language) return;
      const key = `${state.sessionId}:app:greeting`;
      const guestWelcome = greet(event.language);
      const staffWelcome = greet(event.staff_language);
      if (state.utterances.has(key) || !guestWelcome || !staffWelcome) return;
      state.utterances.set(key, { id: key, kind: 'app_greeting', side: 'app', from: event.language, to: event.staff_language, original: guestWelcome, text: staffWelcome });
      render();
      return;
    }
    if (state.terminated) return;
    const terminal = type === 'reception_event' && ['stopped','timeout','error'].includes(event.phase);
    if (state.closed && !terminal) return;
    if (type === 'mic_level') {
      const level = state.audio === 'recording' ? Math.min(1, Math.max(0, Number(event.level) || 0)) : 0;
      state.micLevel = level;
      const meter = content.querySelector('.session-icon');
      meter?.style.setProperty('--level', String(level));
      meter?.setAttribute('aria-valuenow', String(Math.round(level * 100)));
      return;
    }
    // Playback energy is not microphone input; never animate the mic from it.
    if (type === 'energy') return;
    if (type === 'notice') {
      addNotice(event.text, event.status);
      if (state.draft && ['error','failed','unavailable','denied','save_failed'].includes(event.status)) { state.confirmed = false; render(); }
      return;
    }
    if (type === 'metrics') { state.metrics = String(event.text || ''); if (state.page === 'active') render(); return; }
    if (type === 'caption') {
      if (typeof event.utterance_id !== 'string' || !event.utterance_id || !['guest','staff','assistant'].includes(event.side) || !['original','text'].includes(event.field) || !['append','replace'].includes(event.operation) || typeof event.text !== 'string' || typeof event.final !== 'boolean') return;
      const key = `${state.sessionId}:${event.side}:${event.utterance_id}`;
      let entry = state.utterances.get(key);
      if (!entry) {
        entry = { id: key, side: event.side, from: '', to: '', original: '', text: '', final: { original: false, text: false } };
        state.utterances.set(key, entry);
      }
      if (entry.final[event.field] && (!event.final || event.operation === 'append')) return;
      entry[event.field] = event.operation === 'replace' ? event.text : entry[event.field] + event.text;
      entry.final[event.field] = event.final;
      if (typeof event.from === 'string' && event.from) entry.from = event.from;
      if (typeof event.to === 'string' && event.to) entry.to = event.to;
      const transcript = content.querySelector('.conversation');
      const scroller = content.querySelector('.session-scroll');
      const follow = scroller && scroller.scrollHeight - scroller.clientHeight - scroller.scrollTop < 60;
      if (transcript) transcript.innerHTML = conversation();
      if (follow) scroller.scrollTop = scroller.scrollHeight;
      if (event.side !== 'assistant' && event.text.trim()) activity();
      return;
    }
    if (type === 'operation_form') {
      if (!shortcuts.includes(event.action) || !Array.isArray(event.fields) || state.draft) return;
      const fields = event.fields.filter(field => field && typeof field.name === 'string' && field.name && typeof field.label === 'string');
      if (!fields.length) { addNotice('This request did not provide usable fields. Please ask reception for help.', 'unavailable'); return; }
      state.operation = { action: event.action, fields, values: Object.fromEntries(fields.map(field => [field.name, ''])) };
      page('operation_form'); return;
    }
    if (type === 'operation_result') {
      const text = String(event.text || '');
      addNotice(text, event.status);
      if (['confirmation_required','needs_confirmation','fields_required'].includes(event.status)) return;
      if (state.draft && !state.confirmed) return;
      state.outcome = { text: text || 'No service receipt was returned. Ask reception to check the request.', receipt: typeof event.receipt === 'string' ? event.receipt : event.receipt ? JSON.stringify(event.receipt) : '' };
      state.draft = null;
      state.confirmed = false;
      state.operation = null;
      page('saved'); return;
    }
    if (type !== 'reception_event') return;
    const phase = event.phase;
    if (terminal) {
      const error = phase === 'error' ? String(event.text || event.message || 'Please ask reception to check the connection and device setup.') : '';
      detachGuest(true);
      state.error = error;
      if (!finishNavigation()) page('active');
      if (phase === 'timeout') toast('Conversation ended after inactivity.');
      return;
    }
    if (phase === 'ending') {
      state.closed = true;
      state.micLevel = 0;
      state.draft = null;
      state.operation = null;
      state.phase = 'ending';
      if (state.audio === 'recording') state.audio = 'stopping';
      page('active'); return;
    }
    if (phase === 'confirming') {
      if (typeof event.draft_id !== 'string' || !event.draft_id || state.draft?.draft_id === event.draft_id) return;
      state.draft = { draft_id: event.draft_id, guest_name: String(event.guest_name || ''), room_reference: String(event.room_reference || ''), message: String(event.message || '') };
      state.confirmed = false;
      state.operation = null;
      page('confirming'); return;
    }
    if (phase === 'saved') {
      if (event.saved !== true || !state.draft || operationDraft() || !state.confirmed) return;
      state.draft = null;
      state.confirmed = false;
      state.outcome = null;
      page('saved'); return;
    }
    if (!['connecting','greeting','listening','thinking','speaking'].includes(phase)) return;
    state.phase = phase;
    // Captions and provider phases must not displace a guest's review or typed form.
    if (state.page === 'confirming' && state.draft?.draft_id.startsWith('voice:') && state.confirmed && phase === 'listening') {
      state.draft = null; state.confirmed = false; state.page = 'active';
    }
    if (['confirming','saved','operation_form'].includes(state.page)) return;
    page('active');
    if (phase === 'listening' && queuedShortcut) {
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
    document.getElementById('date').textContent = new Intl.DateTimeFormat('en-GB',{ weekday:'short',day:'numeric',month:'short' }).format(now);
    document.getElementById('time').textContent = new Intl.DateTimeFormat('en-GB',{ hour:'2-digit',minute:'2-digit' }).format(now);
  }
  updateClock();
  setInterval(updateClock,60000);
  render();
  send('info');
})();
