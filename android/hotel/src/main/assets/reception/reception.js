// Generated from ui/reception/reception.js; run npm run build:reception.
(() => {
  "use strict";
  const native = !!window.ManzaniaNative;
  const hasOwn = (object, key) => Object.prototype.hasOwnProperty.call(object, key);
  const content = document.getElementById("content");
  const catalogue = window.MANZANILLA_UI;
  const languages = /* @__PURE__ */ Object.create(null);
  catalogue.languages.forEach((language) => {
    languages[language.code] = language;
  });
  const translationLanguages = languages;
  let languageSide = "language";
  let appearance = "dark";
  const themeIcon = () => appearance === "light" ? '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" aria-hidden="true"><path d="M20 15.7A8.5 8.5 0 0 1 8.3 4 8.5 8.5 0 1 0 20 15.7Z"/></svg>' : '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" aria-hidden="true"><circle cx="12" cy="12" r="4"/><path d="M12 2v2m0 16v2M2 12h2m16 0h2M5 5l1.5 1.5m11 11L19 19M5 19l1.5-1.5m11-11L19 5"/></svg>';
  const shortcuts = ["checkin", "operator", "taxi", "question"];
  const concierge = () => ["Arrival guide", "Request help from reception", "Request a taxi through staff", "Ask about the hotel"].map((message) => uiText(message));
  const greet = (language) => window.MANZANILLA_GREET && window.MANZANILLA_GREET[language] || "";
  const state = { page: "idle", mode: "translation", night: false, language: "es", staff_language: "es", demo: !native, connected: false, audioAvailable: false, audioGate: "", audio: "off", active: false, phase: "", sessionId: "", generation: null, closed: true, terminated: true, error: "", metrics: "", utterances: /* @__PURE__ */ new Map(), draft: null, confirmed: false, operation: null, outcome: null, notices: [], hotel: "", pendingPage: "", micLevel: 0, turns: [] };
  Object.assign(state, { distribution: "personal-test", configured: false, connectionState: "unconfigured", services: null, bundledKeyAvailable: false, pendingShortcut: null });
  const serviceNames = ["checkin", "question", "taxi", "operator", "message"];
  const isHotel = () => state.distribution === "hotel";
  function serviceReason(name = "") {
    if (!state.configured || state.connectionState === "unconfigured") return uiText("Hotel services need staff setup.");
    if (state.connectionState !== "ready") return uiText({
      checking: "Checking the hotel connection. Please wait.",
      unauthorized: "The hotel device is not authorized. Please ask staff.",
      offline: "The hotel connection is unavailable. Please ask staff."
    }[state.connectionState] || "The hotel connection is unavailable. Please ask staff.");
    if (!state.services) return uiText("The hotel service needs an update. Please ask staff.");
    if (name ? !state.services[name] : !serviceNames.some((id) => state.services[id])) return uiText("This service is not configured by the hotel. Please ask staff.");
    return "";
  }
  const serviceNote = (name) => serviceReason(name) ? '<p class="service-reason" role="status">'.concat(esc(serviceReason(name)), "</p>") : "";
  const serviceChoice = (id, label, busy = false) => '<div class="service-choice">'.concat(action(id, "<strong>".concat(esc(label), "</strong>"), "choice shortcut", busy || !!state.pendingShortcut || !!serviceReason(id), true)).concat(serviceNote(id), "</div>");
  const reviewBlocked = () => !!state.operation && !state.operation.reconciliationPending && !!serviceReason(state.operation.action);
  const confirmationReason = () => {
    var _a;
    return serviceReason(((_a = state.operation) == null ? void 0 : _a.action) || state.requestedAction || "");
  };
  let queuedShortcut = "";
  const captionNodes = /* @__PURE__ */ new Map();
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
  const esc = (value) => String(value != null ? value : "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]);
  function uiText(message, values = {}) {
    const locale = hasOwn(languages, state.language) ? state.language : "es";
    const generic = catalogue.messages["This service is unavailable. Please ask hotel staff."][locale];
    const text = catalogue.messages[message] && catalogue.messages[message][locale];
    if (typeof text !== "string" || !text.trim()) return generic;
    const names = text.match(/\{[A-Za-z_][A-Za-z0-9_]*\}/g) || [];
    if (names.some((name) => !hasOwn(values, name.slice(1, -1))) || Object.keys(values).some((name) => names.indexOf("{" + name + "}") < 0)) return generic;
    return text.replace(/\{([A-Za-z_][A-Za-z0-9_]*)\}/g, (_, name) => String(values[name]));
  }
  const langName = (language) => {
    var _a, _b;
    return ((_a = translationLanguages[language]) == null ? void 0 : _a.native) || ((_b = languages[language]) == null ? void 0 : _b.native) || language;
  };
  const languagePicker = (side, label) => '<section class="language-side" data-keypad="'.concat(languageSide === side, '"><label for="').concat(side, '">').concat(esc(label), '</label><select id="').concat(side, '" data-language-side="').concat(side, '" lang="').concat(state[side], '" dir="').concat(languageDir(state[side]), '" aria-describedby="').concat(side, '-hint" ').concat(state.audio !== "off" ? "disabled" : "", ">").concat(Object.entries(translationLanguages).map(([id, item]) => '<option value="'.concat(id, '" lang="').concat(id, '" dir="').concat(languageDir(id), '" ').concat(state[side] === id ? "selected" : "", ">").concat(esc(item.native), "</option>")).join(""), '</select><small id="').concat(side, '-hint">').concat(esc(uiText(side === "language" ? "The language you speak" : "The language reception speaks")), "</small></section>");
  const send = (action2, detail = {}) => {
    if (!native) return;
    const scope = state.sessionId && !["start", "info", "languages", "appearance", "night", "settings", "voice_setup", "apply_update", "check_updates"].includes(action2) ? { session_id: state.sessionId, ...state.generation === null ? {} : { generation: state.generation } } : {};
    window.ManzaniaNative.postMessage(JSON.stringify({ action: action2, ...scope, ...detail }));
  };
  const action = (name, label, className = "primary", disabled = false, trustedMarkup = false) => '<button type="button" class="'.concat(className, '" data-action="').concat(name, '" ').concat(disabled ? "disabled" : "", ">").concat(trustedMarkup ? label : esc(label), "</button>");
  const fine = (text) => '<p class="fine">'.concat(esc(text), "</p>");
  const operationField = (field, index) => {
    const limit = Number.isInteger(field.max_length) && field.max_length > 0 ? field.max_length : { original_request: 4e3, guest_name: 100, room_number: 20, destination: 300, pickup_time: 100, query: 300 }[field.name] || 300;
    const attrs = 'id="operation-field-'.concat(index, '" name="').concat(esc(field.name), '" maxlength="').concat(limit, '" ').concat(field.required ? "required" : "", " ").concat(state.operation.pending || state.operation.reconciliationPending ? "disabled" : "", ' autocomplete="off" dir="auto"');
    const value = esc(state.operation.values[field.name] || "");
    const labels = { original_request: "Exact message to send to staff", destination: "Destination", pickup_time: "Pickup time", query: "Your hotel question", guest_name: state.operation.action === "message" ? "Guest name (optional)" : "Guest name", room_number: state.operation.action === "message" ? "Room number (optional)" : "Room number" };
    return '<label for="operation-field-'.concat(index, '">').concat(esc(uiText(labels[field.name] || "This service is unavailable. Please ask hotel staff."))).concat(field.required ? " *" : "").concat(field.name === "original_request" ? "<textarea ".concat(attrs, ">").concat(value, "</textarea>") : "<input ".concat(attrs, ' value="').concat(value, '">'), "</label>");
  };
  const languageDir = (language) => {
    var _a;
    return ((_a = languages[language]) == null ? void 0 : _a.direction) || "auto";
  };
  const captionSpeaker = (entry) => entry.side === "guest" ? uiText("Guest") : entry.side === "staff" ? uiText("Receptionist") : uiText("speaker unknown");
  const captionParts = (entry) => ["original", "text"].filter((field) => entry[field] !== "").map((field) => {
    const output = entry.side === "assistant" ? uiText("Assistant output") : field === "original" ? uiText("Original") : entry.association === "unlinked" ? uiText("Provider output") : uiText("Translation");
    const speaker = entry.speakerAttribution === "selected" ? uiText("{speaker} (selected)", { speaker: captionSpeaker(entry) }) : captionSpeaker(entry);
    const language = entry[field === "original" ? "from" : "to"];
    let label = entry.side === "assistant" ? output : uiText("{output} · {speaker}", { output, speaker });
    if (entry.association === "unlinked") label = uiText("{label} · unlinked", { label });
    if (language) label = uiText("{label} · {language}", { label, language: langName(language) });
    return { field, language, text: entry[field], label };
  });
  const captionClass = (entry) => "caption caption-".concat(entry.side === "guest" || entry.side === "human" ? "guest" : "reception");
  const captionBody = (entry) => captionParts(entry).map((part, index) => '<div class="caption-part'.concat(index ? " caption-divider" : "", '" data-field="').concat(part.field, '" data-final="').concat(entry.final[part.field], '"><span class="caption-language">').concat(esc(part.label), '</span><p class="').concat(state.mode === "translation" ? part.field === "original" ? "said" : "translated" : "caption-".concat(part.field), '" lang="').concat(esc(part.language || "und"), '" dir="').concat(languageDir(part.language), '">').concat(esc(part.text), "</p></div>")).join("");
  const caption = (entry) => '<article class="'.concat(captionClass(entry), '" data-caption-key="').concat(esc(entry.id), '" aria-atomic="true">').concat(captionBody(entry), "</article>");
  const turnLabel = (turn) => turn.kind === "assistant" ? uiText("Translation") : turn.side === "guest" ? uiText("Guest · {language}", { language: langName(state.language) }) : turn.side === "staff" ? uiText("Receptionist · {language}", { language: langName(state.staff_language) }) : uiText("Language unclear");
  const turnClass = (turn) => turn.side === "guest" ? "guest" : turn.side === "staff" ? "reception" : "pending";
  const turnHtml = (turn) => {
    const said = turn.original, translated = turn.text;
    const streaming = translated && !turn.final.text;
    return '<span class="caption-side">'.concat(esc(turnLabel(turn)), '</span><p class="said" dir="auto">').concat(esc(said || translated), "</p>").concat(said && translated ? '<p class="translated'.concat(streaming ? " live" : "", '" dir="auto">').concat(esc(translated), "</p>") : "");
  };
  function updateTurn(turn) {
    var _a;
    const transcript = content.querySelector(".conversation");
    if (!transcript) return;
    const scroller = transcript.closest(".session-scroll");
    const follow = scroller && scroller.scrollHeight - scroller.clientHeight - scroller.scrollTop < 60;
    if (!turn.node || !turn.node.isConnected) {
      (_a = transcript.querySelector(".empty-transcript")) == null ? void 0 : _a.remove();
      turn.node = document.createElement("article");
      turn.node.dataset.turnId = turn.id;
      turn.node.setAttribute("aria-atomic", "true");
      transcript.appendChild(turn.node);
    }
    turn.node.className = "caption caption-".concat(turnClass(turn));
    turn.node.innerHTML = turnHtml(turn);
    if (follow) scroller.scrollTop = scroller.scrollHeight;
  }
  function receiveTranslationCaption(event) {
    const changed = turnLedger.accept(event, state.language, state.staff_language);
    changed.forEach(updateTurn);
    if (changed.length && (event.type === "interpretation" || event.side !== "assistant") && (event.original || event.text || "").trim()) activity();
  }
  const conversation = () => {
    if (state.mode === "translation") {
      if (!state.turns.length) return '<div class="empty-transcript">'.concat(esc(uiText("Speak. We translate both ways.")), "</div>");
      return state.turns.map((turn) => '<article class="caption caption-'.concat(turnClass(turn), '" data-turn-id="').concat(esc(turn.id), '" aria-atomic="true">').concat(turnHtml(turn), "</article>")).join("");
    }
    return state.utterances.size ? [...state.utterances.values()].map(caption).join("") : '<div class="empty-transcript">'.concat(esc(uiText("Choose a service or type a message. The microphone stays off.")), "</div>");
  };
  function updateCaption(entry) {
    var _a;
    const transcript = content.querySelector(".conversation");
    if (!transcript) return;
    const scroller = transcript.closest(".session-scroll");
    const follow = scroller && scroller.scrollHeight - scroller.clientHeight - scroller.scrollTop < 60;
    let node = captionNodes.get(entry.id);
    if (!node) {
      (_a = transcript.querySelector(".empty-transcript")) == null ? void 0 : _a.remove();
      node = document.createElement("article");
      node.className = captionClass(entry);
      node.dataset.captionKey = entry.id;
      node.setAttribute("aria-atomic", "true");
      transcript.appendChild(node);
      captionNodes.set(entry.id, node);
    }
    node.innerHTML = captionBody(entry);
    if (follow) scroller.scrollTop = scroller.scrollHeight;
  }
  const phaseLabel = () => ({ connecting: uiText("Connecting"), ready: uiText("Staff-led support"), greeting: uiText("Welcome"), thinking: uiText("Translating"), speaking: uiText("Speaking"), listening: uiText("Live"), ending: uiText("Stopping"), stopped: uiText("Stopped") })[state.phase] || uiText("Hotel assistance");
  const sessionAudioText = () => ({ off: uiText("Mic off"), recording: uiText("Mic on"), muted: uiText("Mic muted"), stopping: uiText("Releasing mic"), unknown: uiText("Mic state unknown · ask staff") })[state.audio];
  const micLabel = () => ({ recording: uiText("Mute the microphone"), muted: uiText("Unmute the microphone"), off: state.phase === "connecting" ? uiText("Connecting · microphone off") : uiText("Microphone off"), stopping: uiText("Stopping · releasing microphone"), unknown: uiText("Microphone state unknown · ask staff") })[state.audio];
  function resetMicLevels() {
    if (state.audio !== "recording") {
      state.micLevel = 0;
      state.bands = [0, 0, 0];
    }
  }
  const sessionDock = () => {
    if (state.mode === "translation") {
      if (state.terminated) {
        const safe = !state.active && state.audio === "off";
        return '<div class="session-actions" data-stopped="true"><div class="session-control"><div class="session-control-copy"><strong>'.concat(esc(uiText("Stopped")), "</strong><span>").concat(esc(sessionAudioText()), "</span></div></div>").concat(action("restart", uiText("Try again"), "primary", !safe), "</div>");
      }
      resetMicLevels();
      const bands = state.bands || [state.micLevel, state.micLevel, state.micLevel];
      const muted = state.audio === "muted";
      const label = micLabel();
      const enabled = state.active && !state.closed && ["recording", "muted"].includes(state.audio);
      return '<button type="button" class="voice-ball" data-action="mute" data-hearing="'.concat(state.audio === "recording", '" aria-pressed="').concat(muted, '" aria-label="').concat(esc(label), '" ').concat(enabled ? "" : "disabled", ' style="--level:').concat(state.micLevel, ";--b0:").concat(bands[0], ";--b1:").concat(bands[1], ";--b2:").concat(bands[2], '">').concat(state.audio === "recording" ? icons.mic : icons.micOff, "</button>");
    }
    const stopped = state.closed || state.terminated;
    const restart = state.terminated && !state.active && state.audio === "off";
    const indicator = '<div class="session-icon" role="img" aria-label="'.concat(esc(uiText("Staff-led support")), '">').concat(icons.bell, "</div>");
    return '<div class="session-actions" data-stopped="'.concat(stopped, '"><div class="session-control">').concat(indicator, '<div class="session-control-copy"><strong>').concat(esc(stopped ? state.phase === "ending" ? uiText("Stopping…") : uiText("Stopped") : phaseLabel()), "</strong><span>").concat(esc(sessionAudioText()), "</span></div></div>").concat(!stopped ? '<div class="service-choice">'.concat(action("message", uiText("Message staff"), "secondary", ["connecting", "greeting"].includes(state.phase) || !!serviceReason("message"))).concat(serviceNote("message"), "</div>") : "").concat(action(restart ? "restart" : "stop", restart ? uiText("Restart") : stopped ? uiText("Releasing…") : uiText("Stop"), "stop", stopped && !restart), "</div>");
  };
  function syncMic() {
    resetMicLevels();
    const button = content.querySelector(".voice-ball");
    if (!button) return;
    const muted = state.audio === "muted";
    button.disabled = !state.active || state.closed || !["recording", "muted"].includes(state.audio);
    button.dataset.hearing = String(state.audio === "recording");
    button.setAttribute("aria-pressed", String(muted));
    button.setAttribute("aria-label", micLabel());
    button.style.setProperty("--level", String(state.micLevel));
    (state.bands || [state.micLevel, state.micLevel, state.micLevel]).forEach((band, index) => button.style.setProperty("--b" + index, String(band)));
    button.innerHTML = state.audio === "recording" ? icons.mic : icons.micOff;
  }
  function renderNotices() {
    const target = document.getElementById("notices");
    target.hidden = !state.notices.length;
    target.innerHTML = state.notices.map((notice, index) => '<div class="notice'.concat(["error", "failed", "unavailable", "denied"].includes(notice.status) ? " error" : "", '"><span><strong>').concat(esc(uiText("Service update")), "</strong> ").concat(esc(notice.text), '</span><button data-dismiss="').concat(index, '" aria-label="').concat(esc(uiText("Dismiss service update")), '">×</button></div>')).join("");
  }
  function addNotice(text, status = "") {
    if (!text) return;
    state.notices.push({ text: String(text), status: String(status) });
    renderNotices();
  }
  function render() {
    var _a, _b, _c, _d, _e, _f, _g;
    document.documentElement.dataset.appearance = appearance;
    document.documentElement.dataset.translation = state.page === "active" && state.mode === "translation" ? "live" : "";
    document.documentElement.lang = state.language;
    document.documentElement.dir = languageDir(state.language);
    document.documentElement.dataset.night = String(state.night);
    document.documentElement.dataset.page = state.page;
    document.documentElement.dataset.captureActive = String(state.active);
    const nightToggle = document.getElementById("night-toggle");
    nightToggle.textContent = state.night ? uiText("After-hours on") : uiText("After-hours reception");
    nightToggle.setAttribute("aria-label", state.night ? uiText("Turn off after-hours reception") : uiText("Turn on after-hours reception"));
    nightToggle.setAttribute("aria-pressed", String(state.night));
    nightToggle.hidden = isHotel() || state.page !== "idle";
    document.getElementById("settings").hidden = !native || !["idle", "error", "translation_languages"].includes(state.page);
    const setupLabel = isHotel() || state.mode !== "translation" ? uiText("Staff setup") : uiText("Fish Audio setup");
    document.getElementById("settings").setAttribute("aria-label", setupLabel);
    document.getElementById("settings").title = setupLabel;
    document.getElementById("check-updates").hidden = isHotel() || !native || state.page !== "idle";
    if (isHotel()) (_a = document.getElementById("update-offer")) == null ? void 0 : _a.remove();
    content.dataset.page = state.page;
    document.getElementById("mode-title").textContent = state.night ? uiText("After-hours reception") : uiText("Reception");
    const connection = document.getElementById("connection");
    connection.hidden = false;
    connection.dataset.state = state.connectionState;
    document.getElementById("connection-text").textContent = uiText({
      unconfigured: "Hotel setup needed",
      checking: "Checking hotel connection",
      ready: "Hotel connected",
      offline: "Hotel connection unavailable",
      unauthorized: "Hotel authorization required"
    }[state.connectionState]);
    document.getElementById("hotel-label").textContent = state.hotel || uiText("Hotel reception");
    document.getElementById("privacy").innerHTML = '<span class="mic-dot'.concat(state.audio === "recording" ? " mic-live" : "", '"></span>').concat(esc(sessionAudioText()));
    document.title = uiText("Reception");
    document.getElementById("check-updates").textContent = uiText("Check updates");
    document.querySelector(".clock").setAttribute("aria-label", uiText("Local date and time"));
    document.getElementById("notices").setAttribute("aria-label", uiText("Hotel service updates"));
    updateClock();
    const guestLocale = () => '<label class="assist-label" for="guest-locale">'.concat(esc(uiText("Language")), '</label><select id="guest-locale" lang="').concat(state.language, '">').concat(Object.entries(languages).map(([code, item]) => '<option value="'.concat(code, '" lang="').concat(code, '" dir="').concat(item.direction, '" ').concat(state.language === code ? "selected" : "", ">").concat(esc(item.native), "</option>")).join(""), "</select>");
    const homeAction = (name, icon, label, note = "", unavailable = "") => '<button class="home-action'.concat(name === (state.night ? "reception" : "translation") ? " home-action-main" : "", '" data-action="').concat(name, '" ').concat(unavailable ? "disabled" : "", ">").concat(name === (state.night ? "reception" : "translation") ? '<span class="orb">' + icons.arrow + "</span>" : icon, "<span>").concat(esc(label), "</span>").concat(unavailable || note ? "<small>" + esc(unavailable || note) + "</small>" : "", "</button>");
    const html = {
      idle: () => '<div class="welcome"><h1>'.concat(esc(uiText("Welcome.")), "</h1><p>").concat(esc(uiText(state.night ? "Self-service hotel assistance." : "Guest and staff support.")), "</p></div>").concat(guestLocale(), '<nav class="home-actions" aria-label="').concat(esc(uiText("Reception services")), '">').concat(state.night ? homeAction("reception", icons.bell, uiText("Hotel assistance"), uiText("Self-service support"), serviceReason()) + homeAction("translation", icons.translate, uiText("Live translation"), state.audioAvailable ? uiText("Talk to each other") : uiText("Audio unavailable")) : homeAction("translation", icons.translate, uiText("Live translation"), state.audioAvailable ? uiText("Talk to each other") : uiText("Audio unavailable")) + homeAction("reception", icons.bell, uiText("Hotel assistance"), "", serviceReason())).concat(homeAction("request:checkin", icons.bed, uiText("Arrival guide"), "", serviceReason("checkin"))).concat(homeAction("request:taxi", icons.car, uiText("Request a taxi through staff"), "", serviceReason("taxi"))).concat(homeAction("request:message", icons.message, uiText("Message staff"), "", serviceReason("message")), "</nav>").concat(!isHotel() ? '<div class="appearance-row"><button class="appearance-toggle" data-action="appearance" aria-label="'.concat(esc(uiText("Appearance")), '" title="').concat(esc(uiText("Appearance")), '">').concat(themeIcon(), "</button></div>") : "").concat(!isHotel() && state.bundledKeyAvailable ? fine(uiText("Personal-test build · bundled Fish key")) + fine(uiText("The bundled Fish key is extractable. Use a disposable, limited key and rotate it after testing.")) : ""),
      languages: () => '<div class="status-line"><div><h2 class="language-heading">'.concat(esc(uiText("Hotel assistance")), '</h2></div><button class="back" data-action="back">').concat(icons.back).concat(esc(uiText("Back")), '</button></div><label class="assist-label" for="assist-language">').concat(esc(uiText("Language")), '</label><select id="assist-language" class="assist-language" lang="').concat(state.language, '" dir="').concat(languageDir(state.language), '">').concat(Object.entries(languages).map(([id, item]) => '<option value="'.concat(id, '" lang="').concat(id, '" dir="').concat(languageDir(id), '" ').concat(state.language === id ? "selected" : "", ">").concat(esc(item.native), "</option>")).join(""), '</select><div class="service-availability">').concat([...shortcuts, "message"].map((id, i) => "<div><strong>".concat(esc(i < shortcuts.length ? concierge()[i] : uiText("Message staff")), "</strong>").concat(serviceNote(id), "</div>")).join(""), "</div>").concat(serviceNote()).concat(action("assist-go", uiText("Continue"), "primary assist-go", !!serviceReason(queuedShortcut))),
      translation_languages: () => '<div class="status-line"><div><h2 class="language-heading">'.concat(esc(uiText("Choose languages")), '</h2><p class="keypad-hint">').concat(esc(uiText("Guest ↔ Receptionist")), '</p></div><button class="back" data-action="back">').concat(icons.back).concat(esc(uiText("Back")), '</button></div><div class="language-pair">').concat(languagePicker("language", uiText("Guest"))).concat(languagePicker("staff_language", uiText("Receptionist")), '</div><p id="translation-readiness" class="setup-feedback" role="status">').concat(esc(translationReadiness()), '</p><button type="button" class="primary setup-start" data-action="').concat(native && !state.audioAvailable ? "setup-translation" : "start", '" aria-describedby="translation-readiness" ').concat(state.audio !== "off" || (!native || state.audioAvailable) && sameLanguage() ? "disabled" : "", ">").concat(esc(uiText(native && !state.audioAvailable ? "Set up translation" : "Start translation")), "</button>"),
      active: () => state.mode === "translation" ? '<button class="translate-back" data-action="back" aria-label="'.concat(esc(uiText("Back")), '">').concat(icons.back, '</button><p class="pair-title" dir="ltr"><bdi>').concat(esc(langName(state.language)), "</bdi> → <bdi>").concat(esc(langName(state.staff_language)), '</bdi></p><div class="session-scroll"><div class="conversation" role="log" aria-label="').concat(esc(uiText("Conversation transcript")), '" aria-live="polite">').concat(conversation(), "</div></div>").concat(sessionDock()) : '<div class="status-line session-top"><div><h2>'.concat(esc(uiText("Hotel assistance")), "</h2><p>").concat(esc(phaseLabel()), '</p></div><button class="back" data-action="back">').concat(icons.back).concat(esc(uiText("Back")), "</button></div>").concat(state.error ? '<div class="notice error">'.concat(esc(state.error), "</div>") : "", '<div class="session-scroll">').concat(!state.closed ? '<div class="shortcut-list">'.concat(shortcuts.map((id, i) => serviceChoice(id, concierge()[i], ["connecting", "greeting"].includes(state.phase))).join(""), "</div>") : "", '<div class="conversation" role="log" aria-label="').concat(esc(uiText("Conversation transcript")), '" aria-live="polite">').concat(conversation(), "</div>").concat(state.metrics ? '<p class="metrics">'.concat(esc(state.metrics), "</p>") : "", "</div>").concat(sessionDock()),
      operation_form: () => {
        var _a2, _b2, _c2, _d2, _e2, _f2, _g2, _h, _i, _j;
        return '<div class="status-line"><h2>'.concat(esc(((_a2 = state.operation) == null ? void 0 : _a2.action) === "message" ? uiText("Message staff") : concierge()[shortcuts.indexOf((_b2 = state.operation) == null ? void 0 : _b2.action)] || uiText("Hotel request")), '</h2><button class="back" data-action="back">').concat(icons.back).concat(esc(uiText("Back")), "</button></div>").concat(guestLocale()).concat(serviceNote((_c2 = state.operation) == null ? void 0 : _c2.action), '<form id="operation-form" class="operation-form" aria-busy="').concat(!!((_d2 = state.operation) == null ? void 0 : _d2.pending), '">').concat((((_e2 = state.operation) == null ? void 0 : _e2.fields) || []).map(operationField).join(""), '<div class="button-row"><button type="submit" class="primary" ').concat(((_f2 = state.operation) == null ? void 0 : _f2.pending) || reviewBlocked() ? "disabled" : "", ">").concat(esc(uiText(((_g2 = state.operation) == null ? void 0 : _g2.pending) ? "Reviewing…" : ((_h = state.operation) == null ? void 0 : _h.reconciliationPending) ? "Retry" : "Review")), "</button>").concat(action("cancel-operation", uiText("Cancel"), "secondary", ((_i = state.operation) == null ? void 0 : _i.pending) || ((_j = state.operation) == null ? void 0 : _j.reconciliationPending)), "</div></form>").concat(action("stop", uiText("End"), "stop"));
      },
      confirming: () => {
        var _a2, _b2, _c2, _d2, _e2, _f2, _g2;
        return '<div class="status-line"><h2>'.concat(esc(uiText("Confirm request?")), '</h2><button class="back" data-action="back">').concat(icons.back).concat(esc(uiText("Back")), '</button></div><div class="draft" dir="auto">').concat(esc(((_a2 = state.draft) == null ? void 0 : _a2.message) || ""), "</div>").concat(confirmationReason() ? fine(confirmationReason()) : "").concat(fine(((_b2 = state.draft) == null ? void 0 : _b2.attempted) ? state.draft.approved === false ? uiText("No cancellation receipt yet. Retry to check the result.") : uiText("No receipt yet. Retry the same request to check its result.") : uiText("Confirm only if the details are correct.")), '<div class="button-row">').concat(action(((_c2 = state.draft) == null ? void 0 : _c2.attempted) ? "retry-draft" : "confirm", state.confirmed ? ((_d2 = state.draft) == null ? void 0 : _d2.approved) === false ? uiText("Cancelling…") : uiText("Sending…") : ((_e2 = state.draft) == null ? void 0 : _e2.attempted) ? uiText("Retry") : uiText("Confirm"), "primary", state.confirmed || !((_f2 = state.draft) == null ? void 0 : _f2.attempted) && !!confirmationReason())).concat(state.operation ? action("edit-draft", uiText("Edit"), "secondary", state.confirmed || ((_g2 = state.draft) == null ? void 0 : _g2.attempted)) : "").concat(action("cancel-draft", uiText("Cancel"), "secondary", state.confirmed), "</div>");
      },
      saved: () => {
        var _a2, _b2, _c2, _d2;
        return "<h2>".concat(esc(uiText("Request receipt")), '</h2><section class="request-receipt"><p class="lead" dir="auto">').concat(esc(((_a2 = state.outcome) == null ? void 0 : _a2.text) || uiText("No receipt returned. Ask staff to check.")), "</p>").concat(((_b2 = state.outcome) == null ? void 0 : _b2.receipt) ? '<div class="receipt" dir="auto"><bdi>'.concat(esc(state.outcome.receipt), "</bdi></div>") : "", '</section><div class="button-row">').concat(((_c2 = state.pendingShortcut) == null ? void 0 : _c2.reconciliationPending) ? action("retry-shortcut", uiText(state.pendingShortcut.pending ? "Sending…" : "Retry"), "primary", state.pendingShortcut.pending) : "".concat(((_d2 = state.outcome) == null ? void 0 : _d2.helpAction) === "operator" ? action("receipt-help", uiText("Request help from reception")) : "").concat(action("continue", uiText("Continue")))).concat(action("stop", uiText("End"), "secondary"), "</div>");
      },
      error: () => '<div class="status-line"><h2>'.concat(esc(uiText("Conversation unavailable")), '</h2><button class="back" data-action="back">').concat(icons.back).concat(esc(uiText("Back")), '</button></div><div class="notice error">').concat(esc(state.error || uiText("Please ask reception for help.")), "</div>").concat(fine(state.audio === "off" ? uiText("Microphone off. Staff can check this device’s setup.") : uiText("Audio release is not confirmed. Ask reception for help before restarting.")), '<div class="button-row">').concat(action("retry", uiText("Try again"), "primary", state.audio !== "off")).concat(action("home", uiText("Back to home"), "secondary"), "</div>")
    };
    const focusedElement = document.activeElement;
    const focusedAction = (_b = focusedElement == null ? void 0 : focusedElement.dataset) == null ? void 0 : _b.action;
    const focusedSelect = (_c = focusedElement == null ? void 0 : focusedElement.dataset) == null ? void 0 : _c.languageSide;
    const focusedAssist = (focusedElement == null ? void 0 : focusedElement.id) === "assist-language";
    const focusedLocale = (focusedElement == null ? void 0 : focusedElement.id) === "guest-locale";
    const focusedInput = (focusedElement == null ? void 0 : focusedElement.closest("#operation-form")) ? { name: focusedElement.name, start: focusedElement.selectionStart, end: focusedElement.selectionEnd } : null;
    const scrollTop = content.scrollTop;
    const scrolls = [...content.querySelectorAll(".session-scroll,.draft,.language-list")].map((element) => ({ top: element.scrollTop, follow: element.classList.contains("session-scroll") && element.scrollHeight - element.clientHeight - element.scrollTop < 60 }));
    const retainedTurns = new Map(state.turns.filter((turn) => {
      var _a2;
      return (_a2 = turn.node) == null ? void 0 : _a2.isConnected;
    }).map((turn) => [turn.id, turn.node]));
    captionNodes.clear();
    content.innerHTML = (html[state.page] || html.idle)();
    if (["idle", "languages", "translation_languages"].includes(state.page)) content.insertAdjacentHTML("beforeend", fine(uiText("Interface translations have not been reviewed by a human.")));
    content.querySelectorAll("[data-caption-key]").forEach((node) => captionNodes.set(node.dataset.captionKey, node));
    const turnNodes = new Map([...content.querySelectorAll("[data-turn-id]")].map((node) => [node.dataset.turnId, node]));
    state.turns.forEach((turn) => {
      const rendered = turnNodes.get(turn.id);
      const retained = retainedTurns.get(turn.id);
      if (rendered && retained) {
        retained.className = rendered.className;
        rendered.replaceWith(retained);
        turn.node = retained;
      } else turn.node = rendered || null;
    });
    content.scrollTop = scrollTop;
    [...content.querySelectorAll(".session-scroll,.draft,.language-list")].forEach((element, index) => {
      const previous = scrolls[index];
      element.scrollTop = (previous == null ? void 0 : previous.follow) ? element.scrollHeight : (previous == null ? void 0 : previous.top) || 0;
    });
    if (focusedAction) (_d = [...content.querySelectorAll("[data-action]")].find((button) => button.dataset.action === focusedAction)) == null ? void 0 : _d.focus({ preventScroll: true });
    if (focusedSelect) (_e = content.querySelector('[data-language-side="'.concat(focusedSelect, '"]'))) == null ? void 0 : _e.focus({ preventScroll: true });
    if (focusedAssist) (_f = content.querySelector("#assist-language")) == null ? void 0 : _f.focus({ preventScroll: true });
    if (focusedLocale) (_g = content.querySelector("#guest-locale")) == null ? void 0 : _g.focus({ preventScroll: true });
    if (focusedInput) {
      const input = [...content.querySelectorAll("#operation-form input,#operation-form textarea")].find((element) => element.name === focusedInput.name);
      input == null ? void 0 : input.focus({ preventScroll: true });
      if (input && focusedInput.start !== null) input.setSelectionRange(focusedInput.start, focusedInput.end);
    }
    renderNotices();
  }
  function activity() {
    send("activity");
  }
  function applyAppearance(value) {
    if (value !== "light" && value !== "dark") return;
    const changed = appearance !== value;
    appearance = value;
    if (changed) render();
  }
  function toggleAppearance() {
    if (isHotel() || state.page !== "idle" || state.active) return;
    activity();
    applyAppearance(appearance === "light" ? "dark" : "light");
    send("appearance", { value: appearance });
  }
  function sameLanguage() {
    return state.mode === "translation" && state.language === state.staff_language;
  }
  function translationReadiness() {
    if (state.audio !== "off") return state.audio === "unknown" ? uiText("Microphone release could not be confirmed. Retry after the previous conversation closes.") : uiText("Waiting for the previous conversation to release the microphone.");
    if (!state.audioAvailable) return state.audioGate || uiText("Checking translation setup…");
    if (sameLanguage()) return uiText("Choose two different languages to start translation.");
    return uiText("Ready. Tap Start translation to turn on the microphone.");
  }
  function page(next) {
    state.page = next;
    render();
  }
  function toggleNight() {
    if (isHotel() || state.page !== "idle" || state.active) return;
    activity();
    state.night = !state.night;
    state.mode = state.night ? "reception" : "translation";
    render();
    send("night", { enabled: state.night });
  }
  function chooseMode(mode) {
    if (state.active || state.audio !== "off") return;
    if (mode === "reception" && serviceReason()) {
      toast(serviceReason());
      return;
    }
    queuedShortcut = "";
    state.mode = mode;
    state.error = "";
    silence();
    page(mode === "translation" ? "translation_languages" : "languages");
  }
  function clearGuest() {
    state.utterances.clear();
    captionNodes.clear();
    turnLedger.clear();
    const transcript = content.querySelector(".conversation");
    if (transcript) transcript.innerHTML = conversation();
    state.draft = null;
    state.confirmed = false;
    state.operation = null;
    state.outcome = null;
    state.pendingShortcut = null;
    state.metrics = "";
    state.notices = [];
    document.getElementById("toast").hidden = true;
    renderNotices();
  }
  function selectLanguage(side, language) {
    if (state.audio !== "off" || !hasOwn(languages, language) || state.active && (state.mode === "translation" || side !== "language")) return;
    if (state[side] === language) return;
    state[side] = language;
    selected[side] = true;
    if (side === "language" && state.configured) {
      state.connectionState = "checking";
      state.services = null;
    }
    send("languages", { language: state.language, staff_language: state.staff_language });
  }
  function start(restart = false) {
    var _a, _b;
    if (state.active || state.phase === "ending" || state.audio !== "off") return;
    if (state.mode === "reception" && serviceReason(queuedShortcut)) {
      toast(serviceReason(queuedShortcut));
      return;
    }
    if (state.mode === "translation" && !state.audioAvailable) {
      if (native) send("voice_setup");
      else toast(state.audioGate || uiText("Checking translation setup…"));
      return;
    }
    if (sameLanguage()) {
      toast(uiText("Choose a different guest language for translation."));
      return;
    }
    if (!native) {
      state.error = uiText("Voice conversations require the reception tablet. No microphone has been opened.");
      page("error");
      return;
    }
    silence();
    if (!restart) clearGuest();
    state.pendingPage = "";
    state.micLevel = 0;
    state.sessionId = ((_b = (_a = window.crypto) == null ? void 0 : _a.randomUUID) == null ? void 0 : _b.call(_a)) || "xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx".replace(/[xy]/g, (character) => {
      var _a2;
      const value = ((_a2 = window.crypto) == null ? void 0 : _a2.getRandomValues) ? window.crypto.getRandomValues(new Uint8Array(1))[0] & 15 : Math.floor(Math.random() * 16);
      return (character === "x" ? value : value & 3 | 8).toString(16);
    });
    state.generation = null;
    state.closed = false;
    state.terminated = false;
    state.active = true;
    state.phase = "connecting";
    state.error = "";
    page("active");
    send("start", { session_id: state.sessionId, mode: state.mode, language: state.language, staff_language: state.staff_language, greet: greet(state.language) });
  }
  function silence() {
    var _a;
    (_a = window.speechSynthesis) == null ? void 0 : _a.cancel();
    send("silence");
    state.micLevel = 0;
  }
  function chooseShortcut(name) {
    if (!state.active || state.closed || state.mode !== "reception" || state.page !== "active" || ["connecting", "greeting"].includes(state.phase)) return;
    if (state.pendingShortcut) return;
    if (serviceReason(name)) {
      toast(serviceReason(name));
      return;
    }
    state.requestedAction = name;
    if (state.demo) {
      addNotice(uiText("Hotel requests require a connected reception tablet."), "unavailable");
      return;
    }
    state.pendingShortcut = { action: name, arguments: {}, pending: true, reconciliationPending: false };
    send(name, { arguments: state.pendingShortcut.arguments });
  }
  function finishNavigation() {
    if (!state.pendingPage || !state.terminated || state.active || state.audio !== "off") return false;
    const next = state.pendingPage;
    state.pendingPage = "";
    queuedShortcut = "";
    if (next === "idle") clearGuest();
    page(next);
    return true;
  }
  function stop(next = "") {
    if (next) state.pendingPage = next;
    if (!state.active) {
      finishNavigation();
      return;
    }
    if (state.phase === "ending") return;
    silence();
    state.closed = true;
    state.draft = null;
    state.confirmed = false;
    state.operation = null;
    state.outcome = null;
    state.pendingShortcut = null;
    queuedShortcut = "";
    state.phase = "ending";
    if (state.audio === "recording") state.audio = "stopping";
    page("active");
    send("stop");
  }
  function goBack() {
    if (state.page === "idle") return;
    if (state.active || state.audio !== "off") {
      stop(state.mode === "translation" ? "translation_languages" : "languages");
      return;
    }
    queuedShortcut = "";
    page(state.page === "active" ? state.mode === "translation" ? "translation_languages" : "languages" : "idle");
  }
  let toastTimer;
  function toast(message) {
    const target = document.getElementById("toast");
    target.textContent = message;
    target.hidden = false;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => {
      target.hidden = true;
    }, 5500);
  }
  function perform(name) {
    var _a, _b, _c, _d, _e;
    activity();
    if (name.startsWith("request:")) {
      const request = name.split(":")[1];
      if (state.page !== "idle" || !["checkin", "taxi", "message"].includes(request)) return;
      if (serviceReason(request)) {
        toast(serviceReason(request));
        return;
      }
      chooseMode("reception");
      if (state.page === "languages") queuedShortcut = request;
      return;
    }
    if (name.startsWith("pair:")) {
      const [, side, language] = name.split(":");
      if (state.page !== "translation_languages" || state.audio !== "off" || !["language", "staff_language"].includes(side) || !hasOwn(translationLanguages, language)) return;
      selectLanguage(side, language);
      languageSide = side;
      render();
      return;
    }
    if (name.startsWith("language:")) {
      const language = name.split(":")[1];
      if (state.page !== "languages" || !languages[language]) return;
      selectLanguage("language", language);
      start();
      return;
    }
    switch (name) {
      case "appearance":
        toggleAppearance();
        break;
      case "translation":
        chooseMode("translation");
        break;
      case "reception":
        chooseMode("reception");
        break;
      case "assist-go": {
        const language = (_a = document.getElementById("assist-language")) == null ? void 0 : _a.value;
        if (state.page !== "languages" || !languages[language]) return;
        selectLanguage("language", language);
        start();
        break;
      }
      case "start":
        start();
        break;
      case "setup-translation":
        if (native && !state.active && state.audio === "off") send("voice_setup");
        break;
      case "mute":
        if (state.mode === "translation" && state.active && !state.closed && ["recording", "muted"].includes(state.audio)) {
          activity();
          send("mute", { muted: state.audio !== "muted" });
        }
        break;
      case "restart":
        if (state.terminated && state.audio === "off") start(true);
        break;
      case "stop":
        stop();
        break;
      case "back":
        goBack();
        break;
      case "home":
        if (state.active || state.audio !== "off") stop("idle");
        else {
          clearGuest();
          page("idle");
        }
        break;
      case "retry":
        if (state.mode === "reception") start();
        else page("translation_languages");
        break;
      case "checkin":
      case "operator":
      case "taxi":
      case "question":
        chooseShortcut(name);
        break;
      case "message":
        chooseShortcut("message");
        break;
      case "retry-shortcut": {
        const attempt = state.pendingShortcut;
        if (state.page !== "saved" || !state.active || state.closed || !(attempt == null ? void 0 : attempt.reconciliationPending) || attempt.pending) return;
        attempt.pending = true;
        send(attempt.action, { arguments: attempt.arguments });
        render();
        break;
      }
      case "receipt-help":
        if (state.page !== "saved" || ((_b = state.outcome) == null ? void 0 : _b.helpAction) !== "operator" || !state.active || state.closed) return;
        if (serviceReason("operator")) {
          toast(serviceReason("operator"));
          return;
        }
        state.outcome = null;
        page("active");
        chooseShortcut("operator");
        break;
      case "cancel-operation":
        if (!((_c = state.operation) == null ? void 0 : _c.pending) && !((_d = state.operation) == null ? void 0 : _d.reconciliationPending)) {
          state.operation = null;
          page("active");
        }
        break;
      case "edit-draft":
        if (!state.active || state.closed || state.confirmed || !state.operation || !state.draft || state.draft.attempted) return;
        state.draft = null;
        state.operation.pending = false;
        page("operation_form");
        break;
      case "confirm":
      case "retry-draft":
      case "cancel-draft":
        if (!state.active || state.closed || !state.draft || state.confirmed) return;
        if (name === "retry-draft" && !state.draft.attempted) return;
        if (name === "confirm" && !state.draft.attempted && confirmationReason()) {
          toast(confirmationReason());
          return;
        }
        state.confirmed = true;
        if (name !== "retry-draft") state.draft.approved = name === "confirm";
        state.draft.attempted = true;
        send("confirm", { draft_id: state.draft.draft_id, approved: state.draft.approved });
        render();
        break;
      case "continue":
        if ((_e = state.pendingShortcut) == null ? void 0 : _e.reconciliationPending) return;
        state.outcome = null;
        page(state.active && !state.closed ? "active" : "idle");
        break;
    }
  }
  content.addEventListener("click", (event) => {
    const button = event.target.closest("[data-action]");
    if (button && !button.disabled) perform(button.dataset.action);
  });
  content.addEventListener("focusin", (event) => {
    const side = event.target.dataset.languageSide;
    if (!["language", "staff_language"].includes(side)) return;
    languageSide = side;
    content.querySelectorAll(".language-side").forEach((element) => {
      element.dataset.keypad = String(element.querySelector("select").dataset.languageSide === side);
    });
  });
  content.addEventListener("change", (event) => {
    const side = event.target.dataset.languageSide;
    if (side) perform("pair:".concat(side, ":").concat(event.target.value));
    else if (event.target.id === "assist-language" || event.target.id === "guest-locale") {
      selectLanguage("language", event.target.value);
      render();
    }
  });
  content.addEventListener("input", (event) => {
    const input = event.target;
    if (state.operation && input.closest("#operation-form")) {
      state.operation.values[input.name] = input.value;
      input.setCustomValidity("");
    }
    activity();
  });
  content.addEventListener("submit", (event) => {
    if (event.target.id !== "operation-form") return;
    event.preventDefault();
    if (!state.active || state.closed || !state.operation || state.operation.pending || reviewBlocked()) return;
    for (const input of event.target.querySelectorAll("[required]")) input.setCustomValidity(input.value.trim() ? "" : uiText("Please complete this field."));
    if (!event.target.reportValidity()) return;
    activity();
    const operation = state.operation;
    operation.pending = true;
    send(operation.action, { arguments: { ...operation.values } });
    render();
  });
  document.getElementById("notices").addEventListener("click", (event) => {
    const button = event.target.closest("[data-dismiss]");
    if (!button) return;
    state.notices.splice(Number(button.dataset.dismiss), 1);
    activity();
    renderNotices();
  });
  document.getElementById("night-toggle").addEventListener("click", toggleNight);
  document.getElementById("settings").addEventListener("click", () => send(state.mode === "translation" ? "voice_setup" : "settings"));
  document.getElementById("check-updates").addEventListener("click", () => {
    if (!isHotel()) send("check_updates");
  });
  function hardware(key) {
    activity();
    if (state.page === "translation_languages" && /^[0-9]$/.test(key)) {
      perform("pair:" + languageSide + ":" + Object.keys(translationLanguages)[key === "0" ? 9 : Number(key) - 1]);
      return;
    }
    if (state.page === "languages" && /^[0-9]$/.test(key)) {
      perform("language:" + Object.keys(languages)[key === "0" ? 9 : Number(key) - 1]);
      return;
    }
    if (key === "back" || key === "red" || key === "0") {
      goBack();
      return;
    }
    if (key === "green") {
      if (state.active) stop();
      else if (state.page === "idle") chooseMode(state.night ? "reception" : "translation");
      else if (state.page === "translation_languages") start();
      else if (state.page === "active" && state.terminated) start(true);
      return;
    }
    if (state.page === "idle" && key === "1") toggleAppearance();
    else if (state.page === "idle" && key === "2") chooseMode("reception");
    else if (state.page === "active" && state.mode === "reception" && /^[1-4]$/.test(key)) chooseShortcut(shortcuts[Number(key) - 1]);
  }
  function offerUpdate(version, text, phase, percent) {
    if (isHotel()) return;
    let bar = document.getElementById("update-offer");
    if (!bar) {
      bar = document.createElement("button");
      bar.id = "update-offer";
      bar.type = "button";
      bar.className = "update-offer";
      bar.addEventListener("click", () => {
        if (["checking", "progress", "installing"].includes(bar.dataset.phase)) return;
        const action2 = bar.dataset.phase === "current" ? "check_updates" : "apply_update";
        bar.dataset.phase = action2 === "check_updates" ? "checking" : "installing";
        send(action2);
      });
      document.body.appendChild(bar);
    }
    phase = phase || "ready";
    bar.dataset.phase = phase;
    if (phase === "installed") {
      bar.hidden = true;
      return;
    }
    bar.hidden = false;
    if (["checking", "progress", "installing"].includes(phase)) {
      bar.disabled = true;
      const pct = Number(percent);
      const known = Number.isFinite(pct) && pct >= 0;
      bar.innerHTML = "".concat(esc(text || uiText("Downloading…")), '<span class="track"><span class="fill" style="width:').concat(known ? Math.min(100, pct) : 0, '%"></span></span>');
      return;
    }
    bar.disabled = false;
    if (phase === "current") bar.textContent = text || uiText("Up to date. Tap to check again.");
    else if (phase === "confirm") bar.textContent = text || uiText("Tap to open the installer. The file is already downloaded.");
    else if (phase === "failed") bar.textContent = text || uiText("Update did not finish. Tap to try the installer again.");
    else bar.textContent = text || uiText("Update {version} is downloaded. Tap to install.", { version });
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
    state.phase = preserveHistory ? "stopped" : "";
    state.micLevel = 0;
    state.draft = null;
    state.confirmed = false;
    state.operation = null;
    state.outcome = null;
    state.pendingShortcut = null;
    queuedShortcut = "";
    if (state.audio !== "off") state.audio = "unknown";
    if (state.audio === "off") send("languages", { language: state.language, staff_language: state.staff_language });
    if (!preserveHistory) {
      clearGuest();
      state.pendingPage = "";
    }
  }
  function receive(event) {
    var _a, _b, _c, _d, _e, _f, _g, _h;
    if (typeof event === "string") {
      try {
        event = JSON.parse(event);
      } catch (e) {
        return;
      }
    }
    if (!event || typeof event !== "object") return;
    const type = event.type;
    if (type === "translation_setup_return") {
      if (!state.active && state.audio === "off") {
        state.mode = "translation";
        page("translation_languages");
      }
      return;
    }
    if (type === "navigation" && event.action === "back") {
      if (state.active && !matchesSession(event)) return;
      goBack();
      return;
    }
    if (type === "appearance") {
      applyAppearance(event.value);
      return;
    }
    if (type === "visibility") {
      if (event.visible === false) {
        detachGuest();
        state.error = "";
        page("idle");
      }
      return;
    }
    if (type === "night_mode") {
      if (typeof event.enabled !== "boolean" || state.night === event.enabled) return;
      state.night = event.enabled;
      if (state.page === "idle") state.mode = state.night ? "reception" : "translation";
      render();
      return;
    }
    if (type === "connection") {
      state.connectionState = ["unconfigured", "checking", "ready", "offline", "unauthorized"].includes(event.state) ? event.state : "offline";
      state.connected = state.connectionState === "ready" && event.connected === true;
      if (!state.connected && state.connectionState === "ready") state.connectionState = "offline";
      render();
      return;
    }
    if (type === "reception_info") {
      state.distribution = event.distribution === "hotel" ? "hotel" : "personal-test";
      state.configured = event.configured === true;
      state.bundledKeyAvailable = event.bundled_key_available === true;
      state.services = null;
      if (event.services_supported !== false && event.services && serviceNames.every((id) => typeof event.services[id] === "boolean")) {
        state.services = {};
        serviceNames.forEach((id) => {
          state.services[id] = event.services[id];
        });
      }
      if (["unconfigured", "checking", "ready", "offline", "unauthorized"].includes(event.state)) {
        state.connectionState = event.state;
        state.connected = event.state === "ready";
      } else if (!state.configured) {
        state.connectionState = "unconfigured";
        state.connected = false;
      }
      state.hotel = String(event.hotel_name || "");
      state.audioAvailable = event.audio_available === true;
      if (!state.active && ["off", "unknown"].includes(event.local_audio_state)) state.audio = event.local_audio_state;
      if (typeof event.audio_gate === "string" && event.audio_gate) state.audioGate = event.audio_gate;
      if (!state.active && !defaultReceived && !preferencesRestored && !selected.staff_language && hasOwn(languages, event.staff_language)) state.staff_language = event.staff_language;
      defaultReceived = true;
      render();
      return;
    }
    if (type === "language_preferences") {
      if (state.active && state.mode === "translation") return;
      for (const side of ["language", "staff_language"]) {
        if (!selected[side]) state[side] = hasOwn(languages, event[side]) ? event[side] : "es";
      }
      preferencesRestored = true;
      render();
      return;
    }
    if (type === "update") {
      offerUpdate(String(event.version || ""));
      return;
    }
    if (type === "update_status") {
      offerUpdate(String(event.version || ""), String(event.text || uiText("Updating…")), String(event.phase || ""), event.percent);
      return;
    }
    if (!matchesSession(event)) return;
    if (type === "audio_state") {
      if (!["off", "recording", "stopping", "unknown", "muted"].includes(event.state)) return;
      if (state.closed && !["off", "unknown"].includes(event.state)) return;
      state.audio = event.state;
      resetMicLevels();
      if (!finishNavigation()) {
        if (state.page === "active" && state.mode === "translation" && !state.terminated) {
          const privacy = document.getElementById("privacy");
          if (privacy) privacy.innerHTML = '<span class="mic-dot'.concat(state.audio === "recording" ? " mic-live" : "", '"></span>').concat(esc(sessionAudioText()));
          syncMic();
        } else {
          render();
        }
      }
      return;
    }
    if (state.terminated) return;
    const terminal = type === "reception_event" && ["stopped", "timeout", "error"].includes(event.phase);
    if (state.closed && !terminal) return;
    if (type === "input_activity") {
      activity();
      return;
    }
    if (type === "mic_level") {
      const level = state.audio === "recording" ? Math.min(1, Math.max(0, Number(event.level) || 0)) : 0;
      state.micLevel = level;
      if (state.audio === "recording" && Array.isArray(event.bands) && event.bands.length >= 3) state.bands = event.bands.slice(0, 3).map((value) => Math.min(1, Math.max(0, Number(value) || 0)));
      else state.bands = [level, level * 0.72, level * 0.4];
      const meter = content.querySelector(".voice-ball");
      meter == null ? void 0 : meter.style.setProperty("--level", String(level));
      (state.bands || []).forEach((band, index) => meter == null ? void 0 : meter.style.setProperty("--b" + index, String(band)));
      return;
    }
    if (type === "notice") {
      addNotice(event.text, event.status);
      if (["error", "failed", "unavailable", "denied", "save_failed"].includes(event.status)) {
        state.confirmed = false;
        if (state.operation) state.operation.pending = false;
        render();
      }
      return;
    }
    if (type === "metrics") {
      state.metrics = String(event.text || "");
      if (state.page === "active") render();
      return;
    }
    if (type === "interpretation") {
      if (state.mode === "translation") receiveTranslationCaption(event);
      return;
    }
    if (type === "caption") {
      if (typeof event.utterance_id !== "string" || !event.utterance_id || !["human", "guest", "staff", "assistant"].includes(event.side) || !["original", "text"].includes(event.field) || !["append", "replace"].includes(event.operation) || typeof event.text !== "string" || typeof event.final !== "boolean") return;
      if (state.mode === "translation") {
        receiveTranslationCaption(event);
        return;
      }
      const unlinked = event.association === "unlinked";
      const key = JSON.stringify([state.sessionId, event.utterance_id, unlinked ? event.field : null]);
      let entry = state.utterances.get(key);
      if (!entry) {
        const side = event.side === "assistant" ? "assistant" : unlinked && event.speaker_attribution !== "selected" ? "human" : event.side;
        entry = { id: key, side, association: unlinked ? "unlinked" : "linked", speakerAttribution: side !== "assistant" && event.speaker_attribution === "selected" ? "selected" : "unknown", from: "", to: "", original: "", text: "", final: { original: false, text: false } };
        state.utterances.set(key, entry);
      }
      if (entry.final[event.field] && (!event.final || event.operation === "append")) return;
      entry[event.field] = event.operation === "replace" ? event.text : entry[event.field] + event.text;
      entry.final[event.field] = event.final;
      if (typeof event.from === "string" && event.from) entry.from = event.from;
      if (typeof event.to === "string" && event.to) entry.to = event.to;
      updateCaption(entry);
      if (event.side !== "assistant" && event.text.trim()) activity();
      return;
    }
    if (type === "operation_form") {
      if (![...shortcuts, "message"].includes(event.action) || !Array.isArray(event.fields) || state.draft) return;
      const fields = event.fields.filter((field) => field && typeof field.name === "string" && field.name && typeof field.label === "string");
      if (!fields.length) {
        addNotice(uiText("This request did not provide usable fields. Please ask reception for help."), "unavailable");
        return;
      }
      const previous = ((_a = state.operation) == null ? void 0 : _a.action) === event.action ? state.operation : null;
      const allFields = new Map(((previous == null ? void 0 : previous.fields) || []).map((field) => [field.name, field]));
      fields.forEach((field) => allFields.set(field.name, field));
      const emptyValues = /* @__PURE__ */ Object.create(null);
      for (const name of allFields.keys()) emptyValues[name] = "";
      state.operation = { action: event.action, fields: [...allFields.values()], values: { ...emptyValues, ...previous == null ? void 0 : previous.values }, pending: false };
      state.pendingShortcut = null;
      page("operation_form");
      return;
    }
    if (type === "operation_result") {
      const text = String(event.text || "");
      if (["confirmation_required", "needs_confirmation", "fields_required"].includes(event.status)) return;
      if (["unavailable", "error", "pending"].includes(event.status) && ((_b = event.result) == null ? void 0 : _b.help_action) !== "operator" && (state.operation || state.draft)) {
        state.confirmed = false;
        if (state.operation) {
          state.operation.pending = false;
          state.operation.reconciliationPending = event.reconciliation_pending === true || state.operation.reconciliationPending === true;
        }
        addNotice(text || uiText("No receipt was received. Retry or ask reception to check."), event.status);
        page(state.draft ? "confirming" : "operation_form");
        return;
      }
      if (["unavailable", "error", "pending"].includes(event.status) && state.pendingShortcut && (event.reconciliation_pending === true || event.status === "pending" || state.pendingShortcut.reconciliationPending)) {
        state.pendingShortcut.pending = false;
        state.pendingShortcut.reconciliationPending = true;
        state.outcome = { text: text || uiText("No receipt yet. Retry the same request to check its result."), receipt: "", helpAction: "" };
        page("saved");
        return;
      }
      if (state.draft && !state.confirmed) {
        addNotice(text, event.status);
        return;
      }
      state.pendingShortcut = null;
      const receipt = ((_c = event.result) == null ? void 0 : _c.item_id) || ((_d = event.result) == null ? void 0 : _d.request_id) || ((_e = event.result) == null ? void 0 : _e.escalation_id);
      state.outcome = { text: text || uiText("No receipt returned. Ask staff to check."), receipt: typeof receipt === "string" ? uiText("Receipt: {id}", { id: receipt }) : "", helpAction: ((_f = event.result) == null ? void 0 : _f.help_action) === "operator" ? "operator" : "" };
      state.draft = null;
      state.confirmed = false;
      state.operation = null;
      page("saved");
      return;
    }
    if (type !== "reception_event") return;
    const phase = event.phase;
    if (terminal) {
      const error = phase === "error" ? String(event.text || event.message || uiText("Please ask reception to check the connection and device setup.")) : "";
      detachGuest(true);
      state.error = error;
      if (!finishNavigation()) page("active");
      if (error) {
        state.notices.push({ status: "error", text: error });
        renderNotices();
      }
      if (phase === "timeout") toast(uiText("Conversation ended after inactivity."));
      return;
    }
    if (phase === "ending") {
      state.closed = true;
      state.micLevel = 0;
      state.draft = null;
      state.operation = null;
      state.phase = "ending";
      if (state.audio === "recording" || state.audio === "muted") state.audio = "stopping";
      page("active");
      return;
    }
    if (phase === "confirming") {
      if (typeof event.draft_id !== "string" || !event.draft_id || ((_g = state.draft) == null ? void 0 : _g.draft_id) === event.draft_id) return;
      state.draft = { draft_id: event.draft_id, guest_name: String(event.guest_name || ""), room_reference: String(event.room_reference || ""), message: String(event.message || "") };
      state.pendingShortcut = null;
      state.confirmed = false;
      if (state.operation) state.operation.pending = false;
      page("confirming");
      return;
    }
    if (!["connecting", "ready", "greeting", "listening", "thinking", "speaking"].includes(phase)) return;
    state.phase = phase;
    if (state.mode === "translation" && state.page === "active") {
      syncMic();
      return;
    }
    if (state.page === "confirming" && ((_h = state.draft) == null ? void 0 : _h.draft_id.startsWith("voice:")) && state.confirmed && phase === "listening") {
      state.draft = null;
      state.confirmed = false;
      state.page = "active";
    }
    if (["confirming", "saved", "operation_form"].includes(state.page)) return;
    page("active");
    if (["ready", "listening"].includes(phase) && queuedShortcut) {
      const request = queuedShortcut;
      queuedShortcut = "";
      if (request === "message") perform("message");
      else chooseShortcut(request);
    }
  }
  window.manzania = Object.freeze({ receive });
  document.addEventListener("keydown", (event) => {
    if (event.key === "Escape") {
      event.preventDefault();
      hardware("back");
      return;
    }
    if (event.target.matches("input,textarea")) return;
    if (/^[0-9]$/.test(event.key)) {
      event.preventDefault();
      hardware(event.key);
    }
  });
  document.addEventListener("pointerdown", activity, { passive: true });
  document.addEventListener("wheel", activity, { passive: true });
  function hideGuest() {
    silence();
    if (state.active) send("stop");
    detachGuest();
    state.error = "";
    page("idle");
  }
  document.addEventListener("visibilitychange", () => {
    if (document.hidden) hideGuest();
  });
  window.addEventListener("pagehide", hideGuest);
  function updateClock() {
    const now = /* @__PURE__ */ new Date();
    document.getElementById("date").textContent = new Intl.DateTimeFormat(state.language, { weekday: "short", day: "numeric", month: "short" }).format(now);
    document.getElementById("time").textContent = new Intl.DateTimeFormat(state.language, { hour: "2-digit", minute: "2-digit" }).format(now);
  }
  updateClock();
  setInterval(updateClock, 6e4);
  render();
  send("info");
})();
