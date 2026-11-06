package com.kurdman.app;

import android.content.Context;
import android.content.res.Configuration;
import android.os.Handler;
import android.os.Looper;
import androidx.appcompat.app.AppCompatDelegate;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/** Process-independent checkpoints + one process-wide runner, no Activity retained. */
final class SavedJobCoordinator {
    interface Listener { void changed(TranslationProject project,String status,boolean running); }
    private interface Preparation { TranslationProject prepare() throws Exception; }
    private static final ExecutorService IO=Executors.newSingleThreadExecutor();
    private static final java.util.Set<Listener> LISTENERS=new java.util.concurrent.CopyOnWriteArraySet<>();
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static volatile TranslationProject active;
    private SavedJobCoordinator() { }
    static void addListener(Listener listener){LISTENERS.add(listener);if(active!=null||JobRuntime.isRunning())listener.changed(active,JobRuntime.status(),JobRuntime.isRunning());}
    static void removeListener(Listener listener){LISTENERS.remove(listener);}
    private static Context localized(Context context){Configuration config=new Configuration(context.getResources().getConfiguration());androidx.core.os.LocaleListCompat locales=AppCompatDelegate.getApplicationLocales();if(!locales.isEmpty())config.setLocale(locales.get(0));return context.createConfigurationContext(config);}
    static boolean start(Context context,SavedJobInput input){Context app=context.getApplicationContext();return submit(app,()->input.prepare(app),true);}
    static boolean resume(Context context,TranslationProject project,boolean explicit){Context app=context.getApplicationContext();if(!TranslationProjectStore.hasSources(project)||!project.resumable||project.completed)return false;if(!explicit&&!TranslationProjectStore.canRecover(app,project))return false;return submit(app,()->{TranslationProjectStore.activate(app,project);project.autoResume=true;project.save();TranslationProjectStore.allowRecovery(app,project,true);return project;},explicit);}
    static boolean hasPending(Context context){if(!BuildConfig.PUBLIC_EDITION)return false;try{return TranslationProjectStore.canRecover(context,TranslationProjectStore.load(context));}catch(Exception invalid){return false;}}
    static boolean maybeRecover(Context context){if(JobRuntime.isRunning()||!hasPending(context))return false;TranslationProject p=TranslationProjectStore.load(context);long after=context.getSharedPreferences("koorix_recovery",0).getLong(p.projectDir.getName(),0);return after<=System.currentTimeMillis()&&resume(context,p,false);}
    private static void status(Context context,String message){JobRuntime.setStatus(message);DiagnosticLog.status(context,message);TranslationProject project=active;MAIN.post(()->{for(Listener listener:LISTENERS)listener.changed(project,message,JobRuntime.isRunning());});if(KeepAliveService.isServiceRunning())KeepAliveService.updateNotification(context);}
    private static boolean submit(Context raw,Preparation preparation,boolean explicit){
        if(!JobRuntime.tryBeginJob())return false;JobRuntime.init(raw);Context context=localized(raw);KeepAliveService.startForJob(context);
        IO.execute(()->{
            JobRuntime.markThreadStarted();TranslationProject project=null;SavedJobBackend backend=new SavedJobBackend(context,text->status(context,text));Runnable cancel=backend::cancel;JobRuntime.registerCancellation(cancel);
            try {
                status(context,context.getString(R.string.rel_prepare));project=preparation.prepare();active=project;JobRuntime.activateSavedProject(project);
                JobRuntime.throwIfStopped();
                if(!TranslationLanguages.source(project.sourceLanguage)||!TranslationLanguages.target(project.targetLanguage))throw new IOException("Unsupported saved language pair");
                if(!TranslationProjectStore.hasSources(project))throw new IOException("Saved source is missing");
                ManhwaRenderer.i18nContext=context;ManhwaRenderer.activeTargetLanguage=project.targetLanguage;
                SavedJobPipeline.Progress progress=(phase,page,total)->status(context,context.getString(phase==0?R.string.rel_ocr_progress:phase==1?R.string.rel_translate_progress:R.string.rel_render_progress,page,total));
                SavedJobPipeline.ocr(project,backend,progress);
                if(project.totalSegments()==0){for(TranslationProject.Page page:project.pages)if(!page.unreadable){page.ocrComplete=false;page.translationComplete=false;}project.autoResume=false;project.save();throw new NoText();}
                if(SavedJobPipeline.needsTranslation(project)){
                    if(project.quotaJobId.isEmpty())project.quotaJobId=UUID.randomUUID().toString();
                    if(project.quotaPages<=0)project.quotaPages=project.pages.size();project.save();
                    status(context,context.getString(R.string.rel_check_allowance));
                    CountDownLatch latch=new CountDownLatch(1);boolean[] allowed={false};String[] reason={"network"};
                    AccountGate.authorize(context,project,(ok,why)->{allowed[0]=ok;reason[0]=why;latch.countDown();});
                    if(!latch.await(90,TimeUnit.SECONDS))throw new NetworkUnavailable();JobRuntime.throwIfStopped();
                    if(!allowed[0]){project.autoResume=false;project.save();if("network".equals(reason[0]))throw new NetworkUnavailable();status(context,context.getString(project.offlineMode?R.string.rel_offline_allowance:R.string.kx_limit_body,3,60));return;}
                    project.jobOwner=AccountGate.identity(context);project.save();SavedJobPipeline.translate(project,backend,progress);
                }
                JobRuntime.throwIfStopped();SavedJobPipeline.render(project,backend,progress);ProjectResults.publish(context,project);
                project.completed=true;project.autoResume=false;project.save();TranslationProjectStore.allowRecovery(context,project,false);AccountGate.jobFinished(context,project);
                status(context,context.getString(R.string.rel_complete));
            }catch(Throwable failure){
                if(project!=null){try{
                    boolean stopped=JobRuntime.wasStopped(failure);boolean retry=!stopped&&transientFailure(failure);
                    project.autoResume=retry;project.save();TranslationProjectStore.allowRecovery(context,project,retry);
                    if(retry)context.getSharedPreferences("koorix_recovery",0).edit().putLong(project.projectDir.getName(),System.currentTimeMillis()+60000).commit();
                }catch(Exception ignored){}}
                if(JobRuntime.wasStopped(failure))status(context,context.getString(R.string.rel_stopped_saved));
                else if(failure instanceof NoText)status(context,context.getString(R.string.ks_0334));
                else{DiagnosticLog.error(context,"saved_job",failure,"Saved job interrupted; completed checkpoints retained.");status(context,context.getString(R.string.rel_job_failed_saved));}
            }finally{
                backend.closeOcr();backend.closeTranslator();JobRuntime.unregisterCancellation(cancel);JobRuntime.markThreadFinished();JobRuntime.endJob();
                TranslationProject done=project;String message=JobRuntime.status();MAIN.post(()->{for(Listener listener:LISTENERS)listener.changed(done,message,false);});KeepAliveService.jobFinished(context);
            }
        });return true;
    }
    private static boolean transientFailure(Throwable failure){for(Throwable e=failure;e!=null;e=e.getCause()){if(e instanceof NetworkUnavailable||e instanceof SocketTimeoutException||e instanceof UnknownHostException||e instanceof ConnectException)return true;String message=e.getMessage();if(message!=null&&(message.contains("HTTP 429")||message.matches(".*HTTP 5[0-9]{2}.*")))return true;}return false;}
    private static final class NetworkUnavailable extends IOException {NetworkUnavailable(){super("Network unavailable; checkpoint retained");}}
    private static final class NoText extends IOException {NoText(){super("No readable dialogue");}}
}
