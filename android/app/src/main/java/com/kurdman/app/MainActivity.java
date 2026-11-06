package com.kurdman.app;

import android.Manifest;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Parcelable;
import android.os.PowerManager;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.text.method.HideReturnsTransformationMethod;
import android.text.method.PasswordTransformationMethod;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;
import androidx.core.content.ContextCompat;
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
    private String[] sourceLabels() {
        return new String[]{
                getString(R.string.ks_0689), getString(R.string.ks_0690),
                getString(R.string.ks_0691), getString(R.string.ks_0692),
                getString(R.string.ks_0693)};
    }
    private static final String[] SOURCE_CODES = TranslationLanguages.SOURCES;
    private String[] translationModeLabels() {
        return new String[]{
                getString(R.string.ks_0694), getString(R.string.ks_0695)};
    }

    /** Target languages (autonym labels; codes are Google/ML Kit tags). */
    private static final String[] TARGET_CODES = TranslationLanguages.TARGETS;
    private static final String[] TARGET_LABELS = TranslationLanguages.LABELS;

    private String[] backgroundModeLabels() {
        return new String[]{
                getString(R.string.ks_0205), getString(R.string.ks_0439),
                getString(R.string.ks_0176), getString(R.string.ks_0688)};
    }
    private static final String PREF_GLOBAL_STYLE = "global_text_style_json";
    private static final String PREF_ACCURATE_OCR = "accurate_ocr_v170";
    private static final String PREF_UNLIMITED_OCR_ENABLED = "unlimited_ocr_enabled_v180";
    private static final String PREF_UNLIMITED_OCR_URL = "unlimited_ocr_url_v180";
    private static final String PREF_UNLIMITED_OCR_KEY = "unlimited_ocr_key_v180";
    private static final String PREF_UNLIMITED_OCR_MODEL = "unlimited_ocr_model_v180";
    private static final String PREF_CREATE_PDF = "create_pdf_when_finished_v171";
    private static final String PREF_CLEAN_DEFAULT = "clean_erase_default_v174";
    private static final String PREF_KEEP_SCREEN = "keep_screen_on_during_jobs";
    private static final String PREF_MAIN_PAGE = "main_page_index_v161";
    private static final int PAGE_TRANSLATE = 0;
    private static final int PAGE_STYLE = 1;
    private static final int PAGE_RESULTS = 2;
    private static final int PAGE_SETTINGS = 3;
    private static final int PAGE_REPORTS = 4;

    // Process-wide so a recreated activity cannot accidentally terminate a
    // job that the keep-alive foreground service is protecting.
    private static final ExecutorService executor =
            Executors.newSingleThreadExecutor();
    private static volatile SoraniTranslator activeTranslator;
    private static volatile PaddleOcrClient activePaddleOcrClient;

    private SharedPreferences prefs;
    private EditText tokenInput;
    private EditText chatInput;
    private EditText messageInput;
    private Spinner sourceSpinner;
    private Spinner targetSpinner;
    private Spinner translationModeSpinner;
    private ComicTranslateControls comicControls;
    private TextView offlineModelStatus;
    private Button downloadOfflineButton;
    private Button deleteOfflineButton;
    private volatile boolean offlinePackReady;
    private volatile String offlinePackPair = "";
    private OfflineModelManager offlineModelManager;
    private TextView offlineTileTitle;
    private TextView offlineTileDesc;
    private TextView offlineTileFooter;
    private CheckBox accurateOcrCheck;
    private CheckBox createPdfCheck;
    private CheckBox cleanEraseCheck;
    private TextView paddleOcrStatus;
    private Button downloadPaddleOcrButton;
    private Button deletePaddleOcrButton;
    private PaddleOcrModelManager paddleOcrModelManager;
    private CheckBox unlimitedOcrCheck;
    private EditText unlimitedOcrUrlInput;
    private EditText unlimitedOcrKeyInput;
    private EditText unlimitedOcrModelInput;
    private Button pasteTokenButton;
    private Button pasteChatButton;
    private CheckBox showTokenCheck;
    private Button saveButton;
    private Button verifyButton;
    private Button sendTextButton;
    private Button pickButton;
    private Button translateButton;
    private Button editResultButton;
    private Button sendResultButton;
    private Button stopJobButton;
    private Button keepAliveToggleButton;
    private Button batterySettingsButton;
    private CheckBox keepScreenCheck;
    private TextView keepAliveStatus;
    private Spinner fontSpinner;
    private Spinner stylePresetSpinner;
    private Spinner backgroundModeSpinner;
    private TextView stylePreview;
    private TextView styleSizeLabel;
    private TextView styleWeightLabel;
    private SeekBar styleSizeSeek;
    private SeekBar styleWeightSeek;
    private Button importFontButton;
    private Button textColorButton;
    private Button backgroundColorButton;
    private CheckBox styleAutoColorCheck;
    private CheckBox styleOutlineCheck;
    private CheckBox styleShadowCheck;
    private CheckBox styleItalicCheck;
    private TextStyleSettings globalTextStyle = new TextStyleSettings();
    private List<FontCatalog.FontOption> fontOptions = new ArrayList<>();
    private boolean bindingStyleControls = false;
    private boolean globalPresetTouched = false;
    private boolean currentBusy = false;
    private Button refreshDownloadsButton;
    private Button openDownloadsButton;
    private LinearLayout downloadsContainer;
    private ProgressBar progress;
    private TextView statusText;
    private TextView resultText;
    private ImageView previewImage;
    private ScrollView mainScrollView;
    private View pageTranslate;
    private View pageStyle;
    private View pageResults;
    private View pageSettings;
    private View pageReports;
    private Button navTranslateButton;
    private Button navStyleButton;
    private Button navResultsButton;
    private Button navSettingsButton;
    private Button navReportsButton;
    private TextView reportSummaryText;
    private TextView reportText;
    private EditText reportNoteInput;
    private Button addReportNoteButton;
    private Button copyReportButton;
    private Button shareReportButton;
    private Button refreshReportButton;
    private Button clearReportButton;
    private Button openStudioFromStyleButton;
    private int currentMainPage = PAGE_TRANSLATE;

    private ActivityResultLauncher<String[]> pickLauncher;
    private ActivityResultLauncher<PickVisualMediaRequest> galleryLauncher;
    private ActivityResultLauncher<Intent> galleryFallbackLauncher;
    private ActivityResultLauncher<Void> allFilesLauncher;
    private ActivityResultLauncher<Intent> filesFallbackLauncher;
    private ActivityResultLauncher<String[]> importFontLauncher;
    private ActivityResultLauncher<String> notificationPermissionLauncher;
    private ActivityResultLauncher<Intent> editorLauncher;
    private Bitmap originalBitmap;
    private Bitmap resultBitmap;
    private File resultFile;
    private File resultTextFile;
    private String pairsText = "";
    private File selectedImageFile;
    private File selectedCbzFile;
    private String selectedName = "";
    private int selectedCbzPages = 0;
    /** Files/names for a multi-image selection, naturally page-sorted. */
    private final List<File> selectedBatchFiles = new ArrayList<>();
    private final List<String> selectedBatchNames = new ArrayList<>();
    private String selectedBatchLabel = "";
    private String selectedBatchBase = "";

    private static class ExtractedPage {
        final String entryName;
        final File file;

        ExtractedPage(String entryName, File file) {
            this.entryName = entryName;
            this.file = file;
        }
    }

    private static class PickedInput {
        final Uri uri;
        final String displayName;
        final String mimeType;
        final boolean cbz;

        PickedInput(Uri uri, String displayName, String mimeType, boolean cbz) {
            this.uri = uri;
            this.displayName = displayName;
            this.mimeType = mimeType;
            this.cbz = cbz;
        }
    }

    @Override
    protected void attachBaseContext(android.content.Context base) {
        if (!BuildConfig.PUBLIC_EDITION) {
            // The personal edition is pinned to Sorani regardless of
            // the device language; Koorix uses the in-app picker.
            android.content.res.Configuration config =
                    new android.content.res.Configuration(
                            base.getResources().getConfiguration());
            java.util.Locale sorani = new java.util.Locale("ckb");
            config.setLocale(sorani);
            config.setLayoutDirection(sorani);
            base = base.createConfigurationContext(config);
        }
        super.attachBaseContext(base);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (BuildConfig.PUBLIC_EDITION
                && AppCompatDelegate.getApplicationLocales().isEmpty()) {
            // First launch: default the UI to Sorani regardless of the
            // device language (users thought Kurdish was "removed"
            // when the app followed an English/Arabic phone locale).
            // The hero picker can switch to English/Arabic any time.
            AppCompatDelegate.setApplicationLocales(
                    LocaleListCompat.forLanguageTags("ckb"));
        }
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        tokenInput = findViewById(R.id.tokenInput);
        chatInput = findViewById(R.id.chatInput);
        pasteTokenButton = findViewById(R.id.pasteTokenButton);
        pasteChatButton = findViewById(R.id.pasteChatButton);
        showTokenCheck = findViewById(R.id.showTokenCheck);
        messageInput = findViewById(R.id.messageInput);
        sourceSpinner = findViewById(R.id.sourceSpinner);
        targetSpinner = findViewById(R.id.targetSpinner);
        translationModeSpinner = findViewById(R.id.translationModeSpinner);
        comicControls = new ComicTranslateControls(this, findViewById(android.R.id.content), savedInstanceState);
        findViewById(R.id.resultQualityButton).setOnClickListener(v -> openComicQuality());
        offlineModelStatus = findViewById(R.id.offlineModelStatus);
        downloadOfflineButton = findViewById(R.id.downloadOfflineButton);
        deleteOfflineButton = findViewById(R.id.deleteOfflineButton);
        offlineTileTitle = findViewById(R.id.offlineTileTitle);
        offlineTileDesc = findViewById(R.id.offlineTileDesc);
        offlineTileFooter = findViewById(R.id.offlineTileFooter);
        offlineModelManager = new OfflineModelManager(this);
        accurateOcrCheck = findViewById(R.id.accurateOcrCheck);
        createPdfCheck = findViewById(R.id.createPdfCheck);
        cleanEraseCheck = findViewById(R.id.cleanEraseCheck);
        paddleOcrStatus = findViewById(R.id.paddleOcrStatus);
        downloadPaddleOcrButton = findViewById(R.id.downloadPaddleOcrButton);
        deletePaddleOcrButton = findViewById(R.id.deletePaddleOcrButton);
        paddleOcrModelManager = new PaddleOcrModelManager(this);
        unlimitedOcrCheck = findViewById(R.id.unlimitedOcrCheck);
        unlimitedOcrUrlInput = findViewById(R.id.unlimitedOcrUrlInput);
        unlimitedOcrKeyInput = findViewById(R.id.unlimitedOcrKeyInput);
        unlimitedOcrModelInput = findViewById(R.id.unlimitedOcrModelInput);
        saveButton = findViewById(R.id.saveButton);
        verifyButton = findViewById(R.id.verifyButton);
        sendTextButton = findViewById(R.id.sendTextButton);
        pickButton = findViewById(R.id.pickButton);
        translateButton = findViewById(R.id.translateButton);
        editResultButton = findViewById(R.id.editResultButton);
        sendResultButton = findViewById(R.id.sendResultButton);
        stopJobButton = findViewById(R.id.stopJobButton);
        keepAliveToggleButton = findViewById(R.id.keepAliveToggleButton);
        batterySettingsButton = findViewById(R.id.batterySettingsButton);
        keepScreenCheck = findViewById(R.id.keepScreenCheck);
        keepAliveStatus = findViewById(R.id.keepAliveStatus);
        fontSpinner = findViewById(R.id.fontSpinner);
        stylePresetSpinner = findViewById(R.id.stylePresetSpinner);
        backgroundModeSpinner = findViewById(R.id.backgroundModeSpinner);
        stylePreview = findViewById(R.id.stylePreview);
        styleSizeLabel = findViewById(R.id.styleSizeLabel);
        styleWeightLabel = findViewById(R.id.styleWeightLabel);
        styleSizeSeek = findViewById(R.id.styleSizeSeek);
        styleWeightSeek = findViewById(R.id.styleWeightSeek);
        importFontButton = findViewById(R.id.importFontButton);
        textColorButton = findViewById(R.id.textColorButton);
        backgroundColorButton = findViewById(R.id.backgroundColorButton);
        styleAutoColorCheck = findViewById(R.id.styleAutoColorCheck);
        styleOutlineCheck = findViewById(R.id.styleOutlineCheck);
        styleShadowCheck = findViewById(R.id.styleShadowCheck);
        styleItalicCheck = findViewById(R.id.styleItalicCheck);
        refreshDownloadsButton = findViewById(R.id.refreshDownloadsButton);
        openDownloadsButton = findViewById(R.id.openDownloadsButton);
        downloadsContainer = findViewById(R.id.downloadsContainer);
        progress = findViewById(R.id.progress);
        statusText = findViewById(R.id.statusText);
        resultText = findViewById(R.id.resultText);
        previewImage = findViewById(R.id.previewImage);
        mainScrollView = findViewById(R.id.mainScrollView);
        pageTranslate = findViewById(R.id.pageTranslate);
        pageStyle = findViewById(R.id.pageStyle);
        pageResults = findViewById(R.id.pageResults);
        pageSettings = findViewById(R.id.pageSettings);
        pageReports = findViewById(R.id.pageReports);
        navTranslateButton = findViewById(R.id.navTranslateButton);
        navStyleButton = findViewById(R.id.navStyleButton);
        navResultsButton = findViewById(R.id.navResultsButton);
        navSettingsButton = findViewById(R.id.navSettingsButton);
        navReportsButton = findViewById(R.id.navReportsButton);
        reportSummaryText = findViewById(R.id.reportSummaryText);
        reportText = findViewById(R.id.reportText);
        reportNoteInput = findViewById(R.id.reportNoteInput);
        addReportNoteButton = findViewById(R.id.addReportNoteButton);
        copyReportButton = findViewById(R.id.copyReportButton);
        shareReportButton = findViewById(R.id.shareReportButton);
        refreshReportButton = findViewById(R.id.refreshReportButton);
        clearReportButton = findViewById(R.id.clearReportButton);
        openStudioFromStyleButton = findViewById(R.id.openStudioFromStyleButton);

        // The token stays in this app's private storage on this phone only.
        tokenInput.setText(prefs.getString("token", ""));
        chatInput.setText(prefs.getString("chat_id", ""));
        tokenInput.setTransformationMethod(PasswordTransformationMethod.getInstance());

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this,
                R.layout.spinner_item,
                sourceLabels());
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
                refreshOfflineModelUi();
                refreshComicSkillUi();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // Keep the last saved source language.
            }
        });

        ArrayAdapter<String> targetAdapter = new ArrayAdapter<>(
                this,
                R.layout.spinner_item,
                TARGET_LABELS);
        targetAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        targetSpinner.setAdapter(targetAdapter);
        int savedTargetIndex = prefs.getInt("target_index", 0);
        if (savedTargetIndex >= 0 && savedTargetIndex < TARGET_CODES.length) {
            targetSpinner.setSelection(savedTargetIndex);
        }
        targetSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                prefs.edit().putInt("target_index", position).apply();
                refreshOfflineModelUi();
                updateHeroTagline();
                refreshComicSkillUi();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // Keep the last saved target language.
            }
        });

        ArrayAdapter<String> modeAdapter = new ArrayAdapter<>(
                this,
                R.layout.spinner_item,
                translationModeLabels());
        modeAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        translationModeSpinner.setAdapter(modeAdapter);
        int savedModeIndex = prefs.getInt("translation_mode_index", 0);
        if (savedModeIndex >= 0
                && savedModeIndex < 2) {
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
                new ActivityResultContracts.OpenMultipleDocuments(),
                new ActivityResultCallback<List<Uri>>() {
                    @Override
                    public void onActivityResult(List<Uri> uris) {
                        if (uris != null && !uris.isEmpty()) {
                            loadPickedFiles(uris);
                        }
                    }
                });

        galleryLauncher = registerForActivityResult(new ActivityResultContracts.PickMultipleVisualMedia(),this::acceptPickedInput);
        galleryFallbackLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                result -> acceptPickedInput(ComicInputPicker.selected(result.getResultCode(),result.getData())));
        allFilesLauncher = registerForActivityResult(new ComicInputPicker.Documents(),this::acceptPickedInput);
        filesFallbackLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                result -> acceptPickedInput(ComicInputPicker.selected(result.getResultCode(),result.getData())));

        importFontLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                uri -> {
                    if (uri != null) {
                        importCustomFont(uri);
                    }
                });
        notificationPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> {
                    refreshKeepAliveUi();
                    if (!granted) {
                        setStatus(getString(R.string.ks_0529)
                                + getString(R.string.ks_0182));
                    }
                });
        editorLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> handleStudioOrLibraryResult(result.getResultCode(),result.getData()));

        JobRuntime.setStopAction(() -> {
            SoraniTranslator translator = activeTranslator;
            if (translator != null) {
                translator.cancelOffline();
            }
            PaddleOcrClient paddleClient = activePaddleOcrClient;
            if (paddleClient != null) {
                paddleClient.requestCancel();
            }
        });
        keepScreenCheck.setChecked(prefs.getBoolean(PREF_KEEP_SCREEN, true));
        keepScreenCheck.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean(PREF_KEEP_SCREEN, isChecked).apply();
            updateKeepScreenFlag();
        });
        stopJobButton.setOnClickListener(v -> stopEverything());
        keepAliveToggleButton.setOnClickListener(v -> toggleManualKeepAlive());
        batterySettingsButton.setOnClickListener(v -> openBatteryOptimizationSettings());
        editResultButton.setOnClickListener(v -> openTextStudio());
        openStudioFromStyleButton.setOnClickListener(v -> openTextStudio());
        initializeStyleControls();
        initializeMainPages();

        pasteTokenButton.setOnClickListener(v -> pasteTokenFromClipboard());
        pasteChatButton.setOnClickListener(v -> pasteChatIdFromClipboard());
        showTokenCheck.setOnCheckedChangeListener(
                (buttonView, isChecked) -> setTokenVisible(isChecked));
        accurateOcrCheck.setChecked(prefs.getBoolean(PREF_ACCURATE_OCR, true));
        accurateOcrCheck.setOnCheckedChangeListener(
                (buttonView, isChecked) -> prefs.edit()
                        .putBoolean(PREF_ACCURATE_OCR, isChecked).apply());
        if (unlimitedOcrCheck != null) {
            unlimitedOcrCheck.setChecked(
                    prefs.getBoolean(PREF_UNLIMITED_OCR_ENABLED, false));
            unlimitedOcrCheck.setOnCheckedChangeListener(
                    (buttonView, isChecked) -> prefs.edit()
                            .putBoolean(PREF_UNLIMITED_OCR_ENABLED, isChecked)
                            .apply());
        }
        if (unlimitedOcrUrlInput != null) {
            unlimitedOcrUrlInput.setText(
                    prefs.getString(PREF_UNLIMITED_OCR_URL, ""));
            unlimitedOcrUrlInput.setOnFocusChangeListener((v, hasFocus) -> {
                if (!hasFocus) {
                    prefs.edit().putString(PREF_UNLIMITED_OCR_URL,
                            unlimitedOcrUrlInput.getText().toString().trim())
                            .apply();
                }
            });
        }
        if (unlimitedOcrKeyInput != null) {
            unlimitedOcrKeyInput.setText(
                    prefs.getString(PREF_UNLIMITED_OCR_KEY, ""));
            unlimitedOcrKeyInput.setOnFocusChangeListener((v, hasFocus) -> {
                if (!hasFocus) {
                    prefs.edit().putString(PREF_UNLIMITED_OCR_KEY,
                            unlimitedOcrKeyInput.getText().toString().trim())
                            .apply();
                }
            });
        }
        if (unlimitedOcrModelInput != null) {
            unlimitedOcrModelInput.setText(
                    prefs.getString(PREF_UNLIMITED_OCR_MODEL, ""));
            unlimitedOcrModelInput.setOnFocusChangeListener((v, hasFocus) -> {
                if (!hasFocus) {
                    prefs.edit().putString(PREF_UNLIMITED_OCR_MODEL,
                            unlimitedOcrModelInput.getText().toString().trim())
                            .apply();
                }
            });
        }
        createPdfCheck.setChecked(prefs.getBoolean(PREF_CREATE_PDF, false));
        createPdfCheck.setOnCheckedChangeListener(
                (buttonView, isChecked) -> {
                    prefs.edit().putBoolean(PREF_CREATE_PDF, isChecked).apply();
                    setStatus(isChecked
                            ? getString(R.string.ks_0551)
                            : getString(R.string.ks_0112));
                });
        saveButton.setOnClickListener(v -> {
            saveSettings();
            setStatus(getString(R.string.ks_0560));
        });
        verifyButton.setOnClickListener(v -> verifyBot());
        sendTextButton.setOnClickListener(v -> sendTextMessage());
        pickButton.setOnClickListener(v -> {
            if(BuildConfig.PUBLIC_EDITION)ComicInputPicker.choose(this,this::openGallery,this::openAllFiles);
            else pickLauncher.launch(new String[]{"image/*","application/zip","application/x-zip-compressed","application/x-cbz","application/vnd.comicbook+zip","application/x-comicbook","application/octet-stream"});
        });
        View inputSources=findViewById(R.id.inputSourceButtons);
        inputSources.setVisibility(BuildConfig.PUBLIC_EDITION?View.VISIBLE:View.GONE);
        findViewById(R.id.pickGalleryButton).setOnClickListener(v -> openGallery());
        findViewById(R.id.pickFilesButton).setOnClickListener(v -> openAllFiles());
        downloadOfflineButton.setOnClickListener(v -> downloadOfflineModel());
        deleteOfflineButton.setOnClickListener(v -> confirmDeleteOfflineModel());
        downloadPaddleOcrButton.setOnClickListener(v -> downloadPaddleOcrPack());
        deletePaddleOcrButton.setOnClickListener(v -> confirmDeletePaddleOcrPack());
        View libraryButton = findViewById(R.id.projectLibraryButton);
        libraryButton.setVisibility(BuildConfig.PUBLIC_EDITION ? View.VISIBLE : View.GONE);
        libraryButton.setOnClickListener(v -> { if (!JobRuntime.isRunning()) editorLauncher.launch(new Intent(this,ProjectLibraryActivity.class)); });
        translateButton.setOnClickListener(v -> translateSelected());
        sendResultButton.setOnClickListener(v -> sendResultToTelegram());
        refreshDownloadsButton.setOnClickListener(v -> refreshDownloadsList());
        openDownloadsButton.setOnClickListener(v -> openSystemDownloads());
        if (addReportNoteButton != null) {
            addReportNoteButton.setOnClickListener(v -> addReportNote());
        }
        if (copyReportButton != null) {
            copyReportButton.setOnClickListener(v -> copyReportToClipboard());
        }
        if (shareReportButton != null) {
            shareReportButton.setOnClickListener(v -> shareReport());
        }
        if (refreshReportButton != null) {
            refreshReportButton.setOnClickListener(v -> refreshReportUi());
        }
        if (clearReportButton != null) {
            clearReportButton.setOnClickListener(v -> confirmClearReport());
        }

        refreshOfflineModelUi();
        refreshPaddleOcrUi();
        refreshKeepAliveUi();
        if (JobRuntime.isRunning()) {
            currentBusy = true;
            progress.setVisibility(View.VISIBLE);
            statusText.setText(JobRuntime.status());
        }
        updateButtons(currentBusy);
        refreshDownloadsList();
        refreshReportUi();
        applyEditionUi();
        if (savedInstanceState == null) {
            handleIncomingShareIntent(getIntent());
        }
    }

    /**
     * Applies the per-edition differences between the personal KurdMan
     * build and the public Koorix build: branding, the account button,
     * hiding the Telegram / Unlimited-OCR tooling that only exists in
     * the personal build, and — Koorix only — localized main-screen
     * texts (Sorani / English / Arabic) with a language picker.
     */
    private void applyEditionUi() {
        TextView heroTitle = findViewById(R.id.heroTitle);
        TextView heroSubtitle = findViewById(R.id.heroSubtitle);
        TextView heroVersionChip = findViewById(R.id.heroVersionChip);
        TextView heroTelegramChip = findViewById(R.id.heroTelegramChip);
        View previewNotice = findViewById(R.id.previewNotice);
        previewNotice.setVisibility(BuildConfig.PREVIEW_BUILD ? View.VISIBLE : View.GONE);
        if (BuildConfig.PUBLIC_EDITION) {
            // Direction: English LTR, Sorani/Arabic RTL.
            String lang = getResources().getConfiguration()
                    .getLocales().get(0).getLanguage();
            View root = findViewById(R.id.mainRoot);
            if (root != null) {
                root.setLayoutDirection("en".equals(lang)
                        ? View.LAYOUT_DIRECTION_LTR
                        : View.LAYOUT_DIRECTION_RTL);
            }
            if (heroTitle != null) {
                heroTitle.setText(BuildConfig.PREVIEW_BUILD ? "Koorix Preview" : "Koorix");
            }
            if (heroSubtitle != null) {
                updateHeroTagline();
            }
            if (heroVersionChip != null) {
                heroVersionChip.setText(getString(R.string.kx_version_chip,
                        BuildConfig.VERSION_NAME));
            }
            if (heroTelegramChip != null) {
                heroTelegramChip.setText(R.string.edition_free_short);
            }
            TextView settingsSubtitle = findViewById(R.id.settingsSubtitle);
            if (settingsSubtitle != null) {
                settingsSubtitle.setText(R.string.kx_settings_subtitle);
            }
            if (navTranslateButton != null) {
                navTranslateButton.setText(R.string.kx_nav_translate);
            }
            Button navStyle = findViewById(R.id.navStyleButton);
            if (navStyle != null) {
                navStyle.setText(R.string.kx_nav_style);
            }
            if (navResultsButton != null) {
                navResultsButton.setText(R.string.kx_nav_results);
            }
            if (navSettingsButton != null) {
                navSettingsButton.setText(R.string.kx_nav_settings);
            }
            if (navReportsButton != null) {
                navReportsButton.setText(R.string.kx_nav_reports);
            }
            Button pick = findViewById(R.id.pickButton);
            if (pick != null) {
                pick.setText(R.string.kx_pick_btn);
            }
            Button translate = findViewById(R.id.translateButton);
            if (translate != null) {
                translate.setText(R.string.kx_translate_btn);
            }
            View languageRow = findViewById(R.id.languageRow);
            if (languageRow != null) {
                languageRow.setVisibility(View.VISIBLE);
            }
            Button langKu = findViewById(R.id.langKuButton);
            Button langEn = findViewById(R.id.langEnButton);
            Button langAr = findViewById(R.id.langArButton);
            if (langKu != null) {
                langKu.setOnClickListener(v -> setAppLanguage("ckb"));
            }
            if (langEn != null) {
                langEn.setOnClickListener(v -> setAppLanguage("en"));
            }
            if (langAr != null) {
                langAr.setOnClickListener(v -> setAppLanguage("ar"));
            }
        } else if (heroVersionChip != null) {
            heroVersionChip.setText("v" + BuildConfig.VERSION_NAME
                    + getString(R.string.ks_0009));
        }
        findViewById(R.id.freeEditionNote).setVisibility(BuildConfig.PUBLIC_EDITION?View.VISIBLE:View.GONE);
        View unlimitedSection = findViewById(R.id.unlimitedOcrSection);
        if (unlimitedSection != null && !BuildConfig.SHOW_UNLIMITED_OCR) {
            unlimitedSection.setVisibility(View.GONE);
        }
        View telegramSection = findViewById(R.id.telegramSection);
        if (telegramSection != null && !BuildConfig.SHOW_TELEGRAM) {
            telegramSection.setVisibility(View.GONE);
        }
        if (sendResultButton != null && !BuildConfig.SHOW_TELEGRAM) {
            sendResultButton.setVisibility(View.GONE);
        }
    }

    /** Koorix language picker: ckb (Sorani), en, ar. Recreated by AppCompat. */
    private void setAppLanguage(String tag) {
        LocaleListCompat locales = LocaleListCompat.forLanguageTags(tag);
        if (!locales.equals(AppCompatDelegate.getApplicationLocales())) {
            AppCompatDelegate.setApplicationLocales(locales);
        }
    }

    private final SavedJobCoordinator.Listener savedJobListener = (project,message,running) -> {
        if (isFinishing() || isDestroyed() || statusText == null) return;
        currentBusy=running; progress.setVisibility(running?View.VISIBLE:View.GONE);
        statusText.setText(message); updateButtons(running); refreshKeepAliveUi(); updateKeepScreenFlag();
        if (!running && project != null && project.completed && project.exportsComplete && project.projectDir.equals(TranslationProjectStore.projectDir(this))) showSavedProjectResult(project);
        if (!running) refreshDownloadsList();
    };
    @Override protected void onStart() { super.onStart(); if(BuildConfig.PUBLIC_EDITION)SavedJobCoordinator.addListener(savedJobListener); }
    @Override protected void onStop() { if(BuildConfig.PUBLIC_EDITION)SavedJobCoordinator.removeListener(savedJobListener); super.onStop(); }

    @Override
    protected void onResume() {
        super.onResume();
        if (KeepAliveService.isManualDesired(this)
                && !KeepAliveService.isServiceRunning()) {
            // The user asked for protection until STOP; restart it when
            // they reopen the app after Android/OEM stopped the process.
            KeepAliveService.startManual(this);
        }
        reloadGlobalTextStyleFromPrefs();
        refreshComicSkillUi();
        refreshKeepAliveUi();
        if (BuildConfig.PUBLIC_EDITION) {
            currentBusy=JobRuntime.isRunning();
            progress.setVisibility(currentBusy?View.VISIBLE:View.GONE);
        }
        if (JobRuntime.isRunning()) {
            currentBusy = true;
            progress.setVisibility(View.VISIBLE);
            statusText.setText(JobRuntime.status());
        }
        updateButtons(currentBusy);
        updateKeepScreenFlag();
        if (BuildConfig.PUBLIC_EDITION) {
            if (!JobRuntime.isRunning()) SavedJobCoordinator.maybeRecover(getApplicationContext());
            TranslationProject restored=TranslationProjectStore.load(this);
            if (!JobRuntime.isRunning() && restored!=null && restored.completed && restored.exportsComplete) showSavedProjectResult(restored);
        }
    }

    @Override
    protected void onDestroy() {
        // The executor is process-wide and deliberately not shut down here:
        // the foreground keep-alive service may be protecting a job while
        // Android recreates this activity.
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingShareIntent(intent);
    }

    /**
     * Accept one or many images (or one CBZ) shared from Gallery,
     * Files, Telegram and other apps. The extras are consumed
     * immediately so activity recreation cannot load the same share
     * twice. While another job runs, the share is reported and the
     * user is asked to send it again after stopping/finishing; silently
     * queueing it behind the protected executor could replace the
     * current selection at a surprising time.
     */
    private void handleIncomingShareIntent(Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }
        String action = intent.getAction();
        boolean single = Intent.ACTION_SEND.equals(action);
        boolean multiple = Intent.ACTION_SEND_MULTIPLE.equals(action);
        if (!single && !multiple) {
            return;
        }

        List<Uri> uris = new ArrayList<>();
        if (multiple) {
            ArrayList<Uri> shared =
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (shared != null) {
                for (Uri uri : shared) {
                    if (uri != null) {
                        uris.add(uri);
                    }
                }
            }
        } else {
            Parcelable shared = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (shared instanceof Uri) {
                uris.add((Uri) shared);
            }
        }
        if (uris.isEmpty() && intent.getClipData() != null) {
            ClipData clip = intent.getClipData();
            for (int i = 0; i < clip.getItemCount(); i++) {
                Uri uri = clip.getItemAt(i).getUri();
                if (uri != null && !uris.contains(uri)) {
                    uris.add(uri);
                }
            }
        }

        intent.removeExtra(Intent.EXTRA_STREAM);
        intent.setClipData(null);
        intent.setAction(Intent.ACTION_MAIN);
        if (uris.isEmpty()) {
            DiagnosticLog.warning(getApplicationContext(), "share-intent",
                    "Share intent had no readable stream URI; action=" + action
                            + ", type=" + intent.getType());
            setStatus(getString(R.string.ks_0527));
            return;
        }
        if (currentBusy || JobRuntime.isRunning()) {
            DiagnosticLog.warning(getApplicationContext(), "share-intent",
                    "Rejected shared selection while another job is running; count="
                            + uris.size());
            setStatus(getString(R.string.ks_0543));
            return;
        }
        DiagnosticLog.info(getApplicationContext(), "share-intent",
                "Accepted shared selection: count=" + uris.size()
                        + ", action=" + action + ", type=" + intent.getType());
        showMainPage(PAGE_TRANSLATE);
        loadPickedFiles(uris);
    }

    // ------------------------------------------------------------------
    // Main pages: one clear job per bottom-tab page
    // ------------------------------------------------------------------

    private void initializeMainPages() {
        navTranslateButton.setOnClickListener(v -> showMainPage(PAGE_TRANSLATE));
        navStyleButton.setOnClickListener(v -> showMainPage(PAGE_STYLE));
        navResultsButton.setOnClickListener(v -> showMainPage(PAGE_RESULTS));
        navSettingsButton.setOnClickListener(v -> showMainPage(PAGE_SETTINGS));
        if (navReportsButton != null) {
            navReportsButton.setOnClickListener(v -> showMainPage(PAGE_REPORTS));
        }
        int savedPage = prefs == null
                ? PAGE_TRANSLATE
                : prefs.getInt(PREF_MAIN_PAGE, PAGE_TRANSLATE);
        showMainPage(savedPage, false);
    }

    private void showMainPage(int page) {
        showMainPage(page, true);
    }

    private void showMainPage(int page, boolean save) {
        if (pageTranslate == null || pageStyle == null
                || pageResults == null || pageSettings == null
                || pageReports == null) {
            return;
        }
        if (page < PAGE_TRANSLATE || page > PAGE_REPORTS) {
            page = PAGE_TRANSLATE;
        }
        currentMainPage = page;
        pageTranslate.setVisibility(page == PAGE_TRANSLATE ? View.VISIBLE : View.GONE);
        pageStyle.setVisibility(page == PAGE_STYLE ? View.VISIBLE : View.GONE);
        pageResults.setVisibility(page == PAGE_RESULTS ? View.VISIBLE : View.GONE);
        pageSettings.setVisibility(page == PAGE_SETTINGS ? View.VISIBLE : View.GONE);
        pageReports.setVisibility(page == PAGE_REPORTS ? View.VISIBLE : View.GONE);
        updateMainNavButton(navTranslateButton, page == PAGE_TRANSLATE);
        updateMainNavButton(navStyleButton, page == PAGE_STYLE);
        updateMainNavButton(navResultsButton, page == PAGE_RESULTS);
        updateMainNavButton(navSettingsButton, page == PAGE_SETTINGS);
        updateMainNavButton(navReportsButton, page == PAGE_REPORTS);
        if (save && prefs != null) {
            prefs.edit().putInt(PREF_MAIN_PAGE, page).apply();
        }
        if (mainScrollView != null) {
            mainScrollView.post(() -> mainScrollView.scrollTo(0, 0));
        }
        if (page == PAGE_RESULTS) {
            refreshDownloadsList();
        } else if (page == PAGE_SETTINGS) {
            refreshOfflineModelUi();
            refreshPaddleOcrUi();
            refreshKeepAliveUi();
        } else if (page == PAGE_REPORTS) {
            refreshReportUi();
        }
    }

    private void updateMainNavButton(Button button, boolean selected) {
        if (button == null) {
            return;
        }
        button.setSelected(selected);
        button.setAlpha(1f);
        button.setBackgroundResource(selected
                ? R.drawable.bg_button_primary
                : R.drawable.bg_button_secondary);
        button.setTextColor(ContextCompat.getColor(
                this, selected ? R.color.white : R.color.text_primary));
    }

    // ------------------------------------------------------------------
    // Diagnostic reports: what happened, in a form the user can send
    // ------------------------------------------------------------------

    private void refreshReportUi() {
        if (reportText == null && reportSummaryText == null) {
            return;
        }
        String report = DiagnosticLog.readReport(getApplicationContext());
        if (reportText != null) {
            reportText.setText(report);
        }
        if (reportSummaryText != null) {
            reportSummaryText.setText(latestReportHeadline(report));
        }
    }

    private String latestReportHeadline(String report) {
        if (report == null || report.trim().isEmpty()
                || report.contains("No diagnostic problem has been recorded yet")) {
            return getString(R.string.ks_0573)
                    + getString(R.string.ks_0329);
        }
        String[] lines = report.split("\\n");
        int errorCount = 0;
        boolean hasWarning = false;
        String latest = null;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            boolean errorLine = line.contains("] ERROR /")
                    || line.contains("UNCAUGHT_CRASH")
                    || line.contains("STATUS_ERROR");
            if (line.contains("] WARN /") || line.contains("STATUS_WARN")) {
                hasWarning = true;
            }
            if (errorLine) {
                if (line.contains("] ERROR /")) {
                    errorCount++;
                }
                String detail = i + 1 < lines.length ? lines[i + 1].trim() : "";
                latest = detail.isEmpty() ? line.trim() : detail;
            }
        }
        if (latest != null) {
            return getString(R.string.ks_0607) + errorCount + getString(R.string.ks_0052)
                    + latest
                    + getString(R.string.ks_0000);
        }
        if (hasWarning) {
            return getString(R.string.ks_0536)
                    + getString(R.string.ks_0365);
        }
        return getString(R.string.ks_0571)
                + getString(R.string.ks_0459);
    }

    private void addReportNote() {
        String note = reportNoteInput == null
                ? "" : reportNoteInput.getText().toString().trim();
        if (note.isEmpty()) {
            setStatus(getString(R.string.ks_0521));
            return;
        }
        DiagnosticLog.note(getApplicationContext(), note);
        reportNoteInput.setText("");
        refreshReportUi();
        setStatus(getString(R.string.ks_0564));
    }

    private void copyReportToClipboard() {
        String report = reportText != null
                && reportText.getText() != null
                && reportText.getText().length() > 0
                ? reportText.getText().toString()
                : DiagnosticLog.readReport(getApplicationContext());
        ClipboardManager manager =
                (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (manager == null) {
            setStatus(getString(R.string.ks_0614));
            return;
        }
        manager.setPrimaryClip(ClipData.newPlainText("KurdMan diagnostic report", report));
        setStatus(getString(R.string.ks_0582));
    }

    private void shareReport() {
        String report = DiagnosticLog.readReport(getApplicationContext());
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_SUBJECT, "KurdMan diagnostic report");
        intent.putExtra(Intent.EXTRA_TEXT, report);
        try {
            startActivity(Intent.createChooser(intent, getString(R.string.ks_0327)));
        } catch (ActivityNotFoundException e) {
            DiagnosticLog.error(getApplicationContext(), "report-share", e,
                    "No activity can share text/plain");
            setStatus(getString(R.string.ks_0612));
        }
    }

    private void confirmClearReport() {
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.ks_0408))
                .setMessage(getString(R.string.ks_0351)
                        + getString(R.string.ks_0152))
                .setNegativeButton(getString(R.string.ks_0357), null)
                .setPositiveButton(getString(R.string.ks_0407), (dialog, which) -> {
                    DiagnosticLog.clear(getApplicationContext());
                    refreshReportUi();
                    setStatus(getString(R.string.ks_0581));
                })
                .show();
    }

    // ------------------------------------------------------------------
    // Keep-alive and global text style
    // ------------------------------------------------------------------

    private StyledTextRenderer.TypefaceResolver styledTypefaceResolver() {
        final Context appContext = getApplicationContext();
        return style -> FontCatalog.getTypeface(appContext, style);
    }

    private void initializeStyleControls() {
        globalTextStyle = TextStyleSettings.fromJsonString(
                prefs.getString(PREF_GLOBAL_STYLE, ""));
        globalTextStyle.prepareAsGlobalDefault();
        // Clean erase becomes the default once, so existing styles saved by
        // older versions stop painting the white square patch without the
        // user having to find any toggle. v1.7.4 applies this to every
        // saved mode (an old style preset could otherwise keep the solid
        // custom background); choosing another mode afterwards sticks.
        if (!prefs.getBoolean(PREF_CLEAN_DEFAULT, false)) {
            globalTextStyle.backgroundMode = TextStyleSettings.BG_CLEAN;
            prefs.edit()
                    .putBoolean(PREF_CLEAN_DEFAULT, true)
                    .putString(PREF_GLOBAL_STYLE, globalTextStyle.toJsonString())
                    .apply();
        }

        ArrayAdapter<String> presetAdapter = new ArrayAdapter<>(
                this, R.layout.spinner_item, TextStyleSettings.presetLabels(this));
        presetAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        stylePresetSpinner.setAdapter(presetAdapter);

        ArrayAdapter<String> backgroundAdapter = new ArrayAdapter<>(
                this, R.layout.spinner_item, backgroundModeLabels());
        backgroundAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        backgroundModeSpinner.setAdapter(backgroundAdapter);

        fontSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view,
                                       int position, long id) {
                if (bindingStyleControls || position < 0 || position >= fontOptions.size()) {
                    return;
                }
                String fontId = fontOptions.get(position).id;
                if (!fontId.equals(globalTextStyle.fontId)) {
                    globalTextStyle.fontId = fontId;
                    saveGlobalTextStyle();
                    updateStylePreview();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        stylePresetSpinner.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) {
                globalPresetTouched = true;
            }
            return false;
        });
        stylePresetSpinner.setOnItemSelectedListener(
                new AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(AdapterView<?> parent, View view,
                                               int position, long id) {
                        if (bindingStyleControls || !globalPresetTouched) {
                            return;
                        }
                        globalPresetTouched = false;
                        globalTextStyle.applyPreset(position);
                        saveGlobalTextStyle();
                        refreshStyleControls();
                    }

                    @Override
                    public void onNothingSelected(AdapterView<?> parent) {
                    }
                });
        backgroundModeSpinner.setOnItemSelectedListener(
                new AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(AdapterView<?> parent, View view,
                                               int position, long id) {
                        if (bindingStyleControls) {
                            return;
                        }
                        globalTextStyle.backgroundMode = position;
                        saveGlobalTextStyle();
                        syncCleanEraseCheck();
                        updateStylePreview();
                    }

                    @Override
                    public void onNothingSelected(AdapterView<?> parent) {
                    }
                });

        cleanEraseCheck.setChecked(
                globalTextStyle.backgroundMode == TextStyleSettings.BG_CLEAN);
        cleanEraseCheck.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (bindingStyleControls) {
                return;
            }
            globalTextStyle.backgroundMode = isChecked
                    ? TextStyleSettings.BG_CLEAN
                    : TextStyleSettings.BG_AUTO;
            saveGlobalTextStyle();
            refreshStyleControls();
            setStatus(isChecked
                    ? getString(R.string.ks_0567)
                    : getString(R.string.ks_0260));
        });

        styleSizeSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser || bindingStyleControls) {
                    return;
                }
                globalTextStyle.sizeScale = (progress + 50) / 100f;
                styleSizeLabel.setText(getString(R.string.ks_0295) + (progress + 50) + "%");
                updateStylePreview();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                saveGlobalTextStyle();
            }
        });
        styleWeightSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser || bindingStyleControls) {
                    return;
                }
                globalTextStyle.fontWeight = 100 + progress * 50;
                styleWeightLabel.setText(
                        getString(R.string.ks_0140) + globalTextStyle.fontWeight);
                updateStylePreview();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                saveGlobalTextStyle();
            }
        });

        importFontButton.setOnClickListener(v -> importFontLauncher.launch(
                new String[]{"font/*", "application/x-font-ttf",
                        "application/octet-stream"}));
        textColorButton.setOnClickListener(v -> ColorPicker.show(
                this, getString(R.string.ks_0440), globalTextStyle.textColor, color -> {
                    globalTextStyle.textColor = color;
                    globalTextStyle.autoTextColor = false;
                    saveGlobalTextStyle();
                    refreshStyleControls();
                }));
        backgroundColorButton.setOnClickListener(v -> ColorPicker.show(
                this, getString(R.string.ks_0446), globalTextStyle.backgroundColor, color -> {
                    globalTextStyle.backgroundColor = color;
                    globalTextStyle.backgroundMode = TextStyleSettings.BG_CUSTOM;
                    saveGlobalTextStyle();
                    refreshStyleControls();
                }));
        styleAutoColorCheck.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (bindingStyleControls) {
                return;
            }
            globalTextStyle.autoTextColor = isChecked;
            saveGlobalTextStyle();
            updateStylePreview();
        });
        styleOutlineCheck.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (bindingStyleControls) {
                return;
            }
            globalTextStyle.outline = isChecked;
            if (isChecked && globalTextStyle.outlineWidthRatio <= 0f) {
                globalTextStyle.outlineWidthRatio = 0.12f;
            }
            saveGlobalTextStyle();
            updateStylePreview();
        });
        styleShadowCheck.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (bindingStyleControls) {
                return;
            }
            globalTextStyle.shadow = isChecked;
            saveGlobalTextStyle();
            updateStylePreview();
        });
        styleItalicCheck.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (bindingStyleControls) {
                return;
            }
            globalTextStyle.italic = isChecked;
            saveGlobalTextStyle();
            updateStylePreview();
        });

        refreshFontSpinnerOptions();
        refreshStyleControls();
    }

    private void reloadGlobalTextStyleFromPrefs() {
        if (prefs == null || fontSpinner == null) {
            return;
        }
        globalTextStyle = TextStyleSettings.fromJsonString(
                prefs.getString(PREF_GLOBAL_STYLE, ""));
        globalTextStyle.prepareAsGlobalDefault();
        refreshFontSpinnerOptions();
        refreshStyleControls();
    }

    private void refreshFontSpinnerOptions() {
        fontOptions = FontCatalog.getOptions(this);
        int selectedFont = FontCatalog.indexOfId(fontOptions, globalTextStyle.fontId);
        if (!fontOptions.isEmpty()
                && !fontOptions.get(selectedFont).id.equals(globalTextStyle.fontId)) {
            // A previously selected custom font may have been deleted in
            // the editor; fall back instead of silently using system text.
            globalTextStyle.fontId = fontOptions.get(selectedFont).id;
            saveGlobalTextStyle();
        }
        bindingStyleControls = true;
        fontSpinner.setAdapter(new FontSpinnerAdapter(this, fontOptions));
        fontSpinner.setSelection(selectedFont, false);
        stylePreview.postDelayed(() -> bindingStyleControls = false, 450L);
    }

    private void syncCleanEraseCheck() {
        if (cleanEraseCheck == null) {
            return;
        }
        boolean target = globalTextStyle.backgroundMode
                == TextStyleSettings.BG_CLEAN;
        if (cleanEraseCheck.isChecked() != target) {
            boolean wasBinding = bindingStyleControls;
            bindingStyleControls = true;
            cleanEraseCheck.setChecked(target);
            bindingStyleControls = wasBinding;
        }
    }

    private void refreshStyleControls() {
        if (stylePreview == null) {
            return;
        }
        bindingStyleControls = true;
        globalTextStyle.sanitize();
        backgroundModeSpinner.setSelection(globalTextStyle.backgroundMode, false);
        syncCleanEraseCheck();
        styleSizeSeek.setProgress(Math.max(0, Math.min(170,
                Math.round(globalTextStyle.sizeScale * 100f) - 50)));
        styleWeightSeek.setProgress(Math.max(0, Math.min(16,
                (globalTextStyle.fontWeight - 100) / 50)));
        styleSizeLabel.setText(getString(R.string.ks_0295)
                + Math.round(globalTextStyle.sizeScale * 100f) + "%");
        styleWeightLabel.setText(getString(R.string.ks_0140) + globalTextStyle.fontWeight);
        styleAutoColorCheck.setChecked(globalTextStyle.autoTextColor);
        styleOutlineCheck.setChecked(globalTextStyle.outline);
        styleShadowCheck.setChecked(globalTextStyle.shadow);
        styleItalicCheck.setChecked(globalTextStyle.italic);
        textColorButton.setEnabled(!globalTextStyle.autoTextColor);
        textColorButton.setText(getString(R.string.ks_0089)
                + TextStyleSettings.hex(globalTextStyle.textColor));
        backgroundColorButton.setText(getString(R.string.ks_0492)
                + TextStyleSettings.hex(globalTextStyle.backgroundColor));
        updateStylePreview();
        stylePreview.postDelayed(() -> bindingStyleControls = false, 450L);
    }

    private void updateStylePreview() {
        if (stylePreview == null) {
            return;
        }
        globalTextStyle.sanitize();
        Typeface typeface = FontCatalog.getTypeface(this, globalTextStyle);
        stylePreview.setTypeface(typeface);
        stylePreview.setTextSize(Math.max(12f, Math.min(30f,
                17f * globalTextStyle.sizeScale)));
        stylePreview.setTextColor(TextStyleSettings.withOpacity(
                globalTextStyle.autoTextColor ? Color.BLACK : globalTextStyle.textColor,
                globalTextStyle.textOpacity));
        if (globalTextStyle.shadow) {
            stylePreview.setShadowLayer(5f, 2f, 3f,
                    TextStyleSettings.withOpacity(globalTextStyle.shadowColor, 170));
        } else {
            stylePreview.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT);
        }
        GradientDrawable background = new GradientDrawable();
        int previewBackground = globalTextStyle.backgroundMode == TextStyleSettings.BG_CUSTOM
                ? globalTextStyle.backgroundColor
                : (globalTextStyle.backgroundMode == TextStyleSettings.BG_NONE
                        ? Color.rgb(15, 23, 42) : Color.WHITE);
        background.setColor(TextStyleSettings.withOpacity(
                previewBackground, globalTextStyle.backgroundOpacity));
        background.setCornerRadius(18f * getResources().getDisplayMetrics().density);
        if (globalTextStyle.backgroundBorder) {
            background.setStroke(Math.max(1,
                            Math.round(globalTextStyle.backgroundBorderWidth)),
                    globalTextStyle.backgroundBorderColor);
        }
        stylePreview.setBackground(background);
    }

    private void saveGlobalTextStyle() {
        globalTextStyle.prepareAsGlobalDefault();
        prefs.edit()
                .putString(PREF_GLOBAL_STYLE, globalTextStyle.toJsonString())
                .apply();
    }

    private void importCustomFont(Uri uri) {
        setStatus(getString(R.string.ks_0328));
        new Thread(() -> {
            try {
                String fontId = FontCatalog.importFont(getApplicationContext(), uri);
                runOnUiThread(() -> {
                    globalTextStyle.fontId = fontId;
                    saveGlobalTextStyle();
                    refreshFontSpinnerOptions();
                    refreshStyleControls();
                    setStatus(getString(R.string.ks_0569));
                });
            } catch (Throwable e) {
                setStatus("❌ " + friendlyError(e));
            }
        }, "KurdManFontImport").start();
    }

    private void openComicQuality() {
        if (currentBusy || JobRuntime.isRunning()) {
            setStatus(getString(R.string.ks_0541));
            return;
        }
        editorLauncher.launch(new Intent(this, ComicQualityActivity.class)
                .putExtra("source", selectedSourceLanguage())
                .putExtra("target", selectedTargetLanguage()));
    }

    private void openTextStudio() {
        if (currentBusy || JobRuntime.isRunning()) {
            setStatus(getString(R.string.ks_0541));
            return;
        }
        if (!TranslationProjectStore.hasCompletedProject(this)) {
            setStatus(getString(R.string.ks_0526));
            return;
        }
        editorLauncher.launch(new Intent(this, EditorActivity.class));
    }

    private void refreshKeepAliveUi() {
        if (keepAliveToggleButton == null || keepAliveStatus == null) {
            return;
        }
        boolean manual = KeepAliveService.isManualDesired(this);
        boolean running = JobRuntime.isRunning();
        boolean service = KeepAliveService.isServiceRunning();
        keepAliveToggleButton.setText(manual
                ? getString(R.string.ks_0487)
                : getString(R.string.ks_0493));
        boolean batteryFree = isIgnoringBatteryOptimizations();
        batterySettingsButton.setText(batteryFree
                ? getString(R.string.ks_0592)
                : getString(R.string.ks_0656));
        String text;
        if (running) {
            text = getString(R.string.ks_0682)
                    + getString(R.string.ks_0452);
        } else if (manual || service) {
            text = getString(R.string.ks_0683)
                    + getString(R.string.ks_0132);
        } else {
            text = getString(R.string.ks_0312)
                    + getString(R.string.ks_0363);
        }
        if (!batteryFree) {
            text += getString(R.string.ks_0006);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this,
                        Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            text += getString(R.string.ks_0008);
        }
        keepAliveStatus.setText(text);
        if (stopJobButton != null) {
            stopJobButton.setVisibility(currentBusy || running || manual || service
                    ? View.VISIBLE : View.GONE);
        }
        updateKeepScreenFlag();
    }

    private void toggleManualKeepAlive() {
        if (KeepAliveService.isManualDesired(this)) {
            stopEverything();
            return;
        }
        JobRuntime.setStatus(getString(R.string.ks_0106));
        KeepAliveService.startManual(this);
        requestNotificationPermissionIfNeeded();
        refreshKeepAliveUi();
        setStatus(getString(R.string.ks_0681));
    }

    private void stopEverything() {
        KeepAliveService.stopAll(this);
        setStatus(JobRuntime.isRunning()
                ? getString(R.string.ks_0676)
                : getString(R.string.ks_0678));
        if (!JobRuntime.isRunning()) {
            currentBusy = false;
            progress.setVisibility(View.GONE);
            updateButtons(false);
        }
        refreshKeepAliveUi();
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || prefs.getBoolean("notification_permission_asked", false)) {
            return;
        }
        if (ContextCompat.checkSelfPermission(this,
                        Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED
                && notificationPermissionLauncher != null) {
            prefs.edit().putBoolean("notification_permission_asked", true).apply();
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
        }
    }

    private boolean isIgnoringBatteryOptimizations() {
        PowerManager powerManager = getSystemService(PowerManager.class);
        return powerManager != null
                && powerManager.isIgnoringBatteryOptimizations(getPackageName());
    }

    private void openBatteryOptimizationSettings() {
        if (isIgnoringBatteryOptimizations()) {
            setStatus(getString(R.string.ks_0576));
            return;
        }
        try {
            Intent intent = new Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
            setStatus(getString(R.string.ks_0657));
        } catch (ActivityNotFoundException | SecurityException e) {
            try {
                startActivity(new Intent(
                        Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (RuntimeException second) {
                setStatus(getString(R.string.ks_0528));
            }
        }
    }

    private void updateKeepScreenFlag() {
        if (getWindow() == null || keepScreenCheck == null) {
            return;
        }
        boolean keep = keepScreenCheck.isChecked()
                && (currentBusy || JobRuntime.isRunning()
                        || KeepAliveService.isManualDesired(this));
        if (keep) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    private void throwIfJobStopped() throws InterruptedException {
        JobRuntime.throwIfStopped();
    }

    private boolean selectedOfflineMode() {
        return translationModeSpinner != null
                && translationModeSpinner.getSelectedItemPosition() == 1;
    }

    private boolean selectedCreatePdf() {
        if (createPdfCheck != null) {
            return createPdfCheck.isChecked();
        }
        return prefs != null && prefs.getBoolean(PREF_CREATE_PDF, false);
    }

    /**
     * Builds the optional Unlimited-OCR block reader from the Settings
     * widgets (call on the UI thread before a job starts). Returns null
     * unless the user enabled it and entered a usable http(s) address;
     * the current widget values are persisted for next time either way.
     */
    private ManhwaRenderer.BlockOcr createUnlimitedOcrIfEnabled() {
        if (!BuildConfig.SHOW_UNLIMITED_OCR) {
            // The Koorix public edition has no Baidu/Unlimited-OCR mode.
            return null;
        }
        boolean enabled = unlimitedOcrCheck != null
                ? unlimitedOcrCheck.isChecked()
                : prefs.getBoolean(PREF_UNLIMITED_OCR_ENABLED, false);
        String url = unlimitedOcrUrlInput != null
                ? unlimitedOcrUrlInput.getText().toString().trim()
                : prefs.getString(PREF_UNLIMITED_OCR_URL, "");
        String key = unlimitedOcrKeyInput != null
                ? unlimitedOcrKeyInput.getText().toString().trim()
                : prefs.getString(PREF_UNLIMITED_OCR_KEY, "");
        String model = unlimitedOcrModelInput != null
                ? unlimitedOcrModelInput.getText().toString().trim()
                : prefs.getString(PREF_UNLIMITED_OCR_MODEL, "");
        prefs.edit()
                .putBoolean(PREF_UNLIMITED_OCR_ENABLED, enabled)
                .putString(PREF_UNLIMITED_OCR_URL, url)
                .putString(PREF_UNLIMITED_OCR_KEY, key)
                .putString(PREF_UNLIMITED_OCR_MODEL, model)
                .apply();
        if (!enabled) {
            return null;
        }
        if (!UnlimitedOcrClient.isConfigured(url)) {
            setStatus(getString(R.string.ks_0509)
                    + getString(R.string.ks_0110));
            return null;
        }
        return new UnlimitedOcrClient(url, key, model);
    }

    private PaddleOcrClient startPaddleOcrIfAvailable(
            String sourceLanguage, boolean accurateOcr) {
        if (!accurateOcr) {
            return null;
        }
        if (!paddleOcrModelManager.isReadyFor(sourceLanguage)) {
            setStatus(getString(R.string.ks_0199)
                    + getString(R.string.ks_0310));
            return null;
        }
        PaddleOcrClient client = new PaddleOcrClient(this, sourceLanguage);
        activePaddleOcrClient = client;
        try {
            setStatus(getString(R.string.ks_0154));
            client.initialize();
            return client;
        } catch (Throwable e) {
            closePaddleOcrClient(client);
            if (!JobRuntime.wasStopped(e)) {
                setStatus(getString(R.string.ks_0508));
            }
            return null;
        }
    }

    private void closePaddleOcrClient(PaddleOcrClient client) {
        if (client == null) {
            return;
        }
        if (activePaddleOcrClient == client) {
            activePaddleOcrClient = null;
        }
        try {
            client.close();
        } catch (Throwable ignored) {
            // The isolated OCR process is disposable.
        }
    }

    private ManhwaRenderer.SecondaryOcr createPaddleSecondaryOcr(
            PaddleOcrClient client) {
        if (client == null) {
            return null;
        }
        return (page, boxes) -> {
            List<ManhwaRenderer.LineText> output = new ArrayList<>();
            for (int i = 0; i < boxes.size(); i++) {
                output.add(new ManhwaRenderer.LineText("", 0f));
            }
            File dir = new File(getCacheDir(),
                    "ppocr-lines-" + timestamp() + "-" + System.nanoTime());
            if (!dir.exists() && !dir.mkdirs()) {
                throw new IOException("Could not create PP-OCR crop directory");
            }
            List<File> files = new ArrayList<>();
            List<Integer> boxIndexes = new ArrayList<>();
            try {
                for (int i = 0; i < boxes.size(); i++) {
                    throwIfJobStopped();
                    Rect rect = expandedLineRect(boxes.get(i), page);
                    if (rect == null) {
                        continue;
                    }
                    Bitmap crop = Bitmap.createBitmap(page,
                            rect.left, rect.top, rect.width(), rect.height());
                    File originalFile = new File(dir, String.format(
                            Locale.ROOT, "line-%04d-a.png", i));
                    writeBitmapPng(originalFile, crop);
                    files.add(originalFile);
                    boxIndexes.add(i);

                    if (isDarkBitmap(crop)) {
                        Bitmap inverted = invertBitmap(crop);
                        try {
                            File invertedFile = new File(dir, String.format(
                                    Locale.ROOT, "line-%04d-b.png", i));
                            writeBitmapPng(invertedFile, inverted);
                            files.add(invertedFile);
                            boxIndexes.add(i);
                        } finally {
                            inverted.recycle();
                        }
                    }
                }
                if (files.isEmpty()) {
                    return output;
                }
                List<PaddleOcrClient.LineResult> results =
                        client.recognizeFiles(files);
                for (int i = 0; i < Math.min(results.size(), files.size()); i++) {
                    int boxIndex = boxIndexes.get(i);
                    PaddleOcrClient.LineResult result = results.get(i);
                    ManhwaRenderer.LineText current = output.get(boxIndex);
                    if (result.confidence > current.confidence) {
                        output.set(boxIndex, new ManhwaRenderer.LineText(
                                result.text, result.confidence));
                    }
                }
                return output;
            } finally {
                deleteRecursive(dir);
            }
        };
    }

    private Rect expandedLineRect(Rect box, Bitmap page) {
        if (box == null || page == null) {
            return null;
        }
        int padX = Math.max(5, Math.round(box.width() * 0.08f));
        int padY = Math.max(4, Math.round(box.height() * 0.28f));
        Rect out = new Rect(
                Math.max(0, box.left - padX),
                Math.max(0, box.top - padY),
                Math.min(page.getWidth(), box.right + padX),
                Math.min(page.getHeight(), box.bottom + padY));
        return out.width() >= 8 && out.height() >= 6 ? out : null;
    }

    private void writeBitmapPng(File file, Bitmap bitmap) throws IOException {
        OutputStream out = new BufferedOutputStream(new FileOutputStream(file));
        try {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                throw new IOException("Could not write PP-OCR line crop");
            }
        } finally {
            out.close();
        }
    }

    private boolean isDarkBitmap(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int stepX = Math.max(1, width / 12);
        int stepY = Math.max(1, height / 8);
        long sum = 0;
        int count = 0;
        for (int x = 0; x < width; x += stepX) {
            sum += luminance(bitmap.getPixel(x, 0));
            sum += luminance(bitmap.getPixel(x, height - 1));
            count += 2;
        }
        for (int y = 0; y < height; y += stepY) {
            sum += luminance(bitmap.getPixel(0, y));
            sum += luminance(bitmap.getPixel(width - 1, y));
            count += 2;
        }
        return count > 0 && (sum / (double) count) < 118.0;
    }

    private Bitmap invertBitmap(Bitmap source) {
        Bitmap output = Bitmap.createBitmap(
                source.getWidth(), source.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        paint.setColorFilter(new ColorMatrixColorFilter(new ColorMatrix(new float[]{
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f})));
        canvas.drawBitmap(source, 0f, 0f, paint);
        return output;
    }

    private int luminance(int color) {
        return Math.round(0.299f * Color.red(color)
                + 0.587f * Color.green(color)
                + 0.114f * Color.blue(color));
    }

    private void refreshComicSkillUi() {
        if (comicControls != null) comicControls.selectPair(selectedSourceLanguage(), selectedTargetLanguage());
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        if (comicControls != null) comicControls.saveState(outState);
        super.onSaveInstanceState(outState);
    }

    private SoraniTranslator createTranslator(boolean offlineMode,
                                              String targetLanguage,
                                              ComicTranslationSkill.Config comicConfig) throws Exception {
        // Validate/load before allocating a native engine.
        ComicTranslationSkill skill = new ComicTranslationSkill(comicConfig, ComicSettings.lexicon(this));
        SoraniTranslator translator;
        if (!offlineMode) {
            translator = new SoraniTranslator();
        } else if ("ckb".equals(targetLanguage)) {
            // Sorani has no ML Kit pack, so it uses our own downloadable
            // Sorani language pack (other sources pivot to English first).
            translator = new SoraniTranslator(new SoraniPackEngine(this));
        } else {
            translator = new SoraniTranslator(new MlKitOfflineEngine());
        }
        translator.setTargetLanguage(targetLanguage);
        translator.setComicSkill(skill);
        activeTranslator = translator;
        return translator;
    }

    private void translateSegments(SoraniTranslator translator, List<Segment> segments,
                                   String source, ComicTranslationSkill.Config comicConfig,
                                   String statusPrefix) throws Exception {
        List<String> texts = new ArrayList<>();
        for (Segment segment : segments) texts.add(segment.text);
        ComicTranslationSkill.PageResult page = translator.translatePage(texts, source,
                (completed, total) -> {
                    throwIfJobStopped();
                    setStatus(statusPrefix + Math.min(completed + 1, total) + "/" + total
                            + "…" + (comicConfig.enabled ? " • " + getString(R.string.auto_skill_progress) : ""));
                });
        throwIfJobStopped();
        int review = ComicTranslationFlow.apply(segments, page, source, translator.getTargetLanguage(),
                comicConfig);
        DiagnosticLog.info(getApplicationContext(), "comic_skill",
                "genre=" + page.genre + ", bubbles=" + segments.size() + ", localRules=" + page.localRules
                        + ", contextRequests=" + page.contextRequests + ", contextFallbacks=" + page.contextFallbacks
                        + ", glossaryFallbacks=" + page.glossaryFallbacks + ", autoRetries=" + page.qualityRetries
                        + ", autoRecovered=" + page.qualityRecoveries + ", review=" + review);
    }

    private void closeTranslator(SoraniTranslator translator) {
        if (translator != null) {
            if (activeTranslator == translator) {
                activeTranslator = null;
            }
            try {
                translator.close();
            } catch (Exception ignored) {
                // The translator implementation already treats model
                // cleanup as best-effort; this is a final safety net.
            }
        }
    }

    /**
     * Refreshes the offline language-pack card for the currently
     * selected source→target pair. ML Kit pack state is queried on the
     * executor; the result is cached in {@link #offlinePackReady} so
     * job start can gate synchronously.
     */
    private void refreshOfflineModelUi() {
        if (offlineModelStatus == null || downloadOfflineButton == null) {
            return;
        }
        final String source = selectedSourceLanguage();
        final String target = selectedTargetLanguage();
        final String pair = source + ">" + target;
        if ("ckb".equals(target)) {
            if (offlineTileTitle != null) {
                offlineTileTitle.setText(R.string.ks_0717);
            }
            if (offlineTileDesc != null) {
                offlineTileDesc.setText(R.string.ks_0718);
            }
            if (offlineTileFooter != null) {
                offlineTileFooter.setText(R.string.ks_0719);
            }
            boolean ready = offlineModelManager != null
                    && offlineModelManager.isModelReady();
            long downloaded = offlineModelManager == null ? 0
                    : offlineModelManager.downloadedBytes();
            long total = OfflineModelManager.totalBytes();
            offlinePackReady = ready;
            offlinePackPair = pair;
            if (ready) {
                offlineModelStatus.setText(R.string.ks_0720);
            } else if (downloaded > 0) {
                offlineModelStatus.setText(getString(R.string.ks_0624)
                        + formatStorage(downloaded) + " / "
                        + formatStorage(total)
                        + getString(R.string.ks_0063));
            } else {
                offlineModelStatus.setText(getString(R.string.ks_0340)
                        + formatStorage(total));
            }
            downloadOfflineButton.setText(ready
                    ? getString(R.string.ks_0723)
                    : getString(R.string.ks_0722));
            downloadOfflineButton.setEnabled(!ready);
            if (deleteOfflineButton != null) {
                deleteOfflineButton.setEnabled(downloaded > 0);
            }
            if (ready && !"en".equals(source)) {
                // Non-English pages pivot through English offline.
                offlinePackReady = false;
                executor.execute(() -> {
                    boolean pivotReady;
                    try {
                        pivotReady = MlKitOfflineEngine.isPackDownloaded(
                                source, "en");
                    } catch (Exception e) {
                        pivotReady = false;
                    }
                    final boolean pivot = pivotReady;
                    runOnUiThread(() -> {
                        if (!("ckb".equals(selectedTargetLanguage())
                                && source.equals(selectedSourceLanguage()))) {
                            return; // selection changed meanwhile
                        }
                        offlinePackReady = pivot;
                        offlinePackPair = pair;
                        if (offlineModelStatus != null) {
                            offlineModelStatus.setText(pivot
                                    ? getString(R.string.ks_0720)
                                    : getString(R.string.ks_0750));
                        }
                        if (downloadOfflineButton != null) {
                            downloadOfflineButton.setText(pivot
                                    ? getString(R.string.ks_0723)
                                    : getString(R.string.ks_0722));
                            downloadOfflineButton.setEnabled(!pivot);
                        }
                    });
                });
            }
            return;
        }
        if (offlineTileTitle != null) {
            offlineTileTitle.setText(R.string.ks_0655);
        }
        if (offlineTileDesc != null) {
            offlineTileDesc.setText(R.string.ks_0161);
        }
        if (offlineTileFooter != null) {
            offlineTileFooter.setText(R.string.ks_0101);
        }
        if (!MlKitOfflineEngine.isPairSupported(source, target)) {
            offlinePackReady = false;
            offlinePackPair = pair;
            offlineModelStatus.setText(getString(R.string.ks_0515)
                    + getString(R.string.ks_0074));
            downloadOfflineButton.setText(getString(R.string.ks_0621));
            downloadOfflineButton.setEnabled(false);
            if (deleteOfflineButton != null) {
                deleteOfflineButton.setEnabled(false);
            }
            return;
        }
        offlineModelStatus.setText(getString(R.string.ks_0415));
        executor.execute(() -> {
            boolean ready;
            try {
                ready = MlKitOfflineEngine.isPackDownloaded(source, target);
            } catch (Exception e) {
                ready = false;
            }
            final boolean packReady = ready;
            runOnUiThread(() -> {
                if (!pair.equals(selectedSourceLanguage() + ">"
                        + selectedTargetLanguage())) {
                    return; // selection changed meanwhile
                }
                offlinePackReady = packReady;
                offlinePackPair = pair;
                if (offlineModelStatus != null) {
                    offlineModelStatus.setText(packReady
                            ? getString(R.string.ks_0577)
                            : getString(R.string.ks_0342));
                }
                if (downloadOfflineButton != null) {
                    downloadOfflineButton.setText(packReady
                            ? getString(R.string.ks_0598) : getString(R.string.ks_0621));
                    downloadOfflineButton.setEnabled(!packReady);
                }
                if (deleteOfflineButton != null) {
                    deleteOfflineButton.setEnabled(packReady);
                }
            });
        });
    }

    private void refreshPaddleOcrUi() {
        if (paddleOcrModelManager == null || paddleOcrStatus == null
                || downloadPaddleOcrButton == null) {
            return;
        }
        boolean ready = paddleOcrModelManager.isPackReady();
        long downloaded = paddleOcrModelManager.downloadedBytes();
        long total = PaddleOcrModelManager.totalBytes();
        if (ready) {
            paddleOcrStatus.setText(getString(R.string.ks_0552)
                    + formatStorage(total));
            downloadPaddleOcrButton.setText(getString(R.string.ks_0595));
        } else if (downloaded > 0) {
            paddleOcrStatus.setText(getString(R.string.ks_0624)
                    + formatStorage(downloaded) + " / " + formatStorage(total)
                    + getString(R.string.ks_0063));
            downloadPaddleOcrButton.setText(getString(R.string.ks_0484));
        } else {
            paddleOcrStatus.setText(getString(R.string.ks_0340) + formatStorage(total));
            downloadPaddleOcrButton.setText(getString(R.string.ks_0620));
        }
    }

    private void downloadPaddleOcrPack() {
        if (paddleOcrModelManager.isPackReady()) {
            refreshPaddleOcrUi();
            setStatus(getString(R.string.ks_0555));
            return;
        }
        setBusy(true);
        setStatus(getString(R.string.ks_0626)
                + formatStorage(PaddleOcrModelManager.totalBytes()) + ")…");
        executor.execute(() -> {
            JobRuntime.markThreadStarted();
            final int[] lastPercent = {-1};
            try {
                paddleOcrModelManager.downloadPack(
                        (downloadedBytes, totalBytes, currentFile, verifying) -> {
                            int percent = totalBytes == 0 ? 0
                                    : (int) ((downloadedBytes * 100L) / totalBytes);
                            if (percent == lastPercent[0] && percent < 100) {
                                return;
                            }
                            lastPercent[0] = percent;
                            String status = (verifying
                                    ? getString(R.string.ks_0661)
                                    : getString(R.string.ks_0625))
                                    + percent + "% ("
                                    + formatStorage(downloadedBytes) + " / "
                                    + formatStorage(totalBytes) + ")";
                            setStatus(status);
                            runOnUiThread(() -> {
                                if (paddleOcrStatus != null) {
                                    paddleOcrStatus.setText(status);
                                }
                            });
                        });
                runOnUiThread(this::refreshPaddleOcrUi);
                setStatus(getString(R.string.ks_0553)
                        + getString(R.string.ks_0426));
            } catch (Throwable e) {
                runOnUiThread(this::refreshPaddleOcrUi);
                setStatus(JobRuntime.wasStopped(e)
                        ? getString(R.string.ks_0674)
                        : getString(R.string.ks_0605)
                                + friendlyError(e));
            } finally {
                setBusy(false);
            }
        });
    }

    private void confirmDeletePaddleOcrPack() {
        long downloaded = paddleOcrModelManager.downloadedBytes();
        if (downloaded <= 0 && !paddleOcrModelManager.isPackReady()) {
            refreshPaddleOcrUi();
            setStatus(getString(R.string.ks_0533));
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.ks_0258))
                .setMessage(getString(R.string.ks_0322) + formatStorage(downloaded)
                        + getString(R.string.ks_0022)
                        + getString(R.string.ks_0181))
                .setNegativeButton(getString(R.string.ks_0401), null)
                .setPositiveButton(getString(R.string.ks_0174), (dialog, which) -> {
                    setBusy(true);
                    executor.execute(() -> {
                        JobRuntime.markThreadStarted();
                        try {
                            boolean deleted = paddleOcrModelManager.deletePack();
                            runOnUiThread(this::refreshPaddleOcrUi);
                            setStatus(deleted
                                    ? getString(R.string.ks_0554)
                                    : getString(R.string.ks_0537));
                        } finally {
                            setBusy(false);
                        }
                    });
                })
                .show();
    }

    private void downloadOfflineModel() {
        final String source = selectedSourceLanguage();
        final String target = selectedTargetLanguage();
        if ("ckb".equals(target)) {
            final boolean packReady = offlineModelManager.isModelReady();
            final boolean needPivot = !"en".equals(source);
            if (packReady && !needPivot) {
                refreshOfflineModelUi();
                setStatus(getString(R.string.ks_0729));
                return;
            }
            setBusy(true);
            setStatus(getString(R.string.ks_0724)
                    + formatStorage(OfflineModelManager.totalBytes()) + ")…");
            executor.execute(() -> {
                JobRuntime.markThreadStarted();
                final int[] lastPercent = {-1};
                final boolean[] lastVerifying = {false};
                try {
                    if (!packReady) {
                    offlineModelManager.downloadModel(
                            (downloadedBytes, totalBytes, currentFile,
                             verifying) -> {
                                int percent = totalBytes == 0 ? 0
                                        : (int) ((downloadedBytes * 100L)
                                                / totalBytes);
                                if (percent == lastPercent[0]
                                        && verifying == lastVerifying[0]
                                        && percent < 100) {
                                    return;
                                }
                                lastPercent[0] = percent;
                                lastVerifying[0] = verifying;
                                String status = (verifying
                                        ? getString(R.string.ks_0725)
                                        : getString(R.string.ks_0726))
                                        + percent + "% ("
                                        + formatStorage(downloadedBytes)
                                        + " / " + formatStorage(totalBytes)
                                        + ")"
                                        + (currentFile == null
                                                || currentFile.isEmpty()
                                                ? "" : " — " + currentFile);
                                setStatus(status);
                                runOnUiThread(() -> {
                                    if (offlineModelStatus != null) {
                                        offlineModelStatus.setText(status);
                                    }
                                });
                            });
                    }
                    if (needPivot) {
                        setStatus(getString(R.string.ks_0749));
                        MlKitOfflineEngine.downloadPack(source, "en");
                    }
                    runOnUiThread(this::refreshOfflineModelUi);
                    setStatus(getString(R.string.ks_0727));
                } catch (Throwable e) {
                    runOnUiThread(this::refreshOfflineModelUi);
                    setStatus(getString(R.string.ks_0728)
                            + friendlyOfflineError(e));
                } finally {
                    setBusy(false);
                }
            });
            return;
        }
        if (!MlKitOfflineEngine.isPairSupported(source, target)) {
            refreshOfflineModelUi();
            return;
        }
        setBusy(true);
        setStatus(getString(R.string.ks_0627));
        executor.execute(() -> {
            JobRuntime.markThreadStarted();
            try {
                MlKitOfflineEngine.downloadPack(source, target);
                runOnUiThread(this::refreshOfflineModelUi);
                setStatus(getString(R.string.ks_0578)
                        + getString(R.string.ks_0133));
            } catch (Throwable e) {
                runOnUiThread(this::refreshOfflineModelUi);
                setStatus(getString(R.string.ks_0606)
                        + friendlyOfflineError(e));
            } finally {
                setBusy(false);
            }
        });
    }

    private void confirmDeleteOfflineModel() {
        if ("ckb".equals(selectedTargetLanguage())) {
            long downloaded = offlineModelManager.downloadedBytes();
            if (downloaded <= 0 && !offlineModelManager.isModelReady()) {
                refreshOfflineModelUi();
                setStatus(getString(R.string.ks_0748));
                return;
            }
            new AlertDialog.Builder(this)
                    .setTitle(getString(R.string.ks_0730))
                    .setMessage(getString(R.string.ks_0731)
                            + formatStorage(downloaded)
                            + getString(R.string.ks_0732))
                    .setNegativeButton(getString(R.string.ks_0401), null)
                    .setPositiveButton(getString(R.string.ks_0174),
                            (dialog, which) -> deleteOfflineModel())
                    .show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.ks_0263))
                .setMessage(getString(R.string.ks_0409)
                        + getString(R.string.ks_0158))
                .setNegativeButton(getString(R.string.ks_0401), null)
                .setPositiveButton(getString(R.string.ks_0174),
                        (dialog, which) -> deleteOfflineModel())
                .show();
    }

    private void deleteOfflineModel() {
        if ("ckb".equals(selectedTargetLanguage())) {
            setBusy(true);
            setStatus(getString(R.string.ks_0733));
            executor.execute(() -> {
                JobRuntime.markThreadStarted();
                try {
                    boolean deleted = offlineModelManager.deleteModel();
                    offlinePackReady = false;
                    runOnUiThread(this::refreshOfflineModelUi);
                    setStatus(deleted
                            ? getString(R.string.ks_0734)
                            : getString(R.string.ks_0735));
                } catch (Exception e) {
                    runOnUiThread(this::refreshOfflineModelUi);
                    setStatus(getString(R.string.ks_0736)
                            + friendlyError(e));
                } finally {
                    setBusy(false);
                }
            });
            return;
        }
        final String source = selectedSourceLanguage();
        final String target = selectedTargetLanguage();
        setBusy(true);
        setStatus(getString(R.string.ks_0264));
        executor.execute(() -> {
            JobRuntime.markThreadStarted();
            try {
                MlKitOfflineEngine.deletePack(source, target);
                offlinePackReady = false;
                runOnUiThread(this::refreshOfflineModelUi);
                setStatus(getString(R.string.ks_0579));
            } catch (Exception e) {
                runOnUiThread(this::refreshOfflineModelUi);
                setStatus(getString(R.string.ks_0608)
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

    private String friendlyOfflineError(Throwable error) {
        if (JobRuntime.wasStopped(error)) {
            return getString(R.string.ks_0451);
        }
        String raw = friendlyError(error);
        String lower = errorChainText(error);
        if (lower.contains("offline_pair_unsupported")) {
            return getString(R.string.ks_0143)
                    + getString(R.string.ks_0280) + raw + ")";
        }
        if (lower.contains("offline_pack_missing")) {
            return getString(R.string.ks_0271)
                    + getString(R.string.ks_0077) + raw + ")";
        }
        if (lower.contains("not enough free storage")) {
            return getString(R.string.ks_0738)
                    + formatStorage(OfflineModelManager.totalBytes())
                    + getString(R.string.ks_0739) + raw + ")";
        }
        if (lower.contains("checksum mismatch")
                || lower.contains("sha-256 mismatch")) {
            return getString(R.string.ks_0740) + raw + ")";
        }
        if (lower.contains("offline_ai_out_of_memory")
                || lower.contains("outofmemoryerror")
                || lower.contains("failed to allocate")) {
            return getString(R.string.ks_0741) + raw + ")";
        }
        if (lower.contains("offline_ai_process_died")) {
            return getString(R.string.ks_0742) + raw + ")";
        }
        if (lower.contains("offline_ai_timeout")) {
            return getString(R.string.ks_0743) + raw + ")";
        }
        if (lower.contains("offline_model_not_ready")) {
            return getString(R.string.ks_0744) + raw + ")";
        }
        if (lower.contains("offline_ai_returned_untranslated")
                || lower.contains("offline ai returned untranslated")) {
            return getString(R.string.ks_0745) + raw + ")";
        }
        if (lower.contains("offline_ai_runtime_error")
                || lower.contains("offline_ai_model_mismatch")
                || lower.contains("onnx")
                || lower.contains("ortexception")) {
            return getString(R.string.ks_0746) + raw + ")";
        }
        if (lower.contains("no space left")) {
            return getString(R.string.ks_0278)
                    + raw + ")";
        }
        if (lower.contains("unable to resolve host")
                || lower.contains("unknownhostexception")
                || lower.contains("failed to connect")
                || lower.contains("timed out") || lower.contains("timeout")
                || lower.contains("connection reset")
                || lower.contains("network is unreachable")
                || lower.contains("network_error")) {
            return getString(R.string.ks_0421)
                    + getString(R.string.ks_0134) + raw + ")";
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
        setStatus(getString(R.string.ks_0420));
        executor.execute(() -> {
            JobRuntime.markThreadStarted();
            try {
                TelegramApi api = new TelegramApi(token);
                JSONObject me = api.getMe();
                String username = me.optString("username", "");
                String status = username.isEmpty()
                        ? getString(R.string.ks_0562)
                        : getString(R.string.ks_0563) + username;
                if (!chatId.isEmpty()) {
                    // Prove the bot can actually reach this chat; getMe alone
                    // does not validate the Chat ID at all.
                    api.sendChatAction(chatId, "typing");
                    status += getString(R.string.ks_0064);
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
                    status += getString(R.string.ks_0010);
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
            setStatus(getString(R.string.ks_0544));
            return;
        }
        tokenInput.setText(clean);
        tokenInput.setSelection(clean.length());
        tokenInput.requestFocus();
        if (isValidToken(clean)) {
            setStatus(getString(R.string.ks_0561));
        } else {
            setStatus(getString(R.string.ks_0513));
        }
    }

    private void pasteChatIdFromClipboard() {
        Matcher idMatcher = Pattern.compile("-?\\d{5,}").matcher(clipboardText());
        String clean = idMatcher.find() ? idMatcher.group() : "";
        if (clean.isEmpty() || clean.equals("-")) {
            setStatus(getString(R.string.ks_0545));
            return;
        }
        chatInput.setText(clean);
        chatInput.setSelection(clean.length());
        chatInput.requestFocus();
        setStatus(getString(R.string.ks_0550));
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
            setStatus(getString(R.string.ks_0530));
            return;
        }
        saveSettings();
        setBusy(true);
        setStatus(getString(R.string.ks_0316));
        executor.execute(() -> {
            JobRuntime.markThreadStarted();
            try {
                TelegramApi api = new TelegramApi(token);
                for (String chunk : splitText(text, 3500)) {
                    api.sendMessage(chatId, chunk);
                }
                runOnUiThread(() -> messageInput.setText(""));
                setStatus(getString(R.string.ks_0570));
            } catch (Exception e) {
                setStatus("❌ " + friendlyTelegramError(e));
            } finally {
                setBusy(false);
            }
        });
    }

    private void acceptPickedInput(List<Uri> uris) {
        if(uris==null||uris.isEmpty())return; // Cancel never clears the current choice.
        for(Uri uri:uris)try {
            getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }catch(SecurityException|IllegalArgumentException transientGrant) {
            // Gallery/GetContent can grant temporary access only. Sources are
            // copied immediately into private storage by the existing importer.
        }
        loadPickedFiles(uris);
    }

    private void openGallery() {
        if(currentBusy||JobRuntime.isRunning())return;
        try {
            if(ActivityResultContracts.PickVisualMedia.isPhotoPickerAvailable()) galleryLauncher.launch(ComicInputPicker.imagesOnly());
            else galleryFallbackLauncher.launch(Intent.createChooser(ComicInputPicker.galleryFallback(),getString(R.string.input_gallery)));
        }catch(ActivityNotFoundException unavailable) {
            try { galleryFallbackLauncher.launch(Intent.createChooser(ComicInputPicker.galleryFallback(),getString(R.string.input_gallery))); }
            catch(ActivityNotFoundException missing){setStatus(getString(R.string.input_picker_unavailable));}
        }
    }

    private void openAllFiles() {
        if(currentBusy||JobRuntime.isRunning())return;
        try { allFilesLauncher.launch(null); }
        catch(ActivityNotFoundException unavailable) {
            try {filesFallbackLauncher.launch(Intent.createChooser(ComicInputPicker.filesFallback(),getString(R.string.input_files)));}
            catch(ActivityNotFoundException missing){setStatus(getString(R.string.input_picker_unavailable));}
        }
    }

    private void clearBatchSelection() {
        selectedBatchFiles.clear();
        selectedBatchNames.clear();
        selectedBatchLabel = "";
        selectedBatchBase = "";
    }

    private void loadPickedFiles(List<Uri> uris) {
        if (uris == null || uris.isEmpty()) {
            return;
        }
        if (uris.size() == 1) {
            loadPickedFile(uris.get(0));
            return;
        }

        List<PickedInput> picked = new ArrayList<>();
        for (Uri uri : uris) {
            if (uri == null) {
                continue;
            }
            String displayName = queryDisplayName(uri);
            String mimeType = getContentResolver().getType(uri);
            picked.add(new PickedInput(uri, displayName, mimeType,
                    isCbzSelection(displayName, mimeType)));
        }
        if (picked.isEmpty()) {
            return;
        }
        for (PickedInput input : picked) {
            if (input.cbz) {
                String message = getString(R.string.ks_0167)
                        + getString(R.string.ks_0093);
                DiagnosticLog.warning(getApplicationContext(), "file-picker",
                        "Mixed multi-selection rejected: CBZ must be selected alone; count="
                                + picked.size());
                setStatus("⚠ " + message);
                return;
            }
        }
        Collections.sort(picked, new Comparator<PickedInput>() {
            @Override
            public int compare(PickedInput a, PickedInput b) {
                return naturalFileNameCompare(a.displayName, b.displayName);
            }
        });
        final int requestedCount = picked.size();
        final int ignoredCount = Math.max(0, requestedCount - 300);
        if (ignoredCount > 0) {
            picked = new ArrayList<>(picked.subList(0, 300));
        }
        final List<PickedInput> finalPicked = picked;

        setBusy(true);
        setStatus(getString(R.string.ks_0195) + finalPicked.size() + getString(R.string.ks_0060));
        executor.execute(() -> {
            JobRuntime.markThreadStarted();
            try {
                List<File> copiedFiles = new ArrayList<>();
                List<String> copiedNames = new ArrayList<>();
                List<String> skippedNames = new ArrayList<>();
                Bitmap preview = null;
                long totalBytes = 0L;
                String batchStamp = timestamp() + "-" + System.nanoTime();

                for (int i = 0; i < finalPicked.size(); i++) {
                    throwIfJobStopped();
                    PickedInput input = finalPicked.get(i);
                    if (!isSupportedImageSelection(input)) {
                        skippedNames.add(input.displayName);
                        DiagnosticLog.warning(getApplicationContext(), "file-picker",
                                "Skipped non-image in multi-selection: "
                                        + input.displayName + " mime=" + input.mimeType);
                        continue;
                    }
                    setStatus(getString(R.string.ks_0201) + (i + 1) + "/"
                            + finalPicked.size() + "…");
                    File copied = new File(getCacheDir(),
                            "selected-batch-" + batchStamp + "-"
                                    + String.format(Locale.ROOT, "%04d", i + 1)
                                    + extensionWithDot(input.displayName, ".jpg"));
                    long bytes = copyUriToFile(
                            input.uri, copied, 300L * 1024L * 1024L);
                    totalBytes += bytes;
                    if (totalBytes > 1024L * 1024L * 1024L) {
                        throw new IOException(
                                "Selected images are too large together (max 1 GB)");
                    }
                    if (preview == null) {
                        preview = decodeBitmap(copied);
                        if (preview == null) {
                            skippedNames.add(input.displayName);
                            DiagnosticLog.warning(getApplicationContext(),
                                    "file-picker",
                                    "Could not decode selected image: "
                                            + input.displayName);
                            continue;
                        }
                    }
                    copiedFiles.add(copied);
                    copiedNames.add(input.displayName);
                }
                if (copiedFiles.isEmpty()) {
                    throw new IOException("No readable images were selected");
                }

                final Bitmap finalPreview = preview;
                final String batchBase = batchBaseName(copiedNames);
                final String batchLabel = batchBase + " — "
                        + copiedFiles.size() + getString(R.string.ks_0055);
                StringBuilder summary = new StringBuilder();
                summary.append("🖼 ").append(copiedFiles.size())
                        .append(getString(R.string.ks_0057))
                        .append(getString(R.string.ks_0379));
                int shown = Math.min(12, copiedNames.size());
                for (int i = 0; i < shown; i++) {
                    summary.append(i + 1).append(". ")
                            .append(copiedNames.get(i)).append('\n');
                }
                if (copiedNames.size() > shown) {
                    summary.append(getString(R.string.ks_0470)).append(copiedNames.size() - shown)
                            .append(getString(R.string.ks_0058));
                }
                if (!skippedNames.isEmpty()) {
                    summary.append("⚠ ").append(skippedNames.size())
                            .append(getString(R.string.ks_0037));
                }
                if (ignoredCount > 0) {
                    summary.append(getString(R.string.ks_0519))
                            .append(ignoredCount).append(getString(R.string.ks_0020));
                }
                final String summaryText = summary.toString().trim();
                DiagnosticLog.info(getApplicationContext(), "file-picker",
                        "Selected image batch: accepted=" + copiedFiles.size()
                                + ", skipped=" + skippedNames.size()
                                + ", ignoredOver300=" + ignoredCount
                                + ", totalBytes=" + totalBytes
                                + ", base=" + batchBase);

                runOnUiThread(() -> {
                    clearBatchSelection();
                    selectedBatchFiles.addAll(copiedFiles);
                    selectedBatchNames.addAll(copiedNames);
                    selectedBatchLabel = batchLabel;
                    selectedBatchBase = batchBase;
                    selectedImageFile = copiedFiles.get(0);
                    selectedCbzFile = null;
                    selectedName = copiedNames.get(0);
                    selectedCbzPages = copiedFiles.size();
                    originalBitmap = finalPreview;
                    resultBitmap = null;
                    resultFile = null;
                    resultTextFile = null;
                    pairsText = "";
                    if (finalPreview != null) {
                        previewImage.setImageBitmap(finalPreview);
                    }
                    resultText.setText(summaryText);
                    updateButtons(false);
                });
                setStatus("✅ " + copiedFiles.size()
                        + getString(R.string.ks_0056)
                        + (ignoredCount > 0
                                ? " (" + ignoredCount + getString(R.string.ks_0019)
                                : ""));
            } catch (Throwable e) {
                if (JobRuntime.wasStopped(e)) {
                    setStatus(getString(R.string.ks_0673));
                } else {
                    DiagnosticLog.error(getApplicationContext(), "file-picker", e,
                            "Loading multi-image selection; requested="
                                    + requestedCount);
                    setStatus(getString(R.string.ks_0611)
                            + friendlyError(e)
                            + getString(R.string.ks_0053));
                }
            } finally {
                setBusy(false);
            }
        });
    }

    private boolean isSupportedImageSelection(PickedInput input) {
        if (input == null) {
            return false;
        }
        if (input.mimeType != null
                && input.mimeType.toLowerCase(Locale.ROOT).startsWith("image/")) {
            return true;
        }
        return isImageEntry(input.displayName == null
                ? "" : input.displayName.toLowerCase(Locale.ROOT));
    }

    private String batchBaseName(List<String> names) {
        if (names == null || names.isEmpty()) {
            return "kurdman-pages";
        }
        String prefix = baseNameWithoutExtension(names.get(0));
        for (int n = 1; n < names.size() && !prefix.isEmpty(); n++) {
            String other = baseNameWithoutExtension(names.get(n));
            int max = Math.min(prefix.length(), other.length());
            int i = 0;
            while (i < max && prefix.charAt(i) == other.charAt(i)) {
                i++;
            }
            prefix = prefix.substring(0, i);
        }
        prefix = prefix.replaceAll("[-_ .]*\\d*$", "").trim();
        while (!prefix.isEmpty()) {
            char last = prefix.charAt(prefix.length() - 1);
            if (last == '-' || last == '_' || last == ' ' || last == '.') {
                prefix = prefix.substring(0, prefix.length() - 1).trim();
            } else {
                break;
            }
        }
        String clean = sanitizeFileName(prefix);
        return clean.length() >= 3 ? clean : "kurdman-pages";
    }

    /** Numeric-aware name order: page-2 before page-10. */
    private int naturalFileNameCompare(String first, String second) {
        String a = first == null ? "" : first.toLowerCase(Locale.ROOT);
        String b = second == null ? "" : second.toLowerCase(Locale.ROOT);
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
                String numberA = stripLeadingZeros(a.substring(startA, i));
                String numberB = stripLeadingZeros(b.substring(startB, j));
                if (numberA.length() != numberB.length()) {
                    return Integer.compare(numberA.length(), numberB.length());
                }
                int byNumber = numberA.compareTo(numberB);
                if (byNumber != 0) {
                    return byNumber;
                }
                continue;
            }
            if (ca != cb) {
                return Character.compare(ca, cb);
            }
            i++;
            j++;
        }
        return Integer.compare(a.length() - i, b.length() - j);
    }

    private String stripLeadingZeros(String value) {
        int i = 0;
        while (i + 1 < value.length() && value.charAt(i) == '0') {
            i++;
        }
        return value.substring(i);
    }

    private void loadPickedFile(Uri uri) {
        final String displayName = queryDisplayName(uri);
        final String mimeType = getContentResolver().getType(uri);
        final boolean cbz = isCbzSelection(displayName, mimeType);
        setBusy(true);
        setStatus(cbz ? getString(R.string.ks_0196) : getString(R.string.ks_0202));
        executor.execute(() -> {
            JobRuntime.markThreadStarted();
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
                        clearBatchSelection();
                        selectedImageFile = null;
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
                                + getString(R.string.ks_0007) + finalPageCount
                                + getString(R.string.ks_0005));
                        updateButtons(false);
                    });
                    setStatus(getString(R.string.ks_0548) + displayName
                            + " — " + finalPageCount + getString(R.string.ks_0043));
                } else {
                    File copiedImage = new File(getCacheDir(),
                            "selected-image-" + timestamp()
                                    + extensionWithDot(displayName, ".jpg"));
                    copyUriToFile(uri, copiedImage, 300L * 1024L * 1024L);
                    final Bitmap loaded = decodeBitmap(copiedImage);
                    if (loaded == null) {
                        throw new IOException("Could not decode image");
                    }
                    runOnUiThread(() -> {
                        clearBatchSelection();
                        selectedImageFile = copiedImage;
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
                    setStatus(getString(R.string.ks_0574)
                            + loaded.getWidth() + "×" + loaded.getHeight());
                }
            } catch (Exception e) {
                setStatus(getString(R.string.ks_0609) + friendlyError(e));
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

    private String selectedTargetLanguage() {
        int index = targetSpinner == null ? 0 : targetSpinner.getSelectedItemPosition();
        if (index < 0 || index >= TARGET_CODES.length) {
            index = 0;
        }
        return TARGET_CODES[index];
    }

    /** File-name suffix per target language (ckb kept for old outputs). */
    private static String outputSuffixFor(String target) {
        if (target == null || target.isEmpty() || "ckb".equals(target)) {
            return "ckb";
        }
        return target.toLowerCase(java.util.Locale.ROOT).replace("-", "");
    }

    private String outSuffix() {
        return outputSuffixFor(ManhwaRenderer.activeTargetLanguage);
    }

    /** Hero tagline follows the chosen target language (Koorix only). */
    private void updateHeroTagline() {
        if (!BuildConfig.PUBLIC_EDITION) {
            return;
        }
        TextView heroSubtitle = findViewById(R.id.heroSubtitle);
        if (heroSubtitle != null) {
            heroSubtitle.setText(R.string.kx_hero_subtitle);
        }
    }

    private boolean hasPublicInput() {
        return selectedCbzFile != null && selectedCbzFile.isFile() || selectedBatchFiles.size()>1
                || selectedImageFile != null && selectedImageFile.isFile()
                || originalBitmap != null && !originalBitmap.isRecycled();
    }

    private void translatePublicSelection() {
        if (comicControls==null || !comicControls.validate()) return;
        String source=selectedSourceLanguage(),target=selectedTargetLanguage(); boolean offline=selectedOfflineMode();
        boolean input=hasPublicInput(),present=input;
        if(selectedCbzFile!=null)present=selectedCbzFile.isFile();
        else if(selectedBatchFiles.size()>1)for(File file:selectedBatchFiles)if(file==null||!file.isFile())present=false;
        boolean supported=TranslationLanguages.source(source)&&TranslationLanguages.target(target)
                && (!offline || "ckb".equals(target) || MlKitOfflineEngine.isPairSupported(source,target));
        boolean ready=offlinePackReady && (source+">"+target).equals(offlinePackPair)
                && (!"ckb".equals(target) || offlineModelManager!=null && offlineModelManager.isModelReady());
        TranslationPreflight.Error problem=TranslationPreflight.check(currentBusy||JobRuntime.isRunning(),input,present,supported,offline,ready);
        if(problem!=TranslationPreflight.Error.NONE) {
            setStatus(getString(problem==TranslationPreflight.Error.NO_INPUT?R.string.ks_0524:
                    problem==TranslationPreflight.Error.SOURCE_MISSING?R.string.rel_source_missing:
                    problem==TranslationPreflight.Error.BUSY?R.string.rel_busy:
                    problem==TranslationPreflight.Error.PACK_MISSING?R.string.ks_0737:R.string.ks_0539)); return;
        }
        ComicTranslationSkill.Config config=comicControls.forTranslation(); if(config==null)return;
        boolean batch=selectedCbzFile==null&&selectedBatchFiles.size()>1;
        String name=batch?selectedBatchLabel:selectedName;
        String base=batch?selectedBatchBase:baseNameWithoutExtension(selectedName);
        base=sanitizeFileName(base);if(base.isEmpty())base="Koorix";
        SavedJobInput request=new SavedJobInput(selectedImageFile,selectedCbzFile,new ArrayList<>(selectedBatchFiles),name,base,
                source,target,offline,accurateOcrCheck==null||accurateOcrCheck.isChecked(),selectedCreatePdf(),config,globalTextStyle,originalBitmap);
        if(SavedJobCoordinator.start(getApplicationContext(),request)){
            resultFile=null;resultTextFile=null;showMainPage(PAGE_RESULTS);currentBusy=true;
            progress.setVisibility(View.VISIBLE);statusText.setText(R.string.rel_prepare);updateButtons(true);
            requestNotificationPermissionIfNeeded();updateKeepScreenFlag();
        }
    }

    private void handleStudioOrLibraryResult(int code,Intent data) {
        reloadGlobalTextStyleFromPrefs();
        if(code==RESULT_OK&&data!=null){
            String story=data.getStringExtra(ProjectLibraryActivity.EXTRA_STORY);
            if(story!=null){try{
                TranslationProject p=TranslationProjectStore.load(this,story);
                if(p!=null && TranslationLanguages.source(p.sourceLanguage) && TranslationLanguages.target(p.targetLanguage)){sourceSpinner.setSelection(java.util.Arrays.asList(SOURCE_CODES).indexOf(p.sourceLanguage));targetSpinner.setSelection(java.util.Arrays.asList(TARGET_CODES).indexOf(p.targetLanguage));comicControls.useStory(p.sourceLanguage,p.targetLanguage,p.comicConfig);showMainPage(PAGE_TRANSLATE);setStatus(getString(R.string.rel_story_loaded));}
            }catch(Exception failure){setStatus(getString(R.string.rel_failed));}return;}
            String resume=data.getStringExtra(ProjectLibraryActivity.EXTRA_RESUME);
            if(resume!=null){try{TranslationProject p=TranslationProjectStore.load(this,resume);
                if(p!=null&&SavedJobCoordinator.resume(getApplicationContext(),p,true)){resultFile=null;resultTextFile=null;currentBusy=true;showMainPage(PAGE_RESULTS);progress.setVisibility(View.VISIBLE);updateButtons(true);requestNotificationPermissionIfNeeded();}
            }catch(Exception failure){setStatus(getString(R.string.rel_failed));}return;}
            if(data.getBooleanExtra(EditorActivity.EXTRA_EXPORT,false)){exportEditedProject();return;}
            if(data.getBooleanExtra(ProjectLibraryActivity.EXTRA_CHANGED,false)){
                TranslationProject p=TranslationProjectStore.load(this);resultFile=null;resultTextFile=null;
                if(p!=null&&p.completed&&p.exportsComplete)showSavedProjectResult(p);
                setStatus(getString(R.string.rel_project_selected));
            }
        }
        updateButtons(currentBusy);
    }

    private long shownProjectUpdatedAt;
    private void showSavedProjectResult(TranslationProject project) {
        File product=ProjectResults.result(project);
        if(!product.isFile())return;
        if(resultFile!=null&&resultFile.equals(product)&&shownProjectUpdatedAt==project.updatedAt)return;
        try{if(!project.outputBinding.equals(ProjectResults.binding(project)))return;}catch(Exception invalid){return;}
        shownProjectUpdatedAt=project.updatedAt;
        resultFile=product;resultTextFile=ProjectResults.text(project);pairsText=ProjectResults.pairs(project);resultText.setText(pairsText);
        Bitmap thumbnail=null;
        for(int i=0;i<project.pages.size()&&thumbnail==null;i++)thumbnail=BitmapIo.decodePreview(SavedJobPipeline.renderedPage(project,i));
        if(thumbnail!=null){Bitmap oldResult=resultBitmap,oldSource=originalBitmap;resultBitmap=thumbnail;previewImage.setImageBitmap(thumbnail);
            if(selectedImageFile!=null&&selectedImageFile.isFile())originalBitmap=null;
            if(oldResult!=null&&oldResult!=thumbnail&&oldResult!=originalBitmap&&!oldResult.isRecycled())oldResult.recycle();
            if(oldSource!=null&&oldSource!=originalBitmap&&oldSource!=oldResult&&!oldSource.isRecycled())oldSource.recycle();
        }
        updateButtons(false);
    }

    private void translateSelected() {
        if (BuildConfig.PUBLIC_EDITION) { translatePublicSelection(); return; }
        // Invalid optional names must not consume an account allowance or start OCR.
        if (comicControls != null && !comicControls.validate()) return;
        int jobPages = selectedCbzFile != null
                ? Math.max(1, selectedCbzPages)
                : (!selectedBatchFiles.isEmpty()
                        ? selectedBatchFiles.size() : 1);
        // Koorix public edition: jobs are gated behind the signed-in
        // account's daily allowance (checked server-side). The
        // personal KurdMan build runs the job immediately.
        performTranslateSelected();
    }

    private void performTranslateSelected() {
        final Bitmap sourceBitmap = originalBitmap;
        final File imageFile = selectedImageFile;
        final File cbzFile = selectedCbzFile;
        final String cbzName = selectedName;
        final List<File> batchFiles = new ArrayList<>(selectedBatchFiles);
        final List<String> batchNames = new ArrayList<>(selectedBatchNames);
        final String batchLabel = selectedBatchLabel;
        final String batchBase = selectedBatchBase;
        final boolean imageBatch = cbzFile == null && batchFiles.size() > 1;
        final TextStyleSettings chosenStyle = new TextStyleSettings(globalTextStyle);
        if (!imageBatch && sourceBitmap == null
                && (cbzFile == null || !cbzFile.exists())) {
            DiagnosticLog.warning(getApplicationContext(), "translation",
                    "Translate tapped with no selected image/CBZ.");
            setStatus(getString(R.string.ks_0524));
            return;
        }
        final String sourceLanguage = selectedSourceLanguage();
        final String targetLanguage = selectedTargetLanguage();
        refreshComicSkillUi();
        final ComicTranslationSkill.Config comicConfig = comicControls.forTranslation();
        if (comicConfig == null) return;
        ManhwaRenderer.activeTargetLanguage = targetLanguage;
        ManhwaRenderer.i18nContext = getApplicationContext();
        JobRuntime.init(getApplicationContext());
        final boolean offlineMode = selectedOfflineMode();
        final boolean accurateOcr = accurateOcrCheck == null
                || accurateOcrCheck.isChecked();
        final ManhwaRenderer.BlockOcr unlimitedOcr =
                createUnlimitedOcrIfEnabled();
        final boolean createPdf = selectedCreatePdf();
        if (offlineMode) {
            refreshOfflineModelUi();
            if ("ckb".equals(targetLanguage)) {
                String pair = sourceLanguage + ">" + targetLanguage;
                if (offlineModelManager == null
                        || !offlineModelManager.isModelReady()
                        || !offlinePackReady
                        || !pair.equals(offlinePackPair)) {
                    setStatus(getString(R.string.ks_0737));
                    return;
                }
            } else if (!MlKitOfflineEngine.isPairSupported(
                    sourceLanguage, targetLanguage)) {
                setStatus(getString(R.string.ks_0539)
                        + getString(R.string.ks_0075));
                return;
            } else {
                String pair = sourceLanguage + ">" + targetLanguage;
                if (!offlinePackReady || !pair.equals(offlinePackPair)) {
                    setStatus(getString(R.string.ks_0517)
                            + getString(R.string.ks_0123)
                            + getString(R.string.ks_0469));
                    return;
                }
            }
        }

        DiagnosticLog.info(getApplicationContext(), "translation",
                "Job started: type=" + (cbzFile != null ? "CBZ"
                        : (imageBatch ? "IMAGE_BATCH" : "IMAGE"))
                        + ", file=" + (cbzFile != null ? cbzName
                                : (imageBatch ? batchLabel : cbzName))
                        + ", pages=" + (cbzFile != null ? selectedCbzPages
                                : (imageBatch ? batchFiles.size() : 1))
                        + ", source=" + sourceLanguage
                        + ", offline=" + offlineMode
                        + ", comicSkill=" + comicConfig.enabled + ", comicGenre=" + comicConfig.genre
                        + ", accurateOcr=" + accurateOcr
                        + ", createPdf=" + createPdf
                        + ", unlimitedOcr=" + (unlimitedOcr != null));

        // Move straight to the visible result/progress page; the user should
        // not have to hunt through the other sections while a job is running.
        showMainPage(PAGE_RESULTS);
        setBusy(true);
        setStatus(offlineMode
                ? getString(R.string.ks_0685)
                : getString(R.string.ks_0219));
        executor.execute(() -> {
            JobRuntime.markThreadStarted();
            SoraniTranslator translator = null;
            PaddleOcrClient paddleOcrClient = null;
            try {
                throwIfJobStopped();
                if (cbzFile != null || imageBatch) {
                    translateMultiPageJob(cbzFile,
                            imageBatch ? batchFiles : null,
                            imageBatch ? batchNames : null,
                            imageBatch ? batchLabel : cbzName,
                            imageBatch ? batchBase : null,
                            sourceLanguage, targetLanguage, offlineMode, comicConfig,
                            accurateOcr,
                            createPdf, chosenStyle, unlimitedOcr);
                    return;
                }

                String imageBase = sanitizeFileName(
                        baseNameWithoutExtension(cbzName));
                if (imageBase.isEmpty()) {
                    imageBase = "kurdman-image";
                }
                TranslationProject project = TranslationProjectStore.createNew(
                        getApplicationContext(), false, cbzName,
                        imageBase, sourceLanguage);
                String sourceExtension = extensionWithDot(cbzName, ".jpg");
                TranslationProject.Page projectPage = new TranslationProject.Page(
                        TranslationProjectStore.pageRelativePath(1, sourceExtension),
                        sourceExtension);
                File projectSource = projectPage.sourceFile(project.projectDir);
                if (imageFile != null && imageFile.exists()) {
                    copyFile(imageFile, projectSource);
                } else {
                    projectPage.sourcePath = TranslationProjectStore.pageRelativePath(
                            1, ".jpg");
                    projectPage.originalExtension = ".jpg";
                    writeJpeg(projectPage.sourceFile(project.projectDir), sourceBitmap);
                }
                projectPage.width = sourceBitmap.getWidth();
                projectPage.height = sourceBitmap.getHeight();
                project.targetLanguage = targetLanguage;
                project.comicConfig = comicConfig;
                project.pages.add(projectPage);
                project.save();

                paddleOcrClient = startPaddleOcrIfAvailable(
                        sourceLanguage, accurateOcr);
                List<Segment> segments;
                try {
                    segments = ManhwaRenderer.recognize(
                            sourceBitmap,
                            sourceLanguage,
                            new ManhwaRenderer.Progress() {
                                @Override
                                public void onProgress(String message) {
                                    setStatus(message);
                                }
                            },
                            accurateOcr,
                            createPaddleSecondaryOcr(paddleOcrClient),
                            unlimitedOcr);
                } finally {
                    closePaddleOcrClient(paddleOcrClient);
                    paddleOcrClient = null;
                }
                if (segments.isEmpty()) {
                    throw new IllegalStateException(getString(R.string.ks_0334));
                }

                projectPage.segments.addAll(segments);
                project.save();
                translator = createTranslator(offlineMode, targetLanguage, comicConfig);
                translateSegments(translator, segments, sourceLanguage, comicConfig, offlineMode
                        ? getString(R.string.ks_0686) : getString(R.string.ks_0371));
                project.save();

                // Free the (offline) model before rendering copies the full
                // bitmap. Holding the offline pack sessions plus two large
                // bitmaps at once is what made low-RAM phones lag or kill
                // the app. The finally block below tolerates a null here.
                closeTranslator(translator);
                translator = null;

                throwIfJobStopped();
                setStatus(getString(R.string.ks_0208));
                ManhwaRenderer.RenderResult renderResult =
                        ManhwaRenderer.render(sourceBitmap, segments,
                                chosenStyle, styledTypefaceResolver());
                // A block split at a balloon waist during rendering is
                // represented by its two halves from now on, so the
                // saved project/editor matches the picture. (Mutate the
                // list instead of reassigning: lambdas capture it.)
                segments.clear();
                segments.addAll(renderResult.segments);
                projectPage.segments.clear();
                projectPage.segments.addAll(segments);
                project.completed = true;
                project.save();
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
                String pdfNote = createAndSavePdfCopy(
                        createPdf, imageBase + "." + outSuffix() + ".pdf", rendered);

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
                        ? getString(R.string.ks_0047)
                        : (savedUri != null
                                ? getString(R.string.ks_0049)
                                : getString(R.string.ks_0051));
                savedNote += pdfNote;
                savedNote += getString(R.string.ks_0015);
                if (renderResult.rendered == 0) {
                    setStatus(getString(R.string.ks_0520)
                            + getString(R.string.ks_0324) + savedNote);
                } else if (renderResult.skipped() > 0) {
                    setStatus("⚠ " + renderResult.rendered + "/" + segments.size()
                            + getString(R.string.ks_0030) + renderResult.skipped()
                            + getString(R.string.ks_0023) + savedNote);
                } else {
                    setStatus("✅ " + renderResult.rendered + "/" + segments.size()
                            + getString(R.string.ks_0029) + savedNote);
                }
            } catch (Throwable e) {
                // Catch Errors too (OutOfMemoryError, LinkageError, ...):
                // an uncaught failure in this background thread would
                // otherwise kill the whole app process.
                if (JobRuntime.wasStopped(e)) {
                    setStatus(getString(R.string.ks_0679));
                } else {
                    DiagnosticLog.error(getApplicationContext(), "translation", e,
                            "type=" + (cbzFile != null ? "CBZ"
                                    : (imageBatch ? "IMAGE_BATCH" : "IMAGE"))
                                    + ", file=" + (imageBatch ? batchLabel : cbzName)
                                    + ", source=" + sourceLanguage
                                    + ", offline=" + offlineMode
                                    + ", accurateOcr=" + accurateOcr
                                    + ", createPdf=" + createPdf);
                    setStatus("❌ " + friendlyTranslationError(e)
                            + getString(R.string.ks_0054));
                }
            } finally {
                closePaddleOcrClient(paddleOcrClient);
                closeTranslator(translator);
                setBusy(false);
            }
        });
    }

    private void translateMultiPageJob(File cbz,
                                       List<File> selectedImagePages,
                                       List<String> selectedPageNames,
                                       String displayName,
                                       String projectBaseOverride,
                                       String sourceLanguage,
                                       String targetLanguage,
                                       boolean offlineMode,
                                       ComicTranslationSkill.Config comicConfig,
                                       boolean accurateOcr,
                                       boolean createPdf,
                                       TextStyleSettings chosenStyle,
                                       ManhwaRenderer.BlockOcr unlimitedOcr)
            throws Exception {
        final boolean imageBatch = cbz == null;
        final boolean saveIndividualPages = imageBatch;
        String projectBase = sanitizeFileName(projectBaseOverride == null
                ? "" : projectBaseOverride);
        if (projectBase.isEmpty()) {
            projectBase = sanitizeFileName(baseNameWithoutExtension(displayName));
        }
        if (projectBase.isEmpty()) {
            projectBase = imageBatch ? "kurdman-pages" : "chapter";
        }
        TranslationProject project = TranslationProjectStore.createNew(
                getApplicationContext(), true, displayName,
                projectBase, sourceLanguage);
        project.targetLanguage = targetLanguage;
        project.comicConfig = comicConfig;
        File inputDir = new File(project.projectDir, "pages");
        File outputDir = new File(getCacheDir(), "cbz-output-" + timestamp());
        if ((!inputDir.exists() && !inputDir.mkdirs())
                || (!outputDir.exists() && !outputDir.mkdirs())) {
            throw new IOException("Could not create multi-page work directory");
        }

        SoraniTranslator translator = null;
        PaddleOcrClient paddleOcrClient = null;
        try {
            throwIfJobStopped();
            List<File> pages;
            if (cbz != null) {
                setStatus(getString(R.string.ks_0217));
                pages = extractCbzImages(cbz, inputDir);
                if (pages.isEmpty()) {
                    throw new IOException("No images were found inside this CBZ");
                }
            } else {
                setStatus(getString(R.string.ks_0128));
                pages = prepareSelectedImagePages(
                        selectedImagePages, selectedPageNames, inputDir);
                if (pages.isEmpty()) {
                    throw new IOException("No selected images could be prepared");
                }
            }
            for (int i = 0; i < pages.size(); i++) {
                File pageFile = pages.get(i);
                project.pages.add(new TranslationProject.Page(
                        "pages/" + pageFile.getName(),
                        extensionWithDot(pageFile.getName(), ".jpg")));
            }
            project.save();

            StringBuilder allPairs = new StringBuilder();
            Bitmap firstRendered = null;
            int totalSegments = 0;
            int totalRendered = 0;
            int totalSkipped = 0;
            int partialPages = 0;
            int individualSaved = 0;
            final int pageTotal = pages.size();

            // Work in three memory-separated phases so the (offline) model
            // is never resident at the same time as page bitmaps or the
            // full-size render copies. Keeping all of them together was
            // enough to make mid-range phones lag heavily or kill the app.
            //
            // Phase 1: OCR every page; a null entry marks a page whose
            // bitmap could not be decoded (copied through in phase 3).
            paddleOcrClient = startPaddleOcrIfAvailable(
                    sourceLanguage, accurateOcr);
            ManhwaRenderer.SecondaryOcr paddleSecondaryOcr =
                    createPaddleSecondaryOcr(paddleOcrClient);
            List<List<Segment>> pageSegments = new ArrayList<>(pageTotal);
            for (int i = 0; i < pageTotal; i++) {
                throwIfJobStopped();
                final int pageNumber = i + 1;
                File pageFile = pages.get(i);
                TranslationProject.Page projectPage = project.pages.get(i);
                setStatus(getString(R.string.ks_0301) + pageNumber + "/" + pageTotal + getString(R.string.ks_0080));

                Bitmap pageBitmap = decodeBitmap(pageFile);
                if (pageBitmap == null) {
                    projectPage.unreadable = true;
                    pageSegments.add(null);
                    continue;
                }
                try {
                    projectPage.width = pageBitmap.getWidth();
                    projectPage.height = pageBitmap.getHeight();
                    List<Segment> recognized = ManhwaRenderer.recognize(
                            pageBitmap,
                            sourceLanguage,
                            new ManhwaRenderer.Progress() {
                                @Override
                                public void onProgress(String message) {
                                    setStatus(getString(R.string.ks_0301) + pageNumber + "/"
                                            + pageTotal + ": " + message);
                                }
                            },
                            accurateOcr,
                            paddleSecondaryOcr,
                            unlimitedOcr);
                    projectPage.segments.addAll(recognized);
                    pageSegments.add(recognized);
                } finally {
                    pageBitmap.recycle();
                }
            }
            project.save();
            closePaddleOcrClient(paddleOcrClient);
            paddleOcrClient = null;

            // Phase 2: translate everything with no page bitmaps in memory,
            // then free the model before any rendering happens.
            translator = createTranslator(offlineMode, targetLanguage, comicConfig);
            for (int i = 0; i < pageTotal; i++) {
                throwIfJobStopped();
                List<Segment> segments = pageSegments.get(i);
                if (segments == null) {
                    continue;
                }
                int pageNumber = i + 1;
                translateSegments(translator, segments, sourceLanguage, comicConfig,
                        getString(R.string.ks_0301) + pageNumber + "/" + pageTotal
                                + (offlineMode ? getString(R.string.ks_0084) : getString(R.string.ks_0083)));
            }
            project.save();
            closeTranslator(translator);
            translator = null;

            // Phase 3: render (or copy through) each page.
            for (int i = 0; i < pageTotal; i++) {
                throwIfJobStopped();
                int pageNumber = i + 1;
                File pageFile = pages.get(i);
                List<Segment> segments = pageSegments.get(i);

                if (segments == null) {
                    File keptPage = new File(outputDir, String.format(Locale.ROOT,
                            "%04d%s", pageNumber,
                            extensionWithDot(pageFile.getName(), ".jpg")));
                    copyFile(pageFile, keptPage);
                    if (allPairs.length() > 0) {
                        allPairs.append("\n\n");
                    }
                    allPairs.append(getString(R.string.ks_0085)).append(pageNumber)
                            .append('/').append(pageTotal).append(" ===\n")
                            .append(getString(R.string.ks_0071));
                    continue;
                }

                setStatus(getString(R.string.ks_0301) + pageNumber + "/" + pageTotal
                        + getString(R.string.ks_0081));
                Bitmap pageBitmap = decodeBitmap(pageFile);
                if (pageBitmap == null) {
                    // Decoded fine in phase 1 but not now: keep the original
                    // page and report its segments as text-only.
                    File keptPage = new File(outputDir, String.format(Locale.ROOT,
                            "%04d%s", pageNumber,
                            extensionWithDot(pageFile.getName(), ".jpg")));
                    copyFile(pageFile, keptPage);
                    int pageSkipped = segments.size();
                    totalSegments += segments.size();
                    totalSkipped += pageSkipped;
                    if (pageSkipped > 0) {
                        partialPages++;
                    }
                    if (allPairs.length() > 0) {
                        allPairs.append("\n\n");
                    }
                    allPairs.append(getString(R.string.ks_0085)).append(pageNumber)
                            .append('/').append(pageTotal).append(" ===\n");
                    allPairs.append(segments.isEmpty()
                            ? getString(R.string.ks_0076)
                            : buildPairs(segments));
                    if (pageSkipped > 0) {
                        allPairs.append("\n[⚠ ").append(pageSkipped)
                                .append(getString(R.string.ks_0025))
                                .append(getString(R.string.ks_0376));
                    }
                    continue;
                }

                ManhwaRenderer.RenderResult renderResult =
                        ManhwaRenderer.render(pageBitmap, segments,
                                chosenStyle, styledTypefaceResolver());
                // Keep the saved project in sync when rendering split
                // a two-lobe block into its halves. (Mutate instead of
                // reassigning: lambdas capture the variable.)
                segments.clear();
                segments.addAll(renderResult.segments);
                project.pages.get(i).segments.clear();
                project.pages.get(i).segments.addAll(segments);
                Bitmap rendered = renderResult.bitmap;
                File outPage = new File(outputDir,
                        String.format(Locale.ROOT, "%04d.jpg", pageNumber));
                writeJpeg(outPage, rendered);
                if (saveIndividualPages && saveIndividualBatchPage(
                        outPage, selectedPageNames, i, projectBase, pageNumber)) {
                    individualSaved++;
                }
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
                allPairs.append(getString(R.string.ks_0085)).append(pageNumber)
                        .append('/').append(pageTotal).append(" ===\n");
                allPairs.append(segments.isEmpty()
                        ? getString(R.string.ks_0076)
                        : buildPairs(segments));
                if (!segments.isEmpty() && pageSkipped > 0) {
                    allPairs.append("\n[⚠ ").append(pageSkipped)
                            .append(getString(R.string.ks_0025))
                            .append(getString(R.string.ks_0376));
                }
            }

            String base = projectBase;
            project.completed = true;
            project.save();
            File outCbz = new File(getCacheDir(), base + "." + outSuffix() + ".cbz");
            zipDirectory(outputDir, outCbz);

            String pairs = allPairs.toString().trim();
            File textFile = writeTextToCache(pairs, base + "." + outSuffix() + ".txt");
            Uri downloadCbzUri = saveFileToDownloads(outCbz, "application/zip");
            saveFileToDownloads(textFile, "text/plain");
            copyToDownloadsLibrary(outCbz);
            copyToDownloadsLibrary(textFile);
            String pdfNote = createAndSavePdfCopy(
                    createPdf, base + "." + outSuffix() + ".pdf", sortedOutputPageFiles(outputDir));

            final Bitmap preview = firstRendered;
            String pairsForUi = pairs.length() > 60000
                    ? pairs.substring(0, 60000)
                            + getString(R.string.ks_0002)
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

            String archiveLabel = imageBatch ? getString(R.string.ks_0095) : "CBZ";
            String savedNote = downloadCbzUri != null
                    ? " " + archiveLabel + getString(R.string.ks_0048)
                    : " " + archiveLabel + getString(R.string.ks_0050);
            if (saveIndividualPages) {
                savedNote += " " + individualSaved
                        + getString(R.string.ks_0059);
            }
            savedNote += pdfNote;
            savedNote += getString(R.string.ks_0016);
            DiagnosticLog.info(getApplicationContext(), "translation",
                    "Multi-page job finished: type="
                            + (imageBatch ? "IMAGE_BATCH" : "CBZ")
                            + ", pages=" + pages.size()
                            + ", rendered=" + totalRendered
                            + ", segments=" + totalSegments
                            + ", skipped=" + totalSkipped
                            + ", partialPages=" + partialPages
                            + ", individualPagesSaved=" + individualSaved);
            if (totalSegments == 0) {
                setStatus("⚠ " + archiveLabel
                        + getString(R.string.ks_0021) + savedNote);
            } else if (totalSkipped > 0) {
                setStatus("⚠ " + archiveLabel + getString(R.string.ks_0014) + pages.size() + getString(R.string.ks_0044)
                        + totalRendered + "/" + totalSegments
                        + getString(R.string.ks_0028) + totalSkipped
                        + getString(R.string.ks_0024) + partialPages
                        + getString(R.string.ks_0045) + savedNote);
            } else {
                setStatus("✅ " + archiveLabel + getString(R.string.ks_0014) + pages.size() + getString(R.string.ks_0044)
                        + totalRendered + "/" + totalSegments
                        + getString(R.string.ks_0027) + savedNote);
            }
        } finally {
            closePaddleOcrClient(paddleOcrClient);
            closeTranslator(translator);
            // Keep inputDir: it is the editable project's permanent page
            // store under files/projects/current/pages. Only rendered output
            // pages in cache are temporary.
            deleteRecursive(outputDir);
        }
    }

    private List<File> prepareSelectedImagePages(List<File> sources,
                                                  List<String> names,
                                                  File destDir) throws Exception {
        List<File> pages = new ArrayList<>();
        if (sources == null || sources.isEmpty()) {
            return pages;
        }
        if (sources.size() > 300) {
            throw new IOException("Too many selected images (max 300)");
        }
        long totalBytes = 0L;
        for (int i = 0; i < sources.size(); i++) {
            throwIfJobStopped();
            File source = sources.get(i);
            if (source == null || !source.exists()) {
                throw new IOException("Selected image is missing: " + (i + 1));
            }
            String displayName = names != null && i < names.size()
                    ? names.get(i) : source.getName();
            File out = new File(destDir, String.format(Locale.ROOT,
                    "%04d%s", i + 1, extensionWithDot(displayName, ".jpg")));
            totalBytes += copyFile(source, out);
            if (totalBytes > 1024L * 1024L * 1024L) {
                throw new IOException(
                        "Selected images are too large together (max 1 GB)");
            }
            pages.add(out);
        }
        return pages;
    }

    private boolean saveIndividualBatchPage(File renderedPage,
                                            List<String> sourceNames,
                                            int pageIndex,
                                            String projectBase,
                                            int pageNumber) {
        try {
            String sourceName = sourceNames != null
                    && pageIndex >= 0 && pageIndex < sourceNames.size()
                    ? sourceNames.get(pageIndex) : renderedPage.getName();
            String base = sanitizeFileName(baseNameWithoutExtension(sourceName));
            if (base.isEmpty()) {
                base = projectBase + "-"
                        + String.format(Locale.ROOT, "%04d", pageNumber);
            }
            File individual = new File(getCacheDir(), base + "." + outSuffix() + ".jpg");
            copyFile(renderedPage, individual);
            Uri downloadUri = saveFileToDownloads(individual, "image/jpeg");
            File libraryFile = copyToDownloadsLibrary(individual);
            return downloadUri != null || libraryFile != null;
        } catch (Exception e) {
            DiagnosticLog.warning(getApplicationContext(), "downloads",
                    "Could not save individual batch page " + pageNumber
                            + ": " + DiagnosticLog.shortSummary(e));
            return false;
        }
    }

    private void exportEditedProject() {
        final TranslationProject project = TranslationProjectStore.load(this);
        if (project == null || !project.completed || project.pages.isEmpty()) {
            setStatus(getString(R.string.ks_0540));
            updateButtons(false);
            return;
        }
        ManhwaRenderer.activeTargetLanguage = project.targetLanguage;
        ManhwaRenderer.i18nContext = getApplicationContext();
        JobRuntime.init(getApplicationContext());
        final boolean createPdf = selectedCreatePdf();
        showMainPage(PAGE_RESULTS);
        setBusy(true);
        setStatus(getString(R.string.ks_0218));
        executor.execute(() -> {
            JobRuntime.markThreadStarted();
            File outputDir = null;
            try {
                throwIfJobStopped();
                StyledTextRenderer.TypefaceResolver resolver = styledTypefaceResolver();
                if (!project.cbz) {
                    TranslationProject.Page page = project.pages.get(0);
                    File source = page.sourceFile(project.projectDir);
                    Bitmap sourceBitmap = source == null ? null : decodeBitmap(source);
                    if (sourceBitmap == null) {
                        throw new IOException("Could not decode the saved project page");
                    }
                    StyledTextRenderer.EditedResult result =
                            StyledTextRenderer.renderEdited(
                                    sourceBitmap, page.segments, resolver);
                    sourceBitmap.recycle();
                    final Bitmap rendered = result.bitmap;
                    File cached = new File(getCacheDir(),
                            "kurdman-edited-" + timestamp() + ".jpg");
                    writeJpeg(cached, rendered);
                    Uri savedUri = saveToPictures(rendered);
                    String pairs = buildPairs(page.segments);
                    File textFile = writeTextToCache(pairs,
                            "kurdman-edited-" + timestamp() + ".txt");
                    Uri downloadImageUri = saveFileToDownloads(cached, "image/jpeg");
                    Uri downloadTextUri = saveFileToDownloads(textFile, "text/plain");
                    copyToDownloadsLibrary(cached);
                    copyToDownloadsLibrary(textFile);
                    String pdfBase = sanitizeFileName(project.baseName);
                    if (pdfBase.isEmpty()) {
                        pdfBase = "kurdman-image";
                    }
                    String pdfNote = createAndSavePdfCopy(
                            createPdf, pdfBase + "." + outSuffix() + ".pdf", rendered);
                    project.save();

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
                            ? getString(R.string.ks_0048)
                            : (savedUri != null
                                    ? getString(R.string.ks_0049)
                                    : getString(R.string.ks_0051));
                    setStatus(getString(R.string.ks_0575)
                            + result.rendered + "/" + page.segments.size()
                            + getString(R.string.ks_0029) + savedNote + pdfNote);
                    return;
                }

                outputDir = new File(getCacheDir(),
                        "cbz-edited-output-" + timestamp());
                if (!outputDir.exists() && !outputDir.mkdirs()) {
                    throw new IOException("Could not create edited CBZ output directory");
                }
                StringBuilder allPairs = new StringBuilder();
                Bitmap firstRendered = null;
                int totalSegments = 0;
                int totalRendered = 0;
                int totalSkipped = 0;
                int partialPages = 0;
                final int pageTotal = project.pages.size();

                for (int i = 0; i < pageTotal; i++) {
                    throwIfJobStopped();
                    int pageNumber = i + 1;
                    TranslationProject.Page page = project.pages.get(i);
                    File source = page.sourceFile(project.projectDir);
                    if (source == null || !source.exists()) {
                        throw new IOException("Saved project page is missing: " + pageNumber);
                    }
                    if (allPairs.length() > 0) {
                        allPairs.append("\n\n");
                    }
                    allPairs.append(getString(R.string.ks_0085)).append(pageNumber)
                            .append('/').append(pageTotal).append(" ===\n");

                    if (page.unreadable) {
                        File kept = new File(outputDir, String.format(Locale.ROOT,
                                "%04d%s", pageNumber,
                                extensionWithDot(source.getName(), ".jpg")));
                        copyFile(source, kept);
                        allPairs.append(getString(R.string.ks_0071));
                        continue;
                    }

                    setStatus(getString(R.string.ks_0301) + pageNumber + "/" + pageTotal
                            + getString(R.string.ks_0082));
                    Bitmap pageBitmap = decodeBitmap(source);
                    if (pageBitmap == null) {
                        File kept = new File(outputDir, String.format(Locale.ROOT,
                                "%04d%s", pageNumber,
                                extensionWithDot(source.getName(), ".jpg")));
                        copyFile(source, kept);
                        totalSegments += page.segments.size();
                        totalSkipped += page.segments.size();
                        if (!page.segments.isEmpty()) {
                            partialPages++;
                        }
                        allPairs.append(page.segments.isEmpty()
                                ? getString(R.string.ks_0076)
                                : buildPairs(page.segments));
                        continue;
                    }

                    StyledTextRenderer.EditedResult result =
                            StyledTextRenderer.renderEdited(
                                    pageBitmap, page.segments, resolver);
                    Bitmap rendered = result.bitmap;
                    File outPage = new File(outputDir,
                            String.format(Locale.ROOT, "%04d.jpg", pageNumber));
                    writeJpeg(outPage, rendered);
                    if (firstRendered == null) {
                        firstRendered = rendered;
                    } else {
                        rendered.recycle();
                    }
                    pageBitmap.recycle();

                    totalSegments += page.segments.size();
                    totalRendered += result.rendered;
                    totalSkipped += result.skipped;
                    if (!page.segments.isEmpty() && result.skipped > 0) {
                        partialPages++;
                    }
                    allPairs.append(page.segments.isEmpty()
                            ? getString(R.string.ks_0076)
                            : buildPairs(page.segments));
                    if (!page.segments.isEmpty() && result.skipped > 0) {
                        allPairs.append("\n[⚠ ").append(result.skipped)
                                .append(getString(R.string.ks_0026))
                                .append(getString(R.string.ks_0377));
                    }
                }

                project.save();
                String base = sanitizeFileName(project.baseName);
                if (base.isEmpty()) {
                    base = "chapter";
                }
                File outCbz = new File(getCacheDir(), base + "." + outSuffix() + ".cbz");
                zipDirectory(outputDir, outCbz);
                String pairs = allPairs.toString().trim();
                File textFile = writeTextToCache(pairs, base + "." + outSuffix() + ".txt");
                Uri downloadCbzUri = saveFileToDownloads(outCbz, "application/zip");
                saveFileToDownloads(textFile, "text/plain");
                copyToDownloadsLibrary(outCbz);
                copyToDownloadsLibrary(textFile);
                String pdfNote = createAndSavePdfCopy(
                        createPdf, base + "." + outSuffix() + ".pdf", sortedOutputPageFiles(outputDir));

                final Bitmap preview = firstRendered;
                String pairsForUi = pairs.length() > 60000
                        ? pairs.substring(0, 60000)
                                + getString(R.string.ks_0002)
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
                        ? getString(R.string.ks_0011)
                        : getString(R.string.ks_0012);
                savedNote += pdfNote;
                if (totalSegments == 0) {
                    setStatus(getString(R.string.ks_0506) + savedNote);
                } else if (totalSkipped > 0) {
                    setStatus(getString(R.string.ks_0505) + pageTotal + getString(R.string.ks_0044)
                            + totalRendered + "/" + totalSegments
                            + getString(R.string.ks_0028) + totalSkipped
                            + getString(R.string.ks_0024) + partialPages + getString(R.string.ks_0046) + savedNote);
                } else {
                    setStatus(getString(R.string.ks_0549) + pageTotal + getString(R.string.ks_0044)
                            + totalRendered + "/" + totalSegments
                            + getString(R.string.ks_0027) + savedNote);
                }
            } catch (Throwable e) {
                if (JobRuntime.wasStopped(e)) {
                    setStatus(getString(R.string.ks_0675));
                } else {
                    setStatus("❌ " + friendlyTranslationError(e));
                }
            } finally {
                if (outputDir != null) {
                    deleteRecursive(outputDir);
                }
                setBusy(false);
            }
        });
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
            setStatus(getString(R.string.ks_0525));
            return;
        }
        saveSettings();
        setBusy(true);
        setStatus(getString(R.string.ks_0315) + formatMb(file.length()) + ")…");
        executor.execute(() -> {
            JobRuntime.markThreadStarted();
            try {
                TelegramApi api = new TelegramApi(token);
                // Fail fast (and clearly) if the bot cannot reach this chat,
                // instead of uploading the whole file first.
                api.sendChatAction(chatId, "upload_document");
                JSONObject sent = api.sendDocument(
                        chatId, file, getString(R.string.ks_0557));
                long messageId = sent.optLong("message_id", 0);
                String doneNote = messageId > 0
                        ? getString(R.string.ks_0558) + messageId + ")."
                        : getString(R.string.ks_0559);
                setStatus(doneNote);

                // The document is already delivered at this point; report a
                // text failure separately instead of marking everything failed.
                if (pairs != null && !pairs.trim().isEmpty()) {
                    try {
                        if (pairs.length() > 12000 && textFile != null && textFile.exists()) {
                            api.sendDocument(chatId, textFile,
                                    getString(R.string.ks_0654));
                            setStatus(doneNote + getString(R.string.ks_0034));
                        } else {
                            List<String> chunks = splitText(pairs, 3300);
                            for (int i = 0; i < chunks.size(); i++) {
                                String prefix = i == 0 ? getString(R.string.ks_0653) : "";
                                api.sendMessage(chatId, prefix + chunks.get(i));
                            }
                            setStatus(doneNote + getString(R.string.ks_0033));
                        }
                    } catch (Exception textError) {
                        setStatus(doneNote + getString(R.string.ks_0018)
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
        return BitmapIo.decodeUri(this, uri);
    }

    private Bitmap decodeBitmap(File file) {
        return BitmapIo.decodeFile(file);
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

    private String createAndSavePdfCopy(boolean enabled, String fileName,
                                        Bitmap rendered)
            throws InterruptedException {
        if (!enabled) {
            return "";
        }
        File pdfFile = new File(getCacheDir(), sanitizeFileName(fileName));
        if (pdfFile.exists()) {
            pdfFile.delete();
        }
        try {
            throwIfJobStopped();
            setStatus(getString(R.string.ks_0647));
            int pdfPages = PdfExporter.writeBitmap(rendered, pdfFile,
                    (completedSources, totalSources, completedPdfPages) -> {
                        throwIfJobStopped();
                        if (completedPdfPages == 1 || completedPdfPages % 10 == 0) {
                            setStatus(getString(R.string.ks_0648)
                                    + completedPdfPages + getString(R.string.ks_0039));
                        }
                    });
            throwIfJobStopped();
            return saveGeneratedPdfCopy(pdfFile, pdfPages);
        } catch (Throwable e) {
            if (pdfFile.exists()) {
                pdfFile.delete();
            }
            if (JobRuntime.wasStopped(e)) {
                throw new InterruptedException(JobRuntime.STOPPED_MESSAGE);
            }
            return getString(R.string.ks_0069);
        }
    }

    private String createAndSavePdfCopy(boolean enabled, String fileName,
                                        List<File> pageFiles)
            throws InterruptedException {
        if (!enabled) {
            return "";
        }
        File pdfFile = new File(getCacheDir(), sanitizeFileName(fileName));
        if (pdfFile.exists()) {
            pdfFile.delete();
        }
        final int[] lastReportedSource = {-1};
        try {
            throwIfJobStopped();
            setStatus(getString(R.string.ks_0645));
            int pdfPages = PdfExporter.writeImageFiles(pageFiles, pdfFile,
                    (completedSources, totalSources, completedPdfPages) -> {
                        throwIfJobStopped();
                        if (completedSources != lastReportedSource[0]
                                || completedPdfPages % 25 == 0) {
                            lastReportedSource[0] = completedSources;
                            setStatus(getString(R.string.ks_0646)
                                    + completedSources + "/" + totalSources + "…");
                        }
                    });
            throwIfJobStopped();
            return saveGeneratedPdfCopy(pdfFile, pdfPages);
        } catch (Throwable e) {
            if (pdfFile.exists()) {
                pdfFile.delete();
            }
            if (JobRuntime.wasStopped(e)) {
                throw new InterruptedException(JobRuntime.STOPPED_MESSAGE);
            }
            return getString(R.string.ks_0068);
        }
    }

    private String saveGeneratedPdfCopy(File pdfFile, int pdfPages) {
        Uri downloadUri = saveFileToDownloads(pdfFile, "application/pdf");
        File libraryFile = copyToDownloadsLibrary(pdfFile);
        if (libraryFile != null) {
            return getString(R.string.ks_0013) + pdfPages + getString(R.string.ks_0042)
                    + getString(R.string.ks_0121);
        }
        if (downloadUri != null) {
            return getString(R.string.ks_0013) + pdfPages + getString(R.string.ks_0041)
                    + getString(R.string.ks_0402);
        }
        return getString(R.string.ks_0070);
    }

    private List<File> sortedOutputPageFiles(File outputDir) {
        List<File> files = new ArrayList<>();
        if (outputDir == null) {
            return files;
        }
        File[] children = outputDir.listFiles();
        if (children == null) {
            return files;
        }
        for (File child : children) {
            if (child.isFile()
                    && isImageEntry(child.getName().toLowerCase(Locale.ROOT))) {
                files.add(child);
            }
        }
        Collections.sort(files,
                (a, b) -> naturalCompare(a.getName(), b.getName()));
        return files;
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
            if (JobRuntime.isRunning() && JobRuntime.isStopRequested()) {
                throw new IOException(JobRuntime.STOPPED_MESSAGE);
            }
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
            empty.setText(getString(R.string.ks_0343));
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
                    + getString(R.string.ks_0038));
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

        Button open = createSmallButton(getString(R.string.ks_0453), true);
        LinearLayout.LayoutParams openParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        open.setLayoutParams(openParams);
        open.setOnClickListener(v -> openDownloadedFile(file));
        actions.addView(open);

        Button share = createSmallButton(getString(R.string.ks_0326), false);
        LinearLayout.LayoutParams shareParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        shareParams.setMarginStart(dp(8));
        share.setLayoutParams(shareParams);
        share.setOnClickListener(v -> shareDownloadedFile(file));
        actions.addView(share);

        Button delete = createSmallButton(getString(R.string.ks_0669), false);
        delete.setBackgroundResource(R.drawable.bg_button_danger);
        delete.setTextColor(getColor(R.color.white));
        LinearLayout.LayoutParams deleteParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        deleteParams.setMarginStart(dp(8));
        delete.setLayoutParams(deleteParams);
        delete.setOnClickListener(v -> confirmDeleteDownloadedFile(file));
        actions.addView(delete);

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
            setStatus(getString(R.string.ks_0532));
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

    private void confirmDeleteDownloadedFile(File file) {
        if (file == null || !file.exists()) {
            refreshDownloadsList();
            setStatus(getString(R.string.ks_0512));
            return;
        }
        if (currentBusy || JobRuntime.isRunning()) {
            setStatus(getString(R.string.ks_0542));
            return;
        }
        try {
            File libraryDir = downloadsLibraryDir().getCanonicalFile();
            File canonicalFile = file.getCanonicalFile();
            if (canonicalFile.getParentFile() == null
                    || !libraryDir.equals(canonicalFile.getParentFile())) {
                setStatus(getString(R.string.ks_0511));
                return;
            }
        } catch (IOException e) {
            setStatus("❌ " + friendlyError(e));
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.ks_0142))
                .setMessage(file.getName() + "\n" + formatStorage(file.length())
                        + getString(R.string.ks_0001)
                        + getString(R.string.ks_0115)
                        + getString(R.string.ks_0149))
                .setNegativeButton(getString(R.string.ks_0401), null)
                .setPositiveButton(getString(R.string.ks_0174),
                        (dialog, which) -> deleteDownloadedFile(file))
                .show();
    }

    private void deleteDownloadedFile(File file) {
        String name = file.getName();
        long size = file.length();
        int publicDeleted = 0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            publicDeleted += deleteMatchingMediaStoreCopies(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    name, size, "Download/KurdMan");
            String mime = mimeForFile(file);
            if (mime.startsWith("image/")) {
                publicDeleted += deleteMatchingMediaStoreCopies(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        name, size, "Pictures/KurdMan");
            }
        }
        boolean deleted = file.delete();
        if (deleted) {
            if (resultFile != null
                    && file.getAbsolutePath().equals(resultFile.getAbsolutePath())) {
                resultFile = null;
            }
            if (resultTextFile != null
                    && file.getAbsolutePath().equals(resultTextFile.getAbsolutePath())) {
                resultTextFile = null;
            }
            refreshDownloadsList();
            updateButtons(currentBusy);
            setStatus("✅ " + name + getString(R.string.ks_0035)
                    + (publicDeleted > 0
                            ? getString(R.string.ks_0062) : ""));
        } else {
            refreshDownloadsList();
            setStatus(getString(R.string.ks_0531) + name + getString(R.string.ks_0017));
        }
    }

    private int deleteMatchingMediaStoreCopies(Uri collection, String displayName,
                                               long size,
                                               String expectedRelativePart) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return 0;
        }
        List<Uri> targets = new ArrayList<>();
        Cursor cursor = null;
        try {
            String[] projection = {
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.RELATIVE_PATH
            };
            String selection = MediaStore.MediaColumns.DISPLAY_NAME + "=?"
                    + " AND " + MediaStore.MediaColumns.SIZE + "=? AND owner_package_name=?";
            cursor = getContentResolver().query(
                    collection,
                    projection,
                    selection,
                    new String[]{displayName, Long.toString(size), getPackageName()},
                    null);
            if (cursor != null) {
                int idIndex = cursor.getColumnIndexOrThrow(
                        MediaStore.MediaColumns._ID);
                int pathIndex = cursor.getColumnIndexOrThrow(
                        MediaStore.MediaColumns.RELATIVE_PATH);
                while (cursor.moveToNext()) {
                    String relative = cursor.getString(pathIndex);
                    if (relative != null && (relative.replace('\\', '/').contains(expectedRelativePart)
                            || BuildConfig.PUBLIC_EDITION && relative.replace('\\', '/').contains(expectedRelativePart.replace("/KurdMan", "/Koorix")))) {
                        targets.add(ContentUris.withAppendedId(
                                collection, cursor.getLong(idIndex)));
                    }
                }
            }
        } catch (Exception ignored) {
            // The app-private Downloads copy below can still be deleted.
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }

        int deleted = 0;
        for (Uri target : targets) {
            try {
                deleted += getContentResolver().delete(target, null, null);
            } catch (SecurityException ignored) {
                // A same-name file owned by another app must not be touched
                // without a separate system permission prompt.
            } catch (RuntimeException ignored) {
                // Best effort for the public copy.
            }
        }
        return deleted;
    }

    private void openSystemDownloads() {
        try {
            startActivity(new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS));
        } catch (Exception e) {
            setStatus(getString(R.string.ks_0514));
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
        if (lower.endsWith(".pdf")) {
            return "application/pdf";
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
            String review = ComicSettings.warnings(this, segment.translationWarnings);
            if (!review.isEmpty()) builder.append('\n').append(review);
            if (!segment.rendered) {
                builder.append(getString(R.string.ks_0004));
            }
            if (segment.ocrConfidence >= 0f && segment.ocrConfidence < 0.78f) {
                builder.append(getString(R.string.ks_0003));
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
            return getString(R.string.ks_0270);
        }
        if (chatId == null || chatId.isEmpty()) {
            return requireChat ? getString(R.string.ks_0099) : null;
        }
        if (!chatId.matches("-?\\d{5,}")) {
            return getString(R.string.ks_0096);
        }
        String botId = token.substring(0, token.indexOf(':')).trim();
        if (chatId.equals(botId)) {
            return getString(R.string.ks_0148)
                    + getString(R.string.ks_0103);
        }
        return null;
    }

    private String timestamp() {
        return new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date());
    }

    private String friendlyError(Throwable error) {
        if (JobRuntime.wasStopped(error)) {
            return getString(R.string.ks_0451);
        }
        // Every handled failure that reaches the user also leaves a full
        // stack trace in Reports; duplicate formatting of the same error
        // is de-duplicated inside DiagnosticLog.
        DiagnosticLog.error(getApplicationContext(), "handled-error", error,
                "A handled error was formatted for display.");
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
    private String friendlyTranslationError(Throwable error) {
        if (JobRuntime.wasStopped(error)) {
            return getString(R.string.ks_0451);
        }
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
            return getString(R.string.ks_0422)
                    + getString(R.string.ks_0348)
                    + getString(R.string.ks_0180)
                    + getString(R.string.ks_0397) + raw + ")";
        }
        if (lower.contains("timed out") || lower.contains("timeout")
                || lower.contains("failed to connect")
                || lower.contains("connection reset")
                || lower.contains("connection refused")
                || lower.contains("network is unreachable")
                || lower.contains("software caused connection abort")) {
            return getString(R.string.ks_0425)
                    + getString(R.string.ks_0134) + raw + ")";
        }
        if (lower.contains("translation http 403")
                || lower.contains("translation http 429")
                || lower.contains("quota")) {
            return getString(R.string.ks_0269)
                    + getString(R.string.ks_0430) + raw + ")";
        }
        if (lower.contains("arrayindexoutofboundsexception")
                || lower.contains("indexoutofboundsexception")) {
            return getString(R.string.ks_0362)
                    + getString(R.string.ks_0330)
                    + getString(R.string.ks_0431)
                    + raw + ")";
        }
        return raw;
    }

    /** Maps common Telegram failures to a clear Kurdish explanation. */
    private String friendlyTelegramError(Exception error) {
        String raw = friendlyError(error);
        String lower = raw.toLowerCase(Locale.ROOT);
        String hint = null;
        if (lower.contains("unauthorized") || lower.contains("http 401")) {
            hint = getString(R.string.ks_0185);
        } else if (lower.contains("chat not found")) {
            hint = getString(R.string.ks_0097)
                    + getString(R.string.ks_0102);
        } else if (lower.contains("bot was blocked") || lower.contains("forbidden")) {
            hint = getString(R.string.ks_0169);
        } else if (lower.contains("file is too big") || lower.contains("too large")
                || lower.contains("http 413")) {
            hint = getString(R.string.ks_0283)
                    + getString(R.string.ks_0370);
        } else if (lower.contains("timed out") || lower.contains("timeout")
                || lower.contains("unable to resolve host")
                || lower.contains("failed to connect")
                || lower.contains("connection reset")
                || lower.contains("network is unreachable")
                || lower.contains("empty response")) {
            hint = getString(R.string.ks_0424);
        } else if (lower.contains("message is too long")) {
            hint = getString(R.string.ks_0318);
        }
        return hint == null ? raw : hint + " (" + raw + ")";
    }

    private String formatMb(long bytes) {
        return String.format(Locale.ROOT, "%.1f MB", bytes / 1024f / 1024f);
    }

    private void setStatus(final String text) {
        DiagnosticLog.status(getApplicationContext(), text);
        JobRuntime.setStatus(text);
        runOnUiThread(() -> statusText.setText(text));
        if (KeepAliveService.isServiceRunning()) {
            KeepAliveService.updateNotification(this);
        }
    }

    private void setBusy(final boolean busy) {
        if (busy) {
            JobRuntime.beginJob();
        }
        runOnUiThread(() -> {
            currentBusy = busy;
            progress.setVisibility(busy ? View.VISIBLE : View.GONE);
            if (busy) {
                // Every long user-started job gets foreground-service and
                // wake-lock protection until it ends or the user taps STOP.
                KeepAliveService.startForJob(this);
                requestNotificationPermissionIfNeeded();
            } else {
                JobRuntime.endJob();
                KeepAliveService.jobFinished(this);
            }
            updateKeepScreenFlag();
            refreshKeepAliveUi();
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
        translateButton.setEnabled(enabled && (!BuildConfig.PUBLIC_EDITION || hasPublicInput()));
        findViewById(R.id.projectLibraryButton).setEnabled(enabled);
        findViewById(R.id.pickGalleryButton).setEnabled(enabled);
        findViewById(R.id.pickFilesButton).setEnabled(enabled);
        refreshDownloadsButton.setEnabled(enabled);
        openDownloadsButton.setEnabled(enabled);
        if (sourceSpinner != null) {
            sourceSpinner.setEnabled(enabled);
        }
        if (translationModeSpinner != null) {
            translationModeSpinner.setEnabled(enabled);
        }
        if (targetSpinner != null) targetSpinner.setEnabled(enabled);
        if (comicControls != null) comicControls.setEnabled(enabled);
        ComicTranslationFlow.bindSummary(this, findViewById(android.R.id.content),
                resultFile != null ? TranslationProjectStore.load(this) : null, busy);
        findViewById(R.id.resultQualityButton).setEnabled(enabled);
        if (accurateOcrCheck != null) {
            accurateOcrCheck.setEnabled(enabled);
        }
        if (createPdfCheck != null) {
            createPdfCheck.setEnabled(enabled);
        }
        if (cleanEraseCheck != null) {
            cleanEraseCheck.setEnabled(enabled);
        }
        if (unlimitedOcrCheck != null) {
            unlimitedOcrCheck.setEnabled(enabled);
        }
        if (unlimitedOcrUrlInput != null) {
            unlimitedOcrUrlInput.setEnabled(enabled);
        }
        if (unlimitedOcrKeyInput != null) {
            unlimitedOcrKeyInput.setEnabled(enabled);
        }
        if (unlimitedOcrModelInput != null) {
            unlimitedOcrModelInput.setEnabled(enabled);
        }
        if (reportNoteInput != null) {
            reportNoteInput.setEnabled(enabled);
        }
        if (addReportNoteButton != null) {
            addReportNoteButton.setEnabled(enabled);
        }
        if (clearReportButton != null) {
            clearReportButton.setEnabled(enabled);
        }
        // Copying/sharing/refreshing a report is most useful precisely
        // while a stuck job is still on screen, so keep those enabled.
        if (copyReportButton != null) {
            copyReportButton.setEnabled(true);
        }
        if (shareReportButton != null) {
            shareReportButton.setEnabled(true);
        }
        if (refreshReportButton != null) {
            refreshReportButton.setEnabled(true);
        }
        if (fontSpinner != null) {
            fontSpinner.setEnabled(enabled);
        }
        if (stylePresetSpinner != null) {
            stylePresetSpinner.setEnabled(enabled);
        }
        if (backgroundModeSpinner != null) {
            backgroundModeSpinner.setEnabled(enabled);
        }
        if (styleSizeSeek != null) {
            styleSizeSeek.setEnabled(enabled);
        }
        if (styleWeightSeek != null) {
            styleWeightSeek.setEnabled(enabled);
        }
        if (importFontButton != null) {
            importFontButton.setEnabled(enabled);
        }
        if (textColorButton != null) {
            textColorButton.setEnabled(enabled && !globalTextStyle.autoTextColor);
        }
        if (backgroundColorButton != null) {
            backgroundColorButton.setEnabled(enabled);
        }
        if (styleAutoColorCheck != null) {
            styleAutoColorCheck.setEnabled(enabled);
        }
        if (styleOutlineCheck != null) {
            styleOutlineCheck.setEnabled(enabled);
        }
        if (styleShadowCheck != null) {
            styleShadowCheck.setEnabled(enabled);
        }
        if (styleItalicCheck != null) {
            styleItalicCheck.setEnabled(enabled);
        }
        if (keepScreenCheck != null) {
            keepScreenCheck.setEnabled(enabled);
        }
        if (batterySettingsButton != null) {
            batterySettingsButton.setEnabled(true);
        }
        if (stopJobButton != null) {
            stopJobButton.setEnabled(true);
        }
        boolean modelReady = offlinePackReady;
        boolean hasModelData = offlinePackReady
                || (offlineModelManager != null
                        && "ckb".equals(selectedTargetLanguage())
                        && offlineModelManager.downloadedBytes() > 0);
        if (downloadOfflineButton != null) {
            downloadOfflineButton.setEnabled(enabled && !modelReady);
        }
        if (deleteOfflineButton != null) {
            deleteOfflineButton.setEnabled(enabled && hasModelData);
        }
        boolean paddleReady = paddleOcrModelManager != null
                && paddleOcrModelManager.isPackReady();
        boolean hasPaddleData = paddleOcrModelManager != null
                && paddleOcrModelManager.downloadedBytes() > 0;
        if (downloadPaddleOcrButton != null) {
            downloadPaddleOcrButton.setEnabled(enabled && !paddleReady);
        }
        if (deletePaddleOcrButton != null) {
            deletePaddleOcrButton.setEnabled(enabled && hasPaddleData);
        }
        sendResultButton.setEnabled(enabled && resultFile != null && resultFile.exists());
        boolean hasProject = TranslationProjectStore.hasCompletedProject(this);
        if (editResultButton != null) {
            editResultButton.setEnabled(enabled && hasProject);
            editResultButton.setAlpha(enabled && hasProject ? 1f : 0.55f);
            editResultButton.setText(hasProject
                    ? getString(R.string.ks_0587)
                    : getString(R.string.ks_0588));
        }
        if (openStudioFromStyleButton != null) {
            openStudioFromStyleButton.setEnabled(enabled && hasProject);
            openStudioFromStyleButton.setAlpha(enabled && hasProject ? 1f : 0.55f);
            openStudioFromStyleButton.setText(hasProject
                    ? getString(R.string.ks_0591)
                    : getString(R.string.ks_0590));
        }
    }
}
