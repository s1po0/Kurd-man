'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const script = fs.readFileSync(__dirname + '/discover-images.js', 'utf8');
function image(source, overrides = {}) {
  const attrs = {src: source, ...overrides.attrs};
  const rect = {left: 0, right: 500, top: 0, bottom: 800, width: 500, height: 800, ...overrides.rect};
  return {currentSrc: source, complete: true, naturalWidth: 500, naturalHeight: 800,
    ...overrides, getAttribute: name => attrs[name] || null, getBoundingClientRect: () => rect};
}
function run(images) {
  const context = {URL, document: {URL: 'https://example.com/chapter/', baseURI: 'https://example.com/chapter/', querySelectorAll: () => images},
    window: {innerHeight: 900, innerWidth: 500,
      getComputedStyle: img => img.style || {display: 'block', visibility: 'visible', opacity: '1'}}};
  return JSON.parse(JSON.stringify(vm.runInNewContext(script, context, {timeout: 2000})));
}
{
  const result = run([image('../one.jpg#view'), image('../one.jpg#other'), image('https://cdn.example.org/two?sig=1', {rect: {top: 1000, bottom: 1800}})]);
  assert.deepEqual(result.images.map(x => [x.url, x.visible, x.order]), [
    ['https://example.com/one.jpg', true, 0], ['https://cdn.example.org/two?sig=1', false, 2]]);
}
{
  const result = run([image('data:image/png;base64,aa'), image('http://example.com/page.jpg'),
    image('https://user:password@example.com/x'), image('https://example.com/hidden', {style: {display: 'none', visibility: 'visible', opacity: '1'}}),
    image('https://example.com/icon', {rect: {width: 20, height: 20}})]);
  assert.equal(result.images.length, 0);
}
{
  const lazy = image('/placeholder', {complete: false, naturalWidth: 1, naturalHeight: 1, attrs: {'data-src': '/actual.webp'}});
  assert.equal(run([lazy]).images[0].url, 'https://example.com/actual.webp');
  assert.equal(run([image('/placeholder', {complete: true, attrs: {'data-src': '/stale'}})]).images[0].url, 'https://example.com/placeholder');
}
{
  const result = run(Array.from({length: 500}, (_, i) => image(`/page-${i}.jpg`)));
  assert.equal(result.images.length, 300);
  assert.equal(result.truncated, true);
  assert.equal(run([]).truncated, false);
}
{
  assert.equal(run([image('https://example.com/' + 'x'.repeat(8200))]).images.length, 0);
  assert.equal(run([image('/transparent', {style: {display: 'block', visibility: 'visible', opacity: '0'}})]).images.length, 0);
  assert.equal(run([image('/bad', {rect: {width: NaN}})]).images.length, 0);
}
{
  const result = run(Array.from({length:300}, (_, i) => image('/'+i+'-'+'x'.repeat(4000))));
  assert.equal(result.truncated, true);
  assert.ok(result.images.reduce((n, x) => n + x.url.length, 0) <= 256 * 1024);
}
{
  const result = run([image('/same.jpg', {rect: {top:1000,bottom:1800}}), image('/same.jpg')]);
  assert.equal(result.images.length, 1);
  assert.equal(result.images[0].visible, true);
  assert.equal(result.images[0].order, 0);
}
{
  const thumb = image('/related.jpg', {naturalWidth: 120, naturalHeight: 120, rect: {width: 120, height: 120}});
  const page = image('/comic.jpg', {naturalWidth: 800, naturalHeight: 1200});
  const result = run([thumb, page]);
  assert.deepEqual(result.images.map(x => x.url), ['https://example.com/comic.jpg']);
  assert.equal(result.filteredSmall, 1);
  assert.equal(run([thumb]).images.length, 1, 'a lone small image may be a genuine panel');
  const lazy = image('/placeholder', {complete: false, naturalWidth: 1, naturalHeight: 1, attrs: {'data-src': '/lazy.jpg'}});
  assert.equal(run([page, lazy]).images.length, 2, 'unknown lazy image dimensions are not discarded');
  assert.equal(run([thumb, image('https://cdn.example.org/large.jpg')]).images.length, 2,
    'an unsupported cross-origin page must not suppress a same-origin small panel');
  const reused = image('/related.jpg', {naturalWidth: 800, naturalHeight: 1200});
  assert.equal(run([thumb, reused, page]).images.length, 2, 'a larger duplicate must not be filtered');
}
console.log('Web reader discovery prototype: 8 grouped tests passed.');
