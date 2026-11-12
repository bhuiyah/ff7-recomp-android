package com.ff7recomp;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

/**
 * Settings > On-screen controls > Size and layout (GitHub #28): the touch controls full screen,
 * as they sit over the game. Drag the D-pad, the face buttons or a shoulder group to move it; the
 * sliders set the size and opacity. Saved to the launcher's "touch_layout" setting, which
 * GameActivity gets at Play (TouchControls.setLayout).
 */
public class TouchLayoutActivity extends Activity {
    private TouchControls controls;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("launcher", MODE_PRIVATE);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF10141C);

        controls = new TouchControls(this, (b, lt, rt) -> { });
        controls.setLayout(prefs.getString("touch_layout", ""));
        controls.setEditing(true, this::save);
        root.addView(controls, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // the panel: size, opacity, reset, done (in the middle, clear of the controls)
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(12), dp(16), dp(12));
        panel.setBackgroundColor(0xE0202A3A);
        TextView hint = new TextView(this);
        hint.setText("Drag the D-pad, the face buttons or a shoulder group to move it. "
                + "Drag this panel aside if it is in the way.");
        hint.setTextColor(0xB0FFFFFF);
        hint.setTextSize(14);
        panel.addView(hint);
        panel.addView(slider("Size", 50, 200, Math.round(controls.scale() * 100), v -> controls.setScale(v / 100f)));
        panel.addView(slider("Opacity", 15, 100, Math.round(controls.getAlpha() * 100), v -> controls.setAlpha(v / 100f)));
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER);
        Button reset = new Button(this);
        reset.setText("Reset");
        reset.setOnClickListener(v -> {
            prefs.edit().remove("touch_layout").apply();
            recreate();
        });
        Button done = new Button(this);
        done.setText("Done");
        done.setOnClickListener(v -> { save(); finish(); });
        row.addView(reset);
        row.addView(done);
        panel.addView(row);
        FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(dp(360), ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);
        pp.topMargin = dp(40);
        root.addView(panel, pp);
        // the panel moves with a finger on its background (the controls under it stay reachable)
        final float[] last = new float[2];
        panel.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) { last[0] = e.getRawX(); last[1] = e.getRawY(); return true; }
            if (e.getActionMasked() == android.view.MotionEvent.ACTION_MOVE) {
                v.setTranslationX(v.getTranslationX() + e.getRawX() - last[0]);
                v.setTranslationY(v.getTranslationY() + e.getRawY() - last[1]);
                last[0] = e.getRawX(); last[1] = e.getRawY();
            }
            return true;
        });

        setContentView(root);
        getWindow().getInsetsController().hide(WindowInsets.Type.systemBars());
    }

    interface IntListener { void set(int v); }

    private LinearLayout slider(String name, int min, int max, int value, IntListener l) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = new TextView(this);
        label.setTextColor(Color.WHITE);
        label.setTextSize(15);
        label.setMinWidth(dp(110));
        SeekBar bar = new SeekBar(this);
        bar.setMax(max - min);
        bar.setProgress(Math.max(0, Math.min(max - min, value - min)));
        label.setText(name + " " + (bar.getProgress() + min) + "%");
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                label.setText(name + " " + (p + min) + "%");
                if (user) l.set(p + min);
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { save(); }
        });
        row.addView(label);
        row.addView(bar, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    private void save() { prefs.edit().putString("touch_layout", controls.layout()).apply(); }

    @Override
    protected void onPause() { save(); super.onPause(); }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
