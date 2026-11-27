package com.kurdman.app;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.Bundle;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Preliminary Android package metadata/signature identity check. A match does
 * NOT make extension code safe, install it, or establish sandboxing. The OS
 * installer must still independently verify the APK. Not wired to UI yet.
 */
final class ExtensionArchiveVerifier {
    private ExtensionArchiveVerifier() { }
    @SuppressWarnings("deprecation")
    static ExtensionIdentityPolicy.Result inspect(PackageManager pm,File apk,ExtensionStoreIndex.Entry entry,String approvedSigner)throws IOException {
        if(pm==null||apk==null||!apk.isFile()||entry==null)throw new IOException("Missing package data");
        int flags=PackageManager.GET_CONFIGURATIONS|PackageManager.GET_META_DATA|
                (Build.VERSION.SDK_INT>=28?PackageManager.GET_SIGNING_CERTIFICATES:PackageManager.GET_SIGNATURES);
        PackageInfo archive=pm.getPackageArchiveInfo(apk.getAbsolutePath(),flags);
        if(archive==null)throw new IOException("Unreadable extension archive");
        PackageInfo installed=null;
        try{installed=pm.getPackageInfo(entry.packageName,flags);}catch(PackageManager.NameNotFoundException notInstalled){}
        return compare(entry,approvedSigner,archive,installed);
    }
    static ExtensionIdentityPolicy.Result compare(ExtensionStoreIndex.Entry entry,String approvedSigner,PackageInfo archive,PackageInfo installed){
        if(entry==null||archive==null||!ExtensionInstalledInventory.isExtension(archive.reqFeatures)||archive.applicationInfo==null)
            return ExtensionIdentityPolicy.Result.INVALID_METADATA;
        Bundle meta=archive.applicationInfo.metaData;String lib=null;
        if(meta!=null){Object value=meta.get("tachiyomix.extensionLib");if(value!=null)lib=value.toString();}
        if(lib==null && archive.versionName!=null){int cut=archive.versionName.lastIndexOf('.');if(cut>0)lib=archive.versionName.substring(0,cut);}
        if(!entry.extensionLib.equals(lib)||!entry.versionName.equals(archive.versionName))return ExtensionIdentityPolicy.Result.INVALID_METADATA;
        return ExtensionIdentityPolicy.check(entry.packageName,entry.versionCode,entry.extensionLib,approvedSigner,
                archive.packageName,version(archive),certificates(archive),
                installed==null?null:installed.packageName,installed==null?0:version(installed),installed==null?null:certificates(installed));
    }
    @SuppressWarnings("deprecation")
    private static long version(PackageInfo info){return Build.VERSION.SDK_INT>=28?info.getLongVersionCode():info.versionCode;}
    @SuppressWarnings("deprecation")
    private static List<byte[]> certificates(PackageInfo info){
        Signature[] values;
        if(Build.VERSION.SDK_INT>=28){
            if(info.signingInfo==null||info.signingInfo.hasMultipleSigners()||info.signingInfo.hasPastSigningCertificates())return null;
            values=info.signingInfo.getApkContentsSigners();
        } else values=info.signatures;
        if(values==null||values.length!=1||values[0]==null)return null;
        List<byte[]> result=new ArrayList<>(1);result.add(values[0].toByteArray());return result;
    }
}
