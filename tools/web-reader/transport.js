/* Experiment only. Not a native bridge and not included in the APK.
 * Page state and all returned values are untrusted. Install only in the
 * explicitly selected top-level page. Native code must validate every result.
 */
((capture, encode) => {
  const CHUNK = 48 * 1024; // multiple of 3; < Android IPC transaction limits
  const EXPIRY = 90000;
  let state = null;
  let token = 0;
  return {
    start(url, maxBytes) {
      if (state && state.status === 'working') throw new Error('already working');
      const id = ++token;
      const controller = new AbortController();
      state = {id, status: 'working', started: Date.now(), controller};
      capture(url, maxBytes, controller.signal).then(result => {
        if (!state || state.id !== id) return;
        state = {id, status: 'ready', started: Date.now(), result, chunk: 0, offset: 0};
      }, () => {
        if (state && state.id === id) state = {id, status: 'error', started: Date.now()};
      });
      return id;
    },
    poll(id) {
      if (!state || state.id !== id) return {status:'expired'};
      if (Date.now() - state.started > EXPIRY) { if (state.controller) state.controller.abort(); state = null; return {status:'expired'}; }
      if (state.status !== 'ready') return {status: state.status};
      const {result} = state;
      const parts = [];
      let remaining = CHUNK;
      while (remaining && state.chunk < result.chunks.length) {
        const part = result.chunks[state.chunk];
        const length = Math.min(part.length - state.offset, remaining);
        if (length) { parts.push(part.subarray(state.offset, state.offset + length)); state.offset += length; remaining -= length; }
        if (state.offset === part.length) { state.chunk++; state.offset = 0; }
      }
      if (remaining === CHUNK) { state = null; return {status:'done'}; }
      let binary = '';
      for (const part of parts) for (let i = 0; i < part.length; i++) binary += String.fromCharCode(part[i]);
      return {status:'chunk', data:encode(binary), mime:result.mime, bytes:result.bytes};
    },
    cancel() { token++; if (state && state.controller) state.controller.abort(); state = null; }
  };
})
