package com.ff7recomp;

import android.app.Activity;
import android.os.Build;
import android.view.Display;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;

/** Android 10 (API 29, the app's minimum) lacks a few calls the app uses: Window
 *  .getInsetsController and Context.getDisplay are API 30. Calling them there throws
 *  NoSuchMethodError, which closed the launcher at start on Android 10 phones (GitHub #45). */
final class Compat {
    private Compat() { }

    /** Status and navigation bars hidden; a swipe from the edge shows them for a moment. */
    @SuppressWarnings("deprecation")
    static void hideSystemBars(Window w) {
        if (w == null) return;
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = w.getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
            return;
        }
        w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    /** The display the activity is on. */
    @SuppressWarnings("deprecation")
    static Display display(Activity a) {
        if (a == null) return null;
        if (Build.VERSION.SDK_INT >= 30) return a.getDisplay();
        return a.getWindowManager().getDefaultDisplay();
    }
}
