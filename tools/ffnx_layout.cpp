// Prints the 32-bit layout of the FFNx game structures the bridge depends on.
// Build for i686-windows with FFNx's headers so offsets match the shipping DLL:
//   source tools/vsenv.sh x86
//   clang-cl /nologo /std:c++20 /EHsc -m32 /I external/FFNx/src tools/ffnx_layout.cpp /Fe:build/layout/ffnx_layout.exe
#include <windows.h>
#include <stdint.h>
#include <stddef.h>
#include <stdio.h>
#include "ff7.h"

static_assert(sizeof(void *) == 4, "build this for x86");

#define OFF(T, m) printf("%-40s 0x%04zX\n", #T "." #m, offsetof(T, m))
#define SIZE(T)   printf("%-40s 0x%04zX\n", "sizeof " #T, sizeof(T))

int main() {
    SIZE(struct ff7_game_obj);
    OFF(struct ff7_game_obj, externals);
    SIZE(struct dll_gfx_externals);
    OFF(struct dll_gfx_externals, assert_free);
    OFF(struct dll_gfx_externals, assert_malloc);
    OFF(struct dll_gfx_externals, assert_calloc);
    SIZE(struct ff7_gfx_driver);
    OFF(struct ff7_gfx_driver, init);
    OFF(struct ff7_gfx_driver, field_EC);
    return 0;
}
