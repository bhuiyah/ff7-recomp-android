#!/usr/bin/env bash
# train_device_clang.sh: the profile for the device clang's PGO build (tools/build_device_clang.sh
# reads config/clang-device.profdata.gz). Runs the instrumented clang on a device over adb,
# compiling a sample of the lifted game the way the on-device build does, and merges what it
# counted. The profile holds counts for clang's own code only, nothing of the game.
# Needs: a device on adb, generated C from this PC's exe (a lift in $1, default generated/recomp),
# the instrumented clang (bash tools/build_device_clang.sh --instrumented) and the kit.
# usage: bash tools/train_device_clang.sh [lift dir]
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/tools/env.sh"
export MSYS_NO_PATHCONV=1
GEN="${1:-$ROOT/generated/recomp}"
INSTR="$ROOT/build/llvm-device-instr/llvm-stripped"   # tools/build_device_clang.sh --instrumented
KITZIP="$ROOT/build/device-kit/assets/kit.zip"
[ -f "$INSTR" ] && [ -f "$KITZIP" ] && [ -f "$GEN/recomp_funcs.h" ] || { sed -n 2,8p "$0"; exit 1; }
D=/data/local/tmp/ff7pgo
"$ADB" shell "rm -rf $D && mkdir -p $D/kit $D/gen $D/prof"
"$ADB" push "$(cygpath -w "$INSTR")" $D/llvm >/dev/null
"$ADB" push "$(cygpath -w "$KITZIP")" $D/kit.zip >/dev/null
"$ADB" shell "cd $D/kit && unzip -q ../kit.zip && chmod 755 $D/llvm"
# a sample across the lift: every 6th file (the biggest files and the tables are in it)
n=0
for f in $(ls "$GEN"/recomp_*.c | sort); do
  if [ $((n % 6)) -eq 0 ] || [ "$(basename "$f")" = recomp_dispatch.c ]; then "$ADB" push "$(cygpath -w "$f")" $D/gen/ >/dev/null; fi
  n=$((n + 1))
done
"$ADB" push "$(cygpath -w "$GEN/recomp_funcs.h")" $D/gen/ >/dev/null
cat > "$ROOT/build/pgo_run.sh" <<'EOF'
D=/data/local/tmp/ff7pgo
cd $D
FL="$(sed -e "s#@KIT@#$D/kit#g" -e "s#@GEN@#$D/gen#g" kit/compile.args | tr '\n' ' ')"
export LLVM_PROFILE_FILE=$D/prof/%p.profraw FL
ls gen/*.c | xargs -P 4 -n 1 sh -c 'taskset 78 ./llvm clang $FL -c $1 -o /dev/null || echo FAIL $1' _
ls prof | wc -l
EOF
"$ADB" push "$(cygpath -w "$ROOT/build/pgo_run.sh")" $D/run.sh >/dev/null
echo "compiling $("$ADB" shell "ls $D/gen/*.c | wc -l" | tr -d '\r') files with the instrumented clang"
"$ADB" shell "sh $D/run.sh"
P="$ROOT/build/pgo-prof"; rm -rf "$P"; mkdir -p "$P"
for f in $("$ADB" shell "ls $D/prof" | tr -d '\r'); do "$ADB" pull "$D/prof/$f" "$(cygpath -w "$P/$f")" >/dev/null; done
"$NDK_BIN/llvm-profdata.exe" merge -o "$(cygpath -w "$P/clang.profdata")" $(for f in "$P"/*.profraw; do cygpath -w "$f"; done)
py -c "import gzip,sys; d=open(sys.argv[1],'rb').read(); f=open(sys.argv[2],'wb'); g=gzip.GzipFile(filename='clang.profdata', mode='wb', fileobj=f, mtime=0, compresslevel=9); g.write(d); g.close()" \
  "$(cygpath -w "$P/clang.profdata")" "$(cygpath -w "$ROOT/config/clang-device.profdata.gz")"
"$ADB" shell "rm -rf $D"
ls -la "$ROOT/config/clang-device.profdata.gz"
