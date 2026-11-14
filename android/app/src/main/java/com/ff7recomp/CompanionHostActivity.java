package com.ff7recomp;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.WindowManager;

/**
 * Swap screens (Settings > Screen, GitHub #41): the second-screen panels on the main display
 * while the game runs on the presentation display (devices such as the Ayaneo Pocket DS, whose
 * main screen is the small one). Android only shows a Presentation on a presentation display,
 * so here the panels are an activity's content (GameActivity.attachCompanionHost). Its window
 * never takes key focus, and any key or stick event that reaches it goes to the game.
 */
public class CompanionHostActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        GameActivity.attachCompanionHost(this);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        GameActivity g = GameActivity.currentGame();
        return g != null ? g.dispatchKeyEvent(e) : super.dispatchKeyEvent(e);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent e) {
        GameActivity g = GameActivity.currentGame();
        return g != null ? g.dispatchGenericMotionEvent(e) : super.dispatchGenericMotionEvent(e);
    }

    /** Back never closes the panels (the key goes to the game, above). */
    @Override
    public void onBackPressed() { }

    @Override
    protected void onDestroy() {
        GameActivity.detachCompanionHost(this);
        super.onDestroy();
    }
}
