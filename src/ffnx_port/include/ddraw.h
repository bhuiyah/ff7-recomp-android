// ddraw.h - the DirectDraw types FFNx's game-structure headers use, in 32-bit layout.
// DDSURFACEDESC and DDCAPS_DX5 are embedded by value in game memory (game_obj), so their
// sizes must match 32-bit Windows exactly (108 and 364 bytes; checked by
// tests/ffnx_layout). Interfaces are only ever referenced through guest pointers.
#pragma once
#include <windows.h>
#include "ffnx_guest.h"

struct IDirectDraw; struct IDirectDraw2; struct IDirectDraw4; struct IDirectDraw7;
struct IDirectDrawSurface; struct IDirectDrawSurface4; struct IDirectDrawSurface7;
struct IDirectDrawClipper; struct IDirectDrawPalette;
struct IDirect3D; struct IDirect3DDevice; struct IDirect3DExecuteBuffer; struct IDirect3DViewport;
typedef IDirectDraw *LPDIRECTDRAW;
typedef IDirectDraw2 *LPDIRECTDRAW2;
typedef IDirectDrawSurface *LPDIRECTDRAWSURFACE;
typedef IDirectDrawClipper *LPDIRECTDRAWCLIPPER;
typedef IDirectDrawPalette *LPDIRECTDRAWPALETTE;

typedef struct _DDCOLORKEY { DWORD dwColorSpaceLowValue, dwColorSpaceHighValue; } DDCOLORKEY;
typedef struct _DDSCAPS { DWORD dwCaps; } DDSCAPS;
typedef struct _DDPIXELFORMAT {
    DWORD dwSize, dwFlags, dwFourCC;
    union { DWORD dwRGBBitCount; DWORD dwYUVBitCount; DWORD dwZBufferBitDepth; DWORD dwAlphaBitDepth; };
    union { DWORD dwRBitMask; DWORD dwYBitMask; };
    union { DWORD dwGBitMask; DWORD dwUBitMask; };
    union { DWORD dwBBitMask; DWORD dwVBitMask; };
    union { DWORD dwRGBAlphaBitMask; DWORD dwYUVAlphaBitMask; DWORD dwRGBZBitMask; };
} DDPIXELFORMAT, *LPDDPIXELFORMAT;

typedef struct _DDSURFACEDESC {
    DWORD dwSize, dwFlags, dwHeight, dwWidth;
    union { LONG lPitch; DWORD dwLinearSize; };
    DWORD dwBackBufferCount;
    union { DWORD dwMipMapCount; DWORD dwZBufferBitDepth; DWORD dwRefreshRate; };
    DWORD dwAlphaBitDepth, dwReserved;
    gptr<void> lpSurface;                     // a guest pointer: this struct lives in game memory
    DDCOLORKEY ddckCKDestOverlay, ddckCKDestBlt, ddckCKSrcOverlay, ddckCKSrcBlt;
    DDPIXELFORMAT ddpfPixelFormat;
    DDSCAPS ddsCaps;
} DDSURFACEDESC, *LPDDSURFACEDESC;
static_assert(sizeof(DDSURFACEDESC) == 108, "DDSURFACEDESC must match 32-bit Windows");

typedef struct _DDCAPS_DX5 {
    DWORD dwSize, dwCaps, dwCaps2, dwCKeyCaps, dwFXCaps, dwFXAlphaCaps, dwPalCaps, dwSVCaps;
    DWORD dwAlphaBltConstBitDepths, dwAlphaBltPixelBitDepths, dwAlphaBltSurfaceBitDepths;
    DWORD dwAlphaOverlayConstBitDepths, dwAlphaOverlayPixelBitDepths, dwAlphaOverlaySurfaceBitDepths;
    DWORD dwZBufferBitDepths, dwVidMemTotal, dwVidMemFree, dwMaxVisibleOverlays, dwCurrVisibleOverlays;
    DWORD dwNumFourCCCodes, dwAlignBoundarySrc, dwAlignSizeSrc, dwAlignBoundaryDest, dwAlignSizeDest, dwAlignStrideAlign;
    DWORD dwRops[8];
    DDSCAPS ddsCaps;
    DWORD dwMinOverlayStretch, dwMaxOverlayStretch, dwMinLiveVideoStretch, dwMaxLiveVideoStretch;
    DWORD dwMinHwCodecStretch, dwMaxHwCodecStretch, dwReserved1, dwReserved2, dwReserved3;
    DWORD dwSVBCaps, dwSVBCKeyCaps, dwSVBFXCaps, dwSVBRops[8];
    DWORD dwVSBCaps, dwVSBCKeyCaps, dwVSBFXCaps, dwVSBRops[8];
    DWORD dwSSBCaps, dwSSBCKeyCaps, dwSSBFXCaps, dwSSBRops[8];
    DWORD dwMaxVideoPorts, dwCurrVideoPorts, dwSVBCaps2;
    DWORD dwNLVBCaps, dwNLVBCaps2, dwNLVBCKeyCaps, dwNLVBFXCaps, dwNLVBRops[8];
} DDCAPS_DX5, *LPDDCAPS_DX5;
static_assert(sizeof(DDCAPS_DX5) == 364, "DDCAPS_DX5 must match 32-bit Windows");
typedef DDCAPS_DX5 DDCAPS;

#define DD_OK S_OK
#define DDSD_CAPS 0x1u
#define DDSD_HEIGHT 0x2u
#define DDSD_WIDTH 0x4u
#define DDSD_PITCH 0x8u
#define DDSD_PIXELFORMAT 0x1000u
#define DDSD_LPSURFACE 0x800u
#define DDPF_RGB 0x40u
#define DDPF_ALPHAPIXELS 0x1u
#define DDSCAPS_PRIMARYSURFACE 0x200u
#define DDSCAPS_BACKBUFFER 0x4u
#define DDSCAPS_OFFSCREENPLAIN 0x40u
#define DDSCAPS_SYSTEMMEMORY 0x800u
