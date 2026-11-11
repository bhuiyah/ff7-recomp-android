package com.ff7recomp;

import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Pixel Guide for the bottom screen's book when it is not installed: its latest release from
 * its own GitHub page (it is not on Google Play), handed to Android's installer, which asks the
 * player to confirm (and, the first time, to allow installs from this app). Runs in the game's
 * process; the companion shows the progress.
 */
final class GuideInstaller {
    private static final String TAG = "ff7guide";
    private static final String RELEASES = "https://api.github.com/repos/rexmont/Pixel-Guide-Android/releases/latest";
    private static final String ACTION_DONE = "com.ff7recomp.GUIDE_INSTALLED";

    static volatile boolean busy;
    static volatile String status;          // shown on the bottom screen while busy / just after
    static volatile long statusUntil;
    private static volatile int displayId = -1;

    static void start(Context ctx, int display) {
        if (busy) return;
        busy = true;
        displayId = display;
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            try {
                run(app);
            } catch (Exception e) {
                Log.w(TAG, "Pixel Guide install: " + e);
                say("Pixel Guide could not be downloaded: " + e.getMessage(), 6000);
                busy = false;
            }
        }, "guide-install").start();
    }

    private static void say(String s, long ms) {
        status = s;
        statusUntil = System.currentTimeMillis() + ms;
    }

    private static void run(Context ctx) throws Exception {
        say("Finding Pixel Guide…", 60000);
        JSONObject rel = new JSONObject(get(RELEASES));
        String version = rel.optString("tag_name");
        JSONArray assets = rel.optJSONArray("assets");
        String url = null;
        long size = 0;
        for (int pass = 0; pass < 2 && url == null; pass++)          // the arm64 build first, then the universal one
            for (int i = 0; assets != null && i < assets.length(); i++) {
                JSONObject a = assets.getJSONObject(i);
                String n = a.optString("name");
                if (!n.endsWith(".apk") || !n.contains(pass == 0 ? "arm64" : "universal")) continue;
                url = a.optString("browser_download_url");
                size = a.optLong("size");
                break;
            }
        if (url == null || !url.startsWith("https://github.com/rexmont/Pixel-Guide-Android/"))
            throw new IOException("no Android download in its latest release");
        File dir = new File(ctx.getCacheDir(), "guide");
        GameBuild.deleteTree(dir);
        dir.mkdirs();
        File apk = new File(dir, "PixelGuide.apk");
        HttpURLConnection c = open(url);
        if (c.getResponseCode() != 200) throw new IOException("download failed (" + c.getResponseCode() + ")");
        long total = c.getContentLengthLong() > 0 ? c.getContentLengthLong() : size, got = 0;
        try (InputStream in = c.getInputStream(); OutputStream o = new FileOutputStream(apk)) {
            byte[] b = new byte[1 << 16];
            int shown = -1;
            for (int n; (n = in.read(b)) > 0; ) {
                o.write(b, 0, n);
                got += n;
                int pc = total > 0 ? (int) (100 * got / total) : 0;
                if (pc != shown) { shown = pc; say("Downloading Pixel Guide " + version + "… " + pc + "%", 60000); }
            }
        }
        if (size > 0 && apk.length() != size) throw new IOException("the download was incomplete");
        say("Installing Pixel Guide: confirm on this screen", 60000);
        PackageInstaller pi = ctx.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams sp = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        sp.setAppPackageName(CompanionView.GUIDE_PACKAGE);
        int id = pi.createSession(sp);
        try (PackageInstaller.Session s = pi.openSession(id)) {
            try (InputStream in = new FileInputStream(apk); OutputStream o = s.openWrite("base.apk", 0, apk.length())) {
                byte[] b = new byte[1 << 16];
                for (int n; (n = in.read(b)) > 0; ) o.write(b, 0, n);
                s.fsync(o);
            }
            Intent done = new Intent(ctx, Result.class).setAction(ACTION_DONE);
            PendingIntent p = PendingIntent.getBroadcast(ctx, id, done,
                    PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0));
            s.commit(p.getIntentSender());
        }
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestProperty("User-Agent", "FF7Recomp");
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        return c;
    }

    private static String get(String url) throws IOException {
        HttpURLConnection c = open(url);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        if (c.getResponseCode() != 200) throw new IOException("GitHub answered " + c.getResponseCode());
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            for (int n; (n = in.read(b)) > 0; ) o.write(b, 0, n);
            return o.toString("UTF-8");
        }
    }

    /** The installer's answer: its confirmation on the bottom screen, or how it ended. */
    public static class Result extends BroadcastReceiver {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            int st = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) {
                    // through GuideHostActivity on the bottom screen: the panels step aside while
                    // the confirmation is up (it is drawn under them otherwise) and come back
                    // however it ends, cancelled from Android's "unknown apps" step included
                    Intent host = new Intent(ctx, GuideHostActivity.class)
                            .putExtra(GuideHostActivity.EXTRA_CONFIRM, confirm)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
                    ActivityOptions o = ActivityOptions.makeBasic();
                    if (displayId >= 0) o.setLaunchDisplayId(displayId);
                    try { ctx.startActivity(host, o.toBundle()); }
                    catch (RuntimeException e) { Log.w(TAG, "install confirmation: " + e); busy = false; }
                }
                return;
            }
            busy = false;
            GameActivity.guideClosed();
            if (st == PackageInstaller.STATUS_SUCCESS) say("Pixel Guide installed: tap the book to open it", 6000);
            else {
                String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                say(st == PackageInstaller.STATUS_FAILURE_ABORTED ? "Pixel Guide install cancelled"
                        : "Pixel Guide was not installed" + (msg != null ? ": " + msg : ""), 6000);
                Log.w(TAG, "install status " + st + " " + msg);
            }
        }
    }
}
