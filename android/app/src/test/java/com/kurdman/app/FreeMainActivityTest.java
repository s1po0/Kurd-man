package com.kurdman.app;

import static org.junit.Assert.*;
import android.app.Application;
import android.content.Intent;
import android.os.Build;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

/** Full main-activity entry points with no real gallery/provider/account service. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk={26,34},application=Application.class,qualifiers="en")
@LooperMode(LooperMode.Mode.PAUSED)
public class FreeMainActivityTest {
    Application app;ActivityController<MainActivity> controller;MainActivity main;
    @Before public void setup(){app=RuntimeEnvironment.getApplication();JobRuntime.beginJob();JobRuntime.endJob();TranslationProjectStore.setCurrent(null);TranslationProjectStore.deleteRecursive(TranslationProjectStore.root(app));app.getSharedPreferences("kurdman_settings",0).edit().clear().commit();app.getSharedPreferences("koorix_device_v2",0).edit().putString("sealed","old-expired-data-kept").commit();app.getSharedPreferences("koorix_account",0).edit().putString("access_token","legacy-kept").commit();controller=Robolectric.buildActivity(MainActivity.class).setup();main=controller.get();Shadows.shadowOf(Looper.getMainLooper()).idle();}
    @After public void cleanup(){if(controller!=null)controller.pause().stop().destroy();JobRuntime.endJob();Shadows.shadowOf(Looper.getMainLooper()).idle();}
    @Test public void openingTheAppIsFreeAndDoesNotReadOrEraseLegacyCredentials(){assertEquals(View.VISIBLE,main.findViewById(R.id.freeEditionNote).getVisibility());assertTrue(((TextView)main.findViewById(R.id.freeEditionNote)).getText().toString().toLowerCase(java.util.Locale.ROOT).contains("free"));assertEquals("old-expired-data-kept",app.getSharedPreferences("koorix_device_v2",0).getString("sealed",""));assertEquals("legacy-kept",app.getSharedPreferences("koorix_account",0).getString("access_token",""));assertNull(Shadows.shadowOf(main).getNextStartedActivity());}
    @Test public void filesButtonActuallyLaunchesAllFilesNotOnlyImages(){assertEquals(View.VISIBLE,main.findViewById(R.id.inputSourceButtons).getVisibility());main.findViewById(R.id.pickFilesButton).performClick();Intent launched=Shadows.shadowOf(main).getNextStartedActivityForResult().intent;assertEquals(Intent.ACTION_OPEN_DOCUMENT,launched.getAction());assertEquals("*/*",launched.getType());assertFalse(launched.hasExtra(Intent.EXTRA_MIME_TYPES));}
    @Test public void galleryButtonActuallyLaunchesAPhotoSource(){main.findViewById(R.id.pickGalleryButton).performClick();Intent launched=Shadows.shadowOf(main).getNextStartedActivityForResult().intent;if(Intent.ACTION_CHOOSER.equals(launched.getAction()))launched=launched.getParcelableExtra(Intent.EXTRA_INTENT);assertEquals("image/*",launched.getType());if(Build.VERSION.SDK_INT>=33)assertEquals("android.provider.action.PICK_IMAGES",launched.getAction());else assertEquals(Intent.ACTION_GET_CONTENT,launched.getAction());}
}
