#!/usr/bin/env bash
# build_all.sh: from a fresh clone and your own FF7 to the APK, every step in order.
# Run from Git Bash on Windows. Each step is skipped when its output is already there, so
# re-running after a failure carries on where it stopped.
#
# usage: bash tools/build_all.sh "<FINAL FANTASY VII install folder>"
#   The install folder is the Steam release's (the one holding ff7\resources\ff7_1.02\ff7_en);
#   it is needed once, to import the exe and link the data into game/.
#
# Needs (see README.md): Git, Python 3 with capstone (pip install capstone), the Android SDK
# with NDK 28.2.13676358 and CMake 3.30.5, JDK 17, LLVM for Windows, Visual Studio 2022
# Build Tools (C++). tools/env.sh finds them in their standard places or from the environment.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/tools/env.sh"
cd "$ROOT"
step() { printf '\n== %s\n' "$*"; }
need() { [ -e "$1" ] || { echo "missing: $1 ($2)" >&2; exit 1; }; }

need "$NDK_BIN/clang.exe" "Android NDK $FF7_NDK_VERSION: SDK Manager > SDK Tools > NDK (Side by side)"
need "$SDK_CMAKE_DIR/cmake.exe" "Android SDK CMake 3.30.5: SDK Manager > SDK Tools > CMake"
need "$HOST_CC" "LLVM for Windows (clang): https://github.com/llvm/llvm-project/releases"
[ -n "${JAVA_HOME:-}" ] || { echo "missing: JDK 17 (set JAVA_HOME)" >&2; exit 1; }
[ -n "${MSBUILD:-}" ] || { echo "missing: Visual Studio 2022 Build Tools with C++ (or set MSBUILD)" >&2; exit 1; }
py -I -c "import capstone" 2>/dev/null || { echo "missing: Python capstone (py -m pip install capstone)" >&2; exit 1; }

step "1/9 toolkits: pcrecomp, FFNx, bgfx (pinned, patched)"
SETUP_MARK="external/.setup-$(git rev-parse --short HEAD:patches 2>/dev/null || echo x)-$(git rev-parse --short HEAD:tools/setup.ps1 2>/dev/null || echo x)"
if [ ! -f "$SETUP_MARK" ]; then   # (re)run when it never finished, or the patches changed
  powershell.exe -NoProfile -ExecutionPolicy Bypass -File "$(cygpath -w "$ROOT/tools/setup.ps1")"
  rm -f external/.setup-*; touch "$SETUP_MARK"
fi

step "2/9 libraries: LZMA, rcheevos, pugixml, ... (pinned)"
bash tools/fetch_deps.sh

step "3/9 your game files"
if [ ! -f game/ff7_en.exe ]; then
  [ $# -ge 1 ] || { echo 'game/ff7_en.exe is missing: pass your FINAL FANTASY VII install folder' >&2; exit 1; }
  py -I tools/import_game.py "$1"
fi
py -I tools/check_inputs.py

step "4/9 function catalog (about 15 minutes, once)"
py -I tools/ffnx_seeds.py
[ -f analysis/catalog.json ] || py tools/run_lift.py catalog game/ff7_en.exe analysis/seeds.json analysis/catalog.json

step "5/9 recompile the game to C (lift32, with FFNx's patch manifests)"
if [ ! -f generated/recomp/recomp_dispatch.c ]; then
  list=$(for f in config/ffnx_manifests/ffnx_manifest_l*.json analysis/ffnx_manifests/hext_*.json; do
           [ -f "$f" ] && cygpath -m "$ROOT/$f"; done | tr '\n' ',' | sed 's/,$//')
  py tools/run_lift.py lift game/ff7_en.exe analysis/catalog.json generated/recomp --manifests "$list"
  py tools/gen_retn_table.py
fi

step "6/9 FFmpeg for Android (movies)"
[ -f build/ffmpeg-android/lib/libavcodec.a ] || bash tools/build_ffmpeg_android.sh

step "7/9 bgfx shader compiler and FFNx's shaders"
ls external/bgfx/.build/win64_vs2022/bin/shadercRelease.exe >/dev/null 2>&1 || bash tools/build_shaderc.sh
[ -d build/shaders ] && [ -n "$(ls build/shaders 2>/dev/null)" ] || bash tools/compile_shaders.sh
bash tools/stage_apk_assets.sh

step "8/9 APK (Gradle; the native part compiles about 550 MB of generated C)"
[ -f android/local.properties ] || printf 'sdk.dir=%s\n' "$(cygpath -m "$ANDROID_SDK_ROOT")" > android/local.properties
(cd android && ./gradlew assembleDebug --console=plain)

step "9/9 done"
echo "APK: $ROOT/android/app/build/outputs/apk/debug/app-debug.apk"
echo "Install it with: \"$ADB\" install -r \"$(cygpath -w "$ROOT/android/app/build/outputs/apk/debug/app-debug.apk")\""
