package com.kurdman.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import androidx.activity.result.contract.ActivityResultContract;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** One app-capable Android chooser; reads only URIs selected by the reader. */
final class ComicInputPicker {
    private ComicInputPicker() { }

    static Intent content() {
        // GET_CONTENT includes installed gallery/archive/file-manager handlers.
        // OPEN_DOCUMENT lists document providers instead; PICK_IMAGES is photos only.
        // No MIME whitelist, local-only restriction, package or initial storage root.
        return new Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    }
    static Intent chooser(Context context) {
        // Always offer the compatible source apps, even if a default was set.
        return Intent.createChooser(content(),context.getString(R.string.input_choose_source));
    }
    static List<Uri> selected(int result,Intent data) {
        LinkedHashSet<Uri> unique=new LinkedHashSet<>();
        if(result!=Activity.RESULT_OK||data==null)return new ArrayList<>();
        ClipData clips=data.getClipData();
        if(clips!=null)for(int i=0;i<clips.getItemCount();i++)add(unique,clips.getItemAt(i).getUri());
        add(unique,data.getData());
        return new ArrayList<>(unique);
    }
    /** Recognition only; selecting an APK never installs or trusts it. */
    static boolean isApk(String displayName,String mimeType) {
        String name=displayName==null?"":displayName.trim().toLowerCase(java.util.Locale.ROOT);
        String type=mimeType==null?"":mimeType.trim().toLowerCase(java.util.Locale.ROOT);
        return name.endsWith(".apk")||type.equals("application/vnd.android.package-archive");
    }
    private static void add(LinkedHashSet<Uri> result,Uri uri) {
        if(uri!=null&&("content".equals(uri.getScheme())||"file".equals(uri.getScheme())))result.add(uri);
    }
    static final class Sources extends ActivityResultContract<Void,List<Uri>> {
        @NonNull @Override public Intent createIntent(@NonNull Context context,@Nullable Void input) { return chooser(context); }
        @NonNull @Override public List<Uri> parseResult(int resultCode,@Nullable Intent intent) { return selected(resultCode,intent); }
    }
}
