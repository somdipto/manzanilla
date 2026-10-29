#!/usr/bin/env bash
# Disposable, explicitly selected API 26+ emulator only; key-free debug APKs.
# Default: uncommitted callback checks, plus API 31+ delivery-parameter checks.
# Optional genuine consent proof: supply QA_BASE_APK, QA_CANDIDATE_APK and QA_TEST_APK
# from two isolated builds of this same source, with increasing versions and one
# disposable QA signer. Never use release credentials or the production signer.
# Consent automation requires an English-language system Install/Update button.
set -euo pipefail
cd "$(dirname "$0")/.."
: "${ANDROID_SERIAL:?Select exactly one disposable emulator with ANDROID_SERIAL}"
evidence="${ANDROID_UPDATE_QA_OUTPUT:-work/android-update-qa}"
adb wait-for-device
test "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" = 1
api="$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
if [[ ! "$api" =~ ^[0-9]+$ ]] || (( api < 26 )); then
  echo 'Installer callback verification requires API 26 or newer' >&2
  exit 1
fi
mkdir -p "$evidence"
printf 'api=%s\nfixture_scope=callbacks_uncommitted\n' "$api" > "$evidence/platform.txt"
base_apk="${QA_BASE_APK:-android/hotel/build/outputs/apk/debug/hotel-debug.apk}"
test_apk="${QA_TEST_APK:-android/hotel/build/outputs/apk/androidTest/debug/hotel-debug-androidTest.apk}"
qa_candidate="${QA_CANDIDATE_APK:-}"
if [[ -n "${QA_BASE_APK:-}${QA_TEST_APK:-}$qa_candidate" ]]; then
  : "${QA_BASE_APK:?Genuine QA requires all three QA APK paths}"
  : "${QA_TEST_APK:?Genuine QA requires all three QA APK paths}"
  : "${QA_CANDIDATE_APK:?Genuine QA requires all three QA APK paths}"
  aapt="${AAPT:-${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}/build-tools/34.0.0/aapt}"
  apksigner="${APKSIGNER:-${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}/build-tools/34.0.0/apksigner}"
  test -x "$aapt" && test -x "$apksigner"
  apk_identity() {
    "$aapt" dump badging "$1" | python3 -c '
import re, sys
text = sys.stdin.read()
match = re.search(r"^package: name=\x27([^\x27]+)\x27 versionCode=\x27([0-9]+)\x27 versionName=\x27([^\x27]+)\x27", text, re.M)
assert match and match[1] == sys.argv[1], "Unexpected QA package"
assert "application-debuggable" in text, "QA APK must be debuggable and run-as accessible"
print(match[2], match[3])
' "$2"
  }
  signer() {
    "$apksigner" verify --print-certs "$1" | python3 -c '
import re, sys
values = re.findall(r"^Signer #[0-9]+ certificate SHA-256 digest: ([0-9a-fA-F]{64})$", sys.stdin.read(), re.M)
assert len(values) == 1, "QA requires exactly one verified signer"
print(values[0].lower())
'
  }
  base_identity="$(apk_identity "$base_apk" dev.orange.hotel)"
  candidate_identity="$(apk_identity "$qa_candidate" dev.orange.hotel)"
  apk_identity "$test_apk" dev.orange.hotel.test > "$evidence/test-identity.txt"
  read -r base_code base_name <<< "$base_identity"
  read -r candidate_code candidate_name <<< "$candidate_identity"
  (( candidate_code > base_code ))
  qa_signer="$(signer "$base_apk")"
  test "$(signer "$qa_candidate")" = "$qa_signer"
  test "$(signer "$test_apk")" = "$qa_signer"
  printf 'base=%s\ncandidate=%s\nsigner_sha256=%s\n' "$base_identity" "$candidate_identity" "$qa_signer" > "$evidence/qa-inputs.txt"
fi
# adb install is bootstrap ONLY. It is never evidence of an Updater installation.
adb install -r "$base_apk"
adb install -r -t "$test_apk"
# All destructive resets remain behind the explicit serial and emulator checks above.
adb shell pm clear dev.orange.hotel
# Do not dump raw logcat: framework callback diagnostics may contain the capability.
run_case() {
  local name="$1"
  timeout 180s adb shell am instrument -w -r -e case "$name" \
    dev.orange.hotel.test/com.agentdeck.hotel.VoiceLifecycleFixture | tee "$evidence/$name.txt"
  # adb may return zero after an instrumentation assertion fails.
  grep -q "PASS case=$name " "$evidence/$name.txt"
  grep -q '^INSTRUMENTATION_CODE: -1' "$evidence/$name.txt"
}
run_case installer-callbacks
if (( api >= 31 )); then
  run_case update-delivery
else
  printf 'update-delivery=not_run reason=requires_API31_parameter_contract\n' >> "$evidence/platform.txt"
fi
if [[ -z "$qa_candidate" ]]; then
  printf 'genuine_consent=blocked reason=isolated_QA_APK_pair_not_supplied\n' >> "$evidence/platform.txt"
  exit 0
fi
# No updater fixture cache/preferences may seed or mask the real consent check.
adb shell pm clear dev.orange.hotel
adb shell appops set dev.orange.hotel REQUEST_INSTALL_PACKAGES allow
remote=/data/local/tmp/manzanilla-updater-qa-candidate.apk
trap 'adb shell rm -f /data/local/tmp/manzanilla-updater-qa-candidate.apk >/dev/null 2>&1 || true' EXIT
adb push "$qa_candidate" "$remote"
adb shell chmod 644 "$remote"
adb shell run-as dev.orange.hotel mkdir -p files
adb shell run-as dev.orange.hotel cp "$remote" files/updater-qa-candidate.apk
# Replacement normally terminates instrumentation. Neither its exit code nor a
# process death is proof; require actual Updater dispatch, real consent UI, then
# exact installed bytes/package/version/signer and retained data in a fresh run.
set +e
timeout 150s adb shell am instrument -w -r -e case installer-consent \
  dev.orange.hotel.test/com.agentdeck.hotel.VoiceLifecycleFixture | tee "$evidence/installer-consent.txt"
consent_status="${PIPESTATUS[0]}"
set -e
printf 'consent_instrumentation_exit=%s\n' "$consent_status" >> "$evidence/platform.txt"
grep -q 'qa_dispatch=actual_updater' "$evidence/installer-consent.txt"
grep -q 'qa_consent=trusted_system_positive_button' "$evidence/installer-consent.txt"
adb wait-for-device
installed_path="$(adb shell pm path dev.orange.hotel | tr -d '\r')"
[[ "$installed_path" == package:/* && "$installed_path" != *$'\n'* ]]
adb pull "${installed_path#package:}" "$evidence/installed-qa.apk"
expected_hash="$(shasum -a 256 "$qa_candidate")"
installed_hash="$(shasum -a 256 "$evidence/installed-qa.apk")"
test "${expected_hash%% *}" = "${installed_hash%% *}"
test "$(apk_identity "$evidence/installed-qa.apk" dev.orange.hotel)" = "$candidate_identity"
test "$(signer "$evidence/installed-qa.apk")" = "$qa_signer"
run_case installer-consent-retained
printf 'genuine_consent=passed installed_apk_sha256=%s retained_data=true\n' "${installed_hash%% *}" >> "$evidence/platform.txt"
