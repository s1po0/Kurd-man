package com.kurdman.app;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.Application;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;

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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** One-button Android contracts with synthetic/mock data, not device/meaning evidence. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk={26,34},application=Application.class,qualifiers="en")
@LooperMode(LooperMode.Mode.PAUSED)
public class ComicAutomaticTranslateTest {
    private Application app;
    private final List<ActivityController<Activity>> activities = new ArrayList<>();
    private Activity host;
    private View root;
    private ComicTranslateControls controls;

    @Before public void before() {
        app = RuntimeEnvironment.getApplication();
        app.getSharedPreferences(ComicSettings.PREFS, 0).edit().clear().commit();
        TranslationProjectStore.deleteCurrent(app);
        JobRuntime.init(app);
        form(null);
    }
    @After public void after() {
        for (ActivityController<Activity> controller : activities) controller.pause().stop().destroy();
        TranslationProjectStore.deleteCurrent(app);
    }
    private void form(Bundle saved) {
        ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup();
        activities.add(controller); host = controller.get();
        host.setTheme(R.style.AppTheme); host.setContentView(R.layout.activity_main);
        root = host.findViewById(android.R.id.content);
        controls = new ComicTranslateControls(host, root, saved);
        controls.selectPair("en", "ckb");
    }
    private EditText names() { return root.findViewById(R.id.comicInlineGlossary); }
    private Segment segment(String source) { return new Segment(source, new Rect(4,8,40,80), 2); }
    private TranslationProject project() throws Exception {
        TranslationProject project = TranslationProjectStore.createNew(app, false, "synthetic", "synthetic", "en");
        project.targetLanguage = "ckb"; project.completed = true;
        project.comicConfig = new ComicTranslationSkill.Config(true, ComicTranslationSkill.Genre.AUTO, "");
        project.pages.add(new TranslationProject.Page("pages/synthetic.png", ".png"));
        return project;
    }

    @Test public void publicAutomaticDefaultOverridesOldOffPresetButNotSavedProjects() {
        assertTrue(BuildConfig.PUBLIC_EDITION);
        app.getSharedPreferences(ComicSettings.PREFS,0).edit().putBoolean("comic_skill_v1_enabled",false).commit();
        assertTrue(ComicSettings.read(app,"en","ckb").enabled);
        assertFalse(ComicSettings.fromJson(null).enabled);
        assertFalse(ComicSettings.fromJson(new org.json.JSONObject()).enabled);
    }
    @Test public void translateHasAutomaticDefaultsAndNoSeparateSkillLauncher() {
        assertEquals(View.GONE, root.findViewById(R.id.comicInlinePanel).getVisibility());
        assertEquals("Translate", ((TextView)root.findViewById(R.id.translateButton)).getText().toString());
        assertTrue(((TextView)root.findViewById(R.id.comicSkillSummary)).getText().toString().contains("automatically"));
        assertEquals(0, app.getResources().getIdentifier("comicQualityButton", "id", BuildConfig.APPLICATION_ID));
        ComicTranslationSkill.Config config = controls.forTranslation();
        assertNotNull(config); assertTrue(config.enabled); assertEquals(ComicTranslationSkill.Genre.AUTO,config.genre);
        assertEquals("",config.glossary);
        assertNull(Shadows.shadowOf(app).getNextStartedActivity());
    }
    @Test public void optionalNamesApplyInlineOnTranslateWithoutSaveOrNavigation() {
        root.findViewById(R.id.comicSettingsButton).performClick();
        assertEquals(View.VISIBLE,root.findViewById(R.id.comicInlinePanel).getVisibility());
        names().setText("Jinwoo = جین‌وو");
        ((Spinner)root.findViewById(R.id.comicInlineGenre)).setSelection(1);
        ComicTranslationSkill.Config config = controls.forTranslation();
        assertNotNull(config); assertEquals("Jinwoo = جین‌وو",config.glossary);
        assertEquals(ComicTranslationSkill.Genre.FANTASY,config.genre);
        assertEquals(config.glossary,ComicSettings.read(app,"en","ckb").glossary);
        assertEquals("",ComicSettings.read(app,"en","ar").glossary);
        assertNull(Shadows.shadowOf(app).getNextStartedActivity());
    }
    @Test public void languageSwitchAndRecreationKeepDraftsOutOfOtherPairs() {
        names().setText("Jinwoo = جین‌وو");
        controls.selectPair("en","ar"); assertEquals("",names().getText().toString());
        names().setText("Jinwoo = جين وو");
        controls.selectPair("en","ckb"); assertEquals("Jinwoo = جین‌وو",names().getText().toString());
        controls.selectPair("en","ar");
        root.findViewById(R.id.comicSettingsButton).performClick();
        Bundle state = new Bundle(); controls.saveState(state); form(state);
        assertEquals("",names().getText().toString()); // current form is en -> ckb
        controls.selectPair("en","ar");
        assertEquals("Jinwoo = جين وو",names().getText().toString());
        assertEquals(View.VISIBLE,root.findViewById(R.id.comicInlinePanel).getVisibility());
        assertEquals("Jinwoo = جين وو",controls.forTranslation().glossary);
        assertEquals("",ComicSettings.read(app,"en","ckb").glossary);
    }
    @Test public void invalidOptionalNamesStayInlineAndDoNotChangePresetOrProject() throws Exception {
        ComicSettings.save(app,"en","ckb",new ComicTranslationSkill.Config(true,ComicTranslationSkill.Genre.AUTO,"Jinwoo = جین‌وو"));
        TranslationProject project=project(); String before=project.snapshotJson();
        controls.selectPair("en","ckb"); names().setText("not a valid glossary line");
        assertFalse(controls.validate()); assertNull(controls.forTranslation());
        assertNotNull(names().getError()); assertEquals(View.VISIBLE,root.findViewById(R.id.comicInlinePanel).getVisibility());
        assertEquals("Jinwoo = جین‌وو",ComicSettings.read(app,"en","ckb").glossary);
        assertEquals(before,project.snapshotJson());
        assertNull(Shadows.shadowOf(app).getNextStartedActivity());
    }
    @Test public void automaticPreRenderPassPersistsHintsAndPreservesLayoutAtomically() throws Exception {
        TranslationProject project=project(); List<Segment> segments=project.pages.get(0).segments;
        Segment first=segment("HP: 25/100"),second=segment("Wait here.");
        first.ocrConfidence=0.5f; first.readerReview="checked"; first.readerReviewBinding="old";
        segments.add(first);segments.add(second); Rect before=new Rect(first.getRenderBox()); Object style=first.style;
        ComicTranslationSkill.PageResult page=new ComicTranslationSkill.PageResult();
        page.results.add(new ComicTranslationSkill.Result("خاڵ: 25/100","comic_system",""));
        try { ComicTranslationFlow.apply(segments,page,"en","ckb",project.comicConfig);fail("Misalignment must fail"); }
        catch(java.io.IOException expected) { assertEquals("",first.ckb); }
        page.results.add(new ComicTranslationSkill.Result("لێرە بوەستە.","comic_individual",""));
        assertEquals(1,ComicTranslationFlow.apply(segments,page,"en","ckb",project.comicConfig));
        assertEquals(before,first.getRenderBox()); assertSame(style,first.style); assertEquals("HP: 25/100",first.text);
        assertEquals("خاڵ: 25/100",first.originalCkb); assertTrue(first.translationWarnings.contains("ocr_low"));
        assertEquals("unreviewed",first.readerReview); assertEquals("",first.readerReviewBinding);
        project.save(); TranslationProject disk=TranslationProject.load(project.projectDir);
        assertEquals(first.ckb,disk.pages.get(0).segments.get(0).ckb);
        assertEquals(2,ComicTranslationFlow.summarize(disk).processed);
        assertEquals(1,ComicTranslationFlow.summarize(disk).hints);
    }
    @Test public void actualOfflineAdapterAutomaticallyUsesRulesNamesAndChecksWithoutAnotherStep() throws Exception {
        AtomicInteger calls=new AtomicInteger();
        ComicTranslationSkill.Config config=new ComicTranslationSkill.Config(true,ComicTranslationSkill.Genre.FANTASY,"Jinwoo = جین‌وو");
        List<String> source=Arrays.asList("HP: 25/100","Jinwoo","An ordinary example without a matching phrase.");
        try (SoraniTranslator translator=new SoraniTranslator((text,from,to)->{
            assertEquals("ckb",to);calls.incrementAndGet();return "دەقی تاقیکردنەوەی ئاسایی.";
        })) {
            translator.setComicSkill(new ComicTranslationSkill(config,ComicSettings.lexicon(app)));
            ComicTranslationSkill.PageResult page=translator.translatePage(source,"en",null);
            assertTrue(translator.isOffline()); assertEquals(3,page.results.size());
            assertEquals("خاڵی تەندروستی: 25/100",page.results.get(0).text);
            assertEquals("جین‌وو",page.results.get(1).text);
            assertEquals(1,calls.get()); assertEquals(0,page.contextRequests);assertEquals(0,page.qualityRetries);
            List<Segment> segments=new ArrayList<>();for(String text:source)segments.add(segment(text));
            ComicTranslationFlow.apply(segments,page,"en","ckb",config);
            assertEquals("جین‌وو",segments.get(1).ckb);
            for(Segment segment:segments)assertEquals("unreviewed",segment.readerReview);
        }
    }
    @Test public void finishedResultsNeverRequireOpeningTheOptionalReviewScreen() throws Exception {
        TranslationProject project=project(); Segment segment=segment("Wait here.");
        segment.ckb="لێرە بوەستە.";segment.translationMethod="comic_individual";
        project.pages.get(0).segments.add(segment);
        ComicTranslationFlow.bindSummary(host,root,project,false);
        assertEquals(View.VISIBLE,root.findViewById(R.id.autoSkillResultSummary).getVisibility());
        assertEquals(View.GONE,root.findViewById(R.id.resultQualityButton).getVisibility());
        assertTrue(root.findViewById(R.id.translateButton).isEnabled());
        segment.translationWarnings="numbers";ComicTranslationFlow.bindSummary(host,root,project,false);
        assertEquals(View.VISIBLE,root.findViewById(R.id.resultQualityButton).getVisibility());
        assertTrue(((TextView)root.findViewById(R.id.resultQualityButton)).getText().toString().contains("optional"));
        ComicTranslationFlow.bindSummary(host,root,project,true);
        assertEquals(View.GONE,root.findViewById(R.id.resultQualityButton).getVisibility());
        assertNull(Shadows.shadowOf(app).getNextStartedActivity());
    }
}
