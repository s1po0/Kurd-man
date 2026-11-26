package com.kurdman.app;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * Bounded, offline metadata parsing groundwork for Mihon index.pb stores.
 * NOT a store implementation, installer, trust decision, or extension runtime.
 * Never load executable code merely because its metadata parses successfully.
 */
final class ExtensionStoreIndex {
    static final int MAX_COMPRESSED=2*1024*1024, MAX_DECODED=8*1024*1024, MAX_EXTENSIONS=10000;
    final String name, signingKey;
    final List<Entry> entries;
    private ExtensionStoreIndex(String name,String signingKey,List<Entry> entries) {
        this.name=name;this.signingKey=signingKey;this.entries=Collections.unmodifiableList(entries);
    }
    static final class Entry {
        final String name, packageName, apkUrl, extensionLib, versionName;
        final long versionCode;
        final int contentWarning;
        Entry(String name,String packageName,String apkUrl,String extensionLib,long versionCode,String versionName,int warning){
            this.name=name;this.packageName=packageName;this.apkUrl=apkUrl;this.extensionLib=extensionLib;
            this.versionCode=versionCode;this.versionName=versionName;this.contentWarning=warning;
        }
    }
    private static final class Reader {
        final byte[] data;int pos,end;
        Reader(byte[] data,int pos,int end){this.data=data;this.pos=pos;this.end=end;}
        boolean more(){return pos<end;}
        long varint() throws IOException {
            long v=0;
            for(int i=0;i<10;i++){
                if(pos==end)throw new IOException("Truncated protobuf varint");
                int b=data[pos++]&255;
                if(i==9 && (b&254)!=0)throw new IOException("Invalid protobuf varint");
                v|=(long)(b&127)<<(i*7);if((b&128)==0)return v;
            }
            throw new IOException("Invalid protobuf varint");
        }
        int key() throws IOException {
            long value=varint();
            if(value<1 || value>0xffffffffL || (value>>>3)==0 || (value>>>3)>0x1fffffffL)
                throw new IOException("Invalid protobuf field");
            return (int)value;
        }
        Reader message() throws IOException {
            long n=varint();if(n<0||n>end-pos)throw new IOException("Truncated protobuf field");
            Reader result=new Reader(data,pos,pos+(int)n);pos+=(int)n;return result;
        }
        String string() throws IOException {
            Reader field=message();if(field.end-field.pos>8192)throw new IOException("Metadata string too large");
            try{return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data,field.pos,field.end-field.pos)).toString();}
            catch(CharacterCodingException e){throw new IOException("Invalid UTF-8",e);}
        }
        void skip(int wire) throws IOException {
            switch(wire){case 0:varint();break;case 1:advance(8);break;case 2:message();break;case 5:advance(4);break;
                default:throw new IOException("Unsupported protobuf wire type");}
        }
        void advance(int n)throws IOException{if(n>end-pos)throw new IOException("Truncated protobuf field");pos+=n;}
    }
    static ExtensionStoreIndex parse(byte[] input)throws IOException {
        if(input==null||input.length==0||input.length>MAX_COMPRESSED)throw new IOException("Invalid index size");
        byte[] bytes=input;
        if(input.length>=2 && (input[0]&255)==0x1f && (input[1]&255)==0x8b){
            try(GZIPInputStream in=new GZIPInputStream(new ByteArrayInputStream(input));ByteArrayOutputStream out=new ByteArrayOutputStream()){
                byte[] buf=new byte[8192];int n;
                while((n=in.read(buf))!=-1){if(n>MAX_DECODED-out.size())throw new IOException("Expanded index too large");out.write(buf,0,n);}
                bytes=out.toByteArray();
            }
        }
        if(bytes.length>MAX_DECODED)throw new IOException("Index too large");
        Reader r=new Reader(bytes,0,bytes.length);String name=null,key=null;List<Entry> entries=null;
        while(r.more()){
            int tag=r.key(),field=tag>>>3,wire=tag&7;
            if(field==1&&wire==2){if(name!=null)throw new IOException("Duplicate name");name=r.string();}
            else if(field==3&&wire==2){if(key!=null)throw new IOException("Duplicate signer");key=r.string();}
            else if(field==101&&wire==2){if(entries!=null)throw new IOException("Duplicate list");entries=parseList(r.message());}
            else if(field==102)throw new IOException("External extension list unsupported");
            else r.skip(wire);
        }
        if(name==null||name.trim().isEmpty()||key==null||!key.matches("(?i)[0-9a-f]{64}")||entries==null)
            throw new IOException("Invalid store metadata");
        return new ExtensionStoreIndex(name,key.toLowerCase(java.util.Locale.ROOT),entries);
    }
    private static List<Entry> parseList(Reader r)throws IOException {
        List<Entry> entries=new ArrayList<>();Set<String> packages=new HashSet<>();
        while(r.more()){
            int tag=r.key();if((tag>>>3)==1 && (tag&7)==2){
                if(entries.size()>=MAX_EXTENSIONS)throw new IOException("Too many extensions");
                Entry entry=parseEntry(r.message());
                if(!packages.add(entry.packageName))throw new IOException("Duplicate extension package");
                entries.add(entry);
            }else r.skip(tag&7);
        }
        return entries;
    }
    private static Entry parseEntry(Reader r)throws IOException {
        String name=null,pkg=null,apk=null,lib=null,version=null;long code=-1;int warning=0;
        while(r.more()){
            int tag=r.key(),field=tag>>>3,wire=tag&7;
            if(field==1&&wire==2){if(name!=null)throw new IOException("Duplicate field");name=r.string();}
            else if(field==2&&wire==2){if(pkg!=null)throw new IOException("Duplicate field");pkg=r.string();}
            else if(field==3&&wire==2){if(apk!=null)throw new IOException("Duplicate field");apk=parseResources(r.message());}
            else if(field==4&&wire==2){if(lib!=null)throw new IOException("Duplicate field");lib=r.string();}
            else if(field==5&&wire==0){if(code!=-1)throw new IOException("Duplicate field");code=r.varint();}
            else if(field==6&&wire==2){if(version!=null)throw new IOException("Duplicate field");version=r.string();}
            else if(field==7&&wire==0){long value=r.varint();if(value<0||value>3)throw new IOException("Unknown content warning");warning=(int)value;}
            else r.skip(wire);
        }
        if(name==null||name.trim().isEmpty()||pkg==null||!pkg.matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+")
                ||apk==null||apk.isEmpty()||lib==null||lib.isEmpty()||version==null||version.isEmpty()||code<=0)
            throw new IOException("Invalid extension metadata");
        // The URL is UNTRUSTED metadata, not authorization to download or install.
        return new Entry(name,pkg,apk,lib,code,version,warning);
    }
    private static String parseResources(Reader r)throws IOException {
        String apk=null;
        while(r.more()){
            int tag=r.key();if((tag>>>3)==1&&(tag&7)==2){if(apk!=null)throw new IOException("Duplicate APK URL");apk=r.string();}
            else r.skip(tag&7);
        }
        return apk;
    }
}
