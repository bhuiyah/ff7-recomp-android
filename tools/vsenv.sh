# Source from Git Bash: imports the MSVC build environment (INCLUDE, LIB, link.exe) for the
# given arch, found with vswhere, and puts LLVM first on PATH so clang/clang-cl work.
# Usage: source tools/vsenv.sh [x86|x64]
_arch="${1:-x64}"
_vswhere="/c/Program Files (x86)/Microsoft Visual Studio/Installer/vswhere.exe"
_vs="$("$_vswhere" -latest -products '*' -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath | tr -d '\r')"
if [ -z "$_vs" ]; then echo "vsenv: no Visual Studio with C++ tools found" >&2; return 1; fi
_tmp="$(mktemp -d)"
printf '@echo off\r\ncall "%s\\VC\\Auxiliary\\Build\\vcvarsall.bat" %s >nul 2>nul\r\nset\r\n' "$_vs" "$_arch" > "$_tmp/e.bat"
while IFS='=' read -r k v; do
  v="${v%$'\r'}"
  case "$k" in
    INCLUDE|LIB|LIBPATH|VCToolsInstallDir|WindowsSdkDir|WindowsSDKVersion|VSINSTALLDIR|VCINSTALLDIR|UCRTVersion|UniversalCRTSdkDir) export "$k=$v";;
    Path|PATH) _winpath="$v";;
  esac
done < <(cmd //c "$(cygpath -w "$_tmp/e.bat")")
_IFS="$IFS"; IFS=';'; _add=""
for p in $_winpath; do case "$p" in *"Microsoft Visual Studio"*|*"Windows Kits"*) _add="$_add:$(cygpath -u "$p")";; esac; done
IFS="$_IFS"
export PATH="/c/Program Files/LLVM/bin:/c/Program Files/CMake/bin${_add}:$PATH"
rm -rf "$_tmp"; unset _tmp _vswhere _vs _arch _winpath _add _IFS
