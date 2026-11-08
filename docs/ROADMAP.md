# FF7Recomp roadmap

Goal: FINAL FANTASY VII (Steam) statically recompiled from the 32-bit 1.02 `ff7_en` exe to native ARM64 Android, rendered by FFNx (bgfx/Vulkan), shipped as one APK whose entry point is a 7th Heaven-style mod launcher. Target device: AYN Thor (Snapdragon 8 Gen 2).

Each milestone ends with its exit test, a commit and a report. One milestone at a time.

| # | Milestone | Exit test |
|---|---|---|
| M0 ✅ | Inputs and toolchain | `tools/check_inputs.py` passes; pinned pcrecomp difftest 246/246 |
| M1 | Lift and boot (on the Thor, headless + APK) | Title screen through FFNx (bgfx/Vulkan) |
| M2 | Playable | New game to Sector 7, no divergence from original exe + FFNx |
| M3 | Android polish | Same run on the Thor, steady frame rate, controller |
| M4 | Mods + launcher APK | IFRIT + Ninostyle Chibi installed in launcher, visible in game on the Thor |
| M5 | Polish | Frame pacing, high frame rate (60, then 120 FPS on the Thor's 120 Hz screen), save states, controller-driven launcher |
| M5w | Widescreen for 16:9 handhelds like the Thor (user request 2026-11-07) | Field, battle, world map and menus fill the Thor's 1920x1080 screen with no stretching; FMVs letterboxed or 16:9 |
| M6 | RetroAchievements (user request 2026-11-07) | Local achievement set unlocks in game; then RA approval to go online |
| M7 | Thor dual-screen companion (user request 2026-11-07) | Bottom screen shows live party/map panels and a touch command deck while the game runs on top; in battle the command window moves to the bottom screen and the top screen shows the battle full-frame |

(M1–M3 changed order when the user chose the Thor as the test host: there is no
Windows-host stage; everything runs on the device.)

## M4: 7th Heaven mod compatibility
7th Heaven mods target the PC exe. Most are data (textures, models, audio, lgp/kernel/scene
edits) and carry over unchanged; the hard cases are hext patches that change x86 code
(already compiled to ARM64 here) and Windows DLL plugins.

How PC 7th Heaven applies mods, and what replaces each part here:
- IRO archives + `mod.xml` (priority order, config options, conditional folders, runtime
  variables): reimplemented in C. IROs read in place via mmap, never extracted (texture
  packs exceed 10 GB).
- 7thWrapper (hooks Win32 file reads to swap in IRO files): replaced by a union file system
  in our own Win32 file layer (`CreateFileA` etc.), which we compile, so no hooking.
- FFNx's override folders (`mod_path`, `direct_mode_path`, `hext_patching_path`,
  `external/FFNx/src/cfg.cpp`): the union file system presents enabled IROs as these
  folders.
- `mod.xml` runtime variables read 32-bit game addresses: add the guest arena base.
- hext patches into `.data`/`.rdata`: work as is (guest memory keeps those sections).
- hext patches into `.text`: the patched bytes are never executed. Plan: functions whose
  code a patch touches run through an x86 interpreter over guest memory (hooked via the
  existing `RECOMP_ENTER` entry overrides); popular patches also get hand-written native
  versions behind toggles. Re-lifting on device is out of scope.
- DLL plugins (.NET or native x86): not supported; popular ones ported natively case by case.

Order:
1. Survey tool: scan a real 7th Heaven library (IROs, `mod.xml`, hext) and report the share
   of data mods, hext data vs code writes, and DLL plugins. Sizes the hext-on-code work.
2. IRO reader + `mod.xml` parser (priority, config options, conditions).
3. Union file system feeding FFNx's override folders and the Win32 file layer. Covers the
   M4 exit test (IFRIT + Ninostyle Chibi).
4. hext: data writes, then the interpreter fallback for code writes.
5. Launcher: import IROs through the Android file picker (scoped storage) into app storage,
   later the Qhimm catalog; each mod shows a status: works / runs interpreted / unsupported.

## M5b: 120 FPS battles (implemented; needs the Thor to verify)
Mode 4 runs battles at 120 FPS (battle multiplier 8). FFNx's battle maths is written for
x2/x4. Comparing its battle patches at x2 and x4 (analysis/ffnx_manifests) showed what breaks
at x8; each case is handled in tools/ffnx_edit_android.py and src/ffnx_port:
- inexact per-frame steps (6/8, the battle menu's 4/8 sub-step count, ...): stepped every
  frame to floor or floor+1 so that they add up exactly (patch.h patch_divide_code ->
  ffnx_recomp::step_fraction, ticked from ff7_limit_fps). The values a site can hold go to
  the manifest as "code_variants", and run_lift.py lifts each one.
- shift counts `c -/+ m/2` use log2 m. 0x5BD22A/0x5BD251 (`shl reg, 2`, a shift of -1 at
  x8) alternate between `shl reg, 0` and `and reg, 0`.
- the battle-enter camera wait (byte, 61*8 overflows) keeps the x4 count and its
  decrement (battle_sub_42D992 + 0x1B) alternates 1 / 0.
- a summon wait multiplied as a byte is a 16-bit immediate: multiplied as a word at x8.
- display-damage bounce: a 120 FPS table. Index maths (`4 / m`, the action-string shift)
  is rewritten.
- left at FFNx's rounding: `20 / m` in Ifrit's movement, camera divides of script values.
Needs on the Thor: refresh_manifests.sh with mode 4 (collects the stepped variants), re-lift.
Until then a stepped site whose bytes have no lifted variant runs its original instruction.
Exit test: every battle animation runs at the same wall-clock speed at 60 and 120 FPS,
checked side by side (attack, magic, summon, limit, enemy deaths, damage numbers, ATB).

## M5w: Widescreen (Thor and other 16:9 handhelds)
The Thor's top screen is 1920x1080 (16:9); FF7 renders 4:3 (pillarboxed today).
- FFNx's widescreen mode (`aspect_ratio`, `enable_uncrop`, `widescreen.cpp`): 3D scenes
  (battle, world map, field models) render at 16:9 and the viewport is widened through
  code patches. Those patches are live now (pcrecomp patch 0017: patched-code variants and
  live dword operands; the viewport globals sit in the guest static block), so the work is
  collecting a widescreen manifest, re-lifting, and testing each mode.
- Field backgrounds are 4:3 pre-rendered art: FFNx's per-field widescreen config (scroll
  limits, "uncrop") plus the community widescreen background packs (via the M4 mod system)
  fill the sides; fields without either stay pillarboxed or zoom (option).
- Menus and battle HUD: centred at 4:3 or stretched to 16:9 (FFNx option), per the user's
  choice in the launcher.
- FMVs: 4:3 letterboxed by default; 16:9 movie packs (mods) where installed.
- Exit test: Sector 1 station, the first battle, the world map and the main menu at 16:9
  on the Thor without stretched 3D.

## M7: Thor dual-screen companion
The AYN Thor reports its top screen as the primary display and the 3.92" bottom touch
screen as an external display. Dual-screen Thor projects use the bottom screen as a live
companion rather than a second copy of the game: Cemu/melonDS forks (Wii U GamePad, DS touch
screen), native ports such as Fallout 2 CE (HUD, automap, Pip-Boy) and the RTS ports (touch
command deck), and EmuLnk-style dashboards that read emulator memory (it lists FF7 for
emulated versions). This port reads the game's own structures directly (savemap, party,
field/world state), so no memory scanning is needed.

- Display: Android DisplayManager -> a Presentation on the secondary display with its own
  SurfaceView -> a second ANativeWindow -> a second bgfx frame buffer (createFrameBuffer(nwh))
  drawn in its own view after the game's frame. Falls back to single screen when there is
  no second display or the user turns it off.
- UI: imgui (already part of FFNx for its devtools) with touch input from the bottom screen.
- Panels (touch tabs), all live from game memory:
  - Party: HP/MP bars, Limit gauge and level, status ailments, ATB in battle.
  - Map: field location name; world map with the player's (and vehicle's) position.
  - Battle: enemies with HP (as the game's Sense would reveal, configurable), turn order.
  - Command deck: big touch buttons for Menu, PHS, Save (where allowed), fast-forward,
    screenshot; the game keeps all physical controls.
  - Materia/inventory summary; play time and gil.
- Battle command screen (user request 2026-11-07): in battle the game's bottom command
  window (the party list with HP/MP/Limit/ATB and the Attack/Magic/Materia/Item menus and
  their sub-lists) is drawn on the bottom screen instead of over the battle, so the top
  screen shows the battle and its animations full-frame. How: FFNx already draws the battle
  menu through the game's own 2D window calls; those draw calls are tagged while the battle
  menu is being drawn and routed to the companion frame buffer (its own view and ortho
  projection at the bottom screen's 1240x1080), and the top screen's battle camera is
  given the freed vertical space (the 3D viewport extends over where the window was).
  Selections work with the pad as today, and by touch on the bottom screen (touch -> the
  same menu-cursor input the pad drives). Falls back to the normal on-screen window with
  one screen or when turned off.
- Options in the launcher (M4): which panels, which screen the game uses, brightness of the
  bottom screen, "bottom screen off" for battery.

## M6: RetroAchievements
- RetroAchievements has a FF7 set for the PlayStation version only; its conditions read
  PS1 RAM addresses. This port runs the PC executable, whose memory layout differs.
- Plan: integrate RA's official `rcheevos` library (rc_client API) and evaluate the PS1 set
  against a *virtual PS1 RAM*: blocks whose structure is shared by both versions (the
  savemap: party, items, materia, story progress) mirrored at their PS1 addresses each
  frame. Achievements that read battle/field-engine RAM need per-address translation or
  will not work; report which ones.
- Online play needs RA's approval of this client: its own user agent (never another
  emulator's), the public-history requirement in their hardcore-compliance rules, and no
  spoofing of the PS1 disc hash. Develop against a local set first; apply to RA with a
  working build. Do not connect to RA servers before approval.

## Ground truth
Original `ff7_en.exe` + FFNx on Windows. Emulator behaviour is never evidence.

## Rules
- Fix the toolkit (patches under `patches/`), never the generated code.
- `game/`, `analysis/`, `generated/`, `external/` are never committed.
- Every behaviour change gets an A/B environment toggle; no silent fallbacks.
