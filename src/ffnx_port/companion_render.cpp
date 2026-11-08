// companion_render.cpp - the battle windows on the Thor's bottom screen (docs/ROADMAP.md M7).
//
// In battle the game draws its windows (party names, HP/MP/Limit/Time, the command menu and
// its lists, the hand cursor in them) as pre-transformed 2D quads along the bottom of its
// 640x480 frame. Every such draw that lies entirely in that band goes to the bottom screen
// instead of the top one: gl_draw_indexed_primitive asks ffnx_companion_route_2d, and
// Renderer::draw submits a routed draw to the companion view, whose frame buffer is the
// bottom screen's swap chain and whose projection puts the band across the top of it. The
// top screen keeps the 3D battle, the action name box, damage numbers and the target hand
// (all above the band), now uncovered.
//
// The bottom screen is an Android Presentation: this SurfaceView with CompanionView over it,
// transparent where the band is drawn (CompanionView.java uses the same proportions).
#include <bgfx/bgfx.h>
#include <bx/math.h>
#include <cstdio>
#include "common.h"
#include "globals.h"
#include "renderer.h"
#include "ff7_boot.h"

namespace {

// The game's battle window band, in its 2D coordinates (640x480).
constexpr float k_band_x0 = 0.0f, k_band_x1 = 640.0f;
constexpr float k_band_y0 = 330.0f, k_band_y1 = 450.0f;
constexpr uint16_t k_view = 250;          // after every view FFNx uses in a frame

bgfx::FrameBufferHandle g_fb = BGFX_INVALID_HANDLE;
void *g_nwh;
uint16_t g_w, g_h;
bool g_active;                            // this frame: battle, with a bottom screen
bool g_route;                             // the next Renderer::draw goes to the bottom screen
bool g_enabled = true;

}  // namespace

extern "C" void ff7_companion_battle_windows(int on) { g_enabled = on != 0; }

// From Renderer::show, after a frame: set up the bottom screen's view for the next one.
void ffnx_companion_frame_begin() {
    int w = 0, h = 0;
    void *nwh = ff7_platform_companion_window(&w, &h);
    if (nwh != g_nwh || (uint16_t)w != g_w || (uint16_t)h != g_h) {
        if (bgfx::isValid(g_fb)) bgfx::destroy(g_fb);
        g_fb = BGFX_INVALID_HANDLE;
        g_nwh = nwh;
        g_w = (uint16_t)w; g_h = (uint16_t)h;
        if (nwh && w > 0 && h > 0) {
            bgfx::SwapChain sc;
            sc.nwh = nwh;
            sc.width = g_w;
            sc.height = g_h;
            sc.formatDepthStencil = bgfx::TextureFormat::D24S8;   // window draws may depth-test
            g_fb = bgfx::createFrameBuffer(sc);
            std::fprintf(stderr, "[companion] bottom screen swap chain %ux%u\n", g_w, g_h);
        }
    }
    struct game_mode *m = getmode_cached();
    g_active = g_enabled && bgfx::isValid(g_fb) && m && m->driver_mode == MODE_BATTLE;
    g_route = false;
    if (!g_active) return;

    // The band across the full width at the top of the screen, at its own aspect ratio; the
    // rest of the screen (CompanionView's panels cover it) is cleared below it.
    const float band_h_px = g_w * (k_band_y1 - k_band_y0) / (k_band_x1 - k_band_x0);
    const float y1 = k_band_y0 + (k_band_y1 - k_band_y0) * (g_h / band_h_px);
    float proj[16];
    const bgfx::Caps *caps = bgfx::getCaps();
    bx::mtxOrtho(proj, k_band_x0, k_band_x1, y1, k_band_y0, caps->homogeneousDepth ? -1.0f : 0.0f, 1.0f, 0.0f,
                 caps->homogeneousDepth);
    bgfx::setViewFrameBuffer(k_view, g_fb);
    bgfx::setViewRect(k_view, 0, 0, g_w, g_h);
    bgfx::setViewClear(k_view, BGFX_CLEAR_COLOR | BGFX_CLEAR_DEPTH, 0x000000ff, 1.0f);
    bgfx::setViewTransform(k_view, nullptr, proj);
    bgfx::setViewMode(k_view, bgfx::ViewMode::Sequential);
    bgfx::touch(k_view);
}

// A 2D draw in battle: does it belong to the bottom screen?
void ffnx_companion_route_2d(const struct nvertex *v, uint32_t n) {
    g_route = false;
    if (!g_active || !v || !n) return;
    for (uint32_t i = 0; i < n; i++) {
        const float y = v[i]._.y;
        if (y < k_band_y0 - 0.5f) return;
    }
    g_route = true;
}

// Renderer::draw: the view for this draw (and whether it is the bottom screen's).
bool ffnx_companion_take_draw(uint16_t *view) {
    if (!g_route) return false;
    g_route = false;
    *view = k_view;
    return true;
}
