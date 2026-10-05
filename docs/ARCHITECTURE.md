# Implemented architecture

Android hotel module → authenticated HTTPS bootstrap → single-use session ticket → WSS Orange Cloud → server-side OpenAI Realtime WebSocket. PCM16LE 24 kHz is used both ways, avoiding a 16 kHz resampling layer. Server VAD cancels generation on interruption; Android clears its playback queue and reports a bounded played position for conversation truncation. API key stays server-side. No PC is required for voice, cloud notes, configured knowledge or staff requests. Device mic capability is an explicit pilot hardware gate.

Cloud `realtime.py` is the voice lifecycle; `gateway.py` is bounded orchestration, not a bundled DANI/Hermes runtime. It has no shell or computer-use tool. The user's request needs these normalized tools, rather than an additional general-purpose agent dependency. Gateway validates JSON arguments, enforces booking access scoped to one conversation, asks the authenticated device to confirm write actions, persists results and deduplicates call IDs. Human escalation bypasses write confirmation. Check-in is guidance only, never a completed PMS check-in/room unlock.

SQLite WAL stores hotels, distinct hashed device/Edge/staff credentials, version/heartbeat metadata, sessions, inbox items, call-result ledger and metadata audit events. Durable notes and requests are saved before reporting success. Raw audio/transcripts are not recorded in the application; notes contain the requested guest text because this is their purpose. Live-session language and automatic-resolution count remain unknown; no invented metrics. Configure provider retention separately and protect/back up guest notes.

Admin master token manages all hotels; hotel staff tokens read and resolve only their assigned property. Admin tokens stay in page memory, not browser localStorage. Credentials are returned only on issuance and can be revoked. Device session tickets expire in 30 seconds and are single use. One active voice session per device. A single backend worker is REQUIRED: live tickets/connections/confirmation futures are in process. Multi-worker or multi-node deployment requires a shared lease/pubsub/ticket store and must not be enabled until implemented.

PMS connectors: demo fixture, hotel-owned read-only REST contract and outbound Edge reservation-file adapter. A first real vendor connector cannot be selected without the four hotels' vendor information. All are isolated behind normalized tools. No Cloudbeds/Mews compatibility is claimed. PMS/CRM note delivery is not implemented; the cloud inbox is the explicit working destination.

Edge uses WSS Bearer authentication and bounded `read_reservation` from one administrator-selected file. No model path, printer, browser or shell execution is enabled. Explicit administrator-configured exports can power legacy lookup. Linux service and Windows startup task templates are supplied; physical reboot behavior requires deployment testing.

## Source/visual migration
Original `android/app` remains intact. `android/hotel` packages only Orange companion assets and adapted Canvas call UI/audio; unused legacy models/pets/camera assets are excluded from the hotel APK. Desktop Arc and employee UI stay in the original attached ZIP; they are not being ported to the hotel's admin dashboard. No original license/notice is removed. The original app's floating MediaPipe version remains in its untouched build file, but the hotel module doesn't use that dependency.

## Limits to resolve before a real pilot
No confirmed telephony transfer exists: ACTION_DIAL opens a phone UI; answered/failed/no-answer outcomes require a native telephony/SIP/managed provider integration. Taxi defaults to staff inbox; dialer mode is optional and never says booked. A device without SIM/dialer must show fallback numbers. Confirmation dialog is English in this initial implementation; voice is multilingual but multilingual confirmation text and accessibility focus need device QA. Reservation surname/reference verification is deliberately insufficient for sensitive actions.

The app has no setup or enrollment screen. The backend address and device credential are baked into the build (ORANGE_BACKEND_URL and ORANGE_DEVICE_TOKEN at build time); without them it runs in demo mode.

Realtime session cap is 30 minutes. Disconnection releases mic/audio and offers manual retry; heartbeats retry every 25 seconds. Sessions do not silently resume with stale booking access. No offline voice/note recording queue is claimed. Cached operator numbers support manual escalation offline.

## Official protocol references checked 2026-09-30
- https://developers.openai.com/api/docs/guides/realtime-conversations
- https://developers.openai.com/api/docs/guides/voice-websockets

The fetched Realtime conversation page documents nested session audio format, 24 kHz PCM, function outputs and played-audio truncation. Configurable model defaults to the documented example `gpt-realtime-2.1`; account availability and a live connection are unverified without a backend key. No app credential is embedded in the APK.
