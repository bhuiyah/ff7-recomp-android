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
    private static final int PICK_GAME = 1, PICK_IRO = 2, PICK_FOLDER = 3;
    private static final int BG = 0xFF0B1020, PANEL = 0xFF141B33, ACCENT = 0xFF3D7BFF, TEXT = 0xFFE8ECF8, DIM = 0xFF8C96B4;

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

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        install = new GameInstall(this);
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
        side.addView(button("Get mods", v -> show("catalog")), navParams());
        downloadsButton = button("Downloads", v -> show("downloads"));
        side.addView(downloadsButton, navParams());
        side.addView(button("Save states", v -> show("states")), navParams());
        side.addView(button("Settings", v -> show("settings")), navParams());
        side.addView(button("Game files", v -> show("game")), navParams());
        // the menu scrolls when it is taller than the screen (Play, five pages, the status)
        ScrollView sideScroll = new ScrollView(this);
        sideScroll.setVerticalScrollBarEnabled(false);
        sideScroll.addView(side);
        root.addView(sideScroll, new LinearLayout.LayoutParams(dp(300), ViewGroup.LayoutParams.MATCH_PARENT));

        content = new FrameLayout(this);
        content.setBackground(rounded(PANEL));
        content.setPadding(dp(20), dp(16), dp(20), dp(16));
        root.addView(content, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        setContentView(root);
        hideSystemBars();
        show(install.hasGame() ? "mods" : "game");
        // catalogs older than a day are fetched quietly in the background (as 7th Heaven does)
        new Thread(() -> { catalog.refresh(false); runOnUiThread(this::refresh); }).start();
        playButton.requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemBars();
        queue.setListener(this::queueChanged);
        refresh();
    }

    @Override
    protected void onPause() {
        super.onPause();
        queue.setListener(null);
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
        playButton.setEnabled(install.hasGame() && !busy);
        content.removeAllViews();
        actionSlots.clear();
        progressViews.clear();
        queueSig = queueSignature();
        switch (page) {
            case "downloads": content.addView(downloadsPage()); break;
            case "settings": content.addView(settingsPage()); break;
            case "states": content.addView(statesPage()); break;
            case "game": content.addView(gamePage()); break;
            case "catalog": content.addView(catalogPage()); break;
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
        actions.addView(button("Get mods…", v -> show("catalog")));
        java.util.List<ModCatalog.Mod> ups = catalog.updates();
        if (!ups.isEmpty()) actions.addView(button("Update all (" + ups.size() + ")", v -> installMods(ups, true)));
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
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Mod conflicts")
                .setView(scroll(warningList(r.lines, "Where two mods replace the same game file, the one higher in the Mods list is used "
                        + "(use ▲ ▼ to change it). Overlaps are often fine, such as a texture pack over a model pack; "
                        + "a ⚠ means a mod itself says something is wrong.")))
                .setPositiveButton("OK", null)
                .show();
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


    // ------------------------------------------------------------------- Get mods (catalogs)
    private View catalogPage() {
        LinearLayout col = column();
        col.addView(heading("Get mods"));
        int total = catalog.mods.size();
        StringBuilder sub = new StringBuilder();
        sub.append(total).append(total == 1 ? " mod" : " mods").append(" in ");
        for (int i = 0; i < catalog.subs.size(); i++) sub.append(i > 0 ? ", " : "").append(catalog.subs.get(i).name);
        col.addView(text(sub.toString(), 14, DIM));

        LinearLayout bar = row();
        EditText q = new EditText(this);
        q.setHint("Search name, author, tag…");
        q.setText(catQuery);
        q.setTextColor(TEXT);
        q.setHintTextColor(DIM);
        q.setSingleLine(true);
        q.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        q.setOnEditorActionListener((v, id, ev) -> { catQuery = v.getText().toString(); refresh(); return true; });
        bar.addView(q, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        bar.addView(small("Search", v -> { catQuery = q.getText().toString(); refresh(); }));
        bar.addView(small("Refresh catalogs", v -> background(() -> {
            progress("Checking the mod catalogs");
            String err = catalog.refresh(true);
            return err == null ? null : "Some catalogs could not be checked:\n" + err;
        })));
        java.util.List<ModCatalog.Mod> ups = catalog.updates();
        if (!ups.isEmpty()) bar.addView(small("Update all (" + ups.size() + ")", v -> installMods(ups, true)));
        col.addView(bar);

        // categories (7th Heaven's), plus All and Installed
        LinearLayout chips = row();
        chips.addView(chip("All", catCategory == null && !catInstalledOnly, v -> { catCategory = null; catInstalledOnly = false; refresh(); }));
        chips.addView(chip("Installed", catInstalledOnly, v -> { catInstalledOnly = !catInstalledOnly; refresh(); }));
        for (String c : ModCatalog.CATEGORIES) {
            boolean any = false;
            for (ModCatalog.Mod m : catalog.mods) if (c.equalsIgnoreCase(m.category)) { any = true; break; }
            if (any) chips.addView(chip(c, c.equals(catCategory), v -> { catCategory = c.equals(catCategory) ? null : c; refresh(); }));
        }
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.addView(chips);
        col.addView(hs);

        if (total == 0) {
            col.addView(text("\nNo catalog loaded yet. Check the internet connection and press Refresh catalogs.", 16, DIM));
            for (ModCatalog.Subscription sc : catalog.subs) if (sc.error != null) col.addView(text(sc.name + ": " + sc.error, 13, 0xFFFF8080));
        }
        java.util.List<ModCatalog.Mod> found = catalog.search(catQuery, catCategory, catInstalledOnly);
        if (total > 0 && found.isEmpty()) col.addView(text("\nNothing matches.", 16, DIM));
        for (ModCatalog.Mod m : found) col.addView(catalogRow(m));
        return scroll(col);
    }

    private View catalogRow(ModCatalog.Mod m) {
        LinearLayout r = row();
        r.setPadding(0, dp(8), 0, dp(8));
        LinearLayout info = column();
        TextView name = text(m.name, 18, TEXT);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        info.addView(name);
        StringBuilder b = new StringBuilder();
        if (!m.author.isEmpty()) b.append("by ").append(m.author).append("  ·  ");
        if (!m.category.isEmpty()) b.append(m.category).append("  ·  ");
        if (!m.versionText.isEmpty()) b.append("v").append(m.versionText);
        if (!m.sizeText().isEmpty()) b.append("  ·  ").append(m.sizeText());
        info.addView(text(b.toString(), 13, DIM));
        r.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        r.addView(small("Details", v -> modPage(m)));
        LinearLayout slot = row();
        fillAction(slot, m);
        actionSlots.put(m.id, slot);
        r.addView(slot);
        r.setFocusable(false);
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
            Button b = small(label, v -> show("downloads"));
            slot.addView(b);
            if (hadFocus) b.requestFocus();
            return;
        }
        if (in == null) slot.addView(small("Install", v -> installMods(java.util.Collections.singletonList(m), false)));
        else if (catalog.updateAvailable(m)) slot.addView(small("Update to " + m.versionText, v -> installMods(java.util.Collections.singletonList(m), true)));
        else slot.addView(text("Installed  ", 15, ACCENT));
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
        s.append(install.hasGame() ? "Game files: ready" : "Game files: not imported yet")
                .append("\nMods: ").append(library.enabledCount()).append(" of ").append(library.mods.size()).append(" enabled");
        if (!catalog.updates().isEmpty()) s.append("\nMod updates: ").append(catalog.updates().size());
        DownloadQueue.Item run = queue.running();
        int waiting = queue.pendingCount() - (run != null ? 1 : 0);
        if (run != null) s.append("\n").append(run.mod.name).append(": ").append(queueLabel(run, false));
        if (waiting > 0) s.append("\n").append(waiting).append(" more queued");
        s.append("\nFrame rate: ").append(fpsName(prefs.getInt("fps", 3))).append(boosterSpeed() > 1 ? ", " + boosterSpeed() + "× speed" : "");
        if (prefs.getBoolean("no_encounters", false) || prefs.getBoolean("battle_plus", false))
            s.append("\nBoosters: ").append(prefs.getBoolean("no_encounters", false) ? "no random battles" : "")
                    .append(prefs.getBoolean("no_encounters", false) && prefs.getBoolean("battle_plus", false) ? ", " : "")
                    .append(prefs.getBoolean("battle_plus", false) ? "battle enhancement" : "");
        status.setText(s);
        int n = queue.pendingCount();
        downloadsButton.setText(n > 0 ? "Downloads (" + n + ")" : "Downloads");
    }

    private Button chip(String label, boolean on, View.OnClickListener l) {
        Button b = small(label, l);
        if (on) {
            GradientDrawable d = new GradientDrawable();
            d.setColor(ACCENT);
            d.setCornerRadius(dp(10));
            b.setBackground(d);
        }
        return b;
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
        d.show();
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
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Possible conflicts")
                .setView(scroll(warningList(warn, "Catalogs do not list the files inside a mod, so this is a guess from what the mods are. "
                        + "After the download, the Mods page shows the exact files they share.")))
                .setPositiveButton(todo.size() == 1 ? "Install anyway" : "Install all anyway", (d, w) -> enqueue(todo))
                .setNegativeButton("Cancel", null)
                .show();
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
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setMessage("Remove " + m.name + "? Its file is deleted from the device.")
                .setPositiveButton("Remove", (x, w) -> {
                    ModDownloader.deleteTree(new File(library.dir, in.file));
                    catalog.forget(m.id);
                    library.load();
                    saveProfile();
                    refresh();
                })
                .setNegativeButton("Cancel", null)
                .show();
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
            new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("Add a mod catalog")
                    .setView(f)
                    .setPositiveButton("Add", (x, w) -> {
                        String err = catalog.addSubscription(name.getText().toString(), url.getText().toString());
                        if (err != null) { toastDialog(err); return; }
                        background(() -> { progress("Checking the new catalog"); String e = catalog.refresh(false); return e; });
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        }));
        return col;
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
        // Game boosters (docs/ROADMAP.md M8): how each game starts; in game the bottom screen's
        // Speed / Battles / Battle+ buttons and FFNx's L3 shortcuts change them.
        col.addView(space(16));
        col.addView(heading("Game boosters"));
        col.addView(text("How the game starts. In game: the bottom screen's Speed, Battles and Battle+ buttons, or L3 then "
                + "R1/L1 (speed), Circle (random battles), Cross (battle enhancement). Icons in the top-right corner show what is on.", 14, DIM));
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
        col.addView(space(16));
        col.addView(heading("Bottom screen in battle"));
        col.addView(text("Where the battle windows go on the Thor's second screen. Mods that restyle the battle "
                + "windows (Finishing Touch, Enhanced Stock UI) need them shown as the game draws them.", 14, DIM));
        RadioGroup bw = new RadioGroup(this);
        String[][] bwModes = {
            { "auto", "Automatic (as drawn when a user interface mod is on)" },
            { "split", "Enlarged: names and commands above, HP/MP below" },
            { "whole", "As the game draws them (one strip)" },
            { "off", "Off: the windows stay on the top screen" },
        };
        String curBw = prefs.getString("battle_windows", "auto");
        for (String[] mde : bwModes) {
            RadioButton rb = new RadioButton(this);
            rb.setText(mde[1]);
            rb.setTextColor(TEXT);
            rb.setTextSize(16);
            rb.setId(View.generateViewId());
            rb.setOnClickListener(x -> prefs.edit().putString("battle_windows", mde[0]).apply());
            bw.addView(rb);
            if (mde[0].equals(curBw)) rb.setChecked(true);
        }
        col.addView(bw);
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

    // ------------------------------------------------------------------- save states
    private View statesPage() {
        if (states == null) states = new SaveStates(install.files);
        LinearLayout col = column();
        col.addView(heading("Save states"));
        col.addView(text("In game: Select+L1 saves to the current slot, Select+R1 loads it, Select+Left / Right "
                + "picks another slot. \"Play from here\" starts the game at that moment.", 14, DIM));
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
            if (sl.exists) r.addView(small("Delete", v -> new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setMessage("Delete save state slot " + k + "? This cannot be undone.")
                    .setPositiveButton("Delete", (d, w) -> { states.delete(k); refresh(); })
                    .setNegativeButton("Cancel", null)
                    .show()));
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
        background(() -> {
            progress("Preparing");
            library.load();
            library.save();
            install.installRuntime();
            install.writeFfnxToml(prefs.getInt("fps", 3), boosterSpeed() > 1, prefs.getInt("aspect", 2), prefs.getBoolean("show_fps", false), library.mods);
            install.writeQuickboot(quickbootOverride != null ? quickbootOverride : prefs.getString("quickboot", ""));
            install.writeBoosters(boosterSpeed(), prefs.getBoolean("no_encounters", false), prefs.getBoolean("battle_plus", false));
            install.writeCompanion(battleWindowsMode());
            runOnUiThread(() -> startActivity(new Intent(this, GameActivity.class)));
            return null;
        });
    }

    /** The bottom screen's battle windows for this game: "split", "whole" or "off". Automatic
     *  shows the strip as drawn when an enabled mod restyles the user interface. */
    private String battleWindowsMode() {
        String m = prefs.getString("battle_windows", "auto");
        if (!m.equals("auto")) return m;
        for (ModLibrary.Mod lm : library.mods) {
            if (!lm.enabled || lm.error != null) continue;
            String cat = lm.category == null ? "" : lm.category;
            ModCatalog.Installed in = catalog.installedByFile(lm.file);
            ModCatalog.Mod cm = in == null ? null : catalog.byId(in.id);
            if (cm != null && cat.isEmpty()) cat = cm.category;
            if (cat.equalsIgnoreCase("User Interface")) return "whole";
        }
        return "split";
    }

    /** The start speed (1-3); older settings had only the 2x "turbo" switch. */
    private int boosterSpeed() {
        return prefs.getInt("speed", prefs.getBoolean("turbo", false) ? 2 : 1);
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
