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
        final List<Option> options = new ArrayList<>();
        final List<String> unsupported = new ArrayList<>();
        final List<String[]> ffnx = new ArrayList<>();        // [key, value]

        String title() { return name != null && !name.isEmpty() ? name : file; }
        int value(Option o) {
            Integer v = chosen.get(o.id.toLowerCase(Locale.ROOT));
            return v != null ? v : o.defaultValue;
        }
    }

    final File dir, profile;
    final List<Mod> mods = new ArrayList<>();

    ModLibrary(File filesDir) {
        dir = new File(filesDir, "mods");
        profile = new File(dir, "profile.xml");
        dir.mkdirs();
    }

    /** Re-read the folder and the saved profile. New mods are added at the end, disabled. */
    void load() {
        mods.clear();
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
            for (String p : present) if (p.equalsIgnoreCase(m.file)) { m.file = p; mods.add(m); break; }
        }
        for (String p : present) {                             // then anything new
            if (saved.containsKey(p.toLowerCase(Locale.ROOT))) continue;
            Mod m = new Mod();
            m.file = p;
            mods.add(m);
        }
        for (Mod m : mods) readInfo(m);
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
                m.ffnx.add(new String[] { fx.getJSONObject(i).optString("key"), fx.getJSONObject(i).optString("value") });
        } catch (Exception ex) {
            m.error = ex.toString();
        }
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
