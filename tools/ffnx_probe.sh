#!/usr/bin/env bash
# Compile FFNx sources for arm64 and summarise the errors (first N per file).
# usage: bash tools/ffnx_probe.sh "gl/texture.cpp;cfg.cpp" [max-errors]
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"
SDK="$ANDROID_SDK_ROOT"
CM="$SDK/cmake/3.30.5/bin"
OUT="$ROOT/build/ffnx-probe"
SRCS="${1:-gl/texture.cpp}"
MAX="${2:-40}"
"$CM/cmake.exe" -S "$ROOT/tests/ffnx_probe" -B "$OUT" -G Ninja -DCMAKE_MAKE_PROGRAM="$CM/ninja.exe" \
  -DCMAKE_TOOLCHAIN_FILE="$SDK/ndk/28.2.13676358/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-29 -DANDROID_STL=c++_static \
  -DCMAKE_BUILD_TYPE=Release "-DFFNX_SOURCES=$SRCS" > "$OUT.configure.log" 2>&1 || { tail -20 "$OUT.configure.log"; exit 1; }
"$CM/cmake.exe" --build "$OUT" --target ffnx -- -k 0 -j 8 > "$OUT.log" 2>&1
rc=$?
grep -E 'error:' "$OUT.log" | sed -E 's#.*/external/FFNx/src/##; s#.*/src/ffnx_port/##' | sort | uniq -c | sort -rn | head -"$MAX"
echo "errors: $(grep -c 'error:' "$OUT.log")  (full log: build/ffnx-probe.log)  rc=$rc"
