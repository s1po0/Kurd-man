package com.kurdman.app;

import static org.junit.Assert.*;
import android.app.Application;
import android.content.pm.ApplicationInfo;
import android.content.pm.FeatureInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

/** Synthetic package-manager state, not Android device/package-visibility proof. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk={26,34},application=Application.class)
public class ExtensionInstalledInventoryTest {
    private static PackageInfo make(String pkg,String feature,String label){
        PackageInfo p=new PackageInfo();p.packageName=pkg;
        p.applicationInfo=new ApplicationInfo();p.applicationInfo.packageName=pkg;p.applicationInfo.nonLocalizedLabel=label;
        FeatureInfo f=new FeatureInfo();f.name=feature;p.reqFeatures=new FeatureInfo[]{f};return p;
    }
    @Test public void selectsOnlyLocallyInstalledExtensionsWithoutEstablishingTrust(){
        PackageManager pm=RuntimeEnvironment.getApplication().getPackageManager();
        Shadows.shadowOf(pm).installPackage(make("org.example.extension","tachiyomi.extension","Installed site"));
        Shadows.shadowOf(pm).installPackage(make("org.example.unrelated","ordinary.feature","Unrelated app"));
        List<ExtensionLibrarySections.Installed> found=ExtensionInstalledInventory.scan(pm);
        assertEquals(1,found.size());assertEquals("org.example.extension",found.get(0).packageName);
        assertEquals("Installed site",found.get(0).displayName);
    }
    @Test public void malformedAndSpoofedLabelsAreBounded(){
        assertFalse(ExtensionInstalledInventory.isExtension(null));
        assertFalse(ExtensionInstalledInventory.isExtension(new FeatureInfo[]{null}));
        assertEquals("AB",ExtensionInstalledInventory.safeLabel("A\u202eB\n","fallback"));
        assertEquals("fallback",ExtensionInstalledInventory.safeLabel("\u202e\n","fallback"));
        assertTrue(ExtensionInstalledInventory.safeLabel(new String(new char[200]).replace('\0','x'),"fallback").length()<=120);
    }
}
