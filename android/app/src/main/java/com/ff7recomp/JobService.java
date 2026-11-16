package com.ff7recomp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The launcher's long jobs before the build, as a foreground service, so they carry on with the
 * screen off or the launcher in the background (as BuildService does for the build, GitHub #53;
 * the same as FF9Recomp's JobService): the Steam download and importing the game from a folder.
 * One at a time. A notification shows the progress, with Pause; a paused job's notification has
 * Continue. When the game files are in, the build starts (BuildService), also in the background.
 *
 * Both jobs keep what they have done: the Steam download skips files already whole (SHA-1), the
 * import skips files already copied whole (same size). The job is kept in the launcher's
 * preferences until it ends, so one stopped by Pause, by the system or by an update shows
 * Continue on the Game files page.
 */
public class JobService extends Service {
    private static final String TAG = "FF7Jobs", CHANNEL = "jobs";
    private static final int ID = 9;
    static final String STEAM = "steam", IMPORT = "import";
    static final String ACTION_PAUSE = "com.ff7recomp.job.PAUSE", ACTION_CONTINUE = "com.ff7recomp.job.CONTINUE";
    private static final String KIND = "job_kind", ARG = "job_arg", RAN = "job_ran";

    /** The job running (one of the kinds), or null. */
    static volatile String running;
    /** What it is doing; when it has ended, how it ended (null: nothing to say). */
    static volatile String status = "";
    /** The last job stopped for Pause (it is still pending). */
    static volatile boolean paused;
    /** Set by Pause: the job stops at its next check and keeps what it has done. */
    static final AtomicBoolean pause = new AtomicBoolean();
    /** The service's worker thread is running. */
    private static volatile boolean working;

    /** Thrown by a job that stopped for Pause. */
    static final class Paused extends java.io.IOException {
        Paused() { super("paused"); }
    }

    static void checkPause() throws Paused { if (pause.get()) throw new Paused(); }

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("launcher", MODE_PRIVATE); }

    /** The job that was started and has not ended (paused or stopped by the system), when none runs. */
    static String pending(Context c) { return running != null ? null : prefs(c).getString(KIND, null); }

    static String title(String kind) {
        return STEAM.equals(kind) ? "Downloading FINAL FANTASY VII from Steam" : "Importing FINAL FANTASY VII";
    }

    /** Start a job (arg: the import's folder URI, held with a persistable permission). */
    static boolean start(Context c, String kind, String arg) {
        if (running != null || GameBuild.running) return false;
        prefs(c).edit().putString(KIND, kind).putString(ARG, arg).remove(RAN).commit();
        begin(kind);
        c.startForegroundService(new Intent(c, JobService.class));
        return true;
    }

    /** Continue the pending job. */
    static boolean resume(Context c) {
        String kind = pending(c);
        if (kind == null || GameBuild.running) return false;
        begin(kind);
        c.startForegroundService(new Intent(c, JobService.class).setAction(ACTION_CONTINUE));
        return true;
    }

    private static void begin(String kind) {
        running = kind;
        paused = false;
        pause.set(false);
        status = "Starting";
    }

    static void pause() { if (running != null) pause.set(true); }

    /** Drop the pending job (what it copied or downloaded stays; a new one skips it). */
    static void forget(Context c) {
        if (running != null) return;
        String arg = prefs(c).getString(ARG, null);
        prefs(c).edit().remove(KIND).remove(ARG).remove(RAN).commit();
        paused = false;
        status = "";
        if (arg != null) {
            try { c.getContentResolver().releasePersistableUriPermission(Uri.parse(arg), Intent.FLAG_GRANT_READ_URI_PERMISSION); }
            catch (Exception ignored) { }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Downloads and imports", NotificationManager.IMPORTANCE_LOW));
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_PAUSE.equals(action)) {               // the notification's Pause (a plain service start)
            pause();
            if (!working) stopSelf(startId);
            return START_NOT_STICKY;
        }
        // started with startForegroundService: startForeground comes first, whatever happens next
        String kind = prefs(this).getString(KIND, null);
        startForeground(ID, note(kind), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        if (working) return START_NOT_STICKY;            // one job at a time
        if (kind == null || GameBuild.running) {
            if (!working) running = null;
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (running == null) begin(kind);                // Continue from the notification
        nm.cancel(ID + 1);
        working = true;
        String arg = prefs(this).getString(ARG, null);
        PowerManager.WakeLock wl = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ff7recomp:job");
        wl.acquire(6 * 60 * 60 * 1000L);
        new Thread(() -> {
            Thread ticker = new Thread(() -> {
                try {
                    while (true) {
                        Thread.sleep(2000);
                        nm.notify(ID, note(kind));
                    }
                } catch (InterruptedException ignored) { }
            });
            ticker.start();
            String msg;
            boolean finished = true, build = false;
            try {
                String err = run(kind, arg);
                if (err == null) { build = true; msg = null; }
                else msg = err;
            } catch (Paused e) {
                msg = null;
            } catch (Throwable e) {
                Log.e(TAG, kind, e);
                // both jobs carry on where they stopped: keep it for Continue
                msg = "Stopped: " + (e.getMessage() != null ? e.getMessage() : e.toString()) + ". Continue goes on where it stopped.";
                finished = false;
            }
            ticker.interrupt();
            if (pause.get() && msg == null && !build) {
                paused = true;
                finished = false;
                msg = "Paused. Continue goes on where it stopped; nothing done so far is lost.";
            }
            if (finished) {
                prefs(this).edit().remove(KIND).remove(ARG).remove(RAN).commit();
                if (arg != null) {
                    try { getContentResolver().releasePersistableUriPermission(Uri.parse(arg), Intent.FLAG_GRANT_READ_URI_PERMISSION); }
                    catch (Exception ignored) { }
                }
            }
            Log.i(TAG, kind + ": " + (build ? "done, building" : msg));
            status = msg;
            running = null;
            if (build) {
                // the game files are in: build the game for this device, also in the background
                // (started while this service is still in the foreground, which lets it start)
                GameBuild.quiet = prefs(this).getBoolean("quiet_build", false);
                try { BuildService.start(this); }
                catch (Exception e) {
                    GameBuild.running = false;
                    Log.w(TAG, "build not started: " + e);
                    build = false;
                    msg = status = "Your game files are in. Open FF7Recomp to build the game for this device.";
                }
            }
            if (wl.isHeld()) wl.release();
            stopForeground(STOP_FOREGROUND_REMOVE);
            if (!build) {
                Notification.Builder done = new Notification.Builder(this, CHANNEL)
                        .setSmallIcon(R.drawable.ic_launcher_monochrome)
                        .setContentIntent(launcher())
                        .setAutoCancel(true)
                        .setContentTitle(paused ? title(kind) + ": paused" : finished ? title(kind) + ": done" : title(kind) + ": stopped")
                        .setContentText(msg)
                        .setStyle(new Notification.BigTextStyle().bigText(msg));
                if (!finished)
                    done.addAction(new Notification.Action.Builder(null, "Continue",
                            PendingIntent.getForegroundService(this, 2, new Intent(this, JobService.class).setAction(ACTION_CONTINUE),
                                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT)).build());
                nm.notify(ID + 1, done.build());
            }
            working = false;
            stopSelf();
        }, "ff7-job").start();
        return START_NOT_STICKY;
    }

    /** Runs the job: null when the game files are in and the game should be built, else how it
     *  ended (a message). Throws Paused when Pause stopped it. */
    private String run(String kind, String arg) throws Exception {
        GameInstall gi = new GameInstall(this);
        if (STEAM.equals(kind)) {
            SteamLink link = new SteamLink(this);
            if (!link.signedIn()) return "Sign in to Steam first (Game files → Download from Steam), then download again.";
            boolean[] exeChanged = { false };
            String err = gi.downloadFromSteam(link, w -> status = w, pause, exeChanged);
            if ("stopped".equals(err)) throw new Paused();
            if (err != null) return "Not downloaded: " + err + ".";
            if (exeChanged[0] || !new GameBuild(this).built()) return null;
            return "Every game file was checked against Steam's copy; any missing or damaged ones were downloaded again.";
        }
        // the import ran before (a pause, or the system stopped it): keep what it copied
        gi.skipCopied = prefs(this).getBoolean(RAN, false);
        prefs(this).edit().putBoolean(RAN, true).commit();
        String err = gi.importGame(Uri.parse(arg), w -> status = w);
        if (err != null) return "Not imported: " + err + ".";
        return null;
    }

    private PendingIntent launcher() {
        return PendingIntent.getActivity(this, 0, new Intent(this, LauncherActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE);
    }

    private Notification note(String kind) {
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle(title(kind))
                .setContentText(status)
                .setProgress(100, 0, true)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(launcher())
                .addAction(new Notification.Action.Builder(null, "Pause",
                        PendingIntent.getService(this, 1, new Intent(this, JobService.class).setAction(ACTION_PAUSE),
                                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT)).build())
                .build();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
