package com.kurdman.app;

import static org.junit.Assert.*;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

/** Off-UI JVM checks only: not a device/network security proof. */
public class ExtensionCatalogClientTest {
    @Test public void returnsOnlyParsedMetadata()throws Exception {
        byte[] index=ExtensionStoreIndexTest.index(ExtensionStoreIndexTest.field(1,ExtensionStoreIndexTest.entry("org.example.ext")));
        ExtensionStoreIndex result=new ExtensionCatalogClient(cancel -> index).load(3000);
        assertEquals("Example store",result.name);assertEquals("org.example.ext",result.entries.get(0).packageName);
    }
    @Test public void noUnboundedQueueWhenResolverIgnoresInterrupts()throws Exception {
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        ExtensionCatalogClient client=new ExtensionCatalogClient(cancel -> {
            entered.countDown();
            while(true){try{release.await();break;}catch(InterruptedException ignored){/* emulate stuck DNS */}}
            return new byte[]{0};
        });
        try{
            try{client.load(150);fail("Expected timeout");}catch(IOException expected){assertTrue(expected.getMessage().contains("timed out"));}
            assertTrue(entered.await(1,TimeUnit.SECONDS));
            try{client.load(300);fail("Expected busy");}catch(IOException expected){assertTrue(expected.getMessage().contains("in progress"));}
        }finally{release.countDown();}
    }
    @Test public void invalidDataAndTimeoutFailClosed()throws Exception {
        ExtensionCatalogClient client=new ExtensionCatalogClient(cancel -> new byte[]{10,9,1});
        try{client.load(1000);fail("Expected parse failure");}catch(IOException expected){}
        try{client.load(0);fail("Expected invalid timeout");}catch(IOException expected){}
        try{client.load(60001);fail("Expected invalid timeout");}catch(IOException expected){}
    }
}
