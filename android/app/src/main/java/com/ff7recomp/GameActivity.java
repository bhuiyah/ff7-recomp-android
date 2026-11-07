package com.ff7recomp;

import android.app.Activity;
import android.app.Presentation;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.util.Log;
import android.view.Display;
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
 * exists, a Presentation with its own SurfaceView is shown there and its Surface goes to
 * native code as the companion screen (docs/ROADMAP.md M7).
 */
public class GameActivity extends Activity implements SurfaceHolder.Callback {
    private static final String TAG = "FF7Recomp";
    private boolean started;
    private Presentation companion;

    static { System.loadLibrary("ff7"); }

    private static native void nativeSetSurface(Surface surface, int width, int height);
    private static native void nativeSetCompanionSurface(Surface surface, int width, int height);
    private static native void nativeStart(String gameDir, String logDir);

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        SurfaceView view = new SurfaceView(this);
        view.getHolder().addCallback(this);
        setContentView(view);
        hideSystemBars(getWindow().getInsetsController());
        showCompanion();
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
        companion = new Presentation(this, d);
        SurfaceView v = new SurfaceView(companion.getContext());
        v.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder h) { }
            @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int hh) {
                Log.i(TAG, "companion surface " + w + "x" + hh);
                nativeSetCompanionSurface(h.getSurface(), w, hh);
            }
            @Override public void surfaceDestroyed(SurfaceHolder h) { nativeSetCompanionSurface(null, 0, 0); }
        });
        companion.setContentView(v);
        companion.show();
        hideSystemBars(companion.getWindow().getInsetsController());
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) { }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        Log.i(TAG, "surface " + width + "x" + height);
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
