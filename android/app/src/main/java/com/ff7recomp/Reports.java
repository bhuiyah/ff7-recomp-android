package com.ff7recomp;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Report a problem: one zip with what it takes to look into one (the launcher's "Report a
 * problem" page). It holds:
 *   report.txt   the app version, the device (model, chip, Android, RAM), where the files are,
 *                the launcher's settings, how the game last closed (Android's exit records)
 *   logs/        ff7.log (this run and the one before), build.log, game/FFNx.log, game/APP.LOG,
 *                boosters.txt, env.txt, quickboot.txt, companion.txt, game/FFNx.toml, the mod
 *                profile (mods/profile.xml)
 *   java-crash.txt  the last Java crash's backtrace, written by the app as it crashes
 *   tombstone.pb the last native crash's record from Android, when there is one (Android 12+)
 *   anr.txt      the last "not responding" record from Android, when there is one (Android 11+)
 *   saves/       the game's saves (game/save/*.ff7), when asked for
 *   savestates/  the newest save state, when asked for
 * Not in it: the game's files, the mods themselves, the Steam sign-in (its own preferences file).
 * Nothing leaves the device here: the zip goes to Downloads and the share sheet, the user sends it.
 * It reads only the app's own files and Android's records for this app: no system log and no
 * commands run (0.1.59 ran logcat; some phones began calling the app harmful).
 */
final class Reports {
    static final String ISSUES = "https://github.com/bhuiyah/ff7-recomp-android/issues/new";
    private static final long MAX_LOG = 16L << 20;   // the end of a log larger than this

    private Reports() {}

    /** The game's saves (game/save/save00.ff7 ...). */
    static List<File> saves(Context c) {
        List<File> out = new ArrayList<>();
        File[] fs = new File(Storage.root(c), "game/save").listFiles();
        if (fs != null) for (File f : fs) if (f.isFile() && f.getName().toLowerCase(Locale.ROOT).endsWith(".ff7")) out.add(f);
        out.sort((a, b) -> a.getName().compareTo(b.getName()));
        return out;
    }

    /** The newest save state, or null. */
    static File latestState(Context c) {
        File best = null;
        File[] fs = new File(Storage.root(c), "savestates").listFiles();
        if (fs != null) for (File f : fs)
            if (f.isFile() && f.getName().endsWith(".ff7s") && (best == null || f.lastModified() > best.lastModified())) best = f;
        return best;
    }

    /** How the game's process last ended, in a line: "crashed (native) 13 Nov 22:42", or null. */
    static String lastGameExit(Context c) {
        if (Build.VERSION.SDK_INT < 30) return null;
        for (ApplicationExitInfo e : exits(c))
            if (e.getProcessName().endsWith(":game"))
                return reason(e) + ", " + new SimpleDateFormat("d MMM HH:mm", Locale.US).format(new Date(e.getTimestamp()));
        return null;
    }

    /** Builds the zip in the app's cache; returns it. */
    static File build(Context c, boolean withSaves, boolean withState) throws IOException {
        File dir = new File(c.getCacheDir(), "reports");
        File[] old = dir.listFiles();
        if (old != null) for (File f : old) f.delete();   // one report at a time
        dir.mkdirs();
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(new Date());
        File zip = new File(dir, "FF7Recomp-report-" + stamp + ".zip");
        File root = Storage.root(c);
        try (ZipOutputStream z = new ZipOutputStream(new java.io.FileOutputStream(zip))) {
            put(z, "report.txt", summary(c).getBytes(StandardCharsets.UTF_8));
            for (String p : new String[] { "ff7.log", "ff7.prev.log", "build.log", "boosters.txt", "env.txt", "quickboot.txt",
                                           "companion.txt", "mods/profile.xml", "game/FFNx.log", "game/APP.LOG", "game/FFNx.toml" }) {
                File f = new File(root, p);
                if (f.isFile()) putFile(z, "logs/" + p.replace('/', '_'), f, MAX_LOG);
            }
            File jc = new File(c.getFilesDir(), JAVA_CRASH);
            if (jc.isFile()) putFile(z, "java-crash.txt", jc, MAX_LOG);
            byte[] tomb = lastTrace(c, ApplicationExitInfo.REASON_CRASH_NATIVE);
            if (tomb != null) put(z, "tombstone.pb", tomb);
            byte[] anr = lastTrace(c, ApplicationExitInfo.REASON_ANR);
            if (anr != null) put(z, "anr.txt", anr);
            if (withSaves) for (File f : saves(c)) putFile(z, "saves/" + f.getName(), f, Long.MAX_VALUE);
            File st = withState ? latestState(c) : null;
            if (st != null) putFile(z, "savestates/" + st.getName(), st, Long.MAX_VALUE);
        }
        return zip;
    }

    /** A copy in Downloads (no permission needed for the app's own files there); its Uri. */
    static Uri toDownloads(Context c, File zip) throws IOException {
        ContentValues v = new ContentValues();
        v.put(MediaStore.Downloads.DISPLAY_NAME, zip.getName());
        v.put(MediaStore.Downloads.MIME_TYPE, "application/zip");
        v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
        v.put(MediaStore.Downloads.IS_PENDING, 1);
        Uri uri = c.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
        if (uri == null) throw new IOException("Downloads is not available");
        try (OutputStream o = c.getContentResolver().openOutputStream(uri); InputStream in = new FileInputStream(zip)) {
            if (o == null) throw new IOException("Downloads is not available");
            copy(in, o, Long.MAX_VALUE);
        }
        v.clear();
        v.put(MediaStore.Downloads.IS_PENDING, 0);
        c.getContentResolver().update(uri, v, null, null);
        return uri;
    }

    /** A new issue on GitHub with the device lines of the bug template filled in. */
    static Uri issueUrl(Context c) {
        String body = "**What happened**\n<!-- What you did, what you expected, what you saw instead. -->\n\n\n"
                + "**Device**\n- Device and chip: " + device() + "\n- Android version: " + Build.VERSION.RELEASE
                + "\n- FF7Recomp version: " + version(c) + "\n\n"
                + "**Report**\n<!-- Drag the FF7Recomp-report zip from your Downloads folder here. -->\n\n";
        return Uri.parse(ISSUES).buildUpon().appendQueryParameter("labels", "bug").appendQueryParameter("body", body).build();
    }

    // ------------------------------------------------------------------ report.txt

    static String summary(Context c) {
        StringBuilder s = new StringBuilder();
        s.append("FF7Recomp problem report, ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(new Date())).append("\n\n");
        s.append("App: ").append(version(c)).append("\n");
        s.append("Device: ").append(device()).append(" (").append(Build.DEVICE).append(")\n");
        s.append("Android: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        s.append("ABIs: ").append(String.join(", ", Build.SUPPORTED_ABIS)).append("\n");
        ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        s.append("RAM: ").append(mi.totalMem >> 20).append(" MB, ").append(mi.availMem >> 20).append(" MB free; Java heap ")
         .append(am.getMemoryClass()).append("/").append(am.getLargeMemoryClass()).append(" MB\n");
        android.util.DisplayMetrics dm = c.getResources().getDisplayMetrics();
        s.append("Screen: ").append(dm.widthPixels).append("x").append(dm.heightPixels).append(", ").append(dm.densityDpi).append(" dpi\n");
        File root = Storage.root(c);
        s.append("Files: ").append(root.getAbsolutePath()).append(Storage.onSd(c) ? " (SD card)" : "").append(", ")
         .append(root.getUsableSpace() >> 20).append(" MB free\n");
        File[] game = new File(root, "game").listFiles();
        s.append("Game folder: ").append(game == null ? "missing" : game.length + " entries").append("\n\n");

        s.append("Settings:\n");
        SharedPreferences p = c.getSharedPreferences("launcher", Context.MODE_PRIVATE);
        java.util.TreeMap<String, Object> all = new java.util.TreeMap<>(p.getAll());
        for (String k : all.keySet())
            if (TestControl.isSetting(k)) s.append("  ").append(k).append(" = ").append(all.get(k)).append("\n");

        s.append("\nHow this app's processes ended (newest first):\n");
        if (Build.VERSION.SDK_INT >= 30) {
            List<ApplicationExitInfo> ex = exits(c);
            if (ex.isEmpty()) s.append("  none recorded\n");
            SimpleDateFormat f = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US);
            for (ApplicationExitInfo e : ex) {
                s.append("  ").append(f.format(new Date(e.getTimestamp()))).append("  ").append(e.getProcessName())
                 .append(": ").append(reason(e)).append(", status ").append(e.getStatus());
                if (e.getDescription() != null) s.append(" (").append(e.getDescription()).append(")");
                s.append(", ").append(e.getPss() >> 10).append(" MB\n");
            }
        } else s.append("  (Android 11 and later only)\n");
        return s.toString();
    }

    static String version(Context c) {
        try {
            android.content.pm.PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return pi.versionName + " (" + pi.getLongVersionCode() + ")";
        } catch (Exception e) {
            return "?";
        }
    }

    static String device() {
        String d = Build.MANUFACTURER + " " + Build.MODEL;
        if (Build.VERSION.SDK_INT >= 31) d += ", " + Build.SOC_MANUFACTURER + " " + Build.SOC_MODEL;
        else d += ", " + Build.HARDWARE;
        return d;
    }

    private static List<ApplicationExitInfo> exits(Context c) {
        if (Build.VERSION.SDK_INT < 30) return new ArrayList<>();
        ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        try {
            return am.getHistoricalProcessExitReasons(c.getPackageName(), 0, 12);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private static String reason(ApplicationExitInfo e) {
        switch (e.getReason()) {
            case ApplicationExitInfo.REASON_CRASH_NATIVE: return "crashed (native)";
            case ApplicationExitInfo.REASON_CRASH: return "crashed (Java)";
            case ApplicationExitInfo.REASON_ANR: return "stopped responding";
            case ApplicationExitInfo.REASON_LOW_MEMORY: return "closed for low memory";
            case ApplicationExitInfo.REASON_EXIT_SELF: return "closed itself";
            case ApplicationExitInfo.REASON_SIGNALED:   // 9: swiped away, an install, or Android needing the memory
                return e.getStatus() == 9 ? "stopped from outside (signal 9)" : "stopped by signal " + e.getStatus();
            case ApplicationExitInfo.REASON_USER_REQUESTED: return "closed by the user";
            case ApplicationExitInfo.REASON_USER_STOPPED: return "force-stopped";
            case ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE: return "closed for using too much";
            case ApplicationExitInfo.REASON_INITIALIZATION_FAILURE: return "failed to start";
            case ApplicationExitInfo.REASON_PERMISSION_CHANGE: return "closed for a permission change";
            case ApplicationExitInfo.REASON_DEPENDENCY_DIED: return "closed with another process";
            default: return "ended (reason " + e.getReason() + ")";
        }
    }

    /** The newest record of a native crash (its tombstone, Android 12+) or of "not responding"
     *  (Android 11+) that Android keeps for this app, or null. */
    private static byte[] lastTrace(Context c, int reason) {
        if (Build.VERSION.SDK_INT < (reason == ApplicationExitInfo.REASON_ANR ? 30 : 31)) return null;
        for (ApplicationExitInfo e : exits(c)) {
            if (e.getReason() != reason) continue;
            try (InputStream in = e.getTraceInputStream()) {
                if (in == null) return null;
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                copy(in, b, 8L << 20);
                return b.toByteArray();
            } catch (IOException ex) {
                return null;
            }
        }
        return null;
    }

    static final String JAVA_CRASH = "java-crash.txt";

    /** Writes a Java crash's backtrace to files/java-crash.txt (for the report), then lets the
     *  crash go on as before. Each process calls it once (LauncherActivity, GameActivity). */
    static void keepJavaCrashes(Context c) {
        File out = new File(c.getApplicationContext().getFilesDir(), JAVA_CRASH);
        Thread.UncaughtExceptionHandler next = Thread.getDefaultUncaughtExceptionHandler();
        if (next instanceof CrashKeeper) return;
        Thread.setDefaultUncaughtExceptionHandler(new CrashKeeper(out, next));
    }

    private static final class CrashKeeper implements Thread.UncaughtExceptionHandler {
        private final File out;
        private final Thread.UncaughtExceptionHandler next;
        CrashKeeper(File out, Thread.UncaughtExceptionHandler next) { this.out = out; this.next = next; }
        @Override public void uncaughtException(Thread t, Throwable e) {
            try (java.io.PrintWriter w = new java.io.PrintWriter(new java.io.FileOutputStream(out))) {
                w.println(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(new Date())
                        + "  " + android.app.Application.getProcessName() + ", thread " + t.getName());
                e.printStackTrace(w);
            } catch (Throwable ignored) {
            }
            if (next != null) next.uncaughtException(t, e);
        }
    }

    // ------------------------------------------------------------------ zip

    private static void put(ZipOutputStream z, String name, byte[] data) throws IOException {
        z.putNextEntry(new ZipEntry(name));
        z.write(data);
        z.closeEntry();
    }

    /** A file, or its last `max` bytes. */
    private static void putFile(ZipOutputStream z, String name, File f, long max) throws IOException {
        ZipEntry e = new ZipEntry(name);
        e.setTime(f.lastModified());
        z.putNextEntry(e);
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
            long len = r.length();
            if (len > max) {
                r.seek(len - max);
                z.write(("[... the first " + (len - max) + " bytes left out ...]\n").getBytes(StandardCharsets.UTF_8));
            }
            byte[] buf = new byte[1 << 16];
            for (int n; (n = r.read(buf)) > 0; ) z.write(buf, 0, n);
        }
        z.closeEntry();
    }

    private static void copy(InputStream in, OutputStream o, long max) throws IOException {
        byte[] buf = new byte[1 << 16];
        long total = 0;
        for (int n; total < max && (n = in.read(buf)) > 0; total += n) o.write(buf, 0, n);
    }
}
