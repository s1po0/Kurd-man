# Koorix 1.7.0 — contour-aware native lettering

Regular **com.koorix.app**, versionCode **15**, permanent production v2 signer.
No Preview app, account, payment, commercial quota, new translation model or API.

## What changes in the actual app

- Text fitting now validates **unclipped Android glyph pixels**, including
  connected Sorani/Arabic shaping, outlines, shadows, italic/decorations and
  rotation, against the padded detected bubble contour.
- Multi-run row masks preserve inward spikes, concave gaps and protected holes
  that the previous left/right extremes could bridge. Width, font size and a
  genuinely contained placement anchor can adapt before accepting the layout.
- The same rule is used by normal Translate, studio and edited export. Moving
  a box cannot detach it from the detected contour; rotation/outline/position
  edits invalidate cached fits. Physical clipping is a guard, not a way to cut
  off dialogue and call it fitted.
- If the complete replacement cannot fit, retain original pixels and full
  editable translation. Do not erase first and leave a blank balloon. The
  reader's explicit hidden-block force-erase control is preserved.
- Mask geometry survives saved projects and duplicates. Legacy projects can be
  retraced from source without moving a reader-edited box, also on direct export.
- Backgrounds/borders/source erasure have antialias clearance at the detected
  contour. Existing glyph-level CleanEraser remains; no white-rectangle cleaner
  or desktop neural stack was imported.

## Research and licensing

[Research report](research/COMIC_RENDERING_RESEARCH_2026-11-07.md): all eight
pagination pages including forks for **manhua**, **manga-translator** and **manhwa**, yielding
499 unique public repositories, metadata-screened; selected relevant source and
license reviews, **not** a deep audit of all 499. Primary reference:
meangrinch/MangaTranslator at `e347a09e024d11702441dd3637f044fdd8990ccb` (Apache-2.0).
Other selected designs and exact pins are documented. Koorix's Java implementation
is independent; no upstream Python/Rust/GPL TypeScript source, model weights,
paid service, new font/runtime library or installer was added.

## Preserved

- The **one** original input button opens Android's app-capable GET_CONTENT
  `*/*` chooser: compatible installed Gallery, MT Manager, ZArchiver, My Files
  and other providers remain eligible. No split Gallery/Files UI, OPEN_DOCUMENT
  replacement, photo-only picker, pinned storage root or MIME whitelist.
- Multi-select/selected-URI access and image/CBZ/ZIP validation; no broad
  storage/media-read permissions. Android/OEM/provider restrictions still apply.
- Free operation with no login/Premium/daily app quota/device registration.
  No entitlement server or historical private records contacted/deleted.
- Automatic Skill with normal Translate, all 74 target choices and Kurdish /
  English / Arabic UI, saved jobs/Reports/STOP/recovery, editable projects,
  backups, studio and optional PDF.
- **300 pages / 300 MB import / 1 GB cache** protections. No new large model,
  paid API, NLLB or hidden online fallback. Sorani OPUS meaning is unchanged.
- Install over existing **v2-signed Koorix**; do not uninstall or clear app data.
  The historical pre-v2 signature-migration limitation remains separate.

## Validation, not overclaiming

`BubbleContainmentAndroidTest` runs actual production layout/paint/erase/studio/
project code with Robolectric **native Skia graphics** on SDK26 and SDK34.
Synthetic fixtures cover all four requested shapes in connected Sorani and Latin,
concavity/inward spikes, style effects/rotation, source/art retention, black
narration, automatic rendering, cache invalidation, reopening, cancellation and
explicit force-erase. Clipped and unclipped glyph output must match.

Baseline tests failed before the fix (10 native failures, other 136 existing
executions passing). Expanded tests also detected SDK34 clip-antialias changes;
those failures blocked distribution until corrected. Final release CI/fixture
and independent APK verification evidence: `koorix/releases/v1.7.0.json`.

**No real reader page or physical device was supplied.** Synthetic/native JVM
checks do not establish universal contour detection, every custom font/OEM,
perfect erasure, ideal typography/performance or translation meaning. When no
reliable contour exists, conservative ellipse/rectangle fallback is still used.
Do not describe this release as a new/better Sorani translation model.

Stable production APK after verified publication:
https://github.com/s1po0/Kurd-man/releases/download/koorix-v1.7.0/Koorix-v1.7.0.apk
