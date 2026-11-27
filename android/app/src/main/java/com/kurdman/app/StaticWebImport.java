package com.kurdman.app;

import android.graphics.BitmapFactory;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Worker-thread-only static image import candidate. The caller must impose a
 * bounded worker/deadline and dispose late results. Never publishes files or
 * executes page code. Not yet wired to the existing browser or translation UI.
 */
final class StaticWebImport {
    interface Progress { void update(int current,int total); }
    interface ImageValidator { boolean isSafe(byte[] data,String expectedExtension); }
    static final int MAX_PAGES=300;
    private final StaticWebTransport transport;
    private final ImageValidator validator;
    StaticWebImport(){this(new StaticWebTransport(),StaticWebImport::validImage);}
    StaticWebImport(StaticWebTransport transport,ImageValidator validator){this.transport=transport;this.validator=validator;}
    static final class Result {
        final File session;final List<File> files;final int failed;
        Result(File session,List<File> files,int failed){this.session=session;this.files=Collections.unmodifiableList(files);this.failed=failed;}
        /** Only the newly created session, never prior reader/project files. */
        void discard(File cache)throws IOException {
            File root=new File(cache,"web-reader").getCanonicalFile();
            if(session==null||Files.isSymbolicLink(session.toPath())||!root.equals(session.getCanonicalFile().getParentFile())
                    ||!session.getName().matches("[0-9a-f-]{36}"))throw new IOException("Unsafe import session");
            TranslationProjectStore.deleteRecursive(session);
        }
    }
    Result capture(List<String> urls,File cache,PinnedHttpsClient.Cancellation cancel,Progress progress)throws IOException {
        if(urls==null||urls.isEmpty()||urls.size()>MAX_PAGES||cache==null||cancel==null)throw new IOException("Invalid image request");
        cancel.check();
        LinkedHashSet<String> unique=new LinkedHashSet<>();for(String url:urls)unique.add(PinnedHttpsClient.checkedUrl(url).toASCIIString());
        long allowance=WebImportBudget.allowance(cache);if(allowance<1)throw new IOException("Cache full");
        File root=new File(cache,"web-reader");
        if(Files.isSymbolicLink(root.toPath())||(!root.isDirectory()&&!root.mkdirs()))throw new IOException("Cannot create import root");
        File session=new File(root,UUID.randomUUID().toString());if(!session.mkdir())throw new IOException("Cannot create import session");
        ArrayList<File> files=new ArrayList<>();Result result=null;int failed=0,index=0;long total=0;
        try{
            for(String url:unique){
                cancel.check();index++;if(progress!=null)progress.update(index,unique.size());
                long remaining=allowance-total;if(remaining<1){failed+=unique.size()-index+1;break;}
                File part=new File(session,String.format(Locale.ROOT,"%04d.part",index));
                try{
                    StaticWebTransport.Image image=transport.image(url,(int)Math.min(StaticWebTransport.MAX_IMAGE,remaining),cancel);
                    if(!validator.isSafe(image.bytes,image.extension)||WebImportBudget.allowance(cache)<image.bytes.length)throw new IOException("Invalid image or cache full");
                    try(FileOutputStream out=new FileOutputStream(part)){out.write(image.bytes);}
                    cancel.check();File dest=new File(session,String.format(Locale.ROOT,"%04d%s",index,image.extension));
                    if(!part.renameTo(dest))throw new IOException("Cannot finalize import");
                    files.add(dest);total+=image.bytes.length;
                }catch(IOException failure){part.delete();cancel.check();failed++;}
            }
            cancel.check();if(files.isEmpty())throw new IOException("No accessible images");
            result=new Result(session,new ArrayList<>(files),failed);return result;
        }finally{if(result==null)TranslationProjectStore.deleteRecursive(session);}
    }
    private static boolean validImage(byte[] data,String extension){
        BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;
        BitmapFactory.decodeByteArray(data,0,data.length,options);
        if(options.outWidth<1||options.outHeight<1||(long)options.outWidth*options.outHeight>100_000_000L)return false;
        String mime=options.outMimeType;
        return ".jpg".equals(extension)&&"image/jpeg".equals(mime)||".png".equals(extension)&&"image/png".equals(mime)
                ||".webp".equals(extension)&&"image/webp".equals(mime)||".bmp".equals(extension)&&"image/bmp".equals(mime);
    }
}
