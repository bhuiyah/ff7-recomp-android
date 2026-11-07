# Headless bring-up runner: the whole lifted ff7_en.exe + win32hle + FF7 glue, as one
# executable that runs over `adb shell` on the Thor. See tools/build_headless.sh.
cmake_minimum_required(VERSION 3.20)
project(FF7Headless C CXX)
get_filename_component(FF7_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../.." ABSOLUTE)
include("${FF7_ROOT}/cmake/ff7core.cmake")

add_executable(ff7_headless "${FF7_ROOT}/src/host/ff7_headless_main.c")
target_link_libraries(ff7_headless PRIVATE ff7_core)
