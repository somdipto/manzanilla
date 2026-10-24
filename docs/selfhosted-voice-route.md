# Self-hosted voice route (issue #8)

`VOICE_ROUTE=livekit` runs interpretation on our own LiveKit deployment with a
worker we control, instead of the provider's hosted black box. The reception
client cannot tell the routes apart: same mint request, same response shape,
same wire contract in the room.

## Components

- **cloud/livekit_route.py** - route-aware twin of the hosted mint/end. Reuses
  the same lease, budget, authorization and ledger machinery (`fish_sessions`
  rows are identical). Mints a hand-rolled HS256 LiveKit JWT whose participant
  metadata carries the language pair; end deletes the room over Twirp.
  `VOICE_ROUTE` defaults to `fish` and the hosted route is untouched.
- **voice-worker/** - one interpreter session per room. Room microphone ->
  Fish realtime recognition (transcribe-stream-1: provider-side turn detection,
  per-turn language identification, backchannel filtering, and an
  `agent_speaking` echo gate fed from our own playback state) -> streaming
  translation over an OpenAI-compatible endpoint -> Fish streaming synthesis
  (s2.1-pro) -> room playback, first clause first. A user speech start during
  playback cancels generation and flushes queued audio, then the new utterance
  is heard in full.
- The worker reproduces the device contract exactly: `lk.agent.state`
  attributes (listening/thinking/speaking), display-only
  `manzanilla_publish_interpretation_v2` reports on the `agent-event` topic,
  `error` and `session.ended` events, 600 s cap.

## Design decisions

- **No tool-calling in the model.** The hosted route's caption report policy
  exists so a general-purpose agent remembers to report. The worker emits
  captions deterministically from measured recognition and translation results,
  so there is no model behavior to skip and no prompt injection surface that
  could suppress captions. The fidelity wording the model does see stays
  byte-identical to the hosted route via a direct `cloud.fish` import.
- **Language identification comes from the recognizer**, never from turn order
  or speaker identity. Unclear utterances report `und` with an empty
  translation and are not spoken.
- **The agent grant needs `canUpdateOwnMetadata`** or `lk.agent.state` changes
  silently never leave the worker.

## Environment

Backend: `VOICE_ROUTE=livekit`, `LIVEKIT_URL` (wss://), `LIVEKIT_API_KEY`,
`LIVEKIT_API_SECRET`.

Worker: `LIVEKIT_URL`, `LIVEKIT_API_KEY`, `LIVEKIT_API_SECRET`, `FISH_API_KEY`,
`FISH_VOICE_ID`, `LLM_BASE_URL`, `LLM_API_KEY`, `LLM_MODEL` (production:
Gemini's OpenAI-compatible endpoint), optional `LLM_EXTRA_JSON`, `ROOM_PREFIX`
(default `manz-`), `VOICE_BENCH_LOG`.

LiveKit server: 1.13.x verified. Participant attributes are the state channel;
1.9.x silently drops them.

## Evidence

`scripts/livekit_pipeline_bench.py` brings up a local livekit-server, the
worker, and a scripted reception device, then measures a real round trip with
real provider calls. The only fixture is the device's input speech, synthesized
by Fish TTS because the bench has no human speaker; every downstream stage is
measured, never canned. See the PR for the measured table.

## Known limits

- One scripted turn pair measured locally; device-side speaker playback and the
  tablet's acoustic echo path are exercised only on real hardware.
- Fish PCM output tops out at 44.1 kHz; the worker synthesizes at 24 kHz and
  resamples to the room's 48 kHz.
