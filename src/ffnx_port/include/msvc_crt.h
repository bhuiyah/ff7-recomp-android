// msvc_crt.h - MSVC C runtime names used by FFNx's dependencies (openpsf), on POSIX.
// Force-included into those libraries; FFNx itself gets them through the windows.h shim.
#pragma once
#include <errno.h>
#include <stdio.h>
#include <string.h>
#include <strings.h>
#ifndef _stricmp
#define _stricmp strcasecmp
#endif
#ifndef _strnicmp
#define _strnicmp strncasecmp
#endif
#define _byteswap_ulong(x) __builtin_bswap32(x)
#define _byteswap_ushort(x) __builtin_bswap16(x)
// openpsf opens hebios.bin and the .psf/.minipsf/.psflib files with fopen_s: through the game's
// path mapping and the mod overlay (win32_compat.cpp), as FFNx's own file calls are, or a mod's
// music/hebios.bin is never found and its PSF music stays silent (GitHub #48). Not a fopen
// macro: psflib has a callback member named fopen.
#ifndef FFNX_HAVE_FOPEN_S
#define FFNX_HAVE_FOPEN_S
#ifdef __cplusplus
extern "C"
#endif
FILE *ffnx_fopen(const char *path, const char *mode);
static inline int fopen_s(FILE **f, const char *name, const char *mode) { *f = ffnx_fopen(name, mode); return *f ? 0 : errno; }
#endif
