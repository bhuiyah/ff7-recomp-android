#!/usr/bin/env bash
# Link the FFNx core into an executable and collect the undefined symbols (demangled) in
# build/ffnx-link/undefined.txt. usage: bash tools/ffnx_link_probe.sh
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SDK=/c/Users/%USERNAME%/AppData/Local/Android/Sdk
CM="$SDK/cmake/3.30.5/bin"
OUT="$ROOT/build/ffnx-link"
"$CM/cmake.exe" -S "$ROOT/tests/ffnx_probe" -B "$OUT" -G Ninja -DCMAKE_MAKE_PROGRAM="$CM/ninja.exe" \
  -DCMAKE_TOOLCHAIN_FILE="$SDK/ndk/28.2.13676358/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-29 -DANDROID_STL=c++_static \
  -DCMAKE_BUILD_TYPE=Release -DFFNX_LINK_PROBE=ON > "$OUT.configure.log" 2>&1 || { tail -20 "$OUT.configure.log"; exit 1; }
"$CM/cmake.exe" --build "$OUT" --target ffnx_link_probe -- -k 0 -j 8 > "$OUT.log" 2>&1
rc=$?
grep -E 'error:' "$OUT.log" | grep -v 'undefined symbol' | head -20
grep -oE 'undefined symbol: .*' "$OUT.log" | sed 's/undefined symbol: //' | sort -u > "$OUT/undefined.txt"
echo "undefined symbols: $(wc -l < "$OUT/undefined.txt")  rc=$rc  (build/ffnx-link/undefined.txt)"
