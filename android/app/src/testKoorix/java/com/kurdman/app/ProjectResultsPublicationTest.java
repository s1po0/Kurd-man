package com.kurdman.app;

import static org.junit.Assert.*;
import android.app.Application;
import android.content.*;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowContentResolver;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.ZipFile;

/** Actual production publication/persistence with an in-memory public MediaStore boundary.
 * Not Samsung Gallery or physical-device acceptance. No test hooks in the APK. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk={26,34},application=Application.class,qualifiers="en")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ProjectResultsPublicationTest {
    Application app;
    PublicMedia media;
    @Before public void setup() {
        app=RuntimeEnvironment.getApplication();JobRuntime.init(app);JobRuntime.beginJob();JobRuntime.endJob();Thread.interrupted();
        TranslationProjectStore.setCurrent(null);TranslationProjectStore.deleteRecursive(TranslationProjectStore.root(app));
        TranslationProjectStore.deleteRecursive(ProjectResults.library(app));
        media=new PublicMedia();ProviderInfo info=new ProviderInfo();info.authority="media";media.attachInfo(app,info);
        ShadowContentResolver.registerProviderInternal("media",media);
    }
    @After public void finish() {JobRuntime.endJob();Thread.interrupted();}
    TranslationProject sample(int count,boolean archive,boolean batch,boolean pdf) throws Exception {
        TranslationProject p=TranslationProjectStore.createNew(app,archive,"Synthetic publication","synthetic-publication","en");
        p.targetLanguage="ckb";p.imageBatch=batch;p.createPdf=pdf;
        File renderedDir=new File(p.projectDir,"rendered");assertTrue(renderedDir.mkdirs());
        for(int i=0;i<count;i++) {
            TranslationProject.Page page=new TranslationProject.Page(TranslationProjectStore.pageRelativePath(i+1,".png"),".png");
            Bitmap image=Bitmap.createBitmap(48,64,Bitmap.Config.ARGB_8888);image.eraseColor(i==0?Color.WHITE:Color.LTGRAY);
            try(OutputStream out=new FileOutputStream(page.sourceFile(p.projectDir))){assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,out));}
            Segment s=new Segment("Wait here.",new Rect(4,4,44,30),1);s.ckb="لێرە بوەستە.";page.segments.add(s);
            page.width=48;page.height=64;page.ocrComplete=true;page.translationComplete=true;page.renderComplete=true;
            p.pages.add(page);SavedJobPipeline.writeJpeg(SavedJobPipeline.renderedPage(p,i),image);image.recycle();
        }
        p.save();return p;
    }
    void noNewTextFiles(File directory) {
        File[] children=directory.listFiles();assertNotNull(children);
        for(File child:children)assertFalse("No automatic TXT sidecar: "+child.getName(),child.getName().endsWith(".txt"));
    }
    @Test public void normalTranslationCreatesOnePublicImageAndNoAutomaticTextSidecar() throws Exception {
        TranslationProject p=sample(1,false,false,false);ProjectResults.publish(app,p);
        assertTrue(ProjectResults.result(p).isFile());noNewTextFiles(ProjectResults.directory(p));noNewTextFiles(ProjectResults.library(app));
        if(Build.VERSION.SDK_INT>=29) {
            assertEquals("One public file, not both Downloads and Pictures",1,media.rows.size());
            Row only=media.rows.get(0);assertEquals(MediaStore.Downloads.EXTERNAL_CONTENT_URI,only.collection);
            assertEquals("Download/Koorix/",only.values.getAsString(MediaStore.MediaColumns.RELATIVE_PATH));
            assertEquals("image/jpeg",only.values.getAsString(MediaStore.MediaColumns.MIME_TYPE));
            assertEquals(Integer.valueOf(0),only.values.getAsInteger(MediaStore.MediaColumns.IS_PENDING));
            assertArrayEquals(Files.readAllBytes(ProjectResults.result(p).toPath()),Files.readAllBytes(only.file.toPath()));
        } else assertTrue(media.rows.isEmpty());
    }
    @Test public void textAndGeometryStillSurviveProjectReopenAndBackupWithoutAnyTxt() throws Exception {
        TranslationProject p=sample(1,false,false,false);ProjectResults.publish(app,p);p.completed=true;p.save();
        TranslationProject reopened=TranslationProject.load(p.projectDir);Segment s=reopened.pages.get(0).segments.get(0);
        assertEquals("Wait here.",s.text);assertEquals("لێرە بوەستە.",s.ckb);assertEquals(new Rect(4,4,44,30),s.box);
        assertTrue(ProjectResults.pairs(reopened).contains("\n→ لێرە بوەستە."));
        File backup=new File(app.getCacheDir(),"publication-roundtrip.koorix");
        try(OutputStream out=new FileOutputStream(backup)){ProjectArchive.exportProject(reopened,out);}
        TranslationProject imported=ProjectArchive.importProject(app,backup);
        assertEquals(s.ckb,imported.pages.get(0).segments.get(0).ckb);noNewTextFiles(ProjectResults.directory(p));
    }
    @Test public void republishingAfterARecoveryOrAnEditUpdatesTheSameOwnedRow() throws Exception {
        TranslationProject p=sample(1,false,false,false);ProjectResults.publish(app,p);
        p=TranslationProject.load(p.projectDir);p.pages.get(0).segments.get(0).ckb="دەقی گۆڕاو";
        Bitmap edited=Bitmap.createBitmap(48,64,Bitmap.Config.ARGB_8888);edited.eraseColor(Color.BLUE);
        SavedJobPipeline.writeJpeg(SavedJobPipeline.renderedPage(p,0),edited);edited.recycle();ProjectResults.publish(app,p);
        if(Build.VERSION.SDK_INT>=29){assertEquals(1,media.rows.size());assertArrayEquals(Files.readAllBytes(ProjectResults.result(p).toPath()),Files.readAllBytes(media.rows.get(0).file.toPath()));}
        assertEquals(1,Objects.requireNonNull(ProjectResults.library(app).listFiles()).length);
        assertEquals(ProjectResults.binding(p),TranslationProject.load(p.projectDir).outputBinding);assertEquals(0,media.deletes);
    }
    @Test public void batchesKeepExactlyOneImagePerPagePlusRequestedArchive() throws Exception {
        TranslationProject p=sample(2,true,true,false);ProjectResults.publish(app,p);
        noNewTextFiles(ProjectResults.directory(p));noNewTextFiles(ProjectResults.library(app));
        try(ZipFile zip=new ZipFile(ProjectResults.result(p))){assertEquals(2,zip.size());assertNotNull(zip.getEntry("0001.jpg"));assertNotNull(zip.getEntry("0002.jpg"));}
        if(Build.VERSION.SDK_INT>=29) {
            assertEquals(3,media.rows.size());assertEquals(2,media.rows.stream().filter(r->r.values.getAsString("mime_type").startsWith("image/")).count());
            assertTrue(media.rows.stream().allMatch(r->"Download/Koorix/".equals(r.values.getAsString("relative_path"))));
            ProjectResults.publish(app,p);assertEquals(3,media.rows.size());
        }
    }
    // Robolectric's native PdfDocument backend is available here on34, not26.
    // Archive/batch/storage contracts above still execute on both SDKs; no skip.
    @Test @Config(sdk=34) public void selectedPdfStillPublishesWithTheArchiveAndExactlyOneImagePerPage() throws Exception {
        TranslationProject p=sample(2,true,true,true);ProjectResults.publish(app,p);
        File pdf=new File(ProjectResults.directory(p),ProjectResults.stem(p)+".pdf");
        assertTrue(pdf.length()>100);assertEquals("%PDF",new String(Files.readAllBytes(pdf.toPath()),0,4,java.nio.charset.StandardCharsets.US_ASCII));
        assertEquals(4,media.rows.size());noNewTextFiles(ProjectResults.directory(p));noNewTextFiles(ProjectResults.library(app));
        ProjectResults.publish(app,p);assertEquals(4,media.rows.size());
    }
    @Test public void cbzWithoutIndividualImageChoiceDoesNotExtractAPublicImageOrTxt() throws Exception {
        TranslationProject p=sample(2,true,false,false);ProjectResults.publish(app,p);
        noNewTextFiles(ProjectResults.directory(p));assertEquals(1,Objects.requireNonNull(ProjectResults.library(app).listFiles()).length);
        if(Build.VERSION.SDK_INT>=29){assertEquals(1,media.rows.size());assertEquals("application/zip",media.rows.get(0).values.getAsString("mime_type"));}
    }
    @Test public void upgradeNeverDeletesPreviouslySavedReaderImagesOrText() throws Exception {
        TranslationProject p=sample(1,false,false,false);
        File legacyText=new File(ProjectResults.directory(p),ProjectResults.stem(p)+".txt");assertTrue(legacyText.getParentFile().mkdirs());
        byte[] before="Previous reader export".getBytes(java.nio.charset.StandardCharsets.UTF_8);Files.write(legacyText.toPath(),before);
        File oldLibrary=new File(ProjectResults.library(app),"old-reader.txt");assertTrue(oldLibrary.getParentFile().mkdirs());Files.write(oldLibrary.toPath(),before);
        ProjectResults.publish(app,p);assertArrayEquals(before,Files.readAllBytes(legacyText.toPath()));assertArrayEquals(before,Files.readAllBytes(oldLibrary.toPath()));assertEquals(0,media.deletes);
        assertTrue(media.rows.stream().noneMatch(r->r.values.getAsString("_display_name").endsWith(".txt")));
    }
    @Test public void anotherAppsSameNameIsNotOverwrittenOrDeleted() throws Exception {
        TranslationProject p=sample(1,false,false,false);
        if(Build.VERSION.SDK_INT<29)return;
        Row other=media.seed(MediaStore.Downloads.EXTERNAL_CONTENT_URI,ProjectResults.result(p).getName(),"Download/Koorix/","another.app");
        Files.write(other.file.toPath(),new byte[]{7,8,9});ProjectResults.publish(app,p);
        assertArrayEquals(new byte[]{7,8,9},Files.readAllBytes(other.file.toPath()));assertEquals(2,media.rows.size());assertEquals(0,media.deletes);
    }
    @Test public void studioRendersCurrentSourcesForEveryPageEvenWithCompletedOldCaches() throws Exception {
        TranslationProject p=sample(2,true,true,false);
        Bitmap fresh=Bitmap.createBitmap(48,64,Bitmap.Config.ARGB_8888);fresh.eraseColor(Color.RED);
        for(TranslationProject.Page page:p.pages) {
            page.segments.get(0).ensureStyle().visible=false;
            try(OutputStream out=new FileOutputStream(page.sourceFile(p.projectDir))){assertTrue(fresh.compress(Bitmap.CompressFormat.PNG,100,out));}
        }
        fresh.recycle();List<Integer> progress=new ArrayList<>();
        SavedJobPipeline.renderEdited(p,style->android.graphics.Typeface.DEFAULT,(phase,page,total)->progress.add(page));
        assertEquals(Arrays.asList(1,2),progress);
        for(int i=0;i<2;i++) {
            Bitmap output=BitmapIo.decodeFile(SavedJobPipeline.renderedPage(p,i));
            try{assertTrue(Color.red(output.getPixel(24,32))>240);assertTrue(Color.green(output.getPixel(24,32))<12);}
            finally{output.recycle();}
        }
        ProjectResults.publish(app,p);assertEquals(ProjectResults.binding(p),TranslationProject.load(p.projectDir).outputBinding);
    }
    @Test public void stoppedStudioExportNeverReplacesAnEarlierProductOrDeletesItsSource() throws Exception {
        TranslationProject p=sample(2,true,true,false);ProjectResults.publish(app,p);
        byte[] before=Files.readAllBytes(ProjectResults.result(p).toPath());JobRuntime.requestStop();
        try {SavedJobPipeline.renderEdited(p,style->android.graphics.Typeface.DEFAULT,(phase,page,total)->{});fail("STOP ignored");}
        catch(InterruptedException expected) { }
        assertArrayEquals(before,Files.readAllBytes(ProjectResults.result(p).toPath()));assertTrue(p.pages.get(0).sourceFile(p.projectDir).isFile());assertEquals(0,media.deletes);
    }
    @Test public void unavailableDuplicateLookupDoesNotBlindlyInsertAnotherPublicCopy() throws Exception {
        TranslationProject p=sample(1,false,false,false);ProjectResults.publish(app,p);
        int existing=media.rows.size();media.unavailableQuery=true;ProjectResults.publish(app,p);
        assertEquals("Keep the durable library; do not create duplicates when public lookup is unavailable",existing,media.rows.size());
        assertTrue(ProjectResults.result(p).isFile());assertEquals(0,media.deletes);
    }
    static class Row {
        Uri collection;ContentValues values;File file;long id;
    }
    public static class PublicMedia extends ContentProvider {
        final List<Row> rows=new ArrayList<>();int deletes;boolean unavailableQuery;
        @Override public boolean onCreate(){return true;}
        Row seed(Uri collection,String name,String path,String owner) throws IOException {
            Row row=new Row();row.id=rows.size()+1;row.collection=collection;row.values=new ContentValues();
            row.values.put("_display_name",name);row.values.put("relative_path",path);row.values.put("owner_package_name",owner);
            row.file=File.createTempFile("public-media-",".bin",getContext().getCacheDir());rows.add(row);return row;
        }
        @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String sort) {
            if(unavailableQuery)return null;
            MatrixCursor cursor=new MatrixCursor(projection);
            for(Row row:rows)if(row.collection.equals(uri)&&args!=null&&args.length==3&&args[0].equals(row.values.getAsString("_display_name"))&&args[1].equals(row.values.getAsString("relative_path"))&&args[2].equals(row.values.getAsString("owner_package_name")))cursor.addRow(new Object[]{row.id});
            return cursor;
        }
        @Override public Uri insert(Uri uri,ContentValues values) {
            try {Row row=seed(uri,values.getAsString("_display_name"),values.getAsString("relative_path"),getContext().getPackageName());row.values.putAll(values);return ContentUris.withAppendedId(uri,row.id);}
            catch(IOException e){throw new IllegalStateException(e);}
        }
        Row at(Uri uri){long id=ContentUris.parseId(uri);return rows.stream().filter(r->r.id==id).findFirst().orElseThrow(IllegalArgumentException::new);}
        @Override public ParcelFileDescriptor openFile(Uri uri,String mode) throws FileNotFoundException {return ParcelFileDescriptor.open(at(uri).file,ParcelFileDescriptor.MODE_READ_WRITE|ParcelFileDescriptor.MODE_CREATE|ParcelFileDescriptor.MODE_TRUNCATE);}
        @Override public int update(Uri uri,ContentValues values,String where,String[] args){at(uri).values.putAll(values);return 1;}
        @Override public int delete(Uri uri,String where,String[] args){deletes++;return 1;}
        @Override public String getType(Uri uri){return "application/octet-stream";}
    }
}
