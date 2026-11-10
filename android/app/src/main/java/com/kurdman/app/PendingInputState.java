package com.kurdman.app;

import android.content.Context;
import android.os.Bundle;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Metadata-only Activity state. No bitmaps, URI re-import, file copies or project mutation. */
final class PendingInputState {
    static final String KEY="koorix.ui.pending-input";
    static final long MAX_BYTES=300L*1024*1024;
    static final class Selection {
        File image,cbz;
        final List<File> batch=new ArrayList<>();
        final List<String> names=new ArrayList<>();
        String title="",label="",base="";
        int pages;
    }
    static void save(Context c,Bundle state,File image,File cbz,List<File> batch,List<String> names,
                     String title,int pages,String label,String base) {
        Bundle values=new Bundle();values.putInt("v",1);
        values.putString("image",relative(c,image));values.putString("cbz",relative(c,cbz));
        ArrayList<String> paths=new ArrayList<>(),display=new ArrayList<>();
        for(int i=0;i<batch.size()&&i<300;i++) {
            String path=relative(c,batch.get(i));if(path==null)continue;
            paths.add(path);display.add(i<names.size()?shortText(names.get(i)):batch.get(i).getName());
        }
        values.putStringArrayList("batch",paths);values.putStringArrayList("names",display);
        values.putString("title",shortText(title));values.putInt("pages",Math.max(0,Math.min(300,pages)));
        values.putString("label",shortText(label));values.putString("base",shortText(base));
        state.putBundle(KEY,values);
    }
    static Selection restore(Context c,Bundle state) {
        Selection s=new Selection();if(state==null)return s;
        Bundle b=state.getBundle(KEY);if(b==null||b.getInt("v",0)!=1)return s;
        s.image=resolve(c,b.getString("image"));s.cbz=resolve(c,b.getString("cbz"));
        ArrayList<String> paths=b.getStringArrayList("batch"),names=b.getStringArrayList("names");
        if(paths!=null&&paths.size()<=300) {
            long total=0;
            for(int i=0;i<paths.size();i++) {
                File file=resolve(c,paths.get(i));
                if(file==null||total+file.length()>MAX_BYTES){s.batch.clear();s.names.clear();break;}
                total+=file.length();s.batch.add(file);s.names.add(names!=null&&i<names.size()?shortText(names.get(i)):file.getName());
            }
        }
        s.title=shortText(b.getString("title",""));s.label=shortText(b.getString("label",""));s.base=shortText(b.getString("base",""));s.pages=Math.max(0,Math.min(300,b.getInt("pages",0)));
        if(s.cbz!=null){s.image=null;s.batch.clear();s.names.clear();}
        else if(!s.batch.isEmpty())s.image=null;
        if(s.image==null&&s.cbz==null&&s.batch.isEmpty())return new Selection();
        if(s.batch.size()==1&&s.image==null){s.image=s.batch.get(0);s.batch.clear();s.names.clear();}
        return s;
    }
    private static String shortText(String value){return value==null?"":value.substring(0,Math.min(500,value.length()));}
    private static String relative(Context c,File f) {
        if(f==null)return null;
        try {
            File canonical=f.getCanonicalFile();
            for(String type:new String[]{"cache","files"}) {
                File root=(type.equals("cache")?c.getCacheDir():c.getFilesDir()).getCanonicalFile();
                String prefix=root.getPath()+File.separator;
                if(canonical.getPath().startsWith(prefix))return type+":"+canonical.getPath().substring(prefix.length());
            }
        }catch(IOException ignored){/* no reader paths in diagnostics */}
        return null;
    }
    private static File resolve(Context c,String path) {
        if(path==null||path.length()>2000)return null;
        String type=path.startsWith("cache:")?"cache":path.startsWith("files:")?"files":null;
        if(type==null)return null;
        try {
            File root=(type.equals("cache")?c.getCacheDir():c.getFilesDir()).getCanonicalFile();
            File f=new File(root,path.substring(type.length()+1)).getCanonicalFile();
            if(!f.getPath().startsWith(root.getPath()+File.separator)||!f.isFile()||f.length()>MAX_BYTES)return null;
            return f;
        }catch(IOException ignored){return null;}
    }
}
