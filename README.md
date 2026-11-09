# FF7Recomp

FINAL FANTASY VII (PC, 1.02 US) statically recompiled to native ARM64 Android, rendered by
[FFNx](https://github.com/julianxhokaxhiu/FFNx) on Vulkan, with a 7th Heaven-style mod launcher.
Built for the AYN Thor (dual screen), and meant to run on other Android handhelds and phones.

> **This repository contains no game code or data.** You need your own copy of FINAL FANTASY VII
> (the Steam release). The build recompiles **your** `ff7_en.exe` on your PC; nothing derived from
> the game is ever published here, and no prebuilt APK is provided.
> Not affiliated with or endorsed by Square Enix. FINAL FANTASY is a trademark of Square Enix.

## What you get

- The game running natively (no emulation, no Wine/FEX), at 30, 60 or 120 FPS.
- A launcher: import your game files, browse and install mods from the 7th Heaven catalogs
  (.iro, .zip, .7z), set mod order and options, settings, save states, achievements.
- 16:9 widescreen, controller support (A confirms, B cancels), save states.
- On the AYN Thor's bottom screen: party status, location, a touch deck (menu, save/load state,
  speed, random battles, battle enhancement).
- Game boosters as in the console re-releases (speed, no random battles, battle enhancement).
- Local achievements (RetroAchievements format; no online account until RetroAchievements
  approves this port).

## Building

The build runs on **Windows** (Git Bash). It takes a while the first time (the function
catalog takes about 15 minutes, compiling the generated code longer).

### 1. Install the tools

| Tool | Notes |
|---|---|
| [Git for Windows](https://git-scm.com/download/win) | provides Git Bash |
| [Python 3](https://www.python.org/downloads/) with `capstone` | `py -m pip install capstone` |
| [Android Studio](https://developer.android.com/studio) or the command-line SDK | SDK Manager → SDK Tools: **NDK (Side by side) 28.2.13676358**, **CMake 3.30.5**, Android SDK Platform-Tools |
| JDK 17 | Android Studio's bundled JBR works, or [Microsoft OpenJDK 17](https://learn.microsoft.com/java/openjdk/download) |
| [LLVM for Windows](https://github.com/llvm/llvm-project/releases) | clang for build-time host tools |
| [Visual Studio 2022 Build Tools](https://visualstudio.microsoft.com/downloads/) | "Desktop development with C++" (builds bgfx's shader compiler) |

`tools/env.sh` finds these in their standard install locations. If yours are elsewhere, set
`ANDROID_SDK_ROOT`, `JAVA_HOME`, `LLVM_DIR` or `MSBUILD`.

### 2. Get your game files

Install FINAL FANTASY VII from Steam. You need its install folder, the one that contains
`ff7\resources\ff7_1.02\ff7_en` and `ff7\workingdir\data`.

### 3. Build

In Git Bash:

```bash
git clone <this repository> FF7Recomp
cd FF7Recomp
bash tools/build_all.sh "C:/Program Files (x86)/Steam/steamapps/common/FINAL FANTASY VII"
```

`build_all.sh` runs every step in order: the pinned toolkits (pcrecomp, FFNx, bgfx) and their
patches, the libraries, importing your exe, the function catalog, the recompile to C, FFmpeg,
shaders, and the APK. If a step fails, fix the cause and run it again; finished steps are skipped.

The APK ends up in `android/app/build/outputs/apk/debug/app-debug.apk`.

### 4. Install and set up on the device

1. Install the APK (`adb install -r app-debug.apk`, or copy it over and open it).
2. Copy your FINAL FANTASY VII install folder to the device (any folder you can browse to).
3. Open FF7Recomp → **Game files** → **Import from FF7 install folder…** and pick that folder.
4. Press **Play**.

The exe you import on the device must be the same one the APK was built from.

## Mods

**Get mods** lists the Qhimm and Sega 7th Heaven catalogs. Install, then enable and order them
under **Mods** (higher in the list wins). Large mods download and unpack on the device; you
can keep using the launcher meanwhile. You can also add your own `.iro` files or mod folders.

## Reporting problems

Please open an issue with your device, Android version, the mods you have enabled, and what
happened. Logs are in `Android/data/com.ff7recomp/files/` (`ff7.log`) and
`.../files/game/FFNx.log`.

## Known limitations

- Only the Steam release's 1.02 US exe (`ff7_en`) is supported.
- Movies decode on the CPU (no hardware decoding yet): upscaled 30 FPS movie mods work on the
  Thor but run warmer.
- The 120 FPS mode is experimental.
- RetroAchievements: offline only, until the port is approved by RetroAchievements.

## Credits and licenses

FF7Recomp is GPL-3.0 (it links FFNx). It builds on:
[pcrecomp](https://github.com/sp00nznet/pcrecomp) (MIT),
[nfsu2-recompiled](https://github.com/codepdbh/nfsu2-recompiled) (MIT; Android host),
[FFNx](https://github.com/julianxhokaxhiu/FFNx) (GPL-3.0),
[bgfx/bx/bimg](https://github.com/bkaradzic/bgfx) (BSD-2),
[7th Heaven](https://github.com/tsunamods-codes/7th-Heaven) (MS-PL; mod formats),
[rcheevos](https://github.com/RetroAchievements/rcheevos) (MIT),
the [LZMA SDK](https://www.7-zip.org/sdk.html) (public domain),
[FFmpeg](https://ffmpeg.org/) (LGPL).
