# Open the actual regular APK and inspect it

This is an **observe → explicitly choose one gesture → observe** session,
not another automated green report. It uses real Android UI/screencap via ADB,
following the reviewed `adb-android-control` Skill discovered through the
owner's `claude-skill` topic. See
`../../.claude/skills/koorix-native-qa/references/adb-android-control/PROVENANCE.md`.

## Execution boundary

- Empty GitHub-hosted Ubuntu24.04/KVM + official Android34 Google APIs x86_64
  emulator with advertised ARM64 native bridge. Never a connected owner's phone.
- Install the **already delivered unchanged** regular `com.koorix.app` 1.8.2,
  code18, SHA256
  `d03ee91ff681e21e2e4cf4938106415f9e5e43d7837a03af976c62e3be97a300`.
  Independently verify its pinned v2 signature/content/manifest before use.
- No APK build, debug variant, re-signing, paid API, new model, mock translator,
  reader input/upload, app-private reads, `run-as`, uninstall, data clearing,
  Telegram message, release operation, public ADB port, or arbitrary shell API.
- The sole imported file is the repository's authored synthetic PNG, published
  to public MediaStore with exact-byte readback. Actual selection still happens
  through the app's ordinary chooser and real URI read grant.
- Remote mailbox permission is an ephemeral workflow token for bounded
  synthetic frame blobs/check output. No token enters control JSON/frames, a
  browser or Android subprocess. It does not grant an upstream Skill secrets.

## Start and observe

The dedicated workflow can be manually dispatched **on the current Arena
branch**. Its initial push trigger also requires `[inspect-koorix-apk]` in the
commit message; updating `control.json` does **not** start a second emulator.
The current bounded session ID is recorded in `worker.py` and `control.json`;
initial command must be sequence0 / snapshot. A future new session requires
resetting the control file and explicitly choosing a new ID, not reusing a
stale gesture sequence.

Run `client.py watch --commit <launch-commit>` as a managed local background
process. It reads the exact launch commit's session check and saves verified
`frame-NN.png`, `frame-NN.xml`, metadata and `latest.json` in ignored
`.cache/direct-apk-session/`. Use `get_process_output` to wait for a new
`OBSERVED sequence=N` line, then **open that real PNG** and inspect the UI
hierarchy if useful. No claim of continuous live video is made: these are
current captured frames from the running app.

## Choose the next gesture only after looking

```sh
python3 tools/direct-apk/client.py act tap_id --id pickButton
# Or: tap --x X --y Y; swipe --x X --y Y --toX X --toY Y --durationMs 350
# Or: back; launch; snapshot; finish
```

Review and commit only `tools/direct-apk/control.json` on the current Arena
branch, then `git push origin arena/d0574002-kurd-man`. The worker requires the
consecutive sequence and SHA256 of the last observed frame. It executes one
allowlisted public-UI gesture, obtains a fresh actual screenshot, and waits
again. There is no preprogrammed tour selected without seeing the app.
Coordinates/resource IDs come from the **observed** frame/XML, not guesses
about a mock screen. A busy hierarchy dump may be unavailable; that is recorded
as null, never replaced with stale XML.

Up to32 commands, a20-minute worker budget plus bounded emulator startup and
workflow timeout; finish closes the session with a **neutral** check result.
Unavailability/failure stays explicit. Raw frames are not committed. Record the
run/check IDs, exact APK, frame blob/digests, actions actually performed and
scoped findings in a small docs receipt. Offline `test_control.py` validates
mailbox safety, not native functionality, translation meaning or device quality.

Physical-phone behavior, TalkBack, the original private reader-page rerender,
and general Sorani quality are **not established** by this session.
