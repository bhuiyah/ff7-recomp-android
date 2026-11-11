package com.ff7recomp;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;

/**
 * The world map picture for the bottom screen's map panel (CompanionView), drawn once from the
 * player's own game files and cached: data/wm/wm0.map holds the world as 9 x 7 blocks of
 * 4 x 4 LZS-compressed meshes; each triangle carries a walkmap type (grass, sea, mountain...).
 * Seen from above and coloured by that type, the triangles make the map. Nothing from the game
 * ships with the app; the picture is made on the device.
 *
 * World coordinates: x 0..WORLD_W east, z 0..WORLD_H south (a mesh is 8192 units, a block 4).
 */
final class WorldMap {
    private static final String TAG = "FF7WorldMap";
    static final int WORLD_W = 9 * 4 * 8192, WORLD_H = 7 * 4 * 8192;
    private static final int PX = 16;                           // pixels per mesh
    private static final int W = 9 * 4 * PX, H = 7 * 4 * PX;    // 576 x 448
    private static final int BLOCK = 0xB800;
    private static final int VERSION = 1;                       // bump to redraw cached maps

    private static volatile Bitmap cached;

    private WorldMap() {}

    /** The map, drawn on first use (a second or two) and kept in files/worldmap-<v>.png. Null
     *  when the game files have no world map. Call off the UI thread. */
    static Bitmap get(File filesDir, File gameDir) {
        Bitmap b = cached;
        if (b != null) return b;
        synchronized (WorldMap.class) {
            if (cached != null) return cached;
            File png = new File(filesDir, "worldmap-" + VERSION + ".png");
            if (png.isFile()) cached = BitmapFactory.decodeFile(png.getAbsolutePath());
            if (cached == null) {
                File map = new File(gameDir, "data/wm/wm0.map");
                if (!map.isFile()) return null;
                try {
                    long t0 = System.currentTimeMillis();
                    cached = draw(map);
                    try (FileOutputStream o = new FileOutputStream(png)) {
                        cached.compress(Bitmap.CompressFormat.PNG, 100, o);
                    }
                    Log.i(TAG, "world map drawn in " + (System.currentTimeMillis() - t0) + " ms");
                } catch (Exception e) {
                    Log.w(TAG, "world map: " + e);
                    cached = null;
                }
            }
            return cached;
        }
    }

    // walkmap type -> colour
    private static int colour(int type) {
        switch (type) {
            case 0: return 0xFF588C3C;   // grass
            case 1: return 0xFF2E602C;   // forest
            case 2: return 0xFF786854;   // mountain
            case 3: case 26: return 0xFF204078;   // sea
            case 4: return 0xFF466E96;   // river crossing
            case 5: return 0xFF3C6EAA;   // river
            case 6: return 0xFF325AA0;   // water
            case 7: return 0xFF50643C;   // swamp
            case 8: return 0xFFC8B478;   // desert
            case 9: return 0xFF968264;   // wasteland
            case 10: return 0xFFE6EBF0;  // snow
            case 11: return 0xFF6E966E;  // riverside
            case 12: return 0xFF645A50;  // cliff
            case 16: return 0xFF6E7850;  // hillside
            case 17: return 0xFFD2C896;  // beach
            case 18: return 0xFF284678;  // sub pen
            case 19: return 0xFFAA7850;  // canyon
            case 20: return 0xFF827864;  // mountain pass
            case 22: return 0xFF78AADC;  // waterfall
            case 24: return 0xFFD2B46E;  // Gold Saucer desert
            case 25: return 0xFF286E32;  // jungle
            case 28: return 0xFFBEAA78;  // desert edge
            default: return 0xFF8C786E;  // bridges, towns' ground, the crater...
        }
    }

    private static Bitmap draw(File mapFile) throws Exception {
        int[] px = new int[W * H];
        java.util.Arrays.fill(px, 0xFF204078);
        byte[] blk = new byte[BLOCK];
        try (RandomAccessFile f = new RandomAccessFile(mapFile, "r")) {
            for (int b = 0; b < 63; b++) {
                f.seek((long) b * BLOCK);
                f.readFully(blk);
                int bx = b % 9, bz = b / 9;
                for (int m = 0; m < 16; m++) {
                    int off = le32(blk, 4 * m), size = le32(blk, off);
                    byte[] mesh = lzs(blk, off + 4, Math.min(size, BLOCK - off - 4));
                    drawMesh(px, mesh, (bx * 4 + m % 4) * PX, (bz * 4 + m / 4) * PX);
                }
            }
        }
        return Bitmap.createBitmap(px, W, H, Bitmap.Config.ARGB_8888);
    }

    private static void drawMesh(int[] px, byte[] m, int ox, int oz) {
        if (m.length < 4) return;
        int nt = le16(m, 0), nv = le16(m, 2), vo = 4 + nt * 12;
        if (vo + nv * 8 > m.length) return;
        float s = PX / 8192f;
        for (int t = 0; t < nt; t++) {
            int p = 4 + 12 * t;
            int a = m[p] & 0xFF, b = m[p + 1] & 0xFF, c = m[p + 2] & 0xFF, type = m[p + 3] & 31;
            if (a >= nv || b >= nv || c >= nv) continue;
            fill(px, colour(type),
                 ox + s16(m, vo + 8 * a) * s, oz + s16(m, vo + 8 * a + 4) * s,
                 ox + s16(m, vo + 8 * b) * s, oz + s16(m, vo + 8 * b + 4) * s,
                 ox + s16(m, vo + 8 * c) * s, oz + s16(m, vo + 8 * c + 4) * s);
        }
    }

    private static void fill(int[] px, int col, float ax, float ay, float bx, float by, float cx, float cy) {
        float den = (by - cy) * (ax - cx) + (cx - bx) * (ay - cy);
        if (den == 0) return;
        int x0 = Math.max(0, (int) Math.min(ax, Math.min(bx, cx))), x1 = Math.min(W - 1, (int) Math.max(ax, Math.max(bx, cx)));
        int y0 = Math.max(0, (int) Math.min(ay, Math.min(by, cy))), y1 = Math.min(H - 1, (int) Math.max(ay, Math.max(by, cy)));
        for (int y = y0; y <= y1; y++)
            for (int x = x0; x <= x1; x++) {
                float qx = x + 0.5f, qy = y + 0.5f;
                float l1 = ((by - cy) * (qx - cx) + (cx - bx) * (qy - cy)) / den;
                float l2 = ((cy - ay) * (qx - cx) + (ax - cx) * (qy - cy)) / den;
                if (l1 >= -0.01f && l2 >= -0.01f && 1 - l1 - l2 >= -0.01f) px[y * W + x] = col;
            }
    }

    /** FF7's LZS (Okumura LZSS, N 4096, F 18, threshold 2), as src/mods/lzs.c. */
    private static byte[] lzs(byte[] in, int start, int n) {
        final int N = 4096, F = 18;
        byte[] ring = new byte[N];
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(4096);
        int r = N - F, flags = 0, ip = start, end = start + Math.max(0, n);
        while (true) {
            flags >>= 1;
            if ((flags & 256) == 0) {
                if (ip >= end) break;
                flags = (in[ip++] & 0xFF) | 0xFF00;
            }
            if ((flags & 1) != 0) {
                if (ip >= end) break;
                byte c = in[ip++];
                out.write(c); ring[r] = c; r = (r + 1) & (N - 1);
            } else {
                if (ip + 1 >= end) break;
                int i = (in[ip] & 0xFF) | ((in[ip + 1] & 0xF0) << 4), j = (in[ip + 1] & 0x0F) + 2;
                ip += 2;
                for (int k = 0; k <= j; k++) {
                    byte c = ring[(i + k) & (N - 1)];
                    out.write(c); ring[r] = c; r = (r + 1) & (N - 1);
                }
            }
        }
        return out.toByteArray();
    }

    private static int le32(byte[] b, int o) {
        return (b[o] & 0xFF) | (b[o + 1] & 0xFF) << 8 | (b[o + 2] & 0xFF) << 16 | (b[o + 3] & 0xFF) << 24;
    }
    private static int le16(byte[] b, int o) { return (b[o] & 0xFF) | (b[o + 1] & 0xFF) << 8; }
    private static int s16(byte[] b, int o) { return (short) le16(b, o); }
}
