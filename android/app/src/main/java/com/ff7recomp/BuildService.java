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
 * shows the progress; GameBuild's static state is what the launcher shows.
 */
public class BuildService extends Service {
    private static final String CHANNEL = "build";
    private static final int ID = 7;

    static void start(Context ctx) {
        if (GameBuild.running) return;
        GameBuild.running = true;          // until the service's thread takes over
        GameBuild.error = null;
        GameBuild.stage = "Starting";
        GameBuild.percent = 0;
        ctx.startForegroundService(new Intent(ctx, BuildService.class));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Building the game", NotificationManager.IMPORTANCE_LOW));
        startForeground(ID, note("Starting", 0), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
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
                    .setContentTitle(err == null ? "FINAL FANTASY VII is ready" : "Building the game stopped")
                    .setContentText(err == null ? "Built for this device. Press Play." : err)
                    .setContentIntent(launcher())
                    .setAutoCancel(true);
            nm.notify(ID + 1, done.build());
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
                .build();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
