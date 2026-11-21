package com.ff7recomp;

import android.content.Context;
import android.content.SharedPreferences;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The AYN Thor's bottom screen while the game runs (docs/ROADMAP.md M7): where the party is,
 * play time and gil; a row of tabs (Auto, Materia, Map, Items, Key items, Extras); the party
 * (HP, MP / ATB, Limit) beside the tab's panel; and a touch command deck. Auto picks the panel
 * by itself: the enemies (what Sense would tell) in battle, the map on the world map, the items
 * elsewhere. « hides the party so the panel takes the whole width.
 *
 * State comes from the game ten times a second (src/ffnx_port/companion.cpp); the buttons send
 * a short pad press or a command that the game thread runs (save / load state, boosters).
 * Nothing from the game ships here: names and the map come from the player's own copy.
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

    // FF7's menu colours
    private static final int WHITE = 0xFFFFFFFF, LIGHT = 0xFFDCDCF0, LABEL = 0xFF8FB4E8, DIM = 0xFF8090B0,
            GOLD = 0xFFF5C84A, LOW = 0xFFFFD040, DIVIDER = 0x30FFFFFF;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private JSONObject state = new JSONObject();
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint emoji = new Paint(Paint.ANTI_ALIAS_FLAG);
    private volatile android.graphics.Bitmap worldMap;   // WorldMap: drawn from the game files
    private final Paint mapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private volatile android.graphics.Bitmap[] portraits;   // Portraits: from the game files
    private final RectF r = new RectF();
    private final SharedPreferences prefs;

    private static final class Button {
        final String label, icon; final int cmd, pad; final RectF box = new RectF();
        Button(String label, String icon, int cmd, int pad) { this.label = label; this.icon = icon; this.cmd = cmd; this.pad = pad; }
    }
    private final Button[] buttons = {
        new Button("Menu", "☰", 0, PAD_Y),
        new Button("Save", "💾", CMD_SAVE, 0),
        new Button("Load", "📂", CMD_LOAD, 0),
        new Button("Speed", "⏩", CMD_SPEED, 0),          // game boosters (docs/ROADMAP.md M8)
        new Button("Battles", "🛡", CMD_BATTLES, 0),
        new Button("Battle+", "✚", CMD_BPLUS, 0),
    };
    private Button pressed;
    // Settings > Screen > Second screen buttons (requested in the comments): off, the panels
    // take the deck's place and the controller does everything.
    private boolean deck = true;
    /** Side by side: the header's hide button (null: none), on the header's left or right end. */
    private Runnable onHide;
    private boolean hideAtLeft, hidePressed;
    private final RectF hideBox = new RectF();
    void setOnHide(Runnable r, boolean atLeft) { onHide = r; hideAtLeft = atLeft; if (r == null) hideBox.setEmpty(); invalidate(); }
    void setDeck(boolean on) { deck = on; if (!on) for (Button b : buttons) b.box.setEmpty(); invalidate(); }

    // ---- tabs ---------------------------------------------------------------------------------
    private static final String[] TABS = { "Auto", "Materia", "Map", "Items", "Key items", "Extras" };
    private static final int V_ENEMIES = 0, V_MATERIA = 1, V_MAP = 2, V_ITEMS = 3, V_KEYS = 4, V_EXTRAS = 5;
    private int tab;                                   // 0 Auto, else the view (V_MATERIA..V_EXTRAS)
    private boolean wide;                              // the party column hidden («)
    private final RectF[] tabBoxes = new RectF[TABS.length + 1];
    private int pressedTab = -1;
    private final RectF panelBox = new RectF();
    private final float[] scroll = new float[6];
    private final float[] contentH = new float[6];
    private int shownView = V_ITEMS;
    private float dragY0, dragScroll0;
    private boolean dragging, dragMoved;
    // Extras: its toggle buttons, as drawn (box, command)
    private final List<RectF> toggleBoxes = new ArrayList<>();
    private final List<Integer> toggleCmds = new ArrayList<>();

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
        emoji.setTextAlign(Paint.Align.CENTER);
        setBackground(null);       // battle: the game's windows show through the top band
        setWillNotDraw(false);
        refreshGuide();
        for (int i = 0; i < tabBoxes.length; i++) tabBoxes[i] = new RectF();
        prefs = c.getSharedPreferences("companion", Context.MODE_PRIVATE);
        tab = Math.max(0, Math.min(TABS.length - 1, prefs.getInt("tab", 0)));
        wide = prefs.getBoolean("wide", false);
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            java.io.File game = new java.io.File(Storage.root(app), "game");
            portraits = Portraits.load(game);
            gameFont = GameFont.load(game);     // the game's own font (FF9Recomp 0.1.17 does the same)
            postInvalidate();
            worldMap = WorldMap.get(app.getFilesDir(), game);
            postInvalidate();
        }, "world-map").start();
    }

    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); handler.post(poll); }
    @Override protected void onDetachedFromWindow() {
        handler.removeCallbacks(poll);
        if (dimAnim != null) dimAnim.cancel();
        super.onDetachedFromWindow();
    }

    // ---- drawing helpers ----------------------------------------------------------------------

    /** A tall, narrow area (Side by side: the panels at the game's right on one screen). The panels
     *  then stack: a two-row header, tabs and deck, the party above the details. */
    private boolean narrow() { return getWidth() < getHeight() * 0.8f; }

    private float u() {
        return narrow() ? Math.min(getHeight() / 1500f, getWidth() / 620f) : Math.min(getHeight() / 1080f, getWidth() / 1240f);
    }

    /** An FF7 menu window: blue gradient, light border, rounded corners. */
    private void window(Canvas c, float l, float t, float rr, float b) {
        r.set(l, t, rr, b);
        float u = u();
        fill.setShader(new LinearGradient(l, t, rr, b, 0xFF2B4BA8, 0xFF0B1A4E, Shader.TileMode.CLAMP));
        c.drawRoundRect(r, 14 * u, 14 * u, fill);
        fill.setShader(null);
        stroke.setColor(0xFFDCDCF0);
        stroke.setStrokeWidth(4 * u);
        c.drawRoundRect(r, 14 * u, 14 * u, stroke);
    }

    /** A gold window: the selected tab, a lit booster, a warning banner. */
    private void goldWindow(Canvas c, RectF b, boolean solid) {
        float u = u();
        fill.setShader(solid ? new LinearGradient(b.left, b.top, b.left, b.bottom, 0xFFF0CF6A, 0xFFB98A28, Shader.TileMode.CLAMP)
                             : new LinearGradient(b.left, b.top, b.right, b.bottom, 0xFF5A4410, 0xFF2A1E06, Shader.TileMode.CLAMP));
        c.drawRoundRect(b, 14 * u, 14 * u, fill);
        fill.setShader(null);
        stroke.setColor(0xFFFFE08A);
        stroke.setStrokeWidth(4 * u);
        c.drawRoundRect(b, 14 * u, 14 * u, stroke);
    }

    private void bar(Canvas c, float l, float t, float w, float h, double frac, int color) {
        bar(c, l, t, w, h, frac, color, color);
    }

    private void bar(Canvas c, float l, float t, float w, float h, double frac, int c0, int c1) {
        frac = Math.max(0, Math.min(1, frac));
        fill.setShader(null);
        fill.setColor(0xFF0A0E1E);
        r.set(l, t, l + w, t + h);
        c.drawRoundRect(r, h / 2, h / 2, fill);
        if (frac <= 0) return;
        fill.setShader(new LinearGradient(l, t, l + w, t, c0, c1, Shader.TileMode.CLAMP));
        r.set(l, t, l + Math.max(h, (float) (w * frac)), t + h);
        c.drawRoundRect(r, h / 2, h / 2, fill);
        fill.setShader(null);
    }

    /** FF7's text font from the game files, once read (null: Android's sans-serif). */
    private volatile GameFont gameFont;

    private void label(Canvas c, String s, float x, float y, float size, int color, Paint.Align a) {
        GameFont f = gameFont;
        if (f != null) { f.draw(c, s, x, y, size, color, a, text); return; }
        text.setTextSize(size);
        text.setColor(color);
        text.setTextAlign(a);
        c.drawText(s, x, y, text);
    }

    /** Text shrunk (to 70%) and then cut to fit maxW. */
    private void fitLabel(Canvas c, String s, float x, float y, float size, float maxW, int color, Paint.Align a) {
        float w = measure(s, size);
        if (w > maxW) {
            float sz = Math.max(size * 0.7f, size * maxW / w);
            if (measure(s, sz) > maxW) {
                int n = s.length();
                while (n > 0 && measure(s.substring(0, n) + "…", sz) > maxW) n--;
                s = s.substring(0, n) + "…";
            }
            size = sz;
        }
        label(c, s, x, y, size, color, a);
    }

    private float measure(String s, float size) {
        GameFont f = gameFont;
        if (f != null) return f.measure(s, size, text);
        text.setTextSize(size);
        return text.measureText(s);
    }

    private static String clock(long s) {
        return String.format(java.util.Locale.US, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60);
    }

    private static String grouped(long v) { return String.format(java.util.Locale.US, "%,d", v); }

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

    // ---- the screen ---------------------------------------------------------------------------

    private void drawPanels(Canvas c) {
        final float W = getWidth(), H = getHeight(), u = u(), pad = 14 * u;
        fill.setShader(null);
        if (state.optInt("ready") != 1) {
            fill.setColor(Color.BLACK);
            c.drawRect(0, 0, W, H, fill);      // blank until a game is under way (companion.cpp)
            return;
        }
        final String mode = state.optString("mode");
        final boolean battle = "battle".equals(mode);
        battleNow = battle;
        final boolean nar = narrow();
        float deckH = deck ? (nar ? 212 * u : 110 * u) : 0, deckT = deck ? H - pad - deckH : H;

        JSONArray layout = state.optJSONArray("layout");
        if (battle && layout != null && layout.length() > 0) {
            drawBattleWindows(c, W, u, 24 * u, deckT);   // the game's windows on this screen (testing)
        } else {
            regions = null;
            fill.setColor(Color.BLACK);
            c.drawRect(0, 0, W, H, fill);
            float top = pad, headH = nar ? 150 * u : 96 * u;
            drawHeader(c, pad, top, W - pad, top + headH, u);
            float tabT = top + headH + 12 * u, tabH = nar ? 134 * u : 62 * u;
            drawTabs(c, pad, tabT, W - pad, tabT + tabH, u);
            float bodyT = tabT + tabH + 12 * u, bodyB = deckT - (deck ? 12 * u : pad);
            int view = resolveView(mode);
            boolean party = !wide;
            float panelL = pad, panelT = bodyT;
            if (party && nar) {              // the party across the top, the details below
                // as many cards as the party has (empty slots would only shrink them)
                JSONArray members = state.optJSONArray("party");
                int slots = Math.max(1, Math.min(3, members == null ? 0 : members.length()));
                float partyH = Math.min(slots * 180 * u + (slots - 1) * 12 * u, (bodyB - bodyT) * 0.5f);
                drawParty(c, pad, bodyT, W - pad, bodyT + partyH, u, battle, slots);
                panelT = bodyT + partyH + 12 * u;
            } else if (party) {
                float colW = Math.min(420 * u, W * 0.36f);
                drawParty(c, pad, bodyT, pad + colW, bodyB, u, battle, 3);
                panelL = pad + colW + 12 * u;
            }
            panelBox.set(panelL, panelT, W - pad, bodyB);
            window(c, panelBox.left, panelBox.top, panelBox.right, panelBox.bottom);
            if (view != shownView) { shownView = view; }
            float in = 20 * u;
            RectF inner = new RectF(panelBox.left + in, panelBox.top + 12 * u, panelBox.right - in, panelBox.bottom - 12 * u);
            c.save();
            c.clipRect(panelBox.left + 4 * u, panelBox.top + 4 * u, panelBox.right - 4 * u, panelBox.bottom - 4 * u);
            float maxScroll = Math.max(0, contentH[view] - inner.height());
            scroll[view] = Math.max(0, Math.min(maxScroll, scroll[view]));
            float y0 = inner.top - scroll[view];
            toggleBoxes.clear();
            toggleCmds.clear();
            float end;
            switch (view) {
            case V_ENEMIES: end = drawEnemies(c, inner, y0, u); break;
            case V_MATERIA: end = drawMateria(c, inner, y0, u); break;
            case V_MAP: drawMap(c, inner, u); end = y0 + inner.height(); scroll[view] = 0; break;
            case V_KEYS: end = drawList(c, inner, y0, u, state.optJSONArray("keys"), false); break;
            case V_EXTRAS: end = drawExtras(c, inner, y0, u); break;
            default: end = drawList(c, inner, y0, u, state.optJSONArray("inv"), true); break;
            }
            contentH[view] = end - y0;
            c.restore();
            if (contentH[view] > inner.height() + 1) {     // a scroll thumb
                float trackH = panelBox.height() - 40 * u, th = Math.max(40 * u, trackH * inner.height() / contentH[view]);
                float ty = panelBox.top + 20 * u + (trackH - th) * (scroll[view] / Math.max(1, contentH[view] - inner.height()));
                fill.setColor(0x60FFFFFF);
                r.set(panelBox.right - 12 * u, ty, panelBox.right - 7 * u, ty + th);
                c.drawRoundRect(r, 3 * u, 3 * u, fill);
            }
        }
        drawDeck(c, W, u, pad, deckT, deckH, battle);
        if (flash != null && System.currentTimeMillis() < flashUntil) {
            float fy = deckT - 22 * u;
            float fw = measure(flash, 36 * u) + 40 * u;
            fill.setShader(null);
            fill.setColor(0xD0000000);
            r.set(W / 2 - fw / 2, fy - 44 * u, W / 2 + fw / 2, fy + 14 * u);
            c.drawRoundRect(r, 12 * u, 12 * u, fill);
            label(c, flash, W / 2, fy, 36 * u, 0xFFFFE060, Paint.Align.CENTER);
        }
    }

    private int resolveView(String mode) {
        if (tab > 0) return tab;                       // TABS[i] is view i past Auto
        if ("battle".equals(mode)) return V_ENEMIES;
        if ("world".equals(mode)) return V_MAP;
        return V_ITEMS;
    }

    private void drawHeader(Canvas c, float l, float t, float rr, float b, float u) {
        window(c, l, t, rr, b);
        float h = b - t;
        final boolean nar = narrow();
        final float l0 = l, r0 = rr;   // the second row (narrow) keeps the whole width
        if (onHide != null) {          // Side by side: hide the panels, on the edge next to the game
            float hs = nar ? 56 * u : Math.min(h - 24 * u, 72 * u);
            float hx = hideAtLeft ? l + 12 * u : rr - 12 * u - hs;
            hideBox.set(hx, t + 12 * u, hx + hs, t + 12 * u + hs);
            drawHideButton(c, hideBox, u);
            if (hideAtLeft) l = hideBox.right - 6 * u; else rr = hideBox.left - 6 * u;
        } else hideBox.setEmpty();
        {                              // the book, top right: Pixel Guide on this screen
            float s = (nar ? 72 * u : h) - 24 * u, bw = s * 1.25f;
            bookBox.set(rr - 14 * u - bw, t + 12 * u, rr - 14 * u, t + 12 * u + s);
            if (bookPressed) {
                fill.setShader(null);
                fill.setColor(0x55FFFFFF);
                c.drawRoundRect(bookBox, 12 * u, 12 * u, fill);
            }
            drawBook(c, bookBox, u);
            if (guide == null) {       // not installed: a download badge (tap to install)
                float br = 14 * u, bx = bookBox.right - br, by = bookBox.bottom - br;
                fill.setColor(GuideInstaller.busy ? 0xFFE0B020 : 0xFF40B060);
                c.drawCircle(bx, by, br, fill);
                stroke.setColor(Color.WHITE);
                stroke.setStrokeWidth(3.5f * u);
                c.drawLine(bx, by - br * 0.55f, bx, by + br * 0.45f, stroke);
                c.drawLine(bx - br * 0.45f, by, bx, by + br * 0.45f, stroke);
                c.drawLine(bx + br * 0.45f, by, bx, by + br * 0.45f, stroke);
            }
        }
        String gil = grouped(state.optLong("gil")) + " G", time = clock(state.optLong("time"));
        if (nar) {                     // the location beside the book; time and gil below it
            String where = state.optString("loc");
            fitLabel(c, where.isEmpty() ? "-" : where, l + 24 * u, t + 56 * u, 40 * u, bookBox.left - l - 40 * u, WHITE, Paint.Align.LEFT);
            float y = t + h - 26 * u;
            label(c, "Time", l0 + 24 * u, y, 24 * u, LABEL, Paint.Align.LEFT);
            label(c, time, l0 + 24 * u + measure("Time ", 24 * u), y, 34 * u, WHITE, Paint.Align.LEFT);
            label(c, gil, r0 - 24 * u, y, 34 * u, GOLD, Paint.Align.RIGHT);
            label(c, "Gil", r0 - 24 * u - measure(gil + " ", 34 * u), y, 24 * u, LABEL, Paint.Align.RIGHT);
            return;
        }
        float gx = bookBox.left - 34 * u, gw = Math.max(measure(gil, 36 * u), measure("Gil", 24 * u));
        label(c, "Gil", gx - gw, t + 36 * u, 24 * u, LABEL, Paint.Align.LEFT);
        label(c, gil, gx - gw, t + 76 * u, 36 * u, GOLD, Paint.Align.LEFT);
        float tx = gx - gw - 34 * u, tw = measure(time, 36 * u);
        label(c, "Time", tx - tw, t + 36 * u, 24 * u, LABEL, Paint.Align.LEFT);
        label(c, time, tx - tw, t + 76 * u, 36 * u, WHITE, Paint.Align.LEFT);
        String where = state.optString("loc");
        fitLabel(c, where.isEmpty() ? "-" : where, l + 24 * u, t + h / 2 + 15 * u, 44 * u, tx - tw - l - 50 * u, WHITE, Paint.Align.LEFT);
    }

    private void drawTabs(Canvas c, float l, float t, float rr, float b, float u) {
        float gap = 10 * u, small = 70 * u;
        int rows = narrow() ? 2 : 1, first = rows == 1 ? TABS.length + 1 : 4;   // narrow: 4 tabs, then the rest and «
        float rowH = (b - t - gap * (rows - 1)) / rows;
        for (int i = 0; i <= TABS.length; i++) {
            int row = i < first ? 0 : 1, from = row == 0 ? 0 : first, to = row == 0 ? first : TABS.length + 1;
            boolean last = to == TABS.length + 1;       // the row with « at its end
            int n = to - from - (last ? 1 : 0);
            float w = (rr - l - (last ? small + gap * n : gap * (n - 1))) / n;
            float x0 = l + (i - from) * (w + gap), x1 = i < TABS.length ? x0 + w : rr, y0 = t + row * (rowH + gap);
            RectF box = tabBoxes[i];
            box.set(x0, y0, x1, y0 + rowH);
            boolean sel = i < TABS.length && i == tab;
            if (sel) goldWindow(c, box, true);
            else window(c, box.left, box.top, box.right, box.bottom);
            if (i == pressedTab) {
                fill.setColor(0x40FFFFFF);
                c.drawRoundRect(box, 14 * u, 14 * u, fill);
            }
            String s = i < TABS.length ? TABS[i] : (wide ? "»" : "«");
            fitLabel(c, s, box.centerX(), box.centerY() + 11 * u, 31 * u, box.width() - 16 * u, sel ? 0xFF2A1A00 : WHITE, Paint.Align.CENTER);
        }
    }

    private static final int[] PORTRAIT = { 0xFF7A6430, 0xFF5A4636, 0xFF7A3446, 0xFF8A3A6A, 0xFF8A4020,
                                            0xFF3A6A3A, 0xFF4A4A7A, 0xFF6A1A22, 0xFF3A5A7A };

    /** The party: a card each (portrait letter, name, Lv, HP, MP or ATB, Limit). */
    private void drawParty(Canvas c, float l, float t, float rr, float b, float u, boolean battle, int slots) {
        JSONArray party = state.optJSONArray("party");
        int n = party == null ? 0 : party.length();
        float gap = 12 * u, cardH = Math.min(260 * u, (b - t - (slots - 1) * gap) / slots);
        for (int i = 0; i < slots; i++) {
            float ct = t + i * (cardH + gap), cb = ct + cardH;
            window(c, l, ct, rr, cb);
            JSONObject m = i < n ? party.optJSONObject(i) : null;
            if (m == null) continue;
            String name = m.optString("name");
            int id = m.optInt("id");
            android.graphics.Bitmap[] faces = portraits;
            android.graphics.Bitmap face = faces != null && id >= 0 && id < faces.length ? faces[id] : null;
            // the game's own face (84 x 96) where the files have it, else a letter tile
            float ph = Math.min(cardH - 36 * u, 120 * u);
            float ps = face != null ? ph * Portraits.FACE_W / Portraits.FACE_H : Math.min(92 * u, ph);
            float px = l + 18 * u, py = ct + (cardH - (face != null ? ph : ps)) / 2;
            fill.setShader(null);
            r.set(px, py, px + ps, py + (face != null ? ph : ps));
            if (face != null) {
                c.save();
                android.graphics.Path clip = new android.graphics.Path();
                clip.addRoundRect(r, 8 * u, 8 * u, android.graphics.Path.Direction.CW);
                c.clipPath(clip);
                c.drawBitmap(face, null, r, mapPaint);
                c.restore();
            } else {
                fill.setColor(PORTRAIT[Math.max(0, Math.min(8, id))]);
                c.drawRoundRect(r, 8 * u, 8 * u, fill);
                label(c, name.isEmpty() ? "?" : name.substring(0, 1), px + ps / 2, py + ps / 2 + ps * 0.2f, ps * 0.58f, WHITE, Paint.Align.CENTER);
            }
            stroke.setColor(0xFFDCDCF0);
            stroke.setStrokeWidth(3 * u);
            c.drawRoundRect(r, 8 * u, 8 * u, stroke);
            float x = px + ps + 18 * u, xr = rr - 18 * u;
            label(c, "Lv " + m.optInt("lv"), xr, ct + 52 * u, 26 * u, LABEL, Paint.Align.RIGHT);
            fitLabel(c, name, x, ct + 52 * u, 36 * u, xr - x - measure("Lv 99", 26 * u) - 12 * u, WHITE, Paint.Align.LEFT);
            int hp = m.optInt("hp"), mhp = Math.max(1, m.optInt("mhp"));
            double hf = (double) hp / mhp;
            float lw = 58 * u, bx = x + lw, bw = xr - bx - 96 * u, rowH = Math.min(42 * u, (cb - ct - 80 * u) / 3f);
            float y = ct + 66 * u + rowH * 0.75f;
            label(c, "HP", x, y, 24 * u, LABEL, Paint.Align.LEFT);
            bar(c, bx, y - 14 * u, bw, 12 * u, hf, hf <= 0.25 ? 0xFFE0B020 : 0xFF5EC8F0, hf <= 0.25 ? 0xFFF0D060 : 0xFF2F6FD8);
            label(c, String.valueOf(hp), xr, y, 26 * u, hf <= 0.25 ? LOW : WHITE, Paint.Align.RIGHT);
            y += rowH;
            int atb = m.optInt("atb", -1);
            if (battle && atb >= 0) {
                boolean ready = atb >= 65000;
                label(c, "ATB", x, y, 24 * u, LABEL, Paint.Align.LEFT);
                bar(c, bx, y - 14 * u, bw, 12 * u, atb / 65535.0, 0xFFF0E070, 0xFFC09020);
                if (ready) label(c, "READY", xr, y, 24 * u, GOLD, Paint.Align.RIGHT);
            } else {
                int mp = m.optInt("mp"), mmp = Math.max(1, m.optInt("mmp"));
                label(c, "MP", x, y, 24 * u, LABEL, Paint.Align.LEFT);
                bar(c, bx, y - 14 * u, bw, 12 * u, (double) mp / mmp, 0xFF7CE07C, 0xFF2E9E4E);
                label(c, String.valueOf(mp), xr, y, 26 * u, WHITE, Paint.Align.RIGHT);
            }
            y += rowH;
            if (y < cb - 8 * u) {
                int lim = m.optInt("limit");
                label(c, "LMT", x, y, 24 * u, LABEL, Paint.Align.LEFT);
                bar(c, bx, y - 14 * u, bw, 12 * u, lim / 255.0, 0xFFF07FB0, 0xFFC0306E);
                label(c, lim >= 255 ? "LIMIT" : "Lv " + m.optInt("limlv"), xr, y, 24 * u, lim >= 255 ? 0xFFFF90C8 : DIM, Paint.Align.RIGHT);
            }
        }
    }

    // ---- panels -------------------------------------------------------------------------------

    private void divider(Canvas c, RectF in, float y, float u) {
        fill.setShader(null);
        fill.setColor(DIVIDER);
        c.drawRect(in.left, y, in.right, y + 2 * u, fill);
    }

    private static List<String> strings(JSONArray a) {
        List<String> out = new ArrayList<>();
        for (int i = 0; a != null && i < a.length(); i++) {
            String s = a.optString(i);
            if (!s.isEmpty() && !out.contains(s)) out.add(s);   // two drop slots can hold the same item
        }
        return out;
    }

    /** A row: a label on the left and chips (or plain text) packed from the right, wrapping. */
    private float chipRow(Canvas c, RectF in, float y, float u, String name, List<String> items, int chipFill,
                          int chipText, boolean chips) {
        float rowH = 54 * u, size = 28 * u, chipPad = 14 * u, gap = 10 * u;
        float labelW = measure(name, 30 * u) + 30 * u;
        label(c, name, in.left, y + 37 * u, 30 * u, LABEL, Paint.Align.LEFT);
        if (items.isEmpty()) {
            label(c, "-", in.right, y + 37 * u, 30 * u, DIM, Paint.Align.RIGHT);
            divider(c, in, y + rowH, u);
            return y + rowH + 2 * u;
        }
        float x = in.right, line = 0, minX = in.left + labelW;
        if (!chips) {
            fitLabel(c, android.text.TextUtils.join(" · ", items), in.right, y + 37 * u, 30 * u, in.right - minX, chipText, Paint.Align.RIGHT);
            divider(c, in, y + rowH, u);
            return y + rowH + 2 * u;
        }
        for (String s : items) {
            float w = measure(s, size) + 2 * chipPad;
            if (x - w < minX && x < in.right) { line++; x = in.right; }
            float cy = y + 8 * u + line * (rowH - 6 * u);
            r.set(x - w, cy, x, cy + 40 * u);
            fill.setShader(null);
            fill.setColor(chipFill);
            c.drawRoundRect(r, 20 * u, 20 * u, fill);
            stroke.setColor(chipText & 0x90FFFFFF);
            stroke.setStrokeWidth(2 * u);
            c.drawRoundRect(r, 20 * u, 20 * u, stroke);
            label(c, s, x - w / 2, cy + 29 * u, size, chipText, Paint.Align.CENTER);
            x -= w + gap;
        }
        float h = rowH + line * (rowH - 6 * u);
        divider(c, in, y + h, u);
        return y + h + 2 * u;
    }

    /** Battle: each kind of enemy once (HP of each), then what Sense would tell about it. */
    private float drawEnemies(Canvas c, RectF in, float y, float u) {
        JSONArray en = state.optJSONArray("enemies");
        if (en == null || en.length() == 0) {
            label(c, "battle".equals(state.optString("mode")) ? "No enemies left" : "Enemy details show here in battle",
                  in.centerX(), in.top + 70 * u, 32 * u, DIM, Paint.Align.CENTER);
            return y + 100 * u;
        }
        Map<String, List<JSONObject>> kinds = new LinkedHashMap<>();
        for (int i = 0; i < en.length(); i++) {
            JSONObject e = en.optJSONObject(i);
            if (e == null) continue;
            String k = e.optString("name");
            if (k.isEmpty()) k = "#" + e.optInt("slot");
            List<JSONObject> l = kinds.get(k);
            if (l == null) kinds.put(k, l = new ArrayList<>());
            l.add(e);
        }
        boolean firstKind = true;
        for (Map.Entry<String, List<JSONObject>> k : kinds.entrySet()) {
            JSONObject e = k.getValue().get(0);
            if (!firstKind) y += 18 * u;
            firstKind = false;
            float nameW = measure(k.getKey(), 38 * u);
            label(c, k.getKey(), in.left, y + 42 * u, 38 * u, WHITE, Paint.Align.LEFT);
            label(c, "Lv " + e.optInt("lv"), in.left + nameW + 14 * u, y + 42 * u, 30 * u, LABEL, Paint.Align.LEFT);
            boolean many = k.getValue().size() > 1;
            if (many) y += 56 * u;
            int nth = 0;
            for (JSONObject one : k.getValue()) {
                int hp = one.optInt("hp"), mhp = Math.max(1, one.optInt("mhp"));
                String hpText = grouped(hp) + " / " + grouped(mhp);
                if (many) {                           // one line each: A, B, C ... and HP
                    label(c, String.valueOf((char) ('A' + nth++)), in.left, y + 30 * u, 26 * u, LABEL, Paint.Align.LEFT);
                    label(c, hpText, in.right, y + 30 * u, 28 * u, hp > 0 ? WHITE : DIM, Paint.Align.RIGHT);
                    bar(c, in.left + 40 * u, y + 18 * u, in.width() - 40 * u - measure("99,999 / 99,999", 28 * u) - 20 * u, 14 * u,
                        (double) hp / mhp, 0xFF60D070, 0xFF9AE88A);
                    y += 46 * u;
                } else {
                    label(c, hpText, in.right, y + 42 * u, 34 * u, WHITE, Paint.Align.RIGHT);
                    bar(c, in.left, y + 60 * u, in.width(), 18 * u, (double) hp / mhp, 0xFF60D070, 0xFF9AE88A);
                    y += 92 * u;
                }
            }
            y = chipRow(c, in, y, u, "Weak", strings(e.optJSONArray("weak")), 0xFF6A1E1E, 0xFFFF9A8A, true);
            List<String> abs = strings(e.optJSONArray("absorb"));
            if (!abs.isEmpty()) y = chipRow(c, in, y, u, "Absorbs", abs, 0xFF0E4A50, 0xFF8AF0F0, true);
            List<String> vd = strings(e.optJSONArray("void"));
            if (!vd.isEmpty()) y = chipRow(c, in, y, u, "Void", vd, 0xFF2A2E3E, 0xFFD0D4E0, true);
            List<String> hv = strings(e.optJSONArray("half"));
            if (!hv.isEmpty()) y = chipRow(c, in, y, u, "Halves", hv, 0xFF1E3060, 0xFFA8C4FF, true);
            y = chipRow(c, in, y, u, "Steal", strings(e.optJSONArray("steal")), 0, GOLD, false);
            y = chipRow(c, in, y, u, "Drops", strings(e.optJSONArray("drop")), 0, WHITE, false);
            String morph = e.optString("morph");
            if (!morph.isEmpty()) y = chipRow(c, in, y, u, "Morph", java.util.Collections.singletonList(morph), 0, 0xFFE0B0FF, false);
            List<String> imm = strings(e.optJSONArray("immune"));
            if (!imm.isEmpty()) y = chipRow(c, in, y, u, "Immune", imm, 0xFF2A2E3E, 0xFFC0C4D0, true);
            List<String> st = strings(e.optJSONArray("status"));
            if (!st.isEmpty()) y = chipRow(c, in, y, u, "Status", st, 0xFF4A3A0A, 0xFFFFE080, true);
        }
        return y + 10 * u;
    }

    /** Equipped materia per party member: AP toward the next level, gold when mastered. */
    private float drawMateria(Canvas c, RectF in, float y, float u) {
        JSONArray party = state.optJSONArray("party");
        for (int i = 0; party != null && i < party.length(); i++) {
            JSONObject m = party.optJSONObject(i);
            if (m == null) continue;
            if (i > 0) y += 16 * u;
            String name = m.optString("name");
            float nw = measure(name, 36 * u);
            label(c, name, in.left, y + 40 * u, 36 * u, WHITE, Paint.Align.LEFT);
            List<String> eq = new ArrayList<>();
            for (String k : new String[] { "wpn", "arm", "acc" }) if (!m.optString(k).isEmpty()) eq.add(m.optString(k));
            fitLabel(c, android.text.TextUtils.join(" · ", eq), in.right, y + 38 * u, 24 * u, in.width() - nw - 30 * u, LABEL, Paint.Align.RIGHT);
            y += 54 * u;
            JSONArray mats = m.optJSONArray("mat");
            if (mats == null || mats.length() == 0) {
                label(c, "No materia", in.left, y + 34 * u, 28 * u, DIM, Paint.Align.LEFT);
                y += 50 * u;
                continue;
            }
            for (int k = 0; k < mats.length(); k++) {
                JSONObject t = mats.optJSONObject(k);
                if (t == null) continue;
                long ap = t.optLong("ap"), nx = t.optLong("nx");
                String right;
                int rc = WHITE;
                double frac;
                if (nx == -1) { right = "mastered"; rc = GOLD; frac = 1; }
                else if (nx < 0) { right = "-"; rc = DIM; frac = 0; }
                else { right = grouped(ap) + " / " + grouped(nx) + " AP"; frac = (double) ap / Math.max(1, nx); }
                String lv = t.optInt("lvs") > 1 ? "  Lv " + t.optInt("lv") : "";
                float rw = measure(right, 28 * u);
                fitLabel(c, t.optString("n") + lv, in.left, y + 32 * u, 30 * u, in.width() - rw - 20 * u, WHITE, Paint.Align.LEFT);
                label(c, right, in.right, y + 32 * u, 28 * u, rc, Paint.Align.RIGHT);
                if (nx == -1) bar(c, in.left, y + 44 * u, in.width(), 9 * u, 1, 0xFFC09020, 0xFFF5D86A);
                else bar(c, in.left, y + 44 * u, in.width(), 9 * u, frac, 0xFF2F6FD8, 0xFF7FC8FF);
                y += 64 * u;
            }
        }
        return y + 10 * u;
    }

    /** Items (with counts) or key items, in two columns. */
    private float drawList(Canvas c, RectF in, float y, float u, JSONArray a, boolean counts) {
        int n = a == null ? 0 : a.length();
        if (n == 0) {
            label(c, counts ? "No items" : "No key items", in.centerX(), in.top + 70 * u, 32 * u, DIM, Paint.Align.CENTER);
            return y + 100 * u;
        }
        int cols = in.width() > 560 * u ? 2 : 1;
        float gap = 36 * u, cw = (in.width() - gap * (cols - 1)) / cols, rowH = 52 * u;
        int rows = (n + cols - 1) / cols;
        for (int i = 0; i < n; i++) {
            int col = i / rows, row = i % rows;       // down the first column, then the next
            float x = in.left + col * (cw + gap), ry = y + row * rowH;
            String name;
            int qty = -1, color = WHITE;
            if (counts) {
                JSONArray it = a.optJSONArray(i);
                if (it == null) continue;
                name = it.optString(0);
                qty = it.optInt(1);
                if (it.optInt(2) == 1) color = 0xFFB8D0FF;    // weapons, armor, accessories
            } else name = a.optString(i);
            float qw = qty >= 0 ? measure(String.valueOf(qty), 28 * u) + 12 * u : 0;
            fitLabel(c, name, x, ry + 36 * u, 29 * u, cw - qw, color, Paint.Align.LEFT);
            if (qty >= 0) label(c, String.valueOf(qty), x + cw, ry + 36 * u, 28 * u, LIGHT, Paint.Align.RIGHT);
            fill.setShader(null);
            fill.setColor(0x18FFFFFF);
            c.drawRect(x, ry + rowH - 2 * u, x + cw, ry + rowH, fill);
        }
        return y + rows * rowH + 10 * u;
    }

    /** The world map with where the party and its vehicles are (from the savemap when the party
     *  is not on the world map now). */
    private float drawMap(Canvas c, RectF in, float u) {
        android.graphics.Bitmap map = worldMap;
        if (map == null) {
            label(c, "The world map appears after the game files are found", in.centerX(), in.top + 70 * u, 28 * u, DIM, Paint.Align.CENTER);
            return in.top + 100 * u;
        }
        // the map trimmed of its open-sea border (the land spans 5..95% across, 3..97% down), as
        // big as the panel allows and centred; the legend sits inside it on the sea, bottom left
        final float cx0 = 0.05f, cx1 = 0.955f, cz0 = 0.03f, cz1 = 0.975f;
        float cw = (cx1 - cx0) * WorldMap.WORLD_W, ch = (cz1 - cz0) * WorldMap.WORLD_H;
        float mw = in.width(), mh = mw * ch / cw;
        if (mh > in.height()) { mh = in.height(); mw = mh * cw / ch; }
        RectF dst = new RectF(in.centerX() - mw / 2, in.centerY() - mh / 2, in.centerX() + mw / 2, in.centerY() + mh / 2);
        android.graphics.Rect src = new android.graphics.Rect(Math.round(cx0 * map.getWidth()), Math.round(cz0 * map.getHeight()),
                Math.round(cx1 * map.getWidth()), Math.round(cz1 * map.getHeight()));
        c.drawBitmap(map, src, dst, mapPaint);
        stroke.setColor(0x80FFFFFF);
        stroke.setStrokeWidth(2 * u);
        c.drawRect(dst, stroke);
        List<String> legend = new ArrayList<>();
        JSONArray veh = state.optJSONArray("veh");
        for (int i = 0; veh != null && i < veh.length(); i++) {
            JSONArray v = veh.optJSONArray(i);
            if (v == null) continue;
            String kind = v.optString(0);
            String e = vehicleEmoji(kind);
            float px = mapX(dst, v.optInt(1), cx0, cx1), py = mapY(dst, v.optInt(2), cz0, cz1);
            fill.setShader(null);
            fill.setColor(0xB0101830);
            c.drawCircle(px, py, 22 * u, fill);
            emoji.setTextSize(28 * u);
            c.drawText(e, px, py + 10 * u, emoji);
            String n = vehicleName(kind);
            if (!legend.contains(e + " " + n)) legend.add(e + " " + n);
        }
        int wx = -1, wz = -1;
        if (state.has("wx")) { wx = state.optInt("wx"); wz = state.optInt("wz"); }
        else {
            JSONArray wl = state.optJSONArray("wlead");
            if (wl != null) { wx = wl.optInt(0); wz = wl.optInt(1); }
        }
        if (wx >= 0) {
            float px = mapX(dst, wx, cx0, cx1), py = mapY(dst, wz, cz0, cz1);
            float pulse = (float) (0.5 + 0.5 * Math.sin(System.currentTimeMillis() / 180.0));
            fill.setShader(null);
            fill.setColor(0x60FF3030);
            c.drawCircle(px, py, (14 + 10 * pulse) * u, fill);
            fill.setColor(Color.WHITE);
            c.drawCircle(px, py, 9 * u, fill);
            fill.setColor(0xFFE02828);
            c.drawCircle(px, py, 6.5f * u, fill);
            if (state.has("wx")) postInvalidateDelayed(50);   // keep the marker pulsing
            legend.add(0, "● You" + (state.has("wx") ? "" : " (last visit)"));
        }
        if (!legend.isEmpty()) {
            float lw = 16 * u;                         // the legend's width, to size its backing
            for (String s : legend) {
                int sp = s.indexOf(' ');
                lw += (s.startsWith("●") ? 26 * u : 34 * u) + measure(s.substring(sp + 1), 26 * u) + 24 * u;
            }
            RectF box = new RectF(dst.left + 10 * u, dst.bottom - 54 * u, dst.left + 10 * u + lw, dst.bottom - 10 * u);
            fill.setShader(null);
            fill.setColor(0xB0101830);
            c.drawRoundRect(box, 12 * u, 12 * u, fill);
            float lx = box.left + 12 * u, ly = box.bottom - 13 * u;
            for (String s : legend) {
                boolean you = s.startsWith("●");
                if (you) {
                    fill.setColor(0xFFE02828);
                    c.drawCircle(lx + 9 * u, ly - 10 * u, 9 * u, fill);
                    label(c, s.substring(2), lx + 26 * u, ly, 26 * u, LIGHT, Paint.Align.LEFT);
                    lx += 26 * u + measure(s.substring(2), 26 * u) + 24 * u;
                } else {
                    int sp = s.indexOf(' ');
                    emoji.setTextSize(26 * u);
                    c.drawText(s.substring(0, sp), lx + 14 * u, ly, emoji);
                    label(c, s.substring(sp + 1), lx + 34 * u, ly, 26 * u, LIGHT, Paint.Align.LEFT);
                    lx += 34 * u + measure(s.substring(sp + 1), 26 * u) + 24 * u;
                }
            }
        }
        return in.bottom;
    }

    /** World x / z to the trimmed map drawn in dst. */
    private static float mapX(RectF dst, int x, float c0, float c1) {
        return dst.left + dst.width() * ((float) x / WorldMap.WORLD_W - c0) / (c1 - c0);
    }

    private static float mapY(RectF dst, int z, float c0, float c1) {
        return dst.top + dst.height() * ((float) z / WorldMap.WORLD_H - c0) / (c1 - c0);
    }

    private static String vehicleEmoji(String k) {
        switch (k) {
        case "highwind": return "✈️";
        case "buggy": return "🚙";
        case "bronco": return "🛩️";
        case "chocobo": return "🐤";
        default: return "•";
        }
    }

    private static String vehicleName(String k) {
        switch (k) {
        case "highwind": return "Highwind";
        case "buggy": return "Buggy";
        case "bronco": return "Tiny Bronco";
        case "chocobo": return "Chocobo";
        default: return k;
        }
    }

    /** The boosters as big buttons, a countdown banner when one runs, and the save's counters. */
    private float drawExtras(Canvas c, RectF in, float y, float u) {
        JSONObject st = state.optJSONObject("stats");
        if (st == null) st = new JSONObject();
        long cd = st.optLong("countdown");
        if (cd > 0) {
            RectF bn = new RectF(in.left, y + 4 * u, in.right, y + 64 * u);
            goldWindow(c, bn, false);
            label(c, "Countdown", bn.left + 20 * u, bn.centerY() + 11 * u, 30 * u, 0xFFFFE08A, Paint.Align.LEFT);
            label(c, String.format(java.util.Locale.US, "%d:%02d left", cd / 60, cd % 60), bn.right - 20 * u, bn.centerY() + 11 * u, 30 * u,
                  0xFFFFE08A, Paint.Align.RIGHT);
            y += 80 * u;
        }
        int speed = Math.max(1, state.optInt("speed", 1));
        String[] names = { "Speed", "No battles", "Battle+" };
        int[] cmds = { CMD_SPEED, CMD_BATTLES, CMD_BPLUS };
        boolean[] on = { speed > 1, state.optInt("noenc") == 1, state.optInt("bplus") == 1 };
        String[] val = { speed > 1 ? speed + "×" : "OFF", on[1] ? "ON" : "OFF", on[2] ? "ON" : "OFF" };
        float gap = 12 * u, bw = (in.width() - 2 * gap) / 3f, bh = 74 * u;
        boolean stack = bw < 250 * u;                   // narrow: one under another
        if (stack) bw = in.width();
        for (int i = 0; i < 3; i++) {
            float bx = stack ? in.left : in.left + i * (bw + gap), by = stack ? y + 4 * u + i * (bh + gap) : y + 4 * u;
            RectF b = new RectF(bx, by, bx + bw, by + bh);
            if (on[i]) {
                fill.setShader(new LinearGradient(b.left, b.top, b.left, b.bottom, 0xFF22306A, 0xFF101A44, Shader.TileMode.CLAMP));
                c.drawRoundRect(b, 12 * u, 12 * u, fill);
                fill.setShader(null);
                stroke.setColor(GOLD);
                stroke.setStrokeWidth(4 * u);
                c.drawRoundRect(b, 12 * u, 12 * u, stroke);
            } else window(c, b.left, b.top, b.right, b.bottom);
            float tw = measure(names[i] + "  ", 30 * u), vw = measure(val[i], 28 * u);
            float sx = b.centerX() - (tw + vw) / 2;
            label(c, names[i] + "  ", sx, b.centerY() + 11 * u, 30 * u, on[i] ? GOLD : WHITE, Paint.Align.LEFT);
            label(c, val[i], sx + tw, b.centerY() + 11 * u, 28 * u, on[i] ? GOLD : LIGHT, Paint.Align.LEFT);
            toggleBoxes.add(b);
            toggleCmds.add(cmds[i]);
        }
        y += (stack ? 3 * bh + 2 * gap : bh) + 24 * u;
        String[][] rows = {
            { "Battles fought", grouped(st.optLong("battles")) },
            { "Escapes", grouped(st.optLong("escapes")) },
            { "Enemies defeated", grouped(st.optLong("kills")) },
            { "Chocobo races won", grouped(st.optLong("races")) },
            { "Key items", String.valueOf(strings(state.optJSONArray("keys")).size()) },
        };
        for (String[] row : rows) {
            label(c, row[0], in.left, y + 36 * u, 30 * u, LABEL, Paint.Align.LEFT);
            label(c, row[1], in.right, y + 36 * u, 30 * u, WHITE, Paint.Align.RIGHT);
            divider(c, in, y + 52 * u, u);
            y += 54 * u;
        }
        // Date points (#71): who Cloud is closest to; the highest goes on the Gold Saucer date.
        JSONArray love = state.optJSONArray("love");
        if (love != null && love.length() > 0) {
            y += 20 * u;
            label(c, "Date points", in.left, y + 36 * u, 30 * u, GOLD, Paint.Align.LEFT);
            label(c, "highest = Gold Saucer date", in.right, y + 36 * u, 24 * u, DIM, Paint.Align.RIGHT);
            y += 50 * u;
            int best = -1, top = -1;
            for (int i = 0; i < love.length(); i++) {
                JSONArray e = love.optJSONArray(i);
                int v = e == null ? 0 : e.optInt(1);
                if (v > top) { top = v; best = i; }
            }
            String[] fallback = { "Aerith", "Tifa", "Yuffie", "Barret" };
            for (int i = 0; i < love.length(); i++) {
                JSONArray e = love.optJSONArray(i);
                if (e == null) continue;
                String nm = e.optString(0);
                if (nm.isEmpty() && i < fallback.length) nm = fallback[i];
                int v = e.optInt(1);
                boolean lead = i == best && v > 0;
                label(c, (lead ? "♥ " : "") + nm, in.left, y + 36 * u, 30 * u, lead ? 0xFFFF8FB0 : LABEL, Paint.Align.LEFT);
                float bx = in.left + in.width() * 0.42f, bwid = in.width() * 0.40f;
                bar(c, bx, y + 22 * u, bwid, 10 * u, Math.min(1.0, v / 255.0), lead ? 0xFFFF8FB0 : 0xFF6F8FD0);
                label(c, String.valueOf(v), in.right, y + 36 * u, 30 * u, WHITE, Paint.Align.RIGHT);
                divider(c, in, y + 52 * u, u);
                y += 54 * u;
            }
        }
        return y + 10 * u;
    }

    // ---- the deck -----------------------------------------------------------------------------

    private void drawDeck(Canvas c, float W, float u, float pad, float deckT, float deckH, boolean battle) {
        if (!deck) return;
        float gap = 12 * u;
        int rows = narrow() ? 2 : 1, per = (buttons.length + rows - 1) / rows;
        float bw = (W - 2 * pad - gap * (per - 1)) / per, bh = (deckH - gap * (rows - 1)) / rows;
        for (int i = 0; i < buttons.length; i++) {
            Button b = buttons[i];
            float l = pad + (i % per) * (bw + gap), top = deckT + (i / per) * (bh + gap);
            b.box.set(l, top, l + bw, top + bh);
            boolean on = (b.cmd == CMD_SPEED && state.optInt("speed", 1) > 1)
                    || (b.cmd == CMD_BATTLES && state.optInt("noenc") == 1)
                    || (b.cmd == CMD_BPLUS && state.optInt("bplus") == 1);
            window(c, b.box.left, b.box.top, b.box.right, b.box.bottom);
            if (on) {
                stroke.setColor(GOLD);
                stroke.setStrokeWidth(5 * u);
                c.drawRoundRect(b.box, 14 * u, 14 * u, stroke);
            }
            if (b == pressed) {
                fill.setColor(0x55FFFFFF);
                c.drawRoundRect(b.box, 14 * u, 14 * u, fill);
            }
            String t = (battle && b.pad == PAD_Y) ? "Back" : b.label;
            if (b.cmd == CMD_SPEED && state.optInt("speed", 1) > 1) t = "Speed " + state.optInt("speed", 1) + "×";
            else if (b.cmd == CMD_BATTLES && state.optInt("noenc") == 1) t = "No battles";
            emoji.setTextSize(30 * u);
            emoji.setColor(on ? GOLD : WHITE);
            c.drawText(b.icon, b.box.centerX(), b.box.top + bh * 0.40f, emoji);
            fitLabel(c, t, b.box.centerX(), b.box.top + bh * 0.80f, 30 * u, bw - 12 * u, on ? GOLD : WHITE, Paint.Align.CENTER);
        }
    }

    // ---- battle windows on this screen (Settings: off by default; companion_render.cpp) -------

    private void drawBattleWindows(Canvas c, float W, float u, float pad, float deckT) {
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
        c.drawRect(0, areaBottom, W, getHeight(), fill);
        regions = state.optJSONArray("layout");
    }

    /** The hide button: a small window with a chevron pointing toward the panels' outer edge. */
    private void drawHideButton(Canvas c, RectF b, float u) {
        window(c, b.left, b.top, b.right, b.bottom);
        if (hidePressed) {
            fill.setShader(null);
            fill.setColor(0x55FFFFFF);
            c.drawRoundRect(b, 14 * u, 14 * u, fill);
        }
        stroke.setColor(WHITE);
        stroke.setStrokeWidth(5 * u);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        float cx = b.centerX(), cy = b.centerY(), dx = b.width() * 0.13f, dy = b.height() * 0.22f;
        // panels at the game's right hide to the right: the chevron points right (away from the game)
        float tip = hideAtLeft ? cx + dx : cx - dx, back = hideAtLeft ? cx - dx : cx + dx;
        c.drawLine(back, cy - dy, tip, cy, stroke);
        c.drawLine(tip, cy, back, cy + dy, stroke);
        stroke.setStrokeCap(Paint.Cap.BUTT);
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

    /** Pixel Guide on this (the bottom) screen: the panels step aside, and its back button
     *  brings them back. Started straight from here, with no activity of ours on this screen:
     *  the Thor gives an app with an activity on the bottom screen that screen's volume (0 out
     *  of the box), which muted the game while the guide was open. Its launcher intent brings a
     *  guide that is already running back to the front, on the page it was left on. */
    private void openGuide() {
        try {
            android.content.Intent i = new android.content.Intent(guide);
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_NO_ANIMATION);
            android.app.ActivityOptions o = android.app.ActivityOptions.makeBasic();
            if (getDisplay() != null) o.setLaunchDisplayId(getDisplay().getDisplayId());
            getContext().startActivity(i, o.toBundle());
            GameActivity.guideOpened(getDisplay() != null ? getDisplay().getDisplayId() : -1);
        } catch (RuntimeException e) {
            flash = "Pixel Guide could not open";
            flashUntil = System.currentTimeMillis() + 2500;
            invalidate();
        }
    }

    // ---- touch --------------------------------------------------------------------------------

    private boolean legacyBattle() { return battleNow && regions != null && regions.length() > 0; }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY(), u = u();
        if (state.optInt("ready") != 1) { pressed = null; return true; }   // blank: no buttons
        switch (e.getActionMasked()) {
        case MotionEvent.ACTION_DOWN:
            pressed = null;
            pressedTab = -1;
            dragging = false;
            bookPressed = !legacyBattle() && !bookBox.isEmpty() && bookBox.contains(x, y);
            if (bookPressed) { invalidate(); return true; }
            hidePressed = onHide != null && !legacyBattle() && !hideBox.isEmpty() && hideBox.contains(x, y);
            if (hidePressed) { invalidate(); return true; }
            for (Button b : buttons) if (b.box.contains(x, y)) pressed = b;
            if (pressed == null && legacyBattle()) {
                if (y >= areaTop && y < areaBottom) battleTap(x, y);
            } else if (pressed == null) {
                for (int i = 0; i < tabBoxes.length; i++) if (tabBoxes[i].contains(x, y)) pressedTab = i;
                if (pressedTab < 0 && panelBox.contains(x, y)) {
                    dragging = true;
                    dragMoved = false;
                    dragY0 = y;
                    dragScroll0 = scroll[shownView];
                }
            }
            invalidate();
            return true;
        case MotionEvent.ACTION_MOVE:
            if (dragging) {
                float dy = y - dragY0;
                if (Math.abs(dy) > 14 * u) dragMoved = true;
                if (dragMoved) { scroll[shownView] = dragScroll0 - dy; invalidate(); }
            }
            return true;
        case MotionEvent.ACTION_UP:
            if (hidePressed) {
                hidePressed = false;
                invalidate();
                if (hideBox.contains(x, y) && onHide != null) {
                    performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
                    onHide.run();
                }
                return true;
            }
            if (bookPressed) {
                bookPressed = false;
                if (bookBox.contains(x, y)) {
                    refreshGuide();          // installed or removed meanwhile
                    if (guide != null) openGuide();   // new, or in front again where it was left (#30)
                    else GuideInstaller.start(getContext(), getDisplay() != null ? getDisplay().getDisplayId() : -1);
                }
                invalidate();
                return true;
            }
            if (pressedTab >= 0) {
                int t = pressedTab;
                pressedTab = -1;
                if (tabBoxes[t].contains(x, y)) selectTab(t);
                invalidate();
                return true;
            }
            if (dragging) {
                dragging = false;
                if (!dragMoved) {
                    for (int i = 0; i < toggleBoxes.size(); i++) {
                        if (toggleBoxes.get(i).contains(x, y)) {
                            performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
                            nativeCommand(toggleCmds.get(i));
                        }
                    }
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
            pressedTab = -1;
            bookPressed = false;
            hidePressed = false;
            dragging = false;
            invalidate();
            return true;
        default:
            return true;
        }
    }

    private void selectTab(int t) {
        performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
        if (t == TABS.length) wide = !wide;          // « / »: the party column
        else tab = t;
        prefs.edit().putInt("tab", tab).putBoolean("wide", wide).apply();
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
