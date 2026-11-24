package com.ff7recomp;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

/**
 * Frame generation (FF8Recomp #8): in-between frames made by Lossless Scaling's shaders, which come
 * from the player's own Lossless.dll (src/ffnx_port/framegen.cpp reads them). It is copied to
 * <files>/framegen and never part of FF7Recomp.
 */
final class FrameGen {
    static final int APP = 993090;                 // Lossless Scaling on Steam

    private FrameGen() {}

    static File dir(File files) { return new File(files, "framegen"); }
    static File dll(File files) { return new File(dir(files), "Lossless.dll"); }

    /** Copies a picked file in, if it is a Lossless.dll with the Vulkan shaders. Returns what is wrong, or null. */
    static String install(InputStream in, File files) throws IOException {
        dir(files).mkdirs();
        File tmp = new File(dir(files), "Lossless.dll.new");
        try (OutputStream o = new FileOutputStream(tmp)) {
            byte[] b = new byte[1 << 16];
            long total = 0;
            for (int n; (n = in.read(b)) > 0; ) {
                if ((total += n) > 64L << 20) { o.close(); tmp.delete(); return "that file is too big to be Lossless.dll"; }
                o.write(b, 0, n);
            }
        }
        String bad = check(tmp);
        if (bad != null) { tmp.delete(); return bad; }
        File to = dll(files);
        to.delete();
        if (!tmp.renameTo(to)) throw new IOException("cannot write " + to.getName());
        return null;
    }

    /** null, or why this file cannot be used. */
    static String check(File f) {
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
            if (u16(r, 0) != 0x5A4D) return "that is not Lossless.dll";
            long pe = u32(r, 0x3C);
            if (u32(r, pe) != 0x4550) return "that is not Lossless.dll";
            int sections = u16(r, pe + 6), opt = u16(r, pe + 20);
            long dir = pe + 24 + (u16(r, pe + 24) == 0x20B ? 112 : 96) + 16, sec = pe + 24 + opt;
            long rva = u32(r, dir), root = -1;
            for (int i = 0; i < sections && root < 0; i++) {
                long s = sec + 40L * i, va = u32(r, s + 12), raw = u32(r, s + 16);
                if (rva >= va && rva < va + raw) root = u32(r, s + 20) + rva - va;
            }
            if (rva == 0 || root < 0) return "that is not Lossless.dll (it has no resources)";
            boolean[] have = new boolean[48];
            int n = u16(r, root + 12) + u16(r, root + 14);
            for (int i = 0; i < n; i++) {
                long off = u32(r, root + 20 + 8L * i);
                if (u32(r, root + 16 + 8L * i) != 10 || off >> 31 == 0) continue;
                long d = root + (off & 0x7FFFFFFFL);
                int m = u16(r, d + 12) + u16(r, d + 14);
                for (int k = 0; k < m; k++) {
                    long id = u32(r, d + 16 + 8L * k);
                    if (id >= 353 && id <= 400) have[(int) id - 353] = true;
                }
            }
            for (boolean h : have)
                if (!h) return "this Lossless.dll has no Vulkan shaders. Update Lossless Scaling in Steam (its normal version) and import its Lossless.dll again";
            return null;
        } catch (IOException e) {
            return "that is not Lossless.dll (" + e.getMessage() + ")";
        }
    }

    private static int u16(RandomAccessFile r, long at) throws IOException {
        r.seek(at);
        int a = r.read(), b = r.read();
        if ((a | b) < 0) throw new IOException("it ends early");
        return a | b << 8;
    }

    private static long u32(RandomAccessFile r, long at) throws IOException {
        return u16(r, at) | (long) u16(r, at + 2) << 16;
    }

    /** The frame rates it adds to: those where the game draws fewer than 60 a second somewhere. */
    static boolean useful(int fps) { return fps != 3 && fps != 4; }

    /** What the game reads when it starts. */
    static void write(File files, boolean on) {
        File d = dir(files);
        if (!on && !d.isDirectory()) return;
        d.mkdirs();
        try (OutputStream o = new FileOutputStream(new File(d, "settings.txt"))) {
            o.write(((on ? "on=1\n" : "on=0\n") + "flow=0.60\n").getBytes(StandardCharsets.US_ASCII));
        } catch (IOException e) {
            android.util.Log.w("FF7FrameGen", "settings: " + e);
        }
    }
}
