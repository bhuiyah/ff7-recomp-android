# Porting FFNx to run natively against the lifted FF7

FFNx (julianxhokaxhiu/FFNx, GPL-3.0, ~89 k lines) is a 32-bit Windows DLL that the game
loads as its graphics driver (`AF3DN.P`). It shares the game's address space: it reads and
writes game structures through raw pointers, calls game functions through raw addresses,
and patches the game's x86 code in memory. Here the game is lifted C running in a 64-bit
ARM process, so each of those contacts needs a defined crossing.

## The boundary, measured (FFNx @ 0adf0b32)

| Contact | Count | What it needs here |
|---|---|---|
| Driver callbacks the game calls (`ff7_gfx_driver`) | ~60 | guest-callable thunks |
| FFNx -> game calls through externals | 717 sites, 213 typed fn-pointer externals | guest calls |
| Game structs touched by FFNx (`ff7.h`, `common.h`) | ~590 pointer members | 32-bit guest pointers |
| `replace_function` / `replace_call` | 785 sites | entry overrides / call-site slots |
| `patch_code_*`, `memcpy_code`, `memset_code` | 640 sites | data: as-is; code: lift-time |
| `get_relative_call` / `get_absolute_value` (reads of x86 code) | 1029 sites | work unchanged (flat memory) |
| Windows API use | 15 files | SDL3 / POSIX / Android |

## Design

**1. Memory: flat, guest addresses are host addresses.** The guest image and all guest heaps
live below 2 GB at their own addresses (verified on the Thor), so a guest pointer converts to
a host pointer by zero-extension. Code-walking reads (`get_relative_call` on the original call
instructions at 0x40xxxx) therefore work as written.

**2. Game structures: `gptr<T>`.** In the headers that describe *game* memory (`ff7.h`,
`common.h` game structs), every pointer member becomes `gptr<T>`: a 4-byte class holding a
guest VA with `operator->`, `operator*`, `operator T*`, assignment from `T*` (asserting the
pointer is guest memory) and arithmetic. Layouts stay byte-identical to the 32-bit originals
(enforced with `static_assert(sizeof)` on every converted struct). FFNx's own internal
structures stay native 64-bit. Conversion is scripted (`tools/ffnx_gptr.py`), not hand-edited,
and kept as a patch series under `patches/ffnx/`.

**3. Calls FFNx -> game: `gfn<R(Args...)>`.** Function-pointer externals and function-pointer
members of game structs become `gfn<Sig>` (4 bytes, a guest VA). `operator()` marshals the
arguments onto the guest stack (cdecl by default, `gfn_stdcall`/`gfn_thiscall` where FFNx's
casts say so) and runs `hle_call_guest`. Externals stored as `uint32_t` and cast at the call
site (`((void(*)(int))ff7_externals.x)(...)`) are rewritten to `gcall<Sig>(ff7_externals.x, ...)`.

**4. Calls game -> FFNx: guest exports.** Every host function the game can reach (the driver
table, replaced functions, callbacks FFNx stores into game structs) is registered as a
win32hle shim through a template, `guest_export<&fn>()`, that reads cdecl arguments from the
guest stack, calls the native function and returns `eax` (and `st0` for float returns).
Pointers handed back to the game must be guest memory; FFNx already allocates shared objects
with the game's own allocator (`external_malloc/calloc`), which lands in the guest heap.

**5. `replace_function(addr, fn)`: entry overrides.** Lifted code reaches a function either
through the dispatch table (indirect calls) or directly (`RECOMP_CALL(sub_X)`). Both pass
through `RECOMP_ENTER(va)`, which gets an override check: a bitmap over `.text` says whether
`va` is replaced, and if so the guest export for `fn` runs and the lifted body returns. Cost:
one bit test per lifted call. Installed at run time, exactly when FFNx calls `replace_function`.

**6. `replace_call(site, fn)`: call-site slots (lift time).** A replaced *call instruction*
cannot be redirected at run time in lifted code. The lift reads `config/callsites.txt` and
emits those sites as indirect calls through a slot table (default = original target);
`replace_call` writes the slot. This is a pcrecomp lifter patch.

**7. Code patches: a manifest, then re-lift.** `patch_code_*`/`memcpy_code` into `.data` work
at run time unchanged. Into `.text` they are recorded (`FFNX_RECORD_PATCHES=1`) to
`analysis/ffnx_patches.json` on a device run; `tools/run_lift.py` applies them to the image
before lifting and also emits call-site slots and function-boundary seeds from the manifest.
Two-pass: run once to record, re-lift, rebuild. Code patches that arrive after startup (by
config change) are reported loudly, never ignored.

**8. Platform.** bgfx renders through Vulkan (Adreno 740) on Android, into the app's
`SurfaceView`. FFNx's window/input/timer/file Windows calls go to SDL3 and POSIX. Steamworks,
crash dumps (StackWalker), DirectXTex, hwinfo and the mimalloc override are compiled out.
Shaders are precompiled to SPIR-V with bgfx `shaderc` at build time.

## Order of work

- **A. Driver core, hooks off.** Port `common.cpp` driver functions, `renderer.cpp`, `gl/*`,
  `image/*`, `cfg`, `log`, `saveload`; guest exports for the driver table; `ff7_init_hooks`
  skipped. Needs an Android app with a surface (the runner is headless). Exit: FF7's title
  screen drawn by FFNx on the Thor.
- **B. Hooks.** Entry overrides (runtime) and call-site slots + code-patch manifest (lift
  time), enabled module by module (`ff7/field`, `ff7/battle`, movies, music...).
- **C. Audio, movies, input.** SoLoud/vgmstream/openpsf, FFmpeg for movies, SDL3 gamepad.

Dependencies for A: bgfx/bx/bimg, toml++, libpng+zlib, xxhash, lz4, pugixml, cmrc, imgui
(overlay), SDL3. Built for arm64-android from source with the NDK.
