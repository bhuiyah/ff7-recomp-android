# bx, bimg and bgfx as static libraries for Android arm64 (or any clang/Linux target),
# from FFNx's own forks at the commits FFNx pins (.vcpkg/ports/bgfx/portfile.cmake):
#   julianxhokaxhiu/bx   c99974204c15405b69647d5097af1102e6210c91
#   julianxhokaxhiu/bimg 671fe06dd7012a1ab85aa931019ed16112909756
#   julianxhokaxhiu/bgfx 1f93b90898dcc492587b617b397e2d2285dcd0ec
# FFNx builds them with GENie; here the amalgamated sources are compiled directly.
# BGFX_CONFIG mirrors FFNx's portfile (buffer sizes, Vulkan descriptor pool).
#
# include(cmake/bgfx.cmake) after setting FF7_ROOT; defines targets bx, bimg, bgfx.

set(BX_DIR   "${FF7_ROOT}/external/bx")
set(BIMG_DIR "${FF7_ROOT}/external/bimg")
set(BGFX_DIR "${FF7_ROOT}/external/bgfx")

math(EXPR FF7_BGFX_DYNAMIC_INDEX_BUFFER_SIZE  "10 << 20")
math(EXPR FF7_BGFX_DYNAMIC_VERTEX_BUFFER_SIZE "30 << 20")
math(EXPR FF7_BGFX_MAX_RECT_CACHE             "8 << 10")

set(FF7_BX_DEFS BX_CONFIG_DEBUG=0 __STDC_LIMIT_MACROS __STDC_FORMAT_MACROS __STDC_CONSTANT_MACROS)

add_library(bx STATIC "${BX_DIR}/src/amalgamated.cpp")
target_include_directories(bx PUBLIC "${BX_DIR}/include" "${BX_DIR}/3rdparty")
target_compile_definitions(bx PUBLIC ${FF7_BX_DEFS})
target_compile_features(bx PUBLIC cxx_std_20)
target_compile_options(bx PRIVATE -w)

# bimg's core decodes ASTC through the vendored astc-encoder (NEON path on arm64).
file(GLOB FF7_ASTC_SOURCES "${BIMG_DIR}/3rdparty/astc-encoder/source/*.cpp")
add_library(astcenc STATIC ${FF7_ASTC_SOURCES})
target_include_directories(astcenc PUBLIC "${BIMG_DIR}/3rdparty/astc-encoder/include")
target_compile_options(astcenc PRIVATE -w)
if(CMAKE_SYSTEM_PROCESSOR MATCHES "aarch64|arm64")
  target_compile_definitions(astcenc PRIVATE ASTCENC_NEON=1 ASTCENC_SSE=0 ASTCENC_AVX=0 ASTCENC_POPCNT=0 ASTCENC_F16C=0)
endif()

add_library(bimg STATIC "${BIMG_DIR}/src/image.cpp")
target_include_directories(bimg PUBLIC "${BIMG_DIR}/include" PRIVATE "${BIMG_DIR}/3rdparty" "${BIMG_DIR}/3rdparty/astc-encoder/include")
target_link_libraries(bimg PUBLIC bx astcenc)
target_compile_options(bimg PRIVATE -w)

add_library(bgfx STATIC "${BGFX_DIR}/src/amalgamated.cpp")
target_include_directories(bgfx
  PUBLIC  "${BGFX_DIR}/include"
  PRIVATE "${BGFX_DIR}/3rdparty" "${BGFX_DIR}/3rdparty/khronos" "${BGFX_DIR}/3rdparty/directx-headers/include/directx")
target_compile_definitions(bgfx
  PUBLIC  BGFX_CONFIG_RENDERER_VULKAN=1
  PRIVATE BGFX_CONFIG_RENDERER_OPENGLES=0
          BGFX_CONFIG_RENDERER_OPENGL=0
          BGFX_CONFIG_PREFER_DISCRETE_GPU=0
          BGFX_CONFIG_DYNAMIC_INDEX_BUFFER_SIZE=${FF7_BGFX_DYNAMIC_INDEX_BUFFER_SIZE}
          BGFX_CONFIG_DYNAMIC_VERTEX_BUFFER_SIZE=${FF7_BGFX_DYNAMIC_VERTEX_BUFFER_SIZE}
          BGFX_CONFIG_MAX_RECT_CACHE=${FF7_BGFX_MAX_RECT_CACHE}
          BGFX_CONFIG_RENDERER_VULKAN_DESCRIPTOR_SETS_PER_POOL=10240)
target_link_libraries(bgfx PUBLIC bimg bx)
target_compile_options(bgfx PRIVATE -w)
if(ANDROID)
  target_link_libraries(bgfx PUBLIC android log dl)
else()
  target_link_libraries(bgfx PUBLIC dl pthread)
endif()
