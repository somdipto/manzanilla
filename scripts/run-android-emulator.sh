#!/usr/bin/env bash
# Run only on a disposable emulator with the key-free debug build.
set -euo pipefail
cd "$(dirname "$0")/.."
distribution="${ANDROID_QA_DISTRIBUTION:-personal-test}"
case "$distribution" in
  personal-test) fixtures=(direct-cleanup-credential reception-bridge reception-truth reception-request-tracking microphone-permission microphone-permission-denied microphone-permission-background translation-startup update-delivery hotel-readiness) ;;
  hotel) fixtures=(direct-cleanup-credential reception-bridge reception-truth reception-request-tracking hotel-readiness hotel-setup-gates) ;;
  *) echo "Unsupported fixture distribution" >&2; exit 1 ;;
esac
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
# HOME must reach the system launcher, not our HOME candidate or Android's chooser.
home="$(adb shell cmd package resolve-activity --brief --query-flags 0x100000 -a android.intent.action.MAIN -c android.intent.category.HOME)"
home="${home##*$'\n'}"
home="${home%$'\r'}"
case "$home" in
  ""|android/*|dev.orange.hotel/*|*" "*) echo "No unambiguous system HOME activity for the isolated fixture" >&2; exit 1 ;;
esac
[[ "$home" == */* ]]
adb shell cmd package set-home-activity "$home"
# Keep only bounded fixture output, never raw logs containing installer capabilities.
trap 'adb shell locksettings clear --old 2468 > /dev/null 2>&1 || true' EXIT
run_fixture() {
  local fixture="$1"
  local label="${2:-$1}"
  # Reset only this disposable, key-free test app between independent scenarios.
  adb shell pm clear dev.orange.hotel
  timeout 240s adb shell am instrument -w -r -e case "$fixture" \
    dev.orange.hotel.test/com.agentdeck.hotel.VoiceLifecycleFixture | tee "$evidence/$label.txt"
  # adb itself returns zero even when an instrumentation assertion fails.
  if ! grep -q "PASS case=$fixture " "$evidence/$label.txt" ||
     ! grep -q '^INSTRUMENTATION_CODE: -1' "$evidence/$label.txt"; then
    # An uncaught exception in this instrumentation process, which also hosts
    # the target app, reports only "Process crashed." Keep the bounded crash
    # buffer (exception class, message and top frames) so the cause is visible.
    adb logcat -d -b crash -t 60 > "$evidence/$label-crash.txt" 2>/dev/null || true
    return 1
  fi
}
for fixture in "${fixtures[@]}"; do
  run_fixture "$fixture"
done
if [[ "$distribution" == hotel ]]; then exit 0; fi
# Personal translation setup must work whether Android has a screen lock or not.
# Only the known synthetic PIN may be cleared on this disposable emulator.
adb shell locksettings clear --old 2468 > /dev/null 2>&1 || adb shell locksettings clear > /dev/null
run_fixture voice-setup voice-setup-no-lock
grep -q 'VOICE_SETUP deviceSecure=false' "$evidence/voice-setup-no-lock.txt"
adb shell locksettings set-pin 2468
run_fixture voice-setup voice-setup-device-pin
grep -q 'VOICE_SETUP deviceSecure=true' "$evidence/voice-setup-device-pin.txt"
