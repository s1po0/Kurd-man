package com.kurdman.app;

import android.app.ActivityManager;
import android.content.Context;
import android.os.StatFs;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Downloads and verifies the optional, app-private NLLB-200 offline pack.
 *
 * <p>The 1.34 GB model is deliberately not bundled in the APK and is never
 * written to the Git repository. Files are fetched from a pinned Hugging
 * Face revision, downloaded to resumable {@code .part} files, checked by
 * exact size and SHA-256, and only then renamed into place.</p>
 */
final class OfflineModelManager {
    private static final String TAG = "OfflineModelManager";

    static final String ENCODER_FILE = "encoder_model_quantized.onnx";
    static final String DECODER_FILE = "decoder_model_quantized.onnx";
    static final String DECODER_WITH_PAST_FILE =
            "decoder_with_past_model_quantized.onnx";
    static final String TOKENIZER_FILE = "sentencepiece.bpe.model";

    private static final String MODEL_REVISION =
            "261c31d1a5732c67cdd16d80e8d6088507c7ccea";
    private static final String BASE_URL =
            "https://huggingface.co/Xenova/nllb-200-distilled-600M/resolve/"
                    + MODEL_REVISION;
    private static final String VERIFIED_MARKER = "kurdman-verified-v1.txt";
    private static final long EXTRA_FREE_SPACE_BYTES = 256L * 1024L * 1024L;
    private static final int BUFFER_SIZE = 256 * 1024;

    interface Progress {
        void onProgress(long downloadedBytes, long totalBytes,
                        String currentFile, boolean verifying);
    }

    private static final class ModelFileSpec {
        final String remotePath;
        final String fileName;
        final long size;
        final String sha256;

        ModelFileSpec(String remotePath, String fileName, long size, String sha256) {
            this.remotePath = remotePath;
            this.fileName = fileName;
            this.size = size;
            this.sha256 = sha256;
        }
    }

    private static final List<ModelFileSpec> FILES = new ArrayList<>();

    static {
        FILES.add(new ModelFileSpec(
                TOKENIZER_FILE,
                TOKENIZER_FILE,
                4852054L,
                "14bb8dfb35c0ffdea7bc01e56cea38b9e3d5efcdcb9c251d6b40538e1aab555a"));
        FILES.add(new ModelFileSpec(
                "onnx/" + ENCODER_FILE,
                ENCODER_FILE,
                419120483L,
                "5cde664eacba07a62f198857ec6c06e09572b1ebb77c8137f1fa99ac604a3a28"));
        FILES.add(new ModelFileSpec(
                "onnx/" + DECODER_FILE,
                DECODER_FILE,
                470533055L,
                "ddea619b640379609719becf91a488c5e6ce4c4b2052efbb5388edaed465a552"));
        FILES.add(new ModelFileSpec(
                "onnx/" + DECODER_WITH_PAST_FILE,
                DECODER_WITH_PAST_FILE,
                445006987L,
                "374293cb4bb59cd29810851a7cfbc568accb8efdc1a953ac04722e30adaf2d0b"));
    }

    private final Context context;

    OfflineModelManager(Context context) {
        this.context = context.getApplicationContext();
    }

    File modelDir() {
        return new File(new File(context.getFilesDir(), "offline-ai"),
                "nllb-200-distilled-600m-int8");
    }

    File modelFile(String fileName) {
        return new File(modelDir(), fileName);
    }

    static long totalBytes() {
        long total = 0;
        for (ModelFileSpec spec : FILES) {
            total += spec.size;
        }
        return total;
    }

    /** Total device RAM in bytes, or -1 when it cannot be determined. */
    long totalRamBytes() {
        try {
            ActivityManager manager = (ActivityManager)
                    context.getSystemService(Context.ACTIVITY_SERVICE);
            if (manager == null) {
                return -1;
            }
            ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
            manager.getMemoryInfo(info);
            return info.totalMem;
        } catch (Exception ignored) {
            return -1;
        }
    }

    boolean isModelReady() {
        File dir = modelDir();
        if (!new File(dir, VERIFIED_MARKER).isFile()) {
            return false;
        }
        for (ModelFileSpec spec : FILES) {
            File file = new File(dir, spec.fileName);
            if (!file.isFile() || file.length() != spec.size) {
                return false;
            }
        }
        return true;
    }

    long downloadedBytes() {
        File dir = modelDir();
        long total = 0;
        for (ModelFileSpec spec : FILES) {
            File finalFile = new File(dir, spec.fileName);
            if (finalFile.isFile()) {
                total += Math.min(spec.size, finalFile.length());
            } else {
                File part = partFile(dir, spec);
                if (part.isFile()) {
                    total += Math.min(spec.size, part.length());
                }
            }
        }
        return total;
    }

    void downloadModel(Progress progress) throws IOException {
        File dir = modelDir();
        ensureDirectory(dir);
        if (isModelReady()) {
            notifyProgress(progress, totalBytes(), "", false);
            return;
        }

        // If a verified marker vanished but exact-size finals are present,
        // verify them below instead of downloading gigabytes again.
        ensureFreeSpace(dir);
        long completedBytes = 0;
        for (ModelFileSpec spec : FILES) {
            checkInterrupted();
            File finalFile = new File(dir, spec.fileName);
            if (finalFile.isFile() && finalFile.length() == spec.size) {
                notifyProgress(progress, completedBytes + spec.size,
                        spec.fileName, true);
                if (verifyFile(finalFile, spec, progress,
                        completedBytes + spec.size)) {
                    completedBytes += spec.size;
                    continue;
                }
                Log.w(TAG, "Deleting corrupt final model file: " + spec.fileName);
                deleteFile(finalFile);
            } else if (finalFile.exists()) {
                deleteFile(finalFile);
            }

            downloadAndVerify(spec, dir, completedBytes, progress);
            completedBytes += spec.size;
            notifyProgress(progress, completedBytes, spec.fileName, false);
        }

        writeVerifiedMarker(dir);
        notifyProgress(progress, totalBytes(), "", false);
        Log.i(TAG, "Offline NLLB model ready in " + dir.getAbsolutePath());
    }

    boolean deleteModel() {
        File dir = modelDir();
        deleteRecursive(dir);
        return !dir.exists();
    }

    private void downloadAndVerify(ModelFileSpec spec, File dir,
                                   long completedBefore,
                                   Progress progress) throws IOException {
        File finalFile = new File(dir, spec.fileName);
        File partFile = partFile(dir, spec);
        IOException lastChecksumError = null;

        // A resumed transfer can theoretically produce a bad hash if the
        // stored .part file was damaged. Retry that one file once from zero.
        for (int attempt = 0; attempt < 2; attempt++) {
            downloadFile(spec, partFile, completedBefore, progress);
            if (!partFile.isFile() || partFile.length() != spec.size) {
                throw new IOException("Downloaded model file has the wrong size: "
                        + spec.fileName);
            }
            notifyProgress(progress, completedBefore + spec.size,
                    spec.fileName, true);
            if (verifyFile(partFile, spec, progress,
                    completedBefore + spec.size)) {
                if (finalFile.exists() && !finalFile.delete()) {
                    throw new IOException("Could not replace model file: "
                            + spec.fileName);
                }
                if (!partFile.renameTo(finalFile)) {
                    throw new IOException("Could not finalize model file: "
                            + spec.fileName);
                }
                return;
            }
            lastChecksumError = new IOException(
                    "Checksum mismatch for offline model file: " + spec.fileName);
            deleteFile(partFile);
        }
        throw lastChecksumError == null
                ? new IOException("Offline model verification failed")
                : lastChecksumError;
    }

    private void downloadFile(ModelFileSpec spec, File partFile,
                              long completedBefore,
                              Progress progress) throws IOException {
        downloadFileAttempt(spec, partFile, completedBefore, progress, true);
    }

    private void downloadFileAttempt(ModelFileSpec spec, File partFile,
                                     long completedBefore,
                                     Progress progress,
                                     boolean canRestartAfter416) throws IOException {
        long existing = partFile.isFile() ? partFile.length() : 0;
        if (existing > spec.size) {
            deleteFile(partFile);
            existing = 0;
        }
        if (existing == spec.size) {
            notifyProgress(progress, completedBefore + existing,
                    spec.fileName, false);
            return;
        }

        HttpURLConnection connection = null;
        InputStream in = null;
        OutputStream out = null;
        try {
            URL url = new URL(BASE_URL + "/" + spec.remotePath);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(30000);
            connection.setReadTimeout(120000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent",
                    "KurdMan/1.5 (Android; NLLB-200 offline model)");
            connection.setRequestProperty("Accept", "application/octet-stream");
            connection.setRequestProperty("Accept-Encoding", "identity");
            if (existing > 0) {
                connection.setRequestProperty("Range", "bytes=" + existing + "-");
            }

            int code = connection.getResponseCode();
            boolean append = false;
            long startAt = 0;
            if (code == HttpURLConnection.HTTP_PARTIAL && existing > 0) {
                String range = connection.getHeaderField("Content-Range");
                if (range != null && !range.startsWith("bytes " + existing + "-")) {
                    throw new IOException("Unexpected resume range for "
                            + spec.fileName + ": " + range);
                }
                append = true;
                startAt = existing;
            } else if (code == HttpURLConnection.HTTP_OK) {
                append = false;
                startAt = 0;
            } else if (code == 416 && existing == spec.size) {
                return;
            } else if (code == 416 && existing > 0 && canRestartAfter416) {
                // Some CDNs reject a stale range. Discard only this file's
                // partial download and fetch it once more from byte zero.
                connection.disconnect();
                connection = null;
                deleteFile(partFile);
                downloadFileAttempt(spec, partFile, completedBefore,
                        progress, false);
                return;
            } else {
                String body = readErrorBody(connection);
                throw new IOException("Model download HTTP " + code
                        + " for " + spec.fileName
                        + (body.isEmpty() ? "" : ": " + body));
            }

            long contentLength = connection.getContentLengthLong();
            if (contentLength >= 0 && startAt + contentLength != spec.size) {
                Log.w(TAG, "Unexpected model content length for " + spec.fileName
                        + ": start=" + startAt + ", length=" + contentLength
                        + ", expected total=" + spec.size);
            }

            in = new BufferedInputStream(connection.getInputStream());
            out = new BufferedOutputStream(new FileOutputStream(partFile, append));
            byte[] buffer = new byte[BUFFER_SIZE];
            long written = startAt;
            long lastProgressAt = written;
            int read;
            notifyProgress(progress, completedBefore + written,
                    spec.fileName, false);
            while ((read = in.read(buffer)) != -1) {
                checkInterrupted();
                if (written + read > spec.size) {
                    throw new IOException("Model server sent too many bytes for "
                            + spec.fileName);
                }
                out.write(buffer, 0, read);
                written += read;
                if (written - lastProgressAt >= 1024L * 1024L
                        || written == spec.size) {
                    lastProgressAt = written;
                    notifyProgress(progress, completedBefore + written,
                            spec.fileName, false);
                }
            }
            out.flush();
            if (written != spec.size) {
                throw new IOException("Incomplete offline model download: "
                        + spec.fileName + " (" + written + "/" + spec.size + ")");
            }
        } finally {
            closeQuietly(out);
            closeQuietly(in);
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private boolean verifyFile(File file, ModelFileSpec spec,
                               Progress progress,
                               long progressBytes) throws IOException {
        String actual = sha256(file, spec, progress, progressBytes);
        boolean ok = spec.sha256.equalsIgnoreCase(actual);
        if (!ok) {
            Log.w(TAG, "SHA-256 mismatch for " + spec.fileName
                    + ": expected=" + spec.sha256 + ", actual=" + actual);
        }
        return ok;
    }

    private String sha256(File file, ModelFileSpec spec,
                          Progress progress,
                          long progressBytes) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 is unavailable on this device", e);
        }
        InputStream in = new BufferedInputStream(new FileInputStream(file));
        try {
            byte[] buffer = new byte[BUFFER_SIZE];
            long processed = 0;
            long lastProgressAt = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                checkInterrupted();
                digest.update(buffer, 0, read);
                processed += read;
                if (processed - lastProgressAt >= 16L * 1024L * 1024L) {
                    lastProgressAt = processed;
                    notifyProgress(progress, progressBytes, spec.fileName, true);
                }
            }
        } finally {
            in.close();
        }
        byte[] hash = digest.digest();
        StringBuilder hex = new StringBuilder(hash.length * 2);
        for (byte value : hash) {
            hex.append(String.format(Locale.ROOT, "%02x", value & 0xFF));
        }
        return hex.toString();
    }

    private void ensureFreeSpace(File dir) throws IOException {
        long missing = 0;
        for (ModelFileSpec spec : FILES) {
            File finalFile = new File(dir, spec.fileName);
            if (finalFile.isFile() && finalFile.length() == spec.size) {
                continue;
            }
            File part = partFile(dir, spec);
            long present = part.isFile()
                    ? Math.min(spec.size, part.length()) : 0;
            missing += spec.size - present;
        }
        long required = missing + (missing == 0
                ? 1024L * 1024L
                : EXTRA_FREE_SPACE_BYTES);
        long available;
        try {
            StatFs stat = new StatFs(dir.getAbsolutePath());
            available = stat.getAvailableBytes();
        } catch (Exception ignored) {
            return;
        }
        if (available < required) {
            throw new IOException("Not enough free storage for the offline AI model: "
                    + "required=" + required + ", available=" + available);
        }
    }

    private void writeVerifiedMarker(File dir) throws IOException {
        File marker = new File(dir, VERIFIED_MARKER);
        OutputStream out = new FileOutputStream(marker);
        try {
            String text = "KurdMan offline NLLB pack verified\n"
                    + "revision=" + MODEL_REVISION + "\n"
                    + "files=" + FILES.size() + "\n"
                    + "bytes=" + totalBytes() + "\n";
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.flush();
        } finally {
            out.close();
        }
    }

    private static File partFile(File dir, ModelFileSpec spec) {
        return new File(dir, spec.fileName + ".part");
    }

    private static String readErrorBody(HttpURLConnection connection) {
        InputStream in = connection.getErrorStream();
        if (in == null) {
            return "";
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            int total = 0;
            while ((read = in.read(buffer)) != -1 && total < 8192) {
                int count = Math.min(read, 8192 - total);
                out.write(buffer, 0, count);
                total += count;
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8)
                    .replaceAll("\\s+", " ").trim();
        } catch (Exception ignored) {
            return "";
        } finally {
            closeQuietly(in);
        }
    }

    private static void notifyProgress(Progress progress,
                                       long downloadedBytes,
                                       String currentFile,
                                       boolean verifying) {
        if (progress != null) {
            progress.onProgress(
                    Math.max(0, Math.min(totalBytes(), downloadedBytes)),
                    totalBytes(),
                    currentFile == null ? "" : currentFile,
                    verifying);
        }
    }

    private static void ensureDirectory(File dir) throws IOException {
        if (!dir.exists() && !dir.mkdirs() && !dir.exists()) {
            throw new IOException("Could not create offline model directory: " + dir);
        }
    }

    private static void deleteFile(File file) throws IOException {
        if (file != null && file.exists() && !file.delete()) {
            throw new IOException("Could not delete file: " + file.getName());
        }
    }

    private static void deleteRecursive(File file) {
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
        // Best effort is enough for the delete action; the caller reports
        // whether the model directory still exists afterwards.
        file.delete();
    }

    private static void closeQuietly(InputStream in) {
        if (in != null) {
            try {
                in.close();
            } catch (Exception ignored) {
                // Nothing useful can be done while closing a download stream.
            }
        }
    }

    private static void closeQuietly(OutputStream out) {
        if (out != null) {
            try {
                out.close();
            } catch (Exception ignored) {
                // The caller verifies final size and SHA-256.
            }
        }
    }

    private static void checkInterrupted() throws IOException {
        if (JobRuntime.isStopRequested() || Thread.currentThread().isInterrupted()) {
            throw new IOException(JobRuntime.STOPPED_MESSAGE);
        }
    }
}
