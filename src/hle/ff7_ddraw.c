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

/* ---- IDirectDrawSurface
 * State after [vtable][refs]: width, height, bpp, pitch, pixels (guest VA), caps,
 * attached back buffer, clipper, palette. Pixels are guest memory, so Lock hands the
 * game a pointer it can write through directly. */
typedef struct {
    uint32_t w, h, bpp, pitch, pixels, caps, back, clipper, palette;
} surf_t;
#define SURF(obj) ((surf_t *)FF7_COM_STATE(obj))

#define DDSD_CAPS            0x1u
#define DDSD_HEIGHT          0x2u
#define DDSD_WIDTH           0x4u
#define DDSD_PITCH           0x8u
#define DDSD_BACKBUFFERCOUNT 0x20u
#define DDSD_LPSURFACE       0x800u
#define DDSD_PIXELFORMAT     0x1000u
#define DDSCAPS_BACKBUFFER   0x4u
#define DDSCAPS_PRIMARY      0x200u
#define DDBLT_COLORFILL      0x400u

static uint32_t g_surf_vt;
static uint32_t surf_new(uint32_t w, uint32_t h, uint32_t bpp, uint32_t caps);

/* Fill a DDSURFACEDESC for surface `s` (keeping the caller's dwSize). */
static void surf_desc(uint32_t s, uint32_t d) {
    surf_t *st = SURF(s);
    fill_mode(d, st->w, st->h, st->bpp);
    W32(d + 0x04) |= DDSD_LPSURFACE;
    W32(d + 0x10) = st->pitch;
    W32(d + 0x24) = st->pixels;
    W32(d + 0x68) = st->caps;
}

static void sf_QueryInterface(void) {               /* (this, riid, ppv) */
    uint32_t self = A32(0), riid = A32(1), ppv = A32(2);
    uint32_t d1 = riid ? W32(riid) : 0;
    /* IID_IDirectDrawSurface {6C14DB81}, IDirectDrawSurface2 {57805885}, 3 {DA044E00} */
    if (ppv && (d1 == 0x6C14DB81u || d1 == 0x57805885u || d1 == 0xDA044E00u)) {
        W32(ppv) = self;
        FF7_COM_REFS(self)++;
        RET(DD_OK, 3);
    }
    fprintf(stderr, "[ddraw] IDirectDrawSurface::QueryInterface(%08X) -> E_NOINTERFACE\n", d1);
    if (ppv) W32(ppv) = 0;
    RET(E_NOINTERFACE, 3);
}
static void sf_AddRef(void)  { uint32_t s = A32(0); RET(++FF7_COM_REFS(s), 1); }
static void sf_Release(void) { uint32_t s = A32(0); uint32_t r = FF7_COM_REFS(s) ? --FF7_COM_REFS(s) : 0; RET(r, 1); }
static void sf_GetSurfaceDesc(void) {               /* (this, desc) */
    if (!A32(1)) RET(DDERR_INVALIDPARAMS, 2);
    surf_desc(A32(0), A32(1));
    RET(DD_OK, 2);
}
static void sf_Lock(void) {                         /* (this, rect, desc, flags, event) */
    uint32_t s = A32(0), rect = A32(1), d = A32(2);
    if (!d) RET(DDERR_INVALIDPARAMS, 5);
    surf_desc(s, d);
    if (rect) {                                     /* a sub-rectangle: point at its corner */
        surf_t *st = SURF(s);
        W32(d + 0x24) = st->pixels + W32(rect + 4) * st->pitch + W32(rect) * (st->bpp / 8);
    }
    RET(DD_OK, 5);
}
static void sf_Unlock(void)  { RET(DD_OK, 2); }     /* (this, ptr) */
static void sf_IsLost(void)  { RET(DD_OK, 1); }
static void sf_Restore(void) { RET(DD_OK, 1); }
static void sf_GetCaps(void) { uint32_t c = A32(1); if (c) W32(c) = SURF(A32(0))->caps; RET(DD_OK, 2); }
static void sf_GetPixelFormat(void) {               /* (this, DDPIXELFORMAT*) */
    uint32_t pf = A32(1);
    if (!pf) RET(DDERR_INVALIDPARAMS, 2);
    uint32_t tmp = (uint32_t)(uintptr_t)guest_calloc(1, 108);
    W32(tmp) = 108;
    fill_mode(tmp, 1, 1, SURF(A32(0))->bpp);
    memcpy((void *)(uintptr_t)pf, (void *)(uintptr_t)(tmp + 0x48), 32);
    guest_free((void *)(uintptr_t)tmp);
    RET(DD_OK, 2);
}
static void sf_GetAttachedSurface(void) {           /* (this, caps*, surf**) */
    uint32_t s = A32(0), out = A32(2);
    surf_t *st = SURF(s);
    if (!out || !st->back) RET(0x887600FFu, 3);      /* DDERR_NOTFOUND */
    W32(out) = st->back;
    FF7_COM_REFS(st->back)++;
    RET(DD_OK, 3);
}
/* Colour keys are stored per surface (low/high), by DDCKEY_* flag. Nothing reads them
 * back from the 2D path yet; FFNx handles transparency in its renderer. */
static void sf_SetColorKey(void) {                  /* (this, flags, DDCOLORKEY*) */
    uint32_t ck = A32(2);
    surf_t *st = SURF(A32(0));
    (void)st;
    if (ck) fprintf(stderr, "[ddraw] SetColorKey(flags 0x%X, 0x%X-0x%X)\n", A32(1), W32(ck), W32(ck + 4));
    RET(DD_OK, 3);
}
static void sf_GetColorKey(void) {                  /* (this, flags, DDCOLORKEY*) */
    uint32_t ck = A32(2);
    if (ck) { W32(ck) = 0; W32(ck + 4) = 0; }
    RET(DD_OK, 3);
}
static void sf_SetClipper(void) { SURF(A32(0))->clipper = A32(1); RET(DD_OK, 2); }
static void sf_SetPalette(void) { SURF(A32(0))->palette = A32(1); RET(DD_OK, 2); }
/* Flip(this, target, flags): presenting is the renderer's job once FFNx draws; here the
 * primary and back buffer just trade pixels so a 2D path sees normal flip semantics. */
static void sf_Flip(void) {
    surf_t *p = SURF(A32(0));
    if (p->back) { surf_t *b = SURF(p->back); uint32_t t = p->pixels; p->pixels = b->pixels; b->pixels = t; }
    RET(DD_OK, 3);
}
/* Copy src rect to dst at (dx,dy), same pixel size, clipped to both surfaces. */
static void blit(uint32_t dst, uint32_t dx, uint32_t dy, uint32_t src, uint32_t sx, uint32_t sy, uint32_t w, uint32_t h) {
    surf_t *d = SURF(dst), *s = SURF(src);
    uint32_t bpp = d->bpp / 8;
    if (dx >= d->w || dy >= d->h || sx >= s->w || sy >= s->h) return;
    if (w > d->w - dx) w = d->w - dx;
    if (w > s->w - sx) w = s->w - sx;
    if (h > d->h - dy) h = d->h - dy;
    if (h > s->h - sy) h = s->h - sy;
    for (uint32_t y = 0; y < h; y++)
        memmove((uint8_t *)(uintptr_t)(d->pixels + (dy + y) * d->pitch + dx * bpp),
                (uint8_t *)(uintptr_t)(s->pixels + (sy + y) * s->pitch + sx * bpp), (size_t)w * bpp);
}
static void rect_or_full(uint32_t r, uint32_t s, uint32_t *x, uint32_t *y, uint32_t *w, uint32_t *h) {
    if (r) { *x = W32(r); *y = W32(r + 4); *w = W32(r + 8) - *x; *h = W32(r + 12) - *y; }
    else   { *x = 0; *y = 0; *w = SURF(s)->w; *h = SURF(s)->h; }
}
/* Blt(this, dstRect, src, srcRect, flags, DDBLTFX*): colour fill and unscaled copy. */
static void sf_Blt(void) {
    uint32_t dst = A32(0), drect = A32(1), src = A32(2), srect = A32(3), flags = A32(4), fx = A32(5);
    uint32_t dx, dy, dw, dh;
    rect_or_full(drect, dst, &dx, &dy, &dw, &dh);
    if (flags & DDBLT_COLORFILL) {
        surf_t *d = SURF(dst);
        uint32_t color = fx ? W32(fx + 0x50) : 0;   /* DDBLTFX.dwFillColor */
        for (uint32_t y = dy; y < dy + dh && y < d->h; y++)
            for (uint32_t x = dx; x < dx + dw && x < d->w; x++) {
                uint32_t a = d->pixels + y * d->pitch + x * (d->bpp / 8);
                if (d->bpp == 16) *(uint16_t *)(uintptr_t)a = (uint16_t)color;
                else W32(a) = color;
            }
        RET(DD_OK, 6);
    }
    if (src) {
        uint32_t sx, sy, sw, sh;
        rect_or_full(srect, src, &sx, &sy, &sw, &sh);
        if (sw != dw || sh != dh) fprintf(stderr, "[ddraw] Blt %ux%u -> %ux%u: scaling not implemented, copying unscaled\n", sw, sh, dw, dh);
        blit(dst, dx, dy, src, sx, sy, sw < dw ? sw : dw, sh < dh ? sh : dh);
    }
    RET(DD_OK, 6);
}
static void sf_BltFast(void) {                      /* (this, x, y, src, srcRect, trans) */
    uint32_t dst = A32(0), x = A32(1), y = A32(2), src = A32(3), srect = A32(4);
    uint32_t sx, sy, sw, sh;
    if (!src) RET(DDERR_INVALIDPARAMS, 6);
    rect_or_full(srect, src, &sx, &sy, &sw, &sh);
    blit(dst, x, y, src, sx, sy, sw, sh);
    RET(DD_OK, 6);
}

static const ff7_com_method surf_methods[] = {
    { "IDirectDrawSurface::QueryInterface", sf_QueryInterface },
    { "IDirectDrawSurface::AddRef", sf_AddRef },
    { "IDirectDrawSurface::Release", sf_Release },
    { "IDirectDrawSurface::AddAttachedSurface", NULL },
    { "IDirectDrawSurface::AddOverlayDirtyRect", NULL },
    { "IDirectDrawSurface::Blt", sf_Blt },
    { "IDirectDrawSurface::BltBatch", NULL },
    { "IDirectDrawSurface::BltFast", sf_BltFast },
    { "IDirectDrawSurface::DeleteAttachedSurface", NULL },
    { "IDirectDrawSurface::EnumAttachedSurfaces", NULL },
    { "IDirectDrawSurface::EnumOverlayZOrders", NULL },
    { "IDirectDrawSurface::Flip", sf_Flip },
    { "IDirectDrawSurface::GetAttachedSurface", sf_GetAttachedSurface },
    { "IDirectDrawSurface::GetBltStatus", NULL },
    { "IDirectDrawSurface::GetCaps", sf_GetCaps },
    { "IDirectDrawSurface::GetClipper", NULL },
    { "IDirectDrawSurface::GetColorKey", sf_GetColorKey },
    { "IDirectDrawSurface::GetDC", NULL },
    { "IDirectDrawSurface::GetFlipStatus", NULL },
    { "IDirectDrawSurface::GetOverlayPosition", NULL },
    { "IDirectDrawSurface::GetPalette", NULL },
    { "IDirectDrawSurface::GetPixelFormat", sf_GetPixelFormat },
    { "IDirectDrawSurface::GetSurfaceDesc", sf_GetSurfaceDesc },
    { "IDirectDrawSurface::Initialize", NULL },
    { "IDirectDrawSurface::IsLost", sf_IsLost },
    { "IDirectDrawSurface::Lock", sf_Lock },
    { "IDirectDrawSurface::ReleaseDC", NULL },
    { "IDirectDrawSurface::Restore", sf_Restore },
    { "IDirectDrawSurface::SetClipper", sf_SetClipper },
    { "IDirectDrawSurface::SetColorKey", sf_SetColorKey },
    { "IDirectDrawSurface::SetOverlayPosition", NULL },
    { "IDirectDrawSurface::SetPalette", sf_SetPalette },
    { "IDirectDrawSurface::Unlock", sf_Unlock },
    { "IDirectDrawSurface::UpdateOverlay", NULL },
    { "IDirectDrawSurface::UpdateOverlayDisplay", NULL },
    { "IDirectDrawSurface::UpdateOverlayZOrder", NULL },
};

static uint32_t surf_new(uint32_t w, uint32_t h, uint32_t bpp, uint32_t caps) {
    if (!g_surf_vt) g_surf_vt = ff7_com_vtable(surf_methods, (int)(sizeof surf_methods / sizeof surf_methods[0]));
    uint32_t s = ff7_com_new(g_surf_vt, sizeof(surf_t));
    surf_t *st = SURF(s);
    st->w = w; st->h = h; st->bpp = bpp; st->caps = caps;
    st->pitch = (w * (bpp / 8) + 3u) & ~3u;
    st->pixels = (uint32_t)(uintptr_t)guest_calloc(1, (size_t)st->pitch * h);
    return s;
}

/* CreateSurface(this, DDSURFACEDESC*, surf**, outer): a primary takes the display mode;
 * DDSD_BACKBUFFERCOUNT attaches one back buffer (FF7 asks for at most one). */
static void dd_CreateSurface(void) {
    uint32_t d = A32(1), out = A32(2);
    if (!d || !out) RET(DDERR_INVALIDPARAMS, 4);
    uint32_t flags = W32(d + 4), caps = (flags & DDSD_CAPS) ? W32(d + 0x68) : 0;
    uint32_t w = (flags & DDSD_WIDTH) ? W32(d + 0x0C) : FF7_MODE_W;
    uint32_t h = (flags & DDSD_HEIGHT) ? W32(d + 0x08) : FF7_MODE_H;
    uint32_t bpp = (flags & DDSD_PIXELFORMAT) ? W32(d + 0x54) : FF7_MODE_BPP;
    if (caps & DDSCAPS_PRIMARY) { w = FF7_MODE_W; h = FF7_MODE_H; bpp = FF7_MODE_BPP; }
    if (bpp != 16 && bpp != 32) bpp = FF7_MODE_BPP;
    uint32_t s = surf_new(w, h, bpp, caps);
    if ((flags & DDSD_BACKBUFFERCOUNT) && W32(d + 0x14))
        SURF(s)->back = surf_new(w, h, bpp, (caps & ~DDSCAPS_PRIMARY) | DDSCAPS_BACKBUFFER);
    fprintf(stderr, "[ddraw] CreateSurface %ux%ux%u caps 0x%X%s -> 0x%08X\n",
            w, h, bpp, caps, SURF(s)->back ? " +backbuffer" : "", s);
    W32(out) = s;
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
    { "IDirectDraw::CreateSurface", dd_CreateSurface },
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
