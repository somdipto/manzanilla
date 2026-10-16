# Manzanilla remediation spec and UI integration contract

Status: repair checklist with the 17.0.6 visual cutover below; passing evidence
lives in `artifacts/VERIFICATION.md`. Historical audit baseline: 17.0.4, `04ae19a`.
Development acceptance procedures: [TEST-PLAN.md](TEST-PLAN.md).
Broader voice/privacy/benchmark gates: [issue #2](https://github.com/somdipto/manzanilla/issues/2).

## Product decisions

- **The approved 17.0.6 open-canvas design supersedes the earlier 3D handoff.**
  Preserve the original production logo, one continuous cyan/ivory gradient,
  navy surfaces and compact phone controls across every guest page and staff setup.
  Remove the obsolete character renderer and its bundled Three.js dependency.
- Preserve independent 52-language translation selectors and existing greeting
  assets. A listed language is not a claim of verified end-to-end voice quality.
- Day offers translation or assistance. Night goes directly to assistance
  language selection. Translation interprets; assistance answers using approved
  hotel information and authorized tools.
- Preserve package `dev.orange.hotel`, release signing identity and installed
  settings/keys/data. Updates install over the existing app, without uninstall.
- Keep provider names and secrets out of guest-facing screens. Staff configuration
  requires staff authorization, not merely a notification or hidden navigation.
- Shared Manzanilla provider keys belong in the backend. Existing supported BYOK
  device credentials require Keystore encryption; no plaintext fallback or baked
  shared master key. Preserve readable encrypted credentials during migration.

## Baseline score and evidence

This is a checklist score, not the percentage of bad source code or a measured
production-readiness score. Deduplicate the caption/shortcut contract defect.
Include the reception-language reset reproduced during the audit.

| Reviewed concerns | Count | Share |
| --- | --- | --- |
| Broken, incomplete or unsafe: R01-R17 below | 17 | 70.8% |
| Implemented or source-correct | 7 | 29.2% |

The seven positive concerns are independent language selectors, appearance
preference wiring, night UI navigation, Fish's greeting/mic gate, recording/backup
flags, previously checked release identity, and truthful local-save wording.
Source correctness does not establish physical-device acceptance.

Unknown audio quality, actual Android installation, provider entitlement,
language coverage and deployment/privacy acceptance are excluded from the score;
they remain release gates. Choosing 3D is not a defect. R11 concerns the frozen
renderer and incorrect state/playback integration.

## Development sequence

Work on a feature branch. Do not push to `main` or publish an APK during
remediation: the existing push workflow can publish before verification.
Finish each phase's observable gate before treating it as complete.

1. **Release/update integrity: R01, R05.** Establish one explicit monotonic APK
   version-code source, semantic release tags from `android/VERSION`, and a
   publication gate dependent on verification. Bind each download to its own
   immutable release metadata. Gate: comparator regressions, interrupted/resumed
   downloads and signed install-over-previous-release proof.
2. **Lifecycle and credential safety: R02, R06, R14-R17.** Centralize session
   ownership, terminal transitions and resource-release acknowledgements;
   preserve selected languages across host refreshes. Protect staff settings and
   remove plaintext credential fallback. Gate: restart after timeout, rapid
   start/stop, permission cancellation, pause/resume and stale-event isolation.
3. **Mode and utterance correctness: R03, R07, R08, R10.** Carry the chosen mode
   through permission requests and provider creation. Correlate original and
   translated utterances; apply greeting-before-capture to every supported route.
   Gate: both directions, overlapping/interrupted turns, Spanish assistance and
   distinct interpreter/concierge behavior.
4. **Guest UI and audio behavior: R04, R11-R13.** Migrate every producer/consumer
   to the contract below, retain message review and truthful audio state, and
   apply the approved shared theme. Gate: actual browser plus landscape Android
   WebView checks, including both appearances and reduced motion.
5. **Hotel operations: R09.** Connect assistance to the existing authenticated
   gateway, property knowledge, scoped PMS reads, staff requests and configured
   taxi/operator routes. Gate: synthetic-property receipts and denial/failure
   paths, followed by acceptance against the configured real services.
6. **Voice and pilot acceptance.** Execute issue #2's entitlement, A/B,
   multilingual, interruption, echo, lifecycle and soak gates; retain every
   known failure. Gate: the release evidence bundle below, not a successful
   build or a mock conversation.

Phases 4 and 5 can run in parallel after the session/event contract is fixed.
The integration owner owns that shared contract; avoid concurrent edits to it.

## Repair acceptance checklist

Each row requires a consumer-visible regression scenario, the implementation
change, and observed passing evidence. UI scenarios exercise the actual assets;
native/domain tests exercise behavior, not source strings or copied payloads.

| ID | Repair and implementation boundary | Required acceptance |
| --- | --- | --- |
| R01 | Align `ReleaseVersion`, Gradle version codes and release workflows. Use semantic publication tags and the APK's actual identity/version metadata. | A newer signed APK is offered despite unrelated workflow run counters; equal/older APKs are rejected. CI cannot publish an unverified candidate. |
| R02 | Reconcile terminal events in `HotelActivity` and both engines, including Gemini `idle`. | After inactivity shutdown, resources are released and the next conversation starts without leaving/reopening the activity. |
| R03 | Replace the single pending `heard` association in `FishVoice` with verified utterance/generation ownership. Do not assume FIFO if the provider can reorder output. | A and B finalized before output A, delayed output, and interruption never pair output with another original or speaker. Capture of interrupting speech is preserved. |
| R04 | Normalize caption delta/replacement events and shortcut notices at the native adapter; update all JS consumers together. | Input/output deltas append once, replacement text replaces, final original/translation records remain correctly paired, and check-in/taxi/question failures are visible. |
| R05 | Bind `Updater` download ID, URL, expected identity/version and available digest to an immutable request; inspect the completed APK before offering it. | Resume A while feed advances to B: A cannot be saved, advertised or installed as B. Cancellation, corruption and retry preserve correct ownership. |
| R06 | Mask `KeyEntryActivity` credential entry. | Typing/pasting a key does not reveal it in clear text; credentials are absent from logs, screenshots and guest UI. |
| R07 | Apply the greeting/capture gate to every supported provider path. | Greeting completes before capture starts regardless of which becomes ready first; cancellation/error cannot later open the microphone. |
| R08 | Carry `translation` versus `reception` into native/provider/gateway session creation; replace the OpenAI-only night route for the selected Fish/Gemini architecture. | Interpreter translates without answering or invoking tools; concierge answers approved hotel questions and can use allowlisted tools. No silent interpreter fallback for assistance. |
| R09 | Implement check-in guidance, human escalation, taxi requests and questions through existing gateway authorization/confirmation/idempotency boundaries. | Receipts identify the real outcome. Queued is not booked, dialer-opened is not answered, guidance is not completed check-in, and missing configuration gives actionable unavailable state. Real-service acceptance remains pending until the integrations exist. |
| R10 | Scope unequal-language validation to translation mode. | Spanish assistance works with Spanish staff configuration; equal-language translation still cannot start. |
| R11 | Apply the approved 17.0.6 open-canvas design, original logo and continuous gradient; remove the obsolete 3D renderer. | Home, paired language selectors, conversation, forms and notices share the theme. Real scoped microphone input drives the input meter; playback never impersonates input. |
| R12 | Restore visible audio privacy state in the guest UI, backed by actual capture/release acknowledgements. | Connecting/greeting does not falsely say mic-on; recording, stopping, off and unknown states remain visible and truthful in both themes. |
| R13 | Remove the conflicting assistance render branch and restore the message action/review/save/cancel path. | A guest can reach review, cancel without saving, and explicitly save. Local-save copy stays truthful; staff delivery requires a separate real backend receipt. |
| R14 | Make `SecretStore` fail closed when secure storage is unavailable. Remove the `pl:` write path and explicitly migrate readable existing plaintext entries into encrypted storage. | Keystore failure never stores a new plaintext key or reports secure save. Migration preserves readable credentials or presents an explicit recoverable error; no silent deletion. |
| R15 | Fence callbacks/events and delayed jobs by session/generation; stop engines and reconcile UI on pause, sleep, disconnect, exit and cancellation. | Error/stop/pause followed by a late speaking/audio/ready event never revives the session. No microphone/socket/playback resurrection; the next guest gets no previous draft/caption. |
| R16 | Gate native staff setup and configuration-changing actions using existing backend staff authorization or a device-bound staff credential with protected setup/recovery. | Guest access is denied; authorized staff can configure keys; cancel, relaunch and notification deep links cannot bypass authorization. No default/shared PIN embedded in the APK. |
| R17 | Separate initial hotel-default language from the current selected/persisted reception language. | Select Deutsch/French, refresh host info and pause/resume: neither selection silently changes to Spanish. Explicit staff changes are the only override. |

## Shared session/event contract

This section specifies target behavior, not the current implementation.

- `start` carries `mode`, `language`, `staff_language` and a unique session
  correlation identifier. Native code retains the whole request across microphone
  permission handling. Night selects `reception`; it is not an appearance theme.
- Each native event carries session/generation ownership. Caption events also
  carry utterance identity, ordering/finality and language/side metadata. Provider
  IDs and fields must be checked against the real SDK/protocol, not invented.
- The UI joins caption fields only with the same session, original speaker side
  and `utterance_id`. Original speech is above its opposite-language translation;
  guest bubbles sit left and receptionist bubbles right in one chronological scroller.
  Fish segment IDs identify speaker revisions, not translation pairs; Gemini Live
  supplies no documented original/output association. Those feeds remain explicitly
  unlinked. Never infer a pair from arrival order or claim live pairing is solved.
- Normalize provider input/output deltas, replacements and final records once at
  the native boundary. Define the concrete schema there before editing callers,
  then migrate both providers, host, bridge allowlist and UI in the same cutover.
  Shortcut/system notices are not fabricated guest speech.
- Keep existing actions `info`, `start`, `stop`, `appearance`, `night`, `bell`,
  `settings`, `checkin`, `operator`, `taxi`, `question`, `message`, `confirm`,
  `silence`, `activity`, `exit`, `apply_update`. Native staff setup owns key entry;
  a dead WebView `save_fish_key` route is not part of this contract.
- `stop` acknowledges completion only after recording/playback are stopped and
  the transport is detached. Terminal states clear native and UI ownership.
  A delayed event from an old session cannot transition a new/closed session.
- Audio state is explicit, separate from provider thinking/listening/speaking.
  Only real scoped `mic_level` while recording drives the microphone input meter.
  Stop/restart and conversation Back wait for terminal state and confirmed audio off.
  Android Back dispatches `navigation/back`; internal navigation never exits the app.
- `reception_info` initializes hotel defaults without overwriting an active
  selection. Appearance/night restore from host preferences. Keep update
  offer/progress/confirmation events separate from voice-session state.
- Meaningful activity feeds a native inactivity watchdog. Provider notifications
  and animation frames are not guest activity. A call-duration cap and a
  cumulative monetary budget are separate controls.
- Message drafts belong to one session. Review cannot be displaced by unrelated
  captions; confirmed writes are idempotent, and a new guest starts clean.

## Visual and interaction acceptance

Use the approved open-canvas navy reception design and original logo. Appearance
preference must survive restart; appearance and night mode remain independent.
Translation opens two native 52-entry language selectors and explicit Start.
Assistance starts after choosing the guest language; the greeting plays before
capture. Preserve the four shortcuts and reachable message review.
Keep digit-key selection and touch working; do not claim 52 unique single-digit
shortcuts. Check green/red/back hardware behavior on the target device.

Verify actual landscape Android WebView pixels and controls at least 48 CSS px.
Cover 640×360, 740×360 and 844×390 phone layouts, RTL and long captions. Transcript
scrolls as one conversation while headers and audio controls stay fixed. Linked
caption fixtures prove the renderer, not upstream provider association or speech.


## External prerequisites and release gate

Implementation of repository-local repairs can start from this spec. Full
product acceptance additionally requires:

- A bootable landscape Android emulator and the target physical audio hardware.
  Earlier AVD boot failures remain unresolved; browser proof is not Android proof.
- Secure provider access plus verified Agents/ASR/voice/LLM entitlements, actual
  model selection, tariffs, grant eligibility and retention settings.
- An approved backend deployment for managed shared keys, hotel knowledge and
  staff/device authorization; real PMS vendor access and operator/taxi routes.
- Spain/EEA deployment and provider-retention approval before real hotel guests.

Use isolated fixtures and synthetic hotel data; never mutate the user's live
installation/data for verification. Production connections are not substitutes
for a disposable acceptance environment.

Keep aggregate live benchmark spending within the user's $4 maximum across
providers/devices/sessions, with no automatic paid fallback or top-up. Establish
safe pricing-based reservations and fail-closed cutoff before paid runs.
If the full language/soak/benchmark program exceeds that allowance, record the
unmet gates and obtain a budget decision; do not silently shorten acceptance or
claim full-duplex quality from a small smoke run.

Before publication require R01-R17 evidence, preserved positive behaviors, real
landscape UI/audio proof, language-quality/latency/interruption/echo results,
session/resource soak results, provider cost/retention evidence and real-service
receipts. Verify same package/signing identity, a higher version code and
install-over-previous-release with saved configuration intact. Android may
require install confirmation; silent installation is not guaranteed.

Each result records source commit, artifact hash/version/certificate, scenario,
observed result and limits. Any unverified or failed release gate remains open.
Planning completion is not implementation completion or permission to publish.

