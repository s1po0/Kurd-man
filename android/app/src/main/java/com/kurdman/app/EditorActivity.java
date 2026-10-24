package com.kurdman.app;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PointF;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Post-translation studio for every OCR block.
 *
 * <p>Blocks can be moved, resized and rotated directly on the page, edited,
 * hidden, duplicated, reordered, recoloured and restyled. Projects autosave
 * to app-private storage, so leaving this screen does not lose the layout.</p>
 */
public class EditorActivity extends AppCompatActivity
        implements PageEditorView.Listener {
    public static final String EXTRA_EXPORT = "export_edited_project";
    private static final String PREFS_NAME = "kurdman_settings";
    private static final String PREF_GLOBAL_STYLE = "global_text_style_json";
    private static final int PANEL_TEXTS = 0;
    private static final int PANEL_POSITION = 1;
    private static final int PANEL_STYLE = 2;
    private static final int PANEL_FINISH = 3;
    private static final int PANEL_ADVANCED_FIRST = 4;
    private static final int PANEL_ADVANCED_TEXT = 4;
    private static final int PANEL_ADVANCED_STYLE = 5;
    private static final int PANEL_ADVANCED_COLOR = 6;
    private static final int PANEL_ADVANCED_BACKGROUND = 7;
    private static final int PANEL_ADVANCED_POSITION = 8;
    private static final int PANEL_ADVANCED_PROJECT = 9;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Deque<String> undoStack = new ArrayDeque<>();
    private final Deque<String> redoStack = new ArrayDeque<>();
    private final Map<Integer, Integer> selectedByPage = new HashMap<>();
    private final List<LinearLayout> panels = new ArrayList<>();
    private final List<Button> tabButtons = new ArrayList<>();
    private final List<View> selectedControls = new ArrayList<>();

    private PageEditorView canvas;
    private TextView statusText;
    private TextView pageLabel;
    private TextView blockLabel;
    private Button undoButton;
    private Button redoButton;
    private LinearLayout panelContainer;
    private LinearLayout tabsContainer;
    private View tabsScroll;
    private ScrollView panelScroll;
    private Button sectionTextsButton;
    private Button sectionPositionButton;
    private Button sectionStyleButton;
    private Button sectionFinishButton;
    private Button showControlsButton;
    private Button toolMoveButton;
    private Button toolResizeButton;
    private Button toolRotateButton;
    private Button toolViewButton;
    private TextView toolHint;
    private int currentPanelIndex = PANEL_TEXTS;
    private boolean panelCollapsed = false;
    private TextView simpleSelectedText;
    private Button simpleFontButton;
    private TextView simpleSizeLabel;
    private Button simpleVisibilityButton;
    private LinearLayout simpleMoveActions;
    private LinearLayout simpleResizeActions;
    private LinearLayout simpleRotateActions;
    private LinearLayout simpleViewActions;

    private TranslationProject project;
    private Bitmap currentBitmap;
    private int pageIndex = 0;
    private int selectedSegmentIndex = -1;
    private int loadGeneration = 0;
    private boolean bindingControls = false;
    private boolean applyingHistory = false;
    private String originalSnapshotAtOpen;
    private TextStyleSettings globalDefaultStyle = new TextStyleSettings();
    private List<FontCatalog.FontOption> fontOptions = new ArrayList<>();
    private ActivityResultLauncher<String[]> importFontLauncher;

    private boolean samplerArmed = false;
    private String samplerTarget = "text";
    private int lastPresetIndex = 0;
    private boolean presetUserTouched = false;

    private TextView originalTextView;
    private EditText translatedEdit;
    private CheckBox visibleCheck;
    private CheckBox eraseCheck;
    private CheckBox forceEraseCheck;
    private CheckBox lockedCheck;

    private Spinner fontSpinner;
    private Spinner presetSpinner;
    private Spinner alignmentSpinner;
    private SeekControl weightSeek;
    private SeekControl sizeSeek;
    private SeekControl lineSeek;
    private SeekControl opacitySeek;
    private CheckBox italicCheck;
    private CheckBox underlineCheck;
    private CheckBox strikeCheck;

    private CheckBox autoColorCheck;
    private Button textColorButton;
    private CheckBox gradientCheck;
    private Button gradientColorButton;
    private Spinner gradientDirectionSpinner;
    private CheckBox outlineCheck;
    private Button outlineColorButton;
    private SeekControl outlineSeek;
    private CheckBox shadowCheck;
    private Button shadowColorButton;
    private SeekControl shadowSeek;

    private Spinner backgroundModeSpinner;
    private Button backgroundColorButton;
    private SeekControl backgroundOpacitySeek;
    private Spinner backgroundShapeSpinner;
    private SeekControl radiusSeek;
    private SeekControl paddingSeek;
    private CheckBox borderCheck;
    private Button borderColorButton;
    private SeekControl borderSeek;

    private SeekControl rotationSeek;
    private SeekControl nudgeStepSeek;
    private TextView geometryLabel;
    private CheckBox snapCheck;

    private TextView projectStats;

    private final Runnable autosaveRunnable = () -> saveProjectAsync(null);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_editor);

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        globalDefaultStyle = TextStyleSettings.fromJsonString(
                prefs.getString(PREF_GLOBAL_STYLE, ""));

        canvas = findViewById(R.id.editorCanvas);
        statusText = findViewById(R.id.editorStatus);
        pageLabel = findViewById(R.id.editorPageLabel);
        blockLabel = findViewById(R.id.editorBlockLabel);
        undoButton = findViewById(R.id.editorUndoButton);
        redoButton = findViewById(R.id.editorRedoButton);
        panelContainer = findViewById(R.id.editorPanelContainer);
        tabsContainer = findViewById(R.id.editorTabs);
        tabsScroll = findViewById(R.id.editorTabsScroll);
        panelScroll = findViewById(R.id.editorPanelScroll);
        sectionTextsButton = findViewById(R.id.editorSectionTextsButton);
        sectionPositionButton = findViewById(R.id.editorSectionPositionButton);
        sectionStyleButton = findViewById(R.id.editorSectionStyleButton);
        sectionFinishButton = findViewById(R.id.editorSectionFinishButton);
        showControlsButton = findViewById(R.id.editorShowControlsButton);

        canvas.setListener(this);
        canvas.setResolver(style -> FontCatalog.getTypeface(
                getApplicationContext(), style));

        importFontLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                uri -> {
                    if (uri != null) {
                        importFont(uri);
                    }
                });

        buildPanels();
        wireTopButtons();
        wireEditorTools();

        getOnBackPressedDispatcher().addCallback(this,
                new OnBackPressedCallback(true) {
                    @Override
                    public void handleOnBackPressed() {
                        closeWithoutExport();
                    }
                });

        project = TranslationProjectStore.load(this);
        if (project == null || project.pages.isEmpty()) {
            setStatus("⚠ هیچ پرۆژەیەکی وەرگێڕدراو نەدۆزرایەوە. سەرەتا وێنەیەک یان CBZ وەربگێڕە.");
            updateSelectedControlsEnabled();
            return;
        }
        originalSnapshotAtOpen = project.snapshotJson();
        loadPage(0, true);
        updateHistoryButtons();
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveProjectAsync(null);
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacks(autosaveRunnable);
        executor.shutdownNow();
        if (currentBitmap != null && !currentBitmap.isRecycled()) {
            currentBitmap.recycle();
        }
        super.onDestroy();
    }

    // ------------------------------------------------------------------
    // Page loading and PageEditorView.Listener
    // ------------------------------------------------------------------

    private void applyPendingTextBeforeLeavingSegment() {
        Segment segment = selectedSegment();
        if (segment == null || translatedEdit == null) {
            return;
        }
        String pending = translatedEdit.getText().toString().trim();
        String current = segment.ckb == null ? "" : segment.ckb.trim();
        if (!pending.equals(current)) {
            pushHistory();
            segment.ckb = pending;
            if (!pending.isEmpty()) {
                segment.ensureStyle().visible = true;
            }
            scheduleAutosave();
        }
    }

    private void loadPage(final int index, final boolean fit) {
        if (project == null || project.pages.isEmpty()) {
            return;
        }
        applyPendingTextBeforeLeavingSegment();
        final int safeIndex = Math.max(0, Math.min(index, project.pages.size() - 1));
        pageIndex = safeIndex;
        final TranslationProject.Page page = project.pages.get(safeIndex);
        Integer remembered = selectedByPage.get(safeIndex);
        selectedSegmentIndex = remembered == null
                ? (page.segments.isEmpty() ? -1 : 0)
                : Math.max(-1, Math.min(remembered, page.segments.size() - 1));
        final int generation = ++loadGeneration;
        setStatus("بارکردنی لاپەڕە " + (safeIndex + 1) + "/" + project.pages.size() + "…");
        updateNavigation();

        executor.execute(() -> {
            File source = page.sourceFile(project.projectDir);
            Bitmap decoded = source == null ? null : BitmapIo.decodeFile(source);
            boolean upgraded = decoded != null
                    && upgradeSegmentsForCleanErase(decoded, page.segments);
            if (upgraded) {
                runOnUiThread(this::scheduleAutosave);
            }
            runOnUiThread(() -> {
                if (generation != loadGeneration || isFinishing() || isDestroyed()) {
                    if (decoded != null) {
                        decoded.recycle();
                    }
                    return;
                }
                Bitmap old = currentBitmap;
                currentBitmap = decoded;
                canvas.setPage(decoded, page.segments, selectedSegmentIndex);
                if (fit) {
                    canvas.post(canvas::fitToScreen);
                }
                if (old != null && old != decoded && !old.isRecycled()) {
                    old.recycle();
                }
                if (decoded == null) {
                    setStatus("⚠ وێنەی ئەم لاپەڕەیە نەخوێنرایەوە؛ دەقەکان لە TXTەکەدا دەپارێزرێن.");
                } else if (page.segments.isEmpty()) {
                    setStatus("ئەم لاپەڕەیە هیچ دەقێکی نییە — دەتوانیت «دەقی نوێ» زیاد بکەیت.");
                } else {
                    setStatus("ئامادەیە — لە خوارەوە: ١ دەقەکان، ٢ شوێن، ٣ ستایل، ٤ دەرکردن.");
                }
                bindSelectedControls();
                updateNavigation();
            });
        });
    }

    /**
     * Projects rendered by older versions carry the solid-fill background
     * mode and no bubble-shape flag, which repainted a white rectangle
     * sticking out of round balloons. Upgrade those auto-generated styles
     * in place (deliberate custom colours are left alone) and re-detect
     * oval bubbles so studio previews and exports match the new renderer.
     */
    private boolean upgradeSegmentsForCleanErase(Bitmap bitmap,
                                                 java.util.List<Segment> segments) {
        boolean changed = false;
        if (bitmap == null || bitmap.isRecycled() || segments == null) {
            return false;
        }
        for (Segment segment : segments) {
            if (segment.ensureStyle().backgroundMode
                    == TextStyleSettings.BG_AUTO) {
                segment.ensureStyle().backgroundMode = TextStyleSettings.BG_CLEAN;
                changed = true;
            }
            if (!segment.bubbleOval && ManhwaRenderer.looksOval(
                    bitmap, segment.getEraseBox(),
                    segment.detectedBackgroundColor)) {
                segment.bubbleOval = true;
                changed = true;
            }
        }
        return changed;
    }

    private TranslationProject.Page currentPage() {
        if (project == null || pageIndex < 0 || pageIndex >= project.pages.size()) {
            return null;
        }
        return project.pages.get(pageIndex);
    }

    private Segment selectedSegment() {
        TranslationProject.Page page = currentPage();
        if (page == null || selectedSegmentIndex < 0
                || selectedSegmentIndex >= page.segments.size()) {
            return null;
        }
        return page.segments.get(selectedSegmentIndex);
    }

    @Override
    public void onSelectionChanged(int index) {
        applyPendingTextBeforeLeavingSegment();
        selectedSegmentIndex = index;
        selectedByPage.put(pageIndex, index);
        bindSelectedControls();
        updateNavigation();
    }

    @Override
    public void onGeometryGestureStarted() {
        pushHistory();
    }

    @Override
    public void onGeometryGestureFinished() {
        updateGeometryLabel();
        Segment segment = selectedSegment();
        if (segment != null && rotationSeek != null) {
            bindingControls = true;
            setSeek(rotationSeek, "سووڕان: "
                            + Math.round(segment.ensureStyle().rotationDegrees) + "°",
                    Math.round(segment.ensureStyle().rotationDegrees) + 180);
            mainHandler.post(() -> bindingControls = false);
        }
        scheduleAutosave();
    }

    @Override
    public void onDoubleTapSegment(int index) {
        selectedSegmentIndex = index;
        selectedByPage.put(pageIndex, index);
        showEditTextDialog();
    }

    @Override
    public boolean onImageTap(float imageX, float imageY) {
        if (!samplerArmed) {
            return false;
        }
        samplerArmed = false;
        Segment segment = selectedSegment();
        if (segment == null || currentBitmap == null) {
            setStatus("⚠ سەرەتا دەقێک هەڵبژێرە بۆ هەڵگرتنی ڕەنگ.");
            return true;
        }
        int x = Math.max(0, Math.min(currentBitmap.getWidth() - 1, Math.round(imageX)));
        int y = Math.max(0, Math.min(currentBitmap.getHeight() - 1, Math.round(imageY)));
        int color = currentBitmap.getPixel(x, y);
        pushHistory();
        TextStyleSettings style = segment.ensureStyle();
        if ("background".equals(samplerTarget)) {
            style.backgroundMode = TextStyleSettings.BG_CUSTOM;
            style.backgroundColor = color;
            setStatus("🎨 ڕەنگی پاشبنەما لە وێنەکەوە: " + TextStyleSettings.hex(color));
        } else {
            style.autoTextColor = false;
            style.textColor = color;
            setStatus("🎨 ڕەنگی دەق لە وێنەکەوە: " + TextStyleSettings.hex(color));
        }
        canvas.refreshStyles();
        bindSelectedControls();
        scheduleAutosave();
        return true;
    }

    // ------------------------------------------------------------------
    // Top bar
    // ------------------------------------------------------------------

    private void wireTopButtons() {
        findViewById(R.id.editorBackButton).setOnClickListener(
                v -> closeWithoutExport());
        findViewById(R.id.editorSaveButton).setOnClickListener(
                v -> saveAndExport());
        undoButton.setOnClickListener(v -> undo());
        redoButton.setOnClickListener(v -> redo());
        findViewById(R.id.editorPagePrevButton).setOnClickListener(v -> {
            if (pageIndex > 0) {
                loadPage(pageIndex - 1, true);
            }
        });
        findViewById(R.id.editorPageNextButton).setOnClickListener(v -> {
            if (project != null && pageIndex < project.pages.size() - 1) {
                loadPage(pageIndex + 1, true);
            }
        });
        findViewById(R.id.editorBlockPrevButton).setOnClickListener(
                v -> selectSegment(selectedSegmentIndex - 1, true));
        findViewById(R.id.editorBlockNextButton).setOnClickListener(
                v -> selectSegment(selectedSegmentIndex + 1, true));
        findViewById(R.id.editorBlockListButton).setOnClickListener(
                v -> showBlockList());
        findViewById(R.id.editorFitButton).setOnClickListener(
                v -> canvas.fitWidth());
        findViewById(R.id.editorZoomInButton).setOnClickListener(
                v -> canvas.zoomBy(1.25f));
        findViewById(R.id.editorZoomOutButton).setOnClickListener(
                v -> canvas.zoomBy(0.8f));
        Button peek = findViewById(R.id.editorPeekButton);
        peek.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                canvas.setPeekOriginal(true);
                return true;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_UP
                    || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                canvas.setPeekOriginal(false);
                view.performClick();
                return true;
            }
            return false;
        });
    }

    private void selectSegment(int index, boolean center) {
        applyPendingTextBeforeLeavingSegment();
        TranslationProject.Page page = currentPage();
        if (page == null || page.segments.isEmpty()) {
            return;
        }
        int next = (index % page.segments.size() + page.segments.size())
                % page.segments.size();
        selectedSegmentIndex = next;
        selectedByPage.put(pageIndex, next);
        canvas.setSelectedIndex(next, false);
        if (center) {
            canvas.centerOnSegment(next);
        }
        bindSelectedControls();
        updateNavigation();
    }

    private void closeWithoutExport() {
        saveProjectAsync(() -> {
            setResult(Activity.RESULT_CANCELED);
            finish();
        });
    }

    private void saveAndExport() {
        applyPendingTextBeforeLeavingSegment();
        saveProjectAsync(() -> {
            Intent result = new Intent();
            result.putExtra(EXTRA_EXPORT, true);
            setResult(Activity.RESULT_OK, result);
            finish();
        });
    }

    // ------------------------------------------------------------------
    // Panels
    // ------------------------------------------------------------------

    private void buildPanels() {
        // One job per bottom section. The simple workflow is deliberately
        // not one long panel any more: Texts, Position, Style and Finish
        // are four separate screens below the large canvas. Detailed
        // panels stay hidden until a section's Advanced button is tapped.
        buildSimpleTextsPanel();
        buildSimplePositionPanel();
        buildSimpleStylePanel();
        buildSimpleFinishPanel();
        buildTextPanel();
        buildStylePanel();
        buildColorPanel();
        buildBackgroundPanel();
        buildPositionPanel();
        buildProjectPanel();
        String[] labels = {"دەق", "ستایل", "ڕەنگ", "پاشبنەما", "شوێن", "پرۆژە"};
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            Button tab = new Button(this);
            tab.setText(labels[i]);
            tab.setAllCaps(false);
            tab.setTextSize(13f);
            tab.setMinWidth(0);
            tab.setMinHeight(dp(44));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(44));
            params.setMargins(dp(3), 0, dp(3), 0);
            tab.setLayoutParams(params);
            tab.setOnClickListener(v -> showPanel(index + PANEL_ADVANCED_FIRST));
            tabsContainer.addView(tab);
            tabButtons.add(tab);
        }
        refreshFontSpinner();
        showPanel(PANEL_TEXTS);
    }

    private void showPanel(int index) {
        if (index < 0 || index >= panels.size()) {
            index = PANEL_TEXTS;
        }
        currentPanelIndex = index;
        boolean advanced = index >= PANEL_ADVANCED_FIRST;
        for (int i = 0; i < panels.size(); i++) {
            panels.get(i).setVisibility(i == index ? View.VISIBLE : View.GONE);
        }
        for (int i = 0; i < tabButtons.size(); i++) {
            Button tab = tabButtons.get(i);
            boolean selected = advanced && i == index - PANEL_ADVANCED_FIRST;
            tab.setBackgroundResource(selected
                    ? R.drawable.bg_button_primary : R.drawable.bg_button_secondary);
            tab.setTextColor(selected ? Color.WHITE : Color.rgb(94, 234, 212));
        }
        if (tabsScroll != null) {
            tabsScroll.setVisibility(advanced ? View.VISIBLE : View.GONE);
        }
        int section = sectionForPanel(index);
        updateStudioSectionButton(sectionTextsButton, section == PANEL_TEXTS);
        updateStudioSectionButton(sectionPositionButton, section == PANEL_POSITION);
        updateStudioSectionButton(sectionStyleButton, section == PANEL_STYLE);
        updateStudioSectionButton(sectionFinishButton, section == PANEL_FINISH);
        if (canvas != null) {
            canvas.setShowAllOverlays(advanced || index == PANEL_TEXTS);
            if (index == PANEL_POSITION
                    && canvas.getTool() == PageEditorView.Tool.VIEW) {
                setEditorTool(PageEditorView.Tool.MOVE);
            }
        }
        if (project != null && !advanced) {
            switch (index) {
                case PANEL_POSITION:
                    setStatus("ئامرازێک هەڵبژێرە؛ دوگمە گەورەکان یان ڕاکێشان بەکاربهێنە.");
                    break;
                case PANEL_STYLE:
                    setStatus("تەنها دەقە هەڵبژێردراوەکە دەگۆڕێت.");
                    break;
                case PANEL_FINISH:
                    setStatus("کاتێک تەواو بوویت «دەرکردن» دابگرە.");
                    break;
                case PANEL_TEXTS:
                default:
                    setStatus("دەقێک هەڵبژێرە، ئینجا وشەکانی بگۆڕە.");
                    break;
            }
        }
        boolean wasCollapsed = panelCollapsed;
        panelCollapsed = false;
        if (panelScroll != null) {
            panelScroll.setVisibility(View.VISIBLE);
            panelScroll.post(() -> panelScroll.scrollTo(0, 0));
        }
        if (showControlsButton != null) {
            showControlsButton.setVisibility(View.GONE);
        }
        if (canvas != null && (wasCollapsed
                || (project != null && currentBitmap != null
                        && (index == PANEL_POSITION || index == PANEL_STYLE)))) {
            canvas.post(canvas::focusSelected);
        }
    }

    private int sectionForPanel(int index) {
        if (index <= PANEL_FINISH) {
            return Math.max(PANEL_TEXTS, index);
        }
        if (index == PANEL_ADVANCED_TEXT) {
            return PANEL_TEXTS;
        }
        if (index == PANEL_ADVANCED_POSITION) {
            return PANEL_POSITION;
        }
        if (index == PANEL_ADVANCED_PROJECT) {
            return PANEL_FINISH;
        }
        return PANEL_STYLE;
    }

    private void updateStudioSectionButton(Button button, boolean selected) {
        if (button == null) {
            return;
        }
        button.setSelected(selected);
        button.setBackgroundResource(selected
                ? R.drawable.bg_button_primary : R.drawable.bg_button_secondary);
        button.setTextColor(selected ? Color.WHITE : Color.rgb(226, 232, 240));
    }

    private void setEditorPanelCollapsed(boolean collapsed) {
        panelCollapsed = collapsed;
        if (panelScroll != null) {
            panelScroll.setVisibility(collapsed ? View.GONE : View.VISIBLE);
        }
        if (showControlsButton != null) {
            showControlsButton.setVisibility(collapsed ? View.VISIBLE : View.GONE);
        }
        if (canvas != null) {
            // After the canvas grows/shrinks, keep the current text large
            // and centred instead of leaving it at the old screen position.
            canvas.post(canvas::focusSelected);
        }
        if (collapsed) {
            setStatus("کۆنترۆڵەکان شاردرانەوە — بۆ گەڕاندنەوەیان «کۆنترۆڵەکان» لەسەر وێنەکە دابگرە.");
        }
    }

    private void wireEditorTools() {
        sectionTextsButton.setOnClickListener(v -> showPanel(PANEL_TEXTS));
        sectionPositionButton.setOnClickListener(v -> showPanel(PANEL_POSITION));
        sectionStyleButton.setOnClickListener(v -> showPanel(PANEL_STYLE));
        sectionFinishButton.setOnClickListener(v -> showPanel(PANEL_FINISH));
        showControlsButton.setOnClickListener(
                v -> setEditorPanelCollapsed(false));
        if (toolMoveButton != null) {
            toolMoveButton.setOnClickListener(
                    v -> setEditorTool(PageEditorView.Tool.MOVE));
        }
        if (toolResizeButton != null) {
            toolResizeButton.setOnClickListener(
                    v -> setEditorTool(PageEditorView.Tool.RESIZE));
        }
        if (toolRotateButton != null) {
            toolRotateButton.setOnClickListener(
                    v -> setEditorTool(PageEditorView.Tool.ROTATE));
        }
        if (toolViewButton != null) {
            toolViewButton.setOnClickListener(
                    v -> setEditorTool(PageEditorView.Tool.VIEW));
        }
        setEditorTool(PageEditorView.Tool.MOVE);
    }

    private void setEditorTool(PageEditorView.Tool tool) {
        canvas.setTool(tool);
        updateEditorToolButton(toolMoveButton,
                tool == PageEditorView.Tool.MOVE);
        updateEditorToolButton(toolResizeButton,
                tool == PageEditorView.Tool.RESIZE);
        updateEditorToolButton(toolRotateButton,
                tool == PageEditorView.Tool.ROTATE);
        updateEditorToolButton(toolViewButton,
                tool == PageEditorView.Tool.VIEW);
        if (simpleMoveActions != null) {
            simpleMoveActions.setVisibility(
                    tool == PageEditorView.Tool.MOVE ? View.VISIBLE : View.GONE);
        }
        if (simpleResizeActions != null) {
            simpleResizeActions.setVisibility(
                    tool == PageEditorView.Tool.RESIZE ? View.VISIBLE : View.GONE);
        }
        if (simpleRotateActions != null) {
            simpleRotateActions.setVisibility(
                    tool == PageEditorView.Tool.ROTATE ? View.VISIBLE : View.GONE);
        }
        if (simpleViewActions != null) {
            simpleViewActions.setVisibility(
                    tool == PageEditorView.Tool.VIEW ? View.VISIBLE : View.GONE);
        }
        if (toolHint == null) {
            return;
        }
        switch (tool) {
            case RESIZE:
                toolHint.setText("قەبارە: دوگمەکانی خوارەوە بەکاربهێنە یان چوارگۆشە سەوزەکە ڕابکێشە.");
                break;
            case ROTATE:
                toolHint.setText("سووڕان: دوگمەکانی خوارەوە بەکاربهێنە یان بازنە زەردەکە ڕابکێشە.");
                break;
            case VIEW:
                toolHint.setText("بینین: وێنەکە ڕابکێشە یان بە دوو پەنجە زووم بکە؛ هیچ دەقێک ناجوڵێت.");
                break;
            case MOVE:
            default:
                toolHint.setText("جوڵاندن: تیرەکانی خوارەوە بەکاربهێنە یان دەقەکە بە یەک پەنجە ڕابکێشە.");
                break;
        }
    }

    private void updateEditorToolButton(Button button, boolean selected) {
        if (button == null) {
            return;
        }
        button.setSelected(selected);
        button.setBackgroundResource(selected
                ? R.drawable.bg_button_primary : R.drawable.bg_button_secondary);
        button.setTextColor(selected ? Color.WHITE : Color.rgb(94, 234, 212));
    }

    private void buildSimpleTextsPanel() {
        LinearLayout panel = createPanel();
        simpleSelectedText = textBox(panel, "هیچ دەقێک هەڵنەبژێردراوە");
        simpleSelectedText.setTextSize(12.5f);
        simpleSelectedText.setMinHeight(0);
        simpleSelectedText.setMaxLines(2);
        simpleSelectedText.setEllipsize(TextUtils.TruncateAt.END);
        simpleSelectedText.setPadding(dp(11), dp(7), dp(11), dp(7));
        LinearLayout selectRow = row(panel);
        button(selectRow, "‹  پێشتر", false,
                v -> selectSegment(selectedSegmentIndex - 1, true));
        button(selectRow, "›  دواتر", false,
                v -> selectSegment(selectedSegmentIndex + 1, true));
        button(selectRow, "☰  لیستەکە", false, v -> showBlockList());
        addSelected(button(panel, "✎  وشەکانی ئەم دەقە بگۆڕە", true,
                v -> showEditTextDialog()));
    }

    private void buildSimplePositionPanel() {
        LinearLayout panel = createPanel();
        LinearLayout toolRow = row(panel);
        toolMoveButton = button(toolRow, "↔ جوڵاندن", true, null);
        toolResizeButton = button(toolRow, "⤢ قەبارە", false, null);
        toolRotateButton = button(toolRow, "⟳ سووڕان", false, null);
        toolViewButton = button(toolRow, "🔍 بینین", false, null);
        toolMoveButton.setTextSize(12f);
        toolResizeButton.setTextSize(12f);
        toolRotateButton.setTextSize(12f);
        toolViewButton.setTextSize(12f);

        toolHint = label(panel,
                "جوڵاندن: تیرەکانی خوارەوە بەکاربهێنە یان دەقەکە ڕابکێشە.");
        toolHint.setTextSize(12f);
        toolHint.setMaxLines(2);
        toolHint.setEllipsize(TextUtils.TruncateAt.END);
        toolHint.setGravity(android.view.Gravity.CENTER);

        // Button-based editing is deliberately available alongside drag
        // gestures: tiny resize/rotate handles are not the only way to work.
        simpleMoveActions = row(panel);
        simpleMoveActions.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        addSelected(button(simpleMoveActions, "←", false,
                v -> nudge(-simpleNudgeStep(), 0))).setTextSize(18f);
        addSelected(button(simpleMoveActions, "↑", false,
                v -> nudge(0, -simpleNudgeStep()))).setTextSize(18f);
        addSelected(button(simpleMoveActions, "↓", false,
                v -> nudge(0, simpleNudgeStep()))).setTextSize(18f);
        addSelected(button(simpleMoveActions, "→", false,
                v -> nudge(simpleNudgeStep(), 0))).setTextSize(18f);
        addSelected(button(simpleMoveActions, "⌂", false,
                v -> resetPosition())).setTextSize(17f);
        button(simpleMoveActions, "⛶", true,
                v -> setEditorPanelCollapsed(true)).setTextSize(17f);

        simpleResizeActions = row(panel);
        addSelected(button(simpleResizeActions, "پانی −", false,
                v -> resizeSelectedBy(-simpleResizeStep(), 0)));
        addSelected(button(simpleResizeActions, "پانی +", false,
                v -> resizeSelectedBy(simpleResizeStep(), 0)));
        addSelected(button(simpleResizeActions, "بەرزی −", false,
                v -> resizeSelectedBy(0, -simpleResizeStep())));
        addSelected(button(simpleResizeActions, "بەرزی +", false,
                v -> resizeSelectedBy(0, simpleResizeStep())));
        button(simpleResizeActions, "⛶", true,
                v -> setEditorPanelCollapsed(true)).setTextSize(17f);
        simpleResizeActions.setVisibility(View.GONE);

        simpleRotateActions = row(panel);
        addSelected(button(simpleRotateActions, "−٢°", false,
                v -> rotateSelectedBy(-2f)));
        addSelected(button(simpleRotateActions, "٠°", false,
                v -> rotateSelectedBy(0f)));
        addSelected(button(simpleRotateActions, "+٢°", false,
                v -> rotateSelectedBy(2f)));
        button(simpleRotateActions, "⛶ گەورە", true,
                v -> setEditorPanelCollapsed(true));
        simpleRotateActions.setVisibility(View.GONE);

        simpleViewActions = row(panel);
        button(simpleViewActions, "◎ دەقەکە", false,
                v -> canvas.focusSelected());
        button(simpleViewActions, "↔ پانایی", false,
                v -> canvas.fitWidth());
        button(simpleViewActions, "⛶ هەمووی", false,
                v -> canvas.fitEntirePage());
        button(simpleViewActions, "شاردنەوە", true,
                v -> setEditorPanelCollapsed(true));
        simpleViewActions.setVisibility(View.GONE);
    }

    private void buildSimpleStylePanel() {
        LinearLayout panel = createPanel();
        simpleFontButton = addSelected(button(panel, "A  فۆنت هەڵبژێرە", true,
                v -> showSimpleFontPicker()));
        LinearLayout sizeRow = row(panel);
        addSelected(button(sizeRow, "A−", false,
                v -> adjustSimpleTextSize(-0.10f))).setTextSize(16f);
        simpleSizeLabel = new TextView(this);
        simpleSizeLabel.setText("قەبارە: ١٠٠٪");
        simpleSizeLabel.setTextColor(Color.WHITE);
        simpleSizeLabel.setTextSize(13f);
        simpleSizeLabel.setGravity(android.view.Gravity.CENTER);
        simpleSizeLabel.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.35f));
        sizeRow.addView(simpleSizeLabel);
        addSelected(button(sizeRow, "A+", false,
                v -> adjustSimpleTextSize(0.10f))).setTextSize(16f);
        LinearLayout colorRow = row(panel);
        addSelected(button(colorRow, "A  ڕەنگی دەق", false,
                v -> openColorPicker("text")));
        addSelected(button(colorRow, "▦  ڕەنگی پاشبنەما", false,
                v -> openColorPicker("background")));
    }

    private void buildSimpleFinishPanel() {
        LinearLayout panel = createPanel();
        button(panel, "✓  پاشەکەوتکردن و دەرکردنی نوێ", true,
                v -> saveAndExport());
        LinearLayout moreRow = row(panel);
        button(moreRow, "؟  ڕێنمایی", false, v -> showSimpleHelp());
        button(moreRow, "⚙  ئامرازی زیاتر", false,
                v -> showPanel(PANEL_ADVANCED_TEXT));
        button(panel, "✕  داخستن و دواتر بەردەوامبوون", false,
                v -> closeWithoutExport());
    }

    private void showSimpleHelp() {
        new AlertDialog.Builder(this)
                .setTitle("چۆن دەقێک دەستکاری بکەم؟")
                .setMessage("١) لە بەشی «دەقەکان» تەنها دەقێک هەڵبژێرە و ئەگەر پێویست بوو وشەکانی بگۆڕە.\n\n"
                        + "٢) لە بەشی «شوێن» یەک ئامراز هەڵبژێرە. ئێستا دەتوانیت بە دوگمە گەورەکانیش بیجوڵێنیت، قەبارەکەی بگۆڕیت یان بیسووڕێنیت — پێویستت بە ڕاکێشانی دەستگیرە بچووکەکان نییە.\n\n"
                        + "٣) دوگمەی ⛶ وێنەکە زۆر گەورە دەکات؛ «کۆنترۆڵەکان»ی سەر وێنەکە بەشەکە دەگەڕێنێتەوە.\n\n"
                        + "٤) لە بەشی «ستایل» تەنها فۆنت، قەبارە و ڕەنگ هەیە.\n\n"
                        + "٥) زیادکردن، کۆپی، شاردنەوە، سڕینەوە و ستایلە وردەکان لە «ئامرازی زیاتر»دان.\n\n"
                        + "٦) لە بەشی «دەرکردن» کاری کۆتایی بکە. پرۆژەکە بە خۆکار پاشەکەوت دەبێت.")
                .setPositiveButton("تێگەیشتم", null)
                .show();
    }

    private void showSimpleFontPicker() {
        Segment segment = selectedSegment();
        if (segment == null || fontOptions.isEmpty()) {
            return;
        }
        int current = FontCatalog.indexOfId(
                fontOptions, segment.ensureStyle().fontId);
        new AlertDialog.Builder(this)
                .setTitle("فۆنتێک هەڵبژێرە")
                .setSingleChoiceItems(
                        new FontSpinnerAdapter(this, fontOptions),
                        current,
                        (dialog, which) -> {
                            if (which >= 0 && which < fontOptions.size()) {
                                String nextId = fontOptions.get(which).id;
                                if (!nextId.equals(segment.ensureStyle().fontId)) {
                                    pushHistory();
                                    segment.ensureStyle().fontId = nextId;
                                    segment.ensureStyle().sanitize();
                                    canvas.refreshStyles();
                                    bindSelectedControls();
                                    scheduleAutosave();
                                }
                            }
                            dialog.dismiss();
                        })
                .setNegativeButton("هەڵپەساردن", null)
                .show();
    }

    private void showSimplePresetPicker() {
        if (selectedSegment() == null) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("ستایلێکی ئامادە هەڵبژێرە")
                .setSingleChoiceItems(
                        TextStyleSettings.PRESET_LABELS,
                        lastPresetIndex,
                        (dialog, which) -> {
                            applyPreset(which);
                            dialog.dismiss();
                        })
                .setNegativeButton("هەڵپەساردن", null)
                .show();
    }

    private void adjustSimpleTextSize(float delta) {
        Segment segment = selectedSegment();
        if (segment == null) {
            return;
        }
        pushHistory();
        TextStyleSettings style = segment.ensureStyle();
        style.sizeScale += delta;
        style.sanitize();
        canvas.refreshStyles();
        bindSelectedControls();
        scheduleAutosave();
        setStatus("قەبارەی دەق: "
                + Math.round(style.sizeScale * 100f) + "٪");
    }

    private void toggleSimpleVisibility() {
        Segment segment = selectedSegment();
        if (segment == null) {
            return;
        }
        pushHistory();
        TextStyleSettings style = segment.ensureStyle();
        style.visible = !style.visible;
        canvas.refreshStyles();
        bindSelectedControls();
        scheduleAutosave();
        setStatus(style.visible
                ? "👁 دەقەکە پیشان دەدرێت."
                : "دەقەکە شاردرایەوە؛ لە فایلی TXTەکەدا هەر دەمێنێتەوە.");
    }

    private void buildTextPanel() {
        LinearLayout panel = createPanel();
        heading(panel, "دەق و دەستکاری");
        label(panel, "دەقی ڕەسەن");
        originalTextView = textBox(panel, "—");
        label(panel, "وەرگێڕانی کوردی (دەتوانیت بیگۆڕیت)");
        translatedEdit = new EditText(this);
        translatedEdit.setBackgroundResource(R.drawable.bg_input);
        translatedEdit.setTextColor(Color.WHITE);
        translatedEdit.setTextSize(15f);
        translatedEdit.setMinLines(2);
        translatedEdit.setPadding(dp(12), dp(10), dp(12), dp(10));
        translatedEdit.setLayoutParams(matchWrap(dp(6)));
        panel.addView(translatedEdit);
        selectedControls.add(translatedEdit);

        LinearLayout row = row(panel);
        addSelected(button(row, "✓ جێبەجێکردنی دەق", true, v -> applyEditedText()));
        addSelected(button(row, "↺ وەرگێڕانی ڕەسەن", false, v -> {
            Segment segment = selectedSegment();
            if (segment == null) {
                return;
            }
            pushHistory();
            segment.ckb = segment.originalCkb == null ? "" : segment.originalCkb;
            translatedEdit.setText(segment.ckb);
            canvas.refreshStyles();
            scheduleAutosave();
        }));

        visibleCheck = check(panel, "نووسینەکە پیشان بدە لەسەر وێنەکە", "visible");
        eraseCheck = check(panel, "دەقی ڕەسەن لە قسەهەڵگرەکەدا پاک بکەرەوە", "erase");
        forceEraseCheck = check(panel,
                "بەزۆر پاککردنەوە تەنانەت ئەگەر پاشبنەما خۆکار دڵنیا نەبوو", "forceErase");
        lockedCheck = check(panel, "قوفڵکردن — ڕێگری لە جوڵاندنی بەهەڵە", "locked");

        LinearLayout actions = row(panel);
        button(actions, "＋ دەقی نوێ", true, v -> addSegment());
        addSelected(button(actions, "⧉ دووبارەکردنەوە", false, v -> duplicateSegment()));
        LinearLayout actions2 = row(panel);
        addSelected(button(actions2, "✎ دەق بگۆڕە", false, v -> showEditTextDialog()));
        addSelected(button(actions2, "🗑 سڕینەوە", false, v -> deleteSegment()));
        hint(panel, "دووجار لەسەر دەقێک بکە بۆ کردنەوەی دەستکاری خێرا. "
                + "دەقە شاردراوەکان لە TXTەکەدا دەمێننەوە.");
    }

    private void buildStylePanel() {
        LinearLayout panel = createPanel();
        heading(panel, "فۆنت و ستایل");
        fontSpinner = spinner(panel, "فۆنت", "font", null);
        LinearLayout fontRow = row(panel);
        button(fontRow, "＋ هاوردەکردنی فۆنت", false, v -> importFontLauncher.launch(
                new String[]{"font/*", "application/x-font-ttf",
                        "application/octet-stream"}));
        addSelected(button(fontRow, "🗑 سڕینەوەی فۆنتی هاوردەکراو", false,
                v -> deleteCurrentCustomFont()));

        ArrayAdapter<String> presetAdapter = simpleAdapter(TextStyleSettings.PRESET_LABELS);
        presetSpinner = spinner(panel, "ستایلی ئامادە", "preset", presetAdapter);
        presetSpinner.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                presetUserTouched = true;
            }
            return false;
        });
        addSelected(button(panel, "✦ جێبەجێکردنی ستایلە ئامادەکرابوو", false,
                v -> applyPreset(lastPresetIndex)));

        weightSeek = seek(panel, "ئەستووری فۆنت", "weight", 16);
        sizeSeek = seek(panel, "قەبارەی دەق", "size", 195);
        lineSeek = seek(panel, "بۆشایی نێوان هێڵەکان", "line", 105);
        opacitySeek = seek(panel, "ڕوونی دەق", "opacity", 235);
        alignmentSpinner = spinner(panel, "هێڵکاری", "alignment",
                simpleAdapter(new String[]{
                        "سەرەتا — بۆ سۆرانی لای ڕاست",
                        "ناوەڕاست",
                        "کۆتایی — بۆ سۆرانی لای چەپ"}));
        italicCheck = check(panel, "لارکردنی دەق", "italic");
        underlineCheck = check(panel, "هێڵی ژێرەوە", "underline");
        strikeCheck = check(panel, "هێڵی ناوەڕاست", "strikethrough");

        LinearLayout applyRow = row(panel);
        addSelected(button(applyRow, "بۆ هەموو ئەم لاپەڕەیە", false,
                v -> confirmApplyStyle(false)));
        addSelected(button(applyRow, "بۆ هەموو لاپەڕەکان", false,
                v -> confirmApplyStyle(true)));
        addSelected(button(panel, "💾 ئەم ستایلە بکە بە بنەڕەتی داهاتوو", true,
                v -> makeSelectedStyleDefault()));
        hint(panel, "فۆنتە ناوەکییەکان پیتەکانی تایبەتی سۆرانیان تێدایە. "
                + "بۆ فۆنتی هاوردەکراو دڵنیابە کە ڕ، ڵ، ێ، ە و ۆی تێدایە.");
    }

    private void buildColorPanel() {
        LinearLayout panel = createPanel();
        heading(panel, "ڕەنگ، دەوروبەر و سێبەر");
        autoColorCheck = check(panel, "ڕەنگی دەقی خۆکار", "autoColor");
        textColorButton = colorButton(panel, "A  ڕەنگی دەق", "text");
        gradientCheck = check(panel, "گرادیانتی ڕەنگ بۆ دەق", "gradient");
        gradientColorButton = colorButton(panel, "↘  ڕەنگی دووەمی گرادیانت", "gradient");
        gradientDirectionSpinner = spinner(panel, "ئاراستەی گرادیانت", "gradientDirection",
                simpleAdapter(new String[]{"ئاسۆیی", "ستوونی", "لار"}));

        outlineCheck = check(panel, "دەوروبەری دەق (Outline)", "outline");
        outlineColorButton = colorButton(panel, "◐  ڕەنگی دەوروبەر", "outline");
        outlineSeek = seek(panel, "ئەستووری دەوروبەر", "outlineWidth", 38);

        shadowCheck = check(panel, "سێبەری دەق", "shadow");
        shadowColorButton = colorButton(panel, "☁  ڕەنگی سێبەر", "shadow");
        shadowSeek = seek(panel, "هێزی سێبەر", "shadowStrength", 100);

        heading(panel, "هەڵگرتنی ڕەنگ لە وێنەکە");
        LinearLayout row = row(panel);
        addSelected(button(row, "🖍 بۆ دەق", false, v -> armSampler("text")));
        addSelected(button(row, "🖍 بۆ پاشبنەما", false,
                v -> armSampler("background")));
        hint(panel, "دوای هەڵبژاردنی ئامرازەکە، لەسەر هەر شوێنێکی وێنەکە بکە و ڕەنگەکەی وەردەگرێت.");
    }

    private void buildBackgroundPanel() {
        LinearLayout panel = createPanel();
        heading(panel, "پاشبنەمای دەق / قسەهەڵگر");
        backgroundModeSpinner = spinner(panel, "جۆری پاشبنەما", "backgroundMode",
                simpleAdapter(new String[]{
                        "خۆکار — ڕەنگی قسەهەڵگری ڕەسەن",
                        "ڕەنگی دیاریکراو",
                        "بێ پاشبنەما",
                        "🧼 سڕینەوەی خاوێن — بێ پاشبنەمای سپی"}));
        backgroundColorButton = colorButton(panel, "▦  ڕەنگی پاشبنەما", "background");
        backgroundOpacitySeek = seek(panel, "ڕوونی پاشبنەما", "backgroundOpacity", 255);
        backgroundShapeSpinner = spinner(panel, "شێوە", "backgroundShape",
                simpleAdapter(new String[]{
                        "گۆشەی خڕ", "لاکێشە", "کەپسوول", "هێلکەیی"}));
        radiusSeek = seek(panel, "خڕی گۆشەکان", "radius", 50);
        paddingSeek = seek(panel, "بۆشایی ناوەوە", "padding", 185);
        borderCheck = check(panel, "سنووری دەوروبەری پاشبنەما", "border");
        borderColorButton = colorButton(panel, "▣  ڕەنگی سنوور", "border");
        borderSeek = seek(panel, "ئەستووری سنوور", "borderWidth", 40);
        hint(panel, "«بێ پاشبنەما» لەگەڵ دەوروبەر یان سێبەر باشتر دەخوێنرێتەوە "
                + "کاتێک دەقەکە لەسەر وێنەیەکی ئاڵۆزە.");
    }

    private void buildPositionPanel() {
        LinearLayout panel = createPanel();
        heading(panel, "شوێن، قەبارە و ڕیزبەندی");
        geometryLabel = textBox(panel, "—");
        rotationSeek = seek(panel, "سووڕان", "rotation", 360);
        nudgeStepSeek = seek(panel, "هەنگاوی جوڵاندن", "nudgeStep", 50);
        nudgeStepSeek.seek.setProgress(9);
        nudgeStepSeek.label.setText("هەنگاوی جوڵاندن: 10px");

        LinearLayout upRow = row(panel);
        button(upRow, "", false, null).setVisibility(View.INVISIBLE);
        addSelected(button(upRow, "↑", false, v -> nudge(0, -nudgeStep())));
        button(upRow, "", false, null).setVisibility(View.INVISIBLE);
        LinearLayout middleRow = row(panel);
        addSelected(button(middleRow, "←", false, v -> nudge(-nudgeStep(), 0)));
        addSelected(button(middleRow, "⌂", false, v -> resetPosition()));
        addSelected(button(middleRow, "→", false, v -> nudge(nudgeStep(), 0)));
        LinearLayout downRow = row(panel);
        button(downRow, "", false, null).setVisibility(View.INVISIBLE);
        addSelected(button(downRow, "↓", false, v -> nudge(0, nudgeStep())));
        button(downRow, "", false, null).setVisibility(View.INVISIBLE);

        LinearLayout centerRow = row(panel);
        addSelected(button(centerRow, "↔ ناوەندی لاپەڕە", false, v -> centerOnPage(true)));
        addSelected(button(centerRow, "↕ ناوەندی لاپەڕە", false, v -> centerOnPage(false)));
        LinearLayout resetRow = row(panel);
        addSelected(button(resetRow, "گەڕاندنەوە بۆ قسەهەڵگر", false,
                v -> resetPosition()));
        addSelected(button(resetRow, "سووڕان بکە بە 0°", false, v -> {
            Segment segment = selectedSegment();
            if (segment != null && !segment.ensureStyle().locked) {
                pushHistory();
                segment.ensureStyle().rotationDegrees = 0f;
                canvas.refreshStyles();
                bindSelectedControls();
                scheduleAutosave();
            }
        }));
        LinearLayout layerRow = row(panel);
        addSelected(button(layerRow, "⬆ بۆ پێشەوە", false, v -> moveLayer(1)));
        addSelected(button(layerRow, "⬇ بۆ دواوە", false, v -> moveLayer(-1)));
        snapCheck = new CheckBox(this);
        snapCheck.setText("لکاندن بە ناوەندی لاپەڕە و قسەهەڵگری ڕەسەن");
        snapCheck.setChecked(true);
        snapCheck.setTextColor(Color.rgb(203, 213, 225));
        snapCheck.setButtonTintList(android.content.res.ColorStateList.valueOf(
                Color.rgb(45, 212, 191)));
        snapCheck.setLayoutParams(matchWrap(dp(8)));
        snapCheck.setOnCheckedChangeListener((buttonView, isChecked) ->
                canvas.setSnapEnabled(isChecked));
        panel.addView(snapCheck);
        hint(panel, "لەسەر وێنەکە: دەقەکە ڕابکێشە، گۆشەی سەوز بۆ قەبارە، "
                + "بازنە زەردەکە بۆ سووڕاندن، دوو پەنجە بۆ زووم.");
    }

    private void buildProjectPanel() {
        LinearLayout panel = createPanel();
        heading(panel, "پرۆژە و ئامرازە گشتییەکان");
        projectStats = textBox(panel, "—");
        addSelected(button(panel, "↺ گەڕاندنەوەی ستایلی ئەم دەقە بۆ بنەڕەتی", false,
                v -> {
                    Segment segment = selectedSegment();
                    if (segment == null) {
                        return;
                    }
                    pushHistory();
                    segment.ensureStyle().copyVisualFrom(globalDefaultStyle);
                    segment.ensureStyle().rotationDegrees = 0f;
                    canvas.refreshStyles();
                    bindSelectedControls();
                    scheduleAutosave();
                }));
        button(panel, "🔎 دۆزینەوە و گۆڕین لە هەموو وەرگێڕانەکان", false,
                v -> showFindReplace());
        button(panel, "☰ لیستی دەقەکانی ئەم لاپەڕەیە", false,
                v -> showBlockList());
        button(panel, "💾 پاشەکەوتکردنی پرۆژەکە ئێستا", false,
                v -> saveProjectAsync(() -> setStatus("✅ پرۆژەکە پاشەکەوت کرا.")));
        button(panel, "✓ پاشەکەوتکردن و دەرکردنی نوێ", true,
                v -> saveAndExport());
        button(panel, "⚠ هەموو دەستکارییەکانی ئەم دانیشتنە بسڕەوە", false,
                v -> confirmResetAllEdits());
        button(panel, "🗑 سڕینەوەی پرۆژەی دەستکاری و ئازادکردنی شوێن", false,
                v -> confirmDeleteProject());
        hint(panel, "پرۆژەکە بە خۆکار لەناو مۆبایلەکەدا پاشەکەوت دەبێت. "
                + "«دەرکردن» وێنە یان CBZی نوێ دروست دەکات و لە Downloads دایدەنێت.");
    }

    // ------------------------------------------------------------------
    // Binding and style changes
    // ------------------------------------------------------------------

    private void bindSelectedControls() {
        bindingControls = true;
        Segment segment = selectedSegment();
        updateSelectedControlsEnabled();
        updateProjectStats();
        if (segment == null) {
            originalTextView.setText("—");
            translatedEdit.setText("");
            geometryLabel.setText("—");
            updateSimpleControls(null);
            mainHandler.post(() -> bindingControls = false);
            return;
        }
        TextStyleSettings style = segment.ensureStyle();
        originalTextView.setText(segment.text == null || segment.text.isEmpty()
                ? "(دەقی نوێی دەستی)" : segment.text);
        if (!translatedEdit.hasFocus()) {
            translatedEdit.setText(segment.ckb == null ? "" : segment.ckb);
        }
        visibleCheck.setChecked(style.visible);
        eraseCheck.setChecked(style.eraseOriginal);
        forceEraseCheck.setChecked(style.forceErase);
        lockedCheck.setChecked(style.locked);

        fontSpinner.setSelection(FontCatalog.indexOfId(fontOptions, style.fontId), false);
        presetSpinner.setSelection(lastPresetIndex, false);
        setSeek(weightSeek, "ئەستووری فۆنت: " + style.fontWeight,
                (style.fontWeight - 100) / 50);
        setSeek(sizeSeek, "قەبارەی دەق: " + Math.round(style.sizeScale * 100f) + "%",
                Math.round(style.sizeScale * 100f) - 45);
        setSeek(lineSeek, "بۆشایی هێڵ: "
                        + String.format(Locale.ROOT, "%.2f", style.lineSpacing),
                Math.round((style.lineSpacing - 0.75f) * 100f));
        setSeek(opacitySeek, "ڕوونی دەق: " + Math.round(style.textOpacity * 100f / 255f) + "%",
                style.textOpacity - 20);
        alignmentSpinner.setSelection(style.alignment, false);
        italicCheck.setChecked(style.italic);
        underlineCheck.setChecked(style.underline);
        strikeCheck.setChecked(style.strikethrough);

        autoColorCheck.setChecked(style.autoTextColor);
        gradientCheck.setChecked(style.gradient);
        gradientDirectionSpinner.setSelection(style.gradientDirection, false);
        outlineCheck.setChecked(style.outline);
        setSeek(outlineSeek, "ئەستووری دەوروبەر: "
                        + Math.round(style.outlineWidthRatio * 100f) + "%",
                Math.round(style.outlineWidthRatio * 100f));
        shadowCheck.setChecked(style.shadow);
        setSeek(shadowSeek, "هێزی سێبەر: " + Math.round(style.shadowStrength * 100f) + "%",
                Math.round(style.shadowStrength * 100f));

        backgroundModeSpinner.setSelection(style.backgroundMode, false);
        setSeek(backgroundOpacitySeek, "ڕوونی پاشبنەما: "
                        + Math.round(style.backgroundOpacity * 100f / 255f) + "%",
                style.backgroundOpacity);
        backgroundShapeSpinner.setSelection(style.backgroundShape, false);
        setSeek(radiusSeek, "خڕی گۆشەکان: "
                        + Math.round(style.backgroundRadiusRatio * 100f) + "%",
                Math.round(style.backgroundRadiusRatio * 100f));
        setSeek(paddingSeek, "بۆشایی ناوەوە: "
                        + Math.round(style.paddingScale * 100f) + "%",
                Math.round((style.paddingScale - 0.35f) * 100f));
        borderCheck.setChecked(style.backgroundBorder);
        setSeek(borderSeek, "ئەستووری سنوور: "
                        + String.format(Locale.ROOT, "%.1fpx", style.backgroundBorderWidth),
                Math.round(style.backgroundBorderWidth * 2f));

        setSeek(rotationSeek, "سووڕان: " + Math.round(style.rotationDegrees) + "°",
                Math.round(style.rotationDegrees) + 180);
        updateColorButtons(style);
        updateGeometryLabel();
        updateSimpleControls(segment);
        mainHandler.post(() -> bindingControls = false);
    }

    private void updateSimpleControls(Segment segment) {
        if (simpleSelectedText == null) {
            return;
        }
        if (segment == null) {
            simpleSelectedText.setText("هیچ دەقێک هەڵنەبژێردراوە");
            if (simpleFontButton != null) {
                simpleFontButton.setText("A  فۆنت هەڵبژێرە");
            }
            if (simpleSizeLabel != null) {
                simpleSizeLabel.setText("قەبارە: —");
            }
            if (simpleVisibilityButton != null) {
                simpleVisibilityButton.setText("👁  پیشان بدە");
            }
            return;
        }
        TextStyleSettings style = segment.ensureStyle();
        String shown = segment.ckb == null ? "" : segment.ckb.trim()
                .replace('\n', ' ');
        if (shown.isEmpty()) {
            shown = "(دەقەکە بەتاڵە)";
        } else if (shown.length() > 72) {
            shown = shown.substring(0, 72) + "…";
        }
        String ocrWarning = segment.ocrConfidence >= 0f
                && segment.ocrConfidence < 0.78f
                ? "⚠ OCR گوماناوییە • " : "";
        simpleSelectedText.setText(ocrWarning + "دەقی "
                + (selectedSegmentIndex + 1) + ": " + shown);
        if (simpleFontButton != null) {
            String fontLabel = style.fontId;
            for (FontCatalog.FontOption option : fontOptions) {
                if (option.id.equals(style.fontId)) {
                    fontLabel = option.label;
                    break;
                }
            }
            simpleFontButton.setText("A  فۆنت: " + fontLabel);
        }
        if (simpleSizeLabel != null) {
            simpleSizeLabel.setText("قەبارە: "
                    + Math.round(style.sizeScale * 100f) + "٪");
        }
        if (simpleVisibilityButton != null) {
            simpleVisibilityButton.setText(style.visible
                    ? "🙈  ئەم دەقە بشارەوە"
                    : "👁  ئەم دەقە پیشان بدە");
        }
    }

    private void updateColorButtons(TextStyleSettings style) {
        if (textColorButton != null) {
            textColorButton.setText("A  ڕەنگی دەق  "
                    + TextStyleSettings.hex(style.textColor));
        }
        if (gradientColorButton != null) {
            gradientColorButton.setText("↘  گرادیانت  "
                    + TextStyleSettings.hex(style.gradientColor));
        }
        if (outlineColorButton != null) {
            outlineColorButton.setText("◐  دەوروبەر  "
                    + TextStyleSettings.hex(style.outlineColor));
        }
        if (shadowColorButton != null) {
            shadowColorButton.setText("☁  سێبەر  "
                    + TextStyleSettings.hex(style.shadowColor));
        }
        if (backgroundColorButton != null) {
            backgroundColorButton.setText("▦  پاشبنەما  "
                    + TextStyleSettings.hex(style.backgroundColor));
        }
        if (borderColorButton != null) {
            borderColorButton.setText("▣  سنوور  "
                    + TextStyleSettings.hex(style.backgroundBorderColor));
        }
    }

    private final SeekBar.OnSeekBarChangeListener seekListener =
            new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress,
                                              boolean fromUser) {
                    if (!fromUser || bindingControls) {
                        return;
                    }
                    String tag = String.valueOf(seekBar.getTag());
                    if ("nudgeStep".equals(tag)) {
                        nudgeStepSeek.label.setText(
                                "هەنگاوی جوڵاندن: " + (progress + 1) + "px");
                        return;
                    }
                    Segment segment = selectedSegment();
                    if (segment == null) {
                        return;
                    }
                    TextStyleSettings style = segment.ensureStyle();
                    switch (tag) {
                        case "weight":
                            style.fontWeight = 100 + progress * 50;
                            weightSeek.label.setText("ئەستووری فۆنت: " + style.fontWeight);
                            break;
                        case "size":
                            style.sizeScale = (progress + 45) / 100f;
                            sizeSeek.label.setText("قەبارەی دەق: " + (progress + 45) + "%");
                            break;
                        case "line":
                            style.lineSpacing = 0.75f + progress / 100f;
                            lineSeek.label.setText("بۆشایی هێڵ: " + String.format(
                                    Locale.ROOT, "%.2f", style.lineSpacing));
                            break;
                        case "opacity":
                            style.textOpacity = progress + 20;
                            opacitySeek.label.setText("ڕوونی دەق: "
                                    + Math.round(style.textOpacity * 100f / 255f) + "%");
                            break;
                        case "outlineWidth":
                            style.outlineWidthRatio = progress / 100f;
                            outlineSeek.label.setText(
                                    "ئەستووری دەوروبەر: " + progress + "%");
                            break;
                        case "shadowStrength":
                            style.shadowStrength = progress / 100f;
                            shadowSeek.label.setText("هێزی سێبەر: " + progress + "%");
                            break;
                        case "backgroundOpacity":
                            style.backgroundOpacity = progress;
                            backgroundOpacitySeek.label.setText("ڕوونی پاشبنەما: "
                                    + Math.round(progress * 100f / 255f) + "%");
                            break;
                        case "radius":
                            style.backgroundRadiusRatio = progress / 100f;
                            radiusSeek.label.setText("خڕی گۆشەکان: " + progress + "%");
                            break;
                        case "padding":
                            style.paddingScale = 0.35f + progress / 100f;
                            paddingSeek.label.setText("بۆشایی ناوەوە: "
                                    + Math.round(style.paddingScale * 100f) + "%");
                            break;
                        case "borderWidth":
                            style.backgroundBorderWidth = progress / 2f;
                            borderSeek.label.setText("ئەستووری سنوور: " + String.format(
                                    Locale.ROOT, "%.1fpx", style.backgroundBorderWidth));
                            break;
                        case "rotation":
                            style.rotationDegrees = progress - 180;
                            rotationSeek.label.setText(
                                    "سووڕان: " + (progress - 180) + "°");
                            break;
                        default:
                            return;
                    }
                    style.sanitize();
                    canvas.refreshStyles();
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {
                    if (!bindingControls && !"nudgeStep".equals(
                            String.valueOf(seekBar.getTag()))) {
                        pushHistory();
                    }
                }

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                    updateGeometryLabel();
                    scheduleAutosave();
                }
            };

    private final CompoundButton.OnCheckedChangeListener checkListener =
            (buttonView, isChecked) -> {
                if (bindingControls) {
                    return;
                }
                Segment segment = selectedSegment();
                if (segment == null) {
                    return;
                }
                pushHistory();
                TextStyleSettings style = segment.ensureStyle();
                switch (String.valueOf(buttonView.getTag())) {
                    case "visible": style.visible = isChecked; break;
                    case "erase": style.eraseOriginal = isChecked; break;
                    case "forceErase": style.forceErase = isChecked; break;
                    case "locked": style.locked = isChecked; break;
                    case "italic": style.italic = isChecked; break;
                    case "underline": style.underline = isChecked; break;
                    case "strikethrough": style.strikethrough = isChecked; break;
                    case "autoColor": style.autoTextColor = isChecked; break;
                    case "gradient": style.gradient = isChecked; break;
                    case "outline": style.outline = isChecked; break;
                    case "shadow": style.shadow = isChecked; break;
                    case "border": style.backgroundBorder = isChecked; break;
                    default: return;
                }
                canvas.refreshStyles();
                scheduleAutosave();
            };

    private final AdapterView.OnItemSelectedListener spinnerListener =
            new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view,
                                           int position, long id) {
                    if (bindingControls) {
                        return;
                    }
                    String tag = String.valueOf(parent.getTag());
                    if ("preset".equals(tag)) {
                        if (!presetUserTouched) {
                            lastPresetIndex = position;
                            return;
                        }
                        presetUserTouched = false;
                        lastPresetIndex = position;
                        applyPreset(position);
                        return;
                    }
                    Segment segment = selectedSegment();
                    if (segment == null) {
                        return;
                    }
                    TextStyleSettings style = segment.ensureStyle();
                    boolean changed = false;
                    switch (tag) {
                        case "font":
                            if (position >= 0 && position < fontOptions.size()
                                    && !fontOptions.get(position).id.equals(style.fontId)) {
                                pushHistory();
                                style.fontId = fontOptions.get(position).id;
                                changed = true;
                            }
                            break;
                        case "alignment":
                            if (style.alignment != position) {
                                pushHistory();
                                style.alignment = position;
                                changed = true;
                            }
                            break;
                        case "gradientDirection":
                            if (style.gradientDirection != position) {
                                pushHistory();
                                style.gradientDirection = position;
                                changed = true;
                            }
                            break;
                        case "backgroundMode":
                            if (style.backgroundMode != position) {
                                pushHistory();
                                style.backgroundMode = position;
                                changed = true;
                            }
                            break;
                        case "backgroundShape":
                            if (style.backgroundShape != position) {
                                pushHistory();
                                style.backgroundShape = position;
                                changed = true;
                            }
                            break;
                        default:
                            break;
                    }
                    if (changed) {
                        style.sanitize();
                        canvas.refreshStyles();
                        scheduleAutosave();
                    }
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {
                }
            };

    private void applyPreset(int preset) {
        Segment segment = selectedSegment();
        if (segment == null) {
            return;
        }
        lastPresetIndex = preset;
        pushHistory();
        segment.ensureStyle().applyPreset(preset);
        canvas.refreshStyles();
        bindSelectedControls();
        scheduleAutosave();
    }

    private void applyEditedText() {
        Segment segment = selectedSegment();
        if (segment == null) {
            return;
        }
        pushHistory();
        segment.ckb = translatedEdit.getText().toString().trim();
        if (!segment.ckb.isEmpty()) {
            segment.ensureStyle().visible = true;
        }
        canvas.refreshStyles();
        updateNavigation();
        updateProjectStats();
        scheduleAutosave();
        setStatus("✅ دەقەکە نوێکرایەوە.");
    }

    private void showEditTextDialog() {
        Segment segment = selectedSegment();
        if (segment == null) {
            return;
        }
        EditText input = new EditText(this);
        input.setText(segment.ckb == null ? "" : segment.ckb);
        input.setMinLines(3);
        input.setPadding(dp(14), dp(10), dp(14), dp(10));
        new AlertDialog.Builder(this)
                .setTitle("دەستکاری وەرگێڕانەکە")
                .setMessage(segment.text)
                .setView(input)
                .setNegativeButton("هەڵپەساردن", null)
                .setPositiveButton("پاشەکەوتکردن", (dialog, which) -> {
                    pushHistory();
                    segment.ckb = input.getText().toString().trim();
                    translatedEdit.setText(segment.ckb);
                    if (!segment.ckb.isEmpty()) {
                        segment.ensureStyle().visible = true;
                    }
                    canvas.refreshStyles();
                    scheduleAutosave();
                })
                .show();
    }

    // ------------------------------------------------------------------
    // Segment actions
    // ------------------------------------------------------------------

    private void addSegment() {
        TranslationProject.Page page = currentPage();
        if (page == null || currentBitmap == null) {
            setStatus("⚠ بۆ زیادکردنی دەق سەرەتا پێویستە وێنەی لاپەڕەکە باربێت.");
            return;
        }
        pushHistory();
        PointF center = canvas.visibleImageCenter();
        int width = Math.max(140, Math.round(currentBitmap.getWidth() * 0.42f));
        int height = Math.max(64, Math.round(width * 0.36f));
        int left = clamp(Math.round(center.x - width / 2f), 0,
                Math.max(0, currentBitmap.getWidth() - width));
        int top = clamp(Math.round(center.y - height / 2f), 0,
                Math.max(0, currentBitmap.getHeight() - height));
        Rect box = new Rect(left, top, left + width, top + height);
        Segment segment = new Segment("", box, 1);
        segment.ckb = "دەقی نوێ";
        segment.originalCkb = segment.ckb;
        Segment selected = selectedSegment();
        segment.style = new TextStyleSettings(
                selected == null ? globalDefaultStyle : selected.ensureStyle());
        segment.style.rotationDegrees = 0f;
        segment.style.visible = true;
        segment.style.eraseOriginal = false;
        segment.style.locked = false;
        int pixelX = clamp(Math.round(center.x), 0, currentBitmap.getWidth() - 1);
        int pixelY = clamp(Math.round(center.y), 0, currentBitmap.getHeight() - 1);
        int pixel = currentBitmap.getPixel(pixelX, pixelY);
        segment.detectedBackgroundColor = Color.WHITE;
        segment.detectedInkColor = luminance(pixel) > 125 ? Color.BLACK : Color.WHITE;
        if (segment.style.backgroundMode == TextStyleSettings.BG_AUTO) {
            segment.style.backgroundMode = TextStyleSettings.BG_CUSTOM;
            segment.style.backgroundColor = Color.WHITE;
            segment.style.textColor = Color.BLACK;
            segment.style.autoTextColor = false;
        }
        page.segments.add(segment);
        selectedSegmentIndex = page.segments.size() - 1;
        selectedByPage.put(pageIndex, selectedSegmentIndex);
        canvas.setSegments(page.segments, selectedSegmentIndex);
        canvas.centerOnSegment(selectedSegmentIndex);
        bindSelectedControls();
        updateNavigation();
        scheduleAutosave();
        showPanel(currentPanelIndex < PANEL_ADVANCED_FIRST
                ? PANEL_TEXTS : PANEL_ADVANCED_TEXT);
    }

    private void duplicateSegment() {
        TranslationProject.Page page = currentPage();
        Segment segment = selectedSegment();
        if (page == null || segment == null) {
            return;
        }
        pushHistory();
        Segment copy = segment.duplicate();
        copy.box.offset(24, 24);
        copy.getRenderBox().offset(24, 24);
        copy.getEraseBox().offset(24, 24);
        copy.ensureStyle().eraseOriginal = false;
        copy.ensureStyle().forceErase = false;
        page.segments.add(selectedSegmentIndex + 1, copy);
        selectedSegmentIndex++;
        canvas.setSegments(page.segments, selectedSegmentIndex);
        bindSelectedControls();
        updateNavigation();
        scheduleAutosave();
    }

    private void deleteSegment() {
        TranslationProject.Page page = currentPage();
        Segment segment = selectedSegment();
        if (page == null || segment == null) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("سڕینەوەی ئەم دەقە؟")
                .setMessage(segment.ckb == null || segment.ckb.isEmpty()
                        ? segment.text : segment.ckb)
                .setNegativeButton("نەخێر", null)
                .setPositiveButton("سڕینەوە", (dialog, which) -> {
                    pushHistory();
                    page.segments.remove(selectedSegmentIndex);
                    selectedSegmentIndex = page.segments.isEmpty() ? -1
                            : Math.min(selectedSegmentIndex, page.segments.size() - 1);
                    selectedByPage.put(pageIndex, selectedSegmentIndex);
                    canvas.setSegments(page.segments, selectedSegmentIndex);
                    bindSelectedControls();
                    updateNavigation();
                    scheduleAutosave();
                })
                .show();
    }

    private void moveLayer(int direction) {
        TranslationProject.Page page = currentPage();
        if (page == null || selectedSegment() == null) {
            return;
        }
        int next = selectedSegmentIndex + direction;
        if (next < 0 || next >= page.segments.size()) {
            return;
        }
        pushHistory();
        Collections.swap(page.segments, selectedSegmentIndex, next);
        selectedSegmentIndex = next;
        canvas.setSegments(page.segments, selectedSegmentIndex);
        bindSelectedControls();
        scheduleAutosave();
    }

    private int simpleNudgeStep() {
        float scale = canvas == null ? 1f : Math.max(0.05f, canvas.getScale());
        return Math.max(5, Math.min(60, Math.round(14f / scale)));
    }

    private int simpleResizeStep() {
        float scale = canvas == null ? 1f : Math.max(0.05f, canvas.getScale());
        return Math.max(8, Math.min(72, Math.round(16f / scale)));
    }

    private void resizeSelectedBy(int deltaWidth, int deltaHeight) {
        Segment segment = selectedSegment();
        if (segment == null) {
            return;
        }
        if (segment.ensureStyle().locked) {
            setStatus("⚠ ئەم دەقە قفڵ کراوە؛ سەرەتا لە ئامرازە پێشکەوتووەکان قفڵەکەی بکەرەوە.");
            return;
        }
        pushHistory();
        Rect box = segment.getRenderBox();
        int centerX = box.centerX();
        int centerY = box.centerY();
        int maxWidth = currentBitmap == null ? 10000
                : Math.max(28, currentBitmap.getWidth() * 2);
        int maxHeight = currentBitmap == null ? 10000
                : Math.max(20, currentBitmap.getHeight() * 2);
        int newWidth = Math.max(28, Math.min(maxWidth,
                box.width() + deltaWidth));
        int newHeight = Math.max(20, Math.min(maxHeight,
                box.height() + deltaHeight));
        box.left = centerX - (newWidth / 2);
        box.top = centerY - (newHeight / 2);
        box.right = box.left + newWidth;
        box.bottom = box.top + newHeight;
        canvas.refreshStyles();
        updateGeometryLabel();
        scheduleAutosave();
    }

    private void rotateSelectedBy(float deltaDegrees) {
        Segment segment = selectedSegment();
        if (segment == null) {
            return;
        }
        if (segment.ensureStyle().locked) {
            setStatus("⚠ ئەم دەقە قفڵ کراوە؛ سەرەتا لە ئامرازە پێشکەوتووەکان قفڵەکەی بکەرەوە.");
            return;
        }
        pushHistory();
        TextStyleSettings style = segment.ensureStyle();
        if (deltaDegrees == 0f) {
            style.rotationDegrees = 0f;
        } else {
            float next = style.rotationDegrees + deltaDegrees;
            while (next > 180f) {
                next -= 360f;
            }
            while (next < -180f) {
                next += 360f;
            }
            style.rotationDegrees = next;
        }
        canvas.refreshStyles();
        bindSelectedControls();
        scheduleAutosave();
    }

    private void nudge(int dx, int dy) {
        Segment segment = selectedSegment();
        if (segment == null || segment.ensureStyle().locked) {
            return;
        }
        pushHistory();
        segment.getRenderBox().offset(dx, dy);
        canvas.refreshStyles();
        updateGeometryLabel();
        scheduleAutosave();
    }

    private int nudgeStep() {
        return nudgeStepSeek == null ? 10 : nudgeStepSeek.seek.getProgress() + 1;
    }

    private void centerOnPage(boolean horizontal) {
        Segment segment = selectedSegment();
        if (segment == null || currentBitmap == null
                || segment.ensureStyle().locked) {
            return;
        }
        pushHistory();
        Rect box = segment.getRenderBox();
        if (horizontal) {
            box.offsetTo((currentBitmap.getWidth() - box.width()) / 2, box.top);
        } else {
            box.offsetTo(box.left, (currentBitmap.getHeight() - box.height()) / 2);
        }
        canvas.refreshStyles();
        updateGeometryLabel();
        scheduleAutosave();
    }

    private void resetPosition() {
        Segment segment = selectedSegment();
        if (segment == null || segment.ensureStyle().locked) {
            return;
        }
        pushHistory();
        segment.renderBox = new Rect(segment.getEraseBox());
        canvas.refreshStyles();
        updateGeometryLabel();
        scheduleAutosave();
    }

    private void confirmApplyStyle(boolean allPages) {
        Segment source = selectedSegment();
        if (source == null) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(allPages ? "بۆ هەموو لاپەڕەکان؟" : "بۆ هەموو ئەم لاپەڕەیە؟")
                .setMessage("فۆنت، ڕەنگ، پاشبنەما، دەوروبەر و سێبەری ئەم دەقە دەگوازرێتەوە؛ "
                        + "شوێن و سووڕانی هەر دەقێک وەک خۆی دەمێنێتەوە.")
                .setNegativeButton("نەخێر", null)
                .setPositiveButton("جێبەجێ بکە", (dialog, which) -> {
                    pushHistory();
                    TextStyleSettings style = new TextStyleSettings(source.ensureStyle());
                    if (allPages) {
                        for (TranslationProject.Page page : project.pages) {
                            for (Segment segment : page.segments) {
                                segment.ensureStyle().copyVisualFrom(style);
                            }
                        }
                    } else {
                        for (Segment segment : currentPage().segments) {
                            segment.ensureStyle().copyVisualFrom(style);
                        }
                    }
                    canvas.refreshStyles();
                    scheduleAutosave();
                    setStatus("✅ ستایلەکە جێبەجێ کرا.");
                })
                .show();
    }

    private void makeSelectedStyleDefault() {
        Segment segment = selectedSegment();
        if (segment == null) {
            return;
        }
        globalDefaultStyle = new TextStyleSettings(segment.ensureStyle());
        globalDefaultStyle.prepareAsGlobalDefault();
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putString(PREF_GLOBAL_STYLE, globalDefaultStyle.toJsonString())
                .apply();
        setStatus("✅ ئەم ستایلە بوو بە ستایلی بنەڕەتی بۆ وەرگێڕانەکانی داهاتوو.");
    }

    private void armSampler(String target) {
        if (selectedSegment() == null) {
            setStatus("⚠ سەرەتا دەقێک هەڵبژێرە.");
            return;
        }
        samplerTarget = target;
        samplerArmed = true;
        setStatus("🖍 ئێستا لەسەر وێنەکە بکە بۆ هەڵگرتنی ڕەنگ بۆ "
                + ("background".equals(target) ? "پاشبنەما." : "دەق."));
    }

    // ------------------------------------------------------------------
    // Project actions, history and persistence
    // ------------------------------------------------------------------

    private void showBlockList() {
        TranslationProject.Page page = currentPage();
        if (page == null || page.segments.isEmpty()) {
            setStatus("ئەم لاپەڕەیە هیچ دەقێکی نییە.");
            return;
        }
        String[] items = new String[page.segments.size()];
        for (int i = 0; i < items.length; i++) {
            Segment segment = page.segments.get(i);
            String shown = segment.ckb == null || segment.ckb.trim().isEmpty()
                    ? segment.text : segment.ckb;
            shown = shown.replace('\n', ' ');
            if (shown.length() > 70) {
                shown = shown.substring(0, 70) + "…";
            }
            String ocrWarning = segment.ocrConfidence >= 0f
                    && segment.ocrConfidence < 0.78f ? "[⚠ OCR] " : "";
            items[i] = (i + 1) + ". "
                    + (segment.ensureStyle().visible ? "" : "[شاردراوە] ")
                    + ocrWarning + shown;
        }
        new AlertDialog.Builder(this)
                .setTitle("دەقەکانی لاپەڕە " + (pageIndex + 1))
                .setItems(items, (dialog, which) -> selectSegment(which, true))
                .setNegativeButton("داخستن", null)
                .show();
    }

    private void showFindReplace() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        layout.setPadding(pad, pad, pad, pad);
        EditText find = new EditText(this);
        find.setHint("دۆزینەوە لە وەرگێڕانەکاندا…");
        EditText replace = new EditText(this);
        replace.setHint("گۆڕین بۆ…");
        layout.addView(find);
        layout.addView(replace);
        new AlertDialog.Builder(this)
                .setTitle("دۆزینەوە و گۆڕین")
                .setView(layout)
                .setNegativeButton("هەڵپەساردن", null)
                .setPositiveButton("گۆڕین لە هەموو پرۆژەکە", (dialog, which) -> {
                    String from = find.getText().toString();
                    String to = replace.getText().toString();
                    if (from.isEmpty() || project == null) {
                        return;
                    }
                    pushHistory();
                    int count = 0;
                    for (TranslationProject.Page page : project.pages) {
                        for (Segment segment : page.segments) {
                            if (segment.ckb != null && segment.ckb.contains(from)) {
                                segment.ckb = segment.ckb.replace(from, to);
                                count++;
                            }
                        }
                    }
                    canvas.refreshStyles();
                    bindSelectedControls();
                    scheduleAutosave();
                    setStatus(count == 0 ? "هیچ دەقێک نەدۆزرایەوە."
                            : "✅ " + count + " دەق گۆڕدرا.");
                })
                .show();
    }

    private void confirmResetAllEdits() {
        if (originalSnapshotAtOpen == null) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("سڕینەوەی دەستکارییەکان؟")
                .setMessage("هەموو گۆڕانکارییەکانی شوێن، فۆنت، ڕەنگ و دەق لەم دانیشتنەدا "
                        + "دەگەڕێنەوە بۆ ئەنجامی خۆکاری وەرگێڕانەکە.")
                .setNegativeButton("نەخێر", null)
                .setPositiveButton("گەڕاندنەوە", (dialog, which) -> {
                    pushHistory();
                    restoreSnapshot(originalSnapshotAtOpen);
                    setStatus("↺ دەستکارییەکان گەڕێنرانەوە. دەتوانیت Undo بەکاربهێنیت بۆ گەڕانەوە.");
                })
                .show();
    }

    private void confirmDeleteProject() {
        if (project == null) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("سڕینەوەی پرۆژەی دەستکاری؟")
                .setMessage("لاپەڕە ڕەسەنەکان و شوێن/ستایلە پاشەکەوتکراوەکانی ئەم "
                        + "وەرگێڕانە دەسڕدرێنەوە. وێنە/CBZ و TXTی پێشتر دەرکراو لە "
                        + "Downloads وەک خۆیان دەمێننەوە.")
                .setNegativeButton("نەخێر", null)
                .setPositiveButton("سڕینەوە", (dialog, which) -> {
                    mainHandler.removeCallbacks(autosaveRunnable);
                    setStatus("سڕینەوەی پرۆژەکە…");
                    executor.execute(() -> {
                        TranslationProjectStore.deleteCurrent(getApplicationContext());
                        runOnUiThread(() -> {
                            project = null;
                            setResult(Activity.RESULT_CANCELED);
                            finish();
                        });
                    });
                })
                .show();
    }

    private void pushHistory() {
        if (project == null || applyingHistory) {
            return;
        }
        String snapshot = project.snapshotJson();
        if (snapshot == null) {
            return;
        }
        if (!undoStack.isEmpty() && snapshot.equals(undoStack.peek())) {
            return;
        }
        undoStack.push(snapshot);
        long retainedChars = 0;
        for (String item : undoStack) {
            retainedChars += item.length();
        }
        while (undoStack.size() > 50
                || (retainedChars > 20_000_000L && undoStack.size() > 5)) {
            String removed = undoStack.removeLast();
            retainedChars -= removed.length();
        }
        redoStack.clear();
        updateHistoryButtons();
    }

    private void undo() {
        if (undoStack.isEmpty() || project == null) {
            return;
        }
        String current = project.snapshotJson();
        if (current != null) {
            redoStack.push(current);
        }
        restoreSnapshot(undoStack.pop());
        updateHistoryButtons();
    }

    private void redo() {
        if (redoStack.isEmpty() || project == null) {
            return;
        }
        String current = project.snapshotJson();
        if (current != null) {
            undoStack.push(current);
        }
        restoreSnapshot(redoStack.pop());
        updateHistoryButtons();
    }

    private void restoreSnapshot(String snapshot) {
        try {
            applyingHistory = true;
            project.restoreSnapshot(snapshot);
            TranslationProjectStore.setCurrent(project);
            pageIndex = Math.max(0, Math.min(pageIndex, project.pages.size() - 1));
            TranslationProject.Page page = currentPage();
            selectedSegmentIndex = page == null || page.segments.isEmpty() ? -1
                    : Math.max(0, Math.min(selectedSegmentIndex, page.segments.size() - 1));
            selectedByPage.put(pageIndex, selectedSegmentIndex);
            canvas.setSegments(page == null ? null : page.segments, selectedSegmentIndex);
            bindSelectedControls();
            updateNavigation();
            scheduleAutosave();
        } catch (Exception e) {
            setStatus("❌ نەتوانرا مێژووەکە بگەڕێنرێتەوە: " + friendly(e));
        } finally {
            applyingHistory = false;
        }
    }

    private void scheduleAutosave() {
        if (project == null) {
            return;
        }
        mainHandler.removeCallbacks(autosaveRunnable);
        mainHandler.postDelayed(autosaveRunnable, 700L);
    }

    private void saveProjectAsync(Runnable afterSave) {
        if (project == null) {
            if (afterSave != null) {
                afterSave.run();
            }
            return;
        }
        final String snapshot = project.snapshotJson();
        final File dir = project.projectDir;
        executor.execute(() -> {
            Exception failure = null;
            try {
                if (snapshot == null || dir == null) {
                    throw new IllegalStateException("Project snapshot is empty");
                }
                TranslationProject copy = TranslationProject.fromJson(
                        new JSONObject(snapshot), dir);
                copy.save();
            } catch (Exception e) {
                failure = e;
            }
            final Exception resultFailure = failure;
            runOnUiThread(() -> {
                if (resultFailure != null) {
                    setStatus("❌ پاشەکەوتکردنی پرۆژەکە سەرنەکەوت: "
                            + friendly(resultFailure));
                    return;
                }
                if (afterSave != null) {
                    afterSave.run();
                }
            });
        });
    }

    private void updateProjectStats() {
        if (projectStats == null) {
            return;
        }
        if (project == null) {
            projectStats.setText("—");
            return;
        }
        int visible = 0;
        int hidden = 0;
        for (TranslationProject.Page page : project.pages) {
            for (Segment segment : page.segments) {
                if (segment.ensureStyle().visible) {
                    visible++;
                } else {
                    hidden++;
                }
            }
        }
        projectStats.setText(project.pages.size() + " لاپەڕە • "
                + project.totalSegments() + " دەق • "
                + visible + " پیشاندراو • " + hidden + " شاردراوە\n"
                + (project.displayName == null ? "" : project.displayName));
    }

    // ------------------------------------------------------------------
    // Fonts
    // ------------------------------------------------------------------

    private void refreshFontSpinner() {
        fontOptions = FontCatalog.getOptions(this);
        if (fontSpinner != null) {
            bindingControls = true;
            fontSpinner.setAdapter(new FontSpinnerAdapter(this, fontOptions));
            Segment segment = selectedSegment();
            if (segment != null) {
                fontSpinner.setSelection(FontCatalog.indexOfId(
                        fontOptions, segment.ensureStyle().fontId), false);
            }
            mainHandler.post(() -> bindingControls = false);
        }
    }

    private void importFont(Uri uri) {
        setStatus("هاوردەکردنی فۆنتەکە…");
        new Thread(() -> {
            try {
                String fontId = FontCatalog.importFont(getApplicationContext(), uri);
                runOnUiThread(() -> {
                    refreshFontSpinner();
                    Segment segment = selectedSegment();
                    if (segment != null) {
                        pushHistory();
                        segment.ensureStyle().fontId = fontId;
                        canvas.refreshStyles();
                        bindSelectedControls();
                        scheduleAutosave();
                    }
                    setStatus("✅ فۆنتەکە هاوردەکرا و ئامادەیە بۆ بەکارهێنان.");
                });
            } catch (Exception e) {
                runOnUiThread(() -> setStatus("❌ " + friendly(e)));
            }
        }, "KurdManFontImport").start();
    }

    private void deleteCurrentCustomFont() {
        Segment segment = selectedSegment();
        if (segment == null || !FontCatalog.isCustom(segment.ensureStyle().fontId)) {
            setStatus("⚠ تەنها فۆنتی هاوردەکراوی هەڵبژێردراو دەسڕدرێتەوە.");
            return;
        }
        final String fontId = segment.ensureStyle().fontId;
        new AlertDialog.Builder(this)
                .setTitle("سڕینەوەی فۆنتەکە؟")
                .setMessage("هەر دەقێک ئەم فۆنتە بەکاردەهێنێت دەگەڕێتەوە بۆ Vazirmatn.")
                .setNegativeButton("نەخێر", null)
                .setPositiveButton("سڕینەوە", (dialog, which) -> {
                    pushHistory();
                    FontCatalog.deleteCustomFont(this, fontId);
                    if (project != null) {
                        for (TranslationProject.Page page : project.pages) {
                            for (Segment item : page.segments) {
                                if (fontId.equals(item.ensureStyle().fontId)) {
                                    item.ensureStyle().fontId = FontCatalog.FONT_VAZIRMATN;
                                }
                            }
                        }
                    }
                    refreshFontSpinner();
                    canvas.refreshStyles();
                    bindSelectedControls();
                    scheduleAutosave();
                })
                .show();
    }

    // ------------------------------------------------------------------
    // Dynamic controls
    // ------------------------------------------------------------------

    private static final class SeekControl {
        TextView label;
        SeekBar seek;
    }

    private LinearLayout createPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setLayoutParams(matchWrap(0));
        panelContainer.addView(panel);
        panels.add(panel);
        return panel;
    }

    private void heading(LinearLayout panel, String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(Color.WHITE);
        view.setTextSize(16f);
        view.setTypeface(view.getTypeface(), android.graphics.Typeface.BOLD);
        view.setLayoutParams(matchWrap(dp(10)));
        panel.addView(view);
    }

    private TextView label(LinearLayout panel, String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(Color.rgb(203, 213, 225));
        view.setTextSize(13f);
        view.setLayoutParams(matchWrap(dp(8)));
        panel.addView(view);
        return view;
    }

    private TextView textBox(LinearLayout panel, String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(Color.rgb(226, 232, 240));
        view.setTextSize(13f);
        view.setBackgroundResource(R.drawable.bg_preview);
        view.setPadding(dp(12), dp(10), dp(12), dp(10));
        view.setLayoutParams(matchWrap(dp(6)));
        panel.addView(view);
        return view;
    }

    private void hint(LinearLayout panel, String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(Color.rgb(100, 116, 139));
        view.setTextSize(12f);
        view.setLineSpacing(0f, 1.2f);
        view.setLayoutParams(matchWrap(dp(10)));
        panel.addView(view);
    }

    private LinearLayout row(LinearLayout panel) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutParams(matchWrap(dp(8)));
        panel.addView(row);
        return row;
    }

    private Button button(LinearLayout parent, String text, boolean primary,
                          View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(13f);
        button.setMinWidth(0);
        button.setMinHeight(dp(48));
        button.setBackgroundResource(primary
                ? R.drawable.bg_button_primary : R.drawable.bg_button_secondary);
        button.setTextColor(primary ? Color.WHITE : Color.rgb(94, 234, 212));
        LinearLayout.LayoutParams params;
        if (parent.getOrientation() == LinearLayout.HORIZONTAL) {
            params = new LinearLayout.LayoutParams(0, dp(48), 1f);
            params.setMargins(dp(3), 0, dp(3), 0);
        } else {
            params = matchWrap(dp(7));
            params.height = dp(48);
        }
        button.setLayoutParams(params);
        if (listener != null) {
            button.setOnClickListener(listener);
        }
        parent.addView(button);
        return button;
    }

    private Button addSelected(Button button) {
        selectedControls.add(button);
        return button;
    }

    private CheckBox check(LinearLayout panel, String text, String tag) {
        CheckBox check = new CheckBox(this);
        check.setText(text);
        check.setTag(tag);
        check.setTextColor(Color.rgb(203, 213, 225));
        check.setTextSize(13f);
        check.setButtonTintList(android.content.res.ColorStateList.valueOf(
                Color.rgb(45, 212, 191)));
        check.setLayoutParams(matchWrap(dp(5)));
        check.setOnCheckedChangeListener(checkListener);
        panel.addView(check);
        selectedControls.add(check);
        return check;
    }

    private SeekControl seek(LinearLayout panel, String title, String tag, int max) {
        SeekControl control = new SeekControl();
        control.label = label(panel, title);
        control.seek = new SeekBar(this);
        control.seek.setTag(tag);
        control.seek.setMax(max);
        control.seek.setLayoutParams(matchWrap(0));
        control.seek.setOnSeekBarChangeListener(seekListener);
        panel.addView(control.seek);
        selectedControls.add(control.seek);
        return control;
    }

    private Spinner spinner(LinearLayout panel, String title, String tag,
                            ArrayAdapter<String> adapter) {
        label(panel, title);
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundResource(R.drawable.bg_input);
        LinearLayout.LayoutParams frameParams = matchWrap(dp(6));
        frameParams.height = dp(54);
        frame.setLayoutParams(frameParams);
        Spinner spinner = new Spinner(this);
        spinner.setTag(tag);
        spinner.setBackground(null);
        spinner.setPopupBackgroundDrawable(new android.graphics.drawable.ColorDrawable(
                getColor(R.color.card_elevated)));
        if (adapter != null) {
            spinner.setAdapter(adapter);
        }
        spinner.setOnItemSelectedListener(spinnerListener);
        frame.addView(spinner, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        TextView arrow = new TextView(this);
        arrow.setText("▾");
        arrow.setTextColor(Color.rgb(45, 212, 191));
        arrow.setTextSize(18f);
        FrameLayout.LayoutParams arrowParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.CENTER_VERTICAL | android.view.Gravity.END);
        arrowParams.setMarginEnd(dp(14));
        frame.addView(arrow, arrowParams);
        panel.addView(frame);
        selectedControls.add(spinner);
        return spinner;
    }

    private Button colorButton(LinearLayout panel, String text, String target) {
        Button button = button(panel, text, false, v -> openColorPicker(target));
        button.setTextColor(Color.WHITE);
        selectedControls.add(button);
        return button;
    }

    private void openColorPicker(String target) {
        Segment segment = selectedSegment();
        if (segment == null) {
            return;
        }
        TextStyleSettings style = segment.ensureStyle();
        int initial;
        String title;
        switch (target) {
            case "gradient": initial = style.gradientColor; title = "ڕەنگی دووەمی گرادیانت"; break;
            case "outline": initial = style.outlineColor; title = "ڕەنگی دەوروبەر"; break;
            case "shadow": initial = style.shadowColor; title = "ڕەنگی سێبەر"; break;
            case "background": initial = style.backgroundColor; title = "ڕەنگی پاشبنەما"; break;
            case "border": initial = style.backgroundBorderColor; title = "ڕەنگی سنوور"; break;
            default: initial = style.textColor; title = "ڕەنگی دەق"; break;
        }
        ColorPicker.show(this, title, initial, color -> {
            pushHistory();
            switch (target) {
                case "gradient": style.gradientColor = color; style.gradient = true; break;
                case "outline": style.outlineColor = color; style.outline = true; break;
                case "shadow": style.shadowColor = color; style.shadow = true; break;
                case "background":
                    style.backgroundColor = color;
                    style.backgroundMode = TextStyleSettings.BG_CUSTOM;
                    break;
                case "border":
                    style.backgroundBorderColor = color;
                    style.backgroundBorder = true;
                    break;
                default:
                    style.textColor = color;
                    style.autoTextColor = false;
                    break;
            }
            canvas.refreshStyles();
            bindSelectedControls();
            scheduleAutosave();
        });
    }

    private ArrayAdapter<String> simpleAdapter(String[] values) {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, R.layout.spinner_item, values);
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        return adapter;
    }

    private void setSeek(SeekControl control, String text, int progress) {
        if (control == null) {
            return;
        }
        control.label.setText(text);
        control.seek.setProgress(Math.max(0, Math.min(control.seek.getMax(), progress)));
    }

    private void updateSelectedControlsEnabled() {
        boolean enabled = selectedSegment() != null;
        for (View view : selectedControls) {
            view.setEnabled(enabled);
            view.setAlpha(enabled ? 1f : 0.48f);
        }
    }

    private void updateNavigation() {
        if (project == null) {
            pageLabel.setText("هیچ پرۆژەیەک نییە");
            blockLabel.setText("هیچ دەقێک نییە");
            return;
        }
        pageLabel.setText("لاپەڕە " + (pageIndex + 1) + "/" + project.pages.size());
        TranslationProject.Page page = currentPage();
        if (page == null || page.segments.isEmpty() || selectedSegmentIndex < 0) {
            blockLabel.setText("هیچ دەقێک هەڵنەبژێردراوە");
        } else {
            blockLabel.setText("دەق " + (selectedSegmentIndex + 1)
                    + "/" + page.segments.size());
        }
    }

    private void updateGeometryLabel() {
        if (geometryLabel == null) {
            return;
        }
        Segment segment = selectedSegment();
        if (segment == null) {
            geometryLabel.setText("—");
            return;
        }
        Rect box = segment.getRenderBox();
        geometryLabel.setText("X " + box.left + " • Y " + box.top
                + " • پانی " + box.width() + " • بەرزی " + box.height()
                + " • سووڕان "
                + Math.round(segment.ensureStyle().rotationDegrees) + "°");
    }

    private void updateHistoryButtons() {
        if (undoButton != null) {
            undoButton.setEnabled(!undoStack.isEmpty());
            undoButton.setAlpha(undoStack.isEmpty() ? 0.45f : 1f);
        }
        if (redoButton != null) {
            redoButton.setEnabled(!redoStack.isEmpty());
            redoButton.setAlpha(redoStack.isEmpty() ? 0.45f : 1f);
        }
    }

    private void setStatus(String text) {
        runOnUiThread(() -> {
            if (statusText != null) {
                statusText.setText(text);
            }
        });
    }

    private String friendly(Throwable error) {
        String message = error == null ? "" : error.getMessage();
        if (message == null || message.trim().isEmpty()) {
            message = error == null ? "هەڵەی نادیار" : error.getClass().getSimpleName();
        }
        return message.length() > 220 ? message.substring(0, 220) + "…" : message;
    }

    private LinearLayout.LayoutParams matchWrap(int topMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = topMargin;
        return params;
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private int luminance(int color) {
        return Math.round(0.299f * Color.red(color)
                + 0.587f * Color.green(color)
                + 0.114f * Color.blue(color));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
