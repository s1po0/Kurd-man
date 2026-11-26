package com.kurdman.app;

import static org.junit.Assert.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;
import org.junit.Test;

/** Generated synthetic metadata only; parsing never confers trust. */
public class ExtensionStoreIndexTest {
    static byte[] var(long n){ByteArrayOutputStream b=new ByteArrayOutputStream();while((n&~127L)!=0){b.write((int)n|128);n>>>=7;}b.write((int)n);return b.toByteArray();}
    static byte[] join(byte[]...parts){ByteArrayOutputStream b=new ByteArrayOutputStream();for(byte[] p:parts)b.write(p,0,p.length);return b.toByteArray();}
    static byte[] field(int id,byte[] value){return join(var(id*8+2),var(value.length),value);}
    static byte[] text(int id,String value){return field(id,value.getBytes(StandardCharsets.UTF_8));}
    static byte[] number(int id,long n){return join(var(id*8),var(n));}
    static byte[] source(long id,String name){return join(number(1,id),text(2,name),text(3,"en"),text(4,"https://example.org"));}
    static byte[] entry(String pkg){return join(text(1,"Example"),text(2,pkg),field(3,text(1,"https://example.org/source.apk")),text(4,"1.6"),number(5,106001),text(6,"1.6.1"),number(7,1),field(8,source(42,"First site")),field(8,source(-3,"Second site")));}
    static byte[] index(byte[] entries){return join(text(1,"Example store"),text(3,"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"),field(101,entries));}
    static void rejects(byte[] data)throws Exception{try{ExtensionStoreIndex.parse(data);fail("expected rejection");}catch(IOException expected){}}
    @Test public void parsesSyntheticIndexAndGzipWithoutEstablishingTrust()throws Exception{
        byte[] source=index(field(1,entry("org.example.extension")));
        ByteArrayOutputStream compressed=new ByteArrayOutputStream();try(GZIPOutputStream z=new GZIPOutputStream(compressed)){z.write(source);}
        for(byte[] input:new byte[][]{source,compressed.toByteArray()}){
            ExtensionStoreIndex store=ExtensionStoreIndex.parse(input);assertEquals("Example store",store.name);assertEquals(1,store.entries.size());
            assertEquals("org.example.extension",store.entries.get(0).packageName);assertEquals(106001,store.entries.get(0).versionCode);
            assertEquals(1,store.entries.get(0).contentWarning);assertEquals(2,store.entries.get(0).sources.size());
            assertEquals("First site",store.entries.get(0).sources.get(0).name);assertEquals(-3,store.entries.get(0).sources.get(1).id);
        }
    }
    @Test public void rejectsMalformedAndAmbiguousStoreData()throws Exception{
        rejects(new byte[0]); rejects(new byte[]{10,10,65});rejects(new byte[]{(byte)0x80});
        rejects(index(join(field(1,entry("org.example.extension")),field(1,entry("org.example.extension")))));
        rejects(index(field(1,entry("../invalid"))));
        rejects(join(index(new byte[0]),text(102,"https://elsewhere.example/")));
        rejects(join(index(new byte[0]),text(3,"another signer")));
        rejects(index(field(1,new byte[]{10,1,(byte)0xff})));
        rejects(new byte[ExtensionStoreIndex.MAX_COMPRESSED+1]);
    }
    @Test public void gzipExpansionHasHardBound()throws Exception {
        ByteArrayOutputStream b=new ByteArrayOutputStream();
        try(GZIPOutputStream gz=new GZIPOutputStream(b)){byte[] data=new byte[8192];for(int n=0;n<1025;n++)gz.write(data);}
        rejects(b.toByteArray());
    }
    @Test public void rejectsTamperedGzip()throws Exception{
        ByteArrayOutputStream b=new ByteArrayOutputStream();try(GZIPOutputStream gz=new GZIPOutputStream(b)){gz.write(index(new byte[0]));}
        byte[] bytes=b.toByteArray();bytes[bytes.length-1]^=1;rejects(bytes);
    }
}
