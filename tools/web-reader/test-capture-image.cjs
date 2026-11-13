'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync(__dirname + '/capture-image.js', 'utf8');
const capture = vm.runInNewContext(source, {URL, document: {baseURI: 'https://example.com/a'}, AbortController, Uint8Array, setTimeout, clearTimeout});
function response(parts, opts = {}) {
  let i = 0;
  return {ok: opts.ok !== false, type: opts.type || 'basic', headers: {get(k) { return k === 'content-type' ? (opts.mime || 'image/jpeg') : (opts.length ?? null); }},
    body: {getReader() { return {read: async () => i < parts.length ? {value: parts[i++], done: false} : {done: true}, cancel: async () => { opts.cancelled = true; }}; }}};
}
(async () => {
  let request;
  const good = response([new Uint8Array([1,2]), new Uint8Array([3])]);
  const got = await capture('/a.jpg', 3, async (url, options) => {request = {url, options}; return good;});
  assert.equal(got.bytes, 3); assert.equal(got.mime, 'image/jpeg');
  assert.equal(request.url, 'https://example.com/a.jpg');
  assert.equal(request.options.credentials, 'same-origin'); assert.equal(request.options.redirect, 'error');
  await assert.rejects(capture('http://example.com/a', 10, () => {throw new Error('network should not run');}));
  await assert.rejects(capture('https://user:pass@example.com/a', 10, () => {throw new Error('network should not run');}));
  await assert.rejects(capture('/a', 3, async () => response([new Uint8Array([1])], {length: '4'})), /large/);
  const state = {};
  await assert.rejects(capture('/a', 3, async () => response([new Uint8Array([1,2]),new Uint8Array([3,4])], state)), /large/);
  assert.equal(state.cancelled, true);
  await assert.rejects(capture('/a', 3, async () => response([new Uint8Array([1])], {mime: 'text/html'})), /unsupported/);
  await assert.rejects(capture('/a', 3, async () => response([], {type:'opaque'})), /unavailable/);
  await assert.rejects(capture('/a', 3, async () => response([])), /empty/);
  await assert.rejects(capture('/a', 0, async () => good), /invalid limit/);
  console.log('Web reader capture prototype: 10 safety cases passed.');
})().catch(e => { console.error(e); process.exit(1); });
