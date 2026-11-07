// ffnx_display.cpp - see ffnx_display.h.
//
// bgfx is initialised on the game thread (the thread that calls the driver), the same
// arrangement FFNx uses: bgfx's API thread is whichever thread calls bgfx::init.
#include "ffnx_display.h"
#include <cstdio>
#include "ff7_boot.h"

#ifdef FF7_WITH_BGFX
#include <bgfx/bgfx.h>          // FFNx's fork keeps renderFrame/PlatformData in bgfx.h

namespace {
bool g_up;
uint16_t g_w, g_h;

void draw(const char *status) {
    bgfx::setViewRect(0, 0, 0, g_w, g_h);
    bgfx::setViewClear(0, BGFX_CLEAR_COLOR, 0x1B3A5Cff);   // FF7-menu blue
    bgfx::touch(0);
    bgfx::dbgTextClear();
    bgfx::dbgTextPrintf(2, 1, 0x0f, "FF7Recomp - lifted FINAL FANTASY VII running natively (arm64)");
    bgfx::dbgTextPrintf(2, 3, 0x0f, "renderer: bgfx %s on %ux%u", bgfx::getRendererName(bgfx::getRendererType()), g_w, g_h);
    bgfx::dbgTextPrintf(2, 4, 0x0f, "driver:   AF3DN.P bridge (FFNx port in progress)");
    if (status) bgfx::dbgTextPrintf(2, 6, 0x0e, "stopped at %s: not ported yet", status);
    bgfx::frame();
}
}  // namespace

bool ffnx_display_init() {
    if (g_up) return true;
    int w = 0, h = 0;
    void *win = ff7_platform_window(&w, &h);
    if (!win) return false;
    g_w = (uint16_t)w; g_h = (uint16_t)h;
    bgfx::renderFrame();                       // single-threaded: render on this thread
    bgfx::Init init;
    init.type = bgfx::RendererType::Vulkan;
    init.swapChain.nwh = win;
    init.swapChain.width = g_w;
    init.swapChain.height = g_h;
    init.reset = BGFX_RESET_VSYNC;
    if (!bgfx::init(init)) {
        std::fprintf(stderr, "[display] bgfx::init (Vulkan) failed\n");
        return false;
    }
    bgfx::setDebug(BGFX_DEBUG_TEXT);
    g_up = true;
    std::fprintf(stderr, "[display] bgfx %s up on %ux%u\n", bgfx::getRendererName(bgfx::getRendererType()), g_w, g_h);
    draw(nullptr);
    return true;
}

bool ffnx_display_status(const char *text) {
    if (!g_up) return false;
    for (int i = 0; i < 3; i++) draw(text);    // fill the swap chain so it stays on screen
    return true;
}

#else
bool ffnx_display_init() { return false; }
bool ffnx_display_status(const char *) { return false; }
#endif
