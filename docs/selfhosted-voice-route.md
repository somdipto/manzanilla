# Self-hosted voice route (issue #8)

`VOICE_ROUTE=livekit` runs interpretation on a LiveKit deployment and worker
we operate. The reception client uses the same session-mint endpoint and room
event contract as the hosted route. Their provider behavior, timing, captions
and failure modes still need separate validation.

## Components

- **cloud/livekit_route.py** uses the shared lease, budget, authorization and
  ledger. Each new lease records its route and room name at reservation so
  ordinary end, lost-response recovery and device revocation can select the
  original transport even after `VOICE_ROUTE` changes. Legacy rows with an
  existing `manz-` room ID are migrated as LiveKit; rows without an ID remain
  conservatively classified as hosted Fish because their outcome is unknown.
  End deletes the room over Twirp. `VOICE_ROUTE` defaults to `fish`.
- **voice-worker/** - one interpreter session per room. Room microphone ->
  Fish realtime recognition (transcribe-stream-1: provider-side turn detection,
  per-turn language identification, backchannel filtering, and an
  `agent_speaking` echo gate fed from our own playback state) -> streaming
  translation over an OpenAI-compatible endpoint -> Fish streaming synthesis
  (s2.1-pro) -> room playback. Speech-start interruption cancels generation and
  flushes queued audio; the behavior and acoustic quality need device testing.
- The worker implements device contract events: `lk.agent.state`
  attributes (listening/thinking/speaking), display-only
  `manzanilla_publish_interpretation_v2` reports on the `agent-event` topic,
  `error` and `session.ended` events, 600 s cap.

## Design decisions

- **No tool-calling in the model.** The worker emits captions from recognition
  and translation results rather than asking the model to invoke a display tool.
  Missing, incorrect, interrupted or delayed provider output can still affect
  coverage and accuracy; this does not guarantee a complete transcript.
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

Use **one worker replica** for a LiveKit room namespace. Its room ownership and
retry counters are process-local; multiple replicas can claim the same room.
The worker now limits failed room-session attempts and adds retry delays, but
this is not distributed ownership or a fleet capacity limit. The backend also
has process-local connection state and a local SQLite ledger. Use coordinated
room dispatch, shared durable state, monitoring and load tests before horizontal
scaling. The account/hotel duration limits are configurable; their conservative
defaults reserve 600 seconds per start and allow six starts account-wide and
three per hotel per day.

## Evidence

`scripts/livekit_pipeline_bench.py` brings up a local livekit-server, the
worker, and a scripted reception device, then measures a real round trip with
real provider calls. The only fixture is the device's input speech, synthesized
by Fish TTS because the bench has no human speaker; every downstream stage is
measured, never canned. This is a synthetic-speaker benchmark, not proof of
physical-device acoustics, voice naturalness, or improvement over the hosted
route without a controlled matched comparison. Live provider calls incur cost.


## Turning it on: from merge to visible in the app

Merging the PR alone changes nothing visible. The app talks to whichever route
the backend mints, and the backend defaults to the hosted route
(`VOICE_ROUTE` unset means `fish`). There are no Android changes in this PR, so
there is no app update to install: the same APK joins whichever room the mint
returns. To see the self-hosted route serve a real session from the app:

1. **Run LiveKit** (`infra/livekit.compose.yaml` has both services):
   livekit-server v1.13.10, reachable by the backend, the worker, and the
   tablet. The tablet joins over `wss://`, so put TLS in front for anything
   beyond local evaluation (a Caddy site proxying to 7880 works; LiveKit Cloud
   also works - the route only needs a URL and a key pair). `--dev` mode is
   local evaluation only.
2. **Run the worker** with `FISH_API_KEY`, `FISH_VOICE_ID`, and the LLM
   endpoint (production: Gemini's OpenAI-compatible route, `gemini-3.8-flash`).
   Run only one worker replica per namespace until coordinated dispatch exists.
3. **Restart the backend** with `VOICE_ROUTE=livekit` plus `LIVEKIT_URL`,
   `LIVEKIT_API_KEY`, `LIVEKIT_API_SECRET`.
4. Enroll the app with this backend, then verify a real session on the target
   device. Check both language directions, interruption, captions, reconnect,
   cleanup and voice quality before expanding rollout.
5. **Rollback**: unset `VOICE_ROUTE` and restart the backend; outstanding
   self-hosted leases settle through the same ledger end path.

## Known limits

- The app offers 52 conversation languages, while Fish's
  [realtime speech-to-text documentation](https://docs.fish.audio/features/speech-to-text/realtime#languages)
  lists 29 `language_code` values when language identification is available.
  This is a limit on the documented attribution labels, not proof that speech
  in other languages cannot be recognized. The self-hosted worker speaks a
  translation only when the returned language code matches one of the selected
  languages; otherwise it marks the turn `und` and does not speak it. Pairs
  outside the documented labels, and recognition and direction for every pair,
  require real provider and target-device acceptance before rollout. The picker
  alone is not evidence that all 52 work on this route.
- A device's initial self-hosted join ticket expires after **120 seconds**;
  the returned expiry matches its JWT. LiveKit documents that connected clients
  receive refreshed credentials for reconnect. These may outlive the initial
  ticket, and the pinned Android SDK/server reconnect behavior after the
  initial ticket expires has not yet been qualified on a physical device.
  In a local LiveKit 1.13.10 check, an initial ticket was still accepted shortly
  after its nominal expiry, so expiry is not an immediate replay cutoff.
- Deleting a room disconnects current participants, and credential revocation
  now attempts deletion through the correct route. This does **not** guarantee
  immediate invalidation of already-issued or refreshed LiveKit tokens: on a
  self-hosted server that permits automatic room creation a valid token may be
  replayed. Limit exposure with enrollment controls and appropriate LiveKit room
  policy; a server-enforced revocation design is required for a strict guarantee.
  Failed deletion preserves the lease for retry; the admin response is HTTP 202
  with `voice_cleanup: pending` rather than claiming the room has closed. Staff
  can retry the same credential revocation after connectivity is restored.
- One scripted turn pair measured locally; device-side speaker playback and the
  tablet's acoustic echo path are exercised only on real hardware.
- Fish PCM output tops out at 44.1 kHz; the worker synthesizes at 24 kHz and
  resamples to the room's 48 kHz.
