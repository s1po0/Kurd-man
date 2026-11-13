package com.kurdman.app;

import android.graphics.Rect;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * The latest translated image/CBZ in an editable, restart-safe form.
 *
 * <p>Source pages are copied under the project directory and the OCR boxes,
 * translations, render boxes and styles are stored in {@code project.json}.
 * That lets the user finish a translation, leave the app, and later reopen
 * the same blocks to move them or change their appearance.</p>
 */
public class TranslationProject {
    static final String PROJECT_FILE = "project.json";

    public int version = 2;
    public boolean cbz;
    public boolean completed;
    public String displayName = "";
    public String baseName = "kurdman";
    public String sourceLanguage = "en";
    public String targetLanguage = "ckb";
    /** Frozen per-project choices, independent of future settings changes. */
    public ComicTranslationSkill.Config comicConfig = ComicSettings.fromJson(null);
    /** Frozen durable job options. Legacy completed projects remain readable. */
    public boolean offlineMode;
    public boolean accurateOcr = true;
    public boolean createPdf;
    public boolean imageBatch;
    /** Web-reader jobs stay private until the reader explicitly exports. */
    public boolean privateResults;
    public boolean resumable;
    public boolean autoResume;
    public int quotaPages;
    public boolean exportsComplete;
    public String outputBinding = "";
    public String quotaJobId = "";
    public String jobOwner = "";
    public TextStyleSettings jobStyle = new TextStyleSettings();
    public long createdAt = System.currentTimeMillis();
    public long updatedAt = System.currentTimeMillis();
    public final List<Page> pages = new ArrayList<>();
    public transient File projectDir;

    public static class Page {
        /** Path relative to the project directory, for example pages/0001.jpg. */
        public String sourcePath;
        public String originalExtension = ".jpg";
        public int width;
        public int height;
        public boolean unreadable;
        public boolean ocrComplete;
        public boolean translationComplete;
        public boolean renderComplete;
        public boolean layoutPrepared;
        /** Browser capture visibility at import; affects work order, not page order. */
        public boolean webPriority;
        public final List<Segment> segments = new ArrayList<>();

        public Page() {
        }

        public Page(String sourcePath, String originalExtension) {
            this.sourcePath = sourcePath;
            this.originalExtension = originalExtension == null
                    ? ".jpg" : originalExtension;
        }

        public File sourceFile(File projectDir) {
            if (sourcePath == null || projectDir == null) return null;
            try {
                File source = new File(projectDir, sourcePath).getCanonicalFile();
                String root = projectDir.getCanonicalPath() + File.separator;
                return source.getPath().startsWith(root) ? source : null;
            } catch (IOException invalid) { return null; }
        }
    }

    public int totalSegments() {
        int total = 0;
        for (Page page : pages) {
            total += page.segments.size();
        }
        return total;
    }

    public int renderedSegments() {
        int total = 0;
        for (Page page : pages) {
            for (Segment segment : page.segments) {
                if (segment.rendered) {
                    total++;
                }
            }
        }
        return total;
    }

    public synchronized void save() throws IOException {
        if (projectDir == null) {
            throw new IOException("Project directory is not set");
        }
        if (!projectDir.exists() && !projectDir.mkdirs()) {
            throw new IOException("Could not create project directory");
        }
        updatedAt = System.currentTimeMillis();
        String json;
        try {
            json = toJson().toString(2);
        } catch (JSONException e) {
            throw new IOException("Could not serialize translation project", e);
        }
        android.util.AtomicFile atomic = new android.util.AtomicFile(new File(projectDir, PROJECT_FILE));
        FileOutputStream stream = null;
        try {
            stream = atomic.startWrite();
            byte[] data = json.getBytes(StandardCharsets.UTF_8);
            if (data.length > 32 * 1024 * 1024) throw new IOException("Saved project metadata is too large");
            stream.write(data);
            atomic.finishWrite(stream);
        } catch (IOException failure) {
            if (stream != null) atomic.failWrite(stream);
            throw failure;
        }
    }

    public JSONObject toJson() throws JSONException {
        JSONObject root = new JSONObject();
        root.put("version", version);
        root.put("cbz", cbz);
        root.put("completed", completed);
        root.put("displayName", displayName == null ? "" : displayName);
        root.put("baseName", baseName == null ? "kurdman" : baseName);
        root.put("sourceLanguage", sourceLanguage == null ? "en" : sourceLanguage);
        root.put("targetLanguage", targetLanguage == null ? "ckb" : targetLanguage);
        root.put("comicSkill", ComicSettings.toJson(comicConfig));
        JSONObject job = new JSONObject();
        job.put("offline", offlineMode); job.put("accurateOcr", accurateOcr);
        job.put("pdf", createPdf); job.put("imageBatch", imageBatch); job.put("privateResults", privateResults);
        job.put("resumable", resumable); job.put("autoResume", autoResume);
        job.put("quotaPages", quotaPages); job.put("exportsComplete", exportsComplete); job.put("outputBinding", outputBinding);
        job.put("quotaJobId", quotaJobId); job.put("owner", jobOwner);
        job.put("style", jobStyle.toJson()); root.put("job", job);
        root.put("createdAt", createdAt);
        root.put("updatedAt", updatedAt);
        JSONArray pageArray = new JSONArray();
        for (Page page : pages) {
            pageArray.put(pageToJson(page));
        }
        root.put("pages", pageArray);
        return root;
    }

    public static TranslationProject fromJson(JSONObject root, File projectDir)
            throws JSONException {
        TranslationProject project = new TranslationProject();
        project.version = root.optInt("version", 1);
        project.cbz = root.optBoolean("cbz", false);
        project.completed = root.optBoolean("completed", false);
        project.displayName = root.optString("displayName", "");
        project.baseName = root.optString("baseName", "kurdman");
        project.sourceLanguage = root.optString("sourceLanguage", "en");
        project.targetLanguage = root.optString("targetLanguage", "ckb");
        project.comicConfig = ComicSettings.fromJson(root.optJSONObject("comicSkill"));
        JSONObject job = root.optJSONObject("job");
        if (job != null) {
            project.offlineMode = job.optBoolean("offline", false);
            project.accurateOcr = job.optBoolean("accurateOcr", true);
            project.createPdf = job.optBoolean("pdf", false);
            project.imageBatch = job.optBoolean("imageBatch", false);
            project.privateResults = job.optBoolean("privateResults", false);
            project.resumable = job.optBoolean("resumable", false);
            project.autoResume = job.optBoolean("autoResume", false);
            project.quotaPages = job.optInt("quotaPages", 0); project.exportsComplete = job.optBoolean("exportsComplete", false); project.outputBinding = job.optString("outputBinding", "");
            project.quotaJobId = job.optString("quotaJobId", "");
            project.jobOwner = job.optString("owner", "");
            project.jobStyle = TextStyleSettings.fromJson(job.optJSONObject("style"));
        }
        project.createdAt = root.optLong("createdAt", System.currentTimeMillis());
        project.updatedAt = root.optLong("updatedAt", project.createdAt);
        project.projectDir = projectDir;
        JSONArray pageArray = root.optJSONArray("pages");
        if (pageArray != null) {
            for (int i = 0; i < pageArray.length(); i++) {
                project.pages.add(pageFromJson(pageArray.getJSONObject(i)));
            }
        }
        if (project.version < 2 && project.completed) for (Page page : project.pages) { page.layoutPrepared=true; page.ocrComplete=true; page.translationComplete=true; }
        return project;
    }

    public static TranslationProject load(File projectDir) throws IOException {
        File file = new File(projectDir, PROJECT_FILE);
        android.util.AtomicFile atomic = new android.util.AtomicFile(file);
        if (!file.exists() && !new File(file.getPath() + ".bak").exists()) return null;
        String text;
        try (java.io.FileInputStream in = atomic.openRead();
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int n;
            while ((n = in.read(buffer)) != -1) {
                if (out.size() + n > 32 * 1024 * 1024) throw new IOException("Saved project is too large");
                out.write(buffer, 0, n);
            }
            text = new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
        try {
            return fromJson(new JSONObject(text), projectDir);
        } catch (JSONException e) {
            throw new IOException("Saved translation project is corrupted", e);
        }
    }

    public String snapshotJson() {
        try {
            return toJson().toString();
        } catch (JSONException ignored) {
            return null;
        }
    }

    public void restoreSnapshot(String snapshot) throws IOException {
        if (snapshot == null) {
            throw new IOException("Empty project snapshot");
        }
        try {
            TranslationProject restored = fromJson(new JSONObject(snapshot), projectDir);
            version = restored.version;
            cbz = restored.cbz;
            completed = restored.completed;
            displayName = restored.displayName;
            baseName = restored.baseName;
            sourceLanguage = restored.sourceLanguage;
            targetLanguage = restored.targetLanguage;
            comicConfig = restored.comicConfig;
            offlineMode = restored.offlineMode; accurateOcr = restored.accurateOcr;
            createPdf = restored.createPdf; imageBatch = restored.imageBatch; privateResults = restored.privateResults;
            resumable = restored.resumable; autoResume = restored.autoResume;
            quotaJobId = restored.quotaJobId; jobOwner = restored.jobOwner; quotaPages = restored.quotaPages; exportsComplete = restored.exportsComplete; outputBinding = restored.outputBinding;
            jobStyle = restored.jobStyle;
            createdAt = restored.createdAt;
            updatedAt = restored.updatedAt;
            pages.clear();
            pages.addAll(restored.pages);
        } catch (JSONException e) {
            throw new IOException("Could not restore project snapshot", e);
        }
    }

    private static JSONObject pageToJson(Page page) throws JSONException {
        JSONObject json = new JSONObject();
        json.put("sourcePath", page.sourcePath == null ? "" : page.sourcePath);
        json.put("originalExtension", page.originalExtension);
        json.put("width", page.width);
        json.put("height", page.height);
        json.put("unreadable", page.unreadable);
        json.put("ocrComplete", page.ocrComplete);
        json.put("translationComplete", page.translationComplete);
        json.put("renderComplete", page.renderComplete); json.put("layoutPrepared", page.layoutPrepared); json.put("webPriority", page.webPriority);
        JSONArray segments = new JSONArray();
        for (Segment segment : page.segments) {
            segments.put(segmentToJson(segment));
        }
        json.put("segments", segments);
        return json;
    }

    private static Page pageFromJson(JSONObject json) throws JSONException {
        Page page = new Page();
        page.sourcePath = json.optString("sourcePath", "");
        page.originalExtension = json.optString("originalExtension", ".jpg");
        page.width = json.optInt("width", 0);
        page.height = json.optInt("height", 0);
        page.unreadable = json.optBoolean("unreadable", false);
        page.ocrComplete = json.optBoolean("ocrComplete", false);
        page.translationComplete = json.optBoolean("translationComplete", false);
        page.renderComplete = json.optBoolean("renderComplete", false); page.layoutPrepared = json.optBoolean("layoutPrepared", false); page.webPriority = json.optBoolean("webPriority", false);
        JSONArray segments = json.optJSONArray("segments");
        if (segments != null) {
            for (int i = 0; i < segments.length(); i++) {
                page.segments.add(segmentFromJson(segments.getJSONObject(i)));
            }
        }
        return page;
    }

    private static JSONObject segmentToJson(Segment segment) throws JSONException {
        JSONObject json = new JSONObject();
        json.put("text", segment.text == null ? "" : segment.text);
        json.put("ckb", segment.ckb == null ? "" : segment.ckb);
        json.put("originalCkb", segment.originalCkb == null ? "" : segment.originalCkb);
        json.put("translationMethod", segment.translationMethod);
        json.put("translationWarnings", segment.translationWarnings);
        json.put("readerReview", segment.readerReview);
        json.put("readerReviewBinding", segment.readerReviewBinding);
        json.put("lines", segment.lines);
        json.put("sourceLineHeight", segment.sourceLineHeight);
        json.put("ocrConfidence", segment.ocrConfidence);
        json.put("rendered", segment.rendered);
        json.put("box", rectToJson(segment.box));
        json.put("renderBox", rectToJson(segment.getRenderBox()));
        json.put("eraseBox", rectToJson(segment.getEraseBox()));
        json.put("detectedBackgroundColor", segment.detectedBackgroundColor);
        json.put("detectedInkColor", segment.detectedInkColor);
        json.put("autoBackgroundReliable", segment.autoBackgroundReliable);
        json.put("bubbleOval", segment.bubbleOval);
        if (segment.bubbleBoundary != null) json.put("bubbleBoundary", segment.bubbleBoundary.toJson());
        json.put("outsideText", segment.outsideText);
        json.put("outsideBrightInk", segment.outsideBrightInk);
        if (segment.interiorLeft != null && segment.interiorRight != null
                && segment.interiorLeft.length == segment.interiorRight.length) {
            json.put("interiorTop", segment.interiorTop);
            json.put("interiorLeft", intsToJson(segment.interiorLeft));
            json.put("interiorRight", intsToJson(segment.interiorRight));
        }
        json.put("style", segment.ensureStyle().toJson());
        return json;
    }

    private static Segment segmentFromJson(JSONObject json) {
        Rect box = rectFromJson(json.optJSONObject("box"));
        Segment segment = new Segment(
                json.optString("text", ""), box, json.optInt("lines", 1));
        segment.ckb = json.optString("ckb", "");
        segment.sourceLineHeight = (float) json.optDouble("sourceLineHeight", 0.0);
        segment.ocrConfidence = (float) json.optDouble("ocrConfidence", -1.0);
        segment.originalCkb = json.optString("originalCkb", segment.ckb);
        segment.translationMethod = json.optString("translationMethod", "standard");
        segment.translationWarnings = json.optString("translationWarnings", "");
        segment.readerReview = json.optString("readerReview", "unreviewed");
        segment.readerReviewBinding = json.optString("readerReviewBinding", "");
        segment.rendered = json.optBoolean("rendered", false);
        segment.renderBox = rectFromJson(json.optJSONObject("renderBox"));
        segment.eraseBox = rectFromJson(json.optJSONObject("eraseBox"));
        if (segment.renderBox.width() <= 0 || segment.renderBox.height() <= 0) {
            segment.renderBox = new Rect(segment.box);
        }
        if (segment.eraseBox.width() <= 0 || segment.eraseBox.height() <= 0) {
            segment.eraseBox = new Rect(segment.box);
        }
        segment.detectedBackgroundColor = json.optInt(
                "detectedBackgroundColor", segment.detectedBackgroundColor);
        segment.detectedInkColor = json.optInt(
                "detectedInkColor", segment.detectedInkColor);
        segment.autoBackgroundReliable = json.optBoolean(
                "autoBackgroundReliable", false);
        segment.bubbleOval = json.optBoolean("bubbleOval", false);
        segment.outsideText = json.optBoolean("outsideText", false);
        segment.outsideBrightInk = json.optBoolean("outsideBrightInk", true);
        if (json.has("bubbleBoundary")) segment.bubbleBoundary =
                BubbleBoundary.fromJson(json.optJSONObject("bubbleBoundary"));
        segment.interiorTop = json.optInt("interiorTop", -1);
        segment.interiorLeft = intsFromJson(json.optJSONArray("interiorLeft"));
        segment.interiorRight = intsFromJson(json.optJSONArray("interiorRight"));
        if (segment.interiorLeft == null || segment.interiorRight == null
                || segment.interiorLeft.length != segment.interiorRight.length
                || segment.interiorTop < 0) {
            segment.interiorLeft = null;
            segment.interiorRight = null;
            segment.interiorTop = -1;
        }
        segment.style = TextStyleSettings.fromJson(json.optJSONObject("style"));
        return segment;
    }

    private static JSONArray intsToJson(int[] values) {
        JSONArray array = new JSONArray();
        for (int value : values) {
            array.put(value);
        }
        return array;
    }

    private static int[] intsFromJson(JSONArray array) {
        if (array == null || array.length() == 0) {
            return null;
        }
        int[] values = new int[array.length()];
        for (int i = 0; i < array.length(); i++) {
            values[i] = array.optInt(i, -1);
        }
        return values;
    }

    private static JSONObject rectToJson(Rect rect) throws JSONException {
        JSONObject json = new JSONObject();
        Rect safe = rect == null ? new Rect() : rect;
        json.put("left", safe.left);
        json.put("top", safe.top);
        json.put("right", safe.right);
        json.put("bottom", safe.bottom);
        return json;
    }

    private static Rect rectFromJson(JSONObject json) {
        if (json == null) {
            return new Rect();
        }
        return new Rect(
                json.optInt("left", 0),
                json.optInt("top", 0),
                json.optInt("right", 0),
                json.optInt("bottom", 0));
    }
}
