package com.kurdman.app;

import static org.junit.Assert.*;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

/** Grouping behavior only, no Android installation or signer trust assertions. */
public class ExtensionLibrarySectionsTest {
    private static ExtensionStoreIndex.Entry remote(String name,String pkg){
        return new ExtensionStoreIndex.Entry(name,pkg,"https://example.org/a.apk","1.6",1,"1.6.1",1,Collections.emptyList());
    }
    @Test public void installedAppearInOwnSectionBeforeAvailableIncludingWhenRemovedFromStore(){
        ExtensionLibrarySections sections=ExtensionLibrarySections.build(Arrays.asList(
                new ExtensionLibrarySections.Installed("x.first","Zed installed"),
                new ExtensionLibrarySections.Installed("x.orphan","Alpha installed")),Arrays.asList(
                remote("Alternate label","x.first"),remote("Beta available","x.second")),"");
        assertEquals(2,sections.installed.size());assertEquals("x.orphan",sections.installed.get(0).packageName);
        assertNull(sections.installed.get(0).catalogEntry);assertEquals("Zed installed",sections.installed.get(1).displayName);
        assertEquals(1,sections.available.size());assertEquals("x.second",sections.available.get(0).packageName);
        assertTrue(sections.installed.get(1).installed);assertFalse(sections.available.get(0).installed);
    }
    @Test public void searchFiltersBothSectionsAndDoesNotMoveInstalledToAvailable(){
        ExtensionLibrarySections sections=ExtensionLibrarySections.build(
                Collections.singletonList(new ExtensionLibrarySections.Installed("org.foo","Old local label")),
                Arrays.asList(remote("New remote label","org.foo"),remote("Foo two","org.two")),"foo");
        assertEquals(1,sections.installed.size());assertEquals(1,sections.available.size());
        assertEquals("org.foo",sections.installed.get(0).packageName);
    }
    @Test public void findIncludedSiteWithoutMovingInstalledExtension(){
        ExtensionStoreIndex.Entry entry=new ExtensionStoreIndex.Entry("Bundle","org.bundle","https://example.org/a.apk","1.6",1,"1.6.1",1,
                Collections.singletonList(new ExtensionStoreIndex.Source(42,"Named site","en","https://example.org")));
        ExtensionLibrarySections sections=ExtensionLibrarySections.build(Collections.singletonList(new ExtensionLibrarySections.Installed("org.bundle","Local")),
                Collections.singletonList(entry),"named site");
        assertEquals(1,sections.installed.size());assertTrue(sections.available.isEmpty());
    }
    @Test public void duplicatePackagesAreRejected(){
        try{ExtensionLibrarySections.build(Collections.emptyList(),Arrays.asList(remote("A","x.same"),remote("B","x.same")),null);fail();}
        catch(IllegalArgumentException expected){}
        try{ExtensionLibrarySections.build(Arrays.asList(new ExtensionLibrarySections.Installed("x.same","A"),
                new ExtensionLibrarySections.Installed("x.same","B")),Collections.emptyList(),null);fail();}
        catch(IllegalArgumentException expected){}
    }
}
