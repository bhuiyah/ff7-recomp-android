#!/usr/bin/env bash
# Build the headless arm64 bring-up runner with the NDK. Prints progress; safe to re-run
# (Ninja only rebuilds what changed, so the 64 generated files compile once per variant).
# usage: bash tools/build_headless.sh [--trace]
#   --trace  separate build (build/headless-arm64-trace) with the lifted-function entry
#            ring on, so crashes and STOPs print the path that led there
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SDK="${ANDROID_SDK_ROOT:-/c/Users/%USERNAME%/AppData/Local/Android/Sdk}"
NDK="$SDK/ndk/28.2.13676358"
CMAKE="$SDK/cmake/3.30.5/bin/cmake.exe"
NINJA="$SDK/cmake/3.30.5/bin/ninja.exe"
OUT="$ROOT/build/headless-arm64"
TRACE=OFF
if [ "${1:-}" = "--trace" ]; then OUT="$ROOT/build/headless-arm64-trace"; TRACE=ON; fi

if [ ! -f "$OUT/build.ninja" ]; then
  "$CMAKE" -S "$ROOT/tests/headless" -B "$OUT" -G Ninja \
    -DCMAKE_MAKE_PROGRAM="$NINJA" \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-29 \
    -DCMAKE_BUILD_TYPE=RelWithDebInfo -DFF7_TRACE=$TRACE
fi
"$CMAKE" --build "$OUT" -- -j 8
ls -la "$OUT/ff7_headless"
