# Application-controlled voice route: bounded evaluation only

New managed sessions use one pipeline: Fish realtime recognition, application-owned
direction, Gemini 3.8 Flash translation, and Fish S2.1 Pro synthesis. A genuinely
new reservation also requires `ALLOW_UNQUALIFIED_LIVEKIT=1`,
`LIVEKIT_WORKER_ENABLED=1`, and separate Fish, voice and Gemini configuration.
The opt-in does not qualify audio, provider access, security or production ownership.
Personal direct translation is the same pipeline on the device and is independent of this gate.
`VOICE_ROUTE` does not select a hosted Fish Agent or another provider.

The reception client uses the existing session-mint endpoint and room event
contract. Provider behavior, timing, captions and recovery require separate
route-, language-pair-, artifact- and target-device qualification. No such
physical qualification is established by this recipe.

## Components

- **cloud/livekit_route.py** uses the shared lease, budget, authorization and
  ledger. Each new lease records its route and room name at reservation so
  ordinary end, lost-response recovery and device revocation can select the
  original transport even after `VOICE_ROUTE` changes. Legacy rows with an
  existing `manz-` room ID are migrated as LiveKit; rows without an ID remain
  conservatively classified as hosted Fish because their outcome is unknown.
  End deletes the room over Twirp. A legacy `VOICE_ROUTE` value cannot restore
  hosted Fish Agent creation.
- **voice-worker/** — one interpreter session per room. The room microphone stays
  live and is sent to Fish realtime recognition (`transcribe-stream-1`: provider
  turn detection, per-turn language identification, backchannel filtering, and an
  `agent_speaking` hint fed from our own playback progress). The application owns
  the utterance record, direction and target language. Gemini translates only
  that recognized text. Fish MessagePack synthesis (`s2.1-pro`) returns 24 kHz
  PCM. The client renders that PCM and acknowledges only samples its audio
  renderer has consumed, including the empty EOF packet. A confirmed human turn
  cancels the active generation and flushes queued audio. Acoustic quality and
  interruption latency still need device testing.
- The worker sends `lk.agent.state` attributes (listening/thinking/speaking),
  revisioned `translation.utterance` records, welcome/output status, reliable
  `translation-audio-v1` PCM, `error` and `session.ended` events. The session cap
  is 600 seconds. Owned microphone capture closes before room disconnect.
  Translated audio is not published as a WebRTC audio track and is not resampled
  to 48 kHz.

## Design decisions

- **No tool-calling in the model.** Gemini receives the supplied translation-only
  system prompt and the recognized transcript separately. It does not choose a
  speaker, invent hotel policy, or call a caption tool. The application record
  is the caption. Missing, incorrect, interrupted or delayed provider output can
  still affect coverage and accuracy.
- **Direction comes from recognition evidence**, never from turn order. A final
  language matching the selected guest or receptionist sets that side and the
  opposite target. Uncertain, mixed, same-language and third-language records
  stay visible and are not spoken until an allowed manual direction, except a
  third language, which requires changing the selected pair. A completed
  translation stays complete if playback is interrupted or fails.
- **The agent grant needs `canUpdateOwnMetadata`** or `lk.agent.state` changes
  silently never leave the worker.

## Environment

Backend: `ALLOW_UNQUALIFIED_LIVEKIT=1`, `LIVEKIT_WORKER_ENABLED=1`,
`LIVEKIT_URL` (wss:// for a tablet), `LIVEKIT_API_KEY`, `LIVEKIT_API_SECRET`,
`FISH_API_KEY`, `FISH_VOICE_ID` and `GEMINI_API_KEY`. Leave the opt-in disabled
outside authorized evaluation. `VOICE_ROUTE` is not a route selector.

The backend looks up that caller's recorded request before the new-reservation
gate. Withdrawing the flag does not prevent startup, recorded-request
reconciliation, end, recovery or cleanup. An existing request must not receive
a proven-noncreation receipt solely because the flag changed. A rejected
genuinely new request creates no reservation, room or token.

Worker: `LIVEKIT_WORKER_ENABLED=1`, `LIVEKIT_URL`, `LIVEKIT_API_KEY`,
`LIVEKIT_API_SECRET`, `FISH_API_KEY`, `FISH_VOICE_ID`, `GEMINI_API_KEY`,
optional `ROOM_PREFIX` (default `manz-`) and `VOICE_BENCH_LOG`. Gemini's
OpenAI-compatible endpoint and `gemini-3.8-flash` are fixed in the worker.
Fish synthesis remains `s2.1-pro`. `FISH_VOICE_ID` is a non-secret identifier,
not an API credential.

The evaluation server is pinned to LiveKit 1.13.10. Historical local checks
found participant attributes worked in 1.13.x, whereas 1.9.x dropped them.
That is transport evidence, not physical tablet or production qualification.

Use exactly **one backend process** with the existing local SQLite ledger and
**one owning worker process per room namespace**. Backend connection state,
worker room ownership and retry counters are process-local. Multiple workers
can claim the same room; more backend processes do not acquire distributed
ledger/dispatch ownership. The retry limits and duration reservations are not
fleet capacity or monetary limits. Defaults reserve 600 seconds per start and
allow six account-wide and three per-hotel starts per day.

Reject any production deployment requiring multiple owners, horizontal or
multi-region dispatch, or strict immediate token revocation. Those properties
are not implemented or qualified. Neither swapping dev secrets, adding a
replica, nor setting the opt-in supplies them. This repair adds no distributed
dispatcher, database replacement or production scaling recipe.

## Evidence

`scripts/livekit_synthetic_probe.py` exercises local transport, interruption
ordering and caption delivery without provider speech calls. It cannot prove
audible interruption, echo cancellation, accuracy or provider entitlement.
Historical scripted local results remain transport-only evidence.

`scripts/livekit_pipeline_bench.py` uses synthesized input and real provider
calls. It is not a tablet acoustic test or a matched hosted baseline. Its
standalone default LLM differs from the Gemini route; do not use that default
as a substitute for the approved pipeline. Any separately authorized run must
explicitly pin Gemini's base/model, establish remaining spend and reserve a
conservative provider-cost bound first. No paid run is authorized here:
the remaining portion of the historical aggregate $4 allowance is unknown.

For physical acceptance follow [TEST-PLAN.md](TEST-PLAN.md#voice-repair-reproduction-unreleased):
at least 100 distinct held-out hotel utterances per intended pair, repeated
trials, both directions, calibrated common-clock capture, bilingual
meaning/naturalness review, critical-entity denominators and all failures.
Exercise initial connection, greeting overlap, long-synthesis interruption,
same-language repetitions, unclear short words, names, room numbers, negation,
prices, dates, mute/unmute, rapid stop/start, network loss, expired initial
ticket, reconnect, revocation, refreshed tokens and room replay. Confirm no
stale translation or microphone resurrection.

No isolated matching physical tablet/capture, verified remaining budget,
current provider entitlement, bilingual reviewers or designated hotel staff
have been supplied for this repair. Provider, physical-audio and actual
staff-fulfilment acceptance remain blocked. A local/browser/native fixture
does not pass those gates; no comparable physical baseline means no measured
improvement claim.

## Preparing an isolated evaluation

These are prerequisites, not authorization to deploy or incur provider cost.
Merging source or publishing an APK does not deploy a backend or worker.

1. Establish authorized remaining spend, provider access, isolated property,
   exact APK identity, consenting reviewers and matching physical hardware.
   Stop before paid calls if any cost prerequisite is missing.
2. For local transport-only checks use the pinned LiveKit 1.13.10 server.
   `infra/livekit.compose.yaml` is evaluation-only: `--dev` and its public dev
   key pair must not be exposed as a hotel service. Its worker uses paid
   providers, so do not start the complete recipe without the cost gate.
3. For separately authorized tablet evaluation configure trusted TLS and
   controlled access. Keep exactly one backend/SQLite owner and one worker per
   room namespace, with the unchanged Fish/Gemini model and voice identity.
   TLS or real secrets do not turn this into a production deployment.
4. Configure the backend's LiveKit URL/key pair, `VOICE_ROUTE=livekit` and
   `ALLOW_UNQUALIFIED_LIVEKIT=1`. Use a managed enrollment for this isolated
   property; personal direct preference otherwise remains an independent path.
5. Qualify each intended hotel pair under the acoustic and recovery protocol.
   Hotel `supported_languages` bounds enrolled speech; staff configuration or
   a selectable language is not a passed qualification record.
6. To stop new evaluation reservations, remove the opt-in. To route new
   managed starts back to Fish, set `VOICE_ROUTE=fish`. Apply configuration
   through the service's normal controlled restart; recorded leases retain
   their original route for reconciliation/end/cleanup. Do not discard their
   ledger or replace unresolved ownership with a fresh request.

## Known limits

- The interface offers 52 languages. Speech selection offers the 28-language
  intersection verified for this pipeline, generated from
  `shared/translation-languages.json`. Fish's
  [realtime speech-to-text documentation](https://docs.fish.audio/features/speech-to-text/realtime#languages)
  lists 29 `language_code` values when language identification is available.
  That list is not acoustic qualification. A returned language outside the
  selected pair stays neutral and is not spoken. Every pair still requires
  provider and target-device acceptance before hotel deployment. All catalogue
  translations remain human-unreviewed. The chooser says: “Speech support
  depends on the provider and tablet. Staff qualification is required.”
  Passing one pairing does not qualify a language globally.
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
  replayed. Enrollment controls and room policy do not prove strict immediate
  revocation. Reject a deployment that requires it until server-enforced
  revocation, refreshed-token behavior and room replay are implemented and
  qualified; nominal expiry or room deletion cannot satisfy that gate.
  Failed deletion preserves the lease for retry; the admin response is HTTP 202
  with `voice_cleanup: pending` rather than claiming the room has closed. Staff
  can retry the same credential revocation after connectivity is restored.
- Historical scripted turn-pair results were synthetic-provider/transport
  evidence, not matched tablet captures. Device speaker playback and the
  acoustic echo path remain unqualified on physical target hardware.
- Fish synthesis for this route is 24 kHz PCM. The worker does not resample it
  to a 48 kHz room track. Playback completion follows the receiver's rendered
  head and EOF acknowledgement. On Android 8–11 the playback buffer must start
  within 4,800 frames so five full packets can start rendering before a short
  sixth packet exhausts the six-packet credit window. That is a renderer bound,
  not acoustic proof.
