/*
 * Unreleased candidate — copied into the Android web reader assets.
 * Evaluated explicitly by the host on the top-level page after a reader action.
 * It does not contact the network, read cookies or alter the document.
 * The host must treat the whole result (including all URLs) as untrusted data.
 */
(() => {
  'use strict';
  const MAX_IMAGES = 300;
  const MAX_SCANNED = 4000;
  const MAX_URL = 8192;
  // Keep evaluateJavascript responses safely bounded even with hostile markup.
  const MAX_RESULT_URL_CHARS = 256 * 1024;
  let urlChars = 0, hitBudget = false;
  const images = document.querySelectorAll('img');
  // Use the document's own URL, not <base>, when deciding which images can
  // influence filtering. Native code separately enforces the same origin.
  const pageOrigin = new URL(document.URL).origin;
  const results = [];
  const seen = new Map();
  const viewportHeight = Math.max(0, Number(window.innerHeight) || 0);
  const viewportWidth = Math.max(0, Number(window.innerWidth) || 0);
  const scanCount = Math.min(images.length, MAX_SCANNED);

  for (let i = 0; i < scanCount && results.length < MAX_IMAGES; i++) {
    const image = images[i];
    const box = image.getBoundingClientRect();
    if (!box || !Number.isFinite(box.width) || !Number.isFinite(box.height)) continue;
    if (box.width < 120 || box.height < 120) continue;
    const style = window.getComputedStyle(image);
    if (style.display === 'none' || style.visibility === 'hidden' || Number(style.opacity) === 0) continue;

    // When a lazy loader has not loaded a real image, prefer its deferred URL
    // over a one-pixel currentSrc. srcset resolution is left to the browser.
    const loaded = image.complete && image.naturalWidth >= 120 && image.naturalHeight >= 120;
    const source = loaded
      ? (image.currentSrc || image.getAttribute('src'))
      : (image.getAttribute('data-src') || image.getAttribute('data-original') || image.currentSrc || image.getAttribute('src'));
    if (!source || source.length > MAX_URL) continue;
    let url;
    try { url = new URL(source, document.baseURI); } catch (_) { continue; }
    if (url.protocol !== 'https:' || url.username || url.password) continue;
    // Fragments don't select a different HTTP image. Query strings are kept:
    // many sites use them to distinguish pages or authorize an image.
    url.hash = '';
    const absolute = url.href;
    if (absolute.length > MAX_URL) continue;
    const visible = box.bottom > 0 && box.top < viewportHeight && box.right > 0 && box.left < viewportWidth;
    const naturalWidth = Number(image.naturalWidth) || 0;
    const naturalHeight = Number(image.naturalHeight) || 0;
    // Only classify dimensions the browser actually knows; a lazy placeholder
    // does not describe its deferred comic page.
    const smallKnown = loaded && naturalWidth <= 256 && naturalHeight <= 256;
    const largeKnown = url.origin === pageOrigin && loaded && naturalWidth >= 480 && naturalHeight >= 480;
    if (seen.has(absolute)) {
      const prior = results[seen.get(absolute)];
      if (visible) prior.visible = true;
      prior.smallKnown = prior.smallKnown && smallKnown;
      prior.largeKnown = prior.largeKnown || largeKnown;
      continue;
    }
    if (urlChars + absolute.length > MAX_RESULT_URL_CHARS) { hitBudget = true; break; }
    urlChars += absolute.length;
    seen.set(absolute, results.length);
    results.push({url: absolute, order: i, visible,
      width: Math.round(box.width), height: Math.round(box.height), smallKnown, largeKnown});
  }
  // A lone small image can be a real panel, so keep it. In a mixed page with
  // an established large page, exclude known tiny thumbnail/icon images and
  // report the omission. Unknown lazy images are never discarded on this rule.
  const hasLarge = results.some(image => image.largeKnown);
  const kept = hasLarge ? results.filter(image => !image.smallKnown) : results;
  const filteredSmall = results.length - kept.length;
  for (const image of kept) { delete image.smallKnown; delete image.largeKnown; }
  return {images: kept, filteredSmall, scanned: scanCount, truncated: hitBudget || images.length > scanCount || results.length === MAX_IMAGES};
})()
