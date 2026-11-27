package com.kurdman.app;

/** Fixed public store-only probe on disposable hosted runners; never installs APKs. */
public final class ExtensionCatalogLiveProbe {
    public static void main(String[] args)throws Exception {
        if(!"true".equals(System.getenv("GITHUB_ACTIONS"))||!"github-hosted".equals(System.getenv("RUNNER_ENVIRONMENT")))
            throw new IllegalStateException("Only disposable hosted runner allowed");
        ExtensionStoreIndex result=new ExtensionCatalogClient().load(30000);
        if(!"Keiyoushi".equals(result.name)||result.entries.isEmpty())throw new IllegalStateException("Unexpected index");
        PinnedHttpsClient.Response redirected=PinnedHttpsClient.getFollowingRedirects(
                "https://github.com/keiyoushi/extensions/raw/repo/index.pb",ExtensionStoreIndex.MAX_COMPRESSED,new PinnedHttpsClient.Cancellation());
        ExtensionStoreIndex viaRedirect=ExtensionStoreIndex.parse(redirected.body);
        if(!"Keiyoushi".equals(viaRedirect.name)||viaRedirect.entries.isEmpty())throw new IllegalStateException("Unexpected redirect response");
        System.out.println("Live public index parsed: "+result.entries.size()+" extensions, including guarded redirect; no signer trust or Android acceptance implied.");
    }
}
