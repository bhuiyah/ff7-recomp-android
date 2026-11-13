// rumble.cpp - controller rumble (Settings > Controller > Rumble; files/boosters.txt rumble=0|1|2).
// FF7 has no vibration of its own on any platform, so rumble follows what the game shows:
//
//   field   while the field shakes (script opcode SHAKE: the background's x/y shake, FFNx's
//           ff7_modules_global_object shake_bg_x/y), as strong as the shake is wide. A shake
//           the script leaves running eases off to a low hum after a couple of seconds.
//   battle  when a damage number comes up, on an enemy (a hit landed) or on the party (a hit
//           taken): the same short pulse; a party member falling gets a long one.
//
// The moment is the damage number's: display_battle_damage (0x5BB410) entering FFNx's effect60
// queue, its field_E the amount and field_10 the target. The HP in the battle context
// (battle_actor_vars) drops when the action starts, seconds earlier; the HP as shown (the
// 0x44-byte records at 0x9A8B14, HP at +0x38, one per actor: party 0-2, enemies 4-9; the battle
// window draws the party's from them, 0x4355F0) after the number: 0.2 s for the party, 1.7 s
// for an enemy. Should field_10 not name an actor with damage waiting, the target is the one
// whose shown HP is still above its HP by the amount. A shown drop no number accounted for
// plays as it comes.
//
// Called once per frame on the game thread (boosters.cpp). The vibrator is driven from Java
// (GameActivity's rumble thread) through ff7_platform_rumble (src/android).
#include <chrono>
#include <cstdint>
#include <cstdio>
#include "common.h"
#include "globals.h"
#include "ff7.h"

extern "C" void ff7_platform_rumble(int amplitude, int ms);   // src/android: 0 stops it

namespace {

using clk = std::chrono::steady_clock;

int g_level;   // 0 off, 1 light, 2 strong

constexpr uint32_t k_shown_hp = 0x9A8B14 + 0x38, k_shown_stride = 0x44;
int g_prev_hp[10];
bool g_number[60];              // display_battle_damage in that effect60 slot last frame
int g_credit[10];               // damage already played from its number, not yet shown
clk::time_point g_credit_at[10];

// what is playing: amplitude and when it ends; a stronger request replaces a weaker one
int g_amp;
clk::time_point g_until;

// field shake: since when, and the last refresh sent
bool g_shaking;
clk::time_point g_shake_since, g_shake_sent;

// Light is 70% of Strong. Short pulses under about 0.4 of full are hard to feel on the Thor's
// motor, so the levels below start high.
int scaled(int amp) {
    if (g_level == 1) amp = amp * 70 / 100;
    return amp < 1 ? 1 : amp > 255 ? 255 : amp;
}

void play(int amp, int ms, clk::time_point now, bool replace = false) {
    amp = scaled(amp);
    if (!replace && now < g_until && amp < g_amp) return;   // a stronger pulse is still going
    g_amp = amp;
    g_until = now + std::chrono::milliseconds(ms);
    ff7_platform_rumble(amp, ms);
}

void stop() {
    if (g_amp) ff7_platform_rumble(0, 0);
    g_amp = 0;
    g_until = clk::time_point();
}

void field_frame(clk::time_point now) {
    ff7_modules_global_object *g = nullptr;   // as the SHAKE opcode reaches it (FFNx field/opcode.cpp)
    if (ff7_externals.field_global_object_ptr && *ff7_externals.field_global_object_ptr)
        g = ff7_externals.field_global_object_ptr->get();
    int width = 0;
    if (g) {
        if (g->shake_bg_x.do_shake && g->shake_bg_x.shake_amplitude > width) width = g->shake_bg_x.shake_amplitude;
        if (g->shake_bg_y.do_shake && g->shake_bg_y.shake_amplitude > width) width = g->shake_bg_y.shake_amplitude;
    }
    if (width <= 0) {
        if (g_shaking) { g_shaking = false; stop(); }
        return;
    }
    if (!g_shaking) {
        g_shaking = true;
        g_shake_since = now;
        g_shake_sent = clk::time_point();
        std::fprintf(stderr, "[rumble] field shake, %d px\n", width);
    }
    // refreshed in short pieces, so it stops within a moment of the shake stopping
    if (now - g_shake_sent < std::chrono::milliseconds(150)) return;
    g_shake_sent = now;
    int amp = 130 + width * 25;  // 1 px: a clear buzz; 5 px and more: full
    const long held = (long)std::chrono::duration_cast<std::chrono::milliseconds>(now - g_shake_since).count();
    if (held > 2000) {           // left running: ease off to half over the next second
        const long fade = held > 3000 ? 1000 : held - 2000;
        amp = amp - (int)((long)amp / 2 * fade / 1000);
    }
    play(amp, 220, now, true);   // the shake's own level, also as it eases off
}

// Every hit, landed or taken, is the same pulse (asked for after trying both): one per damage
// number. Only a party member falling gets a longer, full one.
void hit(int i, int lost, int max, bool falls, clk::time_point now) {
    const bool party = i < 3;
    std::fprintf(stderr, "[rumble] %s %d %s, %d of %d HP\n", party ? "party" : "enemy", i,
                 falls ? "falls" : "hit", lost, max);
    if (falls && party) play(255, 450, now);
    else play(205, 165, now);
}

void battle_frame(clk::time_point now, bool settling) {
    battle_ai_context *bc = ff7_externals.battle_context;
    int shown[10], cur[10], max[10];
    for (int i = 0; i < 10; i++) {
        shown[i] = *(const uint16_t *)(uintptr_t)(k_shown_hp + k_shown_stride * i);
        cur[i] = bc->actor_vars[i].currentHP;
        max[i] = i == 3 ? 0 : bc->actor_vars[i].maxHP;   // 0: no actor
    }

    // A damage number coming up (FFNx's effect60 queue gets display_battle_damage, field_E the
    // amount). Its target: the actor whose shown HP is still above its HP by that much (the
    // action took it already, the window has not yet).
    bool claimed[10] = {};
    for (int k = 0; k < 60; k++) {
        const bool is = ff7_externals.effect60_array_fn[k] == ff7_externals.display_battle_damage_5BB410;
        const bool fresh = is && !g_number[k];
        g_number[k] = is;
        if (!fresh || settling) continue;
        const effect60_data &d = ff7_externals.effect60_array_data[k];
        const int dmg = d.field_E;
        if (dmg <= 0) continue;              // a miss
        // field_10: the target (seen 0/1 on Cloud and Barret, 5/6 on the enemies they hit)
        int t = d.field_10 >= 0 && d.field_10 < 10 && max[d.field_10] > 0 && shown[d.field_10] > cur[d.field_10]
                ? d.field_10 : -1;
        for (int pass = 0; pass < 2 && t < 0; pass++)
            for (int i = 0; i < 10 && t < 0; i++) {
                if (max[i] <= 0 || claimed[i]) continue;
                const int pending = shown[i] - cur[i];
                if (pass == 0 ? pending == dmg : pending > 0) t = i;
            }
        if (t < 0) continue;                 // a heal, or nothing waiting: left to the HP below
        claimed[t] = true;
        g_credit[t] += dmg;
        g_credit_at[t] = now;
        hit(t, dmg, max[t], cur[t] == 0 && shown[t] - dmg <= 0, now);
    }

    // The shown HP: a drop no number accounted for (played now), and the credits used up.
    for (int i = 0; i < 10; i++) {
        const int prev = g_prev_hp[i];
        g_prev_hp[i] = max[i] > 0 ? shown[i] : -1;
        if (g_credit[i] && now - g_credit_at[i] > std::chrono::milliseconds(3000)) g_credit[i] = 0;
        if (settling || max[i] <= 0 || prev < 0 || shown[i] >= prev) continue;
        int lost = prev - shown[i];
        const int used = lost < g_credit[i] ? lost : g_credit[i];
        g_credit[i] -= used;
        lost -= used;
        if (lost > 0) hit(i, lost, max[i], shown[i] == 0, now);
    }
}

}  // namespace

extern "C" void ff7_rumble_set_level(int level) {
    g_level = level < 0 ? 0 : level > 2 ? 2 : level;
    std::fprintf(stderr, "[rumble] %s\n", g_level == 0 ? "off" : g_level == 1 ? "light" : "strong");
}

extern "C" void ff7_rumble_frame(int mode) {
    if (!g_level) return;
    static int last_mode = -1;
    static clk::time_point mode_since;
    const clk::time_point now = clk::now();
    if (mode != last_mode) {     // nothing carries over from one module to the next
        last_mode = mode;
        mode_since = now;
        g_shaking = false;
        for (int &h : g_prev_hp) h = -1;
        for (int &c : g_credit) c = 0;
        stop();
    }
    // a battle's first second sets its records up: only watched, nothing played
    const bool settling = now - mode_since < std::chrono::milliseconds(1000);
    if (mode == MODE_FIELD) field_frame(now);
    else if (mode == MODE_BATTLE && ff7_externals.battle_context) battle_frame(now, settling);
}
