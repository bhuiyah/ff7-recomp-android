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
    private static final int PICK_GAME = 1, PICK_IRO = 2, PICK_FOLDER = 3, PICK_PSX = 4, PICK_STORAGE = 5, PICK_LOSSLESS = 6;
    /** Settings > Storage > Custom folder sent the player to grant All files access: pick the folder when they are back. */
    private boolean wantStorageFolder;

    /** Back with All files access: a custom folder that only lacked it is usable again (restart
     *  into it), else pick the folder. */
    private void storageAccessBack() {
        wantStorageFolder = false;
        if (!Storage.allFilesAccess(this)) return;
        if (Storage.place(this) == Storage.CUSTOM && Storage.customProblem(this) == null) restartLauncher();
        else pick(PICK_STORAGE);
    }
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

    /** A small screen (under 600dp wide, such as a 640x480 handheld): the launcher is drawn a
     *  little smaller and its button rows wrap, so nothing is cut off on the right (GitHub #47). */
    private boolean smallScreen;
    private static final int SMALL_WIDTH_DP = 600, COMPACT_WIDTH_DP = 560;
    /** TestControl SCREEN: the launcher laid out as on another screen, in a corner of this one. */
    private int testW, testH;

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(base);
        android.util.DisplayMetrics dm = base.getResources().getDisplayMetrics();
        int wPx = Math.max(dm.widthPixels, dm.heightPixels), dpi = dm.densityDpi;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)x(\\d+)@(\\d+)")
                .matcher(base.getSharedPreferences("launcher", MODE_PRIVATE).getString(TestControl.TEST_SCREEN, ""));
        if (m.matches()) {
            testW = Integer.parseInt(m.group(1));
            testH = Integer.parseInt(m.group(2));
            wPx = Math.max(testW, testH);
            dpi = Integer.parseInt(m.group(3));
        }
        if (wPx * 160f / dpi >= SMALL_WIDTH_DP) {
            if (testW == 0) return;
        } else smallScreen = true;
        // a little more room: the screen as about 560dp wide (text 13sp is then still 15 pixels or more)
        int want = smallScreen ? Math.max(120, Math.round(wPx * 160f / COMPACT_WIDTH_DP)) : dpi;
        if (want < dpi || testW > 0) {
            android.content.res.Configuration o = new android.content.res.Configuration();
            o.densityDpi = Math.min(want, dpi);
            applyOverrideConfiguration(o);
        }
    }

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
        if (GameBuild.running || JobService.running != null) { toastDialog("Not now: the game is being built or downloaded. Update when it is done."); return; }
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
    private TextView jobLine;                      // Game files page, while JobService works
    private final android.os.Handler ticker = new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean wasBuilding, wasJob;
    private final Runnable buildTick = new Runnable() {
        @Override public void run() {
            if (updateLine != null && !updateLine.getText().toString().equals(updateText())) updateLine.setText(updateText());
            if (JobService.running != null) {
                if (jobLine != null && JobService.status != null && !jobLine.getText().toString().equals(JobService.status)
                        && !jobLine.getText().toString().equals("Pausing…")) jobLine.setText(JobService.status);
                if (!wasJob) { wasJob = true; refresh(); }
            } else if (wasJob) {
                wasJob = false;
                refresh();
                // how it ended, when it did not go on to the build and is not waiting for Continue
                String m = JobService.status;
                if (!GameBuild.running && JobService.pending(LauncherActivity.this) == null && m != null && !m.isEmpty()) {
                    toastDialog(m + ((m.contains("no ff7_en") || m.contains("was not found")) ? "\n\n" + GAMENATIVE_HINT : ""));
                    JobService.status = "";
                }
            }
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
        return install.hasGame() && build.built() && !GameBuild.running && JobService.running == null;
    }

    /** The process's catalog, downloader and download queue (made once; also by QueueService
     *  when its notification's Continue runs without the launcher). */
    static synchronized DownloadQueue queueFor(android.content.Context c) {
        if (sQueue == null) {
            final File files = new GameInstall(c).files;
            sCatalog = new ModCatalog(files);
            sCatalog.rebuild();
            sDownloader = new ModDownloader(files, new ModLibrary(files).dir);
            sQueue = new DownloadQueue(c, sCatalog, sDownloader, new File(files, "downloads"), (m, file, wasUpdate) -> {
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
        return sQueue;
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Reports.keepJavaCrashes(this);
        install = new GameInstall(this);
        build = new GameBuild(this);
        library = new ModLibrary(install.files);
        queueFor(this);
        catalog = sCatalog;
        downloader = sDownloader;
        queue = sQueue;
        prefs = getSharedPreferences("launcher", MODE_PRIVATE);
        if (Storage.sdMissing(this))
            new android.os.Handler(getMainLooper()).post(() -> toastDialog("Your game files are on the SD card, which is "
                    + "not inserted. Insert it and open FF7Recomp again (Settings > Storage moves them back to internal "
                    + "storage)."));
        String customProblem = Storage.customProblem(this);
        if (customProblem != null)
            new android.os.Handler(getMainLooper()).post(() -> toastDialog(customProblem + ". Until that is fixed the "
                    + "launcher uses the app's own folder in internal storage, so your game files, mods and saves are "
                    + "missing. Settings > Storage > Custom folder asks for the access again."));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(BG);
        int pad = smallScreen ? dp(8) : dp(24);
        root.setPadding(pad, pad, pad, pad);

        LinearLayout side = new LinearLayout(this);
        side.setOrientation(LinearLayout.VERTICAL);
        side.setPadding(0, 0, smallScreen ? dp(8) : dp(14), 0);
        TextView title = text("FINAL FANTASY VII", smallScreen ? 13 : 17, TEXT);
        title.setTypeface(Typeface.create("serif", Typeface.BOLD));
        title.setLetterSpacing(0.04f);
        title.setSingleLine(true);
        side.addView(title);
        side.addView(text("native ARM64 · FFNx · Vulkan", smallScreen ? 10 : 11, ACCENT));
        side.addView(space(smallScreen ? 4 : 10));
        // the command window, Play first (the cursor starts there)
        LinearLayout menu = column();
        menu.setBackground(window(WIN_TOP, WIN_BOTTOM, 8));
        menu.setPadding(dp(smallScreen ? 4 : 8), dp(6), dp(smallScreen ? 4 : 8), dp(6));
        playButton = menuItem("Play", null);
        playButton.setTextSize(smallScreen ? 16 : 19);
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
        menu.addView(menuItem("Report a problem", "report"));
        Button quit = menuItem("Quit", null);   // GitHub #42
        quit.setOnClickListener(v -> quitApp());
        menu.addView(quit);
        side.addView(menu, new LinearLayout.LayoutParams(dp(smallScreen ? 152 : 200), ViewGroup.LayoutParams.WRAP_CONTENT));
        // the menu scrolls when it is taller than the screen
        ScrollView sideScroll = new ScrollView(this);
        sideScroll.setVerticalScrollBarEnabled(false);
        sideScroll.addView(side);
        root.addView(sideScroll, new LinearLayout.LayoutParams(dp(smallScreen ? 160 : 214), ViewGroup.LayoutParams.MATCH_PARENT));

        // right: the info bar (FF7's one-line help window) over the page's window
        LinearLayout right = column();
        status = text("", smallScreen ? 12 : 14, TEXT);
        status.setBackground(window(WIN_TOP, WIN_BOTTOM, 8));
        status.setPadding(dp(smallScreen ? 10 : 16), dp(smallScreen ? 4 : 8), dp(smallScreen ? 10 : 16), dp(smallScreen ? 4 : 8));
        status.setMaxLines(2);
        status.setEllipsize(android.text.TextUtils.TruncateAt.END);
        right.addView(status, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        right.addView(space(smallScreen ? 6 : 10));
        content = new FrameLayout(this);
        content.setBackground(window(WIN_TOP, WIN_BOTTOM, 10));
        if (smallScreen) content.setPadding(dp(10), dp(8), dp(10), dp(8));
        else content.setPadding(dp(22), dp(18), dp(22), dp(18));
        right.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        root.addView(right, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        if (testW > 0) {   // TestControl SCREEN: that screen's size, in the top-left corner
            FrameLayout outer = new FrameLayout(this);
            outer.setBackgroundColor(0xFF303030);
            outer.addView(root, new FrameLayout.LayoutParams(Math.max(testW, testH), Math.min(testW, testH)));
            setContentView(outer);
        } else setContentView(root);
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
        if (wantStorageFolder) storageAccessBack();   // back from Android's All files access screen
        queue.setListener(this::queueChanged);
        loadSounds();
        refresh();
        checkUpdates(false);
        ticker.removeCallbacks(buildTick);
        ticker.post(buildTick);
        testPlay(getIntent(), 0);
        clearModCacheAfterGame();
        if (cloudAfterGame && !busy && cloudSaves() && TestControl.gamePid(this) <= 0) {
            cloudAfterGame = false;
            new Thread(() -> {
                String m = syncCloudSaves();
                runOnUiThread(() -> { refresh(); if (m != null) toastDialog(m); });
            }, "cloud-saves").start();
        }
        crashTip();
        Intent ti = getIntent();
        if (ti != null && ti.getBooleanExtra(TestControl.EXTRA_STEAM_QR, false)) {
            ti.removeExtra(TestControl.EXTRA_STEAM_QR);
            steamSignIn();   // layout check; Cancel leaves the current sign-in untouched
        }
    }

    /** Once after each crash (or close for memory) of the game in the last day: what helps, and Report a
     *  problem (GitHub #90: Mali's shader compiler failing on a phone low on free memory). */
    private void crashTip() {
        long t = Reports.lastGameCrash(this);
        if (t <= prefs.getLong("crash_tip_seen", 0) || System.currentTimeMillis() - t > 24 * 3600_000L
                || TestControl.gamePid(this) > 0) return;
        prefs.edit().putLong("crash_tip_seen", t).apply();
        String exit = Reports.lastGameExit(this);
        sound(Sfx.ERROR);
        ff(new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("The game closed unexpectedly")
                .setMessage((exit != null ? "It " + exit + ".\n\n" : "") + "Crashes like this are most often a device "
                        + "running low on memory, for example while a scene's graphics are prepared for the first time.\n\n"
                        + Reports.RECOMMENDED + "\n\nIf it keeps happening, Report a problem (before you play again) makes a "
                        + "zip with the crash record to attach to a GitHub issue.")
                .setPositiveButton("OK", null)
                .setNeutralButton("Report a problem", (d, w) -> show("report")).show());
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
        // only the cached mod info here: opening every archive (a large library on an SD card)
        // took longer than Android lets the UI thread wait (GitHub #57); the rest is read in
        // the background, then the page is rebuilt
        long t0 = android.os.SystemClock.uptimeMillis();
        if (!library.load(true)) warmMods();
        long ms = android.os.SystemClock.uptimeMillis() - t0;
        if (ms > 100) android.util.Log.w("FF7Recomp", "mod list took " + ms + " ms on the UI thread");
        catalog.prune(library.dir);
        updateStatus();
        playButton.setEnabled(ready() && !busy);
        // the same page rebuilt (a mod switched on or off, moved, a setting changed): the focused
        // view goes away with it, and Android put the controller cursor back on Play (GitHub #47)
        final String keepFocus = page.equals(shownPage) ? focusKey() : null;
        // and where it was on the screen: lines above it can change height (a conflict note
        // comes or goes), which moved the list under the cursor (GitHub #47)
        final int focusOffset = keepFocus == null ? Integer.MIN_VALUE : focusOffset();
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
            case "report": content.addView(reportPage()); break;
            case "catalog": content.addView(catalogPage()); break;
            default: content.addView(modsPage()); break;
        }
        for (java.util.Map.Entry<String, Button> e : nav.entrySet()) e.getValue().setSelected(e.getKey().equals(page));
        theme(content);
        final View refocused = keepFocus == null ? null : restoreFocus(keepFocus);
        scrollY = keepY;
        if (content.getChildCount() > 0 && content.getChildAt(0) instanceof ScrollView) {
            ScrollView sv = (ScrollView) content.getChildAt(0);
            sv.setOnScrollChangeListener((v, x, y, ox, oy) -> scrollY = y);
            // before the rebuilt page's first frame: a post() ran after it, so the list showed a
            // frame at the top and jumped back (a mod switched on or off, #32)
            if (keepY > 0 || refocused != null && focusOffset != Integer.MIN_VALUE) sv.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
                @Override public boolean onPreDraw() {
                    sv.getViewTreeObserver().removeOnPreDrawListener(this);
                    int y = refocused != null && focusOffset != Integer.MIN_VALUE ? topIn(refocused, sv) : Integer.MIN_VALUE;
                    sv.scrollTo(0, y != Integer.MIN_VALUE ? Math.max(0, y - focusOffset) : keepY);
                    if (refocused != null && refocused.getWidth() > 0)   // a moved mod stays in view
                        refocused.requestRectangleOnScreen(new android.graphics.Rect(0, 0, refocused.getWidth(), refocused.getHeight()), true);
                    return true;
                }
            });
        }
    }

    private String shownPage;
    private int scrollY;

    /** The focused view's top, from the top of the page's visible part (refresh() keeps it). */
    private int focusOffset() {
        View f = getCurrentFocus();
        if (f == null || content.getChildCount() == 0 || !(content.getChildAt(0) instanceof ScrollView)) return Integer.MIN_VALUE;
        ScrollView sv = (ScrollView) content.getChildAt(0);
        int y = topIn(f, sv);
        return y == Integer.MIN_VALUE ? y : y - sv.getScrollY();
    }

    /** v's top inside the scroll view's content, or MIN_VALUE when v is not in it. */
    private static int topIn(View v, ScrollView sv) {
        int y = 0;
        for (View c = v; c != sv; ) {
            y += c.getTop();
            if (!(c.getParent() instanceof View)) return Integer.MIN_VALUE;
            c = (View) c.getParent();
        }
        return y;
    }

    /** Where the focus is inside the page: a view's "focus:" tag, else its child-index path. */
    private String focusKey() {
        View f = getCurrentFocus();
        if (f == null) return null;
        StringBuilder path = new StringBuilder();
        for (View v = f; v != content; ) {
            if (v.getTag() instanceof String && ((String) v.getTag()).startsWith("focus:") && path.length() == 0)
                return (String) v.getTag();
            if (!(v.getParent() instanceof ViewGroup)) return null;   // not in the page
            ViewGroup g = (ViewGroup) v.getParent();
            path.insert(0, "/" + g.indexOfChild(v));
            v = g;
        }
        return path.toString();
    }

    /** Focus the rebuilt page's view for a focusKey(); else its first focusable, not Play. */
    private View restoreFocus(String key) {
        View t = null;
        if (key.startsWith("focus:")) t = content.findViewWithTag(key);
        else {
            t = content;
            for (String part : key.substring(1).split("/")) {
                int i;
                try { i = Integer.parseInt(part); } catch (NumberFormatException e) { t = null; break; }
                if (!(t instanceof ViewGroup) || i >= ((ViewGroup) t).getChildCount()) { t = null; break; }
                t = ((ViewGroup) t).getChildAt(i);
            }
        }
        if (t != null && t.isFocusable() && t.isEnabled() && t.requestFocus()) return t;
        content.requestFocus();
        return null;
    }

    /** Mods, laid out like FF9Recomp's (the user's wish): a note, one row of buttons, then a row
     *  per mod with its on/off box, its place in the load order and small buttons. The mods that
     *  are on come first, in load order, then the ones that are off. */
    private View modsPage() {
        LinearLayout col = column();
        col.addView(heading("Mods"));
        TextView how = text("FF7Recomp loads the mods that are on, top first: when two change the same file, the higher one wins. "
                + "▲▼ change the order. Get mods finds more; Add a mod file takes a .iro, .zip or .7z from Nexus Mods or another site.", 13, DIM);
        how.setPadding(0, dp(2), 0, dp(4));
        col.addView(how);
        ViewGroup actions = buttonRow();
        actions.addView(button("Get mods…", v -> show("catalog")));
        actions.addView(button("Add a mod file", v -> pick(PICK_IRO)));
        actions.addView(button("Add folder", v -> pick(PICK_FOLDER)));
        if (!library.mods.isEmpty()) {
            // every mod on or off in one go (GitHub #47)
            actions.addView(button("All on", v -> allMods(true)));
            actions.addView(button("All off", v -> allMods(false)));
        }
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
        java.util.List<ModCatalog.Mod> ups = catalog.updates();
        if (!ups.isEmpty()) actions.addView(button("Update all (" + ups.size() + ")", v -> installMods(ups, true)));
        col.addView(actions);
        // what the enabled mods share (computed in the background: archives are read)
        ensureConflicts();
        if (library.enabledCount() > 1) {
            LinearLayout box = column();
            box.setPadding(0, dp(6), 0, dp(4));
            ConflictChecker.Report r = conflictReport;
            // while it is checked again the last answer stays, so the list below does not move
            // up and down (GitHub #47)
            if (r == null || r.lines.isEmpty() && conflictBusy) box.addView(text("Checking which files the enabled mods share…", 14, DIM));
            if (r != null && r.lines.isEmpty() && !conflictBusy) box.addView(text("✓ No conflicts: the enabled mods replace different game files.", 14, 0xFF80D090));
            if (r != null && !r.lines.isEmpty()) {
                String head = (r.problems > 0 ? "⚠ " + r.problems + (r.problems == 1 ? " problem" : " problems") : "")
                        + (r.problems > 0 && r.overlaps > 0 ? ", " : "")
                        + (r.overlaps > 0 ? r.overlaps + (r.overlaps == 1 ? " overlap" : " overlaps") + " between mods" : "")
                        + (conflictBusy ? "  (checking…)" : "");
                box.addView(text(head, 15, r.problems > 0 ? 0xFFFFC060 : 0xFFE0C080));
                for (int i = 0; i < Math.min(2, r.lines.size()); i++) box.addView(text(r.lines.get(i).text, 13, DIM));
                ViewGroup b = buttonRow();
                b.addView(small("All details", v -> showConflicts()));
                if (r.interfaceBelow) b.addView(small("Put interface mods first", v -> {
                    if (library.interfaceFirst()) { saveProfile(); ensureConflicts(); refresh(); }
                }));
                box.addView(b);
            }
            col.addView(box);
        }
        if (library.mods.isEmpty()) col.addView(text("No mods installed yet. Get mods lists 7th Heaven's catalogs; Add a mod file takes one you downloaded.", 16, DIM));
        java.util.List<ModLibrary.Mod> on = new java.util.ArrayList<>(), off = new java.util.ArrayList<>();
        for (ModLibrary.Mod m : library.mods) (m.enabled && m.error == null ? on : off).add(m);
        for (int i = 0; i < on.size(); i++) col.addView(modRow(on.get(i), i, on.size()));
        for (ModLibrary.Mod m : off) col.addView(modRow(m, -1, on.size()));
        return scroll(col);
    }

    /** A mod: its on/off box (load-order number, name, a dim note), ▲▼ while it is on, then
     *  Options, Update, Info and Remove; warnings, if any, on a line under it (which files it
     *  shares with other mods is under All details). */
    private View modRow(ModLibrary.Mod m, int pos, int onCount) {
        ModCatalog.Installed fromCat = catalog.installedByFile(m.file);
        ModCatalog.Mod catMod = fromCat == null ? null : catalog.byId(fromCat.id);
        boolean update = catMod != null && catalog.updateAvailable(catMod);
        StringBuilder note = new StringBuilder();
        String ver = fromCat != null && !fromCat.versionText.isEmpty() ? fromCat.versionText : m.version;
        if (ver != null && !ver.isEmpty()) note.append(ver.matches("(?i)v.*") ? ver : "v" + ver);
        if (update) note.append(note.length() > 0 ? ", " : "").append("update ").append(catMod.versionText);
        if (!m.options.isEmpty()) note.append(note.length() > 0 ? ", " : "").append(m.options.size()).append(m.options.size() == 1 ? " option" : " options");
        if (catMod == null) note.append(note.length() > 0 ? ", " : "").append("added from a file");

        LinearLayout whole = column();
        whole.setPadding(0, dp(2), 0, dp(2));
        // the buttons wrap onto a second line if even their own line is too narrow
        ViewGroup r = new FlowRow(this);
        CheckBox cb = new CheckBox(this);
        cb.setTextColor(TEXT);
        cb.setTextSize(smallScreen ? 15 : 17);
        cb.setText(withNote((pos >= 0 ? (pos + 1) + ".  " : "") + m.title(), note.toString()));
        cb.setChecked(pos >= 0);
        cb.setEnabled(m.error == null);
        cb.setOnCheckedChangeListener((b, isOn) -> { m.enabled = isOn; saveProfile(); ensureConflicts(); refresh(); });
        cb.setTag("focus:mod:" + m.file);   // the cursor stays on it when the page is rebuilt (#47)
        if (pos >= 0) {
            Button up = rowButton("▲", v -> moveOn(m, -1));
            up.setTag("focus:up:" + m.file);     // follows the mod to its new place (#47)
            up.setEnabled(pos > 0);
            r.addView(up);
            Button down = rowButton("▼", v -> moveOn(m, +1));
            down.setTag("focus:down:" + m.file);
            down.setEnabled(pos < onCount - 1);
            r.addView(down);
        }
        if (!m.options.isEmpty()) r.addView(rowButton("Options", v -> options(m)));
        if (update && queue.pending(catMod) == null) r.addView(rowButton("Update", v -> installMods(java.util.Collections.singletonList(catMod), true)));
        if (catMod != null) {
            Button info = rowButton("Info", v -> modPage(catMod));
            info.setTag("focus:info:" + m.file);
            r.addView(info);
        }
        // any mod can be removed here, also one added from a file or folder (asked on Reddit)
        Button rm = rowButton("Remove", v -> removeMod(m, fromCat, catMod));
        rm.setTag("focus:remove:" + m.file);
        r.addView(rm);
        // the name, then its buttons beside it; under it when they would leave the name too
        // little width (a 1240x1080 screen gave it a few pixels, GitHub #58)
        View line = new NameAndButtons(this, cb, r, dp(200), dp(8));
        line.setTag("modrow:" + m.file);
        whole.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // only what needs attention goes under the row
        StringBuilder warn = new StringBuilder();
        if (m.error != null) warn.append("Cannot read: ").append(m.error);
        else {
            for (String u : m.unsupported) warn.append(warn.length() > 0 ? "\n" : "").append("⚠ ").append(u).append(" (Windows code, skipped)");
            String[] inside = new File(library.dir, m.file).list();
            if (inside != null && inside.length == 0) warn.append(warn.length() > 0 ? "\n" : "").append("⚠ This folder is empty, so it changes nothing. Remove it.");
            if (m.hext != null && m.hextProblem) warn.append(warn.length() > 0 ? "\n" : "").append("⚠ ").append(m.hext);
        }
        if (warn.length() > 0) {
            TextView w = text(warn.toString(), 13, m.error != null ? 0xFFFF8080 : 0xFFE0C080);
            w.setPadding(dp(smallScreen ? 32 : 40), 0, 0, dp(4));
            whole.addView(w);
        }
        whole.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return whole;
    }

    /** ▲▼ on the Mods page: m trades places with the mod that is on above or below it. */
    private void moveOn(ModLibrary.Mod m, int dir) {
        java.util.List<ModLibrary.Mod> mods = library.mods;
        int i = mods.indexOf(m);
        if (i < 0) return;
        for (int j = i + dir; j >= 0 && j < mods.size(); j += dir) {
            ModLibrary.Mod o = mods.get(j);
            if (!o.enabled || o.error != null) continue;
            mods.set(j, m);
            mods.set(i, o);
            saveProfile();
            ensureConflicts();
            refresh();
            return;
        }
    }

    /** A small button only as wide as its label (FF9Recomp's size): five of them share a mod's
     *  row with its name. */
    private Button rowButton(String label, View.OnClickListener l) {
        Button b = button(label, l);
        b.setTextSize(14);
        b.setPadding(dp(10), 0, dp(10), 0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(dp(34));
        b.setMinimumHeight(dp(34));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36));
        lp.setMarginStart(dp(6));
        b.setLayoutParams(lp);
        return b;
    }

    /** A label with a smaller, dim note after it (as in FF9Recomp). */
    private CharSequence withNote(String label, String note) {
        if (note == null || note.isEmpty()) return label;
        android.text.SpannableStringBuilder b = new android.text.SpannableStringBuilder(label).append("   ");
        int at = b.length();
        b.append(note);
        b.setSpan(new android.text.style.ForegroundColorSpan(DIM), at, b.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        b.setSpan(new android.text.style.RelativeSizeSpan(0.82f), at, b.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return b;
    }

    /** Remove a mod from the Mods page: the launcher's copy (its file or folder in the mods
     *  folder) and its cache are deleted; the file or folder it was added from is not touched. */
    private void removeMod(ModLibrary.Mod m, ModCatalog.Installed fromCat, ModCatalog.Mod catMod) {
        if (m.file == null || m.file.isEmpty() || m.file.contains("/") || m.file.equals(".") || m.file.equals("..")) return;
        if (catMod != null && queue.pending(catMod) != null) { toastDialog(m.title() + " is in Downloads; cancel it there first."); return; }
        ff(new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setMessage("Remove " + m.title() + "?\n\nThe launcher's copy (" + m.file + ") is deleted from the device. "
                        + "A file or folder you added it from is not touched.")
                .setPositiveButton("Remove", (x, w) -> {
                    ModDownloader.deleteTree(new File(library.dir, m.file));
                    ModDownloader.deleteTree(new File(modCache(), cacheName(m.file)));
                    if (fromCat != null) catalog.forget(fromCat.id);
                    library.load();
                    saveProfile();
                    ensureConflicts();
                    refresh();
                })
                .setNegativeButton("Cancel", null)
                .show());
    }

    /** All on / All off (GitHub #47): every mod that can be read. */
    private void allMods(boolean on) {
        boolean changed = false;
        for (ModLibrary.Mod m : library.mods)
            if (m.error == null && m.enabled != on) { m.enabled = on; changed = true; }
        if (changed) { saveProfile(); ensureConflicts(); refresh(); }
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
        q.setTag("focus:search");     // a rebuilt page keeps the cursor in the box
        q.setSelection(catQuery.length());
        // the list follows what is typed: some keyboards have no Enter key (GitHub #47)
        q.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence c, int a, int b, int n) { }
            @Override public void onTextChanged(CharSequence c, int a, int b, int n) { }
            @Override public void afterTextChanged(android.text.Editable e) {
                catQuery = e.toString();
                ticker.removeCallbacks(searchNow);
                ticker.postDelayed(searchNow, 300);
            }
        });
        q.setOnEditorActionListener((v, id, ev) -> {
            catQuery = v.getText().toString();
            ticker.removeCallbacks(searchNow);
            searchNow.run();
            ((android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(v.getWindowToken(), 0);
            return true;
        });
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
        // picking a category rebuilds the page: keep the category list where it was, not back
        // at the top (GitHub #54)
        final int keepCatY = catScrollY;
        catScroll.setOnScrollChangeListener((v, x, y, ox, oy) -> catScrollY = y);
        if (keepCatY > 0) catScroll.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                catScroll.getViewTreeObserver().removeOnPreDrawListener(this);
                catScroll.scrollTo(0, keepCatY);
                return true;
            }
        });
        split.addView(catScroll, new LinearLayout.LayoutParams(dp(smallScreen ? 104 : 132), ViewGroup.LayoutParams.MATCH_PARENT));
        View vline = new View(this);
        vline.setBackgroundColor(0x40FFFFFF);
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(Math.max(1, dp(1)), ViewGroup.LayoutParams.MATCH_PARENT);
        vlp.setMargins(dp(smallScreen ? 4 : 8), 0, dp(smallScreen ? 6 : 12), 0);
        split.addView(vline, vlp);

        catList = column();
        fillCatalogList();
        split.addView(scroll(catList), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        page.addView(split, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        return page;
    }

    /** Get mods: the list the search and the category pick out (filled again as you type). */
    private LinearLayout catList;
    private int catScrollY;   // the Get mods category list's scroll position (GitHub #54)
    private final Runnable searchNow = () -> {
        if (!"catalog".equals(page) || catList == null) return;
        fillCatalogList();
        theme(catList);
    };

    private void fillCatalogList() {
        LinearLayout list = catList;
        list.removeAllViews();
        actionSlots.clear();
        int total = catalog.mods.size();
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
        if (list.getParent() instanceof ScrollView) ((ScrollView) list.getParent()).scrollTo(0, 0);
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
        LinearLayout slot = row();
        slot.setGravity(Gravity.CENTER_VERTICAL | (smallScreen ? Gravity.START : Gravity.END));
        if (smallScreen) slot.setPadding(dp(8), 0, 0, 0);
        fillAction(slot, m);
        actionSlots.put(m.id, slot);
        LinearLayout r = details(info, slot);
        r.setPadding(0, dp(smallScreen ? 4 : 6), 0, dp(smallScreen ? 4 : 6));
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
                + "a mod that finishes while the game runs is used from the next Play. Downloads go on with the "
                + "launcher in the background; Pause stops them and keeps what is downloaded.", 14, DIM));
        if (queue.paused() && queue.pendingCount() > 0)
            col.addView(text("Paused. Continue goes on where the downloads stopped.", 15, ACCENT));
        ViewGroup bar = buttonRow();
        bar.addView(small("Get mods…", v -> show("catalog")));
        // GitHub #53 (and FF9Recomp): stop for now, keeping the partial download
        if (queue.paused() && queue.pendingCount() > 0) bar.addView(small("Continue", v -> { askNotifications(); queue.resume(); refresh(); }));
        else if (queue.pendingCount() > 0) bar.addView(small("Pause", v -> { queue.pause(); refresh(); }));
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
        ViewGroup bs = buttonRow();
        LinearLayout info = column();
        TextView name = text(q.mod.name + (q.update ? "  (update to " + q.mod.versionText + ")" : ""), smallScreen ? 16 : 18, TEXT);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        info.addView(name);
        boolean bad = q.state == DownloadQueue.State.FAILED;
        TextView line = text(queueLabel(q, true) + (q.state == DownloadQueue.State.QUEUED && !q.mod.sizeText().isEmpty() ? "  ·  " + q.mod.sizeText() : ""),
                14, bad ? 0xFFFF8080 : q.state == DownloadQueue.State.DONE ? ACCENT : DIM);
        info.addView(line);
        switch (q.state) {
            case QUEUED:
                bs.addView(small("▲", v -> queue.move(q, -1)));
                bs.addView(small("▼", v -> queue.move(q, +1)));
                bs.addView(small("Cancel", v -> queue.cancel(q)));
                break;
            case RUNNING:
                bs.addView(small("Cancel", v -> queue.cancel(q)));
                break;
            case FAILED: case CANCELLED:
                bs.addView(small("Retry", v -> queue.retry(q)));
                break;
            default:
                break;
        }
        whole.addView(details(info, bs));
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
                    ModDownloader.deleteTree(new File(modCache(), cacheName(in.file)));
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
            ViewGroup bs = buttonRow();
            LinearLayout info = column();
            info.addView(text(sc.name, 16, TEXT));
            String st = sc.count + " mods" + (sc.lastCheck > 0 ? ", checked " + df.format(new java.util.Date(sc.lastCheck)) : ", not checked yet");
            info.addView(text(st + (sc.error != null ? "\n⚠ " + sc.error : ""), 13, sc.error != null ? 0xFFFFC060 : DIM));
            if (!catalog.isDefault(sc)) bs.addView(small("Remove", v -> { catalog.removeSubscription(sc); catalog.rebuild(); refresh(); }));
            col.addView(details(info, bs));
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
        {
            col.addView(space(10));
            col.addView(heading("Storage"));
            col.addView(text("Where your game files, mods, saves and save states are kept. Moving them takes a few "
                    + "minutes; the built game itself always stays in internal storage. A custom folder (for example "
                    + "/sdcard/FF7Recomp) is easy to back up or sync, and needs Android's \"All files access\".", 14, DIM));
            RadioGroup sg = new RadioGroup(this);
            int place = Storage.place(this);
            File custom = Storage.customRoot(this);
            java.util.List<String[]> places = new java.util.ArrayList<>();
            places.add(new String[] { "0", "Internal storage", ModDownloader.human(Storage.internalRoot(this).getUsableSpace()) + " free" });
            if (sd != null || place == Storage.SD)
                places.add(new String[] { "1", "SD card", sd != null ? ModDownloader.human(sd.getUsableSpace()) + " free" : "not inserted" });
            places.add(new String[] { "2", "Custom folder", custom != null
                    ? custom.getAbsolutePath() + (custom.isDirectory() ? ", " + ModDownloader.human(custom.getUsableSpace()) + " free" : ", not there")
                    : "A folder you choose on the device or the SD card" });
            for (String[] pl : places) {
                RadioButton rb = new RadioButton(this);
                rb.setId(View.generateViewId());
                rb.setText(pl[1] + "\n" + pl[2]);
                rb.setTextColor(TEXT);
                rb.setTextSize(16);
                final int to = Integer.parseInt(pl[0]);
                rb.setChecked(to == place);
                rb.setEnabled(to != Storage.SD || sd != null);
                rb.setOnClickListener(x -> {
                    if (to == Storage.CUSTOM) customStorage();
                    else if (to != Storage.place(this)) moveStorage(to, null);
                    refresh();                               // the radio shows the real place until moved
                });
                sg.addView(rb);
            }
            col.addView(sg);
            if (place == Storage.CUSTOM) col.addView(small("Choose another folder", v -> customStorage()));
        }
        col.addView(space(10));
        col.addView(heading("Frame rate"));
        RadioGroup g = new RadioGroup(this);
        int cur = prefs.getInt("fps", 3);
        for (int mode : new int[] { 0, 1, 2, 5, 3, 4 }) {   // 5: 60 FPS with battles at 30 (#64)
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
        col.addView(text("How the game starts. In game: the bottom screen's Speed, Slow (slow motion, for the minigames), Battles and Battle+ buttons, or L3 then "
                + "R1/L1 (speed), A (random battles), B (battle enhancement). Icons in the top-right corner show what is on.", 14, DIM));
        RadioGroup sg = new RadioGroup(this);
        sg.setOrientation(smallScreen ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
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
            { "1", "Everything stretched to fill", "The whole 4:3 picture stretched to the screen's edges, fields and movies too: no side bars, but people and things look wider" },
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
            { "1", "OpenGL ES", "Recommended on Mali GPUs (most MediaTek and Exynos phones). Try it if the game is slow, crashes, shows graphics errors or does not start with Vulkan" },
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
        frameGenSection(col);
        col.addView(space(16));
        ffnxSections(col);
        col.addView(heading("Screen"));
        CheckBox second = new CheckBox(this);
        second.setText("Use the second screen\nOn dual-screen devices such as the AYN Thor: party, enemy HP and the touch deck. Off: the second screen is free for other apps, such as a guide (tap the game screen to give the controller back to the game)");
        second.setTextColor(TEXT);
        second.setTextSize(16);
        second.setChecked(prefs.getBoolean("bottom_screen", true));
        second.setOnCheckedChangeListener((b, on) -> prefs.edit().putBoolean("bottom_screen", on).apply());
        col.addView(second);
        CheckBox deckBox = new CheckBox(this);
        deckBox.setText("Second screen buttons\nMenu, Save, Load, Speed, Slow, Battles and Battle+ along the bottom of the panels, "
                + "also where they are on the only screen. "
                + "Off: the panels fill the screen and the controller does everything");
        deckBox.setTextColor(TEXT);
        deckBox.setTextSize(16);
        deckBox.setChecked(prefs.getBoolean("bottom_buttons", true));
        deckBox.setOnCheckedChangeListener((b, on) -> prefs.edit().putBoolean("bottom_buttons", on).apply());
        col.addView(deckBox);
        CheckBox artBox = new CheckBox(this);   // #79
        artBox.setText("Game pictures on the panels\nPortraits and the world map as the game itself shows them, with your mods' versions "
                + "(menu avatars, a labelled map...). Off: the original portraits and the panels' own map");
        artBox.setTextColor(TEXT);
        artBox.setTextSize(16);
        artBox.setChecked(prefs.getBoolean("game_art", false));
        artBox.setOnCheckedChangeListener((b, on) -> prefs.edit().putBoolean("game_art", on).apply());
        col.addView(artBox);
        CheckBox swapBox = new CheckBox(this);
        swapBox.setText("Swap screens\nThe game on the second screen and the panels on the main one. "
                + "For devices whose main screen is the small one, such as the Ayaneo Pocket DS");
        swapBox.setTextColor(TEXT);
        swapBox.setTextSize(16);
        swapBox.setChecked(prefs.getBoolean("swap_screens", false));
        swapBox.setOnCheckedChangeListener((b, on) -> prefs.edit().putBoolean("swap_screens", on).apply());
        col.addView(swapBox);
        RadioGroup or = new RadioGroup(this);
        String[][] orientations = {
            { "0", "Landscape", "The game fills the screen" },
            { "1", "Portrait (phones)", "The game on top; below it the party, location and enemy HP, a touch deck "
                    + "(menu, save/load state, speed) and the on-screen controls" },
            { "2", "Split (foldables, tablets)", "Held either way: the game on top and the second screen's panels below it, "
                    + "with the on-screen controls at their sides when they show. Screens without room below the game "
                    + "(a foldable's cover screen) play in landscape" },
            { "3", "Side by side (wide screens)", "The game at the left and the second screen's panels (party, menu, save/load "
                    + "state, speed) at its right, on one screen. Best with the 4:3 picture, which leaves the most room; "
                    + "held upright it is like Split" },
        };
        int curLayout = prefs.getBoolean("side", false) ? 3 : prefs.getBoolean("split", false) ? 2 : prefs.getBoolean("portrait", false) ? 1 : 0;
        for (String[] o : orientations) {
            RadioButton rb = new RadioButton(this);
            rb.setId(View.generateViewId());
            rb.setText(o[1] + "\n" + o[2]);
            rb.setTextColor(TEXT);
            rb.setTextSize(16);
            final int v = Integer.parseInt(o[0]);
            rb.setOnClickListener(x -> prefs.edit().putBoolean("portrait", v == 1).putBoolean("split", v == 2).putBoolean("side", v == 3).apply());
            or.addView(rb);
            if (v == curLayout) rb.setChecked(true);
        }
        col.addView(or);
        CheckBox sideLeftBox = new CheckBox(this);
        sideLeftBox.setText("Side by side: panels on the left\nThe panels at the game's left instead of its right. "
                + "In the game, the button at the top of the panels hides them; the tab at the screen's edge brings them back");
        sideLeftBox.setTextColor(TEXT);
        sideLeftBox.setTextSize(16);
        sideLeftBox.setChecked(prefs.getBoolean("side_left", false));
        sideLeftBox.setOnCheckedChangeListener((b, on) -> prefs.edit().putBoolean("side_left", on).apply());
        col.addView(sideLeftBox);
        CheckBox panelBox = new CheckBox(this);   // #87
        panelBox.setText("Button for the panels (one screen)\nA small button over the game, in the Landscape layout, opens the second screen's "
                + "panels (party, map, items, save/load state, speed) and closes them again (or tap the game beside them). Drag it to move it. "
                + "A controller button can do it too: Controller > Open the panels with");
        panelBox.setTextColor(TEXT);
        panelBox.setTextSize(16);
        panelBox.setChecked(prefs.getBoolean("panel_button", false));
        panelBox.setOnCheckedChangeListener((b, on) -> prefs.edit().putBoolean("panel_button", on).apply());
        col.addView(panelBox);
        // Lock rotation (#73): a phone held flat can turn the game by itself
        TextView lockLabel = new TextView(this);
        lockLabel.setText("Lock rotation\nKeeps the game the way it is set here when the phone is turned or held flat. "
                + "Portrait applies to Portrait, Split and Side by side");
        lockLabel.setTextColor(TEXT);
        lockLabel.setTextSize(16);
        lockLabel.setPadding(0, Math.round(8 * getResources().getDisplayMetrics().density), 0, 0);
        col.addView(lockLabel);
        RadioGroup lockGroup = new RadioGroup(this);
        lockGroup.setOrientation(RadioGroup.HORIZONTAL);
        String[] locks = { "Off", "Landscape", "Portrait" };
        int curLock = prefs.getInt("rotation_lock", 0);
        for (int i = 0; i < locks.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setId(View.generateViewId());
            rb.setText(locks[i]);
            rb.setTextColor(TEXT);
            rb.setTextSize(16);
            final int v = i;
            rb.setOnClickListener(x -> prefs.edit().putInt("rotation_lock", v).apply());
            lockGroup.addView(rb);
            if (v == curLock) rb.setChecked(true);
        }
        col.addView(lockGroup);
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
        col.addView(space(8));
        col.addView(button("Buttons shown…", v -> TouchLayoutActivity.buttonsShown(this, prefs, () -> { })));   // #88
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
        CheckBox run = new CheckBox(this);   // GitHub #72
        run.setText("Always run\nMoving runs on its own; hold the run button to walk instead. "
                + "With the analog stick, a slight tilt still walks");
        run.setTextColor(TEXT);
        run.setTextSize(16);
        run.setChecked(prefs.getBoolean("always_run", false));
        run.setOnCheckedChangeListener((b, on) -> prefs.edit().putBoolean("always_run", on).apply());
        col.addView(run);
        CheckBox fire = new CheckBox(this);   // GitHub #33
        fire.setText("Hold confirm to repeat in battles\nHolding the confirm button keeps picking, about five times a "
                + "second, like auto-fire. Outside battles it presses once, as in the original");
        fire.setTextColor(TEXT);
        fire.setTextSize(16);
        fire.setChecked(prefs.getBoolean("autofire", false));
        fire.setOnCheckedChangeListener((b, on) -> prefs.edit().putBoolean("autofire", on).apply());
        col.addView(fire);
        TextView keyLabel = new TextView(this);   // #87
        keyLabel.setText("Open the panels with\nOn one screen this button opens and closes the panels over the game (Landscape layout), or hides and "
                + "shows Side by side's. The game then does not get it; Start pauses battles and minigames");
        keyLabel.setTextColor(TEXT);
        keyLabel.setTextSize(16);
        keyLabel.setPadding(0, Math.round(8 * getResources().getDisplayMetrics().density), 0, 0);
        col.addView(keyLabel);
        RadioGroup keyGroup = new RadioGroup(this);
        keyGroup.setOrientation(RadioGroup.HORIZONTAL);
        String[] panelKeys = { "Off", "Start", "L3", "R3" };
        for (int i = 0; i < panelKeys.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setId(View.generateViewId());
            rb.setText(panelKeys[i]);
            rb.setTextColor(TEXT);
            rb.setTextSize(16);
            final int v = i;
            rb.setOnClickListener(x -> prefs.edit().putInt("panel_key", v).apply());
            keyGroup.addView(rb);
            if (v == prefs.getInt("panel_key", 0)) rb.setChecked(true);
        }
        col.addView(keyGroup);
        col.addView(text("What each button of a controller or keyboard does. Choosing one that is in use swaps the two.", 14, DIM));
        final LinearLayout maps = new LinearLayout(this);
        maps.setOrientation(LinearLayout.VERTICAL);
        fillButtonMap(maps);
        col.addView(maps);
        col.addView(space(16));
        // Rumble (src/ffnx_port/rumble.cpp): FF7 has none of its own, so it follows the game
        RadioGroup rumbleGroup = new RadioGroup(this);
        String[][] rumbles = {
            { "0", "No rumble", "As the original: the game never vibrates" },
            { "1", "Light rumble", "Field quakes and explosions, and in battles every hit: the ones you land and the ones you take" },
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
                + "Select+Left / Right change the slot. Hold Select+Start to leave the game.", 14, DIM));
        col.addView(space(16));
        col.addView(catalogSettings());
        col.addView(space(16));
        col.addView(modCacheSettings());
        return scroll(col);
    }

    /** files/modcache: the game extracts the files it uses from .iro mods there (src/mods/mods.cpp). */
    private File modCache() { return new File(install.files, "modcache"); }

    /** A mod's folder in the mod cache, as mods.cpp names it: its file name in ASCII lower case. */
    static String cacheName(String file) {
        StringBuilder b = new StringBuilder(file.length());
        for (char c : file.toCharArray()) {
            if (c >= 'A' && c <= 'Z') c = (char) (c + 32);
            else if (c == '/' || c == '\\' || c == ':') c = '_';
            b.append(c);
        }
        String n = b.toString();
        return n.isEmpty() || n.equals(".") || n.equals("..") ? "_" : n;
    }

    /** Settings: the mod cache's size and a button that empties it (issue #51). */
    private View modCacheSettings() {
        LinearLayout col = column();
        col.addView(heading("Mod cache"));
        col.addView(text("The game unpacks the files it uses from .iro mods here, so they load fast the next time. "
                + "Folders of mods you remove are deleted when the game starts. Clearing it is safe: the files are unpacked again as the game needs them.", 14, DIM));
        TextView size = text("Size: counting…", 15, TEXT);
        col.addView(size);
        File dir = modCache();
        Runnable count = () -> new Thread(() -> {
            long n = dir.exists() ? Storage.size(dir) : 0;
            runOnUiThread(() -> size.setText("Size: " + ModDownloader.human(n)));
        }, "modcache-size").start();
        count.run();
        col.addView(space(8));
        col.addView(button("Clear the mod cache", v -> {
            if (TestControl.gamePid(this) > 0) { toastDialog("Close the game first: it is using the mod cache."); return; }
            ff(new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setMessage("Clear the mod cache? Your mods stay installed. The first time you play afterwards, places may load a little slower while their files are unpacked again.")
                    .setPositiveButton("Clear", (x, w) -> {
                        size.setText("Size: clearing…");
                        new Thread(() -> {
                            ModDownloader.deleteTree(dir);
                            runOnUiThread(count);
                        }, "modcache-clear").start();
                    })
                    .setNegativeButton("Cancel", null)
                    .show());
        }));
        // Issue #75: keep it small on phones low on storage
        col.addView(space(12));
        col.addView(text("Size limit", 16, TEXT));
        col.addView(text("While you play, the files used longest ago are deleted to stay under it.", 14, DIM));
        RadioGroup limits = new RadioGroup(this);
        int curLimit = prefs.getInt("modcache_limit_mb", 0);
        for (int mb : new int[] { 0, 500, 1024, 2048, 4096 }) {
            RadioButton rb = new RadioButton(this);
            rb.setId(View.generateViewId());
            rb.setText(mb == 0 ? "No limit" : mb < 1024 ? mb + " MB" : (mb / 1024) + " GB");
            rb.setTextColor(TEXT);
            rb.setTextSize(16);
            rb.setOnClickListener(x -> prefs.edit().putInt("modcache_limit_mb", mb).apply());
            limits.addView(rb);
            if (mb == curLimit) rb.setChecked(true);
        }
        col.addView(limits);
        CheckBox onClose = new CheckBox(this);
        onClose.setText("Clear it when the game closes\nEach time you leave the game, the mod cache is emptied. "
                + "Places load a little slower the first time in each session");
        onClose.setTextColor(TEXT);
        onClose.setTextSize(16);
        onClose.setChecked(prefs.getBoolean("modcache_clear_on_close", false));
        onClose.setOnCheckedChangeListener((b, on) -> prefs.edit().putBoolean("modcache_clear_on_close", on).apply());
        col.addView(onClose);
        return col;
    }

    /** Issue #75: empties the mod cache after a game, when "Clear it when the game closes" is on.
     *  The flag is saved, so a launcher restarted while the game ran still clears it. */
    private void clearModCacheAfterGame() {
        if (!prefs.getBoolean("modcache_used", false) || TestControl.gamePid(this) > 0) return;
        prefs.edit().putBoolean("modcache_used", false).apply();
        if (!prefs.getBoolean("modcache_clear_on_close", false) || install == null) return;
        File dir = modCache();
        new Thread(() -> {
            long n = dir.exists() ? Storage.size(dir) : 0;
            if (TestControl.gamePid(this) > 0) return;     // started again meanwhile
            ModDownloader.deleteTree(dir);
            android.util.Log.i("FF7Launcher", "mod cache cleared after the game (" + ModDownloader.human(n) + ")");
        }, "modcache-clear").start();
    }

    private static String fpsName(int mode) {
        switch (mode) {
            case 0: return "Original";
            case 1: return "Original, fixed";
            case 2: return "30 FPS battles";
            case 4: return "120 FPS (experimental)";
            case 5: return "60 FPS, battles at 30";
            default: return "60 FPS";
        }
    }

    private static String fpsDetail(int mode) {
        switch (mode) {
            case 0: return "As shipped";
            case 1: return "As shipped, smoother pacing";
            case 2: return "Battles at 30, the rest as shipped";
            case 4: return "Field at 120; world map and battles at 60";
            case 5: return "Everything at 60 but battles, for devices that slow down in battle";
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
        jobLine = null;
        col.addView(heading("Your FINAL FANTASY VII"));
        // the Steam download or the import (JobService, GitHub #53): running, or stopped part way
        String pendingJob = JobService.pending(this);
        if (JobService.running != null) {
            col.addView(text(JobService.title(JobService.running) + ". It goes on with the launcher in the background; "
                    + "if the game needs building for this device, that starts right after.", 15, TEXT));
            col.addView(space(10));
            jobLine = text(JobService.status, 15, ACCENT);
            col.addView(jobLine);
            col.addView(space(14));
            col.addView(button("Pause", v -> { JobService.pause(); if (jobLine != null) jobLine.setText("Pausing…"); }));
            col.addView(space(14));
            col.addView(text("Game files: " + install.game.getAbsolutePath(), 12, DIM));
            return scroll(col);
        }
        if (pendingJob != null && !GameBuild.running) {
            String why = JobService.paused ? "Paused." : JobService.status != null && JobService.status.startsWith("Stopped")
                    ? JobService.status : "It was interrupted before it finished.";
            col.addView(text(JobService.title(pendingJob) + ": " + why
                    + (why.contains("Continue") ? "" : " Continue goes on where it stopped; nothing done so far is lost."), 15, TEXT));
            col.addView(space(14));
            ViewGroup b = buttonRow();
            b.addView(button("Continue", v -> { askNotifications(); if (JobService.resume(this)) refresh(); }));
            b.addView(button("Cancel", v -> { JobService.forget(this); refresh(); }));
            col.addView(b);
            col.addView(space(14));
        }
        if (!install.hasGame()) {
            col.addView(text("FF7Recomp needs your copy of FINAL FANTASY VII from Steam. Download it here with your "
                    + "Steam account (about 3.3 GB), or pick the game's folder if you already have it on this device "
                    + "(the folder that holds \"ff7\"). FF7Recomp then turns the game into a native Android app "
                    + "on this device.", 15, TEXT));
            col.addView(space(14));
            col.addView(button(install.steamDownloading().exists() ? "Go on downloading from Steam" : "Download from Steam…",
                    v -> steamDownload()));
            col.addView(text("Sign in once with the Steam app on your phone (a QR code; no password is typed here). "
                    + "It goes on with the launcher in the background, with Pause; if it stops, it goes on "
                    + "where it was.", 13, DIM));
            col.addView(space(14));
            col.addView(button("Find my FF7 folder…", v -> pick(PICK_GAME)));
            col.addView(space(8));
            col.addView(text(GAMENATIVE_HINT, 13, DIM));
            col.addView(space(8));
            col.addView(text(Reports.RECOMMENDED, 13, DIM));
        } else if (GameBuild.running) {
            col.addView(text("Building the game for this device. This happens once: about 8 minutes on an AYN "
                    + "Thor, longer on slower phones (the bar can stay at 4% for several minutes while the "
                    + "game's code is read). You can leave the launcher; it keeps going in the background. "
                    + "If it is paused or stopped, Continue building goes on where it was.", 15, TEXT));
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
            col.addView(space(14));
            // GitHub #53: stop for now, keeping what is done
            col.addView(button("Pause", v -> {
                BuildService.pause();
                if (buildLine != null) buildLine.setText("Pausing…");
            }));
        } else if (GameBuild.error == null && build.resumable()) {
            // paused, or stopped by the system or a restart (#53): the work done so far is kept
            col.addView(text((GameBuild.paused
                    ? "Building is paused at " + GameBuild.percent + "%. Continue building goes on where it stopped."
                    : "The last build was interrupted before it finished. Continue building goes on where it "
                      + "stopped; nothing done so far is lost.")
                    + (build.built() ? " Until then, Play starts the game built before." : ""), 15, TEXT));
            col.addView(space(14));
            col.addView(button("Continue building", v -> startBuild()));
            col.addView(space(8));
            col.addView(button("Import again from another folder…", v -> pick(PICK_GAME)));
        } else if (build.built()) {
            col.addView(text("Ready. The game is built for this device: press Play.", 15, TEXT));
            col.addView(space(14));
            col.addView(button("Import again from another folder…", v -> pick(PICK_GAME)));
            col.addView(space(8));
            col.addView(button("Build again", v -> { new File(build.work, "stamp").delete(); startBuild(); }));
        } else {
            col.addView(text(GameBuild.error != null
                    ? "The last build stopped: " + GameBuild.error
                    + (build.resumable() ? "\nTry again goes on where it stopped." : "")
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
            col.addView(space(8));
            col.addView(button("Repair from Steam", v -> steamDownload()));
            col.addView(text("Checks every game file against Steam's copy and downloads only the missing or damaged ones.", 13, DIM));
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
        askNotifications();
        BuildService.start(this);
        show("game");
    }

    /** Background work shows its progress, Pause and Continue in a notification (API 33+ asks). */
    private void askNotifications() {
        if (android.os.Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[] { android.Manifest.permission.POST_NOTIFICATIONS }, 9);
    }

    /** The Steam download or the import, in JobService (GitHub #53: goes on in the background). */
    private void startJob(String kind, String arg) {
        if (busy || JobService.running != null || GameBuild.running) return;
        askNotifications();
        if (!JobService.start(this, kind, arg)) return;
        show("game");
    }

    // ------------------------------------------------------------------- actions
    /** Settings > Display: frame generation with the player's own Lossless Scaling (FF8Recomp #8). */
    private void frameGenSection(LinearLayout col) {
        col.addView(heading("Frame generation"));
        boolean have = FrameGen.dll(install.files).isFile();
        CheckBox on = new CheckBox(this);
        on.setText("Frame generation (Lossless Scaling)\nShows up to 60 frames a second by making frames between the game's own, "
                + "which keeps its normal speed. Needs Lossless Scaling (bought on Steam) and Vulkan 1.3. It adds a little "
                + "delay and uses more battery; fast scenes can look smeared");
        on.setTextColor(have ? TEXT : DIM);
        on.setTextSize(16);
        on.setEnabled(have);
        on.setChecked(have && prefs.getBoolean("framegen", false));
        on.setOnCheckedChangeListener((b, x) -> prefs.edit().putBoolean("framegen", x).apply());
        col.addView(on);
        if (have && prefs.getInt("renderer", 0) == 1) col.addView(text("It works with the Vulkan renderer only: choose Vulkan above.", 14, DIM));
        if (have && !FrameGen.useful(prefs.getInt("fps", 3))) col.addView(text("Frame rate is set to " + fpsName(prefs.getInt("fps", 3))
                + ": there is nothing to make. Choose a lower frame rate above to use it.", 14, DIM));
        if (!have) col.addView(text("FF7Recomp does not include it: it uses Lossless.dll (7 MB) from your own copy, "
                + "downloaded from your Steam account or chosen here.", 14, DIM));
        if (steam().signedIn()) col.addView(button(have ? "Get Lossless.dll from Steam again" : "Get Lossless.dll from your Steam", v -> background(() -> {
            File tmp = new File(FrameGen.dir(install.files), "steam");
            GameBuild.deleteTree(tmp);
            try {
                String err = new SteamFiles(steam()).downloadLossless(tmp, this::progress, new java.util.concurrent.atomic.AtomicBoolean());
                if (err == null) try (java.io.InputStream in = new java.io.FileInputStream(new File(tmp, "Lossless.dll"))) { err = FrameGen.install(in, install.files); }
                if (err != null) return "Not added: " + err;
                return frameGenAdded();
            } finally { GameBuild.deleteTree(tmp); }
        })));
        else col.addView(button("Sign in to Steam to get Lossless.dll…", v -> steamSignIn()));
        col.addView(small("Choose a Lossless.dll on this device…", v -> pick(PICK_LOSSLESS)));
    }

    private String frameGenAdded() {
        prefs.edit().putBoolean("framegen", true).apply();
        runOnUiThread(() -> show("settings"));
        return "Lossless.dll added. Frame generation is on.";
    }

    private void pick(int what) {
        if (busy) return;
        Intent i;
        if (what == PICK_LOSSLESS) {
            i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
        } else if (what == PICK_IRO || what == PICK_PSX) {
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
        if (req == PICK_STORAGE) { pickedStorageFolder(data.getData()); return; }
        if (req == PICK_GAME) {
            // JobService (#53): the import goes on in the background, and Continue after a stop
            // needs the folder again, so the permission is kept until the import ends
            try { getContentResolver().takePersistableUriPermission(data.getData(), Intent.FLAG_GRANT_READ_URI_PERMISSION); }
            catch (Exception e) { android.util.Log.w("FF7Jobs", "folder permission not kept: " + e); }
            startJob(JobService.IMPORT, data.getData().toString());
            return;
        }
        background(() -> {
            if (req == PICK_LOSSLESS) {
                try (java.io.InputStream in = getContentResolver().openInputStream(data.getData())) {
                    if (in == null) return "Not added: the file cannot be read";
                    String err = FrameGen.install(in, install.files);
                    return err != null ? "Not added: " + err : frameGenAdded();
                }
            }
            if (req == PICK_PSX) return importPsx(data);
            if (req == PICK_IRO) {
                // .iro, .zip or .7z: Nexus Mods and other sites' downloads (unpacked like Get mods')
                java.util.List<Uri> picked = new java.util.ArrayList<>();
                if (data.getClipData() != null)
                    for (int k = 0; k < data.getClipData().getItemCount(); k++) picked.add(data.getClipData().getItemAt(k).getUri());
                else if (data.getData() != null) picked.add(data.getData());
                File inbox = new File(install.files, "downloads");
                inbox.mkdirs();
                java.util.List<String> added = new java.util.ArrayList<>(), failed = new java.util.ArrayList<>();
                for (int k = 0; k < picked.size(); k++) {
                    String tag = picked.size() > 1 ? " " + (k + 1) + " of " + picked.size() : "";
                    progress("Copying mod" + tag);
                    File copy = null;
                    try {
                        copy = install.importFile(picked.get(k), inbox);
                        final String name = copy.getName();
                        progress("Adding " + name);
                        added.add(downloader.installLocal(copy, (what, done, total) ->
                                progress("Unpacking " + name + (total > 0 ? " " + (done * 100 / total) + "%" : ""))));
                    } catch (java.io.IOException e) {
                        if (copy != null) copy.delete();
                        failed.add((copy != null ? copy.getName() : "a file") + ": " + e.getMessage());
                    }
                }
                StringBuilder msg = new StringBuilder();
                if (!added.isEmpty()) msg.append("Added: ").append(String.join(", ", added)).append(". Enable them in the list.");
                if (!failed.isEmpty()) msg.append(msg.length() > 0 ? "\n\n" : "").append("Not added:\n").append(String.join("\n", failed));
                return msg.length() > 0 ? msg.toString() : "No file was picked.";
            }
            File f = install.importFolder(data.getData(), library.dir, this::progress);
            return "Mod folder " + f.getName() + " added. Enable it in the list.";
        });
    }

    // ------------------------------------------------------------------- Steam achievements
    private SteamLink steam;
    /** Play synced the saves with Steam Cloud: sync again when the launcher is back and the game has closed. */
    private boolean cloudAfterGame;

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
        if (c == null) col.addView(text("Your achievements have not been read from Steam yet: press Sync achievements while online.", 13, DIM));
        col.addView(space(8));
        ViewGroup r = buttonRow();
        r.addView(small("Sync achievements", v -> background(() -> {
            progress("Talking to Steam");
            java.util.List<String> got = steam().sync();
            return got.isEmpty() ? "Steam achievements are up to date." : "Sent to Steam: " + String.join(", ", got);
        })));
        r.addView(small("Sign out", v -> ff(new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setMessage("Sign out of Steam? Steam achievements and cloud saves stop until you sign in again.")
                .setPositiveButton("Sign out", (d, x) -> { steam().signOut(); refresh(); })
                .setNegativeButton("Cancel", null)
                .show())));
        col.addView(r);

        col.addView(space(16));
        col.addView(heading("Steam Cloud saves"));
        CheckBox cs = new CheckBox(this);
        cs.setText("Sync my saves with Steam Cloud" + "\n" + "Your save files go to FINAL FANTASY VII's Steam Cloud before "
                + "and after you play, so you pick up where you left off on another device signed in to this Steam "
                + "account. A save about to be replaced is backed up first.");
        cs.setChecked(prefs.getBoolean("cloud_saves", false));
        cs.setOnCheckedChangeListener((b, on) -> {
            prefs.edit().putBoolean("cloud_saves", on).apply();
            if (on) background(() -> {
                progress("Syncing saves with Steam Cloud");
                String m = syncCloudSaves();
                return m != null ? m : "Last sync " + prefs.getString("cloud_last", "");
            });
            else refresh();
        });
        col.addView(cs);
        // where: the PC game's own saves (the same files: SteamFiles.PC_FOLDER) or FF7Recomp's folder
        RadioGroup where = new RadioGroup(this);
        String[][] whereOpts = {
            { "With the PC game too", "Save 1 to Save 10 are the same files as the PC version's (FINAL FANTASY VII on Steam)" },
            { "Android devices only", "Kept in their own FF7Recomp folder in Steam Cloud; the PC game's saves are left alone" } };
        boolean withPc = prefs.getBoolean("cloud_with_pc", true);
        for (int k = 0; k < 2; k++) {
            RadioButton rb = new RadioButton(this);
            rb.setId(View.generateViewId());
            android.text.SpannableString label = new android.text.SpannableString(whereOpts[k][0] + "   " + whereOpts[k][1]);
            int at = whereOpts[k][0].length();
            label.setSpan(new android.text.style.ForegroundColorSpan(DIM), at, label.length(), 0);
            label.setSpan(new android.text.style.RelativeSizeSpan(0.85f), at, label.length(), 0);
            rb.setText(label);
            rb.setTextColor(TEXT);
            rb.setTextSize(16);
            final boolean pc = k == 0;
            rb.setOnClickListener(v -> {
                if (pc == prefs.getBoolean("cloud_with_pc", true)) return;
                prefs.edit().putBoolean("cloud_with_pc", pc).remove("cloud_last").apply();
                if (prefs.getBoolean("cloud_saves", false)) background(() -> {
                    progress("Syncing saves with Steam Cloud");
                    String m = syncCloudSaves();
                    return m != null ? m : "Last sync " + prefs.getString("cloud_last", "");
                });
                else refresh();
            });
            where.addView(rb);
            if (pc == withPc) rb.setChecked(true);
        }
        col.addView(where);
        col.addView(text("With the PC game: a save changed on only one side goes to the other. The first time, a save file "
                + "that differs on both sides keeps the newer one, and the other copy is backed up in "
                + new File(install.files, "steam/cloud-backup").getAbsolutePath() + ". The PC picks the saves up the next time FINAL "
                + "FANTASY VII starts there (with Steam Cloud on).", 13, DIM));
        if (prefs.getBoolean("cloud_saves", false)) {
            String last = prefs.getString("cloud_last", null);
            if (last != null) col.addView(text("Last sync " + last, 13, DIM));
            col.addView(space(6));
            col.addView(small("Sync saves now", v -> background(() -> {
                progress("Syncing saves with Steam Cloud");
                String m = syncCloudSaves();
                return m != null ? m : "Last sync " + prefs.getString("cloud_last", "");
            })));
        }
    }

    /** Download (or repair) the game from Steam: GameInstall.downloadFromSteam. */
    private void steamDownload() {
        if (busy) return;
        if (!steam().signedIn()) { steamSignIn(this::steamDownload); return; }
        startJob(JobService.STEAM, null);
    }

    /** Cloud saves (the Steam page): before Play and after the game. */
    private boolean cloudSaves() { return steam().signedIn() && prefs.getBoolean("cloud_saves", false); }

    /** Syncs the saves with Steam Cloud; returns what changed (for a message) or null when nothing did. */
    private String syncCloudSaves() {
        try {
            SteamFiles.SyncResult r = new SteamFiles(steam()).syncSaves(new File(install.game, "save"), prefs.getBoolean("cloud_with_pc", true));
            prefs.edit().putString("cloud_last", new java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale.getDefault())
                    .format(new java.util.Date()) + ": " + r).apply();
            return r.up.isEmpty() && r.down.isEmpty() ? null : r.toString();
        } catch (Exception e) {
            android.util.Log.w("FF7SteamFiles", "cloud saves: " + SteamLink.message(e));
            prefs.edit().putString("cloud_last", "not synced: " + SteamLink.message(e)).apply();
            return null;
        }
    }

    private void steamSignIn() { steamSignIn(null); }

    /** then: runs after a successful sign-in (instead of the "signed in" message). */
    private void steamSignIn(Runnable then) {
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
                    if (error == null && then != null) {
                        then.run();
                    } else if (error == null) {
                        int[] c = steam().counts();
                        toastDialog("Signed in to Steam as " + steam().account() + "."
                                + (c != null ? " " + c[0] + " of " + c[1] + " FINAL FANTASY VII achievements unlocked." : ""));
                    } else if (then != null && error.startsWith("signed in") && steam().signedIn()) {
                        then.run();   // signed in; only the achievements could not be read yet
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
        File saveDir = new File(install.game, "save");
        for (Uri u : uris) {
            String label = uris.size() > 1 ? displayName(u) + ": " : "";
            progress("Reading the saves");
            byte[] b;
            try (java.io.InputStream in = getContentResolver().openInputStream(u)) {
                if (in == null) throw new java.io.IOException("cannot open the file");
                java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[65536];
                for (int n; (n = in.read(buf)) > 0; ) {
                    o.write(buf, 0, n);
                    if (o.size() > PsxSaves.MAX_ZIP) break;
                }
                b = o.toByteArray();
            } catch (java.io.IOException e) {
                msg.append(label).append("Not imported: ").append(e.getMessage()).append("\n");
                continue;
            }
            if (PsxSaves.isZip(b)) {
                // a zip (Export saves', a problem report, a pack from a website): each save in it
                if (b.length > PsxSaves.MAX_ZIP) { msg.append(label).append("Not imported: this zip is too big for a pack of saves\n"); continue; }
                java.util.List<PsxSaves.Entry> es;
                try { es = PsxSaves.unzip(b); }
                catch (java.io.IOException e) { msg.append(label).append("Not imported: ").append(e.getMessage()).append("\n"); continue; }
                int found = 0;
                for (PsxSaves.Entry e : es) {
                    boolean likely = PsxSaves.saveName(e.name);
                    if (e.data == null) {
                        if (likely) msg.append(e.name).append(": Not imported: too big to be a save\n");
                        continue;
                    }
                    if (!likely && !PsxSaves.isPcSave(e.data)) continue;   // a readme, a log...
                    found++;
                    msg.append(e.name).append(": ").append(importOne(e.data, saveDir)).append("\n");
                }
                if (found == 0) msg.append(label).append("Not imported: there are no FINAL FANTASY VII saves in this zip\n");
                continue;
            }
            if (b.length > PsxSaves.MAX_FILE) { msg.append(label).append("Not imported: this file is too big to be a save or a memory card\n"); continue; }
            msg.append(label).append(importOne(b, saveDir)).append("\n");
        }
        return msg.toString().trim();
    }

    /** One save file or memory card into the first free save file; a line saying where it went. */
    private static String importOne(byte[] b, File saveDir) {
        try {
            PsxSaves.Result r = PsxSaves.importAny(b, saveDir);
            String game = "Save " + r.gameNumber;
            return r.saves + (r.saves == 1 ? " save" : " saves") + " imported into " + game
                    + ". In game: Continue, then pick " + game + ".";
        } catch (java.io.IOException e) {
            return "Not imported: " + e.getMessage();
        }
    }

    private String displayName(Uri u) {
        try (android.database.Cursor c = getContentResolver().query(u, new String[] { android.provider.OpenableColumns.DISPLAY_NAME }, null, null, null)) {
            if (c != null && c.moveToFirst() && c.getString(0) != null) return c.getString(0);
        } catch (Exception ignored) {}
        String p = u.getLastPathSegment();
        return p == null ? "file" : p.substring(p.lastIndexOf('/') + 1);
    }

    /** Export saves (FF9 parity): game/save/*.ff7 in one zip, saved to Downloads, then the share sheet. */
    private void exportSaves() {
        if (busy) return;
        background(() -> {
            progress("Packing the saves");
            File zip = PsxSaves.exportZip(new File(install.game, "save"), new File(getCacheDir(), "export"));
            Uri uri = Reports.toDownloads(this, zip);
            int n = 0;
            try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(zip)) { n = z.size(); }
            final String name = zip.getName();
            runOnUiThread(() -> {
                Intent send = new Intent(Intent.ACTION_SEND).setType("application/zip")
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .putExtra(Intent.EXTRA_SUBJECT, "FF7Recomp saves")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                send.setClipData(android.content.ClipData.newRawUri(name, uri));
                try { startActivity(Intent.createChooser(send, "Send the saves")); } catch (Exception ignored) {}
            });
            return "Saved to Downloads: " + name + " (" + n + (n == 1 ? " save file" : " save files")
                    + "). Import saves takes this zip back, here or on another device.";
        });
    }

    // ------------------------------------------------------------------- save states
    /** The achievements the game tracks (src/ffnx_port/achievements.cpp), offline for now. */
    private View achievementsPage() {
        Achievements ach = new Achievements(install.files);
        LinearLayout col = column();
        col.addView(heading("Steam"));
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

    // ------------------------------------------------------------------ report a problem
    private boolean reportSaves = true, reportState = true, reportBusy;
    private String reportDone;   // the last zip's name and size, as saved to Downloads

    private View reportPage() {
        LinearLayout col = column();
        col.addView(heading("Report a problem"));
        col.addView(text("One zip with what it takes to look into a problem: the game's logs, how it last closed, your "
                + "device, settings and mod list. Not your game files, mods or Steam sign-in. Nothing is sent "
                + "anywhere until you share it.", 14, DIM));
        String exit = Reports.lastGameExit(this);
        if (exit != null) { col.addView(space(4)); col.addView(text("The game last closed: " + exit, 14, TEXT)); }
        col.addView(space(4));
        col.addView(text(Reports.RECOMMENDED, 13, DIM));
        col.addView(space(4));
        java.util.List<File> saves = Reports.saves(this);
        CheckBox s = new CheckBox(this);
        s.setText(saves.isEmpty() ? "Your saves (none yet)" : "Your saves (" + saves.size() + (saves.size() == 1 ? " file)" : " files)"));
        s.setEnabled(!saves.isEmpty());
        s.setChecked(reportSaves && !saves.isEmpty());
        s.setOnCheckedChangeListener((b, on) -> reportSaves = on);
        col.addView(s);
        File st = Reports.latestState(this);
        CheckBox t = new CheckBox(this);
        t.setText(st == null ? "Your newest save state (none yet)"
                : "Your newest save state: " + st.getName().replace(".ff7s", "") + ", "
                  + java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(new java.util.Date(st.lastModified()))
                  + " (" + Math.max(1, st.length() >> 20) + " MB)");
        t.setEnabled(st != null);
        t.setChecked(reportState && st != null);
        t.setOnCheckedChangeListener((b, on) -> reportState = on);
        col.addView(t);
        col.addView(text("Saves let the problem be replayed where it happens. A zip attached on GitHub is public: "
                + "untick them, or share the zip privately, if you'd rather.", 13, DIM));
        col.addView(space(6));
        ViewGroup row = buttonRow();
        Button save = button(reportBusy ? "Packing…" : "Save to Downloads", v -> makeReport(false));
        Button share = button("Share…", v -> makeReport(true));
        save.setEnabled(!reportBusy);
        share.setEnabled(!reportBusy);
        row.addView(save);
        row.addView(share);
        col.addView(row);
        if (reportDone != null) col.addView(text(reportDone, 15, ACCENT));
        col.addView(space(10));
        col.addView(text("Then open an issue on GitHub (the device lines are filled in) and drag the zip from Downloads "
                + "into the comment box. On another device, copy the zip over first.", 14, DIM));
        col.addView(button("Open a GitHub issue", v -> {
            try { startActivity(new Intent(Intent.ACTION_VIEW, Reports.issueUrl(this))); }
            catch (Exception e) { toastDialog("No browser to open " + Reports.ISSUES); }
        }));
        return scroll(col);
    }

    /** Builds the zip off the UI thread, saves it to Downloads, then shares it (share: true). */
    private void makeReport(boolean share) {
        if (reportBusy) return;
        reportBusy = true;
        reportDone = null;
        refresh();
        final boolean saves = reportSaves, state = reportState;
        new Thread(() -> {
            String done;
            Uri uri = null;
            File zip = null;
            try {
                zip = Reports.build(this, saves, state);
                uri = Reports.toDownloads(this, zip);
                done = "Saved to Downloads: " + zip.getName() + " (" + String.format(java.util.Locale.US, "%.1f MB", zip.length() / 1048576.0) + ")";
            } catch (Exception e) {
                done = "Could not make the report: " + e.getMessage();
            }
            final String msg = done;
            final Uri u = uri;
            final String name = zip == null ? "" : zip.getName();
            runOnUiThread(() -> {
                reportBusy = false;
                reportDone = msg;
                if (page.equals("report")) {
                    refresh();
                    if (content.getChildAt(0) instanceof ScrollView) {   // the result line in view
                        ScrollView sv = (ScrollView) content.getChildAt(0);
                        sv.post(() -> sv.smoothScrollTo(0, Math.max(0, sv.getChildAt(0).getHeight() - sv.getHeight())));
                    }
                }
                if (share && u != null) {
                    Intent send = new Intent(Intent.ACTION_SEND).setType("application/zip")
                            .putExtra(Intent.EXTRA_STREAM, u)
                            .putExtra(Intent.EXTRA_SUBJECT, "FF7Recomp problem report")
                            .putExtra(Intent.EXTRA_TEXT, "FF7Recomp " + Reports.version(this) + " on " + Reports.device())
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    send.setClipData(android.content.ClipData.newRawUri(name, u));
                    try { startActivity(Intent.createChooser(send, "Send the report")); }
                    catch (Exception e) { toastDialog("Nothing on this device can share a file"); }
                }
            });
        }).start();
    }

    private View statesPage() {
        if (states == null) states = new SaveStates(install.files);
        LinearLayout col = column();
        col.addView(heading("Save states"));
        col.addView(text("In game: Select+L1 saves to the current slot, Select+R1 loads it, Select+Left / Right "
                + "picks another slot. \"Play from here\" starts the game at that moment.", 14, DIM));
        col.addView(space(10));
        ViewGroup saveButtons = buttonRow();
        saveButtons.addView(small("Import saves…", v -> pick(PICK_PSX)));
        saveButtons.addView(small("Export saves", v -> exportSaves()));
        col.addView(saveButtons);
        col.addView(text("Export saves puts all your save files (Save 1-10) in one zip in Downloads, to keep or to "
                + "move to another device. Import saves takes several files at once, or a zip of them.", 13, DIM));
        col.addView(text("PC: a save file (save00.ff7…save09.ff7) from the current Steam release (in "
                + "%LOCALAPPDATA%\\FINAL FANTASY VII Steam Edition on the PC; Steam Cloud saves on the Achievements page "
                + "can also share them), the 2013 Steam release (Documents\\Square Enix\\FINAL FANTASY VII Steam\\user_…), "
                + "the original PC release or a 7th Heaven install. PlayStation: a memory card from DuckStation (.mcd), ePSXe (.mcr), "
                + "RetroArch (.srm), DexDrive (.gme) or PSP (.vmp); DuckStation keeps its cards in "
                + "Android/data/com.github.stenzek.duckstation/files/memcards. Copy the file somewhere you can pick it "
                + "(Download, for example) first. The saves go into a new save file (Save 2, Save 3… in the game), so "
                + "none of yours are overwritten.", 13, DIM));
        col.addView(space(10));
        int cur = states.current();
        for (int n = 1; n <= SaveStates.SLOTS; n++) {
            SaveStates.Slot sl = states.read(n);
            ViewGroup bs = buttonRow();
            LinearLayout info = column();
            info.addView(text("Slot " + n + (n == cur ? "  (current)" : ""), smallScreen ? 16 : 18, TEXT));
            String sub;
            if (!sl.exists) sub = "Empty";
            else if (!sl.readable) sub = "From an older version of the app (cannot be loaded)";
            else sub = (sl.location.isEmpty() ? "?" : sl.location) + "  ·  " + sl.playTime() + "  ·  saved " + sl.when;
            info.addView(text(sub, 13, DIM));
            final int k = n;
            if (sl.readable && install.hasGame()) bs.addView(small("Play from here", v -> playFrom(k)));
            if (n != cur) bs.addView(small("Make current", v -> {
                try { states.setCurrent(k); } catch (Exception e) { toastDialog("Could not set the slot: " + e); }
                refresh();
            }));
            if (sl.exists) bs.addView(small("Delete", v -> ff(new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setMessage("Delete save state slot " + k + "? This cannot be undone.")
                    .setPositiveButton("Delete", (d, w) -> { states.delete(k); refresh(); })
                    .setNegativeButton("Cancel", null)
                    .show())));
            LinearLayout r = details(info, bs);
            r.setPadding(0, dp(6), 0, dp(6));
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
            if (cloudSaves()) {
                progress("Syncing saves with Steam Cloud");
                syncCloudSaves();
                cloudAfterGame = true;
            }
            FrameGen.write(install.files, prefs.getBoolean("framegen", false) && FrameGen.useful(prefs.getInt("fps", 3)) && FrameGen.dll(install.files).isFile());
            install.crtFilter = prefs.getBoolean("crt_filter", false);
            install.analogControls = prefs.getBoolean("analog_controls", false);
            install.resolutionScale = prefs.getInt("resolution", 0);
            install.openGl = prefs.getInt("renderer", 0) == 1;
            install.ffnxOptions = FfnxOptions.chosen(prefs);
            int aspect = prefs.getInt("aspect", 2);   // 3: 16:9 stretched by Android (GameActivity), FFNx renders 16:9
            install.writeFfnxToml(prefs.getInt("fps", 3) == 5 ? 3 : prefs.getInt("fps", 3), boosterSpeed() > 1, aspect == 3 ? 2 : aspect, prefs.getBoolean("show_fps", false), library.mods,
                                  renderScaleCap());
            install.writeModCacheLimit(prefs.getInt("modcache_limit_mb", 0));   // #75
            prefs.edit().putBoolean("modcache_used", true).apply();
            install.writeQuickboot(quickbootOverride != null ? quickbootOverride : prefs.getString("quickboot", ""));
            install.writeBoosters(boosterSpeed(), prefs.getBoolean("no_encounters", false), prefs.getBoolean("battle_plus", false),
                                  prefs.getInt("perf", 1) != 2, prefs.getBoolean("save_anywhere", false),
                                  prefs.getInt("gil", 1), prefs.getInt("exp", 1), prefs.getInt("ap", 1),
                                  prefs.getBoolean("autofire", false), prefs.getInt("rumble", 0),
                                  prefs.getBoolean("always_run", false));
            install.writeCompanion("off");   // battle windows stay on the top screen
            runOnUiThread(() -> startActivity(new Intent(this, GameActivity.class)
                    .putExtra("touch_controls", prefs.getInt("touch_controls", 0))
                    .putExtra("touch_layout", prefs.getString("touch_layout", ""))
                    .putExtra("touch_layout_square", TouchControls.savedLayout(prefs, "touch_layout_square"))   // #66
                    .putExtra("touch_hidden", prefs.getString("touch_hidden", ""))   // #88
                    .putExtra("touch_extra", prefs.getBoolean("touch_extra", false))
                    .putExtra("portrait", prefs.getBoolean("portrait", false))
                    .putExtra("split", prefs.getBoolean("split", false))
                    .putExtra("side", prefs.getBoolean("side", false))
                    .putExtra("side_left", prefs.getBoolean("side_left", false))
                    .putExtra("panel_button", prefs.getBoolean("panel_button", false))
                    .putExtra("panel_key", prefs.getInt("panel_key", 0))   // #87
                    .putExtra("rotation_lock", prefs.getInt("rotation_lock", 0))
                    .putExtra("bottom_screen", prefs.getBoolean("bottom_screen", true))
                    .putExtra("bottom_buttons", prefs.getBoolean("bottom_buttons", true))
                    .putExtra("game_art", prefs.getBoolean("game_art", false))
                    .putExtra("crt_filter", prefs.getBoolean("crt_filter", false))
                    .putExtra("aspect", prefs.getInt("aspect", 2))
                    .putExtra("fps", prefs.getInt("fps", 3)), gameDisplayOptions()));
            return null;
        });
    }

    /** Swap screens (Settings > Screen, GitHub #41): the game starts on the second display and
     *  GameActivity puts the panels on the main one. Null (this display) otherwise. */
    private android.os.Bundle gameDisplayOptions() {
        android.view.Display d = swapDisplay();
        if (d == null) return null;
        android.app.ActivityOptions o = android.app.ActivityOptions.makeBasic();
        o.setLaunchDisplayId(d.getDisplayId());
        return o.toBundle();
    }

    /** The display the game goes to when the screens are swapped, or null when they are not. */
    private android.view.Display swapDisplay() {
        if (!prefs.getBoolean("swap_screens", false) || !prefs.getBoolean("bottom_screen", true)) return null;
        android.hardware.display.DisplayManager dm = (android.hardware.display.DisplayManager) getSystemService(DISPLAY_SERVICE);
        android.view.Display[] ds = dm.getDisplays(android.hardware.display.DisplayManager.DISPLAY_CATEGORY_PRESENTATION);
        return ds.length > 0 ? ds[0] : null;
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
        android.view.Display gd = swapDisplay();   // the screen the game will be on
        (gd != null ? gd : getWindowManager().getDefaultDisplay()).getRealMetrics(dm);
        int shortSide = Math.min(dm.widthPixels, dm.heightPixels);
        int match = Math.max(1, (shortSide + 240) / 480);
        return perf == 0 ? Math.max(1, match - 1) : match;
    }

    /** Settings > Controller: one row per button (ButtonMap), and a way back to the usual layout. */
    private void fillButtonMap(LinearLayout maps) {
        maps.removeAllViews();
        final int[] map = ButtonMap.read(prefs);
        for (int i = 0; i < ButtonMap.NAMES.length; i++) {
            final int b = i;
            maps.addView(button(ButtonMap.NAMES[b] + (map[b] == b ? "" : "  works as  " + ButtonMap.NAMES[map[b]]), v ->
                    new android.app.AlertDialog.Builder(this).setTitle(ButtonMap.NAMES[b] + " works as")
                            .setSingleChoiceItems(ButtonMap.NAMES, map[b], (d, which) -> {
                                ButtonMap.choose(prefs, b, which);
                                d.dismiss();
                                fillButtonMap(maps);
                            }).setNegativeButton("Cancel", null).show()));
        }
        maps.addView(button("Reset the buttons", v -> { ButtonMap.reset(prefs); fillButtonMap(maps); }));
    }

    private int boosterSpeed() {
        return prefs.getInt("speed", prefs.getBoolean("turbo", false) ? 2 : 1);
    }

    private void saveProfile() {
        try { library.save(); } catch (Exception e) { toastDialog("Could not save the mod list: " + e); }
    }

    /** Nothing is using the files now (a build, a download, the game), else says so and returns false. */
    private boolean canMoveStorage() {
        if (busy) return false;
        if (GameBuild.running) { toastDialog("Not moved: the game is being built. Wait for the build to finish."); return false; }
        if (JobService.running != null || JobService.pending(this) != null) { toastDialog("Not moved: finish or cancel the download or import on the Game files page first."); return false; }
        if (sQueue != null && sQueue.pendingCount() > 0) { toastDialog("Not moved: mods are downloading. Wait for them, or cancel them, first."); return false; }
        if (TestControl.gamePid(this) > 0) { toastDialog("Not moved: close the game first."); return false; }
        return true;
    }

    /** Every per-process object points at the old folder: start the launcher afresh. */
    private void restartLauncher() {
        startActivity(Intent.makeRestartActivityTask(new android.content.ComponentName(this, LauncherActivity.class)));
        Runtime.getRuntime().exit(0);
    }

    /** Settings > Storage: move everything to internal storage, the SD card or a custom folder, then restart the launcher. */
    private void moveStorage(int place, File custom) {
        if (!canMoveStorage()) return;
        long size = Storage.size(install.files);
        String where = place == Storage.CUSTOM ? custom.getAbsolutePath() : place == Storage.SD ? "the SD card" : "internal storage";
        ff(new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setMessage("Move your game files, mods, saves and save states (" + ModDownloader.human(size) + ") to "
                        + where + "? The launcher restarts when it is done.")
                .setPositiveButton("Move", (d, w) -> background(() -> {
                    Storage.move(this, place, custom, this::progress);
                    runOnUiThread(this::restartLauncher);
                    return null;
                }))
                .setNegativeButton("Cancel", null)
                .show());
    }

    /** Settings > Storage > Custom folder (GitHub #61): All files access first, then the folder. */
    private void customStorage() {
        if (!canMoveStorage()) return;
        if (Storage.allFilesAccess(this)) { pick(PICK_STORAGE); return; }
        ff(new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setMessage("To keep your files in a folder you choose, FF7Recomp needs Android's \"All files access\" "
                        + "(apps can only use their own Android/data folder without it). Turn it on for FF7Recomp on the "
                        + "next screen, then go back and pick the folder.")
                .setPositiveButton("Continue", (d, w) -> {
                    wantStorageFolder = true;
                    if (android.os.Build.VERSION.SDK_INT >= 30) {
                        try {
                            startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                    Uri.parse("package:" + getPackageName())));
                        } catch (Exception e) {
                            startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                        }
                    } else {
                        requestPermissions(new String[] { android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                                android.Manifest.permission.READ_EXTERNAL_STORAGE }, 10);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show());
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (req == 10 && wantStorageFolder) storageAccessBack();
    }

    /** The folder picked for Settings > Storage > Custom folder. */
    private void pickedStorageFolder(Uri tree) {
        File to = Storage.treeToPath(tree);
        String why = Storage.customRefusal(this, to);
        if (why != null) { toastDialog("Not moved. " + why); return; }
        if (to.isDirectory() && !Storage.emptyEnough(to)) {
            if (!Storage.looksLikeOurs(to)) {
                toastDialog("Not moved: " + to.getAbsolutePath() + " already has other files in it. Pick an empty folder, "
                        + "or create a new one in the folder picker.");
                return;
            }
            ff(new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setMessage(to.getAbsolutePath() + " already has FF7Recomp's files in it (a backup, or a synced copy). "
                            + "Use the files there? Your current files stay where they are ("
                            + install.files.getAbsolutePath() + "); delete them yourself once you no longer need them. "
                            + "The launcher restarts.")
                    .setPositiveButton("Use them", (d, w) -> {
                        try { Storage.useCustom(this, to); restartLauncher(); }
                        catch (java.io.IOException e) { toastDialog("Not changed: " + e.getMessage()); }
                    })
                    .setNegativeButton("Cancel", null)
                    .show());
            return;
        }
        moveStorage(Storage.CUSTOM, to);
    }

    // ------------------------------------------------------------- Sound, Game options
    /** Settings > Sound and Game options: FFNx.toml keys (FfnxOptions; FF9Recomp's Memoria page). */
    private void ffnxSections(LinearLayout col) {
        java.util.Map<String, String> last = lastToml();
        col.addView(heading("Sound"));
        col.addView(text("The game's own volumes, under the device's volume. Default: as the game or a mod sets it.", 14, DIM));
        for (FfnxOptions.Opt o : FfnxOptions.SOUND) ffnxRow(col, o, last);
        col.addView(space(16));
        col.addView(heading("Game options"));
        col.addView(text("FFNx settings (its FFNx.toml, written before each start). A mod may set these too; what you "
                + "change here wins. A ticked box shows how the game last started.", 14, DIM));
        for (FfnxOptions.Opt o : FfnxOptions.GAME) ffnxRow(col, o, last);
        col.addView(small("Put Sound and Game options back to the defaults", v -> {
            SharedPreferences.Editor e = prefs.edit();
            for (FfnxOptions.Opt o : FfnxOptions.all()) e.remove(FfnxOptions.PREFIX + o.key);
            e.apply();
            refresh();
        }));
        col.addView(space(16));
    }

    private void ffnxRow(LinearLayout col, FfnxOptions.Opt o, java.util.Map<String, String> last) {
        final String key = FfnxOptions.PREFIX + o.key;
        if (o.isSwitch()) {
            CheckBox cb = new CheckBox(this);
            cb.setText(o.label + (o.help == null ? "" : "\n" + o.help));
            cb.setTextColor(TEXT);
            cb.setTextSize(16);
            String v = prefs.getString(key, null);
            if (v == null) v = last.get(o.key);
            cb.setChecked(v == null ? o.defOn : v.equals("true"));
            cb.setOnCheckedChangeListener((b, on) -> prefs.edit().putString(key, on ? "true" : "false").apply());
            col.addView(cb);
            return;
        }
        // a volume: - and + step it, the middle button puts it back to Default
        final int[] at = { FfnxOptions.index(o, prefs.getString(key, null)) };
        final int full = FfnxOptions.index(o, "100");   // where Default stands
        ViewGroup r = buttonRow();
        final Button mid = small(o.label + ": " + o.names[at[0]], null);
        Runnable store = () -> {
            if (at[0] == 0) prefs.edit().remove(key).apply(); else prefs.edit().putString(key, o.values[at[0]]).apply();
            mid.setText(o.label + ": " + o.names[at[0]]);
        };
        r.addView(compact(small("−", v -> { at[0] = Math.min(o.values.length - 1, (at[0] == 0 ? full : at[0]) + 1); store.run(); })));
        mid.setOnClickListener(v -> { at[0] = 0; store.run(); });
        r.addView(mid);
        r.addView(compact(small("+", v -> { at[0] = Math.max(1, (at[0] == 0 ? full : at[0]) - 1); store.run(); })));
        if (o.help != null) r.addView(text(o.help, 13, DIM));
        col.addView(r);
    }

    /** FFNx.toml as the game last started with it: key -> value (quotes off). */
    private java.util.Map<String, String> lastToml() {
        java.util.Map<String, String> m = new java.util.HashMap<>();
        File f = new File(install.game, "FFNx.toml");
        if (!f.isFile()) return m;
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("^([A-Za-z0-9_]+)\\s*=\\s*(.*?)\\s*$");
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f))) {
            for (String line; (line = r.readLine()) != null; ) {
                java.util.regex.Matcher mm = p.matcher(line);
                if (mm.matches()) m.put(mm.group(1), mm.group(2).replace("\"", ""));
            }
        } catch (java.io.IOException ignored) {}
        return m;
    }

    // ------------------------------------------------------------------- plumbing
    private interface Job { String run() throws Exception; }

    private boolean warmingMods;

    /** Read the mods refresh() could not, off the UI thread, then refresh again. */
    private void warmMods() {
        if (warmingMods) return;
        warmingMods = true;
        new Thread(() -> {
            long t0 = android.os.SystemClock.uptimeMillis();
            try { library.warm(); } catch (Throwable t) { android.util.Log.w("FF7Recomp", "mod info: " + t); }
            android.util.Log.i("FF7Recomp", "mod info read in the background: " + (android.os.SystemClock.uptimeMillis() - t0) + " ms");
            runOnUiThread(() -> {
                warmingMods = false;
                if (!isFinishing() && !isDestroyed()) refresh();
            });
        }, "mod-info").start();
    }

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

    /** Quit (GitHub #42): the launcher closes; a game still running is closed too, if you say so. */
    private void quitApp() {
        int pid = TestControl.gamePid(this);
        if (pid <= 0) { finishAndRemoveTask(); return; }
        ff(new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setMessage("The game is still running. Close it too? Anything since your last save is lost.")
                .setPositiveButton("Close both", (d, w) -> {
                    android.os.Process.killProcess(pid);
                    finishAndRemoveTask();
                })
                .setNegativeButton("Cancel", null).show());
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
        Compat.hideSystemBars(getWindow());
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
        b.setTextSize(smallScreen ? 13 : 16);
        b.setSingleLine(true);
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
        b.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                dp(which == null ? (smallScreen ? 34 : 42) : (smallScreen ? 28 : 34))));
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
            // a clear mark on the one the controller is on (a faint ripple was all there was:
            // mod options could not be told apart with a D-pad, GitHub #47)
            GradientDrawable hot = new GradientDrawable();
            hot.setColor(0x40FFFFFF);
            hot.setStroke(Math.max(1, dp(1)), 0xFFE2E6F0);
            hot.setCornerRadius(dp(6));
            android.graphics.drawable.StateListDrawable sl = new android.graphics.drawable.StateListDrawable();
            sl.addState(new int[] { android.R.attr.state_focused }, hot);
            sl.addState(new int[] {}, new android.graphics.drawable.ColorDrawable(0));
            c.setBackground(sl);
            if (c.getPaddingRight() < dp(8)) c.setPadding(c.getPaddingLeft(), c.getPaddingTop(), dp(8), c.getPaddingBottom());
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
            if (testW > 0) {   // TestControl SCREEN: the dialog within that screen too
                d.getWindow().setGravity(Gravity.TOP | Gravity.START);
                d.getWindow().setLayout(Math.max(testW, testH), Math.min(testW, testH));
            }
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
        TextView t = text(s, smallScreen ? 18 : 22, TEXT);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, 0, 0, dp(8));
        return t;
    }

    private Button button(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(smallScreen ? 15 : 17);
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
        b.setTextSize(smallScreen ? 14 : 15);
        if (smallScreen) {
            b.setMinWidth(0);
            b.setMinimumWidth(0);
            b.setPadding(dp(12), b.getPaddingTop(), dp(12), b.getPaddingBottom());
        }
        return b;
    }

    /** Buttons side by side, going on to the next line when they don't fit rather than being
     *  squeezed or cut off (GitHub #47). */
    private ViewGroup buttonRow() {
        return new FlowRow(this);
    }

    /** A line's details and its buttons: side by side, or on a small screen the buttons under the
     *  details, which then keep the whole width. */
    private LinearLayout details(View info, View buttons) {
        LinearLayout r = smallScreen ? column() : row();
        if (smallScreen) {
            r.addView(info, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            r.addView(buttons, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        } else {
            r.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            r.addView(buttons, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        return r;
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
