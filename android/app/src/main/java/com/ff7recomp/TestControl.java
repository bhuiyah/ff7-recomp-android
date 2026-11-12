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

    // The launcher's settings and their types (LauncherActivity)
    private static final Map<String, Class<?>> TYPES = new HashMap<>();
    static {
        for (String k : new String[] { "fps", "renderer", "aspect", "perf", "speed", "touch_controls", "game_language" })
            TYPES.put(k, Integer.class);
        for (String k : new String[] { "crt_filter", "show_fps", "no_encounters", "battle_plus", "bottom_screen", "portrait",
                                       "menu_sounds", "quiet_build", "turbo", "analog_controls", "save_anywhere" })
            TYPES.put(k, Boolean.class);
        TYPES.put("quickboot", String.class);
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
            default:
                out.put("error", "unknown action " + a);
            }
            JSONObject all = new JSONObject();
            for (Map.Entry<String, ?> en : prefs.getAll().entrySet())
                if (TYPES.containsKey(en.getKey())) all.put(en.getKey(), en.getValue());
            out.put("settings", all);
            out.put("game_running", gamePid(ctx) > 0);
        } catch (Exception ex) {
            try { out.put("error", ex.toString()); } catch (Exception ignored) { }
        }
        setResultCode(out.has("error") ? 1 : 0);
        setResultData(out.toString());
    }

    /** The game's process (":game"), or 0. */
    private static int gamePid(Context ctx) {
        ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        if (am.getRunningAppProcesses() == null) return 0;
        for (ActivityManager.RunningAppProcessInfo p : am.getRunningAppProcesses())
            if (p.processName.endsWith(":game")) return p.pid;
        return 0;
    }
}
