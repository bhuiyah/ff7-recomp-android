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
#endif
