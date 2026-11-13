package com.kurdman.app;

import static org.junit.Assert.*;
import org.junit.Test;
import java.io.File;
import java.io.RandomAccessFile;
import java.nio.file.Files;

public class WebImportBudgetTest {
    @Test public void stopsBeforeCacheExceedsOneGibWithoutDeletingAnything() throws Exception {
        File root=Files.createTempDirectory("web-budget").toFile();
        File data=new File(root,"existing");
        try(RandomAccessFile out=new RandomAccessFile(data,"rw")){out.setLength(WebImportBudget.MAX_CACHE-1234);}
        assertEquals(1234,WebImportBudget.allowance(root));assertTrue(data.exists());
        try(RandomAccessFile out=new RandomAccessFile(data,"rw")){out.setLength(WebImportBudget.MAX_CACHE+1);}
        assertEquals(0,WebImportBudget.allowance(root));assertTrue(data.exists());
        assertTrue(data.delete());assertEquals(WebImportBudget.MAX_IMPORT,WebImportBudget.allowance(root));assertTrue(root.delete());
    }
    @Test public void refusesSymbolicLinksRatherThanEscapingCacheRoot() throws Exception {
        File root=Files.createTempDirectory("web-budget-links").toFile();File link=new File(root,"link");
        Files.createSymbolicLink(link.toPath(),root.toPath());
        try{WebImportBudget.allowance(root);fail();}catch(java.io.IOException expected){}finally{Files.delete(link.toPath());Files.delete(root.toPath());}
    }
}
