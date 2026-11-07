// guest_abi.h - the C++ crossing between native FFNx code and the lifted 32-bit game.
//
// Memory is flat (docs/FFNX_PORT.md): a guest VA zero-extends to the host address of the
// same byte. What differs is width -- game structures hold 4-byte pointers, this process
// uses 8-byte ones -- and the call ABI: lifted code runs on the global register model with
// an explicit guest stack, so neither direction of call is an ordinary C call.
//
//   gptr<T>            a 4-byte guest pointer usable like T*; layouts stay 32-bit
//   gcall<R>(va, ...)  call a lifted (or shimmed) guest function, cdecl/stdcall alike
//   gfn<R(A...)>       a 4-byte guest function pointer; operator() is gcall
//   guest_export<F>    a recomp_func_t the guest can call, unmarshalling cdecl args to F
//
// Everything handed to the guest must be guest memory (guest_alloc / the game's own
// allocator); gptr's constructor from T* checks that.
#pragma once
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <array>
#include <type_traits>
#include <tuple>
#include <utility>

extern "C" {
#include "recomp_types.h"
#include "win32hle.h"
#include "guest_alloc.h"
}

namespace guest {

[[noreturn]] inline void fatal(const char *what, uintptr_t v) {
    std::fprintf(stderr, "[guest_abi] FATAL: %s (0x%llx)\n", what, (unsigned long long)v);
    std::abort();
}

// ---- addresses ---------------------------------------------------------------------

inline uint32_t va_of(const void *p) {
    uintptr_t a = reinterpret_cast<uintptr_t>(p);
    if (a > 0xFFFFFFFFu) fatal("host pointer given to the guest (not guest memory)", a);
    return static_cast<uint32_t>(a);
}
template <typename T> inline T *ptr_of(uint32_t va) { return reinterpret_cast<T *>(static_cast<uintptr_t>(va)); }

// ---- gptr<T>: a guest pointer member --------------------------------------------------

template <typename T>
class gptr {
    uint32_t va_ = 0;
public:
    gptr() = default;
    gptr(std::nullptr_t) {}
    gptr(T *p) : va_(va_of(p)) {}
    static gptr from_va(uint32_t va) { gptr g; g.va_ = va; return g; }

    uint32_t va() const { return va_; }
    T *get() const { return ptr_of<T>(va_); }
    operator T *() const { return get(); }
    // C-style casts FFNx writes on game pointers: to another struct view of the same
    // memory ((ff7_tex_header *)tex_header) and to a 32-bit address ((uint32_t)p).
    template <typename U, typename = std::enable_if_t<!std::is_same_v<U, T>>>
    explicit operator U *() const { return reinterpret_cast<U *>(get()); }
    template <typename I, typename = std::enable_if_t<std::is_integral_v<I>>>
    explicit operator I() const { return static_cast<I>(va_); }
    T *operator->() const { return get(); }
    template <typename U = T, typename = std::enable_if_t<!std::is_void_v<U>>>
    U &operator*() const { return *get(); }
    template <typename U = T, typename = std::enable_if_t<!std::is_void_v<U>>>
    U &operator[](std::ptrdiff_t i) const { return get()[i]; }
    gptr &operator=(T *p) { va_ = p ? va_of(p) : 0; return *this; }
    gptr &operator=(std::nullptr_t) { va_ = 0; return *this; }
    explicit operator bool() const { return va_ != 0; }
    gptr &operator++() { va_ += sizeof(T); return *this; }
    gptr operator+(std::ptrdiff_t n) const { return from_va(va_ + static_cast<uint32_t>(n * sizeof(T))); }
    gptr &operator+=(std::ptrdiff_t n) { va_ += static_cast<uint32_t>(n * sizeof(T)); return *this; }
};
static_assert(sizeof(gptr<int>) == 4, "gptr must stay 4 bytes");

// ---- calling the guest ---------------------------------------------------------------

struct result { uint32_t eax, edx; double st0; };

// Push 32-bit argument words right to left and run the function at `va` (lifted body,
// or a shim). All guest registers are restored afterwards, so the convention (cdecl or
// stdcall) does not matter to the caller; eax, edx and st(0) are captured first.
inline result call_va(uint32_t va, int n, const uint32_t *words) {
    recomp_func_t f = recomp_lookup(va);
    if (!f) f = recomp_lookup_import(va);
    if (!f) fatal("guest call to an address with no lifted function", va);
    mach_enter();
    uint32_t s_eax = g_eax, s_ecx = g_ecx, s_edx = g_edx, s_esp = g_esp;
    uint32_t s_ebx = g_ebx, s_esi = g_esi, s_edi = g_edi, s_ebp = g_ebp;
    double s_st[8]; std::memcpy(s_st, g_st, sizeof s_st);
    int s_top = g_fp_top;
    for (int i = n - 1; i >= 0; i--) PUSH32(g_esp, words[i]);
    PUSH32(g_esp, RECOMP_RETADDR);
    f();
    result r{ g_eax, g_edx, g_st[g_fp_top & 7] };
    g_eax = s_eax; g_ecx = s_ecx; g_edx = s_edx; g_esp = s_esp;
    g_ebx = s_ebx; g_esi = s_esi; g_edi = s_edi; g_ebp = s_ebp;
    std::memcpy(g_st, s_st, sizeof s_st); g_fp_top = s_top;
    mach_leave();
    return r;
}

// Argument -> guest stack words.
template <typename A> inline void put_arg(uint32_t *&w, A a) {
    if constexpr (std::is_pointer_v<A>) { *w++ = a ? va_of(a) : 0; }
    else if constexpr (std::is_same_v<A, float>) { uint32_t u; std::memcpy(&u, &a, 4); *w++ = u; }
    else if constexpr (std::is_same_v<A, double>) { uint64_t u; std::memcpy(&u, &a, 8); *w++ = (uint32_t)u; *w++ = (uint32_t)(u >> 32); }
    else if constexpr (sizeof(A) == 8) { uint64_t u = (uint64_t)a; *w++ = (uint32_t)u; *w++ = (uint32_t)(u >> 32); }
    else if constexpr (std::is_class_v<A>) { static_assert(sizeof(A) == 4, "only 4-byte wrappers (gptr) pass by value"); uint32_t u; std::memcpy(&u, &a, 4); *w++ = u; }
    else { *w++ = (uint32_t)a; }
}

template <typename R> inline R take_result(const result &r) {
    if constexpr (std::is_void_v<R>) return;
    else if constexpr (std::is_pointer_v<R>) return ptr_of<std::remove_pointer_t<R>>(r.eax);
    else if constexpr (std::is_floating_point_v<R>) return static_cast<R>(r.st0);
    else if constexpr (sizeof(R) == 8) return (R)(((uint64_t)r.edx << 32) | r.eax);
    else return (R)r.eax;
}

template <typename R, typename... A>
inline R gcall(uint32_t va, A... args) {
    uint32_t words[2 * sizeof...(A) + 1];
    uint32_t *w = words;
    (put_arg(w, args), ...);
    return take_result<R>(call_va(va, static_cast<int>(w - words), words));
}

// thiscall: `this` travels in ecx, the rest on the stack (callee pops them).
template <typename R, typename T, typename... A>
inline R gcall_this(uint32_t va, T self, A... args) {
    uint32_t words[2 * sizeof...(A) + 1];
    uint32_t *w = words;
    (put_arg(w, args), ...);
    uint32_t this_word[1];
    uint32_t *tw = this_word;
    put_arg(tw, self);
    mach_enter();
    uint32_t saved_ecx = g_ecx;
    g_ecx = this_word[0];
    result r = call_va(va, static_cast<int>(w - words), words);
    g_ecx = saved_ecx;
    mach_leave();
    return take_result<R>(r);
}

// ---- guest_export<F>: native function callable from the guest --------------------------
// cdecl: the shim reads arguments at [esp+4...] and returns with esp unchanged (the guest
// caller pops). `Stdcall` exports pop their own arguments instead.

template <typename A> inline A get_arg(uint32_t &off) {
    auto word = [](uint32_t o) { return MEM32(g_esp + o); };
    if constexpr (std::is_pointer_v<A>) { A v = ptr_of<std::remove_pointer_t<A>>(word(off)); off += 4; return v; }
    else if constexpr (std::is_same_v<A, float>) { uint32_t u = word(off); off += 4; float f; std::memcpy(&f, &u, 4); return f; }
    else if constexpr (std::is_same_v<A, double>) { uint64_t u = word(off) | ((uint64_t)word(off + 4) << 32); off += 8; double d; std::memcpy(&d, &u, 8); return d; }
    else if constexpr (sizeof(A) == 8) { uint64_t u = word(off) | ((uint64_t)word(off + 4) << 32); off += 8; return (A)u; }
    else if constexpr (std::is_class_v<A>) { uint32_t u = word(off); off += 4; A a; std::memcpy(&a, &u, 4); return a; }
    else { A v = (A)word(off); off += 4; return v; }
}

template <typename R> inline void set_result(R v) {
    if constexpr (std::is_pointer_v<R>) g_eax = v ? va_of(v) : 0;
    else if constexpr (std::is_floating_point_v<R>) { g_fp_top = (g_fp_top - 1) & 7; g_st[g_fp_top & 7] = (double)v; }
    else if constexpr (sizeof(R) == 8) { g_eax = (uint32_t)(uint64_t)v; g_edx = (uint32_t)((uint64_t)v >> 32); }
    else g_eax = (uint32_t)v;
}

// Run native `fn` with arguments read from the guest stack (cdecl or stdcall).
template <typename R, typename... A, typename Fn>
inline void run_export(Fn fn, bool stdcall_pop) {
    uint32_t off = 4;                                     // [esp] is the return address
    std::tuple<A...> args{ get_arg<A>(off)... };          // braced: evaluated left to right
    if constexpr (std::is_void_v<R>) std::apply(fn, args);
    else set_result<R>(std::apply(fn, args));
    g_esp += 4u + (stdcall_pop ? (off - 4u) : 0u);        // pop return address (+ args if stdcall)
}

// ---- host function pointers handed to the guest ----------------------------------------
// FFNx stores its own functions into game memory (the driver table, callbacks in game
// structs). A native function address does not fit in 32 bits, so each distinct pointer
// gets a guest-callable thunk from a fixed pool per signature; the pool slot remembers
// the native pointer. 256 slots per signature is far more than FFNx uses for any one.
template <typename Sig> struct host_pool;
template <typename R, typename... A>
struct host_pool<R(A...)> {
    using fp = R (*)(A...);
    static constexpr int N = 256;
    static inline fp fns[N];
    static inline uint32_t vas[N];
    static inline int used;
    template <int I> static void thunk() { run_export<R, A...>(fns[I], false); }
    template <int... I>
    static constexpr std::array<recomp_func_t, N> make(std::integer_sequence<int, I...>) { return { &thunk<I>... }; }
    static uint32_t va_for(fp f) {
        static const std::array<recomp_func_t, N> thunks = make(std::make_integer_sequence<int, N>{});
        for (int i = 0; i < used; i++) if (fns[i] == f) return vas[i];
        if (used == N) fatal("host_pool exhausted for a signature", (uintptr_t)f);
        int i = used++;
        fns[i] = f;
        char *name = static_cast<char *>(std::malloc(40));
        std::snprintf(name, 40, "host:%p", reinterpret_cast<void *>(f));
        win32hle_shim one[2] = { { name, thunks[i] }, { nullptr, nullptr } };
        win32hle_register(one);
        vas[i] = hle_resolve(name);
        return vas[i];
    }
};

// ---- gfn<Sig>: a 4-byte function pointer in game memory --------------------------------
// From a value below 4 GB it is a guest address (a game function, or a cast integer as
// in FFNx's externals tables); from a native function it is a pooled thunk.

enum class cc { cdecl_, stdcall_, thiscall_ };

template <typename Sig, cc C = cc::cdecl_> class gfn_t;
template <typename R, typename... A, cc C>
class gfn_t<R(A...), C> {
    uint32_t va_ = 0;
public:
    using fp = R (*)(A...);
    gfn_t() = default;
    gfn_t(std::nullptr_t) {}
    gfn_t(fp f) { *this = f; }
    static gfn_t from_va(uint32_t va) { gfn_t g; g.va_ = va; return g; }
    gfn_t &operator=(fp f) {
        uintptr_t a = reinterpret_cast<uintptr_t>(f);
        if (!f) va_ = 0;
        else if (a <= 0xFFFFFFFFu) va_ = static_cast<uint32_t>(a);            // guest address
        else if constexpr (C == cc::cdecl_) va_ = host_pool<R(A...)>::va_for(f);
        else fatal("native function stored as a stdcall/thiscall guest pointer", a);
        return *this;
    }
    gfn_t &operator=(std::nullptr_t) { va_ = 0; return *this; }
    uint32_t va() const { return va_; }
    explicit operator bool() const { return va_ != 0; }
    // Comparisons FFNx does against raw addresses / other pointers.
    friend bool operator==(const gfn_t &a, const gfn_t &b) { return a.va_ == b.va_; }
    friend bool operator!=(const gfn_t &a, const gfn_t &b) { return a.va_ != b.va_; }
    R operator()(A... a) const {
        if constexpr (C == cc::thiscall_) return gcall_this<R>(va_, a...);
        else return gcall<R>(va_, a...);
    }
};

// Variadic game functions (printf-style): stored as a guest address only; calls pass
// whatever arguments the call site supplies (cdecl, caller cleans up).
template <typename R, typename... A, cc C>
class gfn_t<R(A..., ...), C> {
    uint32_t va_ = 0;
public:
    using fp = R (*)(A..., ...);
    gfn_t() = default;
    gfn_t(std::nullptr_t) {}
    gfn_t(fp f) { *this = f; }
    gfn_t &operator=(fp f) {
        uintptr_t a = reinterpret_cast<uintptr_t>(f);
        if (a > 0xFFFFFFFFu) fatal("native variadic function stored as a guest pointer", a);
        va_ = static_cast<uint32_t>(a);
        return *this;
    }
    uint32_t va() const { return va_; }
    explicit operator bool() const { return va_ != 0; }
    template <typename... V> R operator()(V... v) const { return gcall<R>(va_, v...); }
};

template <typename Sig> using gfn = gfn_t<Sig, cc::cdecl_>;
template <typename Sig> using gfn_stdcall = gfn_t<Sig, cc::stdcall_>;
template <typename Sig> using gfn_thiscall = gfn_t<Sig, cc::thiscall_>;
static_assert(sizeof(gfn<void()>) == 4, "gfn must stay 4 bytes");

// ---- guest_export<F>: a named native function callable from the guest -------------------
template <auto F, bool Stdcall = false> struct export_of;
template <typename R, typename... A, R (*F)(A...), bool Stdcall>
struct export_of<F, Stdcall> {
    static void thunk() { run_export<R, A...>(F, Stdcall); }
};

// Register F under `name` and return its guest VA (idempotent per name).
template <auto F, bool Stdcall = false>
inline uint32_t guest_export(const char *name) {
    uint32_t va = hle_resolve(name);
    if (va) return va;
    win32hle_shim one[2] = { { name, &export_of<F, Stdcall>::thunk }, { nullptr, nullptr } };
    win32hle_register(one);
    return hle_resolve(name);
}

}  // namespace guest
