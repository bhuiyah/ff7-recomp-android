// achievements.cpp - RetroAchievements-style achievements (docs/ROADMAP.md M6), offline.
//
// RetroAchievements' FF7 set is written for the PlayStation version: its conditions read PS1
// RAM. This port runs the PC exe, so the conditions are evaluated against a virtual PS1 RAM
// that mirrors the PC game's structures at their PS1 addresses each frame. The savemap (party,
// items, materia, gil, play time, story variables) has the same layout on both and sits at PS1
// 0x9C6E4 (from the PS1 cheat codes: gil at 0x9D260 = savemap + 0xB7C). Battle and field engine
// RAM differ between the versions and is not mirrored (yet).
//
// The rcheevos runtime (RetroAchievements' own evaluation library, MIT) evaluates the set; no
// server, no account: RetroAchievements has not approved this client, and connecting before it
// does is against their rules. The set is a local file in RA's own local-achievements format
// (files/achievements/ff7-local.txt, the built-in set, rewritten at each start; your own
// additions go in files/achievements/ff7-custom.txt, same format, ids not used by the set):
//   1.0                      (format version)
//   Final Fantasy VII        (title)
//   id:"memaddr":"title":"description":progress:max:format:author:points:created:modified:up:down:badge
// Unlocks: files/achievements/unlocks.txt ("id<TAB>unix time<TAB>title"), shown by the launcher.
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <string>
#include <vector>
#include <set>
#include <sys/stat.h>
#include <unistd.h>
#include "common.h"
#include "globals.h"
#include "log.h"
#include "ff7.h"
#include "rc_runtime.h"

namespace {

constexpr uint32_t k_ps1_ram = 0x200000 + 0x400;   // main RAM, then the scratchpad (rcheevos' PS1 map)
constexpr uint32_t k_ps1_savemap = 0x9C6E4;
constexpr uint32_t k_savemap_size = 0x10F4;

uint8_t g_ram[k_ps1_ram];
rc_runtime_t g_rt;
bool g_inited, g_loaded, g_in_game;
std::string g_dir;

struct Ach { uint32_t id; std::string memaddr, title, description; unsigned points; };
std::vector<Ach> g_set;
std::set<uint32_t> g_unlocked;
std::string g_last_title;            // the latest unlock, for the on-screen banner (companion snapshot)
unsigned g_last_points, g_seq;

// The starter set (our own, savemap-only; RetroAchievements' official set cannot be fetched
// without their servers). PS1 addresses: savemap 0x9C6E4; Cloud's record 0x9C738 (level +1,
// kills +0x24 (16-bit), Limit uses +0x26, exp +0x3C); gil 0x9D260; play time (s) 0x9D264.
const char *k_starter =
    "1.0\n"
    "Final Fantasy VII\n"
    "111000001:\"0x 9c75c>d0x 9c75c\":\"First Blood\":\"Defeat your first enemy as Cloud\"::::FF7Recomp:5:::::\n"
    "111000002:\"0x 9c75c>=100\":\"Seasoned Merc\":\"Defeat 100 enemies as Cloud\"::::FF7Recomp:10:::::\n"
    "111000003:\"0x 9c75c>=1000\":\"SOLDIER First Class\":\"Defeat 1000 enemies as Cloud\"::::FF7Recomp:25:::::\n"
    "111000004:\"0xH9c739>=10\":\"Getting Stronger\":\"Raise Cloud to level 10\"::::FF7Recomp:5:::::\n"
    "111000005:\"0xH9c739>=50\":\"Veteran\":\"Raise Cloud to level 50\"::::FF7Recomp:10:::::\n"
    "111000006:\"0xH9c739>=99\":\"Peak Condition\":\"Raise Cloud to level 99\"::::FF7Recomp:50:::::\n"
    "111000007:\"0x 9c75e>d0x 9c75e\":\"Limit Break!\":\"Use one of Cloud's Limit Breaks\"::::FF7Recomp:5:::::\n"
    "111000008:\"0xX9d260>=10000\":\"Pocket Money\":\"Hold 10,000 gil\"::::FF7Recomp:5:::::\n"
    "111000009:\"0xX9d260>=1000000\":\"Shinra's Envy\":\"Hold 1,000,000 gil\"::::FF7Recomp:25:::::\n"
    "111000010:\"0xX9d264>=36000\":\"Long Journey\":\"Play for 10 hours\"::::FF7Recomp:10:::::\n"
    // Story and bosses: the Game Moment (the story progress the field scripts test; field bank 1
    // word 0, savemap +0xBA4 = PS1 0x9D288), values from the FF7 speedrun community's Game Moment
    // list. Each unlocks when the moment crosses its value (below last frame, at or above now):
    // a save already past it does not unlock it on loading.
    "111000101:\"d0x 9d288<27_0x 9d288>=27\":\"Scorpion Hunter\":\"Defeat the Guard Scorpion\"::::FF7Recomp:10:::::\n"
    "111000102:\"d0x 9d288<33_0x 9d288>=33\":\"Mission Accomplished\":\"Blow up Mako Reactor 1\"::::FF7Recomp:5:::::\n"
    "111000103:\"d0x 9d288<140_0x 9d288>=140\":\"Grounded\":\"Defeat Air Buster\"::::FF7Recomp:10:::::\n"
    "111000104:\"d0x 9d288<188_0x 9d288>=188\":\"Welcome to Wall Market\":\"Arrive at Wall Market\"::::FF7Recomp:5:::::\n"
    "111000105:\"d0x 9d288<209_0x 9d288>=209\":\"Sewer Rat\":\"Defeat Aps\"::::FF7Recomp:10:::::\n"
    "111000106:\"d0x 9d288<236_0x 9d288>=236\":\"The Plate Falls\":\"Witness the fall of the Sector 7 plate\"::::FF7Recomp:5:::::\n"
    "111000107:\"d0x 9d288<260_0x 9d288>=260\":\"Corporate Raid\":\"Break into Shinra Headquarters\"::::FF7Recomp:5:::::\n"
    "111000108:\"d0x 9d288<320_0x 9d288>=320\":\"Hostile Takeover\":\"Defeat Rufus\"::::FF7Recomp:10:::::\n"
    "111000109:\"d0x 9d288<341_0x 9d288>=341\":\"Beyond the Walls\":\"Escape Midgar\"::::FF7Recomp:10:::::\n"
    "111000110:\"d0x 9d288<385_0x 9d288>=385\":\"Five Years Ago\":\"Finish the Kalm flashback\"::::FF7Recomp:5:::::\n"
    "111000111:\"d0x 9d288<388_0x 9d288>=388\":\"Deep Trouble\":\"Defeat Bottomswell\"::::FF7Recomp:10:::::\n"
    "111000112:\"d0x 9d288<463_0x 9d288>=463\":\"Old Friends\":\"Defeat Dyne\"::::FF7Recomp:10:::::\n"
    "111000113:\"d0x 9d288<514_0x 9d288>=514\":\"Spirits at Rest\":\"Defeat Gi Nattak\"::::FF7Recomp:10:::::\n"
    "111000114:\"d0x 9d288<604_0x 9d288>=604\":\"Ancient Ruins\":\"Enter the Temple of the Ancients\"::::FF7Recomp:5:::::\n"
    "111000115:\"d0x 9d288<630_0x 9d288>=630\":\"Gatekeeper\":\"Defeat Demons Gate\"::::FF7Recomp:10:::::\n"
    "111000116:\"d0x 9d288<673_0x 9d288>=673\":\"Jenova's Shadow\":\"Defeat Jenova LIFE\"::::FF7Recomp:25:::::\n"
    "111000117:\"d0x 9d288<677_0x 9d288>=677\":\"End of Disc One\":\"Reach the end of disc one\"::::FF7Recomp:10:::::\n"
    "111000118:\"d0x 9d288<1570_0x 9d288>=1570\":\"Weapon Down\":\"Defeat Diamond Weapon\"::::FF7Recomp:25:::::\n"
    "111000119:\"d0x 9d288<1603_0x 9d288>=1603\":\"Heavy Metal\":\"Defeat Proud Clod\"::::FF7Recomp:25:::::\n"
    "111000120:\"d0x 9d288<1604_0x 9d288>=1604\":\"Mad Science\":\"Defeat Hojo\"::::FF7Recomp:25:::::\n"
    "111000121:\"d0x 9d288<1620_0x 9d288>=1620\":\"End of Disc Two\":\"Reach the end of disc two\"::::FF7Recomp:10:::::\n"
    "111000122:\"d0x 9d288<1999_0x 9d288>=1999\":\"Into the Planet\":\"Reach the bottom of the Northern Crater to face Sephiroth\"::::FF7Recomp:50:::::\n"
    ;

// One field of an RA local-achievements line: "quoted" (with \" and \\) or up to the next ':'.
std::string field(const char *&p) {
    std::string s;
    if (*p == '"') {
        for (p++; *p && *p != '"'; p++) {
            if (*p == '\\' && p[1]) p++;
            s += *p;
        }
        if (*p == '"') p++;
    } else {
        while (*p && *p != ':' && *p != '\n' && *p != '\r') s += *p++;
    }
    if (*p == ':') p++;
    return s;
}

std::string read_file(const std::string &path) {
    std::string s;
    if (FILE *f = fopen(path.c_str(), "rb")) {
        char buf[4096];
        size_t n;
        while ((n = std::fread(buf, 1, sizeof buf, f)) > 0) s.append(buf, n);
        std::fclose(f);
    }
    return s;
}

void parse_set(const std::string &text) {
    int line_no = 0;
    for (const char *p = text.c_str(); *p;) {
        const char *eol = std::strchr(p, '\n');
        std::string line(p, eol ? eol - p : std::strlen(p));
        p = eol ? eol + 1 : p + line.size();
        if (++line_no <= 2 || line.empty() || !(line[0] >= '0' && line[0] <= '9')) continue;   // version, title, leaderboards
        const char *q = line.c_str();
        std::vector<std::string> f;
        while (*q) f.push_back(field(q));
        if (f.size() < 4) continue;
        Ach a{ (uint32_t)std::strtoul(f[0].c_str(), nullptr, 10), f[1], f[2], f[3], f.size() > 8 ? (unsigned)std::atoi(f[8].c_str()) : 0 };
        bool dup = false;
        for (const Ach &x : g_set) dup |= x.id == a.id;
        if (a.id && !a.memaddr.empty() && !dup) g_set.push_back(a);
    }
}

void load_set() {
    // the built-in set, written out for the launcher (and as an example of the format)
    const std::string text = k_starter;
    if (FILE *f = fopen((g_dir + "/ff7-local.txt").c_str(), "wb")) { std::fwrite(text.data(), 1, text.size(), f); std::fclose(f); }
    parse_set(text);
    parse_set(read_file(g_dir + "/ff7-custom.txt"));
    for (const std::string &l : { read_file(g_dir + "/unlocks.txt") })
        for (const char *p = l.c_str(); *p;) {
            g_unlocked.insert((uint32_t)std::strtoul(p, nullptr, 10));
            const char *eol = std::strchr(p, '\n');
            p = eol ? eol + 1 : p + std::strlen(p);
        }
    int active = 0;
    for (const Ach &a : g_set) {
        if (g_unlocked.count(a.id)) continue;
        const int r = rc_runtime_activate_achievement(&g_rt, a.id, a.memaddr.c_str(), nullptr, 0);
        if (r == RC_OK) active++;
        else std::fprintf(stderr, "[achievements] %u \"%s\": condition not understood (%d)\n", a.id, a.title.c_str(), r);
    }
    std::fprintf(stderr, "[achievements] %zu in the set, %zu unlocked, %d active (local, offline)\n",
                 g_set.size(), g_unlocked.size(), active);
}

const Ach *find(uint32_t id) {
    for (const Ach &a : g_set) if (a.id == id) return &a;
    return nullptr;
}

void unlock(uint32_t id) {
    if (!g_unlocked.insert(id).second) return;
    rc_runtime_deactivate_achievement(&g_rt, id);
    const Ach *a = find(id);
    const char *title = a ? a->title.c_str() : "?";
    if (FILE *f = fopen((g_dir + "/unlocks.txt").c_str(), "ab")) {
        std::fprintf(f, "%u\t%lld\t%s\n", id, (long long)std::time(nullptr), title);
        std::fclose(f);
    }
    std::fprintf(stderr, "[achievements] unlocked %u \"%s\"\n", id, title);
    // shown by GameActivity's overlay (BoosterIcons.java) from the companion snapshot
    g_last_title = title;
    g_last_points = a ? a->points : 0;
    g_seq++;
}

void RC_CCONV on_event(const rc_runtime_event_t *e) {
    if (e->type == RC_RUNTIME_EVENT_ACHIEVEMENT_TRIGGERED) unlock(e->id);
}

uint32_t RC_CCONV peek(uint32_t address, uint32_t num_bytes, void *) {
    if (address >= k_ps1_ram || num_bytes > k_ps1_ram - address) return 0;
    uint32_t v = 0;
    std::memcpy(&v, g_ram + address, num_bytes);   // little-endian, as the PS1
    return v;
}

bool gameplay(int mode) {
    switch (mode) {
    case MODE_FIELD: case MODE_WORLDMAP: case MODE_BATTLE: case MODE_SWIRL: case MODE_MENU:
    case MODE_SNOWBOARD: case MODE_COASTER: case MODE_CONDOR: case MODE_SUBMARINE: case MODE_HIGHWAY:
    case MODE_CHOCOBO: case MODE_GAMEOVER: case MODE_AFTER_BATTLE:
        return true;
    default:
        return false;
    }
}

}  // namespace

// The latest unlock for the companion snapshot (companion.cpp): its sequence number (0 = none
// this session), title and points.
extern "C" unsigned ff7_achievements_last(char *title, unsigned size, unsigned *points) {
    if (size) std::snprintf(title, size, "%s", g_last_title.c_str());
    if (points) *points = g_last_points;
    return g_seq;
}

// A Steam achievement FFNx unlocked (src/ffnx_port/steam_android.cpp): the same in-game
// banner as the local set's.
extern "C" void ff7_achievements_banner(const char *title) {
    g_last_title = title;
    g_last_points = 0;
    g_seq++;
}

// A save state was loaded (src/ffnx_port/savestate_ffnx.cpp): the savemap jumped, so every
// achievement must be false again for a frame before it may unlock (as after a game load).
extern "C" void ff7_achievements_reset() {
    if (g_loaded) rc_runtime_reset(&g_rt);
}

// Once per game frame (companion.cpp).
extern "C" void ff7_achievements_frame() {
    if (!g_inited) {
        g_inited = true;
        char cwd[512];
        if (!getcwd(cwd, sizeof cwd)) return;
        g_dir = std::string(cwd) + "/../achievements";
        mkdir(g_dir.c_str(), 0775);
        rc_runtime_init(&g_rt);
        load_set();
        g_loaded = true;
    }
    if (!g_loaded || !ff7_externals.savemap) return;
    struct game_mode *m = getmode_cached();
    const bool in_game = m && gameplay((int)m->driver_mode);
    if (!in_game) { g_in_game = false; return; }
    // the virtual PS1 RAM: the savemap at its PS1 address
    std::memcpy(g_ram + k_ps1_savemap, (const void *)ff7_externals.savemap, k_savemap_size);
    if (!g_in_game) {
        // entering the game from the title screen (a new game or a loaded save): nothing unlocks
        // from the load itself
        g_in_game = true;
        rc_runtime_reset(&g_rt);
    }
    rc_runtime_do_frame(&g_rt, on_event, peek, nullptr, nullptr);
}
