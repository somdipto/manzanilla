# Isolated verification

Use synthetic fixtures and temporary databases. Never run verification writes
against a live hotel service or guest data. See [the full test plan](../TEST-PLAN.md)
for acoustic, Android ownership, update, quality and spending gates.

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
