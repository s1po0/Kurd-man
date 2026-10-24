package com.kurdman.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

/**
 * User-controlled foreground service that keeps KurdMan's process and CPU
 * awake during long translation/render/download jobs.
 *
 * <p>Android can still force-stop an app in extreme cases (user force stop,
 * reboot, OEM task killers), so this is the strongest normal Android API
 * combination rather than an absolute guarantee: foreground-service process
 * priority, a partial wake lock while the screen is off, a visible Stop
 * notification, and an optional persistent manual mode.</p>
 */
public class KeepAliveService extends Service {
    static final String ACTION_START_JOB = "com.kurdman.app.keepalive.START_JOB";
    static final String ACTION_START_MANUAL = "com.kurdman.app.keepalive.START_MANUAL";
    static final String ACTION_UPDATE = "com.kurdman.app.keepalive.UPDATE";
    static final String ACTION_JOB_FINISHED = "com.kurdman.app.keepalive.JOB_FINISHED";
    static final String ACTION_STOP_ALL = "com.kurdman.app.keepalive.STOP_ALL";

    private static final String CHANNEL_ID = "kurdman_keepalive";
    private static final int NOTIFICATION_ID = 61017;
    private static final String PREFS_NAME = "kurdman_settings";
    private static final String PREF_DESIRED = "keep_alive_desired";

    private static volatile boolean serviceRunning = false;

    private PowerManager.WakeLock wakeLock;
    private final Handler watchdogHandler = new Handler(Looper.getMainLooper());
    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            if (!JobRuntime.isRunning() && !isManualDesired(KeepAliveService.this)) {
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
                return;
            }
            ensureForeground();
            watchdogHandler.postDelayed(this, 15_000L);
        }
    };

    public static boolean isManualDesired(Context context) {
        return prefs(context).getBoolean(PREF_DESIRED, false);
    }

    public static boolean isServiceRunning() {
        return serviceRunning;
    }

    public static void startForJob(Context context) {
        Intent intent = new Intent(context, KeepAliveService.class)
                .setAction(ACTION_START_JOB);
        startForegroundServiceSafely(context, intent);
    }

    public static void startManual(Context context) {
        prefs(context).edit().putBoolean(PREF_DESIRED, true).apply();
        Intent intent = new Intent(context, KeepAliveService.class)
                .setAction(ACTION_START_MANUAL);
        startForegroundServiceSafely(context, intent);
    }

    public static void updateNotification(Context context) {
        if (!serviceRunning) {
            return;
        }
        Intent intent = new Intent(context, KeepAliveService.class)
                .setAction(ACTION_UPDATE);
        try {
            context.startService(intent);
        } catch (RuntimeException ignored) {
            // The service remains foreground with its previous text.
        }
    }

    public static void jobFinished(Context context) {
        if (!serviceRunning) {
            return;
        }
        Intent intent = new Intent(context, KeepAliveService.class)
                .setAction(ACTION_JOB_FINISHED);
        try {
            context.startService(intent);
        } catch (RuntimeException ignored) {
            // Manual mode can be stopped from the notification as a fallback.
        }
    }

    public static void stopAll(Context context) {
        prefs(context).edit().putBoolean(PREF_DESIRED, false).apply();
        // Prevent a status update from racing the stop intent and putting
        // the service back into the foreground after the user tapped STOP.
        serviceRunning = false;
        JobRuntime.requestStop();
        if (serviceRunning) {
            Intent intent = new Intent(context, KeepAliveService.class)
                    .setAction(ACTION_STOP_ALL);
            try {
                context.startService(intent);
            } catch (RuntimeException ignored) {
                context.stopService(new Intent(context, KeepAliveService.class));
            }
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        serviceRunning = true;
        createNotificationChannel();
        watchdogHandler.postDelayed(watchdog, 15_000L);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP_ALL.equals(action)) {
            prefs(this).edit().putBoolean(PREF_DESIRED, false).apply();
            JobRuntime.requestStop();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_START_MANUAL.equals(action)) {
            prefs(this).edit().putBoolean(PREF_DESIRED, true).apply();
        }

        if (ACTION_UPDATE.equals(action) && !serviceRunning) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        boolean shouldRun = JobRuntime.isRunning()
                || isManualDesired(this)
                || ACTION_START_JOB.equals(action)
                || ACTION_START_MANUAL.equals(action);
        if (!shouldRun) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }

        serviceRunning = true;
        ensureForeground();
        if (ACTION_JOB_FINISHED.equals(action) && !isManualDesired(this)) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // stopWithTask=false in the manifest plus this foreground service
        // gives the process its best normal chance to finish the user's job
        // after the task is swiped away. There is intentionally no attempt
        // to relaunch an activity behind the user's back.
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        watchdogHandler.removeCallbacks(watchdog);
        releaseWakeLock();
        serviceRunning = false;
        super.onDestroy();
    }

    private void ensureForeground() {
        // The CPU wake lock is needed while real work is running. In idle
        // manual mode the foreground service still protects the process,
        // without needlessly preventing the phone from sleeping.
        if (JobRuntime.isRunning()) {
            acquireWakeLock();
        } else {
            releaseWakeLock();
        }
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private Notification buildNotification() {
        Intent openIntent = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPendingIntent = PendingIntent.getActivity(
                this, 101, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stopIntent = new Intent(this, KeepAliveService.class)
                .setAction(ACTION_STOP_ALL);
        PendingIntent stopPendingIntent = PendingIntent.getService(
                this, 102, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        boolean running = JobRuntime.isRunning();
        String title = running
                ? "KurdMan کاردەکات — ناکوژرێتەوە تا STOP"
                : "KurdMan چالاکە — پارێزگاری هەمیشەیی";
        String text = running ? JobRuntime.status()
                : "ئامادەیە و زیندوو دەمێنێتەوە تا دوگمەی STOP دابگریت";

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(openPendingIntent)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel,
                        "STOP", stopPendingIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void createNotificationChannel() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "KurdMan — کارکردنی بەردەوام",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(
                "وەرگێڕان و ئیشە درێژەکان بەردەوام دەبن تا STOP دابگریت");
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    private void acquireWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            return;
        }
        PowerManager powerManager = getSystemService(PowerManager.class);
        if (powerManager == null) {
            return;
        }
        wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "KurdMan::TranslationKeepAlive");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire();
    }

    private void releaseWakeLock() {
        if (wakeLock != null) {
            try {
                if (wakeLock.isHeld()) {
                    wakeLock.release();
                }
            } catch (RuntimeException ignored) {
                // Process shutdown reclaims the lock if this races teardown.
            }
            wakeLock = null;
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
    }

    private static void startForegroundServiceSafely(Context context, Intent intent) {
        try {
            ContextCompat.startForegroundService(context, intent);
        } catch (RuntimeException ignored) {
            // Android may reject a start if the app was already fully
            // backgrounded without an exemption. The current job still runs
            // in-process; the user can reopen KurdMan to start protection.
        }
    }
}
