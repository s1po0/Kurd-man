package com.kurdman.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;
import java.util.UUID;

/** Silent device entitlements, NOT a user login gate. No email/Google/phone UI. */
public final class AccountGate {
    private AccountGate() { }
    public static void enforceOnLaunch(Activity activity) { /* No login and no startup network. */ }
    public static boolean handleAuthDeepLink(Activity activity,Intent intent) { return false; }
    public static void guardJobStart(Activity activity,int pages,Runnable allowed) {
        guardJobStart(activity,pages,false,UUID.randomUUID().toString(),allowed,()->{});
    }
    public static void guardJobStart(Activity activity,int pages,boolean offline,String id,Runnable allowed,Runnable blocked) {
        if(offline) {
            try {if(SupabaseAuth.localConsume(activity,pages,id)){allowed.run();return;}}
            catch(Exception unavailable){ }
            if(!activity.isFinishing()&&!activity.isDestroyed())Toast.makeText(activity,R.string.rel_offline_allowance,Toast.LENGTH_LONG).show();
            blocked.run();return;
        }
        SupabaseAuth.consumeJob(activity,pages,id,(result,error)->{
            if(result!=null&&result.optBoolean("allowed",false)){allowed.run();return;}
            blocked.run();
            if(activity.isFinishing()||activity.isDestroyed())return;
            if(result==null)Toast.makeText(activity,R.string.rel_device_network,Toast.LENGTH_LONG).show();
            else new AlertDialog.Builder(activity).setTitle(R.string.kx_limit_title)
                    .setMessage(activity.getString(R.string.kx_limit_body,result.optInt("free_job_limit",3),result.optInt("free_page_limit",60)))
                    .setPositiveButton(R.string.kx_premium_btn,(d,w)->openAccount(activity)).setNegativeButton(R.string.kx_later,null).show();
        });
    }
    public interface Decision { void ready(boolean allowed,String reason); }
    public static void authorize(Context context,TranslationProject project,Decision callback) {
        if(isJobApproved(context,project)){callback.ready(true,"");return;}
        int pages=project.quotaPages>0?project.quotaPages:project.pages.size();
        if(project.offlineMode){try{callback.ready(SupabaseAuth.localConsume(context,pages,project.quotaJobId),"offline");}catch(Exception unavailable){callback.ready(false,"device");}return;}
        SupabaseAuth.authorizeProject(context,project,(result,error)->callback.ready(result!=null&&result.optBoolean("allowed",false),result==null?error:"limit"));
    }
    public static void prepareOfflineAllowance(Context context) { SupabaseAuth.fetchStatus(context,null); }
    public static String identity(Context context){return SupabaseAuth.identity(context);}
    public static boolean isJobApproved(Context context,TranslationProject p){return SupabaseAuth.approved(context,p);}
    public static void jobFinished(Context context,TranslationProject p){SupabaseAuth.completed(context,p);}
    public static void openAccount(Activity activity){activity.startActivity(new Intent(activity,PremiumActivity.class));}
}
