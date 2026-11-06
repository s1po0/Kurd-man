package com.kurdman.app;
import static org.junit.Assert.*;
import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.net.Uri;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import java.io.IOException;
import java.time.Instant;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

@RunWith(RobolectricTestRunner.class)
@Config(sdk={26,34},application=Application.class,qualifiers="en")
public class NoLoginDeviceTest {
    Application app;SecretKey key;
    @Before public void setup()throws Exception{app=RuntimeEnvironment.getApplication();app.getSharedPreferences("koorix_device_v2",0).edit().clear().commit();app.getSharedPreferences("koorix_account",0).edit().clear().commit();KeyGenerator gen=KeyGenerator.getInstance("AES");gen.init(256);key=gen.generateKey();}
    DeviceStateStore store(){return new DeviceStateStore(app,()->key);}
    JSONObject state(int jobs,int pages)throws Exception{return new JSONObject().put("validated",true).put("uid","synthetic-device").put("status",new JSONObject().put("usage_day","2026-11-06").put("jobs_today",jobs).put("pages_today",pages).put("free_job_limit",3).put("free_page_limit",60));}
    long now(){return Instant.parse("2026-11-06T12:00:00Z").toEpochMilli();}
    @Test public void launchingPublicAppNeverOpensALoginScreen(){assertFalse(BuildConfig.REQUIRE_ACCOUNT);Activity a=Robolectric.buildActivity(Activity.class).setup().get();AccountGate.enforceOnLaunch(a);assertNull(Shadows.shadowOf(app).getNextStartedActivity());a.finish();}
    @Test public void legacyMagicLinkCannotInjectATokenOrLaunchLogin(){Activity a=Robolectric.buildActivity(Activity.class).setup().get();assertFalse(AccountGate.handleAuthDeepLink(a,new Intent(Intent.ACTION_VIEW,Uri.parse("com.koorix.app://auth/callback#access_token=untrusted&refresh_token=untrusted"))));assertEquals(0,app.getPackageManager().queryIntentActivities(new Intent(Intent.ACTION_VIEW,Uri.parse("com.koorix.app://auth/callback")),0).size());assertNull(Shadows.shadowOf(app).getNextStartedActivity());a.finish();}
    @Test public void tokensAndDeviceCredentialsAreEncryptedAtRest()throws Exception{JSONObject data=new JSONObject().put("access_token","synthetic-private-session").put("device_secret","synthetic-private-device-secret");store().write(data);String raw=app.getSharedPreferences("koorix_device_v2",0).getString("sealed","");assertFalse(raw.contains("synthetic-private"));assertEquals(data.toString(),store().read().toString());}
    @Test public void ciphertextTamperingFailsClosedWithoutPlaintextFallback()throws Exception{store().write(new JSONObject().put("access_token","synthetic"));String raw=app.getSharedPreferences("koorix_device_v2",0).getString("sealed","");int at=raw.indexOf('.')+2;raw=raw.substring(0,at)+(raw.charAt(at)=='A'?'B':'A')+raw.substring(at+1);app.getSharedPreferences("koorix_device_v2",0).edit().putString("sealed",raw).commit();try{store().read();fail();}catch(IOException expected){assertFalse(expected.getMessage().contains("synthetic"));}}
    @Test public void existingSubscriberSessionMigratesWithoutKeepingPersonalEmail()throws Exception{app.getSharedPreferences("koorix_account",0).edit().putString("access_token","legacy-synthetic").putString("refresh_token","legacy-refresh").putString("email","private@example.invalid").commit();JSONObject data=store().read();assertEquals("legacy-synthetic",data.getString("access_token"));assertFalse(data.has("email"));assertTrue(app.getSharedPreferences("koorix_account",0).getAll().isEmpty());assertFalse(app.getSharedPreferences("koorix_device_v2",0).getString("sealed","").contains("legacy"));}
    @Test public void offlineUsesRemainingServerBudgetNotAnotherDailyPool()throws Exception{JSONObject data=state(2,58);assertTrue(DeviceAllowance.reserve(data,2,"job-a",now()));assertFalse(DeviceAllowance.reserve(data,1,"job-b",now()));assertEquals(1,data.getJSONObject("pending_offline").length());}
    @Test public void theSameOfflineJobNeverSpendsTwice()throws Exception{JSONObject data=state(0,0);assertTrue(DeviceAllowance.reserve(data,10,"job-a",now()));assertTrue(DeviceAllowance.reserve(data,10,"job-a",now()));assertFalse(DeviceAllowance.reserve(data,11,"job-a",now()));assertEquals(1,data.getJSONObject("pending_offline").length());}
    @Test public void unvalidatedIdentityCannotReceiveAnOfflineGrant()throws Exception{JSONObject data=state(0,0).put("validated",false);assertFalse(DeviceAllowance.reserve(data,1,"job",now()));assertFalse(data.has("pending_offline"));}
    @Test public void freeAllowanceCannotRenewFromPhoneDateAlone()throws Exception{assertFalse(DeviceAllowance.reserve(state(0,0),1,"job",now()+86400000L));assertFalse(DeviceAllowance.reserve(state(0,0),1,"job",Long.MAX_VALUE));}
    @Test public void validPremiumRemainsOfflineAcrossDaysButNotAfterExpiry()throws Exception{JSONObject data=state(3,60);data.getJSONObject("status").put("premium_until","2026-11-10T00:00:00Z");assertTrue(DeviceAllowance.reserve(data,300,"paid",now()+86400000L));assertFalse(DeviceAllowance.reserve(data,1,"expired",now()+10*86400000L));}
    @Test public void invalidInputAndMissingPacksAreRejectedBeforeAQuotaCall(){assertEquals(TranslationPreflight.Error.NO_INPUT,TranslationPreflight.check(false,false,true,true,false,true));assertEquals(TranslationPreflight.Error.PACK_MISSING,TranslationPreflight.check(false,true,true,true,true,false));assertEquals(TranslationPreflight.Error.SOURCE_MISSING,TranslationPreflight.check(false,true,false,true,false,true));}
    @Test public void preflightPreservesAllSupportedRoutesAndStopsDuplicateStarts(){assertEquals(TranslationPreflight.Error.NONE,TranslationPreflight.check(false,true,true,true,false,false));assertEquals(TranslationPreflight.Error.NONE,TranslationPreflight.check(false,true,true,true,true,true));assertEquals(TranslationPreflight.Error.BUSY,TranslationPreflight.check(true,true,true,true,true,true));assertEquals(TranslationPreflight.Error.UNSUPPORTED_PAIR,TranslationPreflight.check(false,true,true,false,true,true));}
}
