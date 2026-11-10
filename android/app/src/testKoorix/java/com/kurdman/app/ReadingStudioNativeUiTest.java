package com.kurdman.app;

import static org.junit.Assert.*;
import android.app.Activity;
import android.app.Application;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.graphics.ColorUtils;
import androidx.core.os.LocaleListCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.graphics.Insets;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.util.ReflectionHelpers;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import static org.robolectric.Shadows.shadowOf;

/** Actual Java Views/XML native Skia captures. Synthetic data only; no device/TalkBack claim. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk={26,34},application=Application.class,qualifiers="en-rUS-w393dp-h852dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ReadingStudioNativeUiTest {
    private Application app;
    @Before public void setup() {
        app=RuntimeEnvironment.getApplication();
        JobRuntime.beginJob();JobRuntime.endJob();Thread.interrupted();
        app.getSharedPreferences("koorix_ui",0).edit().clear().commit();
        app.getSharedPreferences("kurdman_settings",0).edit().clear().commit();
        TranslationProjectStore.setCurrent(null);
        TranslationProjectStore.deleteRecursive(TranslationProjectStore.root(app));
    }
    private void device(String locale,boolean night,int width,int height,float scale) {
        RuntimeEnvironment.setQualifiers(("ckb".equals(locale)?"b+ckb":locale)+"-w"+width+"dp-h"+height+"dp"+(width>height?"-land":"-port")+(night?"-night":"-notnight")+"-mdpi");
        Configuration c=new Configuration(app.getResources().getConfiguration());c.fontScale=scale;
        app.getResources().updateConfiguration(c,app.getResources().getDisplayMetrics());
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(locale));
        AppCompatDelegate.setDefaultNightMode(night?AppCompatDelegate.MODE_NIGHT_YES:AppCompatDelegate.MODE_NIGHT_NO);
    }
    private View root(Activity activity) {
        return ((ViewGroup)activity.findViewById(android.R.id.content)).getChildAt(0);
    }
    private void layout(Activity activity,int width,int height) {
        View v=root(activity);
        for(int pass=0;pass<2;pass++) {
            v.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));
            v.layout(0,0,width,height);shadowOf(Looper.getMainLooper()).idle();
        }
    }
    private void capture(Activity activity,String name,int width,int height) throws Exception {
        layout(activity,width,height);
        Bitmap image=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
        root(activity).draw(new Canvas(image));
        assertTrue("Native UI must actually paint, not a blank/mocked bitmap",image.getPixel(width/2,20)!=Color.TRANSPARENT);
        File dir=new File(System.getProperty("koorix.uiFixtures","build/outputs/ui-tests"));
        assertTrue(dir.isDirectory()||dir.mkdirs());
        try(FileOutputStream out=new FileOutputStream(new File(dir,name+"-sdk"+android.os.Build.VERSION.SDK_INT+".png"))) {
            assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,out));
        }
        image.recycle();
    }
    private void close(ActivityController<?> controller) {
        controller.pause().stop().destroy();shadowOf(Looper.getMainLooper()).idle();
    }
    private void await(ExecutorService worker) throws Exception {
        worker.submit(()->{}).get(15,TimeUnit.SECONDS);shadowOf(Looper.getMainLooper()).idle();
    }
    private TranslationProject sampleProject() throws Exception {
        TranslationProject p=TranslationProjectStore.createNew(app,false,"Sample chapter","Sample chapter","en");
        p.targetLanguage="ckb";p.completed=true;
        TranslationProject.Page page=new TranslationProject.Page("pages/0001.png",".png");
        Bitmap paper=Bitmap.createBitmap(420,560,Bitmap.Config.ARGB_8888);paper.eraseColor(Color.rgb(236,240,231));
        Canvas c=new Canvas(paper);Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.WHITE);c.drawRoundRect(new RectF(22,24,398,260),24,24,paint);
        paint.setColor(Color.rgb(90,107,94));paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(3);c.drawOval(new RectF(48,55,372,225),paint);
        paint.setStyle(Paint.Style.FILL);paint.setColor(Color.BLACK);paint.setTextSize(26);c.drawText("Stay here.",135,140,paint);
        paint.setColor(Color.rgb(195,211,193));c.drawRoundRect(new RectF(22,286,398,532),18,18,paint);
        paint.setColor(Color.rgb(139,165,144));c.drawCircle(210,378,42,paint);c.drawRoundRect(new RectF(145,425,275,508),25,25,paint);
        Segment s=new Segment("Stay here.",new Rect(126,111,293,148),1);s.ckb="لێرە چاوەڕێ بکە.";s.originalCkb=s.ckb;s.sourceLineHeight=30;s.autoBackgroundReliable=true;s.rendered=true;
        s.renderBox=new Rect(52,59,368,221);s.eraseBox=new Rect(s.renderBox);s.bubbleOval=true;
        ManhwaRenderer.computeInteriorSpans(paper,s,Collections.singletonList(s));
        page.segments.add(s);page.ocrComplete=true;page.translationComplete=true;
        try(FileOutputStream out=new FileOutputStream(page.sourceFile(p.projectDir))){assertTrue(paper.compress(Bitmap.CompressFormat.PNG,100,out));}
        paper.recycle();p.pages.add(page);p.save();TranslationProjectStore.activate(app,p);return p;
    }
    @Test public void nativeMainDestinationsAndAllThreeLanguagesHaveRealCaptures() throws Exception {
        int[] tabs={R.id.navTranslateButton,R.id.navStyleButton,R.id.navResultsButton,R.id.navSettingsButton,R.id.navReportsButton};
        String[] names={"home","style","work","settings","reports"};
        for(boolean night:new boolean[]{false,true}) {
            String mode=night?"dark":"light";device("en",night,393,852,1);
            ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup();
            try {
                MainActivity a=controller.get();
                for(int i=0;i<tabs.length;i++) {a.findViewById(tabs[i]).performClick();capture(a,"main-"+names[i]+"-en-"+mode,393,852);assertTrue(a.findViewById(tabs[i]).isSelected());}
            } finally {close(controller);}
            for(String locale:new String[]{"ckb","ar"}) {
                device(locale,night,393,852,1);controller=Robolectric.buildActivity(MainActivity.class).setup();
                try {MainActivity a=controller.get();a.findViewById(R.id.navTranslateButton).performClick();capture(a,"main-home-"+locale+"-"+mode,393,852);assertEquals(View.LAYOUT_DIRECTION_RTL,root(a).getLayoutDirection());}
                finally {close(controller);}
            }
        }
        int[][] sizes={{320,740},{800,393},{800,1100}};
        String[] labels={"small","landscape","tablet"};
        for(int i=0;i<sizes.length;i++) {
            device("en",false,sizes[i][0],sizes[i][1],1);ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup();
            try {controller.get().findViewById(R.id.navTranslateButton).performClick();capture(controller.get(),"main-home-"+labels[i],sizes[i][0],sizes[i][1]);}
            finally {close(controller);}
        }
        device("en",true,393,852,2f);ActivityController<MainActivity> big=Robolectric.buildActivity(MainActivity.class).setup();
        try {big.get().findViewById(R.id.navSettingsButton).performClick();capture(big.get(),"main-settings-large-type",393,852);}
        finally {close(big);}
    }
    @Test public void realLibraryStudioStoryAndReviewUseTheSameNativeDesign() throws Exception {
        TranslationProject p=sampleProject();
        for(boolean night:new boolean[]{false,true}) {
            String mode=night?"dark":"light";String locale=night?"ckb":"en";device(locale,night,393,852,1);
            ActivityController<ProjectLibraryActivity> library=Robolectric.buildActivity(ProjectLibraryActivity.class).setup();
            try {
                await(ReflectionHelpers.getStaticField(ProjectLibraryActivity.class,"IO"));
                capture(library.get(),"library-"+mode,393,852);
                EditText search=library.get().findViewById(R.id.librarySearch);search.setText("not-a-project");
                assertTrue("Search is local and non-destructive",p.projectDir.isDirectory());search.setText("");
            } finally {close(library);}
            ActivityController<EditorActivity> editor=Robolectric.buildActivity(EditorActivity.class).setup();
            try {
                await(ReflectionHelpers.getField(editor.get(),"executor"));
                capture(editor.get(),"studio-"+mode,393,852);
                for(int id:new int[]{R.id.editorBackButton,R.id.editorUndoButton,R.id.editorRedoButton,R.id.editorPagePrevButton,R.id.editorPeekButton}) {
                    View control=editor.get().findViewById(id);assertTrue(control.getWidth()>=48);assertTrue(control.getHeight()>=48);assertNotNull(control.getContentDescription());
                }
            } finally {close(editor);}
            ActivityController<ComicSettingsActivity> story=Robolectric.buildActivity(ComicSettingsActivity.class).setup();
            try {capture(story.get(),"story-settings-"+mode,393,852);}
            finally {close(story);}
        }
        device("en",false,393,852,1);ActivityController<ComicQualityActivity> quality=Robolectric.buildActivity(ComicQualityActivity.class).setup();
        try {await(ReflectionHelpers.getField(quality.get(),"worker"));capture(quality.get(),"review-light",393,852);}
        finally {close(quality);}
    }
    @Test public void lightDarkContrastAndSyntheticInsetsAreActuallyChecked() {
        for(boolean night:new boolean[]{false,true}) {
            device("en",night,393,852,1);ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup();
            try {
                MainActivity a=controller.get();layout(a,393,852);
                assertEquals(night,KoorixDesign.dark(a));
                for(int ink:new int[]{R.color.text_primary,R.color.text_secondary,R.color.text_muted})assertTrue(ColorUtils.calculateContrast(a.getColor(ink),a.getColor(R.color.card))>=4.5);
                assertTrue(ColorUtils.calculateContrast(a.getColor(R.color.text_on_primary),a.getColor(R.color.primary))>=4.5);
                View root=root(a);WindowInsetsCompat insets=new WindowInsetsCompat.Builder().setInsets(WindowInsetsCompat.Type.systemBars(),Insets.of(0,24,0,16)).build();
                ViewCompat.dispatchApplyWindowInsets(root,insets);assertTrue(root.getPaddingTop()>=24);assertTrue(root.getPaddingBottom()>=16);
            } finally {close(controller);}
        }
    }
}
