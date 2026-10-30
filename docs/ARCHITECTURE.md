# Implemented architecture

Android hotel module → protected native setup (encrypted HTTPS service URL and device credential) → authenticated HTTPS bootstrap → single-use 30-second session ticket → WSS room. Translation runs one application-controlled pipeline in `voice-worker/`: Fish realtime recognition → application-owned records and direction → Gemini translation → Fish synthesis, with 24 kHz PCM acknowledged only after the receiver renders it. The microphone stays live; a human turn cancels the active generation and flushes queued audio. Managed provider credentials stay on the worker; personal testing keeps them in the device keystore. No PC is required for voice, cloud notes, configured knowledge or staff requests. Device microphone and speaker quality remain a pilot hardware gate.

`cloud/livekit_route.py` owns the reservation, join token and room deletion, and one worker session owns each room's pipeline. The earlier dedicated Realtime route (`cloud/realtime.py`) and the hosted Fish Agent mint are not new-session routes; `cloud/fish.py` keeps only historical-lease cleanup. Reception assistance (`mode == "reception"`) is staff-led HTTP operations with the microphone off, not a voice agent. `gateway.py` is bounded orchestration, not a bundled DANI/Hermes runtime, and has no shell or computer-use tool. The user's request needs these normalized tools, rather than an additional general-purpose agent dependency. Gateway validates JSON arguments, enforces booking access scoped to one conversation, asks the authenticated device to confirm write actions, persists results and deduplicates call IDs. Human escalation bypasses write confirmation. Check-in is guidance only, never a completed PMS check-in/room unlock.

SQLite WAL stores hotels, distinct hashed device/Edge/staff credentials, version/heartbeat metadata, sessions, inbox items, call-result ledger and metadata audit events. Durable notes and requests are saved before reporting success. Raw audio/transcripts are not recorded in the application; notes contain the requested guest text because this is their purpose. Live-session language and automatic-resolution count remain unknown; no invented metrics. Configure provider retention separately and protect/back up guest notes.

Admin master token manages all hotels; hotel staff tokens read and resolve only their assigned property. Admin tokens stay in page memory, not browser localStorage. Credentials are returned only on issuance and can be revoked. Device session tickets expire in 30 seconds and are single use. One active voice session per device. A single backend worker is REQUIRED: live tickets/connections/confirmation futures are in process. Multi-worker or multi-node deployment requires a shared lease/pubsub/ticket store and must not be enabled until implemented.

PMS connectors: demo fixture, hotel-owned read-only REST contract and outbound Edge reservation-file adapter. A first real vendor connector cannot be selected without the four hotels' vendor information. All are isolated behind normalized tools. No Cloudbeds/Mews compatibility is claimed. PMS/CRM note delivery is not implemented; the cloud inbox is the explicit working destination.

Edge uses WSS Bearer authentication and bounded `read_reservation` from one administrator-selected file. No model path, printer, browser or shell execution is enabled. Explicit administrator-configured exports can power legacy lookup. Linux service and Windows startup task templates are supplied; physical reboot behavior requires deployment testing.

## Source/visual migration
Original `android/app` remains intact. `android/hotel` packages only its reception page, offline locale catalogue, speech configuration and translation runtime prompt; unused legacy models, pets, camera and companion assets are excluded from the hotel APK. Desktop Arc and employee UI stay in the original attached ZIP; they are not being ported to the hotel's admin dashboard. No original license/notice is removed. The original app's floating MediaPipe version remains in its untouched build file, but the hotel module doesn't use that dependency.

## Limits to resolve before a real pilot
No confirmed telephony transfer exists: ACTION_DIAL opens a phone UI; answered/failed/no-answer outcomes require a native telephony/SIP/managed provider integration. Taxi defaults to staff inbox; dialer mode is optional and never says booked. A device without SIM/dialer must show fallback numbers. Guest and staff copy is localized from the shared catalogue (52 locales, human-unreviewed); confirmation and receipt controls render in the selected locale, while accessibility focus at enlarged text still needs device QA. Reservation surname/reference verification is deliberately insufficient for sensitive actions.

Enrollment and voice setup are native and edited through protected staff setup, which stores the HTTPS service URL, the device credential, and the personal Fish key, Gemini key and voice ID as one encrypted bundle. No backend address or credential is compiled into the APK, and debug builds honor the selected distribution with empty provider fields. Personal voice-only setup stays PIN-free; hotel maintenance requires the device screen lock.

A managed translation session reserves 600 seconds; the loopback web app requests at most 1800. Disconnection releases mic/audio and offers manual retry; heartbeats retry every 25 seconds. Sessions do not silently resume with stale booking access. No offline voice/note recording queue is claimed. Cached operator numbers support manual escalation offline.

## Protocol references

Current, for the shipped route: [Fish realtime speech to text](https://docs.fish.audio/api-reference/endpoint/websocket/asr-realtime), [Fish streaming TTS](https://docs.fish.audio/api-reference/endpoint/websocket/tts-live), and [Gemini's OpenAI-compatible API](https://ai.google.dev/gemini-api/docs/openai).

Historical (2026-09-30 baseline, superseded by the route above):
- https://developers.openai.com/api/docs/guides/realtime-conversations
- https://developers.openai.com/api/docs/guides/voice-websockets

The fetched Realtime conversation page documents nested session audio format, 24 kHz PCM, function outputs and played-audio truncation. Configurable model defaults to the documented example `gpt-realtime-2.1`; account availability and a live connection are unverified without a backend key. No app credential is embedded in the APK.
