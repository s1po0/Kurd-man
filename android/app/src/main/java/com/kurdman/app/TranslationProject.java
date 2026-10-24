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

    public int version = 1;
    public boolean cbz;
    public boolean completed;
    public String displayName = "";
    public String baseName = "kurdman";
    public String sourceLanguage = "en";
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
        public final List<Segment> segments = new ArrayList<>();

        public Page() {
        }

        public Page(String sourcePath, String originalExtension) {
            this.sourcePath = sourcePath;
            this.originalExtension = originalExtension == null
                    ? ".jpg" : originalExtension;
        }

        public File sourceFile(File projectDir) {
            return sourcePath == null ? null : new File(projectDir, sourcePath);
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
        File temporary = new File(projectDir, PROJECT_FILE + ".tmp");
        try (OutputStreamWriter writer = new OutputStreamWriter(
                new FileOutputStream(temporary), StandardCharsets.UTF_8)) {
            writer.write(json);
        }
        File target = new File(projectDir, PROJECT_FILE);
        try {
            Files.move(temporary.toPath(), target.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException moveFailure) {
            // Some Android filesystems dislike atomic replacement across a
            // directory boundary; both files are in the same directory, but
            // keep a plain rename as a conservative fallback.
            if (target.exists() && !target.delete()) {
                throw moveFailure;
            }
            if (!temporary.renameTo(target)) {
                throw moveFailure;
            }
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
        project.createdAt = root.optLong("createdAt", System.currentTimeMillis());
        project.updatedAt = root.optLong("updatedAt", project.createdAt);
        project.projectDir = projectDir;
        JSONArray pageArray = root.optJSONArray("pages");
        if (pageArray != null) {
            for (int i = 0; i < pageArray.length(); i++) {
                project.pages.add(pageFromJson(pageArray.getJSONObject(i)));
            }
        }
        return project;
    }

    public static TranslationProject load(File projectDir) throws IOException {
        File file = new File(projectDir, PROJECT_FILE);
        if (!file.exists()) {
            return null;
        }
        String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
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
        json.put("lines", segment.lines);
        json.put("ocrConfidence", segment.ocrConfidence);
        json.put("rendered", segment.rendered);
        json.put("box", rectToJson(segment.box));
        json.put("renderBox", rectToJson(segment.getRenderBox()));
        json.put("eraseBox", rectToJson(segment.getEraseBox()));
        json.put("detectedBackgroundColor", segment.detectedBackgroundColor);
        json.put("detectedInkColor", segment.detectedInkColor);
        json.put("autoBackgroundReliable", segment.autoBackgroundReliable);
        json.put("bubbleOval", segment.bubbleOval);
        json.put("style", segment.ensureStyle().toJson());
        return json;
    }

    private static Segment segmentFromJson(JSONObject json) {
        Rect box = rectFromJson(json.optJSONObject("box"));
        Segment segment = new Segment(
                json.optString("text", ""), box, json.optInt("lines", 1));
        segment.ckb = json.optString("ckb", "");
        segment.ocrConfidence = (float) json.optDouble("ocrConfidence", -1.0);
        segment.originalCkb = json.optString("originalCkb", segment.ckb);
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
        segment.style = TextStyleSettings.fromJson(json.optJSONObject("style"));
        return segment;
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
