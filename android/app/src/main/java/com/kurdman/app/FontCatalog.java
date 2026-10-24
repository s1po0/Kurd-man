package com.kurdman.app;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Typeface;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Bundled Sorani-capable fonts plus user-imported TTF/OTF files.
 *
 * <p>Every bundled family was checked for the Sorani code points that many
 * generic Arabic fonts miss (ڕ، ڵ، ڤ، گ، چ، پ، ژ، ێ، ە، ۆ and related
 * letters). All bundled fonts are SIL OFL; their license texts live in
 * {@code assets/licenses}.</p>
 */
public final class FontCatalog {
    public static final String FONT_SYSTEM = "system";
    public static final String FONT_VAZIRMATN = "vazirmatn";
    public static final String FONT_NOTO_SANS = "noto_sans_arabic";
    public static final String FONT_NOTO_NASKH = "noto_naskh_arabic";
    public static final String FONT_NOTO_KUFI = "noto_kufi_arabic";
    public static final String FONT_IBM_PLEX = "ibm_plex_sans_arabic";
    public static final String FONT_AMIRI = "amiri";

    private static final String CUSTOM_PREFIX = "custom:";
    private static final long MAX_CUSTOM_FONT_BYTES = 30L * 1024L * 1024L;
    private static final Map<String, Typeface> CACHE = new HashMap<>();

    private FontCatalog() {
    }

    public static final class FontOption {
        public final String id;
        public final String label;
        public final String regularAsset;
        public final String boldAsset;
        public final boolean variable;
        public final File customFile;

        private FontOption(String id, String label, String regularAsset,
                           String boldAsset, boolean variable, File customFile) {
            this.id = id;
            this.label = label;
            this.regularAsset = regularAsset;
            this.boldAsset = boldAsset;
            this.variable = variable;
            this.customFile = customFile;
        }
    }

    public static List<FontOption> getOptions(Context context) {
        List<FontOption> options = new ArrayList<>();
        options.add(new FontOption(FONT_VAZIRMATN,
                "Vazirmatn — مۆدێرن و ڕوون",
                "fonts/vazirmatn_vf.ttf", null, true, null));
        options.add(new FontOption(FONT_NOTO_SANS,
                "Noto Sans Arabic — پاک و پیشەیی",
                "fonts/noto_sans_arabic_vf.ttf", null, true, null));
        options.add(new FontOption(FONT_NOTO_NASKH,
                "Noto Naskh Arabic — خوێندنەوەی درێژ",
                "fonts/noto_naskh_arabic_vf.ttf", null, true, null));
        options.add(new FontOption(FONT_NOTO_KUFI,
                "Noto Kufi Arabic — جوان و گۆشەدار",
                "fonts/noto_kufi_arabic_vf.ttf", null, true, null));
        options.add(new FontOption(FONT_IBM_PLEX,
                "IBM Plex Sans Arabic — پیشەیی",
                "fonts/ibm_plex_sans_arabic_regular.ttf",
                "fonts/ibm_plex_sans_arabic_bold.ttf", false, null));
        options.add(new FontOption(FONT_AMIRI,
                "Amiri — کلاسیک و نەسخ",
                "fonts/amiri_regular.ttf", "fonts/amiri_bold.ttf", false, null));
        options.add(new FontOption(FONT_SYSTEM,
                "فۆنتی سیستەمی مۆبایلەکە", null, null, false, null));

        File dir = customFontsDir(context);
        File[] files = dir.listFiles();
        if (files != null) {
            List<File> sorted = new ArrayList<>();
            for (File file : files) {
                if (file.isFile() && isFontFile(file.getName())) {
                    sorted.add(file);
                }
            }
            Collections.sort(sorted, new Comparator<File>() {
                @Override
                public int compare(File a, File b) {
                    return a.getName().compareToIgnoreCase(b.getName());
                }
            });
            for (File file : sorted) {
                options.add(new FontOption(CUSTOM_PREFIX + file.getName(),
                        displayName(file.getName()) + " — هاوردەکراو",
                        null, null, true, file));
            }
        }
        return options;
    }

    public static List<String> labels(List<FontOption> options) {
        List<String> labels = new ArrayList<>();
        for (FontOption option : options) {
            labels.add(option.label);
        }
        return labels;
    }

    public static int indexOfId(List<FontOption> options, String fontId) {
        if (options != null && fontId != null) {
            for (int i = 0; i < options.size(); i++) {
                if (fontId.equals(options.get(i).id)) {
                    return i;
                }
            }
        }
        return 0;
    }

    public static boolean isCustom(String fontId) {
        return fontId != null && fontId.startsWith(CUSTOM_PREFIX);
    }

    public static Typeface getTypeface(Context context, TextStyleSettings style) {
        if (style == null) {
            return Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD);
        }
        return getTypeface(context, style.fontId, style.fontWeight, style.italic);
    }

    public static Typeface getTypeface(Context context, String fontId,
                                       int weight, boolean italic) {
        Context app = context.getApplicationContext();
        int safeWeight = Math.max(100, Math.min(900, weight));
        // Cache common weights only so dragging a weight slider cannot build
        // hundreds of slightly different typefaces and retain them all.
        int bucketWeight = ((safeWeight + 25) / 50) * 50;
        bucketWeight = Math.max(100, Math.min(900, bucketWeight));
        String key = String.valueOf(fontId) + "|" + bucketWeight + "|" + italic;
        synchronized (CACHE) {
            Typeface cached = CACHE.get(key);
            if (cached != null) {
                return cached;
            }
        }

        FontOption selected = null;
        for (FontOption option : getOptions(app)) {
            if (option.id.equals(fontId)) {
                selected = option;
                break;
            }
        }
        Typeface result = createTypeface(app, selected, bucketWeight, italic);
        if (result == null) {
            result = Typeface.create(Typeface.SANS_SERIF,
                    italic ? (bucketWeight >= 600 ? Typeface.BOLD_ITALIC : Typeface.ITALIC)
                            : (bucketWeight >= 600 ? Typeface.BOLD : Typeface.NORMAL));
        }
        synchronized (CACHE) {
            CACHE.put(key, result);
        }
        return result;
    }

    private static Typeface createTypeface(Context context, FontOption option,
                                           int weight, boolean italic) {
        if (option == null || FONT_SYSTEM.equals(option.id)) {
            return Typeface.create(Typeface.SANS_SERIF,
                    italic ? (weight >= 600 ? Typeface.BOLD_ITALIC : Typeface.ITALIC)
                            : (weight >= 600 ? Typeface.BOLD : Typeface.NORMAL));
        }
        try {
            Typeface base = null;
            String variation = "'wght' " + weight;
            if (FONT_NOTO_SANS.equals(option.id)) {
                variation += ", 'wdth' 100";
            }
            if (option.customFile != null) {
                try {
                    base = new Typeface.Builder(option.customFile)
                            .setFontVariationSettings(variation)
                            .build();
                } catch (RuntimeException ignored) {
                    base = null;
                }
                if (base == null) {
                    base = Typeface.createFromFile(option.customFile);
                }
            } else if (option.variable) {
                base = new Typeface.Builder(context.getAssets(), option.regularAsset)
                        .setFontVariationSettings(variation)
                        .build();
            } else {
                String asset = weight >= 600 && option.boldAsset != null
                        ? option.boldAsset : option.regularAsset;
                base = Typeface.createFromAsset(context.getAssets(), asset);
            }
            if (base == null) {
                return null;
            }
            if (italic) {
                int style = weight >= 600 && !option.variable
                        ? Typeface.BOLD_ITALIC : Typeface.ITALIC;
                Typeface styled = Typeface.create(base, style);
                return styled == null ? base : styled;
            }
            if (!option.variable && weight >= 600 && option.boldAsset == null) {
                Typeface styled = Typeface.create(base, Typeface.BOLD);
                return styled == null ? base : styled;
            }
            return base;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    public static String importFont(Context context, Uri uri) throws IOException {
        Context app = context.getApplicationContext();
        String originalName = queryDisplayName(app, uri);
        if (!isFontFile(originalName)) {
            throw new IOException("FONT_UNSUPPORTED: تەنها فایلی TTF یان OTF هەڵبژێرە");
        }
        File dir = customFontsDir(app);
        String clean = sanitizeFontFileName(originalName);
        String name = System.currentTimeMillis() + "-" + clean;
        File output = new File(dir, name);
        ContentResolver resolver = app.getContentResolver();
        InputStream input = resolver.openInputStream(uri);
        if (input == null) {
            throw new IOException("FONT_OPEN_FAILED: نەتوانرا فۆنتەکە بکرێتەوە");
        }
        long total = 0;
        try (InputStream in = input; OutputStream out = new FileOutputStream(output)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = in.read(buffer)) != -1) {
                total += count;
                if (total > MAX_CUSTOM_FONT_BYTES) {
                    throw new IOException("FONT_TOO_LARGE: فۆنتەکە لە 30 MB گەورەترە");
                }
                out.write(buffer, 0, count);
            }
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            output.delete();
            throw e;
        }
        try {
            Typeface test = Typeface.createFromFile(output);
            if (test == null) {
                throw new IOException("FONT_INVALID: فۆنتەکە نەخوێنرایەوە");
            }
        } catch (RuntimeException e) {
            //noinspection ResultOfMethodCallIgnored
            output.delete();
            throw new IOException("FONT_INVALID: فۆنتەکە نەخوێنرایەوە", e);
        }
        synchronized (CACHE) {
            CACHE.clear();
        }
        return CUSTOM_PREFIX + output.getName();
    }

    public static boolean deleteCustomFont(Context context, String fontId) {
        if (!isCustom(fontId)) {
            return false;
        }
        String name = fontId.substring(CUSTOM_PREFIX.length());
        File file = new File(customFontsDir(context), new File(name).getName());
        boolean deleted = !file.exists() || file.delete();
        synchronized (CACHE) {
            CACHE.clear();
        }
        return deleted;
    }

    public static File customFontsDir(Context context) {
        File dir = new File(context.getApplicationContext().getFilesDir(),
                "fonts/custom");
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return dir;
    }

    private static boolean isFontFile(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".ttf") || lower.endsWith(".otf");
    }

    private static String displayName(String fileName) {
        String name = fileName == null ? "فۆنتی هاوردەکراو" : fileName;
        int dash = name.indexOf('-');
        if (dash > 0 && dash < name.length() - 1) {
            String prefix = name.substring(0, dash);
            boolean digits = true;
            for (int i = 0; i < prefix.length(); i++) {
                digits &= Character.isDigit(prefix.charAt(i));
            }
            if (digits) {
                name = name.substring(dash + 1);
            }
        }
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }
        return name.replace('_', ' ');
    }

    private static String sanitizeFontFileName(String name) {
        String value = new File(name == null ? "font.ttf" : name).getName();
        value = value.replaceAll("[^A-Za-z0-9._-]+", "_");
        if (value.length() > 80) {
            int dot = value.lastIndexOf('.');
            String extension = dot >= 0 ? value.substring(dot) : ".ttf";
            value = value.substring(0, Math.max(1, 80 - extension.length())) + extension;
        }
        if (!isFontFile(value)) {
            value += ".ttf";
        }
        return value;
    }

    private static String queryDisplayName(Context context, Uri uri) {
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(uri,
                    new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String value = cursor.getString(index);
                    if (value != null && !value.trim().isEmpty()) {
                        return value.trim();
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // Fall through to the URI path segment.
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        String segment = uri.getLastPathSegment();
        return segment == null || segment.trim().isEmpty() ? "font.ttf" : segment.trim();
    }
}
