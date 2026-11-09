#!/usr/bin/env bash
# Battle speed per frame-rate mode: walks into the first battle (as tools/play_to_battle.sh)
# and times, from the battle windows appearing, how long Cloud's ATB ("TIME") gauge takes to
# refill after Cloud's first attack (empty -> full). It must not depend on the mode. One pixel per poll from a raw screencap (no image library).
# 4:3 layout (aspect 0): battle window fill at (500, 900); end of the TIME gauge at (1640, 805).
# usage: bash tools/battle_speed_probe.sh [modes = "1 3 4"]
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"   # ADB
export MSYS_NO_PATHCONV=1
MODES="${1:-1 3 4}"
key() { "$ADB" shell input keyevent --longpress "KEYCODE_$1"; }
# Title menu -> New Game. The cursor starts on New Game or on Continue (with saves): read the
# hand cursor's pixel beside "Continue?" (765, 515) and only then move up.
newgame() {
  local p; p=$("$ADB" shell "screencap | dd bs=1 skip=$((16 + (515 * 1920 + 765) * 4)) count=4 2>/dev/null | od -A n -t u1"); set -- $p
  if [ "${1:-0}" -gt 180 ] && [ "${2:-0}" -gt 180 ] && [ "${3:-0}" -gt 180 ]; then key DPAD_UP; sleep 1; fi
  key BUTTON_B
}
px() { "$ADB" shell "screencap | dd bs=1 skip=$((16 + ($2 * 1920 + $1) * 4)) count=4 2>/dev/null | od -A n -t u1"; }
ms() { date +%s%3N; }
wait_for() {      # wait_for <x> <y> <test on r g b> <timeout ms>
  local t0 now; t0=$(ms)
  while :; do
    now=$(ms); [ $((now - t0)) -gt "$4" ] && return 1
    set -- "$1" "$2" "$3" "$4" $(px "$1" "$2")
    local r=${5:-0} g=${6:-0} b=${7:-0}
    eval "$3" && return 0
  done
}
for mode in $MODES; do
  SHOW_FPS=true FPS_LIMITER=$mode ASPECT_RATIO=0 UNCROP=false bash "$ROOT/tools/deploy_ffnx.sh" > /dev/null   # 4:3: the pixel positions below
  "$ADB" shell am force-stop com.ff7recomp
  "$ADB" shell input keyevent KEYCODE_WAKEUP
  "$ADB" shell am start -n com.ff7recomp/.GameActivity > /dev/null
  sleep 22; key BUTTON_START; sleep 6; newgame; sleep 12; key BUTTON_START
  sleep 34; key BUTTON_B; sleep 3; key BUTTON_B; sleep 3
  for i in $(seq 1 24); do key DPAD_UP; done
  if ! wait_for 500 900 '[ $b -gt 100 ] && [ $r -lt 60 ] && [ $g -lt 60 ]' 60000; then
    echo "mode $mode: battle not reached"; continue
  fi
  # The first gauge starts part-full at random (its seed depends on the frames spent in the
  # field, which differs per mode), so time a whole cycle: attack, then empty -> full.
  if ! wait_for 1640 805 '[ $g -gt 150 ] && [ $b -lt 230 ]' 60000; then echo "mode $mode: first turn not reached"; continue; fi
  sleep 1; key BUTTON_B; sleep 1; key BUTTON_B                           # Attack, the first target
  if ! wait_for 1640 805 '! { [ $g -gt 150 ] && [ $b -lt 230 ]; }' 20000; then echo "mode $mode: gauge did not empty"; continue; fi
  t0=$(ms)
  if wait_for 1640 805 '[ $g -gt 150 ] && [ $b -lt 230 ]' 60000; then
    d=$(( ($(ms) - t0) / 100 )); echo "mode $mode: ATB empty -> full in ${d%?}.${d: -1}s"
  else
    echo "mode $mode: ATB not full within 60 s"
  fi
  "$ADB" exec-out screencap -p > "$ROOT/build/battle_speed_mode$mode.png"
done
"$ADB" shell am force-stop com.ff7recomp
