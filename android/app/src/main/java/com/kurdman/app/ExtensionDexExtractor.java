package com.kurdman.app;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.TreeMap;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Bounded DEX extraction groundwork. It does not authenticate an APK, execute
 * or load DEX, grant permissions, or provide a sandbox. Only a separately
 * signature-verified immutable APK should ever reach a future sandbox loader.
 */
final class ExtensionDexExtractor {
    static final int MAX_APK=32*1024*1024, MAX_DEX_TOTAL=32*1024*1024, MAX_FILES=8, MAX_ENTRIES=5000;
    private ExtensionDexExtractor() { }
    static List<byte[]> extract(File apk)throws IOException {
        if(apk==null||!apk.isFile()||apk.length()<1||apk.length()>MAX_APK)throw new IOException("Invalid APK size");
        TreeMap<Integer,ZipEntry> dex=new TreeMap<>();int count=0;
        try(ZipFile zip=new ZipFile(apk)){
            Enumeration<? extends ZipEntry> entries=zip.entries();
            while(entries.hasMoreElements()){
                ZipEntry entry=entries.nextElement();if(++count>MAX_ENTRIES)throw new IOException("Too many APK entries");
                String name=entry.getName();if(name==null||name.length()>4096)throw new IOException("Invalid APK entry");
                int index=index(name);
                if(index>0){
                    if(entry.isDirectory()||dex.put(index,entry)!=null)throw new IOException("Duplicate DEX entry");
                    if(entry.getSize()<0||entry.getSize()>MAX_DEX_TOTAL)throw new IOException("Invalid DEX size");
                }
            }
            if(dex.isEmpty()||dex.size()>MAX_FILES||dex.firstKey()!=1||dex.lastKey()!=dex.size())throw new IOException("Missing or non-contiguous DEX");
            List<byte[]> result=new ArrayList<>();int total=0;
            for(ZipEntry entry:dex.values()){
                if(entry.getSize()>MAX_DEX_TOTAL-total)throw new IOException("DEX exceeds limit");
                ByteArrayOutputStream out=new ByteArrayOutputStream();CRC32 crc=new CRC32();
                try(InputStream in=zip.getInputStream(entry)){
                    byte[] buffer=new byte[8192];int n;
                    while((n=in.read(buffer))!=-1){
                        if(n>MAX_DEX_TOTAL-total-out.size())throw new IOException("DEX exceeds limit");
                        out.write(buffer,0,n);crc.update(buffer,0,n);
                    }
                }
                byte[] data=out.toByteArray();
                if(data.length!=entry.getSize()||entry.getCrc()!=crc.getValue()||!validDexHeader(data))throw new IOException("Invalid DEX content");
                total+=data.length;result.add(data);
            }
            return Collections.unmodifiableList(result);
        }
    }
    private static int index(String name)throws IOException {
        if("classes.dex".equals(name))return 1;
        if(name.matches("classes[0-9]+\\.dex")){
            String n=name.substring(7,name.length()-4);
            if(n.length()>2||n.startsWith("0"))throw new IOException("Unsupported DEX name");
            int value=Integer.parseInt(n);if(value<2||value>MAX_FILES)throw new IOException("Unsupported DEX count");return value;
        }
        return 0;
    }
    private static boolean validDexHeader(byte[] data){
        return data.length>=112&&data[0]=='d'&&data[1]=='e'&&data[2]=='x'&&data[3]=='\n'
                &&data[4]>='0'&&data[4]<='9'&&data[5]>='0'&&data[5]<='9'&&data[6]>='0'&&data[6]<='9'&&data[7]==0;
    }
}
