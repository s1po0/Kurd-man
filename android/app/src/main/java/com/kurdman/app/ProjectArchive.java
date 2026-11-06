package com.kurdman.app;

import android.content.Context;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

/** Explicit user-controlled backup, with bounded, transactional untrusted imports. */
final class ProjectArchive {
    static final long MAX_BYTES = 300L * 1024 * 1024;
    static final int MAX_PAGES = 300;
    private static final int MAX_JSON = 16 * 1024 * 1024;
    private ProjectArchive() { }

    static void exportProject(TranslationProject project, OutputStream output) throws Exception {
        if (!TranslationProjectStore.hasSources(project)) throw new IOException("Project sources are missing");
        if (project.pages.size() > MAX_PAGES) throw new IOException("Too many pages");
        JSONObject json = project.toJson();
        // A backup contains reader material, NOT an account/session or reusable job permission.
        JSONObject job = json.optJSONObject("job");
        if (job != null) { job.put("quotaJobId", ""); job.put("owner", ""); job.put("autoResume", false); job.put("exportsComplete", false); job.put("outputBinding", ""); job.put("quotaPages", 0); }
        byte[] metadata = json.toString().getBytes(StandardCharsets.UTF_8);
        if (metadata.length > MAX_JSON) throw new IOException("Project metadata is too large");
        long total = metadata.length;
        Set<String> names = new HashSet<>();
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(output))) {
            zip.putNextEntry(new ZipEntry(TranslationProject.PROJECT_FILE)); zip.write(metadata); zip.closeEntry();
            for (TranslationProject.Page page : project.pages) {
                JobRuntime.throwIfStopped();
                String name = safePagePath(page.sourcePath);
                if (!names.add(name)) throw new IOException("Duplicate project source");
                File source = page.sourceFile(project.projectDir);
                total += source.length();
                if (total > MAX_BYTES) throw new IOException("Backup exceeds 300 MB");
                zip.putNextEntry(new ZipEntry(name));
                try (InputStream in = new FileInputStream(source)) { copy(in, zip, MAX_BYTES); }
                zip.closeEntry();
            }
        }
    }
    static TranslationProject importProject(Context context, File archive) throws Exception {
        if (!archive.isFile() || archive.length() > MAX_BYTES) throw new IOException("Backup exceeds 300 MB");
        File root = TranslationProjectStore.root(context);
        if (!root.exists() && !root.mkdirs()) throw new IOException("Could not create project library");
        String id = UUID.randomUUID().toString();
        File staging = new File(root, ".import-" + id);
        File destination = new File(root, "p-" + id);
        if (!staging.mkdirs()) throw new IOException("Could not create import directory");
        boolean moved = false;
        try (ZipFile zip = new ZipFile(archive)) {
            Set<String> entries = new HashSet<>();
            Enumeration<? extends ZipEntry> enumeration = zip.entries();
            int count = 0;
            while (enumeration.hasMoreElements()) {
                ZipEntry entry = enumeration.nextElement();
                String name = entry.getName();
                if (++count > MAX_PAGES + 2 || entry.isDirectory() && !"pages/".equals(name)
                        || !entry.isDirectory() && !TranslationProject.PROJECT_FILE.equals(name)
                        && !name.equals(safePagePath(name)) || !entries.add(name))
                    throw new IOException("Unsupported backup structure");
            }
            ZipEntry header = zip.getEntry(TranslationProject.PROJECT_FILE);
            if (header == null) throw new IOException("Missing project metadata");
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (InputStream in = zip.getInputStream(header)) { copy(in, buffer, MAX_JSON); }
            byte[] data = buffer.toByteArray();
            TranslationProject project = TranslationProject.fromJson(
                    new JSONObject(new String(data, StandardCharsets.UTF_8)), staging);
            if (project.pages.isEmpty() || project.pages.size() > MAX_PAGES) throw new IOException("Invalid page count");
            Set<String> expected = new HashSet<>(); expected.add(TranslationProject.PROJECT_FILE);
            long total = data.length;
            for (TranslationProject.Page page : project.pages) {
                JobRuntime.throwIfStopped();
                String path = safePagePath(page.sourcePath);
                if (!expected.add(path)) throw new IOException("Duplicate page path");
                ZipEntry entry = zip.getEntry(path);
                if (entry == null || entry.isDirectory()) throw new IOException("Missing page source");
                File target = page.sourceFile(staging);
                if (target == null || !target.getParentFile().isDirectory() && !target.getParentFile().mkdirs())
                    throw new IOException("Invalid page source");
                try (InputStream in = zip.getInputStream(entry); OutputStream out = new FileOutputStream(target)) {
                    total += copy(in, out, MAX_BYTES - total);
                }
                page.renderComplete = false; // Render cache is deliberately not included in backups.
            }
            entries.remove("pages/");
            if (!expected.equals(entries)) throw new IOException("Unexpected files in backup");
            project.quotaJobId = ""; project.jobOwner = ""; project.autoResume = false; project.quotaPages = 0; project.exportsComplete = false; project.outputBinding = "";
            project.save();
            if (!staging.renameTo(destination)) throw new IOException("Could not finish project import");
            moved = true; project.projectDir = destination;
            TranslationProjectStore.activate(context, project);
            return project;
        } finally {
            if (!moved) TranslationProjectStore.deleteRecursive(staging);
        }
    }
    static String safePagePath(String name) throws IOException {
        if (name == null || !name.matches("pages/[A-Za-z0-9_.-]+\\.(?i:jpg|jpeg|png|webp|bmp|gif|heic|heif|avif)")
                || name.contains("..") || name.length() > 180) throw new IOException("Unsafe page path");
        return name;
    }
    static long copy(InputStream in, OutputStream out, long limit) throws IOException {
        byte[] buffer = new byte[64 * 1024]; long total = 0; int n;
        while ((n = in.read(buffer)) != -1) {
            if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("Backup stopped");
            total += n; if (total > limit) throw new IOException("Backup size limit exceeded");
            out.write(buffer, 0, n);
        }
        return total;
    }
}
