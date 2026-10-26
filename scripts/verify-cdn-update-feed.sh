#!/usr/bin/env bash
# Disposable emulator only. FIXTURE proof, not end-to-end: a 17.0.30 build whose
# primary feed is a fixture manifest and whose legacy fallback is unreachable
# must reach the download phase using only the CDN manifest path. The fixture's
# APK target intentionally does not exist; reaching the download phase (not a
# completed offer) is the proof. Never starts voice or touches keys.
set -euo pipefail
cd "$(dirname "$0")/.."
test "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" = 1
package=dev.orange.hotel
evidence=work/cdn-update-feed
mkdir -p "$evidence"
if adb shell pm path "$package" | grep -q '^package:'; then
  echo 'Feed test requires a fresh disposable emulator' >&2
  exit 1
fi
adb install "$RUNNER_TEMP/fixture.apk" | tee "$evidence/install.txt"
adb shell dumpsys package "$package" | grep -E 'versionCode|versionName' | head -2 | tee "$evidence/installed-version.txt"
adb shell settings put secure immersive_mode_confirmations confirmed
adb logcat -c
adb shell am start -W -n "$package/com.agentdeck.hotel.HotelActivity" | tee "$evidence/launch.txt"
grep -q 'Status: ok' "$evidence/launch.txt"
stage="none"
for round in $(seq 1 12); do
  sleep 10
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || true
  adb pull /sdcard/ui.xml "$evidence/ui-$round.xml" >/dev/null 2>&1 || true
  if [ -f "$evidence/ui-$round.xml" ]; then
    if grep -qE '17\.0\.31|ownloading' "$evidence/ui-$round.xml"; then stage="download-or-offer"; break; fi
    if grep -q 'Could not check for updates' "$evidence/ui-$round.xml"; then stage="check-failed"; break; fi
  fi
done
adb exec-out screencap -p > "$evidence/final.png" || true
adb logcat -d -v threadtime > "$evidence/logcat.txt" || true
{
  echo "stage reached: $stage"
  if grep -q 'Primary update feed failed' "$evidence/logcat.txt"; then
    echo 'UNEXPECTED: primary CDN manifest fetch failed (fallback would be unreachable)'
  else
    echo 'Primary CDN manifest fetch did not fail'
  fi
} | tee "$evidence/result.txt"
test "$stage" = "download-or-offer"
