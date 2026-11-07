/*
 * ff7_headless_main.c - boots the lifted ff7_en.exe with no display, for bring-up.
 *
 * Maps the PE at its own VAs (flat memory), reserves the guest heap arena, binds every
 * import to a win32hle shim (unknown ones to a self-naming stub, so the first missing
 * import stops the run with its name), then calls the lifted CRT entry. The run ends at
 * the first gap; that gap is the next thing to implement.
 *
 * usage: ff7_headless <game dir containing ff7_en.exe and data/>
 */
#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include "guest_alloc.h"
#include "host.h"
#include "win32hle.h"
#include "ff7_hle.h"

int main(int argc, char **argv) {
    if (argc < 2) {
        fprintf(stderr, "usage: %s <game dir>\n", argv[0]);
        return 64;
    }
    setvbuf(stdout, NULL, _IONBF, 0);
    setvbuf(stderr, NULL, _IONBF, 0);
    if (chdir(argv[1]) != 0) {
        perror("chdir to game dir");
        return 66;
    }
    if (guest_alloc_init(FF7_ARENA_LO, FF7_ARENA_HI) != 0) return 70;
    if (ff7_virtual_init() != 0) return 70;
    fprintf(stderr, "[ff7] guest arena [0x%08X,0x%08X)\n", FF7_ARENA_LO, FF7_ARENA_HI);

    ff7_hle_register();          /* FF7 shims first: a name here wins over win32hle */
    recomp_host_init();
    if (getenv("FF7_TRACE_IMPORTS")) win32hle_trace = 1;
    if (recomp_host_boot_permissive("ff7_en.exe") != 0) {
        fprintf(stderr, "[ff7] could not map ff7_en.exe\n");
        return 71;
    }
    fprintf(stderr, "[ff7] calling lifted entry 0x%08X\n", recomp_host_entry());
    uint32_t rc = recomp_host_run();
    fprintf(stderr, "[ff7] entry returned 0x%08X\n", rc);
    return (int)rc;
}
