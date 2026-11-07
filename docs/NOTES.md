# Notes

## 2026-11-07
- Project created. Plan: recompile `ff7\resources\ff7_1.02\ff7_en` (32-bit 1.02 US) with sp00nznet/pcrecomp, Android host from codepdbh/nfsu2-recompiled, renderer = FFNx built natively.
- Game source: `C:\Users\%USERNAME%\Downloads\7thHeavenAndroid\FF7\FINAL FANTASY VII` (not a Steam library). Imported with `tools/import_game.py`; `game/data` is a junction to `ff7\workingdir\data`.
- `ff7_en` (1.02 US, sha1 ac306ae92615af75ff36bba6347c67ca1284151d): x86-32, base 0x400000, entry 0x40B6E0, sections .text/.rdata/.data/.rsrc/.dotemu (import table lives in .dotemu, shorter on disk than in memory). No SteamStub. Imports WINMM, KERNEL32, USER32, GDI32, ole32, OPENGL32, DSOUND, DINPUT, DDRAW, MSACM32 and AF3DN.P (6 functions) directly.
- 2026 data layout: per-language kernel/battle/movies under `data/lang-xx/`; music in `music/` and `music_ogg/`.
- Toolchain: JDK 17 (`C:\Program Files\Microsoft\jdk-17.0.21.21-hotspot`), Android SDK at `%LOCALAPPDATA%\Android\Sdk` with NDK 28.2.13676358 (clang 19), CMake 3.30.5, build-tools 35, platform 35, adb 1.0.41. Python: pefile, unicorn, capstone installed.
- pcrecomp + 14 NFSU2 patches apply cleanly on 35548b8.
- Open: toolkit self-test (`tools/lift/difftest.py` with RECOMP_CC=clang under `source tools/vsenv.sh x64`) was blocked by the auto-mode classifier for running cloned code; needs the user's OK.
