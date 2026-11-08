package com.ff7recomp;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Do mods get in each other's way?
 *
 * Before a download only the catalog is known (no file lists), so {@link #beforeInstall}
 * guesses from what the mods are: the same kind of replacement (two field model packs, two
 * soundtracks, two gameplay overhauls, ...), and the Forbid entries in the mod.xml of mods
 * already installed. After a download the real answer comes from {@link #report}: the
 * profile loaded exactly as the game loads it (ModLibrary.conflicts, src/mods), listing the
 * game files two enabled mods both replace -- the one higher in the list wins, as in the
 * game and in 7th Heaven -- and the mods' own Compatibility Require/Forbid declarations.
 */
final class ConflictChecker {

    static final class Warning {
        final String text;
        final boolean serious;     // declared incompatible, or two gameplay overhauls
        Warning(String text, boolean serious) { this.text = text; this.serious = serious; }
    }

    // ---- before downloading -----------------------------------------------------------------

    /** One side of a comparison: a catalog mod or an installed one. */
    private static final class Who {
        String id = "", name = "", category = "";
        final List<String> tags = new ArrayList<>();
        final List<String[]> forbids = new ArrayList<>();
        final Set<String> needs = new LinkedHashSet<>();
        boolean installed;
    }

    private static Who of(ModCatalog.Mod m) {
        Who w = new Who();
        w.id = m.id.toLowerCase(Locale.ROOT);
        w.name = m.name;
        w.category = m.category;
        w.tags.addAll(m.tags);
        for (String[] r : m.requirements) w.needs.add(r[0].toLowerCase(Locale.ROOT));
        return w;
    }

    private static Who of(ModLibrary.Mod m, ModCatalog catalog) {
        Who w = new Who();
        w.installed = true;
        w.id = m.id == null ? "" : m.id;
        w.name = m.title();
        w.category = m.category == null ? "" : m.category;
        ModCatalog.Installed in = catalog.installedByFile(m.file);
        ModCatalog.Mod cm = in == null ? null : catalog.byId(in.id);
        if (cm != null) {
            if (w.id.isEmpty()) w.id = cm.id.toLowerCase(Locale.ROOT);
            if (w.category.isEmpty()) w.category = cm.category;
            w.tags.addAll(cm.tags);
            for (String[] r : cm.requirements) w.needs.add(r[0].toLowerCase(Locale.ROOT));
        }
        w.forbids.addAll(m.forbids);
        for (String[] r : m.requires) w.needs.add(r[0].toLowerCase(Locale.ROOT));
        return w;
    }

    /** What a mod replaces, as far as its category, tags and name say. */
    private static Set<String> slots(Who w) {
        Set<String> s = new LinkedHashSet<>();
        String cat = w.category.toLowerCase(Locale.ROOT);
        StringBuilder words = new StringBuilder(w.name.toLowerCase(Locale.ROOT));
        for (String t : w.tags) words.append(' ').append(t.toLowerCase(Locale.ROOT));
        String k = words.toString();
        switch (cat) {
            case "gameplay": s.add("gameplay"); break;
            case "field models": s.add("field models"); break;
            case "battle models": s.add("battle models"); break;
            case "animations": s.add("animations"); break;
            case "field textures": s.add("field textures"); break;
            case "world textures": s.add("world map textures"); break;
            case "battle textures": s.add("battle textures"); break;
            case "spell textures": s.add(k.contains("limit") ? "limit break effects" : "spell effects"); break;
            case "minigames": s.add("minigames"); break;
            case "shaders": s.add("shaders"); break;
            default: break;
        }
        if (cat.equals("media") || cat.equals("miscellaneous") || cat.equals("user interface")) {
            if (k.matches(".*\\b(music|soundtrack|ost|arranged|remake|orchestra)\\b.*")) s.add("music");
            if (k.matches(".*\\b(fmv|movies?|videos?|cutscenes?)\\b.*")) s.add("movies");
            if (k.matches(".*\\b(voice|voices|voiced)\\b.*")) s.add("voices");
            if (k.matches(".*\\b(sfx|sounds?)\\b.*")) s.add("sound effects");
            if (k.matches(".*\\bavatars?\\b.*")) s.add("character portraits");
            if (k.matches(".*\\bfonts?\\b.*")) s.add("the font");
            if (k.matches(".*\\b(backgrounds?|menu)\\b.*") && cat.equals("user interface")) s.add("menu graphics");
        }
        return s;
    }

    private static String what(String slot) {
        switch (slot) {
            case "gameplay": return "game data (stats, enemies, items)";
            default: return slot;
        }
    }

    /**
     * Warnings for installing `wanted` (in order; requirements included) next to the enabled
     * mods in `lib`. A new mod goes to the bottom of the Mods list, so where files overlap the
     * mods already there win.
     */
    static List<Warning> beforeInstall(ModCatalog catalog, ModLibrary lib, List<ModCatalog.Mod> wanted) {
        List<Who> have = new ArrayList<>();
        for (ModLibrary.Mod m : lib.mods) if (m.enabled && m.error == null) have.add(of(m, catalog));
        List<Warning> out = new ArrayList<>();
        Set<String> said = new LinkedHashSet<>();
        for (ModCatalog.Mod cm : wanted) {
            Who c = of(cm);
            boolean update = false;
            for (Who h : have) if (!h.id.isEmpty() && h.id.equals(c.id)) update = true;
            if (update) continue;                               // an update replaces itself
            for (Who h : have) {
                if (h.needs.contains(c.id) || c.needs.contains(h.id)) continue;   // meant to go together
                for (String[] f : h.forbids)
                    if (f[0].equalsIgnoreCase(c.id) && said.add("F" + h.id + c.id))
                        out.add(new Warning(h.name + " says it does not work with " + c.name
                                + (f[1].isEmpty() ? "." : ": " + f[1]), true));
                Set<String> both = slots(c);
                both.retainAll(slots(h));
                for (String s : both) {
                    if (!said.add(s + h.id + c.id)) continue;
                    if (s.equals("gameplay"))
                        out.add(new Warning(c.name + " and " + h.name + " both change the " + what(s)
                                + ". Two gameplay mods usually replace the same files, so one of them will mostly not apply"
                                + " -- use one unless " + c.name + "'s page says it works with " + h.name + ".", true));
                    else
                        out.add(new Warning(c.name + " and " + h.name + " both replace " + what(s) + ". "
                                + c.name + " goes to the bottom of your Mods list, so " + h.name
                                + "'s files win where they overlap (move " + c.name + " up to prefer it).", false));
                }
            }
            c.installed = true;
            have.add(c);                                        // the rest of the batch sees it
        }
        return out;
    }

    // ---- after downloading: the real file overlaps -------------------------------------------

    /** Friendly names for the folders the game's files live in (keys' first part). */
    static String area(String a) {
        switch (a.toLowerCase(Locale.ROOT)) {
            case "char": return "field models";
            case "battle": return "battle models and data";
            case "flevel": return "field maps";
            case "field": return "field files";
            case "kernel": return "game data";
            case "vgmstream": case "music": return "music";
            case "movies": return "movies";
            case "magic": return "spell effects";
            case "wm": case "world": return "world map";
            case "menu": return "menus";
            case "cr": return "credits";
            case "disc": return "disc change screens";
            case "snowboard-us": case "high-us": return "minigames";
            case "sfx": return "sound effects";
            case "voice": return "voice acting";
            case "ambient": return "ambient sound";
            case "textures": return "textures";
            case "high": case "chocobo": case "condor": case "coaster": case "snowboard": case "sub": case "minigame":
                return "minigames";
            case "exe": return "engine data";
            case "time": return "day/night";
            case "shaders": return "shaders";
            case "(root)": return "game folder files";
            default: return a;
        }
    }

    static final class Report {
        final List<Warning> lines = new ArrayList<>();
        int overlaps, problems;
        /** Per library file: short notes for its row on the Mods page. */
        final java.util.Map<String, List<String>> perMod = new java.util.HashMap<>();
        void note(String file, String s) { perMod.computeIfAbsent(file.toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(s); }
    }

    static Report report(JSONObject j, ModCatalog catalog) {
        Report r = new Report();
        if (j == null) return r;
        JSONArray mods = j.optJSONArray("mods");
        if (mods == null) return r;
        String[] file = new String[mods.length()], name = new String[mods.length()];
        for (int i = 0; i < mods.length(); i++) {
            file[i] = mods.optJSONObject(i).optString("file");
            name[i] = mods.optJSONObject(i).optString("name", file[i]);
        }
        JSONArray fb = j.optJSONArray("forbids");
        for (int i = 0; fb != null && i < fb.length(); i++) {
            JSONObject f = fb.optJSONObject(i);
            int a = f.optInt("mod"), b = f.optInt("other");
            String why = f.optString("why");
            r.lines.add(new Warning(name[a] + " says it does not work with " + name[b] + (why.isEmpty() ? "." : ": " + why), true));
            r.note(file[a], "Does not work with " + name[b]);
            r.note(file[b], name[a] + " does not work with it");
            r.problems++;
        }
        JSONArray rq = j.optJSONArray("requires");
        for (int i = 0; rq != null && i < rq.length(); i++) {
            JSONObject q = rq.optJSONObject(i);
            int a = q.optInt("mod");
            ModCatalog.Mod need = catalog.byId(q.optString("id"));
            String needName = need != null ? need.name : "another mod (" + q.optString("id") + ")";
            String why = q.optString("why");
            r.lines.add(new Warning(name[a] + " needs " + needName + ", which is not enabled" + (why.isEmpty() ? "." : ": " + why), true));
            r.note(file[a], "Needs " + needName);
            r.problems++;
        }
        JSONArray ov = j.optJSONArray("overlaps");
        for (int i = 0; ov != null && i < ov.length(); i++) {
            JSONObject o = ov.optJSONObject(i);
            int w = o.optInt("winner"), l = o.optInt("loser"), n = o.optInt("files");
            List<String> areas = new ArrayList<>();
            JSONArray as = o.optJSONArray("areas");
            for (int k = 0; as != null && k < as.length(); k++) {
                String a = area(as.optJSONObject(k).optString("area"));
                if (!areas.contains(a)) areas.add(a);
            }
            String where = areas.isEmpty() ? "" : " (" + String.join(", ", areas) + ")";
            r.lines.add(new Warning(name[w] + " and " + name[l] + " both replace " + n + (n == 1 ? " game file" : " game files")
                    + where + ". " + name[w] + " is higher in the list, so its version is used"
                    + (o.optBoolean("conditional") ? " (some only at certain times)" : "") + ".", false));
            r.note(file[w], "Overrides " + name[l] + " in " + n + (n == 1 ? " file" : " files"));
            r.note(file[l], n + (n == 1 ? " file" : " files") + " overridden by " + name[w]);
            r.overlaps++;
        }
        return r;
    }
}
