package com.ff7recomp;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * FF7's own text font for the bottom screen (CompanionView), read from the player's game files
 * when the panels open: nothing from the game ships with the app.
 *
 * The hi-res font is data/menu/menu_us.lgp's usfont_a_h.tex and usfont_b_h.tex, the left and
 * right halves of one 512 x 256 image: 21 glyphs a row in 24 x 24 cells, indexed by FF7 text code
 * (ASCII - 0x20, then FF7's own table for accented letters and symbols). Pixel 3 is a glyph's
 * body, 1 its shadow. Glyphs advance by their own drawn width plus a pixel: kernel/window.bin's
 * widths are for the low-res font and spread this one out (and crowd M and W). Digits share the
 * widest digit's width, so a clock or HP that ticks does not jiggle.
 */
final class GameFont {
    private static final String TAG = "FF7GameFont";
    private static final int CELL = 24, COLS = 21;
    /** Codes from 210 on sit in the texture's last row, which it cuts off. */
    private static final int MAX_CODE = 209;
    /** FF7 text codes 0x60.. as Unicode (the accented letters and symbols that are drawn). */
    private static final String HIGH =
        "ÄÁÇÉÑÖÜáàâäãåçéè" +           // 0x60
        "êëíìîïñóòôöõúùûü" +           // 0x70
        "\0°¢£ÙÛ¶ß®©™´¨≠ÆØ" +          // 0x80
        "∞±≤≥¥µ∂ΣΠπ\0ªºΩæø" +          // 0x90
        "¿¡¬√ƒ≈∆«»…\0ÀÃÕŒœ" +          // 0xA0
        "–—“”‘’÷◊ÿŸ\0\0‹›";            // 0xB0

    private final Bitmap atlas;
    private final int[] advance;          // hi-res pixels
    /** Each glyph's used width in its cell plus an empty column: drawing the whole cell let the
     *  smoothing pick up the next glyph's edge and the row below's tops (thin lines and dots). */
    private final int[] glyphW, glyphH;   // glyphH: the same for the rows (g, j, p, q, y reach lower)
    /** Body height of a capital (hi-res pixels) and the row under it, for size and baseline. */
    private final int capH, baseRow;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Rect src = new Rect();
    private final RectF dst = new RectF();
    private int tint;

    private GameFont(Bitmap atlas, int[] glyphW, int[] glyphH, int capH, int baseRow) {
        this.atlas = atlas;
        this.glyphW = glyphW;
        this.glyphH = glyphH;
        this.capH = capH;
        this.baseRow = baseRow;
        advance = glyphW.clone();             // used width + 1
        advance[0] = 9;                       // space
        int digit = 0;
        for (int c = 0x10; c <= 0x19; c++) digit = Math.max(digit, glyphW[c]);
        for (int c = 0x10; c <= 0x19; c++) advance[c] = digit;
    }

    /** The font, or null when the game files lack it. Call off the UI thread. */
    static GameFont load(File gameDir) {
        try {
            byte[] a = null, b = null;
            File lgp = new File(gameDir, "data/menu/menu_us.lgp");
            if (!lgp.isFile()) return null;
            try (RandomAccessFile f = new RandomAccessFile(lgp, "r")) {
                ByteBuffer head = read(f, 0, 16);
                int count = head.getInt(12);
                if (count <= 0 || count > 100000) return null;
                ByteBuffer toc = read(f, 16, count * 27);
                for (int i = 0; i < count; i++) {
                    String name = cString(toc, i * 27, 20);
                    boolean isA = name.equalsIgnoreCase("usfont_a_h.tex"), isB = name.equalsIgnoreCase("usfont_b_h.tex");
                    if (!isA && !isB) continue;
                    long off = toc.getInt(i * 27 + 20) & 0xFFFFFFFFL;
                    int size = read(f, off + 20, 4).getInt(0);
                    if (size < 0xEC || size > 4 << 20) continue;
                    byte[] px = pixels(read(f, off + 24, size));
                    if (isA) a = px; else b = px;
                }
            }
            if (a == null || b == null) return null;
            int[] argb = new int[512 * 256];
            for (int y = 0; y < 256; y++)
                for (int x = 0; x < 512; x++) {
                    int v = (x < 256 ? a : b)[y * 256 + (x & 255)];
                    argb[y * 512 + x] = v == 3 ? 0xFFFFFFFF : v == 1 ? 0xC0000000 : 0;
                }
            // 'H' (code 0x28): its body's rows give the cap height and the baseline
            int hx = (0x28 % COLS) * CELL, hy = (0x28 / COLS) * CELL, top = -1, bottom = -1;
            for (int y = 0; y < CELL; y++)
                for (int x = 0; x < CELL; x++)
                    if ((argb[(hy + y) * 512 + hx + x] & 0xFFFFFF) != 0) { if (top < 0) top = y; bottom = y; }
            if (top < 0) return null;
            int[] glyphW = new int[MAX_CODE + 1], glyphH = new int[MAX_CODE + 1];
            for (int c = 0; c <= MAX_CODE; c++) {
                int cx = (c % COLS) * CELL, cy = (c / COLS) * CELL, uw = 0, uh = 0;
                for (int y = 0; y < CELL && cy + y < 256; y++)
                    for (int x = 0; x < CELL; x++)
                        if (argb[(cy + y) * 512 + cx + x] != 0) { uw = Math.max(uw, x + 1); uh = y + 1; }
                glyphW[c] = Math.min(CELL, uw + 1);
                glyphH[c] = Math.min(Math.min(CELL, 256 - cy), uh + 1);
            }
            return new GameFont(Bitmap.createBitmap(argb, 512, 256, Bitmap.Config.ARGB_8888), glyphW, glyphH, bottom - top + 1, bottom + 1);
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "font: " + e);
            return null;
        }
    }

    /** The FF7 text code for ch, or -1 when the font has no glyph for it. */
    private static int code(char ch) {
        if (ch >= 0x20 && ch <= 0x7E) return ch - 0x20;
        if (ch == 0xA0) return 0;
        int i = HIGH.indexOf(ch);
        if (ch == 0 || i < 0) return -1;
        int c = 0x60 + i;
        return c <= MAX_CODE ? c : -1;
    }

    /** Hi-res pixels to screen pixels: a capital as tall as a sans-serif one at this size. */
    private float scale(float size) { return size * 0.72f / capH; }

    /** s's width at size; fallback measures the characters the font lacks. */
    float measure(String s, float size, Paint fallback) {
        float k = scale(size), w = 0;
        fallback.setTextSize(size);
        for (int i = 0; i < s.length(); i++) {
            int c = code(s.charAt(i));
            if (c >= 0) { w += advance[c] * k; continue; }
            int j = s.offsetByCodePoints(i, 1);
            w += fallback.measureText(s, i, j);
            i = j - 1;
        }
        return w;
    }

    /** s at (x, baseline y) like Canvas.drawText; characters the font lacks use fallback. */
    void draw(Canvas cv, String s, float x, float y, float size, int color, Paint.Align align, Paint fallback) {
        float w = measure(s, size, fallback);
        if (align == Paint.Align.CENTER) x -= w / 2; else if (align == Paint.Align.RIGHT) x -= w;
        if (tint != color) { paint.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.MULTIPLY)); tint = color; }
        fallback.setTextSize(size);
        fallback.setColor(color);
        fallback.setTextAlign(Paint.Align.LEFT);
        float k = scale(size), top = y - baseRow * k;
        for (int i = 0; i < s.length(); i++) {
            int c = code(s.charAt(i));
            if (c < 0) {
                int j = s.offsetByCodePoints(i, 1);
                cv.drawText(s, i, j, x, y, fallback);
                x += fallback.measureText(s, i, j);
                i = j - 1;
                continue;
            }
            if (c != 0) {
                int sx = (c % COLS) * CELL, sy = (c / COLS) * CELL;
                src.set(sx, sy, sx + glyphW[c], sy + glyphH[c]);
                float gx = digitPad(c, x, k);
                dst.set(gx, top, gx + glyphW[c] * k, top + (src.bottom - src.top) * k);
                cv.drawBitmap(atlas, src, dst, paint);
            }
            x += advance[c] * k;
        }
    }

    /** A digit narrower than the shared digit width sits in the middle of it. */
    private float digitPad(int c, float x, float k) {
        return c >= 0x10 && c <= 0x19 ? x + (advance[c] - glyphW[c]) * k / 2 : x;
    }

    /** The 8-bit pixel indices of a 256 x 256 FF7 .tex (0xEC-byte header, palette, pixels). */
    private static byte[] pixels(ByteBuffer t) {
        int w = t.getInt(0x3C), h = t.getInt(0x40), pal = t.getInt(0x58);
        if (w != 256 || h != 256 || pal <= 0 || pal > 256) throw new IllegalArgumentException("font texture " + w + "x" + h);
        int px = 0xEC + pal * 4;
        if (t.capacity() < px + w * h) throw new IllegalArgumentException("font texture short");
        byte[] out = new byte[w * h];
        t.position(px);
        t.get(out);
        return out;
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
