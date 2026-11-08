#!/usr/bin/env bash
# Build and run the mod overlay tests on the PC (LLVM clang for Windows): generated test
# mods (tests/mods/make_test_mods.py) through src/mods. usage: bash tools/test_mods.sh
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$ROOT/build/mods-test"
CC="/c/Program Files/LLVM/bin/clang.exe"
CXX="/c/Program Files/LLVM/bin/clang++.exe"
rm -rf "$OUT"; mkdir -p "$OUT"
py -I "$ROOT/tests/mods/make_test_mods.py" "$OUT/data"
cd "$OUT"
"$CC" -c -O1 -I"$ROOT/src/mods" -I"$ROOT/external/deps/lzma" "$ROOT/src/mods/lzs.c" "$ROOT/external/deps/lzma/LzmaDec.c"
"$CXX" -std=c++20 -O1 -D_CRT_SECURE_NO_WARNINGS -I"$ROOT/src/mods" -I"$ROOT/external/deps/lzma" -I"$ROOT/external/deps/pugixml/src" \
  "$ROOT/tests/mods/test_mods.cpp" "$ROOT/src/mods/iro.cpp" "$ROOT/src/mods/modinfo.cpp" "$ROOT/src/mods/mods.cpp" "$ROOT/src/mods/runtimevar.cpp" "$ROOT/src/mods/hext.cpp" \
  "$ROOT/external/deps/pugixml/src/pugixml.cpp" lzs.o LzmaDec.o -o test_mods.exe
./test_mods.exe data 2>/dev/null
