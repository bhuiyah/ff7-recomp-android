package com.ff7recomp;

import android.graphics.Bitmap;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * The party's menu portraits for the bottom screen (CompanionView), cut from the player's own
 * data/menu/menu_us.lgp when the panels open: nothing from the game ships with the app.
 *
 * LGP: a 12-byte signature, a u32 file count, then 27-byte entries (name[20], u32 offset, ...);
 * each file is name[20], u32 size, data. The faces are FF7 .tex files: a 0xEC-byte header
 * (width at 0x3C, height at 0x40, palette size at 0x58), a BGRA palette, then 8-bit pixels.
 * The face fills the top-left 84 x 96 of its 128 x 128 texture.
 */
final class Portraits {
    private static final String TAG = "FF7Portraits";
    /** By savemap character id: Cloud, Barret, Tifa, Aerith, Red XIII, Yuffie, Cait Sith,
     *  Vincent, Cid. */
    static final String[] FILES = { "cloud", "barre", "tifa", "earith", "red", "yufi", "ketc", "bins", "cido" };
    static final int FACE_W = 84, FACE_H = 96;

    private Portraits() {}

    /** The faces by character id (null where one could not be read). Call off the UI thread. */
    static Bitmap[] load(File gameDir) {
        Bitmap[] out = new Bitmap[FILES.length];
        File lgp = new File(gameDir, "data/menu/menu_us.lgp");
        if (!lgp.isFile()) return out;
        try (RandomAccessFile f = new RandomAccessFile(lgp, "r")) {
            ByteBuffer head = read(f, 0, 16);
            int count = head.getInt(12);
            if (count <= 0 || count > 100000) return out;
            ByteBuffer toc = read(f, 16, count * 27);
            for (int i = 0; i < count; i++) {
                String name = cString(toc, i * 27, 20);
                for (int k = 0; k < FILES.length; k++) {
                    if (!name.equalsIgnoreCase(FILES[k] + ".tex")) continue;
                    long off = toc.getInt(i * 27 + 20) & 0xFFFFFFFFL;
                    int size = read(f, off + 20, 4).getInt(0);
                    if (size < 0xEC || size > 4 << 20) continue;
                    out[k] = decode(read(f, off + 24, size));
                }
            }
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "portraits: " + e);
        }
        return out;
    }

    /** The face in a mod's texture: the same part of it, at whatever size it is. */
    static Bitmap face(Bitmap tex) {
        if (tex == null) return null;
        int w = Math.round(tex.getWidth() * (float) FACE_W / 128), h = Math.round(tex.getHeight() * (float) FACE_H / 128);
        return w < 1 || h < 1 ? null : Bitmap.createBitmap(tex, 0, 0, w, h);
    }

    /** A .tex file of an LGP archive, its top-left w x h; null when it is not there. */
    static Bitmap tex(File lgp, String name, int w, int h) {
        try (RandomAccessFile f = new RandomAccessFile(lgp, "r")) {
            int count = read(f, 0, 16).getInt(12);
            if (count <= 0 || count > 100000) return null;
            ByteBuffer toc = read(f, 16, count * 27);
            for (int i = 0; i < count; i++) {
                if (!cString(toc, i * 27, 20).equalsIgnoreCase(name)) continue;
                int off = toc.getInt(i * 27 + 20), size = read(f, off + 20, 4).getInt(0);
                return size < 0xEC || size > 4 << 20 ? null : decode(read(f, off + 24, size), w, h);
            }
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, name + ": " + e);
        }
        return null;
    }

    private static Bitmap decode(ByteBuffer t) { return decode(t, FACE_W, FACE_H); }

    private static Bitmap decode(ByteBuffer t, int maxW, int maxH) {
        int w = t.getInt(0x3C), h = t.getInt(0x40), pal = t.getInt(0x58);
        if (w <= 0 || h <= 0 || w > 1024 || h > 1024 || pal <= 0 || pal > 256) return null;
        int px = 0xEC + pal * 4;
        if (t.capacity() < px + w * h) return null;
        int cw = Math.min(maxW, w), ch = Math.min(maxH, h);
        int[] argb = new int[cw * ch];
        for (int y = 0; y < ch; y++) {
            for (int x = 0; x < cw; x++) {
                int i = t.get(px + y * w + x) & 0xFF;
                if (i >= pal) i = 0;
                int p = 0xEC + i * 4;
                int b = t.get(p) & 0xFF, g = t.get(p + 1) & 0xFF, r = t.get(p + 2) & 0xFF;
                argb[y * cw + x] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }
        return Bitmap.createBitmap(argb, cw, ch, Bitmap.Config.ARGB_8888);
    }

    private static ByteBuffer read(RandomAccessFile f, long off, int n) throws IOException {
        byte[] b = new byte[n];
        f.seek(off);
        f.readFully(b);
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static String cString(ByteBuffer b, int off, int max) {
        int n = 0;
        while (n < max && b.get(off + n) != 0) n++;
        byte[] s = new byte[n];
        for (int i = 0; i < n; i++) s[i] = b.get(off + i);
        return new String(s, StandardCharsets.US_ASCII);
    }
}
