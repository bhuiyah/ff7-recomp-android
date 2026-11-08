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
#include "mods.h"

void ff7_install_crash_handler(void);
void ff7_start_watchdog(void);   /* ff7_crash.c, FF7_WATCHDOG=<s> */
int ff7_ffnx_attach(void);   /* src/ffnx_port/ffnx_boot.cpp (FF7_WITH_FFNX) */
void ff7_hext_apply_mods(void);   /* ff7_hext.cpp */

int ff7_boot_and_run(const char *game_dir) {
    if (chdir(game_dir) != 0) {
        perror("[ff7] chdir to game dir");
        return 66;
    }
    ff7_install_crash_handler();
    ff7_start_watchdog();
    /* 7th Heaven mods: <game>/../mods/profile.xml (the launcher writes it), archive entries
     * extracted to <game>/../modcache on first use. No profile = no mods. */
    {
        char cwd[1024], profile[1100], cache[1100];
        if (getcwd(cwd, sizeof cwd)) {
            char *slash = strrchr(cwd, '/');
            if (slash) *slash = 0;
            snprintf(profile, sizeof profile, "%s/mods/profile.xml", cwd);
            snprintf(cache, sizeof cache, "%s/modcache", cwd);
            ff7_mods_load(profile, cache);
        }
    }
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
    /* The mods' hext patches: after the image is mapped, before FFNx (7th Heaven patches the
     * process before the driver loads). */
    ff7_hext_apply_mods();
#ifdef FF7_WITH_FFNX
    /* FFNx's DllMain: version check on the mapped image, FFNx.toml, hooks. As AF3DN.P on
     * Windows it runs before WinMain, so here it runs before the entry. */
    if (ff7_ffnx_attach() != 0) {
        fprintf(stderr, "[ff7] FFNx refused this exe (see FFNx.log)\n");
        return 72;
    }
#endif
    fprintf(stderr, "[ff7] calling lifted entry 0x%08X\n", recomp_host_entry());
    uint32_t rc = recomp_host_run();
    fprintf(stderr, "[ff7] entry returned 0x%08X\n", rc);
    return (int)rc;
}
