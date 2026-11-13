# Koorix 1.8.3 — one public image, no automatic TXT

Regular **Koorix / com.koorix.app**, versionCode **19**, unchanged permanent
**v2** signing identity. No Preview application or personal KurdMan release.

## Requested behavior and implementation

- **One public image per translated page.** `ProjectResults` previously wrote
  a single-page result to both `Download/Koorix/` and `Pictures/Koorix/`.
  Gallery can index both, explaining a concrete duplicate-publication route.
  The canonical route is now **Download/Koorix/** only. Android's Files and
  Images collections may expose the *same* MediaStore row; those views are not
  two copies. The app-private editable source/render/product/library copies
  remain private and are not additional Gallery exports.
- **No automatic TXT sidecar.** Translation and Studio no longer create,
  overwrite, copy into the app download library or publish a TXT. Source and
  target text, geometry, styles and review information remain in `project.json`,
  the normal result TextView, saved-project reopening and explicit backups.
  Existing TXT/image files are not automatically deleted or migrated.
- **Idempotent public exports.** A stable project-specific filename plus exact
  name/path/package-owner lookup updates the same owned result on retry or
  explicit Studio export. If the lookup is unavailable it keeps the durable
  app copy, rather than assuming absence and inserting another public file.
  Another app's same-named file is neither overwritten nor deleted.
- **Studio uses the regular publication route.** Its export first renders the
  current edits of every page from its saved source, using the existing complete
  contour/ink validator, rather than trusting a completed-but-old render cache.
  It then uses `ProjectResults` for the same result filename, public folder,
  output binding and saved-project preview. The legacy timestamped
  `Download/KurdMan` plus `Pictures/KurdMan` plus TXT route is bypassed in regular
  Koorix; the personal edition's separate handlers/version are not rewritten.
  A new translation still has its own project UUID and does not overwrite an
  older book. Explicit Studio Export updates that project's current product.
- **Batches, CBZ and selected PDF remain.** An image batch retains one public
  image per page and its archive; a CBZ without the individual-page choice stays
  an archive. Requested PDF is still produced. Existing app-private download
  storage is not renamed or cleared, so older results remain reachable. PDFs
  are now also included in that result list.

## Native interface and STOP corrections

The actual installed1.8.2 observations are preserved separately in
`KOORIX_DIRECT_APK_INSPECTION.md`, not retroactively marked fixed there.

- Empty-book icon/hint visibility refreshes after a real result is bound, and
  a real selected-source preview is not labelled empty either. It no longer
  depends on opening/returning from Studio or on a later unrelated status call.
- STOP visibility describes actual protected work, pending recoverable work or
  explicit manual keep-alive—not a transient service-running flag during idle
  shutdown. Active-job/manual STOP remains available. `stopAll` remembers the
  prior running flag before clearing it, so its service STOP intent is reachable.
  Activity and notification STOP both disable pending recovery after a worker has ended, without deleting
  checkpoints. It still requests cancellation; only the runner ends its job.
- Undo/Redo rebinds the restored size into the status as well as the controls.
  Restoring100% no longer leaves the earlier90% claim on screen.

The owner-requested UI/UX Pro Max and reviewed Java/universal/Design QA guidance
were applied to native Java Views/XML. The focused local UX searches did not
supply a verified Undo-specific rule; current-state feedback is a general UX
principle, not a claim that an upstream browser audit measured Android. There
is no new UI framework, model, permission, haptic effect or paid service.

## Regression and runtime evidence

### Failing baseline

Test-only commit `ea58027` ran against unchanged1.8.2 Android production code:
**43432576858 / job131909499933**, **256 executions,24 failures,0 skipped**.
The failures include actual automatic-TXT publication/old-text overwrite,
public-output counts and the observed completion/STOP/history contracts.
This baseline was not built or distributed as an update.

One baseline failure was **test-runtime capability**, not a proven PDF defect:
Robolectric4.17 does not supply Android's `PdfDocument` backend; SDK26 and34 both
reported `document is closed`. The archive/batch contracts continue on both
SDKs. PDF is instead required by the **actual installed-APK emulator gate**:
the normal selected-PDF option, real Studio export, public PDF bytes and still
one Gallery image/no TXT. No mock PDF writer is substituted or marked a pass.
The unit MediaStore provider explicitly models its output-stream boundary;
real public provider writes/readback are checked separately on installed Android.

### Candidate and release

Implementation candidate `dbb4d0b`, run **43433661287 / job131913162330**,
executed267 native/JVM cases with2 failures/0 skips: the unsupported PDF backend
and the unit provider's native file-descriptor transport. Its20 completion/UI/
STOP/Undo/Studio regressions all passed; no APK was distributed. Those test
boundaries were corrected explicitly, not hidden in production code.
The production gate now requires **at least268 native/JVM executions**,
including **20 publication** and **22 completion/STOP/Undo/Studio** executions,
plus the existing contour/narration/UI/crypto/comic/quality gates. Counts test
covered contracts, not physical devices or general translation quality.

Final implementation candidate **a0234c7**, run **43435326161 / job131920839279**,
passed **268 native/JVM, 0 failed, 0 skipped**, 20 publication and 22 completion
executions, plus all existing gates. Its exact regular signed candidate APK
SHA256 was `409dc0468b6bcc470cc6e37cf13789f02ce56669983069aa6a4928955d0d2581`.
All 22 installed-APK synthetic files were downloaded, length/SHA256/Git-blob
verified (index `159efa178cef7c581e11c8b27ef78017b80af7b9`); actual result,
Studio Undo, manual STOP label and Studio JPEG were visually reviewed by the
same agent. The synthetic PNG words, selected live translator and UI text were
observed. The public one-row/no-TXT, selected real PDF, Studio same-row export,
process restart and actual manual service shutdown checks passed on API34; no
physical Samsung Gallery or native-human acceptance is inferred. The production
release must independently repeat the gates on its own delivered bytes.

The installed-APK driver was updated instead of retaining unwanted app TXT for
QA: it reads source/target pairs from the normal public result TextView into a
local synthetic JSON evidence file. It requires one app-owned public image and
zero TXT rows, the same row in MediaStore Files/Images, canonical folder,
completed/idle UI, Studio90→Undo100, same-row Studio export, process reopen, requested PDF and actual manual-service STOP.
Screenshots and exported JPEG must be observed and hash-verified. Final build,
run IDs, bytes, native/capture reports and delivery acknowledgements belong in
`koorix/releases/v1.8.3.json` only after those operations actually succeed.

Local developer checks already passed:16 emulator-driver/collector contracts,
16 direct-observation safety contracts,55 offline Skill tests, free-edition and
native UI resource checks,3×6 site-state contracts, Python/Bash syntax and workflow
YAML parsing. Local `javac` and KVM are absent: Java17/native Android execution
uses hosted CI rather than a mock/web substitute.

## Preserved boundaries

No translator, terminology guard, glossary precedence, OCR word/order/geometry
logic, whole-ink erasure validator or source-language/target choice changed.
The muted blue/violet/peach theme, persistent System/Light/Dark + moon/sun,
74 targets and ckb/en/ar UI, one original app-capable chooser, normal automatic
Translate/Skill, free/no-login/no-commercial-quota, true offline route,
Reports/projects/backups and300-page/300-MB/1-GB safety budgets remain.

No NLLB, new large model, paid API, hidden online fallback, reader-page upload,
private signing recovery access, signer rotation, phone uninstall/storage clear,
PR or merge. Existing v2 installations can update without uninstalling.

**Not established by these checks:** the owner's physical phone/Samsung Gallery,
TalkBack, exact historic reader-page OCR/rerender, offline-pack quality, native-
human/general Sorani semantic acceptance or universal layout/erasure perfection.
Do not relabel unchanged1.8.2 as this fix or treat a green count as those results.

## Verified release and APK delivery

The actual production run **43437436521 / job131926069148** passed all268
native/JVM tests (0 failures/skips) and installed its **own delivered APK bytes**
on API34 with the ARM64 bridge. All22 synthetic artifacts were recovered and
SHA256/length/Git-blob verified, index `7a161c046335041ff9ef52012f7747768318c8f8`.
Result preview, Undo, Gallery-related UI/service state and synthetic JPEG were
viewed by the same agent. Actual public MediaStore reported one image row and
no TXT; a selected PDF and manual foreground service STOP were exercised.

Regular APK SHA256 `ccb851fd8827c6b248088c28c6daad55c24cd1e3c9ecf9730a32897e0bdc8be0`,
33,591,433 bytes, permanent v2 signer. The public-only verifier independently
confirmed signature/content, manifest, version and test/private-material
exclusions. Release asset **743861264** has the same digest; the Git blob and
chat-presented file were byte-verified, and the stable URL returned HTTP302.
Telegram acknowledged the APK upload in the production job. Completion notice
is a separate gated operation; its acknowledgement must be recorded only after
it actually succeeds. No phone data was removed and old duplicates remain.

The separate completion gate **43439037030 / job131931517497** passed after
chat presentation and APK delivery; Telegram acknowledged **تەواو بووم**.
