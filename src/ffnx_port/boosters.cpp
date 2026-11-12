// boosters.cpp - game boosters (docs/ROADMAP.md M8), as in the console re-releases: speed
// 1x / 2x / 3x, random battles off, battle enhancement (party HP and MP kept full, Limit gauge
// full). Set from the bottom screen's deck (companion.cpp commands 1, 4, 5), FFNx's L3 shortcut
// mode (R1/L1 speed, Circle battles, Cross battle enhancement) and the launcher's defaults
// (files/boosters.txt, read once at the first frame). The top-screen icons are an Android view
// (GameActivity) fed by the companion snapshot.
//
// Speed and random battles are FFNx's own speed hack and battle mode (gamehacks.cpp). They are not
// in the game's memory, so save states and the game's save files leave them as they are.
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <unistd.h>
#include "common.h"
#include "globals.h"
#include "gamehacks.h"
#include "renderer.h"
#include "log.h"
#include "cfg.h"
#include "ff7.h"
#include "ff7_savestate.h"

extern "C" {
int ff7_booster_battle = 0;     // battle enhancement on
}

namespace {

bool g_inited;
bool g_frameskip = true;   // sped up: draw only the frames 1x would (boosters.txt frameskip=on|off)
int g_gil_mult = 1;        // gil won in battle times this (boosters.txt gil=N, Settings > Game boosters)
bool g_gil_in_battle;      // a battle started and the game has not been back in the field since
uint32_t g_gil_before;     // the party's gil when it started
// A save state loaded mid-battle, or after it, is not that battle's spoils.
void gil_after_load(void *) { g_gil_in_battle = false; }
const ff7_savestate_hook g_gil_hook = { "boosters-gil", nullptr, nullptr, nullptr, nullptr, gil_after_load };
struct GilHook { GilHook() { ff7_savestate_register(&g_gil_hook); } } g_gil_register;
double g_skip_acc;

// The battle's party records (1.02 US): the 0x44-byte ones beside the ATB gauge, and the
// 0x440-byte character records (HP +0x10, Limit gauge +0x1B).
constexpr uint32_t k_atb = 0x9A8B14, k_atb_stride = 0x44;
constexpr uint32_t k_party_rec = 0xDBA498 + 0x10, k_party_rec_stride = 0x440;

// files/boosters.txt beside the game folder (as quickboot.txt): "speed=3", "encounters=off",
// "battle=on", one per line. Written by the launcher at Play.
void read_defaults() {
    char cwd[512], path[600];
    if (!getcwd(cwd, sizeof cwd)) return;
    std::snprintf(path, sizeof path, "%s/../boosters.txt", cwd);
    FILE *f = fopen(path, "rb");   // the prelude maps fopen (a host path passes through)
    if (!f) return;
    char line[64];
    while (std::fgets(line, sizeof line, f)) {
        char key[32] = "", val[32] = "";
        if (std::sscanf(line, " %31[^= ] = %31s", key, val) != 2) continue;
        if (!std::strcmp(key, "speed")) {
            const int s = std::atoi(val);
            if (s >= 1 && s <= 3) gamehacks.companionSetSpeed((double)s);
        } else if (!std::strcmp(key, "encounters")) {
            gamehacks.companionSetBattles(std::strcmp(val, "off") != 0);
        } else if (!std::strcmp(key, "battle")) {
            ff7_booster_battle = !std::strcmp(val, "on");
        } else if (!std::strcmp(key, "frameskip")) {
            g_frameskip = std::strcmp(val, "off") != 0;
        } else if (!std::strcmp(key, "gil")) {
            const int g = std::atoi(val);
            if (g >= 1 && g <= 100) g_gil_mult = g;
        }
    }
    std::fclose(f);
    std::fprintf(stderr, "[boosters] defaults: speed %.0fx, random battles %s, battle enhancement %s, frame skipping %s\n",
                 gamehacks.getCurrentSpeedhack(), gamehacks.companionBattlesWanted() ? "on" : "off",
                 ff7_booster_battle ? "on" : "off", g_frameskip ? "on" : "off");
}

// The rate the game asks for at 1x in the current mode (FFNx's ff7_limit_fps).
double base_rate(int mode) {
    if (ff7_fps_limiter < FPS_LIMITER_60FPS) return mode == MODE_BATTLE ? 15.0 : 30.0;
    if (mode == MODE_BATTLE) return 15.0 * battle_frame_multiplier;
    return 30.0 * common_frame_multiplier;
}

}  // namespace

void GameHacks::companionSetSpeed(double speed) {
    speedhack_current_speed = speed;
    speedhack_enabled = speed > 1.0;
    show_popup_msg(TEXTCOLOR_LIGHT_BLUE, "Speed: %.0fx", speed);
}

void GameHacks::companionSetBattles(bool on) {
    battle_wanted = on;
    show_popup_msg(TEXTCOLOR_LIGHT_BLUE, "Random battles: %s", on ? "ON" : "OFF");
}

void ff7_boosters_toggle_battle() {
    ff7_booster_battle = !ff7_booster_battle;
    show_popup_msg(TEXTCOLOR_LIGHT_BLUE, "Battle enhancement: %s", ff7_booster_battle ? "ON" : "OFF");
}

extern "C" void ff7_boosters_command(int cmd) {
    switch (cmd) {
    case 1: {   // speed: 1x -> 2x -> 3x -> 1x
        const double now = gamehacks.getCurrentSpeedhack();
        gamehacks.companionSetSpeed(now < 1.5 ? 2.0 : now < 2.5 ? 3.0 : 1.0);
        break;
    }
    case 4: gamehacks.companionSetBattles(!gamehacks.companionBattlesWanted()); break;
    case 5: ff7_boosters_toggle_battle(); break;
    default: break;
    }
}

// After each frame (FFNx Renderer::show, ffnx_edit_android.py): is the next one skipped? Sped
// up, only the share 1 / speed of the frames is drawn and presented -- 2x shows every 2nd, 3x
// every 3rd -- so the GPU draws as many frames as at 1x. Never during a movie.
namespace ffnx_recomp { void step_code(); }   // patch_recomp.cpp

extern "C" bool ffnx_frame_skip_next(bool movie) {
    // Frame rate "Original": FFNx installs none of its frame-limiter hooks (ff7_limit_fps), so the
    // per-frame work hung there -- quickboot (the Start setting), the bottom screen's buttons and
    // boosters, achievements -- never ran. It runs here instead, once per presented frame. And
    // the speed hack cannot work there (it scales FFNx's clock, which Original does not use):
    // skipping frames for it only made the game look slower (GitHub issue #11).
    if (ff7_fps_limiter < FPS_LIMITER_DEFAULT) { ffnx_recomp::step_code(); g_skip_acc = 0; return false; }
    const double speed = gamehacks.getCurrentSpeedhack();
    if (!g_frameskip || movie || speed <= 1.01) { g_skip_acc = 0; return false; }
    g_skip_acc += 1.0 / speed;
    if (g_skip_acc >= 0.999) { g_skip_acc -= 1.0; return false; }
    return true;
}

extern "C" int ff7_booster_speed() { return (int)(gamehacks.getCurrentSpeedhack() + 0.5); }
extern "C" int ff7_booster_no_encounters() { return gamehacks.companionBattlesWanted() ? 0 : 1; }

// Once per frame on the game thread (companion.cpp).
extern "C" void ff7_boosters_frame() {
    if (!g_inited) { g_inited = true; read_defaults(); }
    struct game_mode *m = getmode_cached();
    const int mode = m ? (int)m->driver_mode : -1;

    // above the display's 120 Hz the speed hack needs vsync off to get there (unless the extra
    // frames are skipped: then only the 1x rate is presented)
    const bool fast = !g_frameskip && base_rate(mode) * gamehacks.getCurrentSpeedhack() > 121.0;
    static int vsync_state = -1;
    const int want = (fast || !enable_vsync) ? 0 : 1;
    if (want != vsync_state) {
        newRenderer.setVsync(want != 0);
        vsync_state = want;
    }

    const bool battle = mode == MODE_BATTLE && ff7_externals.battle_context;

    // more gil: the gil a battle adds (its spoils screen pays it out after the battle) times
    // g_gil_mult, added when the game is back in the field or on the world map. Gil spent in
    // battle (Yuffie's coin toss) leaves the difference negative: nothing is added then.
    if (g_gil_mult > 1 && ff7_externals.savemap) {
        uint32_t &gil = ff7_externals.savemap->gil;
        if (mode == MODE_BATTLE && !g_gil_in_battle) {
            g_gil_in_battle = true;
            g_gil_before = gil;
        } else if (mode == MODE_GAMEOVER || mode == MODE_MAIN_MENU) {
            g_gil_in_battle = false;            // lost, or back to the title: no spoils
        } else if (g_gil_in_battle && (mode == MODE_FIELD || mode == MODE_WORLDMAP)) {
            g_gil_in_battle = false;
            if (gil > g_gil_before) {
                const uint32_t won = gil - g_gil_before;
                const uint64_t more = (uint64_t)won * (uint64_t)(g_gil_mult - 1);
                const uint64_t total = gil + more;
                gil = total > 999999999u ? 999999999u : (uint32_t)total;   // the game's own cap
                std::fprintf(stderr, "[boosters] battle gil %u, x%d: %u gil now\n", won, g_gil_mult, gil);
            }
        }
    }

    // battle enhancement: the party's HP and MP full in battle, the Limit gauge full
    if (!ff7_booster_battle || !ff7_externals.savemap) return;
    savemap *sm = ff7_externals.savemap;
    for (int slot = 0; slot < 3; slot++) {
        const unsigned id = (unsigned char)sm->party_members[slot];
        if (id >= 9) continue;
        sm->chars[id].current_limit_bar = 0xFF;   // battles start with it full
    }
    if (!battle) return;
    for (int slot = 0; slot < 3; slot++) {
        battle_actor_vars &a = ff7_externals.battle_context->actor_vars[slot];
        if (a.maxHP <= 0) continue;
        a.currentHP = a.maxHP;
        a.currentMP = a.maxMP;
        // the battle's two other copies (1.02 US): the 0x44-byte party records beside the ATB
        // gauge (HP +0x38, MP +0x3A) and the 0x440-byte character records at 0xDBA498 (HP +0x10,
        // max +0x12, MP +0x14, max +0x16), which the HP/MP numbers are drawn from
        uint16_t *rec = (uint16_t *)(uintptr_t)(k_party_rec + k_party_rec_stride * slot);
        if (rec[1] > 0) { rec[0] = rec[1]; rec[2] = rec[3]; }
        uint16_t *atb_rec = (uint16_t *)(uintptr_t)(k_atb + k_atb_stride * slot + 0x38);
        if (rec[1] > 0) { atb_rec[0] = rec[1]; atb_rec[1] = rec[3]; }
        // the Limit gauge in battle: +0x1B of the character record (0..255; found by watching it rise
        // as the MPs shot at Cloud)
        if (rec[1] > 0) *((uint8_t *)rec + 0x0B) = 0xFF;
    }
}
