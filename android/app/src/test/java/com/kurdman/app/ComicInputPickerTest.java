package com.kurdman.app;

import static org.junit.Assert.*;
import android.app.Activity;
import android.app.Application;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.DocumentsContract;
import androidx.activity.result.contract.ActivityResultContracts;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import java.util.*;

/** Real picker contracts/returned URIs, not a claim to control an OEM gallery UI. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk={26,34},application=Application.class,qualifiers="en")
public class ComicInputPickerTest {
    Application app;
    @Before public void setup(){app=RuntimeEnvironment.getApplication();}
    @Test public void allFilesAreVisibleWithoutAMimeWhitelist(){Intent intent=ComicInputPicker.files();assertEquals(Intent.ACTION_OPEN_DOCUMENT,intent.getAction());assertEquals("*/*",intent.getType());assertFalse(intent.hasExtra(Intent.EXTRA_MIME_TYPES));assertTrue(intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE,false));}
    @Test public void filesCanBrowseSharedStorageAndCloudProviders(){Intent intent=ComicInputPicker.files();assertTrue(intent.hasCategory(Intent.CATEGORY_OPENABLE));assertFalse(intent.getBooleanExtra(Intent.EXTRA_LOCAL_ONLY,false));Uri initial=intent.getParcelableExtra(DocumentsContract.EXTRA_INITIAL_URI);assertNotNull(initial);assertEquals("content",initial.getScheme());assertTrue(initial.toString().contains("primary"));}
    @Test public void filesRequestOnlySelectedUriReadAccess(){int flags=ComicInputPicker.files().getFlags();assertTrue((flags&Intent.FLAG_GRANT_READ_URI_PERMISSION)!=0);assertTrue((flags&Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)!=0);assertEquals(0,flags&Intent.FLAG_GRANT_WRITE_URI_PERMISSION);}
    @Test public void galleryFallbackUsesGalleryCompatibleImageIntent(){Intent gallery=ComicInputPicker.galleryFallback();assertEquals(Intent.ACTION_GET_CONTENT,gallery.getAction());assertEquals("image/*",gallery.getType());assertTrue(gallery.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE,false));assertTrue(gallery.hasCategory(Intent.CATEGORY_OPENABLE));}
    @Test public void systemPhotoPickerUsesImagesAndThePlatformsOwnSelectionLimit(){Intent intent=new ActivityResultContracts.PickMultipleVisualMedia().createIntent(app,ComicInputPicker.imagesOnly());assertEquals("image/*",intent.getType());if(Build.VERSION.SDK_INT>=33)assertEquals("android.provider.action.PICK_IMAGES",intent.getAction());else assertEquals(Intent.ACTION_OPEN_DOCUMENT,intent.getAction());}
    @Test public void filesFallbackStillShowsAllTypes(){Intent intent=ComicInputPicker.filesFallback();assertEquals(Intent.ACTION_GET_CONTENT,intent.getAction());assertEquals("*/*",intent.getType());assertFalse(intent.hasExtra(Intent.EXTRA_MIME_TYPES));assertTrue(intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE,false));}
    @Test public void canceledOrNullResultsCannotReplaceSelection(){Intent stale=new Intent().setData(Uri.parse("content://photos/stale"));assertTrue(ComicInputPicker.selected(Activity.RESULT_CANCELED,stale).isEmpty());assertTrue(ComicInputPicker.selected(Activity.RESULT_OK,null).isEmpty());assertTrue(new ComicInputPicker.Documents().parseResult(Activity.RESULT_CANCELED,stale).isEmpty());}
    @Test public void oneGalleryPhotoAndCbzDocumentAreAccepted(){Uri photo=Uri.parse("content://media/picker/7");Uri cbz=Uri.parse("content://files/document/book.cbz");assertEquals(Arrays.asList(photo),ComicInputPicker.selected(Activity.RESULT_OK,new Intent().setData(photo)));assertEquals(Arrays.asList(cbz),new ComicInputPicker.Documents().parseResult(Activity.RESULT_OK,new Intent().setData(cbz)));}
    @Test public void multipleClipUrisAreDeduplicatedWithoutReordering(){Uri a=Uri.parse("content://gallery/2"),b=Uri.parse("content://gallery/10");ClipData clip=ClipData.newRawUri("photos",a);clip.addItem(new ClipData.Item(b));clip.addItem(new ClipData.Item(a));Intent result=new Intent().setData(b);result.setClipData(clip);assertEquals(Arrays.asList(a,b),ComicInputPicker.selected(Activity.RESULT_OK,result));}
    @Test public void nullClipItemsAndRemoteUrlsCannotBecomeFileImports(){ClipData clips=ClipData.newPlainText("not a file","text");clips.addItem(new ClipData.Item(Uri.parse("https://example.invalid/private.png")));clips.addItem(new ClipData.Item(Uri.parse("content://gallery/valid")));Intent result=new Intent();result.setClipData(clips);assertEquals(Arrays.asList(Uri.parse("content://gallery/valid")),ComicInputPicker.selected(Activity.RESULT_OK,result));}
    @Test public void documentResultsAreNotSilentlyTruncatedByPickerCode(){ClipData data=ClipData.newRawUri("files",Uri.parse("content://files/0"));for(int i=1;i<301;i++)data.addItem(new ClipData.Item(Uri.parse("content://files/"+i)));Intent result=new Intent();result.setClipData(data);assertEquals(301,ComicInputPicker.selected(Activity.RESULT_OK,result).size());/* existing importer explicitly enforces 300 */}
    @Test public void contractUsesTheSameUnfilteredFileIntent(){Intent intent=new ComicInputPicker.Documents().createIntent(app,null);assertEquals("*/*",intent.getType());assertEquals(Intent.ACTION_OPEN_DOCUMENT,intent.getAction());assertTrue(intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE,false));}
}
