// companion.cpp - live game state for the Thor's bottom screen (docs/ROADMAP.md M7), and the
// commands its touch buttons send.
//
// The bottom screen is an Android view (android/.../CompanionView.java) that polls
// ff7_companion_json() a few times a second from the UI thread. The snapshot is built on the
// game thread (ff7_companion_frame, once per frame from ffnx_recomp::step_code): reading the
// mode calls into the game, which only that thread may do. The UI thread copies the last one.
// Commands go the other way through a mailbox the game thread drains in the same call
// (FFNx's toggles draw popups; save states run there).
#include <atomic>
#include <chrono>
#include <mutex>
#include <cstdio>
#include <cstring>
#include <string>
#include "common.h"
#include "globals.h"
#include "gamehacks.h"
#include "log.h"
#include <algorithm>
#include "ff7.h"
#include "ff7_savestate.h"

extern "C" void ff7_ss_location(char *out, unsigned n, uint32_t *play_seconds);   // savestate_ffnx.cpp
std::string ffnx_companion_layout_json();                                          // companion_render.cpp

namespace {

std::atomic<int> g_command{0};
std::mutex g_snap_lock;
std::string g_snap = "{\"ready\":0}";

// FF7 text (ASCII shifted down by 0x20; 0xFF ends it) to JSON-safe ASCII.
std::string ff7_name(const char *s, size_t n) {
    std::string out;
    for (size_t i = 0; i < n; i++) {
        unsigned char c = (unsigned char)s[i];
        if (c == 0xFF) break;
        if (c > 0x5E) continue;
        char a = (char)(c + 0x20);
        if (a == '"' || a == '\\') continue;
        out += a;
    }
    return out;
}

const char *mode_name(int m) {
    switch (m) {
    case MODE_FIELD: return "field";
    case MODE_BATTLE: return "battle";
    case MODE_WORLDMAP: return "world";
    case MODE_MENU: return "menu";
    default: return "other";
    }
}

// Each actor's ATB gauge in battle (0..0xFFFF), 0x44 bytes apart (1.02 US).
constexpr uint32_t k_atb = 0x9A8B14, k_atb_stride = 0x44;
// The battle scene's enemy type records (name, level, ..., HP at +0xA4), 1.02 US.
constexpr uint32_t k_scene_enemies = 0x9A8E9C;

}  // namespace

extern "C" void ff7_companion_command(int cmd) { g_command = cmd; }
extern "C" void ff7_companion_battle_tick();                    // companion_render.cpp
extern "C" void ff7_boosters_frame();                           // boosters.cpp
extern "C" void ff7_boosters_command(int cmd);
extern "C" int ff7_booster_speed();
extern "C" int ff7_booster_no_encounters();
extern "C" int ff7_booster_battle;

// The 2x button: 2x on, or back to normal speed. FFNx's own toggle switches the speed hack on
// at its current speed, which is 1x unless the launcher's 2x option set a minimum.
void GameHacks::companionToggleSpeedhack() {
    if (speedhack_enabled && speedhack_current_speed > 1.0) speedhack_enabled = false;
    else { speedhack_current_speed = std::max(speedhack_current_speed, 2.0); speedhack_enabled = true; }
    show_popup_msg(TEXTCOLOR_LIGHT_BLUE, "Speed: %2.1lfx", speedhack_enabled ? speedhack_current_speed : 1.0);
    std::fprintf(stderr, "[companion] speed: enabled %d, %.2fx (now %.2fx)\n", (int)speedhack_enabled, speedhack_current_speed, getCurrentSpeedhack());
}

namespace {
std::string build_snapshot() {
    if (!ff7_externals.savemap) return "{\"ready\":0}";
    struct game_mode *gm = getmode_cached();
    const int mode = gm ? (int)gm->driver_mode : -1;
    savemap *sm = ff7_externals.savemap;

    char loc[64] = "";
    uint32_t seconds = 0;
    ff7_ss_location(loc, sizeof loc, &seconds);
    for (char *p = loc; *p; p++) if (*p == '"' || *p == '\\') *p = ' ';

    std::string j;
    j.reserve(2048);
    char b[512];
    snprintf(b, sizeof b, "{\"ready\":1,\"mode\":\"%s\",\"loc\":\"%s\",\"gil\":%u,\"time\":%u,\"speed2x\":%d,"
             "\"speed\":%d,\"noenc\":%d,\"bplus\":%d,\"party\":[",
             mode_name(mode), loc, sm->gil, seconds, gamehacks.getCurrentSpeedhack() > 1.0 ? 1 : 0,
             ff7_booster_speed(), ff7_booster_no_encounters(), ff7_booster_battle);
    j += b;

    const bool battle = mode == MODE_BATTLE && ff7_externals.battle_context;
    bool first = true;
    for (int slot = 0; slot < 3; slot++) {
        const unsigned id = (unsigned char)sm->party_members[slot];
        if (id >= 9) continue;
        const savemap_char &c = sm->chars[id];
        unsigned hp = c.hp, mhp = c.max_hp, mp = c.mp, mmp = c.max_mp;
        // max_hp/max_mp are the game's last stat calculation (menu, battle); a member who
        // joined since has stale ones: the base values are the best guess until then
        if (hp > mhp) mhp = c.base_hp;
        if (mp > mmp) mmp = c.base_mp;
        int atb = -1;
        if (battle) {
            const battle_actor_vars &a = ff7_externals.battle_context->actor_vars[slot];
            if (a.maxHP > 0) { hp = (unsigned)a.currentHP; mhp = (unsigned)a.maxHP; mp = a.currentMP; mmp = a.maxMP; }
            atb = *(const uint16_t *)(uintptr_t)(k_atb + k_atb_stride * slot);
        }
        snprintf(b, sizeof b, "%s{\"name\":\"%s\",\"lv\":%d,\"hp\":%u,\"mhp\":%u,\"mp\":%u,\"mmp\":%u,\"limit\":%u,\"limlv\":%d,\"atb\":%d}",
                 first ? "" : ",", ff7_name(c.name, sizeof c.name).c_str(), (int)(unsigned char)c.level,
                 hp, mhp, mp, mmp, (unsigned)c.current_limit_bar, (int)c.current_limit_level, atb);
        j += b;
        first = false;
    }
    j += "],\"enemies\":[";
    if (battle) {
        const battle_ai_context *ctx = ff7_externals.battle_context;
        first = true;
        for (int i = 4; i < 10; i++) {
            if (!(ctx->enemyActorsMask & (1u << i))) continue;
            const battle_actor_vars &a = ctx->actor_vars[i];
            if (a.maxHP <= 0) continue;
            // the scene's enemy types (up to 3, 0xB8 bytes each, name first); the actor's
            // index is its type (1.02 US)
            std::string name = a.index < 3 ? ff7_name((const char *)(uintptr_t)(k_scene_enemies + 0xB8u * a.index), 32) : "";
            snprintf(b, sizeof b, "%s{\"slot\":%d,\"name\":\"%s\",\"hp\":%d,\"mhp\":%d,\"lv\":%d}", first ? "" : ",", i - 3,
                     name.c_str(), a.currentHP, a.maxHP, (int)a.level);
            j += b;
            first = false;
        }
    }
    j += "],\"layout\":";
    j += ffnx_companion_layout_json();
    j += "}";
    return j;
}
}  // namespace

// Commands: 1 speed 1x/2x/3x, 2 save state (current slot), 3 load it, 4 random battles on/off,
// 5 battle enhancement on/off (boosters.cpp).
extern "C" void ff7_companion_frame() {
    ff7_companion_battle_tick();
    ff7_boosters_frame();
    int cmd = g_command.exchange(0);
    switch (cmd) {
    case 1: case 4: case 5: ff7_boosters_command(cmd); break;
    case 2: ff7_savestate_request(0, "current"); break;
    case 3: ff7_savestate_request(1, "current"); break;
    default: break;
    }
    // a fresh snapshot ten times a second is plenty for the panels
    static auto last = std::chrono::steady_clock::time_point{};
    const auto now = std::chrono::steady_clock::now();
    if (now - last < std::chrono::milliseconds(100)) return;
    last = now;
    std::string snap = build_snapshot();
    std::lock_guard<std::mutex> lk(g_snap_lock);
    g_snap.swap(snap);
}

extern "C" int ff7_companion_json(char *out, int cap) {
    std::lock_guard<std::mutex> lk(g_snap_lock);
    if ((int)g_snap.size() >= cap) return snprintf(out, cap, "{\"ready\":0}");
    std::memcpy(out, g_snap.c_str(), g_snap.size() + 1);
    return (int)g_snap.size();
}
