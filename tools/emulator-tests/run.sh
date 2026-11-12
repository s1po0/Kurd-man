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
python3 -m unittest discover -s tools/emulator-tests -p 'test_*.py' -v
java -Djava.awt.headless=true tools/emulator-tests/MakePanel.java "$OUT/synthetic-source.png"
# Official SDK components only. No device-farm account, app login or new model.
SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
AVDMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager"
"$SDKMANAGER" --install emulator 'system-images;android-34;google_apis;x86_64' > "$RUNTIME/sdk.log" 2>&1 || {
  tail -n 35 "$RUNTIME/sdk.log"; exit 1;
}
printf 'no\n' | "$AVDMANAGER" create avd --force --name koorix-native-qa \
  --package 'system-images;android-34;google_apis;x86_64' --device pixel > "$RUNTIME/avd.log" 2>&1 || {
  cat "$RUNTIME/avd.log"; exit 1;
}
"$ANDROID_HOME/emulator/emulator" -avd koorix-native-qa -port 5554 -no-window \
  -no-snapshot -noaudio -no-boot-anim -gpu swiftshader_indirect -accel on \
  -cores 2 -memory 2048 -camera-back none -camera-front none > "$RUNTIME/emulator.log" 2>&1 &
pid=$!
trap 'adb -s emulator-5554 emu kill >/dev/null 2>&1 || true; kill "$pid" 2>/dev/null || true; wait "$pid" 2>/dev/null || true' EXIT
if ! python3 tools/emulator-tests/driver.py --apk "$APK_FILE" --out "$OUT"; then
  tail -n 40 "$RUNTIME/emulator.log"
  echo '::error::Actual regular APK emulator check failed; not substituted with a mock or alternate build.'
  exit 1
fi
echo '::notice::Real regular APK installed and exercised on Android API34 emulator with synthetic source; NOT physical-device or general meaning proof'
