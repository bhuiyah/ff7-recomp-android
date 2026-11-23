// test_mods.cpp - the mod overlay on the PC: IRO reading (raw / LZS / LZMA), mod.xml
// conditions, profile priority and the game-path mapping. Data from make_test_mods.py.
// usage: test_mods <dir made by make_test_mods.py>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <iterator>
#include <string>
#include <vector>
#include "iro.hpp"
#include "modinfo.hpp"
#include "mods.h"
#include "runtimevar.hpp"
#include "hext.hpp"
#include <map>

static int g_fail, g_pass;
#define CHECK(cond) do { if (cond) g_pass++; else { g_fail++; std::printf("FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond); } } while (0)

static std::string slurp(const std::string &p) {
    std::ifstream f(p, std::ios::binary);
    return std::string(std::istreambuf_iterator<char>(f), {});
}
static std::string as_str(const std::vector<uint8_t> &v) { return std::string(v.begin(), v.end()); }
#include <sys/stat.h>
#if defined(_WIN32)
#include <direct.h>
static void mkdir_one(const std::string &d) { _mkdir(d.c_str()); }
#else
static void mkdir_one(const std::string &d) { mkdir(d.c_str(), 0775); }
#endif
static void mkdir_p(const std::string &p) {
    for (size_t i = 1; i <= p.size(); i++) if (i == p.size() || p[i] == '/') mkdir_one(p.substr(0, i));
}
static bool exists(const std::string &p) { struct stat st; return stat(p.c_str(), &st) == 0; }
static void setenv_(const char *k, const char *v) {
#if defined(_WIN32)
    _putenv_s(k, v);
#else
    setenv(k, v, 1);
#endif
}

static std::string redirect(const char *rel) {
    char buf[1024];
    const char *r = ff7_mods_redirect(rel, buf, sizeof buf);
    return r ? slurp(r) : std::string("<none>");
}

// Fake game memory for the run-time conditions.
static std::map<uint32_t, uint8_t> g_mem;
static bool fake_read(uint32_t va, void *out, size_t n) {
    for (size_t i = 0; i < n; i++) { auto it = g_mem.find(va + (uint32_t)i); static_cast<uint8_t *>(out)[i] = it == g_mem.end() ? 0 : it->second; }
    return true;
}
static void poke16(uint32_t va, uint16_t v) { g_mem[va] = v & 0xFF; g_mem[va + 1] = v >> 8; }

int main(int argc, char **argv) {
    ff7mods::set_memory_reader(fake_read);
    if (argc < 2) { std::printf("usage: test_mods <dir>\n"); return 2; }
    const std::string dir = argv[1];
#if defined(_WIN32)
    _putenv("FF7_MODS_PRUNE_SYNC=1");                  // unused mod cache folders go at once
#else
    setenv("FF7_MODS_PRUNE_SYNC", "1", 1);
#endif

    // ---- IRO archive
    ff7mods::IroArchive a;
    CHECK(a.open(dir + "/test.iro"));
    CHECK(a.entries().size() == 15);
    std::vector<uint8_t> v;
    CHECK(a.read("BASE/field/RAW.bin", v) && as_str(v) == "raw contents");      // any case, either slash
    CHECK(a.read("base\\field\\abc.bin", v) && as_str(v) == "ABCABCABC");      // LZS with a back-reference
    CHECK(a.read("base\\kernel\\big.bin", v) && as_str(v) == slurp(dir + "/lzma_expected.bin"));   // LZMA
    CHECK(a.size("base\\kernel\\big.bin") == (int64_t)slurp(dir + "/lzma_expected.bin").size());
    CHECK(!a.find("nope.bin"));
    ff7mods::IroArchive bad;
    CHECK(!bad.open(dir + "/lzma_expected.bin") && !bad.error().empty());

    // ---- mod.xml and conditions
    std::string err;
    ff7mods::ModInfo mi;
    CHECK(a.read("mod.xml", v));
    CHECK(ff7mods::parse_mod_xml(as_str(v), mi, err));
    CHECK(mi.name == "Test Mod" && mi.version == "1.5");
    CHECK(mi.options.size() == 1 && mi.options[0].id == "Style" && mi.options[0].values.size() == 2);
    CHECK(mi.mod_folders.size() == 2 && mi.conditionals.size() == 4 && mi.variables.size() == 1);
    CHECK(mi.conditionals[1].conditions.count("field/perfile.bin") == 1);
    CHECK(mi.windows_code.size() == 1);
    ff7mods::OptionValues def = mi.values({}), alt = mi.values({ { "style", 1 } });
    // mod.xml lists alt (Style = 1) before base: as in 7th Heaven, the earlier folder wins
    CHECK(ff7mods::is_active(mi.mod_folders[1].active_when.get(), def, "en"));
    CHECK(!ff7mods::is_active(mi.mod_folders[0].active_when.get(), def, "en"));
    CHECK(ff7mods::is_active(mi.mod_folders[0].active_when.get(), alt, "en"));
    CHECK(ff7mods::is_active(mi.conditionals[3].active_when.get(), def, "en"));
    CHECK(!ff7mods::is_active(mi.conditionals[3].active_when.get(), alt, "en"));
    CHECK(ff7mods::option_spec_active("style != 3", def));
    CHECK(!ff7mods::option_spec_active("unknown = 0", def));

    // ---- profile, default option: base + cond active, alt not
    const std::string cache = dir + "/cache";
    CHECK(ff7_mods_load((dir + "/profile_default.xml").c_str(), cache.c_str()) == 0);
    CHECK(ff7_mods_count() > 0);
    CHECK(redirect("data/field/raw.bin") == "raw contents");
    CHECK(redirect("DATA/FIELD/ABC.BIN") == "ABCABCABC");
    CHECK(redirect("data/lang-en/kernel/big.bin") == slurp(dir + "/lzma_expected.bin"));   // 2026 language folder
    CHECK(redirect("data/field/cond.bin") == "conditional contents");
    CHECK(redirect("data/textures/shared.png") == "from folder mod");    // first mod in the profile wins
    CHECK(redirect("data/x.bin") == "<none>");                           // "ignored" folder not active
    CHECK(redirect("data/mod.xml") == "<none>");
    CHECK(redirect("save/save00.ff7") == "<none>");
    // FFNx's direct mode (LGP entries) and its other folders, as 7th Heaven's ExtraFolders:
    // the mod's field/raw.bin is also <basedir>/direct/field/raw.bin, music/field/raw.bin ...
    CHECK(redirect("direct/field/raw.bin") == "raw contents");
    CHECK(redirect("Music/FIELD/raw.bin") == "raw contents");
    CHECK(redirect("nothere/field/raw.bin") == "<none>");
    // conflicts: the folder mod (first) and the iro both replace textures/shared.png
    {
        char *j = ff7_mods_conflicts_json((dir + "/profile_default.xml").c_str());
        std::string s = j ? j : "";
        std::free(j);
        CHECK(s.find("\"overlaps\":[{\"winner\":") != std::string::npos);
        CHECK(s.find("textures/shared.png") != std::string::npos);
        CHECK(s.find("\"forbids\":[]") != std::string::npos);
    }
    CHECK(ff7_mods_load((dir + "/profile_default.xml").c_str(), cache.c_str()) == 0);

    // ---- profile, Style = 1: alt replaces raw.bin, the conditional folder drops out
    CHECK(ff7_mods_load((dir + "/profile_alt.xml").c_str(), cache.c_str()) == 0);
    CHECK(redirect("data/field/raw.bin") == "alt contents");
    CHECK(redirect("data/field/cond.bin") == "<none>");
    CHECK(redirect("data/field/abc.bin") == "ABCABCABC");               // base still active

    // ---- run-time conditions (game memory through the 7thHeaven.var aliases)
    CHECK(ff7_mods_load((dir + "/profile_default.xml").c_str(), cache.c_str()) == 0);
    g_mem[0xDC08EB] = 0;                                                     // Time
    CHECK(redirect("data/field/raw.bin") == "raw contents");                 // night folder inactive
    g_mem[0xDC08EB] = 2;
    CHECK(redirect("data/field/raw.bin") == "night contents");               // Time in 1..2: night wins (conditionals first)
    g_mem[0xDC08EB] = 3;
    CHECK(redirect("data/field/raw.bin") == "raw contents");
    poke16(0xCC15D0, 116);                                                   // FieldID, per-file ApplyTo
    CHECK(redirect("data/field/perfile.bin") == "perfile contents");
    poke16(0xCC15D0, 1);
    CHECK(redirect("data/field/perfile.bin") == "<none>");
    g_mem[0xDC1000] = 5; g_mem[0xDC1001] = 0x77;                             // Custom = Int & 0xFF == 5
    CHECK(redirect("data/field/custom.bin") == "<none>");                    // <Not> -> inactive
    g_mem[0xDC1000] = 4;
    CHECK(redirect("data/field/custom.bin") == "custom contents");
    ff7mods::RuntimeVars rv;
    CHECK(rv.eval_var("Sys:Year", "2000..3000"));
    CHECK(!rv.eval_var("Bogus:1", "1"));
    CHECK(rv.eval_var("CounterAdv:c:3", "1") && rv.eval_var("CounterAdv:c:3", "2") && rv.eval_var("CounterAdv:c:3", "0"));

    // ---- hext format (AppWrapper/HexPatch.cs)
    {
        std::map<uint32_t, uint8_t> mem;
        auto rd = [](uint32_t) -> uint32_t { return 0x7000; };
        auto wr = [&](uint32_t va, const uint8_t *b, uint32_t n) { for (uint32_t i = 0; i < n; i++) mem[va + i] = b[i]; };
        ff7mods::HextResult r = ff7mods::hext_run(
            "Title 1000=FF\n# comment\n{ comment\n{{\n2000=11\n}}\n+10\n1000=01 02,03\t04\n-10\n"
            "3000=90:3\n4000+4=AB\n5000^+2=CD\n6000:10\n  \n", true, rd, wr);
        CHECK(r.error.empty() && r.delay_ms < 0 && r.writes == 4);
        CHECK(!mem.count(0x1000) && !mem.count(0x2000));                    // title line, {{ }} block skipped
        CHECK(mem[0x1010] == 1 && mem[0x1011] == 2 && mem[0x1012] == 3 && mem[0x1013] == 4);   // +10, mixed separators
        CHECK(mem[0x2FF0] == 0x90 && mem[0x2FF2] == 0x90 && !mem.count(0x2FF3));   // -10, fill
        CHECK(mem[0x3FF4] == 0xAB);                                          // addr+4
        CHECK(mem[0x6FF2] == 0xCD);                                          // pointer read
        mem.clear();
        r = ff7mods::hext_run("t\r\nDelay=500\r\n1000=01\r\n", true, rd, wr);
        CHECK(r.delay_ms == 500 && r.writes == 0 && mem.empty());
        r = ff7mods::hext_run("t\r\nDelay=500\r\n1000=01\r\n", false, rd, wr);
        CHECK(r.writes == 1 && mem[0x1000] == 1);                            // CRLF, Delay line itself skipped
        r = ff7mods::hext_run("t\nDelay = soon\n1000=02\n", true, rd, wr);
        CHECK(r.skipped && mem[0x1000] == 1);                                // bad Delay: never applied
        r = ff7mods::hext_run("t\n1000=GG\n2000=01\n", true, rd, wr);
        CHECK(!r.error.empty() && r.writes == 0 && !mem.count(0x2000));      // an error stops the file
    }

    // ---- hext files of the enabled mods, in 7th Heaven's order
    {
        struct Got { std::vector<std::string> files; std::map<uint32_t, uint8_t> mem; } got;
        auto collect = [](const char *mod, const char *file, const char *text, unsigned len, void *ctx) {
            Got &g = *static_cast<Got *>(ctx);
            g.files.push_back(std::string(mod) + ":" + file);
            ff7mods::hext_run(std::string(text, len), true, [](uint32_t) { return 0u; },
                [&](uint32_t va, const uint8_t *b, uint32_t n) { for (uint32_t i = 0; i < n; i++) g.mem[va + i] = b[i]; });
        };
        CHECK(ff7_mods_load((dir + "/profile_default.xml").c_str(), cache.c_str()) == 0);
        ff7_mods_hext(collect, &got);
        // lowest priority mod first (test.iro, then folder_mod); names from the root first, then
        // folders; each name from Conditionals, ModFolders, root
        CHECK(got.files.size() == 4);
        if (got.files.size() == 4) {
            CHECK(got.files[0] == "test.iro:base/hext/shared.txt");
            CHECK(got.files[1] == "test.iro:hext/shared.txt");
            CHECK(got.files[2] == "test.iro:night/hext/n.txt");
            CHECK(got.files[3] == "folder_mod:hext/f.txt");
        }
        CHECK(got.mem[0x1000] == 3 && got.mem[0x1001] == 0xAA && !got.mem.count(0x1002));   // highest priority wins; alt inactive
        Got alt;
        CHECK(ff7_mods_load((dir + "/profile_alt.xml").c_str(), cache.c_str()) == 0);
        ff7_mods_hext(collect, &alt);
        CHECK(alt.mem[0x1002] == 0xBB);                                      // Style = 1 activates alt/
        Got compat;
        CHECK(ff7_mods_load((dir + "/profile_compat.xml").c_str(), cache.c_str()) == 0);
        ff7_mods_hext(collect, &compat);
        CHECK(compat.files.size() == 4);
        if (compat.files.size() == 4) {
            CHECK(compat.files[0] == "base_ui:hext/p.txt");    // listed first, but theme_mod's
            CHECK(compat.files[1] == "base_ui:v5/hext/v.txt"); // OrderConstraints put it above:
            CHECK(compat.files[3] == "theme_mod:hext/p.txt");  // its hext goes last
        }
        CHECK(compat.mem[0x2003] == 0x66 && !compat.mem.count(0x2004)); // FFNx.toml aspect_ratio = 2
        CHECK(compat.mem[0x2000] == 0x33 && compat.mem[0x2001] == 0x22);
        CHECK(compat.mem[0x2002] == 0x55);                     // theme_mod's Setting: base_ui view = 5
    }

    // ---- the mod cache (issue #51): one folder per mod file, whatever its place in the profile;
    // folders of mods no longer in the profile, and the old "<place>_<name>" ones, are removed
    {
        CHECK(ff7_mods_load((dir + "/profile_default.xml").c_str(), cache.c_str()) == 0);
        char buf[1024];
        const char *r = ff7_mods_redirect("data/field/raw.bin", buf, sizeof buf);
        std::string host = r ? r : "";
        for (char &c : host) if (c == '\\') c = '/';
        CHECK(host.find(cache + "/test.iro/") == 0);
        std::string old_dir = cache + "/1_test.iro", gone_dir = cache + "/Deleted Mod.iro";
        std::string fdir = cache + "/folder_mod";                      // kept: a folder mod's name
        for (const std::string &d : { old_dir, gone_dir, fdir }) {
            mkdir_p(d + "/sub");
            std::FILE *f = std::fopen((d + "/sub/x.bin").c_str(), "wb");
            if (f) { std::fputs("x", f); std::fclose(f); }
        }
        CHECK(ff7_mods_load((dir + "/profile_alt.xml").c_str(), cache.c_str()) == 0);
        CHECK(!exists(old_dir) && !exists(gone_dir));
        CHECK(exists(fdir) && exists(cache + "/test.iro"));
        CHECK(redirect("data/field/raw.bin") == "alt contents");
    }

    // ---- the mod cache's size limit (issue #75): over it, the files used longest ago go, down to
    // 90%, never the one used last; they are unpacked again when used. No one-minute guard here.
    {
        const std::string td = cache + "/test.iro/base/field", big = cache + "/test.iro/base/kernel/big.bin";
        std::filesystem::remove_all(cache + "/test.iro");
        std::filesystem::remove_all(cache + "/folder_mod");      // its x.bin from above
        setenv_("FF7_MODCACHE_GUARD_S", "0");
        setenv_("FF7_MODCACHE_LIMIT_BYTES", "2620");
        CHECK(ff7_mods_load((dir + "/profile_default.xml").c_str(), cache.c_str()) == 0);
        CHECK(ff7_mods_cache_bytes() == 0);
        CHECK(redirect("data/field/raw.bin") == "raw contents");             // 12 bytes
        CHECK(redirect("data/field/abc.bin") == "ABCABCABC");                // 9: 21 in all
        CHECK(ff7_mods_cache_bytes() == 21 && exists(td + "/raw.bin") && exists(td + "/abc.bin"));
        CHECK(redirect("data/kernel/big.bin").size() == 2600);              // 2621 > 2620
        CHECK(!exists(td + "/raw.bin") && !exists(td + "/abc.bin") && exists(big));   // oldest gone, newest kept
        CHECK(ff7_mods_cache_bytes() == 2600);
        CHECK(redirect("data/field/raw.bin") == "raw contents" && exists(td + "/raw.bin"));   // unpacked again
        CHECK(ff7_mods_cache_bytes() == 2612);
        // a new run counts what is there (2612); the next file over the limit evicts big.bin, used longest ago
        CHECK(ff7_mods_load((dir + "/profile_default.xml").c_str(), cache.c_str()) == 0);
        CHECK(ff7_mods_cache_bytes() == 2612);
        CHECK(redirect("data/field/raw.bin") == "raw contents");             // reused: now the newest use
        CHECK(redirect("data/field/abc.bin") == "ABCABCABC");                // 2621 > 2620
        CHECK(!exists(big) && exists(td + "/raw.bin") && exists(td + "/abc.bin"));
        CHECK(ff7_mods_cache_bytes() == 21);
        setenv_("FF7_MODCACHE_LIMIT_BYTES", "");
        CHECK(ff7_mods_load((dir + "/profile_default.xml").c_str(), cache.c_str()) == 0);
        CHECK(ff7_mods_cache_bytes() == 0);                                  // no limit: not counted
        CHECK(redirect("data/kernel/big.bin").size() == 2600 && exists(td + "/raw.bin") && exists(td + "/abc.bin"));
    }

    // ---- animations with their own in-between frames, and the frame counts kept between launches (#76)
    {
        const std::string ad = dir + "/anim", prof = ad + "/mods/profile.xml", cache = dir + "/animcache";
        const std::string known = ad + "/modanims.bin";
        auto name = [](int k) { return std::string{ (char)(97 + k / 676 % 26), (char)(97 + k / 26 % 26), (char)(97 + k % 26) }; };
        auto mine = [&](const std::string &rel) {                // replaced by the archive's copy?
            char buf[1024];
            const char *r = ff7_mods_redirect(rel.c_str(), buf, sizeof buf);
            return r && std::string(r).find("anims.iro") != std::string::npos;
        };
        auto picture = [&] {
            std::string s;
            for (int k = 0; k < 1500; k++) s += mine("direct/char/" + name(k) + "a.a") ? '1' : '0';
            for (int k = 0; k < 200; k++) s += mine("direct/battle/" + name(k).substr(1) + "da") ? '1' : '0';
            return s;
        };
        std::string want;
        for (int k = 0; k < 1500; k++) want += k % 3 ? '1' : '0';
        for (int k = 0; k < 200; k++) want += k % 3 ? '1' : '0';
        auto load = [&] { return ff7_mods_load(prof.c_str(), cache.c_str()) == 0 ? picture() : std::string(); };

        CHECK(load() == want);                                   // nothing known yet
        CHECK(redirect("data/field/texture_with_a_long_enough_name_04999.dds") == "t04999");
        const std::string kept = slurp(known);
        CHECK(kept.size() > 10000);
        CHECK(load() == want);                                   // all known
        CHECK(slurp(known) == kept);                             // nothing new: not written again

        // with the game's archive gone there is nothing to compare with: the animations stay
        namespace fs = std::filesystem;
        const std::string lgp = ad + "/game/data/field/char.lgp";
        fs::rename(lgp, lgp + "x");
        const std::string no_lgp = load();
        CHECK(no_lgp.substr(0, 1500) == std::string(1500, '1') && no_lgp.substr(1500) == want.substr(1500));
        fs::rename(lgp + "x", lgp);
        CHECK(load() == want);                                   // and the missing file was not remembered

        // a changed mod file is read again: the folder mod's is now half the archive's
        const std::string low = ad + "/mods/low/char/" + name(4) + "a.a";
        const std::string short5 = slurp(low).substr(0, 4) + std::string("\x05\0\0\0", 4) + slurp(low).substr(8, 28 + 5 * 48);
        { std::ofstream f(low, std::ios::binary); f.write(short5.data(), (std::streamsize)short5.size()); }
        std::string want2 = want;
        want2[4] = '0';                                          // the archive's 9 = (5 - 1) * 2 + 1
        CHECK(load() == want2);
        CHECK(load() == want2);

        // a damaged or cut-off file is made anew
        for (int kind = 0; kind < 4; kind++) {
            std::string d = slurp(known);
            const size_t full = d.size();
            if (kind == 0) d[full / 2] = (char)(d[full / 2] ^ 1);
            else if (kind == 1) d.resize(full / 2);
            else if (kind == 2) d.resize(5);
            else d.clear();
            { std::ofstream f(known, std::ios::binary); f.write(d.data(), (std::streamsize)d.size()); }
            CHECK(load() == want2);
            CHECK(slurp(known).size() == full);
        }
        // a mod switched off keeps its counts while its file is the same
        {
            std::ofstream f(ad + "/mods/other.xml");
            f << "<Profile Language=\"en\"><Mod Path=\"low\"/></Profile>";
        }
        { std::ofstream f(low, std::ios::binary | std::ios::app); f.put(0); }   // something new to write
        CHECK(ff7_mods_load((ad + "/mods/other.xml").c_str(), cache.c_str()) == 0);
        CHECK(slurp(known).find("anims.iro|") != std::string::npos);
        CHECK(load() == want2);
        CHECK(ff7_mods_load((ad + "/mods/other.xml").c_str(), cache.c_str()) == 0);   // closes the archive
        fs::remove(ad + "/mods/anims.iro");                      // deleted: its counts go with the next write
        { std::ofstream f(low, std::ios::binary | std::ios::app); f.put(0); }
        CHECK(ff7_mods_load((ad + "/mods/other.xml").c_str(), cache.c_str()) == 0);
        CHECK(slurp(known).find("anims.iro|") == std::string::npos);
    }

    // ---- no profile: nothing redirected
    CHECK(ff7_mods_load((dir + "/missing.xml").c_str(), cache.c_str()) == 0);
    CHECK(ff7_mods_count() == 0 && redirect("data/field/raw.bin") == "<none>");

    std::printf("%d passed, %d failed\n", g_pass, g_fail);
    return g_fail ? 1 : 0;
}
