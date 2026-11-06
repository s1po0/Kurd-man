package com.kurdman.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.DocumentsContract;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContract;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** Permissionless, explicit gallery and all-files routes. Only selected URIs are read. */
final class ComicInputPicker {
    private ComicInputPicker() { }

    static void choose(Activity activity,Runnable gallery,Runnable files) {
        new AlertDialog.Builder(activity).setTitle(R.string.input_choose_source)
                .setItems(new String[]{activity.getString(R.string.input_gallery),activity.getString(R.string.input_files)},
                        (dialog,index)->{if(index==0)gallery.run();else files.run();})
                .setNegativeButton(R.string.rel_cancel,null).show();
    }
    static PickVisualMediaRequest imagesOnly() {
        return new PickVisualMediaRequest.Builder()
                .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE).build();
    }
    static Intent galleryFallback() {
        return new Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("image/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    }
    static Intent files() {
        // Do not use EXTRA_MIME_TYPES: many OEM managers hide .cbz/.zip files
        // when MIME metadata is missing or differs from the old whitelist.
        return new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true)
                .putExtra(DocumentsContract.EXTRA_INITIAL_URI,
                        DocumentsContract.buildDocumentUri("com.android.externalstorage.documents","primary:"))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
    }
    static Intent filesFallback() {
        return new Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    }
    static List<Uri> selected(int result,Intent data) {
        LinkedHashSet<Uri> unique=new LinkedHashSet<>();
        if(result!=Activity.RESULT_OK||data==null)return new ArrayList<>();
        ClipData clips=data.getClipData();
        if(clips!=null)for(int i=0;i<clips.getItemCount();i++)add(unique,clips.getItemAt(i).getUri());
        add(unique,data.getData());
        return new ArrayList<>(unique);
    }
    private static void add(LinkedHashSet<Uri> result,Uri uri) {
        if(uri!=null&&("content".equals(uri.getScheme())||"file".equals(uri.getScheme())))result.add(uri);
    }
    static final class Documents extends ActivityResultContract<Void,List<Uri>> {
        @NonNull @Override public Intent createIntent(@NonNull Context context,@Nullable Void input) { return files(); }
        @NonNull @Override public List<Uri> parseResult(int resultCode,@Nullable Intent intent) { return selected(resultCode,intent); }
    }
}
