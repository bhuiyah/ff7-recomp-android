/*
 * ff7_hle.h - FF7's own Win32 shim modules, registered ahead of pcrecomp's win32hle.
 */
#ifndef FF7_HLE_H
#define FF7_HLE_H
#include "win32hle.h"

/* Where the guest believes it is installed. Paths under it map to the host game root
 * (ff7_paths.c re-roots any absolute path at its first component found there). */
#define FF7_GUEST_ROOT "C:\\FF7"

/* Guest address layout (flat memory, all below 2 GB). Inside an Android app process ART
 * already owns parts of the low 4 GB (it keeps the Java heap there for 32-bit object
 * references): on the Thor, dalvik-main 0x12C00000-0x32C00000 and the boot image from
 * ~0x6FF00000 (its base is randomised a little per boot). The guest uses the gaps:
 *   0x00400000-0x00B6F000  ff7_en.exe image
 *   0x33000000-0x5B000000  guest heap arena (guest_alloc: Heap*, Local*, Global*, TIBs, stacks)
 *   0x5B000000-0x6B000000  VirtualAlloc reservations (64 KB aligned, ff7_virtual.c)
 *   0x7F000000-            win32hle synthetic shim VAs (never dereferenced as memory) */
#define FF7_ARENA_LO 0x33000000u
#define FF7_ARENA_HI 0x5B000000u
#define FF7_VM_LO    0x5B000000u
#define FF7_VM_HI    0x6B000000u

extern const win32hle_shim ff7_kernel32[];
extern const win32hle_shim ff7_virtual[];
extern const win32hle_shim ff7_af3dn[];
extern const win32hle_shim ff7_user32[];
extern const win32hle_shim ff7_winmm[];
extern const win32hle_shim ff7_ddraw[];
extern const win32hle_shim ff7_dsound[];
extern const win32hle_shim ff7_msacm[];
extern const win32hle_shim ff7_dinput[];
int ff7_virtual_init(void);

/* The guest working directory, relative to the game root ("" = the root). Set from a guest
 * path (mapped, must be an existing directory); 0 on success. Relative guest paths given
 * to hle_host_path resolve against it, as on Windows. */
const char *ff7_guest_cwd(void);
int ff7_set_guest_cwd(const char *guest_path);

/* Give the machine lock to another guest thread and take it back (a blocking wait). */
void hle_yield_machine(void);

/* Register every FF7 shim module. Call before recomp_host_init(). */
void ff7_hle_register(void);

#endif
