package com.ff7recomp;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

/**
 * Android's install confirmation for Pixel Guide (GuideInstaller), shown on the bottom screen.
 * The companion is a Presentation, which Android draws above activities on that screen, so it
 * steps aside while the confirmation is up. This invisible activity sits under it: once the
 * confirmation is gone it is in front again, puts the panels back and finishes.
 *
 * Pixel Guide itself is opened straight from the panels (CompanionView.openGuide), with nothing
 * of ours left on that screen: the Thor's firmware gives an app with an activity on the bottom
 * screen that screen's volume ("secondary screen volume", 0 out of the box), and an activity
 * kept under the guide muted the game for as long as the guide stayed open. This one runs in a
 * process of its own (":guide") and tells the game's process through Events.
 */
public class GuideHostActivity extends Activity {
    private static final String TAG = "ff7guide";
    private boolean launched, paused;

    /** Extra: the installer confirmation (GuideInstaller) to show here. */
    static final String EXTRA_CONFIRM = "confirm";

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        Intent confirm = getIntent().getParcelableExtra(EXTRA_CONFIRM);
        if (confirm == null) { done(); return; }
        try {                         // however the install ends, the panels come back
            tell(EVENT_ASIDE);
            startActivity(confirm);
            launched = true;
        } catch (RuntimeException e) {
            Log.w(TAG, "install confirmation could not open: " + e);
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
        if (launched && paused) done();   // the confirmation was in front and is gone again
    }

    private void done() {
        tell(EVENT_INSTALL_ENDED);
        finish();
        overridePendingTransition(0, 0);
    }

    private static final String EVENT = "event";
    private static final int EVENT_ASIDE = 1, EVENT_INSTALL_ENDED = 2;

    private void tell(int event) {
        sendBroadcast(new Intent(this, Events.class).putExtra(EVENT, event));
    }

    /** In the game's process: the confirmation's comings and goings, for the panels. */
    public static class Events extends android.content.BroadcastReceiver {
        @Override
        public void onReceive(android.content.Context ctx, Intent intent) {
            switch (intent.getIntExtra(EVENT, 0)) {
                case EVENT_ASIDE: GameActivity.panelsAside(); break;
                case EVENT_INSTALL_ENDED:
                    GuideInstaller.busy = false;           // the book can try again
                    GameActivity.guideClosed();
                    break;
                default: break;
            }
        }
    }
}
