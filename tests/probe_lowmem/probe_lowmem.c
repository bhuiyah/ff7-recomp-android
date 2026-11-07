/* Can this device give us FF7's guest address space at its original addresses?
 *
 * Flat memory (RECOMP_FLAT_MEMORY) needs the guest image at VA 0x00400000 and guest heaps
 * below 4 GB, mapped 1:1 into this 64-bit process. This probe reserves [0x00400000, 0x80000000)
 * with MAP_FIXED_NOREPLACE (fails instead of clobbering an existing mapping), touches each end,
 * and prints what else is mapped below 4 GB if it fails.
 *
 * Build: NDK clang --target=aarch64-linux-android29 probe_lowmem.c -o probe_lowmem
 * Run:   adb push probe_lowmem /data/local/tmp/ && adb shell /data/local/tmp/probe_lowmem
 */
#define _GNU_SOURCE
#include <errno.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>

#ifndef MAP_FIXED_NOREPLACE
#define MAP_FIXED_NOREPLACE 0x100000
#endif

static void show_low_mappings(void) {
    FILE *f = fopen("/proc/self/maps", "r");
    char line[512];
    if (!f) return;
    printf("mappings below 4 GB:\n");
    while (fgets(line, sizeof line, f)) {
        unsigned long long lo = strtoull(line, NULL, 16);
        if (lo < 0x100000000ull) printf("  %s", line);
    }
    fclose(f);
}

int main(void) {
    const uintptr_t base = 0x00400000u, end = 0x80000000u;
    FILE *f = fopen("/proc/sys/vm/mmap_min_addr", "r");
    unsigned long minaddr = 0;
    if (f) { if (fscanf(f, "%lu", &minaddr) != 1) minaddr = 0; fclose(f); }
    printf("mmap_min_addr = %lu (0x%lx)\n", minaddr, minaddr);

    void *p = mmap((void *)base, end - base, PROT_READ | PROT_WRITE,
                   MAP_PRIVATE | MAP_ANONYMOUS | MAP_NORESERVE | MAP_FIXED_NOREPLACE, -1, 0);
    if (p == MAP_FAILED || (uintptr_t)p != base) {
        printf("FAIL: mmap(0x%08lx, 0x%08lx) -> %p errno=%d (%s)\n",
               (unsigned long)base, (unsigned long)(end - base), p, errno, strerror(errno));
        show_low_mappings();
        return 1;
    }
    volatile uint8_t *lo = (uint8_t *)base, *hi = (uint8_t *)(end - 1);
    *lo = 0xA5; *hi = 0x5A;
    printf("PASS: guest range [0x%08lx, 0x%08lx) mapped 1:1, first/last byte %02x/%02x\n",
           (unsigned long)base, (unsigned long)end, *lo, *hi);
    munmap(p, end - base);
    return 0;
}
