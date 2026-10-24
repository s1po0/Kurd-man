package com.kurdman.app;

/** Messenger constants for the isolated {@code :ocr_ai} PP-OCR process. */
final class PaddleOcrProtocol {
    static final int MSG_INIT = 1;
    static final int MSG_RECOGNIZE = 2;
    static final int MSG_SHUTDOWN = 3;

    static final int MSG_INIT_OK = 11;
    static final int MSG_INIT_ERROR = 12;
    static final int MSG_RESULT_OK = 13;
    static final int MSG_RESULT_ERROR = 14;

    static final String KEY_REQUEST_ID = "requestId";
    static final String KEY_SOURCE = "source";
    static final String KEY_PATHS = "paths";
    static final String KEY_RESULT = "result";
    static final String KEY_ERROR = "error";

    private PaddleOcrProtocol() {
    }
}
