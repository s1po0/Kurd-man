package com.kurdman.app;

import static org.junit.Assert.*;
import android.app.Application;
import android.content.pm.PackageManager;
import android.os.Looper;
import android.widget.EditText;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.TextView;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

/** Native Views with synthetic catalog and local inventory; no emulator or real extension execution. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=34,application=Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class ExtensionCatalogActivityTest {
    public static class FakeCatalog extends ExtensionCatalogActivity {
        @Override List<ExtensionLibrarySections.Installed> scanInstalled(PackageManager pm){
            return Collections.singletonList(new ExtensionLibrarySections.Installed("org.example.installed","Installed test"));
        }
        @Override List<ExtensionLibrarySections.Installed> scanImported(){return Collections.emptyList();}
        @Override ExtensionStoreIndex fetchCatalog() throws java.io.IOException {
            return ExtensionStoreIndex.parse(ExtensionStoreIndexTest.index(ExtensionStoreIndexTest.join(
                    ExtensionStoreIndexTest.field(1,ExtensionStoreIndexTest.entry("org.example.installed")),
                    ExtensionStoreIndexTest.field(1,ExtensionStoreIndexTest.entry("org.example.available")))));
        }
    }
    public static class ImportedArchive extends FakeCatalog {
        @Override List<ExtensionLibrarySections.Installed> scanImported(){
            return Collections.singletonList(new ExtensionLibrarySections.Installed("org.example.available","org.example.available",false,true));
        }
    }
    public static class StoreOffline extends FakeCatalog {
        @Override ExtensionStoreIndex fetchCatalog() throws java.io.IOException {throw new java.io.IOException("offline");}
    }
    public static class InventoryUnavailable extends FakeCatalog {
        @Override List<ExtensionLibrarySections.Installed> scanInstalled(PackageManager pm){throw new SecurityException("unavailable");}
    }
    private static void awaitItems(ListView list,int count)throws Exception {
        long end=System.currentTimeMillis()+5000;
        while(list.getAdapter().getCount()!=count && System.currentTimeMillis()<end){Shadows.shadowOf(Looper.getMainLooper()).idle();Thread.sleep(10);}
        assertEquals(count,list.getAdapter().getCount());
    }
    @Test public void installedSectionPrecedesAvailableAndSearchKeepsItFirst()throws Exception {
        org.robolectric.android.controller.ActivityController<FakeCatalog> controller=Robolectric.buildActivity(FakeCatalog.class).setup();
        try{
            FakeCatalog activity=controller.get();ListView list=activity.findViewById(R.id.extensionCatalogList);
            awaitItems(list,4);
            ListAdapter a=list.getAdapter();
            assertEquals(activity.getString(R.string.ext_installed),((TextView)a.getView(0,null,list)).getText().toString());
            assertEquals("Installed test",((TextView)a.getView(1,null,list)).getText().toString());
            assertEquals(activity.getString(R.string.ext_available),((TextView)a.getView(2,null,list)).getText().toString());
            EditText search=activity.findViewById(R.id.extensionCatalogSearch);search.setText("First site");
            assertEquals(4,a.getCount());assertEquals("Installed test",((TextView)a.getView(1,null,list)).getText().toString());
            search.setText("org.example.available");assertEquals(2,a.getCount());
            assertEquals(activity.getString(R.string.ext_available),((TextView)a.getView(0,null,list)).getText().toString());
        }finally{controller.pause().stop().destroy();}
    }
    @Test public void importedArchiveIsMarkedAndAppearsWithLocalEntries()throws Exception {
        org.robolectric.android.controller.ActivityController<ImportedArchive> controller=Robolectric.buildActivity(ImportedArchive.class).setup();
        try{ImportedArchive activity=controller.get();ListView list=activity.findViewById(R.id.extensionCatalogList);awaitItems(list,3);
            ListAdapter a=list.getAdapter();assertEquals(activity.getString(R.string.ext_installed),((TextView)a.getView(0,null,list)).getText().toString());
            int importedPosition=-1;for(int i=1;i<a.getCount();i++)if(((TextView)a.getView(i,null,list)).getText().toString().contains(activity.getString(R.string.ext_imported_label)))importedPosition=i;
            assertTrue(importedPosition>0);list.performItemClick(a.getView(importedPosition,null,list),importedPosition,importedPosition);
            androidx.appcompat.app.AlertDialog dialog=(androidx.appcompat.app.AlertDialog)org.robolectric.shadows.ShadowDialog.getLatestDialog();
            assertTrue(((TextView)dialog.findViewById(android.R.id.message)).getText().toString().contains(activity.getString(R.string.ext_imported_details)));
        }finally{controller.pause().stop().destroy();}
    }
    @Test public void sourceWebsiteOpensOnlyStaticReaderWithoutRunningExtension()throws Exception {
        org.robolectric.android.controller.ActivityController<FakeCatalog> controller=Robolectric.buildActivity(FakeCatalog.class).setup();
        try{FakeCatalog activity=controller.get();ListView list=activity.findViewById(R.id.extensionCatalogList);awaitItems(list,4);
            list.performItemClick(list.getAdapter().getView(1,null,list),1,1);
            androidx.appcompat.app.AlertDialog details=(androidx.appcompat.app.AlertDialog)org.robolectric.shadows.ShadowDialog.getLatestDialog();
            assertEquals(activity.getString(R.string.ext_open_site),details.getButton(android.content.DialogInterface.BUTTON_NEUTRAL).getText().toString());
            details.getButton(android.content.DialogInterface.BUTTON_NEUTRAL).performClick();Shadows.shadowOf(Looper.getMainLooper()).idle();
            androidx.appcompat.app.AlertDialog sites=(androidx.appcompat.app.AlertDialog)org.robolectric.shadows.ShadowDialog.getLatestDialog();
            ListView choices=sites.getListView();assertEquals(2,choices.getCount());choices.performItemClick(choices.getAdapter().getView(0,null,choices),0,0);
            org.robolectric.shadows.ShadowActivity.IntentForResult launch=Shadows.shadowOf(activity).getNextStartedActivityForResult();assertNotNull(launch);
            android.content.Intent intent=launch.intent;
            assertEquals(StaticWebReaderActivity.class.getName(),intent.getComponent().getClassName());
            assertEquals("https://example.org",intent.getStringExtra(StaticWebReaderActivity.EXTRA_START_URL));
            android.content.Intent output=new android.content.Intent().putStringArrayListExtra(WebReaderActivity.EXTRA_FILES,new java.util.ArrayList<>(Collections.singletonList("/synthetic")));
            assertTrue(activity.getActivityResultRegistry().dispatchResult(launch.requestCode,android.app.Activity.RESULT_OK,output));
            assertTrue(activity.isFinishing());assertEquals(output,Shadows.shadowOf(activity).getResultIntent());
        }finally{controller.pause().stop().destroy();}
    }
    @Test public void signerMustBeExplicitlyApprovedBeforeAnyInstallerHandoff()throws Exception {
        org.robolectric.android.controller.ActivityController<FakeCatalog> controller=Robolectric.buildActivity(FakeCatalog.class).setup();
        try{
            FakeCatalog activity=controller.get();ListView list=activity.findViewById(R.id.extensionCatalogList);awaitItems(list,4);
            list.performItemClick(list.getAdapter().getView(1,null,list),1,1);
            androidx.appcompat.app.AlertDialog details=(androidx.appcompat.app.AlertDialog)org.robolectric.shadows.ShadowDialog.getLatestDialog();
            assertEquals(activity.getString(R.string.ext_prepare),details.getButton(android.content.DialogInterface.BUTTON_POSITIVE).getText().toString());
            details.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            androidx.appcompat.app.AlertDialog trust=(androidx.appcompat.app.AlertDialog)org.robolectric.shadows.ShadowDialog.getLatestDialog();
            assertTrue(trust.isShowing());
            assertEquals(activity.getString(R.string.ext_approve_signer),trust.getButton(android.content.DialogInterface.BUTTON_POSITIVE).getText().toString());
            trust.getButton(android.content.DialogInterface.BUTTON_NEGATIVE).performClick();
            assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
        }finally{controller.pause().stop().destroy();}
    }
    @Test public void offlineStoreRetainsInstalledSection()throws Exception {
        org.robolectric.android.controller.ActivityController<StoreOffline> controller=Robolectric.buildActivity(StoreOffline.class).setup();
        try{
            StoreOffline activity=controller.get();ListView list=activity.findViewById(R.id.extensionCatalogList);awaitItems(list,2);
            assertEquals(activity.getString(R.string.ext_store_unavailable),((TextView)activity.findViewById(R.id.extensionCatalogStatus)).getText().toString());
            assertEquals(activity.getString(R.string.ext_installed),((TextView)list.getAdapter().getView(0,null,list)).getText().toString());
        }finally{controller.pause().stop().destroy();}
    }
    @Test public void unknownInventoryIsNotMislabelledAsNotInstalled()throws Exception {
        org.robolectric.android.controller.ActivityController<InventoryUnavailable> controller=Robolectric.buildActivity(InventoryUnavailable.class).setup();
        try{
            InventoryUnavailable activity=controller.get();ListView list=activity.findViewById(R.id.extensionCatalogList);awaitItems(list,3);
            assertEquals(activity.getString(R.string.ext_inventory_unavailable),((TextView)activity.findViewById(R.id.extensionCatalogStatus)).getText().toString());
            assertEquals(activity.getString(R.string.ext_catalog_unknown),((TextView)list.getAdapter().getView(0,null,list)).getText().toString());
            list.performItemClick(list.getAdapter().getView(1,null,list),1,1);
            androidx.appcompat.app.AlertDialog details=(androidx.appcompat.app.AlertDialog)org.robolectric.shadows.ShadowDialog.getLatestDialog();
            assertEquals(activity.getString(android.R.string.ok),details.getButton(android.content.DialogInterface.BUTTON_POSITIVE).getText().toString());
        }finally{controller.pause().stop().destroy();}
    }
}
