---
name: publish-reception
description: Prepare and verify PR-based Manzanilla reception releases and in-place updates.
---

# Reception release delivery

Every change goes through a GitHub PR. The user merges it; do not push changes
directly to main, merge a PR or publish a release unless explicitly instructed.

Before proposing app or release changes, check the latest public release and
increase both `android/VERSION` and `android/VERSION_CODE`. Keep package
`dev.orange.hotel`, the existing signing identity and user data. Never uninstall
or clear a real device as an update workaround. Keep credentials out of source,
logs, artifacts, release notes and chat. Public APKs must not embed a shared
provider key; use an enrolled hotel backend or a staff-configured device key.

Run the applicable isolated source, Android and runtime checks. A green PR
proves the checks that ran, not live provider access or delivery to devices.
On a qualifying merge, the publication workflow verifies and signs the candidate,
checks the previous release and retained-data upgrade, verifies draft assets,
then publishes. Confirm the actual workflow and release before saying a device
can update. Backend and voice-worker deployment are separate from APK delivery.

Android may require system installation approval, even when unattended update
is requested. Dedicated-device fleet enrollment and kiosk relaunch policies are
deployment work; do not claim all tablets will update silently or relaunch.

Read `docs/verification/README.md` and run test mutations only in isolated
fixtures. Live audio quality and latency need measured provider calls, a matched
baseline and real-device acceptance; never infer them solely from unit tests.
