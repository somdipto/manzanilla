---
name: publish-reception
description: Prepare PR-based signed Reception APK updates and verify merge-triggered delivery without uninstalling installed apps.
---

# Reception release delivery

The user requires every change to go through a GitHub PR. Never push application
changes directly to `main` or merge a PR without an explicit instruction. Prepare
and verify a feature branch, open or update its PR, and leave the merge to the user.
Related changes may share an unmerged PR when they must ship together.

## Preparing the PR

1. Read `android/VERSION`, `android/VERSION_CODE` and the latest published release.
   App/release-impacting changes need a stable semantic version and explicit
   integer code strictly above the PR base and current published APK. Include
   both version files in the PR. Do not derive installation identity from a CI
   run counter or mutate `main` with a version-bump bot.
2. Keep the package `dev.orange.hotel` and the current signing certificate. Never
   rotate the signing key implicitly, uninstall, clear data, or instruct users
   to uninstall to repair an update. An incompatible candidate must be rejected.
3. Run the source checks and Android checks appropriate to the change, including
   the committed updater/runtime fixtures when update behavior changes. Keep
   screenshots and verification notes current and preserve the README download
   link: `https://github.com/somdipto/manzanilla/releases/latest/download/Manzanilla-Reception.apk`.
4. Push only the feature branch and create/update its PR. Check the exact PR
   commit and CI results. Never expose signing credentials or provider keys in
   code, logs, PR text, release notes or chat. Leave secrets and local artifacts
   out of commits.

## What merging does

The main-branch publication workflow verifies source, version eligibility,
signing continuity, package identity, digest and an in-place signed upgrade.
It signs with the existing CI key, creates an immutable tag for the exact merge
commit, uploads APK and metadata into a draft, verifies the uploaded asset digests, then
publishes that complete release as latest. A manual tag is not required.

The signed job must receive the existing signing secrets and `FISH_API_KEY` /
`FISH_VOICE_ID` according to the existing pilot deployment. It must fail closed
when required credentials are missing. Do not generate a replacement signing key.
The pilot APK's injected provider key is extractable; managed production devices
should use an enrolled hotel backend with server-side provider credentials.

Do not say an installed app has updated merely because code merged or an Actions
artifact exists. Confirm the final public release has `Manzanilla-Reception.apk`
and `release-metadata.json`, the intended tag/commit and verified identity.

## Device delivery

The direct distribution app reads the HTTPS CDN metadata feed and pins a newer
APK to its immutable version URL and digest. Verification uses the installed
signer before passing bytes to Android. It downloads automatically and applies
only while idle, without an active conversation or uncertain audio/session
cleanup. It requests unattended self-update where Android allows it; mandatory
system confirmation remains a supported fallback. This is in-place replacement,
not uninstall/reinstall. Never disable Play Protect or platform verification.

For thousands of managed hotel devices, prefer managed Google Play/EMM with
maintenance/update policies. A public Play build needs a separate delivery
configuration, current Play target-SDK qualification, and no ordinary sideload
self-updater. Preserve the existing app-signing identity when enrolling in Play.
See `docs/update-delivery.md` for channel limits and rollout prerequisites.
