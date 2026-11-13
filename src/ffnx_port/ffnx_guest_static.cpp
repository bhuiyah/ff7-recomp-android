// ffnx_guest_static.cpp - a guest-memory block for FFNx globals the game reads.
//
// FFNx patches the game's code to read some of its own variables (sfx volume table, the
// widescreen viewport, 60 FPS swirl constants, ...): on Windows any address fits in 32
// bits. Here those globals live in a 64 KB block below 2 GB, reserved when libff7.so loads
// (static initialisation, before the guest heap exists). Its usual place is 0x6B000000, just
// above the VirtualAlloc region and below ART's boot image (see src/hle/ff7_hle.h). Some
// devices already have something mapped there (#36: a Retroid Pocket Classic, Android 14);
// then it goes into a free gap /proc/self/maps shows, away from where the guest heap and the
// VirtualAlloc region usually go. The patches take the address as it is, so any place below
// 2 GB works; save states note a moved block (ff7_savestate.c).
// ffnx_guest.h's ffnx_guest_static<T> / ffnx_guest_static_copy hand out its pieces.
#include <sys/mman.h>
#include <cerrno>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <mutex>
#undef fopen   // ffnx_prelude.h's guest-path fopen: these are host paths, read at load time

namespace {
constexpr uintptr_t k_usual = 0x6B000000u;
constexpr size_t k_size = 0x10000u;   // 64 KB
constexpr uintptr_t k_lo = 0x01000000u;   // above the exe image
constexpr uintptr_t k_hi = 0x7F000000u;   // below win32hle's synthetic shim addresses
constexpr uintptr_t k_heap_lo = 0x33000000u, k_heap_hi = 0x6B000000u;   // FF7_ARENA_LO..FF7_VM_HI
uintptr_t g_base;
uintptr_t g_next;
std::mutex g_lock;

bool reserve(uintptr_t at) {
    void *p = mmap(reinterpret_cast<void *>(at), k_size, PROT_READ | PROT_WRITE,
                   MAP_PRIVATE | MAP_ANONYMOUS | MAP_FIXED_NOREPLACE, -1, 0);
    if (p == reinterpret_cast<void *>(at)) return true;
    if (p != MAP_FAILED) munmap(p, k_size);   // a kernel without MAP_FIXED_NOREPLACE: elsewhere
    return false;
}

// Test: FF7_SIMULATE_STATIC_TAKEN=1 in files/env.txt maps something at the usual place first
// (GameActivity sets it before loading this library; env.txt is applied later otherwise).
bool simulate_taken() {
    const char *v = std::getenv("FF7_SIMULATE_STATIC_TAKEN");
    return v && v[0] == '1';
}

// The highest free gap of k_size in [lo, hi) as /proc/self/maps shows it, or 0.
uintptr_t free_gap(uintptr_t lo, uintptr_t hi) {
    FILE *f = std::fopen("/proc/self/maps", "r");
    if (!f) return 0;
    uintptr_t best = 0, at = 0;   // at: the end of the mappings so far
    char line[512];
    auto consider = [&](uintptr_t end) {   // the gap [at, end)
        uintptr_t a = at > lo ? at : lo, b = end < hi ? end : hi;
        a = (a + k_size - 1) & ~(uintptr_t)(k_size - 1);
        b &= ~(uintptr_t)(k_size - 1);
        if (b > a && b - a >= k_size) best = b - k_size;   // maps is sorted: later gaps are higher
    };
    while (std::fgets(line, sizeof line, f)) {
        unsigned long long a, b;
        if (std::sscanf(line, "%llx-%llx", &a, &b) != 2) continue;
        if (a >= hi) break;
        consider((uintptr_t)a);
        if ((uintptr_t)b > at) at = (uintptr_t)b;
    }
    std::fclose(f);
    consider(hi);
    return best;
}
}  // namespace

void *ffnx_guest_static_alloc(size_t n, size_t align) {
    std::lock_guard<std::mutex> lk(g_lock);
    if (!g_base) {
        if (simulate_taken() && reserve(k_usual))
            std::fprintf(stderr, "[ffnx] test: 0x%08lX occupied\n", (unsigned long)k_usual);
        if (reserve(k_usual)) g_base = k_usual;
        // else the highest free place: above the heap's usual place, then below it, then in it
        const uintptr_t ranges[3][2] = { { k_heap_hi, k_hi }, { k_lo, k_heap_lo }, { k_heap_lo, k_heap_hi } };
        for (int r = 0; r < 3 && !g_base; r++)
            for (int tries = 0; tries < 4 && !g_base; tries++) {   // a thread may map it meanwhile
                const uintptr_t at = free_gap(ranges[r][0], ranges[r][1]);
                if (!at) break;
                if (reserve(at)) g_base = at;
            }
        if (!g_base) {
            std::fprintf(stderr, "[ffnx] FATAL: no room below 0x%08lX for the guest static block\n",
                         (unsigned long)k_hi);
            std::abort();
        }
        if (g_base != k_usual)
            std::fprintf(stderr, "[ffnx] 0x%08lX is in use: guest static block at 0x%08lX\n",
                         (unsigned long)k_usual, (unsigned long)g_base);
        g_next = g_base;
    }
    if (align < 4) align = 4;
    uintptr_t at = (g_next + align - 1) & ~(uintptr_t)(align - 1);
    if (at + n > g_base + k_size) {
        std::fprintf(stderr, "[ffnx] FATAL: guest static block full (%zu more bytes)\n", n);
        std::abort();
    }
    g_next = at + n;
    return reinterpret_cast<void *>(at);   // zeroed (fresh anonymous pages)
}

// Where the block is (save states record it); its usual place before anything was allocated.
extern "C" uint32_t ffnx_guest_static_base(void) {
    std::lock_guard<std::mutex> lk(g_lock);
    return (uint32_t)(g_base ? g_base : k_usual);
}
