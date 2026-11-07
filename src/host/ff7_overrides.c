/*
 * ff7_overrides.c - entry overrides for lifted functions (FFNx replace_function).
 *
 * A replaced function's VA has its bit set in ff7_override_bits (one bit per byte of
 * .text); RECOMP_ENTER (src/host/portable_guest.h) tests it on every lifted call. The
 * replacement is a guest VA: a native FFNx function behind a cdecl guest-export thunk
 * (it pops only its return address), or another lifted game function (which returns the
 * way that function does). For a native replacement of a callee-pops game function
 * (`ret N`, from tools/gen_retn_table.py) the override also pops the N argument bytes, so
 * the caller sees exactly the stack the original would have left.
 */
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "recomp_types.h"
#include "win32hle.h"

#define TEXT_LO 0x00401000u
#define TEXT_HI 0x007B6000u
#define SHIM_LO 0x7F000000u          /* win32hle synthetic shim VAs (host code) */

uint32_t ff7_override_bits[(TEXT_HI - TEXT_LO + 31u) / 32u];

#define MAX_OVERRIDES 4096
static struct { uint32_t va, target; } g_ovr[MAX_OVERRIDES];   /* open addressing on va */
static int g_n;

typedef struct { uint32_t va; uint16_t pop; } recomp_retn_t;
extern const recomp_retn_t recomp_retn_table[];
extern const uint32_t recomp_retn_count;

static uint32_t retn_of(uint32_t va) {
    uint32_t lo = 0, hi = recomp_retn_count;
    while (lo < hi) {
        uint32_t mid = lo + (hi - lo) / 2;
        if (recomp_retn_table[mid].va == va) return recomp_retn_table[mid].pop;
        if (recomp_retn_table[mid].va < va) lo = mid + 1; else hi = mid;
    }
    return 0;
}

static int slot_of(uint32_t va, int create) {
    uint32_t h = (va * 2654435761u) & (MAX_OVERRIDES - 1);
    for (int i = 0; i < MAX_OVERRIDES; i++) {
        uint32_t k = (h + (uint32_t)i) & (MAX_OVERRIDES - 1);
        if (g_ovr[k].va == va) return (int)k;
        if (!g_ovr[k].va) {
            if (!create) return -1;
            if (g_n >= MAX_OVERRIDES * 3 / 4) return -1;
            g_ovr[k].va = va;
            g_n++;
            return (int)k;
        }
    }
    return -1;
}

static void set_bit(uint32_t va, int on) {
    uint32_t i = va - TEXT_LO, w = i >> 5, b = 1u << (i & 31u);
    if (on) ff7_override_bits[w] |= b; else ff7_override_bits[w] &= ~b;
}

/* 0 installed; -1 not a lifted function entry; -2 table full. */
int ff7_override_install(uint32_t va, uint32_t target) {
    if (va < TEXT_LO || va >= TEXT_HI || !recomp_lookup(va)) return -1;
    int s = slot_of(va, 1);
    if (s < 0) return -2;
    g_ovr[s].target = target;
    set_bit(va, 1);
    return 0;
}

void ff7_override_remove(uint32_t va) {
    if (va < TEXT_LO || va >= TEXT_HI) return;
    set_bit(va, 0);                  /* the slot stays (re-install reuses it) */
}

int ff7_override_active(uint32_t va) {
    if (va < TEXT_LO || va >= TEXT_HI) return 0;
    uint32_t i = va - TEXT_LO;
    return (ff7_override_bits[i >> 5] >> (i & 31u)) & 1u;
}

void ff7_run_override(uint32_t va) {
    int s = slot_of(va, 0);
    if (s < 0) { fprintf(stderr, "[override] bit set for 0x%08X but no target\n", va); abort(); }
    uint32_t target = g_ovr[s].target;
    recomp_func_t f = recomp_lookup(target);
    int native = 0;
    if (!f) { f = recomp_lookup_import(target); native = f != NULL; }
    if (!f) { fprintf(stderr, "[override] 0x%08X -> 0x%08X: no such function\n", va, target); abort(); }
    f();
    if (native && target >= SHIM_LO) g_esp += retn_of(va);   /* callee-pops original */
}
