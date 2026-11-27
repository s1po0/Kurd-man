package com.kurdman.app;

import java.io.IOException;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Experimental fixed-public-store reader, not yet called by UI. This reads
 * untrusted metadata only; no APK installation or store approval is implied.
 * Call off the UI thread. It cannot be used to fetch arbitrary source URLs.
 */
final class ExtensionCatalogClient {
    private static final String KEIYOUSHI_INDEX="https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.pb";
    private static final long MAX_WAIT_MILLIS=60000;
    private static final ThreadPoolExecutor SHARED_WORKER=newWorker();
    interface Fetch { byte[] get() throws IOException; }
    private final ThreadPoolExecutor worker;
    private final Fetch fetch;
    ExtensionCatalogClient(){this(() -> PinnedHttpsClient.get(KEIYOUSHI_INDEX,ExtensionStoreIndex.MAX_COMPRESSED).body,SHARED_WORKER);}
    // Injection exists only to test cancellation/resource limits; production
    // uses the fixed HTTPS URL and the pinned-public-DNS transport above.
    ExtensionCatalogClient(Fetch fetch){this(fetch,newWorker());}
    private ExtensionCatalogClient(Fetch fetch,ThreadPoolExecutor worker){
        if(fetch==null)throw new IllegalArgumentException("Missing fetch");
        this.fetch=fetch;this.worker=worker;
    }
    private static ThreadPoolExecutor newWorker(){
        return new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new SynchronousQueue<>(),r -> {
            Thread t=new Thread(r,"koorix-extension-index");t.setDaemon(true);return t;
        },new ThreadPoolExecutor.AbortPolicy());
    }
    ExtensionStoreIndex load(long waitMillis)throws IOException {
        if(waitMillis<1||waitMillis>MAX_WAIT_MILLIS)throw new IOException("Invalid fetch timeout");
        Future<ExtensionStoreIndex> task;
        try {
            task=worker.submit((Callable<ExtensionStoreIndex>) () -> ExtensionStoreIndex.parse(fetch.get()));
        }catch(RejectedExecutionException busy){throw new IOException("Store fetch already in progress",busy);}
        try{return task.get(waitMillis,TimeUnit.MILLISECONDS);}
        catch(TimeoutException timeout){task.cancel(true);throw new IOException("Store fetch timed out",timeout);}
        catch(InterruptedException interrupted){task.cancel(true);Thread.currentThread().interrupt();throw new IOException("Store fetch interrupted",interrupted);}
        catch(ExecutionException failed){Throwable cause=failed.getCause();if(cause instanceof IOException)throw (IOException)cause;throw new IOException("Store metadata unavailable",cause);}
    }
}
