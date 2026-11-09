#!/usr/bin/env bash
# Where does the frame rate cap come from? Runs the game twice in the 120 FPS mode, into
# the opening field (Start skips the FMV), and reports for each run: the display's active
# refresh rate and our layer's frame-rate vote from SurfaceFlinger, and FFNx's FPS counter
# (screenshot). Run 1 with vsync, run 2 without: 120 without vsync but 60 with it means the
# present path (display mode / swapchain pacing) is the cap, not the game.
# usage: bash tools/fps_probe.sh
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"   # ADB
export MSYS_NO_PATHCONV=1
key() { "$ADB" shell input keyevent --longpress "KEYCODE_$1"; }
install=""
for vsync in true false; do
  SHOW_FPS=true FPS_LIMITER=4 bash "$ROOT/tools/deploy_ffnx.sh" > /dev/null
  "$ADB" shell "sed -i 's/^enable_vsync = .*/enable_vsync = $vsync/' /sdcard/Android/data/com.ff7recomp/files/game/FFNx.toml"
  ( sleep 22; key BUTTON_START; sleep 6; key DPAD_UP; sleep 1; key BUTTON_B; sleep 12; key BUTTON_START
    sleep 20
    echo "=== vsync=$vsync"
    "$ADB" shell dumpsys SurfaceFlinger | grep -iE 'refresh-rate|activeMode|Active mode|frameRate|desired' | head -8
    "$ADB" exec-out screencap -p > "$ROOT/build/fps_vsync_$vsync.png" ) &
  bash "$ROOT/tools/run_app.sh" 70 $install > /dev/null 2>&1
  install="--no-install"
  wait
  "$ADB" shell am force-stop com.ff7recomp
done
echo "screens: build/fps_vsync_true.png build/fps_vsync_false.png (FPS counter top left)"
