package com.kurdman.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.io.IOException;

/** Explicit, memory-only sampled preview; never fetches page resources automatically. */
final class StaticWebPreview {
    private static final int MAX_DIMENSION=2048;
    private final StaticWebTransport transport;
    StaticWebPreview(){this(new StaticWebTransport());}
    StaticWebPreview(StaticWebTransport transport){this.transport=transport;}
    Bitmap fetch(String url,PinnedHttpsClient.Cancellation cancel)throws IOException {
        StaticWebTransport.Image image=transport.image(url,StaticWebTransport.MAX_IMAGE,cancel);
        cancel.check();
        if(!StaticWebImport.validImage(image.bytes,image.extension))throw new IOException("Invalid preview image");
        BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;
        try{BitmapFactory.decodeByteArray(image.bytes,0,image.bytes.length,bounds);}
        catch(RuntimeException|OutOfMemoryError invalid){throw new IOException("Preview unavailable",invalid);}
        int sample=1;while(((long)bounds.outWidth+sample-1)/sample>MAX_DIMENSION||((long)bounds.outHeight+sample-1)/sample>MAX_DIMENSION){
            if(sample>=1<<29)throw new IOException("Invalid preview dimensions");sample*=2;
        }
        BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=sample;
        final Bitmap result;
        try{result=BitmapFactory.decodeByteArray(image.bytes,0,image.bytes.length,options);}
        catch(RuntimeException|OutOfMemoryError invalid){throw new IOException("Preview unavailable",invalid);}
        if(result==null)throw new IOException("Preview unavailable");
        if(result.getWidth()>MAX_DIMENSION||result.getHeight()>MAX_DIMENSION){result.recycle();throw new IOException("Preview exceeds limit");}
        try{cancel.check();return result;}catch(IOException stopped){result.recycle();throw stopped;}
    }
}
