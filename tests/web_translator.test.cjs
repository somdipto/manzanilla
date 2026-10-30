const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { EventEmitter } = require('node:events');
const { randomUUID } = require('node:crypto');
const { transformSync } = require('esbuild');

const source = transformSync(fs.readFileSync(path.join(__dirname, '../ui/web/translator.js'), 'utf8'), { format: 'cjs' }).code;
const workletSource = fs.readFileSync(path.join(__dirname, '../ui/web/playback-worklet.js'), 'utf8');
const languages = { guest_language: 'en', staff_language: 'es' };
const deferred = () => {
  let resolve, reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
};
const tick = () => new Promise(resolve => setImmediate(resolve));

function fixture() {
  const requests = [], rooms = [], tracks = [], elements = [], storage = new Map(), window = new EventTarget();
  const meters = [], intervals = new Map();
  const playbacks = [], nodes = [];
  class PlaybackContext {
    constructor() {
      this.state = 'suspended';
      this.sampleRate = 24000;
      this.destination = {};
      this.audioWorklet = { addModule: async url => assert.equal(url, '/playback-worklet.js') };
      playbacks.push(this);
    }
    async resume() { this.resumeCalls = (this.resumeCalls || 0) + 1; this.state = 'running'; this.onstatechange?.(); }
    async close() { this.state = 'closed'; }
  }
  window.AudioContext = PlaybackContext;
  window.AudioWorkletNode = class {
    constructor(audioContext, name, options) {
      assert.equal(name, 'translation-playback');
      assert.equal(options.numberOfInputs, 0);
      assert.equal(options.outputChannelCount[0], 1);
      this.context = audioContext;
      this.port = { postMessage: data => this.processor.receive(data), close() {} };
      let Processor;
      const node = this;
      vm.runInNewContext(workletSource, {
        Uint8Array, DataView, Float32Array, Float64Array, Uint32Array,
        AudioWorkletProcessor: class { constructor() { this.port = { postMessage: data => node.port.onmessage?.({ data }) }; } },
        registerProcessor: (_name, value) => { Processor = value; },
      });
      this.processor = new Processor();
      nodes.push(this);
    }
    connect(destination) { assert.equal(destination, this.context.destination); this.connected = true; }
    disconnect() { this.connected = false; }
    render(size = 128) {
      const channel = new Float32Array(size);
      if (this.connected && this.context.state === 'running') this.processor.process([], [[channel]]);
      return channel;
    }
  };
  let nextInterval = 0;
  let capture, responder, connection;
  class Media extends EventTarget {
    constructor() { super(); this.enabled = true; this.muted = false; this.readyState = 'live'; tracks.push(this); }
    stop() { this.readyState = 'ended'; }
  }
  class LocalAudioTrack {
    constructor(media) { this.mediaStreamTrack = media; this.isMuted = false; }
    async mute() { this.isMuted = true; this.mediaStreamTrack.enabled = false; }
    async unmute() { if (!this.isMuted) return; this.isMuted = false; this.mediaStreamTrack.enabled = true; }
    stop() { this.mediaStreamTrack.stop(); }
  }
  class Room extends EventEmitter {
    constructor() {
      super(); rooms.push(this); this.remoteParticipants = new Map(); this.canPlaybackAudio = true;
      this.controls = [];
      this.localParticipant = {
        publishTrack: async track => { this.published = track; },
        publishData: async (bytes, options) => { this.controls.push({ event: JSON.parse(new TextDecoder().decode(bytes)), options }); },
      };
    }
    async connect(_url, _token, options) { this.connectOptions = options; if (connection) await connection.promise; this.connected = true; }
    async disconnect() { this.disconnected = true; this.connected = false; }
    async startAudio() { this.canPlaybackAudio = true; }
  }
  const events = Object.fromEntries(['DataReceived', 'ParticipantAttributesChanged', 'TrackSubscribed', 'TrackUnsubscribed', 'TrackSubscriptionFailed', 'AudioPlaybackStatusChanged', 'Reconnecting', 'Reconnected', 'Disconnected'].map(name => [name, name]));
  const sdk = { Room, RoomEvent: events, LocalAudioTrack, Track: { Kind: { Audio: 'audio' }, Source: { Microphone: 'microphone' } }, setLogLevel() {}, LogLevel: { silent: 5 } };
  const context = vm.createContext({
    module: { exports: {} }, exports: {}, require: name => { assert.equal(name, 'livekit-client'); return sdk; },
    EventTarget, Event, CustomEvent, TextDecoder, TextEncoder, Uint8Array, DataView, URL, AbortController,
    setTimeout, clearTimeout, crypto: { randomUUID }, window,
    setInterval(callback, milliseconds) { assert.equal(milliseconds, 100); intervals.set(++nextInterval, callback); return nextInterval; },
    clearInterval(id) { intervals.delete(id); },
    navigator: { mediaDevices: { getUserMedia: async () => {
      if (capture) return capture.promise;
      const track = new Media();
      return { getTracks: () => [track], getAudioTracks: () => [track] };
    } } },
    localStorage: { getItem: key => storage.get(key) || null, setItem: (key, value) => storage.set(key, value), removeItem: key => storage.delete(key) },
    document: { body: { appendChild() {} }, createElement: type => {
      const element = { type, setAttribute() {}, appendChild() {}, async play() { this.playing = true; }, pause() { this.playing = false; }, remove() { this.removed = true; } };
      elements.push(element); return element;
    } },
    fetch: async (url, options) => {
      const call = { url, options, body: options.body ? JSON.parse(options.body) : null }; requests.push(call);
      if (responder) { const response = await responder(call); if (response) return response; }
      if (url === '/api/web/status') return response({ configured: true, languages: [], pending_request_id: null });
      if (url.endsWith('/end')) return response({ status: 'ended' });
      return response({ session_id: 'session', transport: 'livekit', token: 'private-join-token', livekit_url: 'wss://voice.invalid', expires_at: new Date(Date.now() + 60000).toISOString(), max_duration_seconds: 600 });
    },
  });
  vm.runInContext(source, context);
  const translator = new context.module.exports.Translator();
  const states = [], utterances = [], welcomes = [], notices = [], levels = [];
  translator.addEventListener('state', event => states.push(event.detail));
  translator.addEventListener('utterance', event => utterances.push(event.detail));
  translator.addEventListener('welcome', event => welcomes.push(event.detail));
  translator.addEventListener('notice', event => notices.push(event.detail));
  translator.addEventListener('level', event => levels.push(event.detail));
  return {
    translator, requests, rooms, tracks, elements, states, utterances, welcomes, notices, storage, window, Media,
    meters, intervals, levels, playbacks, nodes,
    sampleMeters() { for (const sample of intervals.values()) sample(); },
    enableMeter(initialState = 'running') {
      window.AudioContext = class {
        constructor(options) {
          if (options?.sampleRate) return new PlaybackContext();
          this.state = initialState;
          this.amplitude = 0.25;
          this.source = { connect: target => { this.connected = target; }, disconnect: () => { this.sourceDisconnected = true; } };
          this.analyser = { fftSize: 0, getFloatTimeDomainData: values => values.fill(this.amplitude), disconnect: () => { this.analyserDisconnected = true; } };
          meters.push(this);
        }
        createMediaStreamSource(stream) { this.stream = stream; return this.source; }
        createAnalyser() { return this.analyser; }
        async resume() { /* Suspended fixture remains blocked until explicitly changed. */ }
        async close() { this.state = 'closed'; }
      };
    },
    setCapture(value) { capture = value; }, setResponder(value) { responder = value; }, setConnection(value) { connection = value; },
    agent(event, identity = 'agent-session') { rooms[0].emit('DataReceived', new TextEncoder().encode(JSON.stringify(event)), { identity }, null, 'agent-event'); },
    audio(bytes, identity = 'agent-session') { rooms[0].emit('DataReceived', bytes, { identity }, null, 'translation-audio-v1'); },
    render(size) { return nodes[0].render(size); },
  };
}
function response(value, status = 200, notCreated = false) {
  return { ok: status < 400, headers: { get: name => name === 'X-Manzanilla-Session-Creation' && notCreated ? 'not-created' : null }, json: async () => value };
}

function utterance(id, changes = {}) {
  return { type: 'translation.utterance', record: {
    session_id: 'session', utterance_id: id, revision: 1, source_language: 'en', target_language: 'es',
    role: 'guest', original: ' Name ', translation: '', recognition_status: 'final',
    translation_status: 'waiting', playback_status: 'idle', output_generation: 0,
    direction_reason: 'recognized', detected_language: 'en', ...changes,
  } };
}
function ready(f, pair = languages) {
  f.agent({ type: 'translation.ready', session_id: 'session', ...pair });
}
function pcm(generation, sequence, samples = []) {
  const bytes = new Uint8Array(16 + samples.length * 2);
  const view = new DataView(bytes.buffer);
  view.setUint32(0, 0x4d5a5031);
  view.setUint32(4, generation);
  view.setUint32(8, sequence);
  view.setUint32(12, 24000);
  samples.forEach((value, index) => view.setInt16(16 + index * 2, value, true));
  return bytes;
}
function announce(f, generation, status = 'playing') {
  f.agent({ type: 'translation.output', session_id: 'session', output_generation: generation, status });
}

test('capture precedes mint; mute is real; Stop immediately releases capture and output', async () => {
  const f = fixture();
  assert.equal(f.requests.length, 0);
  await f.translator.start(languages);
  assert.equal(f.rooms[0].published.mediaStreamTrack, f.tracks[0]);
  assert.equal(f.states.at(-1).microphone, true);
  await f.translator.setMuted(true);
  assert.equal(f.tracks[0].enabled, false);
  assert.equal(f.states.at(-1).muted, true);
  assert.equal(f.states.at(-1).microphone, false);
  await f.translator.setMuted(false);
  announce(f, 1);
  f.audio(pcm(1, 0, [100, 200, 300]));
  const ending = deferred();
  f.setResponder(call => call.url.endsWith('/end') ? ending.promise : null);
  const stop = f.translator.stop();
  assert.equal(f.tracks[0].readyState, 'ended');
  assert.equal(f.playbacks[0].state, 'closed');
  assert.equal(f.nodes[0].connected, false);
  assert.deepEqual([...f.render()], Array(128).fill(0));
  assert.equal(f.states.at(-1).phase, 'stopping');
  ending.resolve(response({ status: 'ended' }));
  await stop;
  assert.equal(f.states.at(-1).phase, 'idle');
  assert.equal(f.storage.size, 0);
});

test('Stop while permission is pending releases a late track without minting', async () => {
  const f = fixture(), permission = deferred();
  f.setCapture(permission);
  f.enableMeter();
  const start = f.translator.start(languages);
  await f.translator.stop();
  const track = new f.Media();
  permission.resolve({ getTracks: () => [track], getAudioTracks: () => [track] });
  await start;
  assert.equal(track.readyState, 'ended');
  assert.equal(f.requests.length, 0);
  assert.equal(f.meters.length, 0);
});

test('Stop during mint retains exact ownership and cannot connect a late ticket', async () => {
  const f = fixture(), mint = deferred(), ending = deferred();
  f.setResponder(call => call.url.endsWith('/end') ? ending.promise : call.url === '/api/web/session' ? mint.promise : null);
  const start = f.translator.start(languages);
  await tick();
  const id = f.requests[0].body.request_id;
  const stop = f.translator.stop();
  await assert.rejects(f.translator.start(languages), /cleanup/);
  mint.resolve(response({ token: 'secret' }));
  await start;
  assert.equal(f.rooms.length, 0);
  assert.equal(f.tracks[0].readyState, 'ended');
  assert.equal(f.requests[1].url, `/api/web/session/${id}/end`);
  ending.resolve(response({ status: 'ended' }));
  await stop;
});

test('Stop while connecting fences late connection and publication', async () => {
  const f = fixture(), connection = deferred();
  f.setConnection(connection);
  const start = f.translator.start(languages);
  await tick();
  await f.translator.stop();
  connection.resolve();
  await start;
  assert.equal(f.rooms[0].published, undefined);
  assert.equal(f.rooms[0].disconnected, true);
  assert.equal(f.tracks[0].readyState, 'ended');
});

test('cleanup error blocks starts until exact retry confirms settlement', async () => {
  const f = fixture();
  await f.translator.start(languages);
  f.setResponder(call => call.url.endsWith('/end') ? response({}, 503) : null);
  await assert.rejects(f.translator.stop(), /unconfirmed/);
  const id = f.states.at(-1).requestId;
  assert.equal(f.states.at(-1).phase, 'cleanup');
  await assert.rejects(f.translator.start(languages), /cleanup/);
  f.setResponder(null);
  await f.translator.stop();
  assert.equal(f.requests.at(-1).url, `/api/web/session/${id}/end`);
  assert.equal(f.storage.size, 0);
});

test('status recovers backend ownership and absence alone does not clear local UUID', async () => {
  const f = fixture(), id = randomUUID();
  f.setResponder(call => call.url === '/api/web/status' ? response({ configured: true, languages: [], pending_request_id: id }) : null);
  await f.translator.status();
  assert.equal(f.states.at(-1).requestId, id);
  assert.equal(f.states.at(-1).phase, 'cleanup');
  f.setResponder(call => call.url.endsWith('/end') ? response({}, 503) : null);
  await f.translator.status();
  assert.equal(f.states.at(-1).requestId, id);
  f.setResponder(call => call.url.endsWith('/end') ? response({}, 404, true) : null);
  await f.translator.stop();
  assert.equal(f.states.at(-1).requestId, null);
});

test('revisioned originals survive partial recognition, translation errors and stale updates', async () => {
  const f = fixture();
  await f.translator.start(languages);
  f.agent(utterance('a', { recognition_status: 'partial', original: ' Na' }));
  f.agent(utterance('a', { revision: 2, translation_status: 'streaming', translation: ' Nom' }));
  f.agent(utterance('a', { revision: 3, translation_status: 'error', translation: ' Nom' }));
  f.agent(utterance('a', { revision: 2, original: 'stale' }));
  f.agent(utterance('b', { session_id: 'foreign' }));
  f.agent(utterance('c', { revision: 0 }));
  assert.equal(f.utterances.length, 3);
  assert.equal(f.utterances.at(-1).original, ' Name ');
  assert.equal(f.utterances.at(-1).translation, ' Nom');
  assert.equal(f.utterances.at(-1).translation_status, 'error');
  assert.equal(f.notices.length, 1);
  assert.equal(f.tracks[0].readyState, 'live');
  await f.translator.stop();
});

test('same-language direction control is revision-bound and cannot assign a mismatched language', async () => {
  const f = fixture();
  const pair = { guest_language: 'en', staff_language: 'en' };
  await f.translator.start(pair);
  ready(f, pair);
  f.agent(utterance('a', { role: null, target_language: null, translation_status: 'needs_direction', direction_reason: 'same_language' }));
  await assert.rejects(f.translator.resolveDirection('a', 2, 'guest'), /current direction/);
  await f.translator.resolveDirection('a', 1, 'receptionist');
  assert.deepEqual(f.rooms[0].controls[0].event, { type: 'translation.resolve', utterance_id: 'a', revision: 1, role: 'receptionist' });
  assert.equal(f.rooms[0].controls[0].options.topic, 'client-event');
  assert.equal(f.rooms[0].controls[0].options.reliable, true);
  f.agent(utterance('a', { revision: 2, role: null, translation_status: 'needs_direction', direction_reason: 'mismatch', detected_language: 'fr' }));
  await assert.rejects(f.translator.resolveDirection('a', 2, 'guest'), /current direction/);
  await f.translator.stop();
  await assert.rejects(f.translator.resolveDirection('a', 2, 'guest'), /current direction/);
});

test('degraded committed speech keeps current playback and supports manual direction, unlike a partial', async () => {
  const f = fixture();
  await f.translator.start(languages);
  ready(f);
  announce(f, 1);
  f.audio(pcm(1, 0, [100, 200, 300]));
  f.agent(utterance('backchannel', { role: null, source_language: null, target_language: null,
    recognition_status: 'partial', translation_status: 'needs_direction', direction_reason: 'uncertain' }));
  await assert.rejects(f.translator.resolveDirection('backchannel', 1, 'guest'), /current direction/);
  f.agent(utterance('backchannel', { revision: 2, role: null, source_language: null, target_language: null,
    recognition_status: 'degraded', translation_status: 'needs_direction', direction_reason: 'uncertain' }));
  assert.deepEqual([...f.render(3)], [100 / 32768, 200 / 32768, 300 / 32768]);
  assert.equal(f.tracks[0].readyState, 'live');
  await f.translator.resolveDirection('backchannel', 2, 'guest');
  assert.deepEqual(f.rooms[0].controls.at(-1).event, {
    type: 'translation.resolve', utterance_id: 'backchannel', revision: 2, role: 'guest',
  });
  assert.equal(f.utterances.at(-1).original, ' Name ');
  await f.translator.stop();
});

test('renderer ACKs only consumed PCM and EOF; normal completion keeps context alive', async () => {
  const f = fixture();
  await f.translator.start(languages);
  assert.equal(f.rooms[0].connectOptions.autoSubscribe, false);
  announce(f, 1);
  f.audio(pcm(1, 0, [100, 200, 300]));
  f.audio(pcm(1, 1, [400, 500]));
  f.audio(pcm(1, 2));
  assert.equal(f.rooms[0].controls.length, 0);
  assert.deepEqual([...f.render(2)], [100 / 32768, 200 / 32768]);
  assert.equal(f.rooms[0].controls.length, 0);
  assert.deepEqual([...f.render(2)], [300 / 32768, 400 / 32768]);
  assert.deepEqual(f.rooms[0].controls.map(x => x.event.sequence), [0]);
  assert.deepEqual([...f.render(2)], [500 / 32768, 0]);
  assert.deepEqual(f.rooms[0].controls.map(x => x.event.sequence), [0, 1, 2]);
  assert.deepEqual(f.rooms[0].controls.at(-1).event, { type: 'translation.played', output_generation: 1, sequence: 2 });
  announce(f, 1, 'complete');
  assert.equal(f.playbacks[0].state, 'running');
  assert.equal(f.nodes[0].connected, true);
  assert.deepEqual([...f.render(2)], [0, 0]);
  assert.equal(f.rooms[0].controls.length, 3);
  await f.translator.stop();
});

test('interruption flushes queued samples; old and unauthenticated generations cannot play', async () => {
  const f = fixture();
  await f.translator.start(languages);
  const remote = { kind: 'audio', name: 'manzanilla-output-1', attach() { assert.fail('RTC output must never attach'); } };
  f.rooms[0].emit('TrackSubscribed', remote);
  announce(f, 1);
  f.audio(pcm(1, 0, [100, 200]));
  assert.deepEqual([...f.render(1)], [100 / 32768]);
  announce(f, 1, 'interrupted');
  f.audio(pcm(1, 1, [300]));
  assert.deepEqual([...f.render(2)], [0, 0]);
  announce(f, 2);
  f.audio(pcm(1, 2, [400]));
  f.audio(pcm(2, 0, [500]), 'foreign');
  f.agent({ type: 'translation.output', session_id: 'session', output_generation: 3, status: 'playing' }, 'foreign');
  f.audio(pcm(2, 0, [600]));
  assert.deepEqual([...f.render(2)], [600 / 32768, 0]);
  assert.deepEqual(f.rooms[0].controls.map(x => x.event.output_generation), [2]);
  await f.translator.stop();
});

test('welcome is separate, survives a readiness reorder and cannot replay after its terminal event', async () => {
  const f = fixture();
  await f.translator.start(languages);
  assert.equal(f.states.at(-1).phase, 'connecting');
  const welcome = { type: 'translation.welcome', session_id: 'session', language: 'en', text: 'Welcome', status: 'queued', output_generation: 1 };
  f.agent(welcome);
  assert.equal(f.welcomes.length, 0);
  assert.equal(f.notices.length, 0);
  ready(f);
  assert.equal(f.states.at(-1).phase, 'listening');
  assert.equal(f.welcomes.length, 1);
  assert.equal(f.welcomes[0].text, 'Welcome');
  f.agent(welcome);
  f.agent({ ...welcome, status: 'interrupted' });
  f.agent(welcome);
  assert.equal(f.welcomes.length, 3);
  // The worker repeats readiness immediately before the greeting; the repeat must
  // change nothing and must not replay a settled welcome.
  ready(f);
  assert.equal(f.welcomes.length, 3);
  assert.equal(f.utterances.length, 0);
  assert.equal(f.notices.length, 0);
  await f.translator.stop();
});

test('settings pass separate credentials only to the server, not durable browser storage', async () => {
  const f = fixture();
  await f.translator.configure({ api_key: 'fish-private', translation_api_key: 'gemini-private', voice_id: 'voice' });
  assert.equal(f.requests[0].body.translation_api_key, 'gemini-private');
  assert.equal(f.storage.size, 0);
});

test('pagehide stops real local resources and sends exact keepalive cleanup', async () => {
  const f = fixture();
  await f.translator.start(languages);
  const id = f.states.at(-1).requestId;
  f.window.dispatchEvent(new Event('pagehide'));
  assert.equal(f.tracks[0].readyState, 'ended');
  assert.equal(f.requests.at(-1).url, `/api/web/session/${id}/end`);
  assert.equal(f.requests.at(-1).options.keepalive, true);
  assert.equal(f.states.at(-1).phase, 'cleanup');
  assert.equal([...f.storage.values()][0], id);
});

test('rapid mute then unmute restores the actual track even when SDK unmute is a no-op', async () => {
  const f = fixture();
  await f.translator.start(languages);
  await Promise.all([f.translator.setMuted(true), f.translator.setMuted(false)]);
  assert.equal(f.tracks[0].enabled, true);
  assert.equal(f.states.at(-1).microphone, true);
  assert.equal(f.states.at(-1).muted, false);
  await f.translator.stop();
});

test('browser capture ending stops the session and exposes an actionable error', async () => {
  const f = fixture();
  await f.translator.start(languages);
  f.tracks[0].readyState = 'ended';
  f.tracks[0].dispatchEvent(new Event('ended'));
  await tick();
  assert.equal(f.states.at(-1).microphone, false);
  assert.equal(f.states.at(-1).phase, 'error');
  assert.match(f.states.at(-1).message, /Microphone capture ended/);
});

test('autoplay reports queued unplayed audio and unlocks only through the real context', async () => {
  const f = fixture();
  await f.translator.start(languages);
  const room = f.rooms[0], playback = f.playbacks[0];
  playback.state = 'suspended';
  playback.onstatechange();
  announce(f, 1);
  f.audio(pcm(1, 0, [100]));
  f.audio(pcm(1, 1));
  assert.equal(f.states.at(-1).audioBlocked, true);
  assert.deepEqual([...f.render(1)], [0]);
  assert.equal(room.controls.length, 0);
  await f.translator.resumeAudio();
  assert.equal(f.states.at(-1).audioBlocked, false);
  assert.equal(playback.resumeCalls, 2);
  assert.deepEqual([...f.render(1)], [100 / 32768]);
  assert.deepEqual(room.controls.map(x => x.event.sequence), [0, 1]);
  await f.translator.stop();
  const count = f.states.length;
  room.emit('ParticipantAttributesChanged', { 'lk.agent.state': 'speaking' });
  room.emit('Reconnected');
  assert.equal(f.states.length, count);
});

test('meter samples actual captured waveform without speaker feedback and releases all resources', async () => {
  const f = fixture();
  f.enableMeter();
  await f.translator.start(languages);
  const meter = f.meters[0];
  assert.equal(meter.stream.getAudioTracks()[0], f.tracks[0]);
  assert.equal(meter.connected, meter.analyser);
  assert.equal(f.levels.at(-1).available, true);
  assert.equal(f.levels.at(-1).value, 0.25);
  meter.amplitude = 0.5;
  f.sampleMeters();
  assert.equal(f.levels.at(-1).value, 0.5);
  await f.translator.setMuted(true);
  assert.equal(f.levels.at(-1).value, 0);
  f.sampleMeters();
  assert.equal(f.levels.at(-1).value, 0);
  await f.translator.setMuted(false);
  f.sampleMeters();
  assert.equal(f.levels.at(-1).value, 0.5);
  await f.translator.stop();
  assert.equal(f.levels.at(-1).value, 0);
  assert.equal(f.levels.at(-1).available, false);
  assert.equal(meter.state, 'closed');
  assert.equal(meter.sourceDisconnected, true);
  assert.equal(meter.analyserDisconnected, true);
  assert.equal(f.intervals.size, 0);
});

test('blocked or missing analyser is unavailable rather than a fabricated silent microphone', async () => {
  const f = fixture();
  f.enableMeter('suspended');
  await f.translator.start(languages);
  f.sampleMeters();
  assert.equal(f.states.at(-1).microphone, true);
  assert.equal(f.levels.at(-1).available, false);
  f.meters[0].state = 'running';
  f.meters[0].onstatechange();
  assert.equal(f.levels.at(-1).available, true);
  assert.equal(f.levels.at(-1).value, 0.25);
  await f.translator.stop();
  const unavailable = fixture();
  await unavailable.translator.start(languages);
  assert.equal(unavailable.levels.at(-1).available, false);
  await unavailable.translator.stop();
});

test('Stop settles pending records and welcome, preserving completed translation and exact originals', async () => {
  const f = fixture();
  await f.translator.start(languages);
  ready(f);
  f.agent({ type: 'translation.welcome', session_id: 'session', language: 'en', text: 'Welcome', status: 'playing', output_generation: 1 });
  f.agent(utterance('pending', { translation_status: 'streaming', translation: ' Parcial', playback_status: 'playing', output_generation: 2 }));
  f.agent(utterance('translated', { translation_status: 'complete', translation: ' Completa ', playback_status: 'queued', output_generation: 3 }));
  f.agent(utterance('unclear', { role: null, translation_status: 'needs_direction', direction_reason: 'uncertain' }));
  await f.translator.stop();
  const latest = new Map(f.utterances.map(record => [record.utterance_id, record]));
  assert.equal(latest.get('pending').translation_status, 'cancelled');
  assert.equal(latest.get('pending').playback_status, 'interrupted');
  assert.equal(latest.get('pending').translation, ' Parcial');
  assert.equal(latest.get('pending').original, ' Name ');
  assert.equal(latest.get('translated').translation_status, 'complete');
  assert.equal(latest.get('translated').playback_status, 'interrupted');
  assert.equal(latest.get('translated').translation, ' Completa ');
  assert.equal(latest.get('unclear').translation_status, 'cancelled');
  assert.equal(f.welcomes.at(-1).status, 'interrupted');
  const count = f.utterances.length;
  f.agent(utterance('pending', { revision: 100, translation_status: 'streaming' }));
  f.audio(pcm(3, 0, [100]));
  assert.equal(f.utterances.length, count);
  assert.deepEqual([...f.render(1)], [0]);
});

test('malformed current stream fails visibly without rendering queued data or acknowledging it', async () => {
  for (const invalid of [
    pcm(1, 2, [300]), pcm(1, 0, [300]), pcm(1, 1, Array(961).fill(300)),
    new Uint8Array(17),
    (() => { const bytes = pcm(1, 1, [300]); new DataView(bytes.buffer).setUint32(12, 48000); return bytes; })(),
    (() => { const bytes = pcm(1, 1, [300]); bytes[0] = 0; return bytes; })(),
  ]) {
    const f = fixture();
    await f.translator.start(languages);
    f.agent(utterance('a', { translation: ' Text', translation_status: 'complete', playback_status: 'playing', output_generation: 1 }));
    f.audio(pcm(1, 0, [100, 200]));
    f.audio(invalid);
    assert.deepEqual([...f.render(2)], [0, 0]);
    assert.equal(f.rooms[0].controls.length, 0);
    assert.match(f.notices.at(-1).message, /could not be played/);
    assert.equal(f.utterances.at(-1).translation, ' Text');
    assert.equal(f.utterances.at(-1).playback_status, 'error');
    f.audio(pcm(1, 1, [400]));
    assert.deepEqual([...f.render(1)], [0]);
    await f.translator.stop();
  }
});

test('actual worklet rejects reordered packets, ring overflow and marker overflow defensively', async () => {
  for (const scenario of ['sequence', 'samples', 'markers', 'after-eof']) {
    const f = fixture();
    await f.translator.start(languages);
    announce(f, 1);
    const processor = f.nodes[0].processor;
    const send = (sequence, samples) => processor.receive({ type: 'pcm', generation: 1, bytes: pcm(1, sequence, samples) });
    if (scenario === 'sequence') { send(0, [100]); send(2, [200]); }
    if (scenario === 'samples') for (let i = 0; i < 26; i++) send(i, Array(960).fill(100));
    if (scenario === 'markers') for (let i = 0; i < 65; i++) send(i, [100]);
    if (scenario === 'after-eof') { send(0, [100]); send(1, []); send(2, [200]); }
    assert.deepEqual([...f.render(2)], [0, 0]);
    assert.equal(f.rooms[0].controls.length, 0);
    assert.match(f.notices.at(-1).message, /could not be played/);
    await f.translator.stop();
  }
});

test('ring wraps without dropping or replaying tails and EOF is delivered exactly once', async () => {
  const f = fixture();
  await f.translator.start(languages);
  announce(f, 1);
  for (let sequence = 0; sequence < 40; sequence++) {
    const samples = Array.from({ length: sequence === 39 ? 17 : 960 }, (_, i) => (sequence * 960 + i) % 30000 - 15000);
    f.audio(pcm(1, sequence, samples));
    const played = [];
    for (let i = 0; i < samples.length; i += 128) played.push(...f.render(Math.min(128, samples.length - i)));
    assert.deepEqual(played, samples.map(value => value / 32768));
  }
  f.audio(pcm(1, 40));
  assert.equal(f.rooms[0].controls.length, 40);
  assert.deepEqual([...f.render(128)], Array(128).fill(0));
  assert.equal(f.rooms[0].controls.length, 41);
  f.render();
  assert.equal(f.rooms[0].controls.length, 41);
  assert.deepEqual(f.rooms[0].controls.map(x => x.event.sequence), Array.from({ length: 41 }, (_, i) => i));
  await f.translator.stop();
});

test('producer completion cannot claim playback before the renderer drains EOF', async () => {
  const f = fixture();
  await f.translator.start(languages);
  f.agent(utterance('a', { playback_status: 'playing', output_generation: 1 }));
  f.audio(pcm(1, 0, [100]));
  announce(f, 1, 'complete');
  assert.deepEqual([...f.render(1)], [0]);
  assert.equal(f.rooms[0].controls.length, 0);
  assert.equal(f.utterances.at(-1).playback_status, 'error');
  await f.translator.stop();
});

test('Start unlocks the context during the gesture and unsupported rates fail before a provider session', async () => {
  const f = fixture(), permission = deferred();
  f.setCapture(permission);
  const start = f.translator.start(languages);
  assert.equal(f.playbacks[0].resumeCalls, 1);
  await f.translator.stop();
  const track = new f.Media();
  permission.resolve({ getTracks: () => [track], getAudioTracks: () => [track] });
  await start;
  const bad = fixture();
  const Context = bad.window.AudioContext;
  bad.window.AudioContext = class extends Context { constructor(options) { super(options); this.sampleRate = 48000; } };
  await assert.rejects(bad.translator.start(languages), /24 kHz AudioWorklet/);
  assert.equal(bad.requests.length, 0);
  assert.equal(bad.tracks[0].readyState, 'ended');
  assert.equal(bad.playbacks[0].state, 'closed');
});

test('worklet itself fences flushed generations and cannot reopen one with a stale begin', async () => {
  const f = fixture();
  await f.translator.start(languages);
  announce(f, 1);
  const processor = f.nodes[0].processor;
  processor.receive({ type: 'pcm', generation: 1, bytes: pcm(1, 0, [100, 200]) });
  processor.receive({ type: 'flush', generation: 1 });
  processor.receive({ type: 'begin', generation: 1 });
  processor.receive({ type: 'pcm', generation: 1, bytes: pcm(1, 1, [300]) });
  assert.deepEqual([...f.render(2)], [0, 0]);
  announce(f, 2);
  processor.receive({ type: 'begin', generation: 1 });
  processor.receive({ type: 'pcm', generation: 1, bytes: pcm(1, 0, [400]) });
  f.audio(pcm(2, 0, [500]));
  assert.deepEqual([...f.render(2)], [500 / 32768, 0]);
  assert.deepEqual(f.rooms[0].controls.map(x => x.event.output_generation), [2]);
  await f.translator.stop();
});

test('capture prefers reported all-output echo cancellation without connecting the mic to speakers', async () => {
  const f = fixture();
  f.Media.prototype.getCapabilities = () => ({ echoCancellation: [true, false, 'all'] });
  f.Media.prototype.applyConstraints = async function (constraints) { this.constraints = constraints; };
  f.enableMeter();
  await f.translator.start(languages);
  assert.equal(f.tracks[0].constraints.echoCancellation.exact, 'all');
  assert.equal(f.meters[0].connected, f.meters[0].analyser);
  assert.equal(f.rooms[0].published.mediaStreamTrack, f.tracks[0]);
  await f.translator.stop();
});
