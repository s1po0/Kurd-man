package com.kurdman.app;

import java.nio.file.Files;
import java.nio.file.Paths;

/** Hosted, fixed-public-fixture offline metadata probe: does not install code. */
public final class ExtensionIndexProbe {
    public static void main(String[] args) throws Exception {
        if (!"true".equals(System.getenv("GITHUB_ACTIONS")) || !"github-hosted".equals(System.getenv("RUNNER_ENVIRONMENT")) || args.length!=1)
            throw new IllegalStateException("Disposable hosted fixture only");
        byte[] blob=Files.readAllBytes(Paths.get(args[0]));
        ExtensionStoreIndex index=new ExtensionCatalogClient(cancel -> blob).load(5000);
        if(!"Keiyoushi".equals(index.name)||!"9add655a78e96c4ec7a53ef89dccb557cb5d767489fac5e785d671a5a75d4da2".equals(index.signingKey)||index.entries.isEmpty())
            throw new IllegalStateException("Unexpected fixed public metadata");
        ExtensionStoreIndex.Entry example=index.entries.get(0);
        if(example.sources.isEmpty())throw new IllegalStateException("Expected real source metadata");
        ExtensionLibrarySections grouped=ExtensionLibrarySections.build(
                java.util.Arrays.asList(new ExtensionLibrarySections.Installed(example.packageName,"Installed example"),
                        new ExtensionLibrarySections.Installed("org.example.missing","Missing from store")),
                index.entries,example.sources.get(0).name);
        if(grouped.installed.size()!=1 || !grouped.installed.get(0).packageName.equals(example.packageName))
            throw new IllegalStateException("Installed site search/grouping failed");
        for(ExtensionLibrarySections.Row row:grouped.available)
            if(row.packageName.equals(example.packageName))throw new IllegalStateException("Installed extension duplicated");
        grouped=ExtensionLibrarySections.build(java.util.Collections.singletonList(
                new ExtensionLibrarySections.Installed("org.example.missing","Missing from store")),index.entries,"");
        if(grouped.installed.size()!=1||grouped.installed.get(0).catalogEntry!=null)
            throw new IllegalStateException("Locally present extension missing from section");
        System.out.println("Parsed pinned public store fixture: " + index.entries.size() + " entries; installed-first grouping checked. No download, installation or trust implied.");
    }
}
