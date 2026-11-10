# Koorix 1.8.0 — native Reading Studio UI/UX

Regular **Koorix / com.koorix.app**, versionCode **16**, same permanent v2 signer.
The owner requested a complete native interface/experience redesign using
[nextlevelbuilder/ui-ux-pro-max-skill](https://github.com/nextlevelbuilder/ui-ux-pro-max-skill),
not a web mockup, a separate Preview app or a translation-model replacement.

## Requested skill: actual use and license

- Pinned source: `6ae86f61f520d6ca229d2f40e070410638c908c5`, MIT, copyright 2024
  Next Level Builder. The local skill, data, search code, references, license and
  provenance are in `.claude/skills/ui-ux-pro-max/`.
- Reviewed the source/license and standard-library local search before running
  it. No global/npm installer, paid API, remote font, model or agent service.
- The first reader search routed to News/Media; its breaking-news palette was
  rejected. A narrower **creative editor productivity** search returned a
  relevant Productivity Tool / Flat Design recommendation. Exact book-product
  searches returned no verified match; this is explicitly not a claimed match.
- Native touch, safe-area, navigation, text-reflow and contrast guidance was
  applied. Actual stack remains **Android Java Views/XML + AppCompat**, not a
  misreported Compose/React stack. Existing bundled Vazirmatn supports connected
  Kurdish/Arabic; suggested Latin web fonts were not downloaded.
- The curated source of truth is `design-system/koorix/MASTER.md`, including
  explicit native overrides and measured semantic colour pairs.

## Whole-app changes

### Translation workspace

- A restrained Paper & Forest visual system replaces the old gradient-heavy
  promotional layout. Persistent, localized screen title and header language
  chooser; light/dark/system appearance stored locally.
- **Import is first**, with a selected-file/page summary. The original ONE
  input button still opens Android GET_CONTENT `*/*`; compatible installed
  Gallery, MT Manager, ZArchiver and My Files remain eligible. No split routes,
  photo-only replacement, MIME whitelist or broad storage/media permission.
- Source/target/mode are clearly labeled. OCR details and names/terms are
  progressively disclosed; normal **Translate** still runs the existing Skill
  automatically, without a required setup/save/review screen.
- Translate stays in a thumb-reachable action dock. Native pressed/disabled
  states, always-available busy STOP, status feedback and import safeguards.

### Navigation and accessibility

- Five destinations: Translate, Style, My work, Settings, Reports. Current
  destination has a heading and selected semantics, not colour alone.
- Back from another tab returns to Translate; per-tab scroll state is retained.
- At large system text, a labeled destination menu replaces cramped/orphaned
  bottom labels; every destination remains accessible. Narrow/large-text pairs
  stack. A readable maximum measure is used on tablets.
- Compact landscape chrome removes redundant introduction space, while retaining
  meaningful job/error feedback and navigation.
- Native 48dp targets, a consistent vector icon family, joined-script UI fonts,
  semantic light/dark tokens, explicit labels and logical start/end directions.
  System bars, cutouts and IME insets are handled without placing buttons under
  OS chrome. No nonessential animation was added.

### Style and text studio

- Style is organized into lettering preview, type/size, colour/effects and studio
  entry, rather than one undifferentiated settings wall.
- Reader-selected font previews and colour swatches are deliberately excluded
  from app-chrome restyling. Actual translated glyph/style data is unchanged.
- Studio retains the artwork-first canvas, undo/redo, selected-text navigation,
  page controls, zoom, peek, four labeled sections, optional advanced tools,
  autosave, original erasure and contour-safe rendering/export. Icon buttons
  have native accessible names; panels use available height rather than hiding
  the artwork behind an oversized control sheet.

### Projects, results, settings and optional review

- Local searchable project library, one Open/Resume primary action and a compact
  project menu for Backup / Reuse story / Rename / Delete. Backup disclosure and
  destructive confirmation remain; sources are never automatically deleted.
- Local library listing runs off the UI thread. Search and result actions do not
  contact an account/server or alter a project.
- Results distinguish preview, editable projects, translated text and exported
  files. Empty states, wrapping actions and consistently styled file cards.
- Settings group appearance/language, export/cleanup, optional packs and background
  work. Reports keep persistent diagnostics and explicit copy/share/clear.
- Optional story preferences and review use the same typography/surfaces, with
  clear headings and keyboard-safe forms; a form does not jump into its glossary
  on opening. These screens are still optional, not a new Translate requirement.

### Pending input and data safety

- Completed imports survive Activity recreation for appearance/locale changes:
  metadata-only state restores existing private files without duplicate copies,
  bitmap IPC or automatic project changes.
- Restored paths must remain under canonical app cache/files roots, with import
  count/byte budgets. Invalid/traversing/missing paths fail closed; no file is
  read as arbitrary task data or deleted by UI restoration.
- Appearance/language changes are disabled during a protected import/job, while
  STOP/navigation/report access remains. Existing durable pipeline, frozen
  options, recovery and editable project/backup data stay intact.

## Explicitly preserved

- Fully free/no login/no Premium/no daily commercial quota/device registration.
- Single original app-capable chooser and selected-URI access only.
- All 74 target choices and full Kurdish / English / Arabic interface resources.
- Automatic translation Skill, offline really offline, saved jobs, STOP, Reports,
  library/backups, optional PDF and the 1.7 contour-safe full-ink validator.
- 300-page / 300-MB import / 1-GB cache protections. No NLLB, new large model,
  paid API, hidden online fallback or semantic-accuracy improvement claim.
- Permanent v2 signer and package: install over v2 Koorix; do not uninstall or
  clear storage. Historical private device/subscriber data is untouched.
- Personal-edition version and delivery remain unchanged; no KurdMan APK sent.

## Evidence and limitations

- Baseline before UI implementation: run **42857557966**, commit `f769e42`,
  **180 executions, 10 UI failures, 0 skipped**. The prior 170 passed. Failures
  covered buried import, missing workspace heading/search, unexpected tab Back
  and the requested paper-surface contract.
- Native resource/style errors were corrected rather than waived. A later run
  produced 38 partial native captures but failed Translate naming and an SDK34
  locale-fixture configuration; distribution remained blocked. The action name
  was retained and the fixture explicitly supplies the real locale configuration,
  without removing RTL assertions.
- Candidate **42865259663 / job 130001776576**, commit `9e2f3fd`, passed **190 executions, 0 failed, 0 skipped**, with **50 native captures**, 34 glyph regressions and 20 UI cases. Final CI requires **at least 190 executions**, including the existing 34 native
  glyph regressions and 20 reading-studio UI cases, no failures/skips, measured
  light/dark contrast and three-locale resource parity. Synthetic native captures
  cover main destinations, both themes, Kurdish/English/Arabic, small phone,
  landscape, tablet, large text, project library, real editor, story and review.
- Only explicitly allowlisted synthetic PNGs are published; no reader screenshots,
  credentials or arbitrary images. Actual native Views/XML/Skia output, not an
  AI-generated mockup. Detailed receipt: `koorix/releases/v1.8.0.json` after
  verified production delivery.

**Not established:** physical phone/OEM/TalkBack/IME behavior, every font/locale/
size or perfect visual/semantic quality. Native JVM tests and fixture inspection
are not those evidence categories. The Sorani translation engine is unchanged.

Production APK after verified publication:
https://github.com/s1po0/Kurd-man/releases/download/koorix-v1.8.0/Koorix-v1.8.0.apk
