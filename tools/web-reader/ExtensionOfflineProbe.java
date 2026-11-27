package com.kurdman.app;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;

/** Synthetic no-Android probe for identity comparison and installed-first grouping. */
public final class ExtensionOfflineProbe {
    private static final String APPROVED="ca978112ca1bbdcafac231b39a23dc4da786eff8147c4e72b9807785afee48bb";
    private static java.util.List<byte[]> signer(String s){return Collections.singletonList(s.getBytes(StandardCharsets.UTF_8));}
    private static void check(boolean v,String name){if(!v)throw new AssertionError(name);}
    public static void main(String[] args){
        check(ExtensionIdentityPolicy.check("org.example.x",2,"1.6",APPROVED,"org.example.x",2,signer("a"),null,0,null)==ExtensionIdentityPolicy.Result.MATCH,"identity");
        check(ExtensionIdentityPolicy.check("org.example.x",2,"1.6",APPROVED,"org.example.x",2,signer("b"),null,0,null)==ExtensionIdentityPolicy.Result.UNAPPROVED_SIGNER,"substitution");
        check(ExtensionIdentityPolicy.check("org.example.x",2,"1.6",APPROVED,"org.example.x",2,signer("a"),"org.example.x",3,signer("a"))==ExtensionIdentityPolicy.Result.DOWNGRADE,"rollback");
        check(ExtensionIdentityPolicy.check("org.example.x",2,"1.6",APPROVED,"org.example.x",2,signer("a"),"org.example.x",1,signer("b"))==ExtensionIdentityPolicy.Result.INSTALLED_IDENTITY_MISMATCH,"signer rotation");
        ExtensionStoreIndex.Entry remote=new ExtensionStoreIndex.Entry("Some extension","org.example.x","https://example.org/a.apk","1.6",2,"1.6.2",1,
                Collections.singletonList(new ExtensionStoreIndex.Source(1,"Site one","en","https://example.org")));
        ExtensionStoreIndex.Entry other=new ExtensionStoreIndex.Entry("Other extension","org.example.y","https://example.org/b.apk","1.6",2,"1.6.2",1,Collections.emptyList());
        ExtensionLibrarySections grouped=ExtensionLibrarySections.build(Arrays.asList(new ExtensionLibrarySections.Installed("org.example.x","Installed"),
                new ExtensionLibrarySections.Installed("org.example.removed","Missing from store")),Arrays.asList(remote,other),"");
        check(grouped.installed.size()==2 && grouped.available.size()==1 && grouped.available.get(0).packageName.equals("org.example.y"),"sections");
        grouped=ExtensionLibrarySections.build(Collections.singletonList(new ExtensionLibrarySections.Installed("org.example.x","Installed")),Collections.singletonList(remote),"site one");
        check(grouped.installed.size()==1 && grouped.available.isEmpty(),"site search");
        System.out.println("Offline synthetic identity and grouping checks passed; not APK verification or an extension runtime.");
    }
}
