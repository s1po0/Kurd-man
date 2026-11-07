# Project map (read first)

Paths below are relative to the checkout root. Resolve it from the workspace;
do not hard-code `/home/user` or a branch name into scripts.

| Concern | Source of truth |
| --- | --- |
| Runtime rules/context/glossary | `android/app/src/main/java/com/kurdman/app/ComicTranslationSkill.java` |
| Curated entries (Sorani only) | `android/app/src/main/assets/comic-lexicon-v1.tsv` |
| Online/offline dispatch | `android/app/src/main/java/com/kurdman/app/SoraniTranslator.java` |
| In-app checks / review / correction memory | `ComicQualityChecks.java`, `ComicQualityReview.java`, `ComicQualityActivity.java` |
| Settings + language-pair persistence | `ComicSettings.java`, `ComicSettingsActivity.java` in the same Java directory |
| One-button automatic controls / pre-render integration | `ComicTranslateControls.java`, `ComicTranslationFlow.java`, `MainActivity.java` |
| Saved JSON / target field | `TranslationProject.java`, `Segment.java`; `ckb` stores the selected target, not necessarily Sorani |
| Local editable library / explicit backups | `TranslationProjectStore.java`, `ProjectLibraryActivity.java`, `ProjectArchive.java`; `files/projects/p-UUID`, legacy `projects/current` retained |
| Durable regular-Koorix pipeline | `SavedJobInput.java`, `SavedJobPipeline.java`, `SavedJobBackend.java`, `SavedJobCoordinator.java`, `ProjectResults.java` |
| Free public edition / one app-capable input chooser | `ComicInputPicker.java`, `MainActivity.java`, `tools/free-edition/check.py`; no runtime account/Premium client |
| Layout / contour / erasure / SFX | `ManhwaRenderer.java`, `StyledTextRenderer.java`, `BubbleBoundary.java`, `CleanEraser.java`, `PageEditorView.java`, `EditorActivity.java`; full unclipped native ink before erasure, not JSON counts |
| Three-language skill UI | `res/values*/comic_strings.xml`, `res/layout/activity_comic_settings.xml` |
| Actual Java contract tests | `tools/comic-skill/test.sh`, `ComicTranslationSkillTest.java` |
| Existing small live smoke probe | `tools/comic-probe/`; advisory, opt-in, synthetic inputs, NOT an accuracy test |
| Regular APK + signing gate | `.github/workflows/koorix-apk.yml`, `tools/signing/README.md` |
| App version / editions | `android/app/build.gradle` (keep the personal edition untouched) |
| Current runtime limits/evidence | `docs/COMIC_TRANSLATION.md`, `KOORIX_KU.md`, `koorix/releases/` |

## Current translator limits, not new claims

- Sorani offline: existing Helsinki OPUS-MT v2 QUInt8/beam4; meaning can be weak.
  Other supported offline targets use ML Kit. No NLLB or new download here.
- Runtime comic mode already supports genre, curated phrases, reader glossary,
  bounded neighbouring context online and safe individual fallback.
- Built-in Sorani terms must not be forced into Arabic, English or another target.
- This AgentSkill does not replace that engine or execute inside Android. In
  1.4.0 the practical local checks/review workflow is separately implemented in
  the native Koorix Skill screen. Since 1.4.1, readers simply use **Translate**:
  local rules, explicit glossary, checks and bounded contextual recovery are in
  that pipeline. Optional names/genre expand inline; manual review is optional.
  The Python developer tools still do not translate.
- The packet adapter accepts an explicitly supplied local JSON copy. Installing the skill alone never adds an export UI. Koorix 1.5 separately
  implements explicit source/vector ZIP backups/restores in Saved projects.
  Do not instruct users to uninstall/reinstall or use `run-as` on a non-debuggable
  release to obtain app-private files.

## Progressive reading

For a term error, read the glossary/rules and relevant tests, not the entire
renderer. For a visual defect, inspect the actual page/rendering implementation.
For an offline error, reproduce with the offline path; do not silently use the
online route to make a test pass. For release work, read public signing metadata
and the existing workflow, not secret material.

Since 1.6 the owner explicitly removed both login and Premium. The public app is
completely free with no daily app quota or device registration. Keep historical
private subscriber data untouched; never reintroduce a paywall without permission.
See `docs/KOORIX_1_5.md` for durable checkpoints, STOP and backup boundaries.

Since 1.6.1 the owner rejected the split Gallery/Files UI. Keep one input button
using a system GET_CONTENT */* chooser with compatible installed source apps.
Do not restore OPEN_DOCUMENT or a photo-only picker for normal public input.

Since 1.7 the regular renderer persists multi-run contours and validates complete
Android-shaped glyph/effect/rotation pixels before erasure. Studio and export
must share the same validator; do not restore allowOverflow or detach a contour
when dragging. See `docs/KOORIX_1_7.md` and the pinned all-topic research report.
