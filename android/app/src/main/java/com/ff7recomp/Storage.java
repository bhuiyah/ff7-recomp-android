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
 * folder in internal storage (Android/data/com.ff7recomp/files) or the same folder on an SD
 * card (Settings > Storage). Neither needs a storage permission. The built game (libff7.so)
 * always stays in the app's private internal storage: Android runs no code from SD cards.
 *
 * Every process reads the choice from the launcher's preferences (the game runs in its own
 * process, started after a move).
 */
final class Storage {
    interface Progress { void update(String what); }

    private static final String PREFS = "launcher", KEY = "storage_sd";

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

    /** The files are (to be) on the SD card. */
    static boolean onSd(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false); }

    /** On the SD card, but the card is not there: the launcher says so. */
    static boolean sdMissing(Context c) { return onSd(c) && sdRoot(c) == null; }

    /** The folder everything lives in. */
    static File root(Context c) {
        if (onSd(c)) {
            File sd = sdRoot(c);
            if (sd != null) return sd;
        }
        return internalRoot(c);
    }

    /**
     * Move everything to the SD card (toSd) or back to internal storage, then remember the
     * choice. The source is deleted only after the whole copy succeeded; a failed move leaves it
     * as it was (and removes the partial copy).
     */
    static void move(Context c, boolean toSd, Progress p) throws IOException {
        File from = root(c), to = toSd ? sdRoot(c) : internalRoot(c);
        if (to == null) throw new IOException("no SD card is inserted");
        if (from.getAbsolutePath().equals(to.getAbsolutePath())) { remember(c, toSd); return; }
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
        remember(c, toSd);
        p.update("Removing the old copy");
        File[] kids = from.listFiles();
        if (kids != null) for (File k : kids) ModDownloader.deleteTree(k);
    }

    private static void remember(Context c, boolean sd) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, sd).commit();
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
