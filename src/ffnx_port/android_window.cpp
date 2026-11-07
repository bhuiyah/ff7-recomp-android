// android_window.cpp - see ffnx_android.h.
#include <cstring>
#include "ffnx_android.h"
#include "guest_abi.h"

using namespace guest;

char *ffnx_android_guest_string(const char *s) {
    size_t n = std::strlen(s) + 1;
    char *p = static_cast<char *>(guest_malloc(n));
    std::memcpy(p, s, n);
    return p;
}

HWND ffnx_android_create_window(const char *guest_class, const char *guest_title,
                                LRESULT (*wndproc)(HWND, UINT, WPARAM, LPARAM), int width, int height) {
    // WNDCLASSA in guest memory: win32hle reads it from the guest stack argument.
    WNDCLASSA *wc = static_cast<WNDCLASSA *>(guest_calloc(1, sizeof(WNDCLASSA)));
    wc->lpfnWndProc = gfn<LRESULT(HWND, UINT, WPARAM, LPARAM)>(wndproc).va();
    wc->lpszClassName = va_of(guest_class);
    uint32_t reg = hle_resolve("RegisterClassA");
    uint32_t create = hle_resolve("CreateWindowExA");
    if (!reg || !create) fatal("win32hle has no RegisterClassA/CreateWindowExA", 0);
    gcall<uint32_t>(reg, wc);
    // CreateWindowExA(exStyle, class, title, style, x, y, w, h, parent, menu, hInstance, param)
    HWND hwnd = gcall<uint32_t>(create, 0u, guest_class, guest_title, 0u, 0, 0, width, height, 0u, 0u, 0x00400000u, 0u);
    std::fprintf(stderr, "[ffnx-android] game window 0x%X (%dx%d), WndProc thunk 0x%08X\n",
                 hwnd, width, height, wc->lpfnWndProc);
    return hwnd;
}
