#!/usr/bin/env bash
# Disposable emulator only. UX-verification of the release 17.0.30 credential
# repair path: fresh install -> gated home -> Staff setup (device-lock) -> dummy
# key save -> Start translation enables -> diagnostics screen renders. The dummy
# key is invalid on purpose: no real credential, no session, no billable call.
# Save is local-only by design (no network validation), so the button-enable
# transition is provable key-free. Evidence is emulator-class, not user device.
# Never free-type digits into the reception WebView: keypad digits drive app actions.
set -euo pipefail
test "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" = 1
package=dev.orange.hotel
evidence="${GITHUB_WORKSPACE:-$PWD}/work/credential-ux"
mkdir -p "$evidence"
say() { echo "$1" | tee -a "$evidence/result.txt"; }

dump() { # dump <name>
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || true
  adb pull /sdcard/ui.xml "$evidence/$1" >/dev/null 2>&1 || true
}
shot() { adb exec-out screencap -p > "$evidence/$1" 2>/dev/null || true; }
wait_for_text() { # wait_for_text <dumpname> <text> <seconds> -> 0 when found
  local name="$1" text="$2" deadline=$(( SECONDS + ${3:-30} ))
  while [ $SECONDS -lt $deadline ]; do
    dump "$name"
    if [ -f "$evidence/$name" ] && grep -qiF "$text" "$evidence/$name"; then return 0; fi
    sleep 3
  done
  return 1
}
tap_text() { # tap_text <dumpfile> <visible text>
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

# 1. Home gate state (wait for render)
adb shell am start -W -n "$package/com.agentdeck.hotel.HotelActivity" | tee "$evidence/launch.txt"
grep -q 'Status: ok' "$evidence/launch.txt"
if wait_for_text ui-home.xml 'Staff setup' 60; then
  shot home.png
  grep -qiF 'Audio unavailable' "$evidence/ui-home.xml" && say 'home gate: Audio unavailable shown' || say 'home gate: rendered but Audio unavailable NOT shown'
  grep -qiF 'Up to date · 17.0.30' "$evidence/ui-home.xml" && say 'updater: live CDN feed reports Up to date on installed 30' || say 'updater: up-to-date text not seen (may appear later)'
else
  say 'home: never rendered with Staff setup within 60s'
fi

# 2. Enroll device PIN (required by Staff setup)
{ adb shell locksettings set-pin 1234 || adb shell cmd lock_settings set-pin 1234; } > "$evidence/lock-enroll.txt" 2>&1 || true
say "lock enroll: $(tr -d '\r\n' < "$evidence/lock-enroll.txt" | cut -c1-120)"

# 3. Staff setup entry -> system device-credential prompt -> PIN
if ! tap_text "$evidence/ui-home.xml" 'Staff setup' >> "$evidence/result.txt" 2>&1; then
  say 'staff setup entry: TAP FAILED'
else
  sleep 4
  dump ui-confirm.xml
  if grep -qiF 'no device screen-lock credential is enrolled' "$evidence/ui-confirm.xml"; then
    say 'staff setup: BLOCKED - no screen lock enrolled'
  elif grep -qiF 'Confirm the device screen lock' "$evidence/ui-confirm.xml"; then
    say 'staff setup: system device-credential prompt shown'
    adb shell input text 1234
    adb shell input keyevent 66
    if wait_for_text ui-staff-form.xml 'Voice credential' 30; then
      say 'staff setup: form reached after device credential'
      shot staff-form.png  # FLAG_SECURE: expected black
    else
      say 'staff setup: form NOT reached after PIN'
    fi
  else
    say 'staff setup: unexpected state after entry tap (no prompt detected)'
  fi
fi

# 4. Dummy key save
if [ -f "$evidence/ui-staff-form.xml" ] && grep -qiF 'Voice credential' "$evidence/ui-staff-form.xml"; then
  # The form is a ScrollView; the Voice credential section sits below the fold.
  adb shell input swipe 900 900 900 300 300 || true
  sleep 2
  dump ui-staff-scrolled.xml
  python3 - "$evidence/ui-staff-scrolled.xml" <<'PYEOF' >> "$evidence/result.txt" 2>&1
import re, subprocess, sys
xml = open(sys.argv[1], encoding='utf-8', errors='replace').read()
for tag in re.findall(r'<node[^>]*>', xml):
    cd = re.search(r'content-desc="([^"]*)"', tag)
    bd = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', tag)
    if cd and bd and cd.group(1) == 'New Voice credential (release override) key, masked':
        l, t, r, b = map(int, bd.groups())
        subprocess.run(['adb', 'shell', 'input', 'tap', str((l + r) // 2), str((t + b) // 2)], check=True)
        sys.exit(0)
sys.exit('masked key field not found')
PYEOF
  sleep 1
  adb shell input text "sk-fish-DUMMY-INVALID-0000-notreal"
  sleep 1
  dump ui-staff-prefill.xml
  tap_text "$evidence/ui-staff-prefill.xml" 'Save Voice credential' >> "$evidence/result.txt" 2>&1 || say 'save button: TAP FAILED'
  if wait_for_text ui-staff-saved.xml 'a key is securely saved' 20; then
    say 'dummy save: CONFIRMED by status line (local-only save, no network validation, by design)'
    shot staff-saved.png  # FLAG_SECURE: expected black
  else
    say 'dummy save: NOT CONFIRMED from status line'
  fi
  tap_text "$evidence/ui-staff-saved.xml" 'Done' >/dev/null 2>&1 || adb shell input keyevent 4
fi

# 5. Reception after save: Start translation should enable (any stored key)
if wait_for_text ui-home-after.xml 'Staff setup' 30; then
  tap_text "$evidence/ui-home-after.xml" 'Live translation' >> "$evidence/result.txt" 2>&1 || say 'translation page: TAP FAILED'
  if wait_for_text ui-translation.xml 'Start translation' 30; then
    shot translation.png
    if grep -qF 'Ready. Tap Start translation to turn on the microphone.' "$evidence/ui-translation.xml"; then
      say 'gate transition: CONFIRMED - readiness Ready, Start translation enabled with a stored key'
    else
      say 'gate transition: Start translation visible but readiness not Ready (see dump)'
    fi
    python3 - "$evidence/ui-translation.xml" <<'PYEOF' >> "$evidence/result.txt" 2>&1
import re, sys
xml = open(sys.argv[1], encoding='utf-8', errors='replace').read()
for m in re.finditer(r'<node[^>]*text="Start translation"[^>]*>', xml):
    en = re.search(r'enabled="(\w+)"', m.group(0))
    print('Start translation node enabled attr:', en.group(1) if en else 'absent')
PYEOF
  else
    say 'translation page: never rendered'
  fi
fi

# 6. Diagnostics screen
adb shell input keyevent 4 >/dev/null 2>&1 || true
if wait_for_text ui-home-again.xml 'Staff setup' 30 && tap_text "$evidence/ui-home-again.xml" 'Staff setup' >/dev/null 2>&1; then
  sleep 4
  dump ui-reauth.xml
  if grep -qiF 'Confirm the device screen lock' "$evidence/ui-reauth.xml"; then
    adb shell input text 1234; adb shell input keyevent 66; sleep 4
  fi
  if wait_for_text ui-staff-again.xml 'Voice diagnostics' 30 && tap_text "$evidence/ui-staff-again.xml" 'Voice diagnostics' >/dev/null 2>&1; then
    if wait_for_text ui-diagnostics.xml 'diagnostic events' 20; then
      shot diagnostics.png
      grep -qiF 'No diagnostic events yet' "$evidence/ui-diagnostics.xml" \
        && say 'diagnostics: renders pre-session empty state' \
        || say 'diagnostics: opened with content (see dump)'
    else
      say 'diagnostics: did not render'
    fi
  else
    say 'diagnostics: entry failed'
  fi
else
  say 'diagnostics: staff re-entry failed'
fi

adb logcat -d -v threadtime > "$evidence/logcat.txt" 2>/dev/null || true
echo '--- summary ---' >> "$evidence/result.txt"
cat "$evidence/result.txt"
