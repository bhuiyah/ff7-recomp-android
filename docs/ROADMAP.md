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
| M5 | Polish | Frame pacing, widescreen, save states, controller-driven launcher |
| M6 | RetroAchievements (user request 2026-11-07) | Local achievement set unlocks in game; then RA approval to go online |
| M7 | Thor dual-screen companion (user request 2026-11-07) | Bottom screen shows live party/map panels and a touch command deck while the game runs on top |

(M1–M3 changed order when the user chose the Thor as the test host: there is no
Windows-host stage; everything runs on the device.)

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
