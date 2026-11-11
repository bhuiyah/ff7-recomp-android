package com.ff7recomp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The AYN Thor's bottom screen while the game runs (docs/ROADMAP.md M7): the party's HP / MP /
 * Limit (and ATB in battle), the enemies' HP in battle, where the party is, play time and gil,
 * and a touch command deck. State comes from the game ten times a second
 * (src/ffnx_port/companion.cpp); the buttons send a short pad press or a command that the
 * game thread runs (save / load state, 2x speed).
 */
public class CompanionView extends View {
    private static native String nativeState();
    /** The game's snapshot (JSON), for the top screen's booster icons (GameActivity). */
    static String snapshot() { return nativeState(); }
    private static native void nativeCommand(int cmd);
    private static native void nativeTap(int buttons);
    private static native void nativeSetArea(int top, int bottom);
    private static native void nativeBattleTap(float x, float y);
    private static native void nativeBattleBack();

    private static final int CMD_SPEED = 1, CMD_SAVE = 2, CMD_LOAD = 3, CMD_BATTLES = 4, CMD_BPLUS = 5;   // companion.cpp / boosters.cpp
    private static final int PAD_Y = 0x8000;   // Triangle: the game's main menu

    private final Handler handler = new Handler(Looper.getMainLooper());
    private JSONObject state = new JSONObject();
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private volatile android.graphics.Bitmap worldMap;   // WorldMap: drawn from the game files
    private final Paint mapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final RectF r = new RectF();

    private static final class Button {
        final String label; final int cmd, pad; final RectF box = new RectF();
        Button(String label, int cmd, int pad) { this.label = label; this.cmd = cmd; this.pad = pad; }
    }
    private final Button[] buttons = {
        new Button("Menu", 0, PAD_Y),
        new Button("Save", CMD_SAVE, 0),
        new Button("Load", CMD_LOAD, 0),
        new Button("Speed", CMD_SPEED, 0),          // game boosters (docs/ROADMAP.md M8)
        new Button("Battles", CMD_BATTLES, 0),
        new Button("Battle+", CMD_BPLUS, 0),
    };
    private Button pressed;
    // Pixel Guide (a game guide app, requested in the comments): a book at the top right opens
    // it here on the bottom screen; when it is not installed the book offers to install it.
    static final String GUIDE_PACKAGE = "app.nanostack.pixelguide";
    private android.content.Intent guide;          // null: not installed
    private long guideCheckedAt;

    private void refreshGuide() {
        guideCheckedAt = android.os.SystemClock.uptimeMillis();
        try { guide = getContext().getPackageManager().getLaunchIntentForPackage(GUIDE_PACKAGE); }
        catch (RuntimeException e) { guide = null; }
    }
    private final RectF bookBox = new RectF();
    private boolean bookPressed;
    private int areaTop = -1, areaBottom = -1;   // the game's battle windows (native code draws there)
    private JSONArray regions;                   // their layout: [sx0, sy0, sx1, sy1, dx, dy, dw, dh]
    private boolean battleNow;
    private String flash;            // a short note after a button ("Saving...")
    private long flashUntil;

    // Cutscenes (a movie, or a field scene that has taken the player's control; companion.cpp
    // "cine") dim the screen with a slow ease, like a theatre's lights. A state has to hold a
    // moment before the lights change, so a script that drops control for a frame does not flicker.
    private static final float DIM_MAX = 0.85f;
    private static final long DIM_HOLD_IN = 250, DIM_HOLD_OUT = 400, DIM_IN_MS = 1200, DIM_OUT_MS = 700;
    private float dim;                   // 0 lit .. 1 dimmed (drawn as DIM_MAX black)
    private boolean dimTarget, seenCine;
    private long seenSince;
    private android.animation.ValueAnimator dimAnim;

    private void updateDim(boolean cine) {
        long now = android.os.SystemClock.uptimeMillis();
        if (cine != seenCine) { seenCine = cine; seenSince = now; }
        if (cine == dimTarget || now - seenSince < (cine ? DIM_HOLD_IN : DIM_HOLD_OUT)) return;
        dimTarget = cine;
        if (dimAnim != null) dimAnim.cancel();
        dimAnim = android.animation.ValueAnimator.ofFloat(dim, cine ? 1f : 0f);
        dimAnim.setDuration((long) ((cine ? DIM_IN_MS : DIM_OUT_MS) * Math.abs((cine ? 1f : 0f) - dim)));
        dimAnim.setInterpolator(new android.view.animation.PathInterpolator(0.45f, 0f, 0.2f, 1f));
        dimAnim.addUpdateListener(a -> { dim = (float) a.getAnimatedValue(); invalidate(); });
        dimAnim.start();
    }

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            try { state = new JSONObject(nativeState()); } catch (JSONException e) { state = new JSONObject(); }
            updateDim(state.optInt("cine") == 1);
            if (guide == null && android.os.SystemClock.uptimeMillis() - guideCheckedAt > 2000) refreshGuide();
            String gs = GuideInstaller.status;
            if (gs != null && System.currentTimeMillis() < GuideInstaller.statusUntil) { flash = gs; flashUntil = GuideInstaller.statusUntil; }
            invalidate();
            handler.postDelayed(this, 100);
        }
    };

    public CompanionView(Context c) {
        super(c);
        text.setColor(Color.WHITE);
        text.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        stroke.setStyle(Paint.Style.STROKE);
        setBackground(null);       // battle: the game's windows show through the top band
        setWillNotDraw(false);
        refreshGuide();
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            worldMap = WorldMap.get(app.getFilesDir(), new java.io.File(Storage.root(app), "game"));
            postInvalidate();
        }, "world-map").start();
    }

    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); handler.post(poll); }
    @Override protected void onDetachedFromWindow() {
        handler.removeCallbacks(poll);
        if (dimAnim != null) dimAnim.cancel();
        super.onDetachedFromWindow();
    }

    // ---- drawing ------------------------------------------------------------------------------

    /** An FF7 menu window: blue gradient, light border, rounded corners. */
    private void window(Canvas c, float l, float t, float rr, float b) {
        r.set(l, t, rr, b);
        float u = getHeight() / 1080f;
        fill.setShader(new LinearGradient(l, t, rr, b, 0xFF2B4BA8, 0xFF0B1A4E, Shader.TileMode.CLAMP));
        c.drawRoundRect(r, 14 * u, 14 * u, fill);
        fill.setShader(null);
        stroke.setColor(0xFFDCDCF0);
        stroke.setStrokeWidth(4 * u);
        c.drawRoundRect(r, 14 * u, 14 * u, stroke);
    }

    private void bar(Canvas c, float l, float t, float w, float h, double frac, int color) {
        frac = Math.max(0, Math.min(1, frac));
        fill.setColor(0xFF101010);
        r.set(l, t, l + w, t + h);
        c.drawRect(r, fill);
        fill.setColor(color);
        r.set(l, t, l + (float) (w * frac), t + h);
        c.drawRect(r, fill);
    }

    private void label(Canvas c, String s, float x, float y, float size, int color, Paint.Align a) {
        text.setTextSize(size);
        text.setColor(color);
        text.setTextAlign(a);
        c.drawText(s, x, y, text);
    }

    private static String clock(long s) {
        return String.format(java.util.Locale.US, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60);
    }

    @Override
    protected void onDraw(Canvas c) {
        drawPanels(c);
        // the cinematic dim, over the panels and the game's battle windows below
        if (dim > 0.001f) {
            fill.setShader(null);
            fill.setColor(Color.BLACK);
            fill.setAlpha(Math.round(255 * DIM_MAX * dim));
            c.drawRect(0, 0, getWidth(), getHeight(), fill);
            fill.setAlpha(255);
        }
    }

    /** On the world map: the map (WorldMap) with the party's position, and the party compact on
     *  its right. */
    private void drawWorld(Canvas c, float l, float t, float r, float b, float u, JSONArray party, int n) {
        float pad = 24 * u, colW = 250 * u;
        float mapH = b - t, mapW = mapH * WorldMap.WORLD_W / WorldMap.WORLD_H;
        if (mapW > r - l - colW - pad) { mapW = r - l - colW - pad; mapH = mapW * WorldMap.WORLD_H / WorldMap.WORLD_W; }
        float mx = l, my = t + (b - t - mapH) / 2;
        window(c, mx, my, mx + mapW, my + mapH);
        float in = 10 * u;
        RectF dst = new RectF(mx + in, my + in, mx + mapW - in, my + mapH - in);
        c.drawBitmap(worldMap, null, dst, mapPaint);
        if (state.has("wx")) {
            float px = dst.left + dst.width() * state.optInt("wx") / WorldMap.WORLD_W;
            float py = dst.top + dst.height() * state.optInt("wz") / WorldMap.WORLD_H;
            float pulse = (float) (0.5 + 0.5 * Math.sin(System.currentTimeMillis() / 180.0));
            fill.setShader(null);
            fill.setColor(0x60FF3030);
            c.drawCircle(px, py, (14 + 10 * pulse) * u, fill);
            fill.setColor(Color.WHITE);
            c.drawCircle(px, py, 9 * u, fill);
            fill.setColor(0xFFE02828);
            c.drawCircle(px, py, 6.5f * u, fill);
            postInvalidateDelayed(50);   // keep the marker pulsing
        }
        // party, compact
        float x = mx + mapW + pad, w = r - x, rowH = (b - t - 2 * pad) / 3f;
        for (int i = 0; i < 3; i++) {
            float rt = t + i * (rowH + pad);
            window(c, x, rt, r, rt + rowH);
            JSONObject m = i < n ? party.optJSONObject(i) : null;
            if (m == null) continue;
            float ix = x + 18 * u, iw = w - 36 * u;
            label(c, m.optString("name"), ix, rt + 44 * u, 34 * u, Color.WHITE, Paint.Align.LEFT);
            int hp = m.optInt("hp"), mhp = Math.max(1, m.optInt("mhp")), mp = m.optInt("mp"), mmp = Math.max(1, m.optInt("mmp"));
            double hf = (double) hp / mhp;
            label(c, "HP " + hp, ix, rt + 92 * u, 26 * u, hf <= 0.25 ? 0xFFFFD040 : 0xFFDCDCF0, Paint.Align.LEFT);
            bar(c, ix, rt + 100 * u, iw, 12 * u, hf, hf <= 0.25 ? 0xFFE0B020 : 0xFF40D070);
            label(c, "MP " + mp, ix, rt + 148 * u, 26 * u, 0xFFDCDCF0, Paint.Align.LEFT);
            bar(c, ix, rt + 156 * u, iw, 12 * u, (double) mp / mmp, 0xFF4090F0);
        }
    }

    private void drawPanels(Canvas c) {
        final float W = getWidth(), H = getHeight(), u = H / 1080f, pad = 24 * u;
        if (state.optInt("ready") != 1) {
            fill.setShader(null);
            fill.setColor(Color.BLACK);
            c.drawRect(0, 0, W, H, fill);      // blank until a game is under way (companion.cpp)
            return;
        }
        final String mode = state.optString("mode");
        final boolean battle = "battle".equals(mode);
        battleNow = battle;

        // command deck at the bottom (in battle the first button is Back)
        float deckH = 150 * u, deckT = H - pad - deckH;

        if (battle) {
            // enemies, compact, on top: two columns
            JSONArray en = state.optJSONArray("enemies");
            int ne = en == null ? 0 : en.length();
            int rows = Math.max(1, (ne + 1) / 2);
            float rowH = 50 * u, enT = pad, enB = enT + 16 * u + rows * rowH;
            fill.setShader(null);
            fill.setColor(Color.BLACK);
            c.drawRect(0, 0, W, enB + pad, fill);
            window(c, pad, enT, W - pad, enB);
            float colW = (W - pad * 2 - 80 * u) / 2f;   // 20 margins, 40 between the columns
            for (int i = 0; i < ne; i++) {
                JSONObject e = en.optJSONObject(i);
                int hp = e.optInt("hp"), mhp = Math.max(1, e.optInt("mhp"));
                float x = pad + 20 * u + (i % 2) * (colW + 40 * u), y = enT + 8 * u + (i / 2) * rowH;
                String nm = e.optString("name");
                label(c, nm.isEmpty() ? "#" + e.optInt("slot") : nm, x, y + 28 * u, 28 * u, hp > 0 ? Color.WHITE : 0xFF8090B0, Paint.Align.LEFT);
                label(c, hp + "/" + mhp, x + colW, y + 28 * u, 26 * u, 0xFFDCDCF0, Paint.Align.RIGHT);
                bar(c, x, y + 36 * u, colW, 8 * u, (double) hp / mhp, 0xFFE05050);
            }
            // the game's windows in between (native code draws them; leave the area clear)
            int areaTop = (int) (enB + pad), areaBottom = (int) (deckT - pad);
            if (areaTop != this.areaTop || areaBottom != this.areaBottom) {
                this.areaTop = areaTop; this.areaBottom = areaBottom;
                nativeSetArea(areaTop, areaBottom);
            }
            fill.setColor(Color.BLACK);
            c.drawRect(0, areaBottom, W, H, fill);
            regions = state.optJSONArray("layout");
        } else {
            regions = null;
            fill.setShader(null);
            fill.setColor(Color.BLACK);
            c.drawRect(0, 0, W, H, fill);
            // top: where, play time, gil
            float top = pad, topH = 110 * u;
            window(c, pad, top, W - pad, top + topH);
            String where = state.optString("loc");
            float rightX = W - pad - 30 * u;
            {                              // the book, top right: Pixel Guide on this screen
                float s = topH - 24 * u, bw = s * 1.25f;
                bookBox.set(W - pad - 14 * u - bw, top + 12 * u, W - pad - 14 * u, top + 12 * u + s);
                if (bookPressed) {
                    fill.setShader(null);
                    fill.setColor(0x55FFFFFF);
                    c.drawRoundRect(bookBox, 12 * u, 12 * u, fill);
                }
                drawBook(c, bookBox, u);
                if (guide == null) {       // not installed: a download badge (tap to install)
                    float br = 15 * u, bx = bookBox.right - br, by = bookBox.bottom - br;
                    fill.setColor(GuideInstaller.busy ? 0xFFE0B020 : 0xFF40B060);
                    c.drawCircle(bx, by, br, fill);
                    stroke.setColor(Color.WHITE);
                    stroke.setStrokeWidth(3.5f * u);
                    c.drawLine(bx, by - br * 0.55f, bx, by + br * 0.45f, stroke);
                    c.drawLine(bx - br * 0.45f, by, bx, by + br * 0.45f, stroke);
                    c.drawLine(bx + br * 0.45f, by, bx, by + br * 0.45f, stroke);
                }
                rightX = bookBox.left - 24 * u;
            }
            label(c, where.isEmpty() ? "-" : where, pad + 30 * u, top + 70 * u, 46 * u, Color.WHITE, Paint.Align.LEFT);
            label(c, clock(state.optLong("time")) + "    " + state.optLong("gil") + " G", rightX, top + 70 * u, 38 * u,
                  0xFFDCDCF0, Paint.Align.RIGHT);
            // party
            float bodyT = top + topH + pad, bodyB = deckT - pad;
            float partyR = W - pad;
            JSONArray party = state.optJSONArray("party");
            int n = party == null ? 0 : party.length();
            float rowH = (bodyB - bodyT - pad * 2) / 3f;
            if ("world".equals(mode) && worldMap != null) {
                drawWorld(c, pad, bodyT, W - pad, bodyB, u, party, n);
                n = 0;   // the party is drawn compactly beside the map
            }
            for (int i = 0; i < 3 && !("world".equals(mode) && worldMap != null); i++) {
                float t = bodyT + i * (rowH + pad), b = t + rowH;
                window(c, pad, t, partyR, b);
                JSONObject m = i < n ? party.optJSONObject(i) : null;
                if (m == null) continue;
                float x = pad + 28 * u, colW = partyR - pad - 56 * u;
                label(c, m.optString("name"), x, t + 58 * u, 48 * u, Color.WHITE, Paint.Align.LEFT);
                label(c, "Lv " + m.optInt("lv"), x + colW, t + 58 * u, 36 * u, 0xFFDCDCF0, Paint.Align.RIGHT);
                int hp = m.optInt("hp"), mhp = Math.max(1, m.optInt("mhp")), mp = m.optInt("mp"), mmp = Math.max(1, m.optInt("mmp"));
                float barW = colW * 0.48f, bh = 16 * u, by = t + 112 * u;
                double hf = (double) hp / mhp;
                label(c, "HP " + hp + "/" + mhp, x, by - 6 * u, 32 * u, hf <= 0.25 ? 0xFFFFD040 : Color.WHITE, Paint.Align.LEFT);
                bar(c, x, by + 4 * u, barW, bh, hf, hf <= 0.25 ? 0xFFE0B020 : 0xFF40D070);
                float x2 = x + colW * 0.52f;
                label(c, "MP " + mp + "/" + mmp, x2, by - 6 * u, 32 * u, Color.WHITE, Paint.Align.LEFT);
                bar(c, x2, by + 4 * u, barW, bh, (double) mp / mmp, 0xFF4090F0);
                float y3 = by + bh + 48 * u;
                if (y3 + bh < b) {
                    label(c, "Limit " + m.optInt("limlv"), x, y3 - 6 * u, 30 * u, 0xFFFFB0D0, Paint.Align.LEFT);
                    bar(c, x, y3 + 4 * u, barW, bh, m.optInt("limit") / 255.0, 0xFFF060B0);
                }
            }
        }

        float bw = (W - pad * (buttons.length + 1)) / buttons.length;
        for (int i = 0; i < buttons.length; i++) {
            Button b = buttons[i];
            float l = pad + i * (bw + pad);
            b.box.set(l, deckT, l + bw, deckT + deckH);
            window(c, b.box.left, b.box.top, b.box.right, b.box.bottom);
            boolean on = (b.cmd == CMD_SPEED && state.optInt("speed", 1) > 1)
                    || (b.cmd == CMD_BATTLES && state.optInt("noenc") == 1)
                    || (b.cmd == CMD_BPLUS && state.optInt("bplus") == 1);
            if (b == pressed || on) {
                fill.setColor(on ? 0x6600FF88 : 0x55FFFFFF);
                c.drawRoundRect(b.box, 14 * u, 14 * u, fill);
            }
            String text = (battle && b.pad == PAD_Y) ? "Back" : b.label;
            if (b.cmd == CMD_SPEED) text = "Speed " + Math.max(1, state.optInt("speed", 1)) + "×";
            else if (b.cmd == CMD_BATTLES) text = state.optInt("noenc") == 1 ? "No battles" : "Battles";
            label(c, text, b.box.centerX(), b.box.centerY() + 12 * u, 34 * u, Color.WHITE, Paint.Align.CENTER);
        }

        if (flash != null && System.currentTimeMillis() < flashUntil)
            label(c, flash, W / 2, deckT - 20 * u, 38 * u, 0xFFFFE060, Paint.Align.CENTER);
    }

    /** An open book: two pages with a few lines, on the window's colours. */
    private void drawBook(Canvas c, RectF b, float u) {
        float cx = b.centerX(), w = b.width() * 0.42f, top = b.top + b.height() * 0.2f, bot = b.bottom - b.height() * 0.16f;
        float sag = b.height() * 0.08f;
        fill.setShader(null);
        for (int side = -1; side <= 1; side += 2) {
            android.graphics.Path p = new android.graphics.Path();
            p.moveTo(cx, top + sag);
            p.quadTo(cx + side * w * 0.5f, top - sag, cx + side * w, top);
            p.lineTo(cx + side * w, bot);
            p.quadTo(cx + side * w * 0.5f, bot - sag * 2, cx, bot);
            p.close();
            fill.setColor(0xFFF2EEDC);
            c.drawPath(p, fill);
            stroke.setColor(0xFF2A3A80);
            stroke.setStrokeWidth(3 * u);
            c.drawPath(p, stroke);
            stroke.setStrokeWidth(2 * u);
            stroke.setColor(0xFF8090B0);
            for (int k = 1; k <= 3; k++) {
                float y = top + sag + (bot - top - sag) * k / 4.5f;
                c.drawLine(cx + side * w * 0.18f, y, cx + side * w * 0.82f, y - sag * 0.6f, stroke);
            }
        }
    }

    /** Pixel Guide on this (the bottom) screen, through GuideHostActivity: the panels step
     *  aside while it is open and come back when it is closed. */
    private void openGuide() {
        try {
            android.content.Intent i = new android.content.Intent(getContext(), GuideHostActivity.class);
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_NO_ANIMATION);
            android.app.ActivityOptions o = android.app.ActivityOptions.makeBasic();
            if (getDisplay() != null) o.setLaunchDisplayId(getDisplay().getDisplayId());
            getContext().startActivity(i, o.toBundle());
        } catch (RuntimeException e) {
            flash = "Pixel Guide could not open";
            flashUntil = System.currentTimeMillis() + 2500;
            invalidate();
        }
    }

    // ---- touch --------------------------------------------------------------------------------

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        if (state.optInt("ready") != 1) { pressed = null; return true; }   // blank: no buttons
        switch (e.getActionMasked()) {
        case MotionEvent.ACTION_DOWN:
            pressed = null;
            bookPressed = !battleNow && !bookBox.isEmpty() && bookBox.contains(x, y);
            if (bookPressed) { invalidate(); return true; }
            for (Button b : buttons) if (b.box.contains(x, y)) pressed = b;
            if (pressed == null && battleNow && y >= areaTop && y < areaBottom) battleTap(x, y);
            invalidate();
            return true;
        case MotionEvent.ACTION_UP:
            if (bookPressed) {
                bookPressed = false;
                if (bookBox.contains(x, y)) {
                    refreshGuide();          // installed or removed meanwhile
                    if (guide != null) openGuide();
                    else GuideInstaller.start(getContext(), getDisplay() != null ? getDisplay().getDisplayId() : -1);
                }
                invalidate();
                return true;
            }
            Button b = pressed;
            pressed = null;
            if (b != null && b.box.contains(x, y)) fire(b);
            invalidate();
            return true;
        case MotionEvent.ACTION_CANCEL:
            pressed = null;
            bookPressed = false;
            invalidate();
            return true;
        default:
            return true;
        }
    }

    /** A tap on the game's battle windows: back to the game's 2D coordinates through the
     *  layout native code drew them with; the game thread steers the cursor there. A tap
     *  outside them (choosing a target on the top screen) confirms. */
    private void battleTap(float x, float y) {
        performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
        for (int i = 0; regions != null && i < regions.length(); i++) {
            JSONArray r = regions.optJSONArray(i);
            if (r == null || r.length() < 8) continue;
            float dx = (float) r.optDouble(4), dy = (float) r.optDouble(5), dw = (float) r.optDouble(6), dh = (float) r.optDouble(7);
            if (x < dx || x >= dx + dw || y < dy || y >= dy + dh) continue;
            float sx0 = (float) r.optDouble(0), sy0 = (float) r.optDouble(1), sx1 = (float) r.optDouble(2), sy1 = (float) r.optDouble(3);
            nativeBattleTap(sx0 + (x - dx) / dw * (sx1 - sx0), sy0 + (y - dy) / dh * (sy1 - sy0));
            return;
        }
        nativeBattleTap(-1, -1);
    }

    private void fire(Button b) {
        performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
        if (battleNow && b.pad == PAD_Y) { nativeBattleBack(); return; }
        if (b.pad != 0) {
            // held long enough for the game to see it (it reads the pad once per frame)
            nativeTap(b.pad);
            handler.postDelayed(() -> nativeTap(0), 150);
            return;
        }
        nativeCommand(b.cmd);
        flash = b.cmd == CMD_SAVE ? "Saving state..." : b.cmd == CMD_LOAD ? "Loading state..." : null;
        flashUntil = System.currentTimeMillis() + 1500;
    }
}
