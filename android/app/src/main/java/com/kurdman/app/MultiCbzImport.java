package com.kurdman.app;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Merges explicitly selected archives into a single bounded, ordered private input.
 * Entry names never become filesystem paths; no reader originals are modified. */
final class MultiCbzImport {
    static final long IMPORT_LIMIT = 300L * 1024 * 1024;
    static final long EXPANDED_LIMIT = 1024L * 1024 * 1024;
    static final long PAGE_LIMIT = 150L * 1024 * 1024;
    interface Cancellation { void check() throws IOException; }
    private MultiCbzImport() {}

    static int merge(List<File> archives, File destination, Cancellation cancel) throws IOException {
        if (archives == null || archives.size() < 2 || archives.size() > 300)
            throw new IOException("Select between 2 and 300 CBZ files");
        long compressed = 0;
        for (File file : archives) {
            if (file == null || !file.isFile() || file.getCanonicalFile().equals(destination.getCanonicalFile()))
                throw new IOException("CBZ input unavailable");
            compressed += file.length();
            if (compressed > IMPORT_LIMIT) throw new IOException("CBZ import limit exceeded (300 MB)");
        }
        boolean complete = false;
        boolean created = false;
        int page = 0;
        long expanded = 0;
        try {
            // Never overwrite an existing selection or any reader-owned file.
            if (!destination.createNewFile()) throw new IOException("Temporary destination already exists");
            created = true;
            try (OutputStream file = new BufferedOutputStream(new FileOutputStream(destination));
                 ZipOutputStream output = new ZipOutputStream(new LimitedOutput(file, IMPORT_LIMIT))) {
                for (File archive : archives) {
                    cancel.check();
                    try (ZipFile zip = new ZipFile(archive)) {
                        List<ZipEntry> entries = new ArrayList<>();
                        Enumeration<? extends ZipEntry> all = zip.entries();
                        while (all.hasMoreElements()) {
                            cancel.check();
                            ZipEntry e = all.nextElement();
                            String lower = e.getName().toLowerCase(Locale.ROOT);
                            if (!e.isDirectory() && !lower.contains("__macosx")
                                    && !lower.endsWith(".ds_store") && SavedJobInput.imageName(lower)) {
                                entries.add(e);
                                if (entries.size() + page > 300) throw new IOException("CBZ page limit exceeded (300)");
                            }
                        }
                        if (entries.isEmpty()) throw new IOException("A CBZ contains no supported image pages");
                        entries.sort((a,b) -> SavedJobInput.natural(a.getName(), b.getName()));
                        for (ZipEntry e : entries) {
                            cancel.check();
                            String name = String.format(Locale.ROOT, "%04d%s", ++page, SavedJobInput.extension(e.getName()));
                            output.putNextEntry(new ZipEntry(name));
                            long pageBytes = 0;
                            try (InputStream in = new BufferedInputStream(zip.getInputStream(e))) {
                                byte[] buf = new byte[64 * 1024];
                                int n;
                                while ((n = in.read(buf)) != -1) {
                                    cancel.check();
                                    pageBytes += n;
                                    expanded += n;
                                    if (pageBytes > PAGE_LIMIT || expanded > EXPANDED_LIMIT)
                                        throw new IOException("CBZ extraction limit exceeded");
                                    output.write(buf, 0, n);
                                }
                            }
                            output.closeEntry();
                        }
                    }
                }
            }
            complete = true;
            return page;
        } finally {
            if (created && !complete) destination.delete();
        }
    }

    private static final class LimitedOutput extends FilterOutputStream {
        private final long limit;
        private long count;
        LimitedOutput(OutputStream out, long limit) { super(out); this.limit = limit; }
        @Override public void write(int value) throws IOException {
            if (++count > limit) throw new IOException("Combined CBZ exceeds 300 MB");
            out.write(value);
        }
        @Override public void write(byte[] data, int offset, int length) throws IOException {
            if (length > limit - count) throw new IOException("Combined CBZ exceeds 300 MB");
            out.write(data, offset, length);
            count += length;
        }
    }
}
