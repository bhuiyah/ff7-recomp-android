// patch_recomp.cpp - FFNx's patch API with recompilation semantics (see override/patch.h).
//
// Everything FFNx asks to change in the game's code is recorded here:
//   hooks       replace_function / replace_call / replace_call_function: (site, guest VA of
//               the replacement). The runtime installs these (entry overrides and call-site
//               slots); until it does, they are counted and reported, never silently lost.
//   code writes patch_code_* / memcpy_code / memset_code into .text: applied to memory (so
//               FFNx's own later reads see them) and recorded for the re-lift manifest.
//               Writes outside .text are data and take effect immediately.
// ffnx_recomp_report() prints a summary; ffnx_recomp_write_manifest(path) writes JSON.
//
// get_relative_call / get_absolute_value / check_is_call are FFNx's (GPL-3.0), unchanged.
#include <cstdarg>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <vector>
#include "patch.h"
#include "log.h"

namespace {

constexpr uint32_t TEXT_LO = 0x00401000u, TEXT_HI = 0x007B6000u;   // ff7_en .text

struct hook_rec { const char *what; uint32_t site, target; int installed; };
struct write_rec { const char *what; uint32_t offset, size; };

std::mutex g_lock;
std::vector<hook_rec> g_hooks;
std::vector<write_rec> g_code_writes;
uint32_t g_data_writes;

bool in_text(uint32_t a) { return a >= TEXT_LO && a < TEXT_HI; }

uint32_t record_hook(const char *what, uint32_t site, uint32_t target, int installed) {
    std::lock_guard<std::mutex> lk(g_lock);
    g_hooks.push_back({ what, site, target, installed });
    return static_cast<uint32_t>(g_hooks.size() - 1);
}

// A/B toggle: FF7_FFNX_HOOKS=0 records hooks without installing any.
bool hooks_enabled() {
    static int on = -1;
    if (on < 0) { const char *e = std::getenv("FF7_FFNX_HOOKS"); on = !(e && e[0] == '0'); }
    return on;
}

}  // namespace

extern "C" int ff7_override_install(uint32_t va, uint32_t target);
extern "C" void ff7_override_remove(uint32_t va);
extern "C" int ff7_callsite_install(uint32_t site, uint32_t target, uint32_t orig);
extern "C" void ff7_callsite_remove(uint32_t site);

namespace ffnx_recomp {

void note_code_write(uint32_t offset, uint32_t size, const char *what);
static bool is_callsite_hook(const hook_rec &h) { return std::strcmp(h.what, "replace_function") != 0; }

uint32_t replace_function_va(uint32_t offset, uint32_t host_va, const char *what) {
    int installed = 0;
    if (hooks_enabled()) {
        int rc = ff7_override_install(offset, host_va);
        installed = rc == 0;
        if (rc == -1) ffnx_warning("replace_function: 0x%08X is not a lifted function entry; hook recorded only\n", offset);
        if (rc == -2) ffnx_error("replace_function: override table full at 0x%08X\n", offset);
    }
    return record_hook(what, offset, host_va, installed);
}
// Call-site replacements: every direct call of the lift is RECOMP_CALL_AT(site, f), so a
// hook at a call instruction is a run-time table entry (src/host/ff7_overrides.c).
// replace_call_function over a non-call instruction writes the call (E8 rel32) into the
// image as FFNx does; the re-lift's patched-code variant for that site (from the manifest)
// then reaches the same hook.
static uint32_t call_target_at(uint32_t site) {
    const uint8_t *p = reinterpret_cast<const uint8_t *>(static_cast<uintptr_t>(site));
    if (p[0] != 0xE8) return 0;
    int32_t rel; std::memcpy(&rel, p + 1, 4);
    return site + 5 + static_cast<uint32_t>(rel);
}
static int install_callsite(uint32_t site, uint32_t target, const char *what, uint32_t orig) {
    if (!hooks_enabled()) return 0;
    int rc = ff7_callsite_install(site, target, orig);
    if (rc != 0) ffnx_error("%s: cannot hook call site 0x%08X (%d)\n", what, site, rc);
    return rc == 0;
}
void replace_call_va(uint32_t offset, uint32_t host_va, const char *what) {
    const uint8_t op = *reinterpret_cast<const uint8_t *>(static_cast<uintptr_t>(offset));
    if (op != 0xE8) ffnx_warning("%s: 0x%08X is not a call rel32 (0x%02X); hooked as a call site anyway\n", what, offset, op);
    record_hook(what, offset, host_va, install_callsite(offset, host_va, what, call_target_at(offset)));
}
uint32_t replace_call_function_va(uint32_t offset, uint32_t host_va, const char *what) {
    uint8_t *p = reinterpret_cast<uint8_t *>(static_cast<uintptr_t>(offset));
    const uint32_t orig = call_target_at(offset);   // 0 over a non-call: nothing to pop for
    if (p[0] != 0xE8) {
        uint8_t call[5] = { 0xE8 };
        int32_t rel = static_cast<int32_t>(host_va - offset - 5);
        std::memcpy(call + 1, &rel, 4);
        note_code_write(offset, 5, "replace_call_function");
        std::memcpy(p, call, 5);
    }
    return record_hook(what, offset, host_va, install_callsite(offset, host_va, what, orig));
}

void note_code_write(uint32_t offset, uint32_t size, const char *what) {
    std::lock_guard<std::mutex> lk(g_lock);
    if (in_text(offset)) g_code_writes.push_back({ what, offset, size });
    else g_data_writes++;
}

}  // namespace ffnx_recomp

using ffnx_recomp::note_code_write;

// FFNx undoes/redoes a replacement by the index replace_function returned.
void unreplace_function(uint32_t func) {
    std::lock_guard<std::mutex> lk(g_lock);
    if (func >= g_hooks.size() || !g_hooks[func].installed) return;
    if (ffnx_recomp::is_callsite_hook(g_hooks[func])) ff7_callsite_remove(g_hooks[func].site);
    else ff7_override_remove(g_hooks[func].site);
}
void rereplace_function(uint32_t func) {
    std::lock_guard<std::mutex> lk(g_lock);
    if (func >= g_hooks.size() || !g_hooks[func].installed) return;
    if (ffnx_recomp::is_callsite_hook(g_hooks[func])) ff7_callsite_install(g_hooks[func].site, g_hooks[func].target, 0);
    else ff7_override_install(g_hooks[func].site, g_hooks[func].target);
}
void unreplace_functions() {
    std::lock_guard<std::mutex> lk(g_lock);
    for (const hook_rec &h : g_hooks) {
        if (!h.installed) continue;
        if (ffnx_recomp::is_callsite_hook(h)) ff7_callsite_remove(h.site); else ff7_override_remove(h.site);
    }
}

static uint8_t check_is_call(const char *name, uint32_t base, uint32_t offset, uint16_t instruction) {
    if ((instruction & 0xFF) != 0xE8 && (instruction & 0xFF) != 0xE9 && instruction != 0x15FF) {
        ffnx_warning("%s: Unrecognized call/jmp instruction at 0x%X + 0x%X (0x%X): 0x%X\n", name, base, offset, base + offset, instruction);
    }
    return instruction == 0x15FF ? 2 : 1;
}

uint32_t get_relative_call(uint32_t base, uint32_t offset) {
    uint16_t instruction = *((uint16_t *)(uintptr_t)(base + offset));
    uint8_t size = check_is_call(__func__, base, offset, instruction);
    return base + *((uint32_t *)(uintptr_t)(base + offset + size)) + offset + 4 + size;
}

uint32_t get_absolute_value(uint32_t base, uint32_t offset) {
    return *((uint32_t *)(uintptr_t)(base + offset));
}

#define PATCH_WRITE(T, name) \
    void name(uint32_t offset, T r) { note_code_write(offset, sizeof(r), #name); std::memcpy((void *)(uintptr_t)offset, &r, sizeof(r)); }
PATCH_WRITE(unsigned char, patch_code_byte)
PATCH_WRITE(char, patch_code_char)
PATCH_WRITE(WORD, patch_code_word)
PATCH_WRITE(short, patch_code_short)
PATCH_WRITE(DWORD, patch_code_dword)
PATCH_WRITE(int, patch_code_int)
PATCH_WRITE(uint32_t, patch_code_uint)
PATCH_WRITE(float, patch_code_float)
PATCH_WRITE(double, patch_code_double)
#undef PATCH_WRITE

void memcpy_code(uint32_t offset, const void *data, uint32_t size) {
    note_code_write(offset, size, "memcpy_code");
    std::memcpy((void *)(uintptr_t)offset, data, size);
}

void memset_code(uint32_t offset, uint32_t val, uint32_t size) {
    note_code_write(offset, size, "memset_code");
    std::memset((void *)(uintptr_t)offset, (int)val, size);
}

// From https://stackoverflow.com/a/21636483 (as in FFNx)
void *member_func_to_ptr(char i, ...) {
    va_list v;
    va_start(v, i);
    void *ret = va_arg(v, void *);
    va_end(v);
    return ret;
}

extern "C" void ffnx_recomp_report(void) {
    std::lock_guard<std::mutex> lk(g_lock);
    size_t installed = 0;
    for (const hook_rec &h : g_hooks) installed += h.installed;
    std::fprintf(stderr, "[ffnx-patch] %zu hooks requested, %zu installed as entry overrides "
                         "(and call-site hooks; the rest are mid-function), "
                         "%zu code writes into .text (need a re-lift), %u data writes applied\n",
                 g_hooks.size(), installed, g_code_writes.size(), g_data_writes);
}

extern "C" int ffnx_recomp_write_manifest(const char *path) {
    std::lock_guard<std::mutex> lk(g_lock);
    FILE *f = fopen(path, "w");
    if (!f) return -1;
    std::fprintf(f, "{\n \"hooks\": [\n");
    for (size_t i = 0; i < g_hooks.size(); i++)
        std::fprintf(f, "  {\"kind\": \"%s\", \"site\": \"0x%08X\", \"target\": \"0x%08X\"}%s\n",
                     g_hooks[i].what, g_hooks[i].site, g_hooks[i].target, i + 1 < g_hooks.size() ? "," : "");
    std::fprintf(f, " ],\n \"code_writes\": [\n");
    for (size_t i = 0; i < g_code_writes.size(); i++) {
        const write_rec &w = g_code_writes[i];
        std::fprintf(f, "  {\"kind\": \"%s\", \"offset\": \"0x%08X\", \"bytes\": \"", w.what, w.offset);
        for (uint32_t b = 0; b < w.size; b++) std::fprintf(f, "%02X", ((uint8_t *)(uintptr_t)w.offset)[b]);
        std::fprintf(f, "\"}%s\n", i + 1 < g_code_writes.size() ? "," : "");
    }
    std::fprintf(f, " ],\n \"data_writes\": %u\n}\n", g_data_writes);
    std::fclose(f);
    return 0;
}
