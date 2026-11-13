# Web reader: candidate implementation and prototype scripts

These scripts are also bundled in the current **unreleased candidate** Android
WebView screen. They are not part of the previously released APK. The Node
tests use a synthetic DOM and stream, not an Android WebView or a live site.
The integration is under development; it must not be advertised as verified.

Run locally:

```sh
node tools/web-reader/test-discover-images.cjs
node tools/web-reader/test-capture-image.cjs
node tools/web-reader/test-transport.cjs
```

## Prototype behavior

When explicitly evaluated on a page, `discover-images.js` looks at the first
4,000 top-level `img` nodes, returning at most 300 non-hidden, reasonably sized
HTTPS candidates in DOM order, with viewport visibility metadata. For unloaded
images it considers common lazy-load attributes. It does not send requests,
read cookies or alter the page. It cannot see inaccessible iframes, CSS images,
canvas drawings, protected resources or all lazy-loading schemes. Both false
positives (e.g. ads) and missed pages are expected. URL query strings can
contain sensitive tokens: never log or persist them unnecessarily.

`capture-image.js` is a separate experiment for bounded streaming through a
page's own fetch context. It uses same-origin credentials, refuses redirects,
requires a successful non-opaque CORS-readable image response, enforces both
announced and streamed byte caps, and aborts after a timeout. Its synthetic
Node tests are not a WebView implementation. Its returned typed arrays are
not directly transferable via Android's `evaluateJavascript`. `transport.js`
uses 48 KiB base64 chunks and cancellation. The candidate native receiver
passes an AbortSignal into the capture function, validates byte limits and image
bounds, and aborts on navigation. Android emulator checks are required; this is
not a general security audit. A hostile
page can tamper with JS and its output: the native host must never trust MIME,
length, origin, bytes or claimed state without independent verification. This
experiment does not validate private network addresses, prevent DNS rebinding,
or validate images.

## Required review and work before shipping

- Build a separate native, localized WebView screen with HTTPS-only address/
  search navigation, back/forward, progress, error and explicit Translate and
  Stop actions. Keep the existing single file chooser unchanged. Do not add
  an account to Koorix; some external websites may independently require one.
- Treat the web page as adversarial. Do not add a general JavaScript/native
  bridge to arbitrary pages or grant page scripts access to application APIs.
  Verify origin and navigation state across every asynchronous result, validate
  all metadata in native code, and never let the web page invoke downloads or
  translation without reader action. Origin checks alone do not make arbitrary
  website JavaScript trustworthy. Review popups, external intents, TLS errors,
  mixed content, file/content URLs and permissions against Android guidance.
- Implement a bounded retrieval strategy with explicit origin/redirect policy,
  protected-resource errors and no cookie/header leakage to unrelated domains.
  Defend against private/loopback/link-local targets, redirect and DNS rebinding;
  URL parsing alone is insufficient. Do not bypass DRM or website protections.
  Verify MIME and image decoding, constrain dimensions and bytes, cancellation,
  timeouts and cumulative storage to existing 300 pages / 300 MB import /
  1 GB cache limits. Signed URLs are sensitive and must not go into logs,
  reports, bookmarks, backups or telemetry. On failure explain what happened.
- Connect downloaded page data to the existing saved-job OCR/translation and
  rendering pipeline, maintaining ordering, automatic Skill, language and STOP
  semantics. Add an explicit private-only web-reader publication mode; current
  `ProjectResults.publish` automatically exports to public storage, and must
  not be reused unchanged for the promised no-save-until-requested behavior.
  Design recovery and migration without changing old project behavior or
  deleting existing reader data.
- Render translated pages in a native reader or safely scoped overlay with
  original/translation toggle. Prioritize visible pages while preserving chapter
  order, handle newly loaded images, deduplicate carefully, and let the reader
  explicitly export once per page without TXT files. Very long webtoon strips
  need bounded decoding and tiling without splitting dialogue incorrectly.
- Add unit, security, lifecycle and integration tests; run actual regular signed
  APK on Android with controlled authored pages, lazy images, redirects, failures,
  many pages, scrolling, cancellation and offline behavior. Test real sites only
  where permitted. Verify light/dark, ckb/en/ar, accessibility and retention.
  Distinguish synthetic emulator checks from physical/OEM device proof.
- Bump the Koorix version only when the Android feature is actually complete.
  Production delivery must use the existing permanent v2 signer, verified regular
  APK and authorized release/Telegram gates described in `AGENTS.md`.
