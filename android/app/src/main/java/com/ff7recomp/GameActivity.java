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
    private static GameActivity current;          // for CompanionView and GuideHostActivity.Events (this process)
    static GameActivity currentGame() { return current; }

    /** Pixel Guide is opening on the bottom screen: the panels step aside (a Presentation is
     *  drawn above activities), and the game is put back in front on the top screen so the
     *  pad keeps playing the game while the guide stays in view. */
    static void guideOpened() {
        GameActivity a = current;
        if (a == null) return;
        a.runOnUiThread(() -> {
            if (a.companion != null) a.companion.hide();
            a.showGuideTab(true);
            new android.os.Handler(a.getMainLooper()).postDelayed(() -> {
                // Android gives keys to the display that last started or touched an activity:
                // starting the game again on the top screen (singleTask: no new instance, see
                // onNewIntent) makes that screen the focused one while the guide stays in view.
                try {
                    android.app.ActivityOptions o = android.app.ActivityOptions.makeBasic();
                    if (a.getDisplay() != null) o.setLaunchDisplayId(a.getDisplay().getDisplayId());
                    a.startActivity(new Intent(a, GameActivity.class).putExtra("refocus", true)
                            .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_NO_ANIMATION), o.toBundle());
                } catch (RuntimeException e) { Log.w(TAG, "game not brought back in front: " + e); }
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
        hideSystemBars(companion.getWindow().getInsetsController());
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
        nativePad(buttons, Math.max(padLt, touchLt), Math.max(padRt, touchRt),
                  padLx, padLy, padRx, padRy);
        // Select+Start held for a second: leave the game (GitHub #42)
        boolean combo = (buttons & 0x0030) == 0x0030;
        if (combo != quitHeld) {
            quitHeld = combo;
            if (combo) ui.postDelayed(askQuit, 1000);
            else ui.removeCallbacks(askQuit);
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
        padLt = padRt = touchLt = touchRt = 0;
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
                && (isGamepad(e.getDevice()) || isPhysicalKeyboard(e.getDevice()))) touch.setVisibility(android.view.View.GONE);
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
            touch.setLayout(getIntent().getStringExtra("touch_layout"));   // size, opacity, positions (#28)
        }
        if (portrait) panel = new CompanionView(this);
        arrange(portrait && isTall());
        preferHighestRefreshRate(getWindow(), getDisplay());
        hideSystemBars(getWindow().getInsetsController());
        if (!portrait) showCompanion();   // in portrait the panels are on this screen
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

    private static void hideSystemBars(WindowInsetsController c) {
        if (c != null) {
            c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
            c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
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
        Display d = displays[0];
        Display mine = getDisplay();
        if (mine != null && mine.getDisplayId() != Display.DEFAULT_DISPLAY) {
            // swapped (Settings > Screen): the game is on the presentation display, the panels
            // go to the main one, in an activity (CompanionHostActivity calls attachCompanionHost)
            Log.i(TAG, "screens swapped: game on display " + mine.getDisplayId() + ", panels on the main display");
            try {
                android.app.ActivityOptions o = android.app.ActivityOptions.makeBasic();
                o.setLaunchDisplayId(Display.DEFAULT_DISPLAY);
                startActivity(new Intent(this, CompanionHostActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION), o.toBundle());
            } catch (RuntimeException e) { Log.w(TAG, "panels not shown on the main display: " + e); }
            return;
        }
        Log.i(TAG, "companion display " + d.getDisplayId() + " \"" + d.getName() + "\"");
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
        hideSystemBars(companion.getWindow().getInsetsController());
    }

    /** The panels on the main display when the screens are swapped (CompanionHostActivity.onCreate). */
    static void attachCompanionHost(Activity host) {
        GameActivity a = current;
        if (a == null) { host.finish(); return; }
        a.companionHost = host;
        Display d = host.getDisplay();
        host.setContentView(a.companionLayers(host, d));
        if (d != null) preferHighestRefreshRate(host.getWindow(), d);
        hideSystemBars(host.getWindow().getInsetsController());
        a.updateTouch();
        // The new activity made the main display the focused one: the game is put back in front
        // on its own screen so the controller keeps playing it (as in guideOpened).
        new android.os.Handler(a.getMainLooper()).postDelayed(() -> {
            if (current != a || a.isFinishing()) return;
            try {
                android.app.ActivityOptions o = android.app.ActivityOptions.makeBasic();
                if (a.getDisplay() != null) o.setLaunchDisplayId(a.getDisplay().getDisplayId());
                a.startActivity(new Intent(a, GameActivity.class).putExtra("refocus", true)
                        .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_NO_ANIMATION), o.toBundle());
            } catch (RuntimeException e) { Log.w(TAG, "game not brought back in front: " + e); }
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
        ((android.hardware.input.InputManager) getSystemService(Context.INPUT_SERVICE)).unregisterInputDeviceListener(deviceListener);
        try { unregisterReceiver(testPad); } catch (IllegalArgumentException ignored) { }
        super.onDestroy();
    }
}
