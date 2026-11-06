// No-login Koorix device identity. Service credentials NEVER leave this function.
// Random device credentials are not personal email/phone accounts.
const cors = {"Access-Control-Allow-Origin":"*", "Access-Control-Allow-Headers":"authorization, apikey, content-type", "Access-Control-Allow-Methods":"POST, DELETE, OPTIONS", "Cache-Control":"no-store"};
const json = (value: unknown, status = 200) => new Response(JSON.stringify(value), {status,headers:{...cors,"Content-Type":"application/json"}});
const hash = async (text: string) => Array.from(new Uint8Array(await crypto.subtle.digest("SHA-256",new TextEncoder().encode(text)))).map(x=>x.toString(16).padStart(2,"0")).join("");
Deno.serve(async (request: Request) => {
  if(request.method==="OPTIONS") return new Response(null,{status:204,headers:cors});
  if(!["POST","DELETE"].includes(request.method)) return json({error:"method_not_allowed"},405);
  try {
    if(Number(request.headers.get("content-length")||0)>4096) return json({error:"invalid_request"},400);
    const reader=request.body?.getReader();let raw="",total=0;const decoder=new TextDecoder();
    if(reader){while(true){const part=await reader.read();if(part.done)break;total+=part.value.length;if(total>4096){await reader.cancel();return json({error:"invalid_request"},400);}raw+=decoder.decode(part.value,{stream:true});}raw+=decoder.decode();}
    const body=JSON.parse(raw);
    if(typeof body.device_id!=="string" || !/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(body.device_id)
      || typeof body.device_secret!=="string" || !/^[0-9a-f]{64}$/.test(body.device_secret)) return json({error:"invalid_device"},400);
    const url=Deno.env.get("SUPABASE_URL")!, key=Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
    const call=async(path:string,data:unknown,method="POST",bearer=key) => {
      const response=await fetch(url+path,{method,headers:{apikey:key,Authorization:"Bearer "+bearer,"Content-Type":"application/json"},body:method==="DELETE"?undefined:JSON.stringify(data)});
      const text=await response.text(); let result:any={}; try{result=text?JSON.parse(text):{};}catch{throw new Error("upstream_error");}
      if(!response.ok) throw new Error("upstream_"+response.status); return result;
    };
    const secretHash=await hash(body.device_secret);
    const peer=(request.headers.get("x-forwarded-for")||"unknown").split(",")[0].trim();
    const bucket=await hash(key+"|koorix-device-rate|"+peer);
    const device=await call("/rest/v1/rpc/koorix_guest_begin",{p_device_id:body.device_id,p_secret_hash:secretHash,p_bucket:bucket});
    if(request.method==="DELETE") {
      const bearer=(request.headers.get("Authorization")||"").replace(/^Bearer /,"");
      if(!bearer || body.confirm!=="DELETE_KOORIX_DEVICE") return json({error:"confirmation_required"},403);
      const response=await fetch(url+"/auth/v1/user",{headers:{apikey:key,Authorization:"Bearer "+bearer}});
      if(!response.ok) return json({error:"not_authenticated"},401);
      const user=await response.json();
      if(user.id!==device.user_id || user.email!==device.email || user.user_metadata?.koorix_device!==true) return json({error:"not_a_koorix_device"},403);
      await call("/rest/v1/rpc/koorix_delete_my_data",{},"POST",bearer);
      await call("/auth/v1/admin/users/"+encodeURIComponent(user.id),null,"DELETE");
      return json({ok:true});
    }
    if(!device.user_id) {
      const password=Array.from(crypto.getRandomValues(new Uint8Array(32))).map(x=>x.toString(16).padStart(2,"0")).join("");
      try {await call("/auth/v1/admin/users",{email:device.email,password,email_confirm:true,user_metadata:{koorix_device:true}});} catch(error) {
        // A concurrent, credential-verified bootstrap may have created this
        // exact server-generated address. The user ID is checked below.
        if(!["upstream_422","upstream_400"].includes((error as Error).message)) throw error;
      }
    }
    const link=await call("/auth/v1/admin/generate_link",{type:"magiclink",email:device.email});
    if(!link.hashed_token) throw new Error("missing_device_token");
    const session=await call("/auth/v1/verify",{type:"magiclink",token_hash:link.hashed_token});
    if(!session.user?.id || session.user.email!==device.email || session.user.user_metadata?.koorix_device!==true
      || device.user_id && device.user_id!==session.user.id) throw new Error("device_identity_mismatch");
    await call("/rest/v1/rpc/koorix_guest_finish",{p_device_id:body.device_id,p_secret_hash:secretHash,p_user_id:session.user.id});
    return json({access_token:session.access_token,refresh_token:session.refresh_token,expires_in:session.expires_in,user:{id:session.user.id}});
  } catch(error) {
    // Never log request bodies, device secrets, service credentials or sessions.
    return json({error:"device_session_unavailable"}, (error as Error).message==="upstream_429"?429:503);
  }
});
