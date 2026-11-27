package com.kurdman.app;

import static org.junit.Assert.*;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/** Synthetic mediation only: does not prove Android TLS, isolation or extension execution. */
public class ExtensionNetworkBrokerTest {
    private static PinnedHttpsClient.Response ok(String url){return new PinnedHttpsClient.Response(200,"image/png",new byte[]{1,2,3},null,url);}
    @Test public void boundedGetForwardsOnlyApprovedSnapshot()throws Exception {
        Map<String,String> headers=new LinkedHashMap<>();headers.put("User-Agent","Test/1");headers.put("Referer","https://example.org/a");
        ExtensionNetworkBroker broker=new ExtensionNetworkBroker((url,max,c,h)->{
            assertEquals("Test/1",h.get("user-agent"));assertEquals("https://example.org/a",h.get("referer"));
            try{h.put("cookie","bad");fail();}catch(UnsupportedOperationException expected){}
            return ok(url);
        });
        assertEquals(3,broker.get("https://example.org/a",headers,10,1000).body.length);
        broker.close();try{broker.get("https://example.org/a",headers,10,1000);fail();}catch(IOException expected){}
    }
    @Test public void rejectsUnsafeInputsBeforeFetcher()throws Exception {
        AtomicBoolean called=new AtomicBoolean();ExtensionNetworkBroker broker=new ExtensionNetworkBroker((url,max,c,h)->{called.set(true);return ok(url);});
        for(String url:new String[]{"http://example.org/","https://127.0.0.1/","https://user@example.org/"}){
            try{broker.get(url,Collections.emptyMap(),10,1000);fail();}catch(IOException expected){}
        }
        try{broker.get("https://example.org/",Collections.singletonMap("Cookie","secret"),10,1000);fail();}catch(IOException expected){}
        try{broker.get("https://example.org/",Collections.emptyMap(),0,1000);fail();}catch(IOException expected){}
        try{broker.get("https://example.org/",Collections.emptyMap(),10,120001);fail();}catch(IOException expected){}
        assertFalse(called.get());broker.close();
    }
    @Test public void closeUnblocksCallerAndDiscardsLateResponse()throws Exception {
        CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1);AtomicReference<Throwable> result=new AtomicReference<>();
        ExtensionNetworkBroker broker=new ExtensionNetworkBroker((url,max,c,h)->{
            started.countDown();for(;;)try{release.await();break;}catch(InterruptedException ignored){}return ok(url);
        });
        Thread caller=new Thread(()->{try{broker.get("https://example.org/",Collections.emptyMap(),10,5000);result.set(new AssertionError("Late result returned"));}catch(IOException expected){result.set(expected);}});caller.start();
        try{assertTrue(started.await(3,TimeUnit.SECONDS));
            try{broker.get("https://example.org/",Collections.emptyMap(),10,1000);fail();}catch(IOException expected){}
            broker.close();caller.join(2000);assertFalse("Close must unblock",caller.isAlive());assertTrue(result.get() instanceof IOException);
        }finally{release.countDown();broker.close();caller.join(6000);}
    }
    @Test public void timeoutDiscardsLateResponse()throws Exception {
        CountDownLatch release=new CountDownLatch(1);ExtensionNetworkBroker broker=new ExtensionNetworkBroker((url,max,c,h)->{
            for(;;)try{release.await();break;}catch(InterruptedException ignored){}return ok(url);
        });
        try{try{broker.get("https://example.org/",Collections.emptyMap(),10,50);fail();}catch(IOException expected){}}
        finally{release.countDown();broker.close();}
    }
}
