package com.kurdman.app;

import java.io.IOException;
import java.util.Locale;

/**
 * Static-page candidate transport, not connected to WebView. Every production
 * request uses public-only pinned HTTPS and bounded redirects. No page JavaScript,
 * cookies, implicit subresource requests, forms, or network fallback are used.
 * The public socket primitive still requires Android/device security review.
 */
final class StaticWebTransport {
    interface Fetcher {
        PinnedHttpsClient.Response fetch(String url,int limit,PinnedHttpsClient.Cancellation cancel)throws IOException;
    }
    static final int MAX_IMAGE=32*1024*1024;
    private final Fetcher fetcher;
    StaticWebTransport(){this(PinnedHttpsClient::getFollowingRedirects);}
    StaticWebTransport(Fetcher fetcher){if(fetcher==null)throw new IllegalArgumentException();this.fetcher=fetcher;}
    StaticWebPage page(String url,PinnedHttpsClient.Cancellation cancel)throws IOException {
        // Direct image URLs may exceed the smaller HTML parsing limit.
        PinnedHttpsClient.Response response=request(url,MAX_IMAGE,cancel);
        String type=mediaType(response.contentType);
        if(type.startsWith("image/")){
            imageExtension(response.contentType,response.body); // no blind image URL promotion
            return StaticWebPage.singleImage(response.finalUrl);
        }
        return StaticWebPage.parse(response.finalUrl,response.contentType,response.body);
    }
    Image image(String url,int maxBytes,PinnedHttpsClient.Cancellation cancel)throws IOException {
        if(maxBytes<1||maxBytes>MAX_IMAGE)throw new IOException("Invalid image budget");
        PinnedHttpsClient.Response response=request(url,maxBytes,cancel);
        return new Image(response.body,imageExtension(response.contentType,response.body),response.finalUrl);
    }
    private PinnedHttpsClient.Response request(String url,int limit,PinnedHttpsClient.Cancellation cancel)throws IOException {
        PinnedHttpsClient.checkedUrl(url);
        if(cancel==null)throw new IOException("Missing cancellation");cancel.check();
        PinnedHttpsClient.Response response=fetcher.fetch(url,limit,cancel);
        cancel.check();
        if(response==null||response.status!=200||response.body==null||response.body.length==0||response.body.length>limit||response.finalUrl==null)
            throw new IOException("Invalid response");
        PinnedHttpsClient.checkedUrl(response.finalUrl);
        return response;
    }
    static final class Image {
        final byte[] bytes;final String extension,finalUrl;
        Image(byte[] bytes,String extension,String finalUrl){this.bytes=bytes;this.extension=extension;this.finalUrl=finalUrl;}
    }
    private static String mediaType(String type)throws IOException {
        if(type==null||type.length()>200)throw new IOException("Missing media type");
        return type.split(";",2)[0].trim().toLowerCase(Locale.ROOT);
    }
    static String imageExtension(String type,byte[] data)throws IOException {
        String mime=mediaType(type);if(data==null)throw new IOException("Missing image");
        if("image/jpeg".equals(mime)&&data.length>=3&&(data[0]&255)==255&&(data[1]&255)==216&&(data[2]&255)==255)return ".jpg";
        if("image/png".equals(mime)&&data.length>=8&&(data[0]&255)==137&&data[1]=='P'&&data[2]=='N'&&data[3]=='G'&&data[4]==13&&data[5]==10&&data[6]==26&&data[7]==10)return ".png";
        if("image/webp".equals(mime)&&data.length>=12&&data[0]=='R'&&data[1]=='I'&&data[2]=='F'&&data[3]=='F'&&data[8]=='W'&&data[9]=='E'&&data[10]=='B'&&data[11]=='P')return ".webp";
        if("image/bmp".equals(mime)&&data.length>=2&&data[0]=='B'&&data[1]=='M')return ".bmp";
        throw new IOException("Unsupported or mismatched image");
    }
}
