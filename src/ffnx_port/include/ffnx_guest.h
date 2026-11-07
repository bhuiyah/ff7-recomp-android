// ffnx_guest.h - included by FFNx's converted game-structure headers (tools/ffnx_port.py).
// Brings the guest-layout member types into FFNx's (global) namespace.
#pragma once
#include "guest_abi.h"

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
