#!/usr/bin/env bash
# Is the game running at the right speed in each frame-rate mode? The opening field plays a
# fixed script (the train arrives, the guards go down, Barret says "C'mon newcomer"); its
# wall-clock length from skipping the FMV to that text box must be the same at 30, 60 and
# 120 FPS. A mode whose frame-rate patches are not in effect plays it 2-4x faster.
# Detects the text box by one pixel of its blue fill (raw screencap, no image library).
# usage: bash tools/speed_probe.sh [modes = "1 3 4"] [aspect = 0]
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"   # ADB
export MSYS_NO_PATHCONV=1
MODES="${1:-1 3 4}"
AR="${2:-0}"
key() { "$ADB" shell input keyevent --longpress "KEYCODE_$1"; }
# Title menu -> New Game. The cursor starts on New Game or on Continue (with saves): read the
# hand cursor's pixel beside "Continue?" (765, 515) and only then move up.
newgame() {
  local p; p=$("$ADB" shell "screencap | dd bs=1 skip=$((16 + (515 * 1920 + 765) * 4)) count=4 2>/dev/null | od -A n -t u1"); set -- $p
  if [ "${1:-0}" -gt 180 ] && [ "${2:-0}" -gt 180 ] && [ "${3:-0}" -gt 180 ]; then key DPAD_UP; sleep 1; fi
  key BUTTON_B
}
OFF=$((16 + (200 * 1920 + 700) * 4))       # pixel (700, 200): inside the text box
blue() { local p; p=$("$ADB" shell "screencap | dd bs=1 skip=$OFF count=4 2>/dev/null | od -A n -t u1"); set -- $p
         [ "${1:-255}" -lt 40 ] && [ "${2:-255}" -lt 40 ] && [ "${3:-0}" -gt 80 ]; }
for mode in $MODES; do
  uncrop=false; [ "$AR" != 0 ] && uncrop=true
  SHOW_FPS=true FPS_LIMITER=$mode ASPECT_RATIO=$AR UNCROP=$uncrop bash "$ROOT/tools/deploy_ffnx.sh" > /dev/null
  "$ADB" shell am force-stop com.ff7recomp
  "$ADB" shell input keyevent KEYCODE_WAKEUP
  "$ADB" shell am start -n com.ff7recomp/.GameActivity > /dev/null
  sleep 22; key BUTTON_START; sleep 6; newgame; sleep 12; key BUTTON_START
  t0=$(date +%s%3N)
  found=""
  while :; do
    now=$(date +%s%3N)
    [ $((now - t0)) -gt 60000 ] && break
    if blue; then found=$(( (now - t0) / 100 )); found="${found%?}.${found: -1}"; break; fi
  done
  "$ADB" exec-out screencap -p > "$ROOT/build/speed_mode$mode.png"
  echo "mode $mode aspect $AR: text box after ${found:-(not seen in 60) }s"
done
"$ADB" shell am force-stop com.ff7recomp
