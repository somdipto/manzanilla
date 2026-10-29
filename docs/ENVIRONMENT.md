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
needs it. Hotel delivery/feeds and signed qualification are separate gates; these
source candidates are not production hotel approval.

## Live audio route

An enrolled device requests a managed session from the backend: `VOICE_ROUTE=fish` remains the default hosted Fish route, while `VOICE_ROUTE=livekit` uses the separate evaluation worker described in [the self-hosted route](selfhosted-voice-route.md). Managed provider credentials stay on the worker/backend. Personal direct testing is a distinct route with the explicit credential-exposure risk above. The legacy generic relay remains disabled.

Provider usage is billed to the owner's provider account. Internal provider component tests intercept all network requests explicitly.

Readiness probes authenticate the saved connection without creating an operation
or opening a microphone. `ready` means the heartbeat succeeded, not that a voice
provider is entitled or staff are present. Per-service flags require current
approvals, configured Gemini, staff credentials or valid contacts as applicable.
An older backend without these flags leaves hotel actions unavailable; it does
not disable independent direct testing.

Managed agent cache identity includes the complete published configuration and credential scope. A changed prompt/model/recognizer/voice/turn setting gets a new identity, without mutating an agent already serving another session.

Timing logs contain endpoint names, sequences and monotonic timestamps, not guest text or credentials. Input activity estimates, provider-final receipt, decoded output receipt and sampled playback-head progress are distinct. None is an acoustic p50/p95 measurement or a reliable input/translation association by itself.

## Signing and publication

Signing uses `ORANGE_KEYSTORE`, `ORANGE_STORE_PASSWORD`, `ORANGE_KEY_ALIAS` and `ORANGE_KEY_PASSWORD`; CI also reads `ORANGE_KEYSTORE_BASE64`. At the owner's request, the signed release job injects `FISH_API_KEY` for direct testing. Never commit credentials. The published APK is public and its embedded Fish key is extractable; use a disposable key with appropriate account limits and rotate it after testing.

`android/VERSION` and `android/VERSION_CODE` advance explicitly. Publication must preserve `dev.orange.hotel` and the existing signer, supersede the previous public APK and reproduce `release-metadata.json` from the exact artifact. Android may require system confirmation for file-installed apps.

Building a key-free hotel artifact does not revoke a credential exposed in any previous public testing APK. Its account owner must rotate/revoke it after testing. Never print or attach that credential while investigating.

## Optional read-only integrations

Reservation gateway credentials stay server-side under `PMS_<UPPERCASE_UTF8_HEX_OF_EXACT_HOTEL_ID>_URL` and `_TOKEN`. The fixed lookup route is read-only by contract; it is not used to complete check-in. Edge deployments use a hotel-scoped role, WSS and an owner-controlled export. No sample export may serve a real hotel.
