package com.kurdman.app;

import static org.junit.Assert.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

/** Synthetic fetcher and bytes only, never a real network or image decode. */
public class StaticWebTransportTest {
    private final byte[] png={(byte)137,'P','N','G',13,10,26,10,1};
    private PinnedHttpsClient.Response response(String type,byte[] body,String url){return new PinnedHttpsClient.Response(200,type,body,null,url);}
    @Test public void pageUsesFinalRedirectUrlAsBaseAndDoesNotLoadResources()throws Exception {
        int[] requests={0};StaticWebTransport t=new StaticWebTransport((url,max,c)->{
            requests[0]++;return response("text/html","<img src='p.jpg'><a href='next'>next</a>".getBytes(StandardCharsets.UTF_8),"https://cdn.example.org/dir/end");});
        StaticWebPage page=t.page("https://example.org/start",new PinnedHttpsClient.Cancellation());
        assertEquals(1,requests[0]);assertEquals("https://cdn.example.org/dir/p.jpg",page.images.get(0));
        assertEquals("https://cdn.example.org/dir/next",page.links.get(0).url);
    }
    @Test public void acceptsOnlyMatchingDeclaredImageAndMagic()throws Exception {
        StaticWebTransport t=new StaticWebTransport((u,m,c)->response("image/png",png,"https://example.org/a"));
        assertEquals(".png",t.image("https://example.org/a",100,new PinnedHttpsClient.Cancellation()).extension);
        assertEquals("https://example.org/a",t.page("https://example.org/a",new PinnedHttpsClient.Cancellation()).images.get(0));
        for(String type:new String[]{"image/jpeg","image/svg+xml","text/html","application/octet-stream"}){
            try{StaticWebTransport.imageExtension(type,png);fail(type);}catch(IOException expected){}
        }
        try{StaticWebTransport.imageExtension("image/png",new byte[]{1,2,3});fail();}catch(IOException expected){}
    }
    @Test public void rejectsBadFinalUrlOversizeAndCancellation()throws Exception {
        PinnedHttpsClient.Cancellation cancel=new PinnedHttpsClient.Cancellation();cancel.cancel();
        StaticWebTransport valid=new StaticWebTransport((u,m,c)->response("image/png",png,"https://example.org/a"));
        try{valid.image("https://example.org/a",100,cancel);fail();}catch(IOException expected){}
        try{valid.image("http://example.org/a",100,new PinnedHttpsClient.Cancellation());fail();}catch(IOException expected){}
        StaticWebTransport bad=new StaticWebTransport((u,m,c)->response("image/png",png,"https://127.0.0.1/a"));
        try{bad.image("https://example.org/a",100,new PinnedHttpsClient.Cancellation());fail();}catch(IOException expected){}
        try{valid.image("https://example.org/a",png.length-1,new PinnedHttpsClient.Cancellation());fail();}catch(IOException expected){}
    }
}
