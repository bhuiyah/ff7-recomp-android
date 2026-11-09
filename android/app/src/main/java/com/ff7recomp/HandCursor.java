package com.ff7recomp;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/**
 * The launcher's menu cursor: a white pointing glove (original drawing, in the spirit of an RPG
 * menu cursor). Stateful: full on the focused or pressed item, faint on the selected one (the
 * page on screen), hidden otherwise; it keeps its size either way so the menu text never moves.
 */
final class HandCursor extends Drawable {
    private static final float W = 26, H = 16;     // drawing units
    private final int width, height;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path shape = new Path();
    private int alpha = 0;

    HandCursor(float density) {
        width = Math.round(W * 1.0f * density);
        height = Math.round(H * 1.0f * density);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeJoin(Paint.Join.ROUND);
        line.setStrokeCap(Paint.Cap.ROUND);
        Path.Direction cw = Path.Direction.CW;
        shape.addRoundRect(new RectF(12, 4.2f, 25, 8.4f), 2.1f, 2.1f, cw);    // index finger
        shape.addRoundRect(new RectF(8, 0.8f, 15.5f, 5.2f), 2.2f, 2.2f, cw);  // thumb
        shape.addRoundRect(new RectF(4, 3, 15, 14.5f), 3.5f, 3.5f, cw);       // palm and curled fingers
        shape.addRect(new RectF(0.8f, 4, 4.6f, 13.5f), cw);                     // cuff
    }

    @Override public void draw(Canvas c) {
        if (alpha == 0) return;
        c.save();
        c.translate(getBounds().left, getBounds().top);
        c.scale(getBounds().width() / W, getBounds().height() / H);
        // drop shadow, outline, glove, cuff, the knuckle lines
        fill.setColor(0x66000000); fill.setAlpha(alpha * 0x66 / 255);
        c.save(); c.translate(1.1f, 1.1f); c.drawPath(shape, fill); c.restore();
        line.setColor(0xFF262A36); line.setAlpha(alpha); line.setStrokeWidth(1.6f);
        c.drawPath(shape, line);
        fill.setColor(0xFFFFFFFF); fill.setAlpha(alpha);
        c.drawPath(shape, fill);
        fill.setColor(0xFFC3C9DA); fill.setAlpha(alpha);
        c.drawRect(0.8f, 4, 4.6f, 13.5f, fill);
        line.setStrokeWidth(0.9f); line.setColor(0xFF8A90A6); line.setAlpha(alpha);
        c.drawLine(8.5f, 9.6f, 14.2f, 9.6f, line);
        c.drawLine(8.5f, 12.1f, 14, 12.1f, line);
        c.drawLine(12.5f, 4.6f, 12.5f, 8, line);
        c.restore();
    }

    @Override public boolean isStateful() { return true; }

    @Override protected boolean onStateChange(int[] state) {
        boolean focused = false, pressed = false, selected = false;
        for (int s : state) {
            if (s == android.R.attr.state_focused) focused = true;
            else if (s == android.R.attr.state_pressed) pressed = true;
            else if (s == android.R.attr.state_selected) selected = true;
        }
        int a = focused || pressed ? 255 : selected ? 110 : 0;
        if (a == alpha) return false;
        alpha = a;
        invalidateSelf();
        return true;
    }

    @Override public int getIntrinsicWidth() { return width; }
    @Override public int getIntrinsicHeight() { return height; }
    @Override public void setAlpha(int a) { }
    @Override public void setColorFilter(ColorFilter cf) { }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
