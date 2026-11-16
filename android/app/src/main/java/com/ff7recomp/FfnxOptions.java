package com.ff7recomp;

import android.content.SharedPreferences;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Settings > Sound and Settings > Game options (FF9Recomp parity: its Memoria settings page):
 * FFNx.toml keys a player would otherwise have to edit by hand, which the launcher could not
 * keep (FFNx.toml is written again before every start). Each is kept as "ffnx.KEY" in the
 * launcher's preferences only once it is changed; until then the template's value, or a mod's,
 * stands. A changed one wins over the mods (GameInstall.writeFfnxToml).
 */
final class FfnxOptions {
    static final String PREFIX = "ffnx.";

    static final class Opt {
        final String key, label, help;
        final String[] values, names;   // a choice; null for an on/off switch
        final boolean defOn;            // a switch's value until it is changed (FFNx's template)
        Opt(String key, String label, String help, String[] values, String[] names) {
            this.key = key; this.label = label; this.help = help; this.values = values; this.names = names; this.defOn = false;
        }
        Opt(String key, String label, String help, boolean defOn) {
            this.key = key; this.label = label; this.help = help; this.values = null; this.names = null; this.defOn = defOn;
        }
        boolean isSwitch() { return values == null; }
    }

    private static final String[] VOL = { "-1", "100", "90", "80", "70", "60", "50", "40", "30", "20", "10", "0" };
    private static final String[] VOL_NAMES = { "Default", "100%", "90%", "80%", "70%", "60%", "50%", "40%", "30%", "20%", "10%", "Off" };

    private static Opt volume(String key, String label, String help) { return new Opt(key, label, help, VOL, VOL_NAMES); }

    static final Opt[] SOUND = {
        volume("external_music_volume", "Music", null),
        volume("external_sfx_volume", "Sound effects", null),
        volume("ffmpeg_video_volume", "Movies", null),
        volume("external_voice_volume", "Voices", "With a voice-acting mod"),
        volume("external_ambient_volume", "Ambient sounds", "With an ambient-sound mod"),
        new Opt("ff7_footsteps", "Footsteps", "A footstep sound as you walk in fields", false),
        new Opt("enable_voice_music_fade", "Quieter music while a voice plays", "With a voice-acting mod", false),
        new Opt("enable_voice_auto_text", "Voiced text boxes close by themselves", "With a voice-acting mod: when the line has been spoken", true),
    };

    static final Opt[] GAME = {
        new Opt("external_music_resume", "Music carries on where it left off",
                "Back on the world map (and other known areas) the music resumes instead of starting again", true),
        new Opt("mdef_fix", "Armor counts toward magic defense", "A fix to the original game's formula (FFNx's default)", true),
        new Opt("ff7_field_autosize_text_box", "Text boxes fit their text", "In fields, dialogue boxes are sized to the text", false),
        new Opt("enable_bilinear", "Smooth 3D textures", "Bilinear filtering on models' and menus' textures (the original is sharp pixels)", false),
    };

    private FfnxOptions() {}

    static Opt[] all() {
        Opt[] a = new Opt[SOUND.length + GAME.length];
        System.arraycopy(SOUND, 0, a, 0, SOUND.length);
        System.arraycopy(GAME, 0, a, SOUND.length, GAME.length);
        return a;
    }

    /** The options changed in the launcher: FFNx.toml key -> value. */
    static Map<String, String> chosen(SharedPreferences p) {
        Map<String, String> m = new LinkedHashMap<>();
        for (Opt o : all()) {
            String v = p.getString(PREFIX + o.key, null);
            if (v != null && !v.isEmpty()) m.put(o.key, v);
        }
        return m;
    }

    static boolean known(String key) {
        for (Opt o : all()) if (o.key.equals(key)) return true;
        return false;
    }

    /** A choice's name for its stored value (the first, "Default", when it is not set). */
    static int index(Opt o, String v) {
        if (v != null) for (int i = 0; i < o.values.length; i++) if (o.values[i].equals(v)) return i;
        return 0;
    }
}
