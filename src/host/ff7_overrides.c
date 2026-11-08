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

/* ---- call-site hooks (FFNx replace_call / replace_call_function) ------------------------
 * The lift wraps every direct call as RECOMP_CALL_AT(site, f) (pcrecomp patch 0017): a
 * hooked site (its bit in ff7_callsite_bits) calls the replacement instead of f. As for
 * entry overrides, a native replacement of a callee-pops original pops its N bytes. */
uint32_t ff7_callsite_bits[(TEXT_HI - TEXT_LO + 31u) / 32u];
uint32_t ff7_callsite_cur;

#define MAX_CALLSITES 1024
static struct { uint32_t site, target, orig; } g_cs[MAX_CALLSITES];
static int g_ncs;

static int cs_slot(uint32_t site, int create) {
    uint32_t h = (site * 2654435761u) & (MAX_CALLSITES - 1);
    for (int i = 0; i < MAX_CALLSITES; i++) {
        uint32_t k = (h + (uint32_t)i) & (MAX_CALLSITES - 1);
        if (g_cs[k].site == site) return (int)k;
        if (!g_cs[k].site) {
            if (!create || g_ncs >= MAX_CALLSITES * 3 / 4) return -1;
            g_cs[k].site = site;
            g_ncs++;
            return (int)k;
        }
    }
    return -1;
}

static void cs_bit(uint32_t site, int on) {
    uint32_t i = site - TEXT_LO, w = i >> 5, b = 1u << (i & 31u);
    if (on) ff7_callsite_bits[w] |= b; else ff7_callsite_bits[w] &= ~b;
}

/* `orig` is the game function the site called (0 if not a call): its `ret N` decides the
 * pop for a native replacement. 0 installed, -1 outside .text, -2 table full. */
int ff7_callsite_install(uint32_t site, uint32_t target, uint32_t orig) {
    if (site < TEXT_LO || site >= TEXT_HI) return -1;
    int s = cs_slot(site, 1);
    if (s < 0) return -2;
    g_cs[s].target = target;
    g_cs[s].orig = orig;
    cs_bit(site, 1);
    return 0;
}

void ff7_callsite_remove(uint32_t site) {
    if (site >= TEXT_LO && site < TEXT_HI) cs_bit(site, 0);
}

/* Called as a lifted function (RECOMP_CALL pushed the return address) for the hooked
 * site in ff7_callsite_cur. */
void ff7_run_callsite(void) {
    uint32_t site = ff7_callsite_cur;
    int s = cs_slot(site, 0);
    if (s < 0) { fprintf(stderr, "[callsite] bit set for 0x%08X but no target\n", site); abort(); }
    uint32_t target = g_cs[s].target;
    recomp_func_t f = recomp_lookup(target);
    int native = 0;
    if (!f) { f = recomp_lookup_import(target); native = f != NULL; }
    if (!f) { fprintf(stderr, "[callsite] 0x%08X -> 0x%08X: no such function\n", site, target); abort(); }
    f();
    if (native && target >= SHIM_LO && g_cs[s].orig) g_esp += retn_of(g_cs[s].orig);
}

/* ---- patched-code variants (pcrecomp patch 0017) ----------------------------------------
 * FFNx's code patches are lifted as alternatives of the original instructions; the lift
 * picks one by comparing the bytes in memory now (mask 0x00 = an operand read live). */
int ff7_code_match(uint32_t va, const char *bytes, const char *mask, uint32_t n) {
    const uint8_t *m = (const uint8_t *)(uintptr_t)va;
    for (uint32_t i = 0; i < n; i++)
        if ((uint8_t)mask[i] && m[i] != (uint8_t)bytes[i]) return 0;
    return 1;
}

void ff7_interp_run(uint32_t va);    /* src/interp/x86_interp.c */
#define FF7_INTERP_TARGET 0xFFFFFFFEu    /* ff7_interp_mark: run this function interpreted */

void ff7_run_override(uint32_t va) {
    int s = slot_of(va, 0);
    if (s < 0) { fprintf(stderr, "[override] bit set for 0x%08X but no target\n", va); abort(); }
    uint32_t target = g_ovr[s].target;
    if (target == FF7_INTERP_TARGET) { ff7_interp_run(va); return; }
    recomp_func_t f = recomp_lookup(target);
    int native = 0;
    if (!f) { f = recomp_lookup_import(target); native = f != NULL; }
    if (!f) { fprintf(stderr, "[override] 0x%08X -> 0x%08X: no such function\n", va, target); abort(); }
    f();
    if (native && target >= SHIM_LO) g_esp += retn_of(va);   /* callee-pops original */
}
