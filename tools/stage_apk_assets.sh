#!/usr/bin/env bash
# Stage FFNx's run-time files into build/apk-assets/ffnx (packed into the APK as assets/ffnx;
# the launcher installs them into the game folder): FFNx's data tables, its default FFNx.toml
# (the launcher's template) and the SPIR-V shaders from tools/compile_shaders.sh.
# usage: bash tools/stage_apk_assets.sh
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$ROOT/build/apk-assets/ffnx"
[ -f "$ROOT/build/shaders/FFNx.flat.vk.frag" ] || { echo "run tools/compile_shaders.sh first"; exit 1; }
rm -rf "$OUT"; mkdir -p "$OUT/shaders"
cp "$ROOT"/external/FFNx/misc/FFNx*.toml "$OUT/"
cp "$ROOT"/build/shaders/* "$OUT/shaders/"
echo "staged $(ls "$OUT" | wc -l) entries, $(ls "$OUT/shaders" | wc -l) shaders"
