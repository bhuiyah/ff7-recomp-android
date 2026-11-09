# tools/env.sh: where the build and device tools are, for every tools/*.sh script (source it).
# Everything can be overridden from the environment; the defaults are the standard install
# locations on Windows (Git Bash paths).
#
#   ANDROID_SDK_ROOT  Android SDK (default: $ANDROID_HOME, then %LOCALAPPDATA%\Android\Sdk)
#   FF7_NDK_VERSION   NDK version inside the SDK (default 28.2.13676358, as the Gradle build)
#   JAVA_HOME         JDK 17 (default: the first jdk-17* under Program Files)
#   LLVM_DIR          LLVM for Windows (clang.exe), for host tools (default: Program Files\LLVM)
#   MSBUILD           Visual Studio 2022 MSBuild (default: found with vswhere)

_ff7_unix() { if command -v cygpath >/dev/null 2>&1; then cygpath -u "$1"; else printf '%s' "$1"; fi; }

if [ -z "${ANDROID_SDK_ROOT:-}" ]; then
  if [ -n "${ANDROID_HOME:-}" ]; then ANDROID_SDK_ROOT="$ANDROID_HOME"
  elif [ -n "${LOCALAPPDATA:-}" ]; then ANDROID_SDK_ROOT="$LOCALAPPDATA/Android/Sdk"
  else ANDROID_SDK_ROOT="$HOME/Android/Sdk"; fi
fi
ANDROID_SDK_ROOT="$(_ff7_unix "$ANDROID_SDK_ROOT")"
FF7_NDK_VERSION="${FF7_NDK_VERSION:-28.2.13676358}"
NDK="${ANDROID_NDK_ROOT:-$ANDROID_SDK_ROOT/ndk/$FF7_NDK_VERSION}"
NDK_BIN="$NDK/toolchains/llvm/prebuilt/windows-x86_64/bin"
ADB="$ANDROID_SDK_ROOT/platform-tools/adb.exe"
[ -x "$ADB" ] || ADB="$(command -v adb 2>/dev/null || echo adb)"
SDK_CMAKE_DIR="$ANDROID_SDK_ROOT/cmake/3.30.5/bin"

if [ -z "${JAVA_HOME:-}" ]; then
  for _j in "/c/Program Files/Microsoft"/jdk-17* "/c/Program Files/Eclipse Adoptium"/jdk-17* "/c/Program Files/Java"/jdk-17* \
            "/c/Program Files/Android/Android Studio/jbr"; do
    [ -x "$_j/bin/java.exe" ] && { JAVA_HOME="$_j"; break; }
  done
fi

LLVM_DIR="$(_ff7_unix "${LLVM_DIR:-/c/Program Files/LLVM}")"
HOST_CC="$LLVM_DIR/bin/clang.exe"
HOST_CXX="$LLVM_DIR/bin/clang++.exe"

if [ -z "${MSBUILD:-}" ]; then
  _vswhere="/c/Program Files (x86)/Microsoft Visual Studio/Installer/vswhere.exe"
  if [ -x "$_vswhere" ]; then
    _vs="$("$_vswhere" -latest -products '*' -requires Microsoft.Component.MSBuild -property installationPath 2>/dev/null | tr -d '\r')"
    [ -n "$_vs" ] && MSBUILD="$(_ff7_unix "$_vs")/MSBuild/Current/Bin/amd64/MSBuild.exe"
  fi
fi

export ANDROID_SDK_ROOT ANDROID_HOME="$ANDROID_SDK_ROOT" NDK NDK_BIN ADB SDK_CMAKE_DIR JAVA_HOME LLVM_DIR HOST_CC HOST_CXX MSBUILD
