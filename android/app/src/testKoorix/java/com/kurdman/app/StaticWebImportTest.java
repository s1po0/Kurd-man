package com.kurdman.app;

import static org.junit.Assert.*;
import android.app.Application;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** Synthetic transport and validator; no Android TLS, actual bitmap or device claim. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk={26,34},application=Application.class)
public class StaticWebImportTest {
    private static final byte[] PNG={(byte)137,'P','N','G',13,10,26,10,1};
    private File cache(String name)throws Exception {File f=new File(RuntimeEnvironment.getApplication().getCacheDir(),name);assertTrue(f.mkdirs());return f;}
    private PinnedHttpsClient.Response ok(String url){return new PinnedHttpsClient.Response(200,"image/png",PNG,null,url);}
    @Test public void preservesOrderSkipsBadImagesAndCleansOnlyItsOwnSession()throws Exception {
        File cache=cache("static-test-one");File prior=new File(cache,"keep.txt");try(FileOutputStream out=new FileOutputStream(prior)){out.write(1);}
        StaticWebTransport transport=new StaticWebTransport((url,max,c)->{if(url.endsWith("/bad"))throw new IOException("failed");return ok(url);});
        StaticWebImport importer=new StaticWebImport(transport,(bytes,ext)->true);
        StaticWebImport.Result result=importer.capture(Arrays.asList("https://example.org/first","https://example.org/bad","https://example.org/last","https://example.org/first"),cache,new PinnedHttpsClient.Cancellation(),null);
        assertEquals(2,result.files.size());assertEquals(1,result.failed);assertEquals("0001.png",result.files.get(0).getName());assertEquals("0003.png",result.files.get(1).getName());
        result.discard(cache);assertFalse(result.session.exists());assertTrue(prior.isFile());
    }
    @Test @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    public void validatesDecodedImageBoundsNotJustMagic()throws Exception {
        byte[] png=java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");
        assertTrue(StaticWebImport.validImage(png,".png"));assertFalse(StaticWebImport.validImage(png,".jpg"));
        assertFalse(StaticWebImport.validImage(PNG,".png"));
    }
    @Test public void cancellationAndInvalidImageLeaveNoNewSession()throws Exception {
        File cache=cache("static-test-two");PinnedHttpsClient.Cancellation cancel=new PinnedHttpsClient.Cancellation();
        StaticWebTransport transport=new StaticWebTransport((url,max,c)->{c.cancel();return ok(url);});
        try{new StaticWebImport(transport,(b,e)->true).capture(Arrays.asList("https://example.org/x"),cache,cancel,null);fail();}catch(IOException expected){}
        File root=new File(cache,"web-reader");assertTrue(root.isDirectory());assertEquals(0,root.listFiles().length);
        StaticWebImport invalid=new StaticWebImport(new StaticWebTransport((url,max,c)->ok(url)),(b,e)->false);
        try{invalid.capture(Arrays.asList("https://example.org/x"),cache,new PinnedHttpsClient.Cancellation(),null);fail();}catch(IOException expected){}
        assertEquals(0,root.listFiles().length);
    }
}
