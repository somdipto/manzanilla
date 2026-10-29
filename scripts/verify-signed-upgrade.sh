#!/usr/bin/env bash
# Fresh explicitly selected disposable emulator only. No voice session is started.
# RELEASE_DISTRIBUTION, GITHUB_SHA, ANDROID_SERIAL and RUNNER_TEMP are required.
set -euo pipefail
cd "$(dirname "$0")/.."
exec python3 scripts/signed_upgrade_proof.py
