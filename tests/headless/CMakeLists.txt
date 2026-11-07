# Headless bring-up runner: the whole lifted ff7_en.exe + pcrecomp win32hle + FF7 host glue,
# as one executable that runs over `adb shell` on the Thor (or on any Linux host).
#
# Configure for the Thor (see tools/build_headless.sh):
#   cmake -S tests/headless -B build/headless-arm64 -G Ninja \
#     -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
#     -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-29
cmake_minimum_required(VERSION 3.20)
project(FF7Headless C)

get_filename_component(FF7_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../.." ABSOLUTE)
set(RT  "${FF7_ROOT}/external/pcrecomp/runtime")
set(GEN "${FF7_ROOT}/generated/recomp")
set(HOST "${FF7_ROOT}/src/host")
set(FF7HLE "${FF7_ROOT}/src/hle")

if(NOT EXISTS "${GEN}/recomp_dispatch.c")
  message(FATAL_ERROR "generated/recomp is empty: run tools/run_lift.py lift first")
endif()

set(GUEST_DEFS RECOMP_FLAT_MEMORY RECOMP_STRICT_ICALL)

# Generated code: big, machine-written, built once. -O1 keeps compile time sane;
# FP contraction off so x87 results match the original rounding.
file(GLOB GEN_SOURCES "${GEN}/recomp_*.c")
add_library(ff7_guest OBJECT ${GEN_SOURCES})
target_include_directories(ff7_guest PRIVATE "${RT}/recomp32" "${GEN}")
target_compile_definitions(ff7_guest PRIVATE ${GUEST_DEFS})
target_compile_options(ff7_guest PRIVATE -O1 -w -fno-strict-aliasing -ffp-contract=off
                       -include "${HOST}/portable_guest.h")

set(HLE_SOURCES
  "${RT}/win32hle/host_lite.c"
  "${RT}/win32hle/host.c"
  "${RT}/win32hle/pe_loader.c"
  "${RT}/win32hle/guest_alloc.c"
  "${RT}/win32hle/kernel32.c"
  "${RT}/win32hle/kernel32_ext.c"
  "${RT}/win32hle/kernel32_crt.c"
  "${RT}/win32hle/gdi32.c"
  "${RT}/win32hle/user32.c")

add_executable(ff7_headless
  "${HOST}/ff7_headless_main.c"
  "${HOST}/ff7_runtime_hooks.c"
  "${FF7HLE}/ff7_hle.c"
  "${FF7HLE}/ff7_kernel32.c"
  "${FF7HLE}/ff7_paths.c"
  "${FF7HLE}/ff7_virtual.c"
  "${FF7HLE}/ff7_af3dn.c"
  "${FF7HLE}/ff7_user32.c"
  "${FF7HLE}/ff7_winmm.c"
  "${FF7HLE}/ff7_com.c"
  "${FF7HLE}/ff7_ddraw.c"
  ${HLE_SOURCES}
  $<TARGET_OBJECTS:ff7_guest>)
target_include_directories(ff7_headless PRIVATE "${RT}/recomp32" "${RT}/win32hle" "${GEN}" "${FF7HLE}")
target_compile_definitions(ff7_headless PRIVATE ${GUEST_DEFS})
target_compile_options(ff7_headless PRIVATE -O1 -g -Wall -Wno-unused-function -Wno-unused-but-set-variable)
target_link_libraries(ff7_headless PRIVATE m)
if(NOT ANDROID)
  target_link_libraries(ff7_headless PRIVATE pthread)
endif()
