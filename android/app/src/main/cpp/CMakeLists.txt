# libff7.so for com.ff7recomp: the lifted game, its runtime, the FFNx bridge and bgfx.
cmake_minimum_required(VERSION 3.20)
project(FF7App C CXX)
get_filename_component(FF7_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../.." ABSOLUTE)

set(FF7_WITH_BGFX ON CACHE BOOL "" FORCE)
set(FF7_WITH_FFNX ON CACHE BOOL "" FORCE)
include("${FF7_ROOT}/cmake/ff7core.cmake")

add_library(ff7 SHARED "${FF7_ROOT}/src/android/ff7_android_main.cpp")
target_link_libraries(ff7 PRIVATE ff7_core android log)
