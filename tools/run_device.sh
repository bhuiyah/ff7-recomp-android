#!/usr/bin/env bash
# Strip, push and run the headless runner on the connected Thor; log to logs/device-<n>.log.
# usage: bash tools/run_device.sh [extra env, e.g. FF7_PATH_TRACE=1]
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SDK=/c/Users/%USERNAME%/AppData/Local/Android/Sdk
ADB="$SDK/platform-tools/adb.exe"
STRIP="$SDK/ndk/28.2.13676358/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-strip.exe"
BIN="$(cygpath -w "$ROOT/build/headless-arm64/ff7_headless")"
"$STRIP" -o "$BIN.stripped" "$BIN"
export MSYS_NO_PATHCONV=1
"$ADB" push "$BIN.stripped" /data/local/tmp/ff7/ff7_headless >/dev/null
mkdir -p "$ROOT/logs"
n=$(ls "$ROOT"/logs/device-*.log 2>/dev/null | wc -l); log="$ROOT/logs/device-$((n + 1)).log"
"$ADB" shell "cd /data/local/tmp/ff7 && chmod 755 ff7_headless && env FF7_TRACE_IMPORTS=1 $* timeout 60 ./ff7_headless game; echo exit=\$?" > "$log" 2>&1
echo "log: $log ($(wc -l < "$log") lines)"
tail -25 "$log"
