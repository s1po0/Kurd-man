package com.kurdman.app;

import static org.junit.Assert.*;
import android.app.Application;
import android.graphics.Rect;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;
import androidx.core.graphics.ColorUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.robolectric.Shadows.shadowOf;

/** Actual native Android UI contracts, not physical-device/TalkBack acceptance. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk={26,34},application=Application.class,qualifiers="en-rUS-w393dp-h852dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ReadingStudioUiTest {
    private ActivityController<MainActivity> controller;
    private MainActivity activity;
    @Before public void setup() {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en"));
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
        controller=Robolectric.buildActivity(MainActivity.class).setup();
        activity=controller.get();
        activity.findViewById(R.id.navTranslateButton).performClick();
        measure(activity.findViewById(R.id.mainRoot));
    }
    @After public void teardown() {
        if(controller!=null)controller.pause().stop().destroy();
        JobRuntime.endJob();Thread.interrupted();
    }
    private void measure(View view) {
        view.measure(View.MeasureSpec.makeMeasureSpec(393,View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(852,View.MeasureSpec.EXACTLY));
        view.layout(0,0,393,852);shadowOf(Looper.getMainLooper()).idle();
    }
    @Test public void defaultReadingSurfaceIsLightAndTextHasAccessibleContrast() {
        int page=activity.getColor(R.color.page),ink=activity.getColor(R.color.text_primary);
        assertTrue("A calm paper reading surface must replace the old dark gradient",ColorUtils.calculateLuminance(page)>.8);
        assertTrue(ColorUtils.calculateContrast(ink,page)>=4.5);
    }
    @Test public void oneUnifiedImportIsReachableInTheFirstPhoneViewport() {
        View pick=activity.findViewById(R.id.pickButton),scroll=activity.findViewById(R.id.mainScrollView);
        Rect bounds=new Rect(0,0,pick.getWidth(),pick.getHeight());
        ((android.view.ViewGroup)activity.findViewById(R.id.mainRoot)).offsetDescendantRectToMyCoords(pick,bounds);
        Rect viewport=new Rect(0,0,scroll.getWidth(),scroll.getHeight());
        ((android.view.ViewGroup)activity.findViewById(R.id.mainRoot)).offsetDescendantRectToMyCoords(scroll,viewport);
        assertTrue("Do not bury the single image/CBZ import under a promotional hero",bounds.top>=viewport.top&&bounds.bottom<=viewport.bottom);
        assertTrue(pick.getHeight()>=48);
    }
    @Test public void everyDestinationHasAPredictableNamedWorkspaceHeading() {
        int id=activity.getResources().getIdentifier("workspaceTitle","id",activity.getPackageName());
        assertTrue("The persistent shell needs a localized current-screen heading",id!=0);
        assertNotNull(activity.findViewById(id));
    }
    @Test public void backFromSettingsReturnsToTranslateWithoutDiscardingTheActivity() {
        activity.findViewById(R.id.navSettingsButton).performClick();
        activity.getOnBackPressedDispatcher().onBackPressed();
        assertFalse("Back from a tab should return home, not unexpectedly close the app",activity.isFinishing());
        assertEquals(View.VISIBLE,activity.findViewById(R.id.pageTranslate).getVisibility());
    }
    @Test public void savedProjectsHaveALocalSearchRatherThanAnUnboundedButtonWall() {
        ActivityController<ProjectLibraryActivity> library=Robolectric.buildActivity(ProjectLibraryActivity.class).setup();
        try {
            ProjectLibraryActivity screen=library.get();
            int id=screen.getResources().getIdentifier("librarySearch","id",screen.getPackageName());
            assertTrue("Project library must be searchable without a network/account",id!=0);
            assertNotNull(screen.findViewById(id));
        } finally { library.pause().stop().destroy(); }
    }

    @Test public void completedPendingInputSurvivesAnAppearanceOrLocaleRecreation() throws Exception {
        java.io.File image=new java.io.File(activity.getCacheDir(),"pending-ui.png");
        android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(12,12,android.graphics.Bitmap.Config.ARGB_8888);
        try(java.io.FileOutputStream out=new java.io.FileOutputStream(image)){bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}bitmap.recycle();
        org.robolectric.util.ReflectionHelpers.setField(activity,"selectedImageFile",image);
        org.robolectric.util.ReflectionHelpers.setField(activity,"selectedName","My pending page.png");
        controller.recreate();activity=controller.get();
        java.io.File restored=org.robolectric.util.ReflectionHelpers.getField(activity,"selectedImageFile");
        assertNotNull("Changing appearance must not discard already imported pages",restored);
        assertEquals(image.getCanonicalFile(),restored.getCanonicalFile());
        assertEquals("My pending page.png",org.robolectric.util.ReflectionHelpers.getField(activity,"selectedName"));
        assertTrue("UI restoration must not copy or delete the source",image.isFile());
    }
    @Test public void pendingInputStateRejectsTraversalWithoutReadingOrDeletingTheFile() throws Exception {
        java.io.File outside=new java.io.File(activity.getFilesDir(),"outside-cache.png");
        try(java.io.FileOutputStream out=new java.io.FileOutputStream(outside)){out.write(new byte[]{1,2,3});}
        android.os.Bundle values=new android.os.Bundle();values.putInt("v",1);values.putString("image","cache:../files/outside-cache.png");
        android.os.Bundle state=new android.os.Bundle();state.putBundle(PendingInputState.KEY,values);
        assertNull(PendingInputState.restore(activity,state).image);
        assertTrue(outside.isFile());
    }
}
