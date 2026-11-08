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
        catalog = new ModCatalog(install.files);
        downloader = new ModDownloader(install.files, library.dir);
        catalog.rebuild();
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
        refresh();
    }

    // ------------------------------------------------------------------- pages
    private void show(String which) {
        page = which;
        refresh();
    }

    private void refresh() {
        library.load();
        catalog.prune(library.dir);
        status.setText((install.hasGame() ? "Game files: ready" : "Game files: not imported yet")
                + "\nMods: " + library.enabledCount() + " of " + library.mods.size() + " enabled"
                + (catalog.updates().isEmpty() ? "" : "\nMod updates: " + catalog.updates().size())
                + "\nFrame rate: " + fpsName(prefs.getInt("fps", 3)) + (prefs.getBoolean("turbo", false) ? ", 2\u00d7 speed" : ""));
        playButton.setEnabled(install.hasGame() && !busy);
        content.removeAllViews();
        switch (page) {
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
        ModCatalog.Installed fromCat = catalog.installedByFile(m.file);
        ModCatalog.Mod catMod = fromCat == null ? null : catalog.byId(fromCat.id);
        if (catMod != null && catalog.updateAvailable(catMod))
            info.addView(text("Update available: " + fromCat.versionText + " → " + catMod.versionText, 13, ACCENT));
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
        ModCatalog.Installed in = catalog.installedOf(m);
        if (in == null) r.addView(small("Install", v -> installMods(java.util.Collections.singletonList(m), false)));
        else if (catalog.updateAvailable(m)) r.addView(small("Update to " + m.versionText, v -> installMods(java.util.Collections.singletonList(m), true)));
        else r.addView(text("Installed  ", 15, ACCENT));
        r.setFocusable(false);
        return r;
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
        boolean browserOnly = !m.links.isEmpty();
        for (String l : m.links) if (ModDownloader.resolve(l) != null) browserOnly = false;
        if (browserOnly) col.addView(text("\nThis mod is downloaded from a web page: open it, download, then add the file under Mods.", 14, 0xFFFFC060));
        AlertDialog.Builder d = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(m.name)
                .setView(scroll(col))
                .setNegativeButton("Close", null);
        if (in == null && !browserOnly) d.setPositiveButton("Install", (x, w) -> installMods(java.util.Collections.singletonList(m), false));
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
    private void installMods(java.util.List<ModCatalog.Mod> wanted, boolean update) {
        if (busy) return;
        java.util.List<ModCatalog.Mod> order = new java.util.ArrayList<>();
        for (ModCatalog.Mod m : wanted) addWithRequirements(m, order, 0);
        background(() -> {
            StringBuilder done = new StringBuilder(), failed = new StringBuilder();
            int k = 0;
            for (ModCatalog.Mod m : order) {
                k++;
                ModCatalog.Installed in = catalog.installedOf(m);
                if (in != null && !catalog.updateAvailable(m)) continue;
                // an update keeps the library name, so the load order and options stay
                String base = in != null ? in.file.replaceAll("(?i)\\.iro$", "") : m.name;
                final String label = (order.size() > 1 ? "(" + k + "/" + order.size() + ") " : "") + m.name;
                ModDownloader.Result r = downloader.install(m.id, base, m.links.toArray(new String[0]), m.sizeKb * 1024,
                        (what, got, total) -> progress(what + " " + label + (got > 0 ? ": " + ModDownloader.human(got)
                                + (total > 0 ? " of " + ModDownloader.human(total) : "") : "")));
                if (r.error != null) { failed.append(m.name).append(": ").append(r.error).append('\n'); continue; }
                catalog.markInstalled(m, r.file);
                if (in == null) enableNew(r.file);
                done.append(m.name).append(in != null ? " updated to " + m.versionText : " installed").append('\n');
            }
            String msg = (done.length() > 0 ? done.toString() : "") + (failed.length() > 0 ? "\nNot installed:\n" + failed : "");
            return msg.trim().isEmpty() ? null : msg.trim();
        });
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
        CheckBox turbo = new CheckBox(this);
        turbo.setText("2\u00d7 speed (turbo)\nThe whole game runs twice as fast, shown at 120 FPS with the 60 FPS or "
                + "120 FPS setting. In game, L3 then L2 or R2 switches it off and on.");
        turbo.setTextColor(TEXT);
        turbo.setTextSize(16);
        turbo.setChecked(prefs.getBoolean("turbo", false));
        turbo.setOnCheckedChangeListener((b, on) -> { prefs.edit().putBoolean("turbo", on).apply(); refresh(); });
        col.addView(turbo);
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
            install.writeFfnxToml(prefs.getInt("fps", 3), prefs.getBoolean("turbo", false), prefs.getInt("aspect", 2), prefs.getBoolean("show_fps", false), library.mods);
            install.writeQuickboot(quickbootOverride != null ? quickbootOverride : prefs.getString("quickboot", ""));
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
