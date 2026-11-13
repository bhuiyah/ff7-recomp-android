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
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
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
    private static final int PICK_GAME = 1, PICK_IRO = 2, PICK_FOLDER = 3, PICK_PSX = 4;
    // FF7-style menus: blue gradient windows with a light double border, white text with a drop
    // shadow, cyan labels, a pointing-glove cursor (HandCursor.java) on the menu.
    private static final int BG = 0xFF03050C, ACCENT = 0xFF7FD8FF, TEXT = 0xFFF4F6FF, DIM = 0xFFB4C0E4;
    private static final int WIN_TOP = 0xFF3B5CB4, WIN_BOTTOM = 0xFF0E1C64;          // windows
    private static final int BTN_TOP = 0xFF2C4594, BTN_BOTTOM = 0xFF101E5E;          // buttons
    private static final int HOT_TOP = 0xFF6286E6, HOT_BOTTOM = 0xFF2A43A0;          // focused / on
    private final java.util.Map<String, Button> nav = new java.util.HashMap<>();

    private GameInstall install;
    private ModLibrary library;
    private SaveStates states;
    private ModCatalog catalog;
    private ModDownloader downloader;
    private DownloadQueue queue;
    // one catalog / downloader / queue per process: downloads outlive a recreated activity
    private static ModCatalog sCatalog;
    private static ModDownloader sDownloader;
    private static DownloadQueue sQueue;
    private Button downloadsButton;
    private Button settingsButton;                 // shows a dot when an FF7Recomp update is out
    private String queueSig = "";                            // ids+states the page was built with
    private final java.util.Map<String, LinearLayout> actionSlots = new java.util.HashMap<>();   // Get mods rows
    private final java.util.Map<DownloadQueue.Item, Object[]> progressViews = new java.util.HashMap<>(); // Downloads page
    private String catQuery = "", catCategory = null;      // the Get mods page's filters
    private boolean catInstalledOnly;
    private final java.util.Map<String, android.graphics.Bitmap> previews = new java.util.HashMap<>();
    private SharedPreferences prefs;
    private FrameLayout content;
    private TextView status;
    private Button playButton;
    private String page = "mods";
    private boolean busy;
    private GameBuild build;

    // ---- FF7's menu sounds (Sfx.java, from the player's game files)
    private Sfx sfx;
    private long lastNavKey;
    /** On every click of a view theme() passed: the game's confirm sound. Android calls a view's
     *  accessibility delegate on each performClick, with accessibility on or off. */
    private final View.AccessibilityDelegate clickSound = new View.AccessibilityDelegate() {
        @Override public void sendAccessibilityEvent(View host, int type) {
            if (type == android.view.accessibility.AccessibilityEvent.TYPE_VIEW_CLICKED) sound(Sfx.OK);
            super.sendAccessibilityEvent(host, type);
        }
    };

    // ---- updates (Updater.java)
    private TextView updateLine;                   // Settings page: the updater's status
    private String updateShown = "";

    /** Check GitHub for a newer release: when the launcher opens (every 6 hours at most) or on
     *  "Check now". A newer one is offered once per version; Settings keeps the button. */
    private void checkUpdates(boolean asked) {
        if (Updater.busy) return;
        if (!asked && System.currentTimeMillis() - prefs.getLong("update_checked", 0) < 6 * 3600_000L) {
            if (Updater.available != null) offerUpdate(false);
            return;
        }
        Updater.busy = true;
        Updater.status = "Checking for updates";
        new Thread(() -> {
            String msg;
            try {
                Updater.Release r = Updater.check(this);
                prefs.edit().putLong("update_checked", System.currentTimeMillis()).apply();
                msg = r != null ? "Version " + r.version + " is available" : "You have the latest version";
            } catch (Exception e) {
                msg = "Could not check for updates: " + e.getMessage();
            }
            final String m = msg;
            Updater.status = m;
            Updater.busy = false;
            runOnUiThread(() -> {
                updateStatus();
                if (updateLine != null) updateLine.setText(m);
                if (Updater.available != null) offerUpdate(asked);
                else if (asked) refresh();
            });
        }, "update-check").start();
    }

    private void offerUpdate(boolean asked) {
        Updater.Release r = Updater.available;
        if (r == null || isFinishing()) return;
        if (!asked && (r.version.equals(prefs.getString("update_skipped", "")) || r.version.equals(updateShown))) return;
        updateShown = r.version;
        String notes = r.notes.replaceAll("(?m)^#+\\s*", "").replaceAll("\\*\\*", "").trim();
        if (notes.length() > 900) notes = notes.substring(0, 900) + "…";
        ff(new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Update available: " + r.version)
                .setMessage("You have " + Updater.installedVersion(this) + ". Your game files, mods and saves stay.\n\n" + notes)
                .setPositiveButton("Update now", (d, w) -> installUpdate())
                .setNegativeButton("Later", null)
                .setNeutralButton("Skip this version", (d, w) -> prefs.edit().putString("update_skipped", r.version).apply())
                .show());
    }

    private void installUpdate() {
        Updater.Release r = Updater.available;
        if (r == null || Updater.busy) return;
        if (GameBuild.running) { toastDialog("Not now: the game is being built. Update when it is done."); return; }
        Updater.busy = true;
        new Thread(() -> {
            try {
                Updater.install(this, r);           // the system then asks to confirm
            } catch (Exception e) {
                Updater.busy = false;
                Updater.percent = -1;
                Updater.status = "Update failed: " + e.getMessage();
                runOnUiThread(() -> toastDialog(Updater.status));
            }
        }, "update").start();
        show("settings");
    }

    private void sound(int id) {
        if (sfx != null) sfx.play(id);
    }

    /** The cursor sound when the D-pad moves focus (not when a page rebuild moves it). */
    private void cursorOnFocus(View decor) {
        decor.getViewTreeObserver().addOnGlobalFocusChangeListener((was, now) -> {
            if (was != null && now != null && was != now && System.currentTimeMillis() - lastNavKey < 400) sound(Sfx.CURSOR);
        });
    }

    private void loadSounds() {
        if (sfx != null && sfx.isReady() && sfx.profileStamp == library.profile.lastModified()) {
            sfx.enabled = prefs.getBoolean("menu_sounds", true);
            return;
        }
        if (sfx != null) sfx.release();
        sfx = new Sfx(this, install.game, library.profile);   // the mods' sounds win, as in the game
        sfx.enabled = prefs.getBoolean("menu_sounds", true);
    }
    private android.widget.ProgressBar buildBar;   // Game files page, while building
    private TextView buildLine;
    private final android.os.Handler ticker = new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean wasBuilding;
    private final Runnable buildTick = new Runnable() {
        @Override public void run() {
            if (updateLine != null && !updateLine.getText().toString().equals(updateText())) updateLine.setText(updateText());
            if (GameBuild.running) {
                if (buildBar != null) { buildBar.setIndeterminate(GameBuild.percent <= 0); buildBar.setProgress(GameBuild.percent); }
                if (buildLine != null) buildLine.setText(GameBuild.stage + " · " + GameBuild.percent + "%");
                updateStatus();
                wasBuilding = true;
            } else if (wasBuilding) {
                wasBuilding = false;
                refresh();
            }
            ticker.postDelayed(this, 500);
        }
    };

    private String updateText() {
        return Updater.percent >= 0 ? Updater.status + " · " + Updater.percent + "%" : Updater.status;
    }

    /** The game can start: files imported and libff7.so built for this device. */
    private boolean ready() {
        return install.hasGame() && build.built() && !GameBuild.running;
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        install = new GameInstall(this);
        build = new GameBuild(this);
        library = new ModLibrary(install.files);
        if (sQueue == null) {
            final File files = install.files;
            sCatalog = new ModCatalog(files);
            sCatalog.rebuild();
            sDownloader = new ModDownloader(files, library.dir);
            sQueue = new DownloadQueue(sCatalog, sDownloader, new File(files, "downloads"), (m, file, wasUpdate) -> {
                // a new mod is switched on (the library lists new files last, off); an update
                // keeps its place, state and options
                if (wasUpdate) return;
                ModLibrary l = new ModLibrary(files);
                l.load();
                for (ModLibrary.Mod lm : l.mods) if (lm.file.equalsIgnoreCase(file)) lm.enabled = true;
                try { l.save(); } catch (Exception ignored) { }
            });
            sQueue.restore();
        }
        catalog = sCatalog;
        downloader = sDownloader;
        queue = sQueue;
        prefs = getSharedPreferences("launcher", MODE_PRIVATE);
        if (Storage.sdMissing(this))
            new android.os.Handler(getMainLooper()).post(() -> toastDialog("Your game files are on the SD card, which is "
                    + "not inserted. Insert it and open FF7Recomp again (Settings > Storage moves them back to internal "
                    + "storage)."));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(24), dp(24), dp(24), dp(24));

        LinearLayout side = new LinearLayout(this);
        side.setOrientation(LinearLayout.VERTICAL);
        side.setPadding(0, 0, dp(14), 0);
        TextView title = text("FINAL FANTASY VII", 17, TEXT);
        title.setTypeface(Typeface.create("serif", Typeface.BOLD));
        title.setLetterSpacing(0.04f);
        title.setSingleLine(true);
        side.addView(title);
        side.addView(text("native ARM64 · FFNx · Vulkan", 11, ACCENT));
        side.addView(space(10));
        // the command window, Play first (the cursor starts there)
        LinearLayout menu = column();
        menu.setBackground(window(WIN_TOP, WIN_BOTTOM, 8));
        menu.setPadding(dp(8), dp(6), dp(8), dp(6));
        playButton = menuItem("Play", null);
        playButton.setTextSize(19);
        playButton.setTypeface(Typeface.DEFAULT_BOLD);
        menu.addView(playButton);
        menu.addView(menuItem("Mods", "mods"));
        menu.addView(menuItem("Get mods", "catalog"));
        downloadsButton = menuItem("Downloads", "downloads");
        menu.addView(downloadsButton);
        menu.addView(menuItem("Save states", "states"));
        menu.addView(menuItem("Achievements", "achievements"));
        settingsButton = menuItem("Settings", "settings");
        menu.addView(settingsButton);
        menu.addView(menuItem("Game files", "game"));
        side.addView(menu, new LinearLayout.LayoutParams(dp(200), ViewGroup.LayoutParams.WRAP_CONTENT));
        // the menu scrolls when it is taller than the screen
        ScrollView sideScroll = new ScrollView(this);
        sideScroll.setVerticalScrollBarEnabled(false);
        sideScroll.addView(side);
        root.addView(sideScroll, new LinearLayout.LayoutParams(dp(214), ViewGroup.LayoutParams.MATCH_PARENT));

        // right: the info bar (FF7's one-line help window) over the page's window
        LinearLayout right = column();
        status = text("", 14, TEXT);
        status.setBackground(window(WIN_TOP, WIN_BOTTOM, 8));
        status.setPadding(dp(16), dp(8), dp(16), dp(8));
        status.setMaxLines(2);
        status.setEllipsize(android.text.TextUtils.TruncateAt.END);
        right.addView(status, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        right.addView(space(10));
        content = new FrameLayout(this);
        content.setBackground(window(WIN_TOP, WIN_BOTTOM, 10));
        content.setPadding(dp(22), dp(18), dp(22), dp(18));
        right.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        root.addView(right, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        setContentView(root);
        theme(root);
        cursorOnFocus(getWindow().getDecorView());
        hideSystemBars();
        show(ready() ? "mods" : "game");
        // catalogs older than a day are fetched quietly in the background (as 7th Heaven does)
        new Thread(() -> { catalog.refresh(false); runOnUiThread(this::refresh); }).start();
        playButton.requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemBars();
        queue.setListener(this::queueChanged);
        loadSounds();
        refresh();
        checkUpdates(false);
        ticker.removeCallbacks(buildTick);
        ticker.post(buildTick);
        testPlay(getIntent(), 0);
        Intent ti = getIntent();
        if (ti != null && ti.getBooleanExtra(TestControl.EXTRA_STEAM_QR, false)) {
            ti.removeExtra(TestControl.EXTRA_STEAM_QR);
            steamSignIn();   // layout check; Cancel leaves the current sign-in untouched
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
    }

    /** TestControl's PLAY (adb): press Play once the launcher is ready, with its Start override. */
    private void testPlay(Intent i, int tries) {
        if (i == null || !i.getBooleanExtra(TestControl.EXTRA_PLAY, false)) return;
        if (busy || !install.hasGame() || !ready()) {
            if (tries < 40) ticker.postDelayed(() -> testPlay(i, tries + 1), 250);
            return;
        }
        i.removeExtra(TestControl.EXTRA_PLAY);
        String start = i.getStringExtra(TestControl.EXTRA_START);
        play(start == null ? null : start.equals("normal") ? "" : start);
    }

    @Override
    protected void onPause() {
        super.onPause();
        queue.setListener(null);
        ticker.removeCallbacks(buildTick);
    }

    // ------------------------------------------------------------------- pages
    private void show(String which) {
        page = which;
        refresh();
    }

    private void refresh() {
        library.load();
        catalog.prune(library.dir);
        updateStatus();
        playButton.setEnabled(ready() && !busy);
        content.removeAllViews();
        actionSlots.clear();
        progressViews.clear();
        updateLine = null;
        queueSig = queueSignature();
        // the same page again (a mod switched on or off, a setting changed): keep its scroll
        // position, not back to the top (GitHub #32)
        final int keepY = page.equals(shownPage) ? scrollY : 0;
        shownPage = page;
        switch (page) {
            case "downloads": content.addView(downloadsPage()); break;
            case "settings": content.addView(settingsPage()); break;
            case "states": content.addView(statesPage()); break;
            case "achievements": content.addView(achievementsPage()); break;
            case "game": content.addView(gamePage()); break;
            case "catalog": content.addView(catalogPage()); break;
            default: content.addView(modsPage()); break;
        }
        for (java.util.Map.Entry<String, Button> e : nav.entrySet()) e.getValue().setSelected(e.getKey().equals(page));
        theme(content);
        scrollY = keepY;
        if (content.getChildCount() > 0 && content.getChildAt(0) instanceof ScrollView) {
            ScrollView sv = (ScrollView) content.getChildAt(0);
            sv.setOnScrollChangeListener((v, x, y, ox, oy) -> scrollY = y);
            if (keepY > 0) sv.post(() -> sv.scrollTo(0, keepY));
        }
    }

    private String shownPage;
    private int scrollY;

    private View modsPage() {
        LinearLayout col = column();
        col.addView(heading("Mods"));
        col.addView(text("Higher in the list wins when two mods change the same file. Mods are 7th Heaven archives (.iro) or mod folders.", 14, DIM));
        LinearLayout actions = row();
        actions.addView(button("Add .iro", v -> pick(PICK_IRO)));
        actions.addView(button("Add folder", v -> pick(PICK_FOLDER)));
        actions.addView(button("Get mods…", v -> show("catalog")));
        java.util.List<ModCatalog.Mod> ups = catalog.updates();
        if (!ups.isEmpty()) actions.addView(button("Update all (" + ups.size() + ")", v -> installMods(ups, true)));
        // 7th Heaven's Auto-sort: category order, then the mods' own load-order rules (requested in the comments)
        if (library.mods.size() > 1) {
            Button sort = button("Auto-sort", v -> {
                boolean changed = library.autoSort(m -> {
                    ModCatalog.Mod c = m.id == null || m.id.isEmpty() ? null : catalog.byId(m.id);
                    return c != null ? c.category : null;
                });
                if (changed) { saveProfile(); ensureConflicts(); refresh(); }
                android.widget.Toast.makeText(this, changed ? "Sorted like 7th Heaven: by category, then each mod's own load order"
                        : "Already in 7th Heaven's order", android.widget.Toast.LENGTH_LONG).show();
            });
            sort.setSingleLine(true);   // one line: "Auto-" / "sort" read badly
            actions.addView(sort);
        }
        col.addView(actions);
        // what the enabled mods share (computed in the background: archives are read)
        ensureConflicts();
        if (library.enabledCount() > 1) {
            LinearLayout box = column();
            box.setPadding(0, dp(8), 0, dp(4));
            ConflictChecker.Report r = conflictReport;
            if (r == null || conflictBusy) box.addView(text("Checking which files the enabled mods share…", 14, DIM));
            if (r != null && r.lines.isEmpty() && !conflictBusy) box.addView(text("✓ No conflicts: the enabled mods replace different game files.", 14, 0xFF80D090));
            if (r != null && !r.lines.isEmpty()) {
                String head = (r.problems > 0 ? "⚠ " + r.problems + (r.problems == 1 ? " problem" : " problems") : "")
                        + (r.problems > 0 && r.overlaps > 0 ? ", " : "")
                        + (r.overlaps > 0 ? r.overlaps + (r.overlaps == 1 ? " overlap" : " overlaps") + " between mods" : "");
                box.addView(text(head, 15, r.problems > 0 ? 0xFFFFC060 : 0xFFE0C080));
                for (int i = 0; i < Math.min(2, r.lines.size()); i++) box.addView(text(r.lines.get(i).text, 13, DIM));
                LinearLayout b = row();
                b.addView(small("All details", v -> showConflicts()));
                if (r.interfaceBelow) b.addView(small("Put interface mods first", v -> {
                    if (library.interfaceFirst()) { saveProfile(); ensureConflicts(); refresh(); }
                }));
                box.addView(b);
            }
            col.addView(box);
        }
        if (library.mods.isEmpty()) col.addView(text("\nNo mods yet.", 16, DIM));
        for (int i = 0; i < library.mods.size(); i++) col.addView(modRow(i));
        return scroll(col);
    }

    private View modRow(int index) {
        ModLibrary.Mod m = library.mods.get(index);
        CheckBox cb = new CheckBox(this);
        cb.setTextColor(TEXT);
        cb.setTextSize(18);
        cb.setText(m.title() + (m.version != null && !m.version.isEmpty() ? "  " + m.version : ""));
        cb.setChecked(m.enabled && m.error == null);
        cb.setEnabled(m.error == null);
        cb.setOnCheckedChangeListener((b, on) -> { m.enabled = on; saveProfile(); ensureConflicts(); });
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
        ModCatalog.Installed fromCat = catalog.installedByFile(m.file);
        ModCatalog.Mod catMod = fromCat == null ? null : catalog.byId(fromCat.id);
        if (catMod != null && catalog.updateAvailable(catMod))
            info.addView(text("Update available: " + fromCat.versionText + " → " + catMod.versionText, 13, ACCENT));
        java.util.List<String> notes = conflictReport == null || !m.enabled ? null : conflictReport.perMod.get(m.file.toLowerCase(java.util.Locale.ROOT));
        if (notes != null) for (int i = 0; i < Math.min(3, notes.size()); i++) info.addView(text("↕ " + notes.get(i), 13, 0xFFE0C080));
        // the mod's details, then its buttons on a line of their own (side by side, the
        // buttons left the details no width)
        LinearLayout whole = column();
        whole.setPadding(0, dp(8), 0, dp(8));
        whole.addView(info);
        LinearLayout buttons = row();
        buttons.setPadding(dp(32), 0, 0, 0);
        buttons.addView(small("▲", v -> { library.move(index, -1); saveProfile(); refresh(); }));
        buttons.addView(small("▼", v -> { library.move(index, +1); saveProfile(); refresh(); }));
        if (!m.options.isEmpty()) buttons.addView(small("Options", v -> options(m)));
        if (catMod != null && catalog.updateAvailable(catMod)) buttons.addView(small("Update", v -> installMods(java.util.Collections.singletonList(catMod), true)));
        if (catMod != null) buttons.addView(small("Info", v -> modPage(catMod)));
        whole.addView(buttons);
        return whole;
    }

    // ---- conflicts between the enabled mods (ConflictChecker.report, src/mods) ------------------
    private ConflictChecker.Report conflictReport;
    private String conflictKey = "", conflictWanted = "";
    private boolean conflictBusy;

    /** Recompute when the profile or a mod file changed (a background thread; then refresh). */
    private void ensureConflicts() {
        StringBuilder k = new StringBuilder().append(library.profile.lastModified());
        for (ModLibrary.Mod m : library.mods) {
            if (!m.enabled) continue;
            File f = new File(library.dir, m.file);
            k.append('|').append(m.file).append(':').append(f.length()).append(':').append(f.lastModified());
        }
        final String key = k.toString();
        conflictWanted = key;
        if (key.equals(conflictKey) || conflictBusy) return;
        conflictBusy = true;
        new Thread(() -> {
            org.json.JSONObject j = library.conflicts();
            ConflictChecker.Report r = ConflictChecker.report(j, catalog);
            ConflictChecker.optionNeeds(r, library, catalog);
            ConflictChecker.interfaceOrder(r, library);
            runOnUiThread(() -> {
                int before = conflictReport == null ? -1 : conflictReport.overlaps + conflictReport.problems;
                conflictReport = r;
                conflictKey = key;
                conflictBusy = false;
                if (!conflictWanted.equals(key)) { ensureConflicts(); return; }   // changed meanwhile
                if (before >= 0 && r.overlaps + r.problems > before && !"mods".equals(page))
                    android.widget.Toast.makeText(this, "Some mods now share files: see Mods", android.widget.Toast.LENGTH_LONG).show();
                if ("mods".equals(page)) refresh();
            });
        }, "mod-conflicts").start();
    }

    private void showConflicts() {
        ConflictChecker.Report r = conflictReport;
        if (r == null) return;
        ff(new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Mod conflicts")
                .setView(scroll(warningList(r.lines, "Where two mods replace the same game file, the one higher in the Mods list is used "
                        + "(use ▲ ▼ to change it). Overlaps are often fine, such as a texture pack over a model pack; "
                        + "a ⚠ means a mod itself says something is wrong.")))
                .setPositiveButton("OK", null)
                .show());
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
        ff(new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(m.title())
                .setView(scroll(col))
                .setPositiveButton("Done", (d, w) -> { saveProfile(); refresh(); })
                .show());
    }

    // ------------------------------------------------------------------- Get mods (catalogs)
    /** Get mods, laid out like a store: header (count, refresh, update all), search, then the
     *  categories on the left and the matching mods on the right. A row opens the mod's page;
     *  its one button installs or updates it. */
    private View catalogPage() {
        LinearLayout page = column();
        int total = catalog.mods.size();

        LinearLayout head = row();
        TextView h = heading("Get mods");
        h.setPadding(0, 0, dp(12), 0);
        head.addView(h);
        head.addView(text(total + (total == 1 ? " mod" : " mods"), 15, ACCENT));
        View gap = new View(this);
        head.addView(gap, new LinearLayout.LayoutParams(0, 1, 1));
        java.util.List<ModCatalog.Mod> ups = catalog.updates();
        if (!ups.isEmpty()) head.addView(compact(small("Update all (" + ups.size() + ")", v -> installMods(ups, true))));
        head.addView(compact(small("⟳ Refresh", v -> background(() -> {
            progress("Checking the mod catalogs");
            String err = catalog.refresh(true);
            return err == null ? null : "Some catalogs could not be checked:\n" + err;
        }))));
        page.addView(head);

        EditText q = new EditText(this);
        q.setHint("🔍  Search by name, author or tag");
        q.setText(catQuery);
        q.setSingleLine(true);
        q.setTextSize(16);
        q.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        q.setOnEditorActionListener((v, id, ev) -> { catQuery = v.getText().toString(); refresh(); return true; });
        page.addView(q, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        page.addView(space(6));

        LinearLayout split = row();
        split.setGravity(Gravity.TOP);
        // categories: All, Installed, then 7th Heaven's that have mods, each with its count
        LinearLayout cats = column();
        java.util.Map<String, Integer> counts = new java.util.HashMap<>();
        int installed = 0;
        for (ModCatalog.Mod m : catalog.mods) {
            counts.merge(m.category.toLowerCase(java.util.Locale.ROOT), 1, Integer::sum);
            if (catalog.installedOf(m) != null) installed++;
        }
        cats.addView(category("All", total, catCategory == null && !catInstalledOnly, v -> { catCategory = null; catInstalledOnly = false; refresh(); }));
        cats.addView(category("Installed", installed, catInstalledOnly, v -> { catCategory = null; catInstalledOnly = true; refresh(); }));
        View line = new View(this);
        line.setBackgroundColor(0x40FFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
        lp.setMargins(dp(8), dp(6), dp(8), dp(6));
        cats.addView(line, lp);
        for (String c : ModCatalog.CATEGORIES) {
            Integer n = counts.get(c.toLowerCase(java.util.Locale.ROOT));
            if (n == null) continue;
            cats.addView(category(c, n, c.equals(catCategory) && !catInstalledOnly, v -> { catCategory = c; catInstalledOnly = false; refresh(); }));
        }
        ScrollView catScroll = scroll(cats);
        catScroll.setVerticalScrollBarEnabled(false);
        split.addView(catScroll, new LinearLayout.LayoutParams(dp(132), ViewGroup.LayoutParams.MATCH_PARENT));
        View vline = new View(this);
        vline.setBackgroundColor(0x40FFFFFF);
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(Math.max(1, dp(1)), ViewGroup.LayoutParams.MATCH_PARENT);
        vlp.setMargins(dp(8), 0, dp(12), 0);
        split.addView(vline, vlp);

        LinearLayout list = column();
        if (total == 0) {
            list.addView(text("No catalog loaded yet. Check the internet connection and press Refresh.", 16, DIM));
            for (ModCatalog.Subscription sc : catalog.subs) if (sc.error != null) list.addView(text(sc.name + ": " + sc.error, 13, 0xFFFF8080));
        }
        java.util.List<ModCatalog.Mod> found = catalog.search(catQuery, catCategory, catInstalledOnly);
        if (total > 0 && found.isEmpty()) list.addView(text("Nothing matches.", 16, DIM));
        for (int i = 0; i < found.size(); i++) {
            if (i > 0) {
                View d = new View(this);
                d.setBackgroundColor(0x30FFFFFF);
                list.addView(d, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1))));
            }
            list.addView(catalogRow(found.get(i), catCategory == null));
        }
        split.addView(scroll(list), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        page.addView(split, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        return page;
    }

    /** A category in the Get mods list: its name and count; the one shown is lit. */
    private Button category(String label, int count, boolean on, View.OnClickListener l) {
        Button b = new Button(this);
        android.text.SpannableString t = new android.text.SpannableString(label + "  " + count);
        t.setSpan(new android.text.style.ForegroundColorSpan(on ? TEXT : DIM), label.length(), t.length(), 0);
        t.setSpan(new android.text.style.RelativeSizeSpan(0.8f), label.length(), t.length(), 0);
        b.setText(t);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setTextColor(TEXT);
        shadow(b);
        b.setSingleLine(true);
        b.setEllipsize(android.text.TextUtils.TruncateAt.END);
        b.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        b.setPadding(dp(8), 0, dp(4), 0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setStateListAnimator(null);
        android.graphics.drawable.StateListDrawable sl = new android.graphics.drawable.StateListDrawable();
        sl.addState(new int[] { android.R.attr.state_focused }, window(HOT_TOP, HOT_BOTTOM, 6));
        sl.addState(new int[] { android.R.attr.state_pressed }, window(HOT_TOP, HOT_BOTTOM, 6));
        if (on) sl.addState(new int[] {}, window(BTN_TOP, BTN_BOTTOM, 6));
        b.setBackground(sl);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34));
        lp.setMargins(0, dp(1), 0, dp(1));
        b.setLayoutParams(lp);
        return b;
    }

    private View catalogRow(ModCatalog.Mod m, boolean showCategory) {
        LinearLayout r = row();
        r.setPadding(0, dp(6), 0, dp(6));
        // the name and details: tap (or A) for the mod's page
        LinearLayout info = column();
        info.setPadding(dp(8), dp(6), dp(8), dp(6));
        TextView name = text(m.name, 16, TEXT);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setMaxLines(2);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        info.addView(name);
        StringBuilder b = new StringBuilder();
        if (!m.author.isEmpty()) b.append(m.author);
        if (showCategory && !m.category.isEmpty()) b.append(b.length() > 0 ? "  ·  " : "").append(m.category);
        if (!m.sizeText().isEmpty()) b.append(b.length() > 0 ? "  ·  " : "").append(m.sizeText());
        TextView meta = text(b.toString(), 13, DIM);
        meta.setSingleLine(true);
        meta.setEllipsize(android.text.TextUtils.TruncateAt.END);
        info.addView(meta);
        info.setFocusable(true);
        info.setClickable(true);
        info.setOnClickListener(v -> modPage(m));
        GradientDrawable hot = new GradientDrawable();
        hot.setColor(0x33FFFFFF);
        hot.setCornerRadius(dp(6));
        android.graphics.drawable.StateListDrawable sl = new android.graphics.drawable.StateListDrawable();
        sl.addState(new int[] { android.R.attr.state_focused }, hot);
        sl.addState(new int[] { android.R.attr.state_pressed }, hot);
        info.setBackground(sl);
        r.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout slot = row();
        slot.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        fillAction(slot, m);
        actionSlots.put(m.id, slot);
        r.addView(slot, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return r;
    }

    /** A Get mods row's action: Install / Update / Installed, or its place in the queue. */
    private void fillAction(LinearLayout slot, ModCatalog.Mod m) {
        DownloadQueue.Item q = queue.pending(m);
        ModCatalog.Installed in = catalog.installedOf(m);
        String label = q != null ? queueLabel(q, false)
                : in == null ? "\u0001install" : catalog.updateAvailable(m) ? "\u0001update" : "\u0001installed";
        if (label.equals(slot.getTag())) return;                     // unchanged: keep the focus
        boolean hadFocus = slot.hasFocus();
        slot.setTag(label);
        slot.removeAllViews();
        if (q != null) {
            Button b = compact(small(label, v -> show("downloads")));
            slot.addView(b);
            if (hadFocus) b.requestFocus();
            return;
        }
        if (in == null) slot.addView(compact(small("Install", v -> installMods(java.util.Collections.singletonList(m), false))));
        else if (catalog.updateAvailable(m)) slot.addView(compact(small("Update", v -> installMods(java.util.Collections.singletonList(m), true))));
        else slot.addView(text("✓ Installed", 14, ACCENT));
    }

    /** "Queued", "Downloading 40%", "Installing"; long: with sizes. */
    private String queueLabel(DownloadQueue.Item q, boolean longForm) {
        switch (q.state) {
            case QUEUED: return "Queued";
            case RUNNING:
                if (!"Downloading".equals(q.phase)) return q.phase + "…";
                int pc = q.percent();
                if (!longForm) return pc >= 0 ? "Downloading " + pc + "%" : "Downloading…";
                return "Downloading " + ModDownloader.human(q.got)
                        + (q.total > 0 ? " of " + ModDownloader.human(q.total) : "") + (pc >= 0 ? "  (" + pc + "%)" : "");
            case DONE: return q.update ? "Updated to " + q.mod.versionText : "Installed";
            case CANCELLED: return "Cancelled";
            default: return "Failed: " + q.error;
        }
    }

    // ------------------------------------------------------------------- Downloads (the queue)
    private View downloadsPage() {
        LinearLayout col = column();
        col.addView(heading("Downloads"));
        col.addView(text("Mods download one at a time, top first. The launcher stays usable meanwhile, and Play works too: "
                + "a mod that finishes while the game runs is used from the next Play. If the launcher is closed, "
                + "the queue carries on (and the download resumes) when it opens again.", 14, DIM));
        LinearLayout bar = row();
        bar.addView(small("Get mods…", v -> show("catalog")));
        bar.addView(small("Clear finished", v -> { queue.clearFinished(); refresh(); }));
        col.addView(bar);
        java.util.List<DownloadQueue.Item> items = queue.snapshot();
        if (items.isEmpty()) col.addView(text("\nNothing downloading. Pick mods under Get mods.", 16, DIM));
        for (DownloadQueue.Item q : items) col.addView(downloadRow(q));
        return scroll(col);
    }

    private View downloadRow(DownloadQueue.Item q) {
        LinearLayout whole = column();
        whole.setPadding(0, dp(10), 0, dp(10));
        LinearLayout r = row();
        LinearLayout info = column();
        TextView name = text(q.mod.name + (q.update ? "  (update to " + q.mod.versionText + ")" : ""), 18, TEXT);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        info.addView(name);
        boolean bad = q.state == DownloadQueue.State.FAILED;
        TextView line = text(queueLabel(q, true) + (q.state == DownloadQueue.State.QUEUED && !q.mod.sizeText().isEmpty() ? "  ·  " + q.mod.sizeText() : ""),
                14, bad ? 0xFFFF8080 : q.state == DownloadQueue.State.DONE ? ACCENT : DIM);
        info.addView(line);
        r.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        switch (q.state) {
            case QUEUED:
                r.addView(small("▲", v -> queue.move(q, -1)));
                r.addView(small("▼", v -> queue.move(q, +1)));
                r.addView(small("Cancel", v -> queue.cancel(q)));
                break;
            case RUNNING:
                r.addView(small("Cancel", v -> queue.cancel(q)));
                break;
            case FAILED: case CANCELLED:
                r.addView(small("Retry", v -> queue.retry(q)));
                break;
            default:
                break;
        }
        whole.addView(r);
        android.widget.ProgressBar bar = null;
        if (q.state == DownloadQueue.State.RUNNING) {
            bar = new android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            bar.setMax(100);
            int pc = q.percent();
            bar.setIndeterminate(pc < 0 || !"Downloading".equals(q.phase));
            if (pc >= 0) bar.setProgress(pc);
            whole.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)));
        }
        progressViews.put(q, new Object[] { line, bar });
        return whole;
    }

    private String queueSignature() {
        StringBuilder b = new StringBuilder();
        for (DownloadQueue.Item q : queue.snapshot()) b.append(q.mod.id).append(':').append(q.state).append(':').append(q.phase).append(';');
        return b.toString();
    }

    /** The queue moved: the status line always; the page in place when only progress changed. */
    private void queueChanged() {
        String sig = queueSignature();
        boolean structural = !sig.equals(queueSig);
        boolean installedOne = structural && countDone(sig) > countDone(queueSig);
        queueSig = sig;
        if (installedOne) { library.load(); catalog.prune(library.dir); ensureConflicts(); }
        updateStatus();
        switch (page) {
            case "downloads":
                if (structural) { refresh(); return; }
                for (java.util.Map.Entry<DownloadQueue.Item, Object[]> e : progressViews.entrySet()) {
                    DownloadQueue.Item q = e.getKey();
                    ((TextView) e.getValue()[0]).setText(queueLabel(q, true));
                    android.widget.ProgressBar bar = (android.widget.ProgressBar) e.getValue()[1];
                    if (bar != null && q.percent() >= 0 && "Downloading".equals(q.phase)) { bar.setIndeterminate(false); bar.setProgress(q.percent()); }
                }
                break;
            case "catalog":
                for (ModCatalog.Mod m : catalog.mods) { LinearLayout s = actionSlots.get(m.id); if (s != null) fillAction(s, m); }
                break;
            case "mods":
                if (installedOne) refresh();
                break;
            default:
                break;
        }
    }

    private static int countDone(String sig) {
        int n = 0, i = 0;
        while ((i = sig.indexOf(":DONE:", i)) >= 0) { n++; i++; }
        return n;
    }

    private void updateStatus() {
        StringBuilder s = new StringBuilder();
        s.append(!install.hasGame() ? "Game: not imported yet"
                : GameBuild.running ? "Game: building for this device, " + GameBuild.percent + "%"
                : build.built() ? "Game: ready" : "Game: not built for this device yet")
                .append("\nMods: ").append(library.enabledCount()).append(" of ").append(library.mods.size()).append(" enabled");
        if (!catalog.updates().isEmpty()) s.append("\nMod updates: ").append(catalog.updates().size());
        if (Updater.available != null) s.append("\nFF7Recomp ").append(Updater.available.version).append(" available (Settings)");
        DownloadQueue.Item run = queue.running();
        int waiting = queue.pendingCount() - (run != null ? 1 : 0);
        if (run != null) s.append("\n").append(run.mod.name).append(": ").append(queueLabel(run, false));
        if (waiting > 0) s.append("\n").append(waiting).append(" more queued");
        s.append("\nFrame rate: ").append(fpsName(prefs.getInt("fps", 3))).append(boosterSpeed() > 1 ? ", " + boosterSpeed() + "× speed" : "");
        if (prefs.getBoolean("no_encounters", false) || prefs.getBoolean("battle_plus", false))
            s.append("\nBoosters: ").append(prefs.getBoolean("no_encounters", false) ? "no random battles" : "")
                    .append(prefs.getBoolean("no_encounters", false) && prefs.getBoolean("battle_plus", false) ? ", " : "")
                    .append(prefs.getBoolean("battle_plus", false) ? "battle enhancement" : "");
        status.setText(s.toString().replace("\n", "   ·   "));
        int n = queue.pendingCount();
        downloadsButton.setText(n > 0 ? "Downloads (" + n + ")" : "Downloads");
        // an FF7Recomp update is out: a gold dot beside Settings, where the update button is
        if (Updater.available != null) {
            android.text.SpannableString t = new android.text.SpannableString("Settings  ●");
            t.setSpan(new android.text.style.ForegroundColorSpan(0xFFFFD24A), t.length() - 1, t.length(), 0);
            settingsButton.setText(t);
            settingsButton.setContentDescription("Settings, FF7Recomp " + Updater.available.version + " available");
        } else {
            settingsButton.setText("Settings");
            settingsButton.setContentDescription(null);
        }
    }


    /** One mod's page: preview, description, notes, links, install / update / remove. */
    private void modPage(ModCatalog.Mod m) {
        LinearLayout col = column();
        col.setPadding(dp(24), dp(8), dp(24), dp(8));
        ImageView img = new ImageView(this);
        img.setAdjustViewBounds(true);
        img.setMaxHeight(dp(260));
        col.addView(img);
        loadPreview(m, img);
        StringBuilder head = new StringBuilder();
        if (!m.author.isEmpty()) head.append("by ").append(m.author).append('\n');
        head.append(m.category.isEmpty() ? "" : m.category + "  ·  ").append("version ").append(m.versionText.isEmpty() ? "?" : m.versionText);
        if (!m.releaseDate.isEmpty()) head.append("  ·  ").append(m.releaseDate.length() > 10 ? m.releaseDate.substring(0, 10) : m.releaseDate);
        if (!m.sizeText().isEmpty()) head.append("  ·  ").append(m.sizeText());
        head.append("\nfrom ").append(m.catalog);
        col.addView(text(head.toString(), 14, DIM));
        ModCatalog.Installed in = catalog.installedOf(m);
        if (in != null) col.addView(text("Installed: version " + in.versionText + " (" + in.file + ")", 14, ACCENT));
        col.addView(space(8));
        if (!m.description.isEmpty()) col.addView(text(m.description, 15, TEXT));
        if (!m.releaseNotes.isEmpty()) { col.addView(space(8)); col.addView(text("Release notes", 16, TEXT)); col.addView(text(m.releaseNotes, 14, DIM)); }
        if (!m.tags.isEmpty()) col.addView(text("\nTags: " + String.join(", ", m.tags), 13, DIM));
        for (String[] req : m.requirements) {
            ModCatalog.Mod rm = catalog.byId(req[0]);
            String what = rm != null ? rm.name : (req[1].isEmpty() ? req[0] : req[1]);
            boolean have = rm != null && catalog.installedOf(rm) != null;
            col.addView(text("Needs: " + what + (have ? " (installed)" : rm != null ? " (installed with it)" : " (not in your catalogs)"), 14, have ? DIM : 0xFFFFC060));
        }
        if (in == null) {
            java.util.List<ModCatalog.Mod> batch = new java.util.ArrayList<>();
            addWithRequirements(m, batch, 0);
            for (ConflictChecker.Warning w : ConflictChecker.beforeInstall(catalog, library, batch))
                col.addView(text((w.serious ? "⚠ " : "Possible conflict: ") + w.text, 14, w.serious ? 0xFFFFC060 : 0xFFE0C080));
        } else {
            java.util.List<String> notes = conflictReport == null ? null : conflictReport.perMod.get(in.file.toLowerCase(java.util.Locale.ROOT));
            if (notes != null) for (String s : notes) col.addView(text("↕ " + s, 14, 0xFFE0C080));
        }
        boolean browserOnly = !m.links.isEmpty();
        for (String l : m.links) if (ModDownloader.resolve(l) != null) browserOnly = false;
        if (browserOnly) col.addView(text("\nThis mod is downloaded from a web page: open it, download, then add the file under Mods.", 14, 0xFFFFC060));
        AlertDialog.Builder d = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(m.name)
                .setView(scroll(col))
                .setNegativeButton("Close", null);
        DownloadQueue.Item q = queue.pending(m);
        if (q != null) d.setPositiveButton(queueLabel(q, false) + " (Downloads)", (x, w) -> show("downloads"));
        else if (in == null && !browserOnly) d.setPositiveButton("Install", (x, w) -> installMods(java.util.Collections.singletonList(m), false));
        else if (in != null && catalog.updateAvailable(m)) d.setPositiveButton("Update to " + m.versionText, (x, w) -> installMods(java.util.Collections.singletonList(m), true));
        else if (in != null) d.setPositiveButton("Remove", (x, w) -> uninstall(m));
        String web = !m.infoLink.isEmpty() ? m.infoLink : null;
        if (browserOnly) for (String l : m.links) { String u = ModDownloader.browserUrl(l); if (u != null) web = u; }
        if (web != null) {
            final String url = web;
            d.setNeutralButton(browserOnly ? "Open download page" : "Mod's web page", (x, w) -> {
                try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Exception e) { toastDialog("No browser to open " + url); }
            });
        }
        ff(d.show());
    }

    private void loadPreview(ModCatalog.Mod m, ImageView img) {
        if (m.preview.isEmpty()) { img.setVisibility(View.GONE); return; }
        android.graphics.Bitmap cached = previews.get(m.preview);
        if (cached != null) { img.setImageBitmap(cached); return; }
        new Thread(() -> {
            try {
                File dir = new File(catalog.dir, "previews");
                dir.mkdirs();
                File f = new File(dir, Integer.toHexString(m.preview.hashCode()) + ".img");
                if (!f.isFile()) {
                    byte[] b = ModDownloader.get(ModDownloader.resolve(m.preview) != null ? ModDownloader.resolve(m.preview) : m.preview);
                    try (java.io.FileOutputStream o = new java.io.FileOutputStream(f)) { o.write(b); }
                }
                android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
                o.inSampleSize = 1;
                android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                android.graphics.BitmapFactory.decodeFile(f.getPath(), bounds);
                while (bounds.outWidth / o.inSampleSize > 1600) o.inSampleSize *= 2;
                android.graphics.Bitmap bm = android.graphics.BitmapFactory.decodeFile(f.getPath(), o);
                if (bm == null) return;
                runOnUiThread(() -> { previews.put(m.preview, bm); img.setImageBitmap(bm); });
            } catch (Exception e) {
                runOnUiThread(() -> img.setVisibility(View.GONE));
            }
        }).start();
    }

    /** Install (or update) mods from the catalog, with the catalog mods they need first. */
    /** Queue mods (installs or updates) from the catalog, with the catalog mods they need first. */
    private void installMods(java.util.List<ModCatalog.Mod> wanted, boolean update) {
        java.util.List<ModCatalog.Mod> order = new java.util.ArrayList<>();
        for (ModCatalog.Mod m : wanted) addWithRequirements(m, order, 0);
        java.util.List<ModCatalog.Mod> todo = new java.util.ArrayList<>();
        for (ModCatalog.Mod m : order) {
            ModCatalog.Installed in = catalog.installedOf(m);
            if (in != null && !catalog.updateAvailable(m)) continue;
            if (queue.pending(m) == null) todo.add(m);
        }
        if (todo.isEmpty()) return;
        // conflicts with the mods already enabled (and within this batch): ask first
        java.util.List<ConflictChecker.Warning> warn = ConflictChecker.beforeInstall(catalog, library, todo);
        if (warn.isEmpty()) { enqueue(todo); return; }
        ff(new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Possible conflicts")
                .setView(scroll(warningList(warn, "Catalogs do not list the files inside a mod, so this is a guess from what the mods are. "
                        + "After the download, the Mods page shows the exact files they share.")))
                .setPositiveButton(todo.size() == 1 ? "Install anyway" : "Install all anyway", (d, w) -> enqueue(todo))
                .setNegativeButton("Cancel", null)
                .show());
    }

    private LinearLayout warningList(java.util.List<ConflictChecker.Warning> warn, String footer) {
        LinearLayout col = column();
        col.setPadding(dp(24), dp(8), dp(24), dp(8));
        for (ConflictChecker.Warning w : warn) {
            col.addView(text((w.serious ? "⚠ " : "• ") + w.text, 15, w.serious ? 0xFFFFC060 : TEXT));
            col.addView(space(8));
        }
        if (footer != null) col.addView(text(footer, 13, DIM));
        return col;
    }

    private void enqueue(java.util.List<ModCatalog.Mod> todo) {
        int added = 0;
        for (ModCatalog.Mod m : todo) if (queue.add(m, catalog.installedOf(m) != null)) added++;
        if (added > 0) {
            android.widget.Toast.makeText(this, added == 1 ? "Added to Downloads" : added + " mods added to Downloads",
                    android.widget.Toast.LENGTH_SHORT).show();
        }
        queueChanged();
    }

    private void addWithRequirements(ModCatalog.Mod m, java.util.List<ModCatalog.Mod> order, int depth) {
        if (order.contains(m) || depth > 8) return;
        for (String[] req : m.requirements) {
            ModCatalog.Mod rm = catalog.byId(req[0]);
            if (rm != null && catalog.installedOf(rm) == null) addWithRequirements(rm, order, depth + 1);
        }
        order.add(m);
    }

    /** A newly installed mod is switched on (the library lists new files last, off). */
    private void enableNew(String file) {
        library.load();
        for (ModLibrary.Mod lm : library.mods) if (lm.file.equalsIgnoreCase(file)) lm.enabled = true;
        try { library.save(); } catch (Exception ignored) { }
    }

    private void uninstall(ModCatalog.Mod m) {
        ModCatalog.Installed in = catalog.installedOf(m);
        if (in == null) return;
        if (queue.pending(m) != null) { toastDialog(m.name + " is in Downloads; cancel it there first."); return; }
        ff(new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setMessage("Remove " + m.name + "? Its file is deleted from the device.")
                .setPositiveButton("Remove", (x, w) -> {
                    ModDownloader.deleteTree(new File(library.dir, in.file));
                    catalog.forget(m.id);
                    library.load();
                    saveProfile();
                    refresh();
                })
                .setNegativeButton("Cancel", null)
                .show());
    }

    /** Settings: the catalogs subscribed to. */
    private View catalogSettings() {
        LinearLayout col = column();
        col.addView(heading("Mod catalogs"));
        col.addView(text("Catalogs list mods to install and update, as in 7th Heaven. Checked once a day when the launcher opens.", 14, DIM));
        java.text.DateFormat df = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT);
        for (ModCatalog.Subscription sc : catalog.subs) {
            LinearLayout r = row();
            LinearLayout info = column();
            info.addView(text(sc.name, 16, TEXT));
            String st = sc.count + " mods" + (sc.lastCheck > 0 ? ", checked " + df.format(new java.util.Date(sc.lastCheck)) : ", not checked yet");
            info.addView(text(st + (sc.error != null ? "\n⚠ " + sc.error : ""), 13, sc.error != null ? 0xFFFFC060 : DIM));
            r.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            if (!catalog.isDefault(sc)) r.addView(small("Remove", v -> { catalog.removeSubscription(sc); catalog.rebuild(); refresh(); }));
            col.addView(r);
        }
        col.addView(button("Add a catalog…", v -> {
            LinearLayout f = column();
            f.setPadding(dp(24), dp(8), dp(24), dp(8));
            EditText name = new EditText(this);
            name.setHint("Name (optional)");
            EditText url = new EditText(this);
            url.setHint("iros://Url/https$... or https://...catalog.xml");
            f.addView(name);
            f.addView(url);
            ff(new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("Add a mod catalog")
                    .setView(f)
                    .setPositiveButton("Add", (x, w) -> {
                        String err = catalog.addSubscription(name.getText().toString(), url.getText().toString());
                        if (err != null) { toastDialog(err); return; }
                        background(() -> { progress("Checking the new catalog"); String e = catalog.refresh(false); return e; });
                    })
                    .setNegativeButton("Cancel", null)
                    .show());
        }));
        return col;
    }

    private View settingsPage() {
        LinearLayout col = column();
        col.addView(heading("Updates"));
        Updater.Release up = Updater.available;
        if (Updater.status.isEmpty()) Updater.status = "FF7Recomp " + Updater.installedVersion(this);
        updateLine = text(updateText(), 15, up != null ? ACCENT : DIM);
        col.addView(updateLine);
        col.addView(space(8));
        LinearLayout ub = new LinearLayout(this);
        ub.addView(button("Check now", v -> checkUpdates(true)));
        if (up != null) {
            Button go = button("Update to " + up.version, v -> installUpdate());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = dp(12);
            ub.addView(go, lp);
        }
        col.addView(ub);
        col.addView(text("Installed: " + Updater.installedVersion(this) + "   ·   from github.com/" + Updater.REPO, 13, DIM));
        col.addView(space(18));
        CheckBox ms = new CheckBox(this);
        ms.setText("Menu sounds (FF7's own, from your game files)");
        ms.setTextSize(16);
        ms.setChecked(prefs.getBoolean("menu_sounds", true));
        ms.setOnCheckedChangeListener((b, on) -> { prefs.edit().putBoolean("menu_sounds", on).apply(); loadSounds(); });
        col.addView(ms);
        col.addView(space(10));
        col.addView(heading("Language"));
        col.addView(text("The game's text and menus. Mods that replace text (field scripts, battle commands) keep their own language.", 14, DIM));
        RadioGroup lg = new RadioGroup(this);
        int curLang = prefs.getInt("game_language", 1);
        for (String[] l : GameInstall.LANGUAGES) {
            RadioButton rb = new RadioButton(this);
            rb.setId(View.generateViewId());
            final int v = Integer.parseInt(l[0]);
            boolean have = !install.hasGame() || install.hasLanguage(v);
            rb.setText(have ? l[1] : l[1] + "\nImport your game files again (Game files) to add it");
            rb.setTextColor(TEXT);
            rb.setTextSize(16);
            rb.setOnClickListener(x -> prefs.edit().putInt("game_language", v).apply());
            lg.addView(rb);
            if (v == curLang) rb.setChecked(true);
        }
        col.addView(lg);
        File sd = Storage.sdRoot(this);
        if (sd != null || Storage.onSd(this)) {
            col.addView(space(10));
            col.addView(heading("Storage"));
            col.addView(text("Where your game files, mods, saves and save states are kept. Moving them takes a few "
                    + "minutes; the built game itself always stays in internal storage.", 14, DIM));
            RadioGroup sg = new RadioGroup(this);
            boolean onSd = Storage.onSd(this);
            String[][] places = {
                { "0", "Internal storage", ModDownloader.human(Storage.internalRoot(this).getUsableSpace()) + " free" },
                { "1", "SD card", sd != null ? ModDownloader.human(sd.getUsableSpace()) + " free" : "not inserted" },
            };
            for (String[] pl : places) {
                RadioButton rb = new RadioButton(this);
                rb.setId(View.generateViewId());
                rb.setText(pl[1] + "\n" + pl[2]);
                rb.setTextColor(TEXT);
                rb.setTextSize(16);
                final boolean toSd = pl[0].equals("1");
                rb.setChecked(toSd == onSd);
                rb.setEnabled(!toSd || sd != null);
                rb.setOnClickListener(x -> {
                    if (toSd == Storage.onSd(this)) return;
                    moveStorage(toSd);
                    refresh();                               // the radio shows the real place until moved
                });
                sg.addView(rb);
            }
            col.addView(sg);
        }
        col.addView(space(10));
        col.addView(heading("Frame rate"));
        RadioGroup g = new RadioGroup(this);
        int cur = prefs.getInt("fps", 3);
        for (int mode = 0; mode <= 4; mode++) {
            RadioButton rb = new RadioButton(this);
            rb.setId(View.generateViewId());
            // one line: the name, then a short dim note
            android.text.SpannableString label = new android.text.SpannableString(fpsName(mode) + "   " + fpsDetail(mode));
            int at = fpsName(mode).length();
            label.setSpan(new android.text.style.ForegroundColorSpan(DIM), at, label.length(), 0);
            label.setSpan(new android.text.style.RelativeSizeSpan(0.85f), at, label.length(), 0);
            rb.setText(label);
            rb.setTextColor(TEXT);
            rb.setTextSize(16);
            final int m = mode;
            rb.setOnClickListener(v -> { prefs.edit().putInt("fps", m).apply(); refresh(); });
            g.addView(rb);
            if (mode == cur) rb.setChecked(true);
        }
        col.addView(g);
        // Performance: render scale and frame skipping (heat and battery on smaller devices)
        col.addView(space(16));
        col.addView(heading("Performance"));
        col.addView(text("How hard the GPU works. Balanced renders at the screen's resolution and, when the game is "
                + "sped up, draws only the frames normal speed would. 120 FPS above doubles the work at normal speed.", 14, DIM));
        RadioGroup pg = new RadioGroup(this);
        String[] perfNames = {
            "Cooler: a lower resolution, for weaker devices or long sessions",
            "Balanced: the screen's resolution (recommended)",
            "Best quality: the resolution mods ask for, every frame drawn when sped up",
        };
        int curPerf = prefs.getInt("perf", 1);
        for (int p = 0; p < perfNames.length; p++) {
            RadioButton rb = new RadioButton(this);
            rb.setId(View.generateViewId());
            rb.setText(perfNames[p]);
            rb.setTextColor(TEXT);
            rb.setTextSize(16);
            final int pv = p;
            rb.setOnClickListener(v -> prefs.edit().putInt("perf", pv).apply());
            pg.addView(rb);
            if (p == curPerf) rb.setChecked(true);
        }
        col.addView(pg);
        // Game boosters (docs/ROADMAP.md M8): how each game starts; in game the bottom screen's
        // Speed / Battles / Battle+ buttons and FFNx's L3 shortcuts change them.
        col.addView(space(16));
        col.addView(heading("Game boosters"));
        col.addView(text("How the game starts. In game: the bottom screen's Speed, Battles and Battle+ buttons, or L3 then "
                + "R1/L1 (speed), A (random battles), B (battle enhancement). Icons in the top-right corner show what is on.", 14, DIM));
        RadioGroup sg = new RadioGroup(this);
        sg.setOrientation(LinearLayout.HORIZONTAL);
        int curSpeed = boosterSpeed();
        for (int sp = 1; sp <= 3; sp++) {
            RadioButton rb = new RadioButton(this);
            rb.setText(sp + "× speed   ");
            rb.setTextColor(TEXT);
            rb.setTextSize(16);
            rb.setId(View.generateViewId());
            final int v = sp;
            rb.setOnClickListener(x -> { prefs.edit().putInt("speed", v).putBoolean("turbo", v > 1).apply(); refresh(); });
            sg.addView(rb);
            if (sp == curSpeed) rb.setChecked(true);
        }
        col.addView(sg);
        CheckBox noBattles = new CheckBox(this);
        noBattles.setText("No random battles\nScripted battles and bosses still happen.");
        noBattles.setTextColor(TEXT);
        noBattles.setTextSize(16);
        noBattles.setChecked(prefs.getBoolean("no_encounters", false));
        noBattles.setOnCheckedChangeListener((b, on) -> prefs.edit().putBoolean("no_encounters", on).apply());
        col.addView(noBattles);
        CheckBox bplus = new CheckBox(this);
        bplus.setText("Battle enhancement\nThe party's HP and MP stay full in battle, and the Limit gauge is full.");
        bplus.setTextColor(TEXT);
        bplus.setTextSize(16);
        bplus.setChecked(prefs.getBoolean("battle_plus", false));
        bplus.setOnCheckedChangeListener((b, on) -> prefs.edit().putBoolean("battle_plus", on).apply());
        col.addView(bplus);
        CheckBox saveAny = new CheckBox(this);
        saveAny.setText("Save anywhere" + "\n" + "Save from the menu outside save points too. Keep a second save file: a save made in the "
                + "middle of an event can leave you stuck.");
        saveAny.setTextColor(TEXT);
        saveAny.setTextSize(16);
        saveAny.setChecked(prefs.getBoolean("save_anywhere", false));
        saveAny.setOnCheckedChangeListener((b, on) -> prefs.edit().putBoolean("save_anywhere", on).apply());
        col.addView(saveAny);
        multiplierRow(col, "Gil from battles", "gil");
        multiplierRow(col, "EXP from battles", "exp");    // GitHub #32
        multiplierRow(col, "AP from battles (materia growth)", "ap");
        col.addView(space(16));
        col.addView(heading("Display"));
        RadioGroup ar = new RadioGroup(this);
        String[][] aspects = {
            { "0", "4:3, original", "Black bars at the sides of a 16:9 screen" },
            { "2", "16:9 widescreen", "3D scenes and menus use the whole screen; fields without widescreen backgrounds keep side bars" },
            { "3", "16:9, stretched to fill", "For screens wider than 16:9 (most phones): the 16:9 picture is stretched sideways to the screen's edges, no black bars" },
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
        col.addView(text("Resolution", 16, TEXT));
        col.addView(text("What the game is drawn at before it is fitted to the screen. Above the screen's own "
                + "resolution it is sharper (supersampling), but the GPU works harder: 4× draws about 3 times "
                + "the pixels of a 1080p screen.", 14, DIM));
        RadioGroup resg = new RadioGroup(this);
        String[][] resolutions = {
            { "0", "Automatic (recommended)", "As Performance sets it: the screen's resolution on Balanced" },
            { "1", "Original", "640×480 (854×480 in 16:9)" },
            { "2", "2×", "1280×960 (1708×960 in 16:9), about 1080p" },
            { "3", "3×", "1920×1440 (2562×1440 in 16:9), 1440p" },
            { "4", "4×", "2560×1920 (3416×1920 in 16:9), close to 4K" },
        };
        int curRes = prefs.getInt("resolution", 0);
        for (String[] r : resolutions) {
            RadioButton rb = new RadioButton(this);
            rb.setId(View.generateViewId());
            rb.setText(r[1] + "\n" + r[2]);
            rb.setTextColor(TEXT);
            rb.setTextSize(16);
            final int v = Integer.parseInt(r[0]);
            rb.setOnClickListener(x -> prefs.edit().putInt("resolution", v).apply());
            resg.addView(rb);
            if (v == curRes) rb.setChecked(true);
        }
        col.addView(resg);
        col.addView(text("Renderer", 16, TEXT));
        RadioGroup rg = new RadioGroup(this);
        String[][] renderers = {
            { "0", "Vulkan (recommended)", "Fastest on most devices" },
            { "1", "OpenGL ES", "Try it if the game is slow, shows graphics errors or does not start with Vulkan (some Mali GPUs)" },
        };
        int curR = prefs.getInt("renderer", 0);
        for (String[] a : renderers) {
            RadioButton rb = new RadioButton(this);
            rb.setId(View.generateViewId());
            rb.setText(a[1] + "\n" + a[2]);
            rb.setTextColor(TEXT);
            rb.setTextSize(16);
            final int v = Integer.parseInt(a[0]);
            rb.setOnClickListener(x -> prefs.edit().putInt("renderer", v).apply());
            rg.addView(rb);
            if (v == curR) rb.setChecked(true);
        }
        col.addView(rg);
        CheckBox crt = new CheckBox(this);
        crt.setText("CRT filter\nScanlines and a phosphor mask, like an old TV (on the second screen too)");
        crt.setTextColor(TEXT);
        crt.setTextSize(16);
        crt.setChecked(prefs.getBoolean("crt_filter", false));
        crt.setOnCheckedChangeListener((b, on) -> prefs.edit().putBoolean("crt_filter", on).apply());
        col.addView(crt);
        col.addView(space(16));
        col.addView(heading("Screen"));
        CheckBox second = new CheckBox(this);
        second.setText("Use the second screen\nOn dual-screen devices such as the AYN Thor: party, enemy HP and the touch deck. Off: the second screen is free for other apps, such as a guide (tap the game screen to give the controller back to the game)");
        second.setTextColor(TEXT);
        second.setTextSize(16);
        second.setChecked(prefs.getBoolean("bottom_screen", true));
        second.setOnCheckedChangeListener((b, on) -> prefs.edit().putBoolean("bottom_screen", on).apply());
        col.addView(second);
        CheckBox deckBox = new CheckBox(this);
        deckBox.setText("Second screen buttons\nMenu, Save, Load, Speed, Battles and Battle+ along the bottom. "
                + "Off: the panels fill the screen and the controller does everything");
        deckBox.setTextColor(TEXT);
        deckBox.setTextSize(16);
        deckBox.setChecked(prefs.getBoolean("bottom_buttons", true));
        deckBox.setOnCheckedChangeListener((b, on) -> prefs.edit().putBoolean("bottom_buttons", on).apply());
        col.addView(deckBox);
        RadioGroup or = new RadioGroup(this);
        String[][] orientations = {
            { "0", "Landscape", "The game fills the screen" },
            { "1", "Portrait (phones)", "The game on top; below it the party, location and enemy HP, a touch deck "
                    + "(menu, save/load state, speed) and the on-screen controls" },
        };
        boolean curPortrait = prefs.getBoolean("portrait", false);
        for (String[] o : orientations) {
            RadioButton rb = new RadioButton(this);
            rb.setId(View.generateViewId());
            rb.setText(o[1] + "\n" + o[2]);
            rb.setTextColor(TEXT);
            rb.setTextSize(16);
            final boolean v = o[0].equals("1");
            rb.setOnClickListener(x -> prefs.edit().putBoolean("portrait", v).apply());
            or.addView(rb);
            if (v == curPortrait) rb.setChecked(true);
        }
        col.addView(or);
        col.addView(space(16));
        col.addView(heading("On-screen controls"));
        RadioGroup tc = new RadioGroup(this);
        String[][] touchModes = {
            { "0", "Automatic", "Shown on devices with one screen when no controller is connected; hidden as soon as one is used" },
            { "1", "Always", "Shown over the game, also with a controller" },
            { "2", "Off", "Never shown" },
        };
        int curTc = prefs.getInt("touch_controls", 0);
        for (String[] t : touchModes) {
            RadioButton rb = new RadioButton(this);
            rb.setId(View.generateViewId());
            rb.setText(t[1] + "\n" + t[2]);
            rb.setTextColor(TEXT);
            rb.setTextSize(16);
            final int v = Integer.parseInt(t[0]);
            rb.setOnClickListener(x -> prefs.edit().putInt("touch_controls", v).apply());
            tc.addView(rb);
            if (v == curTc) rb.setChecked(true);
        }
        col.addView(tc);
        col.addView(button("Size and layout…", v -> startActivity(new Intent(this, TouchLayoutActivity.class))));
        col.addView(space(16));
        col.addView(heading("Controller"));
        CheckBox analog = new CheckBox(this);
        analog.setText("Analog stick\nTilt the left stick a little to walk, all the way to run. In battles the right stick turns "
                + "the camera and L2/R2 zoom it (FFNx's analogue controls)");
        analog.setTextColor(TEXT);
        analog.setTextSize(16);
        analog.setChecked(prefs.getBoolean("analog_controls", false));
        analog.setOnCheckedChangeListener((b, on) -> prefs.edit().putBoolean("analog_controls", on).apply());
        col.addView(analog);
        CheckBox fire = new CheckBox(this);   // GitHub #33
        fire.setText("Hold confirm to repeat in battles\nHolding the confirm button keeps picking, about five times a "
                + "second, like auto-fire. Outside battles it presses once, as in the original");
        fire.setTextColor(TEXT);
        fire.setTextSize(16);
        fire.setChecked(prefs.getBoolean("autofire", false));
        fire.setOnCheckedChangeListener((b, on) -> prefs.edit().putBoolean("autofire", on).apply());
        col.addView(fire);
        // Rumble (src/ffnx_port/rumble.cpp): FF7 has none of its own, so it follows the game
        RadioGroup rumbleGroup = new RadioGroup(this);
        String[][] rumbles = {
            { "0", "No rumble", "As the original: the game never vibrates" },
            { "1", "Light rumble", "Field quakes and explosions, and hits the party takes in battles" },
            { "2", "Strong rumble", "The same, at full strength" },
        };
        int curRumble = prefs.getInt("rumble", 0);
        for (String[] t : rumbles) {
            RadioButton rb = new RadioButton(this);
            rb.setId(View.generateViewId());
            rb.setText(t[1] + "\n" + t[2]);
            rb.setTextColor(TEXT);
            rb.setTextSize(16);
            final int v = Integer.parseInt(t[0]);
            rb.setOnClickListener(x -> prefs.edit().putInt("rumble", v).apply());
            rumbleGroup.addView(rb);
            if (v == curRumble) rb.setChecked(true);
        }
        col.addView(rumbleGroup);
        col.addView(space(16));
        col.addView(heading("Start"));
        RadioGroup st = new RadioGroup(this);
        String[][] starts = {
            { "", "Normal", "Credits, title screen and opening movie, as the game plays them" },
            { "newgame", "Straight into a new game", "Skips the credits, picks New Game and skips the opening movie" },
            { "continue", "Continue straight away", "Skips the credits and loads the first save of the first save file" },
            { "resume", "Resume the current save-state slot", "Starts the game at the moment saved in the current slot (see Save states)" },
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
        col.addView(text("In game: press Start during a movie to skip it. Select+L1 / Select+R1 save and load the game's whole state, "
                + "Select+Left / Right change the slot.", 14, DIM));
        col.addView(space(16));
        col.addView(catalogSettings());
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
            case 0: return "As shipped";
            case 1: return "As shipped, smoother pacing";
            case 2: return "Battles at 30, the rest as shipped";
            case 4: return "Field at 120; world map and battles at 60";
            default: return "Everything at 60";
        }
    }

    private static final String GAMENATIVE_HINT = "Downloaded FF7 with GameNative? Its internal storage is private to "
            + "GameNative, so other apps cannot read it. Use GameNative's external storage (SD card) option, then pick "
            + "GameNative/Steam/steamapps/common/FINAL FANTASY VII on the card. Or copy the folder from a PC.";

    private View gamePage() {
        LinearLayout col = column();
        buildBar = null;
        buildLine = null;
        col.addView(heading("Your FINAL FANTASY VII"));
        if (!install.hasGame()) {
            col.addView(text("Pick the FINAL FANTASY VII folder from your Steam copy (the folder that holds "
                    + "\"ff7\"). FF7Recomp copies the game files, then turns the game into a native Android "
                    + "app on this device. Nothing from the game is downloaded or shared.", 15, TEXT));
            col.addView(space(8));
            col.addView(text(GAMENATIVE_HINT, 13, DIM));
            col.addView(space(14));
            col.addView(button("Find my FF7 folder…", v -> pick(PICK_GAME)));
        } else if (GameBuild.running) {
            col.addView(text("Building the game for this device. This happens once: about 8 minutes on an AYN "
                    + "Thor. You can leave the launcher; it keeps going in the background.", 15, TEXT));
            col.addView(space(14));
            buildLine = text(GameBuild.stage + " · " + GameBuild.percent + "%", 15, ACCENT);
            col.addView(buildLine);
            col.addView(space(6));
            buildBar = new android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            buildBar.setMax(100);
            buildBar.setIndeterminate(GameBuild.percent <= 0);
            buildBar.setProgress(GameBuild.percent);
            buildBar.setProgressTintList(android.content.res.ColorStateList.valueOf(ACCENT));
            col.addView(buildBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10)));
        } else if (build.built()) {
            col.addView(text("Ready. The game is built for this device: press Play.", 15, TEXT));
            col.addView(space(14));
            col.addView(button("Import again from another folder…", v -> pick(PICK_GAME)));
            col.addView(space(8));
            col.addView(button("Build again", v -> { new File(build.work, "stamp").delete(); startBuild(); }));
        } else {
            col.addView(text(GameBuild.error != null
                    ? "The last build stopped: " + GameBuild.error
                    : build.relinkOnly()
                    ? "The app was updated: link the game with the new engine (a few seconds)."
                    : "Your game files are here. One more step: build the game for this device (once: about 8 "
                      + "minutes on an AYN Thor).", 15, GameBuild.error != null ? DIM : TEXT));
            col.addView(space(14));
            col.addView(button(GameBuild.error != null ? "Try again" : build.relinkOnly() ? "Update the game" : "Build for this device",
                    v -> startBuild()));
            col.addView(space(8));
            col.addView(button("Import again from another folder…", v -> pick(PICK_GAME)));
        }
        if (install.hasGame() && !GameBuild.running) {
            col.addView(space(14));
            android.widget.CheckBox q = new android.widget.CheckBox(this);
            q.setText("Quiet build: about 14 minutes instead of 8, about 12 °C cooler");
            q.setChecked(prefs.getBoolean("quiet_build", false));
            q.setOnCheckedChangeListener((b, on) -> prefs.edit().putBoolean("quiet_build", on).apply());
            col.addView(q);
        }
        col.addView(space(14));
        col.addView(text("Game files: " + install.game.getAbsolutePath() + "\nBuild log: "
                + build.log.getAbsolutePath(), 12, DIM));
        return scroll(col);
    }

    private void startBuild() {
        if (GameBuild.running || !install.hasGame()) return;
        GameBuild.quiet = prefs.getBoolean("quiet_build", false);
        if (android.os.Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[] { android.Manifest.permission.POST_NOTIFICATIONS }, 9);
        BuildService.start(this);
        show("game");
    }

    // ------------------------------------------------------------------- actions
    private void pick(int what) {
        if (busy) return;
        Intent i;
        if (what == PICK_IRO || what == PICK_PSX) {
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
                if (err != null) return "Not imported: " + err + "." + ((err.startsWith("no ff7_en") || err.contains("was not found")) ? "\n\n" + GAMENATIVE_HINT : "");
                runOnUiThread(this::startBuild);
                return null;
            }
            if (req == PICK_PSX) return importPsx(data);
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

    // ------------------------------------------------------------------- Steam achievements
    private SteamLink steam;

    private SteamLink steam() {
        if (steam == null) steam = new SteamLink(this);
        return steam;
    }

    private void steamSection(LinearLayout col) {
        SteamLink s = steam();
        if (!s.signedIn()) {
            col.addView(text("Earn FINAL FANTASY VII's Steam achievements here, on your Steam account (the Steam "
                    + "release you built the game from). Sign in once by scanning a QR code with the Steam app on your "
                    + "phone: you never type your Steam password into FF7Recomp. Unlocks made offline are sent the next "
                    + "time you are online.", 14, DIM));
            col.addView(space(10));
            col.addView(button("Sign in with the Steam app…", v -> steamSignIn()));
            return;
        }
        int[] c = s.counts();
        col.addView(text("Signed in as " + s.account() + (c != null ? "  ·  " + c[0] + " of " + c[1] + " unlocked on Steam" : ""), 16, ACCENT));
        int w = s.waiting();
        if (w > 0) col.addView(text(w + " unlock(s) waiting to be sent to Steam", 13, DIM));
        if (c == null) col.addView(text("Your achievements have not been read from Steam yet: press Sync now while online.", 13, DIM));
        col.addView(space(8));
        LinearLayout r = row();
        r.addView(small("Sync now", v -> background(() -> {
            progress("Talking to Steam");
            java.util.List<String> got = steam().sync();
            return got.isEmpty() ? "Steam achievements are up to date." : "Sent to Steam: " + String.join(", ", got);
        })));
        r.addView(small("Sign out", v -> ff(new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setMessage("Sign out of Steam? Steam achievements stop until you sign in again.")
                .setPositiveButton("Sign out", (d, x) -> { steam().signOut(); refresh(); })
                .setNegativeButton("Cancel", null)
                .show())));
        col.addView(r);
    }

    private void steamSignIn() {
        LinearLayout box = column();
        box.setPadding(dp(16), dp(12), dp(16), dp(4));
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView how = text("On your phone: open the Steam app, tap the Steam Guard shield, then \"Scan a QR code\", "
                + "and approve the sign-in.", 15, TEXT);
        box.addView(how);
        ImageView qr = new ImageView(this);
        qr.setBackgroundColor(Color.WHITE);
        // The dialog's title, text, status line and Cancel button take about 220 dp. On a short
        // landscape screen (a 1080p handheld is 360-450 dp tall) a fixed 260 dp code pushed the
        // bottom of the code off the dialog (#19), so the code shrinks to fit the screen's height.
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        int size = Math.max(dp(150), Math.min(dp(260), Math.min(dm.widthPixels, dm.heightPixels) - dp(220)));
        box.addView(qr, new LinearLayout.LayoutParams(size, size));
        TextView st = text("Connecting to Steam…", 13, DIM);
        box.addView(st);
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);   // never cut off
        scroll.addView(box);
        final java.util.concurrent.Future<?>[] cancel = new java.util.concurrent.Future<?>[1];
        final boolean[] open = { true };
        AlertDialog dlg = ff(new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Sign in to Steam")
                .setView(scroll)
                .setNegativeButton("Cancel", null)
                .show());
        dlg.setOnDismissListener(d -> {
            open[0] = false;
            if (cancel[0] != null) cancel[0].cancel(true);
        });
        new Thread(() -> steam().signInQr(new SteamLink.QrListener() {
            @Override public void qr(String url) {
                android.graphics.Bitmap bm = qrBitmap(url, size);
                runOnUiThread(() -> { qr.setImageBitmap(bm); st.setText("Waiting for the Steam app…"); });
            }
            @Override public void done(String error) {
                runOnUiThread(() -> {
                    if (open[0]) dlg.dismiss();
                    refresh();
                    if (error == null) {
                        int[] c = steam().counts();
                        toastDialog("Signed in to Steam as " + steam().account() + "."
                                + (c != null ? " " + c[0] + " of " + c[1] + " FINAL FANTASY VII achievements unlocked." : ""));
                    } else if (!error.equals("cancelled")) {
                        toastDialog(error.startsWith("signed in") ? "S" + error.substring(1) : "Not signed in: " + error);
                    }
                });
            }
        }, cancel), "steam-sign-in").start();
    }

    private static android.graphics.Bitmap qrBitmap(String text, int size) {
        try {
            com.google.zxing.common.BitMatrix m = new com.google.zxing.qrcode.QRCodeWriter()
                    .encode(text, com.google.zxing.BarcodeFormat.QR_CODE, size, size);
            int[] px = new int[size * size];
            for (int y = 0; y < size; y++)
                for (int x = 0; x < size; x++) px[y * size + x] = m.get(x, y) ? Color.BLACK : Color.WHITE;
            return android.graphics.Bitmap.createBitmap(px, size, size, android.graphics.Bitmap.Config.ARGB_8888);
        } catch (Exception e) {
            return null;
        }
    }

    /** PlayStation memory cards picked on the Save states page, each into its own GAME file. */
    private String importPsx(Intent data) throws java.io.IOException {
        java.util.List<Uri> uris = new java.util.ArrayList<>();
        if (data.getClipData() != null)
            for (int k = 0; k < data.getClipData().getItemCount(); k++) uris.add(data.getClipData().getItemAt(k).getUri());
        else if (data.getData() != null) uris.add(data.getData());
        StringBuilder msg = new StringBuilder();
        for (Uri u : uris) {
            progress("Reading the memory card");
            byte[] b;
            try (java.io.InputStream in = getContentResolver().openInputStream(u)) {
                if (in == null) throw new java.io.IOException("cannot open the file");
                java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[65536];
                for (int n; (n = in.read(buf)) > 0; ) {
                    o.write(buf, 0, n);
                    if (o.size() > (1 << 20)) throw new java.io.IOException("this file is too big to be a memory card");
                }
                b = o.toByteArray();
            }
            try {
                File saveDir = new File(install.game, "save");
                PsxSaves.Result r = PsxSaves.isPcSave(b) ? PsxSaves.importPc(b, saveDir) : PsxSaves.importCard(b, saveDir);
                String game = "Save " + r.gameNumber;
                msg.append(r.saves).append(r.saves == 1 ? " save" : " saves").append(" imported into ").append(game)
                   .append(". In game: Continue, then pick ").append(game).append(".\n");
            } catch (java.io.IOException e) {
                msg.append("Not imported: ").append(e.getMessage()).append("\n");
            }
        }
        return msg.toString().trim();
    }

    // ------------------------------------------------------------------- save states
    /** The achievements the game tracks (src/ffnx_port/achievements.cpp), offline for now. */
    private View achievementsPage() {
        Achievements ach = new Achievements(install.files);
        LinearLayout col = column();
        col.addView(heading("Steam achievements"));
        steamSection(col);
        col.addView(space(18));
        col.addView(heading("Achievements"));
        col.addView(text("Unlocked while you play; a notice shows in game. This is a local set, kept on this device: "
                + "RetroAchievements accounts and their official set need RetroAchievements to approve this port first.", 14, DIM));
        if (!ach.haveSet) {
            col.addView(text("\nStart the game once to set them up.", 16, DIM));
            return scroll(col);
        }
        col.addView(text(ach.unlockedCount() + " of " + ach.list.size() + " unlocked  ·  " + ach.points(true) + " of "
                + ach.points(false) + " points", 16, ACCENT));
        java.text.DateFormat df = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT);
        for (Achievements.Ach a : ach.list) {
            LinearLayout r = column();
            r.setPadding(0, dp(8), 0, dp(8));
            boolean on = a.unlockedAt != 0;
            r.addView(text((on ? "★ " : "☆ ") + a.title + "  (" + a.points + ")", 18, on ? TEXT : DIM));
            r.addView(text(a.description + (on ? "  ·  unlocked " + df.format(new java.util.Date(a.unlockedAt * 1000)) : ""), 13, DIM));
            col.addView(r);
        }
        return scroll(col);
    }

    private View statesPage() {
        if (states == null) states = new SaveStates(install.files);
        LinearLayout col = column();
        col.addView(heading("Save states"));
        col.addView(text("In game: Select+L1 saves to the current slot, Select+R1 loads it, Select+Left / Right "
                + "picks another slot. \"Play from here\" starts the game at that moment.", 14, DIM));
        col.addView(space(10));
        col.addView(button("Import saves (PC or PlayStation)…", v -> pick(PICK_PSX)));
        col.addView(text("PC: a save file (save00.ff7…save09.ff7) from the 2013 Steam release, the original PC "
                + "release or a 7th Heaven install; on a PC the Steam release keeps them in Documents\\Square Enix\\"
                + "FINAL FANTASY VII Steam\\user_…. PlayStation: a memory card from DuckStation (.mcd), ePSXe (.mcr), "
                + "RetroArch (.srm), DexDrive (.gme) or PSP (.vmp); DuckStation keeps its cards in "
                + "Android/data/com.github.stenzek.duckstation/files/memcards. Copy the file somewhere you can pick it "
                + "(Download, for example) first. The saves go into a new save file (Save 2, Save 3… in the game), so "
                + "none of yours are overwritten. Saves from the current Steam release's own launcher are a different "
                + "format and are not supported yet.", 13, DIM));
        col.addView(space(10));
        int cur = states.current();
        for (int n = 1; n <= SaveStates.SLOTS; n++) {
            SaveStates.Slot sl = states.read(n);
            LinearLayout r = row();
            r.setPadding(0, dp(6), 0, dp(6));
            LinearLayout info = column();
            info.addView(text("Slot " + n + (n == cur ? "  (current)" : ""), 18, TEXT));
            String sub;
            if (!sl.exists) sub = "Empty";
            else if (!sl.readable) sub = "From an older version of the app (cannot be loaded)";
            else sub = (sl.location.isEmpty() ? "?" : sl.location) + "  ·  " + sl.playTime() + "  ·  saved " + sl.when;
            info.addView(text(sub, 13, DIM));
            r.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            final int k = n;
            if (sl.readable && install.hasGame()) r.addView(small("Play from here", v -> playFrom(k)));
            if (n != cur) r.addView(small("Make current", v -> {
                try { states.setCurrent(k); } catch (Exception e) { toastDialog("Could not set the slot: " + e); }
                refresh();
            }));
            if (sl.exists) r.addView(small("Delete", v -> ff(new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setMessage("Delete save state slot " + k + "? This cannot be undone.")
                    .setPositiveButton("Delete", (d, w) -> { states.delete(k); refresh(); })
                    .setNegativeButton("Cancel", null)
                    .show())));
            col.addView(r);
        }
        return scroll(col);
    }

    /** Play, resuming save-state slot n (this launch only; the Start setting is unchanged). */
    private void playFrom(int n) {
        play("resume " + n);
    }

    private void play() { play(null); }

    private void play(String quickbootOverride) {
        if (busy || !install.hasGame()) return;
        if (!ready()) { show("game"); return; }
        background(() -> {
            progress("Preparing");
            String missing = install.missingMessage();
            if (missing != null) return missing;
            library.load();
            install.gameLanguage = prefs.getInt("game_language", 1);
            library.language = install.hasLanguage(install.gameLanguage) ? GameInstall.languageCode(install.gameLanguage) : "en";
            library.save();
            install.installRuntime();
            install.fixFontWidths();   // the current release's narrow m/w widths (GameInstall)
            boolean steamOn = false;
            if (steam().signedIn()) {
                progress("Checking Steam achievements");
                try { steam().sync(); } catch (Exception e) { android.util.Log.w("FF7Steam", "offline: " + SteamLink.message(e)); }
                steamOn = steam().active();
            }
            install.steamAchievements = steamOn;
            install.crtFilter = prefs.getBoolean("crt_filter", false);
            install.analogControls = prefs.getBoolean("analog_controls", false);
            install.resolutionScale = prefs.getInt("resolution", 0);
            install.openGl = prefs.getInt("renderer", 0) == 1;
            int aspect = prefs.getInt("aspect", 2);   // 3: 16:9 stretched by Android (GameActivity), FFNx renders 16:9
            install.writeFfnxToml(prefs.getInt("fps", 3), boosterSpeed() > 1, aspect == 3 ? 2 : aspect, prefs.getBoolean("show_fps", false), library.mods,
                                  renderScaleCap());
            install.writeQuickboot(quickbootOverride != null ? quickbootOverride : prefs.getString("quickboot", ""));
            install.writeBoosters(boosterSpeed(), prefs.getBoolean("no_encounters", false), prefs.getBoolean("battle_plus", false),
                                  prefs.getInt("perf", 1) != 2, prefs.getBoolean("save_anywhere", false),
                                  prefs.getInt("gil", 1), prefs.getInt("exp", 1), prefs.getInt("ap", 1),
                                  prefs.getBoolean("autofire", false), prefs.getInt("rumble", 0));
            install.writeCompanion("off");   // battle windows stay on the top screen
            runOnUiThread(() -> startActivity(new Intent(this, GameActivity.class)
                    .putExtra("touch_controls", prefs.getInt("touch_controls", 0))
                    .putExtra("touch_layout", prefs.getString("touch_layout", ""))
                    .putExtra("portrait", prefs.getBoolean("portrait", false))
                    .putExtra("bottom_screen", prefs.getBoolean("bottom_screen", true))
                    .putExtra("bottom_buttons", prefs.getBoolean("bottom_buttons", true))
                    .putExtra("crt_filter", prefs.getBoolean("crt_filter", false))
                    .putExtra("aspect", prefs.getInt("aspect", 2))));
            return null;
        });
    }

    /** The start speed (1-3); older settings had only the 2x "turbo" switch. */
    /** Performance's render scale cap: the 640x480 multiple nearest the screen (Balanced; 2 on
     *  a 1080p Thor, 2 on a 720p device, 3 at 1440p), one less (Cooler), none (Best). Rounding
     *  up drew 2560x1440 for the Thor's 1080p screen: 1.8x the pixels it shows (GPU 71% busy
     *  in the field at 120 FPS with ChaOS). */
    private int renderScaleCap() {
        int perf = prefs.getInt("perf", 1);
        if (perf == 2) return 0;
        android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(dm);
        int shortSide = Math.min(dm.widthPixels, dm.heightPixels);
        int match = Math.max(1, (shortSide + 240) / 480);
        return perf == 0 ? Math.max(1, match - 1) : match;
    }

    private int boosterSpeed() {
        return prefs.getInt("speed", prefs.getBoolean("turbo", false) ? 2 : 1);
    }

    private void saveProfile() {
        try { library.save(); } catch (Exception e) { toastDialog("Could not save the mod list: " + e); }
    }

    /** Settings > Storage: move everything to the SD card or back, then restart the launcher. */
    private void moveStorage(boolean toSd) {
        if (busy) return;
        if (GameBuild.running) { toastDialog("Not moved: the game is being built. Wait for the build to finish."); return; }
        if (sQueue != null && sQueue.pendingCount() > 0) { toastDialog("Not moved: mods are downloading. Wait for them, or cancel them, first."); return; }
        android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
        if (am != null && am.getRunningAppProcesses() != null)
            for (android.app.ActivityManager.RunningAppProcessInfo pi : am.getRunningAppProcesses())
                if (pi.processName.endsWith(":game")) { toastDialog("Not moved: close the game first."); return; }
        long size = Storage.size(install.files);
        ff(new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setMessage("Move your game files, mods, saves and save states (" + ModDownloader.human(size) + ") to "
                        + (toSd ? "the SD card" : "internal storage") + "? The launcher restarts when it is done.")
                .setPositiveButton("Move", (d, w) -> background(() -> {
                    Storage.move(this, toSd, this::progress);
                    runOnUiThread(() -> {
                        startActivity(Intent.makeRestartActivityTask(new android.content.ComponentName(this, LauncherActivity.class)));
                        Runtime.getRuntime().exit(0);   // every per-process object points at the old folder
                    });
                    return null;
                }))
                .setNegativeButton("Cancel", null)
                .show());
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
        if (msg.startsWith("Failed") || msg.startsWith("Not ")) sound(Sfx.ERROR);
        ff(new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setMessage(msg).setPositiveButton("OK", null).show());
    }

    /** The pad: A confirms, B goes back, Start plays (D-pad focus navigation is Android's). */
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            if (isNav(code)) lastNavKey = System.currentTimeMillis();
            else if ((code == KeyEvent.KEYCODE_BUTTON_B || code == KeyEvent.KEYCODE_BACK) && e.getRepeatCount() == 0) sound(Sfx.CANCEL);
        }
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

    private static boolean isNav(int code) {
        return code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
                || code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_TAB;
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
        shadow(t);
        return t;
    }

    /** FF7's menu text: a hard drop shadow down and to the right. */
    private void shadow(TextView t) {
        float d = getResources().getDisplayMetrics().density;
        t.setShadowLayer(1.2f * d, 1.4f * d, 1.4f * d, 0xE0000000);
    }

    /** A window: diagonal blue gradient, light outer border, darker inner line. */
    private android.graphics.drawable.Drawable window(int top, int bottom, int cornerDp) {
        GradientDrawable fill = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[] { top, bottom });
        fill.setCornerRadius(dp(cornerDp));
        fill.setStroke(dp(2), 0xFFE2E6F0);
        GradientDrawable inner = new GradientDrawable();
        inner.setColor(0);
        inner.setCornerRadius(Math.max(0, dp(cornerDp) - dp(2)));
        inner.setStroke(Math.max(1, dp(1)), 0xFF4C5576);
        android.graphics.drawable.LayerDrawable l = new android.graphics.drawable.LayerDrawable(
                new android.graphics.drawable.Drawable[] { fill, inner });
        l.setLayerInset(1, dp(2), dp(2), dp(2), dp(2));
        return l;
    }

    /** A command in the menu window: plain text, the glove cursor points at it. which: the page
     *  it shows, or null for Play. */
    private Button menuItem(String label, String which) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(16);
        b.setTextColor(new android.content.res.ColorStateList(
                new int[][] { { -android.R.attr.state_enabled }, {} }, new int[] { 0xFF7F88AE, TEXT }));
        shadow(b);
        b.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        b.setBackground(null);
        b.setPadding(dp(4), 0, dp(4), 0);
        b.setCompoundDrawablesRelativeWithIntrinsicBounds(new HandCursor(getResources().getDisplayMetrics().density), null, null, null);
        b.setCompoundDrawablePadding(dp(6));
        b.setStateListAnimator(null);
        b.setFocusable(true);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setOnClickListener(v -> { if (which == null) play(); else show(which); });
        b.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(which == null ? 42 : 34)));
        if (which != null) nav.put(which, b);
        return b;
    }

    /** Checkboxes, radio buttons and text fields inside v, in the window colours. */
    private void theme(View v) {
        if (v.isClickable() || v instanceof Button) {
            v.setSoundEffectsEnabled(false);
            v.setAccessibilityDelegate(clickSound);
        }
        if (v instanceof android.widget.AbsListView)     // dialog lists make their rows later
            ((ViewGroup) v).setOnHierarchyChangeListener(new ViewGroup.OnHierarchyChangeListener() {
                @Override public void onChildViewAdded(View parent, View child) { theme(child); }
                @Override public void onChildViewRemoved(View parent, View child) { }
            });
        if (v instanceof CheckBox || v instanceof RadioButton) {
            android.widget.CompoundButton c = (android.widget.CompoundButton) v;
            c.setButtonTintList(new android.content.res.ColorStateList(
                    new int[][] { { android.R.attr.state_checked }, {} }, new int[] { ACCENT, 0xFFE2E6F0 }));
            c.setTextColor(TEXT);
            shadow(c);
        } else if (v instanceof EditText) {
            EditText e = (EditText) v;
            e.setTextColor(TEXT);
            e.setHintTextColor(DIM);
            e.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFFE2E6F0));
        } else if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) theme(g.getChildAt(i));
        }
    }

    /** A dialog in a window like the rest of the launcher. */
    private AlertDialog ff(AlertDialog d) {
        if (d.getWindow() != null) {
            android.graphics.drawable.InsetDrawable bg = new android.graphics.drawable.InsetDrawable(window(WIN_TOP, WIN_BOTTOM, 10), dp(16));
            d.getWindow().setBackgroundDrawable(bg);
            theme(d.getWindow().getDecorView());
            cursorOnFocus(d.getWindow().getDecorView());
        }
        d.setOnKeyListener((di, code, e) -> {
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
                if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_BUTTON_B) sound(Sfx.CANCEL);
                else if (isNav(code)) lastNavKey = System.currentTimeMillis();
            }
            return false;
        });
        for (int which : new int[] { AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL }) {
            Button b = d.getButton(which);
            if (b != null) { b.setTextColor(ACCENT); shadow(b); }
        }
        return d;
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
        b.setTextColor(new android.content.res.ColorStateList(
                new int[][] { { -android.R.attr.state_enabled }, {} }, new int[] { 0xFF8088A8, Color.WHITE }));
        shadow(b);
        b.setStateListAnimator(null);
        android.graphics.drawable.StateListDrawable sl = new android.graphics.drawable.StateListDrawable();
        sl.addState(new int[] { android.R.attr.state_focused }, window(HOT_TOP, HOT_BOTTOM, 6));
        sl.addState(new int[] { android.R.attr.state_pressed }, window(HOT_TOP, HOT_BOTTOM, 6));
        sl.addState(new int[] {}, window(BTN_TOP, BTN_BOTTOM, 6));
        b.setBackground(sl);
        b.setFocusable(true);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(4), dp(8), dp(4));
        b.setLayoutParams(lp);
        return b;
    }

    /** A row's button, as small as its label. */
    private Button compact(Button b) {
        b.setTextSize(14);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(14), dp(8), dp(14), dp(8));
        return b;
    }

    private Button small(String label, View.OnClickListener l) {
        Button b = button(label, l);
        b.setTextSize(15);
        return b;
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

    /** A Game boosters row: 1x / 2x / 5x / 10x, kept in the launcher setting `key`. */
    private void multiplierRow(LinearLayout col, String title, String key) {
        col.addView(text(title, 16, TEXT));
        RadioGroup gg = new RadioGroup(this);
        gg.setOrientation(LinearLayout.HORIZONTAL);
        int cur = prefs.getInt(key, 1);
        for (int gm : new int[] { 1, 2, 5, 10 }) {
            RadioButton rb = new RadioButton(this);
            rb.setText(gm + "×   ");
            rb.setTextColor(TEXT);
            rb.setTextSize(16);
            rb.setId(View.generateViewId());
            final int v = gm;
            rb.setOnClickListener(x -> prefs.edit().putInt(key, v).apply());
            gg.addView(rb);
            if (gm == cur) rb.setChecked(true);
        }
        col.addView(gg);
    }

    private ScrollView scroll(View child) {
        ScrollView s = new ScrollView(this);
        s.addView(child);
        return s;
    }

}
