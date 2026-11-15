#!/usr/bin/env bash
set -euo pipefail
if [[ "${GITHUB_ACTIONS:-}" != true || "${RUNNER_ENVIRONMENT:-}" != github-hosted ]]; then
  echo 'Disposable GitHub-hosted emulator required' >&2; exit 1
fi
root="${RUNNER_TEMP:?}/koorix-android-tls"
mkdir -p "$root/classes" "$root/dex"
javac --release 8 -d "$root/classes" \
  android/app/src/main/java/com/kurdman/app/PublicNetworkGuard.java \
  android/app/src/main/java/com/kurdman/app/PinnedHttpsClient.java \
  tools/web-reader/AndroidPinnedTlsProbe.java
jar cf "$root/probe.jar" -C "$root/classes" .
d8=$(find "$ANDROID_HOME/build-tools" -name d8 -type f | sort -V | tail -1)
echo 'Compiling disposable Android probe'
JAVA_TOOL_OPTIONS='-Xmx256m -XX:ActiveProcessorCount=2' "$d8" --min-api 26 --output "$root/dex" "$root/probe.jar"
(cd "$root/dex" && zip -q "$root/probe-dex.jar" classes.dex)
adb push "$root/probe-dex.jar" /data/local/tmp/koorix-tls-probe.jar >/dev/null
echo 'Running Android probe'
trap 'adb shell rm -f /data/local/tmp/koorix-tls-probe.jar >/dev/null || true' EXIT
adb shell CLASSPATH=/data/local/tmp/koorix-tls-probe.jar app_process /system/bin com.kurdman.app.AndroidPinnedTlsProbe
