/* x86_interp.h - an x86-32 interpreter over guest memory, for game code that has no lifted
 * counterpart: a mod's hext patch into a function the lift never saw patched.
 *
 * It runs on the lifted code's own machine state (g_eax.. registers, the lazy flags that
 * cross every call, the x87 stack g_st, the FPU control word), so interpreted and lifted
 * code call each other freely:
 *   - a marked function is an entry override (src/host/ff7_overrides.c): any lifted call
 *     to it, direct or through the dispatch table, lands in ff7_interp_run;
 *   - a call from interpreted code goes to the lifted function (or FFNx's replacement:
 *     entry overrides and call-site hooks are honoured), or is interpreted when the target
 *     is marked or was never lifted (code a patch added);
 *   - a call to code that is interpreted too runs in the same activation, its return slot
 *     recorded: a `ret` through that slot comes back after the call, any other `ret` ends
 *     the activation, as the lifted function's `return` would. So a patch may pop its
 *     return address and jump on, as on a CPU.
 * It executes the bytes in memory now, so everything written there takes effect: the
 * mod's patch, FFNx's code writes, per-frame stepped values.
 *
 * Flags are computed exactly (all six arithmetic flags, per the Intel manual; undefined
 * ones as hardware commonly leaves them) and published as an FK_EFLAGS word. The x87 model
 * is the lift's (double precision, rounding by the control word).
 */
#ifndef FF7_X86_INTERP_H
#define FF7_X86_INTERP_H
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif

/* Run the function at `va` as one activation: [esp] holds the return address. */
void ff7_interp_run(uint32_t va);

/* Interpret the function at `va` (a lifted function entry) from now on.
 * 0 ok; -1 not a lifted function; -2 already replaced (FFNx); -3 table full. */
int ff7_interp_mark(uint32_t va);
int ff7_interp_marked(uint32_t va);

/* The lifted function containing `va` (the nearest entry at or below it), or 0. */
uint32_t ff7_interp_function_of(uint32_t va);

/* Every lifted function whose body holds guest bytes in [va, va+n), inline copies included
 * (src/host/ff7_hext.cpp, from tools/gen_extents.py); at most `max` into `out`. */
int ff7_functions_covering(uint32_t va, uint32_t n, uint32_t *out, int max);

/* Instructions executed so far (statistics). */
extern uint64_t ff7_interp_steps;

#ifdef __cplusplus
}
#endif
#endif
