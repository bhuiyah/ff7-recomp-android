package com.ff7recomp;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Imports FINAL FANTASY VII saves from a PlayStation memory card (DuckStation .mcd, ePSXe .mcr,
 * RetroArch .srm and other raw 128 KiB cards, DexDrive .gme, PSP .vmp) or a single exported
 * save block, into a new PC save file (game/save/saveNN.ff7, "Save N" in the Continue menu).
 *
 * Layout as in ff7tk (Black Chocobo): a card is 16 blocks of 0x2000, block 0 the directory
 * (frame i at 0x80*i: 0x51 = first block of a file, the name at +0x0A); an FF7 save block is a
 * 0x200 header, 0x10F4 bytes of slot data and padding. The PC file is a 9-byte header and 15
 * slots of the same 0x10F4 bytes. On the way to PC the "custom controls" option bit is cleared
 * and the slot CRC recomputed, as Black Chocobo does.
 */
final class PsxSaves {
    static final int SLOT = 0x10F4, PC_HEADER = 9, PC_SIZE = PC_HEADER + 15 * SLOT, FILES = 10;
    private static final Pattern NAME = Pattern.compile("B[A-Z]{5}-(\\d{5})FF7-S(\\d\\d)");
    private static final String[] REGIONS = { "94163", "00867", "00868", "00869", "00900", "00700", "01057" };

    static final class Result {
        int saves;        // FF7 saves found
        int gameNumber;   // the "Save N" they went to (1-based)
    }

    private PsxSaves() {}

    /** Converts {@code data} into the first free save file in {@code saveDir}. */
    static Result importCard(byte[] data, File saveDir) throws IOException {
        byte[] card = rawCard(data);
        byte[] pc = new byte[PC_SIZE];
        pc[0] = 0x71; pc[1] = 0x73; pc[2] = 0x27; pc[3] = 0x06;
        Result r = new Result();
        if (card != null) {
            for (int i = 1; i < 16; i++) {
                int f = 0x80 * i;
                if ((card[f] & 0xFF) != 0x51) continue;
                Matcher m = NAME.matcher(new String(card, f + 0x0A, 20, StandardCharsets.US_ASCII));
                if (!m.lookingAt() || !isFF7(m.group(1))) continue;
                int slot = Integer.parseInt(m.group(2)) - 1;
                if (slot < 0 || slot > 14) continue;
                putSlot(pc, slot, card, 0x2000 * i + 0x200);
                r.saves++;
            }
        } else if (data.length == 0x2000 && data[0] == 'S' && data[1] == 'C') {
            putSlot(pc, 0, data, 0x200);
            r.saves = 1;
        } else {
            throw new IOException("this is not a PlayStation memory card or FF7 save");
        }
        if (r.saves == 0) throw new IOException("there are no FINAL FANTASY VII saves on this memory card");

        int used5 = 0, used6 = 0;
        for (int s = 0; s < 15; s++) {
            if (!slotUsed(pc, s)) continue;
            if (s < 8) used5 |= 1 << s; else used6 |= 1 << (s - 8);
        }
        pc[5] = (byte) used5;
        pc[6] = (byte) used6;

        saveDir.mkdirs();
        for (int n = 0; n < FILES; n++) {
            File f = new File(saveDir, String.format(java.util.Locale.ROOT, "save%02d.ff7", n));
            if (f.exists()) continue;
            try (OutputStream o = new FileOutputStream(f)) { o.write(pc); }
            r.gameNumber = n + 1;
            return r;
        }
        throw new IOException("all " + FILES + " save files (Save 1-10) are in use; delete one first");
    }

    /** The 128 KiB raw card inside {@code d}, or null when it is not a card. */
    private static byte[] rawCard(byte[] d) {
        int[] offsets = { 0, 0x0F40 /* DexDrive .gme */, 0x80 /* PSP .vmp */, 0x40 /* VGS */ };
        for (int off : offsets) {
            if (d.length >= off + 0x20000 && d[off] == 'M' && d[off + 1] == 'C') {
                byte[] c = new byte[0x20000];
                System.arraycopy(d, off, c, 0, c.length);
                return c;
            }
        }
        return null;
    }

    private static boolean isFF7(String region) {
        for (String r : REGIONS) if (r.equals(region)) return true;
        return false;
    }

    private static void putSlot(byte[] pc, int slot, byte[] src, int at) {
        int dst = PC_HEADER + slot * SLOT;
        System.arraycopy(src, at, pc, dst, SLOT);
        int opt = dst + 0x10DA;                       // options: normal (not custom) controls
        pc[opt] &= ~(1 << 2);
        int crc = crc(pc, dst);
        pc[dst] = (byte) crc;
        pc[dst + 1] = (byte) (crc >> 8);
    }

    private static boolean slotUsed(byte[] pc, int slot) {
        int at = PC_HEADER + slot * SLOT;
        int ck = (pc[at] & 0xFF) | (pc[at + 1] & 0xFF) << 8;
        if (ck == 0x4D1D) return false;               // the checksum of an empty slot
        for (int i = 0; i < SLOT; i++) if (pc[at + i] != 0) return true;
        return false;
    }

    /** FF7's slot checksum: CRC-16/CCITT over bytes 4..0x10F4 of the slot, inverted. */
    static int crc(byte[] b, int slotStart) {
        int r = 0xFFFF;
        for (int i = slotStart + 4; i < slotStart + SLOT; i++) {
            r ^= (b[i] & 0xFF) << 8;
            for (int k = 0; k < 8; k++) r = (r & 0x8000) != 0 ? ((r << 1) ^ 0x1021) & 0xFFFF : (r << 1) & 0xFFFF;
        }
        return r ^ 0xFFFF;
    }
}
