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
        if("1".equals(System.getenv("KOORIX_VERIFY_PUBLIC_FIXTURE"))) {
            String temp=System.getenv("RUNNER_TEMP");if(temp==null)throw new IllegalStateException("Missing disposable runner temp");
            java.nio.file.Files.write(java.nio.file.Paths.get(temp,"koorix-public-extension-fixture.apk"),data);
        }
        System.out.println("Fixed public APK container fetched ("+data.length+" bytes); not installed or executed.");
    }
}
