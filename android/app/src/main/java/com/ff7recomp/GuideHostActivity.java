package com.ff7recomp;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

/**
 * Opens Pixel Guide on the bottom screen (the companion's book) and notices when it closes.
 * The companion is a Presentation, which Android draws above activities on that screen, so it
 * is hidden while the guide is up. This invisible activity sits under the guide on the bottom
 * screen: once the guide is closed it is in front again, puts the panels back and finishes.
 */
public class GuideHostActivity extends Activity {
    private static final String TAG = "ff7guide";
    private boolean launched, paused;

    /** Extra: an installer confirmation (GuideInstaller) to show here instead of the guide. */
    static final String EXTRA_CONFIRM = "confirm";

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        Intent confirm = getIntent().getParcelableExtra(EXTRA_CONFIRM);
        if (confirm != null) {        // Pixel Guide's install: however it ends, the panels come back
            try {
                GameActivity.panelsAside();
                startActivity(confirm);
                launched = true;
            } catch (RuntimeException e) {
                Log.w(TAG, "install confirmation could not open: " + e);
                done();
            }
            return;
        }
        Intent guide = getPackageManager().getLaunchIntentForPackage(CompanionView.GUIDE_PACKAGE);
        if (guide == null) { done(); return; }
        try {
            guide.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            android.app.ActivityOptions o = android.app.ActivityOptions.makeBasic();
            if (getDisplay() != null) o.setLaunchDisplayId(getDisplay().getDisplayId());
            startActivity(guide, o.toBundle());
            launched = true;
            GameActivity.guideOpened();
        } catch (RuntimeException e) {
            Log.w(TAG, "Pixel Guide could not open: " + e);
            done();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        paused = true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (launched && paused) done();   // the guide was in front and is gone again
    }

    private void done() {
        if (getIntent().hasExtra(EXTRA_CONFIRM)) GuideInstaller.busy = false;   // the book can try again
        GameActivity.guideClosed();
        finish();
        overridePendingTransition(0, 0);
    }
}
