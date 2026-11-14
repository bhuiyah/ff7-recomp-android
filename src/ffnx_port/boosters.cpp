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
#include "patch.h"

extern "C" {
int ff7_booster_battle = 0;     // battle enhancement on
void ff7_platform_pad_autofire(int on);   // src/android: confirm held repeats (GitHub #33)
void ff7_rumble_set_level(int level);     // rumble.cpp: 0 off, 1 light, 2 strong
void ff7_rumble_frame(int mode);
}

namespace {

bool g_inited;
bool g_frameskip = true;   // sped up: draw only the frames 1x would (boosters.txt frameskip=on|off)
int g_gil_mult = 1;        // gil won in battle times this (boosters.txt gil=N, Settings > Game boosters)
bool g_autofire;           // in battles, holding confirm repeats it (boosters.txt autofire=on, Settings > Controller)
double g_skip_acc;

// More gil, EXP and AP from battles (GitHub #32; boosters.txt gil=N, exp=N, ap=N). At the end
// of a won battle the game (0x4314C5-0x431663) adds up the defeated enemies' EXP, AP and gil
// (0x99E2C0, 0x99E2C4, 0x99E2C8) and applies the gil materia, then calls 0x6CC173 (clears a
// byte) and pays the EXP and AP out to the party and its materia; the results screen then
// counts the gil total into the party's gil (0x6C6DE0; confirm pays the rest at once). The
// call is hooked: the totals are multiplied just before they are paid out, so the results
// screen shows the boosted amounts and levels and materia growth happen as the game does them.
int g_exp_mult = 1, g_ap_mult = 1;
constexpr uint32_t k_spoils_call = 0x431688, k_spoils_callee = 0x6CC173;
constexpr uint32_t k_total_exp = 0x99E2C0, k_total_ap = 0x99E2C4, k_total_gil = 0x99E2C8;

uint32_t scaled(uint32_t v, int mult, uint32_t cap) {
    const uint64_t r = (uint64_t)v * (uint64_t)mult;
    return r > cap ? cap : (uint32_t)r;
}

void spoils_hook() {
    uint32_t &exp = *reinterpret_cast<uint32_t *>(static_cast<uintptr_t>(k_total_exp));
    uint32_t &ap = *reinterpret_cast<uint32_t *>(static_cast<uintptr_t>(k_total_ap));
    uint32_t &gil = *reinterpret_cast<uint32_t *>(static_cast<uintptr_t>(k_total_gil));
    const uint32_t e0 = exp, a0 = ap, g0 = gil;
    // caps: a character's EXP stops at 9,999,999 and a materia's AP at 16,777,215 anyway; the
    // results screen adds the gil without a cap, so the party's stops at the game's 999,999,999
    exp = scaled(exp, g_exp_mult, 9999999u);
    ap = scaled(ap, g_ap_mult, 16777215u);
    const uint32_t have = ff7_externals.savemap ? ff7_externals.savemap->gil : 0;
    gil = scaled(gil, g_gil_mult, have < 999999999u ? 999999999u - have : 0);
    std::fprintf(stderr, "[boosters] battle EXP %u x%d = %u, AP %u x%d = %u, gil %u x%d = %u\n",
                 e0, g_exp_mult, exp, a0, g_ap_mult, ap, g0, g_gil_mult, gil);
    const gfn<void()> callee(k_spoils_callee);
    callee();   // what the call did
}

// The battle's party records (1.02 US): the 0x44-byte ones beside the ATB gauge, and the
// 0x440-byte character records (HP +0x10, Limit gauge +0x1B).
constexpr uint32_t k_atb = 0x9A8B14, k_atb_stride = 0x44;
constexpr uint32_t k_party_rec = 0xDBA498 + 0x10, k_party_rec_stride = 0x440;
// The battle's Limit values (0x34-byte records; +0 the value, +2 what the gauge rises to) and the
// party's Limit ready bits.
constexpr uint32_t k_limit = 0x9A8DC0, k_limit_stride = 0x34, k_limit_ready = 0x9A889E;

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
        } else if (!std::strcmp(key, "exp")) {
            const int g = std::atoi(val);
            if (g >= 1 && g <= 100) g_exp_mult = g;
        } else if (!std::strcmp(key, "ap")) {
            const int g = std::atoi(val);
            if (g >= 1 && g <= 100) g_ap_mult = g;
        } else if (!std::strcmp(key, "autofire")) {
            g_autofire = !std::strcmp(val, "on");
        } else if (!std::strcmp(key, "rumble")) {
            ff7_rumble_set_level(std::atoi(val));   // Settings > Controller > Rumble
        }
    }
    std::fclose(f);
    if (g_exp_mult > 1 || g_ap_mult > 1 || g_gil_mult > 1) {
        const uint8_t *site = reinterpret_cast<const uint8_t *>(static_cast<uintptr_t>(k_spoils_call));
        int32_t rel; std::memcpy(&rel, site + 1, 4);
        if (site[0] == 0xE8 && k_spoils_call + 5 + (uint32_t)rel == k_spoils_callee) replace_call(k_spoils_call, &spoils_hook);
        else std::fprintf(stderr, "[boosters] battle spoils code changed (a mod?): gil/EXP/AP boosters off\n");
    }
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

    ff7_rumble_frame(mode);

    const bool battle = mode == MODE_BATTLE && ff7_externals.battle_context;
    static int fire_state = -1;
    const int fire = g_autofire && battle ? 1 : 0;
    if (fire != fire_state) {
        ff7_platform_pad_autofire(fire);
        if (g_autofire) std::fprintf(stderr, "[boosters] hold-confirm repeat %s\n", fire ? "on (battle)" : "off");
        fire_state = fire;
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
        // the Limit gauge in battle. +0x1B of the character record is only the gauge as drawn: the
        // game raises it towards the value at 0x9A8DC2 + 0x34 * slot (copied from 0x9A8DC0) and,
        // when it reaches 255 on the way up, makes the Limit ready (0x432426: calls 0x4375CE and
        // 0x435139). Writing the drawn byte showed a full gauge that never became ready again
        // after the first Limit (#44). Set the value itself, as Cait Sith's Fury Brand does
        // (0x5DD148), and let the game fill the gauge and make the Limit ready.
        if (rec[1] > 0) {
            uint16_t *limit = (uint16_t *)(uintptr_t)(k_limit + k_limit_stride * slot);
            limit[0] = 0xFF;   // 0x9A8DC0: the value
            limit[1] = 0xFF;   // 0x9A8DC2: what the gauge rises to
        }
    }
    // the party's "Limit ready" bits (0x4375CE sets 1 << slot), logged when they change
    static uint16_t ready_seen = 0xFFFF;
    const uint16_t ready = *(const uint16_t *)(uintptr_t)k_limit_ready;
    if (ready != ready_seen) {
        std::fprintf(stderr, "[boosters] Limit ready bits %#x\n", ready);
        ready_seen = ready;
    }
}
