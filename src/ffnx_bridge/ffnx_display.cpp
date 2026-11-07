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

// The Thor's bottom screen: a second swap chain (bgfx frame buffer on its window), drawn
// in view 1 by the same renderer. Picked up whenever its surface exists.
bgfx::FrameBufferHandle g_companion_fb = BGFX_INVALID_HANDLE;
void *g_companion_nwh;
uint16_t g_cw, g_ch;

void companion_frame() {
    int w = 0, h = 0;
    void *nwh = ff7_platform_companion_window(&w, &h);
    if (nwh != g_companion_nwh || (uint16_t)w != g_cw || (uint16_t)h != g_ch) {
        if (bgfx::isValid(g_companion_fb)) bgfx::destroy(g_companion_fb);
        g_companion_fb = BGFX_INVALID_HANDLE;
        g_companion_nwh = nwh;
        g_cw = (uint16_t)w; g_ch = (uint16_t)h;
        if (nwh && w && h) {
            bgfx::SwapChain sc;                 // FFNx's fork: window frame buffers take a SwapChain
            sc.nwh = nwh;
            sc.width = g_cw;
            sc.height = g_ch;
            g_companion_fb = bgfx::createFrameBuffer(sc);
            std::fprintf(stderr, "[display] companion screen %ux%u attached\n", g_cw, g_ch);
        }
    }
    if (!bgfx::isValid(g_companion_fb)) return;
    bgfx::setViewFrameBuffer(1, g_companion_fb);
    bgfx::setViewRect(1, 0, 0, g_cw, g_ch);
    bgfx::setViewClear(1, BGFX_CLEAR_COLOR, 0x2E5E3Aff);   // materia green: companion screen live
    bgfx::touch(1);
}

void draw(const char *status) {
    companion_frame();
    bgfx::setViewRect(0, 0, 0, g_w, g_h);
    bgfx::setViewClear(0, BGFX_CLEAR_COLOR, 0x1B3A5Cff);   // FF7-menu blue
    bgfx::touch(0);
    bgfx::dbgTextClear();
    bgfx::dbgTextPrintf(2, 1, 0x0f, "FF7Recomp - lifted FINAL FANTASY VII running natively (arm64)");
    bgfx::dbgTextPrintf(2, 3, 0x0f, "renderer: bgfx %s on %ux%u", bgfx::getRendererName(bgfx::getRendererType()), g_w, g_h);
    bgfx::dbgTextPrintf(2, 4, 0x0f, "driver:   AF3DN.P bridge (FFNx port in progress)");
    if (status) bgfx::dbgTextPrintf(2, 6, 0x0e, "stopped at %s: not ported yet", status);
    if (bgfx::isValid(g_companion_fb))
        bgfx::dbgTextPrintf(2, 8, 0x0a, "companion screen: %ux%u (bottom screen, green)", g_cw, g_ch);
    else
        bgfx::dbgTextPrintf(2, 8, 0x07, "companion screen: none");
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
