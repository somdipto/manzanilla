# Manzanilla

**Faster execution hardware for the hospitality industry.**

Manzanilla Reception is a landscape Android app with an open-canvas reception home, day/night appearance and hands-free live translation. Reception and guest languages have independent selectors. The known public 17.0.37/code 67 personal-testing APK contains an extractable shared Fish credential at the owner's request; use a disposable, account-limited key and have its account owner rotate it after testing. The repair source separates personal testing from enrolled hotel use. No release or deployment has occurred in this repair, and no route or language pair is acoustically qualified.

<p align="center">
  <a href="https://github.com/somdipto/manzanilla/releases/latest/download/Manzanilla-Reception.apk"><img src="https://img.shields.io/badge/%E2%AC%87%20Download%20APK-Manzanilla%20Reception-2ea44f?style=for-the-badge&logo=android&logoColor=white" alt="Download APK" height="56"></a>
  &nbsp;
  <a href="https://github.com/somdipto/manzanilla/releases"><img src="https://img.shields.io/badge/All%20releases-GitHub-24292e?style=for-the-badge&logo=github" alt="All releases" height="56"></a>
</p>

Direct download: **[Manzanilla-Reception.apk](https://github.com/somdipto/manzanilla/releases/latest/download/Manzanilla-Reception.apk)**

All releases: https://github.com/somdipto/manzanilla/releases

## Install

1. On the Android device, open this link and download the APK: **[Manzanilla-Reception.apk (latest)](https://github.com/somdipto/manzanilla/releases/latest/download/Manzanilla-Reception.apk)**
2. Open the downloaded file. If Android asks, allow "Install unknown apps" for the browser or file manager you used, then tap Install.
3. Open **Manzanilla**. The personal-testing APK starts on reception without hotel enrollment. Its voice-only Fish setup stays usable without an added PIN. Hotel operations still require their own authenticated enrollment; the download above is not an accepted hotel release.

<p align="center"><img src="docs/screens/17.0.21-home-fixture.png" alt="17.0.21 reception home in an isolated browser fixture" width="720"></p>

## Update an existing installation

After the user merges an eligible PR, **Publish verified Reception APK** builds
the two distributions in isolated jobs using the existing signer. Publication
requires both exact APK/metadata pairs and private signed-upgrade evidence bound
to each artifact's digest, commit, package/code, signer and distribution. The
installed candidate must retain data and initialize its actual packaged
Reception page, bridge and microphone-off state. These publication gates do
not qualify voice or hotel use. Source changes alone do not install an update.

Open Manzanilla while connected to the internet. Resume checks immediately;
foreground checks also run every ten minutes. The updater applies a verified
download only under its existing foreground/idle/audio-release rules. Android
can still require install-source permission or system confirmation. Personal
testing retains its one-click update offer; hotel maintenance is protected
native staff setup. In-place replacement keeps app data and saved keys and may
stop the process, so kiosk recovery needs a separately tested launcher/EMM policy.

Older installed versions through 17.0.7 can suppress checks for ten minutes after a recent attempt and hide discovery errors. If that prevents this repair from arriving, perform a **one-time manual in-place update** using the latest APK link above. Allow your browser to install unknown apps if requested, and choose **Update**, not uninstall.

Publication uses `android/VERSION` and the explicit increasing `android/VERSION_CODE`. Both distributions preserve `dev.orange.hotel` and the existing signer and must supersede the previous public code. Debug verification builds contain no provider keys. Failed validation cannot authorize installation; an active download stays pinned to its immutable artifact/distribution identity.

<p align="center">
  <img src="docs/screens/17.0.10-update-offer.png" alt="The installed app offering a downloaded update" width="720">
</p>

<p align="center">
  <img src="docs/screens/17.0.10-installed.png" alt="The app after the in-place update" width="720">
</p>

Android may require an "Update" confirmation for an app installed from a file; this is not a silent remote installation. Installed apps update from a newer signed GitHub Release, not from a source push or an Actions build artifact.

## Distribution and readiness — repair candidate

Build property `distribution` is `personal-test` by default, or `hotel`. Debug
builds honor the chosen behavior but always compile empty provider fields.
Personal testing keeps PIN-free Fish setup, encrypted saved-key precedence over
the bundled fallback, an explicit direct preference over enrollment and its
one-click update offer. A bundled testing key is labelled extractable.

| Distribution | APK asset | Matching metadata / latest feed | Shared compiled provider key |
| --- | --- | --- | --- |
| `personal-test` | `Manzanilla-Reception.apk` | `release-metadata.json` | Disposable Fish testing key, release only |
| `hotel` | `Manzanilla-Hotel.apk` | `hotel-release-metadata.json` | None; managed enrollment only |

Both artifacts use the same commit/version, application ID and existing signer.
Only personal-test release construction injects `FISH_API_KEY` and requires a
read-only account-access check; it creates no voice session and proves no
acoustic behavior. Hotel releases and all debug builds compile empty provider
keys regardless of the environment. Gemini keys never enter either APK.
`FISH_VOICE_ID` is a non-secret voice identifier. A key-free hotel artifact
cannot revoke a key already exposed in a public testing APK.

Each in-app feed, fallback and cached-download check stays within its current
distribution; it never changes an installation from testing to hotel. An
intentional operator transition uses the official signed `Manzanilla-Hotel.apk`
at a higher version code with Android's in-place-update approval. Never use
equal-code replacement or uninstall to force that transition. A missing hotel
asset or another artifact's proof cannot pass dual-distribution publication.
These are the source delivery contracts, not a claim that a candidate hotel
artifact has been published or accepted.

Hotel new translation starts use only the enrolled managed connection. Existing
personal keys are retained, not shown or erased; original hash-fenced cleanup
can still use its original credential. The hotel gear and voice-setup path both
require Android device-credential confirmation. Enrollment, appearance,
after-hours controls, updates and diagnostics stay in protected native setup.
Credential replacement is refused while original voice cleanup is outstanding.

An enrolled connection is configured, not automatically connected. A private
authenticated heartbeat has a three-second deadline, refreshes on resume and
every25 foreground seconds, and becomes stale after30 seconds. Guest actions
show availability before entry; native admission also enforces it. These flags
describe configuration, not paid provider entitlement or somebody watching the
inbox. Independent direct personal translation remains usable when hotel
operations are unavailable. Uncertain submitted requests retain their exact
reconciliation identity.

All 52 interface locales remain selectable. Catalogue translations are
model-authored and human-unreviewed. Interface completeness is not reviewed
meaning, vendor speech support or tablet qualification. Hotel
`supported_languages` bounds enrolled speech; explicit personal testing of
unqualified pairs remains available. The language chooser states:
**Speech support depends on the provider and tablet. Staff qualification is required.**

## Voice qualification and deployment boundary

`VOICE_ROUTE=fish` remains the managed default. A genuinely new managed LiveKit
reservation additionally requires backend `ALLOW_UNQUALIFIED_LIVEKIT=1`.
Prior-request lookup precedes that gate; withdrawing the flag does not block
backend startup, recorded-request reconciliation, end/recovery/cleanup, or
personal direct Fish. It must not turn an existing uncertain request into a
proven-noncreation response. See [the bounded evaluation route](docs/selfhosted-voice-route.md).

The current deployment contract permits one backend process with its SQLite
ledger and one owning worker per room namespace. The supplied compose recipe
is evaluation-only, not production after replacing secrets. Reject production
requirements for multiple owners, horizontal/multi-region dispatch or strict
immediate token revocation until implemented and qualified. Initial token
expiry and room deletion do not prove refreshed-token invalidation or prevent
room replay. This repair adds no distributed ownership or revocation guarantee.

No recognizer, speculation, endpointing, model, selected voice or route is
changed by this qualification work. Gemini 3.8 Flash, Fish S2.1 Pro and the
existing pair-dependent Nova-3/Scribe routing remain. Provider record gaps,
tool-execution timing, configuration ceilings, transport receipts and
standalone tier benchmarks do not establish tablet acoustic latency or an
improvement. Browser/emulator and zero-paid local transport proofs are separate.

Use [the acoustic protocol](docs/TEST-PLAN.md#voice-repair-reproduction-unreleased):
at least 100 distinct held-out hotel utterances per intended pair, repeated
trials, both directions, calibrated common-clock input/speaker capture,
bilingual meaning/naturalness review, critical-entity denominators and every
scheduled failure. Record route, exact guest/staff pairing, artifact digest,
device and evidence; passing one pairing never qualifies a language globally.
Start with English–Spanish, then English–Arabic and the hotel's intended pairs.
Hotel speech deployment requires its intended pairs to pass.

Day targets are p50 <1.2 s/p95 <2 s to first correct audible translation,
at least 95% successful conversations, at least 99.5% critical-entity
preservation, below 1% self-echo, below 2% failed/retried turns and interruption
<150 ms where supported. Night p50 <1.5 s/p95 <2.5 s applies only to an enabled
night voice route, not current microphone-off staff assistance.

Exercise connection, greeting overlap, long-synthesis interruption, repeats,
unclear words, names/room numbers/negation/prices/dates, mute/unmute,
rapid stop/start, network loss, expired initial token, reconnect and revocation;
on self-hosted evaluation include refreshed-token and room replay tests.
Verify no stale translation or microphone resurrection. No comparable physical
baseline or candidate captures exist for this repair: no measured improvement
is claimed. Matching isolated hardware/capture, current provider entitlement,
bilingual review and actual designated hotel staff acceptance are missing.
The remaining portion of the historical aggregate $4 allowance and a
conservative cost reservation cannot be established: make no paid call,
including Gemini draft/answer acceptance. Provider, physical and staff gates
remain blocked, not passed by synthetic fixtures. See [pilot prerequisites](docs/PILOT-DEPLOYMENT.md).

## Approved hotel answers — repair candidate

The repair candidate answers hotel questions only with staff-approved renderings
for the guest's selected interface language. Existing knowledge and arrival text
remain stored, but are not automatically approved or labelled as English.

Use the admin content-review panel to select the original and target language,
edit a draft or enter reviewed text manually, then explicitly approve its exact
title and body. Gemini can draft translations for review and select approved
fact references; it cannot invent guest-facing policies or execute hotel actions.
Manual approval remains usable when drafting is unavailable.

Editing a source or approved rendering withdraws stale information receipts.
The guest can ask again or choose reception help; an unanswered question never
creates a ticket automatically. New information requests can see new approvals,
while replaying the same request identity performs no model call. Durable taxi,
message and escalation receipts retain their original content and write identity.
Arrival guidance never completes check-in. Deploy this backend before clients
that depend on approved-content capabilities.

## Screens

17.0.28 candidate on an actual Android10 emulator with stock WebView74.
Key-free functional checks; no live conversation or latency measurement.

<p align="center">
  <img src="docs/screens/17.0.28-day-emulator.png" alt="17.0.28 day appearance on Android emulator" width="720">
  <img src="docs/screens/17.0.28-night-emulator.png" alt="17.0.28 night appearance on Android emulator" width="720">
  <img src="docs/screens/17.0.28-start-emulator.png" alt="17.0.28 translation explains missing audio configuration on key-free test APK" width="720">
</p>


17.0.27 candidate: real day/night palettes and repaired reception controls.
These are isolated synthetic browser captures, **not native-device or live-audio evidence**.

<p align="center">
  <img src="docs/screens/17.0.27-day-fixture.png" alt="17.0.27 day appearance browser fixture" width="720">
  <img src="docs/screens/17.0.27-night-fixture.png" alt="17.0.27 night appearance browser fixture" width="720">
</p>


17.0.26 language-attribution fixture: consecutive German receptionist utterances
stay right; French guest speech goes left. This is a synthetic browser capture,
not a live microphone or physical-device result.

<p align="center"><img src="docs/screens/17.0.26-language-fixture.png" alt="Synthetic French/German conversation: repeated German stays right and French goes left" width="720"></p>

17.0.21 release-source fixtures: exact original speech and assistant output remain explicitly unlinked; “Unspecified” does not guess who spoke. Microphone-off, recording and unknown states remain visible. These are synthetic browser captures, **not live speech or physical-device audio proof**.

<p align="center"><img src="docs/screens/17.0.21-translation-fixture.png" alt="17.0.21 synthetic unlinked captions, microphone-driven orb and visible microphone status" width="720"></p>

Earlier language selection and microphone-off assistance:

<p align="center"><img src="docs/screens/17.0.21-languages-fixture.png" alt="17.0.21 independent guest and receptionist language selectors" width="720"></p>

<p align="center"><img src="docs/screens/17.0.21-assistance-fixture.png" alt="17.0.21 microphone-off hotel-assistance browser fixture" width="720"></p>

Historical translation-state captures:

<p align="center"><img src="docs/screens/current-translation-greeting.png" alt="Translation greeting with the green microphone orb" width="720"></p>

<p align="center"><img src="docs/screens/current-translation-listening.png" alt="Translation listening with the red microphone orb" width="720"></p>

Every reception screen, original and current, is listed in [docs/screens](docs/screens/README.md).

## What is in this repository

The **17.0.31 candidate** repairs translation startup and LiveKit cleanup,
starts microphone capture during the worker greeting, avoids repeated PCM
padding, and restores the verified in-place updater and PR release safeguards.
The [local synthetic comparison](docs/verification/livekit-synthetic-2026-10-27.txt)
shows the previous worker missed microphone input during the greeting while the
candidate handled an interruption over a real local LiveKit server. Real Fish
voice naturalness, physical-device interruption and acoustic latency remain
unmeasured. Backend/worker deployment is separate from APK publication.

| Folder | What it is |
| --- | --- |
| `android/hotel` | The Manzanilla Reception app (landscape, microphone, auto-update) |
| `cloud` | Backend for hotel setup, device sessions and voice calls |
| `admin` | Small web dashboard for hotel setup |
| `edge`, `infra`, `shared` | Supporting services and shared definitions |
| `docs` | Architecture, pilot deployment notes, test plan, screens |
| `.github/workflows` | CI: build, test and publish releases |

The original hand-off notes are kept in [docs/HANDOFF-README.md](docs/HANDOFF-README.md).

## Historical source milestones (not current installation instructions)

**17.0.27 translation/reception candidate (unreleased):** translation text is
requested before caption generation, Android audio diagnostics run off the media
callback, and unused raw transcript copying is removed. Fixed the APK's blocked
language script, real light/dark palettes, preference navigation, native setup
appearance, and note review/edit/retry/cancel behavior. Managed `language-v2`
retains separate legacy v1/tool-free contracts; deploy the backend before the APK.
[Investigation, architecture, device-test needs and limitations](docs/realtime-reception-fix.md).

The 2026-10-22 check recorded public 17.0.24 at that time. The later known public
testing artifact is 17.0.37/code 67; neither that version nor this repair is a
qualified hotel release. Historical candidates below describe source work,
not a new publication or physical speech-latency result.


**17.0.26 language-side candidate (unreleased):** the interpretation model sends
one display-only caption report containing its source-language decision, original
speech and translation. Receptionist language maps right, guest language maps
left. That historical candidate inherited a preceding side for unclear speech;
the repair removes inheritance so unclear language is neutral. Earlier managed
clients used `language-v1`. [Contract and acceptance checks](docs/language-bubble-fix.md).

**17.0.25 latency candidate (unreleased):** per-session recognition now uses
Nova-3 only when both selected languages support Fish's hosted multilingual
switching; all other pairs retain Scribe. Expressive delivery and the provider's
three-second opening speech-drop guard are disabled, and Android requests
WebRTC's low-latency playback mode with echo cancellation/noise suppression kept.
Gemini 3.8 Flash, Fish S2.1 Pro and the reception screens stay in place.
[Research, changes and device verification](docs/voice-latency-fix.md).
Physical speech-to-speech improvement is not yet measured; this source change
does not update installed devices until a signed release is published.

17.0.21 separates local audio release from paid-session cleanup, retains uncertain ownership, removes guessed caption associations, and updates individual caption bubbles instead of rebuilding the conversation. Startup failures now acknowledge the proposed session; restored reservations that never dispatched can recover without bypassing genuinely uncertain creation. Managed backends distinguish proven non-creation from lost responses. Refusing an update during a conversation keeps the update action retryable. The models, voice and endpointing settings are unchanged. The offline acoustic tool is `tools/voice_latency.py`; physical speech-to-speech latency and bilingual accuracy qualification remain blocked on an isolated matching tablet/capture setup and confirmed remaining spending authorization. See [verification](artifacts/VERIFICATION.md) and [reproduction instructions](docs/TEST-PLAN.md).

17.0.13 enabled direct Fish translation with an embedded CI testing credential. The later public 17.0.37 testing APK also contains an extractable Fish key; the candidate hotel artifact, not all releases, is key-free and managed-only. The unused device-side Gemini route and legacy managed relay are removed. Provider usage is billed to the owner's account. Physical-device echo, latency and full-duplex behavior remain unqualified.

17.0.10 keeps hotel assistance usable with the microphone off: arrival guidance, hotel questions, taxi requests, confirmed staff messages and human-contact options. Missing hotel configuration stays unavailable. A saved inbox receipt is not staff acknowledgement.

17.0.8 repairs update discovery: app resume no longer skips checks because of a persisted cooldown; offline/discovery errors are visible and retryable; a home-screen check control and foreground rechecks make updates reachable without restarting or reinstalling the app.

17.0.7 refines the approved open-canvas reception design: compact language selectors show the selected language without repeated names or keypad numbers; conversation bubbles use neutral charcoal/gray surfaces in dark mode and warm gray in light mode. Starting translation plays the app's bundled guest-language welcome before microphone capture, with guest-language text above receptionist-language text in one divided reception-side bubble. This welcome is app-owned, not generated by the voice agent.

The current caption path maps the model-reported source language to the selected guest/receptionist language and carries original plus translation in one report. **Fish transcript segment IDs still do not identify translation pairs; raw SDK transcripts are used only for transport diagnostics.** Earlier clients retain their unlinked caption protocol. Hotel assistance still requires the configured backend and real hotel integrations.

Earlier verification: 31 Android JVM tests, Android lint, debug and signed release assembly passed locally. Isolated browser checks covered readable selectors at small landscape sizes, all 52 welcome language pairs, neutral-bubble contrast, stale events and greeting history across restart. A disposable native Android fixture exercised actual bundled welcome playback in English, Japanese and Arabic, player release, and immediate cancellation without opening a microphone or provider connection. All 52 bundled WAV files were validated. Muted emulator playback is not physical speaker or live full-duplex proof. Detailed evidence is in [artifacts/VERIFICATION.md](artifacts/VERIFICATION.md).

Earlier captures: [compact language selection](docs/screens/17.0.7-languages.png) and [app-owned bilingual welcome](docs/screens/17.0.7-welcome-fixture.png). The welcome fixture exercises bundled audio and its native playback event, not a live provider session. Earlier ordinary-conversation captures use **injected synthetic captions, not live speech**: [dual bubbles](docs/screens/17.0.6-conversation-fixture.png), [stopped state](docs/screens/17.0.6-stopped-fixture.png).

## Build it yourself

Requires JDK 17 and the Android SDK.

```sh
cd android
./gradlew :hotel:assembleDebug
```

Signed release builds are produced by GitHub Actions. The signing key is stored as a repository secret and is never committed.

## License

See [LICENSE](LICENSE).
