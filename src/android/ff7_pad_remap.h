// ff7_pad_remap.h - presses the launcher makes for the player follow the game's button mapping.
//
// The bottom screen's Menu / Back / battle-window taps and the quick boot press pad buttons
// (XInput bits) that mean menu, OK and cancel with the game's default mapping. After
// Config > Controller > Customize those buttons do something else (GitHub #34: the Menu button
// stopped opening the menu once Menu was moved to another button). The game's live mapping
// (ff7_en 1.02, filled by 0x41A7EF and read by the input routine at 0x41A3A0) is 3 rows of 25
// dwords at 0x9A85E8: row d, entry i holds the device-d key/button code that sets PSX bit
// (1 << i). Row 1 is the pad, codes 0xEB..0xF4 being FFNx's buttons 1..10 (ff7/misc.cpp:
// X, A, B, Y, LB, RB, LT, RT, Back, Start). A press is moved to the button that now carries
// the action its button carries by default; anything not found keeps its button.
#pragma once
#include <stdint.h>

static const uint32_t FF7_PAD_MAP = 0x9A85E8u + 0x64u;   // row 1: the pad

// The XInput buttons that can be remapped, each with its code and the PSX bit (index) it
// sets by default (0x41AA5F).
struct ff7_pad_button { unsigned short xinput; uint32_t code; int psx; };
static const ff7_pad_button k_ff7_pad_buttons[] = {
    { 0x4000, 0xEB, 7 },    // X:     Square
    { 0x1000, 0xEC, 6 },    // A:     Cross (cancel)
    { 0x2000, 0xED, 5 },    // B:     Circle (OK)
    { 0x8000, 0xEE, 4 },    // Y:     Triangle (menu)
    { 0x0100, 0xEF, 2 },    // LB:    L1
    { 0x0200, 0xF0, 3 },    // RB:    R1
    { 0x0020, 0xF3, 8 },    // Back:  Select
    { 0x0010, 0xF4, 11 },   // Start: Start
};

// buttons: XInput bits meaning their default actions. map: the pad row (25 codes).
static inline unsigned short ff7_pad_remap(unsigned short buttons, const volatile uint32_t *map) {
    unsigned short out = buttons;
    for (const ff7_pad_button &b : k_ff7_pad_buttons) out &= (unsigned short)~b.xinput;
    for (const ff7_pad_button &b : k_ff7_pad_buttons) {
        if (!(buttons & b.xinput)) continue;
        unsigned short to = b.xinput;                    // not mapped to a button: as it was
        const uint32_t code = map[b.psx];
        for (const ff7_pad_button &c : k_ff7_pad_buttons) if (c.code == code) to = c.xinput;
        out |= to;
    }
    return out;
}
