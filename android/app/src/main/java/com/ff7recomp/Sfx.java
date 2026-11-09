package com.ff7recomp;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * FF7's own menu sounds in the launcher, decoded from the player's game files
 * (data/sound/audio.fmt + audio.dat, as FFNx's sfx.cpp reads them): nothing of the game ships
 * in the APK. Each sound is decoded once (MS-ADPCM) into a WAV in the app's cache and played
 * through a SoundPool.
 *
 * audio.fmt: one entry per sound ID (from 1): length and offset in audio.dat, loop flag and
 * points (16 bytes), then a WAVEFORMATEX (18 bytes) and its cbSize extra bytes (the ADPCM
 * coefficients). An empty slot (length 0) is 42 bytes with no extra.
 */
final class Sfx {
    private static final String TAG = "FF7Sfx";
    /** The IDs the game's menus play. */
    static final int CURSOR = 1, ERROR = 3, CANCEL = 4, OK = 1;
    private static final int[] USED = { CURSOR, ERROR, CANCEL, OK };

    private final SoundPool pool;
    private final int[] loaded = new int[16];
    private volatile boolean ready;
    volatile boolean enabled = true;
    private long lastCursor;

    Sfx(Context ctx, File gameDir) {
        pool = new SoundPool.Builder().setMaxStreams(4).setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build();
        File cache = new File(ctx.getCacheDir(), "sfx");
        new Thread(() -> {
            try {
                File fmt = new File(gameDir, "data/sound/audio.fmt"), dat = new File(gameDir, "data/sound/audio.dat");
                if (!fmt.isFile() || !dat.isFile()) return;
                cache.mkdirs();
                String stamp = fmt.length() + "-" + dat.length() + "-" + dat.lastModified();
                for (int id : USED) {
                    if (loaded[id] != 0) continue;
                    File wav = new File(cache, id + "-" + stamp + ".wav");
                    if (!wav.isFile()) decodeTo(fmt, dat, id, wav);
                    if (wav.isFile()) loaded[id] = pool.load(wav.getPath(), 1);
                }
                ready = true;
            } catch (Exception e) {
                Log.w(TAG, "menu sounds unavailable", e);
            }
        }, "sfx").start();
    }

    void play(int id) {
        if (!enabled || !ready || id <= 0 || id >= loaded.length || loaded[id] == 0) return;
        if (id == CURSOR) {                 // holding the D-pad: not a click per step
            long now = System.currentTimeMillis();
            if (now - lastCursor < 45) return;
            lastCursor = now;
        }
        pool.play(loaded[id], 0.8f, 0.8f, 1, 0, 1f);
    }

    boolean isReady() { return ready; }

    void release() { pool.release(); }

    // ------------------------------------------------------------------ decoding
    private static final int[] ADAPT = { 230, 230, 230, 230, 307, 409, 512, 614, 768, 614, 512, 409, 307, 230, 230, 230 };

    private static void decodeTo(File fmtFile, File datFile, int id, File out) throws IOException {
        byte[] f = readAll(fmtFile);
        ByteBuffer b = ByteBuffer.wrap(f).order(ByteOrder.LITTLE_ENDIAN);
        int p = 0;
        for (int n = 1; n < id; n++) {                 // walk to the entry
            if (p + 42 > f.length) return;
            int len = b.getInt(p);
            p += 42 + (len == 0 ? 0 : (b.getShort(p + 40) & 0xFFFF));
        }
        if (p + 42 > f.length) return;
        int len = b.getInt(p), off = b.getInt(p + 4);
        int tag = b.getShort(p + 24) & 0xFFFF, ch = b.getShort(p + 26) & 0xFFFF, rate = b.getInt(p + 28);
        int align = b.getShort(p + 36) & 0xFFFF, cb = b.getShort(p + 40) & 0xFFFF;
        if (len <= 0 || ch < 1 || ch > 2 || rate <= 0 || len > 8 << 20) return;
        byte[] data = new byte[len];
        try (RandomAccessFile r = new RandomAccessFile(datFile, "r")) {
            r.seek(off & 0xFFFFFFFFL);
            r.readFully(data);
        }
        short[] pcm;
        if (tag == 1) {
            pcm = new short[len / 2];
            ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm);
        } else if (tag == 2 && cb >= 4 && align >= 7 * ch) {
            int ncoef = b.getShort(p + 44) & 0xFFFF;
            int[] c1 = new int[ncoef], c2 = new int[ncoef];
            for (int i = 0; i < ncoef; i++) { c1[i] = b.getShort(p + 46 + 4 * i); c2[i] = b.getShort(p + 48 + 4 * i); }
            pcm = adpcm(data, ch, align, c1, c2);
        } else return;
        writeWav(out, pcm, ch, rate);
    }

    /** Microsoft ADPCM, block by block (the same decoder vgmstream / FFmpeg use). */
    private static short[] adpcm(byte[] d, int ch, int align, int[] c1, int[] c2) {
        int blocks = d.length / align, tail = d.length % align;
        int perBlock = 2 + (align - 7 * ch) * 2 / ch;
        short[] out = new short[(blocks + 1) * perBlock * ch];
        int o = 0;
        int[] pred = new int[ch], delta = new int[ch], s1 = new int[ch], s2 = new int[ch];
        for (int base = 0; base + 7 * ch <= d.length; base += align) {
            int end = Math.min(d.length, base + align), q = base;
            for (int c = 0; c < ch; c++) pred[c] = Math.min(d[q++] & 0xFF, c1.length - 1);
            for (int c = 0; c < ch; c++) { delta[c] = (short) ((d[q] & 0xFF) | (d[q + 1] << 8)); q += 2; }
            for (int c = 0; c < ch; c++) { s1[c] = (short) ((d[q] & 0xFF) | (d[q + 1] << 8)); q += 2; }
            for (int c = 0; c < ch; c++) { s2[c] = (short) ((d[q] & 0xFF) | (d[q + 1] << 8)); q += 2; }
            for (int c = 0; c < ch; c++) out[o++] = (short) s2[c];
            for (int c = 0; c < ch; c++) out[o++] = (short) s1[c];
            int k = 0;
            for (; q < end; q++) {
                for (int half = 0; half < 2; half++) {
                    int n = half == 0 ? (d[q] >> 4) & 15 : d[q] & 15;
                    int c = k++ % ch;
                    int pr = (s1[c] * c1[pred[c]] + s2[c] * c2[pred[c]]) >> 8;
                    int v = pr + ((n & 8) != 0 ? n - 16 : n) * delta[c];
                    v = Math.max(-32768, Math.min(32767, v));
                    if (o < out.length) out[o++] = (short) v;
                    s2[c] = s1[c];
                    s1[c] = v;
                    delta[c] = Math.max(16, (ADAPT[n] * delta[c]) >> 8);
                }
            }
        }
        short[] r = new short[o];
        System.arraycopy(out, 0, r, 0, o);
        return r;
    }

    private static void writeWav(File out, short[] pcm, int ch, int rate) throws IOException {
        ByteBuffer h = ByteBuffer.allocate(44 + pcm.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        h.put("RIFF".getBytes()).putInt(36 + pcm.length * 2).put("WAVE".getBytes())
         .put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) ch).putInt(rate)
         .putInt(rate * ch * 2).putShort((short) (ch * 2)).putShort((short) 16)
         .put("data".getBytes()).putInt(pcm.length * 2);
        for (short s : pcm) h.putShort(s);
        File tmp = new File(out.getPath() + ".tmp");
        try (OutputStream o = new FileOutputStream(tmp)) { o.write(h.array()); }
        if (!tmp.renameTo(out)) throw new IOException("cannot write " + out);
    }

    private static byte[] readAll(File f) throws IOException {
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
            byte[] b = new byte[(int) r.length()];
            r.readFully(b);
            return b;
        }
    }
}
