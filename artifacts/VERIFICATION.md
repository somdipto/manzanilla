# Verification record — 2026-09-30

Original source: SHA-256 handoff verifier verified 751 files, all archive contents match.

Final application suite: `python -m pytest tests -q` — 16 passed, one AnyIO dependency deprecation warning. Tests include actual cloud/Edge subprocesses over test TLS. Realtime voice upstream is simulated, explicitly not an OpenAI/hardware test.

Final Android command: `bash gradlew :hotel:assembleDebug :hotel:lintDebug --no-daemon` — BUILD SUCCESSFUL, 40 tasks, 32 seconds. JDK 17, SDK 34, Gradle 8.5. Android lint has two nonfatal warnings for the retained decorative WebView (constructor and performClick). The included APK is a debug build, not a signed pilot release.

Python compilation and admin script Node syntax check pass. No original API keys or hotel credentials were supplied. No real hotel rollout, actual call answer, PMS vendor integration or physical device mic test is claimed.
