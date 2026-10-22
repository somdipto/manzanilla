#!/usr/bin/env bash
# Disposable emulator only. Never starts voice or accesses stored credentials.
set -euo pipefail
cd "$(dirname "$0")/.."
test "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" = 1
package=dev.orange.hotel
evidence=work/signed-upgrade-qa
mkdir -p "$evidence"
trap 'adb logcat -d -v threadtime > "$evidence/logcat.txt"; adb exec-out screencap -p > "$evidence/screen.png"' EXIT
if adb shell pm path "$package" | grep -q '^package:'; then
  echo 'Upgrade test requires a fresh disposable emulator' >&2
  exit 1
fi
previous=("${RUNNER_TEMP:?}/previous/"*.apk)
test "${#previous[@]}" -eq 1
test -f "${previous[0]}"
adb install "${previous[0]}" | tee "$evidence/install-previous.txt"
adb shell dumpsys package "$package" > "$evidence/package-before.txt"
# No uninstall or data clear between the two installs.
adb install -r "$RUNNER_TEMP/candidate/Manzanilla-Reception.apk" | tee "$evidence/install-upgrade.txt"
adb shell dumpsys package "$package" > "$evidence/package-after.txt"
python - "$RUNNER_TEMP/candidate/release-metadata.json" "$evidence" <<'PY'
import json
from pathlib import Path
import re
import sys

metadata = json.loads(Path(sys.argv[1]).read_text())
evidence = Path(sys.argv[2])
before = (evidence / "package-before.txt").read_text()
after = (evidence / "package-after.txt").read_text()
assert re.search(r"versionCode=(\d+)", after).group(1) == str(metadata["version_code"])
assert re.search(r"versionName=(\S+)", after).group(1) == metadata["version_name"]
assert re.search(r"firstInstallTime=(.+)", before).group(1) == re.search(r"firstInstallTime=(.+)", after).group(1)
print(f"PASS: in-place upgrade to {metadata['version_name']} / {metadata['version_code']}")
PY
adb shell settings put secure immersive_mode_confirmations confirmed
adb logcat -c
adb shell am start -W -n "$package/com.agentdeck.hotel.HotelActivity" | tee "$evidence/launch.txt"
grep -q 'Status: ok' "$evidence/launch.txt"
# Observe startup without granting microphone permission or initiating a session.
for attempt in $(seq 1 15); do
  adb shell pidof "$package" > /dev/null
  sleep 1
done
adb logcat -d -v threadtime > "$evidence/logcat.txt"
if grep -E 'FATAL EXCEPTION|Uncaught (SyntaxError|ReferenceError|TypeError)' "$evidence/logcat.txt"; then
  echo 'Release startup failed; see signed-upgrade-evidence' >&2
  exit 1
fi
printf 'PASS: signed upgrade launches and survives startup\n' | tee "$evidence/result.txt"
