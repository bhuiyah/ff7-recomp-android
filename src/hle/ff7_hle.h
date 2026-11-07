/*
 * ff7_hle.h - FF7's own Win32 shim modules, registered ahead of pcrecomp's win32hle.
 */
#ifndef FF7_HLE_H
#define FF7_HLE_H
#include "win32hle.h"

/* Where the guest believes it is installed. Paths under it map to the host game root
 * (ff7_paths.c re-roots any absolute path at its first component found there). */
#define FF7_GUEST_ROOT "C:\\FF7"

/* Guest address layout (flat memory, all below 2 GB):
 *   0x00400000-0x00B6F000  ff7_en.exe image
 *   0x10000000-0x60000000  guest heap arena (guest_alloc: Heap*, Local*, Global*, TIBs, stacks)
 *   0x60000000-0x70000000  VirtualAlloc reservations (64 KB aligned, ff7_virtual.c)
 *   0x7F000000-            win32hle synthetic shim VAs */
#define FF7_ARENA_LO 0x10000000u
#define FF7_ARENA_HI 0x60000000u
#define FF7_VM_LO    0x60000000u
#define FF7_VM_HI    0x70000000u

extern const win32hle_shim ff7_kernel32[];
extern const win32hle_shim ff7_virtual[];
extern const win32hle_shim ff7_af3dn[];
extern const win32hle_shim ff7_user32[];
extern const win32hle_shim ff7_winmm[];
extern const win32hle_shim ff7_ddraw[];
int ff7_virtual_init(void);

/* Give the machine lock to another guest thread and take it back (a blocking wait). */
void hle_yield_machine(void);

/* Register every FF7 shim module. Call before recomp_host_init(). */
void ff7_hle_register(void);

#endif
