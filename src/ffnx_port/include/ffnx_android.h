// ffnx_android.h - small platform helpers FFNx's Android branches call (patches/ffnx).
#pragma once
#include <windows.h>
#include "ff7_boot.h"

// FFNx's wine.h: only consulted when WINELOADER is set, which it never is here.
inline const char *GetWineVersion() { return "Wine is not detected."; }

// A copy of `s` in guest memory, for string fields of game structures.
char *ffnx_android_guest_string(const char *s);

// Register a window class with the given guest class name and create the game's window
// through win32hle, with FFNx's window procedure as its WndProc. Returns the HWND.
HWND ffnx_android_create_window(const char *guest_class, const char *guest_title,
                                LRESULT (*wndproc)(HWND, UINT, WPARAM, LPARAM), int width, int height);
