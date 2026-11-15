package com.kurdman.app;

/** Executed only by the disposable hosted-emulator script; fixed public host. */
public final class AndroidPinnedTlsProbe {
    public static void main(String[] args) throws Exception {
        System.out.println("ANDROID_PINNED_TLS_STAGE=starting-public-get");
        PinnedHttpsClient.Response response=PinnedHttpsClient.get("https://github.com/robots.txt",128*1024);
        if(!response.contentType.toLowerCase(java.util.Locale.ROOT).startsWith("text/plain") || response.body.length==0)
            throw new IllegalStateException("Unexpected public TLS response");
        System.out.println("ANDROID_PINNED_TLS_STAGE=starting-wrong-host");
        boolean mismatch=false;
        try(java.net.Socket plain=PublicNetworkGuard.connectDirect("github.com",443,15000)) {
            javax.net.ssl.SSLSocketFactory factory=(javax.net.ssl.SSLSocketFactory)javax.net.ssl.SSLSocketFactory.getDefault();
            try(javax.net.ssl.SSLSocket tls=(javax.net.ssl.SSLSocket)factory.createSocket(plain,"www.google.com",443,true)) {
                tls.setSoTimeout(15000);
                javax.net.ssl.SSLParameters p=tls.getSSLParameters();p.setEndpointIdentificationAlgorithm("HTTPS");tls.setSSLParameters(p);
                try {tls.startHandshake();}
                catch(javax.net.ssl.SSLHandshakeException expected) {
                    String s=expected.toString();Throwable cause=expected.getCause();
                    while(cause!=null){s+=" "+cause.toString();cause=cause.getCause();}
                    if(!s.contains("www.google.com"))throw new IllegalStateException("Wrong-host probe did not reach certificate hostname rejection",expected);
                    mismatch=true;
                }
            }
        }
        if(!mismatch)throw new IllegalStateException("Wrong TLS hostname accepted");
        System.out.println("ANDROID_PINNED_TLS_PROBE=public-get-and-wrong-host-rejection");
    }
}
