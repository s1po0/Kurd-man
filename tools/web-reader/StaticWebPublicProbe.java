package com.kurdman.app;

/** Fixed public CC-BY comic fixture; no user pages, APK execution or image upload. */
public final class StaticWebPublicProbe {
    public static void main(String[] args)throws Exception {
        if(!"true".equals(System.getenv("GITHUB_ACTIONS"))||!"github-hosted".equals(System.getenv("RUNNER_ENVIRONMENT")))
            throw new IllegalStateException("Disposable hosted runner only");
        StaticWebTransport transport=new StaticWebTransport();
        StaticWebPage page=transport.page("https://www.peppercarrot.com/en/webcomic/ep01_Potion-of-Flight.html",new PinnedHttpsClient.Cancellation());
        if(page.images.isEmpty()||page.links.isEmpty())throw new IllegalStateException("Static public page lacks discovered links or images");
        String prefix="https://www.peppercarrot.com/0_sources/ep01_Potion-of-Flight/low-res/en_Pepper-and-Carrot_by-David-Revoy_E01P";
        int first=page.images.indexOf(prefix+"01.jpg"),second=page.images.indexOf(prefix+"02.jpg"),third=page.images.indexOf(prefix+"03.jpg");
        if(first<0||second<=first||third<=second)throw new IllegalStateException("Three fixed public story pages missing or out of order");
        StaticWebTransport.Image image=transport.image(prefix+"01.jpg",8*1024*1024,new PinnedHttpsClient.Cancellation());
        if(image.bytes.length==0||!".jpg".equals(image.extension))throw new IllegalStateException("Public story image unavailable");
        System.out.println("Static fixture: three known story page URLs found in order, one fetched; other images may be unrelated. No image uploaded, no Android/device/security or translation acceptance.");
    }
}
