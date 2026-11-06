package com.kurdman.app;

import android.content.Context;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Durable local library. Starting a job NEVER deletes an older project. */
final class TranslationProjectStore {
    private static final String PREFS = "koorix_projects";
    private static final String CURRENT = "current_id";
    private static TranslationProject current;
    private TranslationProjectStore() { }

    static File root(Context context) {
        return new File(context.getApplicationContext().getFilesDir(), "projects");
    }
    static boolean validId(String id) {
        return "current".equals(id) || id != null && id.matches("p-[0-9a-f-]{36}");
    }
    static synchronized File projectDir(Context context) {
        String id = context.getApplicationContext().getSharedPreferences(PREFS, 0)
                .getString(CURRENT, "current");
        return new File(root(context), validId(id) ? id : "current");
    }
    static synchronized TranslationProject createNew(Context context, boolean cbz,
            String displayName, String baseName, String sourceLanguage) throws IOException {
        File dir = new File(root(context), "p-" + UUID.randomUUID());
        if (!new File(dir, "pages").mkdirs()) throw new IOException("Could not create project");
        TranslationProject project = new TranslationProject();
        project.projectDir = dir; project.cbz = cbz;
        project.displayName = displayName == null ? "" : displayName;
        project.baseName = baseName == null || baseName.trim().isEmpty() ? "koorix" : baseName;
        project.sourceLanguage = sourceLanguage == null ? "en" : sourceLanguage;
        try {
            project.save();
            activate(context, project);
            return project;
        } catch (IOException failure) {
            deleteRecursive(dir); // Only the new, incomplete allocation, never an older project.
            throw failure;
        }
    }
    static synchronized TranslationProject load(Context context) {
        File dir = projectDir(context);
        if (current != null && dir.equals(current.projectDir)
                && new File(dir, TranslationProject.PROJECT_FILE).isFile()) return current;
        try { current = TranslationProject.load(dir); }
        catch (IOException ignored) { current = null; }
        return current;
    }
    static synchronized TranslationProject load(Context context, String id) throws IOException {
        if (!validId(id)) throw new IOException("Invalid project identity");
        File dir = new File(root(context), id);
        if (current != null && dir.equals(current.projectDir)) return current;
        return TranslationProject.load(dir);
    }
    static synchronized void activate(Context context, TranslationProject project) throws IOException {
        if (project == null || project.projectDir == null || !validId(project.projectDir.getName())
                || !root(context).getCanonicalFile().equals(project.projectDir.getCanonicalFile().getParentFile())
                || !new File(project.projectDir, TranslationProject.PROJECT_FILE).isFile())
            throw new IOException("Invalid saved project");
        if (!context.getApplicationContext().getSharedPreferences(PREFS, 0).edit()
                .putString(CURRENT, project.projectDir.getName()).commit())
            throw new IOException("Could not select saved project");
        current = project;
    }
    static synchronized List<TranslationProject> list(Context context) {
        List<TranslationProject> projects = new ArrayList<>();
        File[] dirs = root(context).listFiles();
        if (dirs != null) for (File dir : dirs) {
            if (!dir.isDirectory() || !validId(dir.getName())) continue;
            try {
                TranslationProject p = current != null && dir.equals(current.projectDir)
                        ? current : TranslationProject.load(dir);
                if (p != null && !p.pages.isEmpty()) projects.add(p);
            } catch (IOException ignored) { /* Keep damaged files for owner recovery; don't delete. */ }
        }
        Collections.sort(projects, (a,b) -> Long.compare(b.updatedAt, a.updatedAt));
        return projects;
    }
    static synchronized boolean hasCompletedProject(Context context) {
        TranslationProject project = load(context);
        return project != null && project.completed && hasSources(project);
    }
    static boolean hasSources(TranslationProject project) {
        if (project == null || project.pages.isEmpty()) return false;
        for (TranslationProject.Page page : project.pages) {
            File source = page.sourceFile(project.projectDir);
            if (source == null || !source.isFile()) return false;
        }
        return true;
    }
    /** Serialize writes with library selection: a stale screen cannot overwrite another job. */
    static synchronized void saveSnapshot(TranslationProject expected, String snapshot) throws IOException {
        if (expected == null || current != expected || snapshot == null)
            throw new IOException("Project changed before save");
        try {
            TranslationProject.fromJson(new org.json.JSONObject(snapshot), expected.projectDir).save();
        } catch (org.json.JSONException invalid) { throw new IOException("Invalid project snapshot", invalid); }
    }
    static synchronized void setCurrent(TranslationProject project) { current = project; }
    static synchronized void deleteCurrent(Context context) {
        File dir = projectDir(context);
        current = null;
        deleteRecursive(dir);
        context.getApplicationContext().getSharedPreferences(PREFS, 0).edit().remove(CURRENT).commit();
    }
    static synchronized void delete(Context context, TranslationProject project) throws IOException {
        if (JobRuntime.isRunning() && !JobRuntime.isJobThread()) throw new IOException("A job is running");
        if (project == null || project.projectDir == null || !validId(project.projectDir.getName())
                || !root(context).getCanonicalFile().equals(project.projectDir.getCanonicalFile().getParentFile()))
            throw new IOException("Invalid project identity");
        if (project.projectDir.equals(projectDir(context))) deleteCurrent(context);
        else deleteRecursive(project.projectDir);
    }
    static void allowRecovery(Context context, TranslationProject p, boolean allowed) {
        synchronized(JobRuntime.class) {
            if (allowed && JobRuntime.isStopRequested()) return;
            context.getApplicationContext().getSharedPreferences(PREFS,0).edit()
                    .putBoolean("resume_" + p.projectDir.getName(), allowed).commit();
        }
    }
    static boolean canRecover(Context context, TranslationProject p) {
        return p != null && p.resumable && p.autoResume && !p.completed && hasSources(p)
                && context.getApplicationContext().getSharedPreferences(PREFS,0)
                .getBoolean("resume_" + p.projectDir.getName(), true);
    }
    static String pageRelativePath(int pageNumber, String extensionWithDot) {
        String ext = extensionWithDot == null || extensionWithDot.trim().isEmpty() ? ".jpg" : extensionWithDot;
        if (!ext.startsWith(".")) ext = "." + ext;
        return String.format(Locale.ROOT, "pages/%04d%s", pageNumber, ext);
    }
    static void deleteRecursive(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteRecursive(child);
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
