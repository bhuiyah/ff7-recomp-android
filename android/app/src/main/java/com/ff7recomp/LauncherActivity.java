package com.ff7recomp;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;

/**
 * The app's entry point (7th Heaven's role): game files, mods (order, on/off, options),
 * settings, Play. Driven by the Thor's pad (D-pad moves focus, A confirms, B goes back,
 * Start plays) or by touch. The game runs in its own process (GameActivity, ":game"), so
 * quitting it lands back here and Play can start a fresh one.
 */
public class LauncherActivity extends Activity {
    private static final int PICK_GAME = 1, PICK_IRO = 2, PICK_FOLDER = 3;
    private static final int BG = 0xFF0B1020, PANEL = 0xFF141B33, ACCENT = 0xFF3D7BFF, TEXT = 0xFFE8ECF8, DIM = 0xFF8C96B4;

    private GameInstall install;
    private ModLibrary library;
    private SharedPreferences prefs;
    private FrameLayout content;
    private TextView status;
    private Button playButton;
    private String page = "mods";
    private boolean busy;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        install = new GameInstall(this);
        library = new ModLibrary(install.files);
        prefs = getSharedPreferences("launcher", MODE_PRIVATE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(24), dp(24), dp(24), dp(24));

        LinearLayout side = new LinearLayout(this);
        side.setOrientation(LinearLayout.VERTICAL);
        side.setPadding(0, 0, dp(24), 0);
        TextView title = text("FINAL FANTASY VII", 30, TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        side.addView(title);
        side.addView(text("native ARM64 · FFNx · Vulkan", 14, DIM));
        status = text("", 15, DIM);
        status.setPadding(0, dp(16), 0, dp(16));
        side.addView(status);
        playButton = button("▶  Play", v -> play());
        playButton.setTextSize(24);
        side.addView(playButton, new LinearLayout.LayoutParams(dp(280), dp(72)));
        side.addView(space(16));
        side.addView(button("Mods", v -> show("mods")), navParams());
        side.addView(button("Settings", v -> show("settings")), navParams());
        side.addView(button("Game files", v -> show("game")), navParams());
        root.addView(side, new LinearLayout.LayoutParams(dp(300), ViewGroup.LayoutParams.MATCH_PARENT));

        content = new FrameLayout(this);
        content.setBackground(rounded(PANEL));
        content.setPadding(dp(20), dp(16), dp(20), dp(16));
        root.addView(content, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        setContentView(root);
        hideSystemBars();
        show(install.hasGame() ? "mods" : "game");
        playButton.requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemBars();
        refresh();
    }

    // ------------------------------------------------------------------- pages
    private void show(String which) {
        page = which;
        refresh();
    }

    private void refresh() {
        library.load();
        status.setText((install.hasGame() ? "Game files: ready" : "Game files: not imported yet")
                + "\nMods: " + library.enabledCount() + " of " + library.mods.size() + " enabled"
                + "\nFrame rate: " + fpsName(prefs.getInt("fps", 3)));
        playButton.setEnabled(install.hasGame() && !busy);
        content.removeAllViews();
        switch (page) {
            case "settings": content.addView(settingsPage()); break;
            case "game": content.addView(gamePage()); break;
            default: content.addView(modsPage()); break;
        }
    }

    private View modsPage() {
        LinearLayout col = column();
        col.addView(heading("Mods"));
        col.addView(text("Higher in the list wins when two mods change the same file. Mods are 7th Heaven archives (.iro) or mod folders.", 14, DIM));
        LinearLayout actions = row();
        actions.addView(button("Add .iro archive", v -> pick(PICK_IRO)));
        actions.addView(button("Add mod folder", v -> pick(PICK_FOLDER)));
        col.addView(actions);
        if (library.mods.isEmpty()) col.addView(text("\nNo mods yet.", 16, DIM));
        for (int i = 0; i < library.mods.size(); i++) col.addView(modRow(i));
        return scroll(col);
    }

    private View modRow(int index) {
        ModLibrary.Mod m = library.mods.get(index);
        LinearLayout r = row();
        r.setPadding(0, dp(8), 0, dp(8));
        CheckBox cb = new CheckBox(this);
        cb.setTextColor(TEXT);
        cb.setTextSize(18);
        cb.setText(m.title() + (m.version != null && !m.version.isEmpty() ? "  " + m.version : ""));
        cb.setChecked(m.enabled && m.error == null);
        cb.setEnabled(m.error == null);
        cb.setOnCheckedChangeListener((b, on) -> { m.enabled = on; saveProfile(); });
        LinearLayout info = column();
        info.addView(cb);
        StringBuilder sub = new StringBuilder();
        if (m.error != null) sub.append("Cannot read: ").append(m.error);
        else {
            if (m.author != null && !m.author.isEmpty()) sub.append("by ").append(m.author).append("  ·  ");
            sub.append(m.file);
            for (String u : m.unsupported) sub.append("\n⚠ ").append(u).append(" (Windows code, skipped)");
        }
        info.addView(text(sub.toString(), 13, m.error != null ? 0xFFFF8080 : DIM));
        if (m.error == null && m.hext != null)
            info.addView(text((m.hextProblem ? "⚠ " : "") + m.hext, 13, m.hextProblem ? 0xFFFFC060 : DIM));
        r.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        r.addView(small("▲", v -> { library.move(index, -1); saveProfile(); refresh(); }));
        r.addView(small("▼", v -> { library.move(index, +1); saveProfile(); refresh(); }));
        if (!m.options.isEmpty()) r.addView(small("Options", v -> options(m)));
        return r;
    }

    private void options(ModLibrary.Mod m) {
        LinearLayout col = column();
        col.setPadding(dp(24), dp(8), dp(24), dp(8));
        for (ModLibrary.Option o : m.options) {
            col.addView(text(o.name, 17, TEXT));
            if (o.description != null && !o.description.isEmpty()) col.addView(text(o.description, 13, DIM));
            if (o.valueIds.isEmpty() || "Bool".equalsIgnoreCase(o.type)) {
                CheckBox cb = new CheckBox(this);
                cb.setText("On");
                cb.setTextColor(TEXT);
                cb.setChecked(m.value(o) != 0);
                cb.setOnCheckedChangeListener((b, on) -> m.chosen.put(o.id.toLowerCase(java.util.Locale.ROOT), on ? 1 : 0));
                col.addView(cb);
            } else {
                RadioGroup g = new RadioGroup(this);
                for (int k = 0; k < o.valueIds.size(); k++) {
                    RadioButton rb = new RadioButton(this);
                    rb.setText(o.valueNames.get(k));
                    rb.setTextColor(TEXT);
                    rb.setId(View.generateViewId());
                    final int val = o.valueIds.get(k);
                    rb.setOnClickListener(v -> m.chosen.put(o.id.toLowerCase(java.util.Locale.ROOT), val));
                    g.addView(rb);
                    if (val == m.value(o)) rb.setChecked(true);
                }
                col.addView(g);
            }
            col.addView(space(12));
        }
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(m.title())
                .setView(scroll(col))
                .setPositiveButton("Done", (d, w) -> { saveProfile(); refresh(); })
                .show();
    }

    private View settingsPage() {
        LinearLayout col = column();
        col.addView(heading("Frame rate"));
        RadioGroup g = new RadioGroup(this);
        int cur = prefs.getInt("fps", 3);
        for (int mode = 0; mode <= 4; mode++) {
            RadioButton rb = new RadioButton(this);
            rb.setId(View.generateViewId());
            rb.setText(fpsName(mode) + "\n" + fpsDetail(mode));
            rb.setTextColor(TEXT);
            rb.setTextSize(16);
            final int m = mode;
            rb.setOnClickListener(v -> { prefs.edit().putInt("fps", m).apply(); refresh(); });
            g.addView(rb);
            if (mode == cur) rb.setChecked(true);
        }
        col.addView(g);
        col.addView(space(16));
        col.addView(heading("Display"));
        RadioGroup ar = new RadioGroup(this);
        String[][] aspects = {
            { "0", "4:3, original", "Black bars at the sides of a 16:9 screen" },
            { "2", "16:9 widescreen", "3D scenes and menus use the whole screen; fields without widescreen backgrounds keep side bars" },
        };
        int curAr = prefs.getInt("aspect", 2);
        for (String[] a : aspects) {
            RadioButton rb = new RadioButton(this);
            rb.setId(View.generateViewId());
            rb.setText(a[1] + "\n" + a[2]);
            rb.setTextColor(TEXT);
            rb.setTextSize(16);
            final int v = Integer.parseInt(a[0]);
            rb.setOnClickListener(x -> prefs.edit().putInt("aspect", v).apply());
            ar.addView(rb);
            if (v == curAr) rb.setChecked(true);
        }
        col.addView(ar);
        CheckBox fps = new CheckBox(this);
        fps.setText("Show the frame-rate counter");
        fps.setTextColor(TEXT);
        fps.setTextSize(16);
        fps.setChecked(prefs.getBoolean("show_fps", false));
        fps.setOnCheckedChangeListener((b, on) -> prefs.edit().putBoolean("show_fps", on).apply());
        col.addView(fps);
        col.addView(space(16));
        col.addView(heading("Start"));
        RadioGroup st = new RadioGroup(this);
        String[][] starts = {
            { "", "Normal", "Credits, title screen and opening movie, as the game plays them" },
            { "newgame", "Straight into a new game", "Skips the credits, picks New Game and skips the opening movie" },
            { "continue", "Continue straight away", "Skips the credits and loads the first save of the first save file" },
            { "resume", "Resume the save state", "Loads the quick save state (Select+L1 saves it in game) as soon as the game starts" },
        };
        String curStart = prefs.getString("quickboot", "");
        for (String[] s : starts) {
            RadioButton rb = new RadioButton(this);
            rb.setId(View.generateViewId());
            rb.setText(s[1] + "\n" + s[2]);
            rb.setTextColor(TEXT);
            rb.setTextSize(16);
            final String v = s[0];
            rb.setOnClickListener(x -> prefs.edit().putString("quickboot", v).apply());
            st.addView(rb);
            if (v.equals(curStart)) rb.setChecked(true);
        }
        col.addView(st);
        col.addView(text("Any button stops the automatic start.", 14, DIM));
        col.addView(space(16));
        col.addView(text("In game: press Start during a movie to skip it. Select+L1 saves the game's whole state, "
                + "Select+R1 loads it back.", 14, DIM));
        return scroll(col);
    }

    private static String fpsName(int mode) {
        switch (mode) {
            case 0: return "Original";
            case 1: return "Original, fixed";
            case 2: return "30 FPS battles";
            case 4: return "120 FPS (experimental)";
            default: return "60 FPS";
        }
    }

    private static String fpsDetail(int mode) {
        switch (mode) {
            case 0: return "The game's own limiter, as shipped";
            case 1: return "Original speeds with FFNx's limiter fixes";
            case 2: return "Battles at 30 instead of 15, the rest original";
            case 4: return "Field and world map at 120; battles and menus at 60";
            default: return "The whole game at 60 (FFNx's 60 FPS mode)";
        }
    }

    private View gamePage() {
        LinearLayout col = column();
        col.addView(heading("Game files"));
        col.addView(text(install.hasGame()
                ? "FINAL FANTASY VII is imported. Import again to replace it."
                : "Import FINAL FANTASY VII from your copy of the Steam release: copy its install folder "
                  + "(\"FINAL FANTASY VII\", containing ff7) to this device, then pick that folder.", 15, TEXT));
        col.addView(space(12));
        col.addView(button("Import from FF7 install folder…", v -> pick(PICK_GAME)));
        col.addView(space(12));
        col.addView(text("Stored in " + install.game.getAbsolutePath(), 13, DIM));
        return scroll(col);
    }

    // ------------------------------------------------------------------- actions
    private void pick(int what) {
        if (busy) return;
        Intent i;
        if (what == PICK_IRO) {
            i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        } else {
            i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        }
        startActivityForResult(i, what);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null) return;
        background(() -> {
            if (req == PICK_GAME) {
                String err = install.importGame(data.getData(), this::progress);
                return err == null ? "Game files imported." : "Not imported: " + err;
            }
            if (req == PICK_IRO) {
                int n = 0;
                if (data.getClipData() != null) {
                    for (int k = 0; k < data.getClipData().getItemCount(); k++) {
                        progress("Copying mod " + (k + 1));
                        install.importFile(data.getClipData().getItemAt(k).getUri(), library.dir);
                        n++;
                    }
                } else if (data.getData() != null) {
                    progress("Copying mod");
                    install.importFile(data.getData(), library.dir);
                    n = 1;
                }
                return n + " mod archive(s) added. Enable them in the list.";
            }
            File f = install.importFolder(data.getData(), library.dir, this::progress);
            return "Mod folder " + f.getName() + " added. Enable it in the list.";
        });
    }

    private void play() {
        if (busy || !install.hasGame()) return;
        background(() -> {
            progress("Preparing");
            library.load();
            library.save();
            install.installRuntime();
            install.writeFfnxToml(prefs.getInt("fps", 3), prefs.getInt("aspect", 2), prefs.getBoolean("show_fps", false), library.mods);
            install.writeQuickboot(prefs.getString("quickboot", ""));
            runOnUiThread(() -> startActivity(new Intent(this, GameActivity.class)));
            return null;
        });
    }

    private void saveProfile() {
        try { library.save(); } catch (Exception e) { toastDialog("Could not save the mod list: " + e); }
    }

    // ------------------------------------------------------------------- plumbing
    private interface Job { String run() throws Exception; }

    private void background(Job job) {
        busy = true;
        playButton.setEnabled(false);
        new Thread(() -> {
            String msg;
            try { msg = job.run(); } catch (Exception e) { msg = "Failed: " + e.getMessage(); }
            final String m = msg;
            runOnUiThread(() -> {
                busy = false;
                refresh();
                if (m != null) toastDialog(m);
            });
        }).start();
    }

    private void progress(String what) {
        runOnUiThread(() -> status.setText(what + "…"));
    }

    private void toastDialog(String msg) {
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setMessage(msg).setPositiveButton("OK", null).show();
    }

    /** The pad: A confirms, B goes back, Start plays (D-pad focus navigation is Android's). */
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        if (code == KeyEvent.KEYCODE_BUTTON_A)
            return super.dispatchKeyEvent(new KeyEvent(e.getDownTime(), e.getEventTime(), e.getAction(), KeyEvent.KEYCODE_DPAD_CENTER, e.getRepeatCount()));
        if (code == KeyEvent.KEYCODE_BUTTON_B)
            return super.dispatchKeyEvent(new KeyEvent(e.getDownTime(), e.getEventTime(), e.getAction(), KeyEvent.KEYCODE_BACK, e.getRepeatCount()));
        if (code == KeyEvent.KEYCODE_BUTTON_START) {
            if (e.getAction() == KeyEvent.ACTION_UP) play();
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    private void hideSystemBars() {
        WindowInsetsController c = getWindow().getInsetsController();
        if (c != null) {
            c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
            c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    private TextView heading(String s) {
        TextView t = text(s, 22, TEXT);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, 0, 0, dp(8));
        return t;
    }

    private Button button(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(17);
        b.setTextColor(Color.WHITE);
        GradientDrawable normal = new GradientDrawable();
        normal.setColor(0xFF24304F);
        normal.setCornerRadius(dp(10));
        GradientDrawable focused = new GradientDrawable();
        focused.setColor(ACCENT);
        focused.setCornerRadius(dp(10));
        android.graphics.drawable.StateListDrawable sl = new android.graphics.drawable.StateListDrawable();
        sl.addState(new int[] { android.R.attr.state_focused }, focused);
        sl.addState(new int[] { android.R.attr.state_pressed }, focused);
        sl.addState(new int[] {}, normal);
        b.setBackground(sl);
        b.setFocusable(true);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(4), dp(8), dp(4));
        b.setLayoutParams(lp);
        return b;
    }

    private Button small(String label, View.OnClickListener l) {
        Button b = button(label, l);
        b.setTextSize(15);
        return b;
    }

    private LinearLayout.LayoutParams navParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(280), dp(56));
        lp.setMargins(0, dp(4), 0, dp(4));
        return lp;
    }

    private LinearLayout column() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private LinearLayout row() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    private View space(int h) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(h)));
        return v;
    }

    private ScrollView scroll(View child) {
        ScrollView s = new ScrollView(this);
        s.addView(child);
        return s;
    }

    private GradientDrawable rounded(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(16));
        return d;
    }
}
