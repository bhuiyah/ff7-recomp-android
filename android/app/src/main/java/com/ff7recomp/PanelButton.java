package com.ff7recomp;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;

/**
 * Settings > Screen > "Button for the panels" (GitHub #87): on one screen, in the landscape layout,
 * a small button over the game opens the second screen's panels (CompanionView) over part of it and
 * closes them again. A drag moves the button (kept between games, "companion" prefs). The panels
 * take the button's half of the screen, and the button steps beside them while they are open. They
 * are in the window only while open, so closed panels cost nothing.
 */
final class PanelButton extends View {
    private static final String KEY_X = "panel_button_x", KEY_Y = "panel_button_y";

    /** The game with the button over it; places the button and the panels at every size. */
    static final class Layer extends FrameLayout {
        private final CompanionView panel;
        final PanelButton button;

        Layer(Context c, View game, CompanionView panel) {
            super(c);
            this.panel = panel;
            setBackgroundColor(0xFF000000);
            addView(game, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            button = new PanelButton(c, this);
            addView(button, new LayoutParams(button.size, button.size, Gravity.TOP | Gravity.LEFT));
        }

        boolean open() { return panel.getParent() == this; }

        void setOpen(boolean open) {
            if (open == open() || (open && getWidth() == 0)) return;
            if (open) {
                boolean right = button.fx >= 0.5f;
                // as much of the screen as the panels' own shape (1240 x 1080) asks, at most 3/5
                int w = Math.min(Math.round(getWidth() * 0.6f), Math.round(getHeight() * 1240f / 1080f));
                panel.setOnHide(() -> setOpen(false), right);
                addView(panel, 1, new LayoutParams(w, LayoutParams.MATCH_PARENT, Gravity.TOP | (right ? Gravity.RIGHT : Gravity.LEFT)));
            } else {
                panel.setOnHide(null, false);
                removeView(panel);
            }
            button.place();
            button.invalidate();
        }

        /** Not on this screen now (the panels are on a second one): button and panels away. */
        void setUsable(boolean usable) {
            if (!usable) setOpen(false);
            button.setVisibility(usable ? VISIBLE : GONE);
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            post(() -> {
                if (open()) { setOpen(false); setOpen(true); }   // sized again for the new shape
                button.place();
            });
        }
    }

    final int size;
    private float fx, fy;      // the closed button's place: 0..1 of the room there is
    private final Layer layer;
    private final SharedPreferences prefs;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private final int slop;
    private float downX, downY, grabX, grabY;
    private boolean moved;

    private PanelButton(Context c, Layer layer) {
        super(c);
        this.layer = layer;
        prefs = c.getSharedPreferences("companion", Context.MODE_PRIVATE);
        size = Math.round(44 * getResources().getDisplayMetrics().density);
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
        fx = Math.max(0, Math.min(1, prefs.getFloat(KEY_X, 0.5f)));
        fy = Math.max(0, Math.min(1, prefs.getFloat(KEY_Y, 0.02f)));
        setContentDescription("Panels");
    }

    private void place() {
        float w = layer.getWidth() - size, h = layer.getHeight() - size;
        if (w <= 0 || h <= 0) return;
        float x = fx * w;
        if (layer.open()) {        // beside the panels, so it covers none of them
            int pw = layer.panel.getLayoutParams().width, gap = size / 8;
            x = fx >= 0.5f ? Math.min(x, layer.getWidth() - pw - size - gap) : Math.max(x, pw + gap);
        }
        setTranslationX(Math.max(0, Math.min(w, x)));
        setTranslationY(fy * h);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
        case MotionEvent.ACTION_DOWN:
            downX = e.getRawX(); downY = e.getRawY();
            grabX = getTranslationX(); grabY = getTranslationY();
            moved = false;
            return true;
        case MotionEvent.ACTION_MOVE: {
            float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
            if (!moved && Math.hypot(dx, dy) > slop && !layer.open()) moved = true;
            if (moved && !layer.open()) {
                float w = Math.max(1, layer.getWidth() - size), h = Math.max(1, layer.getHeight() - size);
                fx = Math.max(0, Math.min(1, (grabX + dx) / w));
                fy = Math.max(0, Math.min(1, (grabY + dy) / h));
                place();
            }
            return true;
        }
        case MotionEvent.ACTION_UP:
            if (moved) prefs.edit().putFloat(KEY_X, fx).putFloat(KEY_Y, fy).apply();
            else {
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                performClick();
            }
            return true;
        default:
            return true;
        }
    }

    @Override public boolean performClick() {
        super.performClick();
        layer.setOpen(!layer.open());
        return true;
    }

    @Override protected void onDraw(Canvas c) {
        float s = size, in = s * 0.12f, r = s * 0.2f;
        boolean open = layer.open();
        box.set(in, in, s - in, s - in);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(open ? 0xD01A2A6A : 0x701A2A6A);
        c.drawRoundRect(box, r, r, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(s * 0.03f);
        paint.setColor(open ? 0xFFDCDCF0 : 0x90DCDCF0);
        c.drawRoundRect(box, r, r, paint);
        paint.setStrokeWidth(s * 0.05f);
        paint.setColor(open ? 0xFFFFFFFF : 0xB0FFFFFF);
        if (open) {
            c.drawLine(s * 0.37f, s * 0.37f, s * 0.63f, s * 0.63f, paint);
            c.drawLine(s * 0.63f, s * 0.37f, s * 0.37f, s * 0.63f, paint);
        } else {                   // two screens, one above the other
            box.set(s * 0.28f, s * 0.26f, s * 0.72f, s * 0.49f);
            c.drawRoundRect(box, s * 0.04f, s * 0.04f, paint);
            box.set(s * 0.35f, s * 0.57f, s * 0.65f, s * 0.75f);
            c.drawRoundRect(box, s * 0.04f, s * 0.04f, paint);
        }
    }
}
