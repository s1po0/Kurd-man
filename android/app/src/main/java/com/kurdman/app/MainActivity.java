package com.kurdman.app;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private static final String PREFS_NAME = "kurdman_settings";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private SharedPreferences prefs;
    private EditText tokenInput;
    private EditText chatInput;
    private EditText messageInput;
    private Spinner sourceSpinner;
    private Button saveButton;
    private Button verifyButton;
    private Button sendTextButton;
    private Button pickButton;
    private Button translateButton;
    private Button sendResultButton;
    private ProgressBar progress;
    private TextView statusText;
    private TextView resultText;
    private ImageView previewImage;

    private ActivityResultLauncher<String> pickLauncher;
    private Bitmap originalBitmap;
    private Bitmap resultBitmap;
    private File resultFile;
    private String pairsText = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        tokenInput = findViewById(R.id.tokenInput);
        chatInput = findViewById(R.id.chatInput);
        messageInput = findViewById(R.id.messageInput);
        sourceSpinner = findViewById(R.id.sourceSpinner);
        saveButton = findViewById(R.id.saveButton);
        verifyButton = findViewById(R.id.verifyButton);
        sendTextButton = findViewById(R.id.sendTextButton);
        pickButton = findViewById(R.id.pickButton);
        translateButton = findViewById(R.id.translateButton);
        sendResultButton = findViewById(R.id.sendResultButton);
        progress = findViewById(R.id.progress);
        statusText = findViewById(R.id.statusText);
        resultText = findViewById(R.id.resultText);
        previewImage = findViewById(R.id.previewImage);

        // The token stays in this app's private storage on this phone only.
        tokenInput.setText(prefs.getString("token", ""));
        chatInput.setText(prefs.getString("chat_id", ""));

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_item,
                new String[]{"English (EN)", "Korean (KO)"});
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sourceSpinner.setAdapter(adapter);

        pickLauncher = registerForActivityResult(
                new ActivityResultContracts.GetContent(),
                new ActivityResultCallback<Uri>() {
                    @Override
                    public void onActivityResult(Uri uri) {
                        if (uri != null) {
                            loadImage(uri);
                        }
                    }
                });

        saveButton.setOnClickListener(v -> {
            saveSettings();
            setStatus("✅ تۆکن و Chat ID لەسەر ئەم مۆبایلە پاشەکەوت کرا.");
        });
        verifyButton.setOnClickListener(v -> verifyBot());
        sendTextButton.setOnClickListener(v -> sendTextMessage());
        pickButton.setOnClickListener(v -> pickLauncher.launch("image/*"));
        translateButton.setOnClickListener(v -> translateSelected());
        sendResultButton.setOnClickListener(v -> sendResultToTelegram());

        updateButtons(false);
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private void verifyBot() {
        final String token = tokenValue();
        if (!isValidToken(token)) {
            setStatus("⚠ سەرەتا تۆکنی دروست بنووسە (بە شێوەی ژمارە:پیت).");
            return;
        }
        saveSettings();
        setBusy(true);
        setStatus("پەیوەستبوون بە تێلێگرام…");
        executor.execute(() -> {
            try {
                JSONObject me = new TelegramApi(token).getMe();
                String username = me.optString("username", "");
                setStatus(username.isEmpty()
                        ? "✅ تۆکنەکە پەیوەستە."
                        : "✅ پەیوەستە: @" + username);
            } catch (Exception e) {
                setStatus("❌ " + friendlyError(e));
            } finally {
                setBusy(false);
            }
        });
    }

    private void sendTextMessage() {
        final String token = tokenValue();
        final String chatId = chatValue();
        final String text = messageInput.getText().toString().trim();
        if (!isValidToken(token)) {
            setStatus("⚠ سەرەتا تۆکنی دروست بنووسە.");
            return;
        }
        if (chatId.isEmpty()) {
            setStatus("⚠ Chat ID بنووسە.");
            return;
        }
        if (text.isEmpty()) {
            setStatus("⚠ نامەیەک بنووسە.");
            return;
        }
        saveSettings();
        setBusy(true);
        setStatus("ناردنی نامەکە بە تۆکنەکە…");
        executor.execute(() -> {
            try {
                TelegramApi api = new TelegramApi(token);
                for (String chunk : splitText(text, 3500)) {
                    api.sendMessage(chatId, chunk);
                }
                runOnUiThread(() -> messageInput.setText(""));
                setStatus("✅ نامەکە نێردرا.");
            } catch (Exception e) {
                setStatus("❌ " + friendlyError(e));
            } finally {
                setBusy(false);
            }
        });
    }

    private void loadImage(Uri uri) {
        setBusy(true);
        setStatus("خوێندنەوەی وێنەکە…");
        executor.execute(() -> {
            try {
                final Bitmap loaded = decodeBitmap(uri);
                if (loaded == null) {
                    throw new IOException("Could not decode image");
                }
                runOnUiThread(() -> {
                    originalBitmap = loaded;
                    resultBitmap = null;
                    resultFile = null;
                    pairsText = "";
                    previewImage.setImageBitmap(loaded);
                    resultText.setText("");
                    updateButtons(false);
                });
                setStatus("✅ وێنەکە ئامادەیە: "
                        + loaded.getWidth() + "×" + loaded.getHeight());
            } catch (Exception e) {
                setStatus("❌ نەتوانرا وێنەکە بخوێندرێتەوە: " + friendlyError(e));
            } finally {
                setBusy(false);
            }
        });
    }

    private void translateSelected() {
        final Bitmap sourceBitmap = originalBitmap;
        if (sourceBitmap == null) {
            setStatus("⚠ سەرەتا وێنەی مانهواکە هەڵبژێرە.");
            return;
        }
        final boolean korean = sourceSpinner.getSelectedItemPosition() == 1;
        final String sourceLanguage = korean ? "ko" : "en";

        setBusy(true);
        setStatus("دەستپێکردنی وەرگێڕان لەناو مۆبایلەکە…");
        executor.execute(() -> {
            try {
                List<Segment> segments = ManhwaRenderer.recognize(
                        sourceBitmap,
                        korean,
                        new ManhwaRenderer.Progress() {
                            @Override
                            public void onProgress(String message) {
                                setStatus(message);
                            }
                        });
                if (segments.isEmpty()) {
                    throw new IllegalStateException("هیچ دەقێکی ڕوون نەدۆزرایەوە. وێنەیەکی ڕوونتر تاقیبکەرەوە.");
                }

                SoraniTranslator translator = new SoraniTranslator();
                for (int i = 0; i < segments.size(); i++) {
                    Segment segment = segments.get(i);
                    setStatus("وەرگێڕان بۆ سۆرانی " + (i + 1) + "/" + segments.size() + "…");
                    segment.ckb = translator.translate(segment.text, sourceLanguage);
                }

                setStatus("دانانی دەقە کوردییەکان لەسەر وێنەکە…");
                ManhwaRenderer.RenderResult renderResult =
                        ManhwaRenderer.render(sourceBitmap, segments);
                final Bitmap rendered = renderResult.bitmap;
                File cached = writeJpegToCache(rendered);
                Uri savedUri = saveToPictures(rendered);
                String pairs = buildPairs(segments);

                resultBitmap = rendered;
                resultFile = cached;
                pairsText = pairs;
                runOnUiThread(() -> {
                    previewImage.setImageBitmap(rendered);
                    resultText.setText(pairs);
                    updateButtons(false);
                });

                String savedNote = savedUri != null
                        ? " لە Pictures/KurdMan پاشەکەوت کرا."
                        : " لە کاشی ئەپەکەدا پاشەکەوت کرا.";
                if (renderResult.rendered == 0) {
                    setStatus("⚠ دەقەکان وەرگێڕدران، بەڵام هیچ شوێنێکی سادە و پارێزراو "
                            + "نەبوو بۆ نووسینیان لەسەر وێنەکە؛ وەرگێڕانەکان لە خوارەوەن." + savedNote);
                } else {
                    setStatus("✅ " + renderResult.rendered + "/" + segments.size()
                            + " دەق لەسەر وێنەکە داندرا." + savedNote);
                }
            } catch (Exception e) {
                setStatus("❌ " + friendlyError(e));
            } finally {
                setBusy(false);
            }
        });
    }

    private void sendResultToTelegram() {
        final String token = tokenValue();
        final String chatId = chatValue();
        final File file = resultFile;
        final String pairs = pairsText;
        if (!isValidToken(token)) {
            setStatus("⚠ سەرەتا تۆکنی دروست بنووسە.");
            return;
        }
        if (chatId.isEmpty()) {
            setStatus("⚠ Chat ID بنووسە.");
            return;
        }
        if (file == null || !file.exists()) {
            setStatus("⚠ سەرەتا وێنەیەک وەربگێڕە.");
            return;
        }
        saveSettings();
        setBusy(true);
        setStatus("ناردنی ئەنجامەکە بۆ تێلێگرام…");
        executor.execute(() -> {
            try {
                TelegramApi api = new TelegramApi(token);
                api.sendDocument(chatId, file, "✅ ئەنجامی وەرگێڕانی مانهوا — Kurd-man");
                if (pairs != null && !pairs.trim().isEmpty()) {
                    List<String> chunks = splitText(pairs, 3300);
                    for (int i = 0; i < chunks.size(); i++) {
                        String prefix = i == 0 ? "📝 دەقەکان:\n\n" : "";
                        api.sendMessage(chatId, prefix + chunks.get(i));
                    }
                }
                setStatus("✅ ئەنجامەکە نێردرا بۆ تێلێگرام.");
            } catch (Exception e) {
                setStatus("❌ " + friendlyError(e));
            } finally {
                setBusy(false);
            }
        });
    }

    private Bitmap decodeBitmap(Uri uri) throws Exception {
        ContentResolver resolver = getContentResolver();

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

        int sample = 1;
        long pixels = (long) bounds.outWidth * (long) bounds.outHeight;
        while (pixels / ((long) sample * (long) sample) > 28_000_000L && sample < 16) {
            sample *= 2;
        }

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;

        InputStream second = resolver.openInputStream(uri);
        if (second == null) {
            throw new IOException("Could not reopen selected image");
        }
        Bitmap decoded;
        try {
            decoded = BitmapFactory.decodeStream(second, null, options);
        } finally {
            second.close();
        }

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

    private File writeJpegToCache(Bitmap bitmap) throws IOException {
        File file = new File(getCacheDir(), "kurdman-" + timestamp() + ".jpg");
        OutputStream out = new FileOutputStream(file);
        try {
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)) {
                throw new IOException("Could not write result image");
            }
        } finally {
            out.close();
        }
        return file;
    }

    private Uri saveToPictures(Bitmap bitmap) {
        try {
            String fileName = "kurdman-" + timestamp() + ".jpg";
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentResolver resolver = getContentResolver();
                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
                values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                values.put(MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + "/KurdMan");
                values.put(MediaStore.Images.Media.IS_PENDING, 1);

                Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if (uri == null) {
                    return null;
                }
                OutputStream out = resolver.openOutputStream(uri);
                if (out == null) {
                    resolver.delete(uri, null, null);
                    return null;
                }
                try {
                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)) {
                        resolver.delete(uri, null, null);
                        return null;
                    }
                } finally {
                    out.close();
                }
                values.clear();
                values.put(MediaStore.Images.Media.IS_PENDING, 0);
                resolver.update(uri, values, null, null);
                return uri;
            }

            File directory = new File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "KurdMan");
            if (!directory.exists() && !directory.mkdirs()) {
                return null;
            }
            File file = new File(directory, fileName);
            OutputStream out = new FileOutputStream(file);
            try {
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)) {
                    return null;
                }
            } finally {
                out.close();
            }
            return Uri.fromFile(file);
        } catch (Exception e) {
            return null;
        }
    }

    private String buildPairs(List<Segment> segments) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < segments.size(); i++) {
            Segment segment = segments.get(i);
            builder.append('[').append(i + 1).append("]\n");
            builder.append(segment.text.replace('\n', ' ')).append('\n');
            builder.append("→ ").append(segment.ckb).append("\n\n");
        }
        return builder.toString().trim();
    }

    private List<String> splitText(String text, int maxLength) {
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(text.length(), start + maxLength);
            if (end < text.length()) {
                int boundary = text.lastIndexOf('\n', end);
                if (boundary > start + 1000) {
                    end = boundary;
                }
            }
            String chunk = text.substring(start, end).trim();
            if (!chunk.isEmpty()) {
                chunks.add(chunk);
            }
            start = end;
            while (start < text.length() && Character.isWhitespace(text.charAt(start))) {
                start++;
            }
        }
        return chunks;
    }

    private void saveSettings() {
        prefs.edit()
                .putString("token", tokenValue())
                .putString("chat_id", chatValue())
                .apply();
    }

    private String tokenValue() {
        return tokenInput.getText().toString().trim();
    }

    private String chatValue() {
        return chatInput.getText().toString().trim();
    }

    private boolean isValidToken(String token) {
        return token != null && token.length() > 20 && token.indexOf(':') > 0;
    }

    private String timestamp() {
        return new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date());
    }

    private String friendlyError(Exception error) {
        String message = error.getMessage();
        if (message == null || message.trim().isEmpty()) {
            message = error.getClass().getSimpleName();
        }
        if (message.length() > 220) {
            message = message.substring(0, 220) + "…";
        }
        return message;
    }

    private void setStatus(final String text) {
        runOnUiThread(() -> statusText.setText(text));
    }

    private void setBusy(final boolean busy) {
        runOnUiThread(() -> {
            progress.setVisibility(busy ? View.VISIBLE : View.GONE);
            updateButtons(busy);
        });
    }

    private void updateButtons(boolean busy) {
        boolean enabled = !busy;
        saveButton.setEnabled(enabled);
        verifyButton.setEnabled(enabled);
        sendTextButton.setEnabled(enabled);
        pickButton.setEnabled(enabled);
        translateButton.setEnabled(enabled);
        sendResultButton.setEnabled(enabled && resultFile != null && resultFile.exists());
    }
}
