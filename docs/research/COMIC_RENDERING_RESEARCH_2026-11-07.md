# Comic translation/rendering research — 2026-11-07

## Scope and honest coverage

Owner request: investigate [meangrinch/MangaTranslator](https://github.com/meangrinch/MangaTranslator), plus **all three** topic indexes, and improve regular Koorix so complete translated dialogue remains inside circular, rectangular, triangular, quadrilateral and irregular bubbles.

The paginated GitHub REST topic searches returned:

| Topic | Repositories returned | Pages (100/page) | Incomplete results reported |
|---|---:|---:|---|
| [manhua](https://github.com/topics/manhua) | 190 | 2 | false |
| [manga-translator](https://github.com/topics/manga-translator) | 74 | 1 | false |
| [manhwa](https://github.com/topics/manhwa) | 398 | 4 | false |

**485 unique public repositories**, deduplicated from 662 topic memberships. The live HTML topic counts differed; these are the actual REST-search snapshot counts, not a claim to have source-audited 681 projects. Every returned repository, description, topic membership, metadata license and screening decision is recorded in [the complete index](github-topic-index-2026-11-07.json). Topic membership can change after this snapshot.

Screening categories: 8 selected reviews, 101 other translation/OCR/scanlation-workflow leads, 84 reader/source-extension projects, 99 downloader/page-processing tools, and 193 other entries. These categories are **metadata heuristics**, not verification of functionality, safety, ownership or per-file licenses. Many repositories offer reading/downloading rather than usable bubble rendering. Stars, promotional download pages and an Android tag do not establish usable or safe Android source.

Six functional implementations were studied in selected source files; one additional alleged Android implementation was inspected and found to be a stub; one project received README/license review only. **The other indexed projects did not receive a deep code/security audit.** No upstream installers, binaries, agent instructions, models, dependency installers or scraping tools were executed.

## Pinned source/license review

Exact pins and inspected paths are in [source-pins-2026-11-07.json](source-pins-2026-11-07.json). Licenses below refer to the inspected root license, not automatically to every dependency, font or model weight.

| Project | Review depth and license | Useful finding / decision |
|---|---|---|
| **meangrinch/MangaTranslator** — `e347a09e024d11702441dd3637f044fdd8990ccb` | Layout, styled glyph paths, drawing, renderer/mask integration, safe-zone geometry, root Apache-2.0 | Primary design reference: a padded safe mask and rasterized **shaped ink**, not just layout rectangles. Native Android implementation written independently. Do not import its Python/Skia/HarfBuzz/OpenCV/neural-segmentation/LLM stack. |
| **ogkalu2/comic-translate** — `8977b91a4f7a40c3917c5a268e9e7d78e1d818da` | Rendering/wrapping and bubble-mask utility; Apache-2.0 | Adaptive wrapping/font size and image-derived masks are useful concepts. Qt/PIL/desktop dependencies and commercial/API configurations are not a free Android translation backend. No source or dependency imported. |
| **smnexstudio/Panelglass** — `0a56ddce2b860a45ff461138f9b6f402b5d8c4a8` | TextFit, PolygonFit, BalloonFit, RegionRenderer; Apache-2.0 | Confirms Android StaticLayout can retain script shaping across reader/studio. Its polygon corner/midpoint/quarter-point checks are still sampling, not proof against every notch. Its minimum-size fallback is not adopted as permission to overflow. No Gemma/Qwen/vision model imported. |
| **Kyokkei/fukidashi-mcp** — `b600b4263d6173c1f5649f255e5163a3eb7a4af8` | Typesetting layout/mask sections, root Apache-2.0 and Comic Translate attribution file | Compact component-local masks, erosion, full glyph-band intervals and script-aware shaping are appropriate design references. No Rust/MCP/RT-DETR/LaMa/agent-host dependency added. |
| **Unheat/Kites** — `12f87ef67610e47ae516e87963c5177d8942d04e` | Contour-layout entry point/facade and README; GPL-3.0 | Profile-based contour wrapping and bounded fitting are relevant ideas. **No GPL code was copied/translated/ported**; browser/WebGPU engines and models are not imported. Do not mistake an overflow-marked last-resort layout for containment. |
| **Aeonss/BubbleBlaster** — `652bef3b58a53a0e2070431d23ddeed32c58b68b` | Cleaner source and README; MIT | OCR/inpaint workflow reference only. Its white rectangular fill option would violate this owner's art-preservation requirement. Keep Koorix's existing glyph-level CleanEraser, not the desktop EasyOCR/GPU stack or rectangular replacement. |
| **wiryaimd/manga-translator** — `9cdee59ac0a6472a5c4469145472da6667a28940` | Root tree, README, `main.java` | Android description is not evidence of an implementation: inspected `main.java` is a nonfunctional stub. API metadata says MIT, but no root LICENSE file was found at this pin. No code adopted or app installed. |
| **marco0antonio0/translate-manga-br** — `9c7e2706d894a53df1a8bb0ccd6270a8196aaad5` | **README/license only**, MIT | Editable reader/local persistence is relevant but Koorix already has editable saved projects. Runtime rendering was **not** audited. No linked APK, browser extension, API or model adopted. |

Other topic leads—including overlay translators, Go CLIs, AI-agent workflows, GPU-only scanlation stacks, reader forks, commercial/documentation-only products and unlicensed projects—remain explicitly metadata-only in the index. None was silently imported just because it had the right topic.

## What the primary upstream actually does

At the pinned MangaTranslator revision:

- `core/image/image_utils.py::_edge_distance_map` applies an image-edge-aware OpenCV distance transform; `padded_safe_area_mask` selects pixels sufficiently far from the outline.
- `core/text/text_renderer.py::_text_area` establishes a centroid-based layout box **and passes the full safe mask** to layout. The source itself notes that the box's central row/column cannot catch all inward spikes.
- `core/text/layout_engine.py::_styled_line_ink_path` shapes styled glyph outlines; `_ink_leaves_safe_area` rasterizes them, rejects raster bounds outside the mask and tests ink pixels with **alpha >= 128** against excluded mask pixels.
- `_check_collision` positions each line as drawing does. `find_optimal_layout` combines font search and narrower-width retries.
- `core/text/drawing_engine.py` separately applies outlines while painting. The examined layout helper is not evidence that every final stroke/effect/rotation is included in its ink test. Koorix therefore validates its own actual final painting, including those effects.

This is source research, not a claim that the desktop application was run on Android or that its segmentation/translation quality was reproduced.

## Actual Koorix changes

Regular **Koorix 1.7.0 / code 15 / com.koorix.app**, not a replacement app:

1. **Immutable multi-run contours.** `BubbleBoundary` retains every disjoint interval in each component row. The old single min/max pair could bridge concave gaps and protected holes. Exterior-connected notches remain excluded. Enclosed source-letter holes within the OCR footprint can be filled; unrelated holes outside that footprint stay protected. This is not semantic artwork recognition.
2. **Padded safe regions and adaptive anchors.** Integer-mask erosion reserves clearance at all rows, holes and spikes. A histogram scan finds a genuinely contained interior rectangle for optional placement when the box centre is unusable, e.g. a U-shaped bubble. The existing connected-balloon body/ownership selection remains in charge of which lobe owns a block.
3. **Full styled native ink validation.** The real Android StaticLayout and painting code draw, **without clipping**, into a bounded temporary raster. Every nonzero-alpha glyph/antialias/outline/shadow pixel—including rotation, italic overhang and decorations—must lie in the padded mask intersected with the editable box. Width/font-size/anchor retries happen before accepting a fit. No new script-by-script character painting or unshaping is introduced.
4. **No cut-off dialogue fallback.** The old editor `allowOverflow` last resort is removed. A returned fit must contain the full text; the boolean now allows editor size exploration, not overflow. A final physical clip is only defence in depth. Native tests compare clipped and **unclipped** output so a clip-only workaround cannot pass.
5. **Fit before erase everywhere.** Automatic rendering, edited exports and studio previews resolve replacements before cleaning/background painting. Rejected or cancelled fits keep the original pixels and full editable translation. The explicit hidden-block force-erase control remains available as a reader action.
6. **No containment escape through dragging/cache.** Moving a box does not detach its contour. Position, rotation, outline, gradient and other ink-affecting edits invalidate the fit; stale fits are refused. Background/border painting also respects the contour, with one-pixel clip-antialias clearance.
7. **Saved/reopened projects.** Masks are validated, serialized and shared immutably by duplicates. Legacy projects can be retraced from the original source without moving an edited box, including direct export. Present malformed/oversized masks fail closed rather than silently reverting to an unprotected rectangle.
8. **Existing safeguards kept.** Component/raster pixel budgets and interruption checks are enforced. No new translation library/model/API, NLLB, paid key, account, commercial quota or permission is introduced. The single GET_CONTENT `*/*` chooser, all target choices, three UI languages, automatic Skill, saved jobs, STOP, backups and 300-page/300-MB/1-GB protections remain.

The Java implementation is original Koorix code informed by these design studies, **not a source translation or redistribution of those upstream engines**. Their licenses/source pins are retained as provenance and future reuse boundaries.

## Reproduction and evidence boundary

The first regression commit, [`f1fd6e276c1e57420f638aac1eec8ec682c4a2e2`](https://github.com/s1po0/Kurd-man/commit/f1fd6e276c1e57420f638aac1eec8ec682c4a2e2), added tests **before the fix**. Actual Android/Skia-native Robolectric execution on SDK26 and SDK34 failed all five regressions per SDK:

- 2,484 painted pixels outside a rotated/outlined/shadowed rectangular area;
- 49 painted pixels crossing a two-row inward spike missed by the three sampled rows;
- a dragged box detached from its contour;
- a whole-dialogue overflow layout returned by the studio;
- original pixels erased before discovering that the replacement was unplaceable.

Baseline [run 42315026780](https://github.com/s1po0/Kurd-man/actions/runs/42315026780), job 128225412054: **146 executions, 10 failures, 0 skipped**; the other 136 existing executions passed. Production signing/distribution was blocked. Initial fix [run 42315865709](https://github.com/s1po0/Kurd-man/actions/runs/42315865709) passed 146/146. Expanded tests then caught a separate SDK34 background clip-antialias fringe; it was corrected rather than waived.

`BubbleContainmentAndroidTest` uses `@GraphicsMode(NATIVE)`, bundled Vazirmatn for connected Sorani, synthetic circle/rectangle/triangle/quadrilateral/concave pages and actual production render/erase/project/studio methods. Fixtures are generated by that code, not AI image generation. Only explicitly allowlisted synthetic PNGs can be published by `tools/bubble-tests/collect.py`. Final candidate/release run IDs and independent APK verification belong in `koorix/releases/v1.7.0.json` once verified.

**Not established:** real manga-page detection accuracy, all artistic contours, every custom font/device/OEM, semantic translation accuracy or optimal typography/performance for every page. No reader-supplied real page/device was available. If no reliable contour exists, the existing ellipse/rectangle fallback remains conservative, not a universal outline detector. OPUS Sorani meaning quality is unchanged. A passing native synthetic mask test is not permission to promise perfect translation, erasure or device behaviour.
