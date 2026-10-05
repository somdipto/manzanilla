# Manzanilla Orange Reception

**Install on an Android phone/tablet:** [Download the latest APK](https://github.com/somdipto/manzanilla/releases/latest/download/Manzanilla-Reception.apk) (open it on the device, allow "install unknown apps" for your browser once). Every push to `main` builds and publishes a new signed release; the app checks for updates on launch and installs them itself (Android may show one confirmation tap unless the device is set up as device owner).

# Orange Night Reception — development implementation

Code derived from the supplied UI ZIP. This is **not a completed four-hotel production pilot**. Original Android app is retained; independent `hotel` module connects to cloud with HTTPS/WSS. No backend secret is in the APK.

## Reused
Original `android/app` and Gradle wrapper preserved. Hotel module reuses/adapts LiveCompanionView, packaged voice-companion assets, LiveVoiceScene, LiveSpeaker and PcmEnvelope. MicStreamer capture concept becomes HotelMic at 24 kHz. Upstream license inventory retained. Desktop source stays in the original ZIP and is not required by the hotel SKU.

## Replaced in hotel SKU
BridgeClient/mDNS/PC ws:// → authenticated CloudClient. General MainActivity/DeckView → HotelActivity with the supplied scene/companion. Laptop microphone → native capture (hardware unverified). Separate manifest removes camera/PC permissions and denies cleartext. The original SKU is intact.

## Added
Cloud realtime relay, hotel policy/tool gateway, device/staff/Edge authentication, persistent SQLite inbox/audit/call ledger, booking access scoped to session, demo/REST/Edge connector boundaries, restricted outbound Edge executor, admin hotel/config/fleet/integration/inbox/session/morning views, Docker/TLS/service templates, Windows startup scripts, CI/debug build and signed pilot release workflow.

## Verified
16 application tests pass, including actual relay execution against a **simulated** OpenAI connection. Hotel debug APK builds and Android lint passes (nonfatal warnings on the retained decorative WebView). An actual Edge subprocess performs a reservation lookup through cloud over trusted local TLS, then degrades safely when disconnected. Original archive integrity passes. Admin JavaScript syntax and Python module compilation pass. Real OpenAI/PMS/telephony/device acceptance remains open.

| Capability | Current code | Still required |
|---|---|---|
| Multilingual voice | Realtime session + language instructions | Real key, voice/language and handset tests |
| Taxi | Device-confirmed durable staff inbox request, optional dialer | Staff workflow or real taxi provider booking/call |
| Reception note | Confirmation → durable cloud inbox | Physical phone/reception workflow QA |
| Check-in | Verified demo/gateway lookup and eligibility-based guidance | Actual hotel PMS/vendor/policy |
| Operator | Immediate tool/button, configured primary/backup, escalation inbox | Answered/no-answer-aware transfer; dialer opening is not a connected call |
| Concierge | Trusted configurable knowledge/services | Actual property data and language QA |

Demo reservation: DEMO-204 / Smith, marked synthetic. PMS note delivery is not claimed. No completed check-in, taxi booking or answered call is fabricated.

## Run
```sh
python -m venv .venv
. .venv/bin/activate
pip install -r requirements-dev.txt
python -m pytest tests -q
# Export backend secrets from .env.example first:
uvicorn cloud.app:app --host 127.0.0.1 --port 8080 --workers 1
# JDK 17 + SDK 34:
cd android
bash gradlew :hotel:assembleDebug --no-daemon
```
Local admin: http://127.0.0.1:8080/admin. Phone/Edge require trusted HTTPS/WSS. Exact deployment, signing, Edge installation, rollback and Hotel #1 onboarding commands are in docs/PILOT-DEPLOYMENT.md. Required environment: docs/ENVIRONMENT.md. Audit: docs/CURRENT-STATE.md. Protocol/limits: docs/ARCHITECTURE.md and shared/protocol.md. Evidence and release gates: docs/TEST-PLAN.md.

Phases 0–4 have code, with live voice/device acceptance pending. Phases 5–8 have connector/Edge/admin/fleet implementations and templates, with real vendor integration and deployment unverified. Automated hardening is present; physical failure tests and four-hotel pilot remain open. Mac Arc is a separate project and was not altered.
