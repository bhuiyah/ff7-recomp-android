#!/usr/bin/env bash
# build_all.sh: from a fresh clone to the APK, every step in order. Needs no game files: the APK
# carries no game code, and the player's device builds the game from their own copy of FF7
# (GameBuild.java, with the kit from tools/build_device_kit.sh).
# Run from Git Bash on Windows. Each step is skipped when its output is already there, so
# re-running after a failure carries on where it stopped.
#
# usage: bash tools/build_all.sh
#
# Needs (see README.md): Git, Python 3, the Android SDK with NDK 28.2.13676358 and CMake 3.30.5,
# JDK 17, LLVM for Windows, Visual Studio 2022 Build Tools (C++). tools/env.sh finds them in
# their standard places or from the environment.
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
command -v ninja >/dev/null || export PATH="$SDK_CMAKE_DIR:$PATH"   # the host tablegen build

step "1/6 toolkits: pcrecomp, FFNx, bgfx (pinned, patched)"
SETUP_MARK="external/.setup-$(git rev-parse --short HEAD:patches 2>/dev/null || echo x)-$(git rev-parse --short HEAD:tools/setup.ps1 2>/dev/null || echo x)"
if [ ! -f "$SETUP_MARK" ]; then   # (re)run when it never finished, or the patches changed
  powershell.exe -NoProfile -ExecutionPolicy Bypass -File "$(cygpath -w "$ROOT/tools/setup.ps1")"
  rm -f external/.setup-*; touch "$SETUP_MARK"
fi

step "2/6 libraries: LZMA, rcheevos, capstone, Python for Android, ... (pinned)"
bash tools/fetch_deps.sh

step "3/6 FFmpeg for Android (movies)"
[ -f build/ffmpeg-android/lib/libavcodec.a ] || bash tools/build_ffmpeg_android.sh

step "4/6 bgfx shader compiler and FFNx's shaders"
ls external/bgfx/.build/win64_vs2022/bin/shadercRelease.exe >/dev/null 2>&1 || bash tools/build_shaderc.sh
[ -d build/shaders ] && [ -n "$(ls build/shaders 2>/dev/null)" ] || bash tools/compile_shaders.sh
bash tools/stage_apk_assets.sh

step "5/6 the on-device build kit: clang for Android (about 40 minutes, once), the runtime, the lifter"
bash tools/build_device_kit.sh

step "6/6 APK (Gradle)"
[ -f android/local.properties ] || printf 'sdk.dir=%s\n' "$(cygpath -m "$ANDROID_SDK_ROOT")" > android/local.properties
(cd android && ./gradlew assembleDebug --console=plain)

echo
echo "APK: $ROOT/android/app/build/outputs/apk/debug/app-debug.apk"
echo "Install it with: \"$ADB\" install -r \"$(cygpath -w "$ROOT/android/app/build/outputs/apk/debug/app-debug.apk")\""
echo "On the device: Game files > Find my FF7 folder, then wait for the build (once)."
