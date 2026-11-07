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

void ff7_hle_register(void) {
    win32hle_register(ff7_kernel32);
    win32hle_register(ff7_virtual);
    win32hle_register(ff7_af3dn);
    win32hle_register(ff7_user32);
    win32hle_register(ff7_winmm);
    win32hle_register(ff7_ddraw);
}
