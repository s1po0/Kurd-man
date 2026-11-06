package com.kurdman.app;

import static org.junit.Assert.*;

import android.app.Application;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Looper;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Actual Android/JVM UI and persistence, using synthetic text only; NOT device/meaning proof. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk={26,34},application=Application.class,qualifiers="en")
@LooperMode(LooperMode.Mode.PAUSED)
public class ComicQualityAndroidTest {
    private Application app;
    private final List<ActivityController<?>> activities=new ArrayList<>();

    @Before public void before(){
        app=RuntimeEnvironment.getApplication();
        TranslationProjectStore.deleteCurrent(app);
        app.getSharedPreferences(ComicSettings.PREFS,0).edit().clear().commit();
        JobRuntime.init(app);
    }
    @After public void after(){
        for(ActivityController<?> activity:activities){activity.pause().stop().destroy();}
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        TranslationProjectStore.deleteCurrent(app);
    }
    private void await(BooleanSupplier condition) throws Exception {
        long deadline=System.nanoTime()+10_000_000_000L;
        do{Shadows.shadowOf(Looper.getMainLooper()).idle();if(condition.getAsBoolean())return;Thread.sleep(10);}while(System.nanoTime()<deadline);
        assertTrue("Async local save/scan did not finish",condition.getAsBoolean());
    }
    private TranslationProject project() throws Exception {
        TranslationProject p=TranslationProjectStore.createNew(app,true,"synthetic","synthetic","en");
        p.targetLanguage="ckb";p.completed=true;
        p.comicConfig=new ComicTranslationSkill.Config(true,ComicTranslationSkill.Genre.FANTASY,"Jinwoo = جین‌وو");
        for(int i=0;i<2;i++){
            TranslationProject.Page page=new TranslationProject.Page("pages/000"+(i+1)+".png",".png");
            page.width=page.height=1;p.pages.add(page);
            // Public synthetic one-pixel PNG, not a reader's page or visual evidence.
            Files.write(page.sourceFile(p.projectDir).toPath(),Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="));
        }
        p.pages.get(0).segments.add(segment("HP: 25/100","خاڵ: 100/25"));
        p.pages.get(0).segments.add(segment("Wait here.","لێرە بوەستە."));
        p.pages.get(1).segments.add(segment("Jinwoo!","جین‌وو!"));
        p.save();return p;
    }
    private Segment segment(String source,String target){Segment s=new Segment(source,new Rect(0,0,1,1),1);s.ckb=target;s.originalCkb=target;return s;}
    private ComicQualityActivity quality() throws Exception {
        ActivityController<ComicQualityActivity> controller=Robolectric.buildActivity(ComicQualityActivity.class,new Intent(app,ComicQualityActivity.class));
        activities.add(controller);ComicQualityActivity activity=controller.setup().get();
        await(()->!((TextView)activity.findViewById(R.id.qualityStats)).getText().toString().equals(activity.getString(R.string.q_scanning)));
        return activity;
    }
    private ComicSettingsActivity settings() {
        Intent intent=new Intent(app,ComicSettingsActivity.class).putExtra(ComicSettingsActivity.EXTRA_PROJECT,true)
                .putExtra("source","ko").putExtra("target","ar"); // must NOT override the frozen project pair
        ActivityController<ComicSettingsActivity> controller=Robolectric.buildActivity(ComicSettingsActivity.class,intent);
        activities.add(controller);return controller.setup().get();
    }
    private android.app.Dialog blockDialog(ComicQualityActivity activity,int position){
        ListView list=activity.findViewById(R.id.qualityList);
        list.performItemClick(list.getAdapter().getView(position,null,list),position,list.getAdapter().getItemId(position));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        return org.robolectric.shadows.ShadowDialog.getLatestDialog();
    }

    @Test public void noProjectStillHasInAppSetupAndGuide() throws Exception {
        ComicQualityActivity activity=quality();
        assertEquals(activity.getString(R.string.q_no_project),((TextView)activity.findViewById(R.id.qualityEmpty)).getText().toString());
        assertEquals(View.GONE,activity.findViewById(R.id.qualityExport).getVisibility());
        assertTrue(activity.findViewById(R.id.qualitySettings).isEnabled());
        activity.findViewById(R.id.qualityGuide).performClick();
        assertTrue(org.robolectric.shadows.ShadowDialog.getLatestDialog().isShowing());
        assertFalse(activity.getString(R.string.q_guide).isEmpty());
        assertTrue(activity.getString(R.string.q_guide).contains("\n"));
    }
    @Test public void ratioHintFiltersAndPageNavigationAreUsable() throws Exception {
        project();ComicQualityActivity activity=quality();ListView list=activity.findViewById(R.id.qualityList);
        assertEquals(2,list.getAdapter().getCount());
        ((Spinner)activity.findViewById(R.id.qualityFilter)).setSelection(1);
        Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(1,list.getAdapter().getCount());
        ((Spinner)activity.findViewById(R.id.qualityPage)).setSelection(1);
        Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(0,list.getAdapter().getCount());
        ((Spinner)activity.findViewById(R.id.qualityFilter)).setSelection(0);
        Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(1,list.getAdapter().getCount());
    }
    @Test public void readerCanCorrectTextAndExplicitlyReviewWithoutChangingGeometry() throws Exception {
        TranslationProject p=project();Rect before=new Rect(p.pages.get(0).segments.get(0).getRenderBox());
        ComicQualityActivity activity=quality();android.app.Dialog dialog=blockDialog(activity,0);
        ((EditText)dialog.findViewById(R.id.qualityTargetInput)).setText("خاڵ: 25/100");
        ((Spinner)dialog.findViewById(R.id.qualityDecision)).setSelection(1);
        ((AlertDialog)dialog).getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        await(()->!dialog.isShowing());
        TranslationProject disk=TranslationProject.load(p.projectDir);
        assertEquals("خاڵ: 25/100",disk.pages.get(0).segments.get(0).ckb);
        assertEquals(ComicQualityReview.CHECKED,ComicQualityReview.decision(disk,0,0));
        assertEquals(before,disk.pages.get(0).segments.get(0).getRenderBox());
        assertEquals("HP: 25/100",disk.pages.get(0).segments.get(0).text);
        assertEquals("خاڵ: 100/25",disk.pages.get(0).segments.get(0).originalCkb);
        assertEquals(3,disk.totalSegments());
    }
    @Test public void editingADraftResetsItsPreviouslyCheckedDecision() throws Exception {
        TranslationProject p=project();ComicQualityReview.record(p,0,1,ComicQualityReview.CHECKED);
        ComicQualityActivity activity=quality();android.app.Dialog dialog=blockDialog(activity,1);
        Spinner decision=dialog.findViewById(R.id.qualityDecision);assertEquals(1,decision.getSelectedItemPosition());
        ((EditText)dialog.findViewById(R.id.qualityTargetInput)).setText("بوەستە.");
        assertEquals(0,decision.getSelectedItemPosition());
    }
    @Test public void reviewBindingsInvalidateOnWordingContextPairAndSettings() throws Exception {
        TranslationProject p=project();ComicQualityReview.record(p,0,1,ComicQualityReview.CHECKED);
        String original=p.snapshotJson();
        assertEquals(ComicQualityReview.CHECKED,ComicQualityReview.decision(p,0,1));
        p.pages.get(0).segments.get(0).ckb="گۆڕاو";assertEquals(ComicQualityReview.UNREVIEWED,ComicQualityReview.decision(p,0,1));p.restoreSnapshot(original);
        p.targetLanguage="ar";assertEquals(ComicQualityReview.UNREVIEWED,ComicQualityReview.decision(p,0,1));p.restoreSnapshot(original);
        p.sourceLanguage="ko";assertEquals(ComicQualityReview.UNREVIEWED,ComicQualityReview.decision(p,0,1));p.restoreSnapshot(original);
        p.comicConfig=new ComicTranslationSkill.Config(true,ComicTranslationSkill.Genre.MARTIAL,p.comicConfig.glossary);
        assertEquals(ComicQualityReview.UNREVIEWED,ComicQualityReview.decision(p,0,1));p.restoreSnapshot(original);
        p.pages.get(1).segments.get(0).ckb="لاپەڕەی تر";
        assertEquals(ComicQualityReview.CHECKED,ComicQualityReview.decision(p,0,1)); // never crosses page boundaries
    }
    @Test public void legacyProjectsNeverBecomeAutomaticallyReviewed() throws Exception {
        TranslationProject p=project();JSONObject json=p.toJson();
        JSONObject old=json.getJSONArray("pages").getJSONObject(0).getJSONArray("segments").getJSONObject(0);
        old.remove("readerReview");old.remove("readerReviewBinding");
        TranslationProject loaded=TranslationProject.fromJson(json,p.projectDir);
        assertEquals(ComicQualityReview.UNREVIEWED,ComicQualityReview.decision(loaded,0,0));
        loaded.pages.get(0).segments.get(0).readerReview="checked";
        assertEquals(ComicQualityReview.UNREVIEWED,ComicQualityReview.decision(loaded,0,0));
    }
    @Test public void emptyOrLeakedTargetsCannotBeDeclaredChecked() throws Exception {
        TranslationProject p=project();
        for(String bad:new String[]{"","ZXQK001"}){
            p.pages.get(0).segments.get(0).ckb=bad;
            try{ComicQualityReview.record(p,0,0,ComicQualityReview.CHECKED);fail("unsafe target accepted");}
            catch(IllegalArgumentException expected){ }
        }
    }
    @Test public void correctionMemoryChangesOnlyIdenticalWholeBlocksAndKeepsStyles() throws Exception {
        TranslationProject p=project();
        Segment first=segment("Jinwoo","کۆن");first.ensureStyle().visible=false;
        Segment duplicate=segment("Jinwoo","کۆن");
        Segment sentence=segment("Jinwoo is here.","ئەو لێرەیە.");
        p.pages.get(0).segments.add(first);p.pages.get(1).segments.add(duplicate);p.pages.get(1).segments.add(sentence);
        String source=first.text;Rect geometry=new Rect(first.box);
        assertEquals(2,ComicQualityReview.remember(p,"Jinwoo","جین‌وو",true));
        assertEquals("جین‌وو",first.ckb);assertEquals("جین‌وو",duplicate.ckb);
        assertEquals("ئەو لێرەیە.",sentence.ckb);assertEquals(source,first.text);assertEquals(geometry,first.box);
        assertFalse(first.ensureStyle().visible);assertEquals("کۆن",first.originalCkb);
        assertEquals(ComicTranslationSkill.Genre.FANTASY,p.comicConfig.genre);
    }
    @Test public void correctionMemoryValidationIsBeforeAnyProjectMutation() throws Exception {
        TranslationProject p=project();String snapshot=p.snapshotJson();
        try{ComicQualityReview.remember(p,"a=b","ناو",true);fail("invalid memory accepted");}
        catch(ComicTranslationSkill.GlossaryException expected){ }
        assertEquals(snapshot,p.snapshotJson());
    }
    @Test public void projectSetupKeepsFrozenPairAndDoesNotOverwriteFuturePresetByDefault() throws Exception {
        TranslationProject p=project();
        ComicSettings.save(app,"en","ckb",new ComicTranslationSkill.Config(true,ComicTranslationSkill.Genre.EVERYDAY,"Other = تر"));
        ComicSettingsActivity activity=settings();
        assertEquals("en → ckb",((TextView)activity.findViewById(R.id.comicLanguagePair)).getText().toString());
        assertFalse(((CheckBox)activity.findViewById(R.id.comicApplyFuture)).isChecked());
        ((EditText)activity.findViewById(R.id.comicGlossary)).setText("Jinwoo = جین‌وو\nGate = دەروازە");
        activity.findViewById(R.id.comicSave).performClick();await(activity::isFinishing);
        assertEquals(2,ComicTranslationSkill.validateGlossary(TranslationProject.load(p.projectDir).comicConfig.glossary));
        assertEquals("Other = تر",ComicSettings.read(app,"en","ckb").glossary);
    }
    @Test public void futurePresetRequiresExplicitOptIn() throws Exception {
        project();ComicSettingsActivity activity=settings();
        ((EditText)activity.findViewById(R.id.comicGlossary)).setText("Gate = دەروازە");
        ((CheckBox)activity.findViewById(R.id.comicApplyFuture)).setChecked(true);
        activity.findViewById(R.id.comicSave).performClick();await(activity::isFinishing);
        assertEquals("Gate = دەروازە",ComicSettings.read(app,"en","ckb").glossary);
        assertEquals("",ComicSettings.read(app,"ko","ar").glossary);
    }
    @Test public void oldScreenCannotOverwriteAReplacementProject() throws Exception {
        TranslationProject old=project();String snapshot=old.snapshotJson();
        TranslationProject fresh=TranslationProjectStore.createNew(app,false,"new synthetic","new","ja");fresh.save();
        try{TranslationProjectStore.saveSnapshot(old,snapshot);fail("stale snapshot written");}
        catch(java.io.IOException expected){ }
        assertEquals("ja",TranslationProject.load(fresh.projectDir).sourceLanguage);
        assertEquals("new synthetic",TranslationProject.load(fresh.projectDir).displayName);
    }
    @Test public void nameMemoryIsUsableInsideTheDialogWithoutAutoReviewOrPresetOverwrite() throws Exception {
        TranslationProject p=project();ComicQualityActivity activity=quality();
        android.app.Dialog block=blockDialog(activity,0);block.findViewById(R.id.qualityRemember).performClick();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        android.app.Dialog memory=org.robolectric.shadows.ShadowDialog.getLatestDialog();
        assertFalse(((CheckBox)memory.findViewById(R.id.qualityMemoryExact)).isChecked());
        assertFalse(((CheckBox)memory.findViewById(R.id.qualityMemoryFuture)).isChecked());
        ((EditText)memory.findViewById(R.id.qualityMemorySource)).setText("HP: 25/100");
        ((EditText)memory.findViewById(R.id.qualityMemoryTarget)).setText("خاڵ: 25/100");
        ((CheckBox)memory.findViewById(R.id.qualityMemoryExact)).setChecked(true);
        ((AlertDialog)memory).getButton(AlertDialog.BUTTON_POSITIVE).performClick();await(()->!memory.isShowing());
        assertEquals("خاڵ: 25/100",p.pages.get(0).segments.get(0).ckb);
        assertEquals("خاڵ: 25/100",((EditText)block.findViewById(R.id.qualityTargetInput)).getText().toString());
        assertEquals(0,((Spinner)block.findViewById(R.id.qualityDecision)).getSelectedItemPosition());
        assertEquals(ComicQualityReview.UNREVIEWED,ComicQualityReview.decision(p,0,0));
        assertEquals("",ComicSettings.read(app,"en","ckb").glossary);
    }
    @Test public void editorEntryRetainsExactPageAndBlock() throws Exception {
        project();ComicQualityActivity activity=quality();
        ((Spinner)activity.findViewById(R.id.qualityPage)).setSelection(1);Shadows.shadowOf(Looper.getMainLooper()).idle();
        activity.findViewById(R.id.qualityExport).performClick();
        Intent intent=Shadows.shadowOf(activity).getNextStartedActivity();
        assertNotNull(intent);assertEquals(EditorActivity.class.getName(),intent.getComponent().getClassName());
        assertEquals(1,intent.getIntExtra(EditorActivity.EXTRA_PAGE,-1));assertEquals(0,intent.getIntExtra(EditorActivity.EXTRA_SEGMENT,-1));
    }
}
