package com.ff7recomp;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.AssetManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The game's files in app storage (files/game): the 2026 Steam release's ff7_en (as
 * ff7_en.exe) and its data folder, imported once from a folder the player picks; FFNx's
 * run-time files from the APK; FFNx.toml written from the launcher's settings.
 */
final class GameInstall {
    interface Progress { void update(String what); }

    final Context ctx;
    final File files, game;

    GameInstall(Context ctx) {
        this.ctx = ctx;
        files = Storage.root(ctx);   // internal storage or the SD card (Settings > Storage)
        game = new File(files, "game");
    }

    boolean hasGame() {
        return new File(game, "ff7_en.exe").isFile() && new File(game, "data/field").isDirectory();
    }

    // ---------------------------------------------------------------- import from a folder
    /** A document in the picked tree. */
    private static final class Doc { String id, name; boolean dir; }

    private List<Doc> children(Uri tree, String parentId) {
        List<Doc> out = new ArrayList<>();
        Uri u = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId);
        String[] cols = { DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                          DocumentsContract.Document.COLUMN_MIME_TYPE };
        try (Cursor c = ctx.getContentResolver().query(u, cols, null, null, null)) {
            while (c != null && c.moveToNext()) {
                Doc d = new Doc();
                d.id = c.getString(0);
                d.name = c.getString(1);
                d.dir = DocumentsContract.Document.MIME_TYPE_DIR.equals(c.getString(2));
                out.add(d);
            }
        }
        return out;
    }

    private Doc child(Uri tree, String parentId, String name) {
        for (Doc d : children(tree, parentId)) if (d.name.equalsIgnoreCase(name)) return d;
        return null;
    }

    private Doc path(Uri tree, String rootId, String... names) {
        String id = rootId;
        Doc d = null;
        for (String n : names) {
            d = child(tree, id, n);
            if (d == null) return null;
            id = d.id;
        }
        return d;
    }

    /** What to pick, said when the exe found is not the one the game is built from. */
    static final String WHICH_FOLDER = "FF7Recomp needs FINAL FANTASY VII as sold today (Steam), unpatched: pick "
            + "the game's main folder, \"FINAL FANTASY VII Steam Edition\" on Steam (the one that holds \"ff7\"). No "
            + "patch or older version is needed; patched game files and the 2013 release are not supported yet";

    /**
     * Import from the folder the player picked: the Steam "FINAL FANTASY VII" folder, its ff7
     * folder, or any folder holding the 1.02 ff7_en(.exe) and data/. The exe is checked before
     * anything is copied. The Japanese data folder is skipped. Returns null on success,
     * else what is wrong.
     */
    String importGame(Uri tree, Progress p) throws IOException {
        String root = DocumentsContract.getTreeDocumentId(tree);
        // the Steam release keeps the original 1.02 exe in ff7/resources/ff7_1.02; its
        // ff7/workingdir/ff7_en.exe is the re-release's own exe
        // Steam's places first, then a search of the picked folder (other stores, such as GOG,
        // may lay the same files out differently): every ff7_en(.exe) is checked by its SHA-1.
        List<Doc> exes = new ArrayList<>();
        List<String> exeWhere = new ArrayList<>();
        for (String[] at : new String[][] { { "ff7", "resources", "ff7_1.02", "ff7_en" }, { "resources", "ff7_1.02", "ff7_en" },
                                            { "ff7_en.exe" }, { "ff7_en" } }) {
            Doc d = path(tree, root, at);
            if (d != null) { exes.add(d); exeWhere.add(String.join("/", at)); }
        }
        List<Doc> datas = new ArrayList<>();
        for (String[] at : new String[][] { { "ff7", "workingdir", "data" }, { "workingdir", "data" }, { "data" } }) {
            Doc d = path(tree, root, at);
            if (d != null) datas.add(d);
        }
        p.update("Looking for the game files");
        scan(tree, root, "", 0, exes, exeWhere, datas);
        if (exes.isEmpty()) return "no ff7_en game file was found in this folder (Steam keeps it in ff7/resources/ff7_1.02). " + WHICH_FOLDER;
        if (datas.isEmpty()) return "the game's data folder (with field, menu, ...) was not found in this folder. " + WHICH_FOLDER;
        Doc data = datas.get(0);
        game.mkdirs();
        p.update("Checking ff7_en");
        File tmp = new File(game, "ff7_en.exe.new");
        StringBuilder seen = new StringBuilder();
        boolean ok = false;
        java.util.Set<String> tried = new java.util.HashSet<>();
        for (int i = 0; i < exes.size() && !ok; i++) {
            if (!tried.add(exes.get(i).id)) continue;
            copyDoc(tree, exes.get(i).id, tmp);
            String sha = GameBuild.sha1(tmp);
            ok = GameBuild.EXE_SHA1.equals(sha);
            if (!ok) seen.append(seen.length() > 0 ? ", " : "").append(exeWhere.get(i)).append(" (SHA-1 ").append(sha, 0, 12).append("…)");
        }
        if (!ok) {
            tmp.delete();
            return "none of the game files found here is the one the game is built from: " + seen + ". " + WHICH_FOLDER
                    + " (If you have the game from another store, please report these names and numbers.)";
        }
        File exeOut = new File(game, "ff7_en.exe");
        exeOut.delete();
        if (!tmp.renameTo(exeOut)) throw new IOException("cannot write " + exeOut);
        copyTree(tree, data.id, new File(game, "data"), p, "data");
        return null;
    }

    /** Folders the search skips: the game's own data (searched as a whole), and big unrelated ones. */
    private static final java.util.Set<String> SKIP = new java.util.HashSet<>(java.util.Arrays.asList(
            "data", "movies", "music", "music_ogg", "shaders", "mods", "direct", "textures", "voice", "sfx", "ambient",
            "redist", "__support", "_commonredist", "lang-en", "lang-fr", "lang-de", "lang-es", "lang-ja"));

    /**
     * Search the picked folder, 5 levels deep, for ff7_en / ff7_en.exe files and the game's data
     * folder (a "data" folder holding "field" or "lang-en"), appended after the known places.
     */
    private void scan(Uri tree, String id, String rel, int depth, List<Doc> exes, List<String> exeWhere, List<Doc> datas) {
        if (depth > 5) return;
        for (Doc d : children(tree, id)) {
            String lower = d.name.toLowerCase(Locale.ROOT);
            String here = rel.isEmpty() ? d.name : rel + "/" + d.name;
            if (!d.dir) {
                if (lower.equals("ff7_en") || lower.equals("ff7_en.exe")) { exes.add(d); exeWhere.add(here); }
                continue;
            }
            if (lower.equals("data")) {
                for (Doc k : children(tree, d.id)) {
                    String kl = k.name.toLowerCase(Locale.ROOT);
                    if (k.dir && (kl.equals("field") || kl.equals("lang-en"))) { datas.add(d); break; }
                }
                continue;
            }
            if (SKIP.contains(lower)) continue;
            scan(tree, d.id, here, depth + 1, exes, exeWhere, datas);
        }
    }

    private void copyTree(Uri tree, String id, File dst, Progress p, String rel) throws IOException {
        dst.mkdirs();
        for (Doc d : children(tree, id)) {
            String lower = d.name.toLowerCase(Locale.ROOT);
            // English, French, German and Spanish (the Japanese edition is another exe)
            if (rel.equals("data") && d.dir && lower.startsWith("lang-")
                    && !java.util.Arrays.asList("lang-en", "lang-fr", "lang-de", "lang-es").contains(lower)) continue;
            File out = new File(dst, d.name);
            if (d.dir) copyTree(tree, d.id, out, p, rel + "/" + d.name);
            else {
                p.update("Copying " + rel + "/" + d.name);
                copyDoc(tree, d.id, out);
            }
        }
    }

    private void copyDoc(Uri tree, String id, File dst) throws IOException {
        Uri u = DocumentsContract.buildDocumentUriUsingTree(tree, id);
        try (InputStream in = ctx.getContentResolver().openInputStream(u)) {
            if (in == null) throw new IOException("cannot read " + id);
            copy(in, dst);
        }
    }

    /** Copy a picked file (a mod archive) into dir, keeping its display name. */
    File importFile(Uri uri, File dir) throws IOException {
        String name = "mod.iro";
        ContentResolver cr = ctx.getContentResolver();
        try (Cursor c = cr.query(uri, new String[] { android.provider.OpenableColumns.DISPLAY_NAME }, null, null, null)) {
            if (c != null && c.moveToFirst() && c.getString(0) != null) name = c.getString(0);
        }
        File out = new File(dir, new File(name).getName());
        try (InputStream in = cr.openInputStream(uri)) {
            if (in == null) throw new IOException("cannot read " + uri);
            copy(in, out);
        }
        return out;
    }

    /** Copy a picked folder (a mod folder) into dir. */
    File importFolder(Uri tree, File dir, Progress p) throws IOException {
        String root = DocumentsContract.getTreeDocumentId(tree);
        String name = "mod";
        try (Cursor c = ctx.getContentResolver().query(DocumentsContract.buildDocumentUriUsingTree(tree, root),
                new String[] { DocumentsContract.Document.COLUMN_DISPLAY_NAME }, null, null, null)) {
            if (c != null && c.moveToFirst() && c.getString(0) != null) name = c.getString(0);
        }
        File out = new File(dir, name);
        copyTree(tree, root, out, p, name);
        return out;
    }

    private static void copy(InputStream in, File dst) throws IOException {
        File tmp = new File(dst.getPath() + ".part");
        dst.getParentFile().mkdirs();
        try (OutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[1 << 20];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        if (dst.exists() && !dst.delete()) throw new IOException("cannot replace " + dst);
        if (!tmp.renameTo(dst)) throw new IOException("cannot write " + dst);
    }

    // ------------------------------------------------------------ FFNx run-time files
    /** FFNx's shaders and data tables from the APK (assets/ffnx), once per app version. */
    void installRuntime() throws IOException {
        long version;
        try {
            version = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).lastUpdateTime;
        } catch (Exception e) {
            version = 0;
        }
        File marker = new File(game, ".ffnx-runtime");
        String want = Long.toString(version);
        if (marker.isFile() && want.equals(new String(readAll(new java.io.FileInputStream(marker)), StandardCharsets.UTF_8))) return;
        game.mkdirs();
        copyAssets(ctx.getAssets(), "ffnx", game);
        // 7th Heaven's variable aliases for mods' run-time conditions, next to the profile.
        File mods = new File(files, "mods");
        mods.mkdirs();
        try (InputStream in = ctx.getAssets().open("modsdata/7thHeaven.var")) { copy(in, new File(mods, "7thHeaven.var")); }
        try (OutputStream o = new FileOutputStream(marker)) { o.write(want.getBytes(StandardCharsets.UTF_8)); }
    }

    private static void copyAssets(AssetManager am, String path, File dst) throws IOException {
        String[] kids = am.list(path);
        if (kids == null || kids.length == 0) {                 // a file
            if (path.endsWith("FFNx.toml")) {                    // the template; the real one is written by writeFfnxToml
                try (InputStream in = am.open(path)) { copy(in, new File(dst.getParentFile(), "FFNx.template.toml")); }
                return;
            }
            try (InputStream in = am.open(path)) { copy(in, dst); }
            return;
        }
        dst.mkdirs();
        for (String k : kids) copyAssets(am, path + "/" + k, new File(dst, k));
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try (InputStream i = in) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            while ((n = i.read(buf)) > 0) b.write(buf, 0, n);
            return b.toByteArray();
        }
    }

    /**
     * FFNx.toml from FFNx's own defaults (the template in the APK) with the Android settings,
     * the launcher's choices, then each enabled mod's FFNxConfig (later mods first, so the
     * highest-priority mod's values win, as files do).
     */
    /** FFNx's Steam achievement code on (signed in to Steam: SteamLink.java). */
    boolean steamAchievements;

    /** The game's languages: FFNx's game_language, the launcher's name, the data/lang-* folder. */
    static final String[][] LANGUAGES = {
        { "1", "English", "en" }, { "2", "Français", "fr" }, { "3", "Deutsch", "de" }, { "4", "Español", "es" },
    };

    /** FFNx game_language (1 English, 2 French, 3 German, 4 Spanish). */
    int gameLanguage = 1;

    /** The data/lang-xx code of a game_language (en when unknown). */
    static String languageCode(int language) {
        for (String[] l : LANGUAGES) if (Integer.parseInt(l[0]) == language) return l[2];
        return "en";
    }

    /** Whether the imported game files hold that language's data folder (data/lang-xx). */
    boolean hasLanguage(int language) {
        for (String[] l : LANGUAGES)
            if (Integer.parseInt(l[0]) == language) return new File(game, "data/lang-" + l[2]).isDirectory();
        return false;
    }

    void writeFfnxToml(int fpsLimiter, boolean turbo, int aspectRatio, boolean showFps, List<ModLibrary.Mod> mods, int scaleCap) throws IOException {
        File template = new File(game, "FFNx.template.toml");
        String toml = new String(readAll(new java.io.FileInputStream(template)), StandardCharsets.UTF_8);
        toml = set(toml, "renderer_backend", "5");
        toml = set(toml, "fullscreen", "true");
        // 2x speed: FFNx's speedhack at 2x from the start (L3 then L2/R2 toggles it). With 60 or 120
        // FPS chosen the game keeps its 60 FPS timing, which at 2x is shown at 120 FPS.
        if (turbo && fpsLimiter > 3) fpsLimiter = 3;
        toml = set(toml, "ff7_fps_limiter", Integer.toString(fpsLimiter));
        toml = set(toml, "speedhack_min", turbo ? "2.0" : "1.0");
        toml = set(toml, "show_fps", showFps ? "true" : "false");
        // FFNx's version and renderer lines: debug text drawn every frame, not worth the work
        toml = set(toml, "show_version", "false");
        toml = set(toml, "show_renderer_backend", "false");
        toml = set(toml, "enable_steam_achievements", steamAchievements ? "true" : "false");
        toml = set(toml, "trace_achievement", steamAchievements ? "true" : "false");
        // the re-release's English exe in French, German or Spanish (FFNx's multilanguage patches)
        toml = set(toml, "game_language", Integer.toString(hasLanguage(gameLanguage) ? gameLanguage : 1));
        // 16:9 (FFNx aspect_ratio 2) also uncrops: the Thor's screen is 16:9 top to bottom.
        toml = set(toml, "aspect_ratio", Integer.toString(aspectRatio));
        toml = set(toml, "enable_uncrop", aspectRatio != 0 ? "true" : "false");
        for (int i = mods.size() - 1; i >= 0; i--) {
            ModLibrary.Mod m = mods.get(i);
            if (!m.enabled || m.error != null) continue;
            for (String[] kv : m.ffnx)
                if (m.optionsMatch(kv.length > 2 ? kv[2] : null)) toml = set(toml, kv[0], tomlValue(kv[1]));
        }
        // Performance: the internal resolution at most scaleCap times 640x480 (mods ask for 4x,
        // which on a 1080p screen renders 2.4 times the pixels shown; 0 = as the mods set it)
        if (scaleCap > 0) {
            java.util.regex.Matcher sm = java.util.regex.Pattern.compile("(?m)^internal_resolution_scale\\s*=\\s*(\\d+)").matcher(toml);
            int cur = sm.find() ? Integer.parseInt(sm.group(1)) : 0;
            if (cur == 0 || cur > scaleCap) toml = set(toml, "internal_resolution_scale", Integer.toString(scaleCap));
        }
        File out = new File(game, "FFNx.toml");
        try (OutputStream o = new FileOutputStream(out)) { o.write(toml.getBytes(StandardCharsets.UTF_8)); }
    }

    /** files/companion.txt for src/ffnx_port/companion_render.cpp: the bottom screen's battle windows. */
    void writeCompanion(String battleWindows) throws IOException {
        String s = "battlewindows=" + battleWindows + "\n";
        try (OutputStream o = new FileOutputStream(new File(files, "companion.txt"))) { o.write(s.getBytes(StandardCharsets.UTF_8)); }
    }

    /** files/boosters.txt for src/ffnx_port/boosters.cpp: the game boosters a game starts with. */
    void writeBoosters(int speed, boolean noEncounters, boolean battlePlus, boolean frameSkip) throws IOException {
        String s = "speed=" + speed + "\nencounters=" + (noEncounters ? "off" : "on") + "\nbattle=" + (battlePlus ? "on" : "off")
                + "\nframeskip=" + (frameSkip ? "on" : "off") + "\n";
        try (OutputStream o = new FileOutputStream(new File(files, "boosters.txt"))) { o.write(s.getBytes(StandardCharsets.UTF_8)); }
    }

    /** files/quickboot.txt for src/ffnx_port/quickboot.cpp: "newgame", "continue", or none. */
    void writeQuickboot(String mode) throws IOException {
        File f = new File(files, "quickboot.txt");
        if (mode == null || mode.isEmpty()) { if (f.exists() && !f.delete()) throw new IOException("cannot remove " + f); return; }
        try (OutputStream o = new FileOutputStream(f)) { o.write(mode.getBytes(StandardCharsets.UTF_8)); }
    }

    private static final Pattern NUMBER = Pattern.compile("-?\\d+(\\.\\d+)?");

    private static String tomlValue(String v) {
        String t = v.trim();
        if (NUMBER.matcher(t).matches() || t.equalsIgnoreCase("true") || t.equalsIgnoreCase("false")) return t.toLowerCase(Locale.ROOT);
        if (t.startsWith("[") || t.startsWith("\"")) return t;
        if (t.contains(",")) {                                   // a list from <key><item/>...</key>
            StringBuilder b = new StringBuilder("[");
            for (String s : t.split(",")) b.append(b.length() > 1 ? ", " : "").append(tomlValue(s));
            return b.append("]").toString();
        }
        return "\"" + t.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** Replace `key = ...` (uncommented, at line start) or append it. */
    private static String set(String toml, String key, String value) {
        Pattern p = Pattern.compile("(?m)^" + Pattern.quote(key) + "\\s*=.*$");
        Matcher m = p.matcher(toml);
        if (m.find()) return m.replaceFirst(Matcher.quoteReplacement(key + " = " + value));
        return toml + "\n" + key + " = " + value + "\n";
    }
}
