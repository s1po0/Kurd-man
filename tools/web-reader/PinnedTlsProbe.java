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
        System.out.println("Pinned HTTPS public GET succeeded; not Android or WebView security acceptance.");
    }
}
