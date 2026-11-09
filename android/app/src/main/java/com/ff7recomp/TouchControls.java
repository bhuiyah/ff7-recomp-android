package com.ff7recomp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

/**
 * An on-screen pad over the game, for devices with one screen and no controller (GameActivity
 * decides when it shows). It feeds the same XInput-style state as a real pad:
 *
 *   D-pad (8-way, left)          face buttons (right): A = confirm (FF7's Circle),
 *   L1 / L2 (top left)            B = cancel, Y = menu, X = the fourth button
 *   R1 / R2 (top right)          SELECT / START (bottom middle)
 *
 * Multi-touch: every finger presses what is under it, and a finger can slide from one button to
 * another (the D-pad follows the finger's angle).
 */
final class TouchControls extends View {
    interface Listener { void pad(int buttons, int lt, int rt); }

    private static final int UP = 0x0001, DOWN = 0x0002, LEFT = 0x0004, RIGHT = 0x0008;
    private static final int START = 0x0010, SELECT = 0x0020, L1 = 0x0100, R1 = 0x0200;
    private static final int CIRCLE = 0x2000, CROSS = 0x1000, SQUARE = 0x4000, TRIANGLE = 0x8000;
    private static final int L2 = 1 << 20, R2 = 1 << 21;    // triggers: sent as analog values

    private static final class Btn {
        final int bit; final String label, hint; final boolean round;
        final RectF r = new RectF();
        Btn(int bit, String label, String hint, boolean round) { this.bit = bit; this.label = label; this.hint = hint; this.round = round; }
    }

    // face buttons laid out as an Android pad: A bottom, B right, X left, Y top
    private final Btn a = new Btn(CIRCLE, "A", "OK", true), b = new Btn(CROSS, "B", "Back", true),
            x = new Btn(SQUARE, "X", "", true), y = new Btn(TRIANGLE, "Y", "Menu", true),
            l1 = new Btn(L1, "L1", "", false), l2 = new Btn(L2, "L2", "", false),
            r1 = new Btn(R1, "R1", "", false), r2 = new Btn(R2, "R2", "", false),
            select = new Btn(SELECT, "SELECT", "", false), start = new Btn(START, "START", "", false);
    private final Btn[] buttons = { a, b, x, y, l1, l2, r1, r2, select, start };

    private final Listener listener;
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG),
            text = new Paint(Paint.ANTI_ALIAS_FLAG), small = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float padX, padY, padR, unit;
    private int state;

    TouchControls(Context c, Listener l) {
        super(c);
        listener = l;
        line.setStyle(Paint.Style.STROKE);
        line.setColor(0x80FFFFFF);
        fill.setStyle(Paint.Style.FILL);
        text.setColor(0xB0FFFFFF);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(Typeface.DEFAULT_BOLD);
        small.setColor(0x90FFFFFF);
        small.setTextAlign(Paint.Align.CENTER);
        setHapticFeedbackEnabled(true);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        unit = Math.min(w, h) / 8f;
        line.setStrokeWidth(Math.max(2f, unit * 0.04f));
        text.setTextSize(unit * 0.38f);
        small.setTextSize(unit * 0.24f);
        // D-pad, bottom left, close to the corner (FF7's windows sit at the bottom middle)
        padR = unit * 1.25f;
        padX = unit * 0.3f + padR;
        padY = h - unit * 0.3f - padR;
        // face buttons, bottom right
        float br = unit * 0.5f, off = unit * 0.85f;
        float fx = w - unit * 0.3f - off - br, fy = h - unit * 0.3f - off - br;
        circle(a, fx, fy + off, br);
        circle(b, fx + off, fy, br);
        circle(x, fx - off, fy, br);
        circle(y, fx, fy - off, br);
        // shoulders, top corners, side by side (L2 L1 ... R1 R2)
        float sw = unit * 1.4f, sh = unit * 0.6f, m = unit * 0.25f, g = unit * 0.15f;
        l2.r.set(m, m, m + sw, m + sh);
        l1.r.set(m + sw + g, m, m + 2 * sw + g, m + sh);
        r2.r.set(w - m - sw, m, w - m, m + sh);
        r1.r.set(w - m - 2 * sw - g, m, w - m - sw - g, m + sh);
        // select / start, below the shoulders at the sides (the bottom middle is FF7's windows)
        float pw = unit * 1.4f, ph = unit * 0.5f, py = m + sh + unit * 0.25f;
        select.r.set(m, py, m + pw, py + ph);
        start.r.set(w - m - pw, py, w - m, py + ph);
    }

    private static void circle(Btn btn, float cx, float cy, float r) { btn.r.set(cx - r, cy - r, cx + r, cy + r); }

    @Override
    protected void onDraw(Canvas c) {
        // D-pad: a ring with four arrows; the pressed directions light up
        fill.setColor(0x26FFFFFF);
        c.drawCircle(padX, padY, padR, fill);
        c.drawCircle(padX, padY, padR, line);
        drawArrow(c, 0, -1, UP);
        drawArrow(c, 0, 1, DOWN);
        drawArrow(c, -1, 0, LEFT);
        drawArrow(c, 1, 0, RIGHT);
        for (Btn btn : buttons) {
            boolean on = (state & btn.bit) != 0;
            fill.setColor(on ? 0x90FFFFFF : 0x26FFFFFF);
            float cy = btn.r.centerY() - (text.descent() + text.ascent()) / 2;
            if (btn.round) {
                c.drawOval(btn.r, fill);
                c.drawOval(btn.r, line);
                if (!btn.hint.isEmpty()) {
                    c.drawText(btn.label, btn.r.centerX(), cy - unit * 0.08f, text);
                    c.drawText(btn.hint, btn.r.centerX(), cy + unit * 0.24f, small);
                } else {
                    c.drawText(btn.label, btn.r.centerX(), cy, text);
                }
            } else {
                float rr = btn.r.height() / 2;
                c.drawRoundRect(btn.r, rr, rr, fill);
                c.drawRoundRect(btn.r, rr, rr, line);
                Paint p = btn.label.length() > 2 ? small : text;
                c.drawText(btn.label, btn.r.centerX(), btn.r.centerY() - (p.descent() + p.ascent()) / 2, p);
            }
        }
    }

    private void drawArrow(Canvas c, int dx, int dy, int bit) {
        float cx = padX + dx * padR * 0.6f, cy = padY + dy * padR * 0.6f, s = padR * 0.22f;
        android.graphics.Path p = new android.graphics.Path();
        // a triangle pointing (dx, dy)
        p.moveTo(cx + dx * s, cy + dy * s);
        p.lineTo(cx - dx * s + dy * s, cy - dy * s + dx * s);
        p.lineTo(cx - dx * s - dy * s, cy - dy * s - dx * s);
        p.close();
        fill.setColor((state & bit) != 0 ? 0xE0FFFFFF : 0x80FFFFFF);
        c.drawPath(p, fill);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int s = 0;
        int lifted = -1;
        int act = e.getActionMasked();
        if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_POINTER_UP) lifted = e.getActionIndex();
        if (act != MotionEvent.ACTION_CANCEL) {
            for (int i = 0; i < e.getPointerCount(); i++) {
                if (i == lifted) continue;
                s |= hit(e.getX(i), e.getY(i));
            }
        }
        if (s != state) {
            if ((s & ~state) != 0) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            state = s;
            listener.pad(s & 0xFFFF, (s & L2) != 0 ? 255 : 0, (s & R2) != 0 ? 255 : 0);
            invalidate();
        }
        return true;
    }

    /** What a finger at (px, py) presses. */
    private int hit(float px, float py) {
        float dx = px - padX, dy = py - padY;
        float d = (float) Math.hypot(dx, dy);
        if (d < padR * 1.35f) {
            if (d < padR * 0.2f) return 0;                       // dead centre
            double ang = Math.toDegrees(Math.atan2(dy, dx));     // 0 = right, 90 = down
            int bits = 0;
            if (ang > -67.5 && ang < 67.5) bits |= RIGHT;
            if (ang > 22.5 && ang < 157.5) bits |= DOWN;
            if (ang > 112.5 || ang < -112.5) bits |= LEFT;
            if (ang > -157.5 && ang < -22.5) bits |= UP;
            return bits;
        }
        Btn best = null;
        float bestD = Float.MAX_VALUE;
        for (Btn btn : buttons) {
            RectF r = btn.r;
            float grow = btn.round ? unit * 0.25f : unit * 0.15f;  // a little forgiveness around each
            if (px < r.left - grow || px > r.right + grow || py < r.top - grow || py > r.bottom + grow) continue;
            float dd = (float) Math.hypot(px - r.centerX(), py - r.centerY());
            if (dd < bestD) { bestD = dd; best = btn; }
        }
        return best != null ? best.bit : 0;
    }
}
