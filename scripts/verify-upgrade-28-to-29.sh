#!/usr/bin/env bash
# Test branch only: prove the signed 17.0.28 -> 17.0.29 upgrade preserves the
# staff-entered voice credential state and app data, then capture the
# diagnostics screen on the upgraded app. Uses a DUMMY credential string; no
# real key, no audio, no provider session, no network validation (the save
# path is local encrypt+store only). KeyEntryActivity is FLAG_SECURE, so its
# evidence is UI-hierarchy XML text, never screenshots.
set -euo pipefail
CANDIDATE=android/hotel/build/outputs/apk/release/hotel-release.apk
test -f "$CANDIDATE"
mkdir -p work/upgrade-evidence
PKG=dev.orange.hotel
MAIN=dev.orange.hotel/com.agentdeck.hotel.HotelActivity
KEYS=dev.orange.hotel/com.agentdeck.hotel.KeyEntryActivity
DIAG=dev.orange.hotel/com.agentdeck.hotel.DiagnosticsActivity
PIN=1234
DUMMY=DUMMYUPGRADETESTKEY123
PROVIDER_LABEL="Voice credential (release override)"

curl -sSL --max-time 300 -o work/upgrade-evidence/previous-17.0.28.apk \
  https://github.com/somdipto/manzanilla/releases/download/v17.0.28/Manzanilla-Reception.apk
test -s work/upgrade-evidence/previous-17.0.28.apk

adb root
adb wait-for-device
# Staff setup requires a device screen-lock credential; enroll a temporary PIN
# on the userdebug emulator and remove it at the end.
adb shell locksettings set-pin "$PIN"
trap 'adb shell locksettings clear --old "$PIN" >/dev/null 2>&1 || true; adb shell pm uninstall "$PKG" >/dev/null 2>&1 || true' EXIT

uidump() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb shell cat /sdcard/ui.xml; }
tap_node() { # $1 = attribute match text; taps center of first matching node's bounds
  local xml="$1" bounds
  bounds=$(printf '%s' "$xml" | grep -o 'bounds="[^"]*"' | head -1 | sed 's/bounds="//; s/"$//')
  test -n "$bounds"
  python3 -c "
import sys,re
m=re.search(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', '''$bounds''')
print(f'{(int(m.group(1))+int(m.group(3)))//2} {(int(m.group(2))+int(m.group(4)))//2}')"
}
unlock_and_open_staff_setup() {
  adb shell am start -W -n "$KEYS" >/dev/null
  sleep 3
  # Device credential confirmation (system UI): type the PIN and confirm.
  adb shell input text "$PIN"
  adb shell input keyevent 66
  sleep 3
}

# 1. Install the published 17.0.28 and enter a dummy voice credential.
adb install work/upgrade-evidence/previous-17.0.28.apk
adb shell am start -W -n "$MAIN" >/dev/null
sleep 3
unlock_and_open_staff_setup
XML=$(uidump)
printf '%s' "$XML" > work/upgrade-evidence/staff-setup-28-before-save.xml
# Scroll the staff-setup column until a node matching $1 is visible; prints its bounds.
find_node() {
  local pattern="$1" attempt line
  for attempt in 1 2 3 4 5; do
    line=$(uidump | tr '<' '\n' | grep -i "$pattern" | head -1 || true)
    if [ -n "$line" ]; then printf '%s' "$line" | grep -o 'bounds="[^"]*"' | head -1 | sed 's/bounds="//; s/"$//'; return 0; fi
    adb shell input swipe 500 700 500 250
    sleep 1
  done
  return 1
}
tap_bounds() {
  python3 -c "
import re,sys
m=re.search(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', sys.argv[1])
print(f'{(int(m.group(1))+int(m.group(3)))//2} {(int(m.group(2))+int(m.group(4)))//2}')" "$1"
}
# Tap the masked voice-credential field, type the dummy, tap its Save button.
B=$(find_node "New $PROVIDER_LABEL key, masked") || { echo "masked key field not found" >&2; exit 1; }
adb shell input tap $(tap_bounds "$B")
sleep 1
adb shell input text "$DUMMY"
adb shell input keyevent 111  # hide keyboard (ESCAPE)
sleep 1
B=$(find_node "Save $PROVIDER_LABEL key") || { echo "save button not found" >&2; exit 1; }
adb shell input tap $(tap_bounds "$B")
sleep 2
XML=$(uidump)
printf '%s' "$XML" > work/upgrade-evidence/staff-setup-28-after-save.xml
printf '%s' "$XML" | grep -qi "a key is securely saved" || { echo "dummy key did not save on 17.0.28" >&2; exit 1; }
adb shell input keyevent 4  # back out of staff setup
sleep 1

# 2. Upgrade in place to the candidate and verify version + state.
adb install -r "$CANDIDATE"
adb shell dumpsys package "$PKG" | grep -E "versionName|versionCode" | head -2 | tee work/upgrade-evidence/installed-version.txt
grep -q "versionName=17.0.29" work/upgrade-evidence/installed-version.txt || { echo "candidate version not installed" >&2; exit 1; }
adb shell am start -W -n "$MAIN" >/dev/null
sleep 4
adb exec-out screencap -p > work/upgrade-evidence/main-after-upgrade.png

# 3. Credential state must survive the upgrade.
unlock_and_open_staff_setup
find_node "a key is securely saved" >/dev/null || { echo "credential state LOST in 28->29 upgrade" >&2; exit 1; }
uidump > work/upgrade-evidence/staff-setup-29-after-upgrade.xml
adb shell input keyevent 4
sleep 1

# 4. Diagnostics pixels on the upgraded app.
adb shell am start -W -n "$DIAG" >/dev/null
sleep 4
adb exec-out screencap -p > work/upgrade-evidence/diagnostics-after-upgrade.png
echo "UPGRADE CONTINUITY PASS: dummy credential state + app data survived 17.0.28 -> 17.0.29"
