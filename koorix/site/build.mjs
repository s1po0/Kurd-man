// Vercel has no GitHub-provider connection. Deploy three explicit build files,
// then fetch only the public, PINNED release source; no OAuth/token is needed.
import {mkdir,writeFile,rm} from 'node:fs/promises';
import {execFileSync} from 'node:child_process';
const ref=process.env.KOORIX_SITE_SOURCE_REF||'0ceb2f0b0cca489c8d70a766a450dff04671b536';
if(!/^[0-9a-f]{40}$/.test(ref))throw new Error('Expected a pinned source commit');
const response=await fetch('https://codeload.github.com/s1po0/Kurd-man/tar.gz/'+ref);
if(!response.ok)throw new Error('Public release source unavailable');
if(Number(response.headers.get('content-length')||0)>20971520)throw new Error('Source archive exceeds build limit');
const bytes=Buffer.from(await response.arrayBuffer());
if(bytes.length>20971520)throw new Error('Source archive exceeds build limit');
const archive='.koorix-source.tar.gz';await writeFile(archive,bytes);
try{
 const entries=execFileSync('tar',['-tzf',archive],{encoding:'utf8',maxBuffer:2097152}).trim().split(String.fromCharCode(10));
 const prefix=entries[0].split('/')[0]+'/koorix/site/';
 const files=['index.html','privacy.html','site.css','app.js','admin.html','admin.js'];
 await mkdir('public',{recursive:true});
 execFileSync('tar',['-xzf',archive,'--strip-components=3','--no-same-owner','--no-same-permissions','-C','public',...files.map(file=>prefix+file)]);
 console.log('Koorix static source verified at pinned commit '+ref);
}finally{await rm(archive,{force:true});}
