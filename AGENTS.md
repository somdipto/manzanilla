# Manzanilla

Every change must be delivered on a feature branch through a GitHub pull request.
Never push changes directly to `main`, merge a PR, or publish a release on the
user's behalf unless explicitly instructed. The user merges reviewed PRs; the
merge-triggered workflow builds, verifies and publishes eligible app updates.
Keep related work in an existing unmerged PR when it must ship together. App
changes must include increasing `android/VERSION` and `android/VERSION_CODE`.

Before pushing, building, or publishing the reception app, read [`.agents/skills/publish-reception/SKILL.md`](.agents/skills/publish-reception/SKILL.md). A git push is not an update. The installed app updates only from a GitHub Release.

Before claiming a server or conversation change works, follow [`docs/verification/README.md`](docs/verification/README.md). Use an isolated fixture. Do not verify mutations against the live app or data.

An app upgrade must preserve package `dev.orange.hotel`, its compatible signing
identity and saved data. Never uninstall, clear user data or advise uninstalling
as an update workaround. Android may still require system approval on devices
that are not eligible for unattended updates; never bypass platform verification.
