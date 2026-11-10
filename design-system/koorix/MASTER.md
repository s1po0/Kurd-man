# Koorix — Soft Triad native design system

**Current owner direction, 1.8.1:** replace the green-led 1.8.0 interface with
**three balanced, muted colours: blue, violet and peach**. Green is not an app
brand/accent choice. The owner finds it uncomfortable; do not reintroduce it
or claim that a palette change medically treats/prevents eye strain.

This supersedes the earlier generated teal/forest palette. Historical 1.8.0
implementation/evidence is retained in `docs/KOORIX_1_8.md`, not a current spec.

## Source and scope

- Requested skill: `nextlevelbuilder/ui-ux-pro-max-skill`, exact reviewed revision
  `6ae86f61f520d6ca229d2f40e070410638c908c5`, MIT; local license/provenance under
  `.claude/skills/ui-ux-pro-max/`.
- Local searches: reading/low-saturation colour, dark theme native contrast,
  flat design and RTL-safe styles. The book result was warm brown/amber, not this
  custom triad; Nature Distilled/clay results included green/extra decoration and
  were not blindly adopted. Native semantic contrast and the already selected
  functional flat style are the useful recommendations.
- **Actual stack:** Android Java Views/XML + AppCompat. No Compose/React rewrite,
  network font, UI dependency, AI model, paid API or global installer.
- Existing bundled **Vazirmatn 400/700** for joined Sorani/Arabic/English chrome.
  Never apply chrome fonts/colours to actual comic lettering or selected swatches.

## Palette: three complementary roles, not a three-colour rainbow everywhere

| Role | Light | Dark | Use |
|---|---|---|---|
| Blue | `#6674B7` | `#AEBBEF` | Primary family, navigation, import, selected overlays |
| Violet | `#9A79B3` | `#CDB2E3` | Secondary family, status surface, soft centre blend |
| Peach | `#E9BFA9` | `#EBC1AC` | Warm small highlights, theme button, badge/end blend |
| Primary action fill | `#545E9E` | `#B5BEF3` | Measured action-label contrast |
| On primary | `#FFFFFF` | `#20223B` | Labels, including pressed-state checks |
| Page | `#F5F4FA` | `#151621` | Large quiet surface; no green cast |
| Card | `#FFFDFB` | `#202230` | Neutral, readable surfaces |

Actual semantic tokens live in `android/app/src/koorix/res/values{,-night}/colors.xml`.
The hero and thin ribbon blend blue → violet → peach, while controls stay solid
and legible. Large backgrounds stay neutral. Both modes are deliberately muted;
no fluorescent/neon fills or animated colour cycling.

- All normal primary/secondary text and action labels: measured **>=4.5:1**.
- Meaningful control boundaries/icons: **>=3:1** where checked.
- Gradient text is checked against **each colour stop**, not a single endpoint.
- Distinct accent hues with saturation below 0.72. Status/error/danger retain
  explicit wording; colour is never the only signal.
- Dark selection badges/handles use an appropriate on-accent colour. Reader art
  and reader-selected green text remain unchanged: the no-green rule is chrome,
  not permission to recolour a comic or remove palette choices.

## Dark mode: visible, one tap, genuinely persistent

- A native **moon / sun button in the persistent workspace header** switches to
  Dark / Light. It names the next action and exposes the current mode to assistive
  tools, rather than relying on an unlabeled decorative icon.
- Settings retains **System / Light / Dark**, using the same stored `koorix_ui`
  preference and AppCompat night resources. Existing choices remain respected;
  a header tap makes a deliberate manual override. No reset on relaunch.
- On narrow/large-type screens header actions use their vector icons, with full
  accessible names/tooltips; the current-screen heading remains readable.
- Appearance/language recreation stays disabled during an active protected
  import/job. STOP, page navigation and reports remain available. Completed input,
  draft story options and reader data survive Activity recreation.
- Shared themes apply across library, studio, optional story/review and dialogs,
  not just one mock homepage. No new mandatory setup step.

## Preserved native UX

- One input button, original GET_CONTENT `*/*` compatible source chooser.
- Import first, labeled source/target/mode and thumb-reachable normal Translate.
- Five labeled destinations, selected state, Back-to-home, retained tab scroll.
- At large text a named destination menu replaces cramped/orphaned tab labels.
- 48dp touch targets, 4/8dp rhythm, logical start/end, tablet max measure, dynamic
  content heights and narrow/large-text field stacking. No nonessential motion.
- Native system/cutout/IME insets; buttons must stay clear of OS/keyboard chrome.
- Local project search; one Open/Resume action with explicit Backup/Reuse/Rename/
  Delete menu. Existing confirmation, recovery and backup data remain.
- Artwork-first studio and actual contour-safe prefit/undo/autosave/export remain
  untouched. UI overlays must never be exported into the page.
- Free/no login/no daily commercial quotas; all 74 targets and ckb/en/ar UI;
  automatic translation Skill, STOP, backups, PDF and safety budgets unchanged.

## Evidence discipline

Before distribution run the actual native SDK26/34 suite, contrast/locale/XML
checks, allowlisted native captures and public-only same-key APK verifier.
New tests exercise actual theme actions, persistence, pending input, protected
jobs and non-green distinct hue roles. Inspect real native output, not an AI
mockup or source-code counts. Synthetic Skia/Robolectric output does not certify
physical phones, TalkBack, real keyboards, every font/OEM or semantic accuracy.
