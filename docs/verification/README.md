# Isolated verification

Use synthetic fixtures and temporary databases. Never run verification writes
against a live hotel service or guest data. See [the full test plan](../TEST-PLAN.md)
for acoustic, Android ownership, update, quality and spending gates.

## 17.0.45 qualification-gates candidate (S8)

Version 17.0.45/code75. Fish remains the default; LiveKit is explicit evaluation,
not a substitute for unavailable provider or physical acceptance.

- **Passed:** 764 integrated Python cases, 17 Node cases, catalogue generation/check and ten
  Chromium151 scenarios with the existing selected-locale qualification notice
  visible. A deleted host browser cache was restored into task-owned storage.
- **Passed:** actual isolated HTTPS API across backend restarts with opt-in
  absent, enabled and withdrawn. New rejection creates no reservation; one
  admitted request remains recorded, replay is not falsely labelled not-created,
  and end/cleanup still completes against the actual local room service.
- **Passed:** zero-paid synthetic transport with the actual LiveKit1.13.10 server
  built from official tag `e5655ac20267fa5005dccaf3f0e3bfc3ea688c07`.
  Device audio reached the worker during its greeting; interrupted playback
  flushed before replacement audio, and the matching caption reached the client.
  This is local transport/ordering, not acoustic latency, accuracy or naturalness.
- The CLI initially lacked the repository import path. It now uses the existing
  script-root convention. Owned RTC streams/sources close before disconnect;
  the rerun has no FFI teardown assertions and reports PASS only after cleanup.
  The 36 worker tests pass. The known short dev-key warning is intentional in
  this isolated evaluation; it is not a production credential configuration.
- Private `work/voice-acceptance/qualification.json` initializes 159 route/pair
  records covering all52 offered codes plus English/Arabic, all `not_tested`,
  with no device, artifact digest or manufactured capture evidence.
- **Blocked:** matching physical tablet/captures, bilingual review, actual
  designated hotel staff and approved vendor test environments, live provider
  qualification and verified remaining aggregate $4 allowance. No paid call,
  acoustic-improvement claim, release or deployment was made.

## 17.0.43 installer-capability candidate (S6)

Version17.0.43/code73. Callback identity is private per attempt and never logged.

- **Passed:** actual disposable API26/29/32/34 callback execution in
  [run40888340045](https://github.com/somdipto/manzanilla/actions/runs/40888340045).
  API31+ also exercises the actual delivery/session-ownership boundary.
- **Passed:** API34 actual Updater dispatch and trusted Android positive consent,
  followed by exact installed candidate bytes, package/code, disposable signer
  and a fresh retained preferences/file/encrypted-key check, named PASS/-1.
  The in-place QA transition is99.0.0/code9900 →99.0.1/code9901.
- Replacement terminated its instrumentation process. That is not acceptance:
  the host independently verified the candidate and the fresh retained-data run.
  No candidate was installed through adb as the update under test.
- The first local attempt legally updated silently and was not counted as
  genuine consent. A private debug-only manifest overlay omits unattended-update
  permission to require the real Android prompt; production permission/policy
  remains unchanged. Unknown foreground windows never authorize a click.
- Instrumentation APKs legitimately lack app version fields. Their package,
  debuggability and signer are checked separately; application identity remains
  strict. Incidental permission/parameter-copy assertions were removed.
- These synthetic disposable-signer checks do not certify physical OEM behavior
  or replace the original-signer, actual packaged-Reception release gates.
  No provider session, live-device mutation, publication or deployment occurred.

## 17.0.42 staff fulfilment candidate (S5)

Version 17.0.42/code72, stacked on S4. Staff lifecycle metadata is separate from
immutable execution receipts; guest status contains no payload or private note.

- **Passed:** 638 Python cases, 17 Node cases and catalogue generation/check.
  Coverage includes transactional configuration CAS, claim races, ownership,
  idempotent transitions, legacy resolution, revocation and exact session privacy.
- **Passed:** ten Chromium151 scenarios including actual temporary backend/staff
  interactions: blank creation, labelled dictionary-preserving settings, dirty
  configuration conflict, acknowledgement/resolution, retained private outcomes,
  scoped/hidden single-flight refresh, RTL and immutable guest receipts.
- The delayed-overview regression initially lost a successful acknowledgement
  through a detached row reference. Applying the result to the current item by
  ID preserves the newer version; the actual browser scenario now passes.
  Ownership denial refreshes the row without granting the denied actor access.
- A held configuration save reproduced a disabled new-hotel form after a scope
  switch. Scope reset now releases its own busy state; a late old response cannot
  clear a newer save. The real-backend browser regression passes both boundaries.
- Successful acknowledgement/resolution consumes only its submitted draft.
  Resolved private outcomes no longer appear twice; conflicting edits remain
  available for review. The browser suite passes with clean fixture teardown.
- The browser fixture installs its context-wide clock once, before any page
  timers. Refresh waits observe actual overview output, including an in-flight
  visibility refresh whose response headers already arrived; they do not require
  an extra HTTP response. The ten real-backend Chromium scenarios pass with
  these synchronization corrections.
- **Passed:** personal-test JVM, debug/instrumentation assembly and lint with
  JDK17/SDK34 and empty provider fields.
- **Passed:** actual API34 `reception-request-tracking` through the shipped
  WebView, bridge and Activity with synthetic HTTP interception, named PASS/-1.
- **Passed:** real native `HotelGateway` plus actual staff browser against a
  temporary-CA isolated HTTPS backend: one original-preserving request, open →
  acknowledged → resolved, private outcome excluded from guest frames, and one
  operation session ended without polling recreating it. The private smoke
  reports named PASS/-1; its throwaway source was removed after proof.
- Private screenshot: `/tmp/manzanilla-s5-https-fixture-20261029/staff-resolved.png`.
  These are native-client/backend and separate native-UI proofs, not combined
  tablet/backend, designated hotel-staff, provider or external booking acceptance.

## 17.0.41 readiness/distribution candidate (S4)

Version 17.0.41/code71, stacked on S3. The canonical catalogue has 410 messages
for 52 interface locales, all human-unreviewed. Proper brand names now retain
Orange; obsolete keypad hints and unused per-turn scope storage are removed.
Session/generation identity remains part of each immutable report key.

- **Passed:** generation/check, 17 Node cases, 602 Python tests (two dependency
  deprecation warnings), and ten Chromium151 scenarios. Coverage includes
  per-service availability, old-backend capabilities, revoked/offline states,
  independent direct translation, Spanish/Arabic locale changes, preserved form
  focus/input, exact uncertain replay, first-confirm gating and hotel/personal
  maintenance visibility at both small viewports and enlarged text.
- **Passed:** personal-test Android JVM, debug/instrumentation assembly and lint
  with JDK17/SDK34. Despite injected dummy provider values, APK dex inspection
  proved `DISTRIBUTION=personal-test` and both Fish provider fields empty.
  APK SHA256:`741cc7012909cf9bcd706f0da0279ddbec908476a29fd57d5b4e276d75f215df`.
- **Passed:** actual API34 `hotel-setup-gates`, named PASS and instrumentation
  code -1: protected settings/translation routes, absent-lock handling, PIN
  reauthentication, personal-key retention, no direct hotel fallback and the
  maintenance allowlist. The restored direct reservation initially stranded
  cleanup before enrollment; foreground retry of the original hash-fenced end
  fixes it without permitting a new direct session.
- **Reproduced/fixed:** an uncertain no-form help result lost its exact retry
  when readiness or a capability changed. The shipped browser regression now
  passes with retained action/arguments, single-flight replay and no false receipt.
- **Passed:** complete actual API34 hotel suite: `reception-bridge`,
  `reception-truth`, `hotel-readiness` and `hotel-setup-gates`, each named PASS/-1.
  Readiness covers delivered WebView/native states, safe capabilities, stale
  responses and pause/destroy cancellation without provider calls.
- The readiness observer initially tried to replace a frozen API member.
  It now preserves the frozen public envelope and observes delivery. Teardown
  waits for actual Activity destruction, not merely an idle main queue.
- Probes do not create sessions or open a microphone. Service flags describe
  configuration, not provider access or a live staff watcher. No paid provider
  call, live data mutation, release or deployment occurred.

## 17.0.40 approved-content candidate (S3)

Version 17.0.40/code70, stacked on S2. The offline catalogue now has 396 messages
for all 52 interface locales; human translation review remains unavailable.

- **Reproduced:** the actual isolated S2 backend returned configured breakfast
  hours for `breakfast` but no matches for `breakfast?`, with zero provider calls.
- **Passed:** generated assets/check, 17 Node cases and 574 Python tests (two
  dependency deprecation warnings). Approved-content coverage includes exact
  English/Spanish punctuation, Arabic, absent approval, draft isolation, invalid/
  hostile IDs, context limits, duplicate titles, source/rendering changes during
  HTTP and settlement, closed/revoked sessions, replay withdrawal and unchanged
  durable write receipts.
- **Passed:** ten Chromium151 scenarios, including the actual temporary backend
  and admin review panel. With provider credentials blank, drafting fails without
  erasing manual text; explicit approval enables the exact Spanish rendering;
  source change rejects stale approval and withdraws replay. Review labels render
  in all 52 locales. An unanswered FAQ creates no staff item automatically.
- **Passed:** Android JVM, debug/instrumentation assembly and lint with JDK17,
  SDK34 and empty provider fields. The pinned code70 debug APK SHA256 is
  `350d18b0c8ed568c1b655606027fc6399fec894fd302ac09572a42f1ec7eb216`.
- **Passed:** actual isolated HTTPS admin interaction manually approved English
  breakfast, exact Spanish breakfast and Spanish `desayuno` renderings. Missing
  draft-provider configuration preserved manual edits; Spanish/Arabic RTL review
  retained the selected rendering. Private screenshots remain in
  `/tmp/manzanilla-s3-https-fixture-20261029/`.
- **Passed:** the shipped native `HotelGateway` on isolated API34 used that real
  HTTPS backend with a temporary-CA/custom-client fixture, leaving production TLS
  unchanged. Punctuated English, two exact approved Spanish answers, unknown
  policy and unapproved Arabic behaved as required. Three operation leases
  settled, with zero staff items or dial handoffs. `hotel-answers-smoke` reported
  named PASS and instrumentation code -1; its throwaway source was removed.
- This is native-client/backend communication, not a combined tablet UI test.
  That optional gate still needs a device-trusted isolated test origin; human
  bilingual review, actual Gemini access and physical/acoustic gates remain open.

Information reads are scoped to their call ID, so a genuinely new question can
see a later approval. Replaying the same ID never charges another model call.
Write deduplication, confirmation identities and durable write receipts are
unchanged. Missing approved arrival guidance preserves unavailable semantics.

These are source, isolated HTTP and packaged-renderer results, not paid Gemini
entitlement, human bilingual review, tablet/backend or acoustic qualification.
No paid provider call, live hotel mutation, deployment or publication occurred.

## 17.0.39 complete offline interface candidate (S2)

Stacked on S1, version 17.0.39/code69. The source catalogue has 380 English message
IDs with complete translations for all 52 offered interface locales. None has
human language-review approval; translation presence is not acoustic qualification.

- **Passed:** generation/check, 17 Node cases and 463 Python tests (two dependency
  deprecation warnings). Coverage includes catalogue rejection, pinned producer
  locale, preserved originals/identities and immutable historical receipts.
- **Passed:** Android JVM, debug/instrumentation assembly and lint with JDK17,
  SDK34 and empty provider fields.
- **Passed:** nine Chromium151 scenarios with unchanged packaged CSP/resource
  policy, including all52 home lookups, Spanish navigation/error/forms/review/
  cancellation/receipt/retry, Arabic RTL with Spanish reception, 125% text at
  837×471 and 1024×600, both appearances, focus/unsent text and physical sides.
- **Passed:** actual isolated HTTPS backend and staff dashboard. A synthetic
  Spanish operation produced a confirmation and persisted receipt preserving its
  exact original. The actual admin page displayed configured legacy-invalid
  timezone as labelled UTC, switched Spanish/Arabic RTL and retained token,
  hotel and dirty form. Private screenshots: `/tmp/manzanilla-s2-admin-fixture/`.
  This is not combined physical-tablet/backend or designated hotel-staff acceptance.
- **Passed:** actual microphone grant on disposable API34/WebView113 with host
  OpenGL. The pending lease survived consent, the empty injected Fish resolver
  ran exactly once, and the session released without a provider call.
- **Passed:** `reception-bridge`, `reception-truth`, microphone grant, denial and
  background cancellation, `translation-startup` and `update-delivery` on both
  API34/WebView113 and CI Android10 run40853905862; each named PASS has code -1.
  System-only HOME resolution repaired the earlier background precondition.
- **Passed:** final CI run40858640035 at commit74cf360 completed all nine Android10
  reception, permission, startup and setup scenarios, including personal save,
  cancel and encrypted-key reuse both without a lock and with synthetic PIN2468.
  The separate Android12 update-delivery case also passed. Every named case
  includes instrumentation code -1; no provider session was opened.
- Earlier personal-setup fixtures incorrectly selected Spanish controls by
  English prose. Password-role/locale-aware selectors and consumer-visible
  retention/authorization checks replaced those incidental assertions.
- On this Intel Mac, emulator37.2 SwiftShader produced a System UI ANR over the
  real permission dialog and an invalid color-buffer error. `-gpu host -feature
  -Vulkan` yielded the grant pass. A later full-suite attempt while Gradle was
  running failed to attach its first process; that attempt is not a pass. Run
  heavy builds and native UI checks separately on this host.
- Follow-up catalogue data preserves the proper name Orange across all52
  locales. Permission fixtures seed only synthetic local readiness, retain an
  empty provider resolver, dismiss only Android's known fullscreen tutorial and
  assert a recoverable error rather than incidental English wording. Temporary
  diagnostic logging, screenshot capture and raw logcat collection were removed.

The macOS fixture used a dedicated loopback-only Chromium/Playwright context and a
temporary TLS identity. Production TLS, Android CSP and network restrictions were
not relaxed. No live hotel data, provider session, paid call, publication or
deployment was involved.

## 17.0.38 caption-evidence repair candidate (S1)

Baseline `a2b46ea84c0d6ab65b9f1a7fd94975ce69d7554f`, 17.0.37/code67.
Candidate 17.0.38/code68. No release, deployment or paid provider call.

- **Passed:** direct execution of the baseline shipped ledger reproduced an
  unclear report inheriting `staff` and retaining a whitespace translation.
  Candidate Node suite passes 15 cases, including every ordered picker pair,
  neutral uncertain reports, exact real text, late annotations and session fences.
- **Passed:** 459 Python tests (two dependency deprecation warnings), including
  complete/incomplete tool lookup, immutable compatible reuse and webhook defaults.
- **Passed:** Android JVM tests, debug/instrumentation APK assembly and debug lint
  with JDK17/SDK34; provider fields were empty in this verification build.
- **Passed:** all five isolated Chromium151 scenarios. Capture cases drive level
  0.8/bands 0.7,0.5,0.2 followed by muted/off/unknown, an unrelated render and a
  stale generation. All inline levels become zero, accessible labels agree and
  transcript nodes/scroll survive. A separate actual-page visual inspection at
  837×471 shows a neutral unclear caption and a non-illuminated muted control.
- **Passed:** actual packaged native `reception-bridge`, `reception-truth` and
  `translation-startup` on disposable API34/WebView113 emulator-5580. Each named
  PASS includes `INSTRUMENTATION_CODE: -1`. The truth fixture now emits listening
  before testing normal microphone-off; a still-connecting session correctly has
  the separate connecting/microphone-off label. Failed fixture attempts are retained
  in the execution record; no failed attempt was counted as a pass.
- **Renderer observation only:** one pinned-baseline 120-report replay measured
  160.7ms; one candidate replay measured 84.9ms on this host under different
  background load. These are descriptive DOM timings, not a controlled performance
  result and never acoustic latency evidence.

Commands: `npm run build:reception`, `npm run check:reception`,
`node --test tests/*.test.cjs`, `.venv/bin/python -m pytest tests -q`,
`.venv/bin/python tests/run_reception_browser.py`, and
`bash gradlew :hotel:testDebugUnitTest :hotel:assembleDebug
:hotel:assembleDebugAndroidTest :hotel:lintDebug --no-daemon --max-workers=2`.
Native command: `ANDROID_SERIAL=emulator-5580 adb shell am instrument -w -r
-e case reception-truth dev.orange.hotel.test/com.agentdeck.hotel.VoiceLifecycleFixture`.
The pinned production app is from commit `e5a71da`; later test-only changes improve
bounded diagnostics and correctly distinguish connecting from listening scenarios.
No microphone, live provider, physical tablet or audible-latency result is claimed.


## 17.0.31 full-duplex and delivery candidate

Base merged main `eb6e7fa`, version 17.0.31/code 61. This is a source candidate,
not a published release or a deployed backend. See the [local synthetic LiveKit
A/B evidence](livekit-synthetic-2026-10-27.txt) for exact artifacts and scope.

- Full Python suite: **405 passed**, with two dependency deprecation warnings;
  local worker dependencies were installed separately in a temporary directory.
- Node: **14 passed**; generated reception assets match source. All **five**
  isolated Chromium154 scenarios passed, including stopped-session retry and
  assistance-language focus preservation.
- Android JVM: **138 passed**, zero failures/errors/skips. Debug lint: **zero
  errors**, 14 warnings. Debug and instrumentation APK assembly passed with
  the pinned-source AudioSwitch substitution described below.
- The local Android10 in-place upgrade from 17.0.30/code60 to 17.0.31/code61
  retained the signing certificate, UID, first-install time, saved preferences,
  a private file and a synthetic encrypted Keystore marker. There was no clear
  or uninstall between seeding and verification. This used a local debug signer;
  signed publication repeats the test with the actual release key.
- The subsequent `am start -W` timed out on the overloaded software emulator,
  which already had a System UI ANR before installation; logcat later showed the
  activity displayed. This does not establish clean local startup. The PR's
  accelerated Android emulator job must independently check the shipped APK.
- LiveKit 1.13.10 with synthetic audio and fake speech/model providers: the
  prior main worker did not receive the microphone during its greeting; this
  candidate received it, handled a simulated user-turn interruption, emitted
  audio and delivered a matching caption. No provider or acoustic claim follows.
- An isolated 19-chunk PCM fixture went from 380 ms of padded frames to 60 ms
  with the same resampled samples and one terminal pad. This is a deterministic
  pipeline fixture, not a measured speech-to-speech latency improvement.

Current Fish and language-model credentials or verified remaining paid-test
budget were not available locally. No paid calls, real guest data, physical
tablet, live voice-quality comparison or deployed-service change was involved.
Android12 unattended-update eligibility and Android reconnect after a short
initial LiveKit token need their respective CI/physical-device checks; Android
still controls mandatory installation confirmation. The self-hosted route
currently requires a single worker per room namespace and has additional
[revocation and language-attribution limits](../selfhosted-voice-route.md).

## 17.0.28 Android startup and release delivery

Base merged main `02dce27`, version 17.0.28/code58. See
[reproduction and changes](../android-start-fix.md). Tested on 2026-10-22 with a
disposable Android10/API29 x86_64 emulator, stock WebView74.0.3729.185, software
CPU/GPU emulation, and key-free APKs. This environment now has an actual adb
target; earlier candidate sections below retain their historical limitations.

- Prior APK reproduced `Unexpected token ?` in both shipped interface scripts.
- Patched real WebView/native bridge instrumentation passes cold initialization,
  queued preferences, appearance action and Start with the selected pair/UUID.
- Initial microphone permission reproduced cancellation of the pending session;
  the repaired grant path preserves the actual JS session and resumes once.
  Denial and true-background cancellation both pass without starting Fish.
- Python: **260 passed**. Node language ledger: **14 passed**.
- Chromium154: **five browser scenarios passed**, including untouched packaged
  HTML/CSP/resource loading and missing newer Object helpers.
- Android JVM: **110 passed**, including six native loopback HTTPS note cases.
- Android lint: **zero errors**, **14 warnings**.
- Debug and instrumentation APK assembly: **passed**, provider fields empty.
- Local dependency qualification is the same temporary pinned-source AudioSwitch
  substitution described below; GitHub Actions resolves the normal artifact.
- No physical acoustic-latency result or live provider/hotel mutation is claimed.

Generated JavaScript is verified with `npm ci && npm run check:reception`.
`bash scripts/run-android-emulator.sh` executes the committed native runtime
fixtures on a disposable emulator; it rejects a failed instrumentation result
rather than relying on adb's process exit code.

## 17.0.27 translation and reception candidate

Base `b1835c1`, version 17.0.27/code 57, Fish config revision 14. Tested on
2026-10-22 using isolated databases, fake provider transports and browser bridges.
No real provider session, live hotel write, production deployment or release tag
was created. See [investigation and device requirements](../realtime-reception-fix.md).

- Python: **260 passed**, one existing AnyIO deprecation warning.
- Node language ledger: **14 passed**, covering all 2,652 distinct ordered pairs.
- Chromium **154.0.8037.57**: all **three** committed browser fixtures passed:
  captions/session fencing, **24** notes/confirmation checks, and day/night
  appearance/preferences/navigation. Separate appearance runs covered 837×471,
  640×360, 740×360 and 844×390 landscape viewports. These are simulated browser
  interactions, not microphone or Android installation evidence.
- Android JVM: **104 passed**, zero failures, errors or skips. Includes timing
  queue/idle PCM behavior, v2 prompt provisioning, actual HTML/native asset
  compatibility and worst-case escaped note bridge requests.
- Android lint: **zero errors**, **17 warnings**.
- Debug APK and instrumentation APK: **assembled**. Instrumentation was not run;
  no adb target, emulator/system image or hardware acceleration was available.
- Debug package `dev.orange.hotel`, version **17.0.27/code 57**, minSdk 26,
  targetSdk 34. Generated Fish key/voice fields are **empty**. Packaged assets
  match source. All 99 captured relevant source hashes remained stable during
  the final build.
- Final local debug APK SHA-256:
  `41b9ac13466ced2520dc4bfdf6f88f9a590cdfc0896b6fdef46b311bf31a8873`.
- Physical acoustic latency, provider speech-before-caption behavior, bilingual
  accuracy, interrupted-report coverage and actual hotel delivery: **not run**.

### Local Android dependency qualification

This environment blocks `jitpack.io`, preventing retrieval of the pinned
`com.github.davidliu:audioswitch:039a35aefab7747c557242fa216c9ea11743b604` binary.
The exact public GitHub source commit was built into a temporary local Maven
repository and supplied only through a temporary Gradle init script. Production
build files and dependency coordinates are unchanged. The temporary build used
AGP 8.2.2/Kotlin 1.9.22/SDK 34 and the original manifest permissions and dependency
list; the manifest package attribute was moved to the AGP namespace setting.
This establishes application compilation/tests against that dependency source,
not byte-for-byte validation of the published JitPack AAR. Normal GitHub Actions
must still resolve and verify the production dependency artifact. The local debug
APK is a verification artifact, not a production update.

```sh
python -m pytest tests -q
node --test tests/*.test.cjs
pip install -r requirements-browser.txt
python -m playwright install chromium
python tests/run_reception_browser.py
# Or use a system installation: --chromium /usr/bin/chromium
cd android
bash gradlew :hotel:testDebugUnitTest :hotel:lintDebug :hotel:assembleDebug :hotel:assembleDebugAndroidTest --no-daemon
```

## 17.0.26 language-side candidate

Base: merged main `bf9e36b` (includes latency PR #3). Source version 17.0.26,
code 56; config revision 13. No signed release, live provider mutation or paid
session was made during verification.

Completed in this isolated workspace on 2026-10-21:

- Full Python suite: **257 passed**, one existing AnyIO deprecation warning.
- Node language-ledger suite: **14 passed**, including all **2,652 ordered picker
  pairs** with both directions, repeats and uncertain-language continuity.
- Focused Kotlin/JVM compilation and tests: **17 passed** for FishPlan,
  InterpretationReports and VoiceSession. Kotlin 1.9.22/JDK 17, real JSON/JUnit
  dependencies; these are the actual production classes, not rewritten fixtures.
- Real Chromium 133 browser fixture: **passed** at 837 × 471; model-language
  placement, physical sides, repeated speech, late labels, finality, escaping,
  scrolling, microphone state and session fencing. A 120-report DOM replay took
  approximately **31 ms**. This is renderer time, not audio latency.
- Current synthetic screenshot: [French/German fixture](../screens/17.0.26-language-fixture.png),
  captured at 837 × 520 to show the three complete bubbles. No microphone,
  real guest conversation or physical device was involved.
- Caption tool and tool-enabled agent-creation bodies: **validated** against
  Fish's official current OpenAPI contract using JSON Schema.
- Full Android Gradle build/lint and the complete JVM suite: delegated to the
  normal GitHub Actions checks on the PR. The local workspace has no Android SDK.
- Physical-device bilingual accuracy, report coverage and added first-audio
  latency: **not run**. See [the acceptance gates](../language-bubble-fix.md).

```sh
python -m pytest tests -q
node --test tests/reception_language_turns.test.cjs
# In an isolated browser, import tests/reception_caption_fixture.js and run its fixture.
cd android
bash gradlew :hotel:testDebugUnitTest :hotel:lintDebug :hotel:assembleDebug :hotel:assembleDebugAndroidTest --no-daemon
```

## 17.0.25 latency candidate

Base: 17.0.24, `8b7c006b53598f275b73c171a6d44b5f646f923d`.

The implementation was verified in the original isolated workspace on 2026-10-21
before preparing the pull request:

- Baseline Python suite: **243 passed**.
- Focused provider suite: **116 passed**.
- Read-only history diagnostics: **6 passed**.
- Full candidate Python suite: **251 passed**; one existing AnyIO deprecation warning.
- Android JVM tests: **86 passed**, zero failures/errors/skips.
- Android lint: **passed**, zero errors and 18 warnings.
- Debug APK and instrumentation APK assembly: **passed**. The instrumentation
  APK was built; it was not run on an emulator or physical device.
- Compiled debug manifest: package `dev.orange.hotel`, version `17.0.25`, code
  `55`, minSdk 26, targetSdk 34. Generated debug provider credentials were empty.
- Original debug APK SHA-256:
  `5c51b41e46635310cf4df810abaed559790325fdde86d5f63ec7a1fab2719806`.
- Candidate agent configuration and nested session override: **validated**
  with JSON Schema against Fish's official OpenAPI contract.
- Pinned WebRTC `144.7559.14` AAR: inspected the actual bundled builder bytecode;
  `useLowLatency` initializes to `false`, and `setUseLowLatency(boolean)` exists.
  AAR SHA-256: `ddfb649376627be094c7a2a6af8423afc6c4d6f636ef632242b6d520636ecd3a`.
- Physical-device acoustic latency and translation quality: **not run**; no
  attached reception tablet or Fish credential was available.
- Provider mutations and paid calls: **none**.
- Production deployment / signed update release: **not performed**.

The transient workspace was recreated for PR publication. The three production
source blobs exactly match the previously verified implementation:

| File | Git blob SHA |
| --- | --- |
| `FishPlan.kt` | `6598802d1c15904e2b0e5e64c37954accec033dd` |
| `FishVoice.kt` | `f60920754072e7dc530f11f8085d55c20dc3520c` |
| `cloud/fish.py` | `6874e5041c0c80f50092b5ead53ae0cc0b727a25` |

Publication preflight reran the full Python suite: **251 passed** in 8.45 s,
with the same single AnyIO deprecation warning.

The native GitHub connector refused writes with HTTP 403. PR publication uses
the owner's separate authorized GitHub connection through Composio.

Unit tests and successful assembly cannot establish a p50/p95 audible-latency
improvement. GitHub Actions should rerun the repository's normal verification
on the published PR commit.

```sh
python -m pip install -r requirements-dev.txt
python -m pytest tests -q
cd android
./gradlew :hotel:testDebugUnitTest :hotel:lintDebug :hotel:assembleDebug :hotel:assembleDebugAndroidTest --no-daemon
```

The Python suite's `tests/conftest.py` redirects import-time database creation to
a temporary directory. Provider HTTP calls use fake transports. Do not supply
real voice credentials to verification builds or browser/native fixtures.

Original Android toolchain: Gradle 8.5, Android SDK/build-tools 34 and JDK 17.0.20.
The host included the compiler/linker modules but omitted their command
launchers. The missing `javac`, `jlink`, `jmod` and `jar` launchers were supplied
using those installed JDK modules' entrypoints. Normal Gradle tasks were used,
including the Android JDK image transform.
