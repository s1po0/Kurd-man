package com.kurdman.app;

import static org.junit.Assert.*;
import android.app.Application;
import android.graphics.Color;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.Spinner;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.graphics.ColorUtils;
import androidx.core.os.LocaleListCompat;
import org.junit.After;
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
import static org.robolectric.Shadows.shadowOf;

/** Owner's no-green three-colour correction and actual persistent native dark-mode action. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk={26,34},application=Application.class,qualifiers="en-rUS-w393dp-h852dp-notnight-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ThemeTriadAndroidTest {
    private Application app;
    private ActivityController<MainActivity> controller;
    private MainActivity activity;
    @Before public void setup() {
        app=RuntimeEnvironment.getApplication();
        app.getSharedPreferences("koorix_ui",0).edit().clear().commit();
        app.getSharedPreferences("kurdman_settings",0).edit().clear().commit();
        JobRuntime.beginJob();JobRuntime.endJob();Thread.interrupted();
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en"));
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
        controller=Robolectric.buildActivity(MainActivity.class).setup();activity=controller.get();
        activity.findViewById(R.id.navTranslateButton).performClick();
        shadowOf(Looper.getMainLooper()).idle();
    }
    @After public void cleanup() {
        if(controller!=null)controller.pause().stop().destroy();
        JobRuntime.endJob();Thread.interrupted();
    }
    private int color(String name) {
        int id=activity.getResources().getIdentifier(name,"color",activity.getPackageName());
        assertTrue("A real semantic colour token is required: "+name,id!=0);
        return activity.getColor(id);
    }
    private void capture(String name) throws Exception {
        View root=activity.findViewById(R.id.mainRoot);
        root.measure(View.MeasureSpec.makeMeasureSpec(393,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(852,View.MeasureSpec.EXACTLY));
        root.layout(0,0,393,852);shadowOf(Looper.getMainLooper()).idle();
        android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(393,852,android.graphics.Bitmap.Config.ARGB_8888);
        root.draw(new android.graphics.Canvas(bitmap));
        java.io.File dir=new java.io.File(System.getProperty("koorix.uiFixtures","build/outputs/ui-tests"));assertTrue(dir.isDirectory()||dir.mkdirs());
        try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(dir,name+"-sdk"+android.os.Build.VERSION.SDK_INT+".png"))){assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out));}
        bitmap.recycle();
    }
    private Button quick() {
        int id=activity.getResources().getIdentifier("quickThemeButton","id",activity.getPackageName());
        assertTrue("Dark mode must be reachable in the persistent header, not hidden in settings",id!=0);
        Button button=activity.findViewById(id);assertNotNull(button);return button;
    }
    @Test public void threeBalancedBlueVioletPeachAccentsReplaceTheGreenBrand() {
        float[] hues=new float[3];String[] names={"ui_brand_blue","ui_brand_violet","ui_brand_peach"};
        for(int i=0;i<names.length;i++) {
            float[] hsv=new float[3];Color.colorToHSV(color(names[i]),hsv);hues[i]=hsv[0];
            assertFalse("The owner explicitly rejected green: "+names[i],hsv[0]>=70&&hsv[0]<=180&&hsv[1]>.05f);
            assertTrue("Avoid fluorescent/saturated accents",hsv[1]<.72f);
        }
        for(int i=0;i<3;i++)for(int j=i+1;j<3;j++) {
            float gap=Math.abs(hues[i]-hues[j]);assertTrue("Three accents must not be three aliases of one hue",Math.min(gap,360-gap)>=18);
        }
        float[] primary=new float[3];Color.colorToHSV(activity.getColor(R.color.primary),primary);
        assertTrue("Primary action must be blue/indigo, not the rejected green",primary[0]>=210&&primary[0]<=260);
    }
    @Test public void currentModeAndNextThemeActionHaveNativeAccessibleLabels() {
        Button button=quick();assertEquals(View.VISIBLE,button.getVisibility());assertTrue(button.isEnabled());
        assertTrue(button.getContentDescription().toString().toLowerCase(java.util.Locale.ROOT).contains("dark"));
        assertTrue(button.getMinimumWidth()>=48);assertTrue(button.getMinimumHeight()>=48);
        assertNotNull(button.getCompoundDrawablesRelative()[0]);
    }
    @Test public void headerActuallyEnablesPersistentDarkModeAndSettingsShowTheSameChoice() throws Exception {
        quick().performClick();shadowOf(Looper.getMainLooper()).idle();
        assertEquals("Header tap must persist the explicit Dark choice",2,KoorixDesign.themeMode(activity));
        assertEquals(AppCompatDelegate.MODE_NIGHT_YES,AppCompatDelegate.getDefaultNightMode());
        controller.recreate();activity=controller.get();shadowOf(Looper.getMainLooper()).idle();
        assertTrue("Real native night resources must be active",KoorixDesign.dark(activity));
        assertEquals("Recreated Settings must reflect the saved Dark choice, not restore stale System",2,((Spinner)activity.findViewById(R.id.appearanceSpinner)).getSelectedItemPosition());
        assertEquals("Hierarchy restoration must not write System over an explicit Dark choice",2,KoorixDesign.themeMode(activity));
        assertTrue("Dark page is actually dark",ColorUtils.calculateLuminance(activity.getColor(R.color.page))<.04);
        assertTrue(ColorUtils.calculateContrast(activity.getColor(R.color.text_primary),activity.getColor(R.color.page))>=4.5);
        KoorixDesign.initialize(app);assertEquals(AppCompatDelegate.MODE_NIGHT_YES,AppCompatDelegate.getDefaultNightMode());
        capture("quick-theme-dark");
    }
    @Test public void headerCanReturnToLightWithoutTrappingTheReaderInNightMode() throws Exception {
        quick().performClick();controller.recreate();activity=controller.get();shadowOf(Looper.getMainLooper()).idle();
        quick().performClick();shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1,KoorixDesign.themeMode(activity));
        assertEquals(AppCompatDelegate.MODE_NIGHT_NO,AppCompatDelegate.getDefaultNightMode());
        controller.recreate();activity=controller.get();assertFalse(KoorixDesign.dark(activity));
        capture("quick-theme-light");
    }
    @Test public void settingsRetainSystemModeRatherThanOverwritingExistingPreferences() {
        assertEquals(0,KoorixDesign.themeMode(activity));
        KoorixDesign.setTheme(activity,2);KoorixDesign.setTheme(activity,0);
        assertEquals(0,KoorixDesign.themeMode(activity));
        assertEquals(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,AppCompatDelegate.getDefaultNightMode());
        assertEquals("com.koorix.app",activity.getPackageName());
    }
    @Test public void quickThemeChangePreservesAlreadyImportedPrivatePages() throws Exception {
        java.io.File source=new java.io.File(activity.getCacheDir(),"triad-pending.png");
        try(java.io.FileOutputStream out=new java.io.FileOutputStream(source)){out.write(new byte[]{1,2,3});}
        ReflectionHelpers.setField(activity,"selectedImageFile",source);
        ReflectionHelpers.setField(activity,"selectedName","My selected page.png");
        quick().performClick();controller.recreate();activity=controller.get();
        assertEquals(source.getCanonicalFile(),((java.io.File)ReflectionHelpers.getField(activity,"selectedImageFile")).getCanonicalFile());
        assertEquals("My selected page.png",ReflectionHelpers.getField(activity,"selectedName"));assertTrue(source.isFile());
    }
    @Test public void quickModeShortcutIsPresentOnEveryWorkspaceDestination() {
        for(int id:new int[]{R.id.navTranslateButton,R.id.navStyleButton,R.id.navResultsButton,R.id.navSettingsButton,R.id.navReportsButton}) {
            activity.findViewById(id).performClick();assertTrue(quick().isShown());assertTrue(quick().isEnabled());
        }
    }
    @Test public void activeJobDisablesThemeRecreationButKeepsStopAvailable() {
        quick();ReflectionHelpers.setField(activity,"currentBusy",true);
        ReflectionHelpers.callInstanceMethod(activity,"refreshKeepAliveUi");
        ReflectionHelpers.callInstanceMethod(activity,"updateButtons",ReflectionHelpers.ClassParameter.from(boolean.class,true));
        assertFalse(quick().isEnabled());
        assertEquals(View.VISIBLE,activity.findViewById(R.id.stopJobButton).getVisibility());
        assertTrue(activity.findViewById(R.id.stopJobButton).isEnabled());
    }
}
