package com.kurdman.app;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.method.HideReturnsTransformationMethod;
import android.text.method.PasswordTransformationMethod;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends AppCompatActivity {
    private static final String PREFS_NAME = "kurdman_settings";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private SharedPreferences prefs;
    private EditText tokenInput;
    private EditText chatInput;
    private EditText messageInput;
    private Spinner sourceSpinner;
    private Button pasteTokenButton;
    private Button pasteChatButton;
    private CheckBox showTokenCheck;
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
        pasteTokenButton = findViewById(R.id.pasteTokenButton);
        pasteChatButton = findViewById(R.id.pasteChatButton);
        showTokenCheck = findViewById(R.id.showTokenCheck);
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
        tokenInput.setTransformationMethod(PasswordTransformationMethod.getInstance());

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

        pasteTokenButton.setOnClickListener(v -> pasteTokenFromClipboard());
        pasteChatButton.setOnClickListener(v -> pasteChatIdFromClipboard());
        showTokenCheck.setOnCheckedChangeListener(
                (buttonView, isChecked) -> setTokenVisible(isChecked));
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
        final String chatId = chatValue();
        String problem = settingsError(token, chatId, false);
        if (problem != null) {
            setStatus("⚠ " + problem);
            return;
        }
        saveSettings();
        setBusy(true);
        setStatus("پەیوەستبوون بە تێلێگرام…");
        executor.execute(() -> {
            try {
                TelegramApi api = new TelegramApi(token);
                JSONObject me = api.getMe();
                String username = me.optString("username", "");
                String status = username.isEmpty()
                        ? "✅ تۆکنەکە پەیوەستە."
                        : "✅ تۆکنەکە پەیوەستە: @" + username;
                if (!chatId.isEmpty()) {
                    // Prove the bot can actually reach this chat; getMe alone
                    // does not validate the Chat ID at all.
                    api.sendChatAction(chatId, "typing");
                    status += " — و Chat IDـەکەش وەڵامی دا ✅";
                    try {
                        JSONObject chat = api.getChat(chatId);
                        String title = chat.optString("title",
                                chat.optString("first_name",
                                        chat.optString("username", "")));
                        if (!title.isEmpty()) {
                            status += " (" + title + ")";
                        }
                    } catch (Exception ignored) {
                        // sendChatAction already proved reachability.
                    }
                } else {
                    status += " (Chat ID هێشتا نەنووسراوە)";
                }
                setStatus(status);
            } catch (Exception e) {
                setStatus("❌ " + friendlyTelegramError(e));
            } finally {
                setBusy(false);
            }
        });
    }

    private void setTokenVisible(boolean visible) {
        int start = Math.max(0, tokenInput.getSelectionStart());
        int end = Math.max(0, tokenInput.getSelectionEnd());
        tokenInput.setTransformationMethod(visible
                ? HideReturnsTransformationMethod.getInstance()
                : PasswordTransformationMethod.getInstance());
        int length = tokenInput.getText().length();
        tokenInput.setSelection(Math.min(start, length), Math.min(end, length));
    }

    private void pasteTokenFromClipboard() {
        String raw = clipboardText();
        Matcher tokenMatcher = Pattern
                .compile("\\b\\d{6,}:[-_A-Za-z0-9]{20,}\\b")
                .matcher(raw);
        String clean = tokenMatcher.find()
                ? tokenMatcher.group()
                : raw.replaceAll("\\s+", "");
        if (clean.isEmpty()) {
            setStatus("⚠ کلیپبۆردەکە بەتاڵە؛ سەرەتا تۆکنەکە لە @BotFather کۆپی بکە.");
            return;
        }
        tokenInput.setText(clean);
        tokenInput.setSelection(clean.length());
        tokenInput.requestFocus();
        if (isValidToken(clean)) {
            setStatus("✅ تۆکنەکە لکێنرا. ئێستا «پاشەکەوتکردن» پاشان «پشکنینی بۆت» دابگرە.");
        } else {
            setStatus("⚠ ئەوەی لکێنرا وەک تۆکن ناچێت؛ تۆکنەکە بە ژمارە و «:» دەستپێدەکات.");
        }
    }

    private void pasteChatIdFromClipboard() {
        Matcher idMatcher = Pattern.compile("-?\\d{5,}").matcher(clipboardText());
        String clean = idMatcher.find() ? idMatcher.group() : "";
        if (clean.isEmpty() || clean.equals("-")) {
            setStatus("⚠ کلیپبۆردەکە ژمارەیەکی تێدا نییە؛ سەرەتا IDـەکە لە @userinfobot کۆپی بکە.");
            return;
        }
        chatInput.setText(clean);
        chatInput.setSelection(clean.length());
        chatInput.requestFocus();
        setStatus("✅ Chat ID لکێنرا. ئێستا «پاشەکەوتکردن» پاشان «پشکنینی بۆت» دابگرە.");
    }

    private String clipboardText() {
        ClipboardManager manager =
                (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (manager == null || !manager.hasPrimaryClip()) {
            return "";
        }
        ClipData clip = manager.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) {
            return "";
        }
        CharSequence text = clip.getItemAt(0).coerceToText(this);
        return text == null ? "" : text.toString();
    }

    private void sendTextMessage() {
        final String token = tokenValue();
        final String chatId = chatValue();
        final String text = messageInput.getText().toString().trim();
        String problem = settingsError(token, chatId, true);
        if (problem != null) {
            setStatus("⚠ " + problem);
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
                setStatus("❌ " + friendlyTelegramError(e));
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
        String problem = settingsError(token, chatId, true);
        if (problem != null) {
            setStatus("⚠ " + problem);
            return;
        }
        if (file == null || !file.exists() || file.length() <= 0) {
            setStatus("⚠ سەرەتا وێنەیەک وەربگێڕە.");
            return;
        }
        saveSettings();
        setBusy(true);
        setStatus("ناردنی ئەنجامەکە بۆ تێلێگرام (" + formatMb(file.length()) + ")…");
        executor.execute(() -> {
            try {
                TelegramApi api = new TelegramApi(token);
                // Fail fast (and clearly) if the bot cannot reach this chat,
                // instead of uploading the whole file first.
                api.sendChatAction(chatId, "upload_document");
                JSONObject sent = api.sendDocument(
                        chatId, file, "✅ ئەنجامی وەرگێڕانی مانهوا — Kurd-man");
                long messageId = sent.optLong("message_id", 0);
                String doneNote = messageId > 0
                        ? "✅ وێنەکە نێردرا بۆ تێلێگرام (message_id=" + messageId + ")."
                        : "✅ وێنەکە نێردرا بۆ تێلێگرام.";
                setStatus(doneNote);

                // The document is already delivered at this point; report a
                // text failure separately instead of marking everything failed.
                if (pairs != null && !pairs.trim().isEmpty()) {
                    try {
                        List<String> chunks = splitText(pairs, 3300);
                        for (int i = 0; i < chunks.size(); i++) {
                            String prefix = i == 0 ? "📝 دەقەکان:\n\n" : "";
                            api.sendMessage(chatId, prefix + chunks.get(i));
                        }
                        setStatus(doneNote + " دەقەکانیش نێردران ✅");
                    } catch (Exception textError) {
                        setStatus(doneNote + " بەڵام دەقەکان نەچوون: "
                                + friendlyTelegramError(textError));
                    }
                }
            } catch (Exception e) {
                setStatus("❌ " + friendlyTelegramError(e));
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
        // A bot token never contains whitespace; drop it so a token pasted
        // across wrapped lines still works.
        return tokenInput.getText().toString().replaceAll("\\s+", "");
    }

    private String chatValue() {
        return chatInput.getText().toString().replaceAll("\\s+", "");
    }

    private boolean isValidToken(String token) {
        return token != null && token.length() > 20 && token.indexOf(':') > 0;
    }

    /** Returns a Kurdish explanation when the Telegram settings cannot work. */
    private String settingsError(String token, String chatId, boolean requireChat) {
        if (!isValidToken(token)) {
            return "سەرەتا تۆکنی دروست بنووسە (بە شێوەی ژمارە:پیت، لە @BotFather).";
        }
        if (chatId == null || chatId.isEmpty()) {
            return requireChat ? "Chat IDـی خۆت بنووسە (لە @userinfobot وەریبگرە)." : null;
        }
        if (!chatId.matches("-?\\d{5,}")) {
            return "Chat ID دەبێت تەنها ژمارە بێت؛ ناوی بەکارهێنەر یان ناوی بۆتەکە مەنووسە.";
        }
        String botId = token.substring(0, token.indexOf(':')).trim();
        if (chatId.equals(botId)) {
            return "ئەم ژمارەیە IDـی بۆتەکەیە (بەشی پێش «:» لە تۆکندا)، نەک IDـی خۆت. "
                    + "IDـی خۆت لە @userinfobot وەربگرە.";
        }
        return null;
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

    /** Maps common Telegram failures to a clear Kurdish explanation. */
    private String friendlyTelegramError(Exception error) {
        String raw = friendlyError(error);
        String lower = raw.toLowerCase(Locale.ROOT);
        String hint = null;
        if (lower.contains("unauthorized") || lower.contains("http 401")) {
            hint = "تۆکنەکە هەڵەیە یان لە @BotFather هەڵوەشێنراوەتەوە. تۆکنەکە دووبارە کۆپی بکە.";
        } else if (lower.contains("chat not found")) {
            hint = "Chat ID هەڵەیە، یان بۆتەکەت لە تلگرام نەکراوەتەوە و /start نەدراوە. "
                    + "IDـی خۆت لە @userinfobot بپشکنە.";
        } else if (lower.contains("bot was blocked") || lower.contains("forbidden")) {
            hint = "بۆتەکە بلۆک کراوە یان ناتوانێت نامە بنێرێت؛ لە تلگرام بیکەرەوە و /start دابگرە.";
        } else if (lower.contains("file is too big") || lower.contains("too large")
                || lower.contains("http 413")) {
            hint = "فایلەکە زۆر گەورەیە بۆ سنووری بۆتەکانی تلگرام (50 MB). "
                    + "وێنەکە بکە بە دوو بەش و هەر بەشێک جیا وەربگێڕە.";
        } else if (lower.contains("timed out") || lower.contains("timeout")
                || lower.contains("unable to resolve host")
                || lower.contains("failed to connect")
                || lower.contains("connection reset")
                || lower.contains("network is unreachable")
                || lower.contains("empty response")) {
            hint = "پەیوەندییەکە بە تلگرامەوە نەبەسترا یان پچڕا؛ ئینتەرنێتەکە بپشکنە و دووبارە هەوڵبدە.";
        } else if (lower.contains("message is too long")) {
            hint = "نامەکە زۆر درێژ بوو بۆ تلگرام.";
        }
        return hint == null ? raw : hint + " (" + raw + ")";
    }

    private String formatMb(long bytes) {
        return String.format(Locale.ROOT, "%.1f MB", bytes / 1024f / 1024f);
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
        pasteTokenButton.setEnabled(enabled);
        pasteChatButton.setEnabled(enabled);
        showTokenCheck.setEnabled(enabled);
        saveButton.setEnabled(enabled);
        verifyButton.setEnabled(enabled);
        sendTextButton.setEnabled(enabled);
        pickButton.setEnabled(enabled);
        translateButton.setEnabled(enabled);
        sendResultButton.setEnabled(enabled && resultFile != null && resultFile.exists());
    }
}
