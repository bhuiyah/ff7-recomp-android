#!/usr/bin/env bash
# Compile FFNx's shaders to SPIR-V for bgfx's Vulkan renderer, with FFNx's own lists and flags
# (external/FFNx/CMakeLists.txt, .cmake/compile_shaders.cmake), vk only.
# FFNx loads them at run time from <game>/<shader_path> (default "shaders"), named
# <shader>.<flat|smooth>.vk.<frag|vert>, so they are pushed with the game files.
# Output: build/shaders/  usage: bash tools/compile_shaders.sh
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SHADERC="$ROOT/external/bgfx/.build/win64_vs2022/bin/shadercRelease.exe"
SRC="$ROOT/external/FFNx/misc"
# FFNx includes <bgfx/bgfx_shader.sh>, as vcpkg installs it.
INC="$ROOT/build/shader_include"
mkdir -p "$INC/bgfx" && cp "$ROOT"/external/bgfx/src/bgfx_*.sh "$INC/bgfx/"
OUT="$ROOT/build/shaders"
FLAGS=(--platform android --profile spirv)
mkdir -p "$OUT"
w() { cygpath -w "$1"; }

SHADERS="FFNx FFNx.lighting FFNx.shadowmap FFNx.field.shadow FFNx.overlay FFNx.post FFNx.post.ntscj FFNx.blit FFNx.yuvmovie FFNx.yuvmovie.truecolor"
SKINNED="FFNx FFNx.lighting FFNx.shadowmap"
n=0
for s in $SHADERS; do
  for varying in flat smooth; do
    for stage in frag vert; do
      t=${stage:0:1}
      "$SHADERC" -i "$(w "$INC")" -f "$(w "$SRC/$s.$stage")" -o "$(w "$OUT/$s.$varying.vk.$stage")" \
        --type $t --varyingdef "$(w "$SRC/FFNx.varying.$varying.def.sc")" "${FLAGS[@]}" \
        || { echo "FAILED: $s.$varying.vk.$stage"; exit 1; }
      n=$((n+1))
    done
  done
done
for s in $SKINNED; do
  "$SHADERC" -i "$(w "$INC")" -f "$(w "$SRC/$s.vert")" -o "$(w "$OUT/$s.skinned.smooth.vk.vert")" \
    --type v --varyingdef "$(w "$SRC/FFNx.varying.smooth.def.sc")" --define SMOOTH_SKINNING "${FLAGS[@]}" \
    || { echo "FAILED: $s.skinned.smooth.vk.vert"; exit 1; }
  n=$((n+1))
done
echo "$n shaders -> build/shaders"
