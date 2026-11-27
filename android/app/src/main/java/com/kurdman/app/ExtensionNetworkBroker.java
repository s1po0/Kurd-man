package com.kurdman.app;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Experimental per-request network mediation for a future isolated extension
 * worker. Not a sandbox, not connected to extension execution, and not a
 * trust/consent decision. Only anonymous HTTPS GET with narrowly allowed headers
 * is supported. Every redirect and socket goes through the pinned public guard.
 * Android TLS and isolated-process behavior still need independent validation.
 */
final class ExtensionNetworkBroker implements AutoCloseable {
    interface Fetcher { PinnedHttpsClient.Response get(String url,int max,PinnedHttpsClient.Cancellation cancellation,Map<String,String> headers)throws IOException; }
    private static final ThreadPoolExecutor WORKERS=new ThreadPoolExecutor(2,2,0,TimeUnit.MILLISECONDS,new SynchronousQueue<>(),r->{
        Thread t=new Thread(r,"koorix-extension-network");t.setDaemon(true);return t;
    },new ThreadPoolExecutor.AbortPolicy());
    private final Fetcher fetcher;
    private boolean closed,busy;
    private PinnedHttpsClient.Cancellation active;
    private Future<PinnedHttpsClient.Response> activeFuture;
    ExtensionNetworkBroker(){this(PinnedHttpsClient::getFollowingRedirects);}
    ExtensionNetworkBroker(Fetcher fetcher){if(fetcher==null)throw new IllegalArgumentException();this.fetcher=fetcher;}
    PinnedHttpsClient.Response get(String url,Map<String,String> headers,int maxBytes,long timeoutMillis)throws IOException {
        PinnedHttpsClient.checkedUrl(url);Map<String,String> safeHeaders=PinnedHttpsClient.validatedHeaders(headers);
        if(maxBytes<1||maxBytes>PinnedHttpsClient.MAX_BODY||timeoutMillis<1||timeoutMillis>120000)throw new IOException("Invalid request limits");
        PinnedHttpsClient.Cancellation cancellation=new PinnedHttpsClient.Cancellation();
        synchronized(this){if(closed||busy)throw new IOException("Network session unavailable");busy=true;active=cancellation;}
        Future<PinnedHttpsClient.Response> future=null;
        try{
            try{future=WORKERS.submit(()->fetcher.get(url,maxBytes,cancellation,safeHeaders));}
            catch(RejectedExecutionException busy){throw new IOException("Network workers unavailable",busy);}
            synchronized(this){if(closed){future.cancel(true);throw new IOException("Network session closed");}activeFuture=future;}
            PinnedHttpsClient.Response result=future.get(timeoutMillis,TimeUnit.MILLISECONDS);
            cancellation.check();
            if(result==null||result.status!=200||result.contentType==null||result.contentType.length()>200||result.body==null||result.body.length==0||result.body.length>maxBytes||result.finalUrl==null)
                throw new IOException("Invalid network response");
            PinnedHttpsClient.checkedUrl(result.finalUrl);
            synchronized(this){if(closed)throw new IOException("Network session closed");return result;}
        }catch(TimeoutException e){cancellation.cancel();if(future!=null)future.cancel(true);throw new IOException("Network request timed out",e);}
        catch(InterruptedException e){cancellation.cancel();if(future!=null)future.cancel(true);Thread.currentThread().interrupt();throw new IOException("Network request interrupted",e);}
        catch(java.util.concurrent.CancellationException e){throw new IOException("Network request cancelled",e);}
        catch(ExecutionException e){if(e.getCause() instanceof IOException)throw (IOException)e.getCause();throw new IOException("Network request failed",e.getCause());}
        finally{synchronized(this){if(active==cancellation){active=null;activeFuture=null;busy=false;}}}
    }
    @Override public synchronized void close(){closed=true;if(active!=null)active.cancel();if(activeFuture!=null)activeFuture.cancel(true);}
}
