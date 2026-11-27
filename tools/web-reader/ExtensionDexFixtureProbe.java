package com.kurdman.app;

import java.io.File;
import java.util.List;

/** Inspect (never load or execute) the independently signature-checked CI fixture. */
public final class ExtensionDexFixtureProbe {
    public static void main(String[] args)throws Exception {
        if(!"true".equals(System.getenv("GITHUB_ACTIONS"))||!"github-hosted".equals(System.getenv("RUNNER_ENVIRONMENT")))
            throw new IllegalStateException("Disposable hosted runner only");
        if(args.length!=1)throw new IllegalArgumentException("Expected fixture path");
        List<byte[]> dex=ExtensionDexExtractor.extract(new File(args[0]));
        long size=0;for(byte[] part:dex)size+=part.length;
        System.out.println("Extracted "+dex.size()+" DEX entries ("+size+" bytes); no code loaded or executed.");
    }
}
