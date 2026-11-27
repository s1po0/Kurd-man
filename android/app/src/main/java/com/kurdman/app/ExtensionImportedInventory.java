package com.kurdman.app;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Explicitly reader-selected APK copies only. Metadata is untrusted: adding an
 * APK never installs, trusts or executes it. No other app's files are scanned.
 * Persistent copies are not removed automatically, even if damaged.
 */
final class ExtensionImportedInventory {
    static final long MAX_STORE=300L*1024*1024;
    static final int MAX_FILES=100;
    interface Inspector { String packageName(File apk)throws IOException; }
    private ExtensionImportedInventory() { }
    static Inspector inspector(PackageManager pm){return file->{
        PackageInfo info=pm.getPackageArchiveInfo(file.getAbsolutePath(),PackageManager.GET_CONFIGURATIONS);
        if(info==null||!ExtensionInstalledInventory.isExtension(info.reqFeatures)||!validPackage(info.packageName))throw new IOException("Not an extension APK");
        return info.packageName;
    };}
    private static boolean validPackage(String name){return name!=null&&name.length()<=255&&name.matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+");}
    static File directory(File filesDir)throws IOException {
        if(filesDir==null)throw new IOException("Missing storage");File dir=new File(filesDir,"imported-extension-apks");
        if(Files.isSymbolicLink(dir.toPath())||(!dir.isDirectory()&&!dir.mkdirs()))throw new IOException("Unsafe extension storage");return dir;
    }
    static synchronized String store(InputStream input,File filesDir,Inspector inspector)throws IOException {
        if(input==null||inspector==null)throw new IOException("Missing archive");
        File dir=directory(filesDir);File[] existing=dir.listFiles();if(existing==null)throw new IOException("Cannot inspect extension storage");
        long used=0;int count=0;
        for(File f:existing){
            if(Files.isSymbolicLink(f.toPath())||!f.isFile())throw new IOException("Unsafe extension storage entry");
            long n=f.length();if(n<0||n>MAX_STORE-used)throw new IOException("Extension storage full");used+=n;
            if(f.getName().matches("ext-[0-9a-f]{64}\\.apk"))count++;
        }
        if(count>=MAX_FILES||used>MAX_STORE)throw new IOException("Extension storage full");
        MessageDigest digest;try{digest=MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
        File temp=File.createTempFile("import-",".part",dir);boolean done=false;
        try{
            long total=0;byte[] first=new byte[4];int firstCount=0;
            try(FileOutputStream out=new FileOutputStream(temp)){
                byte[] buffer=new byte[8192];int n;
                while((n=input.read(buffer))!=-1){
                    if(n==0)throw new IOException("Invalid archive stream");if(n>ExtensionApkStager.MAX_APK_BYTES-total||n>MAX_STORE-used-total)throw new IOException("Extension archive too large");
                    int copy=Math.min(n,4-firstCount);if(copy>0){System.arraycopy(buffer,0,first,firstCount,copy);firstCount+=copy;}
                    digest.update(buffer,0,n);out.write(buffer,0,n);total+=n;
                }
                if(firstCount<4||first[0]!='P'||first[1]!='K'||first[2]!=3||first[3]!=4)throw new IOException("Not an APK container");
                out.getFD().sync();
            }
            String pkg=inspector.packageName(temp);if(!validPackage(pkg))throw new IOException("Invalid extension identity");
            StringBuilder hex=new StringBuilder();for(byte b:digest.digest())hex.append(String.format(Locale.ROOT,"%02x",b&255));
            File target=new File(dir,"ext-"+hex+".apk");
            if(Files.isSymbolicLink(target.toPath()))throw new IOException("Unsafe destination");
            if(target.exists()){
                if(!target.isFile()||!sameDigest(target,hex.toString()))throw new IOException("Existing archive mismatch");
                done=true;return pkg;
            }
            if(!temp.setReadOnly()||!temp.renameTo(target))throw new IOException("Cannot store extension archive");
            done=true;return pkg;
        }finally{if(!done||temp.exists())temp.delete();}
    }
    static synchronized List<ExtensionLibrarySections.Installed> scan(Context context)throws IOException {
        return scan(directory(context.getFilesDir()),inspector(context.getPackageManager()));
    }
    static List<ExtensionLibrarySections.Installed> scan(File dir,Inspector inspector)throws IOException {
        if(dir==null||Files.isSymbolicLink(dir.toPath())||!dir.isDirectory()||inspector==null)throw new IOException("Invalid extension storage");
        File[] files=dir.listFiles();if(files==null)throw new IOException("Cannot inspect extension storage");
        List<ExtensionLibrarySections.Installed> result=new ArrayList<>();java.util.Set<String> seen=new java.util.HashSet<>();int count=0;long total=0;
        for(File f:files){
            if(!f.getName().matches("ext-[0-9a-f]{64}\\.apk"))continue;
            if(++count>MAX_FILES||Files.isSymbolicLink(f.toPath())||!f.isFile()||f.length()<4||f.length()>ExtensionApkStager.MAX_APK_BYTES||f.length()>MAX_STORE-total)throw new IOException("Invalid stored extension");
            total+=f.length();
            String expected=f.getName().substring(4,68);if(!sameDigest(f,expected))throw new IOException("Stored extension changed");
            String pkg=inspector.packageName(f);if(!validPackage(pkg))throw new IOException("Invalid extension identity");
            if(seen.add(pkg))result.add(new ExtensionLibrarySections.Installed(pkg,pkg,false,true));
        }
        return result;
    }
    private static boolean sameDigest(File file,String expected)throws IOException {
        MessageDigest digest;try{digest=MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
        try(InputStream in=new FileInputStream(file)){byte[] buffer=new byte[8192];int n;long total=0;
            while((n=in.read(buffer))!=-1){if(n>ExtensionApkStager.MAX_APK_BYTES-total)throw new IOException("Stored archive too large");digest.update(buffer,0,n);total+=n;}}
        StringBuilder hex=new StringBuilder();for(byte b:digest.digest())hex.append(String.format(Locale.ROOT,"%02x",b&255));return hex.toString().equals(expected);
    }
}
