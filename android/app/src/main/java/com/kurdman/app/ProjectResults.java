package com.kurdman.app;

import android.content.Context;
import android.content.ContentValues;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/** Durable products and idempotent, owner-scoped exports; never user source deletion. */
final class ProjectResults {
    private ProjectResults() { }
    static File directory(TranslationProject p){return new File(p.projectDir,"products");}
    static String stem(TranslationProject p){String base=(p.baseName==null?"Koorix":p.baseName).replaceAll("[^\\p{L}\\p{N}._ -]","_");if(base.isEmpty())base="Koorix";if(base.length()>60)base=base.substring(0,60);String id=p.projectDir.getName();return "Koorix-"+base+"-"+id+"."+p.targetLanguage.replaceAll("[^a-zA-Z0-9-]","");}
    static File result(TranslationProject p){return new File(directory(p),stem(p)+(p.cbz?".cbz":".jpg"));}
    static String pairs(TranslationProject p){StringBuilder out=new StringBuilder();for(int i=0;i<p.pages.size();i++){if(p.pages.size()>1)out.append("=== ").append(i+1).append(" / ").append(p.pages.size()).append(" ===\n");for(Segment s:p.pages.get(i).segments)out.append(s.text).append("\n→ ").append(s.ckb==null?"":s.ckb).append("\n\n");}return out.toString().trim();}
    static String binding(TranslationProject p) throws Exception {
        org.json.JSONObject json=p.toJson();for(String key:new String[]{"job","updatedAt","completed","displayName","baseName","comicSkill"})json.remove(key);
        org.json.JSONArray pages=json.getJSONArray("pages");
        for(int i=0;i<pages.length();i++){
            org.json.JSONObject page=pages.getJSONObject(i);
            for(String key:new String[]{"ocrComplete","translationComplete","renderComplete","webPriority"})page.remove(key);
            org.json.JSONArray segments=page.getJSONArray("segments");
            for(int j=0;j<segments.length();j++)for(String key:new String[]{"readerReview","readerReviewBinding","translationWarnings","translationMethod","ocrConfidence","originalCkb"})segments.getJSONObject(j).remove(key);
        }
        MessageDigest digest=MessageDigest.getInstance("SHA-256");byte[] bytes=digest.digest(json.toString().getBytes(StandardCharsets.UTF_8));StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();
    }
    static synchronized void publish(Context context,TranslationProject p) throws Exception { publish(context,p,false); }
    static synchronized void publishExplicit(Context context,TranslationProject p) throws Exception { publish(context,p,true); }
    private static void publish(Context context,TranslationProject p,boolean explicit) throws Exception {
        File dir=directory(p);if(!dir.exists()&&!dir.mkdirs())throw new IOException("Could not create product directory");
        List<File> pages=new ArrayList<>();for(int i=0;i<p.pages.size();i++)pages.add(SavedJobPipeline.renderedPage(p,i));
        File result=result(p);
        if(p.cbz){
            File temporary=new File(dir,"archive.part");
            try(ZipOutputStream zip=new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(temporary)))){
                zip.setLevel(1);for(File page:pages){JobRuntime.throwIfStopped();zip.putNextEntry(new ZipEntry(page.getName()));try(InputStream in=new FileInputStream(page)){ProjectArchive.copy(in,zip,1024L*1024*1024);}zip.closeEntry();}
            }
            java.nio.file.Files.move(temporary.toPath(),result.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }else copy(pages.get(0),result);
        // Dialogue remains in project.json and pairs(p) for editing/reopening.
        // No automatic TXT file, including when recovering an older project.
        List<File> exports=new ArrayList<>();exports.add(result);
        if(p.createPdf){File pdf=new File(dir,stem(p)+".pdf");PdfExporter.writeImageFiles(pages,pdf,(done,total,pdfPages)->JobRuntime.throwIfStopped());exports.add(pdf);}
        if(p.imageBatch){for(int i=0;i<pages.size();i++){File page=new File(dir,stem(p)+String.format(Locale.ROOT,"-%04d",i+1)+SavedJobInput.extension(pages.get(i).getName()));copy(pages.get(i),page);exports.add(page);}}
        // Exactly one public destination: Download/Koorix. Images in this
        // MediaStore collection are already visible to Gallery; a second
        // Pictures copy would make each translated page appear twice.
        if(!p.privateResults || explicit)for(File file:exports){JobRuntime.throwIfStopped();copyToLibrary(context,file);savePublic(context,file);}
        JobRuntime.throwIfStopped();p.exportsComplete=true;p.outputBinding=binding(p);p.save();
    }
    static File library(Context context){File root=context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);if(root==null)root=new File(context.getFilesDir(),Environment.DIRECTORY_DOWNLOADS);return new File(root,"KurdMan");}
    private static void copyToLibrary(Context context,File source) throws IOException {File dir=library(context);if(!dir.exists()&&!dir.mkdirs())throw new IOException("Could not create download library");copy(source,new File(dir,source.getName()));}
    static void copy(File source,File destination) throws IOException {
        android.util.AtomicFile atomic=new android.util.AtomicFile(destination);FileOutputStream out=null;
        try{out=atomic.startWrite();try(InputStream in=new FileInputStream(source)){ProjectArchive.copy(in,out,1024L*1024*1024);}atomic.finishWrite(out);}catch(IOException failure){if(out!=null)atomic.failWrite(out);throw failure;}
    }
    private static void savePublic(Context context,File file){
        if(Build.VERSION.SDK_INT<29)return;
        Uri uri=null;try{
            Uri collection=MediaStore.Downloads.EXTERNAL_CONTENT_URI;
            String relative=Environment.DIRECTORY_DOWNLOADS+"/Koorix/";
            String[] projection={MediaStore.MediaColumns._ID};
            try(android.database.Cursor cursor=context.getContentResolver().query(collection,projection,
                    MediaStore.MediaColumns.DISPLAY_NAME+"=? AND "+MediaStore.MediaColumns.RELATIVE_PATH+"=? AND owner_package_name=?",
                    new String[]{file.getName(),relative,context.getPackageName()},null)){
                // A failed/unavailable lookup is not evidence of absence. Keep
                // the durable app copy rather than blindly inserting duplicates.
                if(cursor==null)return;
                if(cursor.moveToFirst())uri=android.content.ContentUris.withAppendedId(collection,cursor.getLong(0));
            }
            if(uri==null){ContentValues values=new ContentValues();values.put(MediaStore.MediaColumns.DISPLAY_NAME,file.getName());values.put(MediaStore.MediaColumns.MIME_TYPE,mime(file));values.put(MediaStore.MediaColumns.RELATIVE_PATH,relative);values.put(MediaStore.MediaColumns.IS_PENDING,1);uri=context.getContentResolver().insert(collection,values);}
            if(uri==null)return;
            try(InputStream in=new FileInputStream(file);OutputStream out=context.getContentResolver().openOutputStream(uri,"wt")){if(out==null)return;ProjectArchive.copy(in,out,1024L*1024*1024);}
            ContentValues ready=new ContentValues();ready.put(MediaStore.MediaColumns.IS_PENDING,0);context.getContentResolver().update(uri,ready,null,null);
        }catch(Exception failure){/* The durable private download-library copy is always retained. */}
    }
    private static String mime(File file){String n=file.getName().toLowerCase(Locale.ROOT);return n.endsWith(".pdf")?"application/pdf":n.endsWith(".cbz")?"application/zip":n.endsWith(".png")?"image/png":n.endsWith(".webp")?"image/webp":n.endsWith(".bmp")?"image/bmp":"image/jpeg";}
}
