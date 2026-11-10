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
#include <sys/mman.h>
#ifndef MAP_FIXED_NOREPLACE
#define MAP_FIXED_NOREPLACE 0x100000
#endif
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

/* Where the guest heap arena goes. [FF7_ARENA_LO, FF7_ARENA_HI) when it is free -- on most
 * devices ART's Java heap ("dalvik-main space", 0x12C00000 + dalvik.vm.heapsize) ends below
 * it -- else the largest free gap in [FF7_ARENA_MIN, FF7_VM_LO) (a phone with a 1 GB Java
 * heap fills 0x12C00000-0x52C00000). The game uses a few tens of MB of it. */
#define FF7_ARENA_MIN  0x01000000u   /* above the exe image (mapped after this) */
#define FF7_ARENA_NEED 0x08000000u   /* 128 MB at least */
static int ff7_pick_arena(uint32_t *lo, uint32_t *hi) {
    *lo = FF7_ARENA_LO; *hi = FF7_ARENA_HI;
    FILE *f = fopen("/proc/self/maps", "r");
    if (!f) return 0;
    /* the occupied ranges below FF7_VM_LO, in address order (maps is sorted) */
    uint32_t occ[512][2]; int n = 0;
    char line[512];
    while (fgets(line, sizeof line, f) && n < 512) {
        unsigned long long a, b;
        if (sscanf(line, "%llx-%llx", &a, &b) != 2 || a >= FF7_VM_LO) continue;
        occ[n][0] = (uint32_t)a; occ[n][1] = (uint32_t)(b < FF7_VM_LO ? b : FF7_VM_LO); n++;
    }
    fclose(f);
    int clash = 0;
    for (int i = 0; i < n; i++) if (occ[i][0] < FF7_ARENA_HI && occ[i][1] > FF7_ARENA_LO) clash = 1;
    if (!clash) return 0;
    uint32_t best_lo = 0, best_hi = 0, at = FF7_ARENA_MIN;
    for (int i = 0; i <= n; i++) {
        uint32_t end = i < n ? occ[i][0] : FF7_VM_LO;
        if (i < n && occ[i][1] <= at) continue;
        if (end > at && end - at > best_hi - best_lo) { best_lo = at; best_hi = end; }
        if (i < n && occ[i][1] > at) at = occ[i][1];
    }
    best_lo = (best_lo + 0xFFFFFu) & ~0xFFFFFu;   /* 1 MB aligned */
    best_hi &= ~0xFFFFFu;
    if (best_hi <= best_lo || best_hi - best_lo < FF7_ARENA_NEED) {
        fprintf(stderr, "[ff7] no free guest arena: [0x%08X,0x%08X) is in use and no gap of 128 MB is free below 0x%08X\n",
                FF7_ARENA_LO, FF7_ARENA_HI, FF7_VM_LO);
        return -1;
    }
    if (best_hi - best_lo > FF7_ARENA_HI - FF7_ARENA_LO) best_hi = best_lo + (FF7_ARENA_HI - FF7_ARENA_LO);
    fprintf(stderr, "[ff7] [0x%08X,0x%08X) is in use (a large Java heap): guest arena moved\n", FF7_ARENA_LO, FF7_ARENA_HI);
    *lo = best_lo; *hi = best_hi;
    return 0;
}

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
    uint32_t arena_lo, arena_hi;
    /* Test: FF7_SIMULATE_HEAP_MB=1024 occupies what a device with that Java heap size has
     * mapped (0x12C00000 + size), as on phones whose heap reaches into the arena. */
    if (getenv("FF7_SIMULATE_HEAP_MB")) {
        uint32_t end = 0x12C00000u + (uint32_t)atoi(getenv("FF7_SIMULATE_HEAP_MB")) * 0x100000u;
        if (end > FF7_ARENA_LO && end <= FF7_VM_LO &&
            mmap((void *)(uintptr_t)FF7_ARENA_LO, end - FF7_ARENA_LO, PROT_NONE,
                 MAP_PRIVATE | MAP_ANONYMOUS | MAP_NORESERVE | MAP_FIXED_NOREPLACE, -1, 0) != MAP_FAILED)
            fprintf(stderr, "[ff7] test: [0x%08X,0x%08X) occupied\n", FF7_ARENA_LO, end);
    }
    if (ff7_pick_arena(&arena_lo, &arena_hi) != 0) return 70;
    if (guest_alloc_init(arena_lo, arena_hi) != 0) return 70;
    if (ff7_virtual_init() != 0) return 70;
    fprintf(stderr, "[ff7] guest arena [0x%08X,0x%08X), game dir %s\n", arena_lo, arena_hi, game_dir);

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
