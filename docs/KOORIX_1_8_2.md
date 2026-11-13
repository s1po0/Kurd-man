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

Only those field labels are local. Names/values and the **complete body** still
use the reader's same selected online/offline engine, existing terminology,
quality hints and bounded contextual requests. The body is not hardcoded for a
chapter, sent to chat, pivoted through the supplied Arabic output or replaced
with an invented “better model”. Reader glossary/whole-block overrides win.

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
  exact normal signed release APK, public UI/MediaStore/share/export/studio and
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
