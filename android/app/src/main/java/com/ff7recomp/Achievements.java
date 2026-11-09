package com.ff7recomp;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The achievements the game evaluates (src/ffnx_port/achievements.cpp): the local set in
 * RetroAchievements' local-achievements format (files/achievements/ff7-local.txt) and what has
 * unlocked (files/achievements/unlocks.txt, "id TAB unix time TAB title").
 */
final class Achievements {
    static final class Ach {
        long id;
        String title = "", description = "";
        int points;
        long unlockedAt;   // unix seconds, 0 = locked
    }

    final List<Ach> list = new ArrayList<>();
    boolean haveSet;

    Achievements(File files) {
        File dir = new File(files, "achievements");
        Map<Long, Long> unlocked = new HashMap<>();
        for (String line : lines(new File(dir, "unlocks.txt"))) {
            String[] t = line.split("\t");
            try { unlocked.put(Long.parseLong(t[0].trim()), t.length > 1 ? Long.parseLong(t[1].trim()) : 1L); }
            catch (NumberFormatException ignored) { }
        }
        List<String> set = lines(new File(dir, "ff7-local.txt"));
        haveSet = !set.isEmpty();
        for (int i = 2; i < set.size(); i++) {                  // after the version and title lines
            String line = set.get(i);
            if (line.isEmpty() || !Character.isDigit(line.charAt(0))) continue;
            List<String> f = fields(line);
            if (f.size() < 4) continue;
            Ach a = new Ach();
            try { a.id = Long.parseLong(f.get(0)); } catch (NumberFormatException e) { continue; }
            a.title = f.get(2);
            a.description = f.get(3);
            try { a.points = f.size() > 8 ? Integer.parseInt(f.get(8).trim()) : 0; } catch (NumberFormatException ignored) { }
            Long at = unlocked.get(a.id);
            a.unlockedAt = at == null ? 0 : at;
            list.add(a);
        }
    }

    int unlockedCount() { int n = 0; for (Ach a : list) if (a.unlockedAt != 0) n++; return n; }
    int points(boolean onlyUnlocked) { int n = 0; for (Ach a : list) if (!onlyUnlocked || a.unlockedAt != 0) n += a.points; return n; }

    private static List<String> lines(File f) {
        List<String> out = new ArrayList<>();
        if (!f.isFile()) return out;
        try {
            for (String l : new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).split("\n")) out.add(l.replace("\r", ""));
        } catch (Exception ignored) { }
        return out;
    }

    /** RA local-achievements fields: "quoted" (with \" and \\) or up to the next ':'. */
    private static List<String> fields(String line) {
        List<String> out = new ArrayList<>();
        int i = 0, n = line.length();
        while (i < n) {
            StringBuilder s = new StringBuilder();
            if (line.charAt(i) == '"') {
                for (i++; i < n && line.charAt(i) != '"'; i++) {
                    if (line.charAt(i) == '\\' && i + 1 < n) i++;
                    s.append(line.charAt(i));
                }
                if (i < n) i++;
            } else {
                while (i < n && line.charAt(i) != ':') s.append(line.charAt(i++));
            }
            if (i < n && line.charAt(i) == ':') i++;
            out.add(s.toString());
        }
        return out;
    }
}
