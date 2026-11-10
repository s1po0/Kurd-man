# Koorix 1.8.1 — soft three-colour mix and visible Dark mode

Regular **Koorix / com.koorix.app**, code **17**, permanent **v2** signer.

## Owner correction

The owner requested three attractive mixed colours rather than the green-led
1.8.0 interface, which they find uncomfortable, plus readily available Dark mode.
The new design uses **muted blue + violet + peach**, with quiet neutral surfaces.
This is a personal colour/UX preference, not a medical eye-strain treatment claim.

## Runtime changes

- Replaced the green day/night palette across regular-app chrome: actions,
  navigation, cards, text, inputs, progress, background and studio UI.
- A deliberately subtle blue → violet → peach hero and thin ribbon mix the three
  colours. Solid action buttons remain readable; there is no neon gradient behind
  ordinary controls or animated colour cycling.
- Small peach theme/badge highlights and violet status surfaces balance the blue
  primary family rather than making every control the same colour.
- A **moon/sun button in the persistent workspace header** actually switches to
  Dark / Light. It is visible on every destination, has an accessible action name
  and current-mode description, and shrinks to an appropriately labeled vector
  icon on narrow/large-type headers.
- Settings still offers **System / Light / Dark**. Both entry points share the
  same persisted local choice. Existing choices stay respected; a deliberate
  header tap creates an explicit Light/Dark override. Restarting does not reset it.
- Dark mode uses dark slate/lavender-neutral surfaces and lower-saturation accent
  colours, rather than recolouring only the homepage or retaining green casts.
- Protected import/job runs still block appearance/locale recreation. STOP stays
  available. Already-imported private pages and other Activity state survive
  theme changes without duplicate imports, bitmap IPC or file deletion.
- Replaced hardcoded teal studio selection/resize/badge overlays with semantic
  accent tokens. This changes **UI overlays only**, not source art, text colours,
  erasure masks, rendering geometry or exported artwork. A reader can still
  explicitly choose green lettering in the colour picker.

- Fixed two theme-transition pitfalls: the appearance spinner does not restore
  stale hierarchy selection over a newly saved shortcut choice, and programmatic
  studio/library button text colours are resolved from the active themed Activity
  instead of reusing an outdated inflated colour-state list.

## Design-skill use and contrast

Uses the owner-requested, already pinned MIT **UI/UX Pro Max** skill at
`6ae86f61f520d6ca229d2f40e070410638c908c5`. Relevant local colour/dark-native/RTL
searches were run; no installer/API/model/font download. Search recommendations
were reviewed rather than blindly adopting the suggested brown/green palettes.
`design-system/koorix/MASTER.md` is now the authoritative Soft Triad native spec;
the obsolete generated forest/teal palette is not current guidance.

`tools/ui-tests/check.py` measures both real palette files. Checks include normal
text/action contrast, each hero gradient stop, violet/peach label pairs, selected
studio badge text, three distinct non-green hue families, limited saturation,
three-language string parity and the actual persistent header control.

## Preserved

Regular app only; original package and permanent signer; no Preview/KurdMan APK.
Free/no login/no Premium/no daily app quota; single GET_CONTENT `*/*` chooser;
compatible installed Gallery/MT Manager/ZArchiver/My Files sources; all 74 target
choices and Kurdish/English/Arabic UI; automatic Skill with normal Translate;
offline/jobs/STOP/recovery/projects/backups/studio/Reports/PDF; 1.7 contour-safe
complete lettering; 300-page / 300-MB import / 1-GB cache safeguards. No new
translation model, NLLB, paid API, runtime UI framework or hidden online fallback.
Phone files, historical accounts/subscriber data and backend services untouched.

## Observed regression history

- Baseline `3dea67e`, run **42916377007 / job 130179766716**: 204 cases,
  12 new failures, 0 skipped; the previous 190 and System-preference contract
  remained green. The missing header control/non-green accent tokens were
  observed, not described as already shipped.
- First implementation `73bc2f2`, run **42916664832 / job 130181470388**:
  206 cases, 4 failures, 52 partial captures; stale appearance spinner restoration
  was a real defect. The STOP fixture was also corrected to model actual busy
  state before checking visibility. Failed distribution remained blocked.
- `2c9a265`, run **42917420450 / job 130183330513**: 206/0/0, 54 captures,
  normal release compilation/public inspection passed; persisted-mode fix passed.
- Expanded final candidate `ff369b2`, run **42917598200 / job 130185599570**:
  **208/0/0**, 38 native UI cases, 34 native glyph cases, 54 captures. Native
  quick Light/Dark action outputs and actual studio button contrast inspected.
  Report blob `6948a779cdb572d660c5bf265c3fe4f03775e2ee` was fetched and hashed;
  selected PNGs were independently hash-verified and viewed.
- Final production run and actual delivery are recorded in the release receipt,
  not inferred from these candidate test counts.

## Evidence boundary

New native SDK26/34 regressions cover three non-green accents, the discoverable
header button, actual Light→Dark→Light changes, persisted theme and matching
Settings, System preference, preserved imported pages, all destinations and
protected-job STOP. They run alongside all existing 190 cases, including 34
native glyph-containment regressions. Native capture collection includes both
actual quick-theme actions as well as full-app language/theme/size fixtures.

Only synthetic native Views/XML/Skia output is collected. It is not a physical
phone, real keyboard, TalkBack or universal OEM/eye-comfort/semantic certification.
Final production evidence is recorded in `koorix/releases/v1.8.1.json` only after
verified delivery. Site source may be updated; no hosting deployment is claimed.

Stable APK after publication:
https://github.com/s1po0/Kurd-man/releases/download/koorix-v1.8.1/Koorix-v1.8.1.apk

Install over the existing **v2** Koorix. **Do not uninstall or clear storage.**
