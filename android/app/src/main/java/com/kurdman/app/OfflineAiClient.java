package com.kurdman.app;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Main-process client for {@link OfflineTranslationService}.
 *
 * <p>The actual ONNX Runtime work happens in the {@code :offline_ai}
 * process. If that process dies (native abort or low-memory kill while
 * loading/running the model), this client turns it into an
 * {@code OFFLINE_AI_PROCESS_DIED} {@link IOException} so the app itself
 * stays open and can explain what happened.</p>
 */
final class OfflineAiClient
        implements SoraniTranslator.OfflineEngine, AutoCloseable {
    private static final long CONNECT_TIMEOUT_MS = 60_000L;
    private static final long INIT_TIMEOUT_MS = 10 * 60_000L;
    private static final long TRANSLATE_TIMEOUT_MS = 20 * 60_000L;
    private static final int MAX_BINDER_CHARS = 4000;
    private static final String PROCESS_DIED =
            "OFFLINE_AI_PROCESS_DIED: The offline AI process stopped";

    private final Context context;
    /** Serializes whole requests (connect + send + await) for one job. */
    private final Object ioLock = new Object();
    /** Guards connection/pending state shared with main-thread callbacks. */
    private final Object stateLock = new Object();

    private Messenger service;
    private boolean bindRequested;
    private CountDownLatch connectLatch;
    private int nextRequestId = 1;
    private Pending pending;
    private volatile boolean cancelled;

    private static final class Pending {
        final int id;
        final CountDownLatch latch = new CountDownLatch(1);
        String result;
        String error;

        Pending(int id) {
            this.id = id;
        }
    }

    private final Messenger clientMessenger = new Messenger(
            new Handler(Looper.getMainLooper(), msg -> {
                handleReply(msg);
                return true;
            }));

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            synchronized (stateLock) {
                service = new Messenger(binder);
                if (connectLatch != null) {
                    connectLatch.countDown();
                }
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            // The :offline_ai process died. The binding may reconnect on
            // its own; any in-flight request is failed either way.
            Pending p;
            synchronized (stateLock) {
                service = null;
                p = pending;
                if (connectLatch != null) {
                    connectLatch.countDown();
                }
            }
            if (p != null) {
                if (p.error == null) {
                    p.error = PROCESS_DIED;
                }
                p.latch.countDown();
            }
        }

        @Override
        public void onBindingDied(ComponentName name) {
            synchronized (stateLock) {
                // The binding itself is dead; the next connect rebinds.
                bindRequested = false;
            }
            onServiceDisconnected(name);
        }

        @Override
        public void onNullBinding(ComponentName name) {
            onBindingDied(name);
        }
    };

    OfflineAiClient(Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * Fails the in-flight request without waiting for {@link #ioLock}. The
     * worker thread that owns this client will receive the stop error and
     * then perform the normal close/unbind from its finally block.
     */
    void requestCancel() {
        cancelled = true;
        Pending p;
        CountDownLatch latch;
        synchronized (stateLock) {
            p = pending;
            latch = connectLatch;
        }
        if (p != null) {
            if (p.error == null) {
                p.error = JobRuntime.STOPPED_MESSAGE;
            }
            p.latch.countDown();
        }
        if (latch != null) {
            latch.countDown();
        }
    }

    void initialize() throws Exception {
        synchronized (ioLock) {
            throwIfCancelled();
            connect();
            sendRequest(OfflineAiProtocol.MSG_INIT, null, null,
                    INIT_TIMEOUT_MS);
        }
    }

    @Override
    public String translate(String text, String sourceLanguage) throws Exception {
        if (text == null || text.trim().isEmpty()) {
            return "";
        }
        synchronized (ioLock) {
            throwIfCancelled();
            connect();
            StringBuilder joined = new StringBuilder();
            for (String piece : splitForBinder(text)) {
                throwIfCancelled();
                String result = sendRequest(
                        OfflineAiProtocol.MSG_TRANSLATE, piece,
                        sourceLanguage, TRANSLATE_TIMEOUT_MS);
                if (result != null && !result.isEmpty()) {
                    if (joined.length() > 0) {
                        joined.append(' ');
                    }
                    joined.append(result);
                }
            }
            return joined.toString();
        }
    }

    @Override
    public void close() {
        cancelled = true;
        synchronized (ioLock) {
            Messenger svc;
            boolean wasBound;
            synchronized (stateLock) {
                svc = service;
                wasBound = bindRequested;
                service = null;
                bindRequested = false;
                connectLatch = null;
            }
            if (svc != null) {
                try {
                    svc.send(Message.obtain(
                            null, OfflineAiProtocol.MSG_SHUTDOWN));
                } catch (RemoteException | RuntimeException ignored) {
                    // The service process is already gone.
                }
            }
            if (wasBound) {
                try {
                    context.unbindService(connection);
                } catch (RuntimeException ignored) {
                    // Already unbound (for example after onBindingDied).
                }
            }
        }
    }

    private void throwIfCancelled() throws IOException {
        if (cancelled || JobRuntime.isStopRequested()) {
            throw new IOException(JobRuntime.STOPPED_MESSAGE);
        }
    }

    private void connect() throws Exception {
        throwIfCancelled();
        CountDownLatch latch;
        boolean needBind;
        synchronized (stateLock) {
            if (service != null) {
                return;
            }
            if (connectLatch == null || connectLatch.getCount() == 0) {
                connectLatch = new CountDownLatch(1);
            }
            latch = connectLatch;
            needBind = !bindRequested;
            bindRequested = true;
        }
        if (needBind) {
            Intent intent = new Intent(context, OfflineTranslationService.class);
            boolean started;
            try {
                started = context.bindService(intent, connection,
                        Context.BIND_AUTO_CREATE | Context.BIND_ABOVE_CLIENT);
            } catch (RuntimeException e) {
                synchronized (stateLock) {
                    bindRequested = false;
                }
                throw new IOException(PROCESS_DIED + " (" + e.getMessage() + ")", e);
            }
            if (!started) {
                synchronized (stateLock) {
                    bindRequested = false;
                }
                throw new IOException(PROCESS_DIED + " (bind failed)");
            }
        }
        boolean connected;
        try {
            connected = latch.await(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Offline translation interrupted", e);
        }
        throwIfCancelled();
        synchronized (stateLock) {
            if (!connected || service == null) {
                throw new IOException(PROCESS_DIED + " (connect timeout)");
            }
        }
    }

    private String sendRequest(int what, String text, String source,
                               long timeoutMs) throws Exception {
        Pending p;
        Messenger svc;
        synchronized (stateLock) {
            if (service == null) {
                throw new IOException(PROCESS_DIED);
            }
            svc = service;
            p = new Pending(nextRequestId++);
            pending = p;
        }
        Message request = Message.obtain(null, what);
        request.replyTo = clientMessenger;
        Bundle data = new Bundle();
        data.putInt(OfflineAiProtocol.KEY_REQUEST_ID, p.id);
        if (text != null) {
            data.putString(OfflineAiProtocol.KEY_TEXT, text);
        }
        if (source != null) {
            data.putString(OfflineAiProtocol.KEY_SOURCE, source);
        }
        request.setData(data);
        try {
            svc.send(request);
        } catch (RemoteException | RuntimeException e) {
            synchronized (stateLock) {
                if (pending == p) {
                    pending = null;
                }
            }
            throw new IOException(PROCESS_DIED + " (" + e.getMessage() + ")", e);
        }

        boolean done;
        try {
            done = p.latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Offline translation interrupted", e);
        } finally {
            synchronized (stateLock) {
                if (pending == p) {
                    pending = null;
                }
            }
        }
        throwIfCancelled();
        if (!done) {
            throw new IOException("OFFLINE_AI_TIMEOUT: "
                    + "The offline AI process did not respond in time");
        }
        if (p.error != null) {
            throw new IOException(p.error);
        }
        return p.result == null ? "" : p.result;
    }

    private void handleReply(Message msg) {
        Bundle data = msg.getData();
        int id = data == null
                ? -1 : data.getInt(OfflineAiProtocol.KEY_REQUEST_ID, -1);
        Pending p;
        synchronized (stateLock) {
            p = pending;
        }
        if (p == null || p.id != id) {
            return;
        }
        if (msg.what == OfflineAiProtocol.MSG_INIT_OK
                || msg.what == OfflineAiProtocol.MSG_RESULT_OK) {
            p.result = data.getString(OfflineAiProtocol.KEY_RESULT, "");
        } else {
            p.error = data.getString(OfflineAiProtocol.KEY_ERROR,
                    "OFFLINE_AI_RUNTIME_ERROR: unknown AI process error");
        }
        p.latch.countDown();
    }

    /**
     * Binder transactions share a ~1 MB buffer, so keep each request far
     * below that. OCR segments are normally tiny; this only matters for a
     * pathological whole-page segment. The service does the real
     * model-token chunking.
     */
    private static List<String> splitForBinder(String text) {
        List<String> pieces = new ArrayList<>();
        String remaining = text;
        while (remaining.length() > MAX_BINDER_CHARS) {
            int cut = remaining.lastIndexOf(' ', MAX_BINDER_CHARS);
            if (cut < MAX_BINDER_CHARS / 2) {
                cut = MAX_BINDER_CHARS;
                while (cut > 0 && Character.isLowSurrogate(remaining.charAt(cut))) {
                    cut--;
                }
            }
            if (cut <= 0) {
                cut = Math.min(MAX_BINDER_CHARS, remaining.length());
            }
            String piece = remaining.substring(0, cut).trim();
            if (!piece.isEmpty()) {
                pieces.add(piece);
            }
            remaining = remaining.substring(cut).trim();
        }
        if (!remaining.isEmpty()) {
            pieces.add(remaining);
        }
        return pieces;
    }
}
