#!/usr/bin/env bash
# Lift the code of 7th Heaven mods' hext patches into the APK (docs/ROADMAP.md M4 hext).
# Hext writes into game data work on the device as they are; writes into the game's code only
# run where the lift has the patched code. This writes one manifest per mod (and per profile
# given) into analysis/ffnx_manifests/hext_*.json; tools/refresh_manifests.sh (or a re-lift
# with --manifests) then lifts them, and the device runs a mod's patched code whenever the
# mod is enabled. On the device, [hext] lines in logcat name every code write that has no
# lifted counterpart.
#
# usage: bash tools/hext_manifest.sh <profile.xml | mod.iro | mod folder>...
#   e.g. the 7th Heaven library on this PC:  bash tools/hext_manifest.sh /c/Games/7th\ Heaven/mods/*.iro
#        the profile on the Thor:           adb pull .../files/mods/profile.xml && bash tools/hext_manifest.sh profile.xml
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$ROOT/build/hext-manifest"
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"
CC="$HOST_CC"
CXX="$HOST_CXX"
[ $# -ge 1 ] || { sed -n 2,13p "$0"; exit 2; }
mkdir -p "$OUT" "$ROOT/analysis/ffnx_manifests"
if [ ! -x "$OUT/hext_manifest.exe" ] || [ -n "$(find "$ROOT/src/mods" "$ROOT/tools/hext_manifest.cpp" -newer "$OUT/hext_manifest.exe")" ]; then
  (cd "$OUT" && "$CC" -c -O1 -I"$ROOT/src/mods" -I"$ROOT/external/deps/lzma" "$ROOT/src/mods/lzs.c" "$ROOT/external/deps/lzma/LzmaDec.c" && \
   "$CXX" -std=c++20 -O1 -D_CRT_SECURE_NO_WARNINGS -I"$ROOT/src/mods" -I"$ROOT/external/deps/lzma" -I"$ROOT/external/deps/pugixml/src" \
     "$ROOT/tools/hext_manifest.cpp" "$ROOT/src/mods/hext.cpp" "$ROOT/src/mods/iro.cpp" "$ROOT/src/mods/modinfo.cpp" \
     "$ROOT/src/mods/mods.cpp" "$ROOT/src/mods/runtimevar.cpp" "$ROOT/external/deps/pugixml/src/pugixml.cpp" lzs.o LzmaDec.o -o hext_manifest.exe)
fi
args=()
for a in "$@"; do args+=("$(cygpath -m "$a")"); done
"$OUT/hext_manifest.exe" "$(cygpath -m "$ROOT/game/ff7_en.exe")" "$(cygpath -m "$ROOT/analysis/ffnx_manifests")" "${args[@]}" 2> >(grep -v '^\[mods\]' >&2)
echo "next: bash tools/refresh_manifests.sh (Thor connected), or re-lift with these manifests"
