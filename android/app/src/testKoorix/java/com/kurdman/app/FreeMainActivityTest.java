package com.kurdman.app;

import static org.junit.Assert.*;
import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.content.ClipData;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Looper;
import java.io.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.zip.ZipFile;
import android.view.View;
import android.widget.TextView;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.util.ReflectionHelpers;

/** Full public main entry point, without real OEM/provider/account services. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk={26,34},application=Application.class,qualifiers="en")
@LooperMode(LooperMode.Mode.PAUSED)
public class FreeMainActivityTest {
    Application app;ActivityController<MainActivity> controller;MainActivity main;
    @Before public void setup(){app=RuntimeEnvironment.getApplication();JobRuntime.beginJob();JobRuntime.endJob();TranslationProjectStore.setCurrent(null);TranslationProjectStore.deleteRecursive(TranslationProjectStore.root(app));app.getSharedPreferences("kurdman_settings",0).edit().clear().commit();app.getSharedPreferences("koorix_device_v2",0).edit().putString("sealed","old-expired-data-kept").commit();app.getSharedPreferences("koorix_account",0).edit().putString("access_token","legacy-kept").commit();controller=Robolectric.buildActivity(MainActivity.class).setup();main=controller.get();Shadows.shadowOf(Looper.getMainLooper()).idle();}
    @After public void cleanup(){if(controller!=null)controller.pause().stop().destroy();JobRuntime.endJob();Shadows.shadowOf(Looper.getMainLooper()).idle();}
    @Test public void openingTheAppIsFreeAndDoesNotReadOrEraseLegacyCredentials(){assertEquals(View.VISIBLE,main.findViewById(R.id.freeEditionNote).getVisibility());assertEquals(main.getString(R.string.edition_free_note),((TextView)main.findViewById(R.id.freeEditionNote)).getText().toString());assertEquals("old-expired-data-kept",app.getSharedPreferences("koorix_device_v2",0).getString("sealed",""));assertEquals("legacy-kept",app.getSharedPreferences("koorix_account",0).getString("access_token",""));assertNull(Shadows.shadowOf(main).getNextStartedActivity());}
    @Test public void oneInputButtonActuallyLaunchesUnifiedAppCapableAllTypesChooser(){main.findViewById(R.id.pickButton).performClick();Intent launched=Shadows.shadowOf(main).getNextStartedActivityForResult().intent;assertEquals(Intent.ACTION_CHOOSER,launched.getAction());Intent content=launched.getParcelableExtra(Intent.EXTRA_INTENT);assertEquals(Intent.ACTION_GET_CONTENT,content.getAction());assertEquals("*/*",content.getType());assertTrue(content.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE,false));assertFalse(content.hasExtra(Intent.EXTRA_MIME_TYPES));}
    @Test public void extensionCatalogCanReturnPrivateReaderInput(){
        main.findViewById(R.id.extensionCatalogButton).performClick();ShadowActivity.IntentForResult request=Shadows.shadowOf(main).getNextStartedActivityForResult();
        assertNotNull(request);assertEquals(ExtensionCatalogActivity.class.getName(),request.intent.getComponent().getClassName());
        assertTrue(main.getActivityResultRegistry().dispatchResult(request.requestCode,Activity.RESULT_CANCELED,null));
    }
    @Test public void separateGalleryAndFilesUiIsRemoved(){for(String id:new String[]{"pickGalleryButton","pickFilesButton","inputSourceButtons"})assertEquals(id,0,app.getResources().getIdentifier(id,"id",BuildConfig.APPLICATION_ID));assertEquals(View.VISIBLE,main.findViewById(R.id.pickButton).getVisibility());assertEquals(View.VISIBLE,main.findViewById(R.id.inputBrowseHint).getVisibility());}
    @Test public void cancelingTheUnifiedChooserKeepsTheExistingSelection(){ReflectionHelpers.setField(main,"selectedName","existing-book.cbz");main.findViewById(R.id.pickButton).performClick();ShadowActivity.IntentForResult request=Shadows.shadowOf(main).getNextStartedActivityForResult();assertTrue(main.getActivityResultRegistry().dispatchResult(request.requestCode,Activity.RESULT_CANCELED,null));assertEquals("existing-book.cbz",ReflectionHelpers.getField(main,"selectedName"));}
    private File comic(String name, boolean empty) throws Exception {
        File file=new File(app.getCacheDir(),name);
        try(ZipOutputStream zip=new ZipOutputStream(new FileOutputStream(file))) {
            if(!empty){zip.putNextEntry(new ZipEntry("page.png"));
                Bitmap image=Bitmap.createBitmap(12,12,Bitmap.Config.ARGB_8888);
                assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,zip));image.recycle();zip.closeEntry();}
        }
        return file;
    }
    private void choose(File a,File b) throws Exception {
        main.findViewById(R.id.pickButton).performClick();
        ShadowActivity.IntentForResult request=Shadows.shadowOf(main).getNextStartedActivityForResult();
        ClipData clip=ClipData.newRawUri("comics",Uri.fromFile(a));clip.addItem(new ClipData.Item(Uri.fromFile(b)));
        Intent result=new Intent();result.setClipData(clip);
        assertTrue(main.getActivityResultRegistry().dispatchResult(request.requestCode,Activity.RESULT_OK,result));
        ExecutorService executor=ReflectionHelpers.getStaticField(MainActivity.class,"executor");
        executor.submit(()->{}).get(30,TimeUnit.SECONDS);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
    @Test @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    public void multipleCbzThroughTheSingleChooserAreOneOrderedReadableJob() throws Exception {
        File first=comic("chapter-2.cbz",false),second=comic("chapter-10.cbz",false);
        choose(second,first);
        File selected=ReflectionHelpers.getField(main,"selectedCbzFile");
        assertNotNull(selected);assertTrue(selected.isFile());
        assertEquals(2,((Integer)ReflectionHelpers.getField(main,"selectedCbzPages")).intValue());
        try(ZipFile zip=new ZipFile(selected)){assertNotNull(zip.getEntry("0001.png"));assertNotNull(zip.getEntry("0002.png"));}
        assertTrue(first.isFile());assertTrue(second.isFile());
    }
    @Test @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    public void badSecondCbzDoesNotReplaceThePreviouslySelectedBook() throws Exception {
        File previous=comic("previous.cbz",false);
        ReflectionHelpers.setField(main,"selectedCbzFile",previous);
        choose(comic("good.cbz",false),comic("empty.cbz",true));
        assertEquals(previous,ReflectionHelpers.getField(main,"selectedCbzFile"));assertTrue(previous.isFile());
    }
}
