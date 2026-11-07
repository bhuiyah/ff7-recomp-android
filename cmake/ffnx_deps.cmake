# Third-party libraries FFNx links against, built from source for Android at the versions
# FFNx pins (external/deps; see tools/setup.ps1). zlib comes from the NDK.
#   ffnx_png, ffnx_imgui, ffnx_pugixml, ffnx_xxhash
set(FFNX_DEPS "${FF7_ROOT}/external/deps")

# libpng 1.6.59 with NEON filters; pnglibconf.h is the stock prebuilt config.
set(PNG_DIR "${FFNX_DEPS}/libpng")
add_library(ffnx_png STATIC
  ${PNG_DIR}/png.c ${PNG_DIR}/pngerror.c ${PNG_DIR}/pngget.c ${PNG_DIR}/pngmem.c
  ${PNG_DIR}/pngpread.c ${PNG_DIR}/pngread.c ${PNG_DIR}/pngrio.c ${PNG_DIR}/pngrtran.c
  ${PNG_DIR}/pngrutil.c ${PNG_DIR}/pngset.c ${PNG_DIR}/pngtrans.c ${PNG_DIR}/pngwio.c
  ${PNG_DIR}/pngwrite.c ${PNG_DIR}/pngwtran.c ${PNG_DIR}/pngwutil.c
  ${PNG_DIR}/arm/arm_init.c ${PNG_DIR}/arm/filter_neon_intrinsics.c ${PNG_DIR}/arm/palette_neon_intrinsics.c)
target_include_directories(ffnx_png PUBLIC "${FFNX_GEN_INC}/libpng16" "${PNG_DIR}")
target_compile_definitions(ffnx_png PRIVATE PNG_ARM_NEON_OPT=2)
target_compile_options(ffnx_png PRIVATE -w)
target_link_libraries(ffnx_png PUBLIC z)
set_target_properties(ffnx_png PROPERTIES POSITION_INDEPENDENT_CODE ON)

# imgui 1.92.9 (FFNx's overlay/devtools; later the dual-screen companion panels)
set(IMGUI_DIR "${FFNX_DEPS}/imgui")
add_library(ffnx_imgui STATIC
  ${IMGUI_DIR}/imgui.cpp ${IMGUI_DIR}/imgui_draw.cpp ${IMGUI_DIR}/imgui_tables.cpp
  ${IMGUI_DIR}/imgui_widgets.cpp ${IMGUI_DIR}/imgui_demo.cpp)
target_include_directories(ffnx_imgui PUBLIC "${IMGUI_DIR}")
target_compile_options(ffnx_imgui PRIVATE -w)
set_target_properties(ffnx_imgui PROPERTIES POSITION_INDEPENDENT_CODE ON)

# pugixml 1.16
add_library(ffnx_pugixml STATIC "${FFNX_DEPS}/pugixml/src/pugixml.cpp")
target_include_directories(ffnx_pugixml PUBLIC "${FFNX_DEPS}/pugixml/src")
set_target_properties(ffnx_pugixml PROPERTIES POSITION_INDEPENDENT_CODE ON)

# xxHash 0.8.3
add_library(ffnx_xxhash STATIC "${FFNX_DEPS}/xxhash/xxhash.c")
target_include_directories(ffnx_xxhash PUBLIC "${FFNX_DEPS}/xxhash")
set_target_properties(ffnx_xxhash PROPERTIES POSITION_INDEPENDENT_CODE ON)
