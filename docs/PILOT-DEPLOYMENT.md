# Reception-support pilot deployment

**Not pilot-validated.** Manzanilla supports guests and hotel staff; staff retain responsibility for all guidance, requests and escalation. Missing hotel information is a blocker, not permission to invent policy.

## Prepare an isolated matching backend

Install pinned dependencies in an isolated environment, configure a private
persistent `ORANGE_DB`, set a fresh administrator credential and serve
`cloud.app:app` behind HTTPS. Use exactly one backend process with the existing
SQLite ledger. Follow [environment and security](ENVIRONMENT.md). Never reuse
a verification database or test credentials for real hotel use.

`VOICE_ROUTE=fish` remains the default. Self-hosted voice is evaluation-only,
requires explicit `ALLOW_UNQUALIFIED_LIVEKIT=1` for genuinely new managed
reservations and permits one owning worker per room namespace. Prior-request
lookup/recovery and cleanup remain available when the flag is withdrawn.
The [compose recipe](../infra/livekit.compose.yaml) is not a production recipe
after changing secrets. Reject deployments requiring multiple owners,
horizontal/multi-region dispatch or strict immediate token revocation until
implemented and qualified. Room deletion and nominal token expiry do not prove
refreshed-token invalidation or prevent room replay.

Open `/admin` with administrator authorization. Enter the actual property ID, name, timezone, approved languages, arrival instructions, knowledge, service hours and contacts. The form starts unconfigured. Do not put access codes, identity documents or payment details in hotel content.

For each intended guest language, use **Approved hotel answers** to review the
exact original and approve the exact displayed title/body. A draft is not an
approval. You can enter a rendering manually or approve the unchanged original
when you know its language; do not infer an old source's language or approval.
Source edits invalidate prior renderings. Oversized content must be curated,
not silently truncated. Missing approval produces staff-help guidance.

Create separate hotel-scoped staff and device credentials. Staff use the
dashboard to receive, acknowledge with a responsible label, and resolve
requests with a private outcome note. A shared credential is not proof of an
individual staff identity. Device credentials belong only to the corresponding
property's installation. Confirm cross-property denial in an isolated property.

## Configure the app

Install an exact verified signed hotel artifact in place; do not uninstall
existing data. The source contract pairs `Manzanilla-Hotel.apk` with
`hotel-release-metadata.json` and contains no provider keys.
`Manzanilla-Reception.apk` / `release-metadata.json` remain personal testing
and are also key-free. Gemini keys and `FISH_VOICE_ID` never enter either APK.
The known public 17.0.37/code 67 APK is personal testing, not hotel acceptance;
this repair has not published or deployed a candidate.

Both distributions retain the same app ID/signer/version and saved data/keys.
Their in-app feeds never switch distribution. An operator conversion requires
the official signed hotel APK at a higher code and Android's in-place approval,
not equal-code replacement or uninstall. Publication requires both artifact
pairs and exact-artifact signed UI/retention proof; that is not voice or staff
acceptance. See [artifact and credential contracts](ENVIRONMENT.md#signing-and-publication).

In authenticated staff setup enter the actual HTTPS service URL and issued
device credential, not the administrator credential. The encrypted connection
bundle is not embedded in the APK.

The repair candidate's hotel distribution is managed-only and its setup requires
the tablet's existing Android screen-lock credential. It retains personal keys
without exposing them. A saved enrollment is only configuration: wait for the
authenticated connection state and the particular service's availability.
Readiness is not proof of provider entitlement or a staff member watching the
inbox. Keep a staffed dashboard and follow the separate delivery and voice gates
before treating any hotel build as production-ready.

Use Hotel assistance to select a language and open microphone-off support. Arrival guidance and answers require a current approved rendering in that language; Gemini may select approved facts, never supply policy prose. Arrival guidance never completes check-in. An unknown answer creates no ticket unless the guest chooses help or a message. Messages and taxi requests require exact review and confirmation before a durable inbox write. A receipt means saved in the staff inbox, not staff acknowledgement or a taxi booking. Human-contact options do not claim an answered call.

For direct personal testing the source keeps saved-key precedence over bundled
Fish fallback and explicit direct preference over enrollment, without adding
a PIN to voice-only setup. The public testing key is extractable; its account
owner must rotate it after testing. Hotel new sessions never silently fall
back to that path. All 52 interface choices remain, with human-unreviewed
catalogue translations. Hotel `supported_languages` bounds enrolled speech,
not acoustic qualification. A provider support list or configured language
does not pass a pair. Explicit personal unqualified testing remains available.
See [the self-hosted evaluation limits](selfhosted-voice-route.md).

Hotel operation cleanup is best effort if the tablet process dies or a network
failure leaves a start outcome uncertain. The server also expires stale sessions;
a durable outbox and server reconciliation are required for stronger guarantees.

## Before supervised use

- Obtain the missing Phase 1 brief sections and explicit three gate definitions; do not substitute these checks for the owner's gates.
- Have a named hotel staff member approve all content and primary/backup contacts, night/short-break responsibility, response expectations and return-to-desk procedure.
- Verify privacy/consent, storage access, retention/deletion and incident handling. Rotate/revoke the credential exposed in older pilot artifacts.
- Exercise an actual staff message receipt/acknowledgement, taxi-request handling and answered/no-answer human-contact drill under supervision. Do not create paid bookings.
- Conduct real staff/guest interviews about the reception problem and workflow fit. Record permission; never fabricate findings or include guest content/secrets in captures.
- Mark source, contract, live and pilot evidence separately. The isolated screenshots/video in the verification record are not property acceptance.
- Use the [acoustic protocol](TEST-PLAN.md#voice-repair-reproduction-unreleased)
  for every intended hotel pairing: at least 100 distinct held-out utterances,
  repetitions, both directions, common-clock captures, bilingual meaning and
  naturalness review, critical-entity denominators and all failed trials.
  Require the absolute latency, interruption, accuracy, echo and recovery gates;
  no comparable baseline means no improvement claim.
- Include connection and greeting overlap, long synthesis interruption, repeated
  same-language speech, unclear words, names/room numbers/negation/prices/dates,
  mute/unmute, rapid stop/start, network loss, expired initial token, reconnect
  and revocation; self-hosted evaluation also needs refreshed-token and room
  replay checks. No stale translation or microphone resurrection is acceptable.
- Run actual designated-staff fulfilment on the isolated property: one guest
  request must be received, acknowledged and marked resolved through the real
  dashboard, while the guest sees only public status and not the private note.
  A saved receipt or synthetic browser/native test is not staff acceptance.
  Manual phone/external reservation adapters require approved test environments
  before stronger claims; a staff resolution is not a certified taxi booking
  or an answered call.

## Currently blocked external gates

No isolated matching physical tablet/common-clock capture, current verified
provider entitlement, bilingual reviewers or designated hotel staff/test
deployment have been supplied for this repair. The remaining portion of the
historical aggregate $4 allowance and conservative provider-cost bound cannot
be established. Make no paid call, including Gemini drafts or answers; the
allowance is not renewed. Physical/provider and actual staff acceptance remain
blocked, and no language pair or route is qualified. Historical provider record
gaps, tool timings, transport checks and emulator screenshots are non-acoustic.
Missing gates do not authorize a provider/model substitution or hotel rollout.


## Explicit exclusions

No PMS writes, completed check-in, ID collection, access-code delivery, room allocation, payments, refunds, paid taxi booking, broad queue product or marketplace. No Phase 2 proposal while Phase 1 acceptance remains incomplete. Keep issue 1 as the single master issue; posting changes there still requires owner review.
