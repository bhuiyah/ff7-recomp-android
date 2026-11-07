# Compile-only probe for the FFNx port: builds the FFNx sources listed in FFNX_SOURCES
# (default: cmake/ffnx.cmake's current list) for arm64, to see what is still missing.
#   -DFFNX_SOURCES="gl/texture.cpp;cfg.cpp"
cmake_minimum_required(VERSION 3.20)
project(FFNxProbe C CXX)
get_filename_component(FF7_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../.." ABSOLUTE)
include("${FF7_ROOT}/cmake/ff7core.cmake")
include("${FF7_ROOT}/cmake/bgfx.cmake")
include("${FF7_ROOT}/cmake/ffnx.cmake")
