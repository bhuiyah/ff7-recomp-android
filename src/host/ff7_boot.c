/*
 * ff7_boot.c - boot and run the lifted ff7_en.exe (shared by the headless runner and the
 * Android app).
 *
 * Maps the PE at its own VAs (flat memory), reserves the guest heap arena and the
 * VirtualAlloc region, registers FF7's shims ahead of win32hle's, binds every import (an
 * unknown one to a self-naming stub, so the first missing import stops the run with its
 * name), then calls the lifted CRT entry on the calling thread.
 */
#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
#include "guest_alloc.h"
#include "host.h"
#include "win32hle.h"
#include "ff7_hle.h"
#include "ff7_boot.h"

void ff7_install_crash_handler(void);

int ff7_boot_and_run(const char *game_dir) {
    if (chdir(game_dir) != 0) {
        perror("[ff7] chdir to game dir");
        return 66;
    }
    ff7_install_crash_handler();
    if (guest_alloc_init(FF7_ARENA_LO, FF7_ARENA_HI) != 0) return 70;
    if (ff7_virtual_init() != 0) return 70;
    fprintf(stderr, "[ff7] guest arena [0x%08X,0x%08X), game dir %s\n", FF7_ARENA_LO, FF7_ARENA_HI, game_dir);

    ff7_hle_register();          /* FF7 shims first: a name here wins over win32hle */
    recomp_host_init();
    if (getenv("FF7_TRACE_IMPORTS")) win32hle_trace = 1;
    if (recomp_host_boot_permissive("ff7_en.exe") != 0) {
        fprintf(stderr, "[ff7] could not map ff7_en.exe in %s\n", game_dir);
        return 71;
    }
    fprintf(stderr, "[ff7] calling lifted entry 0x%08X\n", recomp_host_entry());
    uint32_t rc = recomp_host_run();
    fprintf(stderr, "[ff7] entry returned 0x%08X\n", rc);
    return (int)rc;
}
