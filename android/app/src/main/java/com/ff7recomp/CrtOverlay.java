package com.ff7recomp;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;

/**
 * The CRT filter on the second screen (requested in the comments): the top screen's look
 * (tools/ffnx_edit_android.py CRT_BLOCK: scanlines, an aperture-grille mask, a soft vignette) as a
 * see-through layer over the panels and the game's battle windows. A layer can only darken and
 * tint what is under it (the battle windows are a separate surface), so the scanlines and the
 * mask are a little lighter than the shader's, which brightens afterwards.
 */
public class CrtOverlay extends View {
    private Bitmap layer;

    public CrtOverlay(Context c) {
        super(c);
        setWillNotDraw(false);
        setClickable(false);
        setFocusable(false);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        if (layer != null) { layer.recycle(); layer = null; }
        if (w <= 0 || h <= 0) return;
        // the same line spacing as the top screen (its 240 lines over 1080 pixels on the Thor)
        double lines = h / 4.5;
        int[] px = new int[w * h];
        for (int y = 0; y < h; y++) {
            double scan = 0.5 + 0.5 * Math.cos(2 * Math.PI * (y + 0.5) / h * lines);
            double dy = (y + 0.5) / h - 0.5;
            for (int x = 0; x < w; x++) {
                double dx = (x + 0.5) / w - 0.5;
                // the grille: one column in three a little brighter
                double grille = x % 3 == 0 ? 1.0 : 0.92;
                double keep = (0.76 + 0.24 * scan) * (1.0 - 0.3 * (dx * dx + dy * dy)) * grille;
                int a = (int) Math.round(255 * Math.max(0, Math.min(1, 1 - keep)));
                // pure black, so the layer only darkens: black stays black (a coloured tint
                // lit up the battle screen's black area with lines and a ring)
                px[y * w + x] = a << 24;
            }
        }
        layer = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        layer.setPixels(px, 0, w, 0, 0, w, h);
    }

    @Override
    protected void onDraw(Canvas c) {
        if (layer != null) c.drawBitmap(layer, 0, 0, null);
    }
}
