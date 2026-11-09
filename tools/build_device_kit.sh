#!/usr/bin/env bash
# build_device_kit.sh: the on-device build kit the APK carries instead of the game's code
# (tools/make_device_kit.py has the layout). Needs no game files.
#   1. device clang + lld (tools/build_device_clang.sh, once)
#   2. capstone for Android (the lifter's disassembler)
#   3. the runtime's archives, the JNI entry and Python's launcher (android/kit, FF7_NO_GUEST)
#   4. pack build/device-kit (jniLibs + assets/kit.zip; android/app/build.gradle picks them up)
# usage: bash tools/build_device_kit.sh
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/tools/env.sh"
export NDK NDK_BIN SDK_CMAKE_DIR
TC=(-G Ninja -DCMAKE_MAKE_PROGRAM="$SDK_CMAKE_DIR/ninja.exe"
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake"
    -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=29 -DANDROID_STL=c++_static)

bash "$ROOT/tools/build_device_clang.sh"

CS="$ROOT/build/capstone-android"
if [ ! -f "$CS/libcapstone.so" ]; then
  "$SDK_CMAKE_DIR/cmake.exe" -S "$ROOT/external/deps/capstone" -B "$CS" "${TC[@]}" -DCMAKE_BUILD_TYPE=Release \
    -DBUILD_SHARED_LIBS=ON -DCAPSTONE_ARCHITECTURE_DEFAULT=OFF -DCAPSTONE_X86_SUPPORT=ON \
    -DCAPSTONE_BUILD_TESTS=OFF -DCAPSTONE_BUILD_CSTOOL=OFF -DCAPSTONE_INSTALL=OFF
  "$SDK_CMAKE_DIR/cmake.exe" --build "$CS"
fi

# the same build type and flags as the APK's Gradle build (Debug, every runtime target -O2)
KB="$ROOT/build/device-kit-cmake"
"$SDK_CMAKE_DIR/cmake.exe" -S "$ROOT/android/kit" -B "$KB" "${TC[@]}" -DCMAKE_BUILD_TYPE=Debug
"$SDK_CMAKE_DIR/cmake.exe" --build "$KB"

py -I "$ROOT/tools/make_device_kit.py" "$KB"
