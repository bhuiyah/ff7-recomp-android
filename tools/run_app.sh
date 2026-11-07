#!/usr/bin/env bash
# Install the debug APK on the connected Thor, start it, let it run, then collect
# files/ff7.log (runtime) and files/game/FFNx.log into logs/app-<n>/ and a screenshot.
# usage: bash tools/run_app.sh [seconds=20] [--no-install]
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ADB="/c/Users/%USERNAME%/AppData/Local/Android/Sdk/platform-tools/adb.exe"
APK="$ROOT/android/app/build/outputs/apk/debug/app-debug.apk"
FILES=/sdcard/Android/data/com.ff7recomp/files
SECS="${1:-20}"
export MSYS_NO_PATHCONV=1
if [ "${2:-}" != "--no-install" ]; then
  "$ADB" install -r -g "$(cygpath -w "$APK")" | tail -1
fi
"$ADB" shell input keyevent KEYCODE_WAKEUP   # a sleeping screen never creates the surface
"$ADB" shell am force-stop com.ff7recomp
"$ADB" shell rm -f "$FILES/ff7.log" "$FILES/game/FFNx.log"
"$ADB" logcat -c
"$ADB" shell am start -n com.ff7recomp/.GameActivity > /dev/null
sleep "$SECS"
n=$(ls -d "$ROOT"/logs/app-[0-9]* 2>/dev/null | sed 's/.*app-//' | sort -n | tail -1); out="$ROOT/logs/app-$((${n:-0} + 1))"
mkdir -p "$out"
"$ADB" exec-out screencap -p > "$out/screen.png"
"$ADB" pull "$FILES/ff7.log" "$(cygpath -w "$out/ff7.log")" > /dev/null 2>&1
"$ADB" pull "$FILES/game/FFNx.log" "$(cygpath -w "$out/FFNx.log")" > /dev/null 2>&1
"$ADB" logcat -d -b crash > "$out/crash.txt" 2>&1
"$ADB" logcat -d -s FF7Recomp:* DEBUG:* libc:* > "$out/logcat.txt" 2>&1
echo "logs: $out"
echo "--- ff7.log (tail)"; tail -25 "$out/ff7.log" 2>/dev/null
echo "--- FFNx.log (tail)"; tail -25 "$out/FFNx.log" 2>/dev/null
