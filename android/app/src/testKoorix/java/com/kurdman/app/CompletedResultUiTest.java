package com.kurdman.app;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;
import android.app.Application;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.Looper;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.util.ReflectionHelpers;
import java.io.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/** Regressions from actual 1.8.2 UI observations. Not device/meaning proof. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk={26,34},application=Application.class,qualifiers="en-rUS-w393dp-h852dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class CompletedResultUiTest {
    Application app;
    ActivityController<MainActivity> main;
    @Before public void setup() {
        app=RuntimeEnvironment.getApplication();
        JobRuntime.init(app);JobRuntime.beginJob();JobRuntime.endJob();Thread.interrupted();
        ReflectionHelpers.setStaticField(KeepAliveService.class,"serviceRunning",false);
        app.getSharedPreferences("kurdman_settings",0).edit().clear().commit();
        app.getSharedPreferences("koorix_projects",0).edit().clear().commit();
        TranslationProjectStore.setCurrent(null);
        TranslationProjectStore.deleteRecursive(TranslationProjectStore.root(app));
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en"));
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
        main=Robolectric.buildActivity(MainActivity.class).setup();
    }
    @After public void finish() {
        if(main!=null)main.pause().stop().destroy();
        ReflectionHelpers.setStaticField(KeepAliveService.class,"serviceRunning",false);
        JobRuntime.endJob();Thread.interrupted();
    }
    TranslationProject project() throws Exception {
        TranslationProject p=TranslationProjectStore.createNew(app,false,"Synthetic completion","synthetic-completion","en");
        p.targetLanguage="ckb";p.completed=true;p.exportsComplete=true;
        TranslationProject.Page page=new TranslationProject.Page("pages/0001.png",".png");
        Bitmap image=Bitmap.createBitmap(420,560,Bitmap.Config.ARGB_8888);image.eraseColor(Color.WHITE);
        try(OutputStream out=new FileOutputStream(page.sourceFile(p.projectDir))){assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,out));}
        // Keep the actual production paths, without invoking OCR/translation in a UI contract.
        p.pages.add(page);
        File rendered=SavedJobPipeline.renderedPage(p,0);assertTrue(rendered.getParentFile().isDirectory()||rendered.getParentFile().mkdirs());
        SavedJobPipeline.writeJpeg(rendered,image);image.recycle();
        page.width=420;page.height=560;page.ocrComplete=true;page.translationComplete=true;page.renderComplete=true;page.layoutPrepared=true;
        Segment s=new Segment("Stay here.",new Rect(80,100,330,170),1);s.ckb="لێرە چاوەڕێ بکە.";s.rendered=true;
        s.renderBox=new Rect(60,70,360,220);s.eraseBox=new Rect(s.renderBox);page.segments.add(s);
        File product=ProjectResults.result(p);assertTrue(product.getParentFile().isDirectory()||product.getParentFile().mkdirs());
        ProjectResults.copy(rendered,product);p.outputBinding=ProjectResults.binding(p);p.save();return p;
    }
    @Test public void savedCompletionRemovesTheActualEmptyIconAndHintWithoutAStudioRoundTrip() throws Exception {
        TranslationProject p=project();MainActivity a=main.get();
        SavedJobCoordinator.Listener listener=ReflectionHelpers.getField(a,"savedJobListener");
        listener.changed(p,"Complete synthetic project",false);
        assertNotNull(((ImageView)a.findViewById(R.id.previewImage)).getDrawable());
        assertEquals("The placeholder must not cover a completed translated image",View.GONE,a.findViewById(R.id.resultEmptyIcon).getVisibility());
        assertEquals(View.GONE,a.findViewById(R.id.resultEmptyHint).getVisibility());
    }
    @Test public void privateWebResultExposesReaderWithoutPublishing() throws Exception {
        TranslationProject p=project();p.privateResults=true;p.save();MainActivity a=main.get();
        SavedJobCoordinator.Listener listener=ReflectionHelpers.getField(a,"savedJobListener");listener.changed(p,"Complete",false);
        View read=a.findViewById(R.id.readWebResultButton);assertEquals(View.VISIBLE,read.getVisibility());assertTrue(read.isEnabled());
        p.privateResults=false;p.save();ReflectionHelpers.callInstanceMethod(a,"updateButtons",ReflectionHelpers.ClassParameter.from(boolean.class,false));
        assertEquals(View.GONE,read.getVisibility());
    }
    @Test public void privateWebReaderNavigatesTogglesAndOnlyExportsOnExplicitAction() throws Exception {
        TranslationProject p=project();p.privateResults=true;
        TranslationProject.Page page=new TranslationProject.Page("pages/0002.png",".png");
        ProjectResults.copy(p.pages.get(0).sourceFile(p.projectDir),page.sourceFile(p.projectDir));
        p.pages.add(page);ProjectResults.copy(SavedJobPipeline.renderedPage(p,0),SavedJobPipeline.renderedPage(p,1));page.renderComplete=true;p.save();
        Intent intent=new Intent(app,WebResultActivity.class).putExtra(WebResultActivity.EXTRA_PROJECT,p.projectDir.getName());
        ActivityController<WebResultActivity> reader=Robolectric.buildActivity(WebResultActivity.class,intent).setup();
        try {
            ExecutorService worker=ReflectionHelpers.getField(reader.get(),"decoder");worker.submit(()->{}).get(15,TimeUnit.SECONDS);shadowOf(Looper.getMainLooper()).idle();
            assertNotNull(((ImageView)reader.get().findViewById(R.id.webResultImage)).getDrawable());
            assertEquals(reader.get().getString(R.string.web_next),reader.get().findViewById(R.id.webResultNext).getContentDescription());
            reader.get().findViewById(R.id.webResultNext).performClick();
            reader.get().findViewById(R.id.webResultToggle).performClick();
            worker.submit(()->{}).get(15,TimeUnit.SECONDS);shadowOf(Looper.getMainLooper()).idle();
            assertTrue(((TextView)reader.get().findViewById(R.id.webResultPageLabel)).getText().toString().contains("2 / 2"));
            assertEquals(reader.get().getString(R.string.web_original),reader.get().findViewById(R.id.webResultImage).getContentDescription());
            reader.get().findViewById(R.id.webResultExport).performClick();
            assertEquals(android.app.Activity.RESULT_OK,shadowOf(reader.get()).getResultCode());
            assertTrue(shadowOf(reader.get()).getResultIntent().getBooleanExtra(EditorActivity.EXTRA_EXPORT,false));
        } finally {reader.pause().stop().destroy();}
    }
    @Test public void unfinishedPrivateReaderOnlyShowsDurablePagesAndCannotExport() throws Exception {
        TranslationProject p=project();p.privateResults=true;p.completed=false;p.exportsComplete=false;
        TranslationProject.Page pending=new TranslationProject.Page("pages/0002.png",".png");
        ProjectResults.copy(p.pages.get(0).sourceFile(p.projectDir),pending.sourceFile(p.projectDir));p.pages.add(pending);p.save();
        assertTrue(JobRuntime.tryBeginJob());
        SavedJobCoordinator.Listener listener=ReflectionHelpers.getField(main.get(),"savedJobListener");listener.changed(p,"Working",true);
        View read=main.get().findViewById(R.id.readWebResultButton);assertEquals(View.VISIBLE,read.getVisibility());assertTrue(read.isEnabled());
        assertEquals(java.util.Arrays.asList(0),WebResultActivity.readyPages(p));
        TranslationProject snapshot=WebResultActivity.snapshot(p);assertNotSame(p,snapshot);
        p.pages.get(0).renderComplete=false;
        assertEquals(java.util.Arrays.asList(0),WebResultActivity.readyPages(WebResultActivity.snapshot(p)));
        p.pages.get(0).renderComplete=true;
        Intent intent=new Intent(app,WebResultActivity.class).putExtra(WebResultActivity.EXTRA_PROJECT,p.projectDir.getName());
        ActivityController<WebResultActivity> reader=Robolectric.buildActivity(WebResultActivity.class,intent).setup();
        try {
            assertFalse(reader.get().findViewById(R.id.webResultNext).isEnabled());
            assertFalse(reader.get().findViewById(R.id.webResultExport).isEnabled());
        } finally {reader.pause().stop().destroy();JobRuntime.endJob();}
    }
    private void drainReaderRefresh(WebResultActivity reader) throws Exception {
        ExecutorService worker=ReflectionHelpers.getField(reader,"refresher");
        // A progress event can coalesce with the resume refresh and schedule a second read.
        for(int i=0;i<3;i++){worker.submit(()->{}).get(15,TimeUnit.SECONDS);shadowOf(Looper.getMainLooper()).idle();}
    }
    @Test public void partialReaderUpdatesWithoutLosingTheCurrentChapterPage() throws Exception {
        TranslationProject p=project();p.privateResults=true;p.completed=false;p.exportsComplete=false;
        TranslationProject.Page second=new TranslationProject.Page("pages/0002.png",".png");
        TranslationProject.Page third=new TranslationProject.Page("pages/0003.png",".png");
        ProjectResults.copy(p.pages.get(0).sourceFile(p.projectDir),second.sourceFile(p.projectDir));
        ProjectResults.copy(p.pages.get(0).sourceFile(p.projectDir),third.sourceFile(p.projectDir));
        p.pages.add(second);p.pages.add(third);
        ProjectResults.copy(SavedJobPipeline.renderedPage(p,0),SavedJobPipeline.renderedPage(p,2));third.renderComplete=true;
        p.pages.get(0).renderComplete=false;p.save();assertTrue(JobRuntime.tryBeginJob());
        ActivityController<WebResultActivity> reader=Robolectric.buildActivity(WebResultActivity.class,
                new Intent(app,WebResultActivity.class).putExtra(WebResultActivity.EXTRA_PROJECT,p.projectDir.getName())).setup();
        try {
            WebResultActivity activity=reader.get();drainReaderRefresh(activity);
            assertTrue(((TextView)activity.findViewById(R.id.webResultPageLabel)).getText().toString().contains("3 / 3"));
            activity.findViewById(R.id.webResultToggle).performClick();
            p.pages.get(0).renderComplete=true;p.save();
            SavedJobCoordinator.Listener listener=ReflectionHelpers.getField(activity,"jobListener");listener.changed(p,"Working",true);drainReaderRefresh(activity);
            assertTrue("A newly completed earlier page must not change the page being read",((TextView)activity.findViewById(R.id.webResultPageLabel)).getText().toString().contains("3 / 3"));
            assertFalse(activity.findViewById(R.id.webResultExport).isEnabled());
            activity.findViewById(R.id.webResultNext).performClick();
            assertTrue(((TextView)activity.findViewById(R.id.webResultPageLabel)).getText().toString().contains("3 / 3"));
            android.widget.Button previous=ReflectionHelpers.getField(activity,"previous");assertTrue(previous.isEnabled());previous.performClick();
            assertTrue(((TextView)activity.findViewById(R.id.webResultPageLabel)).getText().toString().contains("1 / 3"));
            ProjectResults.copy(SavedJobPipeline.renderedPage(p,0),SavedJobPipeline.renderedPage(p,1));second.renderComplete=true;p.completed=true;p.exportsComplete=true;p.save();
            JobRuntime.endJob();listener.changed(p,"Complete",false);drainReaderRefresh(activity);
            assertTrue(activity.findViewById(R.id.webResultNext).isEnabled());
            assertTrue(activity.findViewById(R.id.webResultExport).isEnabled());
            assertTrue(((TextView)activity.findViewById(R.id.webResultPageLabel)).getText().toString().contains("1 / 3"));
        } finally {reader.pause().stop().destroy();JobRuntime.endJob();}
    }
    @Test public void aRealSelectedSourcePreviewIsNotCoveredByTheEmptyBookEither() {
        MainActivity a=main.get();
        assertEquals(View.VISIBLE,a.findViewById(R.id.resultEmptyIcon).getVisibility());
        Bitmap source=Bitmap.createBitmap(20,20,Bitmap.Config.ARGB_8888);
        ((ImageView)a.findViewById(R.id.previewImage)).setImageBitmap(source);
        ReflectionHelpers.callInstanceMethod(a,"updateButtons",ReflectionHelpers.ClassParameter.from(boolean.class,false));
        assertEquals(View.GONE,a.findViewById(R.id.resultEmptyIcon).getVisibility());
    }
    @Test public void anIdleServiceShutdownDoesNotLeaveAStopCurrentJobButtonOnComplete() {
        MainActivity a=main.get();ReflectionHelpers.setStaticField(KeepAliveService.class,"serviceRunning",true);
        ReflectionHelpers.callInstanceMethod(a,"refreshKeepAliveUi");
        assertEquals("A transient service flag is not active/manual work",View.GONE,a.findViewById(R.id.stopJobButton).getVisibility());
    }
    @Test public void explicitManualProtectionStillHasStopWhileIdle() {
        app.getSharedPreferences("kurdman_settings",0).edit().putBoolean("keep_alive_desired",true).commit();
        ReflectionHelpers.callInstanceMethod(main.get(),"refreshKeepAliveUi");
        assertEquals(View.VISIBLE,main.get().findViewById(R.id.stopJobButton).getVisibility());
        assertEquals(main.get().getString(R.string.ks_0487),((TextView)main.get().findViewById(R.id.stopJobButton)).getText().toString());
    }
    @Test public void protectedWorkStillHasStopBeforeTheServiceStarts() {
        ReflectionHelpers.setField(main.get(),"currentBusy",true);
        ReflectionHelpers.callInstanceMethod(main.get(),"refreshKeepAliveUi");
        assertEquals(View.VISIBLE,main.get().findViewById(R.id.stopJobButton).getVisibility());
    }
    @Test public void aRealRunningJobKeepsItsResponsiveStopEvenWithoutManualProtection() {
        assertTrue(JobRuntime.tryBeginJob());
        ReflectionHelpers.callInstanceMethod(main.get(),"refreshKeepAliveUi");
        assertEquals(View.VISIBLE,main.get().findViewById(R.id.stopJobButton).getVisibility());
        main.get().findViewById(R.id.stopJobButton).performClick();
        assertTrue(JobRuntime.isStopRequested());
        assertTrue("Only the runner may end its job; STOP requests cancellation",JobRuntime.isRunning());
    }
    @Test public void undoAndRedoRefreshTheStatusInsteadOfKeepingTheOldSizeClaim() throws Exception {
        project();ActivityController<EditorActivity> editor=Robolectric.buildActivity(EditorActivity.class).setup();
        try {
            ExecutorService worker=ReflectionHelpers.getField(editor.get(),"executor");worker.submit(()->{}).get(15,TimeUnit.SECONDS);
            shadowOf(Looper.getMainLooper()).idle();
            ReflectionHelpers.callInstanceMethod(editor.get(),"adjustSimpleTextSize",ReflectionHelpers.ClassParameter.from(float.class,-.10f));
            assertTrue(((TextView)editor.get().findViewById(R.id.editorStatus)).getText().toString().contains("90"));
            editor.get().findViewById(R.id.editorUndoButton).performClick();
            TranslationProject p=ReflectionHelpers.getField(editor.get(),"project");
            assertEquals(1f,p.pages.get(0).segments.get(0).ensureStyle().sizeScale,.001f);
            assertFalse("Undo restored100%; do not keep the earlier90% status",((TextView)editor.get().findViewById(R.id.editorStatus)).getText().toString().contains("90"));
            editor.get().findViewById(R.id.editorRedoButton).performClick();
            assertEquals(.9f,p.pages.get(0).segments.get(0).ensureStyle().sizeScale,.001f);
        } finally {editor.pause().stop().destroy();}
    }
    @Test public void studioExportUsesTheSameStableProductAndRetainsTheEditedProjectBinding() throws Exception {
        TranslationProject p=project();p.pages.get(0).segments.get(0).ensureStyle().sizeScale=.9f;p.save();
        ReflectionHelpers.callInstanceMethod(main.get(),"exportEditedProject");
        ExecutorService worker=ReflectionHelpers.getStaticField(MainActivity.class,"executor");
        worker.submit(()->{}).get(20,TimeUnit.SECONDS);shadowOf(Looper.getMainLooper()).idle();
        File shown=ReflectionHelpers.getField(main.get(),"resultFile");
        assertEquals("Studio must update the same result, not start a second legacy public export",ProjectResults.result(p).getCanonicalFile(),shown.getCanonicalFile());
        assertNull("No automatic Studio TXT",ReflectionHelpers.getField(main.get(),"resultTextFile"));
        TranslationProject saved=TranslationProject.load(p.projectDir);
        assertEquals(ProjectResults.binding(saved),saved.outputBinding);
        assertEquals(.9f,saved.pages.get(0).segments.get(0).ensureStyle().sizeScale,.001f);
        assertEquals(View.GONE,main.get().findViewById(R.id.resultEmptyIcon).getVisibility());
    }
    @Test public void stopAllAlsoPausesPendingRecoveryAfterTheRunnerHasEnded() throws Exception {
        TranslationProject p=project();p.completed=false;p.resumable=true;p.autoResume=true;p.save();
        TranslationProjectStore.allowRecovery(app,p,true);assertTrue(SavedJobCoordinator.hasPending(app));
        KeepAliveService.stopAll(app);
        assertFalse("STOP while waiting for recovery must not silently restart it",SavedJobCoordinator.hasPending(app));
    }
    @Test public void notificationStopAlsoPausesPendingRecoveryWithoutDeletingCheckpoints() throws Exception {
        TranslationProject p=project();p.completed=false;p.resumable=true;p.autoResume=true;p.save();
        TranslationProjectStore.allowRecovery(app,p,true);assertTrue(SavedJobCoordinator.hasPending(app));
        ServiceController<KeepAliveService> service=Robolectric.buildService(KeepAliveService.class).create();
        try {
            assertEquals(KeepAliveService.START_NOT_STICKY,service.get().onStartCommand(
                    new Intent(app,KeepAliveService.class).setAction(KeepAliveService.ACTION_STOP_ALL),0,1));
            assertFalse(SavedJobCoordinator.hasPending(app));assertFalse(KeepAliveService.isServiceRunning());
            assertTrue(p.pages.get(0).sourceFile(p.projectDir).isFile());
        } finally {service.destroy();}
    }
    @Test public void stopAllActuallySendsItsStopIntentToAnAlreadyRunningService() {
        ServiceController<KeepAliveService> service=Robolectric.buildService(KeepAliveService.class).create();
        try {
            while(shadowOf(app).getNextStartedService()!=null) { }
            KeepAliveService.stopAll(app);
            Intent stop=shadowOf(app).getNextStartedService();
            assertNotNull("Do not clear the running flag before deciding whether to send STOP",stop);
            assertEquals(KeepAliveService.ACTION_STOP_ALL,stop.getAction());
        } finally {service.destroy();}
    }
}
