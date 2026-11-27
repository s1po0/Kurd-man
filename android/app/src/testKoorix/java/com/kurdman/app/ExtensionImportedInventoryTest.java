package com.kurdman.app;

import static org.junit.Assert.*;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Synthetic archives and inspectors, not APK verification or execution. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=26,application=android.app.Application.class)
public class ExtensionImportedInventoryTest {
    @Rule public TemporaryFolder root=new TemporaryFolder();
    private static final byte[] APK={'P','K',3,4,1,2,3};
    private static final ExtensionImportedInventory.Inspector INSPECTOR=file->"org.example.extension";
    @Test public void explicitImportIsPrivateDeduplicatedAndNeverDeletesOriginal()throws Exception {
        File files=root.newFolder("files");File original=root.newFile("original.apk");Files.write(original.toPath(),APK);
        try(java.io.InputStream in=Files.newInputStream(original.toPath())){assertEquals("org.example.extension",ExtensionImportedInventory.store(in,files,INSPECTOR));}
        File dir=ExtensionImportedInventory.directory(files);assertEquals(1,dir.listFiles().length);
        ExtensionImportedInventory.store(new ByteArrayInputStream(APK),files,INSPECTOR);
        assertEquals(1,dir.listFiles().length);assertArrayEquals(APK,Files.readAllBytes(original.toPath()));
        List<ExtensionLibrarySections.Installed> local=ExtensionImportedInventory.scan(dir,INSPECTOR);
        assertEquals(1,local.size());assertTrue(local.get(0).imported);assertFalse(local.get(0).systemInstalled);
    }
    @Test public void invalidArchivesAndMetadataLeaveNoPartialCopy()throws Exception {
        File files=root.newFolder("files");
        try{ExtensionImportedInventory.store(new ByteArrayInputStream(new byte[]{1,2,3}),files,INSPECTOR);fail();}catch(IOException expected){}
        try{ExtensionImportedInventory.store(new ByteArrayInputStream(APK),files,f->"bad package");fail();}catch(IOException expected){}
        try{ExtensionImportedInventory.store(new ByteArrayInputStream(APK),files,f->{throw new IOException("invalid");});fail();}catch(IOException expected){}
        assertEquals(0,ExtensionImportedInventory.directory(files).listFiles().length);
    }
    @Test public void modifiedStoredFileFailsClosedWithoutDeletingIt()throws Exception {
        File files=root.newFolder("files");ExtensionImportedInventory.store(new ByteArrayInputStream(APK),files,INSPECTOR);
        File dir=ExtensionImportedInventory.directory(files),stored=dir.listFiles()[0];stored.setWritable(true);
        try(FileOutputStream out=new FileOutputStream(stored)){out.write(new byte[]{'P','K',3,4,9});}
        try{ExtensionImportedInventory.scan(dir,INSPECTOR);fail();}catch(IOException expected){}
        assertTrue(stored.exists());
    }
    @Test public void symlinkedStorageIsRejected()throws Exception {
        File files=root.newFolder("files"),other=root.newFolder("other");
        Files.createSymbolicLink(new File(files,"imported-extension-apks").toPath(),other.toPath());
        try{ExtensionImportedInventory.store(new ByteArrayInputStream(APK),files,INSPECTOR);fail();}catch(IOException expected){}
        assertEquals(0,other.listFiles().length);
    }
    @Test public void importedAndSystemPackagesAreMergedWithoutClaimingTrust(){
        ExtensionLibrarySections.Installed installed=new ExtensionLibrarySections.Installed("org.example.extension","Installed label");
        ExtensionLibrarySections.Installed imported=new ExtensionLibrarySections.Installed("org.example.extension","Imported label",false,true);
        List<ExtensionLibrarySections.Installed> merged=ExtensionLibrarySections.mergeLocal(Collections.singletonList(installed),Arrays.asList(imported,imported));
        assertEquals(1,merged.size());assertTrue(merged.get(0).systemInstalled);assertTrue(merged.get(0).imported);assertEquals("Installed label",merged.get(0).displayName);
    }
}
