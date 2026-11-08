// mods.cpp - the mod overlay: see mods.h. Mirrors 7th Heaven's AppWrapper (Wrap.cs
// AddIROFilesToMappedFiles / AddFolderFilesToMappedFiles, VFile.cs MapFile) and AppCore's
// Profile.GetRuntime, MS-PL.
#include "mods.h"
#include <sys/stat.h>
#include <cctype>
#include <cstdio>
#include <cstring>
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
        const OptionValues opts = m->info.values(chosen);
        int mi = (int)g.mods.size();
        g.mods.push_back(std::move(m));
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
