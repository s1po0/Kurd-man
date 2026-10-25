package com.kurdman.app;

import android.content.Context;

/**
 * Offline Sorani "language pack" used by the main process.
 *
 * <p>Sorani has no Google ML Kit pack, so Koorix downloads its own
 * small Sorani pack (Helsinki-NLP OPUS-MT, English &rarr; Sorani) the
 * same way every other language downloads a pack. Korean/Japanese/
 * Chinese pages are first translated to English by their normal
 * offline packs, then the Sorani pack turns that English into Sorani
 * — everything stays on the phone, no internet and no giant AI model.</p>
 */
final class SoraniPackEngine
        implements SoraniTranslator.OfflineEngine, AutoCloseable {

    private final MlKitOfflineEngine pivot = new MlKitOfflineEngine();
    private final OfflineAiClient packClient;

    SoraniPackEngine(Context context) {
        packClient = new OfflineAiClient(context);
    }

    @Override
    public String translate(String text, String sourceLanguage,
                            String targetLanguage) throws Exception {
        String english = text;
        if (!"en".equals(sourceLanguage)) {
            english = pivot.translate(text, sourceLanguage, "en");
        }
        if (english == null || english.trim().isEmpty()) {
            return "";
        }
        return packClient.translate(english, "en", "ckb");
    }

    void requestCancel() {
        pivot.requestCancel();
        packClient.requestCancel();
    }

    @Override
    public void close() {
        try {
            pivot.close();
        } catch (RuntimeException ignored) {
            // Best effort; the pack client close below still runs.
        }
        packClient.close();
    }
}
