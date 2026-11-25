package com.ff7recomp;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Build;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowInsets;
import android.widget.FrameLayout;

/**
 * Settings > Screen > "Button for the panels" (GitHub #87): on one screen, in the landscape layout,
 * a small button over the game opens the second screen's panels (CompanionView) over part of it and
 * closes them again: the arrow in the panels' header or a tap on the game beside them. A drag moves
 * the button (kept between games, "companion" prefs). The panels take the button's half of the
 * screen; the button is away while they are open. They are in the window only while open, so closed
 * panels cost nothing. A controller button can do the same (GameActivity.panelKey).
 */
final class PanelButton extends View {
    private static final String KEY_X = "panel_button_x", KEY_Y = "panel_button_y";

    /** The game with the button over it; places the button and the panels at every size. */
    static final class Layer extends FrameLayout {
        private final CompanionView panel;
        private final TouchControls touch;
        private final boolean shown;       // false: only the controller button opens the panels
        private boolean usable;
        final PanelButton button;
        private final int slop;
        private boolean tapOutside;
        private float tapX, tapY;

        Layer(Context c, View game, CompanionView panel, TouchControls touch, boolean shown) {
            super(c);
            this.panel = panel;
            this.touch = touch;
            this.shown = shown;
            slop = ViewConfiguration.get(c).getScaledTouchSlop();
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
                clearOfBars(getRootWindowInsets());
            } else {
                panel.setOnHide(null, false);
                removeView(panel);
            }
            button.place();
            showButton();
        }

        /** A system bar that stays on the screen (a tablet's taskbar) covers none of the panels. */
        private void clearOfBars(WindowInsets in) {
            if (in == null || !open()) return;
            int l, t, r, b;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = in.getInsets(WindowInsets.Type.systemBars());
                l = bars.left; t = bars.top; r = bars.right; b = bars.bottom;
            } else {
                l = in.getSystemWindowInsetLeft(); t = in.getSystemWindowInsetTop();
                r = in.getSystemWindowInsetRight(); b = in.getSystemWindowInsetBottom();
            }
            // only what reaches into this view: the window may already end above the bar
            int[] at = new int[2];
            getLocationInWindow(at);
            View root = getRootView();
            l = Math.max(0, l - at[0]);
            t = Math.max(0, t - at[1]);
            r = Math.max(0, r - (root.getWidth() - at[0] - getWidth()));
            b = Math.max(0, b - (root.getHeight() - at[1] - getHeight()));
            LayoutParams lp = (LayoutParams) panel.getLayoutParams();
            boolean right = button.fx >= 0.5f;
            l = right ? 0 : l;
            r = right ? r : 0;
            if (lp.leftMargin == l && lp.topMargin == t && lp.rightMargin == r && lp.bottomMargin == b) return;
            lp.setMargins(l, t, r, b);
            panel.setLayoutParams(lp);
        }

        @Override public WindowInsets onApplyWindowInsets(WindowInsets in) {
            clearOfBars(in);
            return super.onApplyWindowInsets(in);
        }

        /** A tap on the game beside the open panels closes them; the on-screen controls' buttons stay theirs. */
        @Override public boolean dispatchTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                tapX = e.getX(); tapY = e.getY();
                tapOutside = open() && (tapX < panel.getLeft() || tapX >= panel.getRight())
                        && !(touch != null && touch.getVisibility() == VISIBLE && touch.takes(tapX, tapY));
                break;
            case MotionEvent.ACTION_MOVE:
                if (Math.hypot(e.getX() - tapX, e.getY() - tapY) > slop) tapOutside = false;
                break;
            case MotionEvent.ACTION_UP: {
                boolean close = tapOutside && e.getEventTime() - e.getDownTime() < 400;
                tapOutside = false;
                boolean taken = super.dispatchTouchEvent(e);
                if (close) setOpen(false);
                return taken || close;
            }
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_CANCEL:
                tapOutside = false;
                break;
            }
            return super.dispatchTouchEvent(e) || tapOutside;   // ours even where nothing under it takes touches
        }

        private void showButton() { button.setVisibility(shown && usable && !open() ? VISIBLE : GONE); }

        boolean usable() { return usable; }

        /** Not on this screen now (the panels are on a second one): button and panels away. */
        void setUsable(boolean usable) {
            if (!usable) setOpen(false);
            this.usable = usable;
            showButton();
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
        setTranslationX(fx * w);
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
            if (!moved && Math.hypot(dx, dy) > slop) moved = true;
            if (moved) {
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
        layer.setOpen(true);
        return true;
    }

    @Override protected void onDraw(Canvas c) {
        float s = size, in = s * 0.12f, r = s * 0.2f;
        box.set(in, in, s - in, s - in);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x701A2A6A);
        c.drawRoundRect(box, r, r, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(s * 0.03f);
        paint.setColor(0x90DCDCF0);
        c.drawRoundRect(box, r, r, paint);
        paint.setStrokeWidth(s * 0.05f);
        paint.setColor(0xB0FFFFFF);
        box.set(s * 0.28f, s * 0.26f, s * 0.72f, s * 0.49f);   // two screens, one above the other
        c.drawRoundRect(box, s * 0.04f, s * 0.04f, paint);
        box.set(s * 0.35f, s * 0.57f, s * 0.65f, s * 0.75f);
        c.drawRoundRect(box, s * 0.04f, s * 0.04f, paint);
    }
}
