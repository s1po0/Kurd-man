package com.kurdman.app;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.util.Locale;

/** Owns the single "latest translation" project kept for the text studio. */
final class TranslationProjectStore {
    private static TranslationProject current;

    private TranslationProjectStore() {
    }

    static synchronized File projectDir(Context context) {
        return new File(context.getApplicationContext().getFilesDir(),
                "projects/current");
    }

    static synchronized TranslationProject createNew(Context context, boolean cbz,
                                                     String displayName,
                                                     String baseName,
                                                     String sourceLanguage)
            throws IOException {
        File dir = projectDir(context);
        deleteRecursive(dir);
        File pages = new File(dir, "pages");
        if (!pages.mkdirs() && !pages.exists()) {
            throw new IOException("Could not create editable project directory");
        }
        TranslationProject project = new TranslationProject();
        project.projectDir = dir;
        project.cbz = cbz;
        project.displayName = displayName == null ? "" : displayName;
        project.baseName = baseName == null || baseName.trim().isEmpty()
                ? "kurdman" : baseName;
        project.sourceLanguage = sourceLanguage == null ? "en" : sourceLanguage;
        current = project;
        return project;
    }

    static synchronized TranslationProject load(Context context) {
        File dir = projectDir(context);
        if (current != null && dir.equals(current.projectDir)) {
            return current;
        }
        try {
            current = TranslationProject.load(dir);
            return current;
        } catch (IOException ignored) {
            current = null;
            return null;
        }
    }

    static synchronized boolean hasCompletedProject(Context context) {
        TranslationProject project = load(context);
        if (project == null || !project.completed || project.pages.isEmpty()) {
            return false;
        }
        for (TranslationProject.Page page : project.pages) {
            File source = page.sourceFile(project.projectDir);
            if (source == null || !source.exists()) {
                return false;
            }
        }
        return true;
    }

    static synchronized void setCurrent(TranslationProject project) {
        current = project;
    }

    static synchronized void deleteCurrent(Context context) {
        current = null;
        deleteRecursive(projectDir(context));
    }

    static String pageRelativePath(int pageNumber, String extensionWithDot) {
        String ext = extensionWithDot == null || extensionWithDot.trim().isEmpty()
                ? ".jpg" : extensionWithDot;
        if (!ext.startsWith(".")) {
            ext = "." + ext;
        }
        return String.format(Locale.ROOT, "pages/%04d%s", pageNumber, ext);
    }

    static void deleteRecursive(File file) {
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
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
