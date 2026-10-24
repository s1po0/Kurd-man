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

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Main-process client for {@link PaddleOcrService}. */
final class PaddleOcrClient implements AutoCloseable {
    private static final long CONNECT_TIMEOUT_MS = 60_000L;
    private static final long INIT_TIMEOUT_MS = 120_000L;
    private static final String PROCESS_DIED =
            "PPOCR_PROCESS_DIED: The PP-OCR process stopped";

    static final class LineResult {
        final String text;
        final float confidence;

        LineResult(String text, float confidence) {
            this.text = text == null ? "" : text;
            this.confidence = confidence;
        }
    }

    private static final class Pending {
        final int id;
        final CountDownLatch latch = new CountDownLatch(1);
        String result;
        String error;

        Pending(int id) {
            this.id = id;
        }
    }

    private final Context context;
    private final String sourceLanguage;
    private final Object ioLock = new Object();
    private final Object stateLock = new Object();

    private Messenger service;
    private boolean bindRequested;
    private CountDownLatch connectLatch;
    private int nextRequestId = 1;
    private Pending pending;
    private volatile boolean cancelled;

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
                bindRequested = false;
            }
            onServiceDisconnected(name);
        }

        @Override
        public void onNullBinding(ComponentName name) {
            onBindingDied(name);
        }
    };

    PaddleOcrClient(Context context, String sourceLanguage) {
        this.context = context.getApplicationContext();
        this.sourceLanguage = sourceLanguage == null ? "en" : sourceLanguage;
    }

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
            sendRequest(PaddleOcrProtocol.MSG_INIT, null, INIT_TIMEOUT_MS);
        }
    }

    List<LineResult> recognizeFiles(List<File> files) throws Exception {
        List<LineResult> out = new ArrayList<>();
        if (files == null || files.isEmpty()) {
            return out;
        }
        StringBuilder paths = new StringBuilder();
        for (int i = 0; i < files.size(); i++) {
            if (i > 0) {
                paths.append('\n');
            }
            paths.append(files.get(i).getAbsolutePath());
        }
        String raw;
        synchronized (ioLock) {
            throwIfCancelled();
            connect();
            long timeout = Math.min(15L * 60_000L,
                    30_000L + (files.size() * 7_500L));
            raw = sendRequest(PaddleOcrProtocol.MSG_RECOGNIZE,
                    paths.toString(), timeout);
        }
        String[] lines = raw == null ? new String[0] : raw.split("\n", -1);
        for (int i = 0; i < files.size(); i++) {
            String line = i < lines.length ? lines[i] : "";
            int tab = line.indexOf('\t');
            float confidence = 0f;
            String text = "";
            if (tab >= 0) {
                try {
                    confidence = Float.parseFloat(line.substring(0, tab));
                } catch (NumberFormatException ignored) {
                    confidence = 0f;
                }
                text = line.substring(tab + 1);
            }
            out.add(new LineResult(text, confidence));
        }
        return out;
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
                    svc.send(Message.obtain(null, PaddleOcrProtocol.MSG_SHUTDOWN));
                } catch (RemoteException | RuntimeException ignored) {
                    // The isolated process is already gone.
                }
            }
            if (wasBound) {
                try {
                    context.unbindService(connection);
                } catch (RuntimeException ignored) {
                    // Binding already died.
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
            boolean started;
            try {
                started = context.bindService(
                        new Intent(context, PaddleOcrService.class),
                        connection,
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
            throw new IOException("PP-OCR connection interrupted", e);
        }
        throwIfCancelled();
        synchronized (stateLock) {
            if (!connected || service == null) {
                throw new IOException(PROCESS_DIED + " (connect timeout)");
            }
        }
    }

    private String sendRequest(int what, String paths, long timeoutMs)
            throws Exception {
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
        data.putInt(PaddleOcrProtocol.KEY_REQUEST_ID, p.id);
        data.putString(PaddleOcrProtocol.KEY_SOURCE, sourceLanguage);
        if (paths != null) {
            data.putString(PaddleOcrProtocol.KEY_PATHS, paths);
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
            throw new IOException("PP-OCR interrupted", e);
        } finally {
            synchronized (stateLock) {
                if (pending == p) {
                    pending = null;
                }
            }
        }
        throwIfCancelled();
        if (!done) {
            throw new IOException("PPOCR_TIMEOUT: PP-OCR did not respond in time");
        }
        if (p.error != null) {
            throw new IOException(p.error);
        }
        return p.result == null ? "" : p.result;
    }

    private void handleReply(Message msg) {
        Bundle data = msg.getData();
        int id = data == null ? -1
                : data.getInt(PaddleOcrProtocol.KEY_REQUEST_ID, -1);
        Pending p;
        synchronized (stateLock) {
            p = pending;
        }
        if (p == null || p.id != id) {
            return;
        }
        if (msg.what == PaddleOcrProtocol.MSG_INIT_OK
                || msg.what == PaddleOcrProtocol.MSG_RESULT_OK) {
            p.result = data.getString(PaddleOcrProtocol.KEY_RESULT, "");
        } else {
            p.error = data.getString(PaddleOcrProtocol.KEY_ERROR,
                    "PPOCR_RUNTIME_ERROR: unknown PP-OCR process error");
        }
        p.latch.countDown();
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "PaddleOcrClient[%s]", sourceLanguage);
    }
}
