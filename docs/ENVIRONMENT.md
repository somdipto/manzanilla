# Environment and credentials

The backend requires Python 3.12+ and the dependencies pinned in `requirements.txt`. Native builds require JDK 17, SDK 34 and the checked-in build wrapper.

## Backend

- `ORANGE_DB`: private persistent database path. Never point verification at the deployed database.
- `ORANGE_ADMIN_TOKEN`: random administrator credential of at least 32 characters. Keep it server-side; never enter it in the reception app.
- Issue separate hotel-scoped device and staff credentials through the authenticated dashboard. Tokens are shown only on issuance; the database stores their hashes.
- Serve the actual deployment over HTTPS. Do not bypass certificate checks or put credentials in URLs.
- Configure real hotel name, timezone, approved languages, knowledge, arrival instructions and human contacts. The admin form starts empty. A successful JSON save is not staff approval of its content.
- `demo` is not an accepted connector. Existing stale demo settings cannot produce synthetic reservations. Arrival guidance uses approved hotel instructions without reservation lookup; it does not complete check-in.

Application-level inbox payloads are not encrypted by the database layer. Configure storage access, disk/backup encryption, retention, deletion and consent before real guest use. Do not collect identity documents, access codes or payment information.

## Device

Authenticated staff setup accepts the actual HTTPS hotel service URL and a device credential. They are stored as one encrypted bundle through `HotelConnection` / `SecretStore`, using authenticated encryption and a device-keystore key. Replacing the bundle cannot accidentally pair a new token with an old URL. The URL cannot contain login information, query parameters or a fragment.

The signed release compiles `FISH_API_KEY` from the CI secret when the device store is empty. No backend URL, device token, or administrator credential is compiled into the APK. Legacy saved voice credentials remain encrypted. Old local-only notes are not automatically uploaded or erased; they must not be interpreted as staff delivery.

## Live audio gate

All current native start paths and public backend voice admission endpoints deny live audio because no complete free-only route is qualified. This is not an environment opt-in and cannot be bypassed by entering a key. Staff-led operational endpoints remain available. Existing session-end/revocation handling is retained so known sessions can still be cleaned up.

Do not enable a provider before verifying account/model availability, actual region, quotas, billing linkage and privacy terms. A free synthesis model, credit balance, disabled recording flag or passing mocked test does not qualify the complete live service. Do not add paid fallbacks or automatic provider switching. Internal provider component tests intercept all network requests explicitly.

Managed agent cache identity includes the complete published configuration and credential scope. A changed prompt/model/recognizer/voice/turn setting gets a new identity, without mutating an agent already serving another session.

Timing logs contain endpoint names, sequences and monotonic timestamps, not guest text or credentials. Input activity estimates, provider-final receipt, decoded output receipt and sampled playback-head progress are distinct. None is an acoustic p50/p95 measurement or a reliable input/translation association by itself.

## Signing and publication

Signing uses `ORANGE_KEYSTORE`, `ORANGE_STORE_PASSWORD`, `ORANGE_KEY_ALIAS` and `ORANGE_KEY_PASSWORD`; CI also reads `ORANGE_KEYSTORE_BASE64`. The signed release job also reads `FISH_API_KEY` and `FISH_VOICE_ID` from repository secrets and compiles the Fish key into the APK for the owner's test install. These values stay in CI. Never commit them. A published APK makes that Fish key extractable.

`android/VERSION` and `android/VERSION_CODE` advance explicitly. Publication must preserve `dev.orange.hotel` and the existing signer, supersede the previous public APK and reproduce `release-metadata.json` from the exact artifact. Ordinary file-installed apps still require system confirmation to update.

The credential embedded in earlier pilot APKs must be rotated/revoked by its account owner. Removing embedding in this version does not revoke previously exposed copies. Never print or attach that credential while investigating.

## Optional read-only integrations

Reservation gateway credentials stay server-side under `PMS_<UPPERCASE_UTF8_HEX_OF_EXACT_HOTEL_ID>_URL` and `_TOKEN`. The fixed lookup route is read-only by contract; it is not used to complete check-in. Edge deployments use a hotel-scoped role, WSS and an owner-controlled export. No sample export may serve a real hotel.
