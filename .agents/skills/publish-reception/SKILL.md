---
name: publish-reception
description: Push, build, and publish the Manzanilla reception APK as a GitHub Release, with the Fish API injected from CI secrets. Use when updating the Android app, publishing a release, or when an installed app is not updating.
---

# Publish reception

A git push is not an update. The installed app reads `https://api.github.com/repos/somdipto/manzanilla/releases/latest`. It offers an update only when that release is newer than the installed version code and contains `Manzanilla-Reception.apk` plus `release-metadata.json`.

## Steps

1. Read `android/VERSION`, `android/VERSION_CODE`, and `gh release list --repo somdipto/manzanilla --limit 1`. Set the next name and code strictly above that published release. Done when both files are higher than the latest release.

2. Put current screen images in `docs/screens` and show them in `README.md` before the push. Keep the download button: `https://github.com/somdipto/manzanilla/releases/latest/download/Manzanilla-Reception.apk`. Done when the README on the commit displays those images and the link.

3. The signed job in `.github/workflows/release.yml` must pass `secrets.FISH_API_KEY` and `secrets.FISH_VOICE_ID` into Gradle. `android/hotel/build.gradle.kts` compiles `FISH_API_KEY` into `BuildConfig`. `HotelActivity` uses the device store first and that field only when the store is empty. Never write either secret into the repo, logs, release notes, or chat. If `gh secret list --repo somdipto/manzanilla` does not show `FISH_API_KEY`, stop before tagging. Done when the workflow fails closed without the secret and the app can read the compiled field.

4. Commit the app change and the version bump. Push `HEAD` to `main`. Leave `.omo/` and every secret out of the commit. Done when `origin/main` is that commit.

5. Tag that exact commit `v<android/VERSION>` and push the tag. `Publish verified Reception APK` runs on `v*` tags. Done when the remote tag points at the version-bump commit.

6. Wait for that workflow. Done when `gh release view v<version> --repo somdipto/manzanilla` shows a release that is not a draft or prerelease, and its assets are `Manzanilla-Reception.apk` and `release-metadata.json`.

A public release APK makes the injected Fish key extractable. Say that when the release is live. `AudioPolicy.LIVE_AUDIO_ENABLED` still blocks live translation until that flag is changed; the key alone does not start audio. Do not force-push `main`. Do not call the app updated before step 6 is true.
