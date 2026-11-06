# Project map (read first)

Paths below are relative to the checkout root. Resolve it from the workspace;
do not hard-code `/home/user` or a branch name into scripts.

| Concern | Source of truth |
| --- | --- |
| Runtime rules/context/glossary | `android/app/src/main/java/com/kurdman/app/ComicTranslationSkill.java` |
| Curated entries (Sorani only) | `android/app/src/main/assets/comic-lexicon-v1.tsv` |
| Online/offline dispatch | `android/app/src/main/java/com/kurdman/app/SoraniTranslator.java` |
| Settings + language-pair persistence | `ComicSettings.java`, `ComicSettingsActivity.java` in the same Java directory |
| Job snapshots / input integration | `MainActivity.java` |
| Saved JSON / target field | `TranslationProject.java`, `Segment.java`; `ckb` stores the selected target, not necessarily Sorani |
| Latest phone-private project | `TranslationProjectStore.java`: app-private `files/projects/current/project.json` |
| Layout / erasure / SFX | `ManhwaRenderer.java`, `CleanEraser.java`, `EditorActivity.java`; don't guess from JSON counts |
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
- This agent skill does not replace that engine or run inside Android. Its tools
  make review, reproducibility and development safer. They do not translate.
- The packet adapter accepts an explicitly supplied local JSON copy. The release
  app does NOT gain a full-project JSON export/import UI from installing a skill.
  Do not instruct users to uninstall/reinstall or use `run-as` on a non-debuggable
  release to obtain app-private files.

## Progressive reading

For a term error, read the glossary/rules and relevant tests, not the entire
renderer. For a visual defect, inspect the actual page/rendering implementation.
For an offline error, reproduce with the offline path; do not silently use the
online route to make a test pass. For release work, read public signing metadata
and the existing workflow, not secret material.
