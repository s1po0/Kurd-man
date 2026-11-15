package com.kurdman.app;

import static org.junit.Assert.*;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

/** Pure parser/URL contracts; these do not prove TLS or WebView isolation. */
public class PinnedHttpsClientTest {
    private static PinnedHttpsClient.Response parse(String wire,int limit) throws IOException {
        return PinnedHttpsClient.readResponse(new ByteArrayInputStream(wire.getBytes(StandardCharsets.ISO_8859_1)),limit);
    }
    private static void rejected(String wire,int limit) throws Exception {
        try {parse(wire,limit);fail("Expected response rejection");}catch(IOException expected){}
    }
    @Test public void allowsOnlyStrictHttpsDnsNamesAndDefaultPort() throws Exception {
        assertEquals("example.org",PinnedHttpsClient.checkedUrl("https://example.org/a?q=1").getHost());
        assertEquals("example.org",PinnedHttpsClient.checkedUrl("https://example.org:443/").getHost());
        for(String value:new String[]{"http://example.org/", "https://u:p@example.org/", "https://example.org:8443/",
                "https://127.0.0.1/", "https://[::1]/", "https://localhost/", "https://example.org/#secret",
                "https://example.org./", "https://example.org/\r\nHost: evil", "https://☃.test/", "file:///tmp/x"}) {
            try{PinnedHttpsClient.checkedUrl(value);fail(value);}catch(IOException expected){}
        }
    }
    @Test public void boundedLengthAndChunkedBodiesWork() throws Exception {
        PinnedHttpsClient.Response a=parse("HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nContent-Length: 5\r\n\r\nhello",5);
        assertEquals("image/png",a.contentType);assertEquals("hello",new String(a.body,StandardCharsets.US_ASCII));
        PinnedHttpsClient.Response b=parse("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nTransfer-Encoding: chunked\r\n\r\n2\r\nhe\r\n3;foo=bar\r\nllo\r\n0\r\n\r\n",5);
        assertEquals("hello",new String(b.body,StandardCharsets.US_ASCII));
    }
    @Test public void refusesRedirectCompressionAndAmbiguousFraming() throws Exception {
        rejected("HTTP/1.1 302 Found\r\nLocation: https://example.org/\r\n\r\n",10);
        rejected("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Encoding: gzip\r\n\r\nx",10);
        rejected("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: 1\r\nContent-Length: 1\r\n\r\nx",10);
        rejected("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: 1\r\nTransfer-Encoding: chunked\r\n\r\nx",10);
        rejected("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nTransfer-Encoding: gzip, chunked\r\n\r\nx",10);
    }
    @Test public void boundsAndTruncationCannotBecomeSuccessfulResponses() throws Exception {
        rejected("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: 5\r\n\r\nhello",4);
        rejected("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: 5\r\n\r\nhey",5);
        rejected("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nhello\r\n0\r\n\r\n",4);
        rejected("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nhe",5);
        rejected("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\n\r\n",1);
    }
    @Test public void rejectsMalformedHeaderNamesNewlinesAndNonAscii() throws Exception {
        rejected("HTTP/1.1 200 OK\nContent-Type: text/html\n\nx",10);
        rejected("HTTP/1.1 200 OK\r\nContent Type: text/html\r\n\r\nx",10);
        rejected("HTTP/1.1 200 OK\r\nContent-Type: text/html\u0080\r\n\r\nx",10);
        rejected("HTTP/1.1 200 OK\r\n Content-Type: text/html\r\n\r\nx",10);
    }
}
