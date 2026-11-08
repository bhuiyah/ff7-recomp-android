#!/usr/bin/env bash
# Differential test of the x86 interpreter (src/interp) against the lift, on real ff7_en
# code, on this PC (LLVM clang for Windows). usage: bash tools/interp_difftest.sh [runs] [states]
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$ROOT/build/interp-test"
CC="/c/Program Files/LLVM/bin/clang.exe"
RUNS="${1:-6000}"
STATES="${2:-4}"
mkdir -p "$OUT"
(cd "$ROOT" && py tools/interp_difftest.py "$(cygpath -m "$OUT")" "$RUNS")
RT="$ROOT/external/pcrecomp/runtime/recomp32"
COMMON=(-O1 -w -fno-strict-aliasing -ffp-contract=off -DRECOMP_FLAT_MEMORY -D_CRT_SECURE_NO_WARNINGS -I"$RT" -I"$ROOT/tests/interp" -I"$ROOT/src/host" -I"$ROOT/src/interp")
cd "$OUT"
"$CC" "${COMMON[@]}" -c difftest_cases.c -o cases.o
"$CC" "${COMMON[@]}" -DX86_INTERP_TEST -c "$ROOT/src/interp/x86_interp.c" -o interp.o
"$CC" "${COMMON[@]}" -c "$ROOT/tests/interp/difftest_main.c" -o main.o
"$CC" cases.o interp.o main.o -o difftest.exe -Wl,/FIXED:NO -Wl,/BASE:0x140000000
./difftest.exe image.bin "$STATES" 80
