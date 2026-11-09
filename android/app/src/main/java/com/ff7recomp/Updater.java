package com.ff7recomp;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Updates from the project's GitHub releases: the newest release (pre-releases included, drafts
 * not) whose version is above this APK's, with an .apk asset. Downloaded into the app's cache and
 * installed with PackageInstaller; Android asks the player to confirm (and, the first time, to
 * allow installs from FF7Recomp). An update keeps the game files, mods and saves: it must be signed
 * with the same key as the installed APK (release builds are; a self-built debug APK cannot take
 * a release update).
 */
final class Updater {
    private static final String TAG = "FF7Update";
    static final String REPO = "bhuiyah/ff7-recomp-android";
    private static final String API = "https://api.github.com/repos/" + REPO + "/releases?per_page=10";
    static final String ACTION_DONE = "com.ff7recomp.UPDATE_INSTALLED";

    /** A release newer than this APK. */
    static final class Release {
        String version, tag, name, notes, apkUrl, page;
        long apkSize;
    }

    // ---- shared state for the launcher (one check or download at a time)
    static volatile Release available;       // newer release, or null
    static volatile String status = "";      // what is happening / what went wrong
    static volatile int percent = -1;         // download progress, -1 = none
    static volatile boolean busy;
    static volatile long lastCheck;

    static String installedVersion(Context ctx) {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "0";
        }
    }

    /** -1, 0 or 1: dotted numbers ("v0.1.10" > "0.1.9"); anything after a '-' sorts lower. */
    static int compare(String a, String b) {
        String[] x = norm(a).split("-", 2), y = norm(b).split("-", 2);
        String[] p = x[0].split("\\."), q = y[0].split("\\.");
        for (int i = 0; i < Math.max(p.length, q.length); i++) {
            long m = i < p.length ? num(p[i]) : 0, n = i < q.length ? num(q[i]) : 0;
            if (m != n) return m < n ? -1 : 1;
        }
        if (x.length != y.length) return x.length > y.length ? -1 : 1;   // 1.0-rc < 1.0
        return x.length == 2 ? Integer.signum(x[1].compareTo(y[1])) : 0;
    }

    private static String norm(String v) {
        v = v == null ? "0" : v.trim();
        return v.startsWith("v") || v.startsWith("V") ? v.substring(1) : v;
    }

    private static long num(String s) {
        try { return Long.parseLong(s.replaceAll("[^0-9].*$", "")); } catch (NumberFormatException e) { return 0; }
    }

    /** Ask GitHub (on the caller's thread). Sets `available` and returns it, or null. */
    static Release check(Context ctx) throws IOException {
        lastCheck = System.currentTimeMillis();
        HttpURLConnection c = (HttpURLConnection) new URL(API).openConnection();
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setRequestProperty("User-Agent", "FF7Recomp/" + installedVersion(ctx));
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        int code = c.getResponseCode();
        if (code == 404) throw new IOException("no public releases found");
        if (code == 403 || code == 429) throw new IOException("GitHub asked to wait a while (rate limit)");
        if (code != 200) throw new IOException("GitHub answered " + code);
        String body;
        try (InputStream in = c.getInputStream()) { body = new String(GameBuild.readAll(in), StandardCharsets.UTF_8); }
        String mine = installedVersion(ctx);
        Release best = null;
        try {
            JSONArray rs = new JSONArray(body);
            for (int i = 0; i < rs.length(); i++) {
                JSONObject r = rs.getJSONObject(i);
                if (r.optBoolean("draft")) continue;
                String tag = r.optString("tag_name");
                if (compare(tag, mine) <= 0 || (best != null && compare(tag, best.tag) <= 0)) continue;
                JSONArray as = r.optJSONArray("assets");
                if (as == null) continue;
                for (int k = 0; k < as.length(); k++) {
                    JSONObject a = as.getJSONObject(k);
                    if (!a.optString("name").toLowerCase(java.util.Locale.ROOT).endsWith(".apk")) continue;
                    Release rel = new Release();
                    rel.tag = tag;
                    rel.version = norm(tag);
                    rel.name = r.optString("name", tag);
                    rel.notes = r.optString("body", "");
                    rel.page = r.optString("html_url");
                    rel.apkUrl = a.optString("browser_download_url");
                    rel.apkSize = a.optLong("size");
                    best = rel;
                    break;
                }
            }
        } catch (org.json.JSONException e) {
            throw new IOException("unexpected answer from GitHub");
        }
        available = best;
        return best;
    }

    /** Download the release's APK and hand it to the system installer (caller's thread). */
    static void install(Context ctx, Release r) throws IOException {
        File dir = new File(ctx.getCacheDir(), "update");
        GameBuild.deleteTree(dir);
        dir.mkdirs();
        File apk = new File(dir, "FF7Recomp-" + r.version + ".apk");
        percent = 0;
        status = "Downloading " + r.version;
        HttpURLConnection c = (HttpURLConnection) new URL(r.apkUrl).openConnection();
        c.setRequestProperty("User-Agent", "FF7Recomp/" + installedVersion(ctx));
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        if (c.getResponseCode() != 200) throw new IOException("download failed (" + c.getResponseCode() + ")");
        long total = c.getContentLengthLong() > 0 ? c.getContentLengthLong() : r.apkSize, got = 0;
        try (InputStream in = c.getInputStream(); OutputStream o = new FileOutputStream(apk)) {
            byte[] b = new byte[1 << 16];
            for (int n; (n = in.read(b)) > 0; ) {
                o.write(b, 0, n);
                got += n;
                if (total > 0) percent = (int) (100 * got / total);
            }
        }
        if (r.apkSize > 0 && apk.length() != r.apkSize) throw new IOException("the download was incomplete");
        status = "Installing " + r.version;
        PackageInstaller pi = ctx.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams sp = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        sp.setAppPackageName(ctx.getPackageName());
        if (Build.VERSION.SDK_INT >= 31) sp.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        int id = pi.createSession(sp);
        try (PackageInstaller.Session s = pi.openSession(id)) {
            try (InputStream in = new FileInputStream(apk); OutputStream o = s.openWrite("base.apk", 0, apk.length())) {
                byte[] b = new byte[1 << 16];
                for (int n; (n = in.read(b)) > 0; ) o.write(b, 0, n);
                s.fsync(o);
            }
            Intent done = new Intent(ctx, InstallResult.class).setAction(ACTION_DONE);
            PendingIntent p = PendingIntent.getBroadcast(ctx, id, done,
                    PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0));
            s.commit(p.getIntentSender());
        }
        percent = -1;
    }

    /** The installer's answer: ask the player to confirm, or report how it ended. */
    public static class InstallResult extends BroadcastReceiver {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            int st = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) ctx.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                status = "Waiting for you to confirm the update";
                return;
            }
            busy = false;
            percent = -1;
            if (st == PackageInstaller.STATUS_SUCCESS) {
                status = "Updated";
                available = null;
            } else {
                String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                status = st == PackageInstaller.STATUS_FAILURE_ABORTED ? "Update cancelled"
                        : st == PackageInstaller.STATUS_FAILURE_CONFLICT || st == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE
                          ? "This update is signed differently from the installed app (a self-built APK?): install it by hand"
                          : "Update failed" + (msg != null ? ": " + msg : "");
                Log.w(TAG, "install status " + st + " " + msg);
            }
        }
    }
}
