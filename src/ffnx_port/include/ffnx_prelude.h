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

// toml::parse_file opens its file with std::ifstream, which bypasses the macros below: the
// configs FFNx reads (music/vgmstream/config.toml, sfx/, voice/, ambient/, widescreen, time,
// lighting ...) went unmapped, so a mod's config.toml never loaded. Every FFNx call to
// toml::parse_file goes through the path mapping and mod overlay first.
#ifdef __cplusplus
#include <string_view>
#include <toml++/toml.h>
namespace toml {
inline parse_result ffnx_parse_file(std::string_view path)
{
	std::string p(path);
	char buf[1024];
	const char *host = hle_host_path(p.c_str(), buf, sizeof buf);
	return parse_file(std::string_view(host ? host : p.c_str()));
}
}
#define parse_file ffnx_parse_file
#endif

#define fopen(p, m) ffnx_fopen(p, m)
#define stat(p, s) ffnx_stat(p, s)
#define access(p, m) ffnx_access(p, m)
