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
    }
    @Test public void protectedWorkStillHasStopBeforeTheServiceStarts() {
        ReflectionHelpers.setField(main.get(),"currentBusy",true);
        ReflectionHelpers.callInstanceMethod(main.get(),"refreshKeepAliveUi");
        assertEquals(View.VISIBLE,main.get().findViewById(R.id.stopJobButton).getVisibility());
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
