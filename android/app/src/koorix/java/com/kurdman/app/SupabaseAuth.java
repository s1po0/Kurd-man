package com.kurdman.app;

import android.content.Context;
import android.os.Handler;
import android.os.SystemClock;
import org.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** No user login: a random device credential protects free usage and Premium. */
final class SupabaseAuth {
    interface Callback { void onResult(JSONObject result,String error); }
    interface Work { JSONObject run(Context app) throws Exception; }
    private static final ExecutorService IO=Executors.newSingleThreadExecutor();
    private static final Handler MAIN=new Handler(android.os.Looper.getMainLooper());
    private static final Object STATE_LOCK=new Object();
    private SupabaseAuth() { }
    private static DeviceStateStore store(Context ctx){return new DeviceStateStore(ctx);}
    private static JSONObject object(JSONObject root,String key) throws Exception {
        JSONObject value=root.optJSONObject(key);if(value==null){value=new JSONObject();root.put(key,value);}return value;
    }
    static void perform(Context context,Work work,Callback cb) {
        Context app=context.getApplicationContext();
        IO.execute(()->{JSONObject value=null;String error=null;
            try {value=work.run(app);}catch(Exception failure){error=failure instanceof NetworkFailure?"network":"device_unavailable";}
            final JSONObject result=value;final String reason=error;
            MAIN.post(()->{if(cb!=null)cb.onResult(result,reason);});
        });
    }
    private static JSONObject request(String path,JSONObject data,String access,String method) throws Exception {
        HttpURLConnection connection=(HttpURLConnection)new URL(BuildConfig.SUPABASE_URL+path).openConnection();
        try {
            connection.setConnectTimeout(15000);connection.setReadTimeout(20000);connection.setRequestMethod(method);
            connection.setRequestProperty("apikey",BuildConfig.SUPABASE_KEY);
            connection.setRequestProperty("Authorization","Bearer "+(access==null?BuildConfig.SUPABASE_KEY:access));
            connection.setRequestProperty("Content-Type","application/json");
            if(!"GET".equals(method)){connection.setDoOutput(true);try(OutputStream out=connection.getOutputStream()){out.write(data.toString().getBytes(StandardCharsets.UTF_8));}}
            int status=connection.getResponseCode();
            InputStream stream=status<400?connection.getInputStream():connection.getErrorStream();
            String text="";
            if(stream!=null)try(InputStream in=stream;ByteArrayOutputStream out=new ByteArrayOutputStream()){
                byte[] buffer=new byte[4096];int n;while((n=in.read(buffer))!=-1){if(out.size()+n>1024*1024)throw new IOException("Response too large");out.write(buffer,0,n);}
                text=new String(out.toByteArray(),StandardCharsets.UTF_8);
            }
            if(status<200||status>=300)throw new ApiFailure(status);
            if(text.isEmpty()||"null".equals(text))return new JSONObject();
            if(text.trim().startsWith("["))return new JSONObject().put("items",new org.json.JSONArray(text));
            return new JSONObject(text);
        }catch(ApiFailure failure){throw failure;}
        catch(IOException failure){throw new NetworkFailure();}
        finally{connection.disconnect();}
    }
    private static final class ApiFailure extends IOException { final int status;ApiFailure(int status){super("Service request rejected");this.status=status;} }
    private static final class NetworkFailure extends IOException {NetworkFailure(){super("Network unavailable");}}
    private static String randomSecret(){byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();}
    private static void saveSession(Context ctx,JSONObject session) throws Exception {
        synchronized(STATE_LOCK){JSONObject state=store(ctx).read();JSONObject user=session.optJSONObject("user");
            if(user==null||user.optString("id","").isEmpty())throw new IOException("Missing device identity");
            if (!state.optString("uid","").isEmpty() && !state.optString("uid","").equals(user.getString("id"))) {
                state.remove("status");state.remove("approved_jobs");state.remove("pending_offline");state.remove("pending_complete");
            }
            state.put("access_token",session.getString("access_token"));state.put("refresh_token",session.getString("refresh_token"));
            state.put("expires_at_ms",System.currentTimeMillis()+session.optLong("expires_in",3600)*1000L);
            state.put("uid",user.getString("id"));state.put("validated",true);store(ctx).write(state);
        }
    }
    private static String session(Context ctx) throws Exception {
        JSONObject state; synchronized(STATE_LOCK){state=store(ctx).read();}
        String access=state.optString("access_token","");
        if(!access.isEmpty()&&state.optLong("expires_at_ms",0)>System.currentTimeMillis()+60000){
            if(state.optString("uid","").isEmpty()) {
                JSONObject user=request("/auth/v1/user",new JSONObject(),access,"GET");
                synchronized(STATE_LOCK){state=store(ctx).read();state.put("uid",user.getString("id"));state.put("validated",true);store(ctx).write(state);}
            }
            return access;
        }
        String refresh=state.optString("refresh_token","");
        if(!refresh.isEmpty())try {
            JSONObject renewed=request("/auth/v1/token?grant_type=refresh_token",new JSONObject().put("refresh_token",refresh),null,"POST");
            saveSession(ctx,renewed);return renewed.getString("access_token");
        }catch(ApiFailure failure){if(failure.status!=400&&failure.status!=401&&failure.status!=403)throw failure;}
        JSONObject device=new JSONObject();
        synchronized(STATE_LOCK){state=store(ctx).read();
            if(state.optString("device_id","").isEmpty()){state.put("device_id",UUID.randomUUID().toString());state.put("device_secret",randomSecret());store(ctx).write(state);}
            device.put("device_id",state.getString("device_id"));device.put("device_secret",state.getString("device_secret"));
        }
        JSONObject created=request("/functions/v1/koorix-device-session",device,null,"POST");saveSession(ctx,created);return created.getString("access_token");
    }
    private static JSONObject rpc(Context ctx,String name,JSONObject params) throws Exception {
        String token=session(ctx);return request("/rest/v1/rpc/"+name,params,token,"POST");
    }
    private static void sync(Context ctx) throws Exception {
        List<String> ids=new ArrayList<>();
        synchronized(STATE_LOCK){JSONObject pending=object(store(ctx).read(),"pending_offline");Iterator<String> keys=pending.keys();while(keys.hasNext())ids.add(keys.next());}
        for(String id:ids){JSONObject record;synchronized(STATE_LOCK){record=object(store(ctx).read(),"pending_offline").getJSONObject(id);}
            rpc(ctx,"koorix_sync_offline_job",new JSONObject().put("p_job_id",id).put("p_pages",record.getInt("pages")).put("p_day",record.getString("day")).put("p_completed",record.optBoolean("complete",false)));
            synchronized(STATE_LOCK){JSONObject state=store(ctx).read();object(state,"pending_offline").remove(id);store(ctx).write(state);}
        }
        ids.clear();synchronized(STATE_LOCK){JSONObject pending=object(store(ctx).read(),"pending_complete");Iterator<String> keys=pending.keys();while(keys.hasNext())ids.add(keys.next());}
        for(String id:ids){rpc(ctx,"koorix_finish_job",new JSONObject().put("p_job_id",id));synchronized(STATE_LOCK){JSONObject state=store(ctx).read();object(state,"pending_complete").remove(id);store(ctx).write(state);}}
    }
    private static void cache(Context ctx,JSONObject status) throws Exception {
        synchronized(STATE_LOCK){JSONObject state=store(ctx).read();state.put("status",status);state.put("status_elapsed",SystemClock.elapsedRealtime());state.put("status_wall",System.currentTimeMillis());store(ctx).write(state);}
    }
    static void fetchStatus(Context ctx,Callback cb){perform(ctx,app->{session(app);sync(app);JSONObject status=rpc(app,"koorix_status_v2",new JSONObject());cache(app,status);return status;},cb);}
    static void consumeJob(Context ctx,int pages,String id,Callback cb){perform(ctx,app->{session(app);sync(app);JSONObject result=rpc(app,"koorix_authorize_job",new JSONObject().put("p_job_id",id).put("p_pages",pages));if(result.has("usage_day"))cache(app,result);if(result.optBoolean("allowed",false))approve(app,id,pages);return result;},cb);}
    static void authorizeProject(Context ctx,TranslationProject project,Callback cb){perform(ctx,app->{
        session(app);String owner=identity(app);
        if(!project.jobOwner.isEmpty()&&!project.jobOwner.equals(owner)){
            project.quotaJobId=UUID.randomUUID().toString();project.jobOwner="";project.save();
        }
        sync(app);JSONObject result=rpc(app,"koorix_authorize_job",new JSONObject().put("p_job_id",project.quotaJobId).put("p_pages",project.quotaPages));
        if(result.has("usage_day"))cache(app,result);if(result.optBoolean("allowed",false))approve(app,project.quotaJobId,project.quotaPages);return result;
    },cb);}
    private static void approve(Context ctx,String id,int pages) throws Exception {
        synchronized(STATE_LOCK){JSONObject state=store(ctx).read();JSONObject approved=object(state,"approved_jobs");
            approved.put(id,new JSONObject().put("uid",state.getString("uid")).put("pages",pages));
            while(approved.length()>100){String oldest=approved.keys().next();if(oldest.equals(id))break;approved.remove(oldest);}
            store(ctx).write(state);
        }
    }
    static String identity(Context ctx){try{synchronized(STATE_LOCK){return store(ctx).read().optString("uid","");}}catch(Exception unavailable){return "";}}
    static boolean approved(Context ctx,TranslationProject p){try{synchronized(STATE_LOCK){JSONObject state=store(ctx).read();JSONObject permit=object(state,"approved_jobs").optJSONObject(p.quotaJobId);return permit!=null&&state.optBoolean("validated",false)&&!p.jobOwner.isEmpty()&&p.jobOwner.equals(state.optString("uid",""))&&p.jobOwner.equals(permit.optString("uid",""))&&permit.optInt("pages",0)==(p.quotaPages>0?p.quotaPages:p.pages.size());}}catch(Exception unavailable){return false;}}
    static boolean cachedPremium(Context ctx){try{synchronized(STATE_LOCK){JSONObject state=store(ctx).read();JSONObject status=state.optJSONObject("status");return state.optBoolean("validated",false)&&premium(status,now(state));}}catch(Exception unavailable){return false;}}
    static boolean premium(JSONObject status,long now){return DeviceAllowance.premium(status,now);}
    private static long now(JSONObject state){long wall=System.currentTimeMillis(), elapsed=SystemClock.elapsedRealtime(), oldElapsed=state.optLong("status_elapsed",-1);JSONObject status=state.optJSONObject("status");long server=status==null?0:status.optLong("server_ms",0);long oldWall=state.optLong("status_wall",wall);if(wall<oldWall-120000)return Long.MAX_VALUE;return Math.max(wall,server+Math.max(0,elapsed-oldElapsed));}
    static boolean localConsume(Context ctx,int pages,String id) throws Exception {
        synchronized(STATE_LOCK){JSONObject state=store(ctx).read();boolean allowed=DeviceAllowance.reserve(state,pages,id,now(state));if(allowed)store(ctx).write(state);return allowed;}
    }
    static void completed(Context ctx,TranslationProject p){try{synchronized(STATE_LOCK){JSONObject state=store(ctx).read();JSONObject pending=object(state,"pending_offline").optJSONObject(p.quotaJobId);if(pending!=null)pending.put("complete",true);else object(state,"pending_complete").put(p.quotaJobId,true);object(state,"approved_jobs").remove(p.quotaJobId);store(ctx).write(state);}if(!p.offlineMode)perform(ctx,app->{sync(app);return new JSONObject();},null);}catch(Exception ignored){/* Local result remains available; sync is retried on the next online action. */}}
    static void redeemCode(Context ctx,String code,Callback cb){perform(ctx,app->{JSONObject result=rpc(app,"koorix_redeem_code",new JSONObject().put("p_code",code));JSONObject status=rpc(app,"koorix_status_v2",new JSONObject());cache(app,status);return result;},cb);}
    static void requestOrder(Context ctx,int months,Callback cb){perform(ctx,app->rpc(app,"koorix_request_order",new JSONObject().put("p_months",months)),cb);}
    static void deleteDeviceData(Context ctx,Callback cb){perform(ctx,app->{String token=session(app);JSONObject state; synchronized(STATE_LOCK){state=store(app).read();}
        JSONObject result;
        if(state.has("device_id"))result=request("/functions/v1/koorix-device-session",new JSONObject().put("device_id",state.getString("device_id")).put("device_secret",state.getString("device_secret")).put("confirm","DELETE_KOORIX_DEVICE"),token,"DELETE");
        else result=rpc(app,"koorix_delete_my_data",new JSONObject());
        synchronized(STATE_LOCK){store(app).write(new JSONObject());}return result;
    },cb);}
}
