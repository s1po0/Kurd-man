package com.kurdman.app;

import static org.junit.Assert.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

/** Static, network-free fixtures; not proof of Android TLS or live site support. */
public class StaticWebPageTest {
    private StaticWebPage parse(String html)throws Exception{return StaticWebPage.parse("https://example.org/chap/one","text/html; charset=UTF-8",html.getBytes(StandardCharsets.UTF_8));}
    @Test public void findsExplicitLinksAndLazyImagesWithoutExecutingPage()throws Exception {
        StaticWebPage page=parse("<title> A \u202eTitle </title><base href='https://evil.example/'><script>fetch('https://127.0.0.1/')</script>"+
                "<a href='../next#page'> Next chapter </a><a href='../next'>duplicate</a>"+
                "<img src='placeholder.gif' data-src='/images/01.jpg'><img data-original='https://cdn.example.org/02.png'>"+
                "<form><img src='/form.png'></form><iframe src='https://evil.example/'></iframe>");
        assertEquals("A Title",page.title);assertEquals(1,page.links.size());
        assertEquals("Next chapter",page.links.get(0).label);assertEquals("https://example.org/next",page.links.get(0).url);
        assertEquals(2,page.images.size());assertEquals("https://example.org/images/01.jpg",page.images.get(0));
        assertEquals("https://cdn.example.org/02.png",page.images.get(1));assertFalse(page.truncated);
    }
    @Test public void handlesBoundedResponsiveAndLazyImageSets()throws Exception {
        StaticWebPage page=parse("<img src='/fallback.png' srcset='/small.jpg 320w, /large.jpg 1280w'>"+
                "<img data-srcset='/one.jpg 1x, /two.jpg 2x' src='/other.jpg'>"+
                "<img srcset='/invalid.jpg 2w, /mixed.jpg 3x' src='/safe.jpg'>"+
                "<img srcset='http://example.org/a 1x, https://cdn.example.org/a 2x'>");
        assertEquals(4,page.images.size());assertEquals("https://example.org/large.jpg",page.images.get(0));
        assertEquals("https://example.org/two.jpg",page.images.get(1));assertEquals("https://example.org/safe.jpg",page.images.get(2));
        assertEquals("https://cdn.example.org/a",page.images.get(3));
    }
    @Test public void rejectsUnsafeUrlsAndDoesNotFetchAnything()throws Exception {
        StaticWebPage page=parse("<a href='javascript:alert(1)'>x</a><a href='https://user:pass@example.org/x'>x</a>"+
                "<a href='file:///etc/passwd'>x</a><a href='https://127.0.0.1/x'>x</a><a href='#local'>x</a>"+
                "<img src='http://example.org/x'><img src='data:image/png;base64,AAAA'><img src='https://example.org:444/x'>");
        assertTrue(page.images.isEmpty());assertTrue(page.links.isEmpty());
    }
    @Test public void limitsReturnedDataAndSanitizesLabels()throws Exception {
        StringBuilder html=new StringBuilder();for(int i=0;i<350;i++)html.append("<img src='/").append(i).append(".jpg'>");
        html.append("<a href='/target'>\u2066hello\u2069<br>world</a>");
        StaticWebPage page=parse(html.toString());assertEquals(300,page.images.size());assertTrue(page.truncated);
        assertEquals("hello world",page.links.get(0).label);
        try{page.images.clear();fail();}catch(UnsupportedOperationException expected){}
    }
    @Test public void rejectsBadContentTypeCharsetBytesAndSize()throws Exception {
        for(String type:new String[]{"image/jpeg","text/html; charset=iso-8859-1","text/plain"}){
            try{StaticWebPage.parse("https://example.org/",type,new byte[]{65});fail();}catch(IOException expected){}
        }
        try{StaticWebPage.parse("https://example.org/","text/html",new byte[]{(byte)0xc3,0x28});fail();}catch(IOException expected){}
        try{StaticWebPage.parse("https://example.org/","text/html",new byte[StaticWebPage.MAX_HTML+1]);fail();}catch(IOException expected){}
    }
    @Test public void limitsTraversal()throws Exception {
        StringBuilder html=new StringBuilder("<body>");for(int i=0;i<21000;i++)html.append("<i>x</i>");html.append("<img src='/late.jpg'>");
        StaticWebPage page=parse(html.toString());assertTrue(page.truncated);assertTrue(page.images.isEmpty());
    }
}
