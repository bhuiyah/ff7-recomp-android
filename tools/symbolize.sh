#!/usr/bin/env bash
# Symbolize the libff7.so frames of a tombstone collected by tools/run_app.sh.
# usage: bash tools/symbolize.sh [logs/app-N]   (default: the newest)
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SYM=/c/Users/%USERNAME%/AppData/Local/Android/Sdk/ndk/28.2.13676358/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-symbolizer.exe
DIR="${1:-$(ls -d "$ROOT"/logs/app-[0-9]* | sort -V | tail -1)}"
SO=$(ls "$ROOT"/android/app/build/intermediates/cxx/Debug/*/obj/arm64-v8a/libff7.so | head -1)
addrs=$(grep -oE '#[0-9]+ pc [0-9a-f]+ .*libff7' "$DIR/crash.txt" | awk '{print "0x"$3}' | head -${N:-16})
[ -n "$addrs" ] || { echo "no libff7.so frames in $DIR/crash.txt"; grep -E 'signal|Abort message' "$DIR/crash.txt" | head; exit 0; }
grep -E 'signal |Abort message|fault addr' "$DIR/crash.txt" | head -3
"$SYM" --obj="$(cygpath -w "$SO")" -f -C -s $addrs | awk 'NF' | paste - - | sed 's/(unsigned int, unsigned int[^)]*)//' | cut -c1-200
