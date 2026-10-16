# Phase 1 — Validate reception: engineering baseline

Historical pre-change baseline at `d8f45a4`; not the current implementation status. See [the verification record](../artifacts/VERIFICATION.md) for subsequent 17.0.10 remediation and its remaining live-audio/pilot gates.

**Decision: not pilot-ready.** Preserve the current reception app; do not restart it. Manzanilla supports guests and hotel staff. Human staff retain responsibility for guidance, requests and escalation.

This is the pre-implementation baseline requested by the owner. It is an internal engineering audit, not guest-facing copy or approval to deploy. No application code, provider configuration, main branch, release or issue was changed during this baseline pass.

## Source of truth

| Item | Observed state |
| --- | --- |
| Repository | `somdipto/manzanilla`; no work in the assistant or landing-site repositories |
| Working branch | `omp/remediation-r01-r17` |
| Exact baseline revision | `d8f45a4c552ec0b000e64af4530258907bf43a04` |
| Working version | `17.0.9`, version code `39` |
| Fetched main | `04ae19a15c98a195b9e300af17da6abb46da1344`, version `17.0.4` |
| Branch relationship | Eleven commits beyond main; working branch already synchronized with its remote |
| Latest published release | [17.0.8](https://github.com/somdipto/manzanilla/releases/tag/v17.0.8), source `e956abecc9bd013af1398fc4bd21f888666983f2` |
| Existing 17.0.9 build | [Successful signed-build run](https://github.com/somdipto/manzanilla/actions/runs/38939253162) at the exact baseline revision; a build artifact, not a published 17.0.9 release |
| Owner changes | Preserved, including the untracked `.omo/` directory; no reset, checkout, merge or application edits |
| Master issue | [Issue 1](https://github.com/somdipto/manzanilla/issues/1), last updated `2026-10-07T18:08:29Z`; its 10.0.2 truth section predates main and must not cause a rollback |

Previous chat statements claiming 17.0.9 was published, or that the owner's tablet was confirmed upgraded, were incorrect. The observed 17.0.9 installation was in an isolated emulator. The owner's tablet was not connected. Previous claims that the 450 ms setting already existed before this work were also incorrect: the source change was 700 → 450 ms. Neither setting establishes actual speech latency.

## Evidence vocabulary

- **Implemented / works in source:** a concrete code path exists. This does not imply operational acceptance.
- **Contract-tested / actually tested:** a stated isolated test, fixture or CI run exercised the contract. Synthetic upstreams remain synthetic.
- **Live-tested:** the exact external behavior was observed; scope, revision and limitations must be stated.
- **Pilot-validated:** real staff and guests demonstrated usefulness and workflow fit under supervision. No capability below has this status.
- **Merely present:** control, configuration or code exists without the required operational proof.
- **Blocked:** missing specification, approved hotel information, compliant audio, integration proof, safety approval or human validation prevents acceptance.

## Feature baseline and acceptance matrix

| Capability | Works in source / merely present | Actually tested | Missing or blocked before acceptance |
| --- | --- | --- | --- |
| Session and mode correctness | **Implemented.** Separate translation/reception modes, session generations, greeting-before-capture, late-event fencing, stop/release acknowledgements and two independent language selectors. See `HotelActivity.kt:146–189,333–394`, `VoiceSession.kt:14–65`, `ReceptionView.kt:35–78`. | **Contract-tested.** `VoiceSessionTest` covers both greeting/ready orderings, cancellation, release failure and late callbacks; `SessionOwnerTest` covers ownership. Existing CI/build success is not microphone evidence. | **Blocked for physical acceptance:** real interruption, echo, permissions/lifecycle behavior and reliable release on the intended device remain unproved. |
| Live two-way translation | **Implemented.** Native interpreter transports, selected language pair, bundled welcome and streamed captions exist. The UI can display explicitly paired captions, but current live adapters keep unlinked feeds separate rather than inventing a translation association. | **Historical limited live evidence:** two synthetic spoken language-pair exchanges are documented in `artifacts/VERIFICATION.md:79–88`. Not a 52-language, physical-device or free-only qualification. | **Blocked:** no compliant free-only audio route verified for the intended deployment; no current end-of-speech-to-first-audible-output p50/p95, echo or bilingual human evaluation. |
| Nighttime guest guidance | **Partly implemented / merely present operationally.** Night toggle persists a flag and selects reception mode (`reception.js:174–177`, `HotelActivity.kt:348–351`). Approved-knowledge search and operator paths exist in the gateway. | **Contract-tested:** approved knowledge vs unknown-answer behavior is covered by `test_missing_fields_and_approved_knowledge_only` and relay fixtures. | **Needs hotel info:** actual approved answers, hours, escalation owner, contacts and privacy policy. **Missing:** demonstrated staffed nighttime workflow, not just a visual mode. |
| Short-break coverage | **Missing.** No dedicated away/return state, expected return time, coverage owner or resumption acknowledgement was found in the active module, backend, admin or tests. A night flag is not a short-break workflow. | No demonstrated short-break workflow. | **Needs hotel info and missing brief sections:** staff-led activation/resumption, responsibility while away, guest wording and escalation rules must be specified and exercised. |
| Hotel configuration | **Implemented.** Authenticated hotel configuration, knowledge/services, contacts, timezone, check-in instructions and config versioning (`cloud/models.py:7–22`, `cloud/app.py:43–58`, `cloud/db.py:42–55`, `admin/index.html:8,13–17`). | **Contract-tested:** tenant/auth and config routes in the existing suites. **Baseline smoke:** a fresh server returned an empty authenticated hotel list, not a preloaded property. | **Blocked:** no approved pilot-property configuration was supplied or validated. The admin form prepopulates invented hotel details/instructions and must not be used as pilot truth. |
| Check-in guidance | **Implemented, not real check-in.** Existing shortcut collects booking reference/surname, calls a read-only connector, then returns hotel instructions with `guidance_only=true` and `checkin_completed=false` (`cloud/operations.py:23–26,162–177`, `cloud/gateway.py:80–93`). | **Contract-tested:** `test_checkin_guidance_and_connector_unavailability`; its success path uses a synthetic connector, not a hotel integration. | **Blocked:** owner-approved instructions and intended Phase 1 flow are missing. The current happy path depends on a reservation lookup; it is not a proven independent hotel-configured guidance flow. No identity-document collection, room allocation, access codes or PMS writes may be added. |
| Taxi requests | **Implemented as a request, not a booking.** Exact request confirmation, durable inbox item, idempotency and request receipt exist (`cloud/operations.py:109–160`, `cloud/gateway.py:100–108`). A dialer route may be configured. | **Contract-tested:** confirmation binding/cancellation/durability, concurrent deduplication and truthful dialer receipts in `tests/test_operations.py:77–143`. | **Needs hotel info:** who accepts and handles the request, supported hours and response expectation. **Blocked:** no real dispatch/acceptance receipt or staff rehearsal. No paid booking integration is authorized. |
| Staff messages | **Two distinct paths.** Local review/save uses `GuestMessages.kt:19–48` and explicitly does not notify staff. Backend note/request tools persist inbox items (`cloud/gateway.py:119–122`); authenticated staff can view/resolve them (`cloud/app.py:80–109`, `admin/index.html:5,15`). | **Contract-tested:** persistent notes, confirmation rejection, tenant-scoped inbox/resolve and duplicate calls in `tests/test_platform.py`. | **Blocked:** local save is not delivery. The operations API has no explicit typed message action (`cloud/operations.py:14–26`); backend delivery through voice tools is not a proven staff-led no-audio message flow. A real staff acknowledgement and receipt are missing. |
| Human handoff | **Implemented only up to durable escalation and dialer/number offer.** Results explicitly keep `connected=false` and can include a backup (`cloud/gateway.py:109–118`). | **Contract-tested:** no-operator and dialer results never claim an answered call (`tests/test_platform.py:68–71`, `tests/test_operations.py:132–143`). | **Needs hotel info:** actual primary, backup, responsibility and no-answer handling. **Blocked:** opening a dialer is not connected human handoff; no answered/no-answer or backup drill is proved. |
| In-place updates | **Implemented and previously live-tested within isolated Android fixtures.** Pinned metadata, signer/digest checks, resume/manual discovery and retry feedback remain intact. | Actual published 17.0.7 → 17.0.8 download/verification/system-install confirmation, with settings retained, is documented in `artifacts/VERIFICATION.md:5–17`. | No new distribution authorized by this Phase 1 brief. Existing update proof does not qualify audio or hotel operations. |

Application source references above are under `android/hotel/src/main/java/com/agentdeck/hotel/` unless a full alternative path is given; UI references are under `android/hotel/src/main/assets/reception/`. The bridge is `android/hotel/src/main/java/com/agentdeck/ui/ReceptionView.kt`.

### Staff-led access is a real gap

`HotelActivity.connect` starts reception voice before actions, and `chooseShortcut` requires an active reception session. `HotelGateway` also needs a current lease. Consequently, retaining these backend contracts is not enough to deliver a usable staff-led pilot while audio is blocked. Guidance, messages, requests and handoff need an explicit non-audio path without pretending that a voice session is live. Preserve their authorization, confirmation and receipt semantics; do not replace them with canned replies.

## Boundary findings

### Dummy data and operational truth

| Finding | Reachability and decision |
| --- | --- |
| `DemoConnector` (`cloud/connectors.py:7–12,45–49`) | **Reachable.** `Hotel.connector` accepts `demo`; an authorized configuration can select it, and the real gateway invokes that factory. Its synthetic reservation can reach the real check-in-guidance success path. A demo marker does not make it a valid pilot integration. Must be unavailable to pilot flows. |
| Sample message strings (`reception.js:31–41`) | **Inert but present.** Targeted search found definitions only, not a consumer that prefills or submits them. Do not call this live message fabrication. Remove the dead sample copy rather than inventing a replacement. |
| Admin initial configuration (`admin/index.html:17`) | **Reachable.** The page prepopulates an invented property identity, timezone and check-in instruction; Save accepts the edited JSON. Pilot setup must start from absent/unconfigured values, not publish this template as hotel-approved policy. |
| Bundled welcome | Deterministic app-owned greeting is allowed as interface speech, not evidence of live translation. Keep its source distinct from provider captions. |
| Test data/captures | Isolated contract tests and clearly labelled historical synthetic screenshots are not real guest/service proof. Never promote them into pilot receipts or live transcripts. |

### Credentials, guest data and logs

- **Saved device provider keys are encrypted in source:** `SecretStore.kt:24–49,62–75` uses a private no-backup file and AES-GCM with a non-exported device-keystore key. `CredentialStorage.kt:34–60,63–73,87–110` stages, decrypts/read-checks and atomically replaces encrypted content; it does not write a new plaintext fallback. Legacy plaintext migration is explicit and failed migration preserves recoverable data. `CredentialStorageTest` exercises these behaviors. This is not an independent physical-device cryptographic audit.
- **Embedded pilot keys are different:** `android/hotel/build.gradle.kts:50–57` can insert a provider account key into the release binary. An encrypted CI secret does not encrypt the resulting APK constant. Such artifacts are extractable and unsuitable for the new pilot boundary. Do not distribute them as a secure credential-storage solution.
- **Earlier diagnostic output exposed the embedded pilot credential.** Treat that credential as compromised. Temporary plaintext diagnostic copies were removed; owner-controlled rotation/revocation is required. Do not repeat its value or put it in this report, source, captures or issue comments.
- Backend staff/device tokens are stored as SHA-256 hashes, not reversible encrypted tokens (`cloud/db.py:49–60`). Provider/integration credentials come from environment variables; deployment secret management remains unverified.
- Guest local notes (`GuestMessages.kt:14,42–46`) and backend payloads (`cloud/db.py:13–25`) are not application-encrypted by those stores. Retention/deletion, storage protection, access and consent need approval; key encryption is not proof that guest data is encrypted.
- `FishVoice.kt:46–59` contains timing logs. It is incorrect to claim there is no latency instrumentation merely because no separate trace file was committed. No current successful speech-to-speech trace was captured. Logging timestamps alone cannot establish where an unmeasured delay occurs.

### Free-only audio gate

**No existing route is qualified for this pilot. Do not enable a route or run further live calls under an assumption that credits or a free model imply a free complete service. Keep the feature staff-led until qualification.**

This technical audit uses model identifiers solely to identify source/configuration; they are not proposed guest-facing or README copy.

| Existing route | Source and primary-source evidence | Qualification |
| --- | --- | --- |
| Hosted interpreter agent | `FishPlan.kt:37–73`, `cloud/fish.py:77–94,109–138`. Uses `google/gemini-3.8-flash` and `elevenlabs:scribe_v2_realtime` inside a hosted voice-agent session. [API pricing](https://docs.fish.audio/developer-guide/models-pricing/pricing-and-rate-limits.md) lists usage-based API billing and a free standalone TTS model; [agent overview](https://docs.fish.audio/agents/overview.md) describes the separate hosted-agent surface. | **Blocked:** free end-to-end hosted-agent entitlement, account billing linkage, region, quota and privacy approval not established. A free standalone TTS model does not prove free recognition, interpretation, transport or hosted sessions. Duration reservations in `cloud/fish.py` are not monetary/free-only safeguards. |
| Device interpreter | `GeminiVoice.kt:23–31,48–91` opens two `gemini-3.5-live-translate-preview` sessions. [Billing](https://ai.google.dev/gemini-api/docs/billing), [pricing](https://ai.google.dev/gemini-api/docs/pricing), [terms](https://ai.google.dev/gemini-api/terms), [model lifecycle](https://ai.google.dev/gemini-api/docs/deprecations). | **Blocked:** account-specific availability/quota, actual hotel deployment region and billing status not verified. The provider terms require Paid Services for API clients made available to users in the EEA, Switzerland or UK. Any Spanish/EEA pilot therefore cannot be declared compliant with free-only operation through this route. Do not infer the actual hotel's location from the selected language or voice timezone. |
| Managed assistance voice | `cloud/realtime.py:6–7,17–44`, environment-selected model default `gemini-2.5-flash-native-audio-preview-12-2025`. Same primary sources as the device route. | **Blocked:** same account, region, privacy and billing gates. The fetched lifecycle page lists a shutdown on 2026-11-17 for this default; do not assume preview availability indefinitely or silently switch models. |
| Bundled welcome audio | `HotelActivity.kt:228–260`, existing local WAV files and `greetings.js`. | Runtime playback does not need a synthesis API call. Playback is not a free live-translation route or evidence of provider conversation. |

Additional privacy blocker: the [hosted-agent history contract](https://docs.fish.audio/agents/monitor/conversation-history.md#what-gets-stored) says production transcripts/session records are retained even when audio recording is off. `record_audio=false` does **not** establish no transcription retention. The direct-API [unpaid-service terms](https://ai.google.dev/gemini-api/terms#unpaid-services) permit product improvement/human review and prohibit submitting sensitive, confidential or personal information; the terms distinguish paid-service handling and regional conditions. Hotel consent, data handling and deletion cannot be inferred from recording flags.

Provider selection is currently automatic from available backend/credentials (`HotelActivity.kt:160–179`): assistance uses the managed voice route; translation prefers the hosted route, otherwise a saved-key device route. No retry-time cross-provider fallback was found in that selection code, but it is not an owner-reviewed, explicitly pinned free-only route either. Do not add automatic switching or paid fallbacks.

### Scope and copy

- Existing gateway allowlisting rejects arbitrary actions; the inspected reservation connector is lookup-only (`cloud/connectors.py:22–36`). Existing guidance and taxi/handoff receipts explicitly deny completed check-in, booked taxi and connected-call claims. Preserve those boundaries.
- A prompt prohibition does not validate hotel-supplied text for access codes or other prohibited sensitive content. Hotel configuration needs actual review, not merely a successful JSON save.
- Current README/setup copy still names third-party providers (`README.md:5,18,30,62–64`). It does not satisfy the new owner boundary for README-type and guest-facing copy. Audit the remaining documentation and native setup copy during implementation; do not remove technical identifiers needed internally to operate securely.
- No new PMS writes, ID collection, access-code delivery, room allocation, payment/refund or paid taxi booking work is authorized.

## Checks and evidence actually available

### Exercised during this baseline

Started the actual backend with a temporary database/home, an ephemeral admin token and **no provider credentials**. The child inherited a restricted environment rather than the live app configuration. The process was terminated and the disposable database/scaffold removed after the probe.

| Check | Observed result |
| --- | --- |
| `/health` | 200, status `ok` |
| `/api/hotels` without authentication | 401 |
| `/api/hotels` with fixture admin authentication | 200, empty list; zero configured properties |
| Actual `/admin` page | 200, HTML served |
| Device-session endpoint without device authentication | 401 |
| Provider requests | Zero |

This is an isolated runtime/authentication check. It is not hotel setup approval, a successful operational request, speech proof or a staff pilot.

### Prior evidence, not rerun or upgraded into new claims

- Existing CI at the baseline revision succeeded, including the signed artifact build. Prior local output recorded 84 backend tests passed and successful native tests/builds. No new full suite was run merely to repeat that result during this documentation-only baseline.
- `artifacts/VERIFICATION.md:20–35` distinguishes actual native welcome playback from synthetic browser/caption fixtures. Current 17.0.9 assets differ from the historical screenshot revision.
- `artifacts/VERIFICATION.md:79–88` records limited synthetic live speech exchanges and slow caption arrival. Those caption timestamps are not first-audible-output latency, and are not a free-only qualification.
- Earlier setup API timings and an unauthenticated HTTP connection to a media host were not authenticated room-connect or speech-to-speech measurements. They cannot establish the cause of the owner's reported latency. A failed provisioning response is not a successful agent-creation benchmark.
- Reducing configured response wait from 700 to 450 ms removes 250 ms of configured wait, not a measured 250 ms improvement in complete speech latency. The one-sentence output instruction also needs bilingual review: brevity must never drop meaning or critical entities.

Existing image evidence, with limits:

- [Actual reception home, historical release](screens/17.0.6-home.png).
- [Actual language selectors, historical release](screens/17.0.7-languages.png).
- [Actual native welcome fixture, not a provider conversation](screens/17.0.7-welcome-fixture.png).
- [Actual old-app offer for the published update](screens/17.0.8-update-offer.png).

No new Phase 1 end-to-end video, real hotel request receipts, staff/guest interview findings or supervised pilot results exist. Historical recordings are not substitutes. A fresh secret-free recording must show the actual accepted flow and name any fixture use; it must not contain guest data or embedded credentials.

## Configuration and owner inputs required

1. The omitted brief sections 3–14 and the exact definitions of the three acceptance gates. The received text jumps from section 2 to section 15. Do not invent those criteria or claim they passed.
2. Actual pilot-property name, timezone, approved languages, service hours, knowledge and check-in guidance, with an identified staff approver and review date. Do not put access codes, identity documents or guest records in the brief.
3. Night/short-break responsibility: staff coverage owner, return expectations, activation/resumption procedure and failure fallback.
4. Approved primary/backup human contacts, emergency wording, no-answer handling and who is responsible for returning to a waiting guest.
5. Unpaid taxi-request handling: who receives/accepts it, what staff promise, response expectations and the precise receipt that means accepted vs merely queued. No paid booking permission is implied.
6. Staff message handling: local-only vs staff inbox, authentication, acknowledgement/resolution workflow, visibility and retention/deletion policy.
7. A provider/account route with documented free-only eligibility, actual deployment region, quotas, billing linkage, privacy/consent and supported models. Current routes do not pass; staff-led mode is required while blocked. Do not send credentials in chat.
8. Credential rotation/revocation by the credential owner, followed by a non-embedded-key configuration plan. This baseline does not rotate live credentials.
9. Real staff/guest participation and supervised sessions to test whether the pilot solves an actual problem and fits work. Interviews must be conducted, not fabricated. Record permission and avoid guest content in captures.
10. Owner review before posting any issue update, pushing main, publishing a release or distributing an APK. No Phase 2 proposal while Phase 1 acceptance is incomplete.

## Implementation order after the scope is complete

Preserve the approved reception design and active module. First lock session/mode correctness and free-only eligibility; then establish the staff-led route while audio is gated. Validate actual hotel configuration; make night/short-break responsibility explicit; expose only guidance and narrow confirmed requests/messages/handoff with accurate receipts; then perform supervised validation. Remove pilot-reachable demo/sample defaults and secure credential handling as part of those changes. Do not rebuild the app, add broad queues/marketplaces or integrate financial/PMS writes.

## Master issue update — draft for owner review only

Proposed replacement truth summary for issue 1:

- Main remains 17.0.4 at `04ae19a`; newer application work is on `omp/remediation-r01-r17` at `d8f45a4` (17.0.9/code 39). Do not roll back newer work to the issue's old 10.0.2 snapshot.
- Latest public release is 17.0.8. A successful 17.0.9 build artifact is not a new published release or a confirmed upgrade of the owner's tablet.
- Source/contracts cover interpreter sessions, hotel knowledge, local notes, backend request receipts and limited human-contact affordances. Short-break coverage, verified hotel setup and staff-led no-audio access remain gaps.
- Free-only audio is blocked pending eligibility/region/privacy/billing qualification; no automatic provider switching or paid fallback is authorized.
- Pilot-accessible demo configuration, embedded credentials and unapproved policy defaults must be removed or gated before real use. Rotate the exposed pilot credential.
- There are no pilot-validated features, new secret-free end-to-end video, real hotel receipts or interview findings yet. Missing brief sections and the three gate definitions must be supplied before acceptance.
- Phase 1 is reception support under staff responsibility. PMS/financial writes and broader roadmap work remain outside this phase.

**Not posted. No new issue created. Phase 1 remains blocked, not done.**
