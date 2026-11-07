/*
 * ff7_hle.c - registration of FF7's shim modules and small shared helpers.
 */
#include <sched.h>
#include "ff7_hle.h"

void hle_yield_machine(void) {
    mach_leave();
    sched_yield();
    mach_enter();
}

void ffnx_bridge_register(void);   /* src/ffnx_bridge/ffnx_driver.cpp (or ffnx_boot.cpp) */
void ff7_paths_link(void);         /* src/hle/ff7_paths.c */
void ff7_timers_poll(void);        /* src/hle/ff7_winmm.c */

void ff7_hle_register(void) {
    ff7_paths_link();
    ffnx_bridge_register();
    win32hle_register(ff7_winmm);    /* first: its Sleep (runs due timers) wins */
    win32hle_register(ff7_kernel32);
    win32hle_register(ff7_virtual);
    win32hle_register(ff7_af3dn);
    win32hle_register(ff7_user32);
    win32hle_register(ff7_ddraw);
    win32hle_register(ff7_dsound);
    win32hle_register(ff7_msacm);
    win32hle_register(ff7_dinput);
    hle_set_pump_hook(ff7_timers_poll);   /* an empty message queue also runs due timers */
}
