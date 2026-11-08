#!/usr/bin/env bash
# Recollect FFNx's code-patch manifests on the device (one boot per FFNx frame-rate mode;
# FFNx installs its patches at start-up), re-lift with them and rebuild the APK.
# Needed whenever FFNx's patches change (tools/ffnx_edit_android.py, FFNx updates): a
# patched-code variant whose bytes no longer match silently falls back to the original.
# Each frame-rate mode is collected with widescreen off and on (aspect_ratio 0 and 2, uncrop
# on with widescreen): those patches differ per combination.
# usage: bash tools/refresh_manifests.sh [modes = "1 2 3 4"] [aspects = "0 2"]
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ADB="/c/Users/%USERNAME%/AppData/Local/Android/Sdk/platform-tools/adb.exe"
FILES=/sdcard/Android/data/com.ff7recomp/files
M="$ROOT/analysis/ffnx_manifests"
MODES="${1:-1 2 3 4}"
ASPECTS="${2:-0 2}"
export MSYS_NO_PATHCONV=1
mkdir -p "$M"
build() { (cd "$ROOT/android" && JAVA_HOME="/c/Program Files/Microsoft/jdk-17.0.21.21-hotspot" ./gradlew assembleDebug --console=plain > "$ROOT/build/gradle.log" 2>&1) \
          || { grep -E 'error:' "$ROOT/build/gradle.log" | sort -u | head; exit 1; }; }
build
printf 'FF7_WATCHDOG=15\nFF7_FFNX_MANIFEST=%s/ffnx_manifest.json\n' "$FILES" > "$ROOT/build/env.txt"
"$ADB" push "$(cygpath -w "$ROOT/build/env.txt")" "$FILES/env.txt" > /dev/null
install=""
for mode in $MODES; do
 for ar in $ASPECTS; do
  uncrop=false; [ "$ar" != 0 ] && uncrop=true
  FPS_LIMITER=$mode ASPECT_RATIO=$ar UNCROP=$uncrop bash "$ROOT/tools/deploy_ffnx.sh" > /dev/null
  "$ADB" shell rm -f "$FILES/ffnx_manifest.json"
  bash "$ROOT/tools/run_app.sh" 40 $install > /dev/null 2>&1 || true
  install="--no-install"
  "$ADB" shell am force-stop com.ff7recomp
  name="ffnx_manifest_l$mode"; [ "$ar" != 0 ] && name="${name}_ar$ar"
  "$ADB" pull "$FILES/ffnx_manifest.json" "$(cygpath -w "$M/$name.json")" > /dev/null
  echo "mode $mode aspect $ar: $(grep -c '"offset"' "$M/$name.json") code writes"
 done
done
printf 'FF7_WATCHDOG=30\n' > "$ROOT/build/env.txt"
"$ADB" push "$(cygpath -w "$ROOT/build/env.txt")" "$FILES/env.txt" > /dev/null
# Windows paths: run_lift.py runs under the Windows Python, which cannot open /c/... paths.
list=$(for f in "$M"/ffnx_manifest_l*.json "$M"/hext_*.json; do [ -f "$f" ] || continue; cygpath -m "$f"; done | tr '\n' ',' | sed 's/,$//')
(cd "$ROOT" && py tools/run_lift.py lift game/ff7_en.exe analysis/catalog.json generated/recomp --manifests "$list" \
   | grep -E 'FFNx manifests|\[!\]|lift_failures')
(cd "$ROOT" && py tools/gen_retn_table.py | tail -1)
build
echo "rebuilt with $list"
