#!/usr/bin/env bash
# Test branch only: install the signed test APK and capture the diagnostics
# screen's empty state. No session is started; no audio, account or credential.
set -euo pipefail
APK=android/hotel/build/outputs/apk/release/hotel-release.apk
test -f "$APK"
adb install -r "$APK"
adb shell am start -W -n dev.orange.hotel/.DiagnosticsActivity
sleep 4
mkdir -p work/diagnostics-screen
adb exec-out screencap -p > work/diagnostics-screen/diagnostics-empty-state.png
adb shell pm uninstall dev.orange.hotel
