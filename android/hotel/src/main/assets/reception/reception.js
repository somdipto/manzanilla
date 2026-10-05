/* Device UI state machine. All provider credentials and microphone I/O stay native. */
(() => {
  'use strict';
  const native = !!window.ManzaniaNative;
  const content = document.getElementById('content');
  const languages = { en: { name: 'English', native: 'English' }, es: { name: 'Spanish', native: 'Español' }, ja: { name: 'Japanese', native: '日本語' }, zh: { name: 'Chinese', native: '中文' }, fr: { name: 'French', native: 'Français' }, de: { name: 'German', native: 'Deutsch' }, it: { name: 'Italian', native: 'Italiano' }, pt: { name: 'Portuguese', native: 'Português' } };
  const copy = {
    en: { welcome: 'How can I help?', reception: 'Hotel assistance', ready: 'Ready when you are.', start: 'Start conversation', listen: 'Listening', speaking: 'Speaking', connect: 'Connecting', end: 'End conversation', translate: 'Live translation', quiet: 'The microphone stays off until you start.', draft: 'Save this message?', save: 'Save message', cancel: 'Cancel', saved: 'Saved for reception', local: 'Saved on this computer. Not sent to a staff member.', message: 'Leave a message', operator: 'Contact reception', noOperator: 'An operator connection is not set up. Please use the hotel’s posted contact number.', request: 'Ask about your stay, arrival instructions, or a message for reception.', begin: 'Speak naturally. Take turns with reception.' },
    es: { welcome: '¿En qué puedo ayudarte?', reception: 'Asistencia del hotel', ready: 'Todo listo.', start: 'Iniciar conversación', listen: 'Escuchando', speaking: 'Hablando', connect: 'Conectando', end: 'Finalizar', translate: 'Traducción en directo', quiet: 'El micrófono permanece apagado hasta que comiences.', draft: '¿Guardar este mensaje?', save: 'Guardar mensaje', cancel: 'Cancelar', saved: 'Guardado para recepción', local: 'Guardado en este ordenador. No enviado al personal.', message: 'Dejar un mensaje', operator: 'Contactar con recepción', noOperator: 'No hay conexión con un operador. Usa el número de contacto indicado por el hotel.', request: 'Pregunta sobre tu estancia, la llegada o deja un mensaje para recepción.', begin: 'Habla con naturalidad y espera tu turno.' },
    ja: { welcome: 'お手伝いします', reception: 'ホテルのご案内', ready: '準備ができました', start: '会話を開始', listen: 'お話を伺っています', speaking: '話しています', connect: '接続中', end: '会話を終了', translate: 'リアルタイム通訳', quiet: '開始するまでマイクはオフです。', draft: 'このメッセージを保存しますか？', save: '保存する', cancel: 'キャンセル', saved: '受付へのメッセージを保存しました', local: 'このパソコンに保存しました。スタッフにはまだ送信されていません。', message: 'メッセージを残す', operator: '受付に連絡', noOperator: 'オペレーターへの接続は未設定です。ホテルに掲示された連絡先をご利用ください。', request: 'ご滞在や到着についてのご質問、または受付へのメッセージをどうぞ。', begin: '受付の方と交互にお話しください。' }
  };
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
  // Fixed, local walkthrough speech. Live sessions use the provider's greeting instead.
  const conciergeCopy = {
    en: ['Check in','Talk to a person','Get a taxi','Ask anything','Welcome. How can I help?','Hi, welcome! I’m Manzanilla, your reception assistant. I can help with arrival information, finding someone to speak to, taxi information, or questions about your stay. What would you like help with?', 'I can guide you through arrival. Check-in and room access need the hotel’s approved instructions.', 'Calling a person is not connected yet. Please use the contact number on your booking.', 'Taxi booking is not connected yet. Reception can help you arrange a ride.', 'Ask about your stay, restaurants or local information. Live answers will be available when the voice service is connected.'],
    es: ['Llegada al hotel','Hablar con alguien','Pedir un taxi','Hacer una pregunta','Bienvenido. ¿En qué puedo ayudarte?','¡Hola, bienvenido! Soy Manzanilla, tu asistente de recepción. Puedo orientarte sobre tu llegada, cómo contactar con alguien, taxis y preguntas sobre tu estancia. ¿En qué puedo ayudarte?', 'Puedo orientarte sobre tu llegada. El registro y el acceso a la habitación requieren las instrucciones aprobadas del hotel.', 'Las llamadas aún no están conectadas. Usa el teléfono de contacto de tu reserva.', 'La reserva de taxis aún no está conectada. Recepción puede ayudarte a organizar el viaje.', 'Pregunta sobre tu estancia, restaurantes o información local. Las respuestas en directo estarán disponibles al conectar el servicio de voz.'],
    ja: ['チェックイン','スタッフと話す','タクシーを呼ぶ','質問する','ようこそ。お手伝いします。','こんにちは、ようこそ！受付アシスタントのManzanillaです。ご到着のご案内、スタッフへの連絡方法、タクシーの情報、ご滞在についてのご質問をお手伝いします。どのようなご用件でしょうか？', 'ご到着についてご案内します。チェックインやお部屋への入室には、ホテルが承認した手順が必要です。', 'スタッフへの電話はまだ接続されていません。ご予約に記載された連絡先をご利用ください。', 'タクシーの予約はまだ接続されていません。手配については受付にお問い合わせください。', 'ご滞在やレストラン、周辺情報についてご質問ください。音声サービスの接続後に回答できます。'],
    zh: ['办理入住','联系工作人员','叫出租车','咨询问题','欢迎，有什么可以帮您？','您好，欢迎！我是前台助手 Manzanilla。我可以提供抵达指引、联系工作人员的方法、出租车信息，以及住宿相关信息。有什么可以帮您？', '我可以提供抵达指引。办理入住和进入客房需要酒店批准的指引。', '人工通话尚未连接。请使用预订信息中的联系电话。', '出租车预订尚未连接。请联系前台协助安排。', '您可以咨询住宿、餐厅或周边信息。连接语音服务后即可获得实时回答。'],
    fr: ['Arrivée à l’hôtel','Parler à quelqu’un','Demander un taxi','Poser une question','Bienvenue. Comment vous aider ?','Bonjour, bienvenue ! Je suis Manzanilla, votre assistante de réception. Je peux vous renseigner sur votre arrivée, les contacts, les taxis ou votre séjour. Comment puis-je vous aider ?', 'Je peux vous guider à votre arrivée. L’enregistrement et l’accès à la chambre nécessitent les consignes approuvées de l’hôtel.', 'Les appels ne sont pas encore connectés. Utilisez le numéro indiqué sur votre réservation.', 'La réservation de taxi n’est pas encore connectée. La réception peut vous aider à organiser le trajet.', 'Posez vos questions sur le séjour, les restaurants ou les environs. Les réponses seront disponibles après connexion du service vocal.'],
    de: ['Einchecken','Mit jemandem sprechen','Taxi bestellen','Eine Frage stellen','Willkommen. Wie kann ich helfen?','Hallo und willkommen! Ich bin Manzanilla, Ihre Empfangsassistentin. Ich helfe bei Anreiseinformationen, Kontaktmöglichkeiten, Taxiinformationen und Fragen zu Ihrem Aufenthalt. Wie kann ich helfen?', 'Ich kann Sie bei der Anreise unterstützen. Check-in und Zimmerzugang benötigen die bestätigten Anweisungen des Hotels.', 'Anrufe sind noch nicht verbunden. Nutzen Sie die Kontaktnummer auf Ihrer Buchung.', 'Taxibuchungen sind noch nicht verbunden. Die Rezeption kann Ihnen bei der Fahrt helfen.', 'Fragen Sie nach Ihrem Aufenthalt, Restaurants oder der Umgebung. Antworten sind nach Anschluss des Sprachdienstes möglich.'],
    it: ['Check-in','Parla con una persona','Chiedi un taxi','Fai una domanda','Benvenuto. Come posso aiutarti?','Ciao, benvenuto! Sono Manzanilla, la tua assistente alla reception. Posso aiutarti con informazioni sull’arrivo, contatti, taxi o domande sul soggiorno. Come posso aiutarti?', 'Posso guidarti all’arrivo. Il check-in e l’accesso alla camera richiedono le istruzioni approvate dell’hotel.', 'Le chiamate non sono ancora collegate. Usa il numero di contatto sulla prenotazione.', 'La prenotazione di taxi non è ancora collegata. La reception può aiutarti a organizzare il viaggio.', 'Chiedi informazioni sul soggiorno, sui ristoranti o sulla zona. Le risposte saranno disponibili collegando il servizio vocale.'],
    pt: ['Fazer check-in','Falar com uma pessoa','Pedir um táxi','Fazer uma pergunta','Bem-vindo. Como posso ajudar?','Olá, bem-vindo! Sou a Manzanilla, a sua assistente de receção. Posso ajudar com informações sobre a chegada, contactos, táxis ou dúvidas sobre a estadia. Como posso ajudar?', 'Posso orientar a sua chegada. O check-in e o acesso ao quarto precisam das instruções aprovadas pelo hotel.', 'As chamadas ainda não estão ligadas. Use o contacto indicado na sua reserva.', 'A reserva de táxis ainda não está ligada. A receção pode ajudar a organizar a viagem.', 'Pergunte sobre a estadia, restaurantes ou a região. As respostas estarão disponíveis ao ligar o serviço de voz.']
  };
  const shortcuts = ['checkin', 'operator', 'taxi', 'question'];
  const concierge = () => conciergeCopy[state.language] || conciergeCopy.en;
  const state = { page: 'idle', mode: 'translation', night: false, language: 'en', staff_language: 'es', demo: !native, connected: false, mic: false, audioUnknown: false, active: false, phase: '', afterStop: 'idle', text: '', transcript: '', transcriptSource: '', error: '', draft: null, confirmed: false, hotel: '', sample: false, configured: false, lastActivity: Date.now() };
  const icons = {
    arrow: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true"><path d="M5 12h14m-5-5 5 5-5 5"/></svg>',
    translate: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" aria-hidden="true"><path d="M3 5h12M9 2v3m3 0c-.5 5-3.5 8-8 10m1-8c1.5 3 4.2 5.7 7 7m1 7 4-11 4 11m-6.4-4h4.8"/></svg>',
    bell: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" aria-hidden="true"><path d="M3 17h18M5 17v-3a7 7 0 0 1 14 0v3M2 21h20M12 4v3m-2-3h4"/></svg>',
    back: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true"><path d="m14 6-6 6 6 6"/></svg>',
    exchange: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" aria-hidden="true"><path d="M4 8h16m-4-4 4 4-4 4M20 16H4m4-4-4 4 4 4"/></svg>'
  };
  const esc = (value) => String(value ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
  const t = () => copy[state.language] || copy.en;
  const langName = (language) => languages[language]?.native || language;
  const send = (action, detail = {}) => { if (state.demo && ['start','stop','message','operator','checkin','taxi','question','confirm'].includes(action)) return; if (native) window.ManzaniaNative.postMessage(JSON.stringify({ action, ...detail })); };
  const action = (name, label, className = 'primary', disabled = false) => `<button class="${className}" data-action="${name}" ${disabled ? 'disabled' : ''}>${label}</button>`;
  const back = () => `<div class="back-row"><button class="back" data-action="back" aria-label="Back">${icons.back}</button></div>`;
  const fine = (text) => `<p class="fine">${esc(text)}</p>`;
  // Sample content is identified in the staff handoff; guest chrome remains uncluttered.

  function render() {
    document.documentElement.lang = ['idle', 'modes', 'languages'].includes(state.page) ? 'en' : state.language;
    document.documentElement.dataset.night = String(state.night);
    document.documentElement.dataset.page = state.page;
    document.documentElement.dataset.concierge = String(state.page === 'active' && state.mode === 'reception');
    const nightToggle = document.getElementById('night-toggle');
    nightToggle.textContent = state.night ? 'Night reception on' : 'Night reception';
    nightToggle.ariaPressed = String(state.night);
    nightToggle.hidden = !['idle', 'modes'].includes(state.page);
    const status = document.getElementById('connection');
    status.dataset.connected = String(state.connected && !state.demo);
    content.dataset.page = state.page;
    document.getElementById('mode-title').textContent = 'Reception';
    document.getElementById('connection-text').textContent = state.demo ? 'Preview · AI not connected' : state.connected ? 'PC connected' : 'PC not connected';
    document.getElementById('hotel-label').textContent = state.demo ? 'Example experience' : state.active && native ? 'Laptop mic · Manzanilla speaker' : state.hotel ? state.hotel + (state.sample ? ' · Sample' : '') : 'Hotel reception';
    document.getElementById('privacy').innerHTML = `<span class="mic-dot${state.mic ? ' mic-live' : ''}"></span>${state.audioUnknown ? 'Audio state unconfirmed' : state.phase === 'ending' ? 'Closing microphone' : state.mic ? 'Microphone on' : 'Microphone off'}`;
    document.getElementById('hardware-hint').textContent = state.active ? 'Green or red to end' : state.page === 'ready' ? 'Press 1 to start' : state.page === 'languages' ? 'Choose 1 to 8' : 'Green button to begin';
    document.getElementById('stage-caption').textContent = state.page === 'idle' ? 'A little help. A warmer welcome.' : state.page === 'active' && state.mode === 'reception' ? (state.transcript || concierge()[4]) : '';
    const html = {
      idle: () => `<h1>A warm welcome<br>in every language.</h1>${action('wake', icons.bell + 'Ring for reception', 'primary ring-button')}`,
      modes: () => `<h2>How can we help?</h2><div class="choice-list">${action('translation', icons.translate + '<strong>Live translation</strong>', 'choice service')}${action('reception', icons.bell + '<strong>Hotel assistance</strong>', 'choice service')}</div>`,
      languages: () => `<h2 class="language-heading">Choose your language</h2><div class="language-list">${Object.entries(languages).map(([id, item], i) => action('language:' + id, `<span class="key">${i + 1}</span><strong lang="${id}">${item.native}</strong>`, 'choice language language-' + id)).join('')}</div>`,
      ready: () => `${back()}<h2>${esc(t().ready)}</h2><div class="session-scroll">${state.mode === 'translation' ? `<div class="pair">${esc(langName(state.staff_language))}${icons.exchange}${esc(langName(state.language))}</div>` : `<p class="lead">${esc(t().welcome)}</p>`}${sameLanguage() ? '<div class="notice">Choose a different guest language for translation.</div>' : ''}${state.error ? `<div class="notice error">${esc(state.error)}</div>` : ''}${!state.connected && native && !state.demo ? '<div class="notice">Reception is temporarily unavailable. Please ask a member of staff.</div>' : ''}</div>${action('start', esc(t().start), 'primary', (native && !state.demo && !state.connected) || sameLanguage())}`,
      active: () => `<div class="status-line"><h2>${esc(state.phase === 'connecting' ? t().connect : state.phase === 'speaking' ? t().speaking : t().listen)}</h2>${state.phase === 'connecting' ? '<span class="spinner" aria-label="Connecting"></span>' : '<div class="session-icon" aria-hidden="true"><i></i><i></i><i></i><i></i><i></i></div>'}</div><div class="session-scroll">${state.mode === 'translation' ? `<div class="pair">${esc(langName(state.staff_language))}${icons.exchange}${esc(langName(state.language))}</div>` : ''}<div class="conversation"><div class="${state.transcript ? '' : 'empty-transcript'}">${esc(state.transcript || (state.mode === 'translation' ? t().begin : t().welcome))}</div></div></div><div class="session-actions">${state.mode === 'reception' ? action('message', esc(t().message), 'secondary') + action('operator', esc(t().operator), 'secondary') : ''}${action('stop', esc(t().end), 'stop')}</div>`,
      confirming: () => `<h2>${esc(t().draft)}</h2><div class="draft">${state.draft?.guest_name || state.draft?.room_reference ? `<div class="draft-meta">${esc([state.draft?.guest_name, state.draft?.room_reference].filter(Boolean).join(' · '))}</div>` : ''}${esc(state.draft?.message || '')}</div><div class="button-row">${action('confirm', esc(state.confirmed ? t().saving : t().save), 'primary', state.confirmed)}${action('cancel-draft', esc(t().cancel), 'secondary', state.confirmed)}</div>`,
      saved: () => `<h2>${esc(state.demo ? t().prepared : t().saved)}</h2><p class="lead">${esc(state.demo ? t().notSent : t().local)}</p><div class="button-row">${action('continue', esc(t().continue))}${action('stop', esc(t().end), 'secondary')}</div>`,
      ending: () => `<div class="status-line"><h2>Ending conversation</h2><span class="spinner" aria-label="Closing microphone"></span></div>${state.error ? `<div class="notice error">${esc(state.error)}</div>` : ''}`,
      error: () => `<h2>Let’s reconnect.</h2><div class="notice error">${esc(state.error || 'The conversation stopped. Check the PC connection and try again.')}</div>${fine(state.audioUnknown ? 'The PC connection was lost. We cannot confirm that its microphone has closed.' : 'The microphone is off. No new conversation has started.')}<div class="button-row">${action('retry', 'Try again', 'primary', native && (!state.connected || state.audioUnknown))}${action('home', 'Return to bell', 'secondary')}</div>`
    };
    const focused = document.activeElement?.dataset?.action;
    content.innerHTML = state.page === 'active' && state.mode === 'reception'
      ? `<div class="status-line"><h2 class="concierge-heading">${esc(t().welcome)}</h2>${state.phase === 'connecting' ? '<span class="spinner" aria-label="Connecting"></span>' : '<div class="session-icon" aria-hidden="true"><i></i><i></i><i></i><i></i><i></i></div>'}</div><div class="shortcut-list">${shortcuts.map((id,i) => action(id, `<span class="key">${i + 1}</span><strong>${esc(concierge()[i])}</strong>`, 'choice shortcut shortcut-' + i, state.phase === 'connecting')).join('')}</div>${action('stop',esc(t().end),'stop')}`
      : (html[state.page] || html.idle)();
    if (focused) [...content.querySelectorAll('[data-action]')].find(button => button.dataset.action === focused)?.focus();
    window.manzaniaScene?.setState(state.page, state.phase);
  }

  function activity() { state.lastActivity = Date.now(); send('activity'); }
  function sameLanguage() { return state.mode === 'translation' && state.language === state.staff_language; }
  function page(next) { state.page = next; render(); }
  function wake() {
    if (state.page !== 'idle') return;
    window.manzaniaScene?.pressBell();
    ringSound();
    if (state.night) chooseMode('reception'); else page('modes');
  }
  let bellAudio;
  function ringSound() {
    if (native) { send('bell'); return; }
    try {
      const Audio = window.AudioContext || window.webkitAudioContext;
      if (!Audio) return;
      bellAudio ||= new Audio();
      bellAudio.resume();
      const now = bellAudio.currentTime;
      [[1318,.15],[2639,.055],[3681,.025]].forEach(([frequency, volume]) => {
        const oscillator = bellAudio.createOscillator();
        const gain = bellAudio.createGain();
        oscillator.frequency.value = frequency;
        gain.gain.setValueAtTime(.0001, now);
        gain.gain.exponentialRampToValueAtTime(volume, now + .007);
        gain.gain.exponentialRampToValueAtTime(.0001, now + .85);
        oscillator.connect(gain); gain.connect(bellAudio.destination);
        oscillator.start(now); oscillator.stop(now + .9);
        oscillator.onended = () => { oscillator.disconnect(); gain.disconnect(); };
      });
    } catch { /* A muted or unsupported browser still has the visual bell. */ }
  }
  function toggleNight() {
    if (!['idle', 'modes'].includes(state.page) || state.active) return;
    activity(); state.night = !state.night; state.mode = state.night ? 'reception' : 'translation';
    page('idle'); send('night', { enabled: state.night });
  }
  function chooseMode(mode) {
    if (state.night && mode !== 'reception') return;
    state.mode = mode;
    state.error = '';
    silence();
    page('languages');
  }
  function start() {
    if (state.active || state.phase === 'ending' || state.audioUnknown) return;
    if (sameLanguage()) { toast('Choose a different guest language for translation.'); return; }
    if (native && !state.demo && !state.connected) { if(state.mode === 'reception') {state.error = 'Reception is temporarily unavailable. Please ask a member of staff.'; page('error');} else toast('Connect the PC bridge before starting.'); return; }
    silence();
    state.active = true;
    state.mic = false;
    state.phase = state.demo ? 'listening' : 'connecting';
    state.transcript = '';
    state.error = '';
    page('active');
    send('start', { mode: state.mode, language: state.language, staff_language: state.staff_language });
    if (state.demo && state.mode === 'translation') runDemo();
    else if (state.demo) { state.transcript = concierge()[4]; render(); speakLocal(concierge()[5]); }
  }
  let speechSequence = 0;
  let pendingSpeech = '';
  function silence() {
    pendingSpeech = '';
    window.speechSynthesis?.cancel();
    send('silence');
    window.manzaniaScene?.energy(0);
  }
  function speechState(id, phase) {
    if (id !== pendingSpeech || !state.demo || !state.active || state.mode !== 'reception') return;
    state.phase = phase === 'speaking' ? 'speaking' : 'ready';
    window.manzaniaScene?.energy(phase === 'speaking' ? .2 : 0);
    render();
  }
  function speakLocal(text) {
    silence();
    const id = pendingSpeech = 'reception-' + (++speechSequence);
    state.phase = 'ready'; render();
    if (native) { send('speak', { text, language: state.language, id }); return; }
    // Only installed local browser voices. No remote voice service or microphone.
    const voice = window.speechSynthesis?.getVoices().find(item => item.localService && item.lang.split('-')[0] === state.language);
    if (!voice || !window.SpeechSynthesisUtterance) return;
    const utterance = new window.SpeechSynthesisUtterance(text);
    utterance.voice = voice; utterance.lang = voice.lang; utterance.rate = .96;
    utterance.onstart = () => speechState(id,'speaking');
    utterance.onend = utterance.onerror = () => speechState(id,'ended');
    window.speechSynthesis.speak(utterance);
  }
  function chooseShortcut(name) {
    if (!state.active || state.mode !== 'reception' || state.page !== 'active' || state.phase === 'connecting') return;
    if (!state.demo) { send(name); return; }
    const index = shortcuts.indexOf(name);
    if (index < 0) return;
    state.transcript = concierge()[6 + index];
    speakLocal(state.transcript);
  }
  let demoTimer;
  // Localized scripted captions for the key-free walkthrough, never provider output.
  const sampleSpeech = {
    en: ['What time is breakfast?', 'Breakfast is from seven to ten.', 'Could I leave a message for reception?', 'Of course. You can review your message before confirming it.'],
    es: ['¿A qué hora es el desayuno?', 'El desayuno es de siete a diez.', '¿Puedo dejar un mensaje para recepción?', 'Por supuesto. Puedes revisar tu mensaje antes de confirmarlo.'],
    ja: ['朝食は何時ですか？', '朝食は午前7時から10時までです。', '受付にメッセージを残せますか？', 'もちろんです。保存する前にメッセージをご確認いただけます。'],
    zh: ['早餐是几点？', '早餐时间是早上七点到十点。', '我可以给前台留言吗？', '当然可以。确认之前，您可以先查看留言。'],
    fr: ['À quelle heure est le petit-déjeuner ?', 'Le petit-déjeuner est servi de sept heures à dix heures.', 'Puis-je laisser un message à la réception ?', 'Bien sûr. Vous pourrez relire votre message avant de le confirmer.'],
    de: ['Wann gibt es Frühstück?', 'Frühstück gibt es von sieben bis zehn Uhr.', 'Kann ich eine Nachricht für die Rezeption hinterlassen?', 'Natürlich. Sie können Ihre Nachricht vor dem Bestätigen noch einmal lesen.'],
    it: ['A che ora è la colazione?', 'La colazione è dalle sette alle dieci.', 'Posso lasciare un messaggio alla reception?', 'Certamente. Puoi rileggere il messaggio prima di confermarlo.'],
    pt: ['A que horas é o pequeno-almoço?', 'O pequeno-almoço é das sete às dez.', 'Posso deixar uma mensagem para a receção?', 'Claro. Pode rever a mensagem antes de a confirmar.']
  };
  function runDemo() {
    clearTimeout(demoTimer);
    const sample = sampleSpeech[state.language] || sampleSpeech.en;
    const example = state.mode === 'translation'
      ? [['listening','Scripted guest example',sample[0]],['speaking','Scripted Spanish interpretation',sampleSpeech.es[0]],['listening','Scripted receptionist example',sampleSpeech.es[1]],['speaking','Scripted guest-language interpretation',sample[1]]]
      : [['listening','Scripted guest example',sample[2]],['speaking','Scripted hotel example',sample[3]]];
    let index=0;
    const step=()=>{
      if (!state.demo || !state.active) return;
      if (state.page === 'active') { [state.phase,state.transcriptSource,state.transcript]=example[index++%example.length]; render(); window.manzaniaScene?.energy(state.phase === 'speaking' ? .25 : 0); }
      demoTimer=setTimeout(step,4200);
    };
    step();
  }
  function stop(home = true) {
    silence();
    clearTimeout(demoTimer); window.manzaniaScene?.energy(0);
    if (state.phase === 'ending') return;
    if (state.active && native && !state.demo) { state.phase = 'ending'; state.afterStop = home ? 'idle' : 'ready'; page('ending'); send('stop'); return; }
    state.active = false;
    state.mic = false;
    state.draft = null;
    state.transcript = '';
    state.phase = '';
    page(home ? 'idle' : 'ready');
  }
  function goBack() {
    if (state.active) { stop(); return; }
    if (state.page === 'idle') { send('exit'); if (!native) toast('This button returns to the device launcher.'); return; }
    page({ modes: 'idle', languages: state.night ? 'idle' : 'modes', ready: 'languages', error: 'idle' }[state.page] || 'idle');
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
    if (name.startsWith('language:')) { const language=name.split(':')[1]; if(state.page !== 'languages' || !languages[language])return;state.language = language; if(state.mode === 'reception') start(); else page('ready'); return; }
    switch (name) {
      case 'wake': wake(); break;
      case 'translation': chooseMode('translation'); break;
      case 'reception': chooseMode('reception'); break;
      case 'start': start(); break;
      case 'stop': stop(); break;
      case 'back': goBack(); break;
      case 'home': stop(); break;
      case 'retry': if(state.mode === 'reception') start(); else page('ready'); break;
      case 'checkin': case 'operator': case 'taxi': case 'question': chooseShortcut(name); break;
      case 'message':
        if (!state.demo) send('message');
        else { state.draft={draft_id:'ui-demo',guest_name:'',room_reference:'',message:t().sampleMessage};state.confirmed=false;page('confirming'); }
        break;
      case 'confirm':
      case 'cancel-draft':
        if (!state.draft || state.confirmed) return;
        state.confirmed = true;
        if (state.demo) { state.draft=null;state.confirmed=false;page(name==='confirm'?'saved':'active');break; }
        send('confirm', { draft_id: state.draft.draft_id, approved: name === 'confirm' });
        if (name === 'cancel-draft') { state.draft = null; state.confirmed = false; page('active'); }
        else { render(); toast('Saving your message…'); }
        break;
      case 'continue': page('active'); break;
    }
  }
  content.addEventListener('click', event => { const button = event.target.closest('[data-action]'); if (button && !button.disabled) perform(button.dataset.action); });
  document.getElementById('exit').addEventListener('click', () => { stop(); send('exit'); if (!native) toast('This button returns to the device launcher.'); });
  document.getElementById('stage').addEventListener('click', () => { activity(); if (state.page === 'idle') wake(); });
  document.getElementById('night-toggle').addEventListener('click', toggleNight);

  function hardware(key) {
    activity();
    if (key === 'back' || key === 'red' || key === '0') { goBack(); return; }
    if (key === 'green') {
      if (state.active) stop();
      else if (state.page === 'idle') wake();
      else if (state.page === 'ready') start();
      return;
    }
    if (state.page === 'ready' && key === '1') start();
    else if (state.page === 'languages' && /^[1-8]$/.test(key)) perform('language:' + Object.keys(languages)[Number(key) - 1]);
    else if (state.page === 'modes' && key === '1') chooseMode('translation');
    else if (state.page === 'modes' && key === '2') chooseMode('reception');
    else if (state.page === 'active' && state.mode === 'reception' && /^[1-4]$/.test(key)) chooseShortcut(shortcuts[Number(key)-1]);
  }

  function receive(event) {
    if (typeof event === 'string') { try { event = JSON.parse(event); } catch { return; } }
    if (!event || typeof event !== 'object') return;
    const type = event.type || event.t;
    if (type === 'ui_speech') { speechState(String(event.id), String(event.phase)); return; }
    if (type === 'visibility' && event.visible === false) { silence(); return; }
    if (type === 'night_mode') {
      if (state.active) return;
      state.night = event.enabled === true;
      if (state.night) state.mode = 'reception';
      page('idle'); return;
    }
    if (type === 'demo_mode') {
      if (state.active && !state.demo) { toast('End the current conversation before changing preview mode.'); return; }
      stop(); state.demo=event.enabled===true; state.audioUnknown=false; state.mic=false; if(state.demo){state.hotel='Example hotel';state.sample=true;} render(); return;
    }
    if (type === 'live_audio_stop') { if(state.demo)return; state.active = false; state.mic = false; state.audioUnknown = false; state.phase = ''; state.draft = null; page(state.afterStop); return; }
    if (type === 'hardware') { hardware(String(event.key)); return; }
    if (type === 'energy') {
      const level = Math.min(1, Math.max(0, Number(event.level) || 0));
      content.querySelector('.session-icon')?.style.setProperty('--level', String(level));
      window.manzaniaScene?.energy(level);
      return;
    }
    if (type === 'connection') {
      const wasConnected = state.connected;
      state.connected = !!event.connected;
      if (wasConnected && !state.connected && state.active && !state.demo) { send('stop'); state.active = false; state.audioUnknown = true; state.error = 'The PC bridge disconnected. Reconnect it, then try again.'; state.page = 'error'; }
      render();
      return;
    }
    if (type === 'reception_info') {
      state.hotel = String(event.hotel_name || '');
      state.sample = !!event.sample_hotel;
      state.configured = !!event.configured;
      state.staff_language = String(event.staff_language || 'es');
      render(); return;
    }
    if (type !== 'reception_event') return;
    if (state.demo) return;
    state.lastActivity = Date.now();
    const phase = String(event.phase || event.state || '');
    if (state.phase === 'ending' && !['idle', 'ended', 'stopped', 'closed', 'timeout', 'error', 'failed', 'blocked'].includes(phase)) return;
    // Preserve the reviewer's reading position while unrelated live captions arrive.
    if (state.page === 'confirming' && !['confirming','saved','ending','idle','ended','stopped','closed','timeout','error','failed','blocked'].includes(phase)) { window.manzaniaScene?.setState(state.page,phase);return; }
    if (phase === 'ending') {
      state.phase='ending';state.page='ending';state.active=true;
    } else if (phase === 'confirming') {
      if (state.page === 'confirming' && state.draft?.draft_id === String(event.draft_id || '')) return;
      state.draft = { draft_id: String(event.draft_id || ''), guest_name: String(event.guest_name || ''), room_reference: String(event.room_reference || ''), message: String(event.message || '') };
      state.confirmed = false; state.page = 'confirming'; state.phase = 'confirming';
    } else if (phase === 'saved' && event.saved === true) {
      state.draft = null; state.page = 'saved'; state.phase = 'saved';
    } else if (['error', 'failed', 'blocked'].includes(phase)) {
      state.error = String(event.text || event.message || 'The conversation could not start. Check the PC bridge.');
      if (state.active && native) { state.phase = 'ending'; state.afterStop = 'error'; state.page = 'ending'; send('stop'); }
      else { state.page = 'error'; state.active = false; }
    } else if (['idle', 'ended', 'stopped', 'closed', 'timeout'].includes(phase)) {
      state.active = false; state.mic = false; state.audioUnknown = false; state.phase = ''; state.page = state.afterStop; state.afterStop = 'idle'; state.draft = null;
      if (phase === 'timeout') toast('Conversation ended after one minute of inactivity.');
    } else {
      if (event.operator_available === false && event.text) toast(String(event.text));
      if (['connecting', 'starting', 'listening', 'speaking', 'active', 'ready', 'thinking', 'working', 'ending'].includes(phase)) {
        state.phase = phase === 'starting' ? 'connecting' : phase === 'active' || phase === 'ready' ? 'listening' : phase;
        state.active = true;
        state.mic = native && phase !== 'connecting' && phase !== 'starting';
        if (!['confirming', 'saved'].includes(state.page)) state.page = 'active';
      }
      const isDelta = event.transcript === true;
      const transcript = isDelta ? event.text : typeof event.transcript === 'string' ? event.transcript : phase === 'transcript' ? event.text : undefined;
      if (transcript != null) {
        const source = event.source === 'output' ? (state.mode === 'translation' ? 'Translation' : 'Reception') : event.source === 'input' ? 'What was said' : String(event.source || (event.role === 'assistant' ? 'Reception' : 'Guest'));
        state.transcript = ((isDelta && source === state.transcriptSource ? state.transcript : '') + String(transcript)).slice(-1800);
        state.transcriptSource = source;
      }
    }
    render();
  }
  window.manzania = Object.freeze({ receive });
  document.addEventListener('keydown', event => {
    if (event.key === 'Escape') { event.preventDefault(); hardware('back'); }
    else if (/^[0-9]$/.test(event.key)) hardware(event.key);
  });
  setInterval(() => {
    if (state.page !== 'idle' && Date.now() - state.lastActivity > 60000 && (!state.active || state.demo)) stop();
  }, 1000);
  document.addEventListener('pointerdown', activity, { passive: true });
  document.addEventListener('wheel', activity, { passive: true });
  window.addEventListener('pagehide', () => { silence(); if (state.active) send('stop'); });
  render();
  send('info');
})();
