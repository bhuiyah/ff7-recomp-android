/*
 * ff7_virtual.c - VirtualAlloc / VirtualFree / VirtualQuery with Windows' alignment rules.
 *
 * The MSVC CRT's small-block heap reserves regions with VirtualAlloc and indexes pages and
 * paragraphs by address arithmetic, so reservations must be 64 KB aligned and commits
 * page aligned, exactly as on Windows. win32hle's VirtualAlloc returns an ordinary heap
 * block (16 bytes past an alignment boundary), which corrupts that arithmetic.
 *
 * Reservations come from their own guest range, [FF7_VM_LO, FF7_VM_HI), mapped once at
 * startup. Memory is always readable/writable underneath (commit is bookkeeping only);
 * a released range is zeroed so a later reservation starts clean, as Windows guarantees.
 */
#define _GNU_SOURCE
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/mman.h>
#include "win32hle.h"
#include "ff7_hle.h"

#ifndef MAP_FIXED_NOREPLACE
#define MAP_FIXED_NOREPLACE 0x100000
#endif

#define MEM_COMMIT   0x1000u
#define MEM_RESERVE  0x2000u
#define MEM_DECOMMIT 0x4000u
#define MEM_RELEASE  0x8000u
#define GRAN  0x10000u                 /* allocation granularity */
#define PAGE  0x1000u

#define MAX_REGIONS 1024
static struct { uint32_t base, size; } g_reg[MAX_REGIONS];   /* sorted by base */
static int g_nreg;

int ff7_virtual_init(void) {
    void *p = mmap((void *)(uintptr_t)FF7_VM_LO, FF7_VM_HI - FF7_VM_LO, PROT_READ | PROT_WRITE,
                   MAP_PRIVATE | MAP_ANONYMOUS | MAP_NORESERVE | MAP_FIXED_NOREPLACE, -1, 0);
    if (p == MAP_FAILED || (uintptr_t)p != FF7_VM_LO) {
        fprintf(stderr, "[virtual] cannot map [0x%08X,0x%08X)\n", FF7_VM_LO, FF7_VM_HI);
        return -1;
    }
    return 0;
}

static int region_of(uint32_t a) {
    for (int i = 0; i < g_nreg; i++)
        if (a >= g_reg[i].base && a - g_reg[i].base < g_reg[i].size) return i;
    return -1;
}

/* Reserve `size` bytes at `want` (0 = anywhere). Returns base or 0. */
static uint32_t reserve(uint32_t want, uint32_t size) {
    size = (size + GRAN - 1) & ~(GRAN - 1);
    uint32_t base = 0;
    if (want) {
        want &= ~(GRAN - 1);
        if (want < FF7_VM_LO || (uint64_t)want + size > FF7_VM_HI) return 0;
        for (int i = 0; i < g_nreg; i++)
            if (want < g_reg[i].base + g_reg[i].size && g_reg[i].base < want + size) return 0;
        base = want;
    } else {
        uint32_t cur = FF7_VM_LO;
        for (int i = 0; i <= g_nreg; i++) {
            uint32_t next = i < g_nreg ? g_reg[i].base : FF7_VM_HI;
            if (next - cur >= size) { base = cur; break; }
            if (i < g_nreg) cur = (g_reg[i].base + g_reg[i].size + GRAN - 1) & ~(GRAN - 1);
        }
        if (!base) return 0;
    }
    if (g_nreg == MAX_REGIONS) return 0;
    int i = 0;
    while (i < g_nreg && g_reg[i].base < base) i++;
    memmove(&g_reg[i + 1], &g_reg[i], sizeof g_reg[0] * (size_t)(g_nreg - i));
    g_reg[i].base = base;
    g_reg[i].size = size;
    g_nreg++;
    return base;
}

/* VirtualAlloc(addr, size, type, protect) */
static void k_VirtualAlloc(void) {
    uint32_t addr = A32(0), size = A32(1), type = A32(2);
    if (!size) RET(0, 4);
    if (type & MEM_RESERVE) {
        RET(reserve(addr, size), 4);
    }
    if (type & MEM_COMMIT) {
        if (!addr) RET(reserve(0, size), 4);              /* commit without reserve: both */
        if (region_of(addr) < 0) RET(0, 4);               /* commit outside any reservation */
        RET(addr & ~(PAGE - 1), 4);
    }
    RET(0, 4);
}

/* VirtualFree(addr, size, type) */
static void k_VirtualFree(void) {
    uint32_t addr = A32(0), size = A32(1), type = A32(2);
    int i = region_of(addr);
    if (i < 0) RET(0, 3);
    if (type & MEM_RELEASE) {
        if (addr != g_reg[i].base || size) RET(0, 3);
        memset((void *)(uintptr_t)g_reg[i].base, 0, g_reg[i].size);
        memmove(&g_reg[i], &g_reg[i + 1], sizeof g_reg[0] * (size_t)(g_nreg - i - 1));
        g_nreg--;
        RET(1, 3);
    }
    if (type & MEM_DECOMMIT) {
        uint32_t lo = addr & ~(PAGE - 1);
        uint32_t hi = size ? (addr + size + PAGE - 1) & ~(PAGE - 1) : g_reg[i].base + g_reg[i].size;
        memset((void *)(uintptr_t)lo, 0, hi - lo);
        RET(1, 3);
    }
    RET(0, 3);
}

/* VirtualQuery(addr, MEMORY_BASIC_INFORMATION*, len): BaseAddress +0, AllocationBase +4,
 * AllocationProtect +8, RegionSize +0xC, State +0x10, Protect +0x14, Type +0x18. */
static void k_VirtualQuery(void) {
    uint32_t addr = A32(0), mbi = A32(1), len = A32(2);
    if (len < 28) RET(0, 3);
    uint32_t *m = (uint32_t *)(uintptr_t)mbi;
    int i = region_of(addr);
    m[0] = addr & ~(PAGE - 1);
    if (i >= 0) {
        m[1] = g_reg[i].base; m[2] = 0x04; m[3] = g_reg[i].base + g_reg[i].size - m[0];
        m[4] = MEM_COMMIT; m[5] = 0x04; m[6] = 0x20000;   /* PAGE_READWRITE, MEM_PRIVATE */
    } else {
        m[1] = 0; m[2] = 0; m[3] = PAGE; m[4] = 0x10000; m[5] = 0x01; m[6] = 0; /* MEM_FREE, NOACCESS */
    }
    RET(28, 3);
}

const win32hle_shim ff7_virtual[] = {
    { "VirtualAlloc", k_VirtualAlloc },
    { "VirtualFree", k_VirtualFree },
    { "VirtualQuery", k_VirtualQuery },
    { NULL, NULL }
};
