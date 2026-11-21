package com.ff7recomp;

import android.app.Activity;
import android.app.Presentation;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.util.Log;
import android.view.Display;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import java.io.File;

/**
 * Hosts the lifted FF7: a fullscreen SurfaceView whose Surface goes to native code, where
 * bgfx renders through Vulkan. The game runs on its own native thread, started once the
 * surface exists. Game files are read from the app's external files dir
 * (/sdcard/Android/data/com.ff7recomp/files/game: ff7_en.exe and data/).
 *
 * Dual screen (AYN Thor): the bottom screen is a separate presentation display. If one
 * exists, a Presentation shows CompanionView there (party, battle and location panels, a
 * touch command deck) over a SurfaceView the game draws its battle windows into
 * (docs/ROADMAP.md M7).
 *
 * Swap screens (Settings > Screen, GitHub #41): on devices whose main screen is the small one
 * (Ayaneo Pocket DS), the launcher starts the game on the presentation display and the panels
 * go to the main display. Android only puts a Presentation on a presentation display, so there
 * they are the content of CompanionHostActivity, a window that never takes the keys.
 */
public class GameActivity extends Activity implements SurfaceHolder.Callback {
    private static final String TAG = "FF7Recomp";
    private boolean started;
    private Presentation companion;
    private Activity companionHost;               // swapped screens: the panels' activity on the main display
    private int panelsFor = -1;                   // the game's display when the panels were placed (#49)
    private static GameActivity current;          // for CompanionView and GuideHostActivity.Events (this process)
    static GameActivity currentGame() { return current; }

    /** Pixel Guide is opening on the bottom screen: the panels step aside (a Presentation is
     *  drawn above activities), and the game is put back in front on the top screen so the
     *  pad keeps playing the game while the guide stays in view. Opened from panels on the game's
     *  own screen (Portrait, Split: one screen), the guide covers the game instead, and the back
     *  button or Recents returns to it: bringing the game back in front there closed the guide a
     *  moment after it opened (#56). */
    static void guideOpened(int guideDisplay) {
        GameActivity a = current;
        if (a == null) return;
        a.runOnUiThread(() -> {
            Display game = Compat.display(a);
            if (game != null && game.getDisplayId() == guideDisplay) {
                Log.i(TAG, "Pixel Guide opened over the game (one screen)");
                return;
            }
            if (a.companion != null) a.companion.hide();
            a.showGuideTab(true);
            new android.os.Handler(a.getMainLooper()).postDelayed(() -> {
                // Android gives keys to the display that last started or touched an activity:
                // the game is made the focused one again while the guide stays in view.
                a.bringToFront();
            }, 900);
        });
    }

    /** A focus hand-back from guideOpened (the launcher's own starts go through onCreate):
     *  nothing to change, the game keeps running. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        Log.i(TAG, "back in front" + (intent.getBooleanExtra("refocus", false) ? " (guide open)" : ""));
    }

    // Low memory with another app in front on the other screen (GitHub #59). Touching Pixel Guide
    // (or any app) on the bottom screen makes it Android's top app, and the game, still on screen
    // above it, drops to "visible": with ~500 MB it was then the first thing Android's low-memory
    // killer closed, before the guide. When Android reports memory running low while the game is
    // on screen but not the top app, the game is put back in front: it is the top app again (and
    // the controller plays it), and the other app is the one Android trims or closes first.
    private boolean onScreen, topResumed = true;
    private long lastLowMemoryRefocus;

    @Override
    protected void onStart() { super.onStart(); onScreen = true; }

    @Override
    protected void onStop() { onScreen = false; super.onStop(); }

    @Override
    public void onTopResumedActivityChanged(boolean top) {
        super.onTopResumedActivityChanged(top);
        topResumed = top;
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level != TRIM_MEMORY_RUNNING_MODERATE && level != TRIM_MEMORY_RUNNING_LOW
                && level != TRIM_MEMORY_RUNNING_CRITICAL) return;
        if (!onScreen || topResumed || isFinishing()) {
            Log.i(TAG, "memory running low (level " + level + ")" + (onScreen && !topResumed ? "" : ", game in front or not on screen"));
            return;
        }
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastLowMemoryRefocus < 2000) return;
        lastLowMemoryRefocus = now;
        Log.i(TAG, "memory running low (level " + level + ") with another app in front: game back in front");
        bringToFront();
    }

    /** Make the game the focused (top) activity again on its own screen: starting it there again
     *  (singleTask: no new instance, see onNewIntent) moves Android's focus to that screen. */
    private void bringToFront() {
        try {
            android.app.ActivityOptions o = android.app.ActivityOptions.makeBasic();
            if (Compat.display(this) != null) o.setLaunchDisplayId(Compat.display(this).getDisplayId());
            startActivity(new Intent(this, GameActivity.class).putExtra("refocus", true)
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_NO_ANIMATION), o.toBundle());
        } catch (RuntimeException e) { Log.w(TAG, "game not brought back in front: " + e); }
    }

    /** Android's install confirmation for Pixel Guide is on the bottom screen: the panels step
     *  aside so it can be seen (guideClosed puts them back when the install ends). */
    static void panelsAside() {
        GameActivity a = current;
        if (a == null) return;
        a.runOnUiThread(() -> { if (a.companion != null) a.companion.hide(); });
    }

    /** Pixel Guide's install ended: the panels come back. */
    static void guideClosed() {
        GameActivity a = current;
        if (a == null) return;
        a.runOnUiThread(() -> {
            a.showGuideTab(false);
            a.showPanels();
        });
    }

    // Switching between Pixel Guide and the panels without closing the guide (GitHub #30): the
    // guide keeps running under the panels (a Presentation is drawn above activities on that
    // screen), so it is where it was left. Over the guide, a small back button brings the
    // panels back; on the panels, the book brings the guide to the front again.
    private Presentation guideTab;

    private void showPanels() {
        if (companion == null || companion.isShowing()) return;
        companion.show();
        Compat.hideSystemBars(companion.getWindow());
    }

    private void showGuideTab(boolean on) {
        if (!on) { if (guideTab != null) guideTab.hide(); return; }
        if (companion == null) return;
        if (guideTab == null) {
            Display d = companion.getDisplay();
            guideTab = new Presentation(this, d) {
                @Override public boolean dispatchKeyEvent(KeyEvent e) { return GameActivity.this.dispatchKeyEvent(e); }
                @Override public boolean dispatchGenericMotionEvent(MotionEvent e) { return GameActivity.this.dispatchGenericMotionEvent(e); }
            };
            float dp = getResources().getDisplayMetrics().density;
            // a back arrow, drawn (no text, so it covers as little of the guide as it can)
            android.view.View tab = new android.view.View(guideTab.getContext()) {
                final android.graphics.Paint pen = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
                final android.graphics.Path arrow = new android.graphics.Path();
                @Override protected void onDraw(android.graphics.Canvas c) {
                    float w = getWidth(), h = getHeight(), cx = w / 2, cy = h / 2, r = Math.min(w, h) * 0.24f;
                    pen.setColor(0xFFFFFFFF);
                    pen.setStyle(android.graphics.Paint.Style.STROKE);
                    pen.setStrokeWidth(3.2f * dp);
                    pen.setStrokeCap(android.graphics.Paint.Cap.ROUND);
                    pen.setStrokeJoin(android.graphics.Paint.Join.ROUND);
                    arrow.reset();
                    arrow.moveTo(cx + r, cy);
                    arrow.lineTo(cx - r, cy);
                    arrow.moveTo(cx - r * 0.1f, cy - r * 0.9f);
                    arrow.lineTo(cx - r, cy);
                    arrow.lineTo(cx - r * 0.1f, cy + r * 0.9f);
                    c.drawPath(arrow, pen);
                }
            };
            tab.setContentDescription("Back to the panels");
            tab.setMinimumWidth((int) (52 * dp));
            tab.setMinimumHeight((int) (52 * dp));
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.TL_BR, new int[] { 0xF02B4BA8, 0xF00B1A4E });
            bg.setCornerRadius(12 * dp);
            bg.setStroke((int) (2 * dp), 0xFFDCDCF0);
            tab.setBackground(bg);
            tab.setOnClickListener(v -> {
                v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
                showGuideTab(false);
                showPanels();
            });
            guideTab.setContentView(tab, new android.view.ViewGroup.LayoutParams((int) (52 * dp), (int) (52 * dp)));
            android.view.Window w = guideTab.getWindow();
            // a small window: touches beside it go to the guide, keys stay with the game
            w.addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);
            w.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
            android.view.WindowManager.LayoutParams lp = w.getAttributes();
            lp.width = android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
            lp.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
            lp.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.END;
            lp.x = (int) (16 * dp);
            lp.y = (int) (16 * dp);
            w.setAttributes(lp);
            guideTab.setCanceledOnTouchOutside(false);
        }
        guideTab.show();
    }

    /** libff7.so: built on this device from the player's exe (GameBuild.java), or bundled in a
     *  development APK. Loaded before the first native call (onCreate). */
    private static boolean loaded;

    private static void loadGame(android.content.Context ctx) {
        if (loaded) return;
        // Test: FF7_SIMULATE_STATIC_TAKEN=1 in files/env.txt (the rest of env.txt is applied by
        // nativeStart; this one is read while the library loads, src/ffnx_port/ffnx_guest_static.cpp).
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(
                new java.io.File(Storage.root(ctx), "env.txt")))) {
            for (String line; (line = r.readLine()) != null; )
                if (line.trim().equals("FF7_SIMULATE_STATIC_TAKEN=1"))
                    android.system.Os.setenv("FF7_SIMULATE_STATIC_TAKEN", "1", true);
        } catch (Exception ignored) {
        }
        if (new java.io.File(ctx.getApplicationInfo().nativeLibraryDir, "libff7.so").isFile()) System.loadLibrary("ff7");
        else System.load(new java.io.File(ctx.getFilesDir(), "game/libff7.so").getAbsolutePath());
        loaded = true;
    }

    private static native void nativeSetSurface(Surface surface, int width, int height);
    private static native void nativeSetCompanionSurface(Surface surface, int width, int height);
    private static native void nativeStart(String gameDir, String logDir);
    private static native void nativePad(int buttons, int lt, int rt, int lx, int ly, int rx, int ry);
    private static native int nativeRumbleWait(int timeoutMs);

    // ---- controls: one XInput-style pad (XINPUT_GAMEPAD_* bits), fed to FFNx's XInput path.
    private int padButtons;
    private int padLt, padRt, padLx, padLy, padRx, padRy;
    private int hatButtons;

    private static int xinputBit(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:      return 0x0001;
            case KeyEvent.KEYCODE_DPAD_DOWN:    return 0x0002;
            case KeyEvent.KEYCODE_DPAD_LEFT:    return 0x0004;
            case KeyEvent.KEYCODE_DPAD_RIGHT:   return 0x0008;
            case KeyEvent.KEYCODE_BUTTON_START: return 0x0010;
            case KeyEvent.KEYCODE_BUTTON_SELECT:
            case KeyEvent.KEYCODE_BACK:         return 0x0020;
            case KeyEvent.KEYCODE_BUTTON_THUMBL: return 0x0040;
            case KeyEvent.KEYCODE_BUTTON_THUMBR: return 0x0080;
            case KeyEvent.KEYCODE_BUTTON_L1:    return 0x0100;
            case KeyEvent.KEYCODE_BUTTON_R1:    return 0x0200;
            // FF7 confirms with Circle (XInput B, 0x2000) and cancels with Cross (XInput A,
            // 0x1000), the Japanese PlayStation layout. Android pads confirm with A: A sends
            // Circle, B sends Cross.
            case KeyEvent.KEYCODE_BUTTON_A:     return 0x2000;
            case KeyEvent.KEYCODE_BUTTON_B:     return 0x1000;
            case KeyEvent.KEYCODE_BUTTON_X:     return 0x4000;
            case KeyEvent.KEYCODE_BUTTON_Y:     return 0x8000;
            default: return keyboardBit(keyCode);
        }
    }

    /**
     * A physical keyboard (#29): arrows or the number pad move, Enter/Space/Z confirm (Circle),
     * Backspace/X cancel (Cross), Tab/C open the menu (Triangle), V is Square, Q/Page Up and
     * E/Page Down are L1/R1, 1/Home and 3/End are L2/R2 (see dispatchKeyEvent), Esc/P is Start,
     * Shift/Insert is Select.
     */
    private static int keyboardBit(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_NUMPAD_8:     return 0x0001;
            case KeyEvent.KEYCODE_NUMPAD_2:     return 0x0002;
            case KeyEvent.KEYCODE_NUMPAD_4:     return 0x0004;
            case KeyEvent.KEYCODE_NUMPAD_6:     return 0x0008;
            case KeyEvent.KEYCODE_ESCAPE:
            case KeyEvent.KEYCODE_P:            return 0x0010;
            case KeyEvent.KEYCODE_SHIFT_LEFT:
            case KeyEvent.KEYCODE_SHIFT_RIGHT:
            case KeyEvent.KEYCODE_INSERT:       return 0x0020;
            case KeyEvent.KEYCODE_Q:
            case KeyEvent.KEYCODE_PAGE_UP:      return 0x0100;
            case KeyEvent.KEYCODE_E:
            case KeyEvent.KEYCODE_PAGE_DOWN:    return 0x0200;
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_SPACE:
            case KeyEvent.KEYCODE_Z:            return 0x2000;
            case KeyEvent.KEYCODE_DEL:
            case KeyEvent.KEYCODE_X:            return 0x1000;
            case KeyEvent.KEYCODE_V:            return 0x4000;
            case KeyEvent.KEYCODE_TAB:
            case KeyEvent.KEYCODE_C:            return 0x8000;
            default: return 0;
        }
    }

    // the on-screen pad (TouchControls), added to the real one
    private int touchButtons, touchLt, touchRt, touchRx, touchRy;
    private TouchControls touch;
    /** Launcher setting: 0 auto (one screen and no controller), 1 always, 2 off. */
    private int touchMode;
    private boolean portrait;
    /** Split (Settings > Screen): the game on top and the second screen's panels below it on the
     *  same screen, chosen by the window's shape rather than its orientation (a foldable's inner
     *  screen is about square and often wider than tall, so "portrait" never comes). */
    private boolean split;
    /** Side by side (Settings > Screen): Split, but on a window wider than tall the panels are at
     *  the game's right instead of below it (one larger handheld screen: the game and the second
     *  screen's panels beside each other). Held upright it is Split. */
    private boolean side, arrangedSide;
    /** Side by side: the panels at the game's left instead of its right (Settings > Screen). */
    private boolean sideLeft;
    /** Side by side: the panels hidden (their hide button), the game on the whole screen; a small
     *  tab at the screen's edge brings them back. Kept between games ("companion" prefs). */
    private boolean panelsHidden;
    private android.view.View showTab;
    private android.widget.FrameLayout bottom;      // split: the panels, the on-screen pad over them

    private android.widget.FrameLayout gameFrame;   // the game's SurfaceView and the booster icons
    private CompanionView panel;                     // portrait: the panels below the game
    private boolean surfaceReady, waitPosted, arrangedTall;

    /** The game's frame: the whole window, or (portrait) as wide as the window and as tall as
     *  the game's aspect ratio makes it. */
    private static final class AspectFrame extends android.widget.FrameLayout {
        private final float ratio;   // height / width
        boolean fill = true;
        /** Side by side: as tall as the window allows at the game's aspect ratio, leaving at least
         *  a quarter of the width to the panels (a 4:3 picture on a 16:9 screen fills the height). */
        boolean side, sideFull;   // sideFull: the panels hidden, the whole window
        AspectFrame(Context c, float ratio) { super(c); this.ratio = ratio; }
        @Override protected void onMeasure(int w, int h) {
            if (fill) { super.onMeasure(w, h); return; }
            if (side && sideFull) {
                super.onMeasure(MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(w), MeasureSpec.EXACTLY),
                                MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(h), MeasureSpec.EXACTLY));
                return;
            }
            if (side) {
                int maxW = Math.round(MeasureSpec.getSize(w) * 0.75f), maxH = MeasureSpec.getSize(h);
                int width = Math.min(maxW, Math.round(maxH / ratio));
                int height = Math.min(maxH, Math.round(width * ratio));
                super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
                return;
            }
            int width = MeasureSpec.getSize(w);
            int height = Math.round(width * ratio);
            super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
        }
    }

    /** The window is taller than wide (a phone held upright, or a split-screen half). */
    private boolean isTall() {
        return getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT;
    }

    /** Split: the window has room for a usable panel area under the game at its aspect ratio
     *  (at least a quarter of the window and 200 dp). A foldable's inner screen has it held either
     *  way; its cover screen and an ordinary phone in landscape do not, and get the usual layout. */
    private boolean splitRoom() {
        android.content.res.Configuration c = getResources().getConfiguration();
        float ratio = getIntent().getIntExtra("aspect", 2) == 0 ? 3f / 4f : 9f / 16f;
        float left = c.screenHeightDp - c.screenWidthDp * ratio;
        boolean room = left >= 200 && left >= c.screenHeightDp * 0.25f;
        Log.i(TAG, "split: window " + c.screenWidthDp + "x" + c.screenHeightDp + " dp, " + Math.round(left)
                + " dp below the game: " + (room ? "game on top, panels below" : "no room, landscape"));
        return room;
    }

    /** The window is wider than tall (by its shape, not the device's orientation). */
    private boolean isWide() {
        android.content.res.Configuration c = getResources().getConfiguration();
        return c.screenWidthDp > c.screenHeightDp;
    }

    /** The panels on this screen: below the game (Split, Portrait) or beside it (Side by side). */
    private boolean wantStacked() {
        if (side && isWide()) return true;
        return split ? splitRoom() : portrait && isTall();
    }

    /**
     * Lay the screen out: landscape (the game fills the window, the on-screen pad over it) or
     * tall (portrait setting: the game on top at its own aspect ratio; below it the panels and
     * touch deck the AYN Thor shows on its second screen, CompanionView, then the on-screen
     * pad; with the pad hidden, a controller in use, the panels take the space).
     */
    private void arrange(boolean tall) {
        arrangedTall = tall;
        for (android.view.View v : new android.view.View[] { gameFrame, panel, touch })
            if (v != null && v.getParent() != null) ((android.view.ViewGroup) v.getParent()).removeView(v);
        if (bottom != null && bottom.getParent() != null) ((android.view.ViewGroup) bottom.getParent()).removeView(bottom);
        ((AspectFrame) gameFrame).fill = !tall;
        ((AspectFrame) gameFrame).side = false;
        if (showTab != null && showTab.getParent() != null) ((android.view.ViewGroup) showTab.getParent()).removeView(showTab);
        if (bottom != null) bottom.setVisibility(android.view.View.VISIBLE);
        if (panel != null) panel.setOnHide(null, false);
        arrangedSide = tall && side && isWide();
        if (arrangedSide) { arrangeSide(); return; }
        if (tall && split) { arrangeSplit(); return; }
        if (!tall) {
            if (touch != null) touch.setPassThrough(false);
            if (touch != null)
                gameFrame.addView(touch, new android.widget.FrameLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT));
            setContentView(gameFrame);
            return;
        }
        android.widget.LinearLayout col = new android.widget.LinearLayout(this);
        col.setOrientation(android.widget.LinearLayout.VERTICAL);
        col.setBackgroundColor(0xFF000000);
        col.addView(gameFrame, new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
        col.addView(panel, new android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, 0, 2f));
        if (touch != null)
            col.addView(touch, new android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, 0, 3f));
        setContentView(col);
    }

    /**
     * Split: the game across the top at its aspect ratio; below it the panels the AYN Thor shows on
     * its second screen (CompanionView, with the deck and CRT settings of the second screen). The
     * on-screen pad, when it shows, lies over that area: the D-pad and L buttons at its left, the
     * face and R buttons at its right, and the panels narrow to the space between (fitPanels).
     * Touches the pad does not use go through to the panels.
     */
    private void arrangeSplit() {
        android.widget.LinearLayout col = new android.widget.LinearLayout(this);
        col.setOrientation(android.widget.LinearLayout.VERTICAL);
        col.setBackgroundColor(0xFF000000);
        col.addView(gameFrame, new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
        if (bottom == null) {
            bottom = new android.widget.FrameLayout(this);
            bottom.addView(panel, new android.widget.FrameLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT));
            if (getIntent().getBooleanExtra("crt_filter", false)) bottom.addView(new CrtOverlay(this));
        } else if (panel.getParent() == null) {
            bottom.addView(panel, 0);
        }
        if (touch != null) {
            touch.setPassThrough(true);
            touch.setOnPlaced(this::fitPanels);
            bottom.addView(touch, new android.widget.FrameLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT));
        }
        col.addView(bottom, new android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(col);
    }

    /**
     * Side by side: the game at its aspect ratio, as tall as the window (centred when the picture
     * is wider than three quarters of the window); beside it, at its right or (sideLeft) its left,
     * the panels the AYN Thor shows on its second screen (CompanionView, which lays itself out for
     * a tall, narrow area). The panels' hide button gives the game the whole window and shows a
     * small tab at that edge to bring them back; only visibility and sizes change, so the game's
     * surface stays where it is. The on-screen pad, when it shows, lies over the whole window;
     * touches it does not use go through to the panels.
     */
    private void arrangeSide() {
        ((AspectFrame) gameFrame).side = true;
        android.widget.LinearLayout row = new android.widget.LinearLayout(this);
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        row.setBackgroundColor(0xFF000000);
        android.widget.LinearLayout.LayoutParams glp = new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        glp.gravity = android.view.Gravity.CENTER_VERTICAL;
        if (bottom == null) {
            bottom = new android.widget.FrameLayout(this);
            bottom.addView(panel, new android.widget.FrameLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT));
            if (getIntent().getBooleanExtra("crt_filter", false)) bottom.addView(new CrtOverlay(this));
        } else if (panel.getParent() == null) {
            bottom.addView(panel, 0);
        }
        android.widget.FrameLayout.LayoutParams plp = (android.widget.FrameLayout.LayoutParams) panel.getLayoutParams();
        plp.leftMargin = plp.rightMargin = plp.bottomMargin = 0;
        panel.setLayoutParams(plp);
        android.widget.LinearLayout.LayoutParams blp = new android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        if (sideLeft) { row.addView(bottom, blp); row.addView(gameFrame, glp); }
        else { row.addView(gameFrame, glp); row.addView(bottom, blp); }
        // the hide button sits on the panels' edge next to the game
        panel.setOnHide(() -> setPanelsHidden(true), !sideLeft);
        android.widget.FrameLayout root = new android.widget.FrameLayout(this);
        root.addView(row, new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT));
        if (showTab == null) {
            showTab = new ShowTab(this, sideLeft);
            showTab.setOnClickListener(v -> setPanelsHidden(false));
        } else if (showTab.getParent() != null) {
            ((android.view.ViewGroup) showTab.getParent()).removeView(showTab);
        }
        float d = getResources().getDisplayMetrics().density;
        root.addView(showTab, new android.widget.FrameLayout.LayoutParams(Math.round(30 * d), Math.round(76 * d),
                android.view.Gravity.CENTER_VERTICAL | (sideLeft ? android.view.Gravity.LEFT : android.view.Gravity.RIGHT)));
        if (touch != null) {
            touch.setPassThrough(true);
            touch.setOnPlaced(this::fitPanels);
            root.addView(touch, new android.widget.FrameLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT));
        }
        applyPanelsHidden();
        setContentView(root);
    }

    /** Side by side: hide the panels (the game takes the whole window) or bring them back. */
    private void setPanelsHidden(boolean hidden) {
        if (panelsHidden == hidden) return;
        panelsHidden = hidden;
        getSharedPreferences("companion", Context.MODE_PRIVATE).edit().putBoolean("side_hidden", hidden).apply();
        applyPanelsHidden();
    }

    private void applyPanelsHidden() {
        if (bottom == null || showTab == null) return;
        ((AspectFrame) gameFrame).sideFull = panelsHidden;
        bottom.setVisibility(panelsHidden ? android.view.View.GONE : android.view.View.VISIBLE);
        showTab.setVisibility(panelsHidden ? android.view.View.VISIBLE : android.view.View.GONE);
        gameFrame.requestLayout();
    }

    /** The tab at the screen's edge that brings the hidden panels back: a chevron pointing in. */
    private static final class ShowTab extends android.view.View {
        private final boolean left;
        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.RectF box = new android.graphics.RectF();
        ShowTab(Context c, boolean left) {
            super(c);
            this.left = left;
            setContentDescription("Show the panels");
        }
        @Override protected void onDraw(android.graphics.Canvas c) {
            float w = getWidth(), h = getHeight(), r = w * 0.45f;
            // rounded on the inner side only: a tab sticking out of the screen's edge
            box.set(left ? -r : 0, 0, left ? w : w + r, h);
            paint.setStyle(android.graphics.Paint.Style.FILL);
            paint.setColor(0xB01A2A6A);
            c.drawRoundRect(box, r, r, paint);
            paint.setStyle(android.graphics.Paint.Style.STROKE);
            paint.setStrokeWidth(w * 0.07f);
            paint.setColor(0xD0DCDCF0);
            c.drawRoundRect(box, r, r, paint);
            paint.setStrokeWidth(w * 0.1f);
            paint.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            paint.setColor(0xFFFFFFFF);
            float cx = w / 2, cy = h / 2, dx = w * 0.16f, dy = h * 0.14f;
            float tip = left ? cx + dx : cx - dx, back = left ? cx - dx : cx + dx;   // pointing into the screen
            c.drawLine(back, cy - dy, tip, cy, paint);
            c.drawLine(tip, cy, back, cy + dy, paint);
        }
    }

    /** Split: the panels between the pad's two sides while it shows, the whole width otherwise. */
    private void fitPanels() {
        if (!split || panel == null || !(panel.getLayoutParams() instanceof android.widget.FrameLayout.LayoutParams)) return;
        android.widget.FrameLayout.LayoutParams lp = (android.widget.FrameLayout.LayoutParams) panel.getLayoutParams();
        boolean on = touch != null && touch.getVisibility() == android.view.View.VISIBLE && touch.getParent() == bottom;
        int gap = Math.round(8 * getResources().getDisplayMetrics().density);
        int l = on ? Math.round(touch.leftEdge()) + gap : 0, r = on ? Math.round(touch.rightEdge()) + gap : 0, b = 0;
        // a narrow screen (a foldable's cover screen held upright): too little room between the
        // pad's sides, so the panels take the whole width above the pad instead (#56)
        if (on && touch.narrow()) {
            l = r = 0;
            b = Math.max(0, Math.round(touch.getHeight() - touch.topEdge()) + gap);
        }
        if (lp.leftMargin == l && lp.rightMargin == r && lp.bottomMargin == b) return;
        lp.leftMargin = l;
        lp.rightMargin = r;
        lp.bottomMargin = b;
        panel.setLayoutParams(lp);
    }

    private void setTouchVisible(boolean show) {
        touch.setVisibility(show ? android.view.View.VISIBLE : android.view.View.GONE);
        fitPanels();
    }

    private String touchLayoutKey = "touch_layout";

    /** The on-screen controls' layout for this screen's shape (#66; the launcher sends both). */
    private String touchLayout() {
        String main = getIntent().getStringExtra("touch_layout");
        if (!touchLayoutKey.equals("touch_layout") && getIntent().hasExtra(touchLayoutKey))
            return getIntent().getStringExtra(touchLayoutKey);
        return main;
    }

    /** Portrait chosen: follow the window's shape until the game starts (its renderer is made at
     *  the first size). */
    @Override
    public void onConfigurationChanged(android.content.res.Configuration c) {
        super.onConfigurationChanged(c);
        if ((portrait || split) && !started
                && (wantStacked() != arrangedTall || (side && wantStacked() && isWide() != arrangedSide))) arrange(wantStacked());
        // folded or unfolded (#66): the on-screen controls' layout for the screen now in use
        if (touch != null && !TouchControls.layoutKey(this).equals(touchLayoutKey)) {
            touchLayoutKey = TouchControls.layoutKey(this);
            touch.setLayout(touchLayout());
        }
        ui.post(this::followGameDisplay);
    }

    /** The game was moved to another screen after the panels were placed (GitHub #49: the
     *  Ayaneo Pocket DS put the game started on its small screen back on the big one, where the
     *  panels already were and covered it: a blank screen with the game's sound). The panels go
     *  to the screen the game is not on. */
    private void followGameDisplay() {
        if (portrait || (split && arrangedTall) || panelsFor < 0 || isFinishing()) return;
        Display mine = Compat.display(this);
        if (mine == null || mine.getDisplayId() == panelsFor) return;
        displayLog("game moved from display " + panelsFor + " to " + mine.getDisplayId() + ": panels placed again");
        if (companion != null) { companion.dismiss(); companion = null; }
        if (companionHost != null) { Activity h = companionHost; companionHost = null; h.finish(); }
        panelsFor = -1;
        showCompanion();
        updateTouch();
    }

    // ---- test API: adb holds pad buttons for a set time (`input keyevent` presses are too short
    // for the game to see, and --longpress holds long enough for menus to auto-repeat). Only
    // the shell can send it (DUMP), like TestControl:
    //   am broadcast -a com.ff7recomp.test.PAD --ei buttons 0x2 --ei ms 100
    private int testButtons;
    private final Runnable testRelease = () -> { testButtons = 0; sendPad(); };
    private final android.content.BroadcastReceiver testPad = new android.content.BroadcastReceiver() {
        @Override public void onReceive(Context c, android.content.Intent i) {
            ui.removeCallbacks(testRelease);
            testButtons = i.getIntExtra("buttons", 0);
            sendPad();
            ui.postDelayed(testRelease, Math.max(16, Math.min(10000, i.getIntExtra("ms", 100))));
        }
    };

    private void sendPad() {
        int buttons = padButtons | hatButtons | touchButtons | testButtons;
        // the on-screen right stick (#62) when the real one is centred
        boolean real = padRx != 0 || padRy != 0;
        nativePad(buttons, Math.max(padLt, touchLt), Math.max(padRt, touchRt),
                  padLx, padLy, real ? padRx : touchRx, real ? padRy : touchRy);
        // Select+Start held for a second: leave the game (GitHub #42)
        boolean combo = (buttons & 0x0030) == 0x0030;
        if (combo != quitHeld) {
            quitHeld = combo;
            if (combo) ui.postDelayed(askQuit, 1000);
            else ui.removeCallbacks(askQuit);
        }
    }

    // ---- the screen's real refresh rate (GitHub #46) --------------------------------------------
    // The game draws one frame per vsync. When the system keeps the screen at 60 Hz (power saving,
    // a phone's refresh-rate setting, a game booster), 120 FPS can't go above 60 however the app
    // asks. Every 10 s the rate is counted over one second of vsyncs (Choreographer); changes go to
    // files/display.txt, which Report a problem includes. With 120 FPS chosen and the screen held
    // at 60 Hz, the player is told once.
    private static final long DISPLAY_EVERY_MS = 10_000;
    private int displayHzSeen = -1, displayLowSamples;
    private boolean displayToldLow, displayWatching, displayFileStarted;
    private final Runnable displaySample = this::sampleDisplayRate;

    private void startDisplayWatch() {
        Display d = Compat.display(this);
        StringBuilder s = new StringBuilder();
        s.append("display ").append(d == null ? "?" : d.getDisplayId());
        if (d != null) {
            s.append(": mode ").append(d.getMode().getModeId()).append(" @ ").append(hz(d.getMode().getRefreshRate())).append(" Hz; modes");
            for (Display.Mode m : d.getSupportedModes())
                s.append(' ').append(m.getModeId()).append('=').append(m.getPhysicalWidth()).append('x')
                 .append(m.getPhysicalHeight()).append('@').append(hz(m.getRefreshRate()));
        }
        android.os.PowerManager pm = (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            s.append("; power saving ").append(pm.isPowerSaveMode() ? "on" : "off");
            if (android.os.Build.VERSION.SDK_INT >= 29) s.append(", thermal status ").append(pm.getCurrentThermalStatus());
        }
        s.append("; frame rate setting ").append(getIntent().getIntExtra("fps", -1));
        displayLog(s.toString());
        displayWatching = true;
        ui.postDelayed(displaySample, 3000);
    }

    private void sampleDisplayRate() {
        if (!displayWatching) return;
        final android.view.Choreographer ch = android.view.Choreographer.getInstance();
        ch.postFrameCallback(new android.view.Choreographer.FrameCallback() {
            long first; int frames;
            @Override public void doFrame(long t) {
                if (!displayWatching) return;
                if (first == 0) first = t;
                else frames++;
                if (t - first < 1_000_000_000L) { ch.postFrameCallback(this); return; }
                int rate = Math.round(frames * 1e9f / (t - first));
                displayMeasured(rate);
                ui.postDelayed(displaySample, DISPLAY_EVERY_MS);
            }
        });
    }

    private void displayMeasured(int rate) {
        Display d = Compat.display(this);
        int modeHz = d == null ? 0 : Math.round(d.getMode().getRefreshRate());
        if (Math.abs(rate - displayHzSeen) > 5) {
            displayHzSeen = rate;
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
            displayLog("screen at " + rate + " Hz (mode " + modeHz + " Hz"
                    + (pm != null && pm.isPowerSaveMode() ? ", power saving on" : "") + ")");
        }
        // 120 FPS chosen (Settings: 4), the screen can go above 60 Hz, but it is held at 60
        boolean low = getIntent().getIntExtra("fps", -1) == 4 && rate <= 70 && maxModeHz(d) >= 90;
        displayLowSamples = low ? displayLowSamples + 1 : 0;
        if (displayLowSamples >= 2 && !displayToldLow && hasWindowFocus()) {
            displayToldLow = true;
            displayLog("told the player: 120 FPS chosen, the screen is held at " + rate + " Hz");
            android.widget.Toast.makeText(this, "Your screen is running at 60 Hz, so 120 FPS can't go above 60. "
                    + "Turn off power saving and set the screen's refresh rate to its highest "
                    + "(Samsung: Settings > Display > Motion smoothness > Adaptive), or choose 60 FPS.",
                    android.widget.Toast.LENGTH_LONG).show();
        }
    }

    private static int maxModeHz(Display d) {
        float best = 0;
        if (d != null) for (Display.Mode m : d.getSupportedModes()) best = Math.max(best, m.getRefreshRate());
        return Math.round(best);
    }

    private static String hz(float f) { return String.valueOf(Math.round(f)); }

    private void displayLog(String line) {
        Log.i(TAG, line);
        File f = new File(Storage.root(this), "display.txt");
        boolean append = displayFileStarted;   // a new file each run
        displayFileStarted = true;
        try (java.io.FileWriter w = new java.io.FileWriter(f, append)) {
            w.write(new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(new java.util.Date()) + " " + line + "\n");
        } catch (java.io.IOException e) {
            Log.w(TAG, "display.txt: " + e);
        }
    }

    // ---- leaving the game: Select+Start held, then Leave. The game closes as it does on
    // Windows (WM_CLOSE, ff7_android_main.cpp), its process ends and the launcher is back.
    private static native void nativeQuit();
    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable askQuit = this::askQuit;
    private boolean quitHeld;
    private android.app.AlertDialog quitDialog;

    private void askQuit() {
        quitHeld = false;
        if (isFinishing() || (quitDialog != null && quitDialog.isShowing())) return;
        // the dialog takes the keys from here: the game sees everything let go
        padButtons = hatButtons = touchButtons = testButtons = 0;
        padLt = padRt = touchLt = touchRt = touchRx = touchRy = 0;
        sendPad();
        quitDialog = new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Leave the game?")
                .setMessage("You go back to the launcher. Anything since your last save is lost.")
                .setPositiveButton("Leave", (d, w) -> leaveGame())
                .setNegativeButton("Keep playing", null)
                .show();
        android.widget.Button keep = quitDialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE);
        if (keep != null) keep.requestFocus();
    }

    private void leaveGame() {
        Log.i(TAG, "leaving the game (Select+Start)");
        nativeQuit();
        // the game ends the process once it has closed; one that does not within 5 s is ended here
        ui.postDelayed(() -> android.os.Process.killProcess(android.os.Process.myPid()), 5000);
    }

    private static boolean isGamepad(InputDevice d) {
        if (d == null || d.isVirtual()) return false;
        int s = d.getSources();
        return (s & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
            || (s & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
    }

    private static boolean isPhysicalKeyboard(InputDevice d) {
        return d != null && !d.isVirtual() && d.getKeyboardType() == InputDevice.KEYBOARD_TYPE_ALPHABETIC;
    }

    private static boolean gamepadConnected() {
        for (int id : InputDevice.getDeviceIds()) if (isGamepad(InputDevice.getDevice(id))) return true;
        return false;
    }

    /** Shows or hides the on-screen pad: always, never, or (auto) on one screen with no controller. */
    private void updateTouch() {
        if (touch == null) return;
        boolean show = touchMode == 1 || (touchMode == 0 && companion == null && companionHost == null && !gamepadConnected());
        setTouchVisible(show);
        if (!show && (touchButtons != 0 || touchLt != 0 || touchRt != 0 || touchRx != 0 || touchRy != 0)) {
            touchButtons = touchLt = touchRt = touchRx = touchRy = 0;
            sendPad();
        }
    }

    private final android.hardware.input.InputManager.InputDeviceListener deviceListener =
            new android.hardware.input.InputManager.InputDeviceListener() {
        @Override public void onInputDeviceAdded(int id) { updateTouch(); }
        @Override public void onInputDeviceRemoved(int id) { updateTouch(); }
        @Override public void onInputDeviceChanged(int id) { updateTouch(); }
    };

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        // Digital L2/R2 on pads that report them as keys; 1/Home and 3/End on a keyboard.
        boolean l2 = code == KeyEvent.KEYCODE_BUTTON_L2 || code == KeyEvent.KEYCODE_1 || code == KeyEvent.KEYCODE_MOVE_HOME;
        boolean r2 = code == KeyEvent.KEYCODE_BUTTON_R2 || code == KeyEvent.KEYCODE_3 || code == KeyEvent.KEYCODE_MOVE_END;
        if (l2 || r2) {
            int v = e.getAction() == KeyEvent.ACTION_DOWN ? 255 : 0;
            if (l2) padLt = v; else padRt = v;
            sendPad();
            return true;
        }
        int bit = xinputBit(code);
        if (bit == 0) return super.dispatchKeyEvent(e);   // volume etc. stay with the system
        // a controller is in use: the on-screen pad (auto) steps aside
        if (touchMode == 0 && touch != null && touch.getVisibility() == android.view.View.VISIBLE
                && (isGamepad(e.getDevice()) || isPhysicalKeyboard(e.getDevice()))) setTouchVisible(false);
        if (e.getAction() == KeyEvent.ACTION_DOWN) padButtons |= bit;
        else if (e.getAction() == KeyEvent.ACTION_UP) padButtons &= ~bit;
        sendPad();
        return true;
    }

    private static int axis16(float v) { return Math.max(-32768, Math.min(32767, Math.round(v * 32767f))); }
    private static int trigger8(float v) { return Math.max(0, Math.min(255, Math.round(v * 255f))); }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent e) {
        if ((e.getSource() & InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK
                || e.getAction() != MotionEvent.ACTION_MOVE) return super.dispatchGenericMotionEvent(e);
        // XInput's Y axes point up; Android's point down.
        padLx = axis16(e.getAxisValue(MotionEvent.AXIS_X));
        padLy = axis16(-e.getAxisValue(MotionEvent.AXIS_Y));
        padRx = axis16(e.getAxisValue(MotionEvent.AXIS_Z));
        padRy = axis16(-e.getAxisValue(MotionEvent.AXIS_RZ));
        padLt = trigger8(Math.max(e.getAxisValue(MotionEvent.AXIS_LTRIGGER), e.getAxisValue(MotionEvent.AXIS_BRAKE)));
        padRt = trigger8(Math.max(e.getAxisValue(MotionEvent.AXIS_RTRIGGER), e.getAxisValue(MotionEvent.AXIS_GAS)));
        // D-pads that report as a hat.
        float hx = e.getAxisValue(MotionEvent.AXIS_HAT_X), hy = e.getAxisValue(MotionEvent.AXIS_HAT_Y);
        hatButtons = (hy < -0.5f ? 0x0001 : 0) | (hy > 0.5f ? 0x0002 : 0)
                   | (hx < -0.5f ? 0x0004 : 0) | (hx > 0.5f ? 0x0008 : 0);
        sendPad();
        return true;
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Reports.keepJavaCrashes(this);
        current = this;
        loadGame(this);
        // The screen stays on while the game runs (it timed out and the device slept mid-game).
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        side = getIntent().getBooleanExtra("side", false);
        sideLeft = getIntent().getBooleanExtra("side_left", false);
        panelsHidden = side && getSharedPreferences("companion", Context.MODE_PRIVATE).getBoolean("side_hidden", false);
        split = side || getIntent().getBooleanExtra("split", false);
        portrait = !split && getIntent().getBooleanExtra("portrait", false);
        if (portrait) setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
        // split: any way the device is held (the manifest's sensorLandscape would turn a
        // foldable's inner screen sideways)
        if (split) setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_USER);
        SurfaceView view = new SurfaceView(this);
        view.getHolder().addCallback(this);
        // the game, and the game boosters' icons over its top-right corner (BoosterIcons)
        int aspect = getIntent().getIntExtra("aspect", 2);
        gameFrame = new AspectFrame(this, aspect == 0 ? 3f / 4f : 9f / 16f);
        gameFrame.addView(view);
        // "Stretch to fill" (aspect 3): the game renders 16:9 and Android scales its buffer to
        // the whole screen, so screens wider than 16:9 (phones, 21:9) have no side bars.
        if (aspect == 3) view.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int w = r - l, h = b - t;
            if (w <= 0 || h <= 0) return;
            if (!arrangedTall && w * 9 > h * 16 + 9) view.getHolder().setFixedSize(Math.round(h * 16f / 9f), h);
            else view.getHolder().setSizeFromLayout();
        });
        gameFrame.addView(new BoosterIcons(this), new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT));
        touchMode = getIntent().getIntExtra("touch_controls", 0);
        if (touchMode != 2) {
            touch = new TouchControls(this, (buttons, lt, rt, rx, ry) -> {
                touchButtons = buttons;
                touchLt = lt;
                touchRt = rt;
                touchRx = rx;
                touchRy = ry;
                sendPad();
            });
            touch.setExtra(getIntent().getBooleanExtra("touch_extra", false));   // right stick, L3, R3 (#62)
            touchLayoutKey = TouchControls.layoutKey(this);
            touch.setLayout(touchLayout());   // size, opacity, positions (#28), this screen's (#66)
        }
        if (portrait || split) panel = new CompanionView(this);
        if (split) panel.setDeck(getIntent().getBooleanExtra("bottom_buttons", true));
        arrange(wantStacked());
        preferHighestRefreshRate(getWindow(), Compat.display(this));
        startDisplayWatch();
        Compat.hideSystemBars(getWindow());
        // in portrait and split the panels are on this screen; Split on a screen without room
        // below the game (a dual-screen handheld's top screen) keeps the second screen's panels
        if (!portrait && !(split && arrangedTall)) showCompanion();
        updateTouch();
        ((android.hardware.input.InputManager) getSystemService(Context.INPUT_SERVICE)).registerInputDeviceListener(deviceListener, null);
        android.content.IntentFilter padFilter = new android.content.IntentFilter("com.ff7recomp.test.PAD");
        if (android.os.Build.VERSION.SDK_INT >= 33)
            registerReceiver(testPad, padFilter, android.Manifest.permission.DUMP, null, Context.RECEIVER_EXPORTED);
        else
            registerReceiver(testPad, padFilter, android.Manifest.permission.DUMP, null);
        startSteamSync();
    }

    /** Steam achievements the game unlocks (files/steam/pending.txt, src/ffnx_port/steam_android.cpp)
     *  go to Steam while playing: checked every 20 s, sent when there are any (SteamLink.java). */
    private volatile boolean steamSyncRunning;

    private void startSteamSync() {
        SteamLink steam = new SteamLink(this);
        if (!steam.active()) return;
        steamSyncRunning = true;
        Thread t = new Thread(() -> {
            while (steamSyncRunning) {
                try { Thread.sleep(20_000); } catch (InterruptedException e) { return; }
                if (steam.waiting() == 0) continue;
                try {
                    java.util.List<String> got = steam.sync();
                    if (!got.isEmpty()) Log.i(TAG, "Steam achievements sent: " + got);
                } catch (Exception e) {
                    Log.w(TAG, "Steam achievements wait for the next try: " + SteamLink.message(e));
                }
            }
        }, "steam-sync");
        t.setDaemon(true);
        t.start();
    }

    /** The display's fastest mode at its current resolution (the Thor's screens: 120 Hz;
     *  Android starts apps in the 60 Hz mode to save power). */
    private static void preferHighestRefreshRate(android.view.Window w, Display d) {
        if (w == null || d == null) return;
        Display.Mode cur = d.getMode(), best = cur;
        for (Display.Mode m : d.getSupportedModes()) {
            if (m.getPhysicalWidth() == cur.getPhysicalWidth() && m.getPhysicalHeight() == cur.getPhysicalHeight()
                    && m.getRefreshRate() > best.getRefreshRate()) best = m;
        }
        android.view.WindowManager.LayoutParams lp = w.getAttributes();
        lp.preferredDisplayModeId = best.getModeId();
        w.setAttributes(lp);
        Log.i(TAG, "display " + d.getDisplayId() + ": mode " + best.getModeId() + " @ " + best.getRefreshRate() + " Hz");
    }

    /** The surface's own frame-rate vote: without it SurfaceFlinger paces a Vulkan FIFO
     *  swapchain at 60 Hz even on a 120 Hz mode. */
    private static void requestFrameRate(Surface s, Display d) {
        if (s == null || d == null || android.os.Build.VERSION.SDK_INT < 30) return;
        float hz = d.getMode().getRefreshRate();
        for (Display.Mode m : d.getSupportedModes()) hz = Math.max(hz, m.getRefreshRate());
        try {
            s.setFrameRate(hz, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE);
        } catch (IllegalArgumentException | IllegalStateException e) {
            Log.w(TAG, "setFrameRate(" + hz + "): " + e);
        }
    }

    /** The second (bottom) screen, if the device has a presentation display. */
    private void showCompanion() {
        if (!getIntent().getBooleanExtra("bottom_screen", true)) {
            // Settings > Screen: the second screen is left to other apps (a guide, a browser)
            Log.i(TAG, "second screen not used (Settings)");
            return;
        }
        DisplayManager dm = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        Display[] displays = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION);
        if (displays.length == 0) {
            Log.i(TAG, "no second display: single-screen mode");
            return;
        }
        Display mine = Compat.display(this);
        Display d = displays[0];
        if (mine != null)   // not the game's own screen
            for (Display x : displays) if (x.getDisplayId() != mine.getDisplayId()) { d = x; break; }
        panelsFor = mine != null ? mine.getDisplayId() : -1;
        if (mine != null && mine.getDisplayId() != Display.DEFAULT_DISPLAY) {
            // swapped (Settings > Screen): the game is on the presentation display, the panels
            // go to the main one, in an activity (CompanionHostActivity calls attachCompanionHost)
            displayLog("screens swapped: game on display " + mine.getDisplayId() + ", panels on the main display");
            try {
                android.app.ActivityOptions o = android.app.ActivityOptions.makeBasic();
                o.setLaunchDisplayId(Display.DEFAULT_DISPLAY);
                startActivity(new Intent(this, CompanionHostActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION), o.toBundle());
            } catch (RuntimeException e) { Log.w(TAG, "panels not shown on the main display: " + e); }
            return;
        }
        if (mine != null && d.getDisplayId() == mine.getDisplayId()) {
            displayLog("only the game's own screen offers a second screen: no panels");
            return;
        }
        displayLog("panels on display " + d.getDisplayId() + " \"" + d.getName() + "\", game on display "
                + (mine == null ? "?" : mine.getDisplayId()));
        // Touching the bottom screen must not take the pad from the game: the window never
        // takes focus, and any key or stick event that reaches it goes to the game anyway.
        companion = new Presentation(this, d) {
            @Override public boolean dispatchKeyEvent(KeyEvent e) { return GameActivity.this.dispatchKeyEvent(e); }
            @Override public boolean dispatchGenericMotionEvent(MotionEvent e) { return GameActivity.this.dispatchGenericMotionEvent(e); }
        };
        companion.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        companion.setContentView(companionLayers(companion.getContext(), d));
        preferHighestRefreshRate(companion.getWindow(), d);
        companion.show();
        Compat.hideSystemBars(companion.getWindow());
    }

    /** The panels on the main display when the screens are swapped (CompanionHostActivity.onCreate). */
    static void attachCompanionHost(Activity host) {
        GameActivity a = current;
        if (a == null) { host.finish(); return; }
        a.companionHost = host;
        Display d = Compat.display(host);
        host.setContentView(a.companionLayers(host, d));
        if (d != null) preferHighestRefreshRate(host.getWindow(), d);
        Compat.hideSystemBars(host.getWindow());
        a.updateTouch();
        // The new activity made the main display the focused one: the game is put back in front
        // on its own screen so the controller keeps playing it (as in guideOpened).
        new android.os.Handler(a.getMainLooper()).postDelayed(() -> {
            if (current != a || a.isFinishing()) return;
            a.bringToFront();
        }, 400);
    }

    static void detachCompanionHost(Activity host) {
        GameActivity a = current;
        if (a != null && a.companionHost == host) { a.companionHost = null; a.updateTouch(); }
    }

    /** The second screen's content: the battle windows' surface, the panels and the CRT filter. */
    private android.view.View companionLayers(Context ctx, Display d) {
        // Under: the game's battle windows (a swap chain native code draws into in battle,
        // src/ffnx_port/companion_render.cpp). Over: the panels and touch deck (CompanionView),
        // which leaves the windows' band clear in battle.
        android.widget.FrameLayout layers = new android.widget.FrameLayout(ctx);
        SurfaceView windows = new SurfaceView(ctx);
        windows.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder h) { }
            @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int hh) {
                Log.i(TAG, "companion surface " + w + "x" + hh);
                if (d != null) requestFrameRate(h.getSurface(), d);
                nativeSetCompanionSurface(h.getSurface(), w, hh);
            }
            @Override public void surfaceDestroyed(SurfaceHolder h) { nativeSetCompanionSurface(null, 0, 0); }
        });
        layers.addView(windows);
        CompanionView panels = new CompanionView(ctx);
        panels.setDeck(getIntent().getBooleanExtra("bottom_buttons", true));
        layers.addView(panels);
        // the top screen's CRT filter here too, over the panels and the battle windows
        if (getIntent().getBooleanExtra("crt_filter", false)) layers.addView(new CrtOverlay(ctx));
        return layers;
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) { }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        Log.i(TAG, "surface " + width + "x" + height);
        Display sd = Compat.display(this);
        displayLog("game surface " + width + "x" + height + " on display " + (sd == null ? "?" : sd.getDisplayId()));
        ui.post(this::followGameDisplay);
        requestFrameRate(holder.getSurface(), Compat.display(this));
        nativeSetSurface(holder.getSurface(), width, height);
        surfaceReady = true;
        // Portrait chosen: start once the window is tall (the renderer is made at the first size).
        // Some devices keep their screen's rotation (large screens may ignore the request, the
        // AYN Thor's is fixed): after a moment the game starts in landscape.
        if (portrait && !started && !isTall()) {
            if (!waitPosted) {
                waitPosted = true;
                new android.os.Handler(getMainLooper()).postDelayed(() -> {
                    if (started) return;
                    Log.i(TAG, "the screen stayed landscape: starting in landscape");
                    if (arrangedTall) arrange(false);
                    if (surfaceReady) startGame();
                }, 1500);
            }
            return;
        }
        startGame();
    }

    private void startGame() {
        if (started) return;
        started = true;
        File base = Storage.root(this);
        File game = new File(base, "game");
        // GitHub #64: "60 FPS, battles at 30" (frame rate 5): FFNx runs its 60 FPS mode with x2
        // battles when FF7_BATTLE30=1 (tools/ffnx_edit_android.py).
        try {
            android.system.Os.setenv("FF7_BATTLE30", getIntent().getIntExtra("fps", -1) == 5 ? "1" : "0", true);
        } catch (Exception ignored) {
        }
        nativeStart(game.getAbsolutePath(), base.getAbsolutePath());
        startRumble(getApplicationContext());
    }

    // ---- Rumble (Settings > Controller > Rumble; src/ffnx_port/rumble.cpp) ----------------------
    // The game posts what to play; this thread takes it (nativeRumbleWait) and plays it on a
    // controller that can vibrate, else on the device itself (the Thor's own motor). Once per
    // process, like the game thread.
    private static boolean rumbleStarted;

    private static void startRumble(Context app) {
        if (rumbleStarted) return;
        rumbleStarted = true;
        Thread t = new Thread(() -> {
            android.os.Vibrator playing = null;
            while (true) {
                int r = nativeRumbleWait(1000);
                if (r < 0) continue;
                int amp = r & 0xFF, ms = r >>> 8;
                android.os.Vibrator v = rumbleVibrator(app);
                if (playing != null && playing != v) playing.cancel();
                playing = v;
                if (v == null) continue;
                try {
                    if (amp == 0 || ms <= 0) { v.cancel(); continue; }
                    android.os.VibrationEffect e = android.os.VibrationEffect.createOneShot(ms,
                            v.hasAmplitudeControl() ? amp : android.os.VibrationEffect.DEFAULT_AMPLITUDE);
                    if (android.os.Build.VERSION.SDK_INT >= 33)
                        v.vibrate(e, android.os.VibrationAttributes.createForUsage(android.os.VibrationAttributes.USAGE_MEDIA));
                    else
                        v.vibrate(e, new android.media.AudioAttributes.Builder()
                                .setUsage(android.media.AudioAttributes.USAGE_GAME).build());
                } catch (RuntimeException ex) {
                    Log.w(TAG, "rumble: " + ex);
                }
            }
        }, "rumble");
        t.setDaemon(true);
        t.start();
    }

    /** A gamepad's vibrator if one has it, else the device's; null if neither. */
    private static android.os.Vibrator rumbleVibrator(Context app) {
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            for (int id : InputDevice.getDeviceIds()) {
                InputDevice d = InputDevice.getDevice(id);
                if (!isGamepad(d)) continue;
                android.os.Vibrator pv = d.getVibratorManager().getDefaultVibrator();
                if (pv != null && pv.hasVibrator()) return pv;
            }
        }
        android.os.Vibrator v = (android.os.Vibrator) app.getSystemService(Context.VIBRATOR_SERVICE);
        return v != null && v.hasVibrator() ? v : null;
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        surfaceReady = false;
        nativeSetSurface(null, 0, 0);
    }

    @Override
    protected void onDestroy() {
        if (companion != null) companion.dismiss();
        if (guideTab != null) guideTab.dismiss();
        if (companionHost != null) { companionHost.finish(); companionHost = null; }
        if (current == this) current = null;
        steamSyncRunning = false;
        displayWatching = false;
        ui.removeCallbacks(displaySample);
        ((android.hardware.input.InputManager) getSystemService(Context.INPUT_SERVICE)).unregisterInputDeviceListener(deviceListener);
        try { unregisterReceiver(testPad); } catch (IllegalArgumentException ignored) { }
        super.onDestroy();
    }
}
