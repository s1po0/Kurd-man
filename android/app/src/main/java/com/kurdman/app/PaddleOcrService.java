package com.kurdman.app;

import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.util.Locale;

/**
 * PP-OCR inference in the separate {@code :ocr_ai} process.
 *
 * <p>Line crops are passed as app-private cache file paths rather than over
 * Binder. If ONNX Runtime ever aborts natively or this process is killed for
 * memory, the main KurdMan process stays alive and simply falls back to the
 * ML Kit readings.</p>
 */
public final class PaddleOcrService extends Service {
    private static final String TAG = "PaddleOcrService";

    private HandlerThread workerThread;
    private Messenger messenger;
    private PaddleOcrRecognizer recognizer;
    private String recognizerLanguage;

    private final Handler.Callback callback = msg -> {
        switch (msg.what) {
            case PaddleOcrProtocol.MSG_INIT:
                handleInit(msg);
                return true;
            case PaddleOcrProtocol.MSG_RECOGNIZE:
                handleRecognize(msg);
                return true;
            case PaddleOcrProtocol.MSG_SHUTDOWN:
                closeRecognizer();
                stopSelf();
                return true;
            default:
                return false;
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        workerThread = new HandlerThread("kurdman-ppocr");
        workerThread.start();
        messenger = new Messenger(new Handler(workerThread.getLooper(), callback));
    }

    @Override
    public IBinder onBind(Intent intent) {
        return messenger.getBinder();
    }

    @Override
    public void onDestroy() {
        closeRecognizer();
        if (workerThread != null) {
            workerThread.quitSafely();
            workerThread = null;
        }
        super.onDestroy();
    }

    private void handleInit(Message msg) {
        Bundle data = msg.getData();
        int id = data == null ? -1
                : data.getInt(PaddleOcrProtocol.KEY_REQUEST_ID, -1);
        String source = data == null ? "en"
                : data.getString(PaddleOcrProtocol.KEY_SOURCE, "en");
        try {
            ensureRecognizer(source);
            reply(msg, PaddleOcrProtocol.MSG_INIT_OK, id, "", null);
        } catch (Throwable e) {
            reply(msg, PaddleOcrProtocol.MSG_INIT_ERROR, id, null, describe(e));
        }
    }

    private void handleRecognize(Message msg) {
        Bundle data = msg.getData();
        int id = data == null ? -1
                : data.getInt(PaddleOcrProtocol.KEY_REQUEST_ID, -1);
        String source = data == null ? "en"
                : data.getString(PaddleOcrProtocol.KEY_SOURCE, "en");
        String paths = data == null ? ""
                : data.getString(PaddleOcrProtocol.KEY_PATHS, "");
        try {
            ensureRecognizer(source);
            StringBuilder out = new StringBuilder();
            if (!paths.isEmpty()) {
                String[] items = paths.split("\n", -1);
                for (int i = 0; i < items.length; i++) {
                    if (i > 0) {
                        out.append('\n');
                    }
                    out.append(recognizeOne(items[i]));
                }
            }
            reply(msg, PaddleOcrProtocol.MSG_RESULT_OK, id,
                    out.toString(), null);
        } catch (Throwable e) {
            reply(msg, PaddleOcrProtocol.MSG_RESULT_ERROR, id,
                    null, describe(e));
        }
    }

    private String recognizeOne(String path) {
        Bitmap bitmap = null;
        try {
            if (path == null || path.isEmpty()) {
                return "0\t";
            }
            File file = new File(path);
            String cachePath = getCacheDir().getCanonicalPath();
            if (!file.getCanonicalPath().startsWith(cachePath)) {
                return "0\t";
            }
            bitmap = BitmapFactory.decodeFile(path);
            if (bitmap == null || recognizer == null) {
                return "0\t";
            }
            PaddleOcrRecognizer.Result result = recognizer.recognize(bitmap);
            String safeText = result.text
                    .replace('\n', ' ')
                    .replace('\r', ' ')
                    .replace('\t', ' ');
            return String.format(Locale.ROOT, "%.5f\t%s",
                    Math.max(0f, Math.min(1f, result.confidence)), safeText);
        } catch (Throwable e) {
            Log.w(TAG, "Could not recognize one PP-OCR crop", e);
            return "0\t";
        } finally {
            if (bitmap != null) {
                bitmap.recycle();
            }
        }
    }

    private void ensureRecognizer(String sourceLanguage) throws Exception {
        String requested = sourceLanguage == null ? "en" : sourceLanguage;
        boolean requestedKorean = requested.toLowerCase(Locale.ROOT).startsWith("ko");
        boolean currentKorean = recognizerLanguage != null
                && recognizerLanguage.toLowerCase(Locale.ROOT).startsWith("ko");
        if (recognizer != null && requestedKorean == currentKorean) {
            return;
        }
        closeRecognizer();
        recognizer = new PaddleOcrRecognizer(this, requested);
        recognizerLanguage = requested;
    }

    private void closeRecognizer() {
        if (recognizer != null) {
            try {
                recognizer.close();
            } catch (Throwable ignored) {
                // The service process is disposable by design.
            }
            recognizer = null;
            recognizerLanguage = null;
        }
    }

    private void reply(Message request, int what, int id,
                       String result, String error) {
        Messenger target = request.replyTo;
        if (target == null) {
            return;
        }
        Message response = Message.obtain(null, what);
        Bundle data = new Bundle();
        data.putInt(PaddleOcrProtocol.KEY_REQUEST_ID, id);
        if (result != null) {
            data.putString(PaddleOcrProtocol.KEY_RESULT, result);
        }
        if (error != null) {
            data.putString(PaddleOcrProtocol.KEY_ERROR, error);
        }
        response.setData(data);
        try {
            target.send(response);
        } catch (RemoteException | RuntimeException ignored) {
            // The main process has already moved on.
        }
    }

    private static String describe(Throwable error) {
        String message = error.getMessage();
        if (error instanceof OutOfMemoryError) {
            return "PPOCR_OUT_OF_MEMORY: " + message;
        }
        if (error instanceof IOException && message != null
                && message.startsWith("PPOCR_")) {
            return message;
        }
        return "PPOCR_RUNTIME_ERROR: " + error.getClass().getSimpleName()
                + (message == null || message.isEmpty() ? "" : ": " + message);
    }
}
