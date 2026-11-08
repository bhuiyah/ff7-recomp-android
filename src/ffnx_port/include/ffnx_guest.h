// ffnx_guest.h - included by FFNx's converted game-structure headers (tools/ffnx_port.py).
// Brings the guest-layout member types into FFNx's (global) namespace.
#pragma once
#include "guest_abi.h"
#include <new>
#include <utility>

using guest::gptr;
using guest::gfn;
using guest::gfn_stdcall;
using guest::gfn_thiscall;

// The 32-bit value the game should hold for something of FFNx's: a native function becomes
// a guest-callable thunk (cdecl), guest memory its own address. FFNx writes such values
// into game tables and code with patch_code_dword(addr, (DWORD)&fn), which on a 64-bit
// host would truncate the pointer; tools/ffnx_edit_android.py rewrites those calls to this.
template <typename T> inline uint32_t ffnx_guest_va(T *p) {
    if constexpr (std::is_function_v<T>) return p ? guest::host_pool<T>::va_for(p) : 0;
    else return guest::to_va(p);
}

// A guest-memory copy of n elements in a per-call-site scratch buffer that grows as needed
// and is reused (single game thread). For FFNx's temporary copies of game data that end up
// behind gptr members of a native struct copy (gl.cpp's facial vertices, ...).
template <typename T, int Site> inline T *ffnx_guest_scratch(const T *data, size_t n) {
    static T *buf = nullptr;
    static size_t cap = 0;
    if (n > cap) {
        if (buf) guest_free(buf);
        cap = n + n / 2 + 16;
        buf = static_cast<T *>(guest_malloc(cap * sizeof(T)));
        if (!buf) guest::fatal("ffnx_guest_scratch: guest heap exhausted", cap * sizeof(T));
    }
    if (n) std::memcpy(buf, data, n * sizeof(T));
    return buf;
}

// FFNx globals that game code reads (through FFNx's code patches) live in a fixed guest
// block (src/ffnx_port/ffnx_guest_static.cpp); tools/ffnx_edit_android.py turns their
// definitions into references to these.
void *ffnx_guest_static_alloc(size_t n, size_t align);
template <typename T, typename... A> inline T &ffnx_guest_static(A &&...a) {
    return *new (ffnx_guest_static_alloc(sizeof(T), alignof(T))) T(std::forward<A>(a)...);
}
template <typename T, size_t N> inline T (&ffnx_guest_static_copy(const T (&init)[N]))[N] {
    auto *p = static_cast<T (*)[N]>(ffnx_guest_static_alloc(sizeof(T[N]), alignof(T)));
    std::memcpy(*p, init, sizeof(T[N]));
    return *p;
}
template <typename T, size_t N> inline T (&ffnx_guest_static_zero())[N] {
    return *static_cast<T (*)[N]>(ffnx_guest_static_alloc(sizeof(T[N]), alignof(T)));
}
