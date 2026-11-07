/*
 * ff7_ddraw.c - the DirectDraw FF7 touches before its graphics driver takes over.
 *
 * FF7 1.02 enumerates DirectDraw devices and creates a DirectDraw object to set up the
 * display, then hands all rendering to the driver (AF3DN.P = FFNx). FFNx does not fake
 * DirectDraw for FF7 (its fake_dd is FF8-only), so on Windows these calls reach the real
 * ddraw.dll. Here there is one display -- the Thor's screen -- and only the methods the
 * game is seen to call are implemented; any other stops the run naming the method.
 */
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include "guest_alloc.h"
#include "win32hle.h"
#include "ff7_com.h"
#include "ff7_hle.h"

#define DD_OK                0u
#define E_NOINTERFACE        0x80004002u
#define DDERR_INVALIDPARAMS  0x80070057u
#define W32(p) (*(uint32_t *)(uintptr_t)(p))

/* The mode FF7 runs in; FFNx renders at the device resolution regardless. */
#define FF7_MODE_W 640
#define FF7_MODE_H 480
#define FF7_MODE_BPP 16

static void dd_trace(const char *what) { fprintf(stderr, "[ddraw] %s\n", what); }

/* ---- IDirectDraw / IDirectDraw2 (same object: IDirectDraw2 adds one trailing method) */
static void dd_QueryInterface(void) {               /* (this, riid, ppv) */
    uint32_t self = A32(0), riid = A32(1), ppv = A32(2);
    if (!ppv) RET(DDERR_INVALIDPARAMS, 3);
    uint32_t d1 = riid ? W32(riid) : 0;
    /* IID_IDirectDraw {6C14DB80-...}, IID_IDirectDraw2 {B3A6F3E0-...}: this object. */
    if (d1 == 0x6C14DB80u || d1 == 0xB3A6F3E0u) {
        W32(ppv) = self;
        FF7_COM_REFS(self)++;
        fprintf(stderr, "[ddraw] IDirectDraw::QueryInterface(%08X-...) -> same object\n", d1);
        RET(DD_OK, 3);
    }
    fprintf(stderr, "[ddraw] IDirectDraw::QueryInterface(%08X-%04X-%04X) -> E_NOINTERFACE\n",
            d1, *(uint16_t *)(uintptr_t)(riid + 4), *(uint16_t *)(uintptr_t)(riid + 6));
    W32(ppv) = 0;
    RET(E_NOINTERFACE, 3);
}
static void dd_AddRef(void)  { uint32_t s = A32(0); RET(++FF7_COM_REFS(s), 1); }
static void dd_Release(void) { uint32_t s = A32(0); uint32_t r = FF7_COM_REFS(s) ? --FF7_COM_REFS(s) : 0; RET(r, 1); }
static void dd_SetCooperativeLevel(void) {          /* (this, hwnd, flags) */
    fprintf(stderr, "[ddraw] SetCooperativeLevel(hwnd 0x%X, flags 0x%X)\n", A32(1), A32(2));
    RET(DD_OK, 3);
}
static void dd_SetDisplayMode(void) {               /* (this, w, h, bpp) */
    fprintf(stderr, "[ddraw] SetDisplayMode(%u x %u x %u)\n", A32(1), A32(2), A32(3));
    RET(DD_OK, 4);
}
static void dd_RestoreDisplayMode(void) { RET(DD_OK, 1); }

/* DDSURFACEDESC: dwSize +0, dwFlags +4, dwHeight +8, dwWidth +0xC, lPitch +0x10,
 * ddpfPixelFormat +0x48 (dwSize, dwFlags, FourCC, RGBBitCount, R, G, B, A masks). */
static void fill_mode(uint32_t d, uint32_t w, uint32_t h, uint32_t bpp) {
    uint32_t size = W32(d) ? W32(d) : 108;
    memset((void *)(uintptr_t)d, 0, size);
    W32(d) = size;
    W32(d + 0x04) = 0x1u | 0x2u | 0x4u | 0x8u | 0x1000u;   /* CAPS|HEIGHT|WIDTH|PITCH|PIXELFORMAT */
    W32(d + 0x08) = h;
    W32(d + 0x0C) = w;
    W32(d + 0x10) = w * (bpp / 8);
    W32(d + 0x48) = 32;
    W32(d + 0x4C) = 0x40u;                                  /* DDPF_RGB */
    W32(d + 0x54) = bpp;
    if (bpp == 16) { W32(d + 0x58) = 0xF800u; W32(d + 0x5C) = 0x07E0u; W32(d + 0x60) = 0x001Fu; }
    else           { W32(d + 0x58) = 0xFF0000u; W32(d + 0x5C) = 0x00FF00u; W32(d + 0x60) = 0x0000FFu; }
}
static void dd_GetDisplayMode(void) {               /* (this, desc) */
    uint32_t d = A32(1);
    if (!d) RET(DDERR_INVALIDPARAMS, 2);
    fill_mode(d, FF7_MODE_W, FF7_MODE_H, FF7_MODE_BPP);
    RET(DD_OK, 2);
}
/* EnumDisplayModes(this, flags, desc, ctx, cb): cb(DDSURFACEDESC*, ctx) per mode. */
static void dd_EnumDisplayModes(void) {
    uint32_t ctx = A32(3), cb = A32(4);
    static const uint32_t modes[][3] = { { 640, 480, 16 }, { 640, 480, 32 } };
    uint32_t d = (uint32_t)(uintptr_t)guest_calloc(1, 108);
    for (unsigned i = 0; i < sizeof modes / sizeof modes[0]; i++) {
        W32(d) = 108;
        fill_mode(d, modes[i][0], modes[i][1], modes[i][2]);
        uint32_t args[2] = { d, ctx };
        if (!hle_call_guest(cb, 2, args)) break;     /* DDENUMRET_CANCEL */
    }
    guest_free((void *)(uintptr_t)d);
    RET(DD_OK, 5);
}
/* GetCaps(this, drv, hel): sizes kept, everything else zero (no hardware blitter). */
static void dd_GetCaps(void) {
    for (int i = 1; i <= 2; i++) {
        uint32_t c = A32(i);
        if (c) { uint32_t n = W32(c); memset((void *)(uintptr_t)c, 0, n); W32(c) = n; }
    }
    RET(DD_OK, 3);
}

/* GetAvailableVidMem(this, DDSCAPS*, total*, free*): plenty, within 32 bits. */
static void dd_GetAvailableVidMem(void) {
    uint32_t total = A32(2), freem = A32(3);
    if (total) W32(total) = 0x10000000u;
    if (freem) W32(freem) = 0x0F000000u;
    RET(DD_OK, 4);
}

/* ---- IDirectDrawClipper: FF7 attaches one to its window and never reads it back. */
static void clip_AddRef(void)  { uint32_t s = A32(0); RET(++FF7_COM_REFS(s), 1); }
static void clip_Release(void) { uint32_t s = A32(0); uint32_t r = FF7_COM_REFS(s) ? --FF7_COM_REFS(s) : 0; RET(r, 1); }
static void clip_SetHWnd(void) { W32(FF7_COM_STATE(A32(0))) = A32(2); RET(DD_OK, 3); }   /* (this, flags, hwnd) */
static void clip_GetHWnd(void) { uint32_t o = A32(1); if (o) W32(o) = W32(FF7_COM_STATE(A32(0))); RET(DD_OK, 2); }
static const ff7_com_method clip_methods[] = {
    { "IDirectDrawClipper::QueryInterface", NULL },
    { "IDirectDrawClipper::AddRef", clip_AddRef },
    { "IDirectDrawClipper::Release", clip_Release },
    { "IDirectDrawClipper::GetClipList", NULL },
    { "IDirectDrawClipper::GetHWnd", clip_GetHWnd },
    { "IDirectDrawClipper::Initialize", NULL },
    { "IDirectDrawClipper::IsClipListChanged", NULL },
    { "IDirectDrawClipper::SetClipList", NULL },
    { "IDirectDrawClipper::SetHWnd", clip_SetHWnd },
};
/* CreateClipper(this, flags, clipper**, outer) */
static void dd_CreateClipper(void) {
    static uint32_t vt;
    uint32_t out = A32(2);
    if (!out) RET(DDERR_INVALIDPARAMS, 4);
    if (!vt) vt = ff7_com_vtable(clip_methods, (int)(sizeof clip_methods / sizeof clip_methods[0]));
    W32(out) = ff7_com_new(vt, 4);
    RET(DD_OK, 4);
}

static const ff7_com_method dd_methods[] = {
    { "IDirectDraw::QueryInterface", dd_QueryInterface },
    { "IDirectDraw::AddRef", dd_AddRef },
    { "IDirectDraw::Release", dd_Release },
    { "IDirectDraw::Compact", NULL },
    { "IDirectDraw::CreateClipper", dd_CreateClipper },
    { "IDirectDraw::CreatePalette", NULL },
    { "IDirectDraw::CreateSurface", NULL },
    { "IDirectDraw::DuplicateSurface", NULL },
    { "IDirectDraw::EnumDisplayModes", dd_EnumDisplayModes },
    { "IDirectDraw::EnumSurfaces", NULL },
    { "IDirectDraw::FlipToGDISurface", NULL },
    { "IDirectDraw::GetCaps", dd_GetCaps },
    { "IDirectDraw::GetDisplayMode", dd_GetDisplayMode },
    { "IDirectDraw::GetFourCCCodes", NULL },
    { "IDirectDraw::GetGDISurface", NULL },
    { "IDirectDraw::GetMonitorFrequency", NULL },
    { "IDirectDraw::GetScanLine", NULL },
    { "IDirectDraw::GetVerticalBlankStatus", NULL },
    { "IDirectDraw::Initialize", NULL },
    { "IDirectDraw::RestoreDisplayMode", dd_RestoreDisplayMode },
    { "IDirectDraw::SetCooperativeLevel", dd_SetCooperativeLevel },
    { "IDirectDraw::SetDisplayMode", dd_SetDisplayMode },
    { "IDirectDraw::WaitForVerticalBlank", NULL },
    { "IDirectDraw2::GetAvailableVidMem", dd_GetAvailableVidMem },
};

/* ---- exports */

/* DirectDrawEnumerateA(cb, ctx): cb(GUID*, desc, name, ctx) once, for the primary display. */
static void d_DirectDrawEnumerateA(void) {
    uint32_t cb = A32(0), ctx = A32(1);
    static uint32_t desc, name;
    if (!desc) { desc = ff7_guest_strdup("Primary Display Driver"); name = ff7_guest_strdup("display"); }
    dd_trace("DirectDrawEnumerateA -> primary display");
    uint32_t args[4] = { 0, desc, name, ctx };
    hle_call_guest(cb, 4, args);
    RET(DD_OK, 2);
}

/* DirectDrawCreate(guid, lplpDD, outer) */
static void d_DirectDrawCreate(void) {
    uint32_t out = A32(1);
    static uint32_t vtable;
    if (!out) RET(DDERR_INVALIDPARAMS, 3);
    if (!vtable) vtable = ff7_com_vtable(dd_methods, (int)(sizeof dd_methods / sizeof dd_methods[0]));
    W32(out) = ff7_com_new(vtable, 16);
    fprintf(stderr, "[ddraw] DirectDrawCreate -> 0x%08X\n", W32(out));
    RET(DD_OK, 3);
}

const win32hle_shim ff7_ddraw[] = {
    { "DirectDrawEnumerateA", d_DirectDrawEnumerateA },
    { "DirectDrawCreate", d_DirectDrawCreate },
    { NULL, NULL }
};
