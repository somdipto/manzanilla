# Verification record — 2026-10-15

## Scope

17.0.5 is an explicitly authorized diagnostic pilot, not production hotel acceptance. All server mutations used temporary databases and synthetic hotel/device data. Android verification uses the disposable `omp_r17` AVD (`emulator-5580`), not the user's installed physical app. No real guest or PMS data was used.

## Local verification

- Backend: `.venv/bin/python -m pytest -q -x` — **84 passed**, one Starlette/AnyIO deprecation warning, 58.00 seconds. Covers tenant credential isolation, confirmation cancellation and restart recovery, Fish admission/budgets/ownership/revocation, Edge revocation, operations, relay and artifact identity.
- Android: `:hotel:testDebugUnitTest :hotel:lintDebug :hotel:assembleDebug :hotel:assembleRelease --no-daemon` — **BUILD SUCCESSFUL**, 31 JVM tests, Java 17 / SDK 34. Remaining compiler/deprecation warnings are not passing hardware evidence.
- Key scope: direct packaged-DEX inspection found the supplied Fish key in the explicitly opted-in release and absent from the debug APK built in the same environment. The key is not reproduced here.
- Local signed candidate: package `dev.orange.hotel`, version 17.0.5, code 35; `android/release_identity.py` successfully compared it with the downloaded published 17.0.4 APK. Signer SHA-256: `51a69a47b506d1f02e243c7663fe5be44cc4a28bb6306435c5b4e656797ec4e1`.
- Isolated HTTP smoke: launched the real Uvicorn application with a temporary SQLite database and synthetic upstream transport. Mint returned 200; a second outstanding mint returned 409; owner end returned `ended`; revocation ended the next provider lease; a revoked device's subsequent mint returned 401. Both durable leases were `ended`, each retaining its 600-second reservation (1200 total). No paid calls in this smoke.
- The HTTP smoke caught a real reservation INSERT/schema mismatch (11 placeholders for 10 fields). Replaced it with an explicit 10-column INSERT before the successful smoke and full regression run.
- Native signed upgrade: installed published 17.0.4/code 34 in the disposable AVD, then `adb install -r` installed the signed 17.0.5/code 35 successfully. Android's `firstInstallTime` remained exactly `2026-10-15 22:57:31`, confirming replacement of the existing installation rather than uninstall/reinstall. This proves Android signing/update compatibility, not the in-app downloader/installer confirmation flow.
- Native rendering: the landscape reception scene rendered on the 2560×1600 fixture, but cold launch exceeded Android's wait deadline and both System UI and process-system ANR dialogs appeared. This is a failed clean-launch/interaction check, not a passing audio test; further fixture checks use 1280×800 to reduce software-rendering load. No physical-device startup result is claimed.
- Initial GitHub candidate run `38788972535`: cloud tests passed; Android setup failed because the action's default package list requested the removed `tools` package; Windows Edge assertions passed but teardown failed because the import-time SQLite handle was still open. SDK setup now requests only platform-tools/platform 34/build-tools 34.0.0, and pytest closes its owned bootstrap connection before deleting its temporary directory. The targeted Edge fixture run passed locally after the cleanup repair; remote Windows verification is required.

## Real Fish/Gemini speech

Two short synthetic LiveKit calls used an isolated published acceptance agent, multilingual `elevenlabs:scribe_v2_realtime`, Gemini `google/gemini-3.8-flash`, the Spanish voice, no tools, no provider recording, and an explicit bidirectional prompt. Both sessions were ended with the provider end endpoint (HTTP 204).

1. English–Spanish: user final transcription included “I have a reservation for two nights. My room number is 204.” Agent output caption was Spanish and incoming audio was received. Spanish input “El desayuno se sirve a las ocho. Su habitación es la 204.” produced “Breakfast is served at eight. Your room is 204.” The harness received 653520 audio bytes over 32.49 seconds. A recoverable LLM provider error preceded successful output, with `recovered:true`; the native app now preserves the call only for that explicitly recovered event. Unrecovered errors remain terminal. The initial configuration produced a duplicate Spanish response after recovery; speculative replies were then disabled.
2. Catalan–Spanish, speculative replies disabled: “Tinc una reserva per dues nits. El número de la meva habitació és el 204” produced “Tengo una reserva para dos noches y el número de mi habitación es el 204.” Spanish breakfast/room input produced “L'esmorzar se serveix a les vuit i l'habitació és la 204.” One output caption per turn; 899280 incoming audio bytes over 41.91 seconds. This demonstrates a pair outside Deepgram Nova-3's documented mid-call switching set.

These are short synthetic exchanges, not a bilingual human evaluation or a 52-language benchmark. Caption timestamps are not first-audible-output measurements. In the Catalan run, response-caption arrival followed final user transcription by 7.22 and 4.90 seconds: the product's sub-two-second latency target is **not established**. No A/B comparison, acoustic echo test, natural barge-in timing, physical-device microphone test, or long-duration soak passed in this work.

The API wallet returned 180.00 immediately after both calls, unchanged from its initial value. This is not proof of zero charges or settled billing; the session detail did not provide a final monetary charge. No further paid benchmark is justified without a verified remaining monetary allowance.

## Security and operation

Ordinary CI/debug/release builds remain provider-key-free. This requested keyed release is an explicit security exception: anyone holding the APK can extract the account credential and bypass backend admission limits. The key is stored as an encrypted Actions secret, not repository source. Rotate before production.

Managed admission reserves 600 seconds per session, at most one outstanding lease per device; default UTC-day caps are 1800 hotel seconds and 3600 account seconds. Reservations are not refunded and are not dollar-denominated limits. Unknown creation outcomes and failed termination fail closed; they require revocation/reconciliation rather than guessed expiry. Real PMS, operator/taxi delivery, deployment region, privacy/retention setup, and physical reception hardware remain production prerequisites.
