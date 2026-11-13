#!/usr/bin/env bash
# Only a disposable hosted CI emulator. Never target an owner's connected phone.
set -euo pipefail
[ "${GITHUB_ACTIONS:-}" = true ] && [ "${RUNNER_ENVIRONMENT:-}" = github-hosted ] || {
  echo 'Refusing to run against anything except an empty GitHub-hosted emulator.'; exit 1;
}
: "${ANDROID_HOME:?Android SDK required}" "${APK_FILE:?Verified regular APK required}"
[ -z "${KOORIX_RELEASE_STORE_PASSWORD:-}" ] && [ -z "${KOORIX_RELEASE_KEY_PASSWORD:-}" ] && [ -z "${KOORIX_RELEASE_KEYSTORE:-}" ] || {
  echo 'Signing material must be removed from the environment before emulator execution.'; exit 1;
}
[ -c /dev/kvm ] || { echo 'KVM unavailable; actual APK emulator acceptance is NOT established.'; exit 1; }
sudo chmod a+rw /dev/kvm
OUT=android/app/build/outputs/emulator-tests
RUNTIME=android/app/build/outputs/emulator-runtime
mkdir -p "$OUT" "$RUNTIME"
# Recent command-line tools may choose a different Android user-home default
# than the emulator. Give BOTH tools an explicit, disposable AVD location.
export ANDROID_USER_HOME="$RUNNER_TEMP/koorix-native-qa-android"
export ANDROID_AVD_HOME="$ANDROID_USER_HOME/avd"
mkdir -p "$ANDROID_AVD_HOME"
# The standard headless Ubuntu image omits libpulse even with -noaudio.
# These are host emulator libraries, never APK/runtime dependencies.
sudo apt-get update -qq > "$RUNTIME/host-dependencies.log" 2>&1
sudo apt-get install -y --no-install-recommends libpulse0 libnss3 libnspr4 libxcb1 \
  libx11-6 libxcomposite1 libxcursor1 libxi6 libxrandr2 libxrender1 libxtst6 \
  libxkbcommon0 >> "$RUNTIME/host-dependencies.log" 2>&1 || {
  tail -n 20 "$RUNTIME/host-dependencies.log"; exit 1;
}
python3 -m unittest discover -s tools/emulator-tests -p 'test_*.py' -v
java -Djava.awt.headless=true tools/emulator-tests/MakePanel.java "$OUT/synthetic-source.png"
# Official SDK components only. No device-farm account, app login or new model.
SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
AVDMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager"
"$SDKMANAGER" --install emulator 'system-images;android-34;google_apis;x86_64' > "$RUNTIME/sdk.log" 2>&1 || {
  tail -n 35 "$RUNTIME/sdk.log"; exit 1;
}
printf 'no\n' | "$AVDMANAGER" create avd --force --name koorix-native-qa \
  --package 'system-images;android-34;google_apis;x86_64' --device pixel \
  --path "$ANDROID_AVD_HOME/koorix-native-qa.avd" > "$RUNTIME/avd.log" 2>&1 || {
  cat "$RUNTIME/avd.log"; exit 1;
}
"$ANDROID_HOME/emulator/emulator" -avd koorix-native-qa -port 5554 -no-window \
  -no-snapshot -noaudio -no-boot-anim -gpu swiftshader_indirect -accel on \
  -cores 2 -memory 2048 -camera-back none -camera-front none > "$RUNTIME/emulator.log" 2>&1 &
pid=$!
shutdown_emulator() {
  # A dead/unresponsive emulator must not leave the shell waiting indefinitely
  # before the always-run collector can preserve the failed driver's report.
  timeout --kill-after=2s 10s adb -s emulator-5554 emu kill >/dev/null 2>&1 || true
  kill -KILL "$pid" 2>/dev/null || true
  wait "$pid" 2>/dev/null || true
}
trap shutdown_emulator EXIT
if ! python3 tools/emulator-tests/driver.py --apk "$APK_FILE" --out "$OUT" --emulator-pid "$pid"; then
  # Fixed empty-emulator process output only, after signing plaintext removal.
  # Actions log hosts may be inaccessible from Arena; retain a bounded public
  # diagnostic and notices instead of treating a boot failure as APK acceptance.
  {
    echo 'Empty official Android CI emulator diagnostics; no reader/signing data.'
    "$ANDROID_HOME/emulator/emulator" -version 2>&1 | head -n 5 || true
    "$ANDROID_HOME/emulator/emulator" -accel-check 2>&1 || true
    if [ "${KOORIX_OPEN_COMIC_QA:-0}" != 1 ]; then
      tail -n 75 "$RUNTIME/emulator.log"
    else
      echo 'Emulator logs withheld for public-comic privacy.'
    fi
  } > "$OUT/emulator-diagnostics.txt"
  python3 - "$OUT/emulator-diagnostics.txt" <<'PYDIAG'
import re,sys
from pathlib import Path
lines=Path(sys.argv[1]).read_text(errors='replace').splitlines()
selected=[s for s in lines if re.search('PANIC|ERROR|FATAL|denied|No space|cannot|accel|invalid',s,re.I)]
for line in selected[-8:]:
    print('::notice::EMULATOR_DIAGNOSTIC='+line[:600].replace('%','%25').replace('\r','%0D').replace('\n','%0A'))
PYDIAG
  echo '::error::Actual regular APK emulator check failed; not substituted with a mock or alternate build.'
  exit 1
fi
echo '::notice::Real regular APK installed and exercised on Android API34 emulator with synthetic source; NOT physical-device or general meaning proof'
