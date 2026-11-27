package com.kurdman.app;

import static org.junit.Assert.*;
import android.app.Application;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

/** Synthetic native view flow; no device, network, website or translation-quality claim. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk={26,34},application=Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class StaticWebReaderActivityTest {
    public static class FakeReader extends StaticWebReaderActivity {
        volatile int pageLoads,previewLoads;
        @Override android.graphics.Bitmap fetchPreview(String url,PinnedHttpsClient.Cancellation cancel)throws IOException {
            previewLoads++;return android.graphics.Bitmap.createBitmap(4,4,android.graphics.Bitmap.Config.ARGB_8888);
        }
        @Override StaticWebPage fetchPage(String url,PinnedHttpsClient.Cancellation cancel)throws IOException {
            pageLoads++;String html="<title>Example</title><a href='/next'>Next chapter</a><img src='/one.png'>";
            return StaticWebPage.parse(url,"text/html",html.getBytes(StandardCharsets.UTF_8));
        }
        @Override StaticWebImport.Result fetchImages(List<String> images,File cache,PinnedHttpsClient.Cancellation cancel,StaticWebImport.Progress progress)throws IOException {
            File root=new File(cache,"web-reader"),session=new File(root,UUID.randomUUID().toString());if(!session.mkdirs())throw new IOException();
            File file=new File(session,"0001.png");try(FileOutputStream out=new FileOutputStream(file)){out.write(1);}
            return new StaticWebImport.Result(session,Collections.singletonList(file),0);
        }
    }
    public static class SlowPreview extends FakeReader {
        final java.util.concurrent.CountDownLatch started=new java.util.concurrent.CountDownLatch(1), release=new java.util.concurrent.CountDownLatch(1);
        volatile android.graphics.Bitmap lateBitmap;
        @Override android.graphics.Bitmap fetchPreview(String url,PinnedHttpsClient.Cancellation cancel)throws IOException {
            started.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException(e);}
            lateBitmap=android.graphics.Bitmap.createBitmap(4,4,android.graphics.Bitmap.Config.ARGB_8888);return lateBitmap;
        }
    }
    public static class SlowReader extends FakeReader {
        final java.util.concurrent.CountDownLatch started=new java.util.concurrent.CountDownLatch(1), release=new java.util.concurrent.CountDownLatch(1);
        volatile File lateSession;
        @Override StaticWebImport.Result fetchImages(List<String> images,File cache,PinnedHttpsClient.Cancellation cancel,StaticWebImport.Progress progress)throws IOException {
            started.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException(e);}
            StaticWebImport.Result result=super.fetchImages(images,cache,cancel,progress);lateSession=result.session;return result;
        }
    }
    private static void waitFor(java.util.function.BooleanSupplier condition)throws Exception {
        long until=System.currentTimeMillis()+5000;
        while(!condition.getAsBoolean()&&System.currentTimeMillis()<until){Shadows.shadowOf(Looper.getMainLooper()).idle();Thread.sleep(10);}
        Shadows.shadowOf(Looper.getMainLooper()).idle();assertTrue("Timed out",condition.getAsBoolean());
    }
    private static boolean hasWebView(View view){if(view instanceof WebView)return true;if(view instanceof ViewGroup){ViewGroup g=(ViewGroup)view;for(int i=0;i<g.getChildCount();i++)if(hasWebView(g.getChildAt(i)))return true;}return false;}
    @Test public void staticReaderActionsCenterTheirLabels(){
        org.robolectric.android.controller.ActivityController<FakeReader> controller=Robolectric.buildActivity(FakeReader.class).setup();
        try{FakeReader activity=controller.get();for(int id:new int[]{R.id.webReaderGo,R.id.webReaderTranslate,R.id.webReaderStop}){
            Button button=activity.findViewById(id);assertEquals(android.view.Gravity.CENTER,button.getGravity());
            assertTrue(button.getPaddingStart()>0);assertTrue(button.getPaddingEnd()>0);
        }}finally{controller.pause().stop().destroy();}
    }
    @Test public void opensStaticPageNavigatesAndReturnsPrivateImages()throws Exception {
        org.robolectric.android.controller.ActivityController<FakeReader> controller=Robolectric.buildActivity(FakeReader.class).setup();
        try{FakeReader activity=controller.get();assertFalse(hasWebView(activity.getWindow().getDecorView()));
            EditText address=activity.findViewById(R.id.webReaderAddress);address.setText("https://example.org/start");
            activity.findViewById(R.id.webReaderGo).performClick();ListView list=activity.findViewById(R.id.staticWebLinks);
            waitFor(()->list.getAdapter().getCount()==2);assertEquals("Next chapter",((android.widget.TextView)list.getAdapter().getView(0,null,list)).getText().toString());
            android.widget.CheckBox image=list.getAdapter().getView(1,null,list).findViewById(R.id.staticWebImageSelect);assertTrue(image.isChecked());image.setChecked(false);
            assertFalse(activity.findViewById(R.id.webReaderTranslate).isEnabled());image.setChecked(true);assertTrue(activity.findViewById(R.id.webReaderTranslate).isEnabled());
            list.performItemClick(list.getAdapter().getView(0,null,list),0,0);waitFor(()->address.getText().toString().equals("https://example.org/next"));
            activity.onBackPressed();assertEquals("https://example.org/start",address.getText().toString());
            Button translate=activity.findViewById(R.id.webReaderTranslate);assertTrue(translate.isEnabled());translate.performClick();waitFor(activity::isFinishing);
            android.content.Intent result=Shadows.shadowOf(activity).getResultIntent();assertNotNull(result);
            assertEquals(1,result.getStringArrayListExtra(WebReaderActivity.EXTRA_FILES).size());
        }finally{controller.pause().stop().destroy();}
    }
    @Test public void stopDiscardsLateImportWithoutReturningIt()throws Exception {
        org.robolectric.android.controller.ActivityController<SlowReader> controller=Robolectric.buildActivity(SlowReader.class).setup();
        SlowReader activity=controller.get();
        try{((EditText)activity.findViewById(R.id.webReaderAddress)).setText("https://example.org/start");activity.findViewById(R.id.webReaderGo).performClick();
            waitFor(()->activity.findViewById(R.id.webReaderTranslate).isEnabled());activity.findViewById(R.id.webReaderTranslate).performClick();
            assertTrue(activity.started.await(5,java.util.concurrent.TimeUnit.SECONDS));activity.findViewById(R.id.webReaderStop).performClick();activity.release.countDown();
            waitFor(()->activity.lateSession!=null&&!activity.lateSession.exists());assertFalse(activity.isFinishing());assertNull(Shadows.shadowOf(activity).getResultIntent());
        }finally{activity.release.countDown();controller.pause().stop().destroy();}
    }
    @Test public void previewRequiresExplicitTap()throws Exception {
        org.robolectric.android.controller.ActivityController<FakeReader> controller=Robolectric.buildActivity(FakeReader.class).setup();
        try{FakeReader activity=controller.get();((EditText)activity.findViewById(R.id.webReaderAddress)).setText("https://example.org/start");activity.findViewById(R.id.webReaderGo).performClick();
            ListView list=activity.findViewById(R.id.staticWebLinks);waitFor(()->list.getAdapter().getCount()==2);assertEquals(0,activity.previewLoads);
            android.app.Dialog previous=org.robolectric.shadows.ShadowDialog.getLatestDialog();View row=list.getAdapter().getView(1,null,list);row.findViewById(R.id.staticWebImagePreview).performClick();
            waitFor(()->activity.previewLoads==1&&org.robolectric.shadows.ShadowDialog.getLatestDialog()!=previous);
            assertTrue(org.robolectric.shadows.ShadowDialog.getLatestDialog().isShowing());org.robolectric.shadows.ShadowDialog.getLatestDialog().dismiss();
        }finally{controller.pause().stop().destroy();}
    }
    @Test public void stopDiscardsLatePreview()throws Exception {
        org.robolectric.android.controller.ActivityController<SlowPreview> controller=Robolectric.buildActivity(SlowPreview.class).setup();SlowPreview activity=controller.get();
        try{((EditText)activity.findViewById(R.id.webReaderAddress)).setText("https://example.org/start");activity.findViewById(R.id.webReaderGo).performClick();
            ListView list=activity.findViewById(R.id.staticWebLinks);waitFor(()->list.getAdapter().getCount()==2);
            android.app.Dialog previous=org.robolectric.shadows.ShadowDialog.getLatestDialog();list.getAdapter().getView(1,null,list).findViewById(R.id.staticWebImagePreview).performClick();
            assertTrue(activity.started.await(5,java.util.concurrent.TimeUnit.SECONDS));activity.findViewById(R.id.webReaderStop).performClick();activity.release.countDown();
            waitFor(()->activity.lateBitmap!=null&&activity.lateBitmap.isRecycled());assertSame(previous,org.robolectric.shadows.ShadowDialog.getLatestDialog());
        }finally{activity.release.countDown();controller.pause().stop().destroy();}
    }
    @Test public void approvedStartupUrlLoadsWithoutJavaScript()throws Exception {
        android.content.Intent intent=new android.content.Intent().putExtra(StaticWebReaderActivity.EXTRA_START_URL,"https://example.org/source");
        org.robolectric.android.controller.ActivityController<FakeReader> controller=Robolectric.buildActivity(FakeReader.class,intent).setup();
        try{FakeReader activity=controller.get();ListView list=activity.findViewById(R.id.staticWebLinks);
            waitFor(()->list.getAdapter().getCount()==2);assertEquals(1,activity.pageLoads);
            assertEquals("https://example.org/source",((EditText)activity.findViewById(R.id.webReaderAddress)).getText().toString());
        }finally{controller.pause().stop().destroy();}
    }
    @Test public void startupUrlMustPassUrlGate()throws Exception {
        android.content.Intent intent=new android.content.Intent().putExtra(StaticWebReaderActivity.EXTRA_START_URL,"https://127.0.0.1/");
        org.robolectric.android.controller.ActivityController<FakeReader> controller=Robolectric.buildActivity(FakeReader.class,intent).setup();
        try{FakeReader activity=controller.get();Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(0,activity.pageLoads);
            assertEquals(activity.getString(R.string.web_invalid),((android.widget.TextView)activity.findViewById(R.id.webReaderStatus)).getText().toString());
        }finally{controller.pause().stop().destroy();}
    }
    @Test public void invalidUrlDoesNotInvokeFetcher()throws Exception {
        org.robolectric.android.controller.ActivityController<FakeReader> controller=Robolectric.buildActivity(FakeReader.class).setup();
        try{FakeReader activity=controller.get();((EditText)activity.findViewById(R.id.webReaderAddress)).setText("http://127.0.0.1/");
            activity.findViewById(R.id.webReaderGo).performClick();assertFalse(activity.findViewById(R.id.webReaderTranslate).isEnabled());
            assertEquals(activity.getString(R.string.web_invalid),((android.widget.TextView)activity.findViewById(R.id.webReaderStatus)).getText().toString());
        }finally{controller.pause().stop().destroy();}
    }
}
