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
 *
 * Settings > On-screen controls (GitHub #28): a size, an opacity and where each of the four groups
 * sits (the D-pad, the face buttons, L2/L1/SELECT, R1/R2/START), as a layout string (layout()).
 * In edit mode (TouchLayoutActivity) a finger drags a group instead of pressing buttons.
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

    // the four groups that move together
    static final int G_PAD = 0, G_FACE = 1, G_LEFT = 2, G_RIGHT = 3;
    private float scale = 1f;
    private final float[] groupOff = new float[8];   // each group's offset, as fractions of the width / height
    private boolean editing;
    private int dragGroup = -1;
    private float dragX, dragY;
    private Runnable onEdited;

    /** "scale;opacity;padX,padY;faceX,faceY;leftX,leftY;rightX,rightY" (Settings > On-screen controls). */
    void setLayout(String layout) {
        java.util.Arrays.fill(groupOff, 0f);
        scale = 1f;
        float alpha = 1f;
        if (layout != null && !layout.isEmpty()) {
            try {
                String[] p = layout.split(";");
                scale = Math.max(0.5f, Math.min(2f, Float.parseFloat(p[0])));
                alpha = Math.max(0.15f, Math.min(1f, Float.parseFloat(p[1])));
                for (int g = 0; g < 4 && 2 + g < p.length; g++) {
                    String[] xy = p[2 + g].split(",");
                    groupOff[2 * g] = Float.parseFloat(xy[0]);
                    groupOff[2 * g + 1] = Float.parseFloat(xy[1]);
                }
            } catch (RuntimeException ignored) { }   // a damaged setting: the default layout
        }
        setAlpha(alpha);
        if (getWidth() > 0) { place(getWidth(), getHeight()); invalidate(); }
    }

    String layout() {
        StringBuilder b = new StringBuilder();
        b.append(String.format(java.util.Locale.ROOT, "%.2f;%.2f", scale, getAlpha()));
        for (int g = 0; g < 4; g++) b.append(String.format(java.util.Locale.ROOT, ";%.4f,%.4f", groupOff[2 * g], groupOff[2 * g + 1]));
        return b.toString();
    }

    void setScale(float s) { scale = s; if (getWidth() > 0) { place(getWidth(), getHeight()); invalidate(); } }
    float scale() { return scale; }
    void resetPositions() { java.util.Arrays.fill(groupOff, 0f); if (getWidth() > 0) { place(getWidth(), getHeight()); invalidate(); } }

    /** Edit mode: fingers drag the groups; edited runs after each drag. */
    void setEditing(boolean on, Runnable edited) { editing = on; onEdited = edited; invalidate(); }

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
    protected void onSizeChanged(int w, int h, int ow, int oh) { place(w, h); }

    private void place(int w, int h) {
        unit = Math.min(w, h) / 8f * scale;
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
        // each group moved by its offset, then kept on the screen (a bigger size can push it off)
        for (int gi = 0; gi < 4; gi++) {
            shift(gi, groupOff[2 * gi] * w, groupOff[2 * gi + 1] * h);
            RectF r = bounds(gi);
            float dx = r.left < 0 ? -r.left : r.right > w ? w - r.right : 0;
            float dy = r.top < 0 ? -r.top : r.bottom > h ? h - r.bottom : 0;
            if (dx != 0 || dy != 0) {
                shift(gi, dx, dy);
                groupOff[2 * gi] += dx / w;
                groupOff[2 * gi + 1] += dy / h;
            }
        }
    }

    private Btn[] members(int g) {
        switch (g) {
            case G_FACE: return new Btn[] { a, b, x, y };
            case G_LEFT: return new Btn[] { l2, l1, select };
            case G_RIGHT: return new Btn[] { r1, r2, start };
            default: return new Btn[0];
        }
    }

    private void shift(int g, float dx, float dy) {
        if (g == G_PAD) { padX += dx; padY += dy; return; }
        for (Btn btn : members(g)) btn.r.offset(dx, dy);
    }

    private RectF bounds(int g) {
        if (g == G_PAD) return new RectF(padX - padR, padY - padR, padX + padR, padY + padR);
        RectF r = null;
        for (Btn btn : members(g)) { if (r == null) r = new RectF(btn.r); else r.union(btn.r); }
        return r;
    }

    private static void circle(Btn btn, float cx, float cy, float r) { btn.r.set(cx - r, cy - r, cx + r, cy + r); }

    @Override
    protected void onDraw(Canvas c) {
        if (editing) {
            fill.setColor(0x30FFD54F);
            for (int g = 0; g < 4; g++) {
                RectF r = bounds(g);
                r.inset(-unit * 0.15f, -unit * 0.15f);
                fill.setColor(g == dragGroup ? 0x60FFD54F : 0x30FFD54F);
                c.drawRoundRect(r, unit * 0.2f, unit * 0.2f, fill);
            }
        }
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
        if (editing) return drag(e);
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

    /** Edit mode: the first finger drags the group it lands on. */
    private boolean drag(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                dragGroup = -1;
                for (int g = 0; g < 4; g++) {
                    RectF r = bounds(g);
                    r.inset(-unit * 0.3f, -unit * 0.3f);
                    if (r.contains(e.getX(), e.getY())) { dragGroup = g; break; }
                }
                dragX = e.getX(); dragY = e.getY();
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_MOVE:
                if (dragGroup >= 0) {
                    groupOff[2 * dragGroup] += (e.getX() - dragX) / getWidth();
                    groupOff[2 * dragGroup + 1] += (e.getY() - dragY) / getHeight();
                    dragX = e.getX(); dragY = e.getY();
                    place(getWidth(), getHeight());
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (dragGroup >= 0 && onEdited != null) onEdited.run();
                dragGroup = -1;
                invalidate();
                return true;
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
