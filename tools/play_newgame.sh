#!/usr/bin/env bash
# Drive the app from launch into a new game with adb key events (the Thor's pad path:
# KeyEvent -> GameActivity -> XInput -> FFNx), then keep pressing OK (B) to advance text and
# take a screenshot every few seconds. Logs and shots in logs/app-<n>/ (tools/run_app.sh).
# usage: bash tools/play_newgame.sh [seconds after New Game = 60] [--no-install]
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ADB="/c/Users/%USERNAME%/AppData/Local/Android/Sdk/platform-tools/adb.exe"
export MSYS_NO_PATHCONV=1
AFTER="${1:-60}"
SHOTS="$ROOT/build/shots"; rm -rf "$SHOTS"; mkdir -p "$SHOTS"
key() { "$ADB" shell input keyevent --longpress "KEYCODE_$1"; }
(
  sleep 22; key BUTTON_START          # skip the credits
  sleep 6;  key DPAD_UP; sleep 1; key BUTTON_B   # NEW GAME (OK = Circle = XInput B, as on PS1/Steam)
  t=0
  while [ $t -lt "$AFTER" ]; do
    sleep 5; t=$((t + 5))
    "$ADB" exec-out screencap -p > "$SHOTS/t$t.png" 2>/dev/null
    key BUTTON_B
  done
) &
bash "$ROOT/tools/run_app.sh" $((30 + AFTER)) "${2:-}"
wait
n=$(ls -d "$ROOT"/logs/app-[0-9]*/ | sed 's|/$||; s/.*app-//' | sort -n | tail -1)
mv "$SHOTS"/t*.png "$ROOT/logs/app-$n/" 2>/dev/null
ls "$ROOT/logs/app-$n"
