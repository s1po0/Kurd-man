package com.kurdman.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Presentation-only grouping groundwork; not yet attached to a store UI.
 * Installed means locally present, NOT trusted, safe, compatible or enabled.
 * Callers must separately verify identity and signer before loading code.
 */
final class ExtensionLibrarySections {
    static final class Installed {
        final String packageName, displayName;
        Installed(String packageName,String displayName){
            if(packageName==null||packageName.isEmpty()||displayName==null||displayName.trim().isEmpty())
                throw new IllegalArgumentException("Installed extension requires package and label");
            this.packageName=packageName;this.displayName=displayName;
        }
    }
    static final class Row {
        final String packageName, displayName;
        final boolean installed;
        // An available catalog entry with the same package name does not prove
        // signer identity, update eligibility, or permission to run it.
        final ExtensionStoreIndex.Entry catalogEntry;
        Row(String pkg,String name,boolean installed,ExtensionStoreIndex.Entry entry){
            this.packageName=pkg;this.displayName=name;this.installed=installed;this.catalogEntry=entry;
        }
    }
    final List<Row> installed, available;
    private ExtensionLibrarySections(List<Row> installed,List<Row> available){
        this.installed=Collections.unmodifiableList(installed);this.available=Collections.unmodifiableList(available);
    }
    static ExtensionLibrarySections build(List<Installed> local,List<ExtensionStoreIndex.Entry> catalog,String query){
        if(local==null||catalog==null)throw new IllegalArgumentException("Missing sections");
        String search=query==null?"":query.trim().toLowerCase(Locale.ROOT);
        Map<String,ExtensionStoreIndex.Entry> byPackage=new HashMap<>();
        for(ExtensionStoreIndex.Entry e:catalog){
            if(e==null||byPackage.put(e.packageName,e)!=null)throw new IllegalArgumentException("Ambiguous catalog package");
        }
        List<Row> present=new ArrayList<>(),remote=new ArrayList<>();Set<String> installedPackages=new HashSet<>();
        for(Installed item:local){
            if(item==null||!installedPackages.add(item.packageName))throw new IllegalArgumentException("Ambiguous installed package");
            ExtensionStoreIndex.Entry entry=byPackage.get(item.packageName);
            if(matches(item.displayName,item.packageName,entry,search))present.add(new Row(item.packageName,item.displayName,true,entry));
        }
        for(ExtensionStoreIndex.Entry entry:catalog){
            if(!installedPackages.contains(entry.packageName)&&matches(entry.name,entry.packageName,entry,search))
                remote.add(new Row(entry.packageName,entry.name,false,entry));
        }
        Comparator<Row> order=Comparator.comparing((Row r)->r.displayName.toLowerCase(Locale.ROOT))
                .thenComparing(r->r.packageName);
        present.sort(order);remote.sort(order);
        return new ExtensionLibrarySections(present,remote);
    }
    private static boolean matches(String label,String pkg,ExtensionStoreIndex.Entry entry,String query){
        if(query.isEmpty()||label.toLowerCase(Locale.ROOT).contains(query)||pkg.toLowerCase(Locale.ROOT).contains(query))return true;
        if(entry!=null)for(ExtensionStoreIndex.Source source:entry.sources)
            if(source.name.toLowerCase(Locale.ROOT).contains(query)||source.language.toLowerCase(Locale.ROOT).contains(query))return true;
        return false;
    }
}
