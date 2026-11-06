package com.kurdman.app;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Process-wide state for the currently running background job.
 *
 * <p>The stop button in the activity and the Stop action in the foreground
 * service notification both end up here. Long loops check
 * {@link #throwIfStopped()} between pages/segments; blocking calls are also
 * interrupted where the platform allows it.</p>
 */
final class JobRuntime {
    static final String STOPPED_MESSAGE = "JOB_STOPPED_BY_USER";

    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private static final AtomicBoolean STOP_REQUESTED = new AtomicBoolean(false);
    private static final AtomicReference<String> STATUS =
            new AtomicReference<>("KurdMan ئامادەیە");
    private static volatile Thread jobThread;
    private static volatile android.content.Context appContext;

    static void init(android.content.Context context) {
        if (context != null) {
            appContext = context.getApplicationContext();
        }
    }

    private static String s(int resId, String fallback) {
        android.content.Context context = appContext;
        return context == null ? fallback : context.getString(resId);
    }
    private static volatile Runnable stopAction;
    private static volatile TranslationProject activeProject;
    private static final java.util.Set<Runnable> cancellations = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<Runnable,Boolean>());
    static void registerCancellation(Runnable action) { cancellations.add(action); }
    static void unregisterCancellation(Runnable action) { cancellations.remove(action); }
    static synchronized boolean tryBeginJob() {
        if (!RUNNING.compareAndSet(false,true)) return false;
        STOP_REQUESTED.set(false); activeProject=null; return true;
    }
    static synchronized void activateSavedProject(TranslationProject project) { activeProject=project; }
    static boolean isJobThread() { return jobThread==Thread.currentThread(); }


    private JobRuntime() {
    }

    static void setStopAction(Runnable action) {
        stopAction = action;
    }

    static void beginJob() {
        STOP_REQUESTED.set(false);
        RUNNING.set(true);
    }

    static void markThreadStarted() {
        jobThread = Thread.currentThread();
        // The single executor reuses this thread. A previous STOP must not
        // leave its interrupt flag set and instantly cancel the next job.
        Thread.interrupted();
        RUNNING.set(true);
    }

    static void markThreadFinished() {
        if (jobThread == Thread.currentThread()) {
            jobThread = null;
        }
    }

    static synchronized void endJob() {
        activeProject = null;
        RUNNING.set(false);
        jobThread = null;
    }

    static boolean isRunning() {
        return RUNNING.get();
    }

    static boolean isStopRequested() {
        return STOP_REQUESTED.get() || Thread.currentThread().isInterrupted();
    }

    static synchronized void requestStop() {
        STOP_REQUESTED.set(true);
        if (appContext != null && activeProject != null) TranslationProjectStore.allowRecovery(appContext,activeProject,false);
        for (Runnable cancel : cancellations) { try { cancel.run(); } catch (RuntimeException ignored) { } }
        STATUS.set(s(R.string.ks_0677, "🛑 وەستاندن داواکرا…"));
        Runnable action = stopAction;
        if (action != null) {
            try {
                action.run();
            } catch (RuntimeException ignored) {
                // Interrupting the worker below is the fallback.
            }
        }
        Thread thread = jobThread;
        if (thread != null && thread != Thread.currentThread()) {
            thread.interrupt();
        }
    }

    static void throwIfStopped() throws InterruptedException {
        if (STOP_REQUESTED.get()) {
            throw new InterruptedException(STOPPED_MESSAGE);
        }
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException(STOPPED_MESSAGE);
        }
    }

    static boolean wasStopped(Throwable error) {
        if (STOP_REQUESTED.get()) {
            return true;
        }
        Throwable current = error;
        int depth = 0;
        while (current != null && depth < 6) {
            String message = current.getMessage();
            if (current instanceof InterruptedException
                    || (message != null && message.contains(STOPPED_MESSAGE))
                    || (message != null
                    && message.toLowerCase().contains("translation interrupted"))) {
                return true;
            }
            current = current.getCause();
            depth++;
        }
        return false;
    }

    static void setStatus(String status) {
        if (status != null && !status.trim().isEmpty()) {
            STATUS.set(status.trim());
        }
    }

    static String status() {
        return STATUS.get();
    }
}
