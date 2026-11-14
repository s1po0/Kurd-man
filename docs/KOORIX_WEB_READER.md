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
  first if the site only adds images while scrolling. When a same-origin large
  image is known, loaded images no larger than 256×256 pixels are omitted as
  likely thumbnails and the user is told. Single small images and unknown lazy
  images are kept. This heuristic can exclude small legitimate panels or include
  larger ads/unrelated same-origin images; it is not content understanding.
- Translation occurs after capture via the existing job. The unreleased
  candidate processes the initially visible pages through OCR, translation and
  rendering first, then exposes their durable private checkpoints in the native
  reader while the remaining pages continue. Chapter order is unchanged. A
  partially opened reader shows only finished pages and refreshes its durable
  page list as the job progresses without changing the page being read; export
  is disabled until the whole job completes. This refresh still needs installed
  Android verification. It does **not** replace images inside the website,
  translate images added while scrolling, or start before capture finishes.
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

### Installed-APK findings (unreleased)

The signed candidate in run `43456220578` passed 286 JVM tests and captured
MDN's same-origin photograph into the real OCR no-text path without a public
export. It did **not** pass the authored-comic end-to-end test: the synthetic
HTTPS image document loaded, but capture returned the generic page-read error
before any OCR result. The cause has not yet been established. Evidence index:
`e81930a26fe71170d0226eb4166b23c896d5720d`; report blob:
`3763daf62d1e107f97eb8dbf6d6ce1b60004b172`. Subsequent changes add only
non-sensitive failure categories for diagnosis. Run `43459701980` recorded a
malformed/unavailable discovery response on the image document. GitHub raw
resources commonly send a restrictive CSP sandbox, a possible explanation;
this is not proven by the diagnostic alone. The candidate now explains that the
page may be protected or unsupported rather than attempting a bypass. The
updated installed-APK driver tests this explanation and separately attempts a
generated HTTPS text image. Run `43463298444` passed both on the real signed
APK on API34: the generated text reached OCR and translation, the private
reader and original toggle worked, and no public file was created automatically.
It also passed 288 native/JVM tests. Evidence index:
`c92b3beb4f98c0c96548e740d757a947b48a7cd9`; report:
`06bc075ed77801c7cecf8becdf09001ff5933bb3`. A later run `43467020141` on
`807ea86` passed 292 native/JVM tests and the installed signed-APK single-image
browser flow, including the protected-page notice, generated text OCR/translation,
private reader, toggle and no automatic public export. Its installed-APK evidence
index is `69a0653b71f8de5e2464ee55ef085d622ae1071f` and report is
`023571d7b456bfe71803c47ead8fc48cccae57f3`. The visible-first checkpoint behavior
is covered by JVM tests but was not exercised with a multi-page live website on
Android. These synthetic results do not prove real comic translation quality,
other sites, physical phones or the unresolved browser security properties. No
1.8.4 release is authorized by this evidence.

Run `43521849925` on `9f34938` passed 294 JVM tests (including the new
partial-reader refresh regression on SDK26/34), regular APK build/signature
verification, and the existing API34 installed-APK synthetic single-image
web flows. The synthetic installed-APK report blob is
`a19753725d0e5820d23962e77c5a3e30c92a3be5`. This run did not exercise the
new refresh with multiple live web pages on Android and does not resolve any
of the security blockers below; it was a verification run, not a release.

The verification workflow also supports an explicitly opted-in public-comic
probe on the regular installed APK. It opens David Revoy's CC-BY Pepper&Carrot
Episode 1 and retains only counts/check outcomes, never comic screenshots or
dialogue. A public-site success is not a network isolation, physical-phone,
or Sorani-quality review. Ordinary CI does not require the external site.
The first opt-in attempt, run `43526283758`, passed the synthetic tests but
failed before opening the comic because the driver scrolled the wrong way to
reach the browser button. Run `43527624762` opened the public site and returned
to the app, but no readable checkpoint was observed within 420 seconds; the
cause is not established. Report blob `a38d26eb338fc3b36346353a1016200bc28bea8d`.
Run `43530039857` recorded translation followed by completion with retained
text, but its driver did not find the reader button; report blob
`634ac6bfe046ca8b33a221a3018635a9568fa4eb`. This does not establish reader
acceptance. The driver was scrolling down instead of back up towards the
reader and was adjusted.

Run `43532209834` on `d644d75` passed the opt-in public-comic probe on the
same-key regular APK under Android API34. It captured six qualifying images,
opened the private reader before completion, waited until completion with the
reader still open, navigated to another ready page and toggled the original.
The public page contains non-comic images too, so six is the captured-image
count, not a claim that all are story pages. No comic pixels or dialogue were
collected in the report. Index `6645968c0cbfad0a87c0138d03018f959fd9717b`,
report `624c39de99a69e32e4a9995c4ceec8267fde4cc8`; 294 JVM tests passed.
The probe does not establish translation accuracy, visible layout quality,
actual automatic-save behavior for this comic, security isolation or physical
phone behavior. A later experiment in run `43533759794` (`c3bb71c`) applied a
small-thumbnail heuristic and the same page reported only three captured
images, failing the probe's five-image expectation. Report blob
`57e2898fc44585170b3cf8de5adfd009c1a01af0`. A subsequent direct session
(`docs/KOORIX_WEB_DIRECT_CURRENT.md`) observed three story pages in the reader;
the earlier five-image expectation was not independently established. The
three omitted images were not individually identified and the heuristic can
omit legitimate small panels. Further verification is needed; the release
gate remains in place.

Research: GitHub raw's sandbox response policy is documented in
https://github.com/nvm-sh/nvm/issues/2392, and the test image's text/URL format
is documented in https://github.com/kingkool68/dummyimage/blob/master/index.html.
These sources explain the test setup, not verified universal website behavior.

### Security review blockers

The HTTPS/same-origin capture rule is not a network isolation boundary. An
untrusted page can run its own scripts; the browsing WebView can make requests
independent of our capture script. It has not been shown to block local/private
network destinations, DNS rebinding, all redirects, or hostile manipulation of
JavaScript globals used by discovery/capture. Native byte and image bounds are
defence in depth, not proof that a captured image came from the intended DOM
node. Cookies are not copied into project metadata, but WebView's normal cookie
store is a separate consideration. Do not treat this candidate as independently
security-reviewed or ready for release.

Chromium's Local Network Access trial explicitly stated that WebView grants that
permission unconditionally rather than prompting users; Android's separate
app-level local-network permission is not a blanket mitigation on older devices.
See https://groups.google.com/a/chromium.org/g/blink-dev/c/D4nqAa3FUN8. The
API34 emulator test does not prove isolation of private-network devices.

Do not claim a `shouldInterceptRequest` denylist solves this: Android documents
that redirect follow-ups are not all delivered to that callback, and it does not
provide request bodies for transparent replay. References:
https://developer.android.com/reference/android/webkit/WebViewClient#shouldInterceptRequest(android.webkit.WebView,%20android.webkit.WebResourceRequest)
and https://developer.android.com/privacy-and-security/risks/insecure-webview-native-bridges.
A future mitigation needs a threat model and device tests, including DNS changes,
redirects, private IPv4/IPv6, iframes, WebSockets and service workers, without
weakening TLS or bypassing site protections.
The APK workflow has a fail-closed release block for this unfinished candidate;
verification builds may run, but markers or dispatch cannot publish or deliver it
until the blocker is deliberately removed after acceptance.
