# Verification and acceptance

## Current remediation acceptance

The authoritative repair IDs R01-R17 and approved 17.0.6 visual requirements are
in [the remediation spec](theme-handoff-contract.md). Passing evidence is recorded
separately in `artifacts/VERIFICATION.md`; this file defines the procedure.

1. **Reproduce before repairing.** For each repair ID, preserve a minimal
   consumer-visible failure scenario. Keep deterministic native/domain
   regressions at the real ownership boundary; use isolated actual-UI scenarios
   for browser/WebView behavior. Avoid source-text, copied-default and mock-echo
   assertions.
2. **Run existing suites after the integrated change.** From the repository root,
   run `python -m pytest tests -q`. From `android`, run
   `bash gradlew :hotel:testDebugUnitTest :hotel:assembleDebug --no-daemon`.
   Build the release candidate with the established signing configuration, then
   verify the actual APK's package, version code, certificate and digest.
   Compilation alone does not close any user-workflow acceptance row.
3. **Exercise the guest surface.** Serve the actual packaged reception assets
   from an isolated fixture with an explicit native bridge. Exercise translation,
   same-language rejection, Spanish assistance, greeting/cancel, both caption
   forms, message review/cancel/save, all shortcut outcomes, late events,
   language-preserving host refresh and update offer/progress/action. Inspect
   visible audio state, guest-left/receptionist-right paired caption fixtures,
   explicitly unlinked provider captions, both appearances, RTL, text scaling,
   reduced motion and hidden/resumed state. Browser bridge fixtures establish UI
   behavior only; never claim they prove real voice pairing.
4. **Exercise Android ownership and updates.** Use an isolated landscape AVD with
   synthetic configuration, then the target physical device for acoustic checks.
   Cover permissions, idle timeout, pause/resume, screen sleep, disconnect,
   rapid start/stop and recreation. Install the candidate over isolated copies
   of the previous release and 17.0.4, retaining saved configuration/data and
   signing identity. Advance the release feed during a resumed download and
   verify that stale bytes cannot be offered as the new release.
5. **Exercise operational boundaries.** Use a disposable backend/property and
   existing gateway fixtures. Verify authentication, cross-hotel denial, scoped
   booking reads, explicit write confirmation, duplicate call IDs, durable
   requests and accurate receipts. Then exercise the configured real PMS,
   operator and taxi routes in approved test environments; synthetic receipts
   do not prove actual external delivery.
6. **Measure voice acceptance.** Follow issue #2's A/B protocol using the same
   audio/network/device conditions, at least 100 synthetic hotel utterances,
   repeated trials, both directions, interruptions, names/numbers/dates/currencies
   and bilingual human review. Report end-of-speech to first audible output,
   p50/p95, failures, entity denominators, echo, barge-in and costs. Targets:
   Day p50 <1.2 s/p95 <2 s; Night p50 <1.5 s/p95 <2.5 s; interruption <150 ms
   where hardware supports it; >=95% successful conversations; >=99.5% critical
   entity preservation; <1% self-echo turns; <2% failed/retried turns.
   A configured interruptible flag or 52-language menu is not passing evidence.
7. **Keep spending and evidence honest.** Aggregate live calls must remain within
   the authorized $4 maximum. Check verified entitlement/pricing and reserve a
   conservative cost bound before calls; stop when safe remaining spend cannot
   be established. Full real-provider language/one-hour Day/two-hour Night soak
   acceptance stays open if that allowance cannot fund it. Fake-provider resource
   soak proves local ownership/bounds, not acoustic or real-provider quality.
8. **Separate diagnostic publication from production acceptance.** Record commit,
   artifact identity, scenario, result, device evidence, benchmark coverage and
   remaining limits. Production acceptance requires all R01-R17 rows and applicable
   hotel gates. An explicitly authorized testing APK may be published with its
   unverified hardware/quality/integration gates disclosed; it is not production
   acceptance. Failed or unavailable gates are never omitted or marked passed.

No live app/data mutations are permitted for these checks. Use a disposable
landscape AVD and isolated backend databases; do not substitute browser screenshots
for native Android installation or microphone/audio evidence.

## Historical backend baseline

The following records the original backend/handoff verification scope. Its
OpenAI dependency and statements about unavailable signing/releases are historical,
not the selected Fish/Gemini architecture or current 17.0.4 release evidence.

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
