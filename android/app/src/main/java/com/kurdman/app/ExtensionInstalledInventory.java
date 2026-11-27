package com.kurdman.app;

import android.content.pm.ApplicationInfo;
import android.content.pm.FeatureInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Local on-device inventory of system-installed extension APKs. It neither
 * loads extension code nor establishes identity, trust or safety. Apps' private
 * extensions are not visible here. Never log or transmit this package list.
 */
final class ExtensionInstalledInventory {
    private static final int MAX_EXTENSIONS=10000;
    private ExtensionInstalledInventory() { }
    static List<ExtensionLibrarySections.Installed> scan(PackageManager manager){
        if(manager==null)throw new IllegalArgumentException("Package manager unavailable");
        @SuppressWarnings("deprecation") List<PackageInfo> packages=manager.getInstalledPackages(PackageManager.GET_CONFIGURATIONS);
        if(packages==null)throw new IllegalStateException("Package inventory unavailable");
        List<ExtensionLibrarySections.Installed> result=new ArrayList<>();Set<String> seen=new HashSet<>();
        for(PackageInfo info:packages){
            if(info==null||info.packageName==null||!isExtension(info.reqFeatures)||!seen.add(info.packageName))continue;
            if(result.size()>=MAX_EXTENSIONS)throw new IllegalStateException("Too many extensions");
            String label=info.packageName;
            try{
                ApplicationInfo application=info.applicationInfo;
                if(application!=null){CharSequence found=manager.getApplicationLabel(application);if(found!=null)label=found.toString();}
            }catch(RuntimeException ignored){/* Show the package if its resource is unavailable. */}
            label=safeLabel(label,info.packageName);
            result.add(new ExtensionLibrarySections.Installed(info.packageName,label));
        }
        return result;
    }
    static boolean isExtension(FeatureInfo[] features){
        if(features==null)return false;
        for(FeatureInfo feature:features)if(feature!=null&&"tachiyomi.extension".equals(feature.name))return true;
        return false;
    }
    static String safeLabel(String label,String fallback){
        if(label==null)label="";
        StringBuilder result=new StringBuilder();
        for(int i=0;i<label.length() && result.length()<120;){
            int ch=label.codePointAt(i);i+=Character.charCount(ch);
            int category=Character.getType(ch);
            if(category==Character.CONTROL||category==Character.FORMAT||category==Character.LINE_SEPARATOR||category==Character.PARAGRAPH_SEPARATOR)continue;
            if(result.length()+Character.charCount(ch)>120)break;
            result.appendCodePoint(ch);
        }
        String value=result.toString().trim();return value.isEmpty()?fallback:value;
    }
}
