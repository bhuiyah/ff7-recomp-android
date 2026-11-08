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

## 2026-11-07 (M1: first device boots, AYN Thor QCS8550 / Android 13)
- tests/probe_lowmem PASS on the Thor: [0x400000,0x80000000) maps 1:1 (mmap_min_addr 0x8000). Flat memory is viable.
- Guest layout: image 0x400000-0xB6F000; guest heap arena 0x10000000-0x60000000; VirtualAlloc region 0x60000000-0x70000000; win32hle shim VAs 0x7F000000+.
- Loop: tools/build_headless.sh -> tools/run_device.sh (strip, adb push, run 120 s, logs/device-N.log). Each missing import stops with its name.
- Boot reached, in order: CRT init (heaps, TLS, CS, locale, env), WinMain, dotemuReg config reads, swap check, APP.LOG, timing loop (~1.2 M timeGetTime calls), window creation, DirectDraw enumerate/create, IDirect3D QI (refused, as intended with the custom driver), exclusive 640x480x16, primary+back surfaces, colour key.
- Fixes found on device: CRT small-block heap needs 64 KB-aligned VirtualAlloc (ff7_virtual.c); GetCommandLineA/GetEnvironmentStrings returned host statics above 4 GB (now guest copies); win32hle reported install C:\FURY3X (now C:\FF7); GlobalMemoryStatus without swap made FF7 refuse to start.
- The SIGSEGV "in CRT _write (0x0040F250)" was really in its caller (g_cur_func is only set on entry): FF7 loads AF3DN.P with LoadLibraryA and looks up new_dll_graphics_driver with GetProcAddress, our GetProcAddress said NULL, the game logged the failure to APP.LOG and then used the NULL driver. GetProcAddress now resolves any registered shim by name.
- With the driver found, FF7 skips the DirectDraw path entirely (that was its fallback) and calls new_dll_graphics_driver right after configuration. **Pre-renderer boot complete on device: the run ends at the renderer boundary (exit 93, the deliberate STOP).** Next M1 step: FFNx as the driver.
- Entry-trace build: `bash tools/build_headless.sh --trace` + `bash tools/run_device.sh --trace` (src/host/ff7_trace.c ring, printed on crash/STOP).
- Heredoc gotcha hit again (bash turned backslash-n escapes into real newlines in a py heredoc): all edits now via Edit/Write tools.

## 2026-11-07 (FFNx port, step 1: the bridge)
- Design written: docs/FFNX_PORT.md (gptr/gfn/guest_export, entry overrides, call-site slots, code-patch manifest, staged A/B/C).
- FFNx boundary measured: 785 replace_function/call, 640 code/data patches, 1029 code-walking reads, 958+101 externals, 717 FFNx->game calls, ~590 game-struct pointer members, 53 driver callbacks (240-byte table).
- 32-bit layouts measured with clang-cl -m32 on FFNx's own ff7.h (tools/ffnx_layout.cpp, tools/ffnx_driver_offsets.py -> src/ffnx_bridge/driver_table.inc): game_obj.externals +0xA8C, externals.assert_malloc +4.
- src/ffnx_bridge/guest_abi.h: gptr<T> (4-byte guest pointer), gcall/gfn (guest calls capturing eax/edx/st0, registers restored), guest_export<F> (native function as a cdecl/stdcall guest-callable shim).
- Device selftest (FF7_BRIDGE_SELFTEST=1) 10/10 PASS: native C++ called the game's lifted malloc/free via game_obj->externals (block 0x60000D30, in the CRT small-block heap inside our VirtualAlloc region), gptr round trip, int/float/pointer exports, stack balanced.
- new_dll_graphics_driver is now the bridge's export; it returns a 240-byte guest driver table of named stubs. The game's first driver call is gfx::lock (0x00660540): without FFNx's create_window hook, init has not been called yet -- on Windows FFNx's DllMain replaces create_window, which is where the driver init runs.

## 2026-11-07 (FFNx port, step 2: app shell + bgfx on the Thor)
- bgfx/bx/bimg: FFNx's forks at its pinned commits (julianxhokaxhiu/{bx c999742, bimg 671fe06, bgfx 1f93b90}), built from the amalgamated sources by cmake/bgfx.cmake (Vulkan on, FFNx's BGFX_CONFIG sizes; bimg needs the vendored astc-encoder). tests/bgfx_smoke on the Thor: "renderers compiled in: Noop Vulkan", init PASS.
- The fork differs from upstream: resolution lives in Init::swapChain, there is no bgfx/platform.h.
- android/: minimal app com.ff7recomp (Java GameActivity + SurfaceView, landscape fullscreen), libff7.so = cmake/ff7core.cmake (lift + win32hle + HLE + bridge) + bgfx + src/android/ff7_android_main.cpp (JNI, stdout/stderr -> logcat tag FF7Recomp + files/ff7.log, game on a 64 MB-stack thread). Gradle 8.9 / AGP 8.7.3 / NDK 28.2; local.properties needs forward slashes.
- Game files for the app: /sdcard/Android/data/com.ff7recomp/files/game (adb push works there).
- ART owns parts of the low 4 GB inside an app process (dalvik-main 0x12C00000-0x32C00000, boot image from ~0x6FF00000): first app run failed to reserve the arena. New layout: arena 0x33000000-0x5B000000, VirtualAlloc 0x5B000000-0x6B000000 (image stays at 0x400000). Headless runner works with it too.
- **On device, in the APK:** lifted game boots in ~1 s, bgfx Vulkan comes up at 1920x1080 on the game thread, the driver table is handed over, and the screen shows "stopped at gfx::lock: not ported yet" (logs/app-run-2.png). Driver stubs now park the game thread with an on-screen status in the app, exit 93 headless.

## 2026-11-07 (FFNx port, step 3: FFNx compiling for arm64)
- tools/ffnx_port.py (run by setup after patches/ffnx/*):
  - gptr: common_imports.h, common.h, ff7.h, ff8.h game structs -> gptr/gfn members (513 pointers, 478 fn pointers); host tables (externals) keep native data pointers, but T** become gptr<T>* (host pointer to a 4-byte guest cell), std::span<T*> -> std::span<gptr<T>>, LP* pointer typedefs handled.
  - casts: ff7_data.h/ff8_data.cpp `(T **)get_absolute_value(...)` -> `(gptr<T> *)` (114 sites), skipping fn-pointer parameter lists.
  - overrides: src/ffnx_port/override/patch.h replaces FFNx's (typed replace_* templates).
- tools/ffnx_layout_check.py: pristine FFNx headers built for x86 -> src/ffnx_port/layout_asserts.cpp: 154 sizes + 338 offsets, all hold on arm64 (one bug found and fixed: members with `= nullptr` initialisers).
- src/ffnx_port/patch_recomp.cpp: FFNx's patch API with recomp semantics -- hooks recorded (installed later as entry overrides / call-site slots), .text writes recorded for the re-lift manifest, data writes effective; ffnx_recomp_report / ffnx_recomp_write_manifest.
- Shims in src/ffnx_port/include: windows.h (fixed widths, 32-bit handles, VK codes, WNDCLASSA 40 bytes), ddraw.h (DDSURFACEDESC 108, DDCAPS_DX5 364), dsound.h, dinput.h, mmsystem.h, DirectXTex.h (DDS mod textures fail loudly until bimg-based), empty Windows-only headers, sys/timeb.h, placeholder FFmpeg headers.
- guest_abi.h grew: gfn from integers/native fns (thunk pool), implicit gfn->uint32_t address, by-value structs, thiscall/stdcall/variadic.
- patches/ffnx/0001: renderer.cpp draws into ff7_platform_window() on Android and skips Windows HDR detection; C-style casts for guest pointers in gl.cpp/ff7_opengl.cpp.
- Design note: FFNx stores its own ExternalMesh* in a game field (polygon_data.field_48, ff7/loaders.cpp `new ExternalMesh`). Pattern for such objects: allocate them in guest memory (placement new on guest_alloc) so the address fits. To do when ff7/loaders.cpp is ported.
- **Compiles for arm64:** gl/texture, gl/gl, gl/deferred, gl/special_case, renderer, cfg, log, image/tim, saveload, ff7_opengl. Not yet: common.cpp (includes every module), image/image.cpp (libpng), utils.cpp (cryptopp), and the modules common.cpp links against.

## 2026-11-07 (FFNx port, step 5: entry overrides)
- Finding: FFNx's common_create_window (its replacement for the game's create_window 0x67692A) is where FFNx installs hooks, inits the renderer/field/world/music/sfx/voice and then calls the game's engine_loop_obj.init and enter_main. Without that one hook the game runs its own create_window, which is why our run reached gfx::lock before init. So entry overrides are needed now, not in a later phase.
- tools/gen_retn_table.py -> generated/recomp/recomp_retn.c: 380 lifted functions end in `ret N` (callee pops), none with mixed ret forms.
- RECOMP_ENTER is redefined in src/host/portable_guest.h (force-included into the lift, no re-lift): one bit test per lifted call against ff7_override_bits; if set, ff7_run_override (src/host/ff7_overrides.c) runs the replacement (shim ABI) and, for a native replacement of a `ret N` game function, pops the N bytes.
- patch_recomp.cpp: replace_function installs overrides (FF7_FFNX_HOOKS=0 = record only, the A/B toggle); unreplace/rereplace toggle them; replace_call/replace_call_function stay recorded until call-site slots exist.
- FFNx replacement functions are effectively cdecl readers: __thiscall appears only on 3 game-function pointers (gfn_thiscall), never on replacements; callee-pop correctness comes from the binary (ret N), not from FFNx annotations.
- Device selftest 15/15: native override of the game's malloc reached via the lifted path, stack balanced, removal restores the original, mid-function sites refused.
- Next: compile common.cpp (Android branch for common_create_window: no Win32 window, size from the platform window), image.cpp (libpng build, Win32 file API -> stdio), utils.cpp (portable SHA-1/MD5), joystick/DirectInput types, FFmpeg log hook; then link FFNx into the APK and let FFNx's own new_dll_graphics_driver/create_window take over from the stub driver.

## 2026-11-07 (dual screen: bottom screen live)
- User asked for Thor dual-screen support like other Thor projects (Cemu/melonDS forks, Fallout 2 CE, RTS ports, EmuLnk dashboards). Planned as M7 in docs/ROADMAP.md (companion panels: party, map, battle, touch command deck, inventory).
- The Thor exposes the bottom screen as display 4 "Screen-2": 1240x1080 landscape, 120 Hz, own touch (EXTERNAL), FLAG_PRESENTATION. Top is display 0 (1920x1080). Screenshots per display: `adb shell screencap -d <id>` with ids 4630946441858561667 (top) / 4630946482288158084 (bottom).
- GameActivity shows a Presentation on the presentation display with its own SurfaceView -> nativeSetCompanionSurface -> ff7_platform_companion_window(). The bring-up display renders it as a second bgfx frame buffer (FFNx's fork: createFrameBuffer(SwapChain)) in view 1. Verified on device: logs/dual-top.png + logs/dual-bottom.png (bottom cleared green from the game thread).

## 2026-11-07 (FFNx port, step 6: the whole renderer core compiles)
- common.cpp compiles for arm64. Its Android branch of common_create_window: no monitors/display modes/Win32 window; size from the app surface; the game's HWND is a win32hle window (RegisterClassA/CreateWindowExA shims) whose WndProc is FFNx's WindowProc through a guest thunk (src/ffnx_port/android_window.cpp); the rest (hooks, renderer init, field/world/music/sfx/voice init, the game's own init + enter_main) is FFNx's code unchanged.
- ffnx_inject_driver stores the guest thunk of FFNx's new_dll_graphics_driver into game_obj.create_gfx_driver (the bridge mechanism, now from FFNx's own code).
- WPARAM/LPARAM/LRESULT are 32-bit in the shim (FFNx's WindowProc is called by the game).
- src/ffnx_port/win32_compat.cpp: FFNx's own Win32 calls on Android (POSIX files, find, paths, time, critical sections; registry/DLLs/extra monitors absent; window calls inert; FFmpeg log no-op until FFmpeg is built).
- src/ffnx_port/ffnx_hash.cpp: MD5/SHA-1 replacing Crypto++ (md5_hash is used by FFNx's save metadata).
- utils.cpp/image.cpp Android branches (no Authenticode, no version resources, UTF-8 file opens, entry point from the mapped PE headers).
- vgmstream: FFNx expects vgmstream's *internal* src/vgmstream.h at <libvgmstream/vgmstream.h> (its port's cmake.patch).
- **All 13 renderer-core files + 6 port files compile for arm64** (cmake/ffnx.cmake default list). Not yet linked: next is resolving the link (modules common.cpp calls: music, sfx, voice, movies, field, world, lighting, ...; libpng; bimg decode), compiling FFNx's shaders to SPIR-V, and calling FFNx's DllMain(DLL_PROCESS_ATTACH) from the host before the game starts.
- Dual screen (M7) bottom-screen swap chain already works from the bring-up display.

## 2026-11-07 (FFNx linked: FF7 plays on the Thor)
- FFNx fully linked into libff7.so: FFmpeg n9.0.2 static (VP8/Vorbis Matroska), bimg decode, SDL/DirectInput-joystick as "no device", FF8/crash-dialog symbols as named stops (tools/ffnx_link_stubs.py). Shaders: bgfx shaderc built with VS2022 Build Tools, 43 SPIR-V shaders pushed to <game>/shaders.
- Boot: ff7_boot runs FFNx's DllMain after mapping the exe (FF7_WITH_FFNX); the game gets FFNx's own new_dll_graphics_driver.
- Game-side HLE added: DirectSound (AAudio mixer), MSACM (MS-ADPCM), DirectInput init, ole32 (no DirectMusic), multimedia timers run on the game thread at its poll points, guest working directory.
- Bridge (guest_abi.h): host const char* -> interned guest copies; FILE* as handles (0xF0000000+); bounce buffers for host structs / scalar out-params / char* strings passed to game functions.
- ffnx_edit_android.py now also rewrites, by pattern: patch_code_dword(addr, (DWORD)&fn) -> ffnx_guest_va (108); native pointers to game functions -> guest::gfn (39). Plus guest-memory placement for data FFNx hands the game (blend modes, keys, texture sets, driver heap, facial vertices).
- FFNx compiled -O2 with -fno-delete-null-pointer-checks -fno-strict-aliasing (MSVC semantics it relies on; clang -O2 removed a null check after &null_ptr[i]).
- **Verified on the Thor:** credits -> title -> New Game -> opening FMV -> Sector 1 station field (walk with the pad) -> first battle to victory -> field menu. OK is Circle = XInput B (PS1/Steam layout). Audio sessions active (not yet checked by ear).
- Tools: run_app.sh, play_newgame.sh, play_to_battle.sh (adb-driven), symbolize.sh, files/env.txt switches (FF7_PATH_TRACE, FF7_WATCHDOG).
- Open: frame rate (39 FPS in credits at -O0 FFNx; 21 after -O2 with the battery at 5% -- remeasure charged, profile with simpleperf); saves; world map/minigames; bottom-screen panels (the FFNx renderer owns bgfx now, so M7 needs a second view in FFNx's renderer); launcher (M4).
- The Thor drains on 900 mA USB while the game runs: test on a proper charger.

## 2026-11-07 (frame rate: FFNx code patches live, 60 FPS works, 120 FPS mode started)
- Found: FFNx's call-site hooks (111 of 205) and all its code patches had no effect in the
  lift (the lifted code never reads its own instruction bytes). Fixed generally:
  pcrecomp patch 0017 (RECOMP_CALL_AT on every direct call; patched-code variants chosen
  by RECOMP_CODE_MATCH; live dword operands anywhere in an instruction) + run_lift.py
  --manifests + a runtime call-site table. Manifests per FFNx frame-rate mode in
  analysis/ffnx_manifests (game bytes: gitignored); tools/refresh_manifests.sh recollects
  them on the device and re-lifts. All 205-255 hooks now live.
- FFNx globals the game reads via code patches: fixed guest block at 0x6B000000.
- **60 FPS mode (ff7_fps_limiter = 3) verified on the Thor**: field 60, battle 60 (vanilla
  15), swirl, menus; route to the first battle and menu without faults.
- 120 FPS mode (ff7_fps_limiter = 4, ours): field/world x4 ticks (FFNx's shift-count
  patches m/2 = log2 m stay exact at x4), battle/menus/minigames as in 60 mode. App asks
  both screens for their 120 Hz mode and votes 120 on its surfaces. State: boots, field
  and menu run, dialogue fixed (divides that rounded a step to 0 are kept at 1 and logged:
  8 sites, text paging/opening, field and world); the FPS counter still reads 60.0 in the
  field -- a remaining 60 cap to find (next: the game's own field limiter as patched, and
  the swapchain's present interval). Battle at x8 (true 120) needs FFNx's battle maths
  reworked (shift counts 2 - m/2 have no value at x8).
- Movies play at their encoded rate (FFNx's movie_fps_ratio supports 30/60 FPS files);
  higher needs interpolated movie files (offline), not a runtime change.
- The Thor drained to 3% during this session on its 900 mA USB supply.

## 2026-11-07 (M4: mod overlay core)
- src/mods: 7th Heaven's IRO archives (all three storage kinds: raw, LZS, LZMA via the LZMA
  SDK decoder; relocated directories; versions 0x10000-0x10002), mod.xml (ConfigOption,
  ModFolder/Conditional with ActiveWhen And/Or/Not/Option/GameLanguage, FFNxConfig,
  Variable; LoadLibrary & co reported as unsupported Windows code), and the overlay itself.
  Semantics follow 7th Heaven's AppWrapper/AppCore (MS-PL; reference checkout in
  external/7th-Heaven): first mod in the profile wins; within a mod, active Conditionals,
  then active ModFolders in mod.xml order, the root only when it declares neither; game
  paths matched relative to data/ (+ data/lang-<lang>/ for the 2026 edition), FFNx's
  mods/Textures/, and the game root.
- Hook: hle_host_path (every open by the game and by FFNx); IRO entries extracted on first
  use to files/modcache and reused while the archive is unchanged.
- Profile: files/mods/profile.xml (<Profile Language="en"><Mod Path="x.iro"><Option
  ID=".." Value=".."/></Mod>...</Profile>), loaded at boot; no profile = no mods.
- tools/test_mods.sh: 37/37 on the PC (generated IRO + folder mod). tools/fetch_deps.sh.
- Not yet: RuntimeVar conditions (game-memory checks; such folders apply unconditionally
  for now and are reported), hext from mods, FFNxConfig from mods into FFNx.toml (the
  launcher's job), directory listings of modded folders, and a test with real 7th Heaven
  mods on the Thor (the M4 exit test: IFRIT + Ninostyle Chibi).

## 2026-11-07 (M4: launcher)
- LauncherActivity is the app's entry point: Mods (list from files/mods in priority order,
  on/off, move up/down, per-mod options from mod.xml, add .iro archives or mod folders via
  the Android file picker), Settings (frame-rate mode 0-4, FPS counter), Game files (import
  from the Steam folder via the folder picker: ff7/resources/ff7_1.02/ff7_en -> ff7_en.exe,
  ff7/workingdir/data -> data, other languages skipped), Play. Pad-driven: D-pad focus,
  A confirms, B back, Start plays.
- libff7launcher.so: mod.xml/IRO metadata through the same src/mods code (JSON over JNI).
- Play: writes files/mods/profile.xml (all mods, Enabled flags), installs FFNx's runtime
  files from the APK (assets/ffnx, staged by tools/stage_apk_assets.sh) once per app
  version, writes FFNx.toml from FFNx's defaults + Android settings + the frame-rate choice +
  enabled mods' FFNxConfig, then starts GameActivity in its own ":game" process; when the
  game ends the process exits and the launcher shows again.
- Not run yet (the Thor was disconnected): first device run of the launcher is next.

## 2026-11-07: 120 FPS battles assessed; widescreen setting; mod run-time conditions
- Mod run-time conditions: src/mods/runtimevar.* evaluates 7th Heaven's RuntimeVar
  conditions (Byte/Short/Int with mask, Sys, counters, random) through a memory reader, with
  7thHeaven.var aliases installed next to the profile. Conditional folders apply only while
  their condition holds. tests/mods: 49/49.
- Widescreen: the launcher's Settings page gets an aspect choice (4:3, or 16:9 with uncrop,
  the default). writeFfnxToml sets aspect_ratio/enable_uncrop. refresh_manifests.sh collects
  each frame-rate mode with aspect 0 and 2 (ffnx_manifest_l{n}[_ar2].json). Needs the Thor to
  collect the widescreen manifests and re-lift.
- 120 FPS battles: not feasible as constant patches. See ROADMAP M5b; mode 4 keeps battles at 60.
- 120 FPS battles implemented (ROADMAP M5b): battle multiplier 8 in mode 4, with per-frame
  code stepping for the inexact steps (patch_recomp.cpp step_fraction/step_alternate, manifest
  "code_variants" lifted by run_lift.py). Refactored run_lift's manifest handling gives the
  same 384 variants at 240 sites on the existing manifests. APK builds. Needs the mode-4
  manifest from the Thor, a re-lift and a side-by-side check against 60 FPS.

## 2026-11-07: hext patches from mods
- src/mods/hext.cpp mirrors AppWrapper/HexPatch.cs (title line skipped, comments, +/- offset,
  addr=bytes with fill and pointer reads, addr:len, Delay). mods.cpp ff7_mods_hext lists
  each enabled mod's hext files in Wrap.cs order (lowest priority first; names from root,
  active Conditionals, active ModFolders; each name from Conditionals, ModFolders, root).
- src/host/ff7_hext.cpp applies them at boot (after the image is mapped, before FFNx) and
  checks each code write against recomp_variants.c (new run_lift output, weak until the
  next lift).
- tools/hext_manifest.sh: the code writes of given mods/profiles as re-lift manifests,
  read through the same src/mods code. A test mod patching 0x41BDE5 gives one more lifted
  variant (384 -> 385). tests/mods: 68/68.

## 2026-11-07: x86 interpreter for hext code patches
- src/interp/x86_interp.c: every instruction form in ff7_en (148 mnemonics, integer +
  string + x87; no MMX/SSE in the exe) plus pushad/popad, cmovcc, xadd, cmpxchg, bswap,
  bsf/bsr. Runs on g_eax.., the lazy flags (in: recomp_eflags, out: FK_EFLAGS), g_st and
  g_fpu_cw. A marked function is an entry override (target 0xFFFFFFFE in
  ff7_overrides.c); calls go to lifted code (call-site hooks and overrides honoured) or
  recurse into the interpreter for marked / never-lifted targets; ret ends the activation.
  Where the lift deliberately differs from hardware (div by zero -> 0, fdivp by 0.0 keeps
  the dividend, its pow/log formulas) the interpreter follows the lift, so a function
  behaves the same whether a mod made it interpreted or not.
- ff7_hext.cpp marks the function of every code write without a lifted variant (all of them
  until the next lift emits recomp_variants.c).
- tools/interp_difftest.sh: straight-line runs from the catalog, lifted by lift32 and run
  both ways from random states (exe mapped at its VAs, write-watch on all pages), plus
  control-flow tests. 6000 runs x 4 states: 17070 agree, 0 differ (6926 both fault on
  random pointers); control flow pass.
- Found in the lift along the way:
  - 8/16-bit sar shifted zero-extended values (`sar cx, 1` on a negative short gave a
    large positive one; game code e.g. 0x5A5368). Fixed: patches/pcrecomp/0018. Takes
    effect at the next lift.
  - fdivp/fdivrp st(0) (DE F8 / DE F0, CRT code at 0x7B3A30 / 0x7B3A3C) lifted as the
    st(1) form; the real instruction pops its own result away (checked natively on the PC).
    Fixed for all six pop-forms: patches/pcrecomp/0019. Takes effect at the next lift; the
    difftest now includes those runs (17032 agree, 0 differ).
  - documented approximations the difftest masks: CF after inc/dec, OF/PF of shifts,
    imul/mul flags, bt ZF, narrow cmps/sbb SF/OF, fcom kept in the integer flags.

## 2026-11-07: re-lift on the PC; hext status in the launcher
- Re-lifted with the manifests on hand (l1..l4): 25257 functions, 0 failures, patches
  0018 (narrow sar) and 0019 (fOPp st(0)) now in the APK, recomp_variants.c (384
  variants) emitted. The widescreen and mode-4 stepped manifests still need the Thor
  (refresh_manifests.sh).
- Two build fixes: the generated-sources glob is CONFIGURE_DEPENDS (a new generated file
  was not compiled until CMake re-ran), and ff7_hext.cpp references the variant table
  strongly -- a weak reference never pulls a member out of the static ff7_core archive, so
  the table was silently missing. CMake now stops if generated/ predates the table.
- ff7_hext.cpp writes mods/hext_status.txt (one row per hext file: state, data writes,
  code lifted / interpreted / without effect); the launcher shows a line per mod from it
  ("2 hext patches: 3 data, 1 code interpreted (last run)"), flagged when a file stopped
  early or a code patch had no effect.

## 2026-11-07: on the Thor -- speed, 120 FPS battles, quick start
- 60 FPS cap gone: the opening field runs at 119 FPS with vsync (fps_probe.sh).
- "The game looks sped up": the APK was lifted from manifests that predate the 120 FPS battle
  work and widescreen, and patched code with no lifted variant runs the ORIGINAL 15/30 FPS
  instructions. refresh_manifests.sh (modes 1-4 x aspect 0/2): 578 variants at 275 sites
  (was 384 at 240). tools/speed_probe.sh times the scripted opening scene: 16.4 / 16.1 /
  16.2 s at 30 / 60 / 120 FPS -- field speed is right in every mode.
- Safety net (patch_recomp.cpp): each FFNx code write is checked against the lift's variant
  table (ff7_code_runnable in ff7_overrides.c); a function whose patched bytes the lift
  cannot run is switched to the x86 interpreter instead of silently running the original
  code. 0 fallbacks after the refresh. FF7_INTERP_FALLBACK=0 turns it off.
- Two 120 FPS battle bugs of mine, found on the device:
  - the battle-enter countdown's step_alternate wrote the ModRM byte (+0x1B) instead of the
    imm8 (+0x1C) of `sub cl, 1`: `add byte [eax], 1`, a crash as the battle opened.
  - stepping the battle menu loop count to 0/1 left every other frame without the battle
    windows (the loop's last pass draws them). x8 keeps one pass per frame, as at 60.
- Open: ATB fills in 6.5 / 5.8 / 13.4 s at 30 / 60 / 120 FPS (tools/battle_speed_probe.sh):
  120 FPS battles run ~2.3x slow.
- Quick start (src/ffnx_port/quickboot.cpp; launcher Settings > Start; files/quickboot.txt
  or FF7_QUICKBOOT): skips the credits, picks New Game (or Continue, falling back to New
  Game without saves) and skips the opening FMV, driven off the game's mode; in the field
  7.2 s after launch. The title cursor (0x00DD6F24: 0 New Game, 1 Continue) was found
  with FF7_MEMDIFF=1, a watchdog-thread diff of .data/.bss. Continue with a real save is
  not tested yet (no saves on the device).
- Snapshot save states: not yet. FFNx keeps C++ objects with host pointers (gl_texture_set's
  std::maps, GPU texture handles) inside guest memory, so a memory image cannot simply be
  restored; see ROADMAP M5.
- Battle speed, measured as a full ATB refill after Cloud's first attack (the first gauge starts
  part-full from a seed that depends on the field frames, so it misleads): original 6.3 s;
  before the fix 60 FPS 3.1 s (2x fast: FFNx's truncated divides plus my zero clamp). Per-frame
  stepping now applies at every raised frame rate: 30 / 60 / 120 FPS = 5.7 / 6.2 / 6.2 s.
  x8 battles 13.8 s: opt-in (FF7_BATTLE120=1) until that is found.
- Test scripts default to 16:9 (deploy_ffnx.sh); the probes ask for 4:3 where they read pixels.

## 2026-11-08: save states (docs/SAVESTATES.md)
- Safe point: common_flip (ff7_core_game_loop is not the per-frame path on this build). The
  state records the host stack depth (from the thread's stack top) and the guest esp; loads
  wait up to 10 s for a frame where both match (early in the credits a nested loop flips).
- Cross-launch: FFNx's gl_texture_set lives in guest memory with std::map members (host
  nodes): restoring it as-is crashed on 0xbe00000001. Texture sets are now registered and
  rebuilt after a load (fresh maps, every loaded palette uploaded again). The heap layout is
  close to identical across launches (21 vs 20-21 free ranges) but not exactly; nothing relies
  on it.
- Cross-mode: FFNx's field caches/widescreen (ff7/field/enter.h) and the music are put back by
  the saved mode (FFNx's cached mode only updates next frame). Credits -> field load: field,
  16:9 layout, music 95 -> 2.
- Measured on the Thor: 26-30 MB per state, 3 MB on disk, saved in 0.1 s; resume at launch
  lands in the saved moment ~1.5 s after start.

## 2026-11-08: 120 FPS battles (x8) -- animation speed and ATB
- The user saw "character models shaking fast" in 120 FPS battles. A 118 FPS screen recording
  (adb screenrecord; frames via imageio-ffmpeg) showed Cloud's idle pose changing every frame.
  The keyframe counter in battle_update_3d_model_data (+0x13E / +0x316 imm8) advanced once per
  frame, twice the 60 FPS rate. It now steps 1 / 0 on alternate frames.
- ATB: FF7_MEMDIFF=2 (rising 16-bit words) found the actor gauges (0x9A8B14, stride 0x44).
  At x8 they filled twice as fast (party and enemies, about 22 000 / s). The battle menu step
  runs once per 120 FPS frame. The ATB tick at 0x4322AE now adds the speed on alternate frames.
  Refill: x4 6.0 s, x8 6.1 s (FF7_WATCH timeline). The probe's "x8 ATB 13.8 s" was a pixel
  misread of the pulsing full bar.
- Save states crash when loaded under other FFNx settings (a 60 FPS 16:9 state loaded at 120 FPS
  4:3 faulted at 0x660626). States now record the settings (R_CONFIG, format version 3), and a load
  under others is refused with a note. Older states are refused too.

## 2026-11-08: field animations at 60 / 120 FPS
- The user reported characters running too fast in the field above 30 FPS. FFNx's field code
  assumes animation files with common_frame_multiplier times the original frames: CANIM/CANMX
  frame numbers are scaled by it, and PC players install 60 FPS animation mods. With the game's
  own char.lgp animations, every walk and run cycle played 2x (60) or 4x (120) fast. Movement
  speed and scripted timing were right, which is why speed_probe.sh had passed.
- Fix (tools/ffnx_edit_android.py, ff7/loaders.cpp): field_load_animation's call to
  load_animation (+0x16D) goes through field_load_animation_smooth. At m = 2 / 4 it rebuilds
  the animation with n * m frames: original frame k becomes frame k * m, with interpolated poses
  in between (angles the shortest way round), and the last frame is held.
- Checked with FF7_WATCH (now also `*ADDR+OFF`: a field the game points to). Opening-scene model
  1 (field_event_data at *0xCC0B60, stride 0x88: currentFrame +0x68 in 1/16 frames, lastFrame
  +0x6A): 40 / 80 / 160 frames at 30 / 60 / 120 FPS, advancing 30 / 60 / 120 frames a second.
  The cycle takes 1.33 s in every mode.
- Not done: the world map (its models use other loaders; FFNx scales its frame intervals
  itself; no save that reaches it yet). A 60 FPS animation mod installed on top would be
  doubled again.

## 2026-11-08: battle animations at raised frame rates; 2x speed
- Battle models moved 4x fast at 60 / 120 FPS. The original 15 FPS battle advances a model's
  keyframe counter (battle_update_3d_model_data, model state +0xB9C) about 15 times a second;
  FFNx makes that 60 (+2 at 30 FPS, +1 at 60) or 120 at x8. Like the field, it assumes
  animation files with 4x (8x) the frames.
- Fix: each battle animation gets in-between frames the first time it is updated or drawn.
  The hooks are the nine calls of battle_update_3d_model_data and the four battle-model calls of
  draw_3d_model (0x5E0D3C / 0x5E0DDE), so weapon animations are covered too: they live in the
  same model's array at the body's index + 0x34. The factor is 4 at x2 / x4 and 8 at x8, and the
  x8 +1/+0 keyframe alternation is gone. ffnx_smooth_animation is shared with the field, marks
  what it has done (anim_header.rotation_order[3] = 0xA5), and only takes load_animation's
  layout. Weapon animations have no bones, only a root transform per frame.
- Measured (FF7_WATCH on Cloud's counter): original 20 frames at 13 / s; 60 FPS 80 frames at
  57 / s; x8 160 frames at 113 / s. That is the original pace in every mode. A recording at x8
  shows the sword and the soldiers' guns in every frame.
- 2x speed (launcher Settings, "2x speed (turbo)"): FFNx's speedhack starts at 2x
  (speedhack_min = 2.0). With 60 or 120 FPS chosen, the game uses its 60 FPS timing
  (ff7_fps_limiter 3), which at 2x is shown at 120 FPS. In game, L3 then L2/R2 toggles it
  (FFNx's shortcut). Checked: 118 FPS shown, and opening-scene animations advance at twice the
  60 FPS rate.

## 2026-11-08: field camera stuck in md1_1 ("freeze" after the station)
- Report: after the station, walking up in the next area froze the game. It had not frozen: the
  camera stayed put while Cloud walked off the visible part of md1_1 (field 117). Pressing down
  brought him back and the next step started a random battle.
- Cause: FFNx's smooth background scrolling projects the player with a float camera matrix
  (ff7/field/utils.h, engine_apply_matrix_product_float_66307D). It hands a local
  `float matrix[16]` to two game functions; through the guest bridge a `float *` is an
  out-parameter of one float, so the game wrote 64 bytes into a 4-byte guest block (heap
  overrun, every frame of a scrolling field) and the matrix came back uninitialized. The
  projected player was (0.1, 8037) instead of (170, -20), so the camera sat clamped at the
  field's bottom edge. Fix (tools/ffnx_edit_android.py): the matrix crosses as a 16-float struct.
  This is the only FFNx call that passes a float array to the game.
- Found with two save states of the frozen game: field id 0xCC15D0, the trigger header's camera
  range, the md1_1 walkmesh/camera from flevel.lgp (Cloud on triangle 70; the hand projection
  matched), then a one-off FFNx log of the raw and clipped camera point.
- Also: bgfx rebuilt the swapchain after every present. Android answers VK_SUBOPTIMAL_KHR to
  any swapchain whose preTransform is not the panel's rotation (bgfx asks IDENTITY; the Thor's
  panel is ROTATE_90 and the compositor rotates). patches/bgfx/0001: on Android SUBOPTIMAL is
  not a reason to rebuild; a size change still comes as OUT_OF_DATE.
- Note for later checks: /proc/uptime counts suspend time, SurfaceFlinger's --latency times
  do not; compare frame times with each other, not with uptime.

## 2026-11-08: scenery over text boxes (nmkin_1); float results across the guest bridge
- Report: in the reactor entrance (nmkin_1, field 120) the stairs, railing and pipes were drawn
  over every text box. All 400 layer-2 tiles had depth 0.0 (the nearest).
- Cause: FFNx rebuilds the baked layer-2 depths for fields whose near plane is over 50
  (ff7/field/field.cpp, field_initialize_background_depths) with the game's
  field_layer_sub_623C0F, called through the guest bridge. The bridge read a float result as
  `g_st[g_fp_top & 7]`, but in the lifted x87 model st(0) is always `g_st[0]` (g_fp_top only
  counts pushes): every float/double a game function returned to FFNx read as a stale slot,
  usually 0. FFNx's layer 3/4 depths came from the same call. The other direction was wrong too:
  a native function returning a float to the game wrote a slot instead of pushing.
  Fix (src/ffnx_bridge/guest_abi.h): results read g_st[0]; native float results use the lifted
  code's own fp_push_impl.
- Checked on the Thor: the depth function now returns 0.001 / 0.87 / 0.976 / 0.995 for ids
  1 / 100 / 500 / 2000; nmkin_1 reloaded has no zero-depth tile and Biggs's box is drawn whole.
  Fields without the rebuild (station, md1_1) had correct depths with or without the fix.
- Found on the way: finishing a battle after loading a state aborted ("free of a pointer outside
  the guest arena"). FFNx's button-prompt list (ff7/universal_buttons.cpp, prompt_resources)
  is host state pointing at game objects; after a load it still named the old session's
  objects and the menu reload freed whatever was there now. A third save-state hook
  ("ffnx-prompts") keeps the list in the state and renews the restored prompt texture sets'
  host parts (gl_set, atlas texture) after the texture rebuild. States saved before this have
  no record: the list is cleared, and their prompt objects keep stale host pointers (save again).
- Note: "quick" in tools/ss_cmd.sh and Select+L1/R1 mean the current numbered slot
  (savestates/current.txt), not quick.ff7s.

## 2026-11-08: bottom screen companion (M7 step 1)
- The Presentation on the Thor's second display now hosts CompanionView (Android Canvas), not a
  bgfx swap chain: crisp text, native touch, nothing added to the game's renderer.
- State: src/ffnx_port/companion.cpp builds a JSON snapshot on the game thread (from
  ffnx_recomp::step_code, every 100 ms) -- getmode() calls into the game, so the UI thread
  must not -- and the UI thread copies it (JNI CompanionView.nativeState).
  - party: savemap chars[party_members[i]]. max_hp/max_mp are the game's last stat
    calculation (menu/battle); a member who joined since (Barret: 318/222) falls back to
    base_hp/base_mp until then. The runtime party array (0xDBA498, 0x440 each) has the same
    lag.
  - battle: battle_context->actor_vars[0..2] (live HP/MP), ATB at 0x9A8B14 + 0x44*slot,
    enemies actor_vars[4..9] with enemyActorsMask; enemy names from the scene's type records
    at 0x9A8E9C (0xB8 each, FF7 text name first), indexed by actor_vars.index (1.02 US).
  - Checked against the game's own menu and battle window: identical numbers.
- Commands: a mailbox drained on the game thread: 2x speed (GameHacks::companionToggleSpeedhack,
  2x on / normal; FFNx's toggle turns the hack on at its current speed, 1x without the
  launcher's 2x option), save/load state (current slot). Menu = a 150 ms Triangle press
  injected into the pad.
- Testing taps: `adb shell input -d 4 tap X Y` (logical display 4 = bottom screen);
  screenshots: `adb exec-out screencap -p -d 4630946482288158084` (bottom) /
  `-d 4630946441858561667` (top).

## 2026-11-08: battle windows on the bottom screen (M7 step 2)
- In battle the game draws its windows as pre-transformed 2D quads in the band y 330..450 of its
  640x480 frame. gl_draw_indexed_primitive (FFNx edit) asks ffnx_companion_route_2d about each
  battle TLVERTEX draw; one entirely inside the band is submitted by Renderer::draw to view 250,
  whose frame buffer is the bottom screen's swap chain (bgfx window frame buffer on the
  Presentation's SurfaceView, D24S8 depth) and whose ortho projection puts x 0..640 / y 330..450
  across the top of that screen. The top screen no longer gets those draws, so the battle is
  uncovered; the action-name box, damage numbers and the target hand (above the band) stay.
- View 250 is set up in Renderer::show for the next frame (only in battle with a bottom
  screen); FFNx's own views stay 1..n.
- Bottom screen: a FrameLayout with the SurfaceView under CompanionView; in battle
  CompanionView leaves the band (width * 120/640 px) transparent and draws enemies and the
  touch deck below it.
- Cost: none measured (top screen 118 FPS in battle with both screens drawing).
- Not yet seen on the device: the Magic/Item lists (same band, so routed the same way); adb key
  presses are too slow to catch a command turn reliably.

## 2026-11-08: bottom screen battle layout and touch; Magic crash
- Layout (user request: enemies above, compact; bigger command window): CompanionView draws
  enemies in two compact columns at the top and the deck at the bottom, and tells native code
  the area between (ff7_companion_set_area). companion_render.cpp lays the game's windows out
  there: on a command turn the band's left part (x 0..267: name + command menu) and right part
  (267..640: HP/MP/Limit/Time) stacked at ~2.8x; when a window crosses x 267 (Magic/Item/Limit
  lists) the whole band (y 330..480) at full width, up to 1.5x taller than wide. Window frames
  and headers are batched across both parts, so each routed draw is submitted to every
  region's view (Renderer::draw, extra views first with BGFX_DISCARD_NONE) and each view's
  projection crops its part. The layout goes to CompanionView in the snapshot for touch.
- Touch: the game's hand cursor (a 48x26 quad, texture rect 0.75,0.0625-0.9375,0.164) is
  tracked each frame; a tap maps back to game 2D coordinates and the game thread presses the
  D-pad one step at a time (held 4 frames, 6 frames apart, at most 24 steps) until the hand is
  on the tapped row/column, then Circle. With no menu hand in the windows (choosing a target on
  the top screen) a tap confirms. In battle the deck's first button is Back (Cross).
  Checked on the Thor: Magic -> Bolt -> target, by touch; Bolt hit for 92.
- Magic crashed the game (found by the touch test): FFNx's magic_thread_start called the
  game's loader callback natively (a guest address: jump to 0x5AEC40). It is now a guest call
  (tools/ffnx_edit_android.py). Every first spell in a battle hit this.
- Loading a state and then casting crashed in gl_draw_sorted_deferred ("free of a corrupt or
  foreign guest block"): FFNx's two draw-queue arrays (gl/deferred.cpp) are allocated once
  from the game heap and kept in host globals, which a load does not restore. Hook
  "ffnx-deferred" saves the two pointers with the state and restores them (states without the
  record get new arrays).

## 2026-11-08: mod catalogs in the launcher (M4 step 6, "Get mods")
- Format from 7th Heaven's source (tsunamods-codes/7th-Heaven): default subscriptions
  iros://Url/https$github.com/tsunamods-codes/7th-Heaven-Catalogs/releases/download/canary/
  {qhimm,sega}.xml (104 + 9 mods); Catalog > Mods > Mod (ID GUID, Name, Author, Category,
  Description, Link = info page, Tags, Requirement/ModID, MetaVersion, LatestVersion with
  repeated Link = download alternatives, Version decimal, ReleaseDate, ReleaseNotes,
  PreviewImage, DownloadSize in KB). Catalogs refresh after a day; merged by ID (higher version).
  Updates: catalog Version > installed Version.
- Links: iros://Url/<scheme>$<rest> (raw spaces and brackets in paths), iros://GDrive/<id>
  (docs.google.com/uc?id=..&export=download, then the warning page's form or confirm/uuid
  fields; "Quota exceeded" page = error), iros://ExternalUrl (browser). Live: 77 Url, 41 GDrive.
  Files: 41 .7z, 34 .iro, 1 .zip among the Url links; Drive files sniffed.
- Launcher: ModCatalog.java (subscriptions, cache files/catalogs, parse, merge, search, the
  installed registry files/mods/catalog_installed.json), ModDownloader.java (resumable HTTP
  with its own redirects, free-space check, IRO/zip/7z by signature; Commons Compress + XZ
  for 7z), LauncherActivity "Get mods" page and mod pages, Mods page Update / Update all,
  Settings > Mod catalogs. A new install is enabled; an update keeps the library name (order
  and options stay). INTERNET permission added.
- Checked on the Thor: catalogs fetched (113 mods); Jessie Mod (.7z, mod.xml + folders) and
  Curse Ring Challenge (Google Drive -> .iro) installed, enabled, loaded by the game
  ("2 mod(s), 61 game paths replaced"), then removed from their pages.
- Fix: some catalog links are iros://Url/https://host/... (no `$`); they resolved to host
  "https" (Arranged Soundtrack, Cosmos mods, Chthonic Harmony). Both forms are read now.
- Download queue (DownloadQueue.java, one per process): catalog installs/updates queue up and
  run one at a time on a worker while the launcher stays usable (Play too). A Downloads page
  in the left menu shows each item (progress bar, sizes, %), reorders / cancels waiting ones,
  cancels the running one (its .part is deleted), retries failed ones, clears finished ones.
  Get mods rows and mod pages show "Queued" / "Downloading n%"; the status line shows the
  running item and how many wait. Waiting items persist in files/downloads/queue.txt and
  restart (the running download resumes from its .part) when the launcher opens again.
  Not yet exercised on the Thor (the user was playing).
- Mods that replace files inside LGP archives (NinoStyle: char/*.hrc ...) did nothing: the
  overlay watched only data/, mods/textures/ and the root. It now also watches 7th Heaven's
  ExtraFolders (ambient, direct, lighting, mesh, music, override, save, sfx, shaders, time,
  voice, widescreen); FFNx reads LGP entries from direct/<lgp>/<file>. Checked on the Thor:
  NinoStyle Cloud/Barret/NPC models load; Echo-S music/voice and Cosmo Memory FMV now open.
  The knocked-out MPs on the Sector 1 platform look the same with NinoStyle off (game pose).
- override/ redirection crashed at boot (kernel2.bin): FFNx lower-cases the path it built
  from basedir ("/storage/.../android/data/..."), and the game-root test was case-sensitive.
- vgmstream (streamfile_stdio.c) and SoLoud (soloud_file.cpp) fopen now go through the path
  mapping + overlay (force-included ffnx_file_paths.h): FFNx hands them game paths
  ("C:\FF7\data\movies\opening.ogg", "<basedir>/data\sound\audio.dat"), which failed.
- Battle: a confirm held past 200 ms auto-repeated (the game's repeatable query: 200 ms delay,
  50 ms interval), so a firm press on Attack also confirmed the target and the aiming hand
  vanished. In battle, non-direction buttons now count once per press (ffnx_edit_android.py,
  ff7_query_repeatable_input). FF7_INPUT_TRACE=1 logs the input state per frame in battle.
- Known: a save state taken during the battle swirl crashes (memcpy from null in an FFNx
  export); toml configs inside mods ("music/vgmstream/config.toml") still fail to parse
  (toml++ opens them with std::ifstream, not routed); the bottom screen shows "MP" instead of
  enemy names with Echo-S.
