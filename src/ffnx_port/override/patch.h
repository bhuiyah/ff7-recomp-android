/****************************************************************************/
//    FF7Recomp replacement for FFNx's patch.h (copied over external/FFNx/src/patch.h
//    by tools/ffnx_port.py overrides). Same API, recomp semantics:
//
//    FFNx patches the game by writing x86 into its code. Lifted code never re-reads its
//    code bytes, and a native FFNx function has no 32-bit address to jump to, so here:
//      replace_function / replace_call / replace_call_function
//          typed templates: the native replacement gets a guest-callable thunk (gfn) and
//          the request is recorded in the hook table (src/ffnx_port/patch_recomp.cpp),
//          which the runtime installs as entry overrides / call-site slots.
//      patch_code_* / memcpy_code / memset_code
//          write memory as before (fully effective for data); writes into .text are
//          also recorded, because they only take effect after a re-lift.
//      get_relative_call / get_absolute_value
//          unchanged: the original code bytes are mapped at their real addresses.
//
//    Derived from FFNx src/patch.h (GPL-3.0).
/****************************************************************************/
#pragma once

#include <stdint.h>
#include <type_traits>
#include <windows.h>
#include "ffnx_guest.h"

namespace ffnx_recomp {
// host_va: guest address of a guest-callable thunk for the native replacement, or a guest
// function address (FFNx sometimes redirects to another game function).
uint32_t replace_function_va(uint32_t offset, uint32_t host_va, const char *what);
void replace_call_va(uint32_t offset, uint32_t host_va, const char *what);
uint32_t replace_call_function_va(uint32_t offset, uint32_t host_va, const char *what);

template <typename F> uint32_t guest_va_of(F *f) { return guest::gfn<F>(f).va(); }
inline uint32_t guest_va_of(void *p) {
    uintptr_t a = reinterpret_cast<uintptr_t>(p);
    if (a > 0xFFFFFFFFu) guest::fatal("untyped native function given to a patch API (add a typed call site)", a);
    return static_cast<uint32_t>(a);
}
}  // namespace ffnx_recomp

template <typename F>
inline uint32_t replace_function(uint32_t offset, F *func) {
    return ffnx_recomp::replace_function_va(offset, ffnx_recomp::guest_va_of(func), "replace_function");
}
template <typename F>
inline void replace_call(uint32_t offset, F *func) {
    ffnx_recomp::replace_call_va(offset, ffnx_recomp::guest_va_of(func), "replace_call");
}
template <typename F>
inline uint32_t replace_call_function(uint32_t offset, F *func) {
    return ffnx_recomp::replace_call_function_va(offset, ffnx_recomp::guest_va_of(func), "replace_call_function");
}
// FFNx also passes game addresses (integers) as replacements.
inline uint32_t replace_function(uint32_t offset, uint32_t game_va) {
    return ffnx_recomp::replace_function_va(offset, game_va, "replace_function");
}
inline void replace_call(uint32_t offset, uint32_t game_va) {
    ffnx_recomp::replace_call_va(offset, game_va, "replace_call");
}
inline uint32_t replace_call_function(uint32_t offset, uint32_t game_va) {
    return ffnx_recomp::replace_call_function_va(offset, game_va, "replace_call_function");
}

// Can also unreplace a call_function
void unreplace_function(uint32_t func);
// Replace again a function unreplaced before
void rereplace_function(uint32_t func);
void unreplace_functions();

uint32_t get_relative_call(uint32_t base, uint32_t offset);
uint32_t get_absolute_value(uint32_t base, uint32_t offset);
void patch_code_char(uint32_t offset, char r);
void patch_code_byte(uint32_t offset, unsigned char r);
void patch_code_word(uint32_t offset, WORD r);
void patch_code_short(uint32_t offset, short r);
void patch_code_dword(uint32_t offset, DWORD r);
void patch_code_int(uint32_t offset, int r);
void patch_code_uint(uint32_t offset, uint32_t r);
void patch_code_float(uint32_t offset, float r);
void patch_code_double(uint32_t offset, double r);
void memcpy_code(uint32_t offset, const void *data, uint32_t size);
void memset_code(uint32_t offset, uint32_t val, uint32_t size);

namespace ffnx_recomp {
void note_code_write(uint32_t offset, uint32_t size, const char *what);
void note_divide_floor(uint32_t offset, int before, int divisor, int kept);

// FF7Recomp: per-frame code stepping (the 120 FPS mode, where battle runs 8 ticks per
// original one). A per-frame step with no whole value at the frame multiplier (6/8 per tick)
// is rewritten every frame to floor or floor+1 so that it adds up exactly; a choice between two
// instructions of the same length alternates frame by frame. Every value a site can take is
// declared in the re-lift manifest ("code_variants"), so the lifted code has each one and picks
// it by the bytes in memory.
bool fractional_steps();      // on at every raised frame rate (30/60/120 FPS modes)
void declare_code_variant(uint32_t offset, const void *bytes, uint32_t size);
void step_fraction(uint32_t offset, uint32_t size, long long numer, int denom, const char *what);
void step_alternate(uint32_t offset, const void *even, const void *odd, uint32_t size);
void step_mirror(uint32_t offset, uint32_t var);   // a stepped immediate stored once: step the variable too
void verify_code_writes();    // FFNx's code writes so far: switch unlifted ones to the interpreter now
void step_code();             // once per game frame (ff7_limit_fps)
}

template <typename T>
void patch_multiply_code(uint32_t offset, int multiplier) {
    ffnx_recomp::note_code_write(offset, sizeof(T), "patch_multiply_code");
    *(T *)(uintptr_t)offset = (*(T *)(uintptr_t)offset) * (T)multiplier;
}

template <typename T>
void patch_divide_code(uint32_t offset, int multiplier) {
    const T before = *(T *)(uintptr_t)offset;
    if constexpr (std::is_integral_v<T>) {
        // FF7Recomp: an inexact quotient is stepped (floor / floor+1 per frame) in the
        // 120 FPS mode instead of truncated.
        if (ffnx_recomp::fractional_steps() && before % (T)multiplier != 0) {
            ffnx_recomp::step_fraction(offset, sizeof(T), (long long)before, multiplier, "patch_divide_code");
            return;
        }
    }
    ffnx_recomp::note_code_write(offset, sizeof(T), "patch_divide_code");
    T after = before / (T)multiplier;
    // FF7Recomp: a per-frame step divided past zero freezes what it steps (a text box at x4
    // in the 120 FPS mode). Keep the smallest step instead, and name the site: those want
    // a finer fix (the step at x4 then runs up to twice its intended speed).
    if (after == 0 && before != 0) {
        after = before > 0 ? (T)1 : (T)-1;
        ffnx_recomp::note_divide_floor(offset, (int)before, multiplier, (int)after);
    }
    *(T *)(uintptr_t)offset = after;
}

void *member_func_to_ptr(char i, ...);
