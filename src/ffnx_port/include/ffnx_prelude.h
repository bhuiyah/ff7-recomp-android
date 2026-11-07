// ffnx_prelude.h - force-included into every FFNx source (cmake/ffnx.cmake).
// MSVC's standard headers pull many others in transitively; FFNx relies on that (std::list,
// std::find, POSIX-ish read/open...). libc++ does not, so they are included here once.
#pragma once
#ifdef __cplusplus
#include <algorithm>
#include <array>
#include <cmath>
#include <cstring>
#include <functional>
#include <iterator>
#include <list>
#include <map>
#include <memory>
#include <numeric>
#include <set>
#include <string>
#include <unordered_map>
#include <unordered_set>
#include <vector>
// MSVC finds these unqualified through its headers; FFNx calls them that way.
using std::find;
using std::distance;
// The Win32 shim (types, MSVC CRT names) everywhere, as windows.h is on Windows for FFNx.
#include <windows.h>
// Guest-layout types (gptr/gfn) for every FFNx header, whatever its include order.
#include "ffnx_guest.h"
#endif
#include <fcntl.h>
#include <stdio.h>
#include <unistd.h>
#include <sys/stat.h>

// FFNx opens files with the paths the game uses ("C:\FF7\data\sound\audio.fmt",
// "data\movies\...", "shaders/..."): every file call goes through the game's own path
// mapping (src/hle/ff7_paths.c: drive dropped, slashes, case-insensitive match).
// Function-like macros, so only calls are redirected (struct stat, members named access...).
#ifdef __cplusplus
extern "C" {
#endif
FILE *ffnx_fopen(const char *path, const char *mode);
int ffnx_stat(const char *path, struct stat *st);
int ffnx_access(const char *path, int mode);
const char *hle_host_path(const char *guest, char *buf, unsigned n);   // src/hle/ff7_paths.c
#ifdef __cplusplus
}
#endif
#define fopen(p, m) ffnx_fopen(p, m)
#define stat(p, s) ffnx_stat(p, s)
#define access(p, m) ffnx_access(p, m)
