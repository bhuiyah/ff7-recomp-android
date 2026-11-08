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
    private static native void nativeCommand(int cmd);
    private static native void nativeTap(int buttons);

    private static final int CMD_SPEED = 1, CMD_SAVE = 2, CMD_LOAD = 3;
    private static final int PAD_Y = 0x8000;   // Triangle: the game's main menu

    private final Handler handler = new Handler(Looper.getMainLooper());
    private JSONObject state = new JSONObject();
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();

    private static final class Button {
        final String label; final int cmd, pad; final RectF box = new RectF();
        Button(String label, int cmd, int pad) { this.label = label; this.cmd = cmd; this.pad = pad; }
    }
    private final Button[] buttons = {
        new Button("Menu", 0, PAD_Y),
        new Button("Save state", CMD_SAVE, 0),
        new Button("Load state", CMD_LOAD, 0),
        new Button("2× speed", CMD_SPEED, 0),
    };
    private Button pressed;
    private String flash;            // a short note after a button ("Saving...")
    private long flashUntil;

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            try { state = new JSONObject(nativeState()); } catch (JSONException e) { state = new JSONObject(); }
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
    }

    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); handler.post(poll); }
    @Override protected void onDetachedFromWindow() { handler.removeCallbacks(poll); super.onDetachedFromWindow(); }

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
        final float W = getWidth(), H = getHeight(), u = H / 1080f, pad = 24 * u;
        if (state.optInt("ready") != 1) {
            label(c, "FINAL FANTASY VII", W / 2, H / 2, 64 * u, 0xFFDCDCF0, Paint.Align.CENTER);
            label(c, "starting...", W / 2, H / 2 + 70 * u, 40 * u, 0xFF8090B0, Paint.Align.CENTER);
            return;
        }
        final String mode = state.optString("mode");
        final boolean battle = "battle".equals(mode);

        // In battle the game draws its own windows (names, HP/MP/Limit/Time, commands) across
        // the top of this screen, at the band's proportions (companion_render.cpp: x 0..640,
        // y 330..450 of the game's frame); everything else starts below them.
        float bandH = battle ? W * (450f - 330f) / 640f : 0f;
        fill.setShader(null);
        fill.setColor(Color.BLACK);
        c.drawRect(0, bandH, W, H, fill);
        if (!battle) {
            c.drawRect(0, 0, W, H, fill);
        }

        // top: where, play time, gil (outside battle)
        float top = battle ? bandH : pad, topH = battle ? 0 : 110 * u;
        if (!battle) window(c, pad, top, W - pad, top + topH);
        if (!battle) {
            String where = state.optString("loc");
            label(c, where.isEmpty() ? "-" : where, pad + 30 * u, top + 70 * u, 46 * u, Color.WHITE, Paint.Align.LEFT);
            label(c, clock(state.optLong("time")) + "    " + state.optLong("gil") + " G", W - pad - 30 * u, top + 70 * u, 38 * u,
                  0xFFDCDCF0, Paint.Align.RIGHT);
        }

        // command deck at the bottom
        float deckH = 150 * u, deckT = H - pad - deckH;
        float bw = (W - pad * (buttons.length + 1)) / buttons.length;
        for (int i = 0; i < buttons.length; i++) {
            Button b = buttons[i];
            float l = pad + i * (bw + pad);
            b.box.set(l, deckT, l + bw, deckT + deckH);
            window(c, b.box.left, b.box.top, b.box.right, b.box.bottom);
            boolean on = b.cmd == CMD_SPEED && state.optInt("speed2x") == 1;
            if (b == pressed || on) {
                fill.setColor(on ? 0x6600FF88 : 0x55FFFFFF);
                c.drawRoundRect(b.box, 14 * u, 14 * u, fill);
            }
            label(c, b.label, b.box.centerX(), b.box.centerY() + 16 * u, 44 * u, Color.WHITE, Paint.Align.CENTER);
        }

        // party outside battle (in battle the game's own windows above show it); enemies in battle
        float bodyT = top + topH + pad, bodyB = deckT - pad;
        float partyR = W - pad;
        JSONArray party = state.optJSONArray("party");
        int n = party == null ? 0 : party.length();
        float rowH = (bodyB - bodyT - pad * 2) / 3f;
        for (int i = 0; i < 3 && !battle; i++) {
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
                int atb = m.optInt("atb", -1);
                if (atb >= 0) {
                    label(c, "Time", x2, y3 - 6 * u, 30 * u, 0xFFF0E0A0, Paint.Align.LEFT);
                    bar(c, x2, y3 + 4 * u, barW, bh, atb / 65535.0, atb >= 0xFFFF ? 0xFFFFE060 : 0xFFC0A040);
                }
            }
        }
        if (battle) {
            float l = pad;
            window(c, l, bodyT, W - pad, bodyB);
            label(c, "Enemies", l + 28 * u, bodyT + 58 * u, 44 * u, Color.WHITE, Paint.Align.LEFT);
            JSONArray en = state.optJSONArray("enemies");
            float y = bodyT + 120 * u, w = W - pad - l - 56 * u;
            for (int i = 0; en != null && i < en.length() && y < bodyB - 40 * u; i++) {
                JSONObject e = en.optJSONObject(i);
                int hp = e.optInt("hp"), mhp = Math.max(1, e.optInt("mhp"));
                String nm = e.optString("name");
                label(c, nm.isEmpty() ? "#" + e.optInt("slot") : nm, l + 28 * u, y, 32 * u, Color.WHITE, Paint.Align.LEFT);
                label(c, hp + "/" + mhp, W - pad - 28 * u, y, 30 * u, 0xFFDCDCF0, Paint.Align.RIGHT);
                bar(c, l + 28 * u, y + 12 * u, w, 14 * u, (double) hp / mhp, 0xFFE05050);
                y += 78 * u;
            }
        }
        if (flash != null && System.currentTimeMillis() < flashUntil)
            label(c, flash, W / 2, deckT - 20 * u, 38 * u, 0xFFFFE060, Paint.Align.CENTER);
    }

    // ---- touch --------------------------------------------------------------------------------

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
        case MotionEvent.ACTION_DOWN:
            pressed = null;
            for (Button b : buttons) if (b.box.contains(x, y)) pressed = b;
            invalidate();
            return true;
        case MotionEvent.ACTION_UP:
            Button b = pressed;
            pressed = null;
            if (b != null && b.box.contains(x, y)) fire(b);
            invalidate();
            return true;
        case MotionEvent.ACTION_CANCEL:
            pressed = null;
            invalidate();
            return true;
        default:
            return true;
        }
    }

    private void fire(Button b) {
        performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
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
