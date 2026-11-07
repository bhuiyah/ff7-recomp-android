# The lifted game plus everything it runs on, shared by the headless runner and the app.
#   ff7_guest  OBJECT  generated/recomp/*.c (the lift)
#   ff7_core   STATIC  win32hle + FF7 HLE + host glue + FFNx bridge
# Set FF7_ROOT first. Options: FF7_TRACE (entry ring), FF7_WITH_BGFX (bring-up display).

set(RT     "${FF7_ROOT}/external/pcrecomp/runtime")
set(GEN    "${FF7_ROOT}/generated/recomp")
set(HOST   "${FF7_ROOT}/src/host")
set(FF7HLE "${FF7_ROOT}/src/hle")
set(BRIDGE "${FF7_ROOT}/src/ffnx_bridge")

if(NOT EXISTS "${GEN}/recomp_dispatch.c")
  message(FATAL_ERROR "generated/recomp is empty: run tools/run_lift.py lift first")
endif()

set(FF7_GUEST_DEFS RECOMP_FLAT_MEMORY RECOMP_STRICT_ICALL)
option(FF7_TRACE "Lifted-function entry ring (src/host/ff7_trace.c)" OFF)
if(FF7_TRACE)
  list(APPEND FF7_GUEST_DEFS RECOMP_TRACE)
endif()

# Generated code: -O1 keeps compile time sane; no FP contraction so x87 results round
# like the original.
file(GLOB FF7_GEN_SOURCES "${GEN}/recomp_*.c")
add_library(ff7_guest OBJECT ${FF7_GEN_SOURCES})
target_include_directories(ff7_guest PRIVATE "${RT}/recomp32" "${GEN}")
target_compile_definitions(ff7_guest PRIVATE ${FF7_GUEST_DEFS})
target_compile_options(ff7_guest PRIVATE -O1 -w -fno-strict-aliasing -ffp-contract=off
                       -include "${HOST}/portable_guest.h")
set_target_properties(ff7_guest PROPERTIES POSITION_INDEPENDENT_CODE ON)

add_library(ff7_core STATIC
  "${RT}/win32hle/host_lite.c"
  "${RT}/win32hle/host.c"
  "${RT}/win32hle/pe_loader.c"
  "${RT}/win32hle/guest_alloc.c"
  "${RT}/win32hle/kernel32.c"
  "${RT}/win32hle/kernel32_ext.c"
  "${RT}/win32hle/kernel32_crt.c"
  "${RT}/win32hle/gdi32.c"
  "${RT}/win32hle/user32.c"
  "${HOST}/ff7_boot.c"
  "${HOST}/ff7_runtime_hooks.c"
  "${HOST}/ff7_crash.c"
  "${HOST}/ff7_trace.c"
  "${HOST}/ff7_overrides.c"
  "${FF7HLE}/ff7_hle.c"
  "${FF7HLE}/ff7_kernel32.c"
  "${FF7HLE}/ff7_paths.c"
  "${FF7HLE}/ff7_virtual.c"
  "${FF7HLE}/ff7_af3dn.c"
  "${FF7HLE}/ff7_user32.c"
  "${FF7HLE}/ff7_winmm.c"
  "${FF7HLE}/ff7_com.c"
  "${FF7HLE}/ff7_ddraw.c"
  "${FF7HLE}/ff7_dsound.c"
  "${FF7HLE}/ff7_msacm.c"
  "${FF7HLE}/ff7_dinput.c"
  "${BRIDGE}/ffnx_driver.cpp"
  "${BRIDGE}/ffnx_display.cpp"
  $<TARGET_OBJECTS:ff7_guest>)
target_include_directories(ff7_core PUBLIC "${RT}/recomp32" "${RT}/win32hle" "${GEN}" "${HOST}" "${FF7HLE}" "${BRIDGE}")
target_compile_definitions(ff7_core PUBLIC ${FF7_GUEST_DEFS})
target_compile_options(ff7_core PRIVATE -O1 -g -Wall -Wno-unused-function -Wno-unused-but-set-variable)
target_compile_features(ff7_core PUBLIC cxx_std_17)
set_target_properties(ff7_core PROPERTIES POSITION_INDEPENDENT_CODE ON)
target_link_libraries(ff7_core PUBLIC m)
if(ANDROID)
  target_link_libraries(ff7_core PUBLIC aaudio)    # ff7_dsound.c mixer
endif()
if(NOT ANDROID)
  target_link_libraries(ff7_core PUBLIC pthread)
endif()

option(FF7_WITH_BGFX "Bring-up display through bgfx (needs a platform window)" OFF)
if(FF7_WITH_BGFX)
  include("${FF7_ROOT}/cmake/bgfx.cmake")
  target_compile_definitions(ff7_core PRIVATE FF7_WITH_BGFX=1)
  target_link_libraries(ff7_core PUBLIC bgfx)
endif()

# FFNx as the game's renderer and patch set (cmake/ffnx.cmake; src/ffnx_port/ffnx_boot.cpp):
# boot runs FFNx's DllMain, and the game's new_dll_graphics_driver is FFNx's own.
option(FF7_WITH_FFNX "Link FFNx (renderer, audio, movies, hooks)" OFF)
if(FF7_WITH_FFNX)
  if(NOT FF7_WITH_BGFX)
    include("${FF7_ROOT}/cmake/bgfx.cmake")
  endif()
  include("${FF7_ROOT}/cmake/ffnx.cmake")
  target_compile_definitions(ff7_core PRIVATE FF7_WITH_FFNX=1)
  target_link_libraries(ff7_core PUBLIC ffnx)    # cyclic with ffnx -> ff7_core; CMake repeats the pair
endif()
