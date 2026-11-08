#!/usr/bin/env bash
# From launch to the first battle (Sector 1 station guards) and through it, over adb:
# credits -> New Game -> opening FMV -> walk up the platform -> battle, pressing OK (B) to
# attack. Screenshots every 10 s; logs in logs/app-<n>/ (tools/run_app.sh).
# usage: bash tools/play_to_battle.sh [--no-install]
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ADB="/c/Users/%USERNAME%/AppData/Local/Android/Sdk/platform-tools/adb.exe"
export MSYS_NO_PATHCONV=1
SHOTS="$ROOT/build/shots"; rm -rf "$SHOTS"; mkdir -p "$SHOTS"
key() { "$ADB" shell input keyevent --longpress "KEYCODE_$1"; }
shot() { "$ADB" exec-out screencap -p > "$SHOTS/$1.png" 2>/dev/null; }
(
  sleep 22; key BUTTON_START
  sleep 6;  key DPAD_UP; sleep 1; key BUTTON_B           # New Game
  for t in $(seq 10 10 200); do sleep 10; shot "fmv$t"; done   # opening FMV
  key BUTTON_B; sleep 3; key BUTTON_B; sleep 3
  for i in $(seq 1 24); do key DPAD_UP; done; shot walk  # up the platform: the guards attack
  for t in $(seq 1 40); do sleep 3; key BUTTON_B; [ $((t % 3)) -eq 0 ] && shot "battle$t"; done
  # back in the field: the main menu (Triangle = XInput Y), its status screen, then out
  sleep 5; key BUTTON_Y; sleep 3; shot menu1
  key DPAD_DOWN; sleep 1; key BUTTON_B; sleep 3; shot menu2
  key BUTTON_A; sleep 2; key BUTTON_A; sleep 2; shot menu3
) &
bash "$ROOT/tools/run_app.sh" 440 "${1:-}"
wait
n=$(ls -d "$ROOT"/logs/app-[0-9]*/ | sed 's|/$||; s/.*app-//' | sort -n | tail -1)
mv "$SHOTS"/*.png "$ROOT/logs/app-$n/" 2>/dev/null
echo "logs/app-$n"
