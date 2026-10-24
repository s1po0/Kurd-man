package com.kurdman.app;

import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.text.method.HideReturnsTransformationMethod;
import android.text.method.PasswordTransformationMethod;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public class MainActivity extends AppCompatActivity {
    private static final String PREFS_NAME = "kurdman_settings";
    private static final String[] SOURCE_LABELS = {
            "English — ئینگلیزی (EN)",
            "한국어 — کۆری (KO)",
            "日本語 — ژاپۆنی (JA)",
            "中文（简体）— چینی سادەکراو (ZH-CN)",
            "中文（繁體）— چینی تەقلیدی (ZH-TW)"
    };
    private static final String[] SOURCE_CODES = {
            "en", "ko", "ja", "zh-CN", "zh-TW"
    };
    private static final String[] TRANSLATION_MODE_LABELS = {
            "🌐 ئۆنلاین — خێراتر (Google)",
            "🤖 ئۆفلاین AI — Meta NLLB-200"
    };

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private SharedPreferences prefs;
    private EditText tokenInput;
    private EditText chatInput;
    private EditText messageInput;
    private Spinner sourceSpinner;
    private Spinner translationModeSpinner;
    private TextView offlineModelStatus;
    private Button downloadOfflineButton;
    private Button deleteOfflineButton;
    private OfflineModelManager offlineModelManager;
    private Button pasteTokenButton;
    private Button pasteChatButton;
    private CheckBox showTokenCheck;
    private Button saveButton;
    private Button verifyButton;
    private Button sendTextButton;
    private Button pickButton;
    private Button translateButton;
    private Button sendResultButton;
    private Button refreshDownloadsButton;
    private Button openDownloadsButton;
    private LinearLayout downloadsContainer;
    private ProgressBar progress;
    private TextView statusText;
    private TextView resultText;
    private ImageView previewImage;

    private ActivityResultLauncher<String[]> pickLauncher;
    private Bitmap originalBitmap;
    private Bitmap resultBitmap;
    private File resultFile;
    private File resultTextFile;
    private String pairsText = "";
    private File selectedCbzFile;
    private String selectedName = "";
    private int selectedCbzPages = 0;

    private static class ExtractedPage {
        final String entryName;
        final File file;

        ExtractedPage(String entryName, File file) {
            this.entryName = entryName;
            this.file = file;
        }
    }

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
        translationModeSpinner = findViewById(R.id.translationModeSpinner);
        offlineModelStatus = findViewById(R.id.offlineModelStatus);
        downloadOfflineButton = findViewById(R.id.downloadOfflineButton);
        deleteOfflineButton = findViewById(R.id.deleteOfflineButton);
        offlineModelManager = new OfflineModelManager(this);
        saveButton = findViewById(R.id.saveButton);
        verifyButton = findViewById(R.id.verifyButton);
        sendTextButton = findViewById(R.id.sendTextButton);
        pickButton = findViewById(R.id.pickButton);
        translateButton = findViewById(R.id.translateButton);
        sendResultButton = findViewById(R.id.sendResultButton);
        refreshDownloadsButton = findViewById(R.id.refreshDownloadsButton);
        openDownloadsButton = findViewById(R.id.openDownloadsButton);
        downloadsContainer = findViewById(R.id.downloadsContainer);
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
                R.layout.spinner_item,
                SOURCE_LABELS);
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        sourceSpinner.setAdapter(adapter);
        int savedSourceIndex = prefs.getInt("source_index", 0);
        if (savedSourceIndex >= 0 && savedSourceIndex < SOURCE_CODES.length) {
            sourceSpinner.setSelection(savedSourceIndex);
        }
        sourceSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                prefs.edit().putInt("source_index", position).apply();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // Keep the last saved source language.
            }
        });

        ArrayAdapter<String> modeAdapter = new ArrayAdapter<>(
                this,
                R.layout.spinner_item,
                TRANSLATION_MODE_LABELS);
        modeAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        translationModeSpinner.setAdapter(modeAdapter);
        int savedModeIndex = prefs.getInt("translation_mode_index", 0);
        if (savedModeIndex >= 0
                && savedModeIndex < TRANSLATION_MODE_LABELS.length) {
            translationModeSpinner.setSelection(savedModeIndex);
        }
        translationModeSpinner.setOnItemSelectedListener(
                new AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(AdapterView<?> parent, View view,
                                               int position, long id) {
                        prefs.edit()
                                .putInt("translation_mode_index", position)
                                .apply();
                        refreshOfflineModelUi();
                    }

                    @Override
                    public void onNothingSelected(AdapterView<?> parent) {
                        // Keep the last saved translation mode.
                    }
                });

        pickLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                new ActivityResultCallback<Uri>() {
                    @Override
                    public void onActivityResult(Uri uri) {
                        if (uri != null) {
                            loadPickedFile(uri);
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
        pickButton.setOnClickListener(v -> pickLauncher.launch(new String[]{
                "image/*",
                "application/zip",
                "application/x-zip-compressed",
                "application/x-cbz",
                "application/vnd.comicbook+zip",
                "application/x-comicbook",
                "application/octet-stream"
        }));
        downloadOfflineButton.setOnClickListener(v -> downloadOfflineModel());
        deleteOfflineButton.setOnClickListener(v -> confirmDeleteOfflineModel());
        translateButton.setOnClickListener(v -> translateSelected());
        sendResultButton.setOnClickListener(v -> sendResultToTelegram());
        refreshDownloadsButton.setOnClickListener(v -> refreshDownloadsList());
        openDownloadsButton.setOnClickListener(v -> openSystemDownloads());

        refreshOfflineModelUi();
        updateButtons(false);
        refreshDownloadsList();
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private boolean selectedOfflineMode() {
        return translationModeSpinner != null
                && translationModeSpinner.getSelectedItemPosition() == 1;
    }

    private SoraniTranslator createTranslator(boolean offlineMode) throws Exception {
        if (!offlineMode) {
            return new SoraniTranslator();
        }
        setStatus("🤖 بارکردنی مۆدێلی زیرەکیی دەستکردی ئۆفلاین… "
                + "یەکەمجار لەوانەیە چەند چرکەیەک بخایەنێت.");
        OfflineNllbTranslator engine =
                new OfflineNllbTranslator(offlineModelManager);
        try {
            engine.initialize();
            return new SoraniTranslator(engine);
        } catch (Exception e) {
            engine.close();
            throw e;
        }
    }

    private void closeTranslator(SoraniTranslator translator) {
        if (translator != null) {
            try {
                translator.close();
            } catch (Exception ignored) {
                // The translator implementation already treats model
                // cleanup as best-effort; this is a final safety net.
            }
        }
    }

    private void refreshOfflineModelUi() {
        if (offlineModelManager == null || offlineModelStatus == null
                || downloadOfflineButton == null) {
            return;
        }
        boolean ready = offlineModelManager.isModelReady();
        long downloaded = offlineModelManager.downloadedBytes();
        long total = OfflineModelManager.totalBytes();
        if (ready) {
            String text = "✅ ئامادەیە بۆ وەرگێڕانی بێ ئینتەرنێت • "
                    + formatStorage(total);
            if (!selectedOfflineMode()) {
                text += " — «ئۆفلاین AI» هەڵبژێرە بۆ بەکارهێنان";
            }
            offlineModelStatus.setText(text);
            downloadOfflineButton.setText("✓ مۆدێلی AI ئامادەیە");
        } else if (downloaded > 0) {
            offlineModelStatus.setText("⬇ بەشێک دابەزیوە: "
                    + formatStorage(downloaded) + " / " + formatStorage(total)
                    + " — دەتوانیت درێژە بدەیت");
            downloadOfflineButton.setText("↻ درێژەدان بە دابەزاندن");
        } else {
            offlineModelStatus.setText("هێشتا دانەبەزیوە • "
                    + formatStorage(total));
            downloadOfflineButton.setText("⬇  دابەزاندنی مۆدێلی AI");
        }
    }

    private void downloadOfflineModel() {
        if (offlineModelManager.isModelReady()) {
            refreshOfflineModelUi();
            setStatus("✅ مۆدێلی ئۆفلاین لەم مۆبایلەدا ئامادەیە.");
            return;
        }
        setBusy(true);
        setStatus("⬇ دەستپێکردنی دابەزاندنی مۆدێلی ئۆفلاین ("
                + formatStorage(OfflineModelManager.totalBytes()) + ")…");
        executor.execute(() -> {
            final int[] lastPercent = {-1};
            final boolean[] lastVerifying = {false};
            try {
                offlineModelManager.downloadModel(
                        (downloadedBytes, totalBytes, currentFile, verifying) -> {
                            int percent = totalBytes == 0 ? 0
                                    : (int) ((downloadedBytes * 100L) / totalBytes);
                            if (percent == lastPercent[0]
                                    && verifying == lastVerifying[0]
                                    && percent < 100) {
                                return;
                            }
                            lastPercent[0] = percent;
                            lastVerifying[0] = verifying;
                            String status = (verifying
                                    ? "🔎 پشکنینی دروستیی مۆدێلی ئۆفلاین: "
                                    : "⬇ دابەزاندنی مۆدێلی ئۆفلاین: ")
                                    + percent + "% ("
                                    + formatStorage(downloadedBytes) + " / "
                                    + formatStorage(totalBytes) + ")"
                                    + (currentFile == null || currentFile.isEmpty()
                                            ? "" : " — " + currentFile);
                            setStatus(status);
                            runOnUiThread(() -> {
                                if (offlineModelStatus != null) {
                                    offlineModelStatus.setText(status);
                                }
                            });
                        });
                runOnUiThread(this::refreshOfflineModelUi);
                setStatus("✅ مۆدێلی ئۆفلاین ئامادەیە. ئێستا دەتوانیت "
                        + "ئینتەرنێت بکوژێنیتەوە و «ئۆفلاین AI» بەکاربهێنیت.");
            } catch (Exception e) {
                runOnUiThread(this::refreshOfflineModelUi);
                setStatus("❌ دابەزاندنی مۆدێلی ئۆفلاین سەرکەوتوو نەبوو: "
                        + friendlyOfflineError(e));
            } finally {
                setBusy(false);
            }
        });
    }

    private void confirmDeleteOfflineModel() {
        long downloaded = offlineModelManager.downloadedBytes();
        if (downloaded <= 0 && !offlineModelManager.isModelReady()) {
            refreshOfflineModelUi();
            setStatus("⚠ هیچ مۆدێلێکی ئۆفلاین لەم مۆبایلەدا نییە بۆ سڕینەوە.");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("سڕینەوەی مۆدێلی ئۆفلاین؟")
                .setMessage("نزیکەی " + formatStorage(downloaded)
                        + " دەسڕدرێتەوە. بۆ بەکارهێنانەوەی ئۆفلاین AI دەبێت "
                        + "مۆدێلەکە دووبارە دابەزێنیت.")
                .setNegativeButton("پاشگەزبوونەوە", null)
                .setPositiveButton("بیسرەوە",
                        (dialog, which) -> deleteOfflineModel())
                .show();
    }

    private void deleteOfflineModel() {
        setBusy(true);
        setStatus("سڕینەوەی مۆدێلی ئۆفلاین…");
        executor.execute(() -> {
            try {
                boolean deleted = offlineModelManager.deleteModel();
                runOnUiThread(this::refreshOfflineModelUi);
                setStatus(deleted
                        ? "✅ مۆدێلی ئۆفلاین سڕایەوە و شوێنەکە ئازاد کرا."
                        : "⚠ هەندێک فایلی مۆدێلەکە نەسڕایەوە؛ دووبارە هەوڵبدە.");
            } catch (Exception e) {
                runOnUiThread(this::refreshOfflineModelUi);
                setStatus("❌ سڕینەوەی مۆدێلەکە سەرکەوتوو نەبوو: "
                        + friendlyError(e));
            } finally {
                setBusy(false);
            }
        });
    }

    private String formatStorage(long bytes) {
        if (bytes >= 1000000000L) {
            return String.format(Locale.ROOT, "%.2f GB",
                    bytes / 1000000000f);
        }
        if (bytes >= 1000000L) {
            return String.format(Locale.ROOT, "%.1f MB",
                    bytes / 1000000f);
        }
        if (bytes >= 1000L) {
            return String.format(Locale.ROOT, "%.1f KB", bytes / 1000f);
        }
        return bytes + " B";
    }

    private String errorChainText(Throwable error) {
        StringBuilder chain = new StringBuilder();
        Throwable current = error;
        int depth = 0;
        while (current != null && depth < 5) {
            chain.append(current.getClass().getName()).append(':')
                    .append(current.getMessage()).append('\n');
            current = current.getCause();
            depth++;
        }
        return chain.toString().toLowerCase(Locale.ROOT);
    }

    private String friendlyOfflineError(Exception error) {
        String raw = friendlyError(error);
        String lower = errorChainText(error);
        if (lower.contains("not enough free storage")) {
            return "شوێنی بەتاڵی مۆبایلەکە بەش ناکات؛ بۆ مۆدێلەکە نزیکەی "
                    + formatStorage(OfflineModelManager.totalBytes())
                    + " و کەمێک شوێنی زیادە پێویستە. (" + raw + ")";
        }
        if (lower.contains("checksum mismatch")
                || lower.contains("sha-256 mismatch")) {
            return "فایلێکی مۆدێلەکە تێکچووە یان گۆڕاوە و پشکنینی دروستییەکە "
                    + "سەرکەوتوو نەبوو؛ دووبارە دایبەزێنە. (" + raw + ")";
        }
        if (lower.contains("offline_ai_out_of_memory")
                || lower.contains("outofmemoryerror")
                || lower.contains("failed to allocate")) {
            return "بیرگەی ئازادی مۆبایلەکە بەش ناکات بۆ بارکردنی مۆدێلەکە؛ "
                    + "ئەپەکانی تر دابخە و دووبارە هەوڵبدە. (" + raw + ")";
        }
        if (lower.contains("offline_model_not_ready")) {
            return "سەرەتا «دابەزاندنی مۆدێلی AI» دابگرە و چاوەڕوان بکە تا "
                    + "دابەزاندن و پشکنینەکە تەواو بێت. (" + raw + ")";
        }
        if (lower.contains("offline_ai_returned_untranslated")
                || lower.contains("offline ai returned untranslated")) {
            return "مۆدێلە ئۆفلاینەکە بۆ ئەم دەقە وەرگێڕانێکی کوردی بەرهەم "
                    + "نەهێنا؛ وێنەیەکی ڕوونتر یان دۆخی ئۆنلاین تاقیبکەرەوە. ("
                    + raw + ")";
        }
        if (lower.contains("offline_ai_runtime_error")
                || lower.contains("offline_ai_model_mismatch")
                || lower.contains("onnx")
                || lower.contains("ortexception")) {
            return "مۆدێلی ئۆفلاین نەتوانرا لەسەر ئەم مۆبایلە کاربکات؛ "
                    + "ئەگەر دووبارە بووەوە مۆدێلەکە بسرەوە و دووبارە دایبەزێنە. ("
                    + raw + ")";
        }
        if (lower.contains("unable to resolve host")
                || lower.contains("unknownhostexception")
                || lower.contains("failed to connect")
                || lower.contains("timed out") || lower.contains("timeout")
                || lower.contains("connection reset")
                || lower.contains("network is unreachable")) {
            return "پەیوەندی بە سێرفەری دابەزاندنی مۆدێلەکەوە نەبەسترا؛ "
                    + "ئینتەرنێتەکە بپشکنە و درێژە بە دابەزاندن بدە. ("
                    + raw + ")";
        }
        return raw;
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

    private void loadPickedFile(Uri uri) {
        final String displayName = queryDisplayName(uri);
        final String mimeType = getContentResolver().getType(uri);
        final boolean cbz = isCbzSelection(displayName, mimeType);
        setBusy(true);
        setStatus(cbz ? "خوێندنەوەی CBZ و دەرهێنانی وێنەکان…" : "خوێندنەوەی وێنەکە…");
        executor.execute(() -> {
            try {
                if (cbz) {
                    File copied = new File(getCacheDir(),
                            "selected-" + timestamp() + ".cbz");
                    copyUriToFile(uri, copied, 300L * 1024L * 1024L);

                    int pageCount = 0;
                    Bitmap preview = null;
                    ZipFile zip = new ZipFile(copied);
                    try {
                        List<ZipEntry> entries = listCbzImageEntries(zip);
                        pageCount = entries.size();
                        if (pageCount == 0) {
                            throw new IOException("No images were found inside this CBZ");
                        }
                        ZipEntry first = entries.get(0);
                        File previewFile = new File(getCacheDir(),
                                "cbz-preview-" + timestamp()
                                        + extensionWithDot(first.getName(), ".jpg"));
                        copyZipEntry(zip, first, previewFile);
                        preview = decodeBitmap(previewFile);
                    } finally {
                        zip.close();
                    }

                    final Bitmap previewBitmap = preview;
                    final int finalPageCount = pageCount;
                    runOnUiThread(() -> {
                        selectedCbzFile = copied;
                        selectedName = displayName;
                        selectedCbzPages = finalPageCount;
                        originalBitmap = null;
                        resultBitmap = null;
                        resultFile = null;
                        resultTextFile = null;
                        pairsText = "";
                        if (previewBitmap != null) {
                            previewImage.setImageBitmap(previewBitmap);
                        }
                        resultText.setText("📚 " + displayName
                                + "\nلاپەڕەکان: " + finalPageCount
                                + "\nئامادەیە بۆ وەرگێڕان.");
                        updateButtons(false);
                    });
                    setStatus("✅ CBZ ئامادەیە: " + displayName
                            + " — " + finalPageCount + " لاپەڕە.");
                } else {
                    final Bitmap loaded = decodeBitmap(uri);
                    if (loaded == null) {
                        throw new IOException("Could not decode image");
                    }
                    runOnUiThread(() -> {
                        selectedCbzFile = null;
                        selectedName = displayName;
                        selectedCbzPages = 0;
                        originalBitmap = loaded;
                        resultBitmap = null;
                        resultFile = null;
                        resultTextFile = null;
                        pairsText = "";
                        previewImage.setImageBitmap(loaded);
                        resultText.setText("");
                        updateButtons(false);
                    });
                    setStatus("✅ وێنەکە ئامادەیە: "
                            + loaded.getWidth() + "×" + loaded.getHeight());
                }
            } catch (Exception e) {
                setStatus("❌ نەتوانرا فایلەکە بخوێندرێتەوە: " + friendlyError(e));
            } finally {
                setBusy(false);
            }
        });
    }

    private String selectedSourceLanguage() {
        int index = sourceSpinner == null ? 0 : sourceSpinner.getSelectedItemPosition();
        if (index < 0 || index >= SOURCE_CODES.length) {
            index = 0;
        }
        return SOURCE_CODES[index];
    }

    private void translateSelected() {
        final Bitmap sourceBitmap = originalBitmap;
        final File cbzFile = selectedCbzFile;
        final String cbzName = selectedName;
        if (sourceBitmap == null && (cbzFile == null || !cbzFile.exists())) {
            setStatus("⚠ سەرەتا وێنەی مانهوا یان فایلی CBZ هەڵبژێرە.");
            return;
        }
        final String sourceLanguage = selectedSourceLanguage();
        final boolean offlineMode = selectedOfflineMode();
        if (offlineMode && !offlineModelManager.isModelReady()) {
            refreshOfflineModelUi();
            setStatus("⚠ بۆ وەرگێڕانی ئۆفلاین سەرەتا «دابەزاندنی مۆدێلی AI» "
                    + "دابگرە؛ یەکەمجار پێویستی بە ئینتەرنێتە و دواتر بێ ئینتەرنێت کار دەکات.");
            return;
        }

        setBusy(true);
        setStatus(offlineMode
                ? "🤖 دەستپێکردنی وەرگێڕانی ئۆفلاین لەناو مۆبایلەکە…"
                : "دەستپێکردنی وەرگێڕان لەناو مۆبایلەکە…");
        executor.execute(() -> {
            SoraniTranslator translator = null;
            try {
                if (cbzFile != null) {
                    translateCbzJob(cbzFile, cbzName, sourceLanguage, offlineMode);
                    return;
                }

                List<Segment> segments = ManhwaRenderer.recognize(
                        sourceBitmap,
                        sourceLanguage,
                        new ManhwaRenderer.Progress() {
                            @Override
                            public void onProgress(String message) {
                                setStatus(message);
                            }
                        });
                if (segments.isEmpty()) {
                    throw new IllegalStateException("هیچ دەقێکی ڕوون نەدۆزرایەوە. وێنەیەکی ڕوونتر تاقیبکەرەوە.");
                }

                translator = createTranslator(offlineMode);
                for (int i = 0; i < segments.size(); i++) {
                    Segment segment = segments.get(i);
                    setStatus((offlineMode
                                    ? "🤖 وەرگێڕانی ئۆفلاین بۆ سۆرانی "
                                    : "وەرگێڕان بۆ سۆرانی ")
                            + (i + 1) + "/" + segments.size() + "…");
                    segment.ckb = translator.translate(segment.text, sourceLanguage);
                }

                setStatus("دانانی دەقە کوردییەکان لەسەر وێنەکە…");
                ManhwaRenderer.RenderResult renderResult =
                        ManhwaRenderer.render(sourceBitmap, segments);
                final Bitmap rendered = renderResult.bitmap;
                File cached = writeJpegToCache(rendered);
                Uri savedUri = saveToPictures(rendered);
                String pairs = buildPairs(segments);
                File textFile = writeTextToCache(
                        pairs, "kurdman-" + timestamp() + ".txt");
                Uri downloadImageUri = saveFileToDownloads(cached, "image/jpeg");
                Uri downloadTextUri = saveFileToDownloads(textFile, "text/plain");
                copyToDownloadsLibrary(cached);
                copyToDownloadsLibrary(textFile);

                resultBitmap = rendered;
                resultFile = cached;
                resultTextFile = textFile;
                pairsText = pairs;
                runOnUiThread(() -> {
                    previewImage.setImageBitmap(rendered);
                    resultText.setText(pairs);
                    updateButtons(false);
                    refreshDownloadsList();
                });

                String savedNote = downloadImageUri != null || downloadTextUri != null
                        ? " لە Download/KurdMan پاشەکەوت کرا و لە بەشی «داگرتنەکان» دەردەکەوێت."
                        : (savedUri != null
                                ? " لە Pictures/KurdMan پاشەکەوت کرا."
                                : " لە کاشی ئەپەکەدا پاشەکەوت کرا.");
                if (renderResult.rendered == 0) {
                    setStatus("⚠ دەقەکان وەرگێڕدران، بەڵام هیچ شوێنێکی سادە و پارێزراو "
                            + "نەبوو بۆ نووسینیان لەسەر وێنەکە؛ وەرگێڕانەکان لە خوارەوەن." + savedNote);
                } else if (renderResult.skipped() > 0) {
                    setStatus("⚠ " + renderResult.rendered + "/" + segments.size()
                            + " دەق لەسەر وێنەکە داندرا؛ " + renderResult.skipped()
                            + " دەق تەنیا لە لیستی دەقەکاندا هەیە." + savedNote);
                } else {
                    setStatus("✅ " + renderResult.rendered + "/" + segments.size()
                            + " دەق لەسەر وێنەکە داندرا." + savedNote);
                }
            } catch (Exception e) {
                setStatus("❌ " + friendlyTranslationError(e));
            } finally {
                closeTranslator(translator);
                setBusy(false);
            }
        });
    }

    private void translateCbzJob(File cbz, String displayName,
                                   String sourceLanguage,
                                   boolean offlineMode) throws Exception {
        File jobDir = new File(getCacheDir(), "cbz-job-" + timestamp());
        File inputDir = new File(jobDir, "input");
        File outputDir = new File(jobDir, "output");
        if ((!inputDir.exists() && !inputDir.mkdirs())
                || (!outputDir.exists() && !outputDir.mkdirs())) {
            throw new IOException("Could not create CBZ work directory");
        }

        SoraniTranslator translator = null;
        try {
            setStatus("دەرهێنانی وێنەکانی ناو CBZ…");
            List<File> pages = extractCbzImages(cbz, inputDir);
            if (pages.isEmpty()) {
                throw new IOException("No images were found inside this CBZ");
            }

            translator = createTranslator(offlineMode);
            StringBuilder allPairs = new StringBuilder();
            Bitmap firstRendered = null;
            int totalSegments = 0;
            int totalRendered = 0;
            int totalSkipped = 0;
            int partialPages = 0;

            for (int i = 0; i < pages.size(); i++) {
                final int pageNumber = i + 1;
                final int pageTotal = pages.size();
                File pageFile = pages.get(i);
                setStatus("لاپەڕە " + pageNumber + "/" + pageTotal + ": خوێندنەوە…");

                Bitmap pageBitmap = decodeBitmap(pageFile);
                if (pageBitmap == null) {
                    File keptPage = new File(outputDir, String.format(Locale.ROOT,
                            "%04d%s", pageNumber,
                            extensionWithDot(pageFile.getName(), ".jpg")));
                    copyFile(pageFile, keptPage);
                    if (allPairs.length() > 0) {
                        allPairs.append("\n\n");
                    }
                    allPairs.append("=== لاپەڕە ").append(pageNumber)
                            .append('/').append(pageTotal).append(" ===\n")
                            .append("(ئەم وێنەیە نەخوێنرایەوە؛ وەک خۆی هێڵدرايەوە.)");
                    continue;
                }

                List<Segment> segments;
                try {
                    segments = ManhwaRenderer.recognize(
                            pageBitmap,
                            sourceLanguage,
                            new ManhwaRenderer.Progress() {
                                @Override
                                public void onProgress(String message) {
                                    setStatus("لاپەڕە " + pageNumber + "/"
                                            + pageTotal + ": " + message);
                                }
                            });
                    for (int s = 0; s < segments.size(); s++) {
                        Segment segment = segments.get(s);
                        setStatus("لاپەڕە " + pageNumber + "/" + pageTotal
                                + (offlineMode ? ": 🤖 وەرگێڕانی ئۆفلاین " : ": وەرگێڕان ")
                                + (s + 1) + "/" + segments.size() + "…");
                        segment.ckb = translator.translate(segment.text, sourceLanguage);
                    }
                } catch (Exception e) {
                    pageBitmap.recycle();
                    throw e;
                }

                ManhwaRenderer.RenderResult renderResult =
                        ManhwaRenderer.render(pageBitmap, segments);
                Bitmap rendered = renderResult.bitmap;
                File outPage = new File(outputDir,
                        String.format(Locale.ROOT, "%04d.jpg", pageNumber));
                writeJpeg(outPage, rendered);
                if (firstRendered == null) {
                    firstRendered = rendered;
                } else {
                    rendered.recycle();
                }
                pageBitmap.recycle();

                totalSegments += segments.size();
                totalRendered += renderResult.rendered;
                int pageSkipped = renderResult.skipped();
                totalSkipped += pageSkipped;
                if (!segments.isEmpty() && pageSkipped > 0) {
                    partialPages++;
                }
                if (allPairs.length() > 0) {
                    allPairs.append("\n\n");
                }
                allPairs.append("=== لاپەڕە ").append(pageNumber)
                        .append('/').append(pageTotal).append(" ===\n");
                allPairs.append(segments.isEmpty()
                        ? "(هیچ دەقێکی ڕوون نەدۆزرایەوە)"
                        : buildPairs(segments));
                if (!segments.isEmpty() && pageSkipped > 0) {
                    allPairs.append("\n[⚠ ").append(pageSkipped)
                            .append(" دەق لەسەر ئەم لاپەڕەیە دانەندرا و بە زمانی ڕەسەن مایەوە؛ ")
                            .append("وەرگێڕانەکەی لە سەرەوە بە «لەسەر وێنەکە دانەندرا» دیاری کراوە.]");
                }
            }

            String base = sanitizeFileName(baseNameWithoutExtension(displayName));
            if (base.isEmpty()) {
                base = "chapter";
            }
            File outCbz = new File(getCacheDir(), base + ".ckb.cbz");
            zipDirectory(outputDir, outCbz);

            String pairs = allPairs.toString().trim();
            File textFile = writeTextToCache(pairs, base + ".ckb.txt");
            Uri downloadCbzUri = saveFileToDownloads(outCbz, "application/zip");
            saveFileToDownloads(textFile, "text/plain");
            copyToDownloadsLibrary(outCbz);
            copyToDownloadsLibrary(textFile);

            final Bitmap preview = firstRendered;
            String pairsForUi = pairs.length() > 60000
                    ? pairs.substring(0, 60000)
                            + "\n\n… (دەقی تەواو لە فایلی TXTی «داگرتنەکان»دایە)"
                    : pairs;
            resultBitmap = preview;
            resultFile = outCbz;
            resultTextFile = textFile;
            pairsText = pairs;
            runOnUiThread(() -> {
                if (preview != null) {
                    previewImage.setImageBitmap(preview);
                }
                resultText.setText(pairsForUi);
                updateButtons(false);
                refreshDownloadsList();
            });

            String savedNote = downloadCbzUri != null
                    ? " CBZەکە لە Download/KurdMan پاشەکەوت کرا."
                    : " CBZەکە لە بەشی «داگرتنەکان» پاشەکەوت کرا.";
            if (totalSegments == 0) {
                setStatus("⚠ CBZەکە دروست کرا، بەڵام هیچ دەقێکی ڕوون نەدۆزرایەوە." + savedNote);
            } else if (totalSkipped > 0) {
                setStatus("⚠ CBZ ئامادەیە: " + pages.size() + " لاپەڕە، "
                        + totalRendered + "/" + totalSegments
                        + " دەق لەسەر وێنەکان داندرا؛ " + totalSkipped
                        + " دەق لە " + partialPages
                        + " لاپەڕەدا تەنیا لە TXTەکەدایە." + savedNote);
            } else {
                setStatus("✅ CBZ ئامادەیە: " + pages.size() + " لاپەڕە، "
                        + totalRendered + "/" + totalSegments
                        + " دەق لەسەر وێنەکان داندرا." + savedNote);
            }
        } finally {
            closeTranslator(translator);
            deleteRecursive(inputDir);
            deleteRecursive(outputDir);
        }
    }

    private List<File> extractCbzImages(File cbz, File destDir) throws IOException {
        List<ExtractedPage> pages = new ArrayList<>();
        ZipFile zip = new ZipFile(cbz);
        try {
            List<ZipEntry> entries = listCbzImageEntries(zip);
            if (entries.size() > 300) {
                throw new IOException("This CBZ has too many pages (max 300)");
            }
            long totalBytes = 0;
            int index = 0;
            for (ZipEntry entry : entries) {
                index++;
                File out = new File(destDir, String.format(Locale.ROOT, "%04d%s",
                        index, extensionWithDot(entry.getName(), ".jpg")));
                totalBytes += copyZipEntry(zip, entry, out);
                if (totalBytes > 1024L * 1024L * 1024L) {
                    throw new IOException("This CBZ is too large to extract on this phone");
                }
                pages.add(new ExtractedPage(entry.getName(), out));
            }
        } finally {
            zip.close();
        }
        List<File> files = new ArrayList<>();
        for (ExtractedPage page : pages) {
            files.add(page.file);
        }
        return files;
    }

    private List<ZipEntry> listCbzImageEntries(ZipFile zip) {
        List<ZipEntry> entries = new ArrayList<>();
        Enumeration<? extends ZipEntry> all = zip.entries();
        while (all.hasMoreElements()) {
            ZipEntry entry = all.nextElement();
            if (entry.isDirectory()) {
                continue;
            }
            String lower = entry.getName().toLowerCase(Locale.ROOT);
            if (lower.contains("__macosx") || lower.endsWith(".ds_store")) {
                continue;
            }
            if (isImageEntry(lower)) {
                entries.add(entry);
            }
        }
        Collections.sort(entries, new Comparator<ZipEntry>() {
            @Override
            public int compare(ZipEntry a, ZipEntry b) {
                return naturalCompare(a.getName(), b.getName());
            }
        });
        return entries;
    }

    private long copyZipEntry(ZipFile zip, ZipEntry entry, File dest) throws IOException {
        InputStream in = zip.getInputStream(entry);
        if (in == null) {
            throw new IOException("Could not read CBZ entry: " + entry.getName());
        }
        OutputStream out = new FileOutputStream(dest);
        try {
            return copyStream(in, out, 150L * 1024L * 1024L);
        } finally {
            out.close();
            in.close();
        }
    }

    private void zipDirectory(File dir, File zipFile) throws IOException {
        File[] children = dir.listFiles();
        if (children == null || children.length == 0) {
            throw new IOException("No translated pages to pack into CBZ");
        }
        List<File> files = new ArrayList<>();
        for (File child : children) {
            if (child.isFile()) {
                files.add(child);
            }
        }
        Collections.sort(files, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return naturalCompare(a.getName(), b.getName());
            }
        });

        ZipOutputStream zos = new ZipOutputStream(
                new BufferedOutputStream(new FileOutputStream(zipFile)));
        zos.setLevel(1);
        try {
            byte[] buffer = new byte[64 * 1024];
            for (File file : files) {
                zos.putNextEntry(new ZipEntry(file.getName()));
                InputStream in = new BufferedInputStream(new FileInputStream(file));
                try {
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        zos.write(buffer, 0, read);
                    }
                } finally {
                    in.close();
                }
                zos.closeEntry();
            }
        } finally {
            zos.close();
        }
    }

    private int naturalCompare(String a, String b) {
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            char ca = a.charAt(i);
            char cb = b.charAt(j);
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int startA = i;
                int startB = j;
                while (i < a.length() && Character.isDigit(a.charAt(i))) {
                    i++;
                }
                while (j < b.length() && Character.isDigit(b.charAt(j))) {
                    j++;
                }
                String numA = a.substring(startA, i).replaceFirst("^0+", "");
                String numB = b.substring(startB, j).replaceFirst("^0+", "");
                if (numA.length() != numB.length()) {
                    return numA.length() < numB.length() ? -1 : 1;
                }
                int byNumber = numA.compareTo(numB);
                if (byNumber != 0) {
                    return byNumber;
                }
            } else {
                int byChar = Character.compare(
                        Character.toLowerCase(ca), Character.toLowerCase(cb));
                if (byChar != 0) {
                    return byChar;
                }
                i++;
                j++;
            }
        }
        return Integer.compare(a.length() - i, b.length() - j);
    }

    private void sendResultToTelegram() {
        final String token = tokenValue();
        final String chatId = chatValue();
        final File file = resultFile;
        final File textFile = resultTextFile;
        final String pairs = pairsText;
        String problem = settingsError(token, chatId, true);
        if (problem != null) {
            setStatus("⚠ " + problem);
            return;
        }
        if (file == null || !file.exists() || file.length() <= 0) {
            setStatus("⚠ سەرەتا وێنەیەک یان CBZێک وەربگێڕە.");
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
                        ? "✅ ئەنجامەکە نێردرا بۆ تێلێگرام (message_id=" + messageId + ")."
                        : "✅ ئەنجامەکە نێردرا بۆ تێلێگرام.";
                setStatus(doneNote);

                // The document is already delivered at this point; report a
                // text failure separately instead of marking everything failed.
                if (pairs != null && !pairs.trim().isEmpty()) {
                    try {
                        if (pairs.length() > 12000 && textFile != null && textFile.exists()) {
                            api.sendDocument(chatId, textFile,
                                    "📝 دەقەکانی ئەنجامەکە — Kurd-man");
                            setStatus(doneNote + " دەقەکانیش وەک فایل نێردران ✅");
                        } else {
                            List<String> chunks = splitText(pairs, 3300);
                            for (int i = 0; i < chunks.size(); i++) {
                                String prefix = i == 0 ? "📝 دەقەکان:\n\n" : "";
                                api.sendMessage(chatId, prefix + chunks.get(i));
                            }
                            setStatus(doneNote + " دەقەکانیش نێردران ✅");
                        }
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

    private Bitmap decodeBitmap(File file) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null;
        }

        int sample = 1;
        long pixels = (long) bounds.outWidth * (long) bounds.outHeight;
        while (pixels / ((long) sample * (long) sample) > 28_000_000L && sample < 16) {
            sample *= 2;
        }

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap decoded = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
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
        writeJpeg(file, bitmap);
        return file;
    }

    private void writeJpeg(File file, Bitmap bitmap) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Could not create output directory");
        }
        OutputStream out = new BufferedOutputStream(new FileOutputStream(file));
        try {
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)) {
                throw new IOException("Could not write result image");
            }
        } finally {
            out.close();
        }
    }

    private File writeTextToCache(String text, String fileName) throws IOException {
        File file = new File(getCacheDir(), sanitizeFileName(fileName));
        OutputStreamWriter writer = new OutputStreamWriter(
                new FileOutputStream(file), StandardCharsets.UTF_8);
        try {
            writer.write(text == null ? "" : text);
        } finally {
            writer.close();
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

    private String queryDisplayName(Uri uri) {
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(
                    uri,
                    new String[]{OpenableColumns.DISPLAY_NAME},
                    null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String name = cursor.getString(index);
                    if (name != null && !name.trim().isEmpty()) {
                        return name.trim();
                    }
                }
            }
        } catch (Exception ignored) {
            // Fall back to the URI's last segment below.
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        String segment = uri.getLastPathSegment();
        return segment == null || segment.trim().isEmpty() ? "selected-file" : segment.trim();
    }

    private boolean isCbzSelection(String displayName, String mimeType) {
        String lowerName = displayName == null
                ? "" : displayName.toLowerCase(Locale.ROOT);
        if (lowerName.endsWith(".cbz") || lowerName.endsWith(".zip")) {
            return true;
        }
        if (mimeType != null) {
            String lowerMime = mimeType.toLowerCase(Locale.ROOT);
            return lowerMime.contains("zip")
                    || lowerMime.contains("cbz")
                    || lowerMime.contains("comic");
        }
        return false;
    }

    private boolean isImageEntry(String lowerName) {
        return lowerName.endsWith(".jpg")
                || lowerName.endsWith(".jpeg")
                || lowerName.endsWith(".png")
                || lowerName.endsWith(".webp")
                || lowerName.endsWith(".gif")
                || lowerName.endsWith(".bmp");
    }

    private String extensionWithDot(String name, String fallback) {
        if (name == null) {
            return fallback;
        }
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        int dot = name.lastIndexOf('.');
        if (dot > slash && dot < name.length() - 1) {
            String ext = name.substring(dot).toLowerCase(Locale.ROOT);
            if (ext.length() <= 8) {
                return ext;
            }
        }
        return fallback;
    }

    private String baseNameWithoutExtension(String name) {
        if (name == null) {
            return "";
        }
        String clean = name.replace('\\', '/');
        int slash = clean.lastIndexOf('/');
        if (slash >= 0) {
            clean = clean.substring(slash + 1);
        }
        int dot = clean.lastIndexOf('.');
        return dot > 0 ? clean.substring(0, dot) : clean;
    }

    private String sanitizeFileName(String value) {
        if (value == null) {
            return "";
        }
        String clean = value.trim()
                .replaceAll("[\\\\/:*?\"<>|]", "_")
                .replaceAll("\\s+", "_");
        while (clean.startsWith(".")) {
            clean = clean.substring(1);
        }
        if (clean.length() <= 90) {
            return clean;
        }
        int dot = clean.lastIndexOf('.');
        if (dot > 0) {
            String ext = clean.substring(dot);
            return clean.substring(0, 90 - ext.length()) + ext;
        }
        return clean.substring(0, 90);
    }

    private long copyUriToFile(Uri uri, File dest, long maxBytes) throws IOException {
        InputStream in = getContentResolver().openInputStream(uri);
        if (in == null) {
            throw new IOException("Could not open selected file");
        }
        OutputStream out = new BufferedOutputStream(new FileOutputStream(dest));
        try {
            return copyStream(in, out, maxBytes);
        } finally {
            out.close();
            in.close();
        }
    }

    private long copyFile(File source, File dest) throws IOException {
        File parent = dest.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Could not create directory: " + parent);
        }
        InputStream in = new BufferedInputStream(new FileInputStream(source));
        OutputStream out = new BufferedOutputStream(new FileOutputStream(dest));
        try {
            return copyStream(in, out, -1);
        } finally {
            out.close();
            in.close();
        }
    }

    private long copyStream(InputStream in, OutputStream out, long maxBytes)
            throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long total = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            total += read;
            if (maxBytes > 0 && total > maxBytes) {
                throw new IOException("File is too large");
            }
            out.write(buffer, 0, read);
        }
        out.flush();
        return total;
    }

    /** Saves a copy to the public Download/KurdMan folder on Android 10+. */
    private Uri saveFileToDownloads(File source, String mimeType) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return null;
        }
        Uri uri = null;
        try {
            ContentResolver resolver = getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, source.getName());
            values.put(MediaStore.Downloads.MIME_TYPE, mimeType);
            values.put(MediaStore.Downloads.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/KurdMan");
            values.put(MediaStore.Downloads.IS_PENDING, 1);
            uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                return null;
            }
            OutputStream out = resolver.openOutputStream(uri);
            if (out == null) {
                resolver.delete(uri, null, null);
                return null;
            }
            InputStream in = new BufferedInputStream(new FileInputStream(source));
            try {
                copyStream(in, out, -1);
            } finally {
                out.close();
                in.close();
            }
            values.clear();
            values.put(MediaStore.Downloads.IS_PENDING, 0);
            resolver.update(uri, values, null, null);
            return uri;
        } catch (Exception e) {
            if (uri != null) {
                try {
                    getContentResolver().delete(uri, null, null);
                } catch (Exception ignored) {
                    // The library copy below is the fallback.
                }
            }
            return null;
        }
    }

    private File downloadsLibraryDir() {
        File base = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (base == null) {
            base = new File(getFilesDir(), Environment.DIRECTORY_DOWNLOADS);
        }
        File dir = new File(base, "KurdMan");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    private File copyToDownloadsLibrary(File source) {
        try {
            File dir = downloadsLibraryDir();
            File dest = new File(dir, uniqueFileName(dir, source.getName()));
            copyFile(source, dest);
            return dest;
        } catch (Exception e) {
            return null;
        }
    }

    private String uniqueFileName(File dir, String name) {
        String clean = sanitizeFileName(name);
        if (!new File(dir, clean).exists()) {
            return clean;
        }
        String base = baseNameWithoutExtension(clean);
        String ext = extensionWithDot(clean, "");
        for (int i = 2; i < 1000; i++) {
            String candidate = base + " (" + i + ")" + ext;
            if (!new File(dir, candidate).exists()) {
                return candidate;
            }
        }
        return base + "-" + timestamp() + ext;
    }

    private void refreshDownloadsList() {
        if (downloadsContainer == null) {
            return;
        }
        downloadsContainer.removeAllViews();
        File dir = downloadsLibraryDir();
        File[] all = dir.listFiles();
        List<File> files = new ArrayList<>();
        if (all != null) {
            for (File file : all) {
                if (!file.isFile()) {
                    continue;
                }
                String lower = file.getName().toLowerCase(Locale.ROOT);
                if (lower.endsWith(".cbz") || lower.endsWith(".zip")
                        || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                        || lower.endsWith(".png") || lower.endsWith(".txt")) {
                    files.add(file);
                }
            }
        }
        Collections.sort(files, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return Long.compare(b.lastModified(), a.lastModified());
            }
        });

        if (files.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            empty.setBackgroundResource(R.drawable.bg_download_row);
            empty.setPadding(dp(16), dp(15), dp(16), dp(15));
            empty.setText("هێشتا هیچ ئەنجامێک لە داگرتنەکاندا نییە؛ دوای یەکەم وەرگێڕان لێرە دەردەکەوێت.");
            empty.setTextColor(getColor(R.color.text_secondary));
            empty.setTextSize(13);
            downloadsContainer.addView(empty);
            return;
        }

        int shown = 0;
        for (File file : files) {
            if (shown >= 20) {
                break;
            }
            downloadsContainer.addView(createDownloadRow(file));
            shown++;
        }
        if (files.size() > shown) {
            TextView more = new TextView(this);
            more.setText("+ " + (files.size() - shown)
                    + " فایلی تر لە Download/KurdMan");
            more.setTextColor(getColor(R.color.text_secondary));
            more.setTextSize(12);
            more.setPadding(dp(4), dp(2), dp(4), 0);
            downloadsContainer.addView(more);
        }
    }

    private View createDownloadRow(final File file) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setBackgroundResource(R.drawable.bg_download_row);
        row.setElevation(dp(2));
        row.setPadding(dp(15), dp(14), dp(15), dp(14));
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.bottomMargin = dp(10);
        row.setLayoutParams(rowParams);

        TextView name = new TextView(this);
        name.setText(file.getName());
        name.setTextColor(getColor(R.color.text_primary));
        name.setTextSize(13.5f);
        name.setLineSpacing(0f, 1.15f);
        name.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                android.graphics.Typeface.BOLD));
        row.addView(name);

        TextView detail = new TextView(this);
        String when = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT)
                .format(new Date(file.lastModified()));
        detail.setText(formatMb(file.length()) + " • " + when);
        detail.setTextColor(getColor(R.color.text_secondary));
        detail.setTextSize(12);
        LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        detailParams.topMargin = dp(3);
        detail.setLayoutParams(detailParams);
        row.addView(detail);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        actionsParams.topMargin = dp(10);
        actions.setLayoutParams(actionsParams);

        Button open = createSmallButton("کردنەوە", true);
        LinearLayout.LayoutParams openParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        open.setLayoutParams(openParams);
        open.setOnClickListener(v -> openDownloadedFile(file));
        actions.addView(open);

        Button share = createSmallButton("هاوبەشکردن", false);
        LinearLayout.LayoutParams shareParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        shareParams.setMarginStart(dp(8));
        share.setLayoutParams(shareParams);
        share.setOnClickListener(v -> shareDownloadedFile(file));
        actions.addView(share);

        row.addView(actions);
        return row;
    }

    private Button createSmallButton(String text, boolean primary) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(12.5f);
        button.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                android.graphics.Typeface.BOLD));
        button.setStateListAnimator(null);
        button.setMinHeight(dp(46));
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(8), 0, dp(8), 0);
        button.setBackgroundResource(primary
                ? R.drawable.bg_button_primary
                : R.drawable.bg_button_secondary);
        button.setTextColor(getColor(primary
                ? R.color.text_on_primary
                : R.color.primary));
        return button;
    }

    private void openDownloadedFile(File file) {
        try {
            Uri uri = FileProvider.getUriForFile(
                    this, getPackageName() + ".fileprovider", file);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, mimeForFile(file));
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            setStatus("⚠ هیچ ئەپێک نەدۆزرایەوە بۆ کردنەوەی ئەم فایلە؛ «هاوبەشکردن» بەکاربهێنە.");
        } catch (Exception e) {
            setStatus("❌ " + friendlyError(e));
        }
    }

    private void shareDownloadedFile(File file) {
        try {
            Uri uri = FileProvider.getUriForFile(
                    this, getPackageName() + ".fileprovider", file);
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType(mimeForFile(file));
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, file.getName()));
        } catch (Exception e) {
            setStatus("❌ " + friendlyError(e));
        }
    }

    private void openSystemDownloads() {
        try {
            startActivity(new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS));
        } catch (Exception e) {
            setStatus("⚠ ئەپی داگرتنەکانی سیستەم نەدۆزرایەوە؛ فایلەکان لە Download/KurdManن.");
        }
    }

    private String mimeForFile(File file) {
        String lower = file.getName().toLowerCase(Locale.ROOT);
        if (lower.endsWith(".cbz")) {
            return "application/vnd.comicbook+zip";
        }
        if (lower.endsWith(".zip")) {
            return "application/zip";
        }
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".txt")) {
            return "text/plain";
        }
        return "image/jpeg";
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void deleteRecursive(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        file.delete();
    }

    private String buildPairs(List<Segment> segments) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < segments.size(); i++) {
            Segment segment = segments.get(i);
            builder.append('[').append(i + 1).append("]\n");
            builder.append(segment.text.replace('\n', ' ')).append('\n');
            builder.append("→ ").append(segment.ckb);
            if (!segment.rendered) {
                builder.append("\n[⚠ لەسەر وێنەکە دانەندرا]");
            }
            builder.append("\n\n");
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
                .putInt("source_index", sourceSpinner.getSelectedItemPosition())
                .putInt("translation_mode_index",
                        translationModeSpinner.getSelectedItemPosition())
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

    /** Maps translation/network failures to actionable Kurdish guidance. */
    private String friendlyTranslationError(Exception error) {
        String raw = friendlyError(error);
        String lower = errorChainText(error);
        if (lower.contains("offline_") || lower.contains("offline ai")
                || lower.contains("onnx") || lower.contains("ortexception")
                || lower.contains("checksum mismatch")
                || lower.contains("not enough free storage")) {
            return friendlyOfflineError(error);
        }
        if (lower.contains("unable to resolve host")
                || lower.contains("no address associated with hostname")
                || lower.contains("unknownhostexception")) {
            return "پەیوەندی بە سێرفەرەکانی وەرگێڕانەوە نەبەسترا (کێشەی DNS). "
                    + "هێڵەکە بگۆڕە (Wi-Fi ↔ داتای مۆبایل)؛ ئەگەر VPN یان Private DNS "
                    + "بەکاردەهێنیت، جارێک بیکوژێنەوە یان Private DNS بکە بە Automatic، "
                    + "پاشان دووبارە هەوڵبدە. (" + raw + ")";
        }
        if (lower.contains("timed out") || lower.contains("timeout")
                || lower.contains("failed to connect")
                || lower.contains("connection reset")
                || lower.contains("connection refused")
                || lower.contains("network is unreachable")
                || lower.contains("software caused connection abort")) {
            return "پەیوەندییەکە بە سێرفەری وەرگێڕانەوە پچڕا یان کاتەکەی بەسەرچوو؛ "
                    + "ئینتەرنێتەکە بپشکنە و دووبارە هەوڵبدە. (" + raw + ")";
        }
        if (lower.contains("translation http 403")
                || lower.contains("translation http 429")
                || lower.contains("quota")) {
            return "سێرفەرەکانی وەرگێڕانی خۆڕایی بۆ ماوەیەک داواکارییەکەیان ڕەتکردەوە؛ "
                    + "چەند خولەکێک بوەستە و دووبارە هەوڵبدە. (" + raw + ")";
        }
        return raw;
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
        refreshDownloadsButton.setEnabled(enabled);
        openDownloadsButton.setEnabled(enabled);
        if (sourceSpinner != null) {
            sourceSpinner.setEnabled(enabled);
        }
        if (translationModeSpinner != null) {
            translationModeSpinner.setEnabled(enabled);
        }
        boolean modelReady = offlineModelManager != null
                && offlineModelManager.isModelReady();
        boolean hasModelData = offlineModelManager != null
                && offlineModelManager.downloadedBytes() > 0;
        if (downloadOfflineButton != null) {
            downloadOfflineButton.setEnabled(enabled && !modelReady);
        }
        if (deleteOfflineButton != null) {
            deleteOfflineButton.setEnabled(enabled && hasModelData);
        }
        sendResultButton.setEnabled(enabled && resultFile != null && resultFile.exists());
    }
}
