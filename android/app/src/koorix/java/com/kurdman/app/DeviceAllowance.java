package com.kurdman.app;

import org.json.JSONObject;
import java.time.*;
import java.util.Iterator;

/** No I/O or network: consumes the SAME cached server budget, not a second pool. */
final class DeviceAllowance {
    private DeviceAllowance() { }
    static boolean premium(JSONObject status,long now){try{return status!=null&&OffsetDateTime.parse(status.optString("premium_until","")).toInstant().toEpochMilli()>now;}catch(Exception invalid){return false;}}
    static JSONObject object(JSONObject root,String key) throws Exception {JSONObject value=root.optJSONObject(key);if(value==null){value=new JSONObject();root.put(key,value);}return value;}
    static boolean reserve(JSONObject state,int pages,String id,long now) throws Exception {
        if(pages<1||pages>300||id==null||id.isEmpty()||!state.optBoolean("validated",false)||state.optString("uid","").isEmpty())return false;
        JSONObject existing=object(state,"approved_jobs").optJSONObject(id);
        if(existing!=null)return existing.optInt("pages",0)==pages&&existing.optString("uid","").equals(state.optString("uid",""));
        JSONObject status=state.optJSONObject("status");if(status==null||now==Long.MAX_VALUE)return false;
        String day=Instant.ofEpochMilli(now).atOffset(ZoneOffset.UTC).toLocalDate().toString();boolean premium=premium(status,now);
        // Free grants renew from a server UTC day. A valid paid entitlement
        // remains usable offline on later days until its real expiry.
        if(!premium&&!day.equals(status.optString("usage_day","")))return false;
        int jobs=status.optInt("jobs_today",0),used=status.optInt("pages_today",0);
        JSONObject pending=object(state,"pending_offline");Iterator<String> keys=pending.keys();
        while(keys.hasNext()){JSONObject entry=pending.getJSONObject(keys.next());if(day.equals(entry.optString("day",""))){jobs++;used+=entry.optInt("pages",0);}}
        if(!premium&&(jobs>=status.optInt("free_job_limit",3)||used+pages>status.optInt("free_page_limit",60)))return false;
        pending.put(id,new JSONObject().put("pages",pages).put("day",day).put("complete",false));
        object(state,"approved_jobs").put(id,new JSONObject().put("uid",state.getString("uid")).put("pages",pages));return true;
    }
}
