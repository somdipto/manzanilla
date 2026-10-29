# Reception-support pilot deployment

**Not pilot-validated.** Manzanilla supports guests and hotel staff; staff retain responsibility for all guidance, requests and escalation. Missing hotel information is a blocker, not permission to invent policy.

## Deploy the matching backend

Install the pinned dependencies in an isolated environment, configure a private persistent `ORANGE_DB`, set a fresh administrator credential and serve `cloud.app:app` behind HTTPS. Follow [environment and security](ENVIRONMENT.md). Never reuse a verification database or test credentials.

Open `/admin` with administrator authorization. Enter the actual property ID, name, timezone, approved languages, arrival instructions, knowledge, service hours and contacts. The form starts unconfigured. Do not put access codes, identity documents or payment details in hotel content.

For each intended guest language, use **Approved hotel answers** to review the
exact original and approve the exact displayed title/body. A draft is not an
approval. You can enter a rendering manually or approve the unchanged original
when you know its language; do not infer an old source's language or approval.
Source edits invalidate prior renderings. Oversized content must be curated,
not silently truncated. Missing approval produces staff-help guidance.

Create separate hotel-scoped staff and device credentials. Staff use the inbox to inspect and resolve real requests. Device credentials belong only to the corresponding property's installation. Confirm that a credential cannot inspect another property's requests before deployment.

## Configure the app

Install the verified signed update in place; do not uninstall existing app data. In authenticated staff setup, enter the actual HTTPS service URL and the issued device credential—not the administrator credential. The connection bundle is encrypted on-device and is not embedded in the APK.

Use Hotel assistance to select a language and open microphone-off support. Arrival guidance and answers require a current approved rendering in that language; Gemini may select approved facts, never supply policy prose. Arrival guidance never completes check-in. An unknown answer creates no ticket unless the guest chooses help or a message. Messages and taxi requests require exact review and confirmation before a durable inbox write. A receipt means saved in the staff inbox, not staff acknowledgement or a taxi booking. Human-contact options do not claim an answered call.

For the owner's direct testing, the public release contains a shared Fish credential. Anyone with the APK can extract and use it; use a disposable, account-limited key and rotate it after testing. A saved device key takes precedence, and a saved hotel connection remains selected until the user chooses direct Fish. See [the self-hosted route](selfhosted-voice-route.md) for the separate worker deployment. Retained local greeting playback is not a live-speech demonstration. Physical-device echo, latency and full-duplex behavior remain unverified; validate them during supervised use.

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

## Explicit exclusions

No PMS writes, completed check-in, ID collection, access-code delivery, room allocation, payments, refunds, paid taxi booking, broad queue product or marketplace. No Phase 2 proposal while Phase 1 acceptance remains incomplete. Keep issue 1 as the single master issue; posting changes there still requires owner review.
