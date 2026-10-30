import { Room, RoomEvent, LocalAudioTrack, Track, setLogLevel, LogLevel } from 'livekit-client';

// SDK diagnostics may contain transport URLs. Surface only bounded product errors.
setLogLevel(LogLevel.silent);
const STORAGE_KEY = 'manzanilla.web.pending-request';
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const CAPTURE = { echoCancellation: true, noiseSuppression: true, autoGainControl: true };
const CLEANUP_MESSAGE = 'Microphone and audio are off. Session cleanup is unconfirmed. Retry cleanup before starting again.';

class RequestError extends Error {
  constructor(message, notCreated = false) {
    super(message);
    this.notCreated = notCreated;
  }
}

// Never expose network exceptions, server payloads, or transport credentials.
async function request(path, body, { keepalive = false, timeout = 45000 } = {}) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeout);
  try {
    const response = await fetch(path, {
      method: body === undefined ? 'GET' : 'POST',
      credentials: 'same-origin', cache: 'no-store', redirect: 'error',
      headers: body === undefined ? {} : { 'Content-Type': 'application/json', 'X-Manzanilla-Web': '1' },
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: controller.signal, keepalive,
    });
    const notCreated = response.headers.get('X-Manzanilla-Session-Creation') === 'not-created';
    if (!response.ok) {
      const message = path.endsWith('/end') ? CLEANUP_MESSAGE
        : path.endsWith('/config') ? 'Settings could not be saved. Check both API keys and the Fish voice ID, and finish any pending cleanup.'
        : path.endsWith('/session') ? 'Translation could not start. Check the server-held Fish and Gemini keys, voice ID, provider access and session allowance in Settings.'
        : 'The local translation server is unavailable. Check that it is running, then try again.';
      throw new RequestError(message, notCreated);
    }
    return await response.json();
  } catch (error) {
    if (error instanceof RequestError) throw error;
    throw new RequestError(path.endsWith('/end') ? CLEANUP_MESSAGE : 'The local server did not complete the request. Check that it is running and try again.');
  } finally {
    clearTimeout(timer);
  }
}

export class Translator extends EventTarget {
  constructor() {
    super();
    this.run = null;
    this.pendingId = null;
    this.stopFlight = null;
    this.configuring = false;
    this.phase = 'idle';
    this.message = 'Choose two languages, then start translation.';
    try {
      const saved = localStorage.getItem(STORAGE_KEY);
      if (saved && UUID.test(saved)) this.pendingId = saved;
    } catch (_) { /* Start explicitly rejects unavailable durable storage. */ }
    if (this.pendingId) {
      this.phase = 'cleanup';
      this.message = CLEANUP_MESSAGE;
    }
    window.addEventListener('pagehide', () => {
      const run = this.run;
      if (run) this.release(run);
      this.run = null;
      const id = this.pendingId;
      if (id) {
        // Delivery is best effort; persisted exact ownership survives navigation.
        void request(`/api/web/session/${id}/end`, {}, { keepalive: true }).catch(() => {});
      }
      this.emitState(id ? 'cleanup' : 'idle', id ? CLEANUP_MESSAGE : 'Microphone and audio are off.');
    });
    window.addEventListener('pageshow', event => {
      if (event.persisted) void this.status().catch(() => {
        this.emitState(this.pendingId ? 'cleanup' : 'error', this.pendingId ? CLEANUP_MESSAGE : 'Reconnect to the local server before starting.');
      });
    });
  }

  emitState(phase = this.phase, message = this.message) {
    this.phase = phase;
    this.message = message;
    const run = this.run;
    const track = run?.media;
    const muted = Boolean(run && (run.desiredMuted || run.local?.isMuted || (track && !track.enabled)));
    const microphone = Boolean(track && track.readyState === 'live' && track.enabled && !track.muted && !muted);
    this.dispatchEvent(new CustomEvent('state', { detail: {
      phase, message, microphone,
      muted, audioBlocked: Boolean(run?.audioBlocked), requestId: this.pendingId,
    } }));
    if (!microphone) this.emitLevel(0, Boolean(run && this.active(run) && run.meter?.context.state === 'running'));
  }

  notice(message) {
    this.dispatchEvent(new CustomEvent('notice', { detail: { message } }));
  }

  active(run) { return this.run === run && !run.cancelled; }

  emitLevel(value, available) {
    this.dispatchEvent(new CustomEvent('level', { detail: { value, available } }));
  }

  async startPlayback(run) {
    const AudioContext = window.AudioContext || window.webkitAudioContext;
    if (!AudioContext || !window.AudioWorkletNode) throw new Error();
    const context = new AudioContext({ sampleRate: 24000 });
    run.playback = { context, node: null };
    if (context.sampleRate !== 24000 || !context.audioWorklet) throw new Error();
    // Called synchronously from Start, before permission/network awaits. Resume
    // must retain the real user gesture; addModule itself is asynchronous.
    void context.resume().catch(() => {});
    context.onstatechange = () => {
      if (!this.active(run)) return;
      run.audioBlocked = context.state !== 'running';
      this.emitState();
    };
    await context.audioWorklet.addModule('/playback-worklet.js');
    if (!this.active(run)) return;
    const node = new window.AudioWorkletNode(context, 'translation-playback', {
      numberOfInputs: 0, numberOfOutputs: 1, outputChannelCount: [1],
    });
    run.playback.node = node;
    node.connect(context.destination);
    node.onprocessorerror = () => this.playbackFailed(run);
    node.port.onmessage = ({ data }) => {
      if (!this.active(run) || data.generation !== run.outputGeneration || run.outputClosed) return;
      if (data.type === 'error') { this.playbackFailed(run); return; }
      if (data.type !== 'played' || !Number.isInteger(data.sequence) ||
          data.sequence <= run.playedSequence || data.sequence >= run.nextSequence) return;
      run.playedSequence = data.sequence;
      if (data.eof === true && data.sequence === run.eofSequence) run.eofPlayed = true;
      void run.room.localParticipant.publishData(new TextEncoder().encode(JSON.stringify({
        type: 'translation.played', output_generation: data.generation, sequence: data.sequence,
      })), { reliable: true, topic: 'client-event' }).catch(() => {
        if (this.active(run) && data.generation === run.outputGeneration && !run.outputClosed) this.playbackFailed(run);
      });
    };
    if (!this.active(run)) return;
    run.audioBlocked = context.state !== 'running';
    this.emitState();
  }

  playbackFailed(run) {
    if (!this.active(run) || run.outputClosed) return;
    this.output(run, run.outputGeneration, 'error');
    run.failedGeneration = run.outputGeneration;
    this.settleRecords(run, 'error', run.outputGeneration);
    this.notice('Translated audio could not be played. Unplayed audio is not confirmed; recognized speech and translation remain visible.');
  }

  audioPacket(run, payload) {
    if (!this.active(run) || run.outputClosed) return;
    if (!(payload instanceof Uint8Array) || payload.byteLength < 16) { this.playbackFailed(run); return; }
    const header = new DataView(payload.buffer, payload.byteOffset, payload.byteLength);
    const generation = header.getUint32(4);
    if (generation > 0 && generation < run.outputGeneration) return;
    if (generation !== run.outputGeneration || header.getUint32(0) !== 0x4d5a5031 ||
        header.getUint32(12) !== 24000 || payload.byteLength > 1936 || payload.byteLength % 2 ||
        header.getUint32(8) !== run.nextSequence || run.eofSequence !== null ||
        run.nextSequence - run.playedSequence > 64) { this.playbackFailed(run); return; }
    if (payload.byteLength === 16) run.eofSequence = run.nextSequence;
    run.nextSequence++;
    // Transfer only this packet, not an SDK-owned backing store shared with
    // other messages. The worklet owns the single bounded PCM ring.
    const bytes = payload.slice();
    run.playback.node.port.postMessage({ type: 'pcm', generation, bytes }, [bytes.buffer]);
  }

  settleRecords(run, playback, generation = null) {
    for (const [id, previous] of run.records) {
      if (generation !== null && previous.output_generation !== generation) continue;
      const pendingTranslation = ['waiting', 'needs_direction', 'streaming'].includes(previous.translation_status);
      const pendingPlayback = ['queued', 'playing'].includes(previous.playback_status);
      if (!pendingTranslation && !pendingPlayback) continue;
      const record = { ...previous, revision: previous.revision + 1,
        translation_status: pendingTranslation ? (playback === 'error' ? 'error' : 'cancelled') : previous.translation_status,
        playback_status: pendingPlayback ? playback : previous.playback_status };
      run.records.set(id, record);
      this.dispatchEvent(new CustomEvent('utterance', { detail: record }));
    }
    if (run.welcome && !run.welcomeClosed && (generation === null || run.welcomeGeneration === generation)) {
      run.welcomeClosed = true;
      run.welcome = { ...run.welcome, status: playback };
      this.dispatchEvent(new CustomEvent('welcome', { detail: run.welcome }));
    }
  }

  startMeter(run) {
    // Observe only the already-permitted microphone. Never connect to destination:
    // a level meter must not play the guest's own voice back through the speakers.
    const AudioContext = window.AudioContext || window.webkitAudioContext;
    if (!AudioContext) { this.emitLevel(0, false); return; }
    try {
      const context = new AudioContext();
      const meter = { context, source: null, analyser: null, timer: null };
      run.meter = meter;
      meter.source = context.createMediaStreamSource(run.stream);
      meter.analyser = context.createAnalyser();
      meter.analyser.fftSize = 1024;
      meter.source.connect(meter.analyser);
      const samples = new Float32Array(meter.analyser.fftSize);
      const sample = () => {
        if (!this.active(run) || run.meter !== meter) return;
        if (context.state !== 'running') { this.emitLevel(0, false); return; }
        if (run.media.readyState !== 'live' || !run.media.enabled || run.media.muted || run.desiredMuted || run.local?.isMuted) {
          this.emitLevel(0, true);
          return;
        }
        try {
          meter.analyser.getFloatTimeDomainData(samples);
          let energy = 0;
          for (const value of samples) energy += value * value;
          const rms = Math.sqrt(energy / samples.length);
          this.emitLevel(Number.isFinite(rms) ? Math.min(1, rms) : 0, Number.isFinite(rms));
        } catch (_) {
          this.stopMeter(run);
          this.emitLevel(0, false);
        }
      };
      context.onstatechange = sample;
      meter.timer = setInterval(sample, 100);
      sample();
      if (context.state === 'suspended') void context.resume().then(sample).catch(() => {
        if (this.active(run) && run.meter === meter) this.emitLevel(0, false);
      });
    } catch (_) {
      this.stopMeter(run);
      this.emitLevel(0, false);
    }
  }

  stopMeter(run) {
    const meter = run.meter;
    if (!meter) return;
    run.meter = null;
    clearInterval(meter.timer);
    meter.context.onstatechange = null;
    meter.source?.disconnect();
    meter.analyser?.disconnect();
    void meter.context.close().catch(() => {});
  }

  persist(id) {
    try {
      localStorage.setItem(STORAGE_KEY, id);
      if (localStorage.getItem(STORAGE_KEY) !== id) throw new Error();
    } catch (_) {
      throw new Error('Browser storage is unavailable. Enable local site storage before starting so session cleanup can survive a reload.');
    }
    this.pendingId = id;
  }

  clearPending(id) {
    if (this.pendingId !== id) return;
    this.pendingId = null;
    try {
      if (localStorage.getItem(STORAGE_KEY) === id) localStorage.removeItem(STORAGE_KEY);
    } catch (_) { /* Server confirmation remains authoritative; retry is idempotent. */ }
  }

  async status() {
    const owner = this.run;
    const pending = this.pendingId;
    const value = await request('/api/web/status');
    if (!value || typeof value.configured !== 'boolean' || !Array.isArray(value.languages) ||
        (value.pending_request_id != null && !UUID.test(value.pending_request_id))) {
      throw new Error('The local server returned an invalid status. Restart it before using translation.');
    }
    // A slow status response cannot replace an ownership decision made by Start/Stop.
    if (this.run === owner && this.pendingId === pending && !this.run && !this.stopFlight) {
      if (value.pending_request_id) this.pendingId = value.pending_request_id;
      if (this.pendingId) {
        try { localStorage.setItem(STORAGE_KEY, this.pendingId); } catch (_) { /* Server also retains it. */ }
        this.emitState('cleanup', CLEANUP_MESSAGE);
        if (!value.pending_request_id) {
          // Absence alone cannot settle a delayed Start. End tombstones the UUID.
          try { await this.stop(); } catch (_) { /* Keep the explicit Retry cleanup state. */ }
        }
      } else this.emitState();
    }
    return { ...value, pending_request_id: this.pendingId || value.pending_request_id };
  }

  async configure({ api_key, translation_api_key, voice_id }) {
    if (this.run || this.pendingId || this.stopFlight || this.configuring) {
      throw new Error('Stop translation and finish cleanup before changing settings.');
    }
    this.configuring = true;
    try { return await request('/api/web/config', { api_key, translation_api_key, voice_id }); }
    finally { this.configuring = false; }
  }

  async start({ guest_language, staff_language }) {
    if (this.run || this.pendingId || this.stopFlight || this.configuring) {
      throw new Error('Translation is already starting or needs cleanup. Stop it or retry cleanup first.');
    }
    if (typeof guest_language !== 'string' || typeof staff_language !== 'string' ||
        !/^[a-z]{2,3}$/.test(guest_language) || !/^[a-z]{2,3}$/.test(staff_language)) {
      throw new Error('Choose a Guest language and Receptionist language.');
    }
    if (!navigator.mediaDevices?.getUserMedia || !crypto.randomUUID) {
      throw new Error('Microphone capture requires a supported browser on localhost or a secure origin.');
    }
    const run = {
      guest: guest_language, staff: staff_language, cancelled: false, media: null, stream: null,
      local: null, room: null, records: new Map(), playback: null,
      outputGeneration: 0, outputClosed: true, nextSequence: 0, playedSequence: -1,
      eofSequence: null, eofPlayed: false, ready: false, sessionId: null,
      desiredMuted: false, audioBlocked: false, muteQueue: Promise.resolve(), timer: null, meter: null,
    };
    this.run = run;
    this.emitState('permission', 'Allow microphone access to start translation. No provider session has started.');
    let failure = 'Translated audio requires a browser with 24 kHz AudioWorklet playback. Check browser audio support and start again.';
    try {
      if (!this.active(run)) return;
      // Start playback unlock and permission within the same deliberate gesture.
      const playback = this.startPlayback(run).catch(error => error);
      const capture = navigator.mediaDevices.getUserMedia({ audio: CAPTURE, video: false });
      let stream;
      try { stream = await capture; }
      catch (error) {
        failure = 'Microphone access was not granted. Allow it in browser settings, then try again.';
        throw error;
      }
      if (!this.active(run)) {
        stream.getTracks().forEach(track => track.stop());
        return;
      }
      run.stream = stream;
      const playbackError = await playback;
      if (playbackError) throw playbackError;
      if (!this.active(run)) return;
      failure = 'Microphone access was not granted. Allow it in browser settings, then try again.';
      run.media = stream.getAudioTracks()[0];
      if (!run.media || run.media.readyState !== 'live') throw new Error();
      const echoModes = run.media.getCapabilities?.().echoCancellation;
      if (Array.isArray(echoModes) && echoModes.includes('all')) {
        try { await run.media.applyConstraints({ echoCancellation: { exact: 'all' } }); }
        catch (_) { /* Retain the already-requested browser AEC when all-output mode cannot be applied. */ }
        if (!this.active(run)) return;
      }
      run.media.addEventListener('ended', () => {
        if (this.active(run)) this.terminate(run, 'Microphone capture ended. Check the device and browser permission, then start again.');
      });
      for (const event of ['mute', 'unmute']) run.media.addEventListener(event, () => {
        if (this.active(run)) this.emitState();
      });
      run.local = new LocalAudioTrack(run.media, CAPTURE, true);
      this.startMeter(run);
      if (!this.active(run)) return;
      failure = 'Session ownership could not be saved. Enable browser site storage and try again.';
      run.id = crypto.randomUUID();
      this.persist(run.id);
      this.emitState('connecting', 'Connecting to the translation service.');
      if (!this.active(run)) return;
      failure = 'Translation could not start. Check Fish and Gemini configuration and provider access in Settings.';
      // Do not abort mint on Stop: its late response must settle exact ownership.
      run.mint = request('/api/web/session', { guest_language, staff_language, request_id: run.id });
      let ticket;
      try { ticket = await run.mint; }
      catch (error) {
        if (error.notCreated) this.clearPending(run.id);
        throw error;
      }
      if (!this.active(run)) return;
      failure = 'The service returned an unusable transport. Finish cleanup, then check the server configuration.';
      let url;
      try { url = new URL(ticket.livekit_url); } catch (_) { throw new Error(); }
      const loopback = hostname => ['localhost', '127.0.0.1', '[::1]'].includes(hostname);
      const secureTransport = url.protocol === 'wss:' ||
        (url.protocol === 'ws:' && loopback(url.hostname) && loopback(window.location?.hostname));
      if (ticket.transport !== 'livekit' || !secureTransport || url.username || url.password ||
          typeof ticket.token !== 'string' || !ticket.token || typeof ticket.session_id !== 'string' ||
          !Number.isInteger(ticket.max_duration_seconds) || ticket.max_duration_seconds <= 0 || ticket.max_duration_seconds > 1800 ||
          !Number.isFinite(Date.parse(ticket.expires_at)) || Date.parse(ticket.expires_at) <= Date.now()) throw new Error();
      run.sessionId = ticket.session_id;
      // Bound from ticket receipt, not from a possibly much later successful connect.
      run.timer = setTimeout(() => {
        if (this.active(run)) this.terminate(run, 'The session duration limit was reached. Start a new conversation when ready.', false);
      }, ticket.max_duration_seconds * 1000);
      const room = new Room({ disconnectOnPageLeave: true });
      run.room = room;
      this.bindRoom(run);
      failure = 'Audio connection failed. Check your network, finish cleanup, then start again.';
      await room.connect(ticket.livekit_url, ticket.token, { autoSubscribe: false });
      ticket = null;
      if (!this.active(run)) { void room.disconnect(true).catch(() => {}); return; }
      failure = 'The microphone could not be published. Check microphone access, finish cleanup, then try again.';
      await room.localParticipant.publishTrack(run.local, { source: Track.Source.Microphone });
      if (!this.active(run)) { run.local.stop(); void room.disconnect(true).catch(() => {}); return; }
      this.emitState(run.ready ? 'listening' : 'connecting', run.ready ? 'Translation is listening.' : 'Microphone connected. Waiting for speech recognition to be ready.');
    } catch (error) {
      if (!this.active(run)) return;
      const message = error instanceof RequestError ? error.message : failure;
      this.release(run);
      this.run = null;
      if (this.pendingId) {
        try { await this.stop(); } catch (_) { /* Cleanup state is intentionally retained. */ }
      }
      if (!this.pendingId && !this.run) this.emitState('error', message);
      else this.notice(message);
      throw new Error(message);
    }
  }

  bindRoom(run) {
    const room = run.room;
    const on = (event, handler) => room.on(event, (...args) => {
      if (this.active(run)) handler(...args);
    });
    on(RoomEvent.DataReceived, (payload, participant, _kind, topic) => {
      if (participant?.identity !== `agent-${run.sessionId.slice(0, 40)}`) return;
      if (topic === 'agent-event') this.agentEvent(run, payload);
      else if (topic === 'translation-audio-v1') this.audioPacket(run, payload);
    });
    on(RoomEvent.Reconnecting, () => {
      this.notice('Audio connection interrupted. Recognition may have a gap; previous translations will not be replayed.');
      this.emitState('reconnecting', 'Audio connection interrupted; reconnecting.');
    });
    on(RoomEvent.Reconnected, () => {
      this.emitState(run.ready ? 'listening' : 'connecting', run.ready ? 'Translation reconnected.' : 'Waiting for speech recognition to be ready.');
    });
    on(RoomEvent.Disconnected, () => this.terminate(run, 'Translation disconnected. Check your network, then start a new conversation.'));
  }


  output(run, generation, status) {
    if (!Number.isInteger(generation) || generation < 1 || generation > 0xffffffff || generation < run.outputGeneration) return;
    if (generation === run.outputGeneration && run.outputClosed) return;
    const closed = ['complete', 'interrupted', 'error', 'cancelled'].includes(status);
    if (status === 'complete' && (generation !== run.outputGeneration || !run.eofPlayed)) {
      this.playbackFailed(run);
      return;
    }
    if (generation > run.outputGeneration) {
      run.outputGeneration = generation;
      run.nextSequence = 0;
      run.playedSequence = -1;
      run.eofSequence = null;
      run.eofPlayed = false;
      run.playback.node.port.postMessage({ type: 'begin', generation });
    }
    run.outputClosed = closed;
    // Normal completion is receiver-drained. Keep the graph and context alive;
    // only interruption/error clears samples not yet rendered.
    if (closed && status !== 'complete') run.playback.node.port.postMessage({ type: 'flush', generation });
  }

  agentEvent(run, payload) {
    if (!this.active(run)) return;
    const invalid = () => this.notice('An utterance update was unavailable. Recognition and audio may continue.');
    if (!(payload instanceof Uint8Array) || payload.byteLength > 65536) { invalid(); return; }
    let event;
    try { event = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(payload)); }
    catch (_) { invalid(); return; }
    if (!event || typeof event !== 'object') return;
    if (event.session_id != null && event.session_id !== run.sessionId) return;
    if (event.type === 'error') {
      if (event.recovered === true || event.recoverable === true) this.notice('The voice service reported a temporary interruption. Recognized speech remains visible.');
      else this.terminate(run, 'The translation service failed. Check Fish and Gemini access, then start a new conversation.');
      return;
    }
    if (event.type === 'session.ended') {
      this.terminate(run, event.reason === 'conversation_timeout' ? 'The session duration limit was reached.' : 'The provider ended the conversation.', false);
      return;
    }
    if (event.type === 'translation.utterance') {
      const record = event.record;
      if (!record || record.session_id !== run.sessionId) return;
      const language = value => value === null || (typeof value === 'string' && /^[a-z]{2,3}$/.test(value));
      if (typeof record.utterance_id !== 'string' || !record.utterance_id.trim() || record.utterance_id.length > 200 ||
          !Number.isSafeInteger(record.revision) || record.revision < 1 ||
          typeof record.original !== 'string' || record.original.length > 16000 ||
          typeof record.translation !== 'string' || record.translation.length > 16000 ||
          ![null, 'guest', 'receptionist'].includes(record.role) ||
          !language(record.source_language) || !language(record.target_language) || !language(record.detected_language) ||
          !['partial', 'final', 'degraded'].includes(record.recognition_status) ||
          !['waiting', 'needs_direction', 'streaming', 'complete', 'cancelled', 'error', 'ignored'].includes(record.translation_status) ||
          !['idle', 'queued', 'playing', 'complete', 'interrupted', 'error'].includes(record.playback_status) ||
          !['recognized', 'uncertain', 'mixed', 'mismatch', 'same_language', 'manual'].includes(record.direction_reason) ||
          !Number.isSafeInteger(record.output_generation) || record.output_generation < 0) { invalid(); return; }
      const previous = run.records.get(record.utterance_id);
      if (previous && previous.revision >= record.revision) return;
      if (!previous && run.records.size >= 10000) {
        this.terminate(run, 'The conversation record limit was reached. Start a new conversation.');
        return;
      }
      if (record.output_generation === run.outputGeneration && record.playback_status === 'complete' && !run.eofPlayed) this.playbackFailed(run);
      if (record.output_generation === run.failedGeneration) record.playback_status = 'error';
      run.records.set(record.utterance_id, record);
      if (record.playback_status !== 'idle') this.output(run, record.output_generation, record.playback_status);
      if (record.translation_status === 'cancelled') this.output(run, record.output_generation, 'cancelled');
      this.dispatchEvent(new CustomEvent('utterance', { detail: record }));
      return;
    }
    if (event.session_id !== run.sessionId) return;
    if (event.type === 'translation.ready') {
      if (event.guest_language !== run.guest || event.staff_language !== run.staff) { invalid(); return; }
      run.ready = true;
      this.emitState('listening', 'Translation is listening.');
      if (run.pendingWelcome) {
        const pending = run.pendingWelcome;
        run.pendingWelcome = null;
        this.acceptWelcome(run, pending);
      }
    } else if (event.type === 'translation.output') {
      if (!['playing', 'complete', 'interrupted', 'error'].includes(event.status)) { invalid(); return; }
      this.output(run, event.output_generation, event.status);
    } else if (event.type === 'translation.welcome') {
      if (!this.acceptWelcome(run, event)) invalid();
    }
  }

  acceptWelcome(run, event) {
    if (typeof event.text !== 'string' || event.text.length > 2000 || event.language !== run.guest ||
        !Number.isSafeInteger(event.output_generation) || event.output_generation < 1 ||
        !['queued', 'playing', 'complete', 'interrupted', 'error'].includes(event.status)) return false;
    // LiveKit can deliver the first welcome before the readiness packet. Keep it;
    // do not report that reorder as a failed utterance.
    if (!run.ready) {
      run.pendingWelcome = event;
      return true;
    }
    if (run.welcomeGeneration && run.welcomeGeneration !== event.output_generation) return true;
    if (run.welcomeClosed) return true;
    if (event.status === 'complete' && !run.eofPlayed) {
      this.playbackFailed(run);
      event = { ...event, status: 'error' };
    }
    run.welcomeGeneration = event.output_generation;
    run.welcome = event;
    run.welcomeClosed = ['complete', 'interrupted', 'error'].includes(event.status);
    this.output(run, event.output_generation, event.status);
    this.dispatchEvent(new CustomEvent('welcome', { detail: event }));
    return true;
  }

  async resolveDirection(utteranceId, revision, role) {
    const run = this.run;
    const record = run?.records.get(utteranceId);
    if (!run || !this.active(run) || !run.ready || !record || record.revision !== revision ||
        record.recognition_status === 'partial' || record.translation_status !== 'needs_direction' ||
        record.direction_reason === 'mismatch' || !['guest', 'receptionist'].includes(role)) {
      throw new Error('This utterance can no longer be assigned. Use the current direction controls.');
    }
    try {
      await run.room.localParticipant.publishData(new TextEncoder().encode(JSON.stringify({
        type: 'translation.resolve', utterance_id: utteranceId, revision, role,
      })), { reliable: true, topic: 'client-event' });
    } catch (_) { throw new Error('Direction could not be sent. Check the connection and try again.'); }
  }

  async setMuted(muted) {
    const run = this.run;
    if (!run?.local || !this.active(run) || run.media.readyState !== 'live') {
      throw new Error('Wait for microphone capture to connect before changing mute.');
    }
    run.desiredMuted = Boolean(muted);
    // Disable immediately; asynchronous SDK signalling may never delay a mute.
    if (run.desiredMuted) run.media.enabled = false;
    this.emitState();
    const operation = run.muteQueue.then(async () => {
      if (!this.active(run)) return;
      if (run.desiredMuted) await run.local.mute();
      else await run.local.unmute();
      if (!this.active(run)) { run.local.stop(); return; }
      run.media.enabled = !run.desiredMuted && !run.local.isMuted;
      this.emitState();
    });
    run.muteQueue = operation.catch(() => {});
    try { await operation; }
    catch (_) {
      if (this.active(run)) {
        run.desiredMuted = true;
        run.media.enabled = false;
        this.emitState();
      }
      throw new Error('Microphone control failed; capture has been muted. Stop and start again.');
    }
  }

  async resumeAudio() {
    const run = this.run;
    if (!run?.room || !this.active(run)) throw new Error('Start a conversation before enabling audio.');
    try {
      // Invoke all unlock operations in this user gesture, before the first await.
      if (run.meter?.context.state === 'suspended') void run.meter.context.resume().catch(() => {});
      await run.playback.context.resume();
      if (!this.active(run)) return;
      run.audioBlocked = run.playback.context.state !== 'running';
      this.emitState();
      if (run.audioBlocked) throw new Error();
    } catch (_) {
      if (!this.active(run)) return;
      run.audioBlocked = true;
      this.emitState();
      throw new Error('Audio playback is blocked. Allow sound for this site and press Enable audio again.');
    }
  }


  release(run) {
    if (run.cancelled) return;
    run.cancelled = true;
    clearTimeout(run.timer);
    this.stopMeter(run);
    this.emitLevel(0, false);
    run.stream?.getTracks().forEach(track => track.stop());
    run.local?.stop();
    if (run.playback) {
      const { context, node } = run.playback;
      context.onstatechange = null;
      if (node) {
        node.port.postMessage({ type: 'flush', generation: run.outputGeneration });
        node.port.onmessage = null;
        node.onprocessorerror = null;
        node.disconnect();
        node.port.close();
      }
      void context.close().catch(() => {});
    }
    if (run.room) {
      run.room.removeAllListeners();
      void run.room.disconnect(true).catch(() => {});
    }
    this.settleRecords(run, 'interrupted');
  }

  terminate(run, message, isError = true) {
    if (!this.active(run)) return;
    this.notice(message);
    void this.stop().then(() => {
      if (!this.run && !this.pendingId) this.emitState(isError ? 'error' : 'idle', message);
    }).catch(() => {});
  }

  async stop() {
    if (this.stopFlight) return this.stopFlight;
    const run = this.run;
    if (run) this.release(run);
    this.run = null;
    const id = this.pendingId;
    if (!id) { this.emitState('idle', 'Microphone and audio are off.'); return; }
    this.emitState('stopping', 'Microphone and audio are off. Confirming session cleanup.');
    const finish = async () => {
      const end = async () => {
        try {
          const result = await request(`/api/web/session/${id}/end`, {});
          if (result?.status !== 'ended') throw new Error(CLEANUP_MESSAGE);
        } catch (error) {
          // The server tombstones this exact ID, so a delayed mint cannot create it.
          if (!error.notCreated) throw error;
        }
      };
      try {
        try { await end(); }
        catch (error) {
          if (!run?.mint) throw error;
          await run.mint.catch(() => {});
          await end();
        }
        this.clearPending(id);
        this.emitState('idle', 'Conversation ended. Microphone and audio are off.');
      } catch (_) {
        this.emitState('cleanup', CLEANUP_MESSAGE);
        throw new Error(CLEANUP_MESSAGE);
      }
    };
    this.stopFlight = finish();
    try { return await this.stopFlight; }
    finally { this.stopFlight = null; }
  }
}
