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
    static void addListener(Listener listener){LISTENERS.add(listener);if(JobRuntime.isRunning())listener.changed(active,JobRuntime.status(),true);}
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
                // A visible browser page can complete before the rest of the
                // chapter. Preserve chapter order and the same durable page
                // checkpoints; ordinary file/CBZ jobs retain three phases.
                List<Integer> visible=SavedJobPipeline.visibleWebOrder(project);
                if(!visible.isEmpty()) {
                    SavedJobPipeline.ocr(project,backend,progress,visible);
                    int visibleSegments=0;for(int i:visible)visibleSegments+=project.pages.get(i).segments.size();
                    if(visibleSegments>0) {
                        SavedJobPipeline.translate(project,backend,progress,visible);
                        SavedJobPipeline.render(project,backend,progress,visible);
                        status(context,context.getString(R.string.web_first_ready));
                    }
                }
                SavedJobPipeline.ocr(project,backend,progress);
                if(project.totalSegments()==0){for(TranslationProject.Page page:project.pages)if(!page.unreadable){page.ocrComplete=false;page.translationComplete=false;}project.autoResume=false;project.save();throw new NoText();}
                if(SavedJobPipeline.needsTranslation(project)) {
                    // Owner-authorized free edition: online AND offline jobs run
                    // without device registration, a paid permit or an allowance server.
                    SavedJobPipeline.translate(project,backend,progress);
                }
                JobRuntime.throwIfStopped();SavedJobPipeline.render(project,backend,progress);ProjectResults.publish(context,project);
                project.completed=true;project.autoResume=false;project.save();TranslationProjectStore.allowRecovery(context,project,false);
                status(context,context.getString(project.renderedSegments()<project.totalSegments()?R.string.rel_partial:R.string.rel_complete));
            }catch(Throwable failure){
                if(project!=null){try{
                    boolean stopped=JobRuntime.wasStopped(failure);boolean retry=!stopped&&transientFailure(failure);
                    project.autoResume=retry;project.save();TranslationProjectStore.allowRecovery(context,project,retry);
                    if(retry)context.getSharedPreferences("koorix_recovery",0).edit().putLong(project.projectDir.getName(),System.currentTimeMillis()+60000).commit();
                }catch(Exception ignored){}}
                if(JobRuntime.wasStopped(failure))status(context,context.getString(R.string.rel_stopped_saved));
                else if(failure instanceof NoText)status(context,context.getString(R.string.ks_0334));
                else{DiagnosticLog.error(context,"saved_job",safeFailure(failure),"Saved job interrupted; completed checkpoints retained.");status(context,context.getString(R.string.rel_job_failed_saved));}
            }finally{
                backend.closeOcr();backend.closeTranslator();JobRuntime.unregisterCancellation(cancel);JobRuntime.markThreadFinished();JobRuntime.endJob();
                TranslationProject done=project;String message=JobRuntime.status();MAIN.post(()->{for(Listener listener:LISTENERS)listener.changed(done,message,JobRuntime.isRunning());});KeepAliveService.jobFinished(context);
            }
        });return true;
    }
    static Throwable safeFailure(Throwable failure) {
        String code="";
        for(Throwable current=failure;current!=null;current=current.getCause()) {
            String value=current.getMessage();if(value==null)continue;
            java.util.regex.Matcher status=java.util.regex.Pattern.compile("HTTP [0-9]{3}").matcher(value);
            if(status.find()){code=" "+status.group();break;}
        }
        IOException report=new IOException("Saved job "+failure.getClass().getSimpleName()+code);
        report.setStackTrace(failure.getStackTrace());return report;
    }
    private static boolean transientFailure(Throwable failure){for(Throwable e=failure;e!=null;e=e.getCause()){if(e instanceof SocketTimeoutException||e instanceof UnknownHostException||e instanceof ConnectException)return true;String message=e.getMessage();if(message!=null&&(message.contains("HTTP 429")||message.matches(".*HTTP 5[0-9]{2}.*")))return true;}return false;}
    private static final class NoText extends IOException {NoText(){super("No readable dialogue");}}
}
