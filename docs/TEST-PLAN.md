# Verification and acceptance

Original handoff SHA-256 verification passes for 751 listed files plus manifest. Application tests cover persistent notes/deduplication, confirmation, booking verification/session scoping, prohibited tools, taxi queued/not-booked semantics, escalation failure, PMS-unavailable fallback, knowledge, tenant roles, revocation, inbox isolation, morning window, one-use WebSocket tickets and missing API key failure. Realtime integration test executes actual relay code against a fake upstream: PCM input, tool arguments, device confirmation, durable note, function output, PCM output and interruption/truncation. This is not real model/device/language acceptance. Edge tests verify fixed-file lookup and capability/path denial. Admin JS syntax and Python compilation pass. Android hotel debug APK builds under JDK 17/SDK 34/Gradle 8.5.

Run `python -m pytest tests -q` from project root. README records the final count. One dependency AnyIO deprecation warning is emitted. The Edge transport test launches actual cloud and Edge subprocesses over trusted local TLS, verifies lookup and disconnect fallback. Android lint has no errors; retained WebView constructor/accessibility warnings remain. No Docker/CI remote deployment, signed production release, Windows service, emulator or physical phone test has been performed.

| Real pilot test | Required proof |
|---|---|
| Multilingual conversation | Understandable spoken reply for each intended language; guest understands confirmation UI. |
| Taxi | Correct confirmed destination/time; staff handles inbox request or actual provider books it. No false booking claim. |
| Morning note | Note visible after phone/backend restart, with guest/room/time/session and summary; staff resolves it. |
| Check-in | Real PMS verified reservation/eligibility; approved instructions; no autonomous door/payment/modification. |
| Human request | Immediate approved transfer to primary, answered/no-answer detection, backup tested. Dialer opening alone fails this acceptance gate. |
| Concierge | Answers match actual hotel data; unknown questions escalate. |
| PC unavailable | Voice/knowledge/cloud notes still work; Edge-only lookup degrades safely. |
| Phone restart | Selected HOME launches, stable ID/token survive, heartbeat/config recover. |
| Internet/OpenAI failure | Mic/audio close and truthful fallback; retry creates one session. |
| PMS/CRM failure | Lookup error, durable cloud note never dropped. |
| Edge reboot | Automatic startup/reconnect, safe timeout; no shell or arbitrary file access. |
| Interruption | Old playback clears; reported played position agrees with handset audio. |
| Stop during connect | No delayed microphone/socket resurrection. |
| Duplicate/malformed tools | One note per call ID, conflicts rejected, unknown fields denied. |
| Tenant isolation | Staff/device from Hotel A cannot access or alter Hotel B. |
| Update/rollback | Same signed APK for four hotels; config updates without APK; recovery build with higher versionCode. |

Blocked on actual four-hotel PMS/CRM vendor data and credentials, OpenAI account/key, handset mic compatibility, SIM/SIP/native telephony provider, operator/taxi workflows, signing key, deployment host and PC service QA. Obtain those before claiming all six features work end-to-end. Signed APK, managed-device lockdown, retention/backup and per-hotel acceptance are release gates.
