package com.kurdman.app;

import static org.junit.Assert.*;
import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Rect;
import org.robolectric.RuntimeEnvironment;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.io.*;
import java.util.*;
import java.util.zip.*;
import org.json.JSONObject;

/** Actual Android persistence/archive contracts; no translation-quality claims. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26,34},application=Application.class,qualifiers="en")
public class KoorixReliabilityTest {
    private Application app;
    @Before public void setup() {
        app=RuntimeEnvironment.getApplication();
        TranslationProjectStore.setCurrent(null);
        TranslationProjectStore.deleteRecursive(TranslationProjectStore.root(app));
        app.getSharedPreferences("koorix_projects",0).edit().clear().commit();
        JobRuntime.beginJob();JobRuntime.endJob();Thread.interrupted();
    }
    private TranslationProject sample(String name) throws Exception {
        TranslationProject p=TranslationProjectStore.createNew(app,false,name,name,"en");
        TranslationProject.Page page=new TranslationProject.Page("pages/0001.png",".png");
        File source=page.sourceFile(p.projectDir);
        try(OutputStream out=new FileOutputStream(source)) {
            Bitmap image=Bitmap.createBitmap(12,16,Bitmap.Config.ARGB_8888);
            image.compress(Bitmap.CompressFormat.PNG,100,out);image.recycle();
        }
        Segment s=new Segment("Wait here.",new Rect(1,2,10,14),1);s.ckb="لێرە بوەستە.";
        page.segments.add(s);p.pages.add(page);p.completed=true;p.save();return p;
    }
    @Test public void newJobNeverDeletesPreviousSourceOrEditableText() throws Exception {
        TranslationProject old=sample("old");File source=old.pages.get(0).sourceFile(old.projectDir);
        TranslationProject fresh=sample("new");
        assertNotEquals(old.projectDir,fresh.projectDir);assertTrue(source.isFile());
        assertEquals("لێرە بوەستە.",TranslationProject.load(old.projectDir).pages.get(0).segments.get(0).ckb);
        assertEquals(2,TranslationProjectStore.list(app).size());
    }
    @Test public void legacyCurrentIsNotMovedOrDiscardedByANewJob() throws Exception {
        TranslationProject p=sample("temporary");
        File oldDir=p.projectDir; File legacy=new File(TranslationProjectStore.root(app),"current");
        assertTrue(oldDir.renameTo(legacy));p.projectDir=legacy;TranslationProjectStore.activate(app,p);
        sample("next");assertTrue(new File(legacy,"project.json").exists());
        assertEquals(2,TranslationProjectStore.list(app).size());
    }
    @Test public void selectionSurvivesProcessMemoryResetAndStaleSaveIsRejected() throws Exception {
        TranslationProject a=sample("A"), b=sample("B");
        TranslationProjectStore.activate(app,a);String id=a.projectDir.getName();
        TranslationProjectStore.setCurrent(null);TranslationProject loaded=TranslationProjectStore.load(app);
        assertEquals(id,loaded.projectDir.getName());
        TranslationProjectStore.activate(app,b);
        try{TranslationProjectStore.saveSnapshot(loaded,loaded.snapshotJson());fail();}catch(IOException expected){}
        assertEquals("B",TranslationProjectStore.load(app).displayName);
    }
    @Test public void jobCheckpointsAndFrozenChoicesSurviveSerialization() throws Exception {
        TranslationProject p=sample("checkpoint");p.completed=false;p.resumable=true;p.autoResume=true;
        p.offlineMode=true;p.accurateOcr=false;p.createPdf=true;p.imageBatch=true;
        p.quotaJobId=UUID.randomUUID().toString();p.jobOwner="synthetic-owner";
        p.pages.get(0).ocrComplete=true;p.pages.get(0).translationComplete=true;
        p.comicConfig=new ComicTranslationSkill.Config(true,ComicTranslationSkill.Genre.MARTIAL,"Jin = جین");p.save();
        TranslationProject r=TranslationProject.load(p.projectDir);
        assertFalse(r.completed);assertTrue(r.resumable);assertTrue(r.autoResume);assertTrue(r.offlineMode);
        assertFalse(r.accurateOcr);assertTrue(r.createPdf);assertTrue(r.imageBatch);
        assertEquals(p.quotaJobId,r.quotaJobId);assertTrue(r.pages.get(0).translationComplete);
        assertEquals(p.comicConfig.glossary,r.comicConfig.glossary);
    }
    @Test public void backupRoundTripPreservesReaderDataButNotAccountPermission() throws Exception {
        TranslationProject p=sample("backup");p.targetLanguage="ar";p.quotaJobId=UUID.randomUUID().toString();
        p.jobOwner="synthetic-owner";p.autoResume=true;p.pages.get(0).segments.get(0).ensureStyle().visible=false;p.save();
        File archive=new File(app.getCacheDir(),"roundtrip.koorix");
        try(OutputStream out=new FileOutputStream(archive)){ProjectArchive.exportProject(p,out);}
        TranslationProject imported=ProjectArchive.importProject(app,archive);
        assertNotEquals(p.projectDir,imported.projectDir);assertTrue(p.pages.get(0).sourceFile(p.projectDir).isFile());
        assertEquals("ar",imported.targetLanguage);assertEquals("Wait here.",imported.pages.get(0).segments.get(0).text);
        assertEquals("لێرە بوەستە.",imported.pages.get(0).segments.get(0).ckb);
        assertFalse(imported.pages.get(0).segments.get(0).ensureStyle().visible);
        assertEquals("",imported.quotaJobId);assertEquals("",imported.jobOwner);assertFalse(imported.autoResume);
    }
    @Test public void maliciousBackupNeverReplacesCurrentOrWritesOutsideLibrary() throws Exception {
        TranslationProject existing=sample("keep");
        for(String path:Arrays.asList("../escaped.png","/tmp/escaped.png","pages/../escaped.png",".env")) {
            File archive=new File(app.getCacheDir(),"malicious.koorix");
            try(ZipOutputStream out=new ZipOutputStream(new FileOutputStream(archive))) {
                out.putNextEntry(new ZipEntry("project.json"));out.write(existing.toJson().toString().getBytes("UTF-8"));out.closeEntry();
                out.putNextEntry(new ZipEntry(path));out.write(1);out.closeEntry();
            }
            try{ProjectArchive.importProject(app,archive);fail("Unsafe backup accepted");}catch(IOException expected){}
            assertSame(existing,TranslationProjectStore.load(app));
            assertTrue(existing.pages.get(0).sourceFile(existing.projectDir).exists());
        }
    }
    @Test public void sourceResolverRejectsPathsOutsideProject() throws Exception {
        TranslationProject p=sample("paths");TranslationProject.Page page=p.pages.get(0);
        page.sourcePath="../../account.xml";assertNull(page.sourceFile(p.projectDir));
        page.sourcePath="pages/0001.png";assertNotNull(page.sourceFile(p.projectDir));
    }
    @Test public void explicitDeleteOnlyDeletesChosenProject() throws Exception {
        TranslationProject a=sample("A"), b=sample("B");
        TranslationProjectStore.delete(app,a);assertFalse(a.projectDir.exists());assertTrue(b.projectDir.exists());
        assertSame(b,TranslationProjectStore.load(app));assertEquals(1,TranslationProjectStore.list(app).size());
    }
}
