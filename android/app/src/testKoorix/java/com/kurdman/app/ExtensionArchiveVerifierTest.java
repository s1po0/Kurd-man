package com.kurdman.app;

import static org.junit.Assert.*;
import android.app.Application;
import android.content.pm.ApplicationInfo;
import android.content.pm.FeatureInfo;
import android.content.pm.PackageInfo;
import android.content.pm.Signature;
import android.os.Bundle;
import java.util.Collections;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Synthetic package metadata only: does not verify an APK or prove Android installer security. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=26,application=Application.class)
public class ExtensionArchiveVerifierTest {
    private static final String APPROVED="ca978112ca1bbdcafac231b39a23dc4da786eff8147c4e72b9807785afee48bb";
    private static ExtensionStoreIndex.Entry entry(){return new ExtensionStoreIndex.Entry("Example","org.example.ext","https://example.org/a.apk","1.6",7,"1.6.7",1,Collections.emptyList());}
    @SuppressWarnings("deprecation") private static PackageInfo info(String packageName,int version,String signer){
        PackageInfo p=new PackageInfo();p.packageName=packageName;p.versionCode=version;p.versionName="1.6.7";
        p.applicationInfo=new ApplicationInfo();p.applicationInfo.metaData=new Bundle();p.applicationInfo.metaData.putFloat("tachiyomix.extensionLib",1.6f);
        FeatureInfo f=new FeatureInfo();f.name="tachiyomi.extension";p.reqFeatures=new FeatureInfo[]{f};
        p.signatures=new Signature[]{new Signature(signer.getBytes(java.nio.charset.StandardCharsets.UTF_8))};return p;
    }
    @Test public void validMetadataIsNotRuntimeSafetyProof(){
        assertEquals(ExtensionIdentityPolicy.Result.MATCH,ExtensionArchiveVerifier.compare(entry(),APPROVED,info("org.example.ext",7,"a"),null));
    }
    @Test public void detectsWrongSignerVersionFeatureAndLibrary(){
        assertEquals(ExtensionIdentityPolicy.Result.UNAPPROVED_SIGNER,ExtensionArchiveVerifier.compare(entry(),APPROVED,info("org.example.ext",7,"b"),null));
        assertEquals(ExtensionIdentityPolicy.Result.VERSION_MISMATCH,ExtensionArchiveVerifier.compare(entry(),APPROVED,info("org.example.ext",8,"a"),null));
        PackageInfo noFeature=info("org.example.ext",7,"a");noFeature.reqFeatures=null;
        assertEquals(ExtensionIdentityPolicy.Result.INVALID_METADATA,ExtensionArchiveVerifier.compare(entry(),APPROVED,noFeature,null));
        PackageInfo wrongLibrary=info("org.example.ext",7,"a");wrongLibrary.applicationInfo.metaData.putFloat("tachiyomix.extensionLib",1.4f);
        assertEquals(ExtensionIdentityPolicy.Result.INVALID_METADATA,ExtensionArchiveVerifier.compare(entry(),APPROVED,wrongLibrary,null));
    }
    @Test public void rejectsDowngradeAndPreviouslyDifferentSigner(){
        assertEquals(ExtensionIdentityPolicy.Result.DOWNGRADE,ExtensionArchiveVerifier.compare(entry(),APPROVED,info("org.example.ext",7,"a"),info("org.example.ext",8,"a")));
        assertEquals(ExtensionIdentityPolicy.Result.INSTALLED_IDENTITY_MISMATCH,ExtensionArchiveVerifier.compare(entry(),APPROVED,info("org.example.ext",7,"a"),info("org.example.ext",6,"b")));
    }
}
