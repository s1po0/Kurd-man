package com.kurdman.app;

import static org.junit.Assert.*;
import android.app.Activity;
import android.app.Application;
import android.content.ClipData;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.provider.DocumentsContract;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import java.util.*;

/** Intent/filter contracts, not a claim that Samsung/MT/ZArchiver ran on a device. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk={26,34},application=Application.class,qualifiers="en")
public class ComicInputPickerTest {
    Application app;
    @Before public void setup(){app=RuntimeEnvironment.getApplication();}
    @Test public void unifiedInputUsesAppCapableGetContentAndAllTypes(){Intent intent=ComicInputPicker.content();assertEquals(Intent.ACTION_GET_CONTENT,intent.getAction());assertEquals("*/*",intent.getType());assertFalse(intent.hasExtra(Intent.EXTRA_MIME_TYPES));assertTrue(intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE,false));}
    @Test public void inputIsNotPinnedToStorageOrOneApp(){Intent intent=ComicInputPicker.content();assertTrue(intent.hasCategory(Intent.CATEGORY_OPENABLE));assertFalse(intent.getBooleanExtra(Intent.EXTRA_LOCAL_ONLY,false));assertFalse(intent.hasExtra(DocumentsContract.EXTRA_INITIAL_URI));assertNull(intent.getComponent());assertNull(intent.getPackage());}
    @Test public void pickerRequestsSelectedUriReadAccessNotBroadOrWriteAccess(){int flags=ComicInputPicker.content().getFlags();assertTrue((flags&Intent.FLAG_GRANT_READ_URI_PERMISSION)!=0);assertEquals(0,flags&Intent.FLAG_GRANT_WRITE_URI_PERMISSION);assertEquals(0,flags&Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);}
    @Test public void wildcardIntentCanMatchImageOnlyArchiveAndGeneralSourceApps() throws Exception {Intent intent=ComicInputPicker.content();for(String type:new String[]{"image/*","application/zip","*/*"}){IntentFilter handler=new IntentFilter(Intent.ACTION_GET_CONTENT);handler.addCategory(Intent.CATEGORY_DEFAULT);handler.addCategory(Intent.CATEGORY_OPENABLE);handler.addDataType(type);assertTrue("Compatible handler hidden: "+type,handler.match(app.getContentResolver(),intent,true,"picker-test")>=0);}}
    @Test public void contractLaunchesOneSystemChooserNotAPhotoOnlyPicker(){Intent chooser=new ComicInputPicker.Sources().createIntent(app,null);assertEquals(Intent.ACTION_CHOOSER,chooser.getAction());Intent target=chooser.getParcelableExtra(Intent.EXTRA_INTENT);assertNotNull(target);assertEquals(Intent.ACTION_GET_CONTENT,target.getAction());assertEquals("*/*",target.getType());}
    @Test public void chooserDoesNotConstrainProvidersOrAddSeparateImageRoute(){Intent chooser=ComicInputPicker.chooser(app);assertFalse(chooser.hasExtra(Intent.EXTRA_INITIAL_INTENTS));Intent target=chooser.getParcelableExtra(Intent.EXTRA_INTENT);assertNull(target.getComponent());assertNull(target.getPackage());assertFalse(target.hasExtra(Intent.EXTRA_MIME_TYPES));}
    @Test public void canceledOrNullResultsCannotReplaceSelection(){Intent stale=new Intent().setData(Uri.parse("content://photos/stale"));assertTrue(ComicInputPicker.selected(Activity.RESULT_CANCELED,stale).isEmpty());assertTrue(ComicInputPicker.selected(Activity.RESULT_OK,null).isEmpty());assertTrue(new ComicInputPicker.Sources().parseResult(Activity.RESULT_CANCELED,stale).isEmpty());}
    @Test public void sameContractAcceptsGalleryPhotoAndArchiveManagerCbz(){Uri photo=Uri.parse("content://gallery/picker/7"),cbz=Uri.parse("content://archive/document/book.cbz");assertEquals(Arrays.asList(photo),new ComicInputPicker.Sources().parseResult(Activity.RESULT_OK,new Intent().setData(photo)));assertEquals(Arrays.asList(cbz),new ComicInputPicker.Sources().parseResult(Activity.RESULT_OK,new Intent().setData(cbz)));}
    @Test public void multipleClipUrisAreDeduplicatedWithoutReordering(){Uri a=Uri.parse("content://gallery/2"),b=Uri.parse("content://gallery/10");ClipData clip=ClipData.newRawUri("photos",a);clip.addItem(new ClipData.Item(b));clip.addItem(new ClipData.Item(a));Intent result=new Intent().setData(b);result.setClipData(clip);assertEquals(Arrays.asList(a,b),ComicInputPicker.selected(Activity.RESULT_OK,result));}
    @Test public void nullClipItemsAndRemoteUrlsCannotBecomeFileImports(){ClipData clips=ClipData.newPlainText("not a file","text");clips.addItem(new ClipData.Item(Uri.parse("https://example.invalid/private.png")));clips.addItem(new ClipData.Item(Uri.parse("content://gallery/valid")));Intent result=new Intent();result.setClipData(clips);assertEquals(Arrays.asList(Uri.parse("content://gallery/valid")),ComicInputPicker.selected(Activity.RESULT_OK,result));}
    @Test public void resultsAreNotSilentlyTruncatedByPickerCode(){ClipData data=ClipData.newRawUri("files",Uri.parse("content://files/0"));for(int i=1;i<301;i++)data.addItem(new ClipData.Item(Uri.parse("content://files/"+i)));Intent result=new Intent();result.setClipData(data);assertEquals(301,ComicInputPicker.selected(Activity.RESULT_OK,result).size());/* existing importer enforces 300 */}
    @Test public void chooserStaysAvailableRatherThanFollowingADefaultFileApp(){Intent chooser=ComicInputPicker.chooser(app);assertEquals(Intent.ACTION_CHOOSER,chooser.getAction());assertEquals(app.getString(R.string.input_choose_source),chooser.getCharSequenceExtra(Intent.EXTRA_TITLE).toString());Intent content=chooser.getParcelableExtra(Intent.EXTRA_INTENT);assertTrue(content.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE,false));}
}
