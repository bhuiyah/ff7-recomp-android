package com.ff7recomp;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/**
 * A small test API over adb: read and change the launcher's settings, start the game the way
 * Play does (optionally at a save-state slot), and stop it. Only adb can call it: the receiver
 * requires android.permission.DUMP, which the shell holds and apps cannot get. The answer is the
 * broadcast's result data (JSON), which `am broadcast` prints.
 *
 *   adb shell am broadcast -a com.ff7recomp.test.GET  -n com.ff7recomp/.TestControl
 *   adb shell am broadcast -a com.ff7recomp.test.SET  -n com.ff7recomp/.TestControl --ei fps 3 --es quickboot continue --ez crt_filter false
 *   adb shell am start -n com.ff7recomp/.LauncherActivity --ez test_play true [--es test_start "resume 98"]
 *   adb shell am broadcast -a com.ff7recomp.test.STOP -n com.ff7recomp/.TestControl
 *   adb shell am broadcast -a com.ff7recomp.test.REPORT -n com.ff7recomp/.TestControl [--ez saves true] [--ez state true]
 *     (Report a problem's zip, into files/report-test.zip; the answer names its entries)
 *   adb shell am broadcast -a com.ff7recomp.test.STEAM_DOWNLOAD -n com.ff7recomp/.TestControl --es dir ABS
 *     (the game's files from Steam into that folder, in the background; logcat FF7SteamFiles)
 *   adb shell am broadcast -a com.ff7recomp.test.STEAM_CLOUD -n com.ff7recomp/.TestControl --es op list|sync|delete
 *     [--es dir SAVES --es state FILE] --es folder TESTFOLDER   (sync refuses the real FF7Recomp folder; folder PC: the PC game's saves, for a test save folder; delete refuses both)
 *   adb shell am broadcast -a com.ff7recomp.test.SET -n com.ff7recomp/.TestControl --es storage_custom /sdcard/FF7RecompTest
 *     (use the files in that folder, moving nothing; "" goes back to the app's own folder. GitHub #61)
 *   adb shell am broadcast -a com.ff7recomp.test.SCREEN -n com.ff7recomp/.TestControl --es size 640x480@240
 *     (the launcher as on that screen from its next start; --es size "" turns it off)
 *
 * SET takes typed extras (--ei / --ez / --es); a string is converted to the setting's type
 * (fps 0 Original, 1 Original fixed, 2 30 FPS battles, 3 60 FPS, 4 120 FPS; renderer 0 Vulkan,
 * 1 OpenGL ES; quickboot "" / newgame / continue / resume; ...). Unknown names are refused.
 * Play is an activity start (Android does not let a broadcast receiver start one in the
 * background): the launcher presses Play once it is ready; test_start overrides the Start setting
 * for that launch only ("resume N": save-state slot N, "normal", "continue", "newgame").
 */
public class TestControl extends BroadcastReceiver {
    static final String PREFIX = "com.ff7recomp.test.";
    static final String EXTRA_PLAY = "test_play", EXTRA_START = "test_start";
    /** am start ... --ez test_steam_qr true: open the Steam sign-in (QR) dialog, to check its layout. */
    static final String EXTRA_STEAM_QR = "test_steam_qr";
    /** SCREEN --es size 640x480@240 (empty: off): the launcher laid out as on that screen, in a
     *  corner of this one, from its next start (a small-screen layout check, GitHub #47). */
    static final String TEST_SCREEN = "test_screen";

    // The launcher's settings and their types (LauncherActivity)
    private static final Map<String, Class<?>> TYPES = new HashMap<>();
    static {
        for (String k : new String[] { "fps", "renderer", "aspect", "perf", "speed", "touch_controls", "game_language", "gil", "resolution", "exp", "ap", "rumble" })
            TYPES.put(k, Integer.class);
        for (String k : new String[] { "crt_filter", "show_fps", "no_encounters", "battle_plus", "bottom_screen", "portrait", "split",
                                       "menu_sounds", "quiet_build", "turbo", "analog_controls", "save_anywhere", "bottom_buttons", "autofire", "swap_screens" })
            TYPES.put(k, Boolean.class);
        TYPES.put("quickboot", String.class);
        TYPES.put("touch_layout", String.class);
        // Settings > Storage > Custom folder's path ("" = the app's own folder), switched without moving anything
        TYPES.put("storage_custom", String.class);
    }

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String a = intent.getAction();
        if (a == null || !a.startsWith(PREFIX)) return;
        SharedPreferences prefs = ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE);
        JSONObject out = new JSONObject();
        try {
            switch (a.substring(PREFIX.length())) {
            case "GET":
                break;
            case "SET": {
                Bundle b = intent.getExtras();
                SharedPreferences.Editor e = prefs.edit();
                JSONObject changed = new JSONObject();
                for (String k : b == null ? new java.util.HashSet<String>() : b.keySet()) {
                    Class<?> t = TYPES.get(k);
                    Object v = b.get(k);
                    if (k.startsWith(FfnxOptions.PREFIX) && FfnxOptions.known(k.substring(FfnxOptions.PREFIX.length()))) {
                        String s = v == null ? "" : String.valueOf(v).trim();   // "" puts it back to the default
                        if (s.isEmpty()) e.remove(k); else e.putString(k, s);
                        changed.put(k, s);
                        continue;
                    }
                    if (t == null) { out.put("error", "unknown setting " + k); continue; }
                    if (t == Integer.class) { int i = v instanceof Number ? ((Number) v).intValue() : Integer.parseInt(String.valueOf(v).trim()); e.putInt(k, i); changed.put(k, i); }
                    else if (t == Boolean.class) { boolean z = v instanceof Boolean ? (Boolean) v : Boolean.parseBoolean(String.valueOf(v).trim()); e.putBoolean(k, z); changed.put(k, z); }
                    else { String s = v == null ? "" : String.valueOf(v); if (s.equals("normal")) s = ""; e.putString(k, s); changed.put(k, s); }
                    if (k.equals("speed")) e.putBoolean("turbo", changed.optInt("speed", 1) > 1);
                }
                e.commit();
                out.put("set", changed);
                break;
            }
            case "STOP": {
                int pid = gamePid(ctx);
                if (pid > 0) android.os.Process.killProcess(pid);
                out.put("stopped", pid > 0);
                break;
            }
            case "REPORT": {
                java.io.File zip = Reports.build(ctx, intent.getBooleanExtra("saves", false), intent.getBooleanExtra("state", false));
                java.io.File to = new java.io.File(Storage.root(ctx), "report-test.zip");
                try (java.io.InputStream in = new java.io.FileInputStream(zip); java.io.OutputStream o = new java.io.FileOutputStream(to)) {
                    byte[] buf = new byte[1 << 16];
                    for (int n; (n = in.read(buf)) > 0; ) o.write(buf, 0, n);
                }
                org.json.JSONArray names = new org.json.JSONArray();
                try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(to)) {
                    for (java.util.Enumeration<? extends java.util.zip.ZipEntry> en = z.entries(); en.hasMoreElements(); ) {
                        java.util.zip.ZipEntry ze = en.nextElement();
                        names.put(ze.getName() + " " + ze.getSize());
                    }
                }
                out.put("report", to.getAbsolutePath());
                out.put("entries", names);
                break;
            }
            case "SCREEN": {
                String size = intent.getStringExtra("size");
                if (size == null || !(size.isEmpty() || size.matches("[0-9]+x[0-9]+@[0-9]+"))) throw new IllegalArgumentException("size WxH@DPI or empty");
                prefs.edit().putString(TEST_SCREEN, size).commit();
                out.put("screen", size);
                break;
            }
            case "STEAM_DOWNLOAD": {
                // --es dir ABS: the game's files from Steam into that folder (not the game's own), in the background
                String dir = intent.getStringExtra("dir");
                if (dir == null || dir.isEmpty()) throw new IllegalArgumentException("dir is required");
                SteamLink link = new SteamLink(ctx);
                new Thread(() -> {
                    long t0 = System.currentTimeMillis();
                    try {
                        String r = new SteamFiles(link).downloadGame(new java.io.File(dir),
                                w -> android.util.Log.i("FF7SteamFiles", "progress: " + w), new java.util.concurrent.atomic.AtomicBoolean());
                        android.util.Log.i("FF7SteamFiles", "test download done in " + (System.currentTimeMillis() - t0) / 1000 + " s: " + (r == null ? "ok" : r));
                    } catch (Throwable e) { android.util.Log.e("FF7SteamFiles", "test download failed", e); }
                }, "steam-test-download").start();
                out.put("download", dir);
                break;
            }
            case "STEAM_CLOUD": {
                // --es op list | sync | delete; sync: --es dir SAVES --es folder CLOUDFOLDER --es state FILE; delete: --es folder
                String op = intent.getStringExtra("op"), dir = intent.getStringExtra("dir"), folder = intent.getStringExtra("folder"),
                        st = intent.getStringExtra("state");
                if ("sync".equals(op) && (dir == null || folder == null || st == null || folder.equals(SteamFiles.CLOUD_FOLDER) || folder.isEmpty()))
                    throw new IllegalArgumentException("sync takes dir, state and a test folder");
                SteamLink link = new SteamLink(ctx);
                new Thread(() -> {
                    try {
                        SteamFiles sf = new SteamFiles(link);
                        String r = "sync".equals(op) ? sf.syncSaves(new java.io.File(dir), new java.io.File(st), "PC".equals(folder) ? SteamFiles.PC_FOLDER : folder).toString()
                                : "delete".equals(op) ? "deleted " + sf.deleteCloudFolder(folder) : sf.cloudListing();
                        for (String line : r.split("\n")) android.util.Log.i("FF7SteamFiles", "cloud " + op + ": " + line);
                        android.util.Log.i("FF7SteamFiles", "cloud " + op + " done");
                    } catch (Throwable e) { android.util.Log.e("FF7SteamFiles", "cloud " + op + " failed", e); }
                }, "steam-test-cloud").start();
                out.put("cloud", op);
                break;
            }
            default:
                out.put("error", "unknown action " + a);
            }
            JSONObject all = new JSONObject();
            for (Map.Entry<String, ?> en : prefs.getAll().entrySet())
                if (TYPES.containsKey(en.getKey()) || en.getKey().startsWith(FfnxOptions.PREFIX)) all.put(en.getKey(), en.getValue());
            out.put("settings", all);
            out.put("game_running", gamePid(ctx) > 0);
        } catch (Exception ex) {
            try { out.put("error", ex.toString()); } catch (Exception ignored) { }
        }
        setResultCode(out.has("error") ? 1 : 0);
        setResultData(out.toString());
    }

    /** A launcher setting (not the Steam sign-in or other private state): Report a problem lists these. */
    static boolean isSetting(String key) { return TYPES.containsKey(key); }

    /** The game's process (":game"), or 0 (also the launcher's Quit). */
    static int gamePid(Context ctx) {
        ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        if (am.getRunningAppProcesses() == null) return 0;
        for (ActivityManager.RunningAppProcessInfo p : am.getRunningAppProcesses())
            if (p.processName.endsWith(":game")) return p.pid;
        return 0;
    }
}
