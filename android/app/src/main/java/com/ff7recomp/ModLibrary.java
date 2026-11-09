package com.ff7recomp;

import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import org.json.JSONArray;
import org.json.JSONObject;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * The mods the launcher manages: every .iro archive and folder in files/mods, in the
 * player's priority order (first wins, as in 7th Heaven), each enabled or not, with its
 * option choices. Saved as files/mods/profile.xml, which the game's mod overlay reads at
 * boot (src/mods/mods.h); disabled mods stay in the file so order and options survive.
 */
final class ModLibrary {
    private static final String TAG = "FF7Recomp";

    static { System.loadLibrary("ff7launcher"); }
    private static native String nativeModInfo(String path);
    private static native String nativeConflicts(String profilePath);

    static final class Option {
        String id, name, type, description;
        int defaultValue;
        final List<Integer> valueIds = new ArrayList<>();
        final List<String> valueNames = new ArrayList<>();
    }

    static final class Mod {
        String file;                 // name in files/mods (archive or folder)
        boolean enabled;
        final Map<String, Integer> chosen = new LinkedHashMap<>();   // option id -> value
        // from mod.xml
        String name, author, version, category, description, kind, error;
        /** mod.xml's category says it restyles the user interface (fonts, menus, windows). */
        boolean isUi() { return error == null && category != null && category.trim().equalsIgnoreCase("User Interface"); }
        final List<Option> options = new ArrayList<>();
        final List<String> unsupported = new ArrayList<>();
        final List<String[]> ffnx = new ArrayList<>();        // [key, value, conditions "id=v;id=v"]
        String id = "";                                        // mod.xml ID (lower case)
        final List<String[]> requires = new ArrayList<>();    // <Compatibility>: [mod id, why]
        final List<String[]> forbids = new ArrayList<>();
        // hext patches as the last game run applied them (mods/hext_status.txt, written by
        // src/host/ff7_hext.cpp); null if the mod had none or the game has not run with it
        String hext;
        boolean hextProblem;                                   // a file stopped, or a code patch had no effect

        String title() { return name != null && !name.isEmpty() ? name : file; }
        int value(Option o) {
            Integer v = chosen.get(o.id.toLowerCase(Locale.ROOT));
            return v != null ? v : o.defaultValue;
        }
        /** An FFNxConfig entry's conditions ("id=v;id=v", from its attributes) all hold, as
         *  7th Heaven applies them: an option that does not exist never matches. */
        boolean optionsMatch(String when) {
            if (when == null || when.isEmpty()) return true;
            for (String c : when.split(";")) {
                int eq = c.indexOf('=');
                if (eq <= 0) continue;
                String id = c.substring(0, eq).trim(), want = c.substring(eq + 1).trim();
                Option found = null;
                for (Option o : options) if (o.id.equalsIgnoreCase(id)) found = o;
                if (found == null) return false;
                try { if (value(found) != Integer.parseInt(want)) return false; }
                catch (NumberFormatException e) { return false; }
            }
            return true;
        }
    }

    final File dir, profile;
    // Replaced whole (never emptied and refilled) and copy-on-write: the conflict check reads it
    // on a background thread while the launcher reloads it.
    volatile List<Mod> mods = new java.util.concurrent.CopyOnWriteArrayList<>();

    ModLibrary(File filesDir) {
        dir = new File(filesDir, "mods");
        profile = new File(dir, "profile.xml");
        dir.mkdirs();
    }

    /** Re-read the folder and the saved profile. New mods are added at the end, disabled. */
    void load() {
        List<Mod> loaded = new ArrayList<>();
        Map<String, Mod> saved = new LinkedHashMap<>();
        if (profile.exists()) {
            try {
                Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(profile);
                NodeList list = doc.getElementsByTagName("Mod");
                for (int i = 0; i < list.getLength(); i++) {
                    Element e = (Element) list.item(i);
                    Mod m = new Mod();
                    m.file = e.getAttribute("Path");
                    m.enabled = !"false".equalsIgnoreCase(e.getAttribute("Enabled")) && !"0".equals(e.getAttribute("Enabled"));
                    NodeList opts = e.getElementsByTagName("Option");
                    for (int k = 0; k < opts.getLength(); k++) {
                        Element o = (Element) opts.item(k);
                        try { m.chosen.put(o.getAttribute("ID").toLowerCase(Locale.ROOT), Integer.parseInt(o.getAttribute("Value"))); }
                        catch (NumberFormatException ignored) { }
                    }
                    saved.put(m.file.toLowerCase(Locale.ROOT), m);
                }
            } catch (Exception ex) {
                Log.w(TAG, "profile.xml unreadable, starting fresh: " + ex);
            }
        }
        List<String> present = new ArrayList<>();
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) {
            String n = f.getName();
            if (f.isDirectory() || n.toLowerCase(Locale.ROOT).endsWith(".iro")) present.add(n);
        }
        for (Mod m : saved.values()) {                        // saved order first
            for (String p : present) if (p.equalsIgnoreCase(m.file)) { m.file = p; loaded.add(m); break; }
        }
        List<Mod> added = new ArrayList<>();
        for (String p : present) {                             // then anything new
            if (saved.containsKey(p.toLowerCase(Locale.ROOT))) continue;
            Mod m = new Mod();
            m.file = p;
            loaded.add(m);
            added.add(m);
        }
        for (Mod m : loaded) readInfo(m);
        // A new user interface mod goes above the content loaded: its fonts and menu textures
        // pair with its own hext (the letter widths), so a content mod above it that ships the
        // game's fonts too (Echo-S) would draw them with the wrong widths. 7th Heaven's
        // catalog order puts interface loaded first as well.
        for (Mod m : added) {
            if (!m.isUi()) continue;
            int at = firstContent(loaded);
            if (at < 0 || at >= loaded.indexOf(m)) continue;
            loaded.remove(m);
            loaded.add(at, m);
        }
        mods = new java.util.concurrent.CopyOnWriteArrayList<>(loaded);
        readHextStatus();
    }

    /** One line per mod from the last run's hext_status.txt (TSV: mod, file, state, data,
     *  lifted, interpreted, without effect). */
    private void readHextStatus() {
        File f = new File(dir, "hext_status.txt");
        if (!f.isFile()) return;
        Map<String, int[]> sums = new LinkedHashMap<>();       // mod -> files, data, lifted, interpreted, none, problems
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(
                new java.io.FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] t = line.split("\t");
                if (t.length < 7) continue;
                int[] s = sums.computeIfAbsent(t[0].toLowerCase(Locale.ROOT), k -> new int[6]);
                s[0]++;
                for (int i = 0; i < 4; i++) {
                    try { s[1 + i] += Integer.parseInt(t[3 + i]); } catch (NumberFormatException ignored) { }
                }
                if (!t[2].equals("applied") && !t[2].equals("delayed")) s[5]++;
            }
        } catch (Exception ex) {
            Log.w(TAG, "hext_status.txt unreadable: " + ex);
            return;
        }
        for (Mod m : mods) {
            int[] s = sums.get(m.file.toLowerCase(Locale.ROOT));
            if (s == null) continue;
            StringBuilder b = new StringBuilder();
            b.append(s[0]).append(s[0] == 1 ? " hext patch" : " hext patches");
            List<String> parts = new ArrayList<>();
            if (s[1] > 0) parts.add(s[1] + " data");
            if (s[2] > 0) parts.add(s[2] + " code lifted");
            if (s[3] > 0) parts.add(s[3] + " code interpreted");
            if (s[4] > 0) parts.add(s[4] + " code without effect");
            if (s[5] > 0) parts.add(s[5] + (s[5] == 1 ? " file stopped early" : " files stopped early"));
            if (!parts.isEmpty()) b.append(": ").append(String.join(", ", parts));
            b.append(" (last run)");
            m.hext = b.toString();
            m.hextProblem = s[4] > 0 || s[5] > 0;
        }
    }

    private void readInfo(Mod m) {
        try {
            JSONObject j = new JSONObject(nativeModInfo(new File(dir, m.file).getAbsolutePath()));
            m.error = j.optString("error", null);
            if (m.error != null) return;
            m.kind = j.optString("kind");
            m.name = j.optString("name");
            m.author = j.optString("author");
            m.version = j.optString("version");
            m.category = j.optString("category");
            m.description = j.optString("description");
            m.id = j.optString("id").toLowerCase(Locale.ROOT);
            JSONArray rq = j.optJSONArray("requires");
            for (int i = 0; rq != null && i < rq.length(); i++)
                m.requires.add(new String[] { rq.getJSONObject(i).optString("id"), rq.getJSONObject(i).optString("why") });
            JSONArray fb = j.optJSONArray("forbids");
            for (int i = 0; fb != null && i < fb.length(); i++)
                m.forbids.add(new String[] { fb.getJSONObject(i).optString("id"), fb.getJSONObject(i).optString("why") });
            JSONArray opts = j.optJSONArray("options");
            for (int i = 0; opts != null && i < opts.length(); i++) {
                JSONObject o = opts.getJSONObject(i);
                Option op = new Option();
                op.id = o.optString("id");
                op.name = o.optString("name", op.id);
                op.type = o.optString("type");
                op.description = o.optString("description");
                op.defaultValue = o.optInt("default");
                JSONArray vals = o.optJSONArray("values");
                for (int k = 0; vals != null && k < vals.length(); k++) {
                    op.valueIds.add(vals.getJSONObject(k).optInt("value"));
                    op.valueNames.add(vals.getJSONObject(k).optString("name"));
                }
                m.options.add(op);
            }
            JSONArray un = j.optJSONArray("unsupported");
            for (int i = 0; un != null && i < un.length(); i++) m.unsupported.add(un.getString(i));
            JSONArray fx = j.optJSONArray("ffnx");
            for (int i = 0; fx != null && i < fx.length(); i++)
                m.ffnx.add(new String[] { fx.getJSONObject(i).optString("key"), fx.getJSONObject(i).optString("value"),
                                          fx.getJSONObject(i).optString("when") });
        } catch (Exception ex) {
            m.error = ex.toString();
        }
    }

    /** The saved profile's enabled mods loaded as the game loads them: the game files two of
     *  them both replace, and their Compatibility declarations (src/mods ff7_mods_conflicts_json). */
    JSONObject conflicts() {
        try { return new JSONObject(nativeConflicts(profile.getAbsolutePath())); }
        catch (Exception e) { Log.w(TAG, "conflict check failed: " + e); return null; }
    }

    Mod byFile(String file) {
        for (Mod m : mods) if (m.file.equalsIgnoreCase(file)) return m;
        return null;
    }

    /** The first mod that is not a user interface mod, or -1. */
    private static int firstContent(List<Mod> mods) {
        for (int i = 0; i < mods.size(); i++) if (!mods.get(i).isUi()) return i;
        return -1;
    }

    /** User interface mods above all others, each group keeping its order. True if it changed. */
    boolean interfaceFirst() {
        List<Mod> ui = new ArrayList<>(), rest = new ArrayList<>();
        for (Mod m : mods) (m.isUi() ? ui : rest).add(m);
        List<Mod> now = new ArrayList<>(ui);
        now.addAll(rest);
        if (now.equals(mods)) return false;
        mods = new java.util.concurrent.CopyOnWriteArrayList<>(now);
        return true;
    }

    void move(int index, int delta) {
        int to = index + delta;
        if (index < 0 || index >= mods.size() || to < 0 || to >= mods.size()) return;
        mods.add(to, mods.remove(index));
    }

    int enabledCount() {
        int n = 0;
        for (Mod m : mods) if (m.enabled && m.error == null) n++;
        return n;
    }

    /** Write profile.xml (atomically: a half-written profile would drop every mod). */
    void save() throws Exception {
        File tmp = new File(dir, "profile.xml.part");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
            w.write("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<Profile Language=\"en\">\n");
            for (Mod m : mods) {
                w.write("  <Mod Path=\"" + xml(m.file) + "\" Enabled=\"" + (m.enabled ? "true" : "false") + "\"");
                if (m.chosen.isEmpty()) { w.write("/>\n"); continue; }
                w.write(">\n");
                for (Map.Entry<String, Integer> e : m.chosen.entrySet())
                    w.write("    <Option ID=\"" + xml(e.getKey()) + "\" Value=\"" + e.getValue() + "\"/>\n");
                w.write("  </Mod>\n");
            }
            w.write("</Profile>\n");
        }
        if (!tmp.renameTo(profile)) throw new java.io.IOException("cannot replace " + profile);
    }

    private static String xml(String s) {
        return s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
