package com.kurdman.app;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.IDN;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Strict isolated-fetch building block, NOT connected to WebView. Public-only
 * DNS + numeric socket pinning; standard certificate and host verification.
 * GET only, no cookies, proxies, redirects, compression or automatic fallback.
 */
final class PinnedHttpsClient {
    static final int MAX_BODY = 32 * 1024 * 1024;
    private static final int MAX_HEADER_BYTES=16384, MAX_LINE=4096, TIMEOUT_MS=15000;
    static final class Response {
        final String contentType;
        final byte[] body;
        Response(String type,byte[] data){contentType=type;body=data;}
    }
    private PinnedHttpsClient() { }

    /** Closing an attached socket interrupts a blocked read/handshake. DNS or
     * a connect in progress may outlive cancellation; callers must bound the
     * number of such workers and must discard all late results. */
    static final class Cancellation {
        private boolean cancelled;
        private Socket socket;
        synchronized void attach(Socket value)throws IOException {
            if(cancelled){try{value.close();}catch(IOException ignored){}throw new IOException("Fetch cancelled");}
            socket=value;
        }
        synchronized void cancel(){
            cancelled=true;
            if(socket!=null)try{socket.close();}catch(IOException ignored){}
        }
        synchronized void check()throws IOException {if(cancelled)throw new IOException("Fetch cancelled");}
    }
    static Response get(String value,int maxBytes) throws IOException {return get(value,maxBytes,new Cancellation());}
    static Response get(String value,int maxBytes,Cancellation cancellation) throws IOException {
        if(cancellation==null)throw new IOException("Missing cancellation");
        cancellation.check();
        URI uri=checkedUrl(value);
        if(maxBytes<1 || maxBytes>MAX_BODY)throw new IOException("Invalid response limit");
        String host=uri.getHost().toLowerCase(Locale.ROOT);
        Socket plain=PublicNetworkGuard.connectDirect(host,443,TIMEOUT_MS);
        try {
            cancellation.attach(plain);
            SSLSocketFactory factory=(SSLSocketFactory)SSLSocketFactory.getDefault();
            try(SSLSocket tls=(SSLSocket)factory.createSocket(plain,host,443,true)) {
                tls.setSoTimeout(TIMEOUT_MS);
                SSLParameters parameters=tls.getSSLParameters();
                parameters.setEndpointIdentificationAlgorithm("HTTPS");
                tls.setSSLParameters(parameters);
                // JSSE verifies the endpoint against the original host during
                // this handshake. HttpsURLConnection's default verifier is a
                // fallback callback, not a standalone certificate validator.
                tls.startHandshake();
                String path=uri.getRawPath();if(path==null||path.isEmpty())path="/";
                if(uri.getRawQuery()!=null)path+="?"+uri.getRawQuery();
                String request="GET "+path+" HTTP/1.1\r\nHost: "+host+"\r\n"
                        +"Accept: text/html,image/png,image/jpeg,image/webp,image/bmp\r\n"
                        +"Accept-Encoding: identity\r\nConnection: close\r\n\r\n";
                OutputStream out=tls.getOutputStream();out.write(request.getBytes(StandardCharsets.US_ASCII));out.flush();
                return readResponse(tls.getInputStream(),maxBytes);
            }
        } finally { try{plain.close();}catch(IOException ignored){} }
    }

    static URI checkedUrl(String value) throws IOException {
        if(value==null||value.length()>8192)throw new IOException("Invalid HTTPS URL");
        try {
            URI uri=new URI(value);
            String host=uri.getHost();
            if(!"https".equalsIgnoreCase(uri.getScheme())||host==null||host.isEmpty()
                    ||uri.getRawUserInfo()!=null||uri.getRawFragment()!=null
                    ||(uri.getPort()!=-1&&uri.getPort()!=443)
                    ||!uri.toASCIIString().equals(value))throw new IOException("Invalid HTTPS URL");
            // DNS names only; disallow IP literals, ambiguous numeric hosts and
            // Unicode until their normalization/TLS behavior has device tests.
            String ascii=IDN.toASCII(host,IDN.USE_STD3_ASCII_RULES);
            if(!ascii.equalsIgnoreCase(host)||host.indexOf(':')>=0||host.endsWith(".")
                    ||!host.contains(".")||host.matches("[0-9.]+"))throw new IOException("Unsupported host");
            String path=uri.getRawPath();
            if(path!=null&&!path.isEmpty()&&!path.startsWith("/"))throw new IOException("Invalid request path");
            return uri;
        }catch(URISyntaxException|IllegalArgumentException bad){throw new IOException("Invalid HTTPS URL",bad);}
    }

    /** Strict HTTP/1.1 parsing; never follow redirects or use compressed data. */
    static Response readResponse(InputStream input,int limit) throws IOException {
        if(input==null||limit<1||limit>MAX_BODY)throw new IOException("Invalid response limit");
        int[] budget={0};
        String status=line(input,budget);
        if(!status.matches("HTTP/1\\.[01] 200(?: .*)?"))throw new IOException("Unsupported HTTP status");
        String contentType=null,transfer=null,encoding=null;
        long contentLength=-1;int count=0;
        while(true){
            String h=line(input,budget);if(h.isEmpty())break;
            if(++count>100||h.charAt(0)==' '||h.charAt(0)=='\t')throw new IOException("Invalid HTTP headers");
            int colon=h.indexOf(':');if(colon<=0)throw new IOException("Invalid HTTP header");
            String name=h.substring(0,colon).toLowerCase(Locale.ROOT),v=h.substring(colon+1).trim();
            if(!name.matches("[a-z0-9!#$%&'*+.^_`|~-]+"))throw new IOException("Invalid HTTP header");
            switch(name){
                case "content-length":
                    if(contentLength!=-1||!v.matches("[0-9]{1,10}"))throw new IOException("Invalid content length");
                    try{contentLength=Long.parseLong(v);}catch(NumberFormatException bad){throw new IOException("Invalid content length",bad);}
                    break;
                case "transfer-encoding":if(transfer!=null)throw new IOException("Duplicate transfer encoding");transfer=v.toLowerCase(Locale.ROOT);break;
                case "content-encoding":if(encoding!=null)throw new IOException("Duplicate content encoding");encoding=v.toLowerCase(Locale.ROOT);break;
                case "content-type":if(contentType!=null)throw new IOException("Duplicate content type");contentType=v;break;
                default: break;
            }
        }
        if(contentType==null||contentType.length()>200)throw new IOException("Missing content type");
        if(encoding!=null&&!"identity".equals(encoding))throw new IOException("Compressed response unsupported");
        if(transfer!=null&&(!"chunked".equals(transfer)||contentLength!=-1))throw new IOException("Ambiguous HTTP framing");
        if(contentLength>limit)throw new IOException("Response exceeds limit");
        ByteArrayOutputStream body=new ByteArrayOutputStream();
        if(transfer!=null){
            int[] chunkBudget={0};
            while(true){
                String size=line(input,chunkBudget);int semicolon=size.indexOf(';');if(semicolon>=0)size=size.substring(0,semicolon);
                if(!size.matches("[0-9a-fA-F]{1,8}"))throw new IOException("Invalid chunk size");
                long n=Long.parseLong(size,16);if(n>limit-body.size())throw new IOException("Response exceeds limit");
                if(n==0){while(!line(input,chunkBudget).isEmpty()){}break;}
                copyExact(input,body,(int)n);if(input.read()!='\r'||input.read()!='\n')throw new IOException("Invalid chunk ending");
            }
        }else if(contentLength>=0){copyExact(input,body,(int)contentLength);}
        else {
            byte[] buf=new byte[8192];int n;
            while((n=input.read(buf))!=-1){if(n>limit-body.size())throw new IOException("Response exceeds limit");body.write(buf,0,n);}
        }
        if(body.size()==0)throw new IOException("Empty response");
        return new Response(contentType,body.toByteArray());
    }
    private static String line(InputStream input,int[] budget)throws IOException {
        ByteArrayOutputStream line=new ByteArrayOutputStream();
        while(true){
            int b=input.read();if(b<0)throw new EOFException("Truncated HTTP response");
            if(++budget[0]>MAX_HEADER_BYTES)throw new IOException("HTTP headers too large");
            if(b=='\r'){if(input.read()!='\n')throw new IOException("Invalid HTTP line");if(++budget[0]>MAX_HEADER_BYTES)throw new IOException("HTTP headers too large");break;}
            if((b<32&&b!='\t')||b>126||line.size()>=MAX_LINE)throw new IOException("Invalid HTTP line");
            line.write(b);
        }
        return line.toString(StandardCharsets.ISO_8859_1.name());
    }
    private static void copyExact(InputStream input,OutputStream output,int count)throws IOException {
        byte[] buf=new byte[8192];int remaining=count;
        while(remaining>0){int n=input.read(buf,0,Math.min(remaining,buf.length));if(n<0)throw new EOFException("Truncated HTTP body");if(n==0)continue;output.write(buf,0,n);remaining-=n;}
    }
}
