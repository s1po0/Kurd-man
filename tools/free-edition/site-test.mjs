// Standard-library DOM contracts only; not a screenshot or browser/device test.
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import vm from 'node:vm';
const root=new URL('../../koorix/site/',import.meta.url);
const html=readFileSync(new URL('index.html',root),'utf8');
const script=readFileSync(new URL('app.js',root),'utf8');
assert.ok(html.includes('/koorix-v1.7.0/Koorix-v1.7.0.apk'));
for(const retired of ['id="plans"','premium-title','payment-note','admin.js'])assert.ok(!html.includes(retired));
assert.ok(!/<script\b/i.test(readFileSync(new URL('admin.html',root),'utf8')));
class Element{
 constructor(){this.children=[];this.dataset={};this.attributes={};this.listeners={};this.textContent='';}
 append(...nodes){this.children.push(...nodes);}
 replaceChildren(...nodes){this.children=nodes;}
 setAttribute(key,value){this.attributes[key]=value;}
 addEventListener(event,fn){this.listeners[event]=fn;}
}
for(const stored of ['ckb','en','ar','invalid',null,'blocked']){
 const elements=Object.fromEntries([...html.matchAll(/id="([^"]+)"/g)].map(m=>[m[1],new Element()]));
 const buttons=['ckb','en','ar'].map(lang=>{const e=new Element();e.dataset.lang=lang;return e;});
 const document={documentElement:{},getElementById:id=>{assert.ok(elements[id],`Missing DOM id ${id}`);return elements[id];},querySelectorAll:selector=>{assert.equal(selector,'[data-lang]');return buttons;},createElement:()=>new Element()};
 const localStorage={getItem:()=>{if(stored==='blocked')throw Error('storage blocked');return stored;},setItem:()=>{if(stored==='blocked')throw Error('storage blocked');}};
 vm.runInNewContext(script,{document,localStorage,fetch:()=>{throw Error('Free static page must not contact an account/payment service');}});
 assert.equal(document.documentElement.lang,['en','ar'].includes(stored)?stored:'ckb');
 for(const button of buttons){button.listeners.click();assert.equal(document.documentElement.lang,button.dataset.lang);assert.equal(document.documentElement.dir,button.dataset.lang==='en'?'ltr':'rtl');assert.equal(elements.features.children.length,7);assert.ok(elements.download.textContent.includes('1.7.0'));for(const id of ['headline','intro','free-title','free-copy','limits-title','limits-copy','privacy','update','footer'])assert.ok(elements[id].textContent.length>8,id);assert.equal(button.attributes['aria-pressed'],'true');}
}
console.log('Free static site: 3 languages × 6 preference/storage states passed, no account/payment network; DOM contracts, not browser/device evidence.');
