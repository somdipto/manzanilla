# Self-hosted voice route: bounded evaluation only

`VOICE_ROUTE=fish` remains the default. `VOICE_ROUTE=livekit` selects a
separately operated LiveKit/worker evaluation route only when the backend also
has `ALLOW_UNQUALIFIED_LIVEKIT=1` for a genuinely new reservation. The opt-in
does not qualify audio, provider access, security or production ownership.
Personal direct Fish testing is independent of this gate.

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
  Owned input streams and output sources close before room disconnect; stopping
  the worker does not leave those native audio handles for interpreter shutdown.

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

Backend: `VOICE_ROUTE=livekit`, explicit `ALLOW_UNQUALIFIED_LIVEKIT=1`,
`LIVEKIT_URL` (wss:// for the tablet), `LIVEKIT_API_KEY`, `LIVEKIT_API_SECRET`.
Leave the opt-in disabled outside authorized evaluation.

The backend looks up that caller's recorded request before the new-reservation
gate. Withdrawing the flag does not prevent startup, recorded-request
reconciliation, end, recovery or cleanup. An existing request must not receive
a proven-noncreation receipt solely because the flag changed. A rejected
genuinely new request creates no reservation, room or token.

Worker: `LIVEKIT_URL`, `LIVEKIT_API_KEY`, `LIVEKIT_API_SECRET`, `FISH_API_KEY`,
`FISH_VOICE_ID`, `LLM_BASE_URL`, `LLM_API_KEY`, `LLM_MODEL`, optional
`LLM_EXTRA_JSON`, `ROOM_PREFIX` (default `manz-`), `VOICE_BENCH_LOG`.
The supplied recipe retains Gemini's OpenAI-compatible endpoint and
`gemini-3.8-flash`; Fish synthesis remains `s2.1-pro` and the configured voice
is unchanged. `FISH_VOICE_ID` is a non-secret identifier, not an API credential.

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

- The app offers 52 conversation languages, while Fish's
  [realtime speech-to-text documentation](https://docs.fish.audio/features/speech-to-text/realtime#languages)
  lists 29 `language_code` values when language identification is available.
  This is a limit on the documented attribution labels, not proof that speech
  in other languages cannot be recognized. The self-hosted worker speaks a
  translation only when the returned language code matches one of the selected
  languages; otherwise it marks the turn `und` and does not speak it. Pairs
  outside the documented labels, and recognition and direction for every pair,
  require real provider and target-device acceptance before hotel deployment.
  Keep all 52 interface choices and explicit personal testing of unqualified
  pairs. All catalogue translations remain human-unreviewed. The chooser says:
  “Speech support depends on the provider and tablet. Staff qualification is
  required.” Neither vendor support nor staff configuration is acoustic
  qualification, and passing one pairing does not qualify a language globally.
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
- Fish PCM output tops out at 44.1 kHz; the worker synthesizes at 24 kHz and
  resamples to the room's 48 kHz.
