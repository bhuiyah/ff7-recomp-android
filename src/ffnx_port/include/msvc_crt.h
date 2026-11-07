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
#ifndef FFNX_HAVE_FOPEN_S
#define FFNX_HAVE_FOPEN_S
static inline int fopen_s(FILE **f, const char *name, const char *mode) { *f = fopen(name, mode); return *f ? 0 : errno; }
#endif
