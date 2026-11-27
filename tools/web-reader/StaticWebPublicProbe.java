package com.kurdman.app;

/** Fixed public CC-BY comic fixture; no user pages, APK execution or image upload. */
public final class StaticWebPublicProbe {
    public static void main(String[] args)throws Exception {
        if(!"true".equals(System.getenv("GITHUB_ACTIONS"))||!"github-hosted".equals(System.getenv("RUNNER_ENVIRONMENT")))
            throw new IllegalStateException("Disposable hosted runner only");
        StaticWebTransport transport=new StaticWebTransport();
        StaticWebPage page=transport.page("https://www.peppercarrot.com/en/webcomic/ep01_Potion-of-Flight.html",new PinnedHttpsClient.Cancellation());
        if(page.images.isEmpty()||page.links.isEmpty())throw new IllegalStateException("Static public page lacks discovered links or images");
        boolean fetched=false;int attempted=0;
        for(String url:page.images){if(attempted++>=15)break;
            try{StaticWebTransport.Image image=transport.image(url,8*1024*1024,new PinnedHttpsClient.Cancellation());if(image.bytes.length>0){fetched=true;break;}}
            catch(java.io.IOException unavailable){}
        }
        if(!fetched)throw new IllegalStateException("No supported public image fetched in first 15 candidates");
        System.out.println("Static fixture: "+page.links.size()+" links, "+page.images.size()+" image references, one supported image fetched. No image uploaded, no Android/device/security acceptance.");
    }
}
