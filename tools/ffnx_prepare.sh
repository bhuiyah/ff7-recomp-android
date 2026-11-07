#!/usr/bin/env bash
# Reset external/FFNx to its pin and re-apply every FF7Recomp transform, in setup order.
# (tools/setup.ps1 does the same as part of the full setup.)
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FFNX="$ROOT/external/FFNx"
git -C "$FFNX" reset -q --hard
git -C "$FFNX" clean -qfd
for p in "$ROOT"/patches/ffnx/*.patch; do git -C "$FFNX" apply "$p"; echo "  applied $(basename "$p")"; done
py -I "$ROOT/tools/ffnx_port.py" gptr | tail -1
py -I "$ROOT/tools/ffnx_port.py" casts | tail -2
py -I "$ROOT/tools/ffnx_port.py" calls | tail -1
py -I "$ROOT/tools/ffnx_port.py" overrides | tail -1
py -I "$ROOT/tools/ffnx_edit_android.py"
