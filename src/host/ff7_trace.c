/*
 * ff7_trace.c - portable lifted-function entry ring (RECOMP_TRACE builds only).
 *
 * pcrecomp's recomp_trace.c is Windows-only; this keeps just its ring: every
 * RECOMP_ENTER records the function VA, and ff7_trace_dump() prints the last
 * RECOMP_ENTER_SIZE entries (oldest first) -- the path into a crash.
 * Enable with -DFF7_TRACE=ON (tools/build_headless.sh --trace).
 */
#include <stdio.h>
#include "recomp_types.h"
#include "win32hle.h"

#ifdef RECOMP_TRACE
uint32_t g_enter_trace[RECOMP_ENTER_SIZE];
uint32_t g_enter_idx;

void recomp_trace_enter(uint32_t va) {
    g_enter_trace[g_enter_idx++ & (RECOMP_ENTER_SIZE - 1)] = va;
}

void ff7_trace_dump(int last) {
    uint32_t have = g_enter_idx < RECOMP_ENTER_SIZE ? g_enter_idx : RECOMP_ENTER_SIZE;
    uint32_t n = last > 0 && (uint32_t)last < have ? (uint32_t)last : have;
    fprintf(stderr, "  last %u lifted-function entries (oldest first):\n", n);
    for (uint32_t i = n; i > 0; i--) {
        uint32_t va = g_enter_trace[(g_enter_idx - i) & (RECOMP_ENTER_SIZE - 1)];
        fprintf(stderr, "    0x%08X\n", va);
    }
}
#else
void ff7_trace_dump(int last) {
    (void)last;
    fprintf(stderr, "  (entry trace off: rebuild with tools/build_headless.sh --trace)\n");
}
#endif
