#!/usr/bin/env bash
# Disposable emulator only. UX-verification of the release 17.0.30 credential
# repair path: fresh install -> gated home -> Staff setup (device-lock) -> dummy
# key save -> Start translation enables -> diagnostics screen renders. The dummy
# key is invalid on purpose: no real credential, no session, no billable call.
# Save is local-only by design (no network validation), so the button-enable
# transition is provable key-free. Evidence is emulator-class, not user device.
set -euo pipefail
test "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" = 1
package=dev.orange.hotel
evidence="${GITHUB_WORKSPACE:-$PWD}/work/credential-ux"
mkdir -p "$evidence"

dump() { # dump <name> : best-effort UI hierarchy
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || true
  adb pull /sdcard/ui.xml "$evidence/$1" >/dev/null 2>&1 || true
}
shot() { adb exec-out screencap -p > "$evidence/$1" 2>/dev/null || true; }
tap_text() { # tap_text <dumpfile> <visible text> : tap center of node bounds
  python3 - "$1" "$2" <<'PYEOF'
import re, subprocess, sys
xml = open(sys.argv[1], encoding='utf-8', errors='replace').read()
needle = sys.argv[2].lower()
for m in re.finditer(r'text="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
    if needle in m.group(1).lower():
        l, t, r, b = map(int, m.groups()[1:])
        subprocess.run(['adb', 'shell', 'input', 'tap', str((l + r) // 2), str((t + b) // 2)], check=True)
        sys.exit(0)
sys.exit('text not found: ' + sys.argv[2])
PYEOF
}

if adb shell pm path "$package" | grep -q '^package:'; then
  echo 'Credential-UX test requires a fresh disposable emulator' >&2; exit 1
fi
adb install "$RUNNER_TEMP/release30.apk" | tee "$evidence/install.txt"
adb shell dumpsys package "$package" | grep -E 'versionCode|versionName' | head -2 | tee "$evidence/installed-version.txt"
adb shell settings put secure immersive_mode_confirmations confirmed
adb logcat -c

# 1. Home gate state
adb shell am start -W -n "$package/com.agentdeck.hotel.HotelActivity" | tee "$evidence/launch.txt"
grep -q 'Status: ok' "$evidence/launch.txt"
sleep 6
dump ui-home.xml; shot home.png
if grep -qi 'Audio unavailable' "$evidence/ui-home.xml"; then echo 'home gate: Audio unavailable shown' | tee -a "$evidence/result.txt"; else echo 'home gate: NOT VISIBLE' | tee -a "$evidence/result.txt"; fi

# 2. Enroll a device PIN (required by Staff setup)
{ adb shell locksettings set-pin 1234 || adb shell cmd lock_settings set-pin 1234; } > "$evidence/lock-enroll.txt" 2>&1 || true
echo "lock enroll: $(tr -d '\r\n' < "$evidence/lock-enroll.txt" | cut -c1-120)" | tee -a "$evidence/result.txt"

# 3. Enter Staff setup through the reception UI
tap_text "$evidence/ui-home.xml" 'Staff setup' >> "$evidence/result.txt" 2>&1 || echo 'staff setup entry: TAP FAILED' | tee -a "$evidence/result.txt"
sleep 4
dump ui-after-staff-tap.xml; shot staff-screen.png  # FLAG_SECURE: expected black
if grep -qi 'no device screen-lock credential is enrolled' "$evidence/ui-after-staff-tap.xml"; then
  echo 'staff setup: BLOCKED - no screen lock enrolled (PIN enroll failed)' | tee -a "$evidence/result.txt"
  cp "$evidence/ui-after-staff-tap.xml" "$evidence/ui-staff-form.xml" 2>/dev/null || true
else
  # The app auto-requests the system device-credential prompt on resume. Enter
  # the enrolled PIN blindly; the form dump below decides what happened.
  adb shell input text 1234 || true
  adb shell input keyevent 66 || true
  sleep 4
  dump ui-staff-form.xml; shot staff-form.png  # FLAG_SECURE: expected black
  if grep -qi 'Voice credential' "$evidence/ui-staff-form.xml"; then
    echo 'staff setup: device-credential prompt accepted, form reached' | tee -a "$evidence/result.txt"
  else
    echo 'staff setup: form NOT reached after credential attempt' | tee -a "$evidence/result.txt"
  fi
fi

# 4. Dummy key save (form reachable?)
if [ -f "$evidence/ui-staff-form.xml" ] && grep -qi 'Voice credential' "$evidence/ui-staff-form.xml"; then
  echo 'staff form: reached, Voice credential section present' | tee -a "$evidence/result.txt"
  python3 - "$evidence/ui-staff-form.xml" <<'PYEOF' >> "$evidence/result.txt" 2>&1
import re, subprocess, sys
xml = open(sys.argv[1], encoding='utf-8', errors='replace').read()
m = re.search(r'content-desc="New Voice credential \(release override\) key, masked"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
if not m:
    sys.exit('masked key field not found')
l, t, r, b = map(int, m.groups())
subprocess.run(['adb', 'shell', 'input', 'tap', str((l + r) // 2), str((t + b) // 2)], check=True)
PYEOF
  sleep 1
  adb shell input text "sk-fish-DUMMY-INVALID-0000-notreal"
  sleep 1
  tap_text "$evidence/ui-staff-form.xml" 'Save Voice credential' >> "$evidence/result.txt" 2>&1 || echo 'save button: TAP FAILED' | tee -a "$evidence/result.txt"
  sleep 3
  dump ui-staff-saved.xml; shot staff-saved.png  # FLAG_SECURE: expected black
  if grep -qi 'a key is securely saved' "$evidence/ui-staff-saved.xml"; then
    echo 'dummy save: CONFIRMED - status shows key saved (no network validation, by design)' | tee -a "$evidence/result.txt"
  else
    echo 'dummy save: NOT CONFIRMED from status line' | tee -a "$evidence/result.txt"
  fi
  tap_text "$evidence/ui-staff-saved.xml" 'Done' >/dev/null 2>&1 || adb shell input keyevent 4
else
  echo 'staff form: NOT REACHED' | tee -a "$evidence/result.txt"
fi

# 5. Reception after save: Start translation should enable (any stored key)
sleep 3
dump ui-home-after.xml; shot home-after.png
tap_text "$evidence/ui-home-after.xml" 'Live translation' >> "$evidence/result.txt" 2>&1 || echo 'translation page: TAP FAILED' | tee -a "$evidence/result.txt"
sleep 3
dump ui-translation.xml; shot translation.png
if grep -q 'Ready. Tap Start translation to turn on the microphone.' "$evidence/ui-translation.xml"; then
  echo 'gate transition: CONFIRMED - readiness shows Ready, Start translation enabled with stored key' | tee -a "$evidence/result.txt"
else
  echo 'gate transition: NOT REACHED' | tee -a "$evidence/result.txt"
fi
python3 - "$evidence/ui-translation.xml" <<'PYEOF' >> "$evidence/result.txt" 2>&1
import re, sys
xml = open(sys.argv[1], encoding='utf-8', errors='replace').read()
for m in re.finditer(r'<node[^>]*text="Start translation"[^>]*>', xml):
    en = re.search(r'enabled="(\w+)"', m.group(0))
    print('Start translation node enabled attr:', en.group(1) if en else 'absent')
PYEOF

# 6. Diagnostics screen (re-enter Staff setup, open diagnostics)
adb shell input keyevent 4 >/dev/null 2>&1 || true  # back to home
sleep 2
dump ui-home-again.xml
if tap_text "$evidence/ui-home-again.xml" 'Staff setup' >/dev/null 2>&1; then
  sleep 3
  dump ui-reauth.xml
  if grep -qiE 'confirm.*pin|enter.*pin|Choose your PIN' "$evidence/ui-reauth.xml"; then
    adb shell input text 1234; adb shell input keyevent 66; sleep 3
  fi
  dump ui-staff-again.xml
  if tap_text "$evidence/ui-staff-again.xml" 'Voice diagnostics' >/dev/null 2>&1; then
    sleep 3
    dump ui-diagnostics.xml; shot diagnostics.png
    if grep -qi 'No diagnostic events yet' "$evidence/ui-diagnostics.xml"; then
      echo 'diagnostics screen: renders - shows No diagnostic events yet (pre-session)' | tee -a "$evidence/result.txt"
    else
      echo 'diagnostics screen: opened, unexpected content' | tee -a "$evidence/result.txt"
    fi
  else
    echo 'diagnostics: ENTRY TAP FAILED' | tee -a "$evidence/result.txt"
  fi
else
  echo 'diagnostics: staff re-entry failed' | tee -a "$evidence/result.txt"
fi

adb logcat -d -v threadtime > "$evidence/logcat.txt" 2>/dev/null || true
echo '--- summary ---' | tee -a "$evidence/result.txt"
grep -c . "$evidence/result.txt" >/dev/null
cat "$evidence/result.txt"
