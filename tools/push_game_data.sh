#!/usr/bin/env bash
# Push the game's data/ (the 2026 release's ff7/workingdir/data, English) to the app's game
# folder on the device. adb cannot create directories under Android/data (scoped storage:
# "secure_mkdirs failed"), but the shell can: the tree is made first, then files pushed.
# usage: bash tools/push_game_data.sh
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ADB="/c/Users/%USERNAME%/AppData/Local/Android/Sdk/platform-tools/adb.exe"
DEV=/sdcard/Android/data/com.ff7recomp/files/game
SRC="$(cd "$ROOT/game/data" && pwd -P)"
cd "$SRC"
dirs=$(find . -type d ! -path './lang-de*' ! -path './lang-es*' ! -path './lang-fr*' ! -path './lang-ja*' | sed 's|^\./||; s|^\.$||' | grep -v '^$')
{ echo "mkdir -p $DEV/data"; for d in $dirs; do echo "mkdir -p '$DEV/data/$d'"; done; } > "$ROOT/build/mkdirs.sh"
MSYS_NO_PATHCONV=1 "$ADB" push "$(cygpath -w "$ROOT/build/mkdirs.sh")" /data/local/tmp/ff7_mkdirs.sh > /dev/null
MSYS_NO_PATHCONV=1 "$ADB" shell sh /data/local/tmp/ff7_mkdirs.sh
for top in *; do
  case "$top" in lang-de|lang-es|lang-fr|lang-ja) continue;; esac
  if [ -d "$top" ]; then
    MSYS_NO_PATHCONV=1 "$ADB" push --sync "$(cygpath -w "$SRC/$top")/." "$DEV/data/$top/" | tail -1
  else
    MSYS_NO_PATHCONV=1 "$ADB" push --sync "$(cygpath -w "$SRC/$top")" "$DEV/data/$top" | tail -1
  fi
done
