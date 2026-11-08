package com.ff7recomp;

import android.util.Log;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import org.json.JSONArray;
import org.json.JSONObject;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * 7th Heaven's mod catalogs in the launcher: subscriptions, the merged list of catalog mods,
 * and which of them are installed at which version (for "update available").
 *
 * Catalog XML (7th-Heaven AppCore/Catalog.cs, Mod.cs, ModVersion.cs): Catalog > Mods > Mod with
 * ID (GUID), Name, Author, Category, Description, Link (info page), Tags/string, Requirement
 * (ModID, Description), MetaVersion, and LatestVersion with repeated Link (download
 * alternatives), Version (decimal), ReleaseDate, ReleaseNotes, PreviewImage, DownloadSize (KB).
 * Catalogs are re-downloaded after a day (as 7th Heaven) or on request, cached in
 * files/catalogs, and merged by ID (the higher version wins).
 */
final class ModCatalog {
    private static final String TAG = "FF7Recomp";
    static final String[][] DEFAULTS = {
        { "Qhimm Catalog", "iros://Url/https$github.com/tsunamods-codes/7th-Heaven-Catalogs/releases/download/canary/qhimm.xml" },
        { "Sega Catalog", "iros://Url/https$github.com/tsunamods-codes/7th-Heaven-Catalogs/releases/download/canary/sega.xml" },
    };
    /** 7th Heaven's categories, in its load order (AppCore/ModLoadOrder.cs). */
    static final String[] CATEGORIES = {
        "Miscellaneous", "Animations", "Battle Models", "Battle Textures", "Field Models", "Field Textures", "Gameplay",
        "Media", "Minigames", "Spell Textures", "User Interface", "World Models", "World Textures", "Shaders",
    };

    static final class Subscription {
        String name, url;
        long lastCheck;            // ms
        String error;              // the last refresh's problem, if any
        int count;                 // mods in it
    }

    static final class Mod {
        String id, name, author, category, description, infoLink, catalog;
        final List<String> tags = new ArrayList<>();
        final List<String> links = new ArrayList<>();
        final List<String[]> requirements = new ArrayList<>();    // [mod id, description]
        String versionText = "", releaseDate = "", releaseNotes = "", preview = "";
        double version;
        long sizeKb;
        double metaVersion;

        String sizeText() { return sizeKb > 0 ? ModDownloader.human(sizeKb * 1024) : ""; }
    }

    static final class Installed {
        String id, file, versionText, name;
        double version;
    }

    final File dir;
    final List<Subscription> subs = new ArrayList<>();
    final List<Mod> mods = new ArrayList<>();
    final Map<String, Installed> installed = new LinkedHashMap<>();   // by catalog ID (lowercase)
    private final File subsFile, installedFile;

    ModCatalog(File filesDir) {
        dir = new File(filesDir, "catalogs");
        dir.mkdirs();
        subsFile = new File(dir, "subscriptions.json");
        installedFile = new File(new File(filesDir, "mods"), "catalog_installed.json");
        loadSubs();
        loadInstalled();
    }

    // ---- subscriptions ---------------------------------------------------------------------------

    private void loadSubs() {
        subs.clear();
        try {
            if (subsFile.isFile()) {
                JSONArray a = new JSONArray(read(subsFile));
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.getJSONObject(i);
                    Subscription s = new Subscription();
                    s.name = o.optString("name");
                    s.url = o.optString("url");
                    s.lastCheck = o.optLong("lastCheck");
                    s.error = o.optString("error", null);
                    s.count = o.optInt("count");
                    subs.add(s);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "subscriptions.json unreadable: " + e);
        }
        // the defaults are always there, at their built-in address (as 7th Heaven does)
        for (String[] d : DEFAULTS) {
            Subscription found = null;
            for (Subscription s : subs) if (s.name.equalsIgnoreCase(d[0])) found = s;
            if (found == null) { found = new Subscription(); found.name = d[0]; subs.add(found); }
            found.url = d[1];
        }
    }

    void saveSubs() {
        try {
            JSONArray a = new JSONArray();
            for (Subscription s : subs) {
                JSONObject o = new JSONObject();
                o.put("name", s.name).put("url", s.url).put("lastCheck", s.lastCheck).put("count", s.count);
                if (s.error != null) o.put("error", s.error);
                a.put(o);
            }
            write(subsFile, a.toString(2));
        } catch (Exception e) {
            Log.w(TAG, "cannot save subscriptions: " + e);
        }
    }

    /** A catalog link someone pasted (iros:// or https). */
    String addSubscription(String name, String link) {
        String url = ModDownloader.resolve(link);
        if (url == null) return "not a catalog link (iros://Url/... or https://...)";
        for (Subscription s : subs) if (url.equals(ModDownloader.resolve(s.url))) return "already subscribed: " + s.name;
        Subscription s = new Subscription();
        s.name = name == null || name.trim().isEmpty() ? hostOf(url) : name.trim();
        s.url = link.trim();
        subs.add(s);
        saveSubs();
        return null;
    }

    boolean isDefault(Subscription s) {
        for (String[] d : DEFAULTS) if (d[0].equalsIgnoreCase(s.name)) return true;
        return false;
    }

    void removeSubscription(Subscription s) {
        if (isDefault(s)) return;
        subs.remove(s);
        new File(dir, cacheName(s)).delete();
        saveSubs();
    }

    // ---- refresh and parse ------------------------------------------------------------------------

    /** Re-download catalogs older than a day (or all, if forced), then rebuild the list.
     *  Returns a summary of problems, or null. */
    String refresh(boolean force) {
        StringBuilder problems = new StringBuilder();
        long now = System.currentTimeMillis();
        for (Subscription s : subs) {
            File cache = new File(dir, cacheName(s));
            if (!force && cache.isFile() && now - s.lastCheck < 24L * 3600 * 1000) continue;
            try {
                String url = ModDownloader.resolve(s.url);
                if (url == null) throw new Exception("not a downloadable link");
                byte[] xml = ModDownloader.get(url);
                parse(xml, s.name, new ArrayList<>());          // check it before replacing the cache
                File tmp = new File(dir, cacheName(s) + ".part");
                try (FileOutputStream o = new FileOutputStream(tmp)) { o.write(xml); }
                if (!tmp.renameTo(cache)) throw new Exception("cannot write the cache");
                s.lastCheck = now;
                s.error = null;
            } catch (Exception e) {
                s.error = e.getMessage() != null ? e.getMessage() : e.toString();
                problems.append(s.name).append(": ").append(s.error).append('\n');
            }
        }
        rebuild();
        saveSubs();
        return problems.length() == 0 ? null : problems.toString().trim();
    }

    /** The merged list from the cached catalogs (no network). */
    void rebuild() {
        Map<String, Mod> byId = new LinkedHashMap<>();
        for (Subscription s : subs) {
            File cache = new File(dir, cacheName(s));
            if (!cache.isFile()) continue;
            List<Mod> list = new ArrayList<>();
            try {
                parse(Files.readAllBytes(cache.toPath()), s.name, list);
            } catch (Exception e) {
                s.error = "cached catalog unreadable: " + e.getMessage();
                continue;
            }
            s.count = list.size();
            for (Mod m : list) {
                String k = m.id.toLowerCase(Locale.ROOT);
                Mod had = byId.get(k);
                if (had == null || m.version > had.version || (m.version == had.version && m.metaVersion > had.metaVersion)) byId.put(k, m);
            }
        }
        mods.clear();
        mods.addAll(byId.values());
        Collections.sort(mods, (a, b) -> a.name.compareToIgnoreCase(b.name));
    }

    static void parse(byte[] xml, String catalogName, List<Mod> out) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setExpandEntityReferences(false);
        Document doc = f.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
        NodeList list = doc.getElementsByTagName("Mod");
        if (!"Catalog".equals(doc.getDocumentElement().getNodeName())) throw new Exception("not a 7th Heaven catalog");
        for (int i = 0; i < list.getLength(); i++) {
            Element e = (Element) list.item(i);
            if (!"Mods".equals(e.getParentNode().getNodeName())) continue;   // Requirement etc. hold <ModID>, not <Mod>
            Mod m = new Mod();
            m.id = child(e, "ID");
            if (m.id.isEmpty()) continue;
            m.name = child(e, "Name");
            if (m.name.isEmpty()) m.name = m.id;
            m.author = child(e, "Author");
            m.category = child(e, "Category");
            m.description = child(e, "Description");
            m.infoLink = child(e, "Link");
            m.catalog = catalogName;
            m.metaVersion = number(child(e, "MetaVersion"));
            Element tags = firstChild(e, "Tags");
            if (tags != null) for (Element t : children(tags, "string")) if (!text(t).isEmpty()) m.tags.add(text(t));
            for (Element r : children(e, "Requirement")) m.requirements.add(new String[] { child(r, "ModID"), child(r, "Description") });
            Element lv = firstChild(e, "LatestVersion");
            if (lv != null) {
                for (Element l : children(lv, "Link")) if (!text(l).isEmpty()) m.links.add(text(l));
                m.versionText = child(lv, "Version");
                m.version = number(m.versionText);
                m.releaseDate = child(lv, "ReleaseDate");
                m.releaseNotes = child(lv, "ReleaseNotes");
                m.preview = child(lv, "PreviewImage");
                m.sizeKb = (long) number(child(lv, "DownloadSize"));
            }
            out.add(m);
        }
    }

    // ---- installed ---------------------------------------------------------------------------------

    private void loadInstalled() {
        installed.clear();
        try {
            if (!installedFile.isFile()) return;
            JSONObject o = new JSONObject(read(installedFile));
            for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) {
                String id = it.next();
                JSONObject j = o.getJSONObject(id);
                Installed in = new Installed();
                in.id = id;
                in.file = j.optString("file");
                in.versionText = j.optString("version");
                in.version = number(in.versionText);
                in.name = j.optString("name");
                installed.put(id.toLowerCase(Locale.ROOT), in);
            }
        } catch (Exception e) {
            Log.w(TAG, "catalog_installed.json unreadable: " + e);
        }
    }

    private void saveInstalled() {
        try {
            JSONObject o = new JSONObject();
            for (Installed in : installed.values())
                o.put(in.id, new JSONObject().put("file", in.file).put("version", in.versionText).put("name", in.name));
            write(installedFile, o.toString(2));
        } catch (Exception e) {
            Log.w(TAG, "cannot save catalog_installed.json: " + e);
        }
    }

    void markInstalled(Mod m, String file) {
        Installed in = new Installed();
        in.id = m.id;
        in.file = file;
        in.versionText = m.versionText;
        in.version = m.version;
        in.name = m.name;
        installed.put(m.id.toLowerCase(Locale.ROOT), in);
        saveInstalled();
    }

    void forget(String id) {
        installed.remove(id.toLowerCase(Locale.ROOT));
        saveInstalled();
    }

    /** Entries whose library file was deleted are dropped. */
    void prune(File modsDir) {
        boolean changed = false;
        for (java.util.Iterator<Installed> it = installed.values().iterator(); it.hasNext(); ) {
            if (!new File(modsDir, it.next().file).exists()) { it.remove(); changed = true; }
        }
        if (changed) saveInstalled();
    }

    Installed installedOf(Mod m) { return installed.get(m.id.toLowerCase(Locale.ROOT)); }

    /** The catalog entry for a library file, if it came from a catalog. */
    Installed installedByFile(String file) {
        for (Installed in : installed.values()) if (in.file.equalsIgnoreCase(file)) return in;
        return null;
    }

    Mod byId(String id) {
        for (Mod m : mods) if (m.id.equalsIgnoreCase(id)) return m;
        return null;
    }

    boolean updateAvailable(Mod m) {
        Installed in = installedOf(m);
        return in != null && m.version > in.version;
    }

    List<Mod> updates() {
        List<Mod> u = new ArrayList<>();
        for (Mod m : mods) if (updateAvailable(m)) u.add(m);
        return u;
    }

    /** Search: every word must appear in the name, author, category, tags or description. */
    List<Mod> search(String query, String category, boolean installedOnly) {
        String[] words = query == null ? new String[0] : query.toLowerCase(Locale.ROOT).trim().split("\\s+");
        List<Mod> out = new ArrayList<>();
        for (Mod m : mods) {
            if (category != null && !category.equalsIgnoreCase(m.category)) continue;
            if (installedOnly && installedOf(m) == null) continue;
            String hay = (m.name + " " + m.author + " " + m.category + " " + String.join(" ", m.tags) + " " + m.description).toLowerCase(Locale.ROOT);
            boolean all = true;
            for (String w : words) if (!w.isEmpty() && !hay.contains(w)) { all = false; break; }
            if (all) out.add(m);
        }
        return out;
    }

    // ---- small helpers -----------------------------------------------------------------------------

    static String cacheName(Subscription s) {
        return ModDownloader.safe(s.name).replace(' ', '_') + ".xml";
    }

    private static String hostOf(String url) {
        try { return new java.net.URL(url).getHost(); } catch (Exception e) { return "Catalog"; }
    }

    static double number(String s) {
        if (s == null || s.trim().isEmpty()) return 0;
        try { return Double.parseDouble(s.trim().replace(',', '.')); } catch (NumberFormatException e) { return 0; }
    }

    private static Element firstChild(Element e, String name) {
        for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling())
            if (n instanceof Element && n.getNodeName().equals(name)) return (Element) n;
        return null;
    }

    private static List<Element> children(Element e, String name) {
        List<Element> out = new ArrayList<>();
        for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling())
            if (n instanceof Element && n.getNodeName().equals(name)) out.add((Element) n);
        return out;
    }

    private static String child(Element e, String name) {
        Element c = firstChild(e, name);
        return c == null ? "" : text(c);
    }

    private static String text(Element e) { return e.getTextContent() == null ? "" : e.getTextContent().trim(); }

    private static String read(File f) throws Exception { return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8); }

    private static void write(File f, String s) throws Exception {
        File tmp = new File(f.getPath() + ".part");
        try (FileOutputStream o = new FileOutputStream(tmp)) { o.write(s.getBytes(StandardCharsets.UTF_8)); }
        if (!tmp.renameTo(f)) throw new java.io.IOException("cannot replace " + f);
    }
}
