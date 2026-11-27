package com.kurdman.app;

import static org.junit.Assert.*;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Synthetic ZIP bounds only; these fixtures are not runnable or verified DEX. */
public class ExtensionDexExtractorTest {
    @Rule public TemporaryFolder folder=new TemporaryFolder();
    private byte[] dex(int marker){byte[] b=new byte[112];byte[] h={'d','e','x','\n','0','3','5',0};System.arraycopy(h,0,b,0,h.length);b[20]=(byte)marker;return b;}
    private File archive(String[] names,byte[][] contents)throws Exception {
        File file=folder.newFile();try(ZipOutputStream out=new ZipOutputStream(new FileOutputStream(file))){
            for(int i=0;i<names.length;i++){out.putNextEntry(new ZipEntry(names[i]));out.write(contents[i]);out.closeEntry();}
        }return file;
    }
    private void refuses(String[] names,byte[][] data)throws Exception {
        try{ExtensionDexExtractor.extract(archive(names,data));fail("Invalid archive accepted");}catch(IOException expected){}
    }
    @Test public void extractsDexInNumericOrder()throws Exception {
        List<byte[]> dex=ExtensionDexExtractor.extract(archive(new String[]{"classes2.dex","assets/other","classes.dex"},new byte[][]{dex(2),new byte[]{1},dex(1)}));
        assertEquals(2,dex.size());assertEquals(1,dex.get(0)[20]);assertEquals(2,dex.get(1)[20]);
        try{dex.clear();fail();}catch(UnsupportedOperationException expected){}
    }
    @Test public void refusesMissingInvalidAndNonContiguousDex()throws Exception {
        refuses(new String[]{"assets/x"},new byte[][]{dex(1)});
        refuses(new String[]{"classes2.dex"},new byte[][]{dex(2)});
        refuses(new String[]{"classes.dex","classes3.dex"},new byte[][]{dex(1),dex(3)});
        refuses(new String[]{"classes.dex"},new byte[][]{new byte[112]});
        refuses(new String[]{"classes.dex","classes01.dex"},new byte[][]{dex(1),dex(1)});
        refuses(new String[]{"classes.dex","classes9.dex"},new byte[][]{dex(1),dex(9)});
    }
    @Test public void refusesZipBombAndTooManyEntries()throws Exception {
        refuses(new String[]{"classes.dex"},new byte[][]{new byte[ExtensionDexExtractor.MAX_DEX_TOTAL+1]});
        String[] names=new String[ExtensionDexExtractor.MAX_ENTRIES+1];byte[][] data=new byte[names.length][];
        for(int i=0;i<names.length;i++){names[i]="assets/"+i;data[i]=new byte[0];}
        refuses(names,data);
    }
}
