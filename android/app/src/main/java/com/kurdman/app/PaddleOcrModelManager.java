package com.kurdman.app;

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
 * Downloads the optional PP-OCR recognition pack.
 *
 * <p>The pack contains PaddlePaddle's PP-OCRv6 small recognizer for
 * English/Japanese/Chinese and the PP-OCRv5 mobile Korean recognizer. The
 * models are too large to keep the normal KurdMan APK comfortably below
 * Telegram's bot-upload limit, so they are downloaded once, verified, and
 * then used entirely on-device.</p>
 */
final class PaddleOcrModelManager {
    private static final String TAG = "PaddleOcrModels";

    static final String GENERAL_MODEL_FILE = "PP-OCRv6_small_rec.onnx";
    static final String GENERAL_DICT_FILE = "PP-OCRv6_small_rec.yml";
    static final String KOREAN_MODEL_FILE = "korean_PP-OCRv5_mobile_rec.onnx";
    static final String KOREAN_DICT_FILE = "korean_PP-OCRv5_mobile_rec.yml";

    private static final String GENERAL_REVISION =
            "b8f84f0b80c529de40b4fbb3544b84fa7233a513";
    private static final String KOREAN_REVISION =
            "5c6f574b8e2230adf4287b33e736d71b9fabd28e";
    private static final String GENERAL_BASE_URL =
            "https://huggingface.co/PaddlePaddle/PP-OCRv6_small_rec_onnx/resolve/"
                    + GENERAL_REVISION;
    private static final String KOREAN_BASE_URL =
            "https://huggingface.co/PaddlePaddle/korean_PP-OCRv5_mobile_rec_onnx/resolve/"
                    + KOREAN_REVISION;
    private static final String VERIFIED_MARKER = "kurdman-ppocr-verified-v1.txt";
    private static final long EXTRA_FREE_SPACE_BYTES = 32L * 1024L * 1024L;
    private static final int BUFFER_SIZE = 128 * 1024;

    interface Progress {
        void onProgress(long downloadedBytes, long totalBytes,
                        String currentFile, boolean verifying);
    }

    private static final class FileSpec {
        final String url;
        final String fileName;
        final long size;
        final String sha256;

        FileSpec(String baseUrl, String remotePath, String fileName,
                 long size, String sha256) {
            this.url = baseUrl + "/" + remotePath;
            this.fileName = fileName;
            this.size = size;
            this.sha256 = sha256;
        }
    }

    private static final List<FileSpec> FILES = new ArrayList<>();

    static {
        FILES.add(new FileSpec(
                GENERAL_BASE_URL,
                "inference.onnx",
                GENERAL_MODEL_FILE,
                21159378L,
                "5435fd747c9e0efe15a96d0b378d5bd157e9492ed8fd80edf08f30d02fa24634"));
        FILES.add(new FileSpec(
                GENERAL_BASE_URL,
                "inference.yml",
                GENERAL_DICT_FILE,
                150579L,
                null));
        FILES.add(new FileSpec(
                KOREAN_BASE_URL,
                "inference.onnx",
                KOREAN_MODEL_FILE,
                13418787L,
                "92f0b7785e64fc9090106a241cf4c1eb97472824558272751b88a2a4476d3a08"));
        FILES.add(new FileSpec(
                KOREAN_BASE_URL,
                "inference.yml",
                KOREAN_DICT_FILE,
                96039L,
                null));
    }

    private final Context context;

    PaddleOcrModelManager(Context context) {
        this.context = context.getApplicationContext();
    }

    File modelDir() {
        return new File(new File(context.getFilesDir(), "ocr-ai"),
                "pp-ocrv6-small-v1");
    }

    File modelFile(String name) {
        return new File(modelDir(), name);
    }

    static long totalBytes() {
        long total = 0;
        for (FileSpec spec : FILES) {
            total += spec.size;
        }
        return total;
    }

    boolean isPackReady() {
        File dir = modelDir();
        if (!new File(dir, VERIFIED_MARKER).isFile()) {
            return false;
        }
        for (FileSpec spec : FILES) {
            File file = new File(dir, spec.fileName);
            if (!file.isFile() || file.length() != spec.size) {
                return false;
            }
        }
        return true;
    }

    boolean isReadyFor(String sourceLanguage) {
        if (!isPackReady()) {
            return false;
        }
        boolean korean = sourceLanguage != null
                && sourceLanguage.toLowerCase(Locale.ROOT).startsWith("ko");
        File model = modelFile(korean ? KOREAN_MODEL_FILE : GENERAL_MODEL_FILE);
        File dict = modelFile(korean ? KOREAN_DICT_FILE : GENERAL_DICT_FILE);
        return model.isFile() && model.length() > 0
                && dict.isFile() && dict.length() > 0;
    }

    long downloadedBytes() {
        File dir = modelDir();
        long total = 0;
        for (FileSpec spec : FILES) {
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

    void downloadPack(Progress progress) throws IOException {
        File dir = modelDir();
        ensureDirectory(dir);
        if (isPackReady()) {
            notifyProgress(progress, totalBytes(), "", false);
            return;
        }
        ensureFreeSpace(dir);
        long completed = 0;
        for (FileSpec spec : FILES) {
            checkInterrupted();
            File finalFile = new File(dir, spec.fileName);
            if (finalFile.isFile() && finalFile.length() == spec.size) {
                notifyProgress(progress, completed + spec.size,
                        spec.fileName, true);
                if (verifyFile(finalFile, spec)) {
                    completed += spec.size;
                    continue;
                }
                deleteFile(finalFile);
            } else if (finalFile.exists()) {
                deleteFile(finalFile);
            }
            downloadAndVerify(spec, dir, completed, progress);
            completed += spec.size;
            notifyProgress(progress, completed, spec.fileName, false);
        }
        writeVerifiedMarker(dir);
        notifyProgress(progress, totalBytes(), "", false);
        Log.i(TAG, "PP-OCR pack ready in " + dir.getAbsolutePath());
    }

    boolean deletePack() {
        File dir = modelDir();
        deleteRecursive(dir);
        return !dir.exists();
    }

    private void downloadAndVerify(FileSpec spec, File dir, long completedBefore,
                                   Progress progress) throws IOException {
        File finalFile = new File(dir, spec.fileName);
        File partFile = partFile(dir, spec);
        for (int attempt = 0; attempt < 2; attempt++) {
            downloadAttempt(spec, partFile, completedBefore, progress, true);
            if (!partFile.isFile() || partFile.length() != spec.size) {
                throw new IOException("PP-OCR file has the wrong size: "
                        + spec.fileName);
            }
            notifyProgress(progress, completedBefore + spec.size,
                    spec.fileName, true);
            if (verifyFile(partFile, spec)) {
                if (finalFile.exists() && !finalFile.delete()) {
                    throw new IOException("Could not replace PP-OCR file: "
                            + spec.fileName);
                }
                if (!partFile.renameTo(finalFile)) {
                    throw new IOException("Could not finalize PP-OCR file: "
                            + spec.fileName);
                }
                return;
            }
            deleteFile(partFile);
        }
        throw new IOException("Checksum mismatch for PP-OCR file: " + spec.fileName);
    }

    private void downloadAttempt(FileSpec spec, File partFile,
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
            connection = (HttpURLConnection) new URL(spec.url).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(30000);
            connection.setReadTimeout(120000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent",
                    "KurdMan/1.7 (Android; PP-OCR model pack)");
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
                    throw new IOException("Unexpected PP-OCR resume range: " + range);
                }
                append = true;
                startAt = existing;
            } else if (code == HttpURLConnection.HTTP_OK) {
                append = false;
                startAt = 0;
            } else if (code == 416 && existing == spec.size) {
                return;
            } else if (code == 416 && existing > 0 && canRestartAfter416) {
                connection.disconnect();
                connection = null;
                deleteFile(partFile);
                downloadAttempt(spec, partFile, completedBefore, progress, false);
                return;
            } else {
                String body = readErrorBody(connection);
                throw new IOException("PP-OCR download HTTP " + code
                        + " for " + spec.fileName
                        + (body.isEmpty() ? "" : ": " + body));
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
                    throw new IOException("PP-OCR server sent too many bytes for "
                            + spec.fileName);
                }
                out.write(buffer, 0, read);
                written += read;
                if (written - lastProgressAt >= 512L * 1024L
                        || written == spec.size) {
                    lastProgressAt = written;
                    notifyProgress(progress, completedBefore + written,
                            spec.fileName, false);
                }
            }
            out.flush();
            if (written != spec.size) {
                throw new IOException("Incomplete PP-OCR download: "
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

    private boolean verifyFile(File file, FileSpec spec) throws IOException {
        if (!file.isFile() || file.length() != spec.size) {
            return false;
        }
        if (spec.sha256 == null) {
            // The small YAML dictionaries are pinned by revision and checked
            // by exact byte length; the executable ONNX models below are
            // additionally checked against their official LFS SHA-256.
            return true;
        }
        return spec.sha256.equalsIgnoreCase(sha256(file));
    }

    private String sha256(File file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 is unavailable on this device", e);
        }
        InputStream in = new BufferedInputStream(new FileInputStream(file));
        try {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = in.read(buffer)) != -1) {
                checkInterrupted();
                digest.update(buffer, 0, read);
            }
        } finally {
            in.close();
        }
        StringBuilder hex = new StringBuilder(64);
        for (byte value : digest.digest()) {
            hex.append(String.format(Locale.ROOT, "%02x", value & 0xFF));
        }
        return hex.toString();
    }

    private void ensureFreeSpace(File dir) throws IOException {
        long missing = 0;
        for (FileSpec spec : FILES) {
            File finalFile = new File(dir, spec.fileName);
            if (finalFile.isFile() && finalFile.length() == spec.size) {
                continue;
            }
            File part = partFile(dir, spec);
            long present = part.isFile() ? Math.min(spec.size, part.length()) : 0;
            missing += spec.size - present;
        }
        long available;
        try {
            available = new StatFs(dir.getAbsolutePath()).getAvailableBytes();
        } catch (Exception ignored) {
            return;
        }
        if (available < missing + EXTRA_FREE_SPACE_BYTES) {
            throw new IOException("Not enough free storage for the PP-OCR pack: "
                    + "required=" + (missing + EXTRA_FREE_SPACE_BYTES)
                    + ", available=" + available);
        }
    }

    private void writeVerifiedMarker(File dir) throws IOException {
        File marker = new File(dir, VERIFIED_MARKER);
        OutputStream out = new FileOutputStream(marker);
        try {
            String text = "KurdMan PP-OCR pack verified\n"
                    + "generalRevision=" + GENERAL_REVISION + "\n"
                    + "koreanRevision=" + KOREAN_REVISION + "\n"
                    + "bytes=" + totalBytes() + "\n";
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.flush();
        } finally {
            out.close();
        }
    }

    private static File partFile(File dir, FileSpec spec) {
        return new File(dir, spec.fileName + ".part");
    }

    private static String readErrorBody(HttpURLConnection connection) {
        InputStream in = connection.getErrorStream();
        if (in == null) {
            return "";
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[2048];
            int read;
            int total = 0;
            while ((read = in.read(buffer)) != -1 && total < 4096) {
                int count = Math.min(read, 4096 - total);
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

    private static void notifyProgress(Progress progress, long downloaded,
                                       String file, boolean verifying) {
        if (progress != null) {
            progress.onProgress(
                    Math.max(0, Math.min(totalBytes(), downloaded)),
                    totalBytes(), file == null ? "" : file, verifying);
        }
    }

    private static void ensureDirectory(File dir) throws IOException {
        if (!dir.exists() && !dir.mkdirs() && !dir.exists()) {
            throw new IOException("Could not create PP-OCR model directory: " + dir);
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
        file.delete();
    }

    private static void closeQuietly(InputStream in) {
        if (in != null) {
            try {
                in.close();
            } catch (Exception ignored) {
                // Final size/SHA checks catch damaged downloads.
            }
        }
    }

    private static void closeQuietly(OutputStream out) {
        if (out != null) {
            try {
                out.close();
            } catch (Exception ignored) {
                // Final size/SHA checks catch damaged downloads.
            }
        }
    }

    private static void checkInterrupted() throws IOException {
        if (JobRuntime.isStopRequested()
                || Thread.currentThread().isInterrupted()) {
            throw new IOException(JobRuntime.STOPPED_MESSAGE);
        }
    }
}
