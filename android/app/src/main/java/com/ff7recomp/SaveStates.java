package com.ff7recomp;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.Date;

/**
 * The game's save-state slots (files/savestates/slotNN.ff7s, written by
 * src/host/ff7_savestate.c) as the launcher lists them: the header says where in the game each
 * was saved, the play time and when. current.txt is the slot the in-game buttons use.
 */
final class SaveStates {
    static final int SLOTS = 10;

    static final class Slot {
        int number;
        boolean exists, readable;
        String location = "", when = "";
        long playSeconds, sizeBytes;
        String playTime() { return String.format(java.util.Locale.ROOT, "%d:%02d:%02d", playSeconds / 3600, playSeconds / 60 % 60, playSeconds % 60); }
    }

    final File dir;

    SaveStates(File filesDir) { dir = new File(filesDir, "savestates"); }

    File file(int n) { return new File(dir, String.format(java.util.Locale.ROOT, "slot%02d.ff7s", n)); }

    /* ss_header in ff7_savestate.c: magic[8], version u32, header_size u32, build[64],
     * saved_at i64, mode i32, raw_size u32, label[64], location[48], play_seconds u32 (216 bytes; version 3). */
    Slot read(int n) {
        Slot s = new Slot();
        s.number = n;
        File f = file(n);
        if (n == 1 && !f.isFile()) f = new File(dir, "quick.ff7s");     // not migrated yet
        s.exists = f.isFile();
        if (!s.exists) return s;
        s.sizeBytes = f.length();
        byte[] h = new byte[216];
        try (InputStream in = new FileInputStream(f)) {
            if (in.read(h) != h.length) return s;
        } catch (IOException e) {
            return s;
        }
        ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
        if (h[0] != 'F' || h[1] != 'F' || h[2] != '7' || b.getInt(8) != 3 || b.getInt(12) != 216) return s;
        s.readable = true;
        s.when = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(b.getLong(80) * 1000L));
        s.location = cstr(h, 160, 48);
        s.playSeconds = b.getInt(208) & 0xFFFFFFFFL;
        return s;
    }

    private static String cstr(byte[] b, int at, int n) {
        int end = at;
        while (end < at + n && b[end] != 0) end++;
        return new String(b, at, end - at, StandardCharsets.UTF_8);
    }

    int current() {
        File f = new File(dir, "current.txt");
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[8];
            int n = in.read(buf);
            int k = Integer.parseInt(new String(buf, 0, Math.max(n, 0), StandardCharsets.US_ASCII).trim());
            return k >= 1 && k <= SLOTS ? k : 1;
        } catch (Exception e) {
            return 1;
        }
    }

    void setCurrent(int n) throws IOException {
        dir.mkdirs();
        try (OutputStream o = new FileOutputStream(new File(dir, "current.txt"))) {
            o.write(Integer.toString(n).getBytes(StandardCharsets.US_ASCII));
        }
    }

    /** Remove slot n (its .bak too). */
    boolean delete(int n) {
        File f = file(n);
        if (n == 1) new File(dir, "quick.ff7s").delete();
        new File(f.getPath() + ".bak").delete();
        return !f.exists() || f.delete();
    }
}
