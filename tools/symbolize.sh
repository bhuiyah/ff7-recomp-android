#!/usr/bin/env bash
# Symbolize the libff7.so frames of a crash collected by tools/run_app.sh: the tombstone's
# (crash.txt, for aborts) or, for faults our own handler reported, ff7.log's
# "libff7.so+0x..." lines (src/host/ff7_crash.c).
# usage: bash tools/symbolize.sh [logs/app-N]   (default: the newest)
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"
SYM="$NDK_BIN/llvm-symbolizer.exe"
DIR="${1:-$(ls -d "$ROOT"/logs/app-[0-9]*/ | sed 's|/$||' | sort -V | tail -1)}"
SO=$(ls "$ROOT"/android/app/build/intermediates/cxx/Debug/*/obj/arm64-v8a/libff7.so | head -1)
addrs=$(grep -oE '#[0-9]+ pc [0-9a-f]+ .*libff7' "$DIR/crash.txt" 2>/dev/null | awk '{print "0x"$3}' | head -${N:-16})
if [ -z "$addrs" ]; then
  addrs=$(grep -oE 'libff7\.so\+0x[0-9a-f]+' "$DIR/ff7.log" 2>/dev/null | sed 's/libff7.so+//' | head -${N:-24})
  [ -n "$addrs" ] || { echo "no libff7.so frames in $DIR"; exit 0; }
  grep -E 'FAULT|STOP|FATAL' "$DIR/ff7.log" | head -2
else
  grep -E 'signal |Abort message|fault addr' "$DIR/crash.txt" | head -3
fi
"$SYM" --obj="$(cygpath -w "$SO")" -f -C -s $addrs | awk 'NF' | paste - - | sed 's/(unsigned int, unsigned int[^)]*)//' | cut -c1-200
