# FFNx's audio stack for Android, at FFNx's pinned versions (external/deps):
#   ffnx_soloud    SoLoud (FFNx fork 73cea78), miniaudio backend only -- as FFNx's port
#                  (--with-miniaudio-only); miniaudio drives AAudio/OpenSL ES on Android
#   ffnx_vgmstream vgmstream 71e2361 with Vorbis (music_ogg) and G.722.1, as FFNx's port
#   ffnx_ogg, ffnx_vorbis   libogg 1.3.5 / libvorbis 1.3.7 for vgmstream
#   ffnx_openpsf   openpsf + psflib + highly_experimental (FFNx forks), PSF music
#   ffnx_lz4       lz4 1.10.0
set(FFNX_DEPS "${FF7_ROOT}/external/deps")

# ---- libogg / libvorbis
set(OGG_GEN "${CMAKE_BINARY_DIR}/ogg_include/ogg")
file(MAKE_DIRECTORY "${OGG_GEN}")
file(WRITE "${OGG_GEN}/config_types.h"
  "#ifndef __CONFIG_TYPES_H__\n#define __CONFIG_TYPES_H__\n#include <stdint.h>\n"
  "typedef int16_t ogg_int16_t;\ntypedef uint16_t ogg_uint16_t;\ntypedef int32_t ogg_int32_t;\n"
  "typedef uint32_t ogg_uint32_t;\ntypedef int64_t ogg_int64_t;\ntypedef uint64_t ogg_uint64_t;\n#endif\n")
add_library(ffnx_ogg STATIC "${FFNX_DEPS}/ogg/src/bitwise.c" "${FFNX_DEPS}/ogg/src/framing.c")
target_include_directories(ffnx_ogg PUBLIC "${CMAKE_BINARY_DIR}/ogg_include" "${FFNX_DEPS}/ogg/include")
target_compile_options(ffnx_ogg PRIVATE -w)

set(VORBIS_LIB "${FFNX_DEPS}/vorbis/lib")
add_library(ffnx_vorbis STATIC
  ${VORBIS_LIB}/analysis.c ${VORBIS_LIB}/bitrate.c ${VORBIS_LIB}/block.c ${VORBIS_LIB}/codebook.c
  ${VORBIS_LIB}/envelope.c ${VORBIS_LIB}/floor0.c ${VORBIS_LIB}/floor1.c ${VORBIS_LIB}/info.c
  ${VORBIS_LIB}/lookup.c ${VORBIS_LIB}/lpc.c ${VORBIS_LIB}/lsp.c ${VORBIS_LIB}/mapping0.c
  ${VORBIS_LIB}/mdct.c ${VORBIS_LIB}/psy.c ${VORBIS_LIB}/registry.c ${VORBIS_LIB}/res0.c
  ${VORBIS_LIB}/sharedbook.c ${VORBIS_LIB}/smallft.c ${VORBIS_LIB}/synthesis.c
  ${VORBIS_LIB}/vorbisenc.c ${VORBIS_LIB}/vorbisfile.c ${VORBIS_LIB}/window.c)
target_include_directories(ffnx_vorbis PUBLIC "${FFNX_DEPS}/vorbis/include" PRIVATE "${VORBIS_LIB}")
target_link_libraries(ffnx_vorbis PUBLIC ffnx_ogg m)
target_compile_options(ffnx_vorbis PRIVATE -w)

# ---- vgmstream (its own source globs, src/CMakeLists.txt)
set(VGM "${FFNX_DEPS}/vgmstream/src")
file(GLOB VGM_SOURCES "${VGM}/base/*.c" "${VGM}/coding/*.c" "${VGM}/coding/libs/*.c"
  "${VGM}/layout/*.c" "${VGM}/meta/*.c" "${VGM}/util/*.c" "${VGM}/*.c")
add_library(ffnx_vgmstream STATIC ${VGM_SOURCES})
target_include_directories(ffnx_vgmstream PUBLIC "${VGM}"
  PRIVATE "${FFNX_DEPS}/vgmstream/ext_includes" "${VGM}/base" "${VGM}/coding" "${VGM}/layout" "${VGM}/meta" "${VGM}/util")
target_compile_definitions(ffnx_vgmstream PUBLIC VGM_USE_VORBIS VGM_USE_G7221)
target_link_libraries(ffnx_vgmstream PUBLIC ffnx_vorbis m)
target_compile_options(ffnx_vgmstream PRIVATE -w)

# ---- SoLoud: core + miniaudio backend (+ the bundled audio sources FFNx's subclasses use)
set(SL "${FFNX_DEPS}/soloud")
file(GLOB SOLOUD_SOURCES "${SL}/src/core/*.cpp" "${SL}/src/audiosource/wav/*.cpp" "${SL}/src/audiosource/wav/*.c"
  "${SL}/src/filter/*.cpp" "${SL}/src/backend/miniaudio/*.cpp")
add_library(ffnx_soloud STATIC ${SOLOUD_SOURCES})
target_include_directories(ffnx_soloud PUBLIC "${SL}/include" PRIVATE "${SL}/src/backend/miniaudio")
target_compile_definitions(ffnx_soloud PUBLIC WITH_MINIAUDIO)
target_compile_options(ffnx_soloud PRIVATE -w)
if(ANDROID)
  target_link_libraries(ffnx_soloud PUBLIC OpenSLES aaudio log)
endif()

# ---- openpsf (PSF music): highly_experimental + psflib + openpsf
add_library(ffnx_openpsf STATIC
  ${FFNX_DEPS}/highly_experimental/src/bios.c ${FFNX_DEPS}/highly_experimental/src/iop.c
  ${FFNX_DEPS}/highly_experimental/src/ioptimer.c ${FFNX_DEPS}/highly_experimental/src/psx.c
  ${FFNX_DEPS}/highly_experimental/src/r3000.c ${FFNX_DEPS}/highly_experimental/src/r3000asm.c
  ${FFNX_DEPS}/highly_experimental/src/r3000dis.c ${FFNX_DEPS}/highly_experimental/src/spu.c
  ${FFNX_DEPS}/highly_experimental/src/spucore.c ${FFNX_DEPS}/highly_experimental/src/vfs.c
  ${FFNX_DEPS}/psflib/src/psf2fs.c ${FFNX_DEPS}/psflib/src/psflib.c
  ${FFNX_DEPS}/openpsf/src/circular_buffer.cpp ${FFNX_DEPS}/openpsf/src/openpsf.cpp
  ${FFNX_DEPS}/openpsf/src/openpsf_c.cpp ${FFNX_DEPS}/openpsf/src/PSXFilter.cpp)
target_include_directories(ffnx_openpsf
  PUBLIC "${FFNX_DEPS}/openpsf/include" "${FFNX_DEPS}/psflib/include" "${FFNX_DEPS}/highly_experimental/include"
  PRIVATE "${FFNX_DEPS}/highly_experimental/src" "${FFNX_DEPS}/psflib/src" "${FFNX_DEPS}/openpsf/src")
target_compile_definitions(ffnx_openpsf PRIVATE EMU_COMPILE EMU_LITTLE_ENDIAN)
target_compile_options(ffnx_openpsf PRIVATE "SHELL:-include ${FF7_ROOT}/src/ffnx_port/include/msvc_crt.h")
target_link_libraries(ffnx_openpsf PUBLIC z)
target_compile_options(ffnx_openpsf PRIVATE -w)

# ---- lz4
add_library(ffnx_lz4 STATIC "${FFNX_DEPS}/lz4/lib/lz4.c" "${FFNX_DEPS}/lz4/lib/lz4hc.c" "${FFNX_DEPS}/lz4/lib/lz4frame.c" "${FFNX_DEPS}/lz4/lib/xxhash.c")
target_include_directories(ffnx_lz4 PUBLIC "${FFNX_DEPS}/lz4/lib")
target_compile_definitions(ffnx_lz4 PRIVATE XXH_NAMESPACE=LZ4_)

foreach(t ffnx_ogg ffnx_vorbis ffnx_vgmstream ffnx_soloud ffnx_openpsf ffnx_lz4)
  set_target_properties(${t} PROPERTIES POSITION_INDEPENDENT_CODE ON)
endforeach()
