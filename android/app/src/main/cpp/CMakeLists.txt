# libff7.so for com.ff7recomp: the lifted game, its runtime, the FFNx bridge and bgfx.
cmake_minimum_required(VERSION 3.20)
project(FF7App C CXX)
get_filename_component(FF7_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../.." ABSOLUTE)

set(FF7_WITH_BGFX ON CACHE BOOL "" FORCE)
set(FF7_WITH_FFNX ON CACHE BOOL "" FORCE)
include("${FF7_ROOT}/cmake/ff7core.cmake")

add_library(ff7 SHARED "${FF7_ROOT}/src/android/ff7_android_main.cpp")
target_link_libraries(ff7 PRIVATE ff7_core android log)

# libff7launcher.so for the launcher activity: mod archives and mod.xml read with the same
# code as the game's mod overlay (src/mods), without loading the game.
set(FF7_DEPS "${FF7_ROOT}/external/deps")
add_library(ff7launcher SHARED
  "${FF7_ROOT}/src/android/ff7_launcher_jni.cpp"
  "${FF7_ROOT}/src/mods/lzs.c"
  "${FF7_ROOT}/src/mods/iro.cpp"
  "${FF7_ROOT}/src/mods/modinfo.cpp"
  "${FF7_ROOT}/src/mods/mods.cpp"
  "${FF7_ROOT}/src/mods/runtimevar.cpp"
  "${FF7_DEPS}/lzma/LzmaDec.c"
  "${FF7_DEPS}/pugixml/src/pugixml.cpp")
target_include_directories(ff7launcher PRIVATE "${FF7_ROOT}/src/mods" "${FF7_DEPS}/lzma" "${FF7_DEPS}/pugixml/src")
target_compile_options(ff7launcher PRIVATE -O2)
target_compile_features(ff7launcher PRIVATE cxx_std_20)
target_link_libraries(ff7launcher PRIVATE log)
