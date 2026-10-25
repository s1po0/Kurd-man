package com.kurdman.app;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.common.model.RemoteModelManager;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.TranslateRemoteModel;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Lightweight fully-offline translation via ML Kit on-device
 * translation. Each language is a small downloadable pack (~30 MB)
 * fetched once; afterwards translation works with no network and no
 * large AI model. ML Kit covers ~58 languages (Arabic, English,
 * Persian, Turkish, Korean, Japanese, Chinese, ...). Sorani (ckb) is
 * not among them, so Sorani targets use the online route.
 */
public class MlKitOfflineEngine
        implements SoraniTranslator.OfflineEngine, AutoCloseable {

    private final Map<String, Translator> clients = new HashMap<>();
    private final Map<String, Boolean> ensured = new HashMap<>();
    private volatile boolean cancelled;

    /** Maps an app language code to an ML Kit code, or null if unsupported. */
    public static String mlKitCode(String code) {
        if (code == null) {
            return null;
        }
        String c = code.trim();
        if (c.startsWith("zh")) {
            return TranslateLanguage.CHINESE;
        }
        if ("nb".equals(c) || "nn".equals(c)) {
            c = "no";
        }
        if ("iw".equals(c)) {
            c = "he";
        }
        if ("fil".equals(c)) {
            c = "tl";
        }
        try {
            return TranslateLanguage.fromLanguageTag(c);
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean isPairSupported(String source, String target) {
        return mlKitCode(source) != null && mlKitCode(target) != null;
    }

    // ---------- pack management (used by Settings UI) ----------

    public static boolean isPackDownloaded(String source, String target)
            throws Exception {
        String s = mlKitCode(source);
        String t = mlKitCode(target);
        if (s == null || t == null) {
            return false;
        }
        RemoteModelManager mgr = RemoteModelManager.getInstance();
        boolean srcOk = Boolean.TRUE.equals(Tasks.await(
                mgr.isModelDownloaded(
                        new TranslateRemoteModel.Builder(s).build()),
                20, TimeUnit.SECONDS));
        boolean tgtOk = Boolean.TRUE.equals(Tasks.await(
                mgr.isModelDownloaded(
                        new TranslateRemoteModel.Builder(t).build()),
                20, TimeUnit.SECONDS));
        return srcOk && tgtOk;
    }

    public static void downloadPack(String source, String target)
            throws Exception {
        String s = mlKitCode(source);
        String t = mlKitCode(target);
        if (s == null || t == null) {
            throw new IOException("OFFLINE_PAIR_UNSUPPORTED");
        }
        RemoteModelManager mgr = RemoteModelManager.getInstance();
        DownloadConditions conditions = new DownloadConditions.Builder()
                .build();
        Tasks.await(mgr.download(
                new TranslateRemoteModel.Builder(s).build(), conditions),
                15, TimeUnit.MINUTES);
        Tasks.await(mgr.download(
                new TranslateRemoteModel.Builder(t).build(), conditions),
                15, TimeUnit.MINUTES);
    }

    public static void deletePack(String source, String target)
            throws Exception {
        RemoteModelManager mgr = RemoteModelManager.getInstance();
        String s = mlKitCode(source);
        String t = mlKitCode(target);
        if (s != null) {
            Tasks.await(mgr.deleteDownloadedModel(
                    new TranslateRemoteModel.Builder(s).build()),
                    60, TimeUnit.SECONDS);
        }
        if (t != null) {
            Tasks.await(mgr.deleteDownloadedModel(
                    new TranslateRemoteModel.Builder(t).build()),
                    60, TimeUnit.SECONDS);
        }
    }

    // ---------- translation ----------

    @Override
    public String translate(String text, String sourceLanguage,
                            String targetLanguage) throws Exception {
        if (cancelled) {
            throw new InterruptedException("Offline translation cancelled");
        }
        String s = mlKitCode(sourceLanguage);
        String t = mlKitCode(targetLanguage);
        if (s == null || t == null) {
            throw new IOException("OFFLINE_PAIR_UNSUPPORTED");
        }
        String key = s + ">" + t;
        Translator translator;
        synchronized (clients) {
            translator = clients.get(key);
            if (translator == null) {
                translator = Translation.getClient(
                        new TranslatorOptions.Builder()
                                .setSourceLanguage(s)
                                .setTargetLanguage(t)
                                .build());
                clients.put(key, translator);
            }
        }
        if (!Boolean.TRUE.equals(ensured.get(key))) {
            try {
                Tasks.await(translator.downloadModelIfNeeded(
                        new DownloadConditions.Builder().build()),
                        90, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IOException(
                        "OFFLINE_PACK_MISSING: " + e.getMessage(), e);
            }
            ensured.put(key, Boolean.TRUE);
        }
        String out = Tasks.await(translator.translate(text),
                60, TimeUnit.SECONDS);
        return out == null ? "" : out;
    }

    public void requestCancel() {
        cancelled = true;
    }

    @Override
    public void close() {
        synchronized (clients) {
            for (Translator t : clients.values()) {
                try {
                    t.close();
                } catch (Exception ignored) {
                }
            }
            clients.clear();
        }
        ensured.clear();
    }
}
