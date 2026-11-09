# The on-device build kit (tools/build_device_kit.sh): everything libff7.so is made of except
# the game's own code, built for arm64 Android. The APK carries these; the player's device lifts
# its own ff7_en.exe, compiles the C and links it with them (BuildService.java).
#   ff7_core, ffnx, bgfx, ... STATIC   the runtime (FF7_NO_GUEST: no generated/recomp)
#   ff7_main               OBJECT   src/android/ff7_android_main.cpp
#   ff7  (not built)       SHARED   the link the device repeats: make_device_kit.py reads its
#                                   command line from Ninja and turns it into the device's
#   pyrun                  EXE      Python for Android's interpreter (libpyrun.so)
cmake_minimum_required(VERSION 3.20)
project(FF7Kit C CXX)
get_filename_component(FF7_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../.." ABSOLUTE)

set(FF7_WITH_BGFX ON CACHE BOOL "" FORCE)
set(FF7_WITH_FFNX ON CACHE BOOL "" FORCE)
set(FF7_NO_GUEST ON CACHE BOOL "" FORCE)
include("${FF7_ROOT}/cmake/ff7core.cmake")

add_library(ff7 SHARED EXCLUDE_FROM_ALL "${FF7_ROOT}/src/android/ff7_android_main.cpp")
target_link_libraries(ff7 PRIVATE ff7_core android log)
# the archives the link needs, built by default
add_custom_target(ff7_archives ALL DEPENDS ff7_core)
add_dependencies(ff7_archives ff7_core ffnx)
add_library(ff7_main OBJECT "${FF7_ROOT}/src/android/ff7_android_main.cpp")
target_link_libraries(ff7_main PRIVATE ff7_core android log)
add_dependencies(ff7_archives ff7_main)

set(PYA "${FF7_ROOT}/external/python-android/prefix")
add_executable(pyrun "${FF7_ROOT}/src/android/pyrun.c")
target_include_directories(pyrun PRIVATE "${PYA}/include/python3.14")
target_link_libraries(pyrun PRIVATE "${PYA}/lib/libpython3.14.so")
target_link_options(pyrun PRIVATE "-Wl,-rpath,$ORIGIN")

add_dependencies(ff7_archives pyrun)
