package com.kurdman.app;

import android.graphics.Bitmap;
import java.io.*;
import java.util.*;

/** Three memory-separated phases with a safe checkpoint after EACH page. */
final class SavedJobPipeline {
    interface Backend {
        List<Segment> recognize(Bitmap bitmap,TranslationProject project) throws Exception;
        void translate(List<Segment> segments,TranslationProject project) throws Exception;
        ManhwaRenderer.RenderResult render(Bitmap bitmap,TranslationProject project,TranslationProject.Page page) throws Exception;
        void closeOcr(); void closeTranslator(); void cancel();
    }
    interface Progress { void update(int phase,int page,int total); }
    private SavedJobPipeline() { }
    /** Prioritize visible browser pages without changing stored chapter order. */
    static List<Integer> workOrder(TranslationProject p) {
        List<Integer> order=new ArrayList<>();
        if(p.privateResults)for(int i=0;i<p.pages.size();i++)if(p.pages.get(i).webPriority)order.add(i);
        for(int i=0;i<p.pages.size();i++)if(!p.privateResults||!p.pages.get(i).webPriority)order.add(i);
        return order;
    }
    static void ocr(TranslationProject p,Backend backend,Progress progress) throws Exception {
        try {
            for(int i:workOrder(p)){JobRuntime.throwIfStopped();TranslationProject.Page page=p.pages.get(i);if(page.ocrComplete)continue;
                progress.update(0,i+1,p.pages.size());Bitmap bitmap=BitmapIo.decodeFile(page.sourceFile(p.projectDir));
                if(bitmap==null){page.unreadable=true;page.ocrComplete=true;page.translationComplete=true;p.save();continue;}
                try {List<Segment> segments=backend.recognize(bitmap,p);JobRuntime.throwIfStopped();page.width=bitmap.getWidth();page.height=bitmap.getHeight();page.segments.clear();page.segments.addAll(segments);page.ocrComplete=true;page.translationComplete=segments.isEmpty();p.save();}finally{bitmap.recycle();}
            }
        }finally{backend.closeOcr();}
    }
    static boolean needsTranslation(TranslationProject p){for(TranslationProject.Page page:p.pages)if(!page.translationComplete&&!page.unreadable)return true;return false;}
    static void translate(TranslationProject p,Backend backend,Progress progress) throws Exception {
        try{for(int i:workOrder(p)){JobRuntime.throwIfStopped();TranslationProject.Page page=p.pages.get(i);if(page.translationComplete||page.unreadable)continue;progress.update(1,i+1,p.pages.size());backend.translate(page.segments,p);JobRuntime.throwIfStopped();page.translationComplete=true;p.save();}}finally{backend.closeTranslator();}
    }
    static File renderedPage(TranslationProject p,int index){TranslationProject.Page page=p.pages.get(index);return new File(new File(p.projectDir,"rendered"),String.format(Locale.ROOT,"%04d%s",index+1,page.unreadable?SavedJobInput.extension(page.sourcePath):".jpg"));}
    static void render(TranslationProject p,Backend backend,Progress progress) throws Exception {
        File dir=new File(p.projectDir,"rendered");if(!dir.exists()&&!dir.mkdirs())throw new IOException("Could not create render directory");
        for(int i:workOrder(p)){JobRuntime.throwIfStopped();TranslationProject.Page page=p.pages.get(i);File output=renderedPage(p,i);if(page.renderComplete&&output.isFile())continue;progress.update(2,i+1,p.pages.size());
            Bitmap bitmap=page.unreadable?null:BitmapIo.decodeFile(page.sourceFile(p.projectDir));
            if(bitmap==null){try(InputStream in=new FileInputStream(page.sourceFile(p.projectDir));OutputStream out=new FileOutputStream(output)){ProjectArchive.copy(in,out,1024L*1024*1024);}page.renderComplete=true;p.save();continue;}
            Bitmap rendered=null;
            try{ManhwaRenderer.RenderResult result=backend.render(bitmap,p,page);rendered=result.bitmap;JobRuntime.throwIfStopped();writeJpeg(output,rendered);
                page.segments.clear();page.segments.addAll(result.segments);page.layoutPrepared=true;page.renderComplete=true;p.save();
            }finally{if(rendered!=null&&rendered!=bitmap)rendered.recycle();bitmap.recycle();}
        }
    }
    /** Explicit Studio export must re-render current edits, never reuse stale job pixels. */
    static void renderEdited(TranslationProject p,StyledTextRenderer.TypefaceResolver resolver,Progress progress) throws Exception {
        File dir=new File(p.projectDir,"rendered");
        if(!dir.exists()&&!dir.mkdirs())throw new IOException("Could not create render directory");
        for(int i=0;i<p.pages.size();i++) {
            JobRuntime.throwIfStopped();TranslationProject.Page page=p.pages.get(i);
            progress.update(2,i+1,p.pages.size());File source=page.sourceFile(p.projectDir);
            if(source==null||!source.isFile())throw new IOException("Saved source is missing");
            File output=renderedPage(p,i);Bitmap bitmap=page.unreadable?null:BitmapIo.decodeFile(source);
            if(bitmap==null) {ProjectResults.copy(source,output);page.renderComplete=true;p.save();continue;}
            Bitmap rendered=null;
            try {
                rendered=StyledTextRenderer.renderEdited(bitmap,page.segments,resolver).bitmap;
                JobRuntime.throwIfStopped();writeJpeg(output,rendered);
                page.layoutPrepared=true;page.renderComplete=true;p.save();
            } finally {if(rendered!=null&&rendered!=bitmap)rendered.recycle();bitmap.recycle();}
        }
    }
    static void writeJpeg(File file,Bitmap bitmap) throws IOException {
        android.util.AtomicFile atomic=new android.util.AtomicFile(file);FileOutputStream out=null;
        try{out=atomic.startWrite();if(!bitmap.compress(Bitmap.CompressFormat.JPEG,96,out))throw new IOException("Could not save render");atomic.finishWrite(out);}catch(IOException error){if(out!=null)atomic.failWrite(out);throw error;}
    }
}
