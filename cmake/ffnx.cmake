# FFNx compiled for the lifted game on Android (see docs/FFNX_PORT.md).
#
#   ffnx  STATIC  FFNx's own sources (external/FFNx/src, with tools/ffnx_port.py applied)
#                 against src/ffnx_port/include (Win32/DirectX shims in 32-bit layout)
#
# FFNX_SOURCES is the list of FFNx files ported so far; it grows module by module.
# Set FF7_ROOT and include cmake/bgfx.cmake first.

set(FFNX_DIR  "${FF7_ROOT}/external/FFNx")
set(FFNX_SRC  "${FFNX_DIR}/src")
set(FFNX_DEPS "${FF7_ROOT}/external/deps")

include("${FFNX_DEPS}/cmrc/CMakeRC.cmake")
cmrc_add_resource_library(ffnx-resources ALIAS ffnx::rc NAMESPACE FFNx WHENCE "${FFNX_DIR}"
  "${FFNX_DIR}/.logo/logo_nobg.png")
set_target_properties(ffnx-resources PROPERTIES POSITION_INDEPENDENT_CODE ON)

# Header layout FFNx expects from vcpkg: <libpng16/png.h>, <libvgmstream/vgmstream.h>.
set(FFNX_GEN_INC "${CMAKE_BINARY_DIR}/ffnx_include")
file(MAKE_DIRECTORY "${FFNX_GEN_INC}/libpng16" "${FFNX_GEN_INC}/libvgmstream")
configure_file("${FFNX_DEPS}/libpng/png.h" "${FFNX_GEN_INC}/libpng16/png.h" COPYONLY)
configure_file("${FFNX_DEPS}/libpng/pngconf.h" "${FFNX_GEN_INC}/libpng16/pngconf.h" COPYONLY)
configure_file("${FFNX_DEPS}/libpng/scripts/pnglibconf.h.prebuilt" "${FFNX_GEN_INC}/libpng16/pnglibconf.h" COPYONLY)
file(WRITE "${FFNX_GEN_INC}/libvgmstream/vgmstream.h"
  "#pragma once\n#include \"${FFNX_DEPS}/vgmstream/src/libvgmstream.h\"\n")

if(NOT DEFINED FFNX_SOURCES)
  set(FFNX_SOURCES gl/texture.cpp)
endif()
list(TRANSFORM FFNX_SOURCES PREPEND "${FFNX_SRC}/")

add_library(ffnx STATIC ${FFNX_SOURCES}
  # 32-bit layout proof for every game struct (tools/ffnx_layout_check.py)
  "${FF7_ROOT}/src/ffnx_port/layout_asserts.cpp"
  # FFNx's patch API with recomp semantics (replaces FFNx's patch.cpp)
  "${FF7_ROOT}/src/ffnx_port/patch_recomp.cpp")
target_include_directories(ffnx BEFORE PUBLIC
  "${FF7_ROOT}/src/ffnx_port/include"           # Win32/DirectX shims first
  "${FF7_ROOT}/src/ffnx_port/include_stub_ffmpeg"
  "${FF7_ROOT}/src/ffnx_bridge"
  "${FFNX_SRC}"
  "${FFNX_DEPS}/tomlplusplus/include"
  "${FFNX_DEPS}/imgui"
  "${FFNX_DEPS}/xxhash"
  "${FFNX_GEN_INC}"
  "${FFNX_DEPS}/SDL3/include"
  "${FFNX_DEPS}/pugixml/src"
  "${FFNX_DEPS}/soloud/include")
target_compile_definitions(ffnx PUBLIC
  VERSION="1.25.2-ff7recomp"
  DIRECTINPUT_VERSION=0x0800
  NOMINMAX
  FFNX_ANDROID=1)
target_compile_options(ffnx PRIVATE
  -ferror-limit=0 -fms-extensions -Wno-microsoft -Wno-invalid-offsetof -Wno-writable-strings
  -Wno-unused-value -Wno-format -Wno-c++11-narrowing-const-reference)
target_compile_features(ffnx PUBLIC cxx_std_20)
set_target_properties(ffnx PROPERTIES POSITION_INDEPENDENT_CODE ON)
target_link_libraries(ffnx PUBLIC ff7_core bgfx ffnx::rc)
