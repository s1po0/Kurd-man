# Koorix web reader — implementation scope

This describes the unreleased 1.8.4 candidate; it is not a release receipt.

The Android screen lets the reader enter an HTTPS URL or a search term, browse,
scroll, and explicitly request translation of discoverable chapter images.
The existing automatic Skill, chosen languages, online/offline route and saved
job pipeline are used, not a new model or paid translator. Images are held in a
private saved project and a native page reader allows navigation and an
original/translation toggle. Explicit export invokes the existing project
export route. It does not automatically add public Gallery images or TXT files.
Older projects keep their prior publication behavior and old files are not
removed. The browser's temporary input is removed only after source pages have
been safely copied into the new project.

## Important limitations

- Images must be discoverable as sufficiently large HTML `img` elements. Only
  HTTPS URLs on the **exact current page origin** are captured; cross-origin
  image CDNs, opaque responses, redirects, blob/data URLs, CSS backgrounds,
  canvas, protected resources and some lazy loaders are not supported. Scroll
  first if the site only adds images while scrolling. Some ads or unrelated
  same-origin images can be included. The user is told when images are skipped.
- Translation occurs after capture via the existing job. Images visible at the
  time of capture are processed first within each OCR, translation and render
  phase, while final chapter order stays unchanged. It does **not** yet replace
  images inside the website, automatically translate while scrolling, or show
  the first translated page before the whole job completes.
- Per-browser-image limit 32 MB, 300 pages, 300 MB aggregate import, and 1 GB
  app-cache budget. Decoding very long images is bounded; no promise of perfect
  layout or legibility for every webtoon. Search and browsing require network;
  translation may use an already installed offline pack.
- No JavaScript/native bridge is exposed to website scripts. `fetch` is limited
  to same-origin HTTPS with redirects refused, and no website URL or cookies
  are persisted in project metadata. Native code validates image bytes and
  bounds, treats all page-script output as untrusted, bounds IPC responses and
  aborts on navigation. These measures are not a complete browser security audit.

## Evidence boundaries

The regression suite includes native publication and URL/cache tests plus
synthetic JavaScript tests. The installed-APK driver exercises capture of a
public MDN photograph and verifies it reaches the actual OCR no-text result; a
separate authored synthetic panel exercises OCR, translation, and publication.
This does **not** constitute an end-to-end web-to-translated-comic test,
physical phone/OEM gallery verification, universal site support, or native-human
Sorani quality review. No reader page is uploaded. Actual outcomes and exact
release signatures must be recorded separately if a release is made.
