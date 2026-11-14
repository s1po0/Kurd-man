package com.kurdman.app;

import static org.junit.Assert.*;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34)
public class MultiCbzImportTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private File archive(String name, String... paths) throws IOException {
        File result = temp.newFile(name);
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(result))) {
            for (String path : paths) {
                out.putNextEntry(new ZipEntry(path));
                out.write(path.getBytes("UTF-8"));
                out.closeEntry();
            }
        }
        return result;
    }
    private static String text(ZipFile zip, String name) throws IOException {
        try (InputStream in = zip.getInputStream(zip.getEntry(name)); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] b = new byte[1024]; int n; while ((n=in.read(b))!=-1) out.write(b,0,n);
            return out.toString("UTF-8");
        }
    }
    @Test public void preservesArchiveAndNaturalPageOrderWithSafeUniqueNames() throws Exception {
        File a=archive("a.cbz", "../p10.png", "../p2.png", "__MACOSX/p3.png");
        File b=archive("b.zip", "folder/p2.png", "folder/p1.jpg");
        File dest=new File(temp.getRoot(),"out.cbz");
        assertEquals(4,MultiCbzImport.merge(Arrays.asList(a,b),dest,()->{}));
        try(ZipFile z=new ZipFile(dest)) {
            assertEquals(4,z.size());
            assertEquals("../p2.png",text(z,"0001.png"));
            assertEquals("../p10.png",text(z,"0002.png"));
            assertEquals("folder/p1.jpg",text(z,"0003.jpg"));
            assertEquals("folder/p2.png",text(z,"0004.png"));
        }
        assertTrue(a.isFile());assertTrue(b.isFile());
    }
    @Test public void malformedAndEmptyArchivesFailAtomically() throws Exception {
        File a=archive("good.cbz","1.png"), b=archive("empty.cbz","notes.txt");
        File dest=new File(temp.getRoot(),"out.cbz");
        try { MultiCbzImport.merge(Arrays.asList(a,b),dest,()->{}); fail(); } catch(IOException expected) { }
        assertFalse(dest.exists());assertTrue(a.isFile());assertTrue(b.isFile());
    }
    @Test public void neverOverwritesOrDeletesAnExistingDestination() throws Exception {
        File a=archive("a.cbz","1.png"),b=archive("b.cbz","2.png"),dest=temp.newFile("existing.cbz");
        Files.write(dest.toPath(),new byte[]{42});
        try {MultiCbzImport.merge(Arrays.asList(a,b),dest,()->{});fail();}catch(IOException expected){}
        assertArrayEquals(new byte[]{42},Files.readAllBytes(dest.toPath()));
        try {MultiCbzImport.merge(Arrays.asList(a,b),a,()->{});fail();}catch(IOException expected){}
        try(ZipFile zip=new ZipFile(a)){assertNotNull(zip.getEntry("1.png"));}
    }
    @Test public void refusesMoreThan300PagesWithoutLeavingPartialOutput() throws Exception {
        String[] pages=new String[300];for(int i=0;i<pages.length;i++)pages[i]=i+".png";
        File a=archive("a.cbz",pages),b=archive("b.cbz","last.png"),dest=new File(temp.getRoot(),"out.cbz");
        try {MultiCbzImport.merge(Arrays.asList(a,b),dest,()->{});fail();}catch(IOException expected){}
        assertFalse(dest.exists());
    }
    @Test public void cancellationDeletesOnlyThePartialResult() throws Exception {
        File a=archive("a.cbz","1.png"),b=archive("b.cbz","2.png"),dest=new File(temp.getRoot(),"out.cbz");
        final int[] count={0};
        try {MultiCbzImport.merge(Arrays.asList(a,b),dest,()->{if(++count[0]==4)throw new IOException("stopped");});fail();}catch(IOException expected){}
        assertFalse(dest.exists());assertTrue(a.isFile());assertTrue(b.isFile());
    }
}
