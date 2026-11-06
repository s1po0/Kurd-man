'use strict';
const BASE='https://cbkddsztcivllpwzflkx.supabase.co';
const PUBLIC_KEY='sb_publishable_vVtVNjubYllxuSnfyzcolA_qk034IwO';
const $=id=>document.getElementById(id);
let token='';
const say=text=>{$('owner-message').textContent=text;};
function base64(bytes){return btoa(String.fromCharCode(...bytes)).replace(/\+/g,'-').replace(/\//g,'_').replace(/=+$/,'');}
async function request(path,data,access=token){const headers={apikey:PUBLIC_KEY,'Content-Type':'application/json'};if(access)headers.Authorization='Bearer '+access;const response=await fetch(BASE+path,{method:'POST',headers,body:JSON.stringify(data)});const value=await response.json().catch(()=>null);if(!response.ok)throw new Error(response.status===429?'Please wait before trying again.':'Request rejected. Check your publisher session or request a fresh sign-in link.');return value;}
async function rpc(name,data={}){return request('/rest/v1/rpc/'+name,data);}
async function authorize(){if(!token)return false;try{if(await rpc('koorix_is_admin')!==true)throw new Error('Only the configured publisher with a recent email-verified session can use this console.');$('owner-tools').hidden=false;$('sign-in').hidden=true;$('sign-out').hidden=false;say('Publisher verified. No reader login is required in Koorix.');return true;}catch(error){$('owner-tools').hidden=true;say(error.message);return false;}}
$('sign-in').addEventListener('submit',async event=>{event.preventDefault();const submit=event.submitter;submit.disabled=true;try{
 const verifier=base64(crypto.getRandomValues(new Uint8Array(48)));const challenge=base64(new Uint8Array(await crypto.subtle.digest('SHA-256',new TextEncoder().encode(verifier))));
 localStorage.setItem('koorix-owner-pkce',JSON.stringify({verifier,expires:Date.now()+600000}));
 await request('/auth/v1/otp?redirect_to='+encodeURIComponent(location.origin+location.pathname),{email:$('owner-email').value.trim(),create_user:true,code_challenge:challenge,code_challenge_method:'s256'},'');
 say('Open the secure sign-in link from your publisher mailbox in this browser. Supabase’s built-in email service has a small hourly limit; readers never use it.');
 }catch(error){say(error.message);}finally{submit.disabled=false;}});
$('sign-out').addEventListener('click',async()=>{try{await request('/auth/v1/logout?scope=local',{});}catch{}token='';sessionStorage.removeItem('koorix-owner-session');$('owner-tools').hidden=true;$('sign-in').hidden=false;$('sign-out').hidden=true;say('Signed out of the publisher console.');});
$('create-code').addEventListener('click',async()=>{try{if(!confirm('Create a private single-use activation code?'))return;const result=await rpc('koorix_admin_create_code',{p_months:Number($('code-months').value)});$('new-code').textContent=result.code+'\n'+result.days+' days';}catch(error){say(error.message);}});
async function orders(){try{const rows=await rpc('koorix_admin_orders');$('orders').replaceChildren(...rows.map(row=>{const node=document.createElement('article');node.className='order';const text=document.createElement('p');text.textContent=[row.months+' month(s)',new Intl.NumberFormat('en').format(row.price_iqd)+' IQD',row.status,row.created_at,'Order: '+row.id,'Device profile: '+row.user_id].join(' · ');node.append(text);if(row.status==='pending'){const approve=document.createElement('button');approve.textContent='I verified payment — activate Premium';approve.addEventListener('click',async()=>{if(!confirm('I have independently verified the payment for this exact order. Activate Premium?'))return;approve.disabled=true;try{await rpc('koorix_admin_approve_order',{p_order_id:row.id});await orders();}catch(error){say(error.message);approve.disabled=false;}});node.append(approve);}return node;}));}catch(error){say(error.message);}}
$('refresh-orders').addEventListener('click',orders);
(async()=>{try{
 const code=new URL(location.href).searchParams.get('code');
 if(code){history.replaceState(null,'',location.pathname);const pending=JSON.parse(localStorage.getItem('koorix-owner-pkce')||'null');if(!pending||pending.expires<Date.now()||code.length>512)throw new Error('Sign-in request expired. Request a new link from this page.');const session=await request('/auth/v1/token?grant_type=pkce',{auth_code:code,code_verifier:pending.verifier},'');localStorage.removeItem('koorix-owner-pkce');token=session.access_token;sessionStorage.setItem('koorix-owner-session',JSON.stringify({token,expires:Date.now()+Math.min(3600,session.expires_in||3600)*1000}));}
 else{const cached=JSON.parse(sessionStorage.getItem('koorix-owner-session')||'null');if(cached&&cached.expires>Date.now())token=cached.token;}
 if(await authorize())await orders();
 }catch(error){say(error.message);}})();
