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
// The field script's UC flag (opcode 0x33, handler 0x614155): non-zero while the script has
// taken the player's control away (cutscenes). The handler stores it at field global +0x32 and
// mirrors it here (1.02 US).
constexpr uint32_t k_field_uc = 0xCC040C;

// A cutscene: a movie playing, or a field scene running with the player's control taken.
bool cinematic(int mode) {
    if (ff7_externals.movie_object && ff7_externals.movie_object->is_playing) return true;
    return mode == MODE_FIELD && *(const volatile uint8_t *)(uintptr_t)k_field_uc != 0;
}

}  // namespace

// ---- the bottom screen's tabs (CompanionView): names from the game's own text --------------
// kernel2.bin's text sections, loaded by FFNx (ff7/kernel.cpp): kernel sections 10..27 at
// index section - 10. Each is a table of u16 offsets, then FF7 text.
extern char *kernel2_sections[20];
extern uint32_t kernel2_section_counter;

namespace {

constexpr int k_txt_items = 10, k_txt_weapons = 11, k_txt_armor = 12, k_txt_acc = 13,
              k_txt_materia = 14, k_txt_keys = 15;

std::string ktext(int sec, unsigned i) {
    if ((uint32_t)sec >= kernel2_section_counter || !kernel2_sections[sec]) return "";
    const char *s = kernel2_sections[sec];
    const uint16_t *off = (const uint16_t *)s;
    if (i >= off[0] / 2u) return "";
    return ff7_name(s + off[i], 40);
}

// An inventory or enemy item number: 0-127 items, 128-255 weapons, 256-287 armor, 288-319
// accessories (checked against scene.bin: Guard Scorpion drops the Assault Gun, 0x80+x).
std::string item_name(unsigned id) {
    if (id < 128) return ktext(k_txt_items, id);
    if (id < 256) return ktext(k_txt_weapons, id - 128);
    if (id < 288) return ktext(k_txt_armor, id - 256);
    if (id < 320) return ktext(k_txt_acc, id - 288);
    return "";
}

void json_list(std::string &j, const char *key, const std::string *v, int n) {
    j += "\""; j += key; j += "\":[";
    for (int i = 0; i < n; i++) { if (i) j += ","; j += "\""; j += v[i]; j += "\""; }
    j += "],";
}

const char *const k_elements[16] = { "Fire", "Ice", "Lightning", "Earth", "Poison", "Gravity", "Water", "Wind",
                                      "Holy", "Restorative", "Cut", "Hit", "Punch", "Shoot", "Shout", "Hidden" };
const char *const k_statuses[32] = { "Death", "Near-death", "Sleep", "Poison", "Sadness", "Fury", "Confu", "Silence",
                                     "Haste", "Slow", "Stop", "Frog", "Mini", "Slow-numb", "Petrify", "Regen",
                                     "Barrier", "MBarrier", "Reflect", "Dual", "Shield", "D.Sentence", "Manipulate",
                                     "Berserk", "Peerless", "Paralyzed", "Darkness", "Dual-Drain", "DeathForce",
                                     "Resist", "Lucky Girl", "Imprisoned" };
// statuses worth naming as immunities (what a player would try to inflict)
constexpr uint32_t k_inflictable = (1u << 0) | (1u << 2) | (1u << 3) | (1u << 4) | (1u << 5) | (1u << 6) | (1u << 7) |
                                   (1u << 9) | (1u << 10) | (1u << 11) | (1u << 12) | (1u << 14) | (1u << 21) |
                                   (1u << 22) | (1u << 23) | (1u << 25) | (1u << 26);
// shown as an enemy's current status (not the internal ones)
constexpr uint32_t k_visible_status = 0x3FFFFFFEu & ~(1u << 19) & ~(1u << 27) & ~(1u << 28) & ~(1u << 30) & ~(1u << 31);

// The scene's enemy record (scene.bin layout, 0xB8 bytes) as Sense would tell it.
void enemy_details(std::string &j, const uint8_t *r, uint32_t status_now) {
    std::string weak[8], absorb[8], voids[8], half[8];
    int nw = 0, na = 0, nv = 0, nh = 0;
    for (int k = 0; k < 8; k++) {
        const unsigned el = r[0x28 + k], rate = r[0x30 + k];
        if (el > 15 || rate == 0xFF) continue;
        const std::string e = k_elements[el];
        switch (rate) {
        case 0x00: weak[nw++] = e + " (KO)"; break;
        case 0x02: weak[nw++] = e; break;
        case 0x04: half[nh++] = e; break;
        case 0x05: voids[nv++] = e; break;
        case 0x06: case 0x07: absorb[na++] = e; break;
        default: break;
        }
    }
    json_list(j, "weak", weak, nw);
    json_list(j, "absorb", absorb, na);
    json_list(j, "void", voids, nv);
    json_list(j, "half", half, nh);
    std::string steal[4], drop[4];
    int ns = 0, nd = 0;
    for (int k = 0; k < 4; k++) {
        const unsigned rate = r[0x88 + k], id = *(const uint16_t *)(r + 0x8C + 2 * k);
        if (id == 0xFFFF) continue;
        std::string n = item_name(id);
        if (n.empty()) continue;
        if (rate >= 0x80) steal[ns++] = n; else drop[nd++] = n;
    }
    json_list(j, "steal", steal, ns);
    json_list(j, "drop", drop, nd);
    const unsigned morph = *(const uint16_t *)(r + 0xA0);
    j += "\"morph\":\"" + (morph == 0xFFFF ? std::string() : item_name(morph)) + "\",";
    // the mask holds the statuses that can be inflicted: a clear bit is an immunity (bosses
    // clear Death, Sleep, Confu, Stop...; ordinary enemies have FFFFFFFF)
    const uint32_t can = *(const uint32_t *)(r + 0xB0);
    std::string imm[32], now[32];
    int ni = 0, nn = 0;
    for (int b = 0; b < 32; b++) {
        if ((k_inflictable >> b & 1) && !(can >> b & 1)) imm[ni++] = k_statuses[b];
        if ((k_visible_status >> b & 1) && (status_now >> b & 1)) now[nn++] = k_statuses[b];
    }
    json_list(j, "immune", imm, ni);
    json_list(j, "status", now, nn);
}

// Equipped materia: name, AP, AP for the next level (kernel.bin section 9: four u16 level
// thresholds in hundreds of AP, FFFF past the last level). nx -1: mastered; -2: no levels.
void materia_json(std::string &j, const savemap_char &c) {
    const uint8_t *mdata = (const uint8_t *)(char *)ff7_externals.kernel_1to9_sections[8];
    j += ",\"mat\":[";
    bool first = true;
    for (int s = 0; s < 16; s++) {
        const uint32_t m = c.equipped_materia[s];
        const unsigned id = m & 0xFF, ap = m >> 8;
        if (id == 0xFF || m == 0xFFFFFFFFu) continue;
        std::string name = ktext(k_txt_materia, id);
        if (name.empty()) continue;
        long nx = -2;
        int lv = 1, levels = 1;
        if (mdata && id < 96) {
            const uint16_t *th = (const uint16_t *)(mdata + 0x14 * id);
            for (int k = 0; k < 4 && th[k] != 0xFFFF; k++) {
                levels++;
                if (ap >= th[k] * 100u) lv++;
                else if (nx < 0) nx = th[k] * 100L;
            }
            if (levels > 1 && (nx < 0 || ap == 0xFFFFFF)) nx = -1;
        }
        char b[160];
        snprintf(b, sizeof b, "%s{\"n\":\"%s\",\"ap\":%u,\"nx\":%ld,\"lv\":%d,\"lvs\":%d,\"arm\":%d}", first ? "" : ",",
                 name.c_str(), ap == 0xFFFFFF ? 0 : ap, nx, lv, levels, s >= 8 ? 1 : 0);
        j += b;
        first = false;
    }
    j += "]";
}

// Items, key items, counters, vehicles: the savemap (offsets checked against a mid-game save).
void savemap_json(std::string &j, const savemap *sm, bool world) {
    const uint8_t *s = (const uint8_t *)sm;
    j += "\"inv\":[";
    bool first = true;
    for (int i = 0; i < 320; i++) {
        const uint16_t w = sm->items[i];
        if (w == 0xFFFF) continue;
        const unsigned id = w & 0x1FF, qty = w >> 9;
        std::string n = item_name(id);
        if (n.empty() || !qty) continue;
        char b[96];
        snprintf(b, sizeof b, "%s[\"%s\",%u,%d]", first ? "" : ",", n.c_str(), qty, id < 128 ? 0 : 1);
        j += b;
        first = false;
    }
    j += "],\"keys\":[";
    first = true;
    for (int i = 0; i < 64; i++) {                      // 0x0BE4: one bit per key item
        if (!(s[0x0BE4 + i / 8] >> (i % 8) & 1)) continue;
        std::string n = ktext(k_txt_keys, i);
        if (n.empty()) continue;
        j += first ? "\"" : ",\""; j += n; j += "\"";
        first = false;
    }
    j += "],";
    unsigned kills = 0;
    for (int c = 0; c < 9; c++) kills += sm->chars[c].num_kills;
    unsigned races = 0;
    for (int c = 0; c < 4; c++) races += (unsigned char)sm->chocobo_slots_first[c].n_races_won;
    for (int c = 0; c < 2; c++) races += (unsigned char)sm->chocobo_slots_last[c].n_races_won;
    char b[256];
    snprintf(b, sizeof b, "\"stats\":{\"battles\":%u,\"escapes\":%u,\"kills\":%u,\"races\":%u,\"countdown\":%u},",
             *(const uint16_t *)(s + 0x0BBC), *(const uint16_t *)(s + 0x0BBE), kills, races, sm->countdown_timer);
    j += b;
    // Date (love) points, 0x0BA8..0x0BAB: Aerith, Tifa, Yuffie, Barret (#71). The highest one
    // decides who asks Cloud out at the Gold Saucer. Names as the player chose them.
    static const int love_char[4] = { 3, 2, 5, 1 };
    j += "\"love\":[";
    for (int i = 0; i < 4; i++) {
        snprintf(b, sizeof b, "%s[\"%s\",%u]", i ? "," : "",
                 ff7_name(sm->chars[love_char[i]].name, sizeof sm->chars[0].name).c_str(), (unsigned)s[0x0BA8 + i]);
        j += b;
    }
    j += "],";
    // world map: x in the low 19 bits of the first word, the model in the 5 above, z in the low 18
    // of the second (leader 0x0F5C, wild chocobo 0x0F64, Tiny Bronco / chocobo 0x0F6C, buggy /
    // Highwind 0x0F74, submarine 0x0F7C, the weapons 0x0F84); which vehicles exist: 0x0C23
    // (1 buggy, 4 Tiny Bronco, 0x10 Highwind)
    bool f = true;
    unsigned shown = 0;                                  // kinds listed, by model bit
    auto put = [&](const char *kind, unsigned x, unsigned z) {
        snprintf(b, sizeof b, "%s[\"%s\",%u,%u]", f ? "" : ",", kind, x, z);
        j += b;
        f = false;
    };
    j += "\"veh\":[";
    // On the world map the game writes those only when it leaves it (a battle, a town): the live
    // ones are its list of map objects (0xE39A00, up to 16 of 0xC0 bytes from 0xE39BC8; #79). The
    // underwater map has its own coordinates.
    if (world && ff7_externals.world_map_type_E045E8 && *ff7_externals.world_map_type_E045E8 == 0) {
        constexpr uint32_t k_list = 0xE39A00, k_pool = 0xE39BC8, k_obj = 0xC0, k_max = 16;
        uint32_t e = *(const uint32_t *)(uintptr_t)k_list;
        for (uint32_t n = 0; n < k_max && e >= k_pool && e < k_pool + k_obj * k_max && (e - k_pool) % k_obj == 0; n++) {
            const world_event_data &o = *(const world_event_data *)(uintptr_t)e;
            const char *kind = nullptr;
            switch (o.model_id) {
            case 3: kind = "highwind"; break;
            case 5: kind = "bronco"; break;
            case 6: kind = "buggy"; break;
            case 11: kind = "ultima"; break;
            case 13: kind = "submarine"; break;
            case 19: kind = "chocobo"; break;
            default: break;
            }
            if (kind && !(shown >> o.model_id & 1) && o.position.x > 0 && o.position.x < 9 * 4 * 8192 &&
                o.position.z > 0 && o.position.z < 7 * 4 * 8192) {
                put(kind, (unsigned)o.position.x, (unsigned)o.position.z);
                shown |= 1u << o.model_id;
            }
            e = *(const uint32_t *)(uintptr_t)e;         // next_ptr
        }
    }
    auto pos = [&](unsigned off, const char *kind, unsigned model) {
        const uint32_t a = *(const uint32_t *)(s + off), z = *(const uint32_t *)(s + off + 4);
        if ((!a && !z) || (shown >> model & 1)) return;
        put(kind, a & 0x7FFFF, z & 0x3FFFF);
    };
    const unsigned veh = s[0x0C23];
    if (veh & 0x10) pos(0x0F74, "highwind", 3);
    else if (veh & 0x01) pos(0x0F74, "buggy", 6);
    if (veh & 0x04) pos(0x0F6C, "bronco", 5);
    if (s[0x0C22] & 0x01) pos(0x0F64, "chocobo", 19);
    j += "],";
    const uint32_t lx = *(const uint32_t *)(s + 0x0F5C), lz = *(const uint32_t *)(s + 0x0F60);
    if (lx || lz) { snprintf(b, sizeof b, "\"wlead\":[%u,%u],", lx & 0x7FFFF, lz & 0x3FFFF); j += b; }
}

}  // namespace

extern "C" void ff7_companion_command(int cmd) { g_command = cmd; }
extern "C" void ff7_companion_battle_tick();                    // companion_render.cpp
extern "C" void ff7_boosters_frame();                           // boosters.cpp
extern "C" void ff7_achievements_frame();                       // achievements.cpp
extern "C" unsigned ff7_achievements_last(char *title, unsigned size, unsigned *points);
extern "C" void ff7_boosters_command(int cmd);
extern "C" int ff7_booster_speed();
extern "C" int ff7_booster_slow();
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
    // Nothing to show until a game is under way: at the title and its save screen the savemap
    // holds whatever was there (a play time of 1193046 hours). A game starts at its first field,
    // battle or world map, and ends back at the title or the credits.
    static bool in_game = false;
    if (mode == MODE_FIELD || mode == MODE_BATTLE || mode == MODE_WORLDMAP) in_game = true;
    else if (mode == MODE_MAIN_MENU || mode == MODE_CREDITS || mode == MODE_GAMEOVER) in_game = false;
    if (!in_game) return "{\"ready\":0}";

    char loc[64] = "";
    uint32_t seconds = 0;
    ff7_ss_location(loc, sizeof loc, &seconds);
    for (char *p = loc; *p; p++) if (*p == '"' || *p == '\\') *p = ' ';

    std::string j;
    j.reserve(2048);
    char b[512];
    snprintf(b, sizeof b, "{\"ready\":1,\"mode\":\"%s\",\"loc\":\"%s\",\"gil\":%u,\"time\":%u,\"speed2x\":%d,"
             "\"speed\":%d,\"slow\":%d,\"noenc\":%d,\"bplus\":%d,\"cine\":%d,\"party\":[",
             mode_name(mode), loc, sm->gil, seconds, gamehacks.getCurrentSpeedhack() > 1.0 ? 1 : 0,
             ff7_booster_speed(), ff7_booster_slow(), ff7_booster_no_encounters(), ff7_booster_battle, cinematic(mode) ? 1 : 0);
    j += b;
    {
        char at[96];
        unsigned pts = 0;
        const unsigned seq = ff7_achievements_last(at, sizeof at, &pts);
        for (char *p = at; *p; p++) if (*p == '"' || *p == '\\' || (unsigned char)*p < 0x20) *p = ' ';
        snprintf(b, sizeof b, "\"achseq\":%u,\"ach\":\"%s\",\"achpts\":%u,", seq, at, pts);
        j.insert(j.size() - std::strlen("\"party\":["), b);
    }
    // the world map panel (CompanionView): the party's position on the world map, live while
    // there; wsave: the savemap's two words at 0x0F5C (where the game keeps it between visits)
    {
        if (mode == MODE_WORLDMAP && ff7_externals.world_player_pos_E04918) {
            const vector4<int> &p = *ff7_externals.world_player_pos_E04918;
            snprintf(b, sizeof b, "\"wx\":%d,\"wz\":%d,", p.x, p.z);
            j.insert(j.size() - std::strlen("\"party\":["), b);
        }
        const uint32_t *ws = (const uint32_t *)((const uint8_t *)sm + 0x0F5C);
        snprintf(b, sizeof b, "\"wsave\":[%u,%u],", ws[0], ws[1]);
        j.insert(j.size() - std::strlen("\"party\":["), b);
    }

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
        snprintf(b, sizeof b, "%s{\"name\":\"%s\",\"id\":%u,\"lv\":%d,\"hp\":%u,\"mhp\":%u,\"mp\":%u,\"mmp\":%u,\"limit\":%u,\"limlv\":%d,\"atb\":%d",
                 first ? "" : ",", ff7_name(c.name, sizeof c.name).c_str(), id, (int)(unsigned char)c.level,
                 hp, mhp, mp, mmp, (unsigned)c.current_limit_bar, (int)c.current_limit_level, atb);
        j += b;
        const unsigned acc = (unsigned char)c.field_1E;
        j += ",\"wpn\":\"" + ktext(k_txt_weapons, (unsigned char)c.equipped_weapon) + "\",\"arm\":\"" +
             ktext(k_txt_armor, (unsigned char)c.equipped_armor) + "\",\"acc\":\"" +
             (acc < 32 ? ktext(k_txt_acc, acc) : std::string()) + "\"";
        materia_json(j, c);
        j += "}";
        first = false;
    }
    j += "],";
    savemap_json(j, sm, mode == MODE_WORLDMAP);
    j += "\"enemies\":[";
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
            snprintf(b, sizeof b, "%s{\"slot\":%d,\"name\":\"%s\",\"hp\":%d,\"mhp\":%d,\"lv\":%d,", first ? "" : ",", i - 3,
                     name.c_str(), a.currentHP, a.maxHP, (int)a.level);
            j += b;
            if (a.index < 3) enemy_details(j, (const uint8_t *)(uintptr_t)(k_scene_enemies + 0xB8u * a.index), a.statusMask);
            j.back() = '}';
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
// 5 battle enhancement on/off, 6 slow motion off/0.5x/0.25x (boosters.cpp).
extern "C" void ff7_companion_frame() {
    ff7_companion_battle_tick();
    ff7_boosters_frame();
    ff7_achievements_frame();
    int cmd = g_command.exchange(0);
    switch (cmd) {
    case 1: case 4: case 5: case 6: ff7_boosters_command(cmd); break;
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
