# Manzanilla

Reception support for guests and hotel staff. Staff retain responsibility for guidance, requests and human handoff.

## Current version: 17.0.10

- Hotel assistance works with the microphone off: arrival guidance, hotel questions, taxi requests, confirmed staff messages and human-contact options.
- Actual hotel configuration is required. Missing guidance or contacts remain unavailable; no sample reservation or invented policy powers the app.
- Messages and taxi requests require review and confirmation. A saved inbox receipt is not staff acknowledgement, an answered call or a taxi booking.
- **Live translation is gated pending verified free-only audio eligibility.** Existing language selectors, local greetings and voice code are retained, but credentials cannot bypass the gate. There is no paid fallback or automatic route switching.
- The English startup greeting remains **“Hey, how are you doing?”** Local greeting playback is app-owned, not proof of a live provider conversation.
- No provider account key or hotel device token is embedded in the APK. The hotel connection is entered through authenticated staff setup and encrypted on the device.

This release fixes stale managed-agent configuration identities, prevents conflicting translation output from being mixed, removes the translation-shortening instruction and adds endpoint-labelled timing. These are source/contract improvements, **not a measured physical-device latency or full-duplex acceptance claim**.

## Install or update

**[Download the latest app](https://github.com/somdipto/manzanilla/releases/latest/download/Manzanilla-Reception.apk)** · [Release history](https://github.com/somdipto/manzanilla/releases)

1. Open Manzanilla and tap **Check updates**. Resume also checks automatically; a foreground app rechecks every ten minutes.
2. Tap the downloaded update, allow installation from this source if requested, then approve the system **Update** prompt.
3. Do not uninstall. In-place updates retain app data and saved credentials.

Versions through 17.0.7 can hide discovery failures or defer checks. If the update does not appear, use the download link for a one-time manual in-place **Update**, not an uninstall.

Installation still requires system confirmation; this is not silent remote installation.

## Staff setup

Open the setup gear and authenticate with the device screen lock. Enter the hotel's actual HTTPS service URL and a hotel-scoped **device** credential issued by its administrator. Never enter the administrator credential. URL and device token are encrypted and replaced together.

The backend must also run the matching revision for confirmed messages and microphone-off guidance. Staff approve the hotel's real policies, contacts, hours and knowledge before use. Live audio remains blocked even if old voice keys are saved. Rotate/revoke the exposed credential from older pilot builds through its account owner; removing it from a new APK does not revoke it.

Legacy local-only notes are not uploaded, relabelled as delivered or automatically deleted by the update. New staff messages use the confirmed backend inbox flow.

## Evidence and limitations

[Verification record](artifacts/VERIFICATION.md) · [Pre-change Phase 1 baseline](docs/PHASE-1-BASELINE.md) · [Configuration and security](docs/ENVIRONMENT.md) · [Pilot deployment](docs/PILOT-DEPLOYMENT.md)

[Isolated message form](docs/screens/17.0.10-message-form-fixture.png) · [Isolated request receipt](docs/screens/17.0.10-message-receipt-fixture.png) · [Secret-free staff-led fixture video](docs/videos/17.0.10-staff-led-fixture.webm)

Fixture screenshots/video are labelled isolated verification, not real hotel requests or guest interviews. Browser bridge fixtures are not native audio proof. Physical echo handling, audible latency, language quality, real hotel receipts and supervised workflow validation remain unverified. Phase 1 is not declared complete; the omitted brief sections and three acceptance-gate definitions are still required.

## Repository

`android/hotel` is the active app. `cloud` contains authenticated hotel operations; `admin` contains hotel setup and inbox views. `edge`, `infra` and `shared` contain supporting interfaces. The older `android/app` is not the reception implementation.

## Build

Requires JDK 17 and SDK 34:

```sh
cd android
./gradlew :hotel:testDebugUnitTest :hotel:lintDebug :hotel:assembleDebug
```

Signed publication preserves the existing application identity and signing certificate, verifies the APK against the previous public release, and checks immutable update metadata. CI holds the signing credential; it is never committed. There is no keyed-pilot build option.

## License

See [LICENSE](LICENSE).
