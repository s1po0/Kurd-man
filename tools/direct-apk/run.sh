#!/usr/bin/env bash
# Empty hosted emulator only; opens the existing release, never builds an APK.
set -euo pipefail
[ "${GITHUB_ACTIONS:-}" = true ] && [ "${RUNNER_ENVIRONMENT:-}" = github-hosted ] || {
  echo 'Refusing to target any non-hosted device.'; exit 1;
}
: "${ANDROID_HOME:?Official Android SDK required}" "${APK_FILE:?Existing verified release required}"
[ -z "${KOORIX_RELEASE_STORE_PASSWORD:-}" ] && [ -z "${KOORIX_RELEASE_KEY_PASSWORD:-}" ] && [ -z "${KOORIX_RELEASE_KEYSTORE:-}" ] || {
  echo 'Signing secrets do not belong in direct app inspection.'; exit 1;
}
[ -c /dev/kvm ] || { echo 'No KVM: a direct Android session is unavailable.'; exit 1; }
sudo chmod a+rw /dev/kvm
OUT=android/app/build/outputs/direct-apk
RUNTIME=android/app/build/outputs/direct-apk-runtime
mkdir -p "$OUT" "$RUNTIME"
export ANDROID_USER_HOME="$RUNNER_TEMP/koorix-direct-android"
export ANDROID_AVD_HOME="$ANDROID_USER_HOME/avd"
mkdir -p "$ANDROID_AVD_HOME"
sudo apt-get update -qq > "$RUNTIME/host-dependencies.log" 2>&1
sudo apt-get install -y --no-install-recommends libpulse0 libnss3 libnspr4 libxcb1 \
  libx11-6 libxcomposite1 libxcursor1 libxi6 libxrandr2 libxrender1 libxtst6 \
  libxkbcommon0 >> "$RUNTIME/host-dependencies.log" 2>&1 || {
  tail -n 20 "$RUNTIME/host-dependencies.log"; exit 1;
}
python3 -m unittest discover -s tools/direct-apk -p 'test_*.py' -v
java -Djava.awt.headless=true tools/emulator-tests/MakePanel.java "$OUT/synthetic-source.png"
SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
AVDMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager"
"$SDKMANAGER" --install emulator 'system-images;android-34;google_apis;x86_64' > "$RUNTIME/sdk.log" 2>&1 || {
  tail -n 35 "$RUNTIME/sdk.log"; exit 1;
}
printf 'no\n' | "$AVDMANAGER" create avd --force --name koorix-direct \
  --package 'system-images;android-34;google_apis;x86_64' --device pixel \
  --path "$ANDROID_AVD_HOME/koorix-direct.avd" > "$RUNTIME/avd.log" 2>&1 || {
  cat "$RUNTIME/avd.log"; exit 1;
}
env -u GH_TOKEN "$ANDROID_HOME/emulator/emulator" -avd koorix-direct -port 5554 -no-window \
  -no-snapshot -noaudio -no-boot-anim -gpu swiftshader_indirect -accel on \
  -cores 2 -memory 2048 -camera-back none -camera-front none > "$RUNTIME/emulator.log" 2>&1 &
pid=$!
shutdown_emulator() {
  timeout --kill-after=2s 10s adb -s emulator-5554 emu kill >/dev/null 2>&1 || true
  kill -KILL "$pid" 2>/dev/null || true
  wait "$pid" 2>/dev/null || true
}
trap shutdown_emulator EXIT
export EMULATOR_PID="$pid"
python3 tools/direct-apk/worker.py
