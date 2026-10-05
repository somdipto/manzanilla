# Manzanilla

**Faster execution hardware for the hospitality industry.**

Manzanilla Reception is a landscape Android app with a reception bell and a hands-free live-translation screen. Choose the guest and hotel languages from the top language pill. Add your Gemini API key in Settings; it is encrypted on the device and is not included in the APK.

<p align="center">
  <img src="docs/screens/design-u-emulator.png" alt="Manzanilla translation screen, scripted UI replay" width="720">
</p>

<p align="center">
  <a href="https://github.com/somdipto/manzanilla/releases/latest/download/Manzanilla-Reception.apk"><img src="https://img.shields.io/badge/%E2%AC%87%20Download%20APK-Manzanilla%20Reception-2ea44f?style=for-the-badge&logo=android&logoColor=white" alt="Download APK" height="56"></a>
  &nbsp;
  <a href="https://github.com/somdipto/manzanilla/releases"><img src="https://img.shields.io/badge/All%20releases-GitHub-24292e?style=for-the-badge&logo=github" alt="All releases" height="56"></a>
</p>

## Install

1. On the Android device, open this link and download the APK: **[Manzanilla-Reception.apk (latest)](https://github.com/somdipto/manzanilla/releases/latest/download/Manzanilla-Reception.apk)**
2. Open the downloaded file. If Android asks, allow "Install unknown apps" for the browser or file manager you used, then tap Install.
3. Open **Manzanilla**. It starts straight on reception, without staff enrollment. Add your Gemini API key in Settings before trying translation.

<p align="center"><img src="docs/screens/1-bell.png" alt="Reception bell" width="520"></p>

All releases: https://github.com/somdipto/manzanilla/releases

## Updates install themselves

Every push to `main` builds a new signed release automatically. The app checks for a newer release each time it opens, downloads it, and asks Android to install it. You do not reinstall by hand.

<p align="center">
  <img src="docs/screenshots/auto-update-prompt.png" alt="Update prompt" width="520">
</p>

Android shows one "Update" confirmation for apps that were installed from a file. A device set up as device owner (kiosk) installs updates with no tap at all.

## Screens

Every reception screen, original and current, is listed in [docs/screens](docs/screens/README.md).

## What is in this repository

| Folder | What it is |
| --- | --- |
| `android/hotel` | The Manzanilla Reception app (landscape, microphone, auto-update) |
| `android/app` | Earlier general-purpose controller app, kept for reference |
| `cloud` | Backend for hotel setup, device sessions and voice calls |
| `admin` | Small web dashboard for hotel setup |
| `edge`, `infra`, `shared` | Supporting services and shared definitions |
| `docs` | Architecture, pilot deployment notes, test plan |
| `.github/workflows` | CI: build, test and publish releases |

The original hand-off notes are kept in [docs/HANDOFF-README.md](docs/HANDOFF-README.md).

## Status

Beta: design U has been built, installed and visually checked on an Android emulator. The screenshot and [screen recording](docs/videos/design-u-ui-replay.mp4) use debug-only scripted conversation events, not live Gemini output. The app uses `gemini-3.5-live-translate-preview` with two language sessions and on-device key entry. Real-key translation, microphone/speaker performance, echo cancellation and reconnect behavior have not yet been verified on a phone. Do not treat this as a production-ready interpreter. A paid key is recommended for real guest audio; free-tier use may be used for training.

## Build it yourself

Requires JDK 17 and the Android SDK.

```sh
cd android
./gradlew :hotel:assembleDebug
```

Signed release builds are produced by GitHub Actions; the signing key is stored as a repository secret and is never committed.

## License

See [LICENSE](LICENSE).
