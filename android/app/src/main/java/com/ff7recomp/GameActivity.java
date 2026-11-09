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

    static { System.loadLibrary("ff7"); }

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

    private void sendPad() {
        nativePad(padButtons | hatButtons, padLt, padRt, padLx, padLy, padRx, padRy);
    }

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
        SurfaceView view = new SurfaceView(this);
        view.getHolder().addCallback(this);
        // the game, and the game boosters' icons over its top-right corner (BoosterIcons)
        android.widget.FrameLayout layers = new android.widget.FrameLayout(this);
        layers.addView(view);
        layers.addView(new BoosterIcons(this), new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(layers);
        preferHighestRefreshRate(getWindow(), getDisplay());
        hideSystemBars(getWindow().getInsetsController());
        showCompanion();
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
        if (!started) {
            started = true;
            File base = getExternalFilesDir(null);
            File game = new File(base, "game");
            nativeStart(game.getAbsolutePath(), base.getAbsolutePath());
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        nativeSetSurface(null, 0, 0);
    }

    @Override
    protected void onDestroy() {
        if (companion != null) companion.dismiss();
        super.onDestroy();
    }
}
