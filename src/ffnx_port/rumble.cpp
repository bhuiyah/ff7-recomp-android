// rumble.cpp - controller rumble (Settings > Controller > Rumble; files/boosters.txt rumble=0|1|2).
// FF7 has no vibration of its own on any platform, so rumble follows what the game shows:
//
//   field   while the field shakes (script opcode SHAKE: the background's x/y shake, FFNx's
//           ff7_modules_global_object shake_bg_x/y), as strong as the shake is wide. A shake
//           the script leaves running eases off to a low hum after a couple of seconds.
//   battle  when a party member's HP drops: a pulse as strong and as long as the share of
//           their max HP lost, a long full one when they fall. The HP is the one the battle
//           window draws (the 0x440-byte character records, boosters.cpp), so the pulse
//           comes with the damage number.
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

// battle: the party's HP as the battle window draws it (1.02 US; boosters.cpp has the layout)
constexpr uint32_t k_party_rec = 0xDBA498 + 0x10, k_party_rec_stride = 0x440;
int g_prev_hp[3] = { -1, -1, -1 };

// what is playing: amplitude and when it ends; a stronger request replaces a weaker one
int g_amp;
clk::time_point g_until;

// field shake: since when, and the last refresh sent
bool g_shaking;
clk::time_point g_shake_since, g_shake_sent;

int scaled(int amp) {
    if (g_level == 1) amp = amp * 55 / 100;
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
    int amp = 70 + width * 30;   // 1 px: a light buzz; 6 px and more: full
    const long held = (long)std::chrono::duration_cast<std::chrono::milliseconds>(now - g_shake_since).count();
    if (held > 2000) {           // left running: ease off to a third over the next second
        const long fade = held > 3000 ? 1000 : held - 2000;
        amp = amp - (int)((long)amp * 2 / 3 * fade / 1000);
    }
    play(amp, 220, now, true);   // the shake's own level, also as it eases off
}

void battle_frame(clk::time_point now, bool settling) {
    savemap *sm = ff7_externals.savemap;
    for (int slot = 0; slot < 3; slot++) {
        const uint16_t *rec = (const uint16_t *)(uintptr_t)(k_party_rec + k_party_rec_stride * slot);
        const int hp = rec[0], max = rec[1];
        const int prev = g_prev_hp[slot];
        // an empty party slot (0xFF) keeps an old record the battle clears as it sets up: that
        // read as a fall at every battle's start with two in the party
        const bool member = sm && (unsigned char)sm->party_members[slot] < 9;
        g_prev_hp[slot] = member && max > 0 ? hp : -1;
        if (settling || !member || max <= 0 || prev < 0 || hp >= prev) continue;
        if (hp == 0) {           // fallen
            play(255, 450, now);
            std::fprintf(stderr, "[rumble] party %d KO\n", slot);
            continue;
        }
        const int lost = prev - hp;
        std::fprintf(stderr, "[rumble] party %d hit, %d of %d HP\n", slot, lost, max);
        const int share = lost * 1000 / max;               // per mille of max HP
        const int amp = 110 + share * 145 / 400;          // 40% of max HP and more: full
        const int ms = 90 + (share > 500 ? 500 : share) * 260 / 500;
        play(amp, ms, now);
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
        stop();
    }
    // a battle's first second sets its records up: only watched, nothing played
    const bool settling = now - mode_since < std::chrono::milliseconds(1000);
    if (mode == MODE_FIELD) field_frame(now);
    else if (mode == MODE_BATTLE && ff7_externals.battle_context) battle_frame(now, settling);
}
