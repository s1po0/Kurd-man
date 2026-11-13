# Open the real regular APK, not a rendered UI mock

This is a **test-only** adapter for the owner's request to inspect the APK directly.
It follows the reviewed Java/code-review and rendered-build QA guidance in
`.claude/skills/koorix-native-qa/`. Those Skills do not contain an Android runtime.
The Arena sandbox currently has no Android SDK/KVM; official Android virtualization
runs on the existing disposable GitHub-hosted APK CI runner instead.

## What actually runs

After all existing native/unit/free/signature gates, the production workflow:

1. Deletes ephemeral restored signing plaintext and clears its environment values.
2. Installs official emulator/API34 Google APIs x86_64 SDK components.
3. Requires KVM and **advertised ARM64 native bridge support**. No ARM64 support
   means failed/unavailable, not permission to rebuild a different app/ABI.
4. Installs the exact same non-debuggable, production-v2-signed ARM64
   `com.koorix.app` APK that is eligible for delivery.
5. Uses ADB public UI actions (resource-ID XML, actual screen coordinates), a new
   empty emulator, no accounts and a fixed authored panel generated with Java AWT.
6. Switches UI language and Light/Dark; restarts the process to verify persistence;
   publishes the authored PNG into public MediaStore, opens the original unified
   source chooser and selects that exact file through real DocumentsUI. Installed
   OEM apps are not inferred; no shell-only intent is treated as a granted share.
7. Taps normal **Translate**, waits for real bundled ML Kit OCR / existing online
   translation / render/export,
   checks every source word/order, both distinct field labels, the fixture's
   description-supported custodian role and explicit inability in the normal
   visible result TextView, and opens the saved result in the native studio.
   It does **not** require the app to create an unwanted TXT file for QA.
   A local `synthetic-ui-pairs.json` is evidence read from public UI, not a phone
   sidecar or private-storage extraction. These fixture assertions are not a
   general semantic score; actual output still needs review.
8. Requires exactly one app-owned public JPEG and zero TXT rows in MediaStore
   Files, and that Gallery's Images view points at the **same row**, in
   `Download/Koorix/`. Checks the completed/idle UI, Studio size90→Undo100 status
   and controls, normal Studio export updating that same public row, and a
   process reopen without republishing. This is not Samsung Gallery testing.
9. Checks for an observed app crash, captures genuine emulator screenshots and
   exports only explicitly named synthetic products. Screenshots are reviewed by
   the agent; a green count alone is not a visual/semantic acceptance decision.

No debug/instrumentation app, injected OCR/translation mock, alternate native
libraries, private-storage `run-as`, test-only runtime hook, paid API, new model,
reader page upload, signing-identity change, phone uninstall or storage clearing.
The selected public translator can be unavailable/limited; do not hide a failure
with another route or mark it passed merely because deterministic tests pass.

## Commands

The actual device run is **only authorized on an empty GitHub-hosted runner**:

```sh
# Existing workflow provides ANDROID_HOME + already verified APK_FILE.
bash tools/emulator-tests/run.sh
python3 tools/emulator-tests/collect.py --repository "$GITHUB_REPOSITORY" \
  --commit "$GITHUB_SHA" --run "$GITHUB_RUN_ID"
```

### Android14 MediaStore setup

The official Android14 [`Content.InsertCommand`](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-14.0.0_r1/cmds/content/src/com/android/commands/content/Content.java#L554-L568)
ignores the return value of `provider.insert`: successful `content insert` is
silent. Do not require a printed URI or infer failure from an empty stdout.
The driver first requires no matching fixture row, inserts the authored image,
then queries its exact name/path/MIME and requires exactly one positive `_id`.
Only the public external image collection is used. It writes the PNG and verifies
its public readback SHA256 before opening the real chooser. Multiple/stale rows,
errors or altered bytes fail; offline mocks of these contracts emit no actual-APK
pass notices. The earlier ADB SEND-extra setup produced an actual no-read-access
error, not a valid app-to-app URI grant; the current run selects through the
ordinary GET_CONTENT result and its real provider grant instead.
The driver has a 15-minute total budget, and emulator shutdown is bounded so a
failed test can reach the always-run evidence collector. These are QA changes,
not an alternate application or proof that the hosted runtime passed.

Local helper checks do not require Android and do not establish device acceptance:

```sh
python3 -m unittest discover -s tools/emulator-tests -p 'test_*.py' -v
```

CI output lives in ignored `android/app/build/outputs/emulator-tests/`.
`collect.py` rejects unexpected files/symlinks/budgets and publicizes only its
named synthetic PNG/JPEG/text/report set as Git blobs, not APKs or arbitrary logs.
A failed run can publish partial diagnostics but must fail the acceptance gate.
The final release receipt identifies source, APK hash, actual run/report blob,
viewed captures and the explicit limitations.

## What it cannot prove

This is an **installed-APK emulator** test, distinct from native Robolectric/Skia.
It is not the owner's physical ARM64 phone, Samsung Gallery implementation,
TalkBack/IME acceptance, the exact old reader-page OCR, an offline pack benchmark,
or a general Sorani translation score. The body still uses the existing engine.
No result here grants access to connected phones, reader projects or private keys.
