package com.kurdman.app;

import static org.junit.Assert.*;
import android.app.Application;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** Synthetic rejection/cleanup only; not device APK signature or install proof. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=26,application=Application.class)
public class ExtensionApkStagerTest {
    private static final String APPROVED="ca978112ca1bbdcafac231b39a23dc4da786eff8147c4e72b9807785afee48bb";
    private static ExtensionStoreIndex.Entry entry(){return new ExtensionStoreIndex.Entry("Example","org.example.ext","https://example.org/a.apk","1.6",7,"1.6.7",1,Collections.emptyList());}
    @Test public void badArchiveIsNotStagedAndExistingFilesArePreserved()throws Exception {
        File cache=new File(RuntimeEnvironment.getApplication().getCacheDir(),"extension-stage-test");assertTrue(cache.mkdirs());
        File existing=new File(cache,"keep.txt");try(FileOutputStream out=new FileOutputStream(existing)){out.write("keep".getBytes(StandardCharsets.US_ASCII));}
        for(byte[] bytes:new byte[][]{new byte[]{1,2,3,4},new byte[]{'P','K',3,4,1,2,3,4}}){
            try{ExtensionApkStager.stage(bytes,entry(),APPROVED,cache,RuntimeEnvironment.getApplication().getPackageManager(),new PinnedHttpsClient.Cancellation());fail("Invalid APK accepted");}
            catch(Exception expected){}
            assertTrue(existing.isFile());assertEquals(4,existing.length());
            File dir=new File(cache,"extension-install");if(dir.exists())assertEquals(0,dir.listFiles().length);
        }
    }
    @Test public void oversizeAndCancellationFailWithoutLeavingApks()throws Exception {
        File cache=new File(RuntimeEnvironment.getApplication().getCacheDir(),"extension-stage-cancel");assertTrue(cache.mkdirs());
        byte[] oversized=new byte[ExtensionApkStager.MAX_APK_BYTES+1];oversized[0]='P';oversized[1]='K';oversized[2]=3;oversized[3]=4;
        try{ExtensionApkStager.stage(oversized,entry(),APPROVED,cache,RuntimeEnvironment.getApplication().getPackageManager(),new PinnedHttpsClient.Cancellation());fail();}
        catch(java.io.IOException expected){}
        PinnedHttpsClient.Cancellation cancel=new PinnedHttpsClient.Cancellation();cancel.cancel();
        try{ExtensionApkStager.stage(new byte[]{'P','K',3,4,1,2},entry(),APPROVED,cache,RuntimeEnvironment.getApplication().getPackageManager(),cancel);fail();}
        catch(java.io.IOException expected){}
        File dir=new File(cache,"extension-install");if(dir.exists())assertEquals(0,dir.listFiles().length);
    }
    @Test public void requiresExplicitValidFingerprintBeforeNetwork()throws Exception {
        try{ExtensionApkStager.prepare(entry(),"",RuntimeEnvironment.getApplication().getCacheDir(),RuntimeEnvironment.getApplication().getPackageManager(),1000);fail();}
        catch(java.io.IOException expected){}
    }
}
