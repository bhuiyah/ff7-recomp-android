# Notes

## 2026-11-07
- Project created. Plan: recompile `ff7\resources\ff7_1.02\ff7_en` (32-bit 1.02 US) with sp00nznet/pcrecomp, Android host from codepdbh/nfsu2-recompiled, renderer = FFNx built natively.
- Game source: `C:\Users\%USERNAME%\Downloads\7thHeavenAndroid\FF7\FINAL FANTASY VII` (not a Steam library). Imported with `tools/import_game.py`; `game/data` is a junction to `ff7\workingdir\data`.
- `ff7_en` (1.02 US, sha1 ac306ae92615af75ff36bba6347c67ca1284151d): x86-32, base 0x400000, entry 0x40B6E0, sections .text/.rdata/.data/.rsrc/.dotemu (import table lives in .dotemu, shorter on disk than in memory). No SteamStub. Imports WINMM, KERNEL32, USER32, GDI32, ole32, OPENGL32, DSOUND, DINPUT, DDRAW, MSACM32 and AF3DN.P (6 functions) directly.
- 2026 data layout: per-language kernel/battle/movies under `data/lang-xx/`; music in `music/` and `music_ogg/`.
- Toolchain: JDK 17 (`C:\Program Files\Microsoft\jdk-17.0.21.21-hotspot`), Android SDK at `%LOCALAPPDATA%\Android\Sdk` with NDK 28.2.13676358 (clang 19), CMake 3.30.5, build-tools 35, platform 35, adb 1.0.41. Python: pefile, unicorn, capstone installed.
- pcrecomp + 14 NFSU2 patches apply cleanly on 35548b8.
- Toolkit self-test (user approved running cloned code): `source tools/vsenv.sh x64; RECOMP_CC=clang py tools/lift/difftest.py` in `external/pcrecomp` → **246/246 match Unicorn, 0 failures**. Two cases (`sbb.idiom`, `span.carry.default`) report FIXED: the NFSU2 patches corrected divergences upstream still marks `known=`. Compiler: LLVM clang + VS 2022 Build Tools 14.44 link.
- **M0 exit met.** Next: M1 (catalog + lift `ff7_en`, Windows x64 host, FFNx linked as AF3DN.P).

## 2026-11-07 (M1 start)
- Catalog: 25,257 functions in 168 s (49/50 FFNx seeds are entries; `cdcheck` 0x408FF3 is a patch site inside 0x408FA6, correctly not an entry).
- Lift: 0 failures, 9.87 M lines in 64 files (520 MB), 68 s. 37 unimplemented instructions (`insb/sti/cli/iretd/int/aaa/arpl/into`) in 7 data-as-code "functions": 0x6338E2, 0x65181D, 0x6CFFD5, 0x6CFFFF, 0x6D0000, 0x76ABA8 and 0x7B3A1A..0x7B3C55. They stop with a named RECOMP_UNIMPL if ever reached.
- Whole lift compiles for aarch64 (NDK clang 19, -O1, RECOMP_FLAT_MEMORY) once `src/host/portable_guest.h` is force-included (portable rdtsc/cpuid, from NFSU2's arm64_guest.h).
- Host decision: pcrecomp win32hle (portable shim registry) + flat memory. Guest image at its real VAs (0x400000+), guest heap in a low arena. FFNx will use a 32-bit guest-pointer type for game-struct pointer fields (590 in ff7.h).
- win32hle allocated guest-visible memory with host malloc (above 4 GB on arm64). Patch 0015 adds `guest_alloc.c` (first-fit low arena, MAP_FIXED_NOREPLACE) and routes Heap*/Local*/Global*/VirtualAlloc/TIB/guest stacks/DIBs through it.
- `tests/probe_lowmem`: checks the Thor can map [0x400000, 0x80000000) 1:1. Waiting on the device (adb shows none yet).
- Dev loop: headless NDK binaries run over `adb shell` on the Thor (user's choice; no WSL on this PC).
