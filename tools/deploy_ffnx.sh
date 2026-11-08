#!/usr/bin/env bash
# Push FFNx's run-time files to the game folder on the device (next to ff7_en.exe):
#   FFNx.toml          FFNx's defaults with the Android settings below
#   FFNx.*.toml        FFNx's data tables (music, SFX, lighting, ...)
#   shaders/           SPIR-V from tools/compile_shaders.sh
# usage: bash tools/deploy_ffnx.sh   (adb device connected)
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ADB="/c/Users/%USERNAME%/AppData/Local/Android/Sdk/platform-tools/adb.exe"
DEV=/sdcard/Android/data/com.ff7recomp/files/game
STAGE="$ROOT/build/ffnx-runtime"
MISC="$ROOT/external/FFNx/misc"

[ -f "$ROOT/build/shaders/FFNx.flat.vk.frag" ] || { echo "run tools/compile_shaders.sh first"; exit 1; }
rm -rf "$STAGE"; mkdir -p "$STAGE/shaders"
cp "$MISC"/FFNx.*.toml "$STAGE/"
cp "$ROOT"/build/shaders/* "$STAGE/shaders/"
# Android settings: Vulkan (the only backend built), the device's own display mode.
sed -e 's/^renderer_backend = .*/renderer_backend = 5/' \
    -e 's/^fullscreen = .*/fullscreen = true/' \
    -e "s/^show_fps = .*/show_fps = ${SHOW_FPS:-false}/" \
    -e "s/^ff7_fps_limiter = .*/ff7_fps_limiter = ${FPS_LIMITER:-1}/" \
    -e "s/^aspect_ratio = .*/aspect_ratio = ${ASPECT_RATIO:-2}/" \
    -e "s/^enable_uncrop = .*/enable_uncrop = ${UNCROP:-true}/" \
    "$MISC/FFNx.toml" > "$STAGE/FFNx.toml"
# adb cannot create directories under Android/data: the shell makes them first.
MSYS_NO_PATHCONV=1 "$ADB" shell mkdir -p "$DEV/shaders" "$DEV/save"
MSYS_NO_PATHCONV=1 "$ADB" push "$(cygpath -w "$STAGE")/." "$DEV/" > /dev/null
# adb-pushed files belong to the shell user: let the app (the launcher rewrites FFNx.toml) write them
MSYS_NO_PATHCONV=1 "$ADB" shell "chmod -R a+rwX $DEV" > /dev/null 2>&1 || true
echo "pushed $(ls "$STAGE" | wc -l) entries + $(ls "$STAGE/shaders" | wc -l) shaders to $DEV"
