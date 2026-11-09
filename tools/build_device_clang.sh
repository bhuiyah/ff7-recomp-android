#!/usr/bin/env bash
# build_device_clang.sh: clang + lld that run ON Android (arm64), for the launcher's on-device
# build (the game's code is recompiled from the player's own exe on the device; the APK carries
# no game code). One multicall binary, AArch64 target only:
#   build/device-clang/llvm        (packed into the APK as jniLibs/arm64-v8a/libllvm.so)
#   build/device-clang/include     clang's builtin headers (stddef.h, arm_neon.h, ...)
# Steps: host tablegen tools (clang-cl, MSVC env), then a cross build with the NDK.
# usage: bash tools/build_device_clang.sh      (about 40 minutes on 16 cores, once)
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/tools/env.sh"
LLVM_VER=20.1.8
SRC="$ROOT/external/llvm-project-$LLVM_VER.src"
OUT="$ROOT/build/device-clang"
B="$ROOT/build/llvm-device"
NATIVE="$ROOT/build/llvm-native"
[ -x "$OUT/llvm" ] && [ -f "$OUT/include/stddef.h" ] && { echo "device clang ok ($OUT/llvm)"; exit 0; }

if [ ! -f "$SRC/llvm/CMakeLists.txt" ]; then
  mkdir -p "$ROOT/external"
  tgz="$ROOT/external/llvm-project-$LLVM_VER.src.tar.xz"
  [ -f "$tgz" ] || curl -sfL -o "$tgz" "https://github.com/llvm/llvm-project/releases/download/llvmorg-$LLVM_VER/llvm-project-$LLVM_VER.src.tar.xz"
  echo "unpacking LLVM $LLVM_VER"
  # only what the build needs (the full tree is ~1.5 GB)
  tar -C "$ROOT/external" -xJf "$tgz" \
    "llvm-project-$LLVM_VER.src/llvm" "llvm-project-$LLVM_VER.src/clang" "llvm-project-$LLVM_VER.src/lld" \
    "llvm-project-$LLVM_VER.src/cmake" "llvm-project-$LLVM_VER.src/third-party" "llvm-project-$LLVM_VER.src/libunwind/include" 2>/dev/null || true
  [ -f "$SRC/llvm/CMakeLists.txt" ] || { echo "LLVM source unpack failed" >&2; exit 1; }
fi

COMMON=(-G Ninja -DCMAKE_BUILD_TYPE=Release -DLLVM_TARGETS_TO_BUILD=AArch64
  -DLLVM_INCLUDE_TESTS=OFF -DLLVM_INCLUDE_BENCHMARKS=OFF -DLLVM_INCLUDE_EXAMPLES=OFF -DLLVM_INCLUDE_DOCS=OFF
  -DCLANG_INCLUDE_TESTS=OFF -DCLANG_INCLUDE_DOCS=OFF -DLLVM_ENABLE_ZLIB=OFF -DLLVM_ENABLE_ZSTD=OFF
  -DLLVM_ENABLE_LIBXML2=OFF -DLLVM_ENABLE_BINDINGS=OFF -DCLANG_ENABLE_ARCMT=OFF
  -DCLANG_ENABLE_STATIC_ANALYZER=OFF -DCLANG_PLUGIN_SUPPORT=OFF)

# 1. host tablegen (llvm-tblgen, llvm-min-tblgen, clang-tblgen) for Windows
if [ ! -f "$NATIVE/bin/clang-tblgen.exe" ]; then
  ( source "$ROOT/tools/vsenv.sh" x64
    cmake -S "$SRC/llvm" -B "$NATIVE" "${COMMON[@]}" -DLLVM_ENABLE_PROJECTS=clang \
      -DCMAKE_C_COMPILER=clang-cl -DCMAKE_CXX_COMPILER=clang-cl -DCMAKE_MAKE_PROGRAM="$(command -v ninja)"
    cmake --build "$NATIVE" --target llvm-tblgen llvm-min-tblgen clang-tblgen )
fi

# 2. the cross build: one `llvm` multicall binary holding clang and lld
cmake -S "$SRC/llvm" -B "$B" "${COMMON[@]}" -DLLVM_ENABLE_PROJECTS="clang;lld" \
  -DCMAKE_MAKE_PROGRAM="$SDK_CMAKE_DIR/ninja.exe" \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=29 \
  -DANDROID_STL=c++_static -DLLVM_HOST_TRIPLE=aarch64-linux-android29 -DLLVM_DEFAULT_TARGET_TRIPLE=aarch64-linux-android29 \
  -DLLVM_NATIVE_TOOL_DIR="$NATIVE/bin" -DLLVM_TABLEGEN="$NATIVE/bin/llvm-tblgen.exe" \
  -DCLANG_TABLEGEN="$NATIVE/bin/clang-tblgen.exe" -DLLVM_TOOL_LLVM_DRIVER_BUILD=ON \
  -DLLVM_BUILD_TOOLS=OFF -DLLVM_BUILD_UTILS=OFF -DLLVM_ENABLE_TERMINFO=OFF -DLLVM_ENABLE_LIBEDIT=OFF \
  -DLLVM_ENABLE_PIC=ON -DLLVM_ENABLE_THREADS=ON -DCLANG_ENABLE_OBJC_REWRITER=OFF \
  -DCMAKE_EXE_LINKER_FLAGS="-Wl,--gc-sections -s"
cmake --build "$B" --target llvm-driver clang-resource-headers
mkdir -p "$OUT"
cp "$B/bin/llvm" "$OUT/llvm"
"$NDK_BIN/llvm-strip.exe" "$OUT/llvm"
rm -rf "$OUT/include"; cp -r "$B/lib/clang/20/include" "$OUT/include"
ls -la "$OUT/llvm"
echo "device clang ok"
