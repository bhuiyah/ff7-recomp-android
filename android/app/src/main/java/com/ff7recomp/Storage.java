package com.ff7recomp;

import android.content.Context;
import android.os.Environment;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

/**
 * Where the app keeps its files (the game files, mods, downloads, saves, save states): the app's
 * folder in internal storage (Android/data/com.ff7recomp/files), the same folder on an SD card,
 * or a folder the player picks anywhere on the device or the SD card, e.g. /sdcard/FF7Recomp
 * for backups and Syncthing (Settings > Storage, GitHub #61). The first two need no storage
 * permission; a custom folder needs Android's "All files access". The built game (libff7.so)
 * always stays in the app's private internal storage: Android runs no code from shared storage.
 *
 * Every process reads the choice from the launcher's preferences (the game runs in its own
 * process, started after a move).
 */
final class Storage {
    interface Progress { void update(String what); }

    private static final String PREFS = "launcher", KEY = "storage_sd", KEY_CUSTOM = "storage_custom";

    /** Settings > Storage's places. */
    static final int INTERNAL = 0, SD = 1, CUSTOM = 2;

    private Storage() {}

    /** The SD card's app folder, or null when no card is inserted. */
    static File sdRoot(Context c) {
        File[] dirs = c.getExternalFilesDirs(null);
        for (int i = 1; i < dirs.length; i++)
            if (dirs[i] != null && Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState(dirs[i])))
                return dirs[i];
        return null;
    }

    static File internalRoot(Context c) { return c.getExternalFilesDir(null); }

    /** The custom folder the files are (to be) in, or null when they are in the app's own folder. */
    static File customRoot(Context c) {
        String p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CUSTOM, "");
        return p == null || p.isEmpty() ? null : new File(p);
    }

    /** The files are (to be) on the SD card (the app's own folder there). */
    static boolean onSd(Context c) { return customRoot(c) == null && c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false); }

    static int place(Context c) { return customRoot(c) != null ? CUSTOM : onSd(c) ? SD : INTERNAL; }

    /** On the SD card, but the card is not there: the launcher says so. */
    static boolean sdMissing(Context c) { return onSd(c) && sdRoot(c) == null; }

    /** The app may read and write anywhere in shared storage ("All files access"; on Android 10,
     *  the storage permission with legacy storage). */
    static boolean allFilesAccess(Context c) {
        if (android.os.Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        return c.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    /** In a custom folder the app cannot use now (All files access was turned off, or the SD card
     *  it is on was taken out): null when it is fine, else why, for the launcher to say. */
    static String customProblem(Context c) {
        File f = customRoot(c);
        if (f == null) return null;
        if (!allFilesAccess(c)) return "FF7Recomp no longer has \"All files access\", which it needs for " + f.getAbsolutePath();
        if (!f.isDirectory()) return f.getAbsolutePath() + " is not there (is the SD card it is on inserted?)";
        return null;
    }

    /** The folder everything lives in. */
    static File root(Context c) {
        File custom = customRoot(c);
        if (custom != null && customProblem(c) == null) return custom;
        if (custom == null && onSd(c)) {
            File sd = sdRoot(c);
            if (sd != null) return sd;
        }
        return internalRoot(c);
    }

    /**
     * A folder picked with the system's folder picker (ACTION_OPEN_DOCUMENT_TREE) as a path the
     * app can open with All files access, or null for a folder that is not on the device's
     * storage or an SD card (a cloud drive, say).
     */
    static File treeToPath(android.net.Uri tree) {
        if (tree == null || !"com.android.externalstorage.documents".equals(tree.getAuthority())) return null;
        String id = android.provider.DocumentsContract.getTreeDocumentId(tree);
        int colon = id.indexOf(':');
        if (colon < 0) return null;
        String vol = id.substring(0, colon), rel = id.substring(colon + 1);
        File base = vol.equalsIgnoreCase("primary") ? Environment.getExternalStorageDirectory() : new File("/storage", vol);
        return rel.isEmpty() ? base : new File(base, rel);
    }

    /** f is d or inside it. */
    static boolean within(File f, File d) {
        String a = f.getAbsolutePath(), b = d.getAbsolutePath();
        return a.equals(b) || a.startsWith(b.endsWith("/") ? b : b + "/");
    }

    /** The folder already holds FF7Recomp's files (a backup, another device's, a synced copy). */
    static boolean looksLikeOurs(File d) {
        for (String k : new String[] { "game", "mods", "savestates" })
            if (new File(d, k).isDirectory()) return true;
        return false;
    }

    /** The folder has nothing in it but hidden files (.nomedia, Syncthing's .stfolder). */
    static boolean emptyEnough(File d) {
        File[] kids = d.listFiles();
        if (kids != null) for (File k : kids) if (!k.getName().startsWith(".")) return false;
        return true;
    }

    /**
     * Why the files cannot go to this custom folder, or null when they can. The app's own
     * folders are Settings' other places; a folder inside the current one would copy into itself.
     */
    static String customRefusal(Context c, File to) {
        if (to == null) return "Pick a folder on the device's storage or the SD card.";
        File from = root(c);
        File app = internalRoot(c).getParentFile();   // Android/data/com.ff7recomp
        File sd = sdRoot(c);
        if (within(to, app) || (sd != null && within(to, sd.getParentFile())))
            return "That is the app's own folder: choose Internal storage or SD card instead.";
        if (within(to, from) || within(from, to)) return "Pick a folder outside " + from.getAbsolutePath() + ".";
        String a = to.getAbsolutePath();
        if (a.equals(Environment.getExternalStorageDirectory().getAbsolutePath()) || a.matches("/storage/[^/]+/?"))
            return "Pick or create a folder for FF7Recomp, not the top of the storage.";
        return null;
    }

    /** Use the files already in a custom folder (looksLikeOurs), leaving the current ones where they are. */
    static void useCustom(Context c, File to) throws IOException {
        String why = customRefusal(c, to);
        if (why != null) throw new IOException(why);
        if (!to.isDirectory() || !to.canWrite()) throw new IOException("cannot write to " + to);
        noMedia(to);
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, false)
                .putString(KEY_CUSTOM, to.getAbsolutePath()).commit();
    }

    /** Keep the gallery and music apps out of the mods' pictures and music. */
    private static void noMedia(File d) {
        try { new File(d, ".nomedia").createNewFile(); } catch (IOException ignored) {}
    }

    /**
     * Move everything to the SD card (toSd) or back to internal storage, then remember the
     * choice. The source is deleted only after the whole copy succeeded; a failed move leaves it
     * as it was (and removes the partial copy).
     */
    static void move(Context c, boolean toSd, Progress p) throws IOException {
        move(c, toSd ? SD : INTERNAL, null, p);
    }

    /** Move everything to a place (CUSTOM: into the folder `custom`, which must be empty). */
    static void move(Context c, int place, File custom, Progress p) throws IOException {
        File from = root(c), to = place == CUSTOM ? custom : place == SD ? sdRoot(c) : internalRoot(c);
        if (to == null) throw new IOException(place == SD ? "no SD card is inserted" : "no folder was picked");
        if (place == CUSTOM) {
            String why = customRefusal(c, to);
            if (why != null) throw new IOException(why);
            if (!to.isDirectory() && !to.mkdirs()) throw new IOException("cannot create " + to);
            if (!emptyEnough(to)) throw new IOException(to + " is not empty: pick an empty folder, or create a new one");
        }
        if (from.getAbsolutePath().equals(to.getAbsolutePath())) { remember(c, place, to); return; }
        long total = size(from);
        if (to.getUsableSpace() < total + (64L << 20))
            throw new IOException("not enough free space: your files take " + ModDownloader.human(total) + ", "
                    + ModDownloader.human(to.getUsableSpace()) + " free there");
        long[] done = { 0, 0 };
        try {
            copyTree(from, to, total, done, p);
        } catch (IOException e) {
            File[] kids = from.listFiles();                     // undo the partial copy
            if (kids != null) for (File k : kids) ModDownloader.deleteTree(new File(to, k.getName()));
            throw e;
        }
        if (place == CUSTOM) noMedia(to);
        remember(c, place, to);
        p.update("Removing the old copy");
        File[] kids = from.listFiles();
        if (kids != null) for (File k : kids) ModDownloader.deleteTree(k);
    }

    private static void remember(Context c, int place, File custom) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, place == SD)
                .putString(KEY_CUSTOM, place == CUSTOM ? custom.getAbsolutePath() : "").commit();
    }

    static long size(File f) {
        if (f.isFile()) return f.length();
        long n = 0;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) n += size(k);
        return n;
    }

    private static void copyTree(File src, File dst, long total, long[] done, Progress p) throws IOException {
        if (src.isDirectory()) {
            if (!dst.isDirectory() && !dst.mkdirs()) throw new IOException("cannot create " + dst);
            File[] kids = src.listFiles();
            if (kids != null) for (File k : kids) copyTree(k, new File(dst, k.getName()), total, done, p);
            return;
        }
        byte[] buf = new byte[1 << 20];
        try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(dst)) {
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                done[0] += n;
                if (done[0] - done[1] >= (64L << 20)) {
                    done[1] = done[0];
                    p.update(String.format(Locale.US, "Moving your files: %s of %s", ModDownloader.human(done[0]), ModDownloader.human(total)));
                }
            }
        }
        if (dst.length() != src.length()) throw new IOException("copy of " + src.getName() + " is incomplete");
        dst.setLastModified(src.lastModified());
    }
}
