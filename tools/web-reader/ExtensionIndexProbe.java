package com.kurdman.app;

import java.nio.file.Files;
import java.nio.file.Paths;

/** Hosted, fixed-public-fixture offline metadata probe: does not install code. */
public final class ExtensionIndexProbe {
    public static void main(String[] args) throws Exception {
        if (!"true".equals(System.getenv("GITHUB_ACTIONS")) || !"github-hosted".equals(System.getenv("RUNNER_ENVIRONMENT")) || args.length!=1)
            throw new IllegalStateException("Disposable hosted fixture only");
        byte[] blob=Files.readAllBytes(Paths.get(args[0]));
        ExtensionStoreIndex index=ExtensionStoreIndex.parse(blob);
        if(!"Keiyoushi".equals(index.name)||!"9add655a78e96c4ec7a53ef89dccb557cb5d767489fac5e785d671a5a75d4da2".equals(index.signingKey)||index.entries.isEmpty())
            throw new IllegalStateException("Unexpected fixed public metadata");
        System.out.println("Parsed pinned public store fixture: " + index.entries.size() + " entries. No download, installation or trust implied.");
    }
}
