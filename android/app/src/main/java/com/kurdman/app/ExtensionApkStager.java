package com.kurdman.app;

import android.content.pm.PackageManager;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Experimental staged APK preparation only. This does not install or load an
 * extension. Store signer approval must be obtained independently from the
 * reader before calling; metadata must never approve itself.
 */
final class ExtensionApkStager {
    static final int MAX_APK_BYTES=32*1024*1024;
    private static final ThreadPoolExecutor WORKER=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,
            new SynchronousQueue<>(),r->{Thread t=new Thread(r,"koorix-extension-apk");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private ExtensionApkStager() { }
    static File prepare(ExtensionStoreIndex.Entry entry,String approvedSigner,File cacheDir,PackageManager pm,long timeoutMillis)throws IOException {
        if(entry==null||approvedSigner==null||!approvedSigner.matches("(?i)[0-9a-f]{64}")||cacheDir==null||pm==null
                ||timeoutMillis<1||timeoutMillis>120000)throw new IOException("Invalid extension request");
        PinnedHttpsClient.Cancellation cancellation=new PinnedHttpsClient.Cancellation();
        Future<File> future;
        try{future=WORKER.submit((Callable<File>)()->download(entry,approvedSigner,cacheDir,pm,cancellation));}
        catch(RejectedExecutionException busy){throw new IOException("Extension preparation already in progress",busy);}
        try{return future.get(timeoutMillis,TimeUnit.MILLISECONDS);}
        catch(TimeoutException e){cancellation.cancel();future.cancel(true);throw new IOException("Extension preparation timed out",e);}
        catch(InterruptedException e){cancellation.cancel();future.cancel(true);Thread.currentThread().interrupt();throw new IOException("Extension preparation interrupted",e);}
        catch(ExecutionException e){if(e.getCause() instanceof IOException)throw (IOException)e.getCause();throw new IOException("Extension preparation failed",e.getCause());}
    }
    private static File download(ExtensionStoreIndex.Entry entry,String approvedSigner,File cacheDir,PackageManager pm,
                                 PinnedHttpsClient.Cancellation cancellation)throws IOException {
        long capacity=WebImportBudget.allowance(cacheDir);
        if(capacity<1)throw new IOException("Cache is full");
        int max=(int)Math.min(MAX_APK_BYTES,capacity);
        PinnedHttpsClient.Response response=PinnedHttpsClient.getFollowingRedirects(entry.apkUrl,max,cancellation);
        cancellation.check();
        return stage(response.body,entry,approvedSigner,cacheDir,pm,cancellation);
    }
    static File stage(byte[] bytes,ExtensionStoreIndex.Entry entry,String approvedSigner,File cacheDir,PackageManager pm,
                      PinnedHttpsClient.Cancellation cancellation)throws IOException {
        if(bytes==null||bytes.length<4||bytes.length>MAX_APK_BYTES||bytes[0]!='P'||bytes[1]!='K'||bytes[2]!=3||bytes[3]!=4)
            throw new IOException("Downloaded content is not an APK archive");
        if(WebImportBudget.allowance(cacheDir)<bytes.length)throw new IOException("Cache is full");
        File dir=new File(cacheDir,"extension-install");
        if(Files.isSymbolicLink(dir.toPath())||(!dir.isDirectory()&&!dir.mkdirs()))throw new IOException("Cannot prepare private cache");
        File file=File.createTempFile("extension-",".apk",dir);boolean keep=false;
        try{
            try(FileOutputStream out=new FileOutputStream(file)){out.write(bytes);out.getFD().sync();}
            if(!file.setReadOnly())throw new IOException("Cannot protect staged APK");
            cancellation.check();
            ExtensionIdentityPolicy.Result result=ExtensionArchiveVerifier.inspect(pm,file,entry,approvedSigner);
            if(result!=ExtensionIdentityPolicy.Result.MATCH)throw new IOException("Extension identity refused: "+result.name());
            cancellation.check();keep=true;return file;
        }finally{if(!keep)file.delete();}
    }
}
