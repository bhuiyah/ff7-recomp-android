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
import java.io.File;

/**
 * Runs GameBuild as a foreground service: building the game for the device takes minutes, and
 * it should carry on with the screen off or the launcher in the background. A notification
 * shows the progress, with Pause; a paused build's notification has Continue (GitHub #53).
 * GameBuild's static state is what the launcher shows.
 */
public class BuildService extends Service {
    private static final String CHANNEL = "build";
    private static final int ID = 7;
    static final String ACTION_PAUSE = "com.ff7recomp.build.PAUSE", ACTION_CONTINUE = "com.ff7recomp.build.CONTINUE";
    /** The service's build thread is running (GameBuild.running is set before it starts). */
    private static volatile boolean working;

    static void start(Context ctx) {
        if (GameBuild.running) return;
        GameBuild.running = true;          // until the service's thread takes over
        GameBuild.error = null;
        GameBuild.paused = false;
        GameBuild.pauseRequested = false;
        GameBuild.stage = "Starting";
        GameBuild.percent = 0;
        ctx.startForegroundService(new Intent(ctx, BuildService.class));
    }

    /** Pause: the build stops at its next check and keeps what it has done. */
    static void pause() {
        if (GameBuild.running) GameBuild.pauseRequested = true;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Building the game", NotificationManager.IMPORTANCE_LOW));
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_PAUSE.equals(action)) {            // from the notification (a plain service start)
            pause();
            if (!working) stopSelf(startId);
            return START_NOT_STICKY;
        }
        // started with startForegroundService (the launcher, or Continue in the notification)
        startForeground(ID, note(GameBuild.running ? GameBuild.stage : "Starting", GameBuild.percent), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        if (working) return START_NOT_STICKY;         // one build at a time
        if (ACTION_CONTINUE.equals(action)) {
            if (GameBuild.running) return START_NOT_STICKY;
            GameBuild.running = true;
            GameBuild.error = null;
            GameBuild.paused = false;
            GameBuild.pauseRequested = false;
            GameBuild.stage = "Starting";
            GameBuild.quiet = getSharedPreferences("launcher", MODE_PRIVATE).getBoolean("quiet_build", false);
        }
        nm.cancel(ID + 1);
        working = true;
        PowerManager.WakeLock wl = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ff7recomp:build");
        wl.acquire(3 * 60 * 60 * 1000L);
        Thread t = new Thread(() -> {
            GameBuild b = new GameBuild(this);
            Thread ticker = new Thread(() -> {
                try {
                    while (true) {
                        Thread.sleep(2000);
                        nm.notify(ID, note(GameBuild.stage, GameBuild.percent));
                    }
                } catch (InterruptedException ignored) { }
            });
            ticker.start();
            String err = b.run(new File(new GameInstall(this).game, "ff7_en.exe"));
            ticker.interrupt();
            if (wl.isHeld()) wl.release();
            stopForeground(STOP_FOREGROUND_REMOVE);
            Notification.Builder done = new Notification.Builder(this, CHANNEL)
                    .setSmallIcon(R.drawable.ic_launcher_monochrome)
                    .setContentIntent(launcher())
                    .setAutoCancel(true);
            if (GameBuild.paused) {
                done.setContentTitle("Building the game is paused at " + GameBuild.percent + "%")
                        .setContentText("Continue goes on where it stopped.")
                        .addAction(new Notification.Action.Builder(null, "Continue",
                                PendingIntent.getForegroundService(this, 2, new Intent(this, BuildService.class).setAction(ACTION_CONTINUE),
                                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT)).build());
            } else {
                done.setContentTitle(err == null ? "FINAL FANTASY VII is ready" : "Building the game stopped")
                        .setContentText(err == null ? "Built for this device. Press Play." : err);
            }
            nm.notify(ID + 1, done.build());
            working = false;
            stopSelf();
        }, "ff7-build");
        t.start();
        return START_NOT_STICKY;
    }

    private PendingIntent launcher() {
        return PendingIntent.getActivity(this, 0, new Intent(this, LauncherActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE);
    }

    private Notification note(String what, int pct) {
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle("Building FINAL FANTASY VII for this device")
                .setContentText(what + " · " + pct + "%")
                .setProgress(100, pct, false)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(launcher())
                .addAction(new Notification.Action.Builder(null, "Pause",
                        PendingIntent.getService(this, 1, new Intent(this, BuildService.class).setAction(ACTION_PAUSE),
                                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT)).build())
                .build();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
