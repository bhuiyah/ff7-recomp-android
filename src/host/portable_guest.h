/*
 * portable_guest.h - force-included (-include) into the generated C on non-x86 hosts.
 *
 * The lift emits x86-only intrinsics for two instructions; this supplies portable
 * versions so the same generated C builds for arm64 (Android) and x86-64 Linux.
 * Adapted from codepdbh/nfsu2-recompiled android/app/src/main/cpp/arm64_guest.h (MIT).
 *
 *   rdtsc -> a stable virtual 1 GHz counter (CLOCK_MONOTONIC in ns), not a hardware
 *            cycle counter: FF7 only uses it for timing deltas.
 *   cpuid -> a fixed "GenuineIntel" Pentium-class answer advertising x87, TSC, CX8,
 *            CMOV, MMX, SSE, SSE2 -- nothing the global-register lift can't execute.
 */
#ifndef FF7_PORTABLE_GUEST_H
#define FF7_PORTABLE_GUEST_H
#if !defined(_MSC_VER)
#include <time.h>
#define RECOMP_GENERATED_CODE
#include "recomp_types.h"

static inline uint64_t ff7_guest_rdtsc(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return (uint64_t)t.tv_sec * 1000000000ull + (uint64_t)t.tv_nsec;
}
#define __rdtsc() ff7_guest_rdtsc()

static inline void ff7_guest_cpuid(uint32_t leaf) {
    g_eax = g_ebx = g_ecx = g_edx = 0;
    if (leaf == 0) {
        g_eax = 1; g_ebx = 0x756e6547u; g_edx = 0x49656e69u; g_ecx = 0x6c65746eu;   /* "GenuineIntel" */
    } else if (leaf == 1) {
        g_eax = 0x00000663u;
        g_edx = ((1u << 0) | (1u << 4) | (1u << 8) | (1u << 15) | (1u << 23) | (1u << 25) | (1u << 26)) & g_cpuid_edx1;
    } else if (leaf == 0x80000000u) {
        g_eax = 0x80000001u;
    }
}
#undef CPUID
#define CPUID(a, b, c, d) do { RECOMP_REGS_OUT(); ff7_guest_cpuid((a)); RECOMP_REGS_IN(); } while (0)
#endif

/* ---- entry overrides (src/host/ff7_overrides.c) ---------------------------------------
 * FFNx's replace_function(va, fn) is installed here, at run time: every lifted function
 * entry -- reached by a direct call or through the dispatch table alike -- tests one bit
 * for its VA; if set, the replacement runs on the current guest state (the shim ABI:
 * [esp] = return address, arguments above it) and the lifted body is skipped. */
extern uint32_t ff7_override_bits[];
void ff7_run_override(uint32_t va);
#define FF7_TEXT_LO 0x00401000u
#define FF7_TEXT_HI 0x007B6000u
#define FF7_OVERRIDDEN(va) \
    ((uint32_t)((va) - FF7_TEXT_LO) < (FF7_TEXT_HI - FF7_TEXT_LO) && \
     (ff7_override_bits[((va) - FF7_TEXT_LO) >> 5] >> (((va) - FF7_TEXT_LO) & 31u) & 1u))

#undef RECOMP_ENTER
#ifdef RECOMP_TRACE
#define FF7_ENTER_TRACE(va) recomp_trace_enter(va)
#else
#define FF7_ENTER_TRACE(va) ((void)0)
#endif
#define RECOMP_ENTER(va) do { \
    g_cur_func = (va); RECOMP_FLAGS_IN(); FF7_ENTER_TRACE(va); \
    if (__builtin_expect(FF7_OVERRIDDEN(va), 0)) { \
        RECOMP_REGS_OUT(); ff7_run_override(va); RECOMP_REGS_IN(); RECOMP_RETURN(); \
    } \
} while (0)
#endif
