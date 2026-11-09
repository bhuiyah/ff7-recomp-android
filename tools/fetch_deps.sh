#!/usr/bin/env bash
# Fetch dependencies of the mod overlay into external/ (gitignored), pinned:
#   external/deps/lzma      LZMA SDK decoder (public domain), 7-Zip 24.09 C sources
#   external/7th-Heaven     7th Heaven 4.6.1 (MS-PL): reference for the IRO / mod.xml formats
# usage: bash tools/fetch_deps.sh
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
L="$ROOT/external/deps/lzma"
mkdir -p "$L"
# the 7z reader too (src/android/un7z.c: the launcher unpacks .7z mods in native code)
for f in LzmaDec.c LzmaDec.h 7zTypes.h Precomp.h Compiler.h 7zWindows.h          7z.h 7zArcIn.c 7zBuf.c 7zBuf.h 7zCrc.c 7zCrc.h 7zCrcOpt.c 7zDec.c 7zStream.c 7zAlloc.c 7zAlloc.h          7zFile.c 7zFile.h Bcj2.c Bcj2.h Bra.c Bra.h Bra86.c BraIA64.c CpuArch.c CpuArch.h Delta.c Delta.h          Lzma2Dec.c Lzma2Dec.h RotateDefs.h Alloc.h; do
  [ -f "$L/$f" ] || curl -sfL -o "$L/$f" "https://raw.githubusercontent.com/ip7z/7zip/24.09/C/$f"
done
if [ ! -d "$ROOT/external/7th-Heaven" ]; then
  git -c advice.detachedHead=false clone -q --depth 1 --branch 4.6.1 https://github.com/tsunamods-codes/7th-Heaven.git "$ROOT/external/7th-Heaven" \
    || git clone -q --depth 1 https://github.com/tsunamods-codes/7th-Heaven.git "$ROOT/external/7th-Heaven"
fi
# rcheevos (MIT): RetroAchievements' achievement evaluation library (src/ffnx_port/achievements.cpp)
if [ ! -d "$ROOT/external/deps/rcheevos" ]; then
  git -c advice.detachedHead=false clone -q --depth 1 --branch v12.5.0 https://github.com/RetroAchievements/rcheevos.git "$ROOT/external/deps/rcheevos"
fi
echo "deps ok"
