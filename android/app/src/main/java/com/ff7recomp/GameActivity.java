package com.ff7recomp;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import java.io.File;

/**
 * Hosts the lifted FF7: a fullscreen SurfaceView whose Surface goes to native code, where
 * bgfx renders through Vulkan. The game runs on its own native thread, started once the
 * surface exists. Game files are read from the app's external files dir
 * (/sdcard/Android/data/com.ff7recomp/files/game: ff7_en.exe and data/).
 */
public class GameActivity extends Activity implements SurfaceHolder.Callback {
    private static final String TAG = "FF7Recomp";
    private boolean started;

    static { System.loadLibrary("ff7"); }

    private static native void nativeSetSurface(Surface surface, int width, int height);
    private static native void nativeStart(String gameDir, String logDir);

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        SurfaceView view = new SurfaceView(this);
        view.getHolder().addCallback(this);
        setContentView(view);
        hideSystemBars();
    }

    private void hideSystemBars() {
        WindowInsetsController c = getWindow().getInsetsController();
        if (c != null) {
            c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
            c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
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
}
