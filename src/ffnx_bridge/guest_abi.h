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
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>
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
template <typename T> inline T *ptr_of_raw(uint32_t va) { return reinterpret_cast<T *>(static_cast<uintptr_t>(va)); }

// ---- host handles: opaque host objects the game only stores and hands back ----------
// FFNx's open_lgp_file returns a FILE* that the game keeps in its LGP tables and FFNx's
// own LGP functions read back. Such pointers cross as handles in a range with no guest
// memory, [0xF0000000, 0xF0010000), and turn back into the host pointer on the way in.
template <typename T> struct is_host_handle : std::false_type {};
template <> struct is_host_handle<FILE> : std::true_type {};
constexpr uint32_t k_handle_base = 0xF0000000u, k_handle_max = 0x10000u;
inline std::mutex &handle_lock() { static std::mutex m; return m; }
inline std::vector<void *> &handle_table() { static std::vector<void *> t; return t; }
inline uint32_t host_handle_of(void *p) {
    if (!p) return 0;
    std::lock_guard<std::mutex> lk(handle_lock());
    auto &t = handle_table();
    for (size_t i = 0; i < t.size(); i++) if (t[i] == p) return k_handle_base + (uint32_t)i;
    if (t.size() >= k_handle_max) fatal("host handle table full", t.size());
    t.push_back(p);
    return k_handle_base + (uint32_t)(t.size() - 1);
}
inline void *host_handle_get(uint32_t h) {
    if (!h) return nullptr;
    std::lock_guard<std::mutex> lk(handle_lock());
    uint32_t i = h - k_handle_base;
    if (h < k_handle_base || i >= handle_table().size()) fatal("not a host handle", h);
    return handle_table()[i];
}

// Any pointer type to its 32-bit guest form and back (host handles included).
template <typename T> inline uint32_t to_va(T *p) {
    if constexpr (is_host_handle<std::remove_cv_t<T>>::value) return host_handle_of(const_cast<std::remove_cv_t<T> *>(p));
    else return p ? va_of(p) : 0;
}
template <typename T> inline T *ptr_of(uint32_t va) {
    if constexpr (is_host_handle<std::remove_cv_t<T>>::value) return static_cast<T *>(host_handle_get(va));
    else return ptr_of_raw<T>(va);
}

// ---- gptr<T>: a guest pointer member --------------------------------------------------

template <typename T>
class gptr {
    uint32_t va_ = 0;
public:
    gptr() = default;
    gptr(std::nullptr_t) {}
    gptr(T *p) : va_(to_va(p)) {}
    // An address computed as an integer (0, get_absolute_value...): exact-match template so
    // a literal 0 is not ambiguous with the pointer constructor.
    template <typename I, typename = std::enable_if_t<std::is_integral_v<I>>>
    gptr(I va) : va_(static_cast<uint32_t>(va)) {}
    template <typename I, typename = std::enable_if_t<std::is_integral_v<I>>>
    gptr &operator=(I va) { va_ = static_cast<uint32_t>(va); return *this; }
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
    gptr &operator=(T *p) { va_ = to_va(p); return *this; }
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

template <typename Sig> struct host_pool;

// A function signature that native code may expose to the guest: no parameter or return
// may be a pointer to a raw pointer (T **). In game memory such a cell is 4 bytes; native
// code would read or write 8. Use gptr<T> * instead (tools/ffnx_port.py does this for
// FFNx's game-function signatures; replacement functions are fixed in patches/ffnx).
template <typename T> struct is_raw_ptr_ptr : std::false_type {};
template <typename T> struct is_raw_ptr_ptr<T **> : std::true_type {};
template <typename T> struct is_raw_ptr_ptr<T *const *> : std::true_type {};
template <typename T> struct is_raw_ptr_ptr<const T **> : std::true_type {};
template <typename Sig> struct guest_safe_sig;
template <typename R, typename... A> struct guest_safe_sig<R(A...)> {
    static constexpr bool value = !is_raw_ptr_ptr<R>::value && !(is_raw_ptr_ptr<A>::value || ...);
};

// A guest copy of a host string, one per distinct text, kept for the run.
inline uint32_t guest_string(const char *s) {
    static std::mutex lock;
    static std::unordered_map<std::string, uint32_t> copies;
    std::lock_guard<std::mutex> lk(lock);
    auto it = copies.find(s);
    if (it != copies.end()) return it->second;
    size_t n = std::strlen(s) + 1;
    char *g = static_cast<char *>(guest_malloc(n));
    if (!g) fatal("guest_string: guest heap exhausted", n);
    std::memcpy(g, s, n);
    uint32_t va = va_of(g);
    copies.emplace(s, va);
    return va;
}

// Argument -> guest stack words.
template <typename A> inline void put_arg(uint32_t *&w, A a) {
    if constexpr (std::is_pointer_v<A> && std::is_function_v<std::remove_pointer_t<A>>) {
        // A native callback handed to the game: give it a guest-callable thunk.
        uintptr_t p = reinterpret_cast<uintptr_t>(a);
        *w++ = !a ? 0 : p <= 0xFFFFFFFFu ? static_cast<uint32_t>(p)
                                           : host_pool<std::remove_pointer_t<A>>::va_for(a);
    }
    else if constexpr (std::is_same_v<A, const char *>) {
        // Read-only strings may be host literals (FFNx passes __FILE__ to the game's debug
        // allocator): a host one goes over as a guest copy, made once per distinct text.
        uintptr_t p = reinterpret_cast<uintptr_t>(a);
        *w++ = !a ? 0 : p <= 0xFFFFFFFFu ? static_cast<uint32_t>(p) : guest_string(a);
    }
    else if constexpr (std::is_pointer_v<A>) { *w++ = to_va(a); }
    else if constexpr (std::is_same_v<A, float>) { uint32_t u; std::memcpy(&u, &a, 4); *w++ = u; }
    else if constexpr (std::is_same_v<A, double>) { uint64_t u; std::memcpy(&u, &a, 8); *w++ = (uint32_t)u; *w++ = (uint32_t)(u >> 32); }
    else if constexpr (std::is_class_v<A>) {               // by-value struct: its 32-bit image
        uint32_t n = (sizeof(A) + 3u) / 4u; std::memset(w, 0, n * 4u); std::memcpy(w, &a, sizeof(A)); w += n;
    }
    else if constexpr (sizeof(A) == 8) { uint64_t u = (uint64_t)a; *w++ = (uint32_t)u; *w++ = (uint32_t)(u >> 32); }
    else { *w++ = (uint32_t)a; }
}
template <typename A> constexpr size_t arg_words() { return std::is_pointer_v<A> ? 1 : (sizeof(A) + 3u) / 4u; }

template <typename R> inline R take_result(const result &r) {
    if constexpr (std::is_void_v<R>) return;
    else if constexpr (std::is_pointer_v<R>) return ptr_of<std::remove_pointer_t<R>>(r.eax);
    else if constexpr (std::is_floating_point_v<R>) return static_cast<R>(r.st0);
    else if constexpr (std::is_class_v<R>) {
        static_assert(sizeof(R) <= 8, "structs over 8 bytes return through a hidden pointer: not supported");
        uint64_t u = ((uint64_t)r.edx << 32) | r.eax; R v; std::memcpy(&v, &u, sizeof(R)); return v;
    }
    else if constexpr (sizeof(R) == 8) return (R)(((uint64_t)r.edx << 32) | r.eax);
    else return (R)r.eax;
}

template <typename R, typename... A>
inline R gcall(uint32_t va, A... args) {
    uint32_t words[(arg_words<A>() + ... + 0) + 1];
    uint32_t *w = words;
    (put_arg(w, args), ...);
    return take_result<R>(call_va(va, static_cast<int>(w - words), words));
}

// thiscall: `this` travels in ecx, the rest on the stack (callee pops them).
template <typename R, typename T, typename... A>
inline R gcall_this(uint32_t va, T self, A... args) {
    uint32_t words[(arg_words<A>() + ... + 0) + 1];
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

// By-value structs (and gptr/gfn) are copied as their 32-bit image: x86 passes them on the
// stack rounded up to 4 bytes, and MSVC returns 4/8-byte structs in eax / edx:eax.
template <typename A> inline A get_arg(uint32_t &off) {
    auto word = [](uint32_t o) { return MEM32(g_esp + o); };
    if constexpr (std::is_pointer_v<A>) { A v = ptr_of<std::remove_pointer_t<A>>(word(off)); off += 4; return v; }
    else if constexpr (std::is_same_v<A, float>) { uint32_t u = word(off); off += 4; float f; std::memcpy(&f, &u, 4); return f; }
    else if constexpr (std::is_same_v<A, double>) { uint64_t u = word(off) | ((uint64_t)word(off + 4) << 32); off += 8; double d; std::memcpy(&d, &u, 8); return d; }
    else if constexpr (std::is_class_v<A>) {
        A a; std::memcpy(&a, ptr_of<void>(g_esp + off), sizeof(A)); off += (sizeof(A) + 3u) & ~3u; return a;
    }
    else if constexpr (sizeof(A) == 8) { uint64_t u = word(off) | ((uint64_t)word(off + 4) << 32); off += 8; return (A)u; }
    else { A v = (A)word(off); off += 4; return v; }
}

template <typename R> inline void set_result(R v) {
    if constexpr (std::is_pointer_v<R>) g_eax = to_va(v);
    else if constexpr (std::is_floating_point_v<R>) { g_fp_top = (g_fp_top - 1) & 7; g_st[g_fp_top & 7] = (double)v; }
    else if constexpr (std::is_class_v<R>) {
        static_assert(sizeof(R) <= 8, "structs over 8 bytes return through a hidden pointer: not supported");
        uint64_t u = 0; std::memcpy(&u, &v, sizeof(R)); g_eax = (uint32_t)u; g_edx = (uint32_t)(u >> 32);
    }
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
template <typename R, typename... A>
struct host_pool<R(A...)> {
    static_assert(guest_safe_sig<R(A...)>::value,
                  "a native function exposed to the guest takes or returns T **: use gptr<T> * (4-byte guest cells)");
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
    // FFNx computes game addresses as integers (get_relative_call) and stores them here.
    template <typename I, typename = std::enable_if_t<std::is_integral_v<I>>>
    gfn_t(I va) : va_(static_cast<uint32_t>(va)) {}
    template <typename I, typename = std::enable_if_t<std::is_integral_v<I>>>
    gfn_t &operator=(I va) { va_ = static_cast<uint32_t>(va); return *this; }
    uint32_t va() const { return va_; }
    explicit operator bool() const { return va_ != 0; }
    // ...and passes them back as addresses (get_relative_call, replace_function).
    operator uint32_t() const { return va_; }
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
    template <typename I, typename = std::enable_if_t<std::is_integral_v<I>>>
    gfn_t(I va) : va_(static_cast<uint32_t>(va)) {}
    template <typename I, typename = std::enable_if_t<std::is_integral_v<I>>>
    gfn_t &operator=(I va) { va_ = static_cast<uint32_t>(va); return *this; }
    uint32_t va() const { return va_; }
    explicit operator bool() const { return va_ != 0; }
    operator uint32_t() const { return va_; }
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
    static_assert(guest_safe_sig<R(A...)>::value,
                  "a native function exposed to the guest takes or returns T **: use gptr<T> * (4-byte guest cells)");
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
