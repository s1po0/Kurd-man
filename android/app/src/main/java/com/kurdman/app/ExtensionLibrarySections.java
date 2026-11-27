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
 * Presentation-only grouping for the catalog. Local entries can be Android
 * installed packages or explicit private APK imports; neither implies trust,
 * compatibility, permission to execute, or verified signer identity.
 */
final class ExtensionLibrarySections {
    static final class Installed {
        final String packageName, displayName;
        final boolean systemInstalled, imported;
        Installed(String packageName,String displayName){this(packageName,displayName,true,false);}
        Installed(String packageName,String displayName,boolean systemInstalled,boolean imported){
            if(packageName==null||packageName.isEmpty()||displayName==null||displayName.trim().isEmpty()||(!systemInstalled&&!imported))
                throw new IllegalArgumentException("Local extension requires package and label");
            this.packageName=packageName;this.displayName=displayName;this.systemInstalled=systemInstalled;this.imported=imported;
        }
    }
    static final class Row {
        final String packageName, displayName;
        final boolean installed,systemInstalled,imported;
        // An available catalog entry with the same package name does not prove
        // signer identity, update eligibility, or permission to run it.
        final ExtensionStoreIndex.Entry catalogEntry;
        Row(String pkg,String name,boolean installed,ExtensionStoreIndex.Entry entry,boolean systemInstalled,boolean imported){
            this.packageName=pkg;this.displayName=name;this.installed=installed;this.catalogEntry=entry;this.systemInstalled=systemInstalled;this.imported=imported;
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
            String display=item.imported&&!item.systemInstalled&&entry!=null?entry.name:item.displayName;
            if(matches(display,item.packageName,entry,search))present.add(new Row(item.packageName,display,true,entry,item.systemInstalled,item.imported));
        }
        for(ExtensionStoreIndex.Entry entry:catalog){
            if(!installedPackages.contains(entry.packageName)&&matches(entry.name,entry.packageName,entry,search))
                remote.add(new Row(entry.packageName,entry.name,false,entry,false,false));
        }
        Comparator<Row> order=Comparator.comparing((Row r)->r.displayName.toLowerCase(Locale.ROOT))
                .thenComparing(r->r.packageName);
        present.sort(order);remote.sort(order);
        return new ExtensionLibrarySections(present,remote);
    }
    static List<Installed> mergeLocal(List<Installed> system,List<Installed> imported){
        if(system==null||imported==null)throw new IllegalArgumentException("Missing local inventory");
        Map<String,Installed> merged=new java.util.LinkedHashMap<>();
        for(Installed item:system){if(item==null||!item.systemInstalled||merged.put(item.packageName,item)!=null)throw new IllegalArgumentException("Ambiguous installed package");}
        for(Installed item:imported){
            if(item==null||!item.imported)throw new IllegalArgumentException("Invalid imported package");
            Installed prior=merged.get(item.packageName);
            if(prior==null)merged.put(item.packageName,item);
            else if(prior.systemInstalled)merged.put(item.packageName,new Installed(prior.packageName,prior.displayName,true,true));
        }
        return new ArrayList<>(merged.values());
    }
    private static boolean matches(String label,String pkg,ExtensionStoreIndex.Entry entry,String query){
        if(query.isEmpty()||label.toLowerCase(Locale.ROOT).contains(query)||pkg.toLowerCase(Locale.ROOT).contains(query))return true;
        if(entry!=null)for(ExtensionStoreIndex.Source source:entry.sources)
            if(source.name.toLowerCase(Locale.ROOT).contains(query)||source.language.toLowerCase(Locale.ROOT).contains(query))return true;
        return false;
    }
}
