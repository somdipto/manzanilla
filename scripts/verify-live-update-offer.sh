#!/usr/bin/env bash
# Disposable emulator only. Observes the published update feed from an installed
# previous release. Capture tool, not the verdict: the offer is confirmed by
# inspecting the retained screenshots and UI dumps. Never starts voice or touches keys.
set -euo pipefail
expect_version="${EXPECT_VERSION:-17.0.30}"
cd "$(dirname "$0")/.."
test "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" = 1
package=dev.orange.hotel
evidence=work/update-offer
mkdir -p "$evidence"
if adb shell pm path "$package" | grep -q '^package:'; then
  echo 'Offer test requires a fresh disposable emulator' >&2
  exit 1
fi
adb install "$RUNNER_TEMP/prev.apk" | tee "$evidence/install.txt"
adb shell dumpsys package "$package" | grep -E 'versionCode|versionName' | head -2 | tee "$evidence/installed-version.txt"
adb shell settings put secure immersive_mode_confirmations confirmed
adb logcat -c
found=0
for round in $(seq 1 18); do
  adb shell am start -W -n "$package/com.agentdeck.hotel.HotelActivity" > "$evidence/launch-$round.txt" 2>&1 || true
  sleep 20
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || true
  adb pull /sdcard/ui.xml "$evidence/ui-$round.xml" >/dev/null 2>&1 || true
  if [ $((round % 3)) -eq 0 ]; then
    adb exec-out screencap -p > "$evidence/screen-$round.png" || true
  fi
  if [ -f "$evidence/ui-$round.xml" ] && grep -q "$expect_version" "$evidence/ui-$round.xml"; then
    found=1
    break
  fi
  if adb logcat -d | grep -q 'UPD.*Release metadata unavailable'; then
    echo 'Updater reported feed failure; keeping evidence' | tee "$evidence/feed-failure.txt"
    break
  fi
done
adb exec-out screencap -p > "$evidence/offer.png" || true
adb logcat -d -v threadtime > "$evidence/logcat.txt" || true
if [ "$found" = 1 ]; then
  echo "OBSERVED: UI hierarchy contains $expect_version (confirm via screenshots)" | tee "$evidence/result.txt"
else
  echo 'NOT-OBSERVED in UI hierarchy; screenshots may still show the offer - inspect pixels' | tee "$evidence/result.txt"
fi
