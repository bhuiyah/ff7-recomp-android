// GitHub #34: the launcher's presses follow Config > Controller > Customize.
// Build and run: clang++ -std=c++17 -I src/android tests/pad_remap/test_pad_remap.cpp && ./a.out
#include <cstdio>
#include <cstring>
#include "ff7_pad_remap.h"

static int fails;
static void check(const char *what, unsigned got, unsigned want) {
    std::printf("%-44s %04X (want %04X)%s\n", what, got, want, got == want ? "" : "  FAIL");
    if (got != want) fails++;
}

int main() {
    uint32_t map[25] = {};
    // the defaults (0x41AA5F)
    map[0] = 0xF1; map[1] = 0xF2; map[2] = 0xEF; map[3] = 0xF0; map[4] = 0xEE; map[5] = 0xED;
    map[6] = 0xEC; map[7] = 0xEB; map[8] = 0xF3; map[11] = 0xF4;
    check("default: Y (menu) stays Y", ff7_pad_remap(0x8000, map), 0x8000);
    check("default: B (OK) stays B", ff7_pad_remap(0x2000, map), 0x2000);
    check("default: d-pad untouched", ff7_pad_remap(0x0005, map), 0x0005);
    // the reporter's case: Menu (Triangle) and Square swapped
    map[4] = 0xEB; map[7] = 0xEE;
    check("swapped: menu press goes to X", ff7_pad_remap(0x8000, map), 0x4000);
    check("swapped: menu + up", ff7_pad_remap(0x8001, map), 0x4001);
    check("swapped: OK unchanged", ff7_pad_remap(0x2000, map), 0x2000);
    // OK on A, cancel on B (a Nintendo-style layout)
    map[5] = 0xEC; map[6] = 0xED;
    check("OK moved to A", ff7_pad_remap(0x2000, map), 0x1000);
    check("cancel moved to B", ff7_pad_remap(0x1000, map), 0x2000);
    // menu on a trigger: no button carries it, the press stays where it was
    map[4] = 0xF1;
    check("menu on LT: stays Y", ff7_pad_remap(0x8000, map), 0x8000);
    // before the game has a mapping (all zero)
    uint32_t none[25] = {};
    check("no mapping yet: unchanged", ff7_pad_remap(0xA010, none), 0xA010);
    std::printf(fails ? "%d FAILED\n" : "all passed\n", fails);
    return fails != 0;
}
