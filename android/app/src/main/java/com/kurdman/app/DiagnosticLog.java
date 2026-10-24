package com.kurdman.app;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Process;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Small private diagnostic log shown in the in-app Reports section.
 *
 * <p>The log intentionally contains only app events, exception classes,
 * stack traces and non-secret job metadata. Telegram tokens, chat IDs and
 * API keys must never be passed to this class; common secret-looking
 * patterns in arbitrary exception text are scrubbed defensively as well.</p>
 */
final class DiagnosticLog {
    private static final String TAG = "KurdManDiagnostic";
    private static final String DIRECTORY = "diagnostics";
    private static final String FILE_NAME = "kurdman-diagnostics.log";
    private static final long MAX_FILE_BYTES = 512L * 1024L;
    private static final int MAX_REPORT_CHARS = 90_000;
    private static final long DEDUPE_WINDOW_MS = 15_000L;

    private static volatile boolean handlerInstalled;
    private static volatile String lastFingerprint = "";
    private static volatile long lastFingerprintAt;

    private DiagnosticLog() {
    }

    static synchronized void installUncaughtHandler(Context context) {
        if (context == null || handlerInstalled) {
            return;
        }
        handlerInstalled = true;
        final Context appContext = context.getApplicationContext();
        final Thread.UncaughtExceptionHandler previous =
                Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                error(appContext, "UNCAUGHT_CRASH", error,
                        "thread=" + (thread == null ? "?" : thread.getName()));
            } catch (Throwable ignored) {
                // Never let diagnostics create a second crash.
            }
            if (previous != null) {
                previous.uncaughtException(thread, error);
            } else {
                Process.killProcess(Process.myPid());
            }
        });
    }

    static void info(Context context, String category, String message) {
        append(context, "INFO", category, message, null, null);
    }

    static void warning(Context context, String category, String message) {
        append(context, "WARN", category, message, null, null);
    }

    static void status(Context context, String status) {
        if (status == null) {
            return;
        }
        String clean = status.trim();
        if (clean.startsWith("❌")) {
            append(context, "STATUS_ERROR", "app", clean, null, null);
        } else if (clean.startsWith("⚠")) {
            append(context, "STATUS_WARN", "app", clean, null, null);
        } else if (clean.startsWith("🛑")) {
            append(context, "STATUS_STOP", "app", clean, null, null);
        }
    }

    static void note(Context context, String note) {
        if (note == null || note.trim().isEmpty()) {
            return;
        }
        append(context, "USER_NOTE", "report", note.trim(), null, null);
    }

    static void error(Context context, String category, Throwable error,
                      String details) {
        if (error == null) {
            append(context, "ERROR", category, details, null, null);
            return;
        }
        String fingerprint = fingerprint(error);
        long now = System.currentTimeMillis();
        synchronized (DiagnosticLog.class) {
            if (fingerprint.equals(lastFingerprint)
                    && now - lastFingerprintAt < DEDUPE_WINDOW_MS) {
                return;
            }
            lastFingerprint = fingerprint;
            lastFingerprintAt = now;
        }
        String message = shortSummary(error);
        append(context, "ERROR", category, message, details,
                Log.getStackTraceString(error));
        try {
            Log.e(TAG, category + ": " + message, error);
        } catch (Throwable ignored) {
            // Diagnostics are best-effort.
        }
    }

    static synchronized String readReport(Context context) {
        StringBuilder out = new StringBuilder();
        out.append(header(context)).append("\n\n");
        File file = logFile(context);
        String body = readFile(file);
        if (body.trim().isEmpty()) {
            out.append("هیچ کێشەیەک هێشتا لەم بەشەدا تۆمار نەکراوە.\n")
                    .append("No diagnostic problem has been recorded yet.\n");
        } else {
            out.append("===== DIAGNOSTIC LOG (oldest → newest) =====\n");
            out.append(body);
        }
        String report = out.toString();
        if (report.length() > MAX_REPORT_CHARS) {
            report = "… (older part shortened for display) …\n"
                    + report.substring(report.length() - MAX_REPORT_CHARS);
        }
        return report;
    }

    static synchronized void clear(Context context) {
        File file = logFile(context);
        if (file != null && file.exists()) {
            // Best effort: the following entry makes the time of clearing clear.
            file.delete();
        }
        synchronized (DiagnosticLog.class) {
            lastFingerprint = "";
            lastFingerprintAt = 0L;
        }
        info(context, "report", "Diagnostic log cleared by user.");
    }

    static String shortSummary(Throwable error) {
        if (error == null) {
            return "Unknown error";
        }
        Throwable root = error;
        int depth = 0;
        while (root.getCause() != null && root.getCause() != root && depth < 8) {
            root = root.getCause();
            depth++;
        }
        StringBuilder out = new StringBuilder();
        out.append(error.getClass().getSimpleName());
        if (error.getMessage() != null && !error.getMessage().trim().isEmpty()) {
            out.append(": ").append(sanitize(error.getMessage().trim()));
        }
        if (root != error) {
            out.append(" | root=").append(root.getClass().getSimpleName());
            if (root.getMessage() != null && !root.getMessage().trim().isEmpty()) {
                out.append(": ").append(sanitize(root.getMessage().trim()));
            }
        }
        String frame = firstAppFrame(error);
        if (frame != null) {
            out.append(" | at ").append(frame);
        }
        return out.toString();
    }

    private static synchronized void append(Context context, String level,
                                            String category, String message,
                                            String details, String stackTrace) {
        if (context == null) {
            return;
        }
        try {
            File file = logFile(context.getApplicationContext());
            if (file == null) {
                return;
            }
            rotateIfNeeded(file);
            StringBuilder entry = new StringBuilder();
            entry.append('[').append(timestamp()).append("] ")
                    .append(level).append(" / ")
                    .append(category == null ? "app" : sanitize(category))
                    .append(" pid=").append(Process.myPid())
                    .append("\n");
            if (message != null && !message.trim().isEmpty()) {
                entry.append(sanitize(message.trim())).append('\n');
            }
            if (details != null && !details.trim().isEmpty()) {
                entry.append("details: ").append(sanitize(details.trim()))
                        .append('\n');
            }
            if (stackTrace != null && !stackTrace.trim().isEmpty()) {
                entry.append(sanitize(stackTrace.trim())).append('\n');
            }
            entry.append("----\n");

            OutputStreamWriter writer = new OutputStreamWriter(
                    new FileOutputStream(file, true), StandardCharsets.UTF_8);
            try {
                writer.write(entry.toString());
                writer.flush();
            } finally {
                writer.close();
            }
        } catch (Throwable ignored) {
            // A report must never break translation or app startup.
        }
    }

    private static File logFile(Context context) {
        if (context == null) {
            return null;
        }
        File dir = new File(context.getFilesDir(), DIRECTORY);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return new File(dir, FILE_NAME);
    }

    private static void rotateIfNeeded(File file) {
        try {
            if (file == null || !file.exists() || file.length() <= MAX_FILE_BYTES) {
                return;
            }
            String body = readFile(file);
            int keepChars = 180_000;
            String tail = body.length() > keepChars
                    ? body.substring(body.length() - keepChars) : body;
            OutputStreamWriter writer = new OutputStreamWriter(
                    new FileOutputStream(file, false), StandardCharsets.UTF_8);
            try {
                writer.write("[older diagnostic entries removed to save space]\n");
                writer.write(tail);
                writer.flush();
            } finally {
                writer.close();
            }
        } catch (Throwable ignored) {
            // If rotation fails, appending a small entry is still acceptable.
        }
    }

    private static String readFile(File file) {
        if (file == null || !file.exists()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    new FileInputStream(file), StandardCharsets.UTF_8));
            try {
                char[] buffer = new char[8192];
                int read;
                while ((read = reader.read(buffer)) != -1) {
                    out.append(buffer, 0, read);
                }
            } finally {
                reader.close();
            }
        } catch (Throwable ignored) {
            return "";
        }
        return out.toString();
    }

    private static String header(Context context) {
        StringBuilder out = new StringBuilder();
        out.append("KurdMan diagnostic report\n");
        out.append("Generated: ").append(timestamp()).append('\n');
        if (context != null) {
            try {
                PackageInfo info = context.getPackageManager().getPackageInfo(
                        context.getPackageName(), 0);
                long code = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                        ? info.getLongVersionCode() : info.versionCode;
                out.append("App: ").append(info.versionName)
                        .append(" (").append(code).append(")\n");
            } catch (Throwable ignored) {
                out.append("App: unknown\n");
            }
        }
        out.append("Android: ").append(Build.VERSION.RELEASE)
                .append(" / API ").append(Build.VERSION.SDK_INT).append('\n');
        out.append("Device: ").append(Build.MANUFACTURER)
                .append(' ').append(Build.MODEL)
                .append(" (").append(Build.DEVICE).append(")\n");
        try {
            out.append("ABI: ").append(android.text.TextUtils.join(
                    ", ", Build.SUPPORTED_ABIS)).append('\n');
        } catch (Throwable ignored) {
            // Optional header detail.
        }
        out.append("Locale: ").append(Locale.getDefault().toLanguageTag())
                .append('\n');
        if (context != null) {
            try {
                File files = context.getFilesDir();
                out.append("Free internal storage: ")
                        .append(formatBytes(files.getFreeSpace()))
                        .append(" / ").append(formatBytes(files.getTotalSpace()))
                        .append('\n');
                ActivityManager manager = (ActivityManager)
                        context.getSystemService(Context.ACTIVITY_SERVICE);
                if (manager != null) {
                    ActivityManager.MemoryInfo memory =
                            new ActivityManager.MemoryInfo();
                    manager.getMemoryInfo(memory);
                    out.append("Device RAM available now: ")
                            .append(formatBytes(memory.availMem))
                            .append(" / ").append(formatBytes(memory.totalMem))
                            .append('\n');
                    out.append("App memory class: ")
                            .append(manager.getLargeMemoryClass())
                            .append(" MB (large heap)\n");
                }
            } catch (Throwable ignored) {
                // Optional header detail.
            }
        }
        out.append("Privacy: Telegram token/chat ID and API keys are not "
                + "intentionally included.");
        return out.toString();
    }

    private static String formatBytes(long bytes) {
        if (bytes >= 1_000_000_000L) {
            return String.format(Locale.ROOT, "%.2f GB", bytes / 1_000_000_000f);
        }
        if (bytes >= 1_000_000L) {
            return String.format(Locale.ROOT, "%.1f MB", bytes / 1_000_000f);
        }
        if (bytes >= 1_000L) {
            return String.format(Locale.ROOT, "%.1f KB", bytes / 1_000f);
        }
        return bytes + " B";
    }

    private static String timestamp() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT)
                .format(new Date());
    }

    private static String fingerprint(Throwable error) {
        StringBuilder out = new StringBuilder();
        Throwable current = error;
        int depth = 0;
        while (current != null && depth < 4) {
            out.append(current.getClass().getName()).append(':')
                    .append(current.getMessage()).append('|');
            StackTraceElement[] frames = current.getStackTrace();
            for (int i = 0; frames != null && i < Math.min(3, frames.length); i++) {
                out.append(frames[i]).append(';');
            }
            current = current.getCause();
            depth++;
        }
        return Integer.toHexString(out.toString().hashCode());
    }

    private static String firstAppFrame(Throwable error) {
        Throwable current = error;
        int depth = 0;
        while (current != null && depth < 6) {
            StackTraceElement[] frames = current.getStackTrace();
            if (frames != null) {
                for (StackTraceElement frame : frames) {
                    if (frame != null && frame.getClassName() != null
                            && frame.getClassName().startsWith("com.kurdman.app")) {
                        return frame.toString();
                    }
                }
            }
            current = current.getCause();
            depth++;
        }
        return null;
    }

    private static String sanitize(String value) {
        if (value == null) {
            return "";
        }
        String clean = value;
        clean = clean.replaceAll("\\b\\d{6,}:[A-Za-z0-9_-]{20,}\\b", "[TOKEN_HIDDEN]");
        clean = clean.replaceAll("(?i)(api[_-]?key|authorization|token)\\s*[:=]\\s*[^\\s,;]+",
                "$1=[HIDDEN]");
        clean = clean.replaceAll("(?i)(chat[_-]?id)\\s*[:=]\\s*-?\\d{5,}",
                "$1=[HIDDEN]");
        return clean;
    }
}
