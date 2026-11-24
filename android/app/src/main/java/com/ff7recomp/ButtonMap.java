package com.ff7recomp;

import android.content.SharedPreferences;

/** Settings > Controller: which button of the pad works as which (FF8Recomp #7). Kept as a permutation,
 *  so no button of the game's can be lost. */
final class ButtonMap {
    static final String[] NAMES = { "A", "B", "X", "Y", "L1", "R1", "L2", "R2", "Select", "Start" };
    private static final int L2 = 6, R2 = 7;
    /** GameActivity.xinputBit's bits; 0: a trigger. */
    private static final int[] BITS = { 0x2000, 0x1000, 0x4000, 0x8000, 0x0100, 0x0200, 0, 0, 0x0020, 0x0010 };
    private static final int MAPPED = 0x2000 | 0x1000 | 0x4000 | 0x8000 | 0x0100 | 0x0200 | 0x0020 | 0x0010;

    static int[] read(SharedPreferences prefs) {
        int[] map = new int[NAMES.length];
        for (int i = 0; i < map.length; i++) map[i] = i;
        String[] s = prefs.getString("button_map", "").split(",");
        if (s.length != map.length) return map;
        int[] m = new int[map.length];
        int seen = 0;
        try {
            for (int i = 0; i < m.length; i++) {
                m[i] = Integer.parseInt(s[i]);
                if (m[i] < 0 || m[i] >= m.length) return map;
                seen |= 1 << m[i];
            }
        } catch (NumberFormatException e) { return map; }
        return seen == (1 << map.length) - 1 ? m : map;
    }

    static void choose(SharedPreferences prefs, int button, int as) {
        int[] m = read(prefs);
        for (int i = 0; i < m.length; i++) if (m[i] == as) m[i] = m[button];
        m[button] = as;
        StringBuilder s = new StringBuilder();
        for (int v : m) s.append(s.length() > 0 ? "," : "").append(v);
        prefs.edit().putString("button_map", s.toString()).apply();
    }

    static void reset(SharedPreferences prefs) { prefs.edit().remove("button_map").apply(); }

    /** The pad's buttons and triggers as the game gets them: { buttons, lt, rt }. */
    static int[] apply(int[] map, int buttons, int lt, int rt) {
        int[] out = { buttons & ~MAPPED, 0, 0 };
        for (int i = 0; i < map.length; i++) {
            int v = i == L2 ? lt : i == R2 ? rt : (buttons & BITS[i]) != 0 ? 255 : 0;
            if (v == 0) continue;
            if (map[i] == L2) out[1] = Math.max(out[1], v);
            else if (map[i] == R2) out[2] = Math.max(out[2], v);
            else if (v > 60) out[0] |= BITS[map[i]];
        }
        return out;
    }
}
