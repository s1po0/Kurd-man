package com.kurdman.app;

/** Fetches one fixed public fixture on disposable CI; never installs or executes it. */
public final class ExtensionApkDownloadProbe {
    public static void main(String[] args)throws Exception {
        if(!"true".equals(System.getenv("GITHUB_ACTIONS"))||!"github-hosted".equals(System.getenv("RUNNER_ENVIRONMENT")))
            throw new IllegalStateException("Disposable hosted runner only");
        String url="https://github.com/keiyoushi/extensions/releases/download/b4f9106-0/tachiyomi-all.achymanga-v1.6.1.apk";
        byte[] data=PinnedHttpsClient.getFollowingRedirects(url,32*1024*1024,new PinnedHttpsClient.Cancellation()).body;
        if(data==null||data.length<4||data[0]!='P'||data[1]!='K'||data[2]!=3||data[3]!=4)
            throw new IllegalStateException("Fixed public response is not an APK container");
        System.out.println("Fixed public APK container fetched ("+data.length+" bytes); not installed, executed, or signature verified.");
    }
}
