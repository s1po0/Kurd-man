// Build the reviewed checkout, without fetching stale paid-edition files or tokens.
import {mkdir,copyFile,rm} from 'node:fs/promises';
const root=new URL('./',import.meta.url);
const output=new URL('public/',root);
await rm(output,{recursive:true,force:true});
await mkdir(output,{recursive:true});
for(const name of ['index.html','privacy.html','site.css','app.js','admin.html']){
 await copyFile(new URL(name,root),new URL(name,output));
}
console.log('Koorix 1.6.1 free-edition static site built from this checkout; no deployment performed.');
