package com.ff7recomp;

import android.app.Activity;
import android.app.Presentation;
import android.content.Context;
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
 */
public class GameActivity extends Activity implements SurfaceHolder.Callback {
    private static final String TAG = "FF7Recomp";
    private boolean started;
    private Presentation companion;

    /** libff7.so: built on this device from the player's exe (GameBuild.java), or bundled in a
     *  development APK. Loaded before the first native call (onCreate). */
    private static boolean loaded;

    private static void loadGame(android.content.Context ctx) {
        if (loaded) return;
        if (new java.io.File(ctx.getApplicationInfo().nativeLibraryDir, "libff7.so").isFile()) System.loadLibrary("ff7");
        else System.load(new java.io.File(ctx.getFilesDir(), "game/libff7.so").getAbsolutePath());
        loaded = true;
    }

    private static native void nativeSetSurface(Surface surface, int width, int height);
    private static native void nativeSetCompanionSurface(Surface surface, int width, int height);
    private static native void nativeStart(String gameDir, String logDir);
    private static native void nativePad(int buttons, int lt, int rt, int lx, int ly, int rx, int ry);

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
            default: return 0;
        }
    }

    // the on-screen pad (TouchControls), added to the real one
    private int touchButtons, touchLt, touchRt;
    private TouchControls touch;
    /** Launcher setting: 0 auto (one screen and no controller), 1 always, 2 off. */
    private int touchMode;
    private boolean portrait;

    private android.widget.FrameLayout gameFrame;   // the game's SurfaceView and the booster icons
    private CompanionView panel;                     // portrait: the panels below the game
    private boolean surfaceReady, waitPosted, arrangedTall;

    /** The game's frame: the whole window, or (portrait) as wide as the window and as tall as
     *  the game's aspect ratio makes it. */
    private static final class AspectFrame extends android.widget.FrameLayout {
        private final float ratio;   // height / width
        boolean fill = true;
        AspectFrame(Context c, float ratio) { super(c); this.ratio = ratio; }
        @Override protected void onMeasure(int w, int h) {
            if (fill) { super.onMeasure(w, h); return; }
            int width = MeasureSpec.getSize(w);
            int height = Math.round(width * ratio);
            super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
        }
    }

    /** The window is taller than wide (a phone held upright, or a split-screen half). */
    private boolean isTall() {
        return getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT;
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
        ((AspectFrame) gameFrame).fill = !tall;
        if (!tall) {
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

    /** Portrait chosen: follow the window's shape until the game starts (its renderer is made at
     *  the first size). */
    @Override
    public void onConfigurationChanged(android.content.res.Configuration c) {
        super.onConfigurationChanged(c);
        if (portrait && !started && isTall() != arrangedTall) arrange(isTall());
    }

    private void sendPad() {
        nativePad(padButtons | hatButtons | touchButtons, Math.max(padLt, touchLt), Math.max(padRt, touchRt),
                  padLx, padLy, padRx, padRy);
    }

    private static boolean isGamepad(InputDevice d) {
        if (d == null || d.isVirtual()) return false;
        int s = d.getSources();
        return (s & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
            || (s & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
    }

    private static boolean gamepadConnected() {
        for (int id : InputDevice.getDeviceIds()) if (isGamepad(InputDevice.getDevice(id))) return true;
        return false;
    }

    /** Shows or hides the on-screen pad: always, never, or (auto) on one screen with no controller. */
    private void updateTouch() {
        if (touch == null) return;
        boolean show = touchMode == 1 || (touchMode == 0 && companion == null && !gamepadConnected());
        touch.setVisibility(show ? android.view.View.VISIBLE : android.view.View.GONE);
        if (!show && (touchButtons != 0 || touchLt != 0 || touchRt != 0)) {
            touchButtons = touchLt = touchRt = 0;
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
        // Digital L2/R2 on pads that report them as keys.
        if (code == KeyEvent.KEYCODE_BUTTON_L2 || code == KeyEvent.KEYCODE_BUTTON_R2) {
            int v = e.getAction() == KeyEvent.ACTION_DOWN ? 255 : 0;
            if (code == KeyEvent.KEYCODE_BUTTON_L2) padLt = v; else padRt = v;
            sendPad();
            return true;
        }
        int bit = xinputBit(code);
        if (bit == 0) return super.dispatchKeyEvent(e);   // volume etc. stay with the system
        // a controller is in use: the on-screen pad (auto) steps aside
        if (touchMode == 0 && touch != null && touch.getVisibility() == android.view.View.VISIBLE
                && isGamepad(e.getDevice())) touch.setVisibility(android.view.View.GONE);
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
        loadGame(this);
        portrait = getIntent().getBooleanExtra("portrait", false);
        if (portrait) setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
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
            touch = new TouchControls(this, (buttons, lt, rt) -> {
                touchButtons = buttons;
                touchLt = lt;
                touchRt = rt;
                sendPad();
            });
        }
        if (portrait) panel = new CompanionView(this);
        arrange(portrait && isTall());
        preferHighestRefreshRate(getWindow(), getDisplay());
        hideSystemBars(getWindow().getInsetsController());
        if (!portrait) showCompanion();   // in portrait the panels are on this screen
        updateTouch();
        ((android.hardware.input.InputManager) getSystemService(Context.INPUT_SERVICE)).registerInputDeviceListener(deviceListener, null);
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

    private static void hideSystemBars(WindowInsetsController c) {
        if (c != null) {
            c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
            c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
    }

    /** The second (bottom) screen, if the device has a presentation display. */
    private void showCompanion() {
        DisplayManager dm = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        Display[] displays = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION);
        if (displays.length == 0) {
            Log.i(TAG, "no second display: single-screen mode");
            return;
        }
        Display d = displays[0];
        Log.i(TAG, "companion display " + d.getDisplayId() + " \"" + d.getName() + "\"");
        // Touching the bottom screen must not take the pad from the game: the window never
        // takes focus, and any key or stick event that reaches it goes to the game anyway.
        companion = new Presentation(this, d) {
            @Override public boolean dispatchKeyEvent(KeyEvent e) { return GameActivity.this.dispatchKeyEvent(e); }
            @Override public boolean dispatchGenericMotionEvent(MotionEvent e) { return GameActivity.this.dispatchGenericMotionEvent(e); }
        };
        companion.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        // Under: the game's battle windows (a swap chain native code draws into in battle,
        // src/ffnx_port/companion_render.cpp). Over: the panels and touch deck (CompanionView),
        // which leaves the windows' band clear in battle.
        android.widget.FrameLayout layers = new android.widget.FrameLayout(companion.getContext());
        SurfaceView windows = new SurfaceView(companion.getContext());
        windows.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder h) { }
            @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int hh) {
                Log.i(TAG, "companion surface " + w + "x" + hh);
                requestFrameRate(h.getSurface(), d);
                nativeSetCompanionSurface(h.getSurface(), w, hh);
            }
            @Override public void surfaceDestroyed(SurfaceHolder h) { nativeSetCompanionSurface(null, 0, 0); }
        });
        layers.addView(windows);
        layers.addView(new CompanionView(companion.getContext()));
        companion.setContentView(layers);
        preferHighestRefreshRate(companion.getWindow(), d);
        companion.show();
        hideSystemBars(companion.getWindow().getInsetsController());
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) { }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        Log.i(TAG, "surface " + width + "x" + height);
        requestFrameRate(holder.getSurface(), getDisplay());
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
        nativeStart(game.getAbsolutePath(), base.getAbsolutePath());
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        surfaceReady = false;
        nativeSetSurface(null, 0, 0);
    }

    @Override
    protected void onDestroy() {
        if (companion != null) companion.dismiss();
        steamSyncRunning = false;
        ((android.hardware.input.InputManager) getSystemService(Context.INPUT_SERVICE)).unregisterInputDeviceListener(deviceListener);
        super.onDestroy();
    }
}
