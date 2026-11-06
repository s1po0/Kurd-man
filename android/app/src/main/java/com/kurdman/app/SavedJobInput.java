package com.kurdman.app;

import android.content.Context;
import android.graphics.Bitmap;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/** Frozen UI input. Sources are private and durable before translation starts. */
final class SavedJobInput {
    final File image,cbz;
    final List<File> images;
    final String name,base,source,target;
    final boolean offline,accurate,pdf;
    final ComicTranslationSkill.Config config;
    final TextStyleSettings style;
    Bitmap fallback;
    SavedJobInput(File image,File cbz,List<File> images,String name,String base,String source,String target,
            boolean offline,boolean accurate,boolean pdf,ComicTranslationSkill.Config config,TextStyleSettings style,Bitmap fallback){
        this.image=image;this.cbz=cbz;this.images=new ArrayList<>(images);this.name=name;this.base=base;
        this.source=source;this.target=target;this.offline=offline;this.accurate=accurate;this.pdf=pdf;
        this.config=config;this.style=new TextStyleSettings(style);this.fallback=fallback;
    }
    TranslationProject prepare(Context context) throws Exception {
        JobRuntime.throwIfStopped();
        if(!TranslationLanguages.source(source)||!TranslationLanguages.target(target))throw new IOException("Unsupported language pair");
        boolean batch=cbz==null&&images.size()>1;
        TranslationProject p=TranslationProjectStore.createNew(context,cbz!=null||batch,name,base,source);
        JobRuntime.activateSavedProject(p);
        p.targetLanguage=target;p.comicConfig=config;p.jobStyle=style;p.offlineMode=offline;p.accurateOcr=accurate;p.createPdf=pdf;p.imageBatch=batch;
        try {
            if(cbz!=null) {
                if(!cbz.isFile()||cbz.length()>300L*1024*1024)throw new IOException("CBZ import limit exceeded");
                try(ZipFile zip=new ZipFile(cbz)){
                    List<ZipEntry> entries=new ArrayList<>();Enumeration<? extends ZipEntry> all=zip.entries();
                    while(all.hasMoreElements()){ZipEntry e=all.nextElement();String n=e.getName().toLowerCase(Locale.ROOT);if(!e.isDirectory()&&!n.contains("__macosx")&&!n.endsWith(".ds_store")&&imageName(n))entries.add(e);}
                    entries.sort((a,b)->natural(a.getName(),b.getName()));
                    if(entries.isEmpty()||entries.size()>300)throw new IOException("CBZ page limit exceeded");
                    long total=0;int index=0;
                    for(ZipEntry entry:entries){JobRuntime.throwIfStopped();String path=TranslationProjectStore.pageRelativePath(++index,extension(entry.getName()));
                        TranslationProject.Page page=new TranslationProject.Page(path,extension(entry.getName()));
                        try(InputStream in=zip.getInputStream(entry);OutputStream out=new FileOutputStream(page.sourceFile(p.projectDir))){total+=ProjectArchive.copy(in,out,150L*1024*1024);}
                        if(total>1024L*1024*1024)throw new IOException("CBZ extraction limit exceeded");p.pages.add(page);p.save();
                    }
                }
            } else {
                List<File> input=batch?images:Collections.singletonList(image);
                if(input.size()>300)throw new IOException("Page limit exceeded");long total=0;
                for(int i=0;i<input.size();i++) {JobRuntime.throwIfStopped();File file=input.get(i);String ext=file==null?".jpg":extension(file.getName());
                    TranslationProject.Page page=new TranslationProject.Page(TranslationProjectStore.pageRelativePath(i+1,ext),ext);
                    try(OutputStream out=new FileOutputStream(page.sourceFile(p.projectDir))){
                        if(file!=null&&file.isFile()){try(InputStream in=new FileInputStream(file)){total+=ProjectArchive.copy(in,out,300L*1024*1024-total);}}
                        else if(!batch&&fallback!=null&&!fallback.isRecycled()){if(!fallback.compress(Bitmap.CompressFormat.JPEG,96,out))throw new IOException("Could not save source image");}
                        else throw new IOException("Source image missing");
                    }
                    p.pages.add(page);p.save();
                }
            }
            JobRuntime.throwIfStopped();p.resumable=true;p.autoResume=true;p.save();
            TranslationProjectStore.allowRecovery(context,p,true);return p;
        } finally {fallback=null;if(!p.resumable)p.save();}
    }
    static boolean imageName(String name){String n=name.toLowerCase(Locale.ROOT);return n.endsWith(".jpg")||n.endsWith(".jpeg")||n.endsWith(".png")||n.endsWith(".webp")||n.endsWith(".bmp");}
    static String extension(String name){int index=name.lastIndexOf('.');String ext=index<0?".jpg":name.substring(index).toLowerCase(Locale.ROOT);return ext.matches("\\.(jpg|jpeg|png|webp|bmp|gif|heic|heif|avif)")?ext:".jpg";}
    static int natural(String a,String b){int i=0,j=0;while(i<a.length()&&j<b.length()){char ca=a.charAt(i),cb=b.charAt(j);if(Character.isDigit(ca)&&Character.isDigit(cb)){int sa=i,sb=j;while(i<a.length()&&Character.isDigit(a.charAt(i)))i++;while(j<b.length()&&Character.isDigit(b.charAt(j)))j++;String na=a.substring(sa,i).replaceFirst("^0+",""),nb=b.substring(sb,j).replaceFirst("^0+","");if(na.length()!=nb.length())return Integer.compare(na.length(),nb.length());int n=na.compareTo(nb);if(n!=0)return n;}else{int n=Character.compare(Character.toLowerCase(ca),Character.toLowerCase(cb));if(n!=0)return n;i++;j++;}}return Integer.compare(a.length()-i,b.length()-j);}
}
