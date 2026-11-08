// ffnx_guest_static.cpp - a fixed guest-memory block for FFNx globals the game reads.
//
// FFNx patches the game's code to read some of its own variables (sfx volume table, the
// widescreen viewport, 60 FPS swirl constants, ...): on Windows any address fits in 32
// bits. Here those globals live in a block at a fixed guest address, reserved when
// libff7.so loads (static initialisation, before the guest heap exists): just above the
// VirtualAlloc region and well below ART's boot image (see src/hle/ff7_hle.h).
// ffnx_guest.h's ffnx_guest_static<T> / ffnx_guest_static_copy hand out its pieces.
#include <sys/mman.h>
#include <cerrno>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <mutex>

namespace {
constexpr uintptr_t k_base = 0x6B000000u;
constexpr size_t k_size = 0x10000u;   // 64 KB
uintptr_t g_next;
std::mutex g_lock;
}

void *ffnx_guest_static_alloc(size_t n, size_t align) {
    std::lock_guard<std::mutex> lk(g_lock);
    if (!g_next) {
        void *p = mmap(reinterpret_cast<void *>(k_base), k_size, PROT_READ | PROT_WRITE,
                       MAP_PRIVATE | MAP_ANONYMOUS | MAP_FIXED_NOREPLACE, -1, 0);
        if (p != reinterpret_cast<void *>(k_base)) {
            std::fprintf(stderr, "[ffnx] FATAL: cannot reserve the guest static block at 0x%08lX (%s)\n",
                         (unsigned long)k_base, std::strerror(errno));
            std::abort();
        }
        g_next = k_base;
    }
    if (align < 4) align = 4;
    uintptr_t at = (g_next + align - 1) & ~(uintptr_t)(align - 1);
    if (at + n > k_base + k_size) {
        std::fprintf(stderr, "[ffnx] FATAL: guest static block full (%zu more bytes)\n", n);
        std::abort();
    }
    g_next = at + n;
    return reinterpret_cast<void *>(at);   // zeroed (fresh anonymous pages)
}
