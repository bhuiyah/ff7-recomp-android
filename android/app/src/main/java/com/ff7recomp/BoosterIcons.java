package com.ff7recomp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import org.json.JSONObject;

/**
 * The game boosters' icons (docs/ROADMAP.md M8) in the top screen's top-right corner while a
 * booster is on: speed (a double chevron with 2x / 3x), random battles off (a crossed-out
 * exclamation mark), battle enhancement (a heart with a plus). Drawn here, not by the game:
 * their state comes from the companion snapshot (src/ffnx_port/companion.cpp, boosters.cpp),
 * polled twice a second. Not touchable; the game keeps all input.
 */
final class BoosterIcons extends View {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final Path path = new Path();
    private int speed = 1;
    private boolean noBattles, battlePlus;

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            try {
                JSONObject s = new JSONObject(CompanionView.snapshot());
                int sp = Math.max(1, s.optInt("speed", 1));
                boolean nb = s.optInt("noenc") == 1, bp = s.optInt("bplus") == 1;
                if (sp != speed || nb != noBattles || bp != battlePlus) {
                    speed = sp; noBattles = nb; battlePlus = bp;
                    invalidate();
                }
            } catch (Exception ignored) { }
            handler.postDelayed(this, 500);
        }
    };

    BoosterIcons(Context c) {
        super(c);
        setFocusable(false);
        setClickable(false);
        setWillNotDraw(false);
        ink.setColor(0xFFFFFFFF);
        ink.setStrokeCap(Paint.Cap.ROUND);
        ink.setStrokeJoin(Paint.Join.ROUND);
        ink.setTextAlign(Paint.Align.CENTER);
        ink.setFakeBoldText(true);
    }

    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); handler.post(poll); }
    @Override protected void onDetachedFromWindow() { handler.removeCallbacks(poll); super.onDetachedFromWindow(); }

    @Override
    protected void onDraw(Canvas c) {
        final float s = getHeight() / 1080f;          // the Thor's top screen is 1080 high
        final float size = 64 * s, gap = 12 * s, top = 22 * s;
        float right = getWidth() - 22 * s;
        if (battlePlus) { badge(c, right - size, top, size, 0xCCB0304A); heartPlus(c, right - size, top, size); right -= size + gap; }
        if (noBattles) { badge(c, right - size, top, size, 0xCC2D5FA8); noEncounter(c, right - size, top, size); right -= size + gap; }
        if (speed > 1) { badge(c, right - size, top, size, 0xCC2E8A57); speedMark(c, right - size, top, size, speed); }
    }

    private void badge(Canvas c, float x, float y, float size, int color) {
        r.set(x, y, x + size, y + size);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(color);
        c.drawRoundRect(r, size * 0.22f, size * 0.22f, fill);
        fill.setStyle(Paint.Style.STROKE);
        fill.setStrokeWidth(size * 0.04f);
        fill.setColor(0xB0FFFFFF);
        c.drawRoundRect(r, size * 0.22f, size * 0.22f, fill);
    }

    /** ">>" over the multiplier. */
    private void speedMark(Canvas c, float x, float y, float size, int n) {
        ink.setStyle(Paint.Style.STROKE);
        ink.setStrokeWidth(size * 0.08f);
        float cy = y + size * 0.36f, h = size * 0.13f;
        for (int k = 0; k < 2; k++) {
            float cx = x + size * (0.36f + 0.2f * k);
            c.drawLine(cx - h, cy - h, cx, cy, ink);
            c.drawLine(cx, cy, cx - h, cy + h, ink);
        }
        ink.setStyle(Paint.Style.FILL);
        ink.setTextSize(size * 0.36f);
        c.drawText(n + "×", x + size / 2, y + size * 0.88f, ink);
    }

    /** "!" in a ring, struck through: no random battles. */
    private void noEncounter(Canvas c, float x, float y, float size) {
        float cx = x + size / 2, cy = y + size / 2, rad = size * 0.32f;
        ink.setStyle(Paint.Style.STROKE);
        ink.setStrokeWidth(size * 0.07f);
        c.drawCircle(cx, cy, rad, ink);
        ink.setStyle(Paint.Style.FILL);
        ink.setTextSize(size * 0.42f);
        c.drawText("!", cx, cy + size * 0.15f, ink);
        ink.setStyle(Paint.Style.STROKE);
        float d = rad * 0.72f;
        c.drawLine(cx - d, cy + d, cx + d, cy - d, ink);
    }

    /** A heart with a plus: HP, MP and Limit kept full. */
    private void heartPlus(Canvas c, float x, float y, float size) {
        float cx = x + size * 0.46f, cy = y + size * 0.5f, w = size * 0.3f;
        path.reset();
        path.moveTo(cx, cy + w * 0.95f);
        path.cubicTo(cx - w * 1.4f, cy - w * 0.1f, cx - w * 0.75f, cy - w * 1.1f, cx, cy - w * 0.45f);
        path.cubicTo(cx + w * 0.75f, cy - w * 1.1f, cx + w * 1.4f, cy - w * 0.1f, cx, cy + w * 0.95f);
        path.close();
        ink.setStyle(Paint.Style.FILL);
        c.drawPath(path, ink);
        ink.setStyle(Paint.Style.STROKE);
        ink.setStrokeWidth(size * 0.07f);
        float px = x + size * 0.78f, py = y + size * 0.26f, p = size * 0.12f;
        c.drawLine(px - p, py, px + p, py, ink);
        c.drawLine(px, py - p, px, py + p, ink);
    }
}
