# Koorix 1.8.2 — duplicate narration OCR and contextual field labels

Regular **Koorix / com.koorix.app**, versionCode **18**, permanent **v2** signer.
This is an actual runtime change, not the earlier text-only comparison/review.

## Two distinct defects, not a universal model upgrade

The supplied Sorani rendering visibly had a smaller second text run painted over
one paragraph line, and an overly literal label for an English fantasy trait
field. The original page/output were reviewed **privately**. Exact original OCR
blocks, APK version and selected online/offline mode cannot be inferred from a
JPEG. Its page extent also differs from the supplied raw. We do not claim a
pixel-identical rerun of that old phone job or publish the reader's page/dialogue.

### Covered OCR lines become one source block, not two translations

Multi-pass OCR could return both a complete paragraph and a short re-read of its
first/last line. Old duplicate tests compared whole-block text/centre distance:
a short edge row has little whole-paragraph similarity and a distant centre,
so it survived as another block and could be translated/painted over the body.

The actual candidate normalizer now additionally requires:

- substantial overlap of **the actual OCR line boxes**, aligned in the same row;
- equal normalized words, or a fully covered multiword fragment at word edges;
- every line of the smaller reading accounted for by the larger reading.

It then uses the existing confidence-aware line merge, retaining all words/other lines,
regardless of which candidate had the higher initial score. Different rows,
columns, or unmatched source content are not discarded merely because the text
is repeated or lies inside a large box. Equal covered readings do not create a
false OCR-disagreement warning. A final normalize/group pass after re-reading
handles geometry/text updates before stable segments are translated/persisted.

This fixes a demonstrated class of duplicate-run overlap. It is **not** a blanket
promise that every artistic layout or OCR error is solved, and it does not erase
original text when the existing whole-ink contour fit fails.

### Fantasy field labels are local and contextual; body stays with the translator

For enabled **English → Sorani**, Auto/Fantasy only, a page must contain both
anchored `Aspect:` and `Aspect Description:` fields, a short named value, and
independent game-system vocabulary. Ordinary uses of “aspect”, insufficient
context, other targets, explicit Everyday/Martial, disabled Skill and later
unrelated pages keep their normal route.

In that narrow context:

- `Aspect:` → `تایبەتمەندی:`
- `Aspect Description:` → `وەسفی تایبەتمەندی:`

Those field labels are local. The installed-APK review also exposed an unrelated
synthetic title being mistranslated with the sports sense of “Keeper”. A second,
conservative role guard handles **one** unambiguous title/description pair only:
for a short `object + Keeper` title, the description must independently mention
that object (or its regular English plural), with no sports cues and no reader
term in the title. Only this custodian role head becomes `پارێزەری`; its noun
modifier still uses the same engine. Unknown forms, unreferenced objects,
multiple field pairs, sports, ordinary dialogue and reader choices are not
rewritten. No full synthetic title or supplied chapter title is hardcoded.

Other names/values and the **complete body** still use the reader's same selected
online/offline engine, existing terminology, quality hints and bounded contextual
requests. The body is not hardcoded for a chapter, sent to chat, pivoted through
the supplied Arabic output or replaced with an invented “better model”. Reader
glossary/whole-block overrides win.

Original block count, ordering and progress are preserved when fields are in
one OCR block or several. STOP remains checked during planning/requests; no
hidden network fallback is added for offline. Final script cleanup is unchanged.

## Evidence

- Baseline commit `2276349`, run **43329803605 / job 131571376153**:
  **222 executions, 8 failures, 0 skipped**. Covered first/last lines, reversed
  score order and automatic dark-panel normalization failed on SDK26/34. The
  existing 208 and adversarial negative cases passed. Release was blocked.
- Native synthetic dark-panel fixtures use unrelated authored text/art, not a
  reader page. They exercise the real normalizer and automatic renderer, joined
  Sorani white text on black, complete dialogue and preservation outside the
  panel. `NarrationPanelAndroidTest` runs on SDK26/34.
- `ComicSystemPanelTest` exercises split/combined fields, reader overrides,
  ordinary meanings, explicit genre opt-out, other targets, disabled Skill,
  STOP, original block IDs/count and monotonic progress. These are rule/backend
  contracts, **not a general semantic benchmark**.
- The existing advisory public-route probe also tried an unrelated synthetic
  trait panel. Run **43330531173 / job 131573797942** acknowledged the contextual
  labels using the real selected public MT route; its body output was observed.
  No reader text/image was sent. This does not establish the meaning of an entire
  chapter or validate every body sentence. Public-route availability is advisory.
- Reviewed owner-supplied `alirezarezvani/claude-skills` at
  `08cd8a9fc86cc370e2c0d448e8171fbcfdbc5a30` (MIT): Java/universal code review
  and Design QA's rendered-build/unverified-is-not-pass guidance. Its React/Jest/
  Playwright scaffolding does not run Android and was not installed or claimed
  as native QA. The relevant licensed documents and safe native adapter live in
  `.claude/skills/koorix-native-qa/`.
- Additional fragment baseline `f7cb272`, run **43331847036 / job 131578214250**:
  **226 executions, 4 failures, 0 skipped**. Both score orders dropped words in
  a partially covered source row on SDK26/34. The complete same-row reading is
  now preferred over an exact word-fragment, regardless of score; unrelated or
  contradictory OCR is not repaired by guessing. These regressions block release.
- `tools/emulator-tests/` adds a fail-closed **actual installed APK** check on an
  empty official API34 CI emulator (ARM64 native bridge required). It uses the
  exact normal signed release APK, public UI/MediaStore/chooser/export/studio and
  an unrelated authored PNG, real bundled OCR and selected translator. No test
  variant, reader page, private app-storage bypass or source substitution. Only
  named synthetic captures/exports may leave CI. Signing plaintext is removed
  before emulator execution. Its result must be observed, not inferred from
  Robolectric; final evidence is recorded in the release receipt.
- A missing renderer locale qualification caused an intermediate compile failure
  and was fixed; it was not bypassed. Final production must pass at least **226
  native/JVM executions**, including 34 existing contour cases, 38 UI cases and
  18 narration cases, plus existing cryptographic/comic/quality/Skill checks.
- Candidate `0bb0618`, run **43332616841 / job 131580780730**:
  **226 native/JVM executions, 0 failures/skips**, 20 contour/panel fixtures and
  54 UI captures; regular signed APK compilation/independent content gates passed.
  **Actual APK emulator acceptance did not pass**: Android did not boot; no app
  install/action was claimed. Its partial report is
  `9d88defd07160ff812e8f24181c4ac2bc94b7581`. A stable Ubuntu24.04 Android host,
  early-exit detection and bounded empty-emulator diagnostics were added rather
  than weakening the gate or substituting another build. Final outcome remains
  separate until observed in the release receipt.
- The bounded diagnostic in run **43334072434 / job 131585686615** identified
  an **unknown AVD directory** and a **missing host libpulse**; KVM itself was
  reported usable. Explicit shared Android-user/AVD locations and ordinary
  headless-emulator host libraries address those observed setup errors. This was
  infrastructure, not a claim that the APK had run; all 226 native regressions
  passed again. No alternate app/ABI or mock route was used.
- Run **43334918303 / job 131588556158** booted Android34, advertised
  `x86_64,arm64-v8a` with `libndk_translation.so`, and installed/ran the **real
  regular signed APK** (`95dc1a124d31ff7f0334cabafa489f968db186484afaa71a87e74579a4eb4863`).
  Actual Kurdish/English light/dark screenshots and real theme/process-persistence
  actions were observed. Its synthetic input setup stopped at MediaStore insertion,
  so **OCR/MT/render was not claimed**. The driver now accepts the public
  `external_primary` volume too, retains bounded insertion errors, requires a
  genuinely focused chooser/fresh XML rather than a historical Activity entry,
  and disables only emulator system animations. Partial report:
  `3dfa4604b422e09ddb0532a805d12e5d30012912`.
- The fresh foreground check in **43336006343 / job 131592225693** correctly
  prevented a false pass: Android's real **DocumentsUI file chooser was visible**,
  but its activity class was not the driver's old literal-name list. The actual
  screenshot was viewed; the normal app chooser was not replaced. Matching now
  recognizes the focused system DocumentsUI/IntentResolver packages (including
  PickActivity), still rejects activity-history/home entries, and uses shell-temp
  XML rather than polluting the user's synthetic file list. Partial report:
  `db98acf5ed541187941cde3b713feabce18b253e`. No OCR/MT success was claimed for
  this aborted input-setup run.
- Run **43337378843 / job 131596884913** passed the corrected actual
  DocumentsUI foreground check and captured the real unified chooser without a
  public XML file. It then confirmed the original empty-output MediaStore setup
  error, **before OCR/MT**, and an unbounded emulator shutdown delayed collection
  until the 40-minute job limit. The eventual hash-verified partial report is
  `9dc16662ec95e6b24f2649d6283372ffeb5f93f4`; the actual chooser and failure
  screenshots were viewed. All 226 native/JVM regressions still passed. The run
  did not publish/deliver anything and is not an end-to-end pass.
- A subsequent source review of official Android14 `Content.InsertCommand`
  showed that `content insert` **does not print its returned URI** even when
  successful. Accepting another volume spelling was insufficient for that real
  shell behavior. The synthetic setup now queries the exact authored fixture's
  public name/path/MIME, requires one positive image ID with no pre-existing
  fixture, and verifies PNG readback bytes before selection. Local regression
  reproduces the earlier empty-output failure and rejects stale/multiple rows
  or corrupted bytes. This is a QA input-setup correction, **not** a replacement
  OCR source or proof of a completed hosted translation. A total driver budget
  and bounded emulator shutdown preserve failure evidence rather than hanging.
- Run **43341332890 / job 131614563311** validated the real public MediaStore
  write/readback and reached the app, but the shell SEND-extra path lacked a real
  app-readable URI grant. The viewed actual failure screenshot explicitly reports
  no access to that synthetic URI; it is not OCR/translation evidence. Its verified
  partial report is `03accc19760af30b88937e89b6ccb3cd8ce9695c`. Shutdown now
  finished within the bounded test, and all 226 native/JVM tests passed again.
  The driver now publishes the fixture **before opening the original chooser**
  and taps its real DocumentsUI row, obtaining the same selected-URI read grant
  as a normal reader. It does not grant broad media access or change the app.
  This input correction must still pass the hosted end-to-end test.
- Run **43344151462 / job 131619307783** was the first **successful installed
  regular APK OCR → selected live MT → rendered public export → native studio**
  run. All source words and their order survived as one source/target block,
  real chooser/theme/STOP-protection actions passed, and all 12 named artifacts
  were hash-verified (`e927e9e0b9a98338927a6a0f5cb16976570e2092`). The actual
  JPEG/studio/busy/chooser captures were viewed: complete joined white lettering
  on black, no duplicated smaller text run. **That does not make its meaning
  perfect:** same-agent review found “Keeper” had the unrelated goalkeeper sense.
  This candidate was not published/delivered. Baseline **c09bb99**, run
  **43345791834 / job 131624724682**, then reproduced that wrong-role failure
  in the actual Java code, while prior 605 comic/51 quality assertions passed.
  The conservative description-supported role guard, adversarial cases and the
  stricter real-export fixture check address that observed defect; a new hosted
  run and visual/meaning review are required, not reuse of the earlier green icon.
- The actual delivered old 1.8.1 APK was downloaded and independently hash/signature
  verified locally; it passes the old UI gates but **correctly fails** the new
  narration/system-field implementation gate. A stale APK cannot be relabelled
  as this fix.
- All 20 glyph/panel fixtures and 54 UI captures remain strictly allowlisted
  synthetic native output. Final counts, hashes, source tag and actual deliveries
  are recorded in `koorix/releases/v1.8.2.json` after verification.

**Still not established:** physical-phone/OEM/TalkBack behavior, the exact old OCR
segmentation, an end-to-end rerender of the supplied raw with real OCR/MT, or
universal Sorani meaning quality. The underlying engines are unchanged and their
semantic limitations remain. Do not substitute passing counts for those claims.

## Preserved

Muted blue/violet/peach day/night UI, header moon/sun and saved theme; one original
GET_CONTENT `*/*` source chooser; compatible Gallery/MT Manager/ZArchiver sources;
all 74 targets and ckb/en/ar UI; completely free/no login/no Premium/no commercial
daily quota; automatic Skill with normal Translate; projects/backups/studio/STOP/
recovery/Reports/PDF; complete-ink containment/clean erasure; 300-page/300-MB import
and 1-GB cache safeguards. No paid API, NLLB, large model or new runtime dependency.
No private signing recovery opened, signer rotation, uninstall or data deletion.

## Use

Install over existing v2 Koorix without uninstalling or clearing storage. Translate
the **original source page again**: an already flattened bad JPEG is not repaired
by installing an APK, and existing reader-edited projects are not silently rewritten.
No separate Skill setup or manual glossary is required for the guarded field case.

Stable APK after verified publication:
https://github.com/s1po0/Kurd-man/releases/download/koorix-v1.8.2/Koorix-v1.8.2.apk
