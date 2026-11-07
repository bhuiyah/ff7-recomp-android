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
#include <unistd.h>
#include <sys/stat.h>
