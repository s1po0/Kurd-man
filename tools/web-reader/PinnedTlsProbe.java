package com.kurdman.app;

/** Fixed, public, hosted-CI-only connectivity probe; no reader data or secrets. */
public final class PinnedTlsProbe {
    public static void main(String[] args) throws Exception {
        if (!"true".equals(System.getenv("GITHUB_ACTIONS"))
                || !"github-hosted".equals(System.getenv("RUNNER_ENVIRONMENT")))
            throw new IllegalStateException("Only a disposable hosted runner may use this probe");
        PinnedHttpsClient.Response response = PinnedHttpsClient.get("https://github.com/robots.txt", 128*1024);
        if (!response.contentType.toLowerCase(java.util.Locale.ROOT).startsWith("text/plain") || response.body.length == 0)
            throw new IllegalStateException("Public TLS fixture response did not match");
        // Prove the JSSE endpoint identification is actually enforced on the
        // host provider, not merely that a flag can be set. The socket still
        // targets the verified public github.com address; the deliberately
        // incorrect TLS peer hostname must not pass its handshake.
        boolean refused = false;
        try (java.net.Socket plain = PublicNetworkGuard.connectDirect("github.com",443,15000)) {
            javax.net.ssl.SSLSocketFactory factory=(javax.net.ssl.SSLSocketFactory)javax.net.ssl.SSLSocketFactory.getDefault();
            try (javax.net.ssl.SSLSocket tls=(javax.net.ssl.SSLSocket)factory.createSocket(plain,"wrong-koorix-test.invalid",443,true)) {
                tls.setSoTimeout(15000);
                javax.net.ssl.SSLParameters parameters=tls.getSSLParameters();
                parameters.setEndpointIdentificationAlgorithm("HTTPS");
                parameters.setServerNames(java.util.Collections.singletonList(new javax.net.ssl.SNIHostName("github.com")));
                tls.setSSLParameters(parameters);
                try { tls.startHandshake(); } catch (javax.net.ssl.SSLHandshakeException expected) {
                    String message=expected.getMessage();
                    refused=message!=null && message.contains("wrong-koorix-test.invalid");
                }
            }
        }
        if (!refused) throw new IllegalStateException("Wrong TLS hostname was accepted");
        System.out.println("Pinned HTTPS and wrong-host rejection succeeded; not Android or WebView security acceptance.");
    }
}
