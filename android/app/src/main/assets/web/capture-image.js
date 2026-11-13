/* Standalone experiment; not shipped in Koorix. The host must validate all
 * returned bytes, URL/destination, navigation state and aggregate limits.
 * Evaluating untrusted page JavaScript is not a trust boundary. */
(async (candidate, maxBytes, fetchImpl = fetch, timeoutMs = 15000, externalSignal = null) => {
  if (!Number.isSafeInteger(maxBytes) || maxBytes < 1 || maxBytes > 32 * 1024 * 1024) throw new Error('invalid limit');
  const url = new URL(candidate, document.baseURI);
  if (url.protocol !== 'https:' || url.username || url.password || url.href.length > 8192) throw new Error('invalid URL');
  const abort = new AbortController();
  const onExternalAbort = () => abort.abort();
  if (externalSignal) {
    if (externalSignal.aborted) abort.abort();
    else externalSignal.addEventListener('abort', onExternalAbort, {once:true});
  }
  const timer = setTimeout(() => abort.abort(), timeoutMs);
  let reader;
  try {
    // Same-origin cookies only; cross-origin requires CORS. Never follow a
    // redirect automatically: the native host must validate each destination.
    const response = await fetchImpl(url.href, {credentials: 'same-origin', redirect: 'error', signal: abort.signal});
    if (!response.ok || response.type === 'opaque' || !response.body) throw new Error('image unavailable');
    const mime = (response.headers.get('content-type') || '').split(';', 1)[0].trim().toLowerCase();
    if (!['image/jpeg', 'image/png', 'image/webp', 'image/bmp'].includes(mime)) throw new Error('unsupported image');
    const advertised = response.headers.get('content-length');
    if (advertised !== null && (!/^\d+$/.test(advertised) || Number(advertised) > maxBytes)) throw new Error('image too large');
    reader = response.body.getReader();
    const chunks = [];
    let total = 0;
    while (true) {
      const part = await reader.read();
      if (part.done) break;
      if (!(part.value instanceof Uint8Array) || total + part.value.byteLength > maxBytes) throw new Error('image too large');
      total += part.value.byteLength;
      // Copy: a stream implementation may reuse a mutable buffer.
      chunks.push(new Uint8Array(part.value));
    }
    if (!total) throw new Error('empty image');
    return {mime, bytes: total, chunks};
  } catch (failure) {
    if (reader) { try { await reader.cancel(); } catch (_) { /* best effort */ } }
    throw failure;
  } finally {
    clearTimeout(timer);
    if (externalSignal) externalSignal.removeEventListener('abort', onExternalAbort);
  }
})
