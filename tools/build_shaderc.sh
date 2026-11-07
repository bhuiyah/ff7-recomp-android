#!/usr/bin/env bash
# Build bgfx's shaderc (Windows host tool) from FFNx's bgfx fork, the way FFNx's vcpkg port
# does: GENie vs2022 --with-tools, then MSBuild of shaderc.vcxproj (Release|x64).
# Needs Visual Studio 2022 Build Tools (C++). Output: external/bgfx/.build/win64_vs2022/bin/shadercRelease.exe
# usage: bash tools/build_shaderc.sh
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MSB="/c/Program Files (x86)/Microsoft Visual Studio/2022/BuildTools/MSBuild/Current/Bin/amd64/MSBuild.exe"
cd "$ROOT/external/bgfx"
../bx/tools/bin/windows/genie.exe --with-tools vs2022 > /dev/null
# bx is built with /WX; MSVC 17.x warns C4505 on bx::crtReportHook. A host tool, so not fatal.
sed -i 's|<TreatWarningAsError>true</TreatWarningAsError>|<TreatWarningAsError>false</TreatWarningAsError>|' .build/projects/vs2022/*.vcxproj
"$MSB" .build/projects/vs2022/shaderc.vcxproj -p:Configuration=Release -p:Platform=x64 -m -v:m -nologo > .build/shaderc_build.log 2>&1 \
  || { grep -E ' error ' .build/shaderc_build.log | head -20; exit 1; }
ls -la .build/win64_vs2022/bin/shadercRelease.exe
