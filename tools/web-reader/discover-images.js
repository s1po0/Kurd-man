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
    if (seen.has(absolute)) { if (visible) results[seen.get(absolute)].visible = true; continue; }
    if (urlChars + absolute.length > MAX_RESULT_URL_CHARS) { hitBudget = true; break; }
    urlChars += absolute.length;
    seen.set(absolute, results.length);
    results.push({url: absolute, order: i, visible,
      width: Math.round(box.width), height: Math.round(box.height)});
  }
  return {images: results, scanned: scanCount, truncated: hitBudget || images.length > scanCount || results.length === MAX_IMAGES};
})()
