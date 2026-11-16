package com.ff7recomp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

/**
 * Keeps the app running while the mod download queue works, so mods keep downloading with the
 * launcher in the background or the screen off (as JobService does for the game's own jobs; the
 * same as FF9Recomp's QueueService). The worker itself is DownloadQueue's; this shows its
 * progress, with Pause, and stops when the queue is done or paused. A paused queue's
 * notification has Continue.
 */
public class QueueService extends Service {
    private static final String CHANNEL = "jobs";
    private static final int ID = 11;
    static final String ACTION_PAUSE = "com.ff7recomp.queue.PAUSE", ACTION_CONTINUE = "com.ff7recomp.queue.CONTINUE";
    private static volatile boolean watching;

    static void start(Context c) {
        try {
            c.startForegroundService(new Intent(c, QueueService.class));
        } catch (Exception e) {      // not allowed from the background: the queue still runs while the app does
            Log.w("FF7Mods", "download queue without its service: " + e);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Downloads and imports", NotificationManager.IMPORTANCE_LOW));
        DownloadQueue q = LauncherActivity.queueFor(this);
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_PAUSE.equals(action)) {
            q.pause();
            if (!watching) stopSelf(startId);
            return START_NOT_STICKY;
        }
        startForeground(ID, note(q), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        if (ACTION_CONTINUE.equals(action)) q.resume();
        if (watching) return START_NOT_STICKY;
        nm.cancel(ID + 1);
        watching = true;
        PowerManager.WakeLock wl = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ff7recomp:mods");
        wl.acquire(6 * 60 * 60 * 1000L);
        new Thread(() -> {
            try {
                while (q.working()) {
                    nm.notify(ID, note(q));
                    Thread.sleep(2000);
                }
            } catch (InterruptedException ignored) { }
            if (wl.isHeld()) wl.release();
            stopForeground(STOP_FOREGROUND_REMOVE);
            if (q.paused() && q.pendingCount() > 0) {
                nm.notify(ID + 1, new Notification.Builder(this, CHANNEL)
                        .setSmallIcon(R.drawable.ic_launcher_monochrome)
                        .setContentTitle("Mod downloads are paused")
                        .setContentText(q.pendingCount() + " waiting. Continue goes on where they stopped.")
                        .setContentIntent(launcher())
                        .setAutoCancel(true)
                        .addAction(new Notification.Action.Builder(null, "Continue",
                                PendingIntent.getForegroundService(this, 4, new Intent(this, QueueService.class).setAction(ACTION_CONTINUE),
                                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT)).build())
                        .build());
            }
            watching = false;
            stopSelf();
        }, "mod-queue-service").start();
        return START_NOT_STICKY;
    }

    private PendingIntent launcher() {
        return PendingIntent.getActivity(this, 0, new Intent(this, LauncherActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE);
    }

    private Notification note(DownloadQueue q) {
        DownloadQueue.Item it = q.running();
        int n = q.pendingCount(), pct = it != null ? it.percent() : -1;
        String title = it != null ? it.mod.name : "Mod downloads";
        String text = (it != null ? it.phase + (pct >= 0 ? " · " + pct + "%" : "") : "Starting") + (n > 1 ? " · " + (n - 1) + " more waiting" : "");
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle(title)
                .setContentText(text)
                .setProgress(100, Math.max(pct, 0), pct < 0)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(launcher())
                .addAction(new Notification.Action.Builder(null, "Pause",
                        PendingIntent.getService(this, 3, new Intent(this, QueueService.class).setAction(ACTION_PAUSE),
                                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT)).build())
                .build();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
