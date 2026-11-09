#!/usr/bin/env bash
# Build FFmpeg n9.0.2 (the version FFNx pins) for Android arm64 with the NDK, from Git Bash,
# using the NDK's own make. Only what FFNx needs for FF7's movies: the 2026 release's
# movies are Matroska with VP8 video and Vorbis audio (checked from data/movies/*.avi).
# Output: build/ffmpeg-android/{include,lib} (static libraries).
# usage: bash tools/build_ffmpeg_android.sh
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC="$ROOT/external/deps/ffmpeg"
OUT="$ROOT/build/ffmpeg-android"
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"   # NDK, HOST_CC
TC="$NDK/toolchains/llvm/prebuilt/windows-x86_64/bin"
MAKE="$NDK/prebuilt/windows-x86_64/bin/make.exe"
API=29

[ -d "$SRC" ] || git -c advice.detachedHead=false clone -q --depth 1 -b n9.0.2 https://github.com/FFmpeg/FFmpeg.git "$SRC"
mkdir -p "$OUT/build" "$OUT/tmp"
# configure needs a POSIX temp dir (Git Bash exports TMPDIR as a Windows path).
export TMPDIR="$OUT/tmp" TMP="$OUT/tmp" TEMP="$OUT/tmp"
# Host compiler for the build-time generators (libswscale/aarch64/ops_asmgen): LLVM's clang
# for Windows, through a wrapper because configure cannot take a path with spaces.
printf '#!/bin/sh\nexec "%s" "$@"\n' "$HOST_CC" > "$OUT/hostcc.sh"
chmod +x "$OUT/hostcc.sh"
cd "$OUT/build"
"$SRC/configure" \
  --prefix="$(cygpath -m "$OUT")" \
  --target-os=android --arch=aarch64 --cpu=armv8-a --enable-cross-compile \
  --cc="$TC/aarch64-linux-android$API-clang.cmd" \
  --cxx="$TC/aarch64-linux-android$API-clang++.cmd" \
  --ar="$TC/llvm-ar.exe" --nm="$TC/llvm-nm.exe" --ranlib="$TC/llvm-ranlib.exe" --strip="$TC/llvm-strip.exe" \
  --pkg-config=false --host-cc="$OUT/hostcc.sh" \
  --enable-static --disable-shared --enable-pic \
  --disable-programs --disable-doc --disable-debug --disable-avdevice --disable-avfilter \
  --disable-everything \
  --enable-avcodec --enable-avformat --enable-avutil --enable-swscale --enable-swresample \
  --enable-protocol=file \
  --enable-demuxer=matroska,ogg,avi,mov,mp3,wav \
  --enable-decoder=vp8,vp9,vorbis,opus,h264,mpeg4,aac,mp3,pcm_s16le,adpcm_ms \
  --enable-parser=vp8,vp9,vorbis,opus,h264,mpeg4video,aac,mpegaudio \
  --enable-neon --disable-vulkan --disable-mediacodec --disable-jni \
  --extra-cflags="-O2 -fPIC" > "$OUT/configure.log" 2>&1 || { tail -30 "$OUT/configure.log"; exit 1; }
# The NDK's make.exe is a native Windows make: it cannot open the MSYS /c/... paths configure
# writes into the makefiles. C:/... works for it and for the sh that runs the recipes.
sed -i 's|/\([a-z]\)/Users/|\U\1\E:/Users/|g' Makefile ffbuild/config.mak
echo "configured; building with $(nproc) jobs"
"$MAKE" -j"$(nproc)" > "$OUT/make.log" 2>&1 || { tail -30 "$OUT/make.log"; exit 1; }
# install-*-headers overflows the Windows command line partway through libavutil; the
# libraries install, the headers are copied here instead (all of each library's headers,
# plus the two generated ones).
"$MAKE" install > "$OUT/install.log" 2>&1 || true
for l in libavcodec libavformat libavutil libswresample libswscale; do
  mkdir -p "$OUT/include/$l"; cp "$SRC/$l"/*.h "$OUT/include/$l/"
done
cp libavutil/avconfig.h libavutil/ffversion.h "$OUT/include/libavutil/"
ls -la "$OUT/lib"
