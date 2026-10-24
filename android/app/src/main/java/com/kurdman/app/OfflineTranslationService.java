package com.kurdman.app;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;

/**
 * Runs the offline NLLB engine in a separate {@code :offline_ai} process.
 *
 * <p>Loading and running three ~450 MB ONNX sessions can abort in native
 * code or get the process killed for memory on some phones. Keeping that
 * work in this process means such a failure ends only this process; the
 * main app process stays alive and can show a friendly error instead of
 * throwing the user out of the app.</p>
 */
public class OfflineTranslationService extends Service {
    private static final String TAG = "OfflineAiService";

    private HandlerThread workerThread;
    private Handler worker;
    private Messenger messenger;
    private OfflineModelManager modelManager;
    private OfflineNllbTranslator engine;

    private final class IncomingHandler extends Handler {
        IncomingHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case OfflineAiProtocol.MSG_INIT:
                    try {
                        ensureEngine().initialize();
                        reply(msg, OfflineAiProtocol.MSG_INIT_OK, "", null);
                    } catch (Throwable t) {
                        reply(msg, OfflineAiProtocol.MSG_INIT_ERROR,
                                null, describe(t));
                    }
                    break;
                case OfflineAiProtocol.MSG_TRANSLATE: {
                    Bundle data = msg.getData();
                    String text = data == null
                            ? "" : data.getString(OfflineAiProtocol.KEY_TEXT, "");
                    String source = data == null
                            ? "en" : data.getString(OfflineAiProtocol.KEY_SOURCE, "en");
                    try {
                        String result = ensureEngine().translate(text, source);
                        reply(msg, OfflineAiProtocol.MSG_RESULT_OK, result, null);
                    } catch (Throwable t) {
                        reply(msg, OfflineAiProtocol.MSG_RESULT_ERROR,
                                null, describe(t));
                    }
                    break;
                }
                case OfflineAiProtocol.MSG_SHUTDOWN:
                    closeEngine();
                    break;
                default:
                    super.handleMessage(msg);
                    break;
            }
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        modelManager = new OfflineModelManager(this);
        workerThread = new HandlerThread("OfflineAiWorker");
        workerThread.start();
        worker = new IncomingHandler(workerThread.getLooper());
        messenger = new Messenger(worker);
        Log.i(TAG, "Offline AI process started");
    }

    @Override
    public IBinder onBind(Intent intent) {
        return messenger.getBinder();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        if (worker != null) {
            worker.post(this::closeEngine);
        }
        return false;
    }

    @Override
    public void onDestroy() {
        if (worker != null) {
            worker.post(this::closeEngine);
        }
        if (workerThread != null) {
            workerThread.quitSafely();
        }
        super.onDestroy();
    }

    private OfflineNllbTranslator ensureEngine() {
        if (engine == null) {
            engine = new OfflineNllbTranslator(modelManager);
        }
        return engine;
    }

    /** Only ever called on the worker thread. */
    private void closeEngine() {
        if (engine != null) {
            try {
                engine.close();
            } catch (Throwable ignored) {
                // Process teardown will reclaim anything left behind.
            }
            engine = null;
        }
    }

    private void reply(Message request, int what, String result, String error) {
        Messenger target = request.replyTo;
        if (target == null) {
            return;
        }
        int requestId = -1;
        Bundle requestData = request.getData();
        if (requestData != null) {
            requestId = requestData.getInt(OfflineAiProtocol.KEY_REQUEST_ID, -1);
        }
        Message response = Message.obtain(null, what);
        Bundle data = new Bundle();
        data.putInt(OfflineAiProtocol.KEY_REQUEST_ID, requestId);
        if (result != null) {
            data.putString(OfflineAiProtocol.KEY_RESULT, result);
        }
        if (error != null) {
            data.putString(OfflineAiProtocol.KEY_ERROR, error);
        }
        response.setData(data);
        try {
            target.send(response);
        } catch (RemoteException ignored) {
            // The client is gone; there is nobody left to report to.
        }
    }

    private static String describe(Throwable t) {
        String message = t.getMessage();
        String text = (message == null || message.trim().isEmpty())
                ? t.getClass().getSimpleName()
                : t.getClass().getSimpleName() + ": " + message;
        return text.length() > 600 ? text.substring(0, 600) : text;
    }
}
