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
// transparent over the windows' area (layout below; CompanionView gets it in its snapshot).
#include <bgfx/bgfx.h>
#include <bx/math.h>
#include <cstdio>
#include <cstdlib>
#include <set>
#include <string>
#include <cmath>
#include <cstdlib>
#include <algorithm>
#include <set>
#include <string>
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


// ---- touch on the battle windows ----------------------------------------------------------
// A tap on the bottom screen's windows steers the game's own hand cursor: each frame the hand
// quad's position is recorded (it is drawn wherever the cursor is: the command menu, the
// Magic/Item lists, or over a target on the top screen), and the game thread presses the
// D-pad one step at a time toward the tapped row and column, then confirms. Menus whose
// layout differs need nothing special: every step is measured, not predicted.
namespace {

// The hand sprite: 48x26 in the game's 2D coordinates, this rectangle of its texture page.
bool is_hand(const struct nvertex *v, float *x0, float *y0) {
    float ax = 1e9f, ay = 1e9f, bx = -1e9f, by = -1e9f, u0 = 1e9f, v0 = 1e9f, u1 = -1e9f, v1 = -1e9f;
    for (int i = 0; i < 4; i++) {
        ax = std::min(ax, v[i]._.x); bx = std::max(bx, v[i]._.x); ay = std::min(ay, v[i]._.y); by = std::max(by, v[i]._.y);
        u0 = std::min(u0, v[i].u); u1 = std::max(u1, v[i].u); v0 = std::min(v0, v[i].v); v1 = std::max(v1, v[i].v);
    }
    auto near = [](float a, float b, float tol) { return std::fabs(a - b) <= tol; };
    if (!near(bx - ax, 48, 3) || !near(by - ay, 26, 3)) return false;
    if (!near(u0, 0.75f, 0.01f) || !near(v0, 0.0625f, 0.01f) || !near(u1, 0.9375f, 0.01f) || !near(v1, 0.1641f, 0.01f)) return false;
    *x0 = ax; *y0 = ay;
    return true;
}

uint64_t g_frame;                      // frames since boot (ffnx_companion_frame_begin)
float g_hand_x, g_hand_y;              // the hand's last top-left
uint64_t g_hand_frame;                 // when it was last drawn

void track_hand(const struct nvertex *v) {
    float x, y;
    if (is_hand(v, &x, &y)) { g_hand_x = x; g_hand_y = y; g_hand_frame = g_frame; }
}

}  // namespace

extern "C" void ff7_companion_battle_windows(int on) { g_enabled = on != 0; }

// ---- layout --------------------------------------------------------------------------------
// CompanionView reserves the enemies panel above and the touch deck below; the windows get
// the space between (ff7_companion_set_area). Two layouts:
//  - command turns: the band's left part (name, command menu) and right part (HP/MP/Limit/
//    Time) stacked, each scaled up as far as the area allows;
//  - a list window across the band (Magic, Item, ...: one window background quad wider than
//    500): the whole band at full width.
// Many of the game's draws span both parts (window frames and headers are batched), so every
// routed draw is submitted to each region's view and each view's projection crops its part.
namespace {
constexpr float k_split = 267.0f;                 // between the name window and the status window
constexpr uint16_t k_view_clear = 249;
struct Region { float sx0, sy0, sx1, sy1; uint16_t dx, dy, dw, dh; };
Region g_regions[2];
int g_nregions;
int g_area_top = 200, g_area_bottom = 880;        // pixels; CompanionView sets them
bool g_wide_seen, g_wide;                          // a list window this frame / the last one
int g_wide_hold;                                   // frames to keep the wide layout
int g_extra;                                       // views still to submit the current draw to

void set_region_view(uint16_t view, const Region &r) {
    float proj[16];
    const bgfx::Caps *caps = bgfx::getCaps();
    bx::mtxOrtho(proj, r.sx0, r.sx1, r.sy1, r.sy0, caps->homogeneousDepth ? -1.0f : 0.0f, 1.0f, 0.0f,
                 caps->homogeneousDepth);
    bgfx::setViewFrameBuffer(view, g_fb);
    bgfx::setViewRect(view, r.dx, r.dy, r.dw, r.dh);
    bgfx::setViewClear(view, BGFX_CLEAR_DEPTH, 0, 1.0f);
    bgfx::setViewTransform(view, nullptr, proj);
    bgfx::setViewMode(view, bgfx::ViewMode::Sequential);
    bgfx::touch(view);
}

void layout() {
    const float W = g_w, top = (float)g_area_top, H = (float)std::max(1, g_area_bottom - g_area_top);
    const float band = k_band_y1 - k_band_y0, gap = 16.0f;
    if (g_wide) {
        // the lists reach the bottom of the frame; full width, and up to 1.5x taller than wide
        // so the rows are bigger to read and to touch (the space is there)
        const float wy1 = 480.0f, wband = wy1 - k_band_y0;
        const float sx = W / (k_band_x1 - k_band_x0), sy = std::min(H / wband, sx * 1.5f);
        const float w = (k_band_x1 - k_band_x0) * sx, h = wband * sy;
        g_regions[0] = { k_band_x0, k_band_y0, k_band_x1, wy1,
                         (uint16_t)((W - w) / 2), (uint16_t)(top + (H - h) / 2), (uint16_t)w, (uint16_t)h };
        g_nregions = 1;
        return;
    }
    const float lw = k_split - k_band_x0, rw = k_band_x1 - k_split;
    const float s = std::min({ (H - gap) / (2 * band), W / lw, W / rw });
    const float h = band * s, y = top + (H - (2 * h + gap)) / 2;
    g_regions[0] = { k_band_x0, k_band_y0, k_split, k_band_y1,
                     (uint16_t)((W - lw * s) / 2), (uint16_t)y, (uint16_t)(lw * s), (uint16_t)h };
    g_regions[1] = { k_split, k_band_y0, k_band_x1, k_band_y1,
                     (uint16_t)((W - rw * s) / 2), (uint16_t)(y + h + gap), (uint16_t)(rw * s), (uint16_t)h };
    g_nregions = 2;
}

}  // namespace

extern "C" void ff7_companion_set_area(int top, int bottom) { g_area_top = top; g_area_bottom = bottom; }

// The layout now on the bottom screen, for CompanionView's touch mapping (JSON array of
// [sx0, sy0, sx1, sy1, dx, dy, dw, dh]); empty outside battle.
std::string ffnx_companion_layout_json() {
    if (!g_active) return "[]";
    std::string j = "[";
    char b[160];
    for (int i = 0; i < g_nregions; i++) {
        const Region &r = g_regions[i];
        snprintf(b, sizeof b, "%s[%.1f,%.1f,%.1f,%.1f,%u,%u,%u,%u]", i ? "," : "", r.sx0, r.sy0, r.sx1, r.sy1, r.dx, r.dy, r.dw, r.dh);
        j += b;
    }
    return j + "]";
}

// From Renderer::show, after a frame: set up the bottom screen's views for the next one.
void ffnx_companion_frame_begin() {
    g_frame++;
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
    g_extra = 0;
    // a list window seen last frame keeps the wide layout (a few frames of grace so a list
    // that redraws every other frame does not flip the layout)
    if (g_wide_seen) g_wide_hold = 8;
    else if (g_wide_hold > 0) g_wide_hold--;
    g_wide = g_wide_hold > 0;
    g_wide_seen = false;
    if (!g_active) return;

    layout();
    bgfx::setViewFrameBuffer(k_view_clear, g_fb);
    bgfx::setViewRect(k_view_clear, 0, 0, g_w, g_h);
    bgfx::setViewClear(k_view_clear, BGFX_CLEAR_COLOR | BGFX_CLEAR_DEPTH, 0x000000ff, 1.0f);
    bgfx::touch(k_view_clear);
    for (int i = 0; i < g_nregions; i++) set_region_view((uint16_t)(k_view + i), g_regions[i]);
}

// A 2D draw in battle: does it belong to the bottom screen?
void ffnx_companion_route_2d(const struct nvertex *v, uint32_t n) {
    g_route = false;
    if (!v || !n) return;
    if (n == 4) track_hand(v);
    if (!g_active) return;
    for (uint32_t i = 0; i < n; i++)
        if (v[i]._.y < k_band_y0 - 0.5f) return;
    // A window across the split (the Magic/Item/Limit lists) means the wide layout: on a
    // command turn no window does (name window ends at 263, status window starts at 268).
    // Windows come as quads, often batched: test each one.
    for (uint32_t q = 0; q + 4 <= n && !g_wide_seen; q += 4) {
        float x0 = 1e9f, x1 = -1e9f;
        for (uint32_t i = q; i < q + 4; i++) { x0 = std::min(x0, v[i]._.x); x1 = std::max(x1, v[i]._.x); }
        if (x0 < k_split - 20.0f && x1 > k_split + 20.0f) g_wide_seen = true;
    }
    g_route = true;
}

// Renderer::draw: the first view for a routed draw (false: not routed) ...
bool ffnx_companion_take_draw(uint16_t *view) {
    if (!g_route) return false;
    g_route = false;
    *view = k_view;
    g_extra = g_nregions - 1;
    return true;
}

// ... and the other regions' views, submitted first with the bindings kept.
bool ffnx_companion_extra_view(uint16_t *view) {
    if (g_extra <= 0) return false;
    *view = (uint16_t)(k_view + g_extra);
    g_extra--;
    return true;
}

// ---- the steering, run once per frame on the game thread (companion.cpp) ------------------
extern "C" void ff7_platform_pad_inject(unsigned short buttons);   // src/android
namespace {
constexpr unsigned short PAD_UP = 0x0001, PAD_DOWN = 0x0002, PAD_LEFT = 0x0004, PAD_RIGHT = 0x0008;
constexpr unsigned short PAD_OK = 0x2000, PAD_CANCEL = 0x1000;     // Circle, Cross
constexpr int k_hold = 4, k_gap = 6, k_max_steps = 24;
struct Steer {
    bool on = false;
    float tx = 0, ty = 0;              // the tap, in the game's 2D coordinates
    int steps = 0;
    int phase = 0;                     // 0 decide, 1 holding, 2 released (waiting)
    int timer = 0;
    bool final_press = false;          // the press now held is the confirm
} g_steer;
unsigned short g_press_once;           // a lone button press (Back), outside the steering
int g_press_timer;

void press(unsigned short b, bool final) {
    ff7_platform_pad_inject(b);
    g_steer.phase = 1; g_steer.timer = k_hold; g_steer.final_press = final;
}
}  // namespace

// A tap on the bottom screen's battle windows (game 2D coordinates), or Back.
extern "C" void ff7_companion_battle_tap(float x, float y) {
    g_steer = Steer{};
    g_steer.on = true; g_steer.tx = x; g_steer.ty = y;
}
extern "C" void ff7_companion_battle_back() { g_steer = Steer{}; g_press_once = PAD_CANCEL; g_press_timer = 0; }

extern "C" void ff7_companion_battle_tick() {
    if (g_press_once) {                // Back: hold, then let go
        if (g_press_timer == 0) ff7_platform_pad_inject(g_press_once);
        if (++g_press_timer > k_hold) { ff7_platform_pad_inject(0); g_press_once = 0; }
        return;
    }
    Steer &s = g_steer;
    if (!s.on) return;
    if (s.phase == 1) {                // holding a button
        if (--s.timer > 0) return;
        ff7_platform_pad_inject(0);
        if (s.final_press) { s.on = false; return; }
        s.phase = 2; s.timer = k_gap;
        return;
    }
    if (s.phase == 2 && --s.timer > 0) return;
    s.phase = 0;
    const bool menu_hand = g_frame - g_hand_frame <= 3 && g_hand_y >= k_band_y0 - 0.5f;
    if (s.steps++ >= k_max_steps) { s.on = false; return; }
    // No menu hand in the windows: choosing a target on the top screen (that hand is not
    // tracked) -- the tap confirms it. A hand that vanished mid-way (a list opened) ends it.
    if (!menu_hand) {
        if (s.steps == 1) press(PAD_OK, true);
        else s.on = false;
        return;
    }
    // the hand sits left of its item, pointing right: its row is its vertical middle, the
    // item starts at its tip
    const float hy = g_hand_y + 13.0f, tip = g_hand_x + 48.0f;
    if (s.tx < 0) { s.on = false; return; }             // a tap beside the windows: nothing
    if (s.ty < hy - 9.0f) { press(PAD_UP, false); return; }
    if (s.ty > hy + 9.0f) { press(PAD_DOWN, false); return; }
    if (s.tx < g_hand_x - 4.0f) { press(PAD_LEFT, false); return; }
    if (s.tx > tip + 90.0f) { press(PAD_RIGHT, false); return; }
    press(PAD_OK, true);
}
