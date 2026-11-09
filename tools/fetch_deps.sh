#!/usr/bin/env bash
# Fetch dependencies of the mod overlay into external/ (gitignored), pinned:
#   external/deps/lzma      LZMA SDK decoder (public domain), 7-Zip 24.09 C sources
#   external/7th-Heaven     7th Heaven 4.6.1 (MS-PL): reference for the IRO / mod.xml formats
#   external/deps/*        FFNx's libraries (SDL3, bgfx helpers, audio codecs, ...), pinned by commit
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
# FFNx's libraries (cmake/ffnx.cmake), each at the exact commit this port builds with. Only that
# commit is fetched; patches/<name>/*.patch (if any) are applied after the checkout.
pin() {   # pin <dir name> <url> <commit>
  local d="$ROOT/external/deps/$1"
  if [ ! -d "$d/.git" ] || [ "$(git -C "$d" rev-parse HEAD 2>/dev/null)" != "$3" ]; then
    rm -rf "$d"; mkdir -p "$d"
    git -C "$d" init -q
    git -C "$d" remote add origin "$2"
    git -C "$d" fetch -q --depth 1 origin "$3"
    git -C "$d" -c advice.detachedHead=false checkout -q FETCH_HEAD
    for p in "$ROOT/patches/$1"/*.patch; do
      [ -f "$p" ] || continue
      git -C "$d" apply "$p"
      echo "  applied $1/$(basename "$p")"
    done
    echo "$1 @ ${3:0:9}"
  fi
}
pin SDL3                https://github.com/libsdl-org/SDL.git                829a65d769d935c4852f8159e964312c0957260a   # release-3.4.18
pin cgltf               https://github.com/jkuhlmann/cgltf.git               360db1a95480fe102ae9c69b27c5d101167ff5ba   # v1.15
pin cmrc                https://github.com/vector-of-bool/cmrc.git           952ffddba731fc110bd50409e8d2b8a06abbd237
pin highly_experimental https://github.com/julianxhokaxhiu/highly_experimental.git 8a045a8619116ce2b4c11387f527cc48636ef7a9
pin imgui               https://github.com/ocornut/imgui.git                 01380c579715e62fb9a8d6ec0502c4ea83bfde6e   # v1.92.9
pin libpng              https://github.com/pnggroup/libpng.git               cd952f49f95bb27154ae77dbb103032d95f6e580   # v1.6.59
pin lz4                 https://github.com/lz4/lz4.git                       ebb370ca83af193212df4dcbadcc5d87bc0de2f0   # v1.10.0
pin ogg                 https://github.com/xiph/ogg.git                      e1774cd77f471443541596e09078e78fdc342e4f   # v1.3.5
pin openpsf             https://github.com/julianxhokaxhiu/openpsf.git       9b832c44ab57084f5e96aa6b78eb3a68e77e0cd9
pin psflib              https://github.com/julianxhokaxhiu/psflib.git        93d14f05f5943ca5437378a731be60f8525366be
pin pugixml             https://github.com/zeux/pugixml.git                  c8033ce9d039e7f9d134877c363397b3cfe20816
pin soloud              https://github.com/julianxhokaxhiu/soloud.git        73cea78c4b33bcf6e2f0477c491cc02cef2652e0
pin tomlplusplus        https://github.com/marzer/tomlplusplus.git           30172438cee64926dc41fdd9c11fb3ba5b2ba9de   # v3.4.0
pin vgmstream           https://github.com/vgmstream/vgmstream.git           71e2361042531fe767fb98300cf8c1ee95e539a0   # r2117
pin vorbis              https://github.com/xiph/vorbis.git                   0657aee69dec8508a0011f47f3b69d7538e9d262   # v1.3.7
pin xxhash              https://github.com/Cyan4973/xxHash.git               e626a72bc2321cd320e953a0ccf1584cad60f363   # v0.8.3
echo "deps ok"
