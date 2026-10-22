#!/usr/bin/env bash
# Run only on a disposable emulator with the key-free debug build.
set -euo pipefail
cd "$(dirname "$0")/.."
evidence="${ANDROID_QA_OUTPUT:-work/android-emulator-qa}"
mkdir -p "$evidence"
adb wait-for-device
# Refuse destructive fixture resets on a physical device.
test "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" = 1
adb shell settings put secure immersive_mode_confirmations confirmed
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
adb install -r android/hotel/build/outputs/apk/debug/hotel-debug.apk
adb install -r android/hotel/build/outputs/apk/androidTest/debug/hotel-debug-androidTest.apk
adb shell dumpsys webviewupdate > "$evidence/webview.txt"
adb shell cmd package set-home-activity com.android.launcher3/.Launcher
adb logcat -c
trap 'adb logcat -d > "$evidence/logcat.txt"' EXIT
for fixture in reception-bridge microphone-permission microphone-permission-denied microphone-permission-background; do
  # Reset only this disposable, key-free test app between independent scenarios.
  adb shell pm clear dev.orange.hotel
  timeout 180s adb shell am instrument -w -r -e case "$fixture" \
    dev.orange.hotel.test/com.agentdeck.hotel.VoiceLifecycleFixture | tee "$evidence/$fixture.txt"
  # adb itself returns zero even when an instrumentation assertion fails.
  grep -q "PASS case=$fixture " "$evidence/$fixture.txt"
  grep -q '^INSTRUMENTATION_CODE: -1' "$evidence/$fixture.txt"
done
