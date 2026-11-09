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
        files = ctx.getExternalFilesDir(null);
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

    /**
     * Import from the folder the player picked: the Steam "FINAL FANTASY VII" folder, its
     * ff7/workingdir, or any folder holding ff7_en(.exe) and data/. Other languages' data
     * folders are skipped. Returns null on success, else what is missing.
     */
    String importGame(Uri tree, Progress p) throws IOException {
        String root = DocumentsContract.getTreeDocumentId(tree);
        Doc exe = path(tree, root, "ff7", "resources", "ff7_1.02", "ff7_en");
        Doc data = path(tree, root, "ff7", "workingdir", "data");
        if (exe == null) exe = path(tree, root, "ff7_en.exe");
        if (exe == null) exe = path(tree, root, "ff7_en");
        if (data == null) data = path(tree, root, "data");
        if (exe == null && data != null) {                      // picked workingdir itself
            exe = path(tree, root, "..", "resources", "ff7_1.02", "ff7_en");
        }
        if (exe == null) return "ff7_en (ff7/resources/ff7_1.02/ff7_en in the Steam folder) was not found";
        if (data == null) return "the data folder (ff7/workingdir/data in the Steam folder) was not found";
        game.mkdirs();
        p.update("Copying ff7_en.exe");
        copyDoc(tree, exe.id, new File(game, "ff7_en.exe"));
        copyTree(tree, data.id, new File(game, "data"), p, "data");
        return null;
    }

    private void copyTree(Uri tree, String id, File dst, Progress p, String rel) throws IOException {
        dst.mkdirs();
        for (Doc d : children(tree, id)) {
            String lower = d.name.toLowerCase(Locale.ROOT);
            if (rel.equals("data") && d.dir && lower.startsWith("lang-") && !lower.equals("lang-en")) continue;
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
    void writeFfnxToml(int fpsLimiter, boolean turbo, int aspectRatio, boolean showFps, List<ModLibrary.Mod> mods) throws IOException {
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
        // 16:9 (FFNx aspect_ratio 2) also uncrops: the Thor's screen is 16:9 top to bottom.
        toml = set(toml, "aspect_ratio", Integer.toString(aspectRatio));
        toml = set(toml, "enable_uncrop", aspectRatio != 0 ? "true" : "false");
        for (int i = mods.size() - 1; i >= 0; i--) {
            ModLibrary.Mod m = mods.get(i);
            if (!m.enabled || m.error != null) continue;
            for (String[] kv : m.ffnx) toml = set(toml, kv[0], tomlValue(kv[1]));
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
    void writeBoosters(int speed, boolean noEncounters, boolean battlePlus) throws IOException {
        String s = "speed=" + speed + "\nencounters=" + (noEncounters ? "off" : "on") + "\nbattle=" + (battlePlus ? "on" : "off") + "\n";
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
