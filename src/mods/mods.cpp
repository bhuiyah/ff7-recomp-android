// mods.cpp - the mod overlay: see mods.h. Mirrors 7th Heaven's AppWrapper (Wrap.cs
// AddIROFilesToMappedFiles / AddFolderFilesToMappedFiles, VFile.cs MapFile) and AppCore's
// Profile.GetRuntime, MS-PL.
#include "mods.h"
#include <sys/stat.h>
#include <cctype>
#include <cstdio>
#include <cstring>
#include <algorithm>
#include <cstdlib>
#include <map>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>
#include "iro.hpp"
#include "modinfo.hpp"
#include "runtimevar.hpp"
#include "pugixml.hpp"
#if defined(_WIN32)
#include <direct.h>
#include <io.h>
#else
#include <dirent.h>
#include <unistd.h>
#endif

namespace ff7mods {
namespace {

std::string lower(std::string s) {
    for (char &c : s) c = (char)std::tolower((unsigned char)c);
    return s;
}
// Override keys: lower case, '/' separators, no leading slash.
std::string norm_key(std::string s) {
    for (char &c : s) c = c == '\\' ? '/' : (char)std::tolower((unsigned char)c);
    while (!s.empty() && s[0] == '/') s.erase(0, 1);
    return s;
}
bool is_dir(const std::string &p) { struct stat st; return stat(p.c_str(), &st) == 0 && (st.st_mode & S_IFDIR); }
bool is_file(const std::string &p) { struct stat st; return stat(p.c_str(), &st) == 0 && (st.st_mode & S_IFREG); }
int64_t mtime_of(const std::string &p) { struct stat st; return stat(p.c_str(), &st) == 0 ? (int64_t)st.st_mtime : -1; }
std::string join(const std::string &a, const std::string &b) {
    if (a.empty()) return b;
    if (b.empty()) return a;
    return a.back() == '/' ? a + b : a + "/" + b;
}
void mkdirs(const std::string &path) {
    for (size_t i = 1; i <= path.size(); i++) {
        if (i == path.size() || path[i] == '/') {
            std::string d = path.substr(0, i);
#if defined(_WIN32)
            _mkdir(d.c_str());
#else
            mkdir(d.c_str(), 0775);
#endif
        }
    }
}
// Every file under dir (recursive), relative, '/' separators, real case.
void list_files(const std::string &dir, const std::string &rel, std::vector<std::string> &out) {
#if defined(_WIN32)
    _finddata_t fd;
    intptr_t h = _findfirst((dir + "/*").c_str(), &fd);
    if (h == -1) return;
    do {
        if (!std::strcmp(fd.name, ".") || !std::strcmp(fd.name, "..")) continue;
        std::string r = rel.empty() ? fd.name : rel + "/" + fd.name;
        if (fd.attrib & _A_SUBDIR) list_files(dir + "/" + fd.name, r, out); else out.push_back(r);
    } while (_findnext(h, &fd) == 0);
    _findclose(h);
#else
    DIR *d = opendir(dir.c_str());
    if (!d) return;
    while (dirent *e = readdir(d)) {
        if (!std::strcmp(e->d_name, ".") || !std::strcmp(e->d_name, "..")) continue;
        std::string r = rel.empty() ? e->d_name : rel + "/" + e->d_name;
        std::string full = dir + "/" + e->d_name;
        if (is_dir(full)) list_files(full, r, out); else out.push_back(r);
    }
    closedir(d);
#endif
}
// Case-insensitive directory lookup for folder mods (Android filesystems are case-sensitive,
// mod.xml folder names are not).
std::string resolve_ci(const std::string &base, const std::string &rel) {
    std::string cur = base;
    size_t i = 0;
    std::string r = rel;
    for (char &c : r) if (c == '\\') c = '/';
    while (i < r.size()) {
        size_t j = r.find('/', i);
        std::string part = r.substr(i, j == std::string::npos ? std::string::npos : j - i);
        i = j == std::string::npos ? r.size() : j + 1;
        if (part.empty() || part == ".") continue;
        std::string exact = join(cur, part);
        if (is_dir(exact) || is_file(exact)) { cur = exact; continue; }
        std::vector<std::string> kids;
        bool found = false;
#if defined(_WIN32)
        cur = exact; found = true;   // Windows: case-insensitive anyway
#else
        if (DIR *d = opendir(cur.c_str())) {
            while (dirent *e = readdir(d)) if (lower(e->d_name) == lower(part)) { cur = join(cur, e->d_name); found = true; break; }
            closedir(d);
        }
#endif
        if (!found) return std::string();
    }
    return cur;
}

struct Mod {
    std::string path, name;                 // .iro file or folder
    std::unique_ptr<IroArchive> iro;
    int64_t mtime = 0;
    ModInfo info;
    // Where its hext/ files are looked for, in 7th Heaven's order (RuntimeMod.GetOverrides):
    // active Conditionals, active ModFolders, then the mod root (always, unlike files).
    std::vector<std::string> hext_folders;
};

struct Override {
    int mod;                                // index in g.mods
    std::string file;                       // archive entry name, or host path for folder mods
    const ConditionalFolder *cond;          // its Conditional folder (run-time conditions), or null
};

struct State {
    std::mutex lock;
    std::vector<std::unique_ptr<Mod>> mods;
    std::unordered_map<std::string, std::vector<Override>> map;   // key -> overrides, priority order
    std::string cache, language = "en";
    RuntimeVars vars;                       // 7thHeaven.var + the mods' <Variable> aliases
    // mods the profile listed against their <OrderConstraints>: `who` was moved below / above `other`
    struct OrderMove { const Mod *who, *other; bool below; };
    std::vector<OrderMove> order_moves;
    // options set by other mods' <Compatibility><Setting>s: mod, option name, the value's name
    struct SettingChange { const Mod *mod; std::string option, value; };
    std::vector<SettingChange> setting_changes;
} g;

void add_override(const std::string &key, int mod, const std::string &file, const ConditionalFolder *cond) {
    g.map[norm_key(key)].push_back({ mod, file, cond });
}

// ConditionalFolder.IsActive(path): the condition for this file, else the folder-wide one
// (ApplyTo=""), else none (active).
bool cond_active(const ConditionalFolder *cf, const std::string &key) {
    if (!cf || cf->conditions.empty()) return true;
    auto it = cf->conditions.find(key);
    if (it == cf->conditions.end()) it = cf->conditions.find("");
    return it == cf->conditions.end() || g.vars.eval(it->second.get());
}

// Files of one folder of a mod ("" = the mod root) into the map, keyed relative to it.
void add_folder(int mi, const std::string &folder, const ConditionalFolder *cond = nullptr) {
    Mod &m = *g.mods[mi];
    std::string fkey = norm_key(folder);
    if (m.iro) {
        for (const IroArchive::Entry &e : m.iro->entries()) {
            std::string k = norm_key(e.name);
            if (fkey.empty()) add_override(k, mi, e.name, cond);
            else if (k.size() > fkey.size() && k.compare(0, fkey.size(), fkey) == 0 && k[fkey.size()] == '/')
                add_override(k.substr(fkey.size() + 1), mi, e.name, cond);
        }
    } else {
        std::string dir = fkey.empty() ? m.path : resolve_ci(m.path, folder);
        if (dir.empty() || !is_dir(dir)) return;
        std::vector<std::string> files;
        list_files(dir, "", files);
        for (const std::string &f : files) add_override(f, mi, join(dir, f), cond);
    }
}

bool load_modinfo(Mod &m, std::string &err) {
    std::string xml;
    if (m.iro) {
        std::vector<uint8_t> data;
        if (!m.iro->read("mod.xml", data)) return true;        // no mod.xml: whole archive applies
        xml.assign(data.begin(), data.end());
    } else {
        std::string p = resolve_ci(m.path, "mod.xml");
        if (p.empty()) return true;
        std::FILE *f = std::fopen(p.c_str(), "rb");
        if (!f) return true;
        char buf[65536];
        size_t r;
        while ((r = std::fread(buf, 1, sizeof buf, f)) > 0) xml.append(buf, r);
        std::fclose(f);
    }
    if (xml.size() >= 3 && (uint8_t)xml[0] == 0xEF && (uint8_t)xml[1] == 0xBB && (uint8_t)xml[2] == 0xBF) xml.erase(0, 3);
    return parse_mod_xml(xml, m.info, err);
}

// The cached host copy of an archive entry (extracted if missing or older than the archive).
std::string materialize(const Mod &m, int mi, const std::string &entry) {
    std::string rel = entry;
    for (char &c : rel) if (c == '\\') c = '/';
    std::string out = join(join(g.cache, std::to_string(mi) + "_" + lower(m.name)), rel);
    struct stat st;
    if (stat(out.c_str(), &st) == 0 && (int64_t)st.st_mtime >= m.mtime) return out;
    std::vector<uint8_t> data;
    if (!m.iro->read(entry, data)) {
        std::fprintf(stderr, "[mods] %s: cannot read %s\n", m.name.c_str(), entry.c_str());
        return std::string();
    }
    size_t slash = out.rfind('/');
    if (slash != std::string::npos) mkdirs(out.substr(0, slash));
    std::string tmp = out + ".part";
    std::FILE *f = std::fopen(tmp.c_str(), "wb");
    if (!f) { std::fprintf(stderr, "[mods] cannot write %s\n", tmp.c_str()); return std::string(); }
    bool ok = data.empty() || std::fwrite(data.data(), 1, data.size(), f) == data.size();
    ok = std::fclose(f) == 0 && ok;
    if (!ok) { std::remove(tmp.c_str()); return std::string(); }
    if (std::rename(tmp.c_str(), out.c_str()) != 0) {      // Windows will not rename over a file
        std::remove(out.c_str());
        if (std::rename(tmp.c_str(), out.c_str()) != 0) { std::remove(tmp.c_str()); return std::string(); }
    }
    return out;
}

// The hext files (names relative to <folder>/hext) of one folder of a mod.
std::vector<std::string> hext_names(const Mod &m, const std::string &folder) {
    std::vector<std::string> out;
    std::string prefix = norm_key(folder.empty() ? std::string("hext") : folder + "/hext") + "/";
    if (m.iro) {
        for (const IroArchive::Entry &e : m.iro->entries()) {
            std::string k = norm_key(e.name);
            if (k.size() > prefix.size() && k.compare(0, prefix.size(), prefix) == 0) out.push_back(k.substr(prefix.size()));
        }
    } else {
        std::string dir = resolve_ci(m.path, folder.empty() ? std::string("hext") : folder + "/hext");
        if (!dir.empty() && is_dir(dir)) list_files(dir, "", out);
    }
    return out;
}

bool read_hext(const Mod &m, const std::string &folder, const std::string &name, std::string &text) {
    std::string rel = (folder.empty() ? std::string("hext") : folder + "/hext") + "/" + name;
    if (m.iro) {
        const IroArchive::Entry *e = m.iro->find(rel);
        if (!e) {
            std::string bs = rel;
            for (char &c : bs) if (c == '/') c = '\\';
            e = m.iro->find(bs);
        }
        if (!e) return false;
        std::vector<uint8_t> data;
        if (!m.iro->read(e->name, data)) return false;
        text.assign(data.begin(), data.end());
        return true;
    }
    std::string p = resolve_ci(m.path, rel);
    if (p.empty() || !is_file(p)) return false;
    std::FILE *f = std::fopen(p.c_str(), "rb");
    if (!f) return false;
    char buf[65536];
    size_t r;
    text.clear();
    while ((r = std::fread(buf, 1, sizeof buf, f)) > 0) text.append(buf, r);
    std::fclose(f);
    return true;
}

}  // namespace
}  // namespace ff7mods

using namespace ff7mods;

// AppWrapper Wrap.cs: mods lowest priority first; each mod's hext file names from its root,
// active Conditionals and active ModFolders; each name applied from every one of those
// folders that has it (Conditionals, ModFolders, root).
extern "C" void ff7_mods_hext(ff7_mods_hext_cb cb, void *ctx) {
    std::lock_guard<std::mutex> lk(g.lock);
    for (size_t i = g.mods.size(); i-- > 0;) {
        const Mod &m = *g.mods[i];
        std::vector<std::string> names, order;
        order.push_back("");
        for (const std::string &f : m.hext_folders) if (!f.empty()) order.push_back(f);
        for (const std::string &f : order)
            for (const std::string &n : hext_names(m, f)) {
                std::string k = norm_key(n);
                bool dup = false;
                for (const std::string &x : names) dup |= (x == k);
                if (!dup) names.push_back(k);
            }
        for (const std::string &n : names)
            for (const std::string &f : m.hext_folders) {
                std::string text;
                if (!read_hext(m, f, n, text)) continue;
                std::string label = (f.empty() ? std::string("hext/") : f + "/hext/") + n;
                cb(m.name.c_str(), label.c_str(), text.data(), (unsigned)text.size(), ctx);
            }
    }
}

extern "C" int ff7_mods_load(const char *profile_path, const char *cache_dir) {
    std::lock_guard<std::mutex> lk(g.lock);
    g.mods.clear();
    g.map.clear();
    g.order_moves.clear();
    g.setting_changes.clear();
    g.vars = RuntimeVars();
    g.cache = cache_dir ? cache_dir : "";
    if (!profile_path || !is_file(profile_path)) return 0;
    pugi::xml_document doc;
    if (!doc.load_file(profile_path)) { std::fprintf(stderr, "[mods] cannot parse %s\n", profile_path); return -1; }
    pugi::xml_node prof = doc.child("Profile");
    if (const char *lang = prof.attribute("Language").value(); lang && *lang) g.language = lower(lang);
    std::string base = profile_path;
    for (char &c : base) if (c == '\\') c = '/';
    base = base.find('/') == std::string::npos ? std::string(".") : base.substr(0, base.rfind('/'));

    // 7th Heaven's variable aliases (Time, FieldID, ...), next to the profile.
    if (std::FILE *vf = std::fopen(join(base, "7thHeaven.var").c_str(), "rb")) {
        std::string text;
        char vb[4096];
        size_t r;
        while ((r = std::fread(vb, 1, sizeof vb, vf)) > 0) text.append(vb, r);
        std::fclose(vf);
        g.vars.load_aliases(text);
    }

    // FFNx's settings for "ffnx_<key> = N" conditions (Profile.cs IsConfigActive reads
    // Sys.FFNxConfig): <files>/game/FFNx.toml, next to the mods folder. Integer values only, as
    // there (int.Parse). Tsunamods' widescreen battle layouts are chosen on ffnx_aspect_ratio = 2.
    OptionValues ffnx;
    auto trim = [](std::string s) {
        size_t a = s.find_first_not_of(" \t\r\n\""), b = s.find_last_not_of(" \t\r\n\"");
        return a == std::string::npos ? std::string() : s.substr(a, b - a + 1);
    };
    if (std::FILE *tf = std::fopen(join(base, "../game/FFNx.toml").c_str(), "rb")) {
        char line[512];
        while (std::fgets(line, sizeof line, tf)) {
            std::string l = line;
            size_t eq = l.find('=');
            if (l.empty() || l[0] == '#' || l[0] == '[' || eq == std::string::npos) continue;
            std::string key = lower(trim(l.substr(0, eq))), val = trim(l.substr(eq + 1));
            size_t hash = val.find('#');
            if (hash != std::string::npos) val = trim(val.substr(0, hash));
            char *end = nullptr;
            long v = std::strtol(val.c_str(), &end, 10);
            if (key.empty() || val.empty() || *end) continue;
            ffnx["ffnx_" + key] = (int)v;
        }
        std::fclose(tf);
    }

    struct Pending { std::unique_ptr<Mod> mod; OptionValues chosen; };
    std::vector<Pending> pending;
    for (pugi::xml_node mn : prof.children("Mod")) {
        std::string p = mn.attribute("Path").value();
        for (char &c : p) if (c == '\\') c = '/';
        if (p.empty()) continue;
        // The launcher keeps disabled mods in the profile (their order and options survive).
        if (pugi::xml_attribute en = mn.attribute("Enabled"); en && !en.as_bool(true)) continue;
        if (p[0] != '/' && !(p.size() > 1 && p[1] == ':')) p = join(base, p);
        auto m = std::make_unique<Mod>();
        m->path = p;
        m->name = p.substr(p.rfind('/') + 1);
        m->mtime = mtime_of(p);
        if (lower(p).size() > 4 && lower(p).compare(p.size() - 4, 4, ".iro") == 0) {
            m->iro = std::make_unique<IroArchive>();
            if (!m->iro->open(p)) { std::fprintf(stderr, "[mods] %s: %s, skipped\n", m->name.c_str(), m->iro->error().c_str()); continue; }
        } else if (!is_dir(p)) {
            std::fprintf(stderr, "[mods] %s: not found, skipped\n", p.c_str());
            continue;
        }
        std::string err;
        if (!load_modinfo(*m, err)) { std::fprintf(stderr, "[mods] %s: %s, skipped\n", m->name.c_str(), err.c_str()); continue; }
        OptionValues chosen;
        for (pugi::xml_node o : mn.children("Option")) chosen[lower(o.attribute("ID").value())] = o.attribute("Value").as_int();
        pending.push_back({ std::move(m), std::move(chosen) });
    }

    // The mods' <OrderConstraints>, as 7th Heaven's auto-sort applies them (MyModsViewModel
    // AutoSortBasedOnCategory: each mod in turn moved below its After mods, then above its Before
    // mods). 7th Heaven also refuses to start with such an order unless the player insists; here
    // the order is fixed and logged. Finishing Touch declares Before Enhanced Stock UI: below it,
    // ESUI's battle hext overwrote one byte of FT's rewritten call at 0x6DD49F and every battle
    // crashed (issue #12).
    auto find_id = [&](const std::string &id) {
        for (size_t k = 0; k < pending.size(); k++) if (lower(pending[k].mod->info.id) == id) return (int)k;
        return -1;
    };
    std::vector<Mod *> by_turn;
    for (const Pending &pm : pending) by_turn.push_back(pm.mod.get());
    for (Mod *who : by_turn) {
        auto move = [&](const std::string &other, bool after) {
            int o = find_id(other), me = -1;
            for (size_t k = 0; k < pending.size(); k++) if (pending[k].mod.get() == who) me = (int)k;
            if (o < 0 || o == me || (after ? o < me : o > me)) return;
            Pending pm = std::move(pending[me]);
            pending.erase(pending.begin() + me);
            int to = find_id(other);                         // its place after the removal
            pending.insert(pending.begin() + (after ? to + 1 : to), std::move(pm));
            const Mod *o2 = pending[after ? to : to + 1].mod.get();
            g.order_moves.push_back({ who, o2, after });
            std::fprintf(stderr, "[mods] %s moved %s %s (its mod.xml load order)\n", who->name.c_str(),
                         after ? "below" : "above", o2->name.c_str());
        };
        for (const std::string &id : who->info.order_after) move(id, true);
        for (const std::string &id : who->info.order_before) move(id, false);
    }

    // The mods' <Compatibility><Setting>s, as 7th Heaven applies them at launch (GetConstraints
    // with the options as chosen, then Constraint.Verify on each): the other mod's option is set to
    // the Require, or off a Forbid value. Finishing Touch's theme sets a dozen of Enhanced Stock
    // UI's (its battle view, battle boxes, ...); without them both mods drew their own battle
    // layout, names over the HP numbers.
    // 7th Heaven writes each change into the profile, so over launches the values settle (Finishing
    // Touch's theme off sets its own battle view to Theme Off, and from then on its "battle view 11"
    // rules no longer apply). The profile is not written here, so the same is reached in passes:
    // each pass applies, to the options as chosen, the rules that the previous pass's values call for.
    {
        struct Need { std::vector<int> require; std::vector<int> forbid; };
        std::vector<OptionValues> orig, vals;
        for (const Pending &pm : pending) orig.push_back(pm.chosen);
        vals = orig;
        for (int pass = 0; pass < 8; pass++) {
            const bool last = pass == 7;
            std::map<std::pair<int, std::string>, Need> needs;
            for (size_t a = 0; a < pending.size(); a++) {
                const ModInfo &info = pending[a].mod->info;
                const OptionValues mine = info.values(vals[a]);
                for (const ModInfo::CompatSetting &cs : info.compat_settings) {
                    if (!cs.my_id.empty()) {
                        auto it = mine.find(cs.my_id);
                        if (it == mine.end() || it->second != cs.my_value) continue;
                    }
                    int b = find_id(cs.mod_id);
                    if (b < 0) continue;
                    Need &n = needs[{ b, cs.their_id }];
                    if (cs.has_require) n.require.push_back(cs.require);
                    n.forbid.insert(n.forbid.end(), cs.forbid.begin(), cs.forbid.end());
                }
            }
            std::vector<OptionValues> next = orig;
            for (const auto &kv : needs) {
                const int ti = kv.first.first;
                const ModInfo &tinfo = pending[ti].mod->info;
                const ConfigOption *opt = nullptr;
                for (const ConfigOption &o : tinfo.options) if (lower(o.id) == kv.first.second) opt = &o;
                if (!opt) continue;                                  // 7th Heaven: the option no longer exists
                std::vector<std::pair<int, std::string>> allowed = opt->values;
                if (allowed.empty()) allowed = { { 0, "Off" }, { 1, "On" } };
                const Need &n = kv.second;
                auto forbidden = [&](int v) { return std::find(n.forbid.begin(), n.forbid.end(), v) != n.forbid.end(); };
                const int cur = tinfo.values(next[ti])[kv.first.second];
                int want = cur;
                bool clash = false;
                if (!n.require.empty()) {
                    want = n.require[0];
                    for (int r : n.require) clash |= (r != want);
                    clash |= forbidden(want);
                    bool exists = false;
                    for (const auto &v : allowed) exists |= (v.first == want);
                    clash |= !exists;
                } else if (forbidden(cur)) {
                    clash = true;
                    for (const auto &v : allowed) if (!forbidden(v.first)) { want = v.first; clash = false; break; }
                }
                if (clash) {     // 7th Heaven refuses to start; here the option stays as chosen
                    if (last) std::fprintf(stderr, "[mods] %s: option %s: the other mods ask for values that clash, left as chosen\n",
                                           pending[ti].mod->name.c_str(), opt->id.c_str());
                    continue;
                }
                next[ti][kv.first.second] = want;
            }
            if (next == vals) break;
            vals = std::move(next);
        }
        // what changed against the options as chosen
        for (size_t i = 0; i < pending.size(); i++) {
            const ModInfo &info = pending[i].mod->info;
            const OptionValues before = info.values(orig[i]), after = info.values(vals[i]);
            for (const ConfigOption &o : info.options) {
                const std::string id = lower(o.id);
                auto bi = before.find(id), ai = after.find(id);
                if (bi == before.end() || ai == after.end() || bi->second == ai->second) continue;
                std::string vname = std::to_string(ai->second);
                for (const auto &v : o.values) if (v.first == ai->second && !v.second.empty()) vname = v.second;
                g.setting_changes.push_back({ pending[i].mod.get(), o.name.empty() ? o.id : o.name, vname });
                std::fprintf(stderr, "[mods] %s: option %s set to %s (another mod's compatibility settings)\n",
                             pending[i].mod->name.c_str(), o.id.c_str(), vname.c_str());
            }
            pending[i].chosen = vals[i];
        }
    }

    for (Pending &pm : pending) {
        OptionValues opts = pm.mod->info.values(pm.chosen);
        for (const auto &kv : ffnx) opts.emplace(kv.first, kv.second);   // a mod's own option wins
        int mi = (int)g.mods.size();
        g.mods.push_back(std::move(pm.mod));
        Mod &mod = *g.mods[mi];
        for (const auto &v : mod.info.variables) g.vars.add_alias(v.first, v.second);
        // AppCore Profile.GetRuntime: active Conditionals and ModFolders; the mod root only
        // when it has neither (Wrap.cs). Conditionals come first, as there.
        int folders = 0;
        for (const ConditionalFolder &c : mod.info.conditionals) {
            if (!is_active(c.active_when.get(), opts, g.language)) continue;
            add_folder(mi, c.folder, &c);   // its run-time conditions are checked at each open
            mod.hext_folders.push_back(c.folder);
            folders++;
        }
        for (const ModFolder &f : mod.info.mod_folders) {
            if (!is_active(f.active_when.get(), opts, g.language)) continue;
            add_folder(mi, f.folder);
            mod.hext_folders.push_back(f.folder);
            folders++;
        }
        if (mod.info.conditionals.empty() && mod.info.mod_folders.empty()) add_folder(mi, "");
        mod.hext_folders.push_back("");
        for (const std::string &w : mod.info.windows_code)
            std::fprintf(stderr, "[mods] %s: %s -- Windows code, not supported on Android\n", mod.name.c_str(), w.c_str());
        std::fprintf(stderr, "[mods] %s \"%s\" %s: %d active folder(s)\n", mod.name.c_str(), mod.info.name.c_str(),
                     mod.info.version.c_str(), folders);
    }
    // The mod.xml at a mod's root is metadata, never a game file.
    g.map.erase("mod.xml");
    std::fprintf(stderr, "[mods] %zu mod(s), %zu game paths replaced\n", g.mods.size(), g.map.size());
    return 0;
}

namespace {
std::string jesc(const std::string &s) {
    std::string o;
    for (unsigned char c : s) {
        if (c == '"' || c == '\\') { o += '\\'; o += (char)c; }
        else if (c == '\n') o += "\\n";
        else if (c < 0x20) continue;
        else o += (char)c;
    }
    return o;
}
// Files in a mod's active folders that are not game files: its own metadata.
bool is_game_file(const std::string &key) {
    if (key == "mod.xml" || key.compare(0, 5, "hext/") == 0 || key.compare(0, 7, "preview") == 0) return false;
    if (key.find('/') != std::string::npos) return true;
    static const char *const meta[] = { ".png", ".jpg", ".jpeg", ".gif", ".txt", ".md", ".pdf", ".url", ".html", ".htm" };
    for (const char *e : meta) {
        size_t n = std::strlen(e);
        if (key.size() > n && key.compare(key.size() - n, n, e) == 0) return false;
    }
    return true;
}
std::string area_of(const std::string &key) {
    size_t s = key.find('/');
    return s == std::string::npos ? std::string("(root)") : key.substr(0, s);
}
}  // namespace

// The enabled mods of a profile, loaded as the game loads them, and where they collide:
// game files two mods both replace (the first in the profile wins, as in the game), and the
// mods' own <Compatibility> declarations. JSON; the caller frees it.
extern "C" char *ff7_mods_conflicts_json(const char *profile_path) {
    ff7_mods_load(profile_path, "");
    std::lock_guard<std::mutex> lk(g.lock);
    struct Pair { int files = 0; std::map<std::string, int> areas; std::vector<std::string> examples; bool conditional = false; };
    std::map<std::pair<int, int>, Pair> pairs;               // (winner, loser)
    for (const auto &kv : g.map) {
        if (!is_game_file(kv.first) || kv.second.size() < 2) continue;
        const int w = kv.second[0].mod;
        std::vector<int> seen{ w };
        for (size_t i = 1; i < kv.second.size(); i++) {
            const int l = kv.second[i].mod;
            if (std::find(seen.begin(), seen.end(), l) != seen.end()) continue;
            seen.push_back(l);
            Pair &p = pairs[{ w, l }];
            p.files++;
            p.areas[area_of(kv.first)]++;
            if (p.examples.size() < 6) p.examples.push_back(kv.first);
            if (kv.second[0].cond && kv.second[0].cond->has_runtime_conditions) p.conditional = true;
        }
    }
    std::string j = "{\"mods\":[";
    for (size_t i = 0; i < g.mods.size(); i++) {
        const Mod &m = *g.mods[i];
        j += std::string(i ? "," : "") + "{\"file\":\"" + jesc(m.name) + "\",\"id\":\"" + jesc(lower(m.info.id)) +
             "\",\"name\":\"" + jesc(m.info.name.empty() ? m.name : m.info.name) + "\"}";
    }
    j += "],\"overlaps\":[";
    bool first = true;
    for (const auto &kv : pairs) {
        const Pair &p = kv.second;
        std::vector<std::pair<int, std::string>> areas;
        for (const auto &a : p.areas) areas.push_back({ a.second, a.first });
        std::sort(areas.rbegin(), areas.rend());
        j += std::string(first ? "" : ",") + "{\"winner\":" + std::to_string(kv.first.first) + ",\"loser\":" +
             std::to_string(kv.first.second) + ",\"files\":" + std::to_string(p.files) + ",\"conditional\":" +
             (p.conditional ? "true" : "false") + ",\"areas\":[";
        for (size_t i = 0; i < areas.size() && i < 5; i++)
            j += std::string(i ? "," : "") + "{\"area\":\"" + jesc(areas[i].second) + "\",\"files\":" + std::to_string(areas[i].first) + "}";
        j += "],\"examples\":[";
        for (size_t i = 0; i < p.examples.size(); i++) j += std::string(i ? "," : "") + "\"" + jesc(p.examples[i]) + "\"";
        j += "]}";
        first = false;
    }
    // <Compatibility>: Forbid against another enabled mod; Require of a mod not enabled
    j += "],\"forbids\":[";
    first = true;
    for (size_t i = 0; i < g.mods.size(); i++)
        for (const auto &f : g.mods[i]->info.compat_forbids)
            for (size_t k = 0; k < g.mods.size(); k++)
                if (k != i && lower(g.mods[k]->info.id) == f.mod_id) {
                    j += std::string(first ? "" : ",") + "{\"mod\":" + std::to_string(i) + ",\"other\":" + std::to_string(k) +
                         ",\"why\":\"" + jesc(f.description) + "\"}";
                    first = false;
                }
    j += "],\"requires\":[";
    first = true;
    for (size_t i = 0; i < g.mods.size(); i++)
        for (const auto &r : g.mods[i]->info.compat_requires) {
            bool have = false;
            for (const auto &o : g.mods) if (lower(o->info.id) == r.mod_id) have = true;
            if (have) continue;
            j += std::string(first ? "" : ",") + "{\"mod\":" + std::to_string(i) + ",\"id\":\"" + jesc(r.mod_id) +
                 "\",\"why\":\"" + jesc(r.description) + "\"}";
            first = false;
        }
    // <OrderConstraints> the profile's order broke; the game loads them in the mods' order
    j += "],\"reordered\":[";
    first = true;
    for (const auto &mv : g.order_moves) {
        int a = -1, b = -1;
        for (size_t i = 0; i < g.mods.size(); i++) {
            if (g.mods[i].get() == mv.who) a = (int)i;
            if (g.mods[i].get() == mv.other) b = (int)i;
        }
        if (a < 0 || b < 0) continue;
        j += std::string(first ? "" : ",") + "{\"mod\":" + std::to_string(a) + ",\"other\":" + std::to_string(b) +
             ",\"below\":" + (mv.below ? "true" : "false") + "}";
        first = false;
    }
    j += "],\"settings\":[";
    first = true;
    for (const auto &sc : g.setting_changes) {
        int a = -1;
        for (size_t i = 0; i < g.mods.size(); i++) if (g.mods[i].get() == sc.mod) a = (int)i;
        if (a < 0) continue;
        j += std::string(first ? "" : ",") + "{\"mod\":" + std::to_string(a) + ",\"option\":\"" + jesc(sc.option) +
             "\",\"value\":\"" + jesc(sc.value) + "\"}";
        first = false;
    }
    j += "]}";
    char *out = (char *)std::malloc(j.size() + 1);
    if (out) std::memcpy(out, j.c_str(), j.size() + 1);
    return out;
}

extern "C" unsigned ff7_mods_count(void) {
    std::lock_guard<std::mutex> lk(g.lock);
    return (unsigned)g.map.size();
}

extern "C" const char *ff7_mods_redirect(const char *rel_in, char *buf, unsigned n) {
    if (!rel_in || !n) return nullptr;
    std::lock_guard<std::mutex> lk(g.lock);
    if (g.map.empty()) return nullptr;
    const std::string rel = norm_key(rel_in);
    // The folders 7th Heaven watches, most specific first (GameLauncher MonitorPaths: the
    // data folder and FFNx's texture folder, then Settings.ExtraFolders -- FFNx's direct/
    // (LGP entries: a mod's char/x.hrc is read as direct/char/x.hrc), music/, sfx/, ... --
    // then the game root).
    static const char *const kExtra[] = { "ambient/", "direct/", "lighting/", "mesh/", "music/", "override/",
                                          "save/", "sfx/", "shaders/", "time/", "voice/", "widescreen/" };
    std::vector<std::string> prefixes = { "data/lang-" + g.language + "/", "data/", "mods/textures/" };
    for (const char *e : kExtra) prefixes.push_back(e);
    prefixes.push_back("");
    for (const std::string &p : prefixes) {
        if (rel.compare(0, p.size(), p) != 0) continue;
        auto it = g.map.find(rel.substr(p.size()));
        if (it == g.map.end()) continue;
        // VFile.MapFile: the first override whose folder is active for this file wins.
        const std::string key = rel.substr(p.size());
        const Override *pick = nullptr;
        for (const Override &c : it->second) if (cond_active(c.cond, key)) { pick = &c; break; }
        if (!pick) continue;
        const Override &o = *pick;
        const Mod &m = *g.mods[o.mod];
        std::string host = m.iro ? materialize(m, o.mod, o.file) : o.file;
        if (host.empty()) return nullptr;
        std::snprintf(buf, n, "%s", host.c_str());
        return buf;
    }
    return nullptr;
}
