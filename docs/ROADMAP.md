# FF7Recomp roadmap

Goal: FINAL FANTASY VII (Steam) statically recompiled from the 32-bit 1.02 `ff7_en` exe to native ARM64 Android, rendered by FFNx (bgfx/Vulkan), shipped as one APK whose entry point is a 7th Heaven-style mod launcher. Target device: AYN Thor (Snapdragon 8 Gen 2).

Each milestone ends with its exit test, a commit and a report. One milestone at a time.

| # | Milestone | Exit test |
|---|---|---|
| M0 | Inputs and toolchain | `tools/check_inputs.py` passes; pinned pcrecomp builds |
| M1 | Lift and boot on Windows x64 host | Title screen through statically linked FFNx |
| M2 | Playable on Windows | New game to Sector 7, no divergence from original exe + FFNx |
| M3 | Android port | Same run on the Thor, steady frame rate |
| M4 | Mods + launcher APK | IFRIT + Ninostyle Chibi installed in launcher, visible in game on the Thor |
| M5 | Polish | Frame pacing, widescreen, save states, controller-driven launcher |

## Ground truth
Original `ff7_en.exe` + FFNx on Windows. Emulator behaviour is never evidence.

## Rules
- Fix the toolkit (patches under `patches/`), never the generated code.
- `game/`, `analysis/`, `generated/`, `external/` are never committed.
- Every behaviour change gets an A/B environment toggle; no silent fallbacks.
