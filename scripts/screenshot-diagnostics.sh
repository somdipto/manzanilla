#!/usr/bin/env bash
# Test branch only: install the signed test APK and capture the diagnostics
# screen's empty state. No session is started; no audio, account or credential.
set -euo pipefail
APK=android/hotel/build/outputs/apk/release/hotel-release.apk
test -f "$APK"
mkdir -p work/diagnostics-screen
adb install -r "$APK"
# Package registration can lag a streamed install by a beat; retry the launch.
started=""
for attempt in 1 2 3 4 5; do
  sleep 2
  if adb shell am start -W -n dev.orange.hotel/.DiagnosticsActivity 2>&1 | tee work/diagnostics-screen/am-start.log | grep -q "Status: ok"; then
    started=1
    break
  fi
done
if [ -z "$started" ]; then
  adb logcat -d -t 300 > work/diagnostics-screen/logcat-tail.txt || true
  echo "diagnostics activity did not start" >&2
  exit 1
fi
sleep 4
adb exec-out screencap -p > work/diagnostics-screen/diagnostics-empty-state.png
adb shell pm uninstall dev.orange.hotel
