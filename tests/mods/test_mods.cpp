// test_mods.cpp - the mod overlay on the PC: IRO reading (raw / LZS / LZMA), mod.xml
// conditions, profile priority and the game-path mapping. Data from make_test_mods.py.
// usage: test_mods <dir made by make_test_mods.py>
#include <cstdio>
#include <cstdlib>
#include <cstring>
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
    }

    // ---- no profile: nothing redirected
    CHECK(ff7_mods_load((dir + "/missing.xml").c_str(), cache.c_str()) == 0);
    CHECK(ff7_mods_count() == 0 && redirect("data/field/raw.bin") == "<none>");

    std::printf("%d passed, %d failed\n", g_pass, g_fail);
    return g_fail ? 1 : 0;
}
