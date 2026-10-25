package com.kurdman.app;

/**
 * Message constants shared by {@link OfflineAiClient} (main process) and
 * {@link OfflineTranslationService} (the {@code :offline_ai} process).
 *
 * <p>Kept in its own class with only compile-time constants so the main
 * process never has to load the service or ONNX Runtime classes.</p>
 */
final class OfflineAiProtocol {
    static final int MSG_INIT = 1;
    static final int MSG_TRANSLATE = 2;
    static final int MSG_SHUTDOWN = 3;

    static final int MSG_INIT_OK = 11;
    static final int MSG_INIT_ERROR = 12;
    static final int MSG_RESULT_OK = 13;
    static final int MSG_RESULT_ERROR = 14;

    static final String KEY_REQUEST_ID = "requestId";
    static final String KEY_TEXT = "text";
    static final String KEY_SOURCE = "source";
    static final String KEY_RESULT = "result";
    static final String KEY_ERROR = "error";

    private OfflineAiProtocol() {
    }
}
