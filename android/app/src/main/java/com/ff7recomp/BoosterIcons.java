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
    // achievement banner (src/ffnx_port/achievements.cpp): shown for a few seconds per unlock
    private long achSeq = -1, achUntil;
    private String achTitle = "";
    private int achPoints;
    private static final long ACH_MS = 5000;
    // Test: FF7_BANNER_TEST=<seconds> in files/env.txt shows a made-up unlock that often, to
    // exercise the banner without unlocking anything (GitHub #21).
    private long testEveryMs, testNext;
    private int testCount;

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
                long seq = s.optLong("achseq", 0);
                if (achSeq >= 0 && seq != achSeq && seq > 0) {
                    achTitle = s.optString("ach");
                    achPoints = s.optInt("achpts");
                    achUntil = System.currentTimeMillis() + ACH_MS;
                    invalidate();
                    handler.postDelayed(BoosterIcons.this::invalidate, ACH_MS + 50);
                }
                achSeq = seq;
                if (testEveryMs > 0 && System.currentTimeMillis() >= testNext) {
                    testNext = System.currentTimeMillis() + testEveryMs;
                    achTitle = "Test banner " + (++testCount);
                    achPoints = 10;
                    achUntil = System.currentTimeMillis() + ACH_MS;
                    android.util.Log.i("FF7Recomp", "banner test " + testCount);
                    invalidate();
                    handler.postDelayed(BoosterIcons.this::invalidate, ACH_MS + 50);
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
        java.io.File env = new java.io.File(Storage.root(c), "env.txt");
        try (java.io.BufferedReader rd = new java.io.BufferedReader(new java.io.FileReader(env))) {
            for (String line; (line = rd.readLine()) != null; )
                if (line.startsWith("FF7_BANNER_TEST="))
                    testEveryMs = 1000L * Math.max(1, Integer.parseInt(line.substring(16).trim()));
        } catch (Exception ignored) { }
        testNext = System.currentTimeMillis() + testEveryMs;
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
        if (System.currentTimeMillis() < achUntil) achievementBanner(c, s);
    }

    /** "Achievement unlocked" with the title and points, top centre. */
    private void achievementBanner(Canvas c, float s) {
        final float w = 760 * s, h = 112 * s, x = (getWidth() - w) / 2, y = 24 * s;
        r.set(x, y, x + w, y + h);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(0xE6141A33);
        c.drawRoundRect(r, 22 * s, 22 * s, fill);
        fill.setStyle(Paint.Style.STROKE);
        fill.setStrokeWidth(3 * s);
        fill.setColor(0xFFE8C15A);
        c.drawRoundRect(r, 22 * s, 22 * s, fill);
        // a gold star
        float cx = x + 64 * s, cy = y + h / 2, ro = 36 * s, ri = 15 * s;
        path.reset();
        for (int k = 0; k < 10; k++) {
            double a = Math.PI / 2 + k * Math.PI / 5;
            float rr = (k % 2 == 0) ? ro : ri;
            float px = cx + (float) Math.cos(a) * rr, py = cy - (float) Math.sin(a) * rr;
            if (k == 0) path.moveTo(px, py); else path.lineTo(px, py);
        }
        path.close();
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(0xFFE8C15A);
        c.drawPath(path, fill);
        Paint.Align was = ink.getTextAlign();
        ink.setTextAlign(Paint.Align.LEFT);
        ink.setStyle(Paint.Style.FILL);
        ink.setColor(0xFFE8C15A);
        ink.setTextSize(30 * s);
        c.drawText("Achievement unlocked", x + 120 * s, y + 44 * s, ink);
        ink.setColor(0xFFFFFFFF);
        ink.setTextSize(38 * s);
        String t = achTitle + (achPoints > 0 ? "  (" + achPoints + ")" : "");
        c.drawText(t, x + 120 * s, y + 90 * s, ink);
        ink.setTextAlign(was);
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
