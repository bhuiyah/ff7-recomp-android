// ffnx_boot.cpp - FFNx's process attach, in a build that links FFNx (cmake/ffnx.cmake).
//
// On Windows FFNx is AF3DN.P and its DllMain runs before the game's WinMain: it detects the
// exe version from code bytes at 0x401004, fills the address tables (externals_102_us.h),
// reads FFNx.toml and installs its hooks (replace_function → recomp entry overrides here,
// src/ffnx_port/patch_recomp.cpp). Here the same DllMain runs from ff7_boot.c once the
// image is mapped and the guest heap is up, before the lifted entry is called.
//
// Both hooks are strong definitions of weak symbols in ff7_core, so the runtime without FFNx
// (the bridge self-test build) keeps working:
//   ffnx_bridge_register  exports FFNx's own new_dll_graphics_driver to the game (the
//                         bridge's stub driver in src/ffnx_bridge/ffnx_driver.cpp is unused)
//   ff7_ffnx_attach       calls DllMain(DLL_PROCESS_ATTACH)
#include <cstdio>
#include "guest_abi.h"
#include "ffnx_android.h"

extern "C" void *new_dll_graphics_driver(void *game_object);               // FFNx common.cpp
extern "C" BOOL WINAPI DllMain(HINSTANCE hinstDLL, DWORD fdwReason, LPVOID lpvReserved); // FFNx common.cpp
extern "C" void ffnx_recomp_report(void);                                   // patch_recomp.cpp

extern "C" void ffnx_bridge_register(void) {
    guest::guest_export<&new_dll_graphics_driver>("new_dll_graphics_driver");
}

extern "C" int ff7_ffnx_attach(void) {
    std::fprintf(stderr, "[ffnx] DllMain(DLL_PROCESS_ATTACH)\n");
    BOOL ok = DllMain(FFNX_MODULE, DLL_PROCESS_ATTACH, nullptr);
    ffnx_recomp_report();
    std::fprintf(stderr, "[ffnx] DllMain returned %d\n", (int)ok);
    return ok ? 0 : 1;
}
