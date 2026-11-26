#!/usr/bin/env bash
# An AddressSanitizer APK for hunting memory bugs on a device (GitHub #90):
#   bash tools/asan/setup.sh            puts the NDK's ASan runtime, our wrap.sh and the prctl
#                                       shim in android/app/asan (not committed)
#   cd android; ./gradlew assembleRelease -PbundleGame=ON -Pasan=ON
# The APK is debuggable (wrap.sh needs it) and bundles the PC's lift (generated/recomp: run
# tools/run_lift.py lift first). FFNx, bgfx and the runtime are instrumented, the lift is not.
# Reports go to logcat (tag FF7Recomp / app_process64). /data/local/tmp/ff7wrap.sh, if present,
# is sourced by wrap.sh (PRE=, ASAN_OPTIONS=, JDWP= overrides) to try options without a rebuild.
# Put a normal release back on the device afterwards.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "$ROOT/tools/env.sh"
OUT="$ROOT/android/app/asan"
mkdir -p "$OUT/jni/arm64-v8a" "$OUT/res/lib/arm64-v8a"
RT=$(ls "$NDK"/toolchains/llvm/prebuilt/windows-x86_64/lib/clang/*/lib/linux/libclang_rt.asan-aarch64-android.so | head -1)
cp "$RT" "$OUT/jni/arm64-v8a/"
"$NDK_BIN/clang.exe" --target=aarch64-linux-android29 -O2 -shared -fPIC -mbranch-protection=none -nostdlib++ \
  -o "$OUT/jni/arm64-v8a/libprctlshim.so" "$ROOT/tools/asan/prctl_shim.c"
cp "$ROOT/tools/asan/wrap.sh" "$OUT/res/lib/arm64-v8a/wrap.sh"
echo "asan: $OUT ready"
