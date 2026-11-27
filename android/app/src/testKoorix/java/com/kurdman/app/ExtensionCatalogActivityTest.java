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
        @Override ExtensionStoreIndex fetchCatalog() throws java.io.IOException {
            return ExtensionStoreIndex.parse(ExtensionStoreIndexTest.index(ExtensionStoreIndexTest.join(
                    ExtensionStoreIndexTest.field(1,ExtensionStoreIndexTest.entry("org.example.installed")),
                    ExtensionStoreIndexTest.field(1,ExtensionStoreIndexTest.entry("org.example.available")))));
        }
    }
    @Test public void installedSectionPrecedesAvailableAndSearchKeepsItFirst()throws Exception {
        org.robolectric.android.controller.ActivityController<FakeCatalog> controller=Robolectric.buildActivity(FakeCatalog.class).setup();
        try{
            FakeCatalog activity=controller.get();ListView list=activity.findViewById(R.id.extensionCatalogList);
            long end=System.currentTimeMillis()+5000;
            while(list.getAdapter().getCount()!=4 && System.currentTimeMillis()<end){Shadows.shadowOf(Looper.getMainLooper()).idle();Thread.sleep(10);}
            ListAdapter a=list.getAdapter();assertEquals(4,a.getCount());
            assertEquals(activity.getString(R.string.ext_installed),((TextView)a.getView(0,null,list)).getText().toString());
            assertEquals("Installed test",((TextView)a.getView(1,null,list)).getText().toString());
            assertEquals(activity.getString(R.string.ext_available),((TextView)a.getView(2,null,list)).getText().toString());
            EditText search=activity.findViewById(R.id.extensionCatalogSearch);search.setText("First site");
            assertEquals(4,a.getCount());assertEquals("Installed test",((TextView)a.getView(1,null,list)).getText().toString());
            search.setText("org.example.available");assertEquals(2,a.getCount());
            assertEquals(activity.getString(R.string.ext_available),((TextView)a.getView(0,null,list)).getText().toString());
        }finally{controller.pause().stop().destroy();}
    }
}
