# Isolated verification

Use synthetic fixtures and temporary databases. Never run verification writes
against a live hotel service or guest data. See [the full test plan](../TEST-PLAN.md)
for acoustic, Android ownership, update, quality and spending gates.

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
