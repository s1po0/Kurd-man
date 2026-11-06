#!/usr/bin/env bash
set -euo pipefail
mapfile -t APKS < <(find android/app/build/outputs/apk/koorix/release -type f -name '*.apk')
[ "${#APKS[@]}" -eq 1 ] || { echo "::error::Expected exactly one Koorix release APK"; exit 1; }
APK="${APKS[0]}"
APKSIGNER="$ANDROID_HOME/build-tools/35.0.0/apksigner"
AAPT="$ANDROID_HOME/build-tools/35.0.0/aapt"
if ! "$APKSIGNER" verify --verbose --print-certs "$APK" > /tmp/koorix-apk-certs.txt 2>&1; then
  cat /tmp/koorix-apk-certs.txt
  echo 'APK signature verification failed; nothing is distributed'
  exit 1
fi
"$AAPT" dump badging "$APK" > /tmp/koorix-apk-badging.txt
python3 - "$APK" <<'PY'
import json, os, re, sys
manifest = json.load(open('signing/koorix-release-v2.json'))
certs = open('/tmp/koorix-apk-certs.txt').read()
digests = re.findall(r'^Signer #\d+ certificate SHA-256 digest: ([0-9a-f]+)$', certs, re.M)
print('Actual certificate SHA-256 digests: ' + json.dumps(digests))
assert digests == [os.environ['EXPECTED_SIGNER']] == [manifest['signer_sha256']], 'APK signer mismatch'
data = open('/tmp/koorix-apk-badging.txt').read()
package = re.search(r"package: name='([^']+)'", data).group(1)
version = re.search(r"versionName='([^']+)'", data).group(1)
code = int(re.search(r"versionCode='(\d+)'", data).group(1))
label = re.search(r"^application-label:'([^']*)'$", data, re.M).group(1)
expected = re.search(r'\bkoorix\s*\{.*?versionCode\s+(\d+).*?versionName\s+"([^"]+)"',
                     open('android/app/build.gradle').read(), re.S)
debuggable = 'application-debuggable' in data
print(f'Actual package={package} version={version} code={code} label={label} debuggable={debuggable}')
assert package == manifest['application_id'] == 'com.koorix.app', 'Not regular Koorix'
assert version == expected.group(2) and code == int(expected.group(1)), 'APK version mismatch'
assert 'preview' not in version.lower() and label == 'Koorix', 'Preview is not a production delivery'
assert 'application-debuggable' not in data, 'Production APK must not be debuggable'
with open(os.environ['GITHUB_ENV'], 'a') as env:
    env.write(f'KOORIX_VERSION={version}\nKOORIX_CODE={code}\nAPK_FILE={sys.argv[1]}\n')
print(f'::notice::APK_PACKAGE={package} version={version} code={code} label={label} debuggable=false')
print('::notice::APK_SIGNER_SHA256=' + digests[0])
print('::notice::APK_SIGNER_SHA1=' + manifest['signer_sha1'])
PY
