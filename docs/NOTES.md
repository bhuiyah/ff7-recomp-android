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
