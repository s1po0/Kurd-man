package com.kurdman.app;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Shared bounded image decoding used by the translator and text studio. */
final class BitmapIo {
    private static final long MAX_PIXELS = 28_000_000L;

    private BitmapIo() {
    }

    static Bitmap decodeFile(File file) {
        if (file == null || !file.exists()) {
            return null;
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight);
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return toSoftwareBitmap(BitmapFactory.decodeFile(
                file.getAbsolutePath(), options));
    }

    static Bitmap decodeUri(Context context, Uri uri) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        InputStream first = resolver.openInputStream(uri);
        if (first == null) {
            throw new IOException("Could not open selected image");
        }
        try {
            BitmapFactory.decodeStream(first, null, bounds);
        } finally {
            first.close();
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw new IOException("Unsupported image");
        }

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight);
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        InputStream second = resolver.openInputStream(uri);
        if (second == null) {
            throw new IOException("Could not reopen selected image");
        }
        try {
            return toSoftwareBitmap(BitmapFactory.decodeStream(second, null, options));
        } finally {
            second.close();
        }
    }

    static void writeJpeg(File file, Bitmap bitmap) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Could not create output directory");
        }
        OutputStream out = new BufferedOutputStream(new FileOutputStream(file));
        try {
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)) {
                throw new IOException("Could not encode JPEG image");
            }
        } finally {
            out.close();
        }
    }

    private static int sampleSize(int width, int height) {
        int sample = 1;
        long pixels = (long) width * (long) height;
        while (pixels / ((long) sample * (long) sample) > MAX_PIXELS && sample < 16) {
            sample *= 2;
        }
        return sample;
    }

    private static Bitmap toSoftwareBitmap(Bitmap decoded) {
        if (decoded != null
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && decoded.getConfig() == Bitmap.Config.HARDWARE) {
            Bitmap software = decoded.copy(Bitmap.Config.ARGB_8888, false);
            if (software != null) {
                decoded.recycle();
                return software;
            }
        }
        return decoded;
    }
}
