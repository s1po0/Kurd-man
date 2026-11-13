package com.kurdman.app;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/** Refuse new browser imports when existing app cache leaves insufficient room. */
final class WebImportBudget {
    static final long MAX_CACHE=1024L*1024*1024;
    static final long MAX_IMPORT=300L*1024*1024;
    private WebImportBudget() { }
    static long allowance(File cache) throws IOException {
        long used=count(cache,MAX_CACHE,0);
        return Math.min(MAX_IMPORT,Math.max(0,MAX_CACHE-used));
    }
    private static long count(File file,long limit,int depth) throws IOException {
        if(depth>64||Files.isSymbolicLink(file.toPath()))throw new IOException("Unsafe cache entry");
        if(file.isFile())return Math.min(limit,Math.max(0,file.length()));
        if(!file.exists())return 0;
        if(!file.isDirectory())throw new IOException("Unrecognized cache entry");
        File[] children=file.listFiles();if(children==null)throw new IOException("Could not inspect cache");
        long sum=0;
        for(File child:children){sum+=count(child,limit-sum,depth+1);if(sum>=limit)return limit;}
        return sum;
    }
}
