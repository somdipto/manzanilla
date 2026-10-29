# Environment and credentials

The backend requires Python 3.12+ and the dependencies pinned in `requirements.txt`. Native builds require JDK 17, SDK 34 and the checked-in build wrapper.

## Backend

- `ORANGE_DB`: private persistent database path. Never point verification at the deployed database.
- `ORANGE_ADMIN_TOKEN`: random administrator credential of at least 32 characters. Keep it server-side; never enter it in the reception app.
- Issue separate hotel-scoped device and staff credentials through the authenticated dashboard. Tokens are shown only on issuance; the database stores their hashes.
- Serve the actual deployment over HTTPS. Do not bypass certificate checks or put credentials in URLs.
- Configure real hotel name, timezone, approved languages, knowledge, arrival instructions and human contacts. The admin form starts empty. A successful JSON save is not staff approval of its content.
- `GEMINI_API_KEY`: backend-only hotel fact selection and unapproved staff-review drafts, using fixed `gemini-3.8-flash`. Manual approval and exact normalized-key lookup do not require a model call. Never put this key in either APK or use a second model as proof that a translation is true.
- Approve a current rendering for each guest locale in the admin content-review panel. Old hotel configuration is not an approval; source or rendering edits invalidate cached information. Draft failure leaves manual editing and existing approvals intact.
- `demo` is not an accepted connector. Existing stale demo settings cannot produce synthetic reservations. Arrival guidance uses approved hotel instructions without reservation lookup; it does not complete check-in.

Application-level inbox payloads are not encrypted by the database layer. Configure storage access, disk/backup encryption, retention, deletion and consent before real guest use. Do not collect identity documents, access codes or payment information.

## Device

Authenticated staff setup accepts the actual HTTPS hotel service URL and a device credential. They are stored as one encrypted bundle through `HotelConnection` / `SecretStore`, using authenticated encryption and a device-keystore key. Replacing the bundle cannot accidentally pair a new token with an old URL. The URL cannot contain login information, query parameters or a fragment.

The published 17.0.37 testing APK contains an owner-requested, extractable shared Fish key. In the repair candidate, `-Pdistribution=personal-test` remains the default: direct Fish uses an encrypted staff-saved key before any bundled testing fallback, and explicit direct selection takes precedence over enrollment. `-Pdistribution=hotel` permits only new managed translation sessions and never exposes or erases a retained personal key. Both debug distributions and hotel releases compile empty provider fields even when keys are injected. No backend URL, device token or administrator credential belongs in either APK. Old local-only notes are not uploaded or erased and are not staff delivery.

Hotel setup requires the tablet's existing Android screen-lock credential. No
screen lock means setup remains locked; the guest flow cannot enroll or reset
one. Personal voice-only setup remains usable without an added PIN. Do not
replace the original connection or voice credential while durable cleanup still
needs it. Signed installation, distribution-specific delivery and hotel
qualification remain separate gates; source candidates are not production
hotel approval. All 52 interface translations remain human-unreviewed.

## Live audio route

An enrolled device requests a managed session from the backend: `VOICE_ROUTE=fish` remains the default hosted Fish route, while `VOICE_ROUTE=livekit` uses the separate evaluation worker described in [the self-hosted route](selfhosted-voice-route.md). Managed provider credentials stay on the worker/backend. Personal direct testing is a distinct route with the explicit credential-exposure risk above. The legacy generic relay remains disabled.

For new managed LiveKit reservations the backend additionally requires
`ALLOW_UNQUALIFIED_LIVEKIT=1`. Leave it disabled normally. The check follows
lookup of the caller's prior request: flag withdrawal must not block backend
startup, recorded-request recovery/reconciliation, end or cleanup, or relabel
an existing uncertain request as not created. It does not gate direct Fish.

The current deployment boundary is exactly one backend process with the local
SQLite ledger and one owning worker per room namespace. The compose recipe
is evaluation-only, not production after replacing secrets. Reject production
requirements for multiple owners, horizontal/multi-region dispatch or strict
immediate token revocation until implemented and qualified. Initial token
expiry and room deletion do not prove refreshed-token invalidation or prevent
room replay. See [evaluation constraints](selfhosted-voice-route.md).

Hotel `supported_languages` bounds enrolled hotel speech without removing the
52 interface choices or explicit personal testing of unqualified pairs. Vendor
coverage, staff configuration and interface translation presence are not
provider/device speech qualification.

Provider usage is billed to the owner's provider account. Internal provider component tests intercept all network requests explicitly.

Readiness probes authenticate the saved connection without creating an operation
or opening a microphone. `ready` means the heartbeat succeeded, not that a voice
provider is entitled or staff are present. Per-service flags require current
approvals, configured Gemini, staff credentials or valid contacts as applicable.
An older backend without these flags leaves hotel actions unavailable; it does
not disable independent direct testing.

Managed agent cache identity includes the complete published configuration and credential scope. A changed prompt/model/recognizer/voice/turn setting gets a new identity, without mutating an agent already serving another session.

Timing logs contain endpoint names, sequences and monotonic timestamps, not guest text or credentials. Input activity estimates, provider-final receipt, decoded output receipt and sampled playback-head progress are distinct. None is an acoustic p50/p95 measurement or a reliable input/translation association by itself.

Physical/provider acceptance requires [the acoustic protocol](TEST-PLAN.md#voice-repair-reproduction-unreleased):
100 distinct held-out hotel utterances per intended pair, repetitions, both
directions, common-clock capture, bilingual meaning/naturalness review,
critical-entity denominators and all failures. No comparable physical baseline
or candidate capture is available, so no measured improvement is claimed.
Current provider entitlement, isolated matching tablet/capture, bilingual
review and actual designated staff acceptance are missing. The remaining
historical aggregate $4 allowance and conservative cost reservation cannot be
established; make no paid call, including Gemini draft/answer acceptance.

## Signing and publication

Signing uses `ORANGE_KEYSTORE`, `ORANGE_STORE_PASSWORD`, `ORANGE_KEY_ALIAS` and
`ORANGE_KEY_PASSWORD`; CI also reads `ORANGE_KEYSTORE_BASE64`. Never commit
credentials. The source publication contract builds two distributions in
isolated jobs from the same commit/version:

| Distribution | Public APK | Metadata / matching latest feed | Compiled provider access |
| --- | --- | --- | --- |
| `personal-test` (default) | `Manzanilla-Reception.apk` | `release-metadata.json` | Disposable Fish testing key in release only |
| `hotel` | `Manzanilla-Hotel.apk` | `hotel-release-metadata.json` | No shared provider key; managed enrollment only |

The personal-test release requires its injected disposable `FISH_API_KEY` and
a read-only account-access check; that check is not a voice session or acoustic
acceptance. The resulting public key is extractable. All debug keys and hotel
release provider keys are empty regardless of injected values. Gemini keys
never enter either APK; `FISH_VOICE_ID` is a non-secret voice identifier.
Building hotel does not revoke any previously exposed testing key: its account
owner must rotate/revoke it after testing, without printing or attaching it.

`android/VERSION` and `android/VERSION_CODE` advance explicitly. Both artifacts
retain `dev.orange.hotel`, the existing signer and increasing version code.
Each feed selects only its own distribution, including fallback and cached
downloads; the in-app updater never switches distribution. An operator's
intentional personal-test-to-hotel transition uses the official signed
`Manzanilla-Hotel.apk` at a higher code and Android-approved in-place update,
never equal-code replacement or uninstall. Saved keys/data remain retained.

Publication requires both exact APK/metadata pairs and private signed-upgrade
proof bound to each artifact's digest, commit, package/code, signer and
distribution. Retained data and actual installed Reception/bridge/mic-off
readiness are separate from voice/hotel acceptance. A missing hotel asset or
another artifact's evidence cannot pass publication. These source changes
have not published or deployed a candidate: the known public 17.0.37/code 67
artifact is personal testing with an exposed Fish key, not an accepted hotel
release. Android may still require system installation approval.

## Optional read-only integrations

Reservation gateway credentials stay server-side under `PMS_<UPPERCASE_UTF8_HEX_OF_EXACT_HOTEL_ID>_URL` and `_TOKEN`. The fixed lookup route is read-only by contract; it is not used to complete check-in. Edge deployments use a hotel-scoped role, WSS and an owner-controlled export. No sample export may serve a real hotel.
