package com.kurdman.app;

import static org.junit.Assert.*;
import android.app.Application;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;

/** Owner-authorized free edition: no registration, purchase screen or daily budget. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk={26,34},application=Application.class,qualifiers="en")
public class NoLoginDeviceTest {
    Application app;
    @Before public void setup(){app=RuntimeEnvironment.getApplication();}
    @Test public void buildDeclaresACompletelyFreePublicEdition(){assertTrue(BuildConfig.PUBLIC_EDITION);assertTrue(BuildConfig.FREE_EDITION);assertFalse(BuildConfig.REQUIRE_ACCOUNT);assertEquals(0,BuildConfig.FREE_DAILY_JOBS);assertEquals(0,BuildConfig.FREE_DAILY_PAGES);}
    @Test public void noLoginOrPremiumActivityIsRegistered(){for(String name:new String[]{"AccountActivity","PremiumActivity"})try{app.getPackageManager().getActivityInfo(new ComponentName(app.getPackageName(),"com.kurdman.app."+name),0);fail(name+" is still registered");}catch(PackageManager.NameNotFoundException expected){}}
    @Test public void oldMagicLinksCannotOpenAuthentication(){Intent link=new Intent(Intent.ACTION_VIEW,Uri.parse("com.koorix.app://auth/callback#access_token=untrusted"));assertEquals(0,app.getPackageManager().queryIntentActivities(link,0).size());}
    @Test public void paymentAndQuotaImplementationsAreNotInTheApp(){for(String name:new String[]{"AccountGate","PremiumActivity","SupabaseAuth","DeviceAllowance","DeviceStateStore"})try{Class.forName("com.kurdman.app."+name);fail(name+" remains executable");}catch(ClassNotFoundException expected){}}
    @Test public void publicBuildDoesNotContainAnEntitlementServerConfiguration(){for(String name:new String[]{"SUPABASE_URL","SUPABASE_KEY"})try{BuildConfig.class.getDeclaredField(name);fail(name+" remains configured");}catch(NoSuchFieldException expected){}}
    @Test public void mainLayoutHasNoPremiumOrAccountEntry(){android.app.Activity host=Robolectric.buildActivity(android.app.Activity.class).setup().get();host.setTheme(R.style.AppTheme);View root=LayoutInflater.from(host).inflate(R.layout.activity_main,null);assertEquals(0,app.getResources().getIdentifier("accountButton","id",BuildConfig.APPLICATION_ID));assertNotNull(root.findViewById(R.id.freeEditionNote));assertNotNull(root.findViewById(R.id.pickGalleryButton));assertNotNull(root.findViewById(R.id.pickFilesButton));host.finish();}
    @Test public void invalidInputAndMissingPacksStillUseTechnicalPreflight(){assertEquals(TranslationPreflight.Error.NO_INPUT,TranslationPreflight.check(false,false,true,true,false,true));assertEquals(TranslationPreflight.Error.PACK_MISSING,TranslationPreflight.check(false,true,true,true,true,false));assertEquals(TranslationPreflight.Error.SOURCE_MISSING,TranslationPreflight.check(false,true,false,true,false,true));}
    @Test public void freeModeDoesNotRemoveStopOrPairSafety(){assertEquals(TranslationPreflight.Error.NONE,TranslationPreflight.check(false,true,true,true,false,false));assertEquals(TranslationPreflight.Error.NONE,TranslationPreflight.check(false,true,true,true,true,true));assertEquals(TranslationPreflight.Error.BUSY,TranslationPreflight.check(true,true,true,true,true,true));assertEquals(TranslationPreflight.Error.UNSUPPORTED_PAIR,TranslationPreflight.check(false,true,true,false,true,true));}
}
