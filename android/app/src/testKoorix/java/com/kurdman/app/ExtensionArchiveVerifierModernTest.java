package com.kurdman.app;

import static org.junit.Assert.*;
import android.app.Application;
import android.content.pm.ApplicationInfo;
import android.content.pm.FeatureInfo;
import android.content.pm.PackageInfo;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.Bundle;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadow.api.Shadow;

/** Simulated signing metadata on SDK28/34; not real APK signature or installer proof. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk={28,34},application=Application.class)
public class ExtensionArchiveVerifierModernTest {
    private static final String APPROVED="ca978112ca1bbdcafac231b39a23dc4da786eff8147c4e72b9807785afee48bb";
    private static ExtensionStoreIndex.Entry entry(){return new ExtensionStoreIndex.Entry("Example","org.example.ext","https://example.org/a.apk","1.6",7,"1.6.7",1,Collections.emptyList());}
    private static Signature signature(String s){return new Signature(s.getBytes(StandardCharsets.UTF_8));}
    @SuppressWarnings("deprecation") private static PackageInfo info(Signature[] signers,Signature[] history){
        PackageInfo p=new PackageInfo();p.packageName="org.example.ext";p.versionCode=7;p.versionName="1.6.7";
        p.applicationInfo=new ApplicationInfo();p.applicationInfo.metaData=new Bundle();p.applicationInfo.metaData.putFloat("tachiyomix.extensionLib",1.6f);
        FeatureInfo f=new FeatureInfo();f.name="tachiyomi.extension";p.reqFeatures=new FeatureInfo[]{f};
        p.signingInfo=Shadow.newInstanceOf(SigningInfo.class);Shadows.shadowOf(p.signingInfo).setSignatures(signers);Shadows.shadowOf(p.signingInfo).setPastSigningCertificates(history);return p;
    }
    @Test public void acceptsOnlyOneApprovedCurrentSigner(){
        assertEquals(ExtensionIdentityPolicy.Result.MATCH,ExtensionArchiveVerifier.compare(entry(),APPROVED,info(new Signature[]{signature("a")},null),null));
        assertEquals(ExtensionIdentityPolicy.Result.UNAPPROVED_SIGNER,ExtensionArchiveVerifier.compare(entry(),APPROVED,info(new Signature[]{signature("b")},null),null));
        assertEquals(ExtensionIdentityPolicy.Result.INVALID_SIGNER,ExtensionArchiveVerifier.compare(entry(),APPROVED,info(null,null),null));
    }
    @Test public void refusesMultipleAndRotatedSigners(){
        assertEquals(ExtensionIdentityPolicy.Result.INVALID_SIGNER,ExtensionArchiveVerifier.compare(entry(),APPROVED,info(new Signature[]{signature("a"),signature("b")},null),null));
        assertEquals(ExtensionIdentityPolicy.Result.INVALID_SIGNER,ExtensionArchiveVerifier.compare(entry(),APPROVED,info(new Signature[]{signature("a")},new Signature[]{signature("b"),signature("a")}),null));
        assertEquals(ExtensionIdentityPolicy.Result.INSTALLED_IDENTITY_MISMATCH,ExtensionArchiveVerifier.compare(entry(),APPROVED,info(new Signature[]{signature("a")},null),info(new Signature[]{signature("a")},new Signature[]{signature("b"),signature("a")})));
    }
}
