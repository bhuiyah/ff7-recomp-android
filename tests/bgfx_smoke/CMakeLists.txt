# bgfx smoke test: proves FFNx's bgfx fork builds for arm64 and initialises on the device.
# Headless, so it uses the Noop renderer and also lists the renderers compiled in.
cmake_minimum_required(VERSION 3.20)
project(BgfxSmoke CXX)
get_filename_component(FF7_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../.." ABSOLUTE)
include("${FF7_ROOT}/cmake/bgfx.cmake")
add_executable(bgfx_smoke main.cpp)
target_link_libraries(bgfx_smoke PRIVATE bgfx)
