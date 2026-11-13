# Actual APK opened and manually inspected — regular 1.8.2

**Direct inspection is now established. Findings below are not fixed.** This is
not a new APK release, a web mockup, a replay of the earlier scripted test, or a
general quality-pass claim. The bounded Android session has now been ended.

## What was actually opened

- The **existing delivered regular Koorix** `com.koorix.app`, 1.8.2/code 18,
  non-debuggable ARM64, permanent v2 signer. Downloaded and independently
  public-only verified before installation; never rebuilt/re-signed.
- APK SHA256:
  `d03ee91ff681e21e2e4cf4938106415f9e5e43d7837a03af976c62e3be97a300`.
- Unchanged Android source tree:
  `05817e482d9dbcf101e636ec1bc63797c4c355ea`.
- Empty hosted official API 34 Google APIs emulator, advertised `x86_64` and
  `arm64-v8a`, native bridge `libndk_translation.so`. No reader phone connected.
- [Actual session run 43350571277](https://github.com/s1po0/Kurd-man/actions/runs/43350571277),
  job `131640362164`, session check `131640797293`, launch code
  `37e72776600f5586eb2633a71716a8a2111f4dac` on the current Arena branch.
  Session check concluded **neutral**, deliberately not a quality verdict.

Initial standalone run `43350400691` failed before the first frame because ADB
was not on PATH. Explicit platform-tools install/PATH fixed that infrastructure
failure. It was not called a successful app inspection.

## How the supplied Skills were used

The owner's `alirezarezvani/claude-skills` review/rendered-build guidance remains
pinned and applied, but does not supply an Android runtime. Discovery through
the owner's `claude-skill` topic found the MIT
[`hah23255/adb-android-control`](https://github.com/hah23255/adb-android-control)
Skill, pinned to `cc1cb29de0adc581e260842b36c198217bfab0b2`. Its relevant ADB
screencap/UI-gesture guidance, original Skill, license and provenance are retained
under `.claude/skills/koorix-native-qa/references/adb-android-control/`.
No upstream installer, port scanner, root/private extraction or destructive
example was executed, and no wholesale topic source audit is claimed.

`tools/direct-apk/` opens the running native app and waits. The same agent then
**views a real frame, explicitly selects one next gesture, and views the next
real frame**. No complete click-through tour was scripted in advance. Each
consecutive command is bound to the previous observed frame's digest; public
resource IDs/coordinates came from those actual screens. Twenty-six explicit
commands followed the initial observation, producing **27 viewed/hash-verified
native PNGs plus fresh UI XML**. Captured frames are not continuous video.

The manual path covered Sorani home/settings, a real dark-mode change and its
matching settings value, English whole-app switching, Reports, Style controls
and scroll, original system input chooser, actual PNG selection, EN→ckb/Online,
normal Translate and live OCR, completed result/text, native studio, size-control
change and Undo, saved-project library/reopen, and explicit Save & new export.
The sole source was the repository's authored synthetic panel; translation
words were not manually rewritten to manufacture an app result.

## Concrete findings from the actual app

| Finding | Observed evidence | Current status |
|---|---|---|
| **Empty-state book icon obscures completed preview** | Frames 12 and 20 show the translated page with `resultEmptyIcon` covering real lettering and the empty hint still present. Studio frames 14/22 do not have that book overlay. Back alone does not clear it; explicit studio Save & new export at frame 24 does. | **Observed, unfixed**; an in-app preview/state defect, not proof the book was burned into exported lettering. |
| **STOP remains on ready/completed surfaces** | Frames 9/10/12/13 show STOP despite ready/complete status. It disappears after studio Back (19), then reappears after edited export (24–26). Manual keep-alive was not enabled in this session. | **Observed, unfixed UI-state inconsistency**; does not prove a translation is still running or that STOP fails. |
| **Undo leaves a stale size-status banner** | A-minus gives 90% (17); Undo restores the actual size control to 100% while the status still says 90% (18). | **Observed, unfixed**; no claim that Undo loses style or that a pixel-size change was independently measured. |
| **Studio export still uses the legacy folder route** | Frame 24 reports `Download/KurdMan`; code confirms editor export uses that route while normal saved-job publication uses `Download/Koorix`. | **Observed, unfixed consistency issue**; this is the regular Koorix APK, not a second KurdMan app. Current-session exported file bytes/path were not independently read back. |

### Source correlation, not a substitute for the observations

- `MainActivity.savedJobListener` writes the status directly and invokes
  `showSavedProjectResult`. That path assigns the result bitmap/file without
  calling `refreshReadingSelection`, which owns empty-icon/hint visibility.
  The edited-export route instead calls `setStatus`, which refreshes it. This
  matches the actual initial-result versus post-export difference.
- `refreshKeepAliveUi` includes the asynchronous service-running flag. Job/service
  completion and UI refresh are separate; no service-stop notification refreshes
  this surface in the observed sequence. The true manual-protection case must
  still retain STOP if this later gets corrected.
- `EditorActivity.restoreSnapshot` rebinds size/history controls but does not
  replace the earlier adjustment's status message.
- `exportEditedProject` → `saveFileToDownloads` uses the legacy folder;
  `ProjectResults.savePublic` uses Koorix. Fixing this must not delete/move old
  reader data or silently break existing exports/recovery.

## What remains unproven / unchanged

No Android files, app identity, signer, translator, model, reader data or version
were changed; no APK was built/published/redelivered and no completion message
was sent to Telegram. These findings are **not** a claim of fixes in 1.8.2.

The result/studio wording was viewed by the **same agent**, not a native-human
reviewer. A short eye-button tap did not capture the hold-to-peek original, so
that comparison is not claimed. This session did not press STOP, run an offline
job or PDF export, inspect Arabic UI, exercise TalkBack/physical phones, or
rerender the original private reader page. None becomes accepted because the
hosted workflow finished successfully. Previous release-byte verification is
still valid; it is not a certificate that every UI state is clean.

## Evidence and the next reproduction

`docs/research/direct-apk-v1.8.2-session.json` retains exact run/check/APK/source
identities, all 27 frame PNG/XML blob IDs, lengths/digests, the 26 commands with
their commits and previous-frame binding, and scoped findings. The local client
verified Git blob SHA1, byte count and SHA256 for every downloaded frame.
Raw synthetic screenshots remain outside Git, following the existing evidence
blob convention; use those IDs to recover them, not a rendered web substitute.

Before a future Android fix, add failing regressions for completion-bound
placeholder refresh, automatic-versus-manual service/STOP state, Undo status,
and both export paths. Then reproduce with a **fresh direct session and actual
frames**, not only another green unit test. Any actual Android patch still
requires the normal versioned regular same-key release/delivery process; this
inspection itself does not authorize automatic publication.
